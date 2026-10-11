package com.yuchen.portfolio.web.dto;

import java.util.List;

/**
 * 批量删除素材的结果。
 *
 * @param deleted           成功删掉的记录数
 * @param referencesRemoved 连带从站点内容里摘掉的引用数
 * @param failed            没删成的路径（通常是已不存在或路径非法）
 */
public record MediaBatchDeleteResult(int deleted, int referencesRemoved, List<String> failed) {}
