package com.yuchen.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次访问（会话）。
 *
 * <p>刻意<b>不记录可识别个人身份的信息</b>：IP 只存哈希，UA 只留截断后的 255 字符。
 * 目的是回答"今天几个人来过、待了多久、有没有点开作品"，而不是追踪具体是谁。
 */
@Getter
@Setter
@Entity
@Table(name = "visit_session")
public class VisitSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 匿名标识：hash(ip + ua + 日期)，用于去重"今天几个人" */
  @Column(name = "visitor_key", length = 64)
  private String visitorKey;

  @Column(name = "ip_hash", length = 64)
  private String ipHash;

  @Column(name = "ua", length = 255)
  private String ua;

  @Column(name = "referrer", length = 255)
  private String referrer;

  @Column(name = "entry_path", length = 255)
  private String entryPath;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  /** 累计停留毫秒，由前端心跳累加 */
  @Column(name = "duration_ms", nullable = false)
  private Long durationMs = 0L;

  /** 点了多少次作品播放 */
  @Column(name = "play_count", nullable = false)
  private Integer playCount = 0;

  /** 冗余一份 yyyy-MM-dd（按 Asia/Shanghai 计算），方便按天聚合 */
  @Column(name = "day", length = 10)
  private String day;

  /**
   * 地域：只在会话入库那一刻用原始 IP 查一次离线库得到，之后 IP 本身被丢弃。
   * 精度到<b>省/直辖市</b>，不存市以下的粒度。查不到时写「未知」，
   * 2026-10-09 改造前的历史会话没有这两个字段，旧数据一律显示「未知」。
   */
  @Column(name = "country", length = 32)
  private String country;

  @Column(name = "region", length = 32)
  private String region;

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    if (startedAt == null) startedAt = now;
    if (lastSeenAt == null) lastSeenAt = now;
    if (durationMs == null) durationMs = 0L;
    if (playCount == null) playCount = 0;
  }
}
