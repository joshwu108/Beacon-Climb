package com.beaconclimb.api.platform.web;

import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * CORS for the web client (apps/web README): exact origins from {@code
 * beacon.http.cors-allowed-origins}, the request headers the client sends, the response headers it
 * reads, no credentials. Runs right after the request id filter so a preflight is answered before
 * authentication and rate limiting; a request from any other origin is refused with a 403 FORBIDDEN
 * envelope instead of Spring's plain-text body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ApiCorsFilter extends CorsFilter {

  static final List<String> ALLOWED_METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  static final List<String> ALLOWED_HEADERS =
      List.of(
          "Authorization",
          "Content-Type",
          "X-Request-Id",
          "Idempotency-Key",
          "If-Match",
          "If-None-Match");
  static final List<String> EXPOSED_HEADERS =
      List.of("X-Request-Id", "Retry-After", "ETag", "X-RateLimit-Remaining");
  private static final long MAX_AGE_SECONDS = 600;

  public ApiCorsFilter(HttpProperties properties, JsonMapper json) {
    super(source(properties));
    setCorsProcessor(new EnvelopeCorsProcessor(json));
  }

  private static UrlBasedCorsConfigurationSource source(HttpProperties properties) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(properties.corsAllowedOrigins());
    config.setAllowedMethods(ALLOWED_METHODS);
    config.setAllowedHeaders(ALLOWED_HEADERS);
    config.setExposedHeaders(EXPOSED_HEADERS);
    config.setAllowCredentials(false);
    config.setMaxAge(MAX_AGE_SECONDS);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  /** Same decisions as the default processor; only the rejection body is the contract envelope. */
  static final class EnvelopeCorsProcessor extends DefaultCorsProcessor {

    private final JsonMapper json;

    EnvelopeCorsProcessor(JsonMapper json) {
      this.json = json;
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
      String requestId = MDC.get(RequestIdFilter.MDC_KEY);
      ErrorEnvelope body =
          ErrorEnvelope.of(
              ErrorCode.FORBIDDEN,
              "Origin not allowed",
              requestId == null ? "unknown" : requestId,
              List.of());
      response.setStatusCode(HttpStatus.FORBIDDEN);
      response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      response.getBody().write(json.writeValueAsBytes(body));
      response.flush();
    }
  }
}
