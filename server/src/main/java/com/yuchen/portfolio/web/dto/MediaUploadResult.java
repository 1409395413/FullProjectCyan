package com.yuchen.portfolio.web.dto;

/**
 * 上传结果。{@code warning} 是「能存、但在浏览器里可能放不出来」的提醒
 * （典型：HEVC/H.265 编码的 mp4），不是错误，上传照样成功。
 */
public record MediaUploadResult(String url, String mime, long size, Integer width, Integer height,
    String warning) {}
