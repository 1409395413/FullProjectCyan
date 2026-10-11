package com.yuchen.portfolio.repository;

import com.yuchen.portfolio.entity.ContentRevision;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentRevisionRepository extends JpaRepository<ContentRevision, Long> {

  /** 按时间倒序取最近若干份快照，后台可据此回滚。 */
  List<ContentRevision> findTop20ByOrderByCreatedAtDesc();

  /** 最新一份快照的原文（用于「内容没变就不重复存」的判断）；没有快照返回 null。 */
  @Query(value = "SELECT payload FROM content_revision ORDER BY created_at DESC, id DESC LIMIT 1",
      nativeQuery = true)
  String findLatestPayload();

  /** 只保留最新的 keep 条，其余物理删除。 */
  @Modifying
  @Query(value = "DELETE FROM content_revision WHERE id NOT IN ("
      + "SELECT id FROM (SELECT id FROM content_revision ORDER BY created_at DESC, id DESC LIMIT :keep) t)",
      nativeQuery = true)
  int keepNewest(@Param("keep") int keep);

  /** 删掉最老的一条，返回受影响行数。按量裁剪时逐条删。 */
  @Modifying
  @Query(value = "DELETE FROM content_revision ORDER BY created_at ASC, id ASC LIMIT 1",
      nativeQuery = true)
  int deleteOldest();

  /** 当前条数与总字节数：[0]=count, [1]=bytes */
  @Query(value = "SELECT COUNT(*), COALESCE(SUM(LENGTH(payload)), 0) FROM content_revision",
      nativeQuery = true)
  List<Object[]> countAndBytes();
}
