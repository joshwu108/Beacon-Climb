package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.IdentityTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0122 CORS for apps/web: a preflight from an allowed origin succeeds without a token and
 * without consuming a rate-limit token, carries the allowed methods and headers and no credentials
 * flag; an actual response exposes the headers the client reads; any other origin is refused with a
 * FORBIDDEN envelope and no Access-Control-Allow-Origin.
 */
class CorsIT extends IdentityTestSupport {

  private static final String ALLOWED = "http://localhost:5173";
  private static final String DISALLOWED = "https://evil.example";

  @Test
  void preflightFromAllowedOriginSucceedsWithoutAuthentication() {
    String requested = String.join(",", ApiCorsFilter.ALLOWED_HEADERS).toLowerCase();

    ResponseEntity<String> response = preflight(ALLOWED, "PUT", requested + ",x-not-allowed");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    HttpHeaders headers = response.getHeaders();
    assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
    assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.PUT, HttpMethod.DELETE);
    // The processor echoes the requested subset of the allowed list, never a header outside it.
    assertThat(headers.getAccessControlAllowHeaders())
        .map(String::toLowerCase)
        .containsExactlyInAnyOrderElementsOf(
            ApiCorsFilter.ALLOWED_HEADERS.stream().map(String::toLowerCase).toList());
    assertThat(headers.getAccessControlAllowCredentials()).isFalse();
    assertThat(headers.getFirst(RequestIdFilter.HEADER)).isNotBlank();
  }

  @Test
  void actualResponseExposesTheHeadersTheClientReads() {
    ResponseEntity<String> response =
        client()
            .get()
            .uri("/v1/consent/current")
            .header(HttpHeaders.ORIGIN, "http://127.0.0.1:5173")
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("http://127.0.0.1:5173");
    assertThat(response.getHeaders().getAccessControlExposeHeaders())
        .containsExactlyInAnyOrderElementsOf(ApiCorsFilter.EXPOSED_HEADERS);
  }

  @Test
  void disallowedOriginIsRefusedWithAnEnvelopeAndNoCorsHeaders() {
    ResponseEntity<String> response = preflight(DISALLOWED, "PUT", "content-type");

    assertError(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
  }

  @Test
  void preflightIsNeverRateLimited() {
    for (int i = 0; i < 31; i++) {
      post("/v1/auth/session", "{}", null);
    }
    assertError(
        post("/v1/auth/session", "{}", null), HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);

    ResponseEntity<String> response = preflight(ALLOWED, "POST", "content-type");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ALLOWED);
  }

  private ResponseEntity<String> preflight(String origin, String method, String headers) {
    return client()
        .method(HttpMethod.OPTIONS)
        .uri("/v1/me/preferences")
        .headers(
            h ->
                Map.of(
                        HttpHeaders.ORIGIN, origin,
                        HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method,
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers)
                    .forEach(h::set))
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }
}
