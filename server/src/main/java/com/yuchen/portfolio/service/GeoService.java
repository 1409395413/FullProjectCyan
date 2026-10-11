package com.yuchen.portfolio.service;

import com.yuchen.portfolio.config.AppProperties;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.lionsoul.ip2region.DbConfig;
import org.lionsoul.ip2region.DbSearcher;
import org.lionsoul.ip2region.DataBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * IP → 地域解析（离线库 ip2region）。
 * ---------------------------------------------------------------------------
 * 设计取舍：
 *   · <b>只取到「省」</b>。站点只需要回答"访客大概来自哪些地方"，
 *     精确到市/区对个人作品集没有意义，粒度越细越接近可识别个人。
 *   · <b>IP 依旧只存哈希</b>。地域在会话入库那一刻用原始 IP 查一次，
 *     查完立刻丢弃原始 IP，落库的只有一个省名字符串。
 *   · <b>失败一律降级</b>。库文件缺失、IP 是内网、解析抛异常，都返回「未知」，
 *     绝不能因为一个附加信息拖垮会话记录的主流程。
 *
 * <p>库文件 8.7MB，memorySearch 会整体载入内存（常驻约 10MB），
 * 对 2G 机器可接受；同时用一层小缓存挡掉同一 IP 的重复查询。
 */
@Service
public class GeoService {

  private static final Logger log = LoggerFactory.getLogger(GeoService.class);

  /** 解析不出来时的兜底文案，全站统一 */
  public static final String UNKNOWN = "未知";
  /** 局域网 / 本机访问 */
  public static final String LAN = "内网";

  private final boolean enabled;
  private final String dbPath;
  private final AtomicReference<DbSearcher> searcher = new AtomicReference<>();
  private volatile boolean initTried = false;

  /** 小缓存：IP → 省名。访客量小，几十条足够，避免重复走库 */
  private final Map<String, String> cache = new LinkedHashMap<>(64, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
      return size() > 512;
    }
  };

  public GeoService(AppProperties props) {
    AppProperties.Geo g = props.getGeo();
    this.enabled = g == null || g.isEnabled();
    this.dbPath = g == null ? "" : g.getDbPath();
  }

  /**
   * 解析 IP 所属地域。
   *
   * @return {@code [country, region]}，如 {@code ["中国", "广东"]}；
   *         海外返回 {@code ["美国", "华盛顿"]}；解析不出返回 {@code ["未知", "未知"]}。
   */
  public String[] lookup(String ip) {
    if (!enabled) return new String[] {UNKNOWN, UNKNOWN};
    if (isBlank(ip)) return new String[] {UNKNOWN, UNKNOWN};
    if (isLan(ip)) return new String[] {LAN, LAN};

    String cached = cache.get(ip);
    if (cached != null) return splitCached(cached);

    DbSearcher s = ensureSearcher();
    if (s == null) return new String[] {UNKNOWN, UNKNOWN};

    String raw;
    try {
      DataBlock b = s.memorySearch(ip);
      raw = b == null ? null : b.getRegion();
    } catch (Exception e) {
      log.debug("ip2region 查询失败，IP 已脱敏：{}", e.getMessage());
      return new String[] {UNKNOWN, UNKNOWN};
    }
    String[] out = parse(raw);
    synchronized (cache) {
      cache.put(ip, out[0] + "|" + out[1]);
    }
    return out;
  }

  /** 只要省名（大多数调用方只需要这个） */
  public String regionOf(String ip) {
    return lookup(ip)[1];
  }

  /* ------------------------------ 解析 ------------------------------ */

  /**
   * ip2region 的返回格式：{@code 国家|区域|省|市|运营商}，缺省位填 0。
   * 例：{@code 中国|0|浙江省|杭州市|阿里云}、{@code 美国|0|华盛顿|0|谷歌}、
   * {@code 0|0|0|内网IP|内网IP}。
   */
  private static String[] parse(String raw) {
    if (isBlank(raw)) return new String[] {UNKNOWN, UNKNOWN};
    String[] p = raw.split("\\|");
    String country = pick(p, 0);
    String province = pick(p, 2);

    if ("0".equals(country) || country.isEmpty()) country = UNKNOWN;
    if ("0".equals(province) || province.isEmpty()) {
      // 国内 IP 常见「只有运营商、没有省市」，至少国家还是能确定的
      return new String[] {country, UNKNOWN};
    }
    if ("内网IP".equals(province)) return new String[] {LAN, LAN};
    return new String[] {country, shorten(province)};
  }

  /** 「浙江省」→「浙江」、「上海市」→「上海」、「内蒙古自治区」→「内蒙古」 */
  private static String shorten(String province) {
    String s = province;
    for (String suffix : new String[] {"特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区",
        "自治区", "省", "市"}) {
      if (s.length() > suffix.length() + 1 && s.endsWith(suffix)) {
        return s.substring(0, s.length() - suffix.length());
      }
    }
    return s;
  }

  private static String pick(String[] p, int i) {
    return p.length > i && p[i] != null ? p[i].trim() : "";
  }

  private static String[] splitCached(String v) {
    int i = v.indexOf('|');
    return i < 0 ? new String[] {UNKNOWN, UNKNOWN}
        : new String[] {v.substring(0, i), v.substring(i + 1)};
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** 内网 / 回环 / 保留地址，没必要查库 */
  private static boolean isLan(String ip) {
    if (ip.startsWith("10.") || ip.startsWith("192.168.")
        || ip.startsWith("127.") || ip.equals("::1") || ip.equals("localhost")) return true;
    if (ip.startsWith("172.")) {
      String[] p = ip.split("\\.");
      if (p.length == 4) {
        try {
          int b = Integer.parseInt(p[1]);
          if (b >= 16 && b <= 31) return true;
        } catch (NumberFormatException ignored) { /* 不是纯数字就不是内网段 */ }
      }
    }
    return false;
  }

  /* ------------------------------ 生命周期 ------------------------------ */

  /** 懒加载：库不存在时只尝试一次，之后直接走降级，避免每条日志刷屏 */
  private DbSearcher ensureSearcher() {
    DbSearcher s = searcher.get();
    if (s != null || initTried) return s;
    synchronized (this) {
      if (searcher.get() != null || initTried) return searcher.get();
      initTried = true;
      if (isBlank(dbPath) || !new File(dbPath).isFile()) {
        log.warn("地域库不存在，地域统计降级为「未知」。dbPath={}", dbPath);
        return null;
      }
      try {
        DbSearcher created = new DbSearcher(new DbConfig(), dbPath);
        searcher.set(created);
        log.info("地域库已加载：{}", dbPath);
        return created;
      } catch (Exception e) {
        log.warn("地域库加载失败，地域统计降级为「未知」：{}", e.getMessage());
        return null;
      }
    }
  }

  @PreDestroy
  void shutdown() {
    DbSearcher s = searcher.getAndSet(null);
    if (s != null) {
      try {
        s.getClass().getMethod("close").invoke(s);
      } catch (Exception ignored) { /* 1.7.2 未必有 close，忽略 */ }
    }
  }
}
