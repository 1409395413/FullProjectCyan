package com.yuchen.portfolio.repository;

import com.yuchen.portfolio.entity.Inquiry;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

  List<Inquiry> findTop200ByOrderByCreatedAtDesc();

  long countByStatus(String status);

  /** 转化漏斗用：窗口内的需求件数 */
  long countByCreatedAtAfter(Instant from);
}
