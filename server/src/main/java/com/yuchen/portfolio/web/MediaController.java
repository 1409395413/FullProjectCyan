package com.yuchen.portfolio.web;

import com.yuchen.portfolio.entity.MediaAsset;
import com.yuchen.portfolio.service.AuthService;
import com.yuchen.portfolio.service.MediaService;
import com.yuchen.portfolio.service.MediaTagService;
import com.yuchen.portfolio.util.IpUtil;
import com.yuchen.portfolio.web.ApiException;
import com.yuchen.portfolio.web.dto.MediaBatchDeleteResult;
import com.yuchen.portfolio.web.dto.MediaBatchUploadItem;
import com.yuchen.portfolio.web.dto.MediaDeleteResult;
import com.yuchen.portfolio.web.dto.MediaTagView;
import com.yuchen.portfolio.web.dto.MediaTagsResult;
import com.yuchen.portfolio.web.dto.MediaUploadResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 素材管理：上传需要登录，删除需要登录，读取由 Nginx 直出不需要登录。 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

  private final MediaService media;
  private final MediaTagService tag;
  private final AuthService auth;

  public MediaController(MediaService media, MediaTagService tag, AuthService auth) {
    this.media = media;
    this.tag = tag;
    this.auth = auth;
  }

  @PostMapping
  public ResponseEntity<MediaUploadResult> upload(@RequestParam("file") MultipartFile file,
      HttpServletRequest request) {
    return ResponseEntity.ok(media.upload(file, auth.currentUserId(), IpUtil.clientIp(request)));
  }

  /**
   * 视频首帧封面：{@code GET /api/media/poster?path=2026/10/xxx.mp4}
   *
   * <p>这个端点<b>不需要登录</b> —— 前台访客要能看到竖屏视频的封面，
   * 否则手机端就是一片灰。素材本体 {@code /media/**} 本来也是公开的，
   * 这里只是给出同一份素材的低清缩略图，不额外扩大暴露面。
   */
  @GetMapping("/poster")
  public ResponseEntity<byte[]> poster(@RequestParam("path") String path) {
    return media.posterOf(path)
        .map(p -> ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .cacheControl(org.springframework.http.CacheControl.maxAge(30, java.util.concurrent.TimeUnit.DAYS))
            .body(p.bytes()))
        .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
  }

  @GetMapping
  public ResponseEntity<List<MediaItemView>> list() {
    List<MediaItemView> items = media.listWithTags().stream()
        .map((row) -> MediaItemView.from(row.asset(), row.tags()))
        .toList();
    return ResponseEntity.ok(items);
  }

  /* ------------------------------ 标签 ------------------------------ */

  /**
   * 全部标签 + 各自名下素材数。
   *
   * <p>前端拿它渲染筛选按钮（「直播截图 (12)」）和标签输入框的下拉建议 ——
   * 有这份清单才能做到「点一下已有标签」，而不是每次都要手打一遍、还可能打重。
   */
  @GetMapping("/tags")
  public ResponseEntity<List<MediaTagView>> tags() {
    return ResponseEntity.ok(tag.list());
  }

  /**
   * 给一批素材打标签。body 形如 {@code {"paths":[...], "tags":["直播截图"], "replace":false}}。
   *
   * <p>{@code replace} 区分两种很不一样的意图：批量勾选二十张图设标签时是「追加」，
   * 而在单条素材的编辑框里取消勾选某个标签，语义是「去掉它」 —— 后者必须整体替换，
   * 否则去掉的动作会被当成「没传这个标签」而永远删不掉。
   */
  @PutMapping("/tags")
  public ResponseEntity<MediaTagsResult> applyTags(@RequestBody ApplyTagsRequest body,
      HttpServletRequest request) {
    if (body == null || body.paths() == null || body.paths().isEmpty()) {
      throw ApiException.badRequest("请先选中素材");
    }
    return ResponseEntity.ok(tag.apply(body.paths(), body.tags(), Boolean.TRUE.equals(body.replace()),
        auth.currentUserId(), IpUtil.clientIp(request)));
  }

  /** 标签改名：新名字已存在时是「合并」—— 把素材挪过去、旧名字删掉 */
  @PostMapping("/tags/rename")
  public ResponseEntity<MediaTagsResult> renameTag(@RequestBody RenameTagRequest body,
      HttpServletRequest request) {
    if (body == null || body.from() == null || body.to() == null) {
      throw ApiException.badRequest("缺少标签名");
    }
    return ResponseEntity.ok(tag.rename(body.from(), body.to(), auth.currentUserId(),
        IpUtil.clientIp(request)));
  }

  /** 删标签：只解除关联，素材一个都不动 —— 「删标签」和「删素材」是两回事 */
  @DeleteMapping("/tags")
  public ResponseEntity<java.util.Map<String, Object>> deleteTag(@RequestParam("name") String name,
      HttpServletRequest request) {
    int removed = tag.delete(name, auth.currentUserId(), IpUtil.clientIp(request));
    return ResponseEntity.ok(java.util.Map.of("removed", removed));
  }

  /** 清掉没人用的空标签：改名合并剩下的旧名字、打错的错字都在这里一次性收尾 */
  @DeleteMapping("/tags/empty")
  public ResponseEntity<java.util.Map<String, Object>> pruneEmptyTags() {
    return ResponseEntity.ok(java.util.Map.of("removed", tag.pruneEmpty()));
  }

  /**
   * 删除单个素材，并把站点内容里所有引用它的位置一起摘掉。
   *
   * <p>响应不再是 204：后台要用 {@code referencesRemoved} 提示「页面里同步移除了 N 处引用」，
   * 否则用户会以为删了素材页面没变化。
   */
  @DeleteMapping
  public ResponseEntity<MediaDeleteResult> delete(@RequestParam("path") String path,
      HttpServletRequest request) {
    return ResponseEntity.ok(media.delete(path, auth.currentUserId(), IpUtil.clientIp(request)));
  }

  /**
   * 批量删除。body 形如 {@code {"paths":["2026/10/a.jpg", ...]}}。
   *
   * <p>用 JSON body 而不是重复的 query 参数：一次勾几十个，
   * URL 长度很容易撞上 Nginx 默认 4k 的 large_client_header_buffers，
   * 报的是 400 而原因很难看出来。
   */
  @DeleteMapping("/batch")
  public ResponseEntity<MediaBatchDeleteResult> deleteBatch(
      @RequestBody BatchDeleteRequest body, HttpServletRequest request) {
    List<String> paths = body == null || body.paths() == null ? List.of() : body.paths();
    if (paths.size() > 200) {
      throw ApiException.badRequest("一次最多删除 200 个素材，当前 " + paths.size() + " 个");
    }
    return ResponseEntity.ok(media.deleteBatch(paths, auth.currentUserId(), IpUtil.clientIp(request)));
  }

  /**
   * 批量上传：字段 {@code files} 可以带多个文件，逐个处理、逐个给结论。
   *
   * <p>前端一次拖拽三十张图时，逐个请求会有两个问题：浏览器并发上限排队、失败位置不明。
   * 这里一次性收下，回来是一份「文件名 → url 或 error」的清单。
   *
   * <p>可选字段 {@code tags} 是逗号分隔的标签名（如 {@code 直播截图,客户A}），
   * 传了就在上传结束后给这批成功的文件打上，省得传完再回列表一张张勾选。
   */
  @PostMapping("/batch")
  public ResponseEntity<List<MediaBatchUploadItem>> uploadBatch(
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(value = "tags", required = false) String tags, HttpServletRequest request) {
    if (files != null && files.size() > 100) {
      throw ApiException.badRequest("一次最多上传 100 个文件，当前 " + files.size() + " 个");
    }
    return ResponseEntity.ok(media.uploadBatch(files, splitTags(tags), auth.currentUserId(),
        IpUtil.clientIp(request)));
  }

  /** {@code "a, b,, c"} → {@code [a, b, c]}：逗号分隔、去空白、去空洞 */
  private static List<String> splitTags(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split("[,，]"))
        .map(String::trim)
        .filter((s) -> !s.isEmpty())
        .toList();
  }

  /** 批量删除的请求体。 */
  public record BatchDeleteRequest(List<String> paths) {}

  /** 打标签的请求体。 */
  public record ApplyTagsRequest(List<String> paths, List<String> tags, Boolean replace) {}

  /** 标签改名的请求体。 */
  public record RenameTagRequest(String from, String to) {}

  public record MediaItemView(String path, String url, String mime, long size,
      Integer width, Integer height, String createdAt, List<String> tags) {

    static MediaItemView from(MediaAsset a, List<String> tags) {
      List<String> safe = tags == null ? List.of() : tags;
      return new MediaItemView(
          a.getPath(),
          MediaService.publicUrl(a.getPath()),
          a.getMime(),
          a.getSize() == null ? 0L : a.getSize(),
          a.getWidth(),
          a.getHeight(),
          a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
          safe.isEmpty() ? List.of() : safe);
    }
  }
}
