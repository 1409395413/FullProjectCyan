package com.yuchen.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuchen.portfolio.util.HtmlSanitizer;
import com.yuchen.portfolio.web.ApiException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 站点内容的合法性校验与净化。
 *
 * <p>后端刻意 <b>不解析</b> 模块语义，只做结构安全校验 ——
 * 这样新增任意模块类型都不需要改后端。但要保证：
 * <ul>
 *   <li>整体是合法 JSON 且体积可控（防炸弹）</li>
 *   <li>modules 是数组、每个元素有合法的 id / type，且 id 不重复（渲染层依赖 id 作锚点）</li>
 *   <li>富文本字段过 HTML 白名单（防 XSS）</li>
 *   <li>src / href / poster 只能是安全协议（防 javascript: 伪协议）</li>
 * </ul>
 */
@Component
public class ContentValidator {

  private static final long MAX_PAYLOAD_BYTES = 4L * 1024 * 1024;   // 4MB
  private static final int MAX_MODULES = 300;
  private static final int MAX_NODES = 30_000;
  private static final int MAX_STRING_LEN = 20_000;
  private static final Pattern MODULE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  /** 允许内联 HTML 的字段，其余字符串一律当作纯文本。 */
  private static final Set<String> RICH_TEXT_KEYS = Set.of(
      "title", "lead", "body", "eyebrow", "desc", "description",
      "formNote", "submitLabel", "footerNote", "copyright", "note", "caption", "cap", "cap2");

  /** 视为 URL 的字段。 */
  private static final Set<String> URL_KEYS = Set.of("src", "href", "poster", "link", "url");

  private int nodeCount = 0;

  public JsonNode validateAndClean(JsonNode root) {
    nodeCount = 0;
    if (root == null || !root.isObject()) {
      throw ApiException.badRequest("内容必须是 JSON 对象");
    }
    if (estimateSize(root) > MAX_PAYLOAD_BYTES) {
      throw ApiException.badRequest("内容超过 4MB 上限");
    }

    ObjectNode out = walk((ObjectNode) deepCopy(root));

    JsonNode site = out.get("site");
    if (site == null || !site.isObject()) {
      throw ApiException.badRequest("缺少 site 配置对象");
    }

    JsonNode modules = out.get("modules");
    if (modules == null || !modules.isArray()) {
      throw ApiException.badRequest("modules 必须是数组");
    }
    if (modules.size() > MAX_MODULES) {
      throw ApiException.badRequest("模块数量超过 " + MAX_MODULES + " 上限");
    }

    Set<String> seenIds = new HashSet<>();
    for (JsonNode m : modules) {
      if (!m.isObject()) throw ApiException.badRequest("模块必须是对象");
      JsonNode id = m.get("id");
      JsonNode type = m.get("type");
      if (id == null || !MODULE_ID.matcher(id.asText()).matches()) {
        throw ApiException.badRequest("模块 id 非法：只能包含字母、数字、下划线、连字符");
      }
      if (!seenIds.add(id.asText())) {
        throw ApiException.badRequest("模块 id 重复：" + id.asText());
      }
      if (type == null || type.asText().isBlank() || type.asText().length() > 32) {
        throw ApiException.badRequest("模块缺少 type 或 type 过长");
      }
    }
    return out;
  }

  /** 递归复制并处理字符串节点，同时统计节点数防止恶意膨胀。 */
  private JsonNode deepCopy(JsonNode n) {
    if (++nodeCount > MAX_NODES) {
      throw ApiException.badRequest("内容节点过多，请精简后再保存");
    }
    return n.deepCopy();
  }

  private ObjectNode walk(ObjectNode node) {
    Iterator<Map.Entry<String, JsonNode>> it = node.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String key = e.getKey();
      JsonNode v = e.getValue();

      if (v.isObject()) {
        walk((ObjectNode) v);
      } else if (v.isArray()) {
        walkArray(key, (com.fasterxml.jackson.databind.node.ArrayNode) v);
      } else if (v.isTextual()) {
        String raw = v.asText();
        if (raw.length() > MAX_STRING_LEN) {
          node.put(key, raw.substring(0, MAX_STRING_LEN));
          continue;
        }
        if (RICH_TEXT_KEYS.contains(key)) {
          node.put(key, HtmlSanitizer.clean(raw));
        } else if (URL_KEYS.contains(key)) {
          node.put(key, safeUrl(raw));
        }
      }
    }
    normalizeCaseMedia(node);
    return node;
  }

  /**
   * 案例的双素材结构收敛 —— 幽灵素材的根在这。
   * ---------------------------------------------------------------------------
   * 老版本一个案例只能挂一条素材，字段是 {@code media}（对象）；后来改成 {@code mediaList}（数组）。
   * 后台载入时有个 migrate：如果 mediaList 不存在就把 media 复制成它的第一条。
   * 可一旦 mediaList 里已经有位置了（哪怕全是空占位），这次复制就不会发生 ——
   * 于是 {@code media} 和 {@code mediaList} 并存，各自存着各自的图。
   *
   * <p>前台 caseMedia() 的兜底是「mediaList 取不到东西就退回渲染 media」，于是出现：
   * 在案例里新加了素材位但还没填图，前台显示的却是 {@code media} 里那张旧图；
   * 而后台的表单只认 mediaList，那条旧图看不见、删不掉 —— 成了幽灵素材。
   *
   * <p>规则（幂等，怎么重复保存都收敛到同一份）：
   *   1. mediaList 里已经有真素材 → media 是被淘汰的旧数据，直接删；
   *   2. mediaList 全是空占位 → 把 media 搬成 mediaList 的第一条，
   *      后台立刻就能看见它，能改能删，前台也不再需要靠兜底逻辑显示；
   *   3. 两边都没东西 → 删掉空的 media，别留个空对象占地方。
   */
  private void normalizeCaseMedia(ObjectNode node) {
    JsonNode media = node.get("media");
    JsonNode list = node.get("mediaList");
    if (media == null || list == null) return;
    // 只有案例item才是「media 是对象 + mediaList 是数组」这个组合，
    // 普通模块的 media 本来就是数组，不会被误伤。
    if (!media.isObject() || !list.isArray()) return;

    com.fasterxml.jackson.databind.node.ArrayNode arr =
        (com.fasterxml.jackson.databind.node.ArrayNode) list;
    boolean hasReal = false;
    for (JsonNode n : arr) {
      if (n.isObject() && !n.path("src").asText("").isBlank()) {
        hasReal = true;
        break;
      }
    }

    String legacySrc = media.path("src").asText("");
    if (hasReal || legacySrc.isBlank()) {
      node.remove("media");
      return;
    }

    // 旧素材顶到第一条，其余非空的排在后面，空占位丢弃
    com.fasterxml.jackson.databind.node.ArrayNode merged =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    merged.add(media.deepCopy());
    for (JsonNode n : arr) {
      if (n.isObject() && !n.path("src").asText("").isBlank()) merged.add(n.deepCopy());
    }
    node.set("mediaList", merged);
    node.remove("media");
  }

  private void walkArray(String fieldKey, com.fasterxml.jackson.databind.node.ArrayNode arr) {
    // skills / chips / projectTypes 这类纯字符串数组走一遍净化，防止 <script> 混入
    for (int i = 0; i < arr.size(); i++) {
      JsonNode v = arr.get(i);
      if (v.isObject()) {
        walk((ObjectNode) v);
      } else if (v.isArray()) {
        walkArray(fieldKey, (com.fasterxml.jackson.databind.node.ArrayNode) v);
      } else if (v.isTextual()) {
        String raw = v.asText();
        if (raw.length() > MAX_STRING_LEN) {
          arr.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(
              raw.substring(0, MAX_STRING_LEN)));
        } else if (RICH_TEXT_KEYS.contains(fieldKey)) {
          arr.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(
              HtmlSanitizer.clean(raw)));
        } else if (URL_KEYS.contains(fieldKey)) {
          arr.set(i, com.fasterxml.jackson.databind.node.TextNode.valueOf(safeUrl(raw)));
        }
      }
    }
  }

  /** 只允许 http(s) / mailto / tel / 站内相对路径 / data:image。 */
  private String safeUrl(String raw) {
    String s = raw == null ? "" : raw.trim();
    if (s.isEmpty()) return "";
    String lower = s.toLowerCase();
    if (lower.startsWith("https://") || lower.startsWith("http://")
        || lower.startsWith("mailto:") || lower.startsWith("tel:")
        || lower.startsWith("data:image/")
        || lower.startsWith("/") || lower.startsWith("#") || lower.startsWith("./")) {
      return s;
    }
    return "";
  }

  private long estimateSize(JsonNode n) {
    return n.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
}
