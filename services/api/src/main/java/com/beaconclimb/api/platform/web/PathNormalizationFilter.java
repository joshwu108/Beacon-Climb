package com.beaconclimb.api.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses disguised request paths with a 404 NOT_FOUND envelope before any other filter looks at
 * the path (BC-0123 F2): path parameters ({@code /v1;x=1/...}), backslashes, empty or dot segments,
 * and percent-encodings that would change the path's structure or spell an unreserved character
 * ({@code /%761/me}). Everything else is stored decoded for {@link RequestPaths#of}, so the
 * authentication and rate-limit filters match exactly the path Spring MVC routes on. Runs after
 * {@link RequestIdFilter} so the 404 carries {@code X-Request-Id}; the rejected path is not logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class PathNormalizationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(PathNormalizationFilter.class);

  private final ErrorResponseWriter errors;

  public PathNormalizationFilter(ErrorResponseWriter errors) {
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<String> canonical = RequestPaths.canonical(request.getRequestURI());
    if (canonical.isEmpty()) {
      log.info("Rejected disguised path request_id={}", RequestIdFilter.current(request));
      errors.write(
          request,
          response,
          HttpStatus.NOT_FOUND,
          ErrorCode.NOT_FOUND,
          "No such resource",
          Map.of());
      return;
    }
    request.setAttribute(RequestPaths.ATTRIBUTE, canonical.get());
    chain.doFilter(request, response);
  }
}
