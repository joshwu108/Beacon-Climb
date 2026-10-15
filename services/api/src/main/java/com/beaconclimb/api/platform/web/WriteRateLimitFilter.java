package com.beaconclimb.api.platform.web;

import com.beaconclimb.api.platform.logging.LogFields;
import com.beaconclimb.api.platform.web.RateLimitBuckets.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits every write (POST, PUT, PATCH, DELETE) under {@code /v1/}: by user id once {@code
 * BearerAuthenticationFilter} has resolved one, otherwise (the public register and session POSTs)
 * by client address. A refused request is 429 RATE_LIMITED with {@code Retry-After} in whole
 * seconds rounded up; an allowed one carries {@code X-RateLimit-Remaining}. Reads and CORS
 * preflights are never limited. Runs after authentication so an unauthenticated write to a
 * protected path is a 401 that costs no token, and after {@code PathNormalizationFilter} so the
 * path it matches is the canonical one ({@link RequestPaths}, BC-0123 F2).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class WriteRateLimitFilter extends OncePerRequestFilter {

  public static final String REMAINING_HEADER = "X-RateLimit-Remaining";
  public static final String RETRY_AFTER_HEADER = "Retry-After";
  private static final Logger log = LoggerFactory.getLogger(WriteRateLimitFilter.class);
  private static final String API_PREFIX = "/v1/";
  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

  private final WriteRateLimiter limiter;
  private final ErrorResponseWriter errors;
  private final TrustedProxies trustedProxies;

  public WriteRateLimitFilter(
      WriteRateLimiter limiter, ErrorResponseWriter errors, TrustedProxies trustedProxies) {
    this.limiter = limiter;
    this.errors = errors;
    this.trustedProxies = trustedProxies;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !RequestPaths.of(request).startsWith(API_PREFIX)
        || !WRITE_METHODS.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Object userId = request.getAttribute(LogFields.USER_ID_ATTRIBUTE);
    Decision decision =
        userId != null
            ? limiter.tryUser(userId.toString())
            : limiter.tryClient(ClientAddress.of(request, trustedProxies));
    if (!decision.allowed()) {
      log.info(
          "rate limited scope={} retry_after_s={}",
          userId != null ? "user" : "client",
          decision.retryAfterSeconds());
      errors.write(
          request,
          response,
          HttpStatus.TOO_MANY_REQUESTS,
          ErrorCode.RATE_LIMITED,
          "Too many requests; retry later",
          Map.of(RETRY_AFTER_HEADER, Long.toString(decision.retryAfterSeconds())));
      return;
    }
    response.setHeader(REMAINING_HEADER, Long.toString(decision.remaining()));
    chain.doFilter(request, response);
  }
}
