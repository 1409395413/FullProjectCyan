package com.yuchen.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuchen.portfolio.entity.ContentRevision;
import com.yuchen.portfolio.entity.SiteContent;
import com.yuchen.portfolio.repository.ContentRevisionRepository;
import com.yuchen.portfolio.repository.SiteContentRepository;
import com.yuchen.portfolio.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点内容读写。
 *
 * <p>payload 整体作为 JSON 字符串存取；MySQL 负责持久， Redis 只做短期缓存，
 * 缓存失效或未命中时回源 MySQL。前端所见的每个模块都来自这里，
 * 因此在后台改完即时生效，<b>不需要重新构建或重新部署</b>。
 */
@Service
public class ContentService {

  private static final Long CONTENT_ID = 1L;
  private static final String CACHE_KEY = "content:main";

  /** 历史快照最多保留的条数（超出就删最老的）。 */
  private static final int REVISION_KEEP = 10;

  /** 历史快照总占用上限（字节）：条数达标但体积超标时继续删最老的。 */
  private static final long REVISION_MAX_BYTES = 50L * 1024 * 1024;

  private final SiteContentRepository repo;
  private final ContentRevisionRepository revisions;
  private final CacheService cache;
  private final AuditService audit;
  private final ContentValidator validator;
  private final ObjectMapper mapper;

  public ContentService(SiteContentRepository repo, ContentRevisionRepository revisions,
      CacheService cache, AuditService audit, ContentValidator validator, ObjectMapper mapper) {
    this.repo = repo;
    this.revisions = revisions;
    this.cache = cache;
    this.audit = audit;
    this.validator = validator;
    this.mapper = mapper;
  }

  /** 前台读取：命中缓存直接返回，避免每次首页都查库。 */
  public String getRaw() {
    Optional<String> cached = cache.get(CACHE_KEY);
    if (cached.isPresent()) return cached.get();

    String json = repo.findById(CONTENT_ID).map(SiteContent::getPayload).orElseGet(this::seedDefault);
    cache.set(CACHE_KEY, json);
    return json;
  }

  public int currentVersion() {
    return repo.findById(CONTENT_ID).map(SiteContent::getVersion).orElse(1);
  }

  /** 后台保存：先存快照，再覆盖，最后清缓存。 */
  @Transactional
  public int save(String rawJson, Long userId, String ip) {
    JsonNode parsed;
    try {
      parsed = mapper.readTree(rawJson);
    } catch (Exception e) {
      throw ApiException.badRequest("JSON 格式错误");
    }

    JsonNode cleaned = validator.validateAndClean(parsed);
    String payload;
    try {
      payload = mapper.writeValueAsString(cleaned);
    } catch (Exception e) {
      throw ApiException.badRequest("内容序列化失败");
    }

    SiteContent entity = repo.findById(CONTENT_ID).orElseGet(SiteContent::new);
    if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
      // 内容没变就不产生新快照：连点保存、只改了一个字又改回来，都不该堆积副本
      String latest = revisions.findLatestPayload();
      if (latest == null || !latest.equals(entity.getPayload())) {
        ContentRevision rev = new ContentRevision();
        rev.setPayload(entity.getPayload());
        rev.setCreatedBy(userId);
        revisions.save(rev);
        pruneRevisions();
      }
    }

    entity.setId(CONTENT_ID);
    entity.setPayload(payload);
    entity.setVersion((entity.getVersion() == null ? 0 : entity.getVersion()) + 1);
    entity.setUpdatedBy(userId);
    repo.save(entity);

    cache.del(CACHE_KEY);
    audit.record(userId, "save_content", ip,
        Map.of("version", entity.getVersion(), "bytes", payload.length()));
    return entity.getVersion();
  }

  /**
   * 素材被删除后的连带清理：把站点内容里所有指向这些 URL 的素材条目摘掉。
   * ---------------------------------------------------------------------------
   * 素材和内容是两套数据：素材在 media_asset 表 + 磁盘上，内容是一整块 payload JSON。
   * 以前删素材只删前者，结果就变成「素材库里没了，前台还挂着一张裂图」，
   * 而且因为是死链，连「占位块至少还能看」都做不到。
   *
   * <p>这里做三件事：
   *   1. 按 URL 全树遍历 payload，命中就移除（保留结构，其余素材原地不动）；
   *   2. 清完先留快照，方便回滚 —— 删素材是可撤销的；
   *   3. 版本号 +1 并清缓存，前台下一次刷新就是干净的。
   *
   * @return 摘掉的引用条数；0 表示这份素材本来就没人用
   */
  @Transactional
  public int purgeMediaReferences(java.util.Collection<String> urls, Long userId, String ip) {
    java.util.Set<String> targets = new java.util.HashSet<>();
    for (String u : urls) {
      if (u != null && !u.isBlank()) targets.add(u);
    }
    if (targets.isEmpty()) return 0;

    Optional<SiteContent> opt = repo.findById(CONTENT_ID);
    if (opt.isEmpty()) return 0;
    SiteContent entity = opt.get();
    String raw = entity.getPayload();
    if (raw == null || raw.isBlank()) return 0;

    JsonNode root;
    try {
      root = mapper.readTree(raw);
    } catch (Exception e) {
      return 0; // 内容本身解析不了就别动它，等人工处理
    }

    int removed = stripMediaRefs(root, targets);
    if (removed == 0) return 0;

    JsonNode cleaned;
    String payload;
    try {
      cleaned = validator.validateAndClean(root);
      payload = mapper.writeValueAsString(cleaned);
    } catch (Exception e) {
      throw ApiException.badRequest("内容重新校验失败，引用清理已中止");
    }

    if (entity.getPayload() != null && !entity.getPayload().isBlank()) {
      String latest = revisions.findLatestPayload();
      if (latest == null || !latest.equals(entity.getPayload())) {
        ContentRevision rev = new ContentRevision();
        rev.setPayload(entity.getPayload());
        rev.setCreatedBy(userId);
        revisions.save(rev);
        pruneRevisions();
      }
    }

    entity.setPayload(payload);
    entity.setVersion((entity.getVersion() == null ? 0 : entity.getVersion()) + 1);
    entity.setUpdatedBy(userId);
    repo.save(entity);

    cache.del(CACHE_KEY);
    audit.record(userId, "purge_media_refs", ip, Map.of("removed", removed));
    return removed;
  }

  /**
   * 递归摘除过期引用。
   *
   * <p>两种形态都要照顾到：
   *   · 数组里的素材对象（{@code media[] / mediaList[] / cases[].mediaList[]}）—— 整条移除；
   *   · 零散挂在对象上的 {@code src} / {@code poster} 字符串（如某些模块的单封面）—— 置空而不是删字段，
   *     避免字段消失惹出渲染报错，那比对结构本身更难排查。
   */
  private int stripMediaRefs(JsonNode node, java.util.Set<String> targets) {
    if (node == null || node.isNull()) return 0;

    if (node.isArray()) {
      com.fasterxml.jackson.databind.node.ArrayNode arr =
          (com.fasterxml.jackson.databind.node.ArrayNode) node;
      int removed = 0;
      for (int i = arr.size() - 1; i >= 0; i--) {
        JsonNode el = arr.get(i);
        String src = el != null && el.isObject() ? el.path("src").asText("") : "";
        if (!src.isEmpty() && targets.contains(src)) {
          arr.remove(i);
          removed++;
        } else {
          removed += stripMediaRefs(el, targets);
        }
      }
      return removed;
    }

    if (node.isObject()) {
      com.fasterxml.jackson.databind.node.ObjectNode obj =
          (com.fasterxml.jackson.databind.node.ObjectNode) node;
      int removed = 0;

      // 先把该置空的字符串挑出来，遍历过程中改字段会破坏 fieldNames 迭代器
      java.util.List<String> hit = new java.util.ArrayList<>();
      java.util.List<JsonNode> children = new java.util.ArrayList<>();
      for (String f : copyOfFieldNames(obj)) {
        JsonNode v = obj.get(f);
        if (("src".equals(f) || "poster".equals(f)) && v != null && v.isTextual()
            && targets.contains(v.asText())) {
          hit.add(f);
        } else if (v != null && (v.isObject() || v.isArray())) {
          children.add(v);
        }
      }
      for (String f : hit) {
        obj.put(f, "");
        removed++;
      }
      for (JsonNode child : children) {
        removed += stripMediaRefs(child, targets);
      }
      return removed;
    }
    return 0;
  }

  /**
   * 字段名快照。
   *
   * <p>JsonNode 的 fieldNames() 是活在对象上的迭代器 —— 循环里边遍历边改字段，
   * 轻则漏字段，重则 ConcurrentModificationException。这里先抄成一份普通 List 再遍历。
   */
  private static java.util.List<String> copyOfFieldNames(
      com.fasterxml.jackson.databind.node.ObjectNode obj) {
    java.util.List<String> names = new java.util.ArrayList<>();
    java.util.Iterator<String> it = obj.fieldNames();
    while (it.hasNext()) names.add(it.next());
    return names;
  }

  /** 回滚到某份历史快照。 */
  @Transactional
  public int rollback(Long revisionId, Long userId, String ip) {
    ContentRevision rev = revisions.findById(revisionId)
        .orElseThrow(() -> ApiException.notFound("快照不存在"));
    SiteContent entity = repo.findById(CONTENT_ID).orElseGet(SiteContent::new);

    ContentRevision backup = new ContentRevision();
    backup.setPayload(entity.getPayload());
    backup.setCreatedBy(userId);
    revisions.save(backup);
    pruneRevisions();   // 回滚产生的备份同样占用配额

    entity.setId(CONTENT_ID);
    entity.setPayload(rev.getPayload());
    entity.setVersion((entity.getVersion() == null ? 0 : entity.getVersion()) + 1);
    entity.setUpdatedBy(userId);
    repo.save(entity);

    cache.del(CACHE_KEY);
    audit.record(userId, "rollback_content", ip, Map.of("revision", revisionId));
    return entity.getVersion();
  }

  /**
   * 历史快照裁剪：只保留最近 REVISION_KEEP 条，且总占用不超过 REVISION_MAX_BYTES。
   *
   * <p>payload 是整份内容 JSON，会随着模块和素材变多而变大。不裁剪的话，
   * 每点一次保存就多一份全量拷贝，表会无限增长。
   *
   * <p>先按条数一次性裁到 N 条，再按体积逐条删最老的（防止以后单条 payload 变得很大，
   * 10 条也撑爆）。循环有上限，异常情况下不会转死。
   */
  private void pruneRevisions() {
    try {
      revisions.keepNewest(REVISION_KEEP);
      for (int i = 0; i < 500; i++) {
        List<Object[]> agg = revisions.countAndBytes();
        if (agg.isEmpty()) return;
        Object[] row = agg.get(0);
        long count = toLong(row[0]);
        long bytes = toLong(row[1]);
        if (count <= REVISION_KEEP && bytes <= REVISION_MAX_BYTES) return;
        if (revisions.deleteOldest() <= 0) return;
      }
    } catch (Exception e) {
      // 裁剪失败只影响空间回收，不能连带让保存失败 —— 记日志后继续
      System.err.println("[content] 快照裁剪跳过: " + e.getMessage());
    }
  }

  private static long toLong(Object v) {
    if (v == null) return 0L;
    if (v instanceof Number n) return n.longValue();
    try {
      return Long.parseLong(String.valueOf(v));
    } catch (Exception e) {
      return 0L;
    }
  }

  /** 历史快照摘要，后台用于回滚。 */
  public java.util.List<RevisionItem> listRevisions() {
    return revisions.findTop20ByOrderByCreatedAtDesc().stream()
        .map(r -> new RevisionItem(
            r.getId(),
            r.getCreatedAt(),
            r.getPayload() == null ? 0 : r.getPayload().length(),
            countModules(r.getPayload())))
        .toList();
  }

  private int countModules(String payload) {
    if (payload == null || payload.isBlank()) return 0;
    try {
      com.fasterxml.jackson.databind.JsonNode n = mapper.readTree(payload);
      com.fasterxml.jackson.databind.JsonNode m = n.get("modules");
      return m == null || !m.isArray() ? 0 : m.size();
    } catch (Exception e) {
      return 0;
    }
  }

  /** 首次运行：把随包发布的默认内容写进数据库。 */
  @Transactional
  public String seedDefault() {
    String json = readDefaultFromClasspath();
    SiteContent entity = new SiteContent();
    entity.setId(CONTENT_ID);
    entity.setPayload(json);
    entity.setVersion(1);
    entity.setUpdatedAt(Instant.now());
    repo.save(entity);
    cache.set(CACHE_KEY, json);
    return json;
  }

  private String readDefaultFromClasspath() {
    try (InputStream in = new ClassPathResource("default-content.json").getInputStream()) {
      String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonNode node = mapper.readTree(raw);
      return mapper.writeValueAsString(node);
    } catch (Exception e) {
      return "{\"version\":1,\"site\":{\"brandName\":\"Studio\",\"brandMark\":\"S\",\"footerCols\":[]},\"modules\":[]}";
    }
  }

  /** 暴露给内部用的简化别名 */
  private ApiException bad(String msg) { return ApiException.badRequest(msg); }
}
