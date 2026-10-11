package com.yuchen.portfolio.web;

import com.yuchen.portfolio.entity.Inquiry;
import com.yuchen.portfolio.repository.InquiryRepository;
import com.yuchen.portfolio.service.AuditService;
import com.yuchen.portfolio.service.RateLimiter;
import com.yuchen.portfolio.util.IpUtil;
import com.yuchen.portfolio.web.dto.InquiryRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作对接。
 *
 * <p>访客在网站底部提交需求 → 落 inquiry 表 → 后台「工作对接」板块按
 * 未读 / 已联系 / 已归档 流转，还能写内部备注。
 */
@RestController
@RequestMapping("/api")
public class InquiryController {

  private static final List<String> STATUSES = List.of("new", "contacted", "archived");

  /** 同一个 IP 一分钟内的提交上限：第 6 次开始拒绝（一分钟内超过五次就限制）。 */
  private static final int INQUIRY_LIMIT_PER_MINUTE = 5;

  /** 触发上限后的冷却时长：10 分钟。冷却期内再提交一律拒绝。 */
  private static final long INQUIRY_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(10);

  /** 冷却期内（以及刚触发时）返回给前台的提示语。 */
  private static final String INQUIRY_LIMIT_MESSAGE = "多次提交请十分钟后提交";

  private final InquiryRepository repo;
  private final AuditService audit;
  private final RateLimiter limiter;

  public InquiryController(InquiryRepository repo, AuditService audit, RateLimiter limiter) {
    this.repo = repo;
    this.audit = audit;
    this.limiter = limiter;
  }

  /* ------------------------------ 前台提交 ------------------------------ */

  @PostMapping(value = "/inquiry", consumes = "application/json")
  public ResponseEntity<Map<String, String>> submit(@Valid @RequestBody InquiryRequest req,
      HttpServletRequest http) {
    String ip = IpUtil.clientIp(http);
    if (!limiter.allow("inquiry:" + ip, INQUIRY_LIMIT_PER_MINUTE,
        TimeUnit.MINUTES.toMillis(1), INQUIRY_COOLDOWN_MS)) {
      throw ApiException.tooManyRequests(INQUIRY_LIMIT_MESSAGE);
    }

    Inquiry q = new Inquiry();
    q.setName(trim(req.getName(), 64));
    q.setContact(trim(req.getContact(), 128));
    q.setType(trim(req.getType(), 64));
    q.setBudget(trim(req.getBudget(), 64));
    q.setMessage(trim(req.getMessage(), 2000));
    q.setStatus("new");
    q.setIp(ip);
    repo.save(q);

    audit.record(null, "inquiry", ip, Map.of("id", q.getId(), "type", q.getType()));
    return ResponseEntity.ok(Map.of("status", "ok"));
  }

  /* ------------------------------ 后台处理 ------------------------------ */

  @GetMapping("/admin/inquiries")
  public ResponseEntity<List<InquiryView>> list() {
    return ResponseEntity.ok(repo.findTop200ByOrderByCreatedAtDesc().stream()
        .map(InquiryView::of)
        .toList());
  }

  public record PatchBody(String status, String note) {}

  @PatchMapping(value = "/admin/inquiries/{id}", consumes = "application/json")
  public ResponseEntity<InquiryView> patch(@PathVariable Long id,
      @RequestBody(required = false) PatchBody body) {
    Inquiry q = repo.findById(id).orElseThrow(() -> ApiException.notFound("需求不存在"));
    if (body != null) {
      if (body.status() != null) {
        String s = body.status().trim();
        if (!STATUSES.contains(s)) {
          throw ApiException.badRequest("状态只能是 new / contacted / archived");
        }
        q.setStatus(s);
      }
      if (body.note() != null) q.setNote(trim(body.note(), 4000));
    }
    return ResponseEntity.ok(InquiryView.of(repo.save(q)));
  }

  @DeleteMapping("/admin/inquiries/{id}")
  public ResponseEntity<Void> remove(@PathVariable Long id) {
    if (!repo.existsById(id)) throw ApiException.notFound("需求不存在");
    repo.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  private String trim(String s, int max) {
    if (s == null) return "";
    String t = s.trim();
    return t.length() > max ? t.substring(0, max) : t;
  }

  public record InquiryView(
      Long id,
      String name,
      String contact,
      String type,
      String budget,
      String message,
      String status,
      String note,
      String ip,
      String createdAt) {
    static InquiryView of(Inquiry q) {
      return new InquiryView(
          q.getId(),
          q.getName(),
          q.getContact(),
          q.getType(),
          q.getBudget(),
          q.getMessage(),
          q.getStatus(),
          q.getNote(),
          q.getIp(),
          q.getCreatedAt() == null ? null : q.getCreatedAt().toString());
    }
  }
}
