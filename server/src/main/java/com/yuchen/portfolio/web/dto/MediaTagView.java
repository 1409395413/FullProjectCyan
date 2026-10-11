package com.yuchen.portfolio.web.dto;

/**
 * 一个标签及其名下的素材数。
 *
 * <p>count 是后端算好的：前端拿它直接渲染筛选按钮上的「标签名 (12)」，
 * 不用为了显示数字而先把全量素材拉下来数一遍。
 */
public record MediaTagView(Long id, String name, long count) {}
