package com.beaconclimb.api.identity;

import com.beaconclimb.api.platform.logging.LogFields;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorResponseWriter;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import com.beaconclimb.api.platform.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Plain servlet filter (no Spring Security) that resolves the caller from {@code Authorization:
 * Bearer <token>}: sha256(token) must match an unrevoked, unexpired session of an ACTIVE user.
 * Everything except the public routes below requires it; a missing, malformed, forged, expired or
 * revoked token gets a 401 UNAUTHENTICATED envelope (TM-04, TM-21). The token and header are never
 * logged (TM-16). Runs after {@link RequestIdFilter} so the 401 carries {@code X-Request-Id}, and
 * after {@code PathNormalizationFilter}, so the path it matches is the canonical one Spring routes
 * on ({@link RequestPaths}; BC-0123 F2: no {@code /v1;x/} or {@code /%761/} disguise). Once
 * resolved, the pseudonymous user id goes into the MDC ({@code user_id}) for every log line of the
 * request and into a request attribute for the access log and the rate limiter (BC-0122).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(BearerAuthenticationFilter.class);
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String API_PREFIX = "/v1/";
  private static final Set<String> PUBLIC_GET = Set.of("/v1/consent/current");
  private static final Set<String> PUBLIC_POST = Set.of("/v1/auth/register", "/v1/auth/session");

  private final SessionRepository sessions;
  private final Clock clock;
  private final ErrorResponseWriter errors;

  public BearerAuthenticationFilter(
      SessionRepository sessions, Clock clock, ErrorResponseWriter errors) {
    this.sessions = sessions;
    this.clock = clock;
    this.errors = errors;
  }

  /**
   * Everything under {@code /v1/} is authenticated unless listed as public, including paths that do
   * not exist (no route enumeration without a token). Paths outside {@code /v1/} (the health probes
   * and anything unknown) fall through to MVC, which answers 404 for the unknown ones.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = RequestPaths.of(request);
    String method = request.getMethod();
    if (!path.startsWith(API_PREFIX)) {
      return true;
    }
    return ("GET".equals(method) && PUBLIC_GET.contains(path))
        || ("POST".equals(method) && PUBLIC_POST.contains(path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<AuthenticatedUser> user = authenticate(request.getHeader("Authorization"));
    if (user.isEmpty()) {
      reject(request, response);
      return;
    }
    request.setAttribute(AuthenticatedUser.ATTRIBUTE, user.get());
    String userId = user.get().userId().toString();
    request.setAttribute(LogFields.USER_ID_ATTRIBUTE, userId);
    MDC.put(LogFields.USER_ID, userId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(LogFields.USER_ID);
    }
  }

  private Optional<AuthenticatedUser> authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    String token = authorization.substring(BEARER_PREFIX.length()).trim();
    if (!SessionTokens.WELL_FORMED.matcher(token).matches()) {
      return Optional.empty();
    }
    return sessions.findActive(SessionTokens.sha256Hex(token), clock.instant());
  }

  private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
    log.debug("Rejected unauthenticated request request_id={}", RequestIdFilter.current(request));
    errors.write(
        request,
        response,
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED,
        "Missing or invalid bearer token",
        Map.of());
  }
}
