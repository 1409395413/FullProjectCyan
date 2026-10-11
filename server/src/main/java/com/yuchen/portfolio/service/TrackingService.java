package com.yuchen.portfolio.service;

import com.yuchen.portfolio.entity.VisitEvent;
import com.yuchen.portfolio.entity.VisitSession;
import com.yuchen.portfolio.repository.InquiryRepository;
import com.yuchen.portfolio.repository.VisitEventRepository;
import com.yuchen.portfolio.repository.VisitSessionRepository;
import com.yuchen.portfolio.web.ApiException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 访问统计：会话、停留时长、作品播放、地域、设备与来源。
 *
 * <p>设计取舍：只存哈希后的 IP，不存昵称/账号，也不做跨天追踪。
 * 目的是回答"今天几个人来、从哪来、待多久、有没有播放作品"，而不是建立用户画像。
 * 地域只到省级，且是入库那一刻用原始 IP 查完就丢，IP 本身依旧只落哈希。
 */
@Service
public class TrackingService {

  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  private static final long MAX_BEAT_MS = 5 * 60 * 1000L; // 单次心跳最多累加 5 分钟，防止客户端造假
  /** 聚合窗口内最多取多少条会话做 UA/来源拆解，防止整表进内存 */
  private static final int AGG_LIMIT = 3000;

  private final VisitSessionRepository sessions;
  private final VisitEventRepository events;
  private final InquiryRepository inquiries;
  private final GeoService geo;

  public TrackingService(VisitSessionRepository sessions, VisitEventRepository events,
      InquiryRepository inquiries, GeoService geo) {
    this.sessions = sessions;
    this.events = events;
    this.inquiries = inquiries;
    this.geo = geo;
  }

  public String today() {
    return LocalDate.now(ZONE).toString();
  }

  /** 开一次会话。返回主键，前端存 sessionStorage 后用于心跳与事件上报。 */
  @Transactional
  public long startSession(String ip, String ua, String referrer, String path) {
    String day = today();
    String ipHash = sha256(ip == null ? "" : ip);
    String visitorKey = sha256(ipHash + "|" + (ua == null ? "" : ua) + "|" + day);

    // 地域：趁原始 IP 还在，查一次离线库，只留省名；IP 本身不落库
    String[] g = geo == null ? new String[] {"未知", "未知"} : geo.lookup(ip);

    VisitSession s = new VisitSession();
    s.setVisitorKey(visitorKey);
    s.setIpHash(ipHash);
    s.setUa(truncate(ua, 255));
    s.setReferrer(truncate(referrer, 255));
    s.setEntryPath(truncate(path, 255));
    s.setDay(day);
    s.setCountry(g[0]);
    s.setRegion(g[1]);
    Instant now = Instant.now();
    s.setStartedAt(now);
    s.setLastSeenAt(now);
    s.setDurationMs(0L);
    s.setPlayCount(0);
    return sessions.save(s).getId();
  }

  /** 心跳：累加停留时长。ms 是"自上次心跳以来"的毫秒数。 */
  @Transactional
  public void beat(long sessionId, long ms) {
    long add = Math.max(0, Math.min(ms, MAX_BEAT_MS));
    Optional<VisitSession> opt = sessions.findById(sessionId);
    if (opt.isEmpty()) return;
    VisitSession s = opt.get();
    s.setDurationMs(Math.max(0, (s.getDurationMs() == null ? 0 : s.getDurationMs())) + add);
    s.setLastSeenAt(Instant.now());
    sessions.save(s);
  }

  /** 记录一个动作；play 会同时累加会话的播放次数。 */
  @Transactional
  public void record(long sessionId, String type, String target) {
    String t = (type == null || type.isBlank()) ? "click" : type.trim();
    if (t.length() > 24) t = t.substring(0, 24);

    VisitEvent e = new VisitEvent();
    e.setSessionId(sessionId);
    e.setType(t);
    e.setTarget(truncate(target, 255));
    e.setDay(today());
    events.save(e);

    if ("play".equals(t)) {
      Optional<VisitSession> opt = sessions.findById(sessionId);
      if (opt.isPresent()) {
        VisitSession s = opt.get();
        s.setPlayCount((s.getPlayCount() == null ? 0 : s.getPlayCount()) + 1);
        s.setLastSeenAt(Instant.now());
        sessions.save(s);
      }
    }
  }

  public long requireSession(Long id) {
    if (id == null) throw ApiException.badRequest("缺少 sessionId");
    return id;
  }

  /* ------------------------------ 统计 ------------------------------ */

  /** 后台「监控」板块：今日概览 + 近 N 天趋势 + 窗口内分布。 */
  public Map<String, Object> stats(int days) {
    int n = Math.max(1, Math.min(days, 30));
    String today = today();
    String yesterday = LocalDate.now(ZONE).minusDays(1).toString();

    long visitors = sessions.countVisitorsByDay(today);
    long sessionCount = sessions.countByDay(today);
    long durationSum = sessions.sumDurationByDay(today);
    long plays = sessions.sumPlayByDay(today);
    long playedSessions = sessions.countPlayedSessionsByDay(today);

    long yVisitors = sessions.countVisitorsByDay(yesterday);
    long ySessionCount = sessions.countByDay(yesterday);
    long yDurationSum = sessions.sumDurationByDay(yesterday);
    long yPlays = sessions.sumPlayByDay(yesterday);
    long yPlayedSessions = sessions.countPlayedSessionsByDay(yesterday);

    Map<String, Object> t = new LinkedHashMap<>();
    t.put("day", today);
    t.put("visitors", visitors);
    t.put("sessions", sessionCount);
    t.put("plays", plays);
    t.put("playedSessions", playedSessions);
    t.put("avgDurationMs", sessionCount == 0 ? 0L : durationSum / sessionCount);
    t.put("playRate", sessionCount == 0 ? 0.0 : Math.round(playedSessions * 1000.0 / sessionCount) / 10.0);
    // 每张卡的「较昨日 ↑↓ x%」：昨日为 0 时无法算百分比，给 null 让前端只显示「昨日 0」
    t.put("delta", Map.of(
        "visitors", delta(visitors, yVisitors),
        "sessions", delta(sessionCount, ySessionCount),
        "plays", delta(plays, yPlays),
        "avgDurationMs", delta(sessionCount == 0 ? 0L : durationSum / sessionCount,
            ySessionCount == 0 ? 0L : yDurationSum / ySessionCount),
        "playRate", delta(sessionCount == 0 ? 0.0 : playedSessions * 100.0 / sessionCount,
            ySessionCount == 0 ? 0.0 : yPlayedSessions * 100.0 / ySessionCount)));

    // 近 N 天趋势（按天倒序，前端再正序画）
    List<Object[]> rows = sessions.dailyAggregate(PageRequest.of(0, n));
    List<Map<String, Object>> series = new ArrayList<>();
    for (int i = rows.size() - 1; i >= 0; i--) {
      Object[] r = rows.get(i);
      long cnt = toLong(r[1]);
      long sum = toLong(r[2]);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("day", String.valueOf(r[0]));
      m.put("sessions", cnt);
      m.put("plays", toLong(r[3]));
      m.put("avgDurationMs", cnt == 0 ? 0L : sum / cnt);
      series.add(m);
    }

    // 被点播最多的作品
    List<Map<String, Object>> top = new ArrayList<>();
    for (Object[] r : events.topPlayTargets(PageRequest.of(0, 10))) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("target", r[0] == null ? "未命名作品" : String.valueOf(r[0]));
      m.put("count", toLong(r[1]));
      top.add(m);
    }

    /* ---- 窗口内聚合：地域 / 设备 / 来源 / 时段 ---- */
    Instant from = LocalDate.now(ZONE).minusDays(n - 1L).atStartOfDay(ZONE).toInstant();
    List<VisitSession> window = sessions.since(from, PageRequest.of(0, AGG_LIMIT));

    Map<String, Long> regionCount = new TreeMap<>();
    Map<String, Long> deviceCount = new TreeMap<>();
    Map<String, Long> browserCount = new TreeMap<>();
    Map<String, Long> sourceCount = new TreeMap<>();
    long[][] heat = new long[7][24];
    long windowSessions = 0;
    long windowPlayed = 0;

    for (VisitSession s : window) {
      windowSessions++;
      if (s.getPlayCount() != null && s.getPlayCount() > 0) windowPlayed++;

      bump(regionCount, normRegion(s.getRegion()));
      bump(deviceCount, deviceOf(s.getUa()));
      bump(browserCount, browserOf(s.getUa()));
      bump(sourceCount, sourceOf(s.getReferrer()));

      if (s.getStartedAt() != null) {
        ZonedDateTime z = s.getStartedAt().atZone(ZONE);
        int d = z.getDayOfWeek().getValue() - 1;  // 周一=0 … 周日=6
        heat[d][z.getHour()]++;
      }
    }

    // 时段热力：168 格铺平，周一 0 点起。前端按 7×24 还原
    List<Long> heatFlat = new ArrayList<>(168);
    for (int d = 0; d < 7; d++) {
      for (int h = 0; h < 24; h++) heatFlat.add(heat[d][h]);
    }

    /**
     * 转化漏斗：窗口内 访问会话 → 播放过作品 → 提交需求。
     * 统一用「会话」口径 —— 之前"来访"按去重访客、"点开"按会话，
     * 会出现「点开作品 > 来访」的倒挂（同一访客来两次只算 1，播放却算 2）。
     */
    Map<String, Object> funnel = new LinkedHashMap<>();
    funnel.put("visits", windowSessions);
    funnel.put("players", windowPlayed);
    funnel.put("inquirers", inquiries.countByCreatedAtAfter(from));

    Map<String, Object> out = new HashMap<>();
    out.put("today", t);
    out.put("series", series);
    out.put("topPlays", top);
    out.put("recent", recentList());
    out.put("region", toRanking(regionCount, 10));
    out.put("devices", toRanking(deviceCount, 0));
    out.put("browsers", toRanking(browserCount, 5));
    out.put("sources", toRanking(sourceCount, 5));
    out.put("heatmap", heatFlat);
    out.put("funnel", funnel);
    out.put("windowDays", n);
    out.put("windowSessions", windowSessions);
    return out;
  }

  /** 最近 20 次访问明细（追加地域列） */
  private List<Map<String, Object>> recentList() {
    List<Map<String, Object>> recent = new ArrayList<>();
    for (VisitSession s : sessions.findTop50ByOrderByStartedAtDesc()) {
      if (recent.size() >= 20) break;
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", s.getId());
      m.put("startedAt", s.getStartedAt() == null ? null : s.getStartedAt().toString());
      m.put("durationMs", s.getDurationMs());
      m.put("playCount", s.getPlayCount());
      m.put("entryPath", s.getEntryPath());
      m.put("referrer", blankToNull(s.getReferrer()));
      m.put("ua", shortUa(s.getUa()));
      m.put("region", normRegion(s.getRegion()));
      m.put("device", deviceOf(s.getUa()));
      recent.add(m);
    }
    return recent;
  }

  /* ------------------------------ utils ------------------------------ */

  private static void bump(Map<String, Long> m, String k) {
    m.merge(k, 1L, Long::sum);
  }

  /** 计数 → 排行榜（降序），limit=0 表示不限 */
  private static List<Map<String, Object>> toRanking(Map<String, Long> m, int limit) {
    List<Map.Entry<String, Long>> list = new ArrayList<>(m.entrySet());
    list.sort(Comparator.<Map.Entry<String, Long>, Long>comparing(Map.Entry::getValue)
        .reversed().thenComparing(Map.Entry::getKey));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<String, Long> e : list) {
      if (limit > 0 && out.size() >= limit) break;
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("name", e.getKey());
      o.put("count", e.getValue());
      out.add(o);
    }
    return out;
  }

  /** 较昨日变化百分比；昨日为 0 时返回 null（前端显示「新增」而不是 ∞%） */
  private static Object delta(long now, long before) {
    if (before == 0) return now == 0 ? 0.0 : null;
    return Math.round((now - before) * 1000.0 / before) / 10.0;
  }

  private static Object delta(double now, double before) {
    if (before == 0) return now == 0 ? 0.0 : null;
    return Math.round((now - before) * 1000.0 / before) / 10.0;
  }

  private static String normRegion(String r) {
    return (r == null || r.isBlank()) ? "未知" : r.trim();
  }

  /** 手机 / 平板 / 桌面 —— 后台「设备占比」用 */
  private static String deviceOf(String ua) {
    if (ua == null || ua.isBlank()) return "未知";
    String u = ua.toLowerCase(Locale.ROOT);
    boolean tablet = u.contains("ipad") || u.contains("tablet")
        || (u.contains("android") && !u.contains("mobile"));
    if (tablet) return "平板";
    boolean mobile = u.contains("iphone") || u.contains("android") || u.contains("mobile")
        || u.contains("windows phone");
    return mobile ? "手机" : "桌面";
  }

  /** 浏览器识别：微信内置浏览器单列，国内站它占比往往不低 */
  private static String browserOf(String ua) {
    if (ua == null || ua.isBlank()) return "未知";
    String u = ua.toLowerCase(Locale.ROOT);
    if (u.contains("micromessenger")) return "微信";
    if (u.contains("qq/") || u.contains("mqqbrowser")) return "QQ 浏览器";
    if (u.contains("edg/")) return "Edge";
    if (u.contains("chrome/") && !u.contains("chromium")) return "Chrome";
    if (u.contains("firefox/")) return "Firefox";
    if (u.contains("safari/") && !u.contains("chrome")) return "Safari";
    return "其他";
  }

  /** referrer → 来源域名；为空算直接访问 */
  private static String sourceOf(String ref) {
    if (ref == null || ref.isBlank()) return "直接访问";
    try {
      URI uri = URI.create(ref.trim());
      String host = uri.getHost();
      if (host == null) return "直接访问";
      String h = host.toLowerCase(Locale.ROOT);
      if (h.startsWith("www.")) h = h.substring(4);
      if (h.contains("baidu")) return "百度";
      if (h.contains("google")) return "Google";
      if (h.contains("bing")) return "Bing";
      if (h.contains("douyin")) return "抖音";
      if (h.contains("weixin") || h.contains("wechat")) return "微信";
      if (h.contains("xiaohongshu") || h.contains("xhslink")) return "小红书";
      if (h.contains("bilibili")) return "B 站";
      return h;
    } catch (IllegalArgumentException e) {
      return "直接访问";
    }
  }

  private static long toLong(Object o) {
    if (o == null) return 0L;
    if (o instanceof Number n) return n.longValue();
    return Long.parseLong(String.valueOf(o));
  }

  /** 把冗长的 UA 缩成"浏览器 + 系统"，后台看一眼就够 */
  private static String shortUa(String ua) {
    if (ua == null || ua.isBlank()) return "未知";
    String u = ua.toLowerCase(Locale.ROOT);
    String os = u.contains("iphone") ? "iPhone"
        : u.contains("ipad") ? "iPad"
        : u.contains("android") ? "Android"
        : u.contains("mac os x") || u.contains("macintosh") ? "Mac"
        : u.contains("windows") ? "Windows"
        : u.contains("linux") ? "Linux" : "其他";
    String br = browserOf(ua);
    return os + " · " + br;
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  private static String truncate(String s, int max) {
    if (s == null) return null;
    String t = s.trim();
    return t.length() > max ? t.substring(0, max) : t;
  }

  private static String sha256(String raw) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < d.length; i++) {
        if (i == 8) break; // 16 个十六进制字符足够区分，不必存全
        sb.append(String.format("%02x", d[i]));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      return String.valueOf(Math.abs(raw.hashCode()));
    }
  }
}
