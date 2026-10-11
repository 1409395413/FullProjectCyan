package com.yuchen.portfolio.web.dto;

/**
 * 单条素材删除的结果。
 *
 * @param deleted           删掉的素材库记录数（0 或 1）
 * @param referencesRemoved 连带从站点内容里摘掉的引用数
 *                          —— 素材被引用时这个值才可能大于 0，
 *                             后台用它提示「已同步移除 N 处引用」。
 */
public record MediaDeleteResult(int deleted, int referencesRemoved) {}
