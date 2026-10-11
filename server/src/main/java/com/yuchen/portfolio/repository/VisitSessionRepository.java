package com.yuchen.portfolio.repository;

import com.yuchen.portfolio.entity.VisitSession;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitSessionRepository extends JpaRepository<VisitSession, Long> {

  List<VisitSession> findTop50ByOrderByStartedAtDesc();

  long countByDay(String day);

  /** 按天聚合：日期 / 会话数 / 总停留毫秒 / 总播放次数 */
  @Query("select s.day, count(s), coalesce(sum(s.durationMs), 0), coalesce(sum(s.playCount), 0) "
      + "from VisitSession s group by s.day order by s.day desc")
  List<Object[]> dailyAggregate(Pageable pageable);

  @Query("select count(distinct s.visitorKey) from VisitSession s where s.day = :day")
  long countVisitorsByDay(@Param("day") String day);

  @Query("select coalesce(sum(s.durationMs), 0) from VisitSession s where s.day = :day")
  long sumDurationByDay(@Param("day") String day);

  @Query("select coalesce(sum(s.playCount), 0) from VisitSession s where s.day = :day")
  long sumPlayByDay(@Param("day") String day);

  /** 当天真正点开过作品的会话数（用来算播放率） */
  @Query("select count(s) from VisitSession s where s.day = :day and s.playCount > 0")
  long countPlayedSessionsByDay(@Param("day") String day);

  /**
   * 取窗口内的会话，用于「地域 / 设备 / 来源 / 时段」这类 SQL 不好分组的聚合
   * （UA、referrer 都得在 Java 里拆）。
   *
   * <p>用 Pageable 硬性截断：站点流量小（历史 85 条），但万一哪天爆了，
   * 宁可少统计也不能把整表拉进内存 —— 2G 机器经不起。
   */
  @Query("select s from VisitSession s where s.startedAt >= :from order by s.startedAt desc")
  List<VisitSession> since(@Param("from") Instant from, Pageable pageable);
}
