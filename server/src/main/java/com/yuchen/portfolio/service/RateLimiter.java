package com.yuchen.portfolio.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * 滑动窗口限流，纯内存实现（单实例够用，多实例请配合 Nginx limit_req）。
 *
 * <p>之所以不用 Redis 计数：限流失败不应该导致整个接口不可用，本地计数即使被打散也 still 有兜底。
 *
 * <p>实现要点：每个桶存一串「命中时间戳」，判定时先把窗口外的旧时间戳清掉再数。
 * 早期版本用一个固定起点 + 计数器，窗口过期后没有复位，一旦打满就永久拒绝，
 * 这里改成真正的滑动窗口 —— 打满之后，等最早那次滚出窗口就自动恢复。
 *
 * <p>另外支持「超限冷却」：触发上限后直接把桶锁住一段时间（如工作对接的 10 分钟），
 * 冷却期内不管怎么刷都拒绝，冷却结束后重新计数。
 */
@Service
public class RateLimiter {

  private static final long DEFAULT_WINDOW_MS = TimeUnit.MINUTES.toMillis(1);

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RateLimiter() {
    ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "rate-sweeper");
      t.setDaemon(true);
      return t;
    });
    ses.scheduleAtFixedRate(this::sweep, 120, 120, TimeUnit.SECONDS);
  }

  /** 返回 true 表示放行。limitPerMinute = 每分钟最多允许的次数。 */
  public boolean allow(String bucket, int limitPerMinute) {
    return allow(bucket, limitPerMinute, DEFAULT_WINDOW_MS, 0L);
  }

  /**
   * 带冷却期的限流。
   *
   * @param windowMs   统计窗口（毫秒）
   * @param cooldownMs 触发上限后的锁定时长（毫秒）；0 表示不锁定，只拒绝当前这一次
   */
  public boolean allow(String bucket, int limitPerMinute, long windowMs, long cooldownMs) {
    if (limitPerMinute <= 0) return true;
    long now = System.currentTimeMillis();

    Bucket b = buckets.computeIfAbsent(bucket, k -> new Bucket());

    // 冷却期内一律拒绝
    if (b.blockedUntil > now) return false;

    long cutoff = now - windowMs;
    while (true) {
      Long head = b.hits.peek();
      if (head == null || head >= cutoff) break;
      b.hits.poll();
    }

    if (b.hits.size() >= limitPerMinute) {
      if (cooldownMs > 0) {
        b.blockedUntil = now + cooldownMs;
        b.hits.clear();       // 冷却结束后从零开始，避免刚解锁又被判超限
      }
      return false;
    }
    b.hits.add(now);
    return true;
  }

  /** 还剩多少秒解除冷却；不在冷却中返回 0。 */
  public long cooldownRemainingSeconds(String bucket) {
    Bucket b = buckets.get(bucket);
    if (b == null) return 0;
    long left = b.blockedUntil - System.currentTimeMillis();
    return left > 0 ? (left + 999) / 1000 : 0;
  }

  public int remainingQuota(String bucket, int limitPerMinute) {
    if (limitPerMinute <= 0) return limitPerMinute;
    Bucket b = buckets.get(bucket);
    if (b == null) return limitPerMinute;
    long cutoff = System.currentTimeMillis() - DEFAULT_WINDOW_MS;
    long live = b.hits.stream().filter(t -> t >= cutoff).count();
    return Math.max(0, limitPerMinute - (int) live);
  }

  /** 清掉空桶，避免长期运行后 map 无限增长 */
  private void sweep() {
    long now = System.currentTimeMillis();
    buckets.entrySet().removeIf(e -> e.getValue().hits.isEmpty() && e.getValue().blockedUntil <= now);
  }

  private static final class Bucket {
    final ConcurrentLinkedQueue<Long> hits = new ConcurrentLinkedQueue<>();
    /** 冷却截止时间戳（epoch millis），0 = 未冷却 */
    volatile long blockedUntil = 0;
  }
}
