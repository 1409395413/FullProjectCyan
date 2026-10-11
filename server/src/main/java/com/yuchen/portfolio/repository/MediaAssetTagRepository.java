package com.yuchen.portfolio.repository;

import com.yuchen.portfolio.entity.MediaAssetTag;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaAssetTagRepository extends JpaRepository<MediaAssetTag, Long> {

  List<MediaAssetTag> findByAssetIdIn(Collection<Long> assetIds);

  List<MediaAssetTag> findByTagId(Long tagId);

  @Modifying
  @Query("delete from MediaAssetTag t where t.assetId in :assetIds")
  void deleteByAssetIdIn(@Param("assetIds") Collection<Long> assetIds);

  @Modifying
  @Query("delete from MediaAssetTag t where t.tagId = :tagId")
  void deleteByTagId(@Param("tagId") Long tagId);

  /**
   * 「每个标签挂了几条素材」一次查出来，返回 [tag_id, count]。
   * 用 JPQL 的 tuple 查询而不是原生 SQL，省得手写列名去对齐表结构。
   */
  @Query("select t.tagId, count(t) from MediaAssetTag t group by t.tagId")
  List<Object[]> countByTagIds();
}
