package com.yuchen.portfolio.service;

import com.yuchen.portfolio.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存抽象：Redis 优先，Redis 不可用自动降级到本地 ConcurrentHashMap。
 *
 * <p>这里只放<b>丢了能重建</b>的数据（内容缓存、失败计数、token 黑名单）。
 * 真正的持久数据一律在 MySQL，所以 Redis 整体挂掉也不会让站点瘫痪。
 * 实例由 {@link CacheBeans} 按 app.cache-enabled 决定注入 Redis 版还是纯内存版。
 */
public class CacheService {

  private final AppProperties props;
  private final boolean enabled;
  private StringRedisTemplate redis;
  private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();
  private volatile long nextRedisRetryAt = 0L;
  private volatile boolean redisDown = false;

  /** Redis 开启时的构造器：注入模板。 */
  public CacheService(AppProperties props, StringRedisTemplate redis) {
    this(props, redis, true);
  }

  /** {@code app.cache-enabled=false} 时使用：纯本地。 */
  public CacheService(AppProperties props) {
    this(props, null, false);
  }

  private CacheService(AppProperties props, StringRedisTemplate redis, boolean enabled) {
    this.props = props;
    this.redis = redis;
    this.enabled = enabled && redis != null;
    ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "Cyan-cache-sweeper");
      t.setDaemon(true);
      return t;
    });
    ses.scheduleAtFixedRate(this::sweep, 60, 60, TimeUnit.SECONDS);
  }

  public String key(String suffix) {
    return props.getKeyPrefix() + suffix;
  }

  public Optional<String> get(String suffix) {
    String k = key(suffix);
    if (useRedis()) {
      try {
        String v = redis.opsForValue().get(k);
        markUp();
        if (v != null) return Optional.of(v);
      } catch (Exception e) {
        markDown();
      }
    }
    LocalEntry e = local.get(k);
    if (e != null && !e.expired()) return Optional.of(e.value);
    if (e != null) local.remove(k, e);
    return Optional.empty();
  }

  public void set(String suffix, String value) {
    set(suffix, value, Duration.ofSeconds(props.getCacheTtlSeconds()));
  }

  public void set(String suffix, String value, Duration ttl) {
    String k = key(suffix);
    if (useRedis()) {
      try {
        redis.opsForValue().set(k, value, ttl);
        markUp();
        return;
      } catch (Exception e) {
        markDown();
      }
    }
    local.put(k, new LocalEntry(value, System.currentTimeMillis() + ttl.toMillis()));
  }

  public void del(String suffix) {
    String k = key(suffix);
    local.remove(k);
    if (useRedis()) {
      try {
        redis.delete(k);
        markUp();
      } catch (Exception e) {
        markDown();
      }
    }
  }

  /** 自增计数，首次写入时设置过期时间。 */
  public long incr(String suffix, Duration ttl) {
    String k = key(suffix);
    if (useRedis()) {
      try {
        Long v = redis.opsForValue().increment(k);
        markUp();
        if (v != null) {
          if (v == 1L) redis.expire(k, ttl);
          return v;
        }
      } catch (Exception e) {
        markDown();
      }
    }
    LocalEntry e = local.compute(k, (ignored, old) -> {
      long now = System.currentTimeMillis();
      if (old == null || old.expired()) return new LocalEntry("1", now + ttl.toMillis());
      return new LocalEntry(String.valueOf(Long.parseLong(old.value) + 1), now + ttl.toMillis());
    });
    return Long.parseLong(e.value);
  }

  /** 剩余秒数；0 表示不存在或已过期。 */
  public long ttlSeconds(String suffix) {
    String k = key(suffix);
    if (useRedis()) {
      try {
        Long t = redis.getExpire(k);
        markUp();
        if (t != null && t > 0) return t;
      } catch (Exception e) {
        markDown();
      }
    }
    LocalEntry e = local.get(k);
    return e == null ? 0 : Math.max(0, (e.expiresAt - System.currentTimeMillis()) / 1000);
  }

  public boolean isRedisDown() { return redisDown; }

  private boolean useRedis() {
    if (!enabled || redis == null) return false;
    if (redisDown && System.currentTimeMillis() < nextRedisRetryAt) return false;
    return true;
  }

  private void markDown() {
    redisDown = true;
    nextRedisRetryAt = System.currentTimeMillis() + 30_000L;
  }

  /**
   * Redis 操作成功 → 复位降级标志。
   *
   * <p>以前只有 markDown 没有复位路径，于是这个标志是<b>单向的</b>：
   * 只要历史上抖动过一次（Redis 重启、网络闪断、一次超时），后台就会
   * 永远显示「Redis 已降级」，哪怕它早就恢复得很好。
   *
   * <p>后果比看上去严重 —— 状态栏天天喊狼来了，真出故障那天就没人信了。
   * 所以每次操作成功都要把标志拉回来。
   *
   * <p>注意只写一次 volatile：先判断再赋值，避免让无故障时的每个缓存操作
   * 都多做一次 volatile 写。
   */
  private void markUp() {
    if (redisDown) {
      redisDown = false;
      nextRedisRetryAt = 0L;
    }
  }

  private void sweep() {
    long now = System.currentTimeMillis();
    local.entrySet().removeIf(e -> e.getValue().expiresAt < now);
  }

  private record LocalEntry(String value, long expiresAt) {
    boolean expired() { return System.currentTimeMillis() > expiresAt; }
  }
}
