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
 * 素材与标签的关联行（多对多）。
 *
 * <p>这里用「两个裸外键 + 独立主键」而不是 JPA 的 {@code @ManyToMany}：
 * 多对多映射的移动顺序、懒加载、级联删除都要靠约定约束，
 * 而这张表最终只会被「按素材查标签」和「按标签删关联」两种用法碰到 ——
 * 显式写清楚反而更不容易出错，删素材时也不会因为忘了配 orphanRemoval 而留下悬空行。
 *
 * <p>联合唯一约束保证同一素材重复打同一个标签不会产生第二行。
 */
@Getter
@Setter
@Entity
@Table(
    name = "media_asset_tag",
    uniqueConstraints = @UniqueConstraint(name = "uk_asset_tag", columnNames = { "asset_id", "tag_id" }))
public class MediaAssetTag {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "asset_id", nullable = false)
  private Long assetId;

  @Column(name = "tag_id", nullable = false)
  private Long tagId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) createdAt = Instant.now();
  }
}
