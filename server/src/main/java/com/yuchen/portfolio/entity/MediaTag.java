package com.yuchen.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 素材标签。
 *
 * <p>做成独立的表而不是在 media_asset 里塞一个逗号分隔的字符串，是为了两件事：
 *   <b>重命名一次改全部</b> —— 「直播截图」改成「直播-截图」，几十条素材跟着变，
 *     字符串方案得逐行 replace，还会误伤恰好包含同样子串的名字；
 *   <b>标签列表和计数是查出来的</b> —— 输入框想提示「已有这些标签」时不用去扫全表、
 *     也不用维护一份容易和真实数据不同步的字典。
 *
 * <p>名字唯一。这也是为什么量不大时可以放心 put-if-absent：
 * 并发插入同名会撞唯一索引，届时退回查一次即可（见 MediaTagService）。
 */
@Getter
@Setter
@Entity
@Table(name = "media_tag", uniqueConstraints = @UniqueConstraint(name = "uk_tag_name", columnNames = "name"))
public class MediaTag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
