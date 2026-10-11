package com.yuchen.portfolio.service;

import com.yuchen.portfolio.config.AppProperties;
import com.yuchen.portfolio.entity.MediaAsset;
import com.yuchen.portfolio.repository.MediaAssetRepository;
import com.yuchen.portfolio.util.SizeUtil;
import com.yuchen.portfolio.web.ApiException;
import com.yuchen.portfolio.web.dto.MediaBatchDeleteResult;
import com.yuchen.portfolio.web.dto.MediaBatchUploadItem;
import com.yuchen.portfolio.web.dto.MediaDeleteResult;
import com.yuchen.portfolio.web.dto.MediaUploadResult;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 素材上传 / 删除 / 列表。
 *
 * <p>只在数据库里存相对路径（例如 {@code 2026/10/ab12.jpg}），
 * 对外暴露 {@code /media/2026/10/ab12.jpg}。换域名或换存储位置都不影响已发布的内容。
 */
@Service
public class MediaService {

  private final StorageService storage;
  private final MediaAssetRepository repo;
  private final AuditService audit;
  private final AppProperties props;
  private final ContentService content;
  private final MediaTagService tagService;

  /**
   * 上传之后的衍生件（WebP 副本、视频封面）都丢到这条后台线程上做。
   * ---------------------------------------------------------------------------
   * 为什么不塞进上传请求里：转图/抽帧要跑 ffmpeg，一张大图几百毫秒到几秒不等，
   * 批量传 30 张时同步做会让整个请求几分钟不返回，前端一直转圈。
   *
   * 为什么是<b>单线程</b>：这台服务器只有 2 核可用内存约 1.6G。ffmpeg 子进程不占
   * JVM 堆，但几个一起跑会互相抢 CPU，把同一时刻正在进行的上传/下载整体拖慢。
   * 排着队做，最坏情况是慢一点才刷出 webp，而 nginx 本来就有「缺 webp 就发原图」
   * 的兜底，慢这么几秒没有任何可见后果。
   *
   * 守护线程：避免 JVM 要停机时还被它拖住。
   */
  private final ExecutorService postExecutor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "cyan-media-postproc");
    t.setDaemon(true);
    return t;
  });

  public MediaService(StorageService storage, MediaAssetRepository repo, AuditService audit,
      AppProperties props, ContentService content, MediaTagService tagService) {
    this.storage = storage;
    this.repo = repo;
    this.audit = audit;
    this.props = props;
    this.content = content;
    this.tagService = tagService;
  }

  @Transactional
  public MediaUploadResult upload(MultipartFile file, Long userId, String ip) {
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("没有收到文件");
    }
    if (file.getSize() > props.getUploadMaxBytes()) {
      throw ApiException.badRequest(
          "文件超过上限 " + SizeUtil.human(props.getUploadMaxBytes()) + "，当前 " + SizeUtil.human(file.getSize()));
    }

    String mime;
    try {
      mime = storage.sniff(file);
    } catch (IOException e) {
      throw ApiException.badRequest("读取文件失败");
    }
    if (mime == null) {
      throw ApiException.badRequest("无法识别的文件类型，只允许图片(jpg/png/gif/webp)与视频(mp4/webm)");
    }
    if (props.getUploadAllowMime() != null && !props.getUploadAllowMime().isEmpty()
        && !props.getUploadAllowMime().contains(mime)) {
      throw ApiException.badRequest("该文件类型不在允许列表中：" + mime);
    }

    String checksum;
    try {
      checksum = sha256(file.getInputStream());
    } catch (Exception e) {
      throw ApiException.badRequest("文件校验失败");
    }

    // 同内容直接复用，避免重复占空间
    Optional<MediaAsset> existing = repo.findTopByChecksum(checksum);
    if (existing.isPresent()) {
      MediaAsset e = existing.get();
      return new MediaUploadResult(publicUrl(e.getPath()), e.getMime(), e.getSize(), e.getWidth(), e.getHeight(),
          warnOf(e.getMime(), e.getPath()));
    }

    String rel;
    try {
      rel = storage.store(file, mime);
    } catch (IOException e) {
      throw ApiException.badRequest("保存文件失败");
    }

    faststartIfVideo(mime, rel);

    int[] wh = readImageSize(mime, rel);
    if (wh == null) wh = readVideoSize(mime, rel);

    MediaAsset asset = new MediaAsset();
    asset.setPath(rel);
    asset.setMime(mime);
    asset.setSize(file.getSize());
    asset.setChecksum(checksum);
    asset.setWidth(wh == null ? null : wh[0]);
    asset.setHeight(wh == null ? null : wh[1]);
    asset.setCreatedBy(userId);
    repo.save(asset);

    String mimeType = mime;
    String relPath = rel;
    try {
      postExecutor.submit(() -> postProcess(relPath, mimeType));
    } catch (Exception e) {
      // 线程池拒绝也好、排队失败也好，都不该让「已经存好的文件」变成一次上传失败
    }

    audit.record(userId, "upload_media", ip, Map.of("path", rel, "mime", mime, "size", file.getSize()));
    return new MediaUploadResult(publicUrl(rel), mime, file.getSize(), asset.getWidth(), asset.getHeight(),
        warnOf(mime, rel));
  }

  /**
   * 上传后的异步加工：照片补 WebP，视频抽封面。
   *
   * <p>两件事都是为了「让人别再去下载原件」：
   *   · WebP：同样一张图体积通常小一个数量级，由 nginx 按 Accept 头自动换给浏览器；
   *   · 封面：列表/卡片只显示这张静态图，浏览器完全不必去碰几百 MB 的原片。
   *
   * <p>这里<b>任何异常都必须被吞掉</b> —— 它们只是优化。
   * 失败的结果是「继续用原图」，而不是「素材打不开」。
   */
  private void postProcess(String rel, String mime) {
    if (rel == null || mime == null) return;
    try {
      if (mime.startsWith("image/")) {
        ensureWebp(rel);
      } else if (mime.startsWith("video/")) {
        ensurePoster(rel);
      }
    } catch (Exception e) {
      // 兜底：日志也不打，避免一次失败淹没正常日志。
      // 想排查时用服务器上那个 /usr/local/bin/gen-webp.sh 手动跑一遍就知道了。
    }
  }

  /**
   * 给图片生成同名 .webp（xxx.jpg 旁边多一个 xxx.jpg.webp）。
   *
   * <p>参数与服务器上的 {@code /usr/local/bin/gen-webp.sh} 完全一致：
   * 长边不超过 1920、quality 78。两边谁先跑到都一样，文件是同一份。
   *
   * <p><b>原图一律保留</b> —— 不支持 webp 的浏览器（以及 nginx 协商失败的场景）
   * 还能拿到原图，不会出现「为了优化把图弄没了」。
   * 转出来反而更大的情况也直接删掉，不占无谓的空间。
   */
  private void ensureWebp(String rel) {
    if (!hasFfmpeg()) return;
    Path tmp = null;
    try {
      Path p = storage.resolve(rel);
      Path out = p.resolveSibling(p.getFileName() + ".webp");
      if (Files.isRegularFile(out) && Files.size(out) > 0) return;

      // 后缀必须是 .webp：ffmpeg 靠扩展名判断输出格式。
      // 另外写 .tmp 再 move，避免前半张半成品被 nginx 读走。
      tmp = out.resolveSibling(out.getFileName() + ".tmp.webp");
      ProcessBuilder pb = guarded("ffmpeg", "-nostdin", "-y", "-loglevel", "error",
          "-threads", "1",
          "-i", p.toString(),
          "-vf", "scale='min(1920,iw)':-2:flags=lanczos",
          "-frames:v", "1",
          "-c:v", "libwebp", "-quality", "78", "-compression_level", "4",
          "-f", "webp",
          tmp.toString());
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      Process proc = pb.start();
      if (!proc.waitFor(60, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        Files.deleteIfExists(tmp);
        return;
      }
      if (proc.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
        Files.deleteIfExists(tmp);
        return;
      }
      if (Files.size(tmp) >= Files.size(p)) {
        Files.deleteIfExists(tmp);   // 没省下来就别要
        return;
      }
      Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
          // 忽略
        }
      }
    }
  }

  /**
   * 视频上传完就把封面抽好，别等到列表/卡片第一次显示时才现场抽。
   *
   * <p>之前是「谁第一次请求封面谁触发 ffmpeg」：后台一次性渲染几十条素材时，
   * 几十个 ffmpeg 同时在跑，实测 63 个并发里有 8 个直接 502 —— 因为 Tomcat 的
   * 线程全被这些几秒钟的请求占住了。提前生成之后，那条路径退化成纯粹的读文件。
   */
  private void ensurePoster(String rel) {
    if (!hasFfmpeg()) return;
    try {
      Path video = storage.resolve(rel);
      if (!Files.isRegularFile(video)) return;
      Path out = posterPathOf(rel);
      if (out == null) return;
      if (Files.isRegularFile(out) && Files.size(out) > 0) return;
      generatePoster(video, out);
    } catch (Exception e) {
      // 抽不到封面不影响任何功能，前端会自动退回首帧/占位
    }
  }

  /**
   * 视频能不能在浏览器里播，看的是**编码**而不是扩展名。
   * ---------------------------------------------------------------------------
   * 一个 .mp4 里面的视频流可能是 H.264，也可能是 HEVC/H.265（iPhone「高效」模式、
   * 剪映/达芬奇的部分导出预设、很多录屏软件的默认输出都是它）。
   * 浏览器对 HEVC 的支持很碎：Safari 能播，Chrome/Edge 要看系统有没有装 HEVC 解码器，
   * Firefox 基本不支持 —— 于是「上传成功、预览正常，但访客端一片黑」这种
   * 极难自查的故障就出现了（本机能播≠别人能播）。
   * 这里只做提示不阻断上传：文件照存，把原因说清楚，让人自己决定转不转。
   */
  private String warnOf(String mime, String rel) {
    if (mime == null || !mime.startsWith("video/")) return null;
    String codec = probeVideoCodec(mime, rel);
    if (codec == null || codec.isBlank()) return null;
    return switch (codec) {
      case "h264", "vp8", "vp9", "av1" -> null; // 浏览器都认
      case "hevc" -> "该视频是 HEVC/H.265 编码，Safari 正常，Chrome/Edge/Firefox 与多数安卓手机都播不了。"
          + "请用剪映/格式工厂导出时选 H.264，或用 ffmpeg 转一次：" 
          + "ffmpeg -i 原文件.mp4 -c:v libx264 -crf 23 -c:a aac -movflags +faststart 新文件.mp4";
      default -> "该视频编码是 " + codec + "，部分浏览器可能无法播放，建议转成 H.264 后重新上传。";
    };
  }

  /** 视频流的编码名（h264 / hevc / vp9 ...），探测不到返回 null */
  private String probeVideoCodec(String mime, String rel) {
    if (mime == null || !mime.startsWith("video/") || !hasFfmpeg()) return null;
    try {
      Path p = storage.resolve(rel);
      ProcessBuilder pb = guarded("ffprobe", "-v", "error",
          "-select_streams", "v:0", "-show_entries", "stream=codec_name",
          "-of", "csv=p=0", p.toString());
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      if (!proc.waitFor(20, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        return null;
      }
      String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim().toLowerCase();
      return out.isEmpty() ? null : out;
    } catch (Exception e) {
      return null; // 探测失败不影响上传
    }
  }

  /**
   * 删除单个素材 —— 连同站点内容里的引用一起摘掉。
   * ---------------------------------------------------------------------------
   * 「删了素材，前台还挂着」是最容易被当成 bug 反馈的那个现象：
   * 素材库和内容 payload 是两套数据，只删前者，页面里那条 src 就成了死链，
   * 表现为一张永远加载不出来的裂图。这里删完立刻做一次引用清扫。
   */
  @Transactional
  public MediaDeleteResult delete(String relativePath, Long userId, String ip) {
    MediaAsset asset = repo.findByPath(relativePath)
        .orElseThrow(() -> ApiException.notFound("素材不存在"));
    Long assetId = asset.getId();
    storage.delete(asset.getPath());
    repo.delete(asset);
    // 标签关联行也得删，否则它指向的素材已经没了，还在给标签贡献计数
    tagService.dropForAssets(List.of(assetId));
    int refs = content.purgeMediaReferences(List.of(publicUrl(relativePath)), userId, ip);
    audit.record(userId, "delete_media", ip, Map.of("path", relativePath, "referencesRemoved", refs));
    return new MediaDeleteResult(1, refs);
  }

  /**
   * 批量删除。
   *
   * <p>先逐个删记录和文件，收集成功者的 URL，最后<b>只做一次</b>内容清扫 ——
   * 每个文件扫一遍 payload 会退化成 O(n×内容大小)，十几个文件就能明显卡住后台。
   * 注意这里不抛「路径不存在」：批量场景下一个失效路径不该让整批回滚。
   */
  @Transactional
  public MediaBatchDeleteResult deleteBatch(List<String> paths, Long userId, String ip) {
    if (paths == null || paths.isEmpty()) {
      return new MediaBatchDeleteResult(0, 0, List.of());
    }
    List<String> urls = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    int deleted = 0;

    for (String p : paths) {
      if (p == null || p.isBlank()) continue;
      Optional<MediaAsset> opt = repo.findByPath(p);
      if (opt.isEmpty()) {
        failed.add(p);
        continue;
      }
      MediaAsset a = opt.get();
      Long id = a.getId();
      storage.delete(a.getPath());
      repo.delete(a);
      tagService.dropForAssets(List.of(id));
      urls.add(publicUrl(p));
      deleted++;
    }

    int refs = urls.isEmpty() ? 0 : content.purgeMediaReferences(urls, userId, ip);
    audit.record(userId, "delete_media_batch", ip,
        Map.of("deleted", deleted, "referencesRemoved", refs, "failed", failed.size()));
    return new MediaBatchDeleteResult(deleted, refs, failed);
  }

  /**
   * 批量上传。
   *
   * <p>逐个走和单条上传完全相同的校验与后处理（秒传、HEVC 提示、faststart、
   * 尺寸探测都照旧），区别只有两点：
   *   1. 单个文件出错被吞掉写进 {@code error} 字段，<b>不影响其余文件</b>；
   *   2. 不做整体事务回滚 —— 传到一半失败，已成功的那些保留，
   *      这比「50 个文件因为第 3 个不支持而全部作废」更符合直觉。
   */
  public List<MediaBatchUploadItem> uploadBatch(List<MultipartFile> files, List<String> tags,
      Long userId, String ip) {
    List<MediaBatchUploadItem> out = new ArrayList<>();
    if (files == null || files.isEmpty()) return out;
    for (MultipartFile f : files) {
      String name = f == null ? "" : String.valueOf(f.getOriginalFilename());
      if (f == null || f.isEmpty()) {
        out.add(MediaBatchUploadItem.failed(name, "空文件，已跳过"));
        continue;
      }
      try {
        MediaUploadResult r = upload(f, userId, ip);
        out.add(MediaBatchUploadItem.ok(name, r.url(), r.mime(), r.size(), r.width(), r.height(),
            r.warning()));
      } catch (ApiException e) {
        out.add(MediaBatchUploadItem.failed(name, e.getMessage()));
      } catch (Exception e) {
        out.add(MediaBatchUploadItem.failed(name, "上传失败：" + e.getMessage()));
      }
    }

    // 上传时就顺手打上标签：一次传三十张「直播截图」的话，
    // 不用传完再回列表把它们一个个挑出来。标签为空自然跳过。
    if (tags != null && !tags.isEmpty()) {
      List<String> relPaths = new ArrayList<>();
      for (MediaBatchUploadItem it : out) {
        String rel = relativeOf(it.url());
        if (rel != null) relPaths.add(rel);
      }
      if (!relPaths.isEmpty()) {
        try {
          tagService.apply(relPaths, tags, false, userId, ip);
        } catch (ApiException e) {
          // 标签失败不该让上传失败 —— 文件已经在服务器上了，说一声就行
          if (!out.isEmpty()) {
            MediaBatchUploadItem first = out.get(0);
            String note = first.warning() == null || first.warning().isBlank()
                ? "素材已上传，但标签没写上：" + e.getMessage()
                : first.warning() + "；另外标签没写上：" + e.getMessage();
            out.set(0, MediaBatchUploadItem.ok(first.name(), first.url(), first.mime(), first.size(),
                first.width(), first.height(), note));
          }
        }
      }
    }
    return out;
  }

  /** 从对外 URL（/media/2026/10/x.jpg）还原出相对路径 */
  private static String relativeOf(String url) {
    if (url == null || url.isBlank()) return null;
    String prefix = "/media/";
    return url.startsWith(prefix) ? url.substring(prefix.length()) : url;
  }

  /** 素材 + 它身上的标签。列表接口要用，所以把标签一起查出来，避免前端一条一条问 */
  public record AssetWithTags(MediaAsset asset, List<String> tags) {}

  public List<MediaAsset> list() {
    return repo.findTop100ByOrderByCreatedAtDesc();
  }

  /**
   * 素材列表（带标签）。
   *
   * <p>标签一次性批量查回来，不是循环里逐个查 —— 数据库最怕的就是这种 1+N。
   */
  @Transactional(readOnly = true)
  public List<AssetWithTags> listWithTags() {
    List<MediaAsset> assets = list();
    if (assets.isEmpty()) return List.of();
    Map<Long, List<String>> map = tagService.tagsOf(assets.stream().map(MediaAsset::getId).toList());
    List<AssetWithTags> out = new ArrayList<>();
    for (MediaAsset a : assets) {
      out.add(new AssetWithTags(a, map.getOrDefault(a.getId(), List.of())));
    }
    return out;
  }

  /**
   * 视频首帧封面图。
   * ---------------------------------------------------------------------------
   * 为什么需要它：<b>手机上视频卡片会是一整块灰</b>。
   * iOS Safari / 部分安卓浏览器对 {@code preload="metadata"} 常常压根不解码画面，
   * 竖屏大码率视频更是要等几十秒才出帧，访客看到的就只是一个"到底传没传上"的灰方块。
   *
   * <p>做法是服务端抽帧而非前端 canvas：
   *   · 前端 canvas 也要等浏览器真的解出一帧，拿不到就是拿不到，根因没解决；
   *   · 这里的 jpg 是 nginx 能直出的静态图，任何浏览器、任何网络环境都显示得出来；
   *   · 按素材路径落盘缓存，**只在第一次请求时抽一次**，之后就是读文件。
   *
   * <p>拿不到就返回 empty，调用方给 404 —— 缺封面不该让页面报错，
   * 前端会退回原来的 video 渲染方式，最多还是灰块，不会比现在更差。
   */
  public Optional<Poster> posterOf(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) return Optional.empty();

    Path video;
    try {
      video = storage.resolve(relativePath);
    } catch (Exception e) {
      return Optional.empty(); // resolve 已挡掉路径穿越
    }
    if (!Files.isRegularFile(video)) return Optional.empty();

    MediaAsset asset = repo.findByPath(relativePath).orElse(null);
    String mime = asset == null ? "" : asset.getMime();
    if (mime == null || !mime.startsWith("video/")) {
      // 没有库记录（比如手工放进来的文件）时，退到扩展名判断
      String n = video.getFileName().toString().toLowerCase();
      if (!n.endsWith(".mp4") && !n.endsWith(".webm")) return Optional.empty();
    }

    Path out = posterPathOf(relativePath);
    if (out == null) return Optional.empty();

    if (Files.isRegularFile(out)) {
      Optional<Poster> cached = readPoster(out);
      if (cached.isPresent()) return cached;
    }
    if (!hasFfmpeg()) return Optional.empty();
    if (!generatePoster(video, out)) return Optional.empty();
    return readPoster(out);
  }

  /** 首帧图 jpg —— bytes + MIME。 */
  public record Poster(byte[] bytes, String mime) {}

  /** 缓存落哪里：与上传目录平级的 posters/，保持同样的 yyyy/MM 子结构。 */
  private Path posterRoot() {
    String cfg = props.getPosterDir();
    if (cfg == null || cfg.isBlank()) {
      return storage.root().resolveSibling("posters").toAbsolutePath().normalize();
    }
    return java.nio.file.Paths.get(cfg).toAbsolutePath().normalize();
  }

  private Path posterPathOf(String relativePath) {
    Path root = posterRoot();
    Path out = root.resolve(relativePath + ".jpg").normalize();
    return out.startsWith(root) ? out : null;
  }

  private Optional<Poster> readPoster(Path p) {
    try {
      if (!Files.isRegularFile(p) || Files.size(p) == 0) return Optional.empty();
      return Optional.of(new Poster(Files.readAllBytes(p), "image/jpeg"));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * ffmpeg 抽第 0.2 秒那一帧。
   *
   * <p>不抽第 0 帧：不少视频开头是黑场/转场<sup>（尤其剪辑软件导出的片头）</sup>，
   * 0.2 秒通常已经是正片画面了。极短的视频会抽帧失败，这时再退到 0 秒重试一次。
   *
   * <p>先写 .tmp 再 move：抽帧要跑一两秒，期间可能有并发请求进来，
   * 直接写目标文件会让别人读到半张图。
   */
  private boolean generatePoster(Path video, Path out) {
    try {
      Files.createDirectories(out.getParent());
      // 后缀必须是 .jpg —— ffmpeg 靠扩展名猜输出封装格式，
      // 写成 xxx.tmp 会直接 "Unable to find a suitable output format" 退出，
      // 跟项目里以前踩过的 faststart 那个坑是同一个原因。
      Path tmp = out.resolveSibling(out.getFileName() + ".tmp.jpg");
      for (String seek : new String[] { "0.2", "0" }) {
        Files.deleteIfExists(tmp);
        ProcessBuilder pb = guarded("ffmpeg", "-y", "-loglevel", "error",
            "-ss", seek,
            "-i", video.toString(),
            "-frames:v", "1",
            "-vf", "scale=640:-2",
            "-q:v", "6",
            "-f", "mjpeg",
            tmp.toString());
        // 丢弃 stderr 而不是 redirectErrorStream：日志不读会填满管道缓冲区把子进程卡死
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
          proc.destroyForcibly();
          continue;
        }
        if (proc.exitValue() == 0 && Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
          Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
          Files.deleteIfExists(tmp);
          return true;
        }
      }
      Files.deleteIfExists(tmp);
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  public static String publicUrl(String relativePath) {
    return "/media/" + relativePath;
  }

    /** ffmpeg / ffprobe 的内存上限：700 MB 虚拟地址空间。抽帧和转图用不到更多。 */
  private static final String TOOL_AS_LIMIT_BYTES = "734003200";

  /** prlimit 在不在机器上；没有就退回裸跑（见 {@link #hasFfmpeg} 同样的探测套路） */
  private volatile Boolean prlimitAvailable = null;

  private boolean hasPrlimit() {
    if (prlimitAvailable == null) {
      synchronized (this) {
        if (prlimitAvailable == null) {
          try {
            Process proc = new ProcessBuilder("prlimit", "--version").start();
            prlimitAvailable = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
          } catch (Exception e) {
            prlimitAvailable = Boolean.FALSE;
          }
        }
      }
    }
    return prlimitAvailable;
  }

  /**
   * 构造 ffmpeg / ffprobe 命令，并给它戴上内存笼子。
   *
   * <p><b>这是整个优化里最关键的一处防御。</b>
   * 子进程和 JVM 共用 systemd 给的那个 cgroup 配额（见 MemoryMax），
   * 一方吃撑，另一方就要陪葬。详细背景见文件头部注释。
   *
   * <p>参数用法：{@code guarded("ffmpeg", "-y", "-i", ...)}，
   * 第一个参数是可执行文件名，其余原样拼到后面。
   */
  private ProcessBuilder guarded(String... tail) {
    java.util.List<String> cmd = new ArrayList<>();
    if (hasPrlimit()) {
      cmd.add("prlimit");
      cmd.add("--as=" + TOOL_AS_LIMIT_BYTES);
    }
    for (String t : tail) {
      cmd.add(t);
    }
    return new ProcessBuilder(cmd);
  }

  /** ffmpeg 是否装了（只探测一次；没装就永远跳过，上传照常成功） */
  private volatile Boolean ffmpegAvailable = null;

  private boolean hasFfmpeg() {
    if (ffmpegAvailable == null) {
      synchronized (this) {
        if (ffmpegAvailable == null) {
          try {
            Process proc = guarded("ffmpeg", "-version").start();
            ffmpegAvailable = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
          } catch (Exception e) {
            ffmpegAvailable = Boolean.FALSE;
          }
        }
      }
    }
    return ffmpegAvailable;
  }

  /**
   * 视频落盘后把 moov 索引搬到文件头（faststart）。
   * ---------------------------------------------------------------------------
   * 手机 / 相机 / 部分剪辑软件导出的 mp4 常常是 mdat（数据）在前、moov（索引）在后。
   * 这种文件浏览器必须**把整个文件下载完**才能拿到尺寸、时长和首帧，后果是：
   *   · 尺寸拿不到 -> 画框退回兜底的 16:9 -> 1080x1920 的竖屏被 cover 裁成横屏
   *   · 首帧渲染不出来 -> 卡片位置一片灰，看不出到底传没传上
   * 一个 40MB 的视频在移动网络下基本等不到元数据。
   *
   * <p>这里是 {@code -c copy} 纯重封装：不重新编码，画质、码率、时长完全不变，
   * 只是把索引挪到前面，秒级完成。任何一步出问题都保持原文件不动，
   * **绝不让上传因为优化失败而失败**。
   */
  private void faststartIfVideo(String mime, String rel) {
    if (mime == null || !mime.startsWith("video/") || !hasFfmpeg()) return;
    Path tmp = null;
    try {
      Path p = storage.resolve(rel);
      // 后缀必须是 .mp4：ffmpeg 靠扩展名猜封装格式，写成 xxx.mp4.fs 会直接
      // "Unable to find a suitable output format" 退出，faststart 就静默失效了。
      // 下面再显式给一次 -f mp4，双保险。
      tmp = p.resolveSibling(p.getFileName() + ".fs.mp4");
      ProcessBuilder pb = guarded("ffmpeg", "-y", "-loglevel", "error",
          "-i", p.toString(), "-c", "copy", "-movflags", "+faststart", "-f", "mp4", tmp.toString());
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      if (!proc.waitFor(120, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        Files.deleteIfExists(tmp);
        return;
      }
      if (proc.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
        Files.deleteIfExists(tmp);
        return;
      }
      Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      // 优化失败不算上传失败：原文件原样可用，只是播放启动会慢一点
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
          // 忽略
        }
      }
    }
  }

  /** 视频尺寸走 ffprobe；图片走 ImageIO，见 {@link #readImageSize} */
  private int[] readVideoSize(String mime, String rel) {
    if (mime == null || !mime.startsWith("video/") || !hasFfmpeg()) return null;
    try {
      Path p = storage.resolve(rel);
      ProcessBuilder pb = guarded("ffprobe", "-v", "error",
          "-select_streams", "v:0", "-show_entries", "stream=width,height",
          "-of", "csv=p=0", p.toString());
      pb.redirectErrorStream(true);
      Process proc = pb.start();
      if (!proc.waitFor(20, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        return null;
      }
      String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      String[] parts = out.split("[,\\s]+");
      if (parts.length >= 2) {
        int w = Integer.parseInt(parts[0]);
        int h = Integer.parseInt(parts[1]);
        if (w > 0 && h > 0) return new int[] { w, h };
      }
    } catch (Exception e) {
      return null; // 读不到就留空，前端运行时还会再探测一次
    }
    return null;
  }

  private int[] readImageSize(String mime, String rel) {
    if (!mime.startsWith("image/")) return null;
    try {
      java.nio.file.Path p = storage.resolve(rel);
      BufferedImage img = ImageIO.read(p.toFile());
      if (img == null) return null;
      return new int[] { img.getWidth(), img.getHeight() };
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * 流式算 SHA-256 —— 不要换成 {@code file.getBytes()}。
   *
   * <p><b>这个坑踩过一次，务必留意。</b>曾经这里写的是 {@code sha256(file.getBytes())}，
   * 意思是把整个上传文件读进一个 {@code byte[]} 再散列。小图无所谓，
   * 但遇到 800MB 的视频就是往堆里塞 800MB —— 而 JVM 的 {@code -Xmx} 只有 512MB，
   * 结果必然是 {@code OutOfMemoryError}，请求挂掉、日志里一行 Java 堆栈都未必留得下。
   * 前端看到的就是一句没有信息量的 "Failed to fetch"。
   *
   * <p>换成 64KB 缓冲区分块喂给 {@link MessageDigest}，内存占用恒定，
   * 1GB 文件和 1KB 文件的开销完全相同。
   */
  private String sha256(InputStream in) throws NoSuchAlgorithmException, IOException {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) > 0) {
      md.update(buf, 0, n);
    }
    return HexFormat.of().formatHex(md.digest());
  }
}
