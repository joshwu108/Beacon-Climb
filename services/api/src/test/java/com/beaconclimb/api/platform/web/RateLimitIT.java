package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.identity.IdentityTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0122 / ADR-0005 acceptance at the shipped defaults: the 61st write within a minute by one user
 * is 429 RATE_LIMITED with Retry-After, the 60th carried X-RateLimit-Remaining: 0, another user is
 * unaffected; unauthenticated public POSTs are limited per client address by a bucket of {@code
 * public-burst} (5) tokens refilling at 30/min (BC-0123 F1: the sixth immediate request is 429, no
 * longer the 31st, because each public POST costs an Argon2 hash), and reads never are.
 */
class RateLimitIT extends IdentityTestSupport {

  private static final String PATH = "/v1/me/preferences";

  @Autowired CatalogImporter importer;
  @Autowired HttpProperties properties;

  @BeforeEach
  void seedDemoBoard() {
    CatalogSeed.seed(importer, jdbc);
  }

  @Test
  void sixtyFirstWriteInAMinuteIsRateLimitedPerUser() {
    assertThat(properties.writeLimitPerMinute()).isEqualTo(60);
    String tokenA = register(uniqueUsername()).token();
    String tokenB = register(uniqueUsername()).token();
    String body = preferences();

    ResponseEntity<String> last = null;
    for (int i = 1; i <= 60; i++) {
      last = put(PATH, body, tokenA);
      assertThat(last.getStatusCode()).as("request %d", i).isEqualTo(HttpStatus.OK);
      assertThat(last.getHeaders().getFirst(WriteRateLimitFilter.REMAINING_HEADER))
          .as("remaining after request %d", i)
          .isEqualTo(String.valueOf(60 - i));
    }
    ResponseEntity<String> limited = put(PATH, body, tokenA);

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    String retryAfter = limited.getHeaders().getFirst(WriteRateLimitFilter.RETRY_AFTER_HEADER);
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
    assertThat(limited.getHeaders().getFirst(WriteRateLimitFilter.REMAINING_HEADER)).isNull();

    ResponseEntity<String> other = put(PATH, body, tokenB);
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(other.getHeaders().getFirst(WriteRateLimitFilter.REMAINING_HEADER)).isEqualTo("59");

    ResponseEntity<String> read = get("/v1/me", tokenA);
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(read.getHeaders().getFirst(WriteRateLimitFilter.REMAINING_HEADER)).isNull();
  }

  /**
   * The public bucket starts with {@code public-burst} tokens, so the sixth immediate request is
   * refused; the 30/min figure is the refill rate (one token every two seconds), which is why
   * Retry-After is at most 2 here.
   */
  @Test
  void publicPostsAreLimitedPerClientAddressWithASmallBurst() {
    assertThat(properties.publicWriteLimitPerMinute()).isEqualTo(30);
    assertThat(properties.publicBurst()).isEqualTo(5);

    for (int i = 1; i <= 5; i++) {
      ResponseEntity<String> response = post("/v1/auth/session", "{}", null);
      assertThat(response.getStatusCode())
          .as("request %d", i)
          .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
      assertThat(response.getHeaders().getFirst(WriteRateLimitFilter.REMAINING_HEADER))
          .isEqualTo(String.valueOf(5 - i));
    }
    ResponseEntity<String> limited = post("/v1/auth/register", "{}", null);

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    String retryAfter = limited.getHeaders().getFirst(WriteRateLimitFilter.RETRY_AFTER_HEADER);
    assertThat(retryAfter).isNotNull();
    assertThat(Integer.parseInt(retryAfter)).isBetween(1, 2);
    assertThat(get("/v1/consent/current", null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void forwardedForIsIgnoredWithoutTrustedProxies() {
    assertThat(properties.trustedProxies()).isEmpty();
    for (int i = 1; i <= 5; i++) {
      post("/v1/auth/session", "{}", null, Map.of(ClientAddress.FORWARDED_FOR, "10.0.0." + i));
    }

    ResponseEntity<String> limited =
        post("/v1/auth/session", "{}", null, Map.of(ClientAddress.FORWARDED_FOR, "10.0.0.99"));

    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
  }

  private String preferences() {
    return json.writeValueAsString(
        Map.of(
            "board_id",
            CatalogSeed.BOARD_ID,
            "angle_deg",
            40,
            "initial_grade_band",
            "BEGINNER",
            "goals",
            List.of("TECHNIQUE")));
  }
}
