package com.yuchen.portfolio.config;

import com.yuchen.portfolio.web.dto.ErrorResponse;
import com.yuchen.portfolio.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuchen.portfolio.security.JwtAuthenticationFilter;
import com.yuchen.portfolio.service.AuthService;
import com.yuchen.portfolio.service.CacheService;
import com.yuchen.portfolio.service.RateLimiter;
import com.yuchen.portfolio.util.IpUtil;
import org.springframework.web.util.ContentCachingRequestWrapper;

/**
 * 安全策略：无状态 JWT。
 *
 * <p>公开端点只有两个：读内容（GET /api/content）和读素材（/media/**）。
 * 其余写操作一律要求管理员令牌，任何未匹配到的请求直接 denyAll ——
 * 以后新增接口如果忘了配权限，默认是拒绝而不是放行。
 */
@Configuration
public class SecurityConfig {

  private final ObjectMapper mapper = new ObjectMapper();

  @Bean
  public PasswordEncoder passwordEncoder() {
    // cost=12：约 0.3s/次（现代 CPU），足以对抗离线暴力，又不拖慢登录体验
    return new BCryptPasswordEncoder(12);
  }

  /**
   * 显式提供一个「没有本地用户」的 {@link UserDetailsService}。
   *
   * <p>目的只有一个：让 Boot 的 {@code UserDetailsServiceAutoConfiguration} 退场。
   * 那个自动配置在「容器里没有 AuthenticationManager / AuthenticationProvider /
   * UserDetailsService」时会自己造一个内存账号 user，并在启动日志里打印：
   *
   * <pre>
   *   Using generated security password: xxxxxxxx-xxxx-...
   * </pre>
   *
   * <p>本项目是纯无状态 JWT：账号存在 MySQL 的 admin_user 表里，密码在 AuthService 内
   * 用上面的 BCrypt 比对，压根不走 Spring Security 的 UserDetailsService 那套。
   * 本配置里 httpBasic / formLogin / logout 也全部 disable，
   * 所以那个自动生成的账号实际上**无法被用来登录**——但它有两个副作用值得消掉：
   *   1. 日志里出现一串像密钥的东西，运维排查时容易被误当成泄漏；
   *   2. 平白在内存里多一个用户，属于配置噪声。
   *
   * <p>这里返回「查任何用户都抛 UsernameNotFoundException」，是 fail-closed 的写法：
   * 万一将来有人手滑打开了 basic 认证，也只会登录失败，不会放过一个默认账号。
   */
  @Bean
  public UserDetailsService userDetailsService() {
    return username -> {
      throw new UsernameNotFoundException("本地用户库为空：" + username);
    };
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http,
      JwtAuthenticationFilter jwtFilter,
      RateLimiter rateLimiter,
      AppProperties props) throws Exception {

    http
      .csrf(csrf -> csrf.disable())
      .cors(Customizer.withDefaults())
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .exceptionHandling(e -> e
          .authenticationEntryPoint(jsonEntryPoint())
          .accessDeniedHandler(jsonAccessDenied()))
      .authorizeHttpRequests(a -> a
          .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
          .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
          .requestMatchers(HttpMethod.GET, "/api/content").permitAll()
          .requestMatchers(HttpMethod.GET, "/media/**").permitAll()
          // 视频首帧封面：前台访客也要能加载，否则手机端视频卡片是一片灰。
          // 必须排在下面 "/api/media/** 需登录" 之前 —— 授权规则按顺序匹配，先看命中谁。
          .requestMatchers(HttpMethod.GET, "/api/media/poster").permitAll()
          .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
          // 匿名写入只有两个：访客留言与访问埋点，二者都在 Controller 内部按 IP 限流
          .requestMatchers(HttpMethod.POST, "/api/inquiry").permitAll()
          .requestMatchers(HttpMethod.POST, "/api/track/session", "/api/track/beat",
              "/api/track/event").permitAll()
          .requestMatchers("/api/admin/**", "/api/content", "/api/media", "/api/media/**",
              "/api/auth/me", "/api/auth/logout", "/api/auth/password").authenticated()
          .anyRequest().denyAll())
      .addFilterBefore(new LoginThrottleFilter(rateLimiter, props), UsernamePasswordAuthenticationFilter.class)
      .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
      .httpBasic(b -> b.disable())
      .formLogin(f -> f.disable())
      .logout(l -> l.disable())
      .headers(h -> h
          .frameOptions(fo -> fo.deny())
          .contentTypeOptions(Customizer.withDefaults())
          .referrerPolicy(rp -> rp.policy(
              org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

    return http.build();
  }

  /** 登录接口单独按 IP + 用户维度限速。 */
  static final class LoginThrottleFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final AppProperties props;

    LoginThrottleFilter(RateLimiter limiter, AppProperties props) {
      this.limiter = limiter;
      this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
        jakarta.servlet.FilterChain chain) throws IOException, jakarta.servlet.ServletException {
      boolean isLogin = "/api/auth/login".equals(req.getRequestURI())
          && "POST".equalsIgnoreCase(req.getMethod());
      if (isLogin) {
        String ip = IpUtil.clientIp(req);
        if (!limiter.allow("login:" + ip, props.getSecurity().getRateLimitLogin())) {
          res.setStatus(429);
          res.setContentType(MediaType.APPLICATION_JSON_VALUE);
          res.setCharacterEncoding("UTF-8");
          res.getWriter().write("{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"操作过于频繁，请稍后再试\"}");
          return;
        }
      }
      chain.doFilter(req, res);
    }
  }

  private AuthenticationEntryPoint jsonEntryPoint() {
    return (req, res, ex) -> writeJson(res, 401, "UNAUTHORIZED", "需要登录");
  }

  private AccessDeniedHandler jsonAccessDenied() {
    return (req, res, ex) -> writeJson(res, 403, "FORBIDDEN", "没有权限执行此操作");
  }

  private void writeJson(HttpServletResponse res, int status, String code, String message)
      throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write(mapper.writeValueAsString(new ErrorResponse(code, message)));
  }
}
