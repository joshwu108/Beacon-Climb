package com.beaconclimb.api.platform.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One INFO access line per request after the chain completes: method, path template when MVC
 * matched one (raw path otherwise; never the query string), status, duration and the user id when
 * authenticated, plus the request id from the MDC. Headers, bodies, tokens and notes are never
 * logged (TM-16, TM-33). Runs right after the request id filter so the duration covers
 * authentication and rate limiting too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLogFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
  private static final long NANOS_PER_MILLI = 1_000_000L;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      logRequest(request, response, (System.nanoTime() - start) / NANOS_PER_MILLI);
    }
  }

  private static void logRequest(
      HttpServletRequest request, HttpServletResponse response, long durationMs) {
    Object userId = request.getAttribute(LogFields.USER_ID_ATTRIBUTE);
    if (userId != null) {
      MDC.put(LogFields.USER_ID, userId.toString());
    }
    try {
      log.atInfo()
          .addKeyValue("method", request.getMethod())
          .addKeyValue("path", path(request))
          .addKeyValue("status", response.getStatus())
          .addKeyValue("duration_ms", durationMs)
          .log(
              "request method={} path={} status={} duration_ms={}",
              request.getMethod(),
              path(request),
              response.getStatus(),
              durationMs);
    } finally {
      MDC.remove(LogFields.USER_ID);
    }
  }

  private static String path(HttpServletRequest request) {
    Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return template != null ? template.toString() : request.getRequestURI();
  }
}
