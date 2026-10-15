package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0107 createSession / deleteSession and the bearer filter: 201 on success, 401
 * INVALID_CREDENTIALS for wrong password and unknown user with the same cost, 429 after ten
 * failures, and 401 UNAUTHENTICATED for missing, forged, malformed, expired, revoked or
 * inactive-user tokens (threat model TM-04, TM-21, TM-22).
 */
class AuthSessionIT extends IdentityTestSupport {

  /** Argon2id at 64 MiB / 3 iterations costs far more than this on any machine. */
  private static final Duration HASHING_FLOOR = Duration.ofMillis(20);

  @Autowired SessionRepository sessions;

  @Test
  void loginReturnsNewSession() {
    String username = uniqueUsername();
    AuthResponse first = register(username);

    ResponseEntity<String> response = post("/v1/auth/session", authBody(username, PASSWORD), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ContractSchemas.assertConforms("AuthResponse", response.getBody());
    AuthResponse second = json.readValue(response.getBody(), AuthResponse.class);
    assertThat(second.token()).matches(SessionTokens.WELL_FORMED).isNotEqualTo(first.token());
    assertThat(get("/v1/me", second.token()).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void wrongPasswordAndUnknownUserShareCodeAndCost() {
    String username = uniqueUsername();
    register(username);

    Instant t0 = Instant.now();
    ResponseEntity<String> wrongPassword =
        post("/v1/auth/session", authBody(username, PASSWORD + "x"), null);
    Instant t1 = Instant.now();
    ResponseEntity<String> unknownUser =
        post("/v1/auth/session", authBody(uniqueUsername(), PASSWORD), null);
    Instant t2 = Instant.now();

    ErrorEnvelope wrong =
        assertError(wrongPassword, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS);
    ErrorEnvelope unknown =
        assertError(unknownUser, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS);
    assertThat(wrong.message()).isEqualTo(unknown.message());
    assertThat(Duration.between(t0, t1)).isGreaterThan(HASHING_FLOOR);
    assertThat(Duration.between(t1, t2)).isGreaterThan(HASHING_FLOOR);
  }

  @Test
  void eleventhFailedLoginWithinWindowIsRateLimitedWithRetryAfter() {
    String username = uniqueUsername();
    register(username);
    for (int attempt = 1; attempt <= 10; attempt++) {
      ResponseEntity<String> failed =
          post("/v1/auth/session", authBody(username, "wrong-password"), null);
      assertThat(failed.getStatusCode())
          .as("attempt %d", attempt)
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    ResponseEntity<String> limited = post("/v1/auth/session", authBody(username, PASSWORD), null);

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    String retryAfter = limited.getHeaders().getFirst("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isBetween(1, 15 * 60);
  }

  @Test
  void missingTokenIsUnauthenticated() {
    assertError(get("/v1/me", null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void forgedAndMalformedTokensAreUnauthenticated() {
    assertError(
        get("/v1/me", SessionTokens.generate()),
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED);
    assertError(get("/v1/me", "not-a-token"), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
    ResponseEntity<String> basic =
        client()
            .get()
            .uri("/v1/me")
            .header("Authorization", "Basic ZGVtbzpkZW1v")
            .retrieve()
            .onStatus(status -> true, (request, response) -> {})
            .toEntity(String.class);
    assertError(basic, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void expiredTokenIsUnauthenticated() {
    AuthResponse auth = register(uniqueUsername());
    UUID userId = userIdOf(auth.token());
    String expiredToken = SessionTokens.generate();
    Instant past = Instant.now().minus(Duration.ofHours(25));
    sessions.insert(
        SessionTokens.sha256Hex(expiredToken), userId, past, past.plus(Duration.ofHours(24)));

    assertError(get("/v1/me", expiredToken), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void logoutRevokesTokenAndRevokedTokenCannotLogOutAgain() {
    AuthResponse auth = register(uniqueUsername());
    assertThat(get("/v1/me", auth.token()).getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> logout = delete("/v1/auth/session", auth.token());
    assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(logout.getHeaders().getFirst("X-Request-Id")).isNotBlank();

    assertError(get("/v1/me", auth.token()), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
    assertError(
        delete("/v1/auth/session", auth.token()),
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void tokenOfNonActiveUserIsUnauthenticated() {
    AuthResponse auth = register(uniqueUsername());
    UUID userId = userIdOf(auth.token());
    jdbc.sql("UPDATE users SET status = 'DELETION_PENDING' WHERE id = :id")
        .param("id", userId)
        .update();

    assertError(get("/v1/me", auth.token()), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void unknownRouteWithValidTokenIsNotFoundEnvelope() {
    AuthResponse auth = register(uniqueUsername());

    assertError(get("/v1/does-not-exist", auth.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertError(delete("/v1/me", auth.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
  }

  @Test
  void unknownRouteWithoutTokenIsUnauthenticated() {
    assertError(
        get("/v1/does-not-exist", null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }
}
