package com.yuchen.portfolio.service;

import com.yuchen.portfolio.entity.MediaAsset;
import com.yuchen.portfolio.entity.MediaAssetTag;
import com.yuchen.portfolio.entity.MediaTag;
import com.yuchen.portfolio.repository.MediaAssetRepository;
import com.yuchen.portfolio.repository.MediaAssetTagRepository;
import com.yuchen.portfolio.repository.MediaTagRepository;
import com.yuchen.portfolio.web.ApiException;
import com.yuchen.portfolio.web.dto.MediaTagView;
import com.yuchen.portfolio.web.dto.MediaTagsResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 素材标签：给素材打标记，然后按标记把素材筛出来。
 *
 * <p>三个动作，对应需求里的三句话：
 *   1. 自定义标签名 —— 标签不需要预先登记，打标签时写到什么名字就创建什么名字；
 *   2. 给素材打标签 —— 一次可以给多个素材（{@link #apply}），支持「追加」和「覆盖」两种语义；
 *   3. 按标签筛选 —— 筛选本身在前端做（列表本来就只有 100 条），后端只提供
 *      {@link #list} 这份「标签 → 素材数」清单，用来渲染筛选按钮上的计数。
 *
 * <p>为什么不把筛选也放到 SQL：素材列表上限 100 条,
 * 为了筛一次标签再往 MySQL 加一个带 join 的查询流程和分页语义，收益远小于复杂度。
 */
@Service
public class MediaTagService {

  /** 单个标签名长度上限。够写下「2026春季-短视频封面」这类名字，又不至于被当成正文用 */
  private static final int NAME_MAX = 32;
  /** 一次给多少素材打标签的上限 */
  private static final int ASSET_MAX = 500;

  private final MediaAssetRepository assetRepo;
  private final MediaTagRepository tagRepo;
  private final MediaAssetTagRepository linkRepo;

  public MediaTagService(MediaAssetRepository assetRepo, MediaTagRepository tagRepo,
      MediaAssetTagRepository linkRepo) {
    this.assetRepo = assetRepo;
    this.tagRepo = tagRepo;
    this.linkRepo = linkRepo;
  }

  /**
   * 全量标签清单 + 每个标签下挂了几条素材。
   *
   * <p>排序：素材多的在前，一样多按名字。这样最常用的标签永远排在筛选栏最左边，
   * 不用在一长串标签里翻找。
   */
  @Transactional(readOnly = true)
  public List<MediaTagView> list() {
    List<MediaTag> tags = tagRepo.findAllByOrderByNameAsc();

    // 一次 group by 拿全部计数，避免「每个标签 count 一次」的 N+1
    Map<Long, Long> counts = new HashMap<>();
    for (Object[] row : linkRepo.countByTagIds()) {
      Object k = row[0];
      Object v = row[1];
      if (k == null) continue;
      long n = v instanceof Number num ? num.longValue() : 0L;
      counts.put(((Number) k).longValue(), n);
    }

    // 按 (计数降序, 名字升序) 排：最常用的标签永远在筛选栏最左边
    List<MediaTag> ordered = new ArrayList<>(tags);
    ordered.sort((a, b) -> {
      int c = Long.compare(counts.getOrDefault(b.getId(), 0L), counts.getOrDefault(a.getId(), 0L));
      return c != 0 ? c : String.valueOf(a.getName()).compareTo(String.valueOf(b.getName()));
    });
    List<MediaTagView> out = new ArrayList<>();
    for (MediaTag t : ordered) {
      out.add(new MediaTagView(t.getId(), t.getName(), counts.getOrDefault(t.getId(), 0L)));
    }
    return out;
  }

  /** 某批素材各自的标签：{@code 素材id -> 标签名列表}（按名字排序） */
  @Transactional(readOnly = true)
  public Map<Long, List<String>> tagsOf(Collection<Long> assetIds) {
    Map<Long, List<String>> out = new HashMap<>();
    if (assetIds == null || assetIds.isEmpty()) return out;
    List<MediaAssetTag> links = linkRepo.findByAssetIdIn(assetIds);
    if (links.isEmpty()) return out;

    Map<Long, String> names = new HashMap<>();
    for (MediaTag t : tagRepo.findAll()) names.put(t.getId(), t.getName());

    for (MediaAssetTag l : links) {
      String n = names.get(l.getTagId());
      if (n == null) continue; // 标签行被删了而关联还在，跳过而不是塞 null 给前端
      out.computeIfAbsent(l.getAssetId(), (k) -> new ArrayList<>()).add(n);
    }
    out.values().forEach(java.util.Collections::sort);
    return out;
  }

  /**
   * 给一批素材打标签。
   *
   * @param replace true = 用传入的标签<b>整体替换</b>这些素材原有的标签；
   *                false = 只往里<b>追加</b>，原有的保留。
   *                差别很重要：勾 20 张图设成「直播封面」，你多半不希望它们原本的
   *                「客户A」标签悄悄消失；而在单条素材的编辑框里取消勾选一个标签，
   *                语义又必须是「去掉它」。所以两种都要，由调用方选。
   */
  @Transactional
  public MediaTagsResult apply(List<String> paths, List<String> rawTags, boolean replace,
      Long userId, String ip) {
    List<String> tags = normalizeTags(rawTags);

    List<String> pending = new ArrayList<>();
    Set<String> seenPath = new LinkedHashSet<>();
    for (String p : paths == null ? List.<String>of() : paths) {
      if (p != null && !p.isBlank() && seenPath.add(p)) pending.add(p);
    }
    if (pending.isEmpty()) {
      throw ApiException.badRequest("没有指定素材");
    }
    if (pending.size() > ASSET_MAX) {
      throw ApiException.badRequest("一次最多给 " + ASSET_MAX + " 个素材打标签，当前 " + pending.size() + " 个");
    }

    List<MediaAsset> assets = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String p : pending) {
      java.util.Optional<MediaAsset> a = assetRepo.findByPath(p);
      if (a.isPresent()) assets.add(a.get());
      else missing.add(p);
    }
    if (assets.isEmpty()) {
      throw ApiException.notFound("这些素材都不存在了，可能刚刚被删掉，刷新一下再试");
    }

    List<MediaTag> normalized = tags.isEmpty() ? List.of() : ensureTags(tags);

    List<Long> assetIds = assets.stream().map(MediaAsset::getId).toList();
    if (replace) {
      linkRepo.deleteByAssetIdIn(assetIds);
    }

    if (!normalized.isEmpty()) {
      // 覆盖模式下刚删干净所以肯定没有重复；追加模式要先知道已经存在哪些组合
      Set<String> existing = new HashSet<>();
      if (!replace) {
        for (MediaAssetTag l : linkRepo.findByAssetIdIn(assetIds)) {
          existing.add(l.getAssetId() + ":" + l.getTagId());
        }
      }
      List<MediaAssetTag> rows = new ArrayList<>();
      for (MediaAsset a : assets) {
        for (MediaTag t : normalized) {
          String key = a.getId() + ":" + t.getId();
          if (existing.contains(key)) continue;
          existing.add(key);
          MediaAssetTag row = new MediaAssetTag();
          row.setAssetId(a.getId());
          row.setTagId(t.getId());
          rows.add(row);
        }
      }
      if (!rows.isEmpty()) linkRepo.saveAll(rows);
    }

    return new MediaTagsResult(assets.size(), missing, tags);
  }

  /**
   * 标签改名。
   *
   * <p>新名字已经有同名的标签时，语义是<b>合并</b>：把旧标签下的素材挪过去，旧标签删掉。
   * 这样「直播」「Live」两个叫法收敛成一个时不用先手动全部取消再重新打一遍。
   */
  @Transactional
  public MediaTagsResult rename(String from, String to, Long userId, String ip) {
    String old = clean(from);
    String next = clean(to);
    if (old.isEmpty()) throw ApiException.badRequest("原标签名为空");
    if (next.isEmpty()) throw ApiException.badRequest("新标签名为空");
    if (old.equals(next)) return new MediaTagsResult(0, List.of(), List.of(next));

    MediaTag src = tagRepo.findByName(old).orElseThrow(() -> ApiException.notFound("标签不存在：" + old));
    java.util.Optional<MediaTag> dup = tagRepo.findByName(next);

    long moved = 0;
    for (MediaAssetTag l : linkRepo.findByTagId(src.getId())) moved++;

    if (dup.isPresent()) {
      MediaTag target = dup.get();
      Set<Long> already = new HashSet<>();
      for (MediaAssetTag l : linkRepo.findByTagId(target.getId())) already.add(l.getAssetId());

      List<MediaAssetTag> rows = new ArrayList<>();
      for (MediaAssetTag l : linkRepo.findByTagId(src.getId())) {
        if (already.contains(l.getAssetId())) continue; // 两边都有的，去重后留目标那条
        already.add(l.getAssetId());
        MediaAssetTag row = new MediaAssetTag();
        row.setAssetId(l.getAssetId());
        row.setTagId(target.getId());
        rows.add(row);
      }
      if (!rows.isEmpty()) linkRepo.saveAll(rows);
      linkRepo.deleteByTagId(src.getId());
      tagRepo.delete(src);
    } else {
      src.setName(next);
      tagRepo.save(src);
    }

    return new MediaTagsResult((int) moved, List.of(), List.of(next));
  }

  /** 删标签：只是把关联行和标签行清掉，<b>素材本身一个都不动</b> */
  @Transactional
  public int delete(String name, Long userId, String ip) {
    String n = clean(name);
    MediaTag t = tagRepo.findByName(n).orElseThrow(() -> ApiException.notFound("标签不存在：" + n));
    int removed = linkRepo.findByTagId(t.getId()).size();
    linkRepo.deleteByTagId(t.getId());
    tagRepo.delete(t);
    return removed;
  }

  /** 素材被删时同步清掉它的关联行，避免留下指向不存在素材的孤儿数据 */
  @Transactional
  public void dropForAssets(Collection<Long> assetIds) {
    if (assetIds == null || assetIds.isEmpty()) return;
    linkRepo.deleteByAssetIdIn(assetIds);
  }

  /** 清理没人用的空标签（比如改名合并后留下的旧名、打错的错字） */
  @Transactional
  public int pruneEmpty() {
    List<MediaTag> all = tagRepo.findAll();
    Map<Long, Long> counts = new HashMap<>();
    for (Object[] row : linkRepo.countByTagIds()) {
      if (row[0] == null) continue;
      counts.put(((Number) row[0]).longValue(), row[1] instanceof Number n ? n.longValue() : 0L);
    }
    int removed = 0;
    for (MediaTag t : all) {
      if (counts.getOrDefault(t.getId(), 0L) > 0) continue;
      tagRepo.delete(t);
      removed++;
    }
    return removed;
  }

  /* ------------------------------ 内部工具 ------------------------------ */

  /** 去空白、去 '#', 去重、限长。同一个名字在不同人口里可能写成 "#直播" 或 " 直播 "，这里统一掉 */
  private List<String> normalizeTags(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    Set<String> seen = new LinkedHashSet<>();
    for (String s : raw) {
      String n = clean(s);
      if (n.isEmpty()) continue;
      if (n.length() > NAME_MAX) {
        throw ApiException.badRequest("标签名最长 " + NAME_MAX + " 个字：「" + n + "」超了");
      }
      if (seen.add(n)) out.add(n);
    }
    return out;
  }

  private String clean(String s) {
    if (s == null) return "";
    String n = s.trim();
    while (n.startsWith("#")) n = n.substring(1).trim();
    return n;
  }

  /**
   * 名字存在就直接复用，不存在才新建。
   *
   * <p>先查后建中间有可能被并发插队导致唯一索引冲突，
   * 这时不用慌 —— 反过来再查一次拿到那条即可，属于正常的乐观路径。
   */
  private List<MediaTag> ensureTags(List<String> names) {
    List<MediaTag> out = new ArrayList<>();
    Map<String, MediaTag> found = new HashMap<>();
    for (MediaTag t : tagRepo.findByNameIn(names)) found.put(t.getName(), t);

    List<MediaTag> created = new ArrayList<>();
    for (String n : names) {
      MediaTag existing = found.get(n);
      if (existing != null) {
        out.add(existing);
        continue;
      }
      try {
        MediaTag t = new MediaTag();
        t.setName(n);
        created.add(tagRepo.saveAndFlush(t));
      } catch (Exception conflict) {
        // 并发下同名已在另一条事务里落库，重新读一次
        MediaTag again = tagRepo.findByName(n).orElse(null);
        if (again == null) {
          throw new IllegalStateException("标签创建冲突且查不到已有记录：" + n, conflict);
        }
        created.add(again);
      }
    }
    // saveAll 之后这批实体才有稳定 id
    out.addAll(created);
    return out;
  }
}
