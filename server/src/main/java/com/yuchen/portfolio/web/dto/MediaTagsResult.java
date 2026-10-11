package com.yuchen.portfolio.web.dto;

import java.util.List;

/**
 * 打标签的结果回执。
 *
 * @param applied 真正被改到的素材数
 * @param missing 前端传了、但库里已经没有的路径（通常是刚被别处删掉）
 * @param tags    规范化之后的标签名 —— 前端传进来的可能带着 '#'、空格或重复，
 *                回显这一份能让界面立刻显示成系统里实际存的样子
 */
public record MediaTagsResult(int applied, List<String> missing, List<String> tags) {}
