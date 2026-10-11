package com.yuchen.portfolio.web.dto;

/**
 * 批量上传里单个文件的结局 —— 成功给 url，失败给 error。
 *
 * <p>批量操作的原则是<b>一条失败不影响其余</b>：50 个文件里第 3 个类型不支持，
 * 剩下 47 个照样要传上去。所以这里用「同一份结构 + 可选错误」而不是直接抛异常。
 */
public record MediaBatchUploadItem(
    String name,
    String url,
    String mime,
    Long size,
    Integer width,
    Integer height,
    String warning,
    String error) {

  public static MediaBatchUploadItem ok(String name, String url, String mime, Long size,
      Integer width, Integer height, String warning) {
    return new MediaBatchUploadItem(name, url, mime, size, width, height, warning, null);
  }

  public static MediaBatchUploadItem failed(String name, String error) {
    return new MediaBatchUploadItem(name, null, null, null, null, null, null, error);
  }
}
