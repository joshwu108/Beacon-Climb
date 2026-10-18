package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.attempts.TestClock;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.HttpProperties;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0123c (F5 residual): with {@code beacon.auth.register-per-ip-per-day=3}, the fourth account
 * one client address creates in a UTC day is 429 RATE_LIMITED with the error envelope and {@code
 * Retry-After} = seconds to midnight UTC, no user row is written, another address still registers,
 * and the cap resets at midnight (application clock). The test peer (127.0.0.1) is listed as a
 * trusted proxy so each test can pick its client address through {@code X-Forwarded-For}, exactly
 * as nginx would present it; the public write bucket keeps its shipped burst of 5 so the cap is
 * shown to work beside it, not instead of it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = {
      "beacon.auth.register-per-ip-per-day=3",
      "beacon.http.trusted-proxies=127.0.0.1/32"
    })
class AuthRegistrationCapIT extends IdentityTestSupport {

  private static final int CAP = 3;
  private static final long SECONDS_PER_DAY = 86_400;

  @Autowired AuthProperties authProperties;
  @Autowired HttpProperties httpProperties;
  @Autowired TestClock testClock;

  @AfterEach
  void releaseClock() {
    testClock.reset();
  }

  @Test
  void fourthAccountFromOneAddressIsRateLimitedWhileAnotherAddressStillRegisters() {
    assertThat(authProperties.registerPerIpPerDay()).isEqualTo(CAP);
    assertThat(httpProperties.publicBurst()).isEqualTo(5);
    String addressA = "203.0.113.10";
    String addressB = "203.0.113.11";

    for (int i = 1; i <= CAP; i++) {
      assertThat(registerFrom(addressA, uniqueUsername()).getStatusCode())
          .as("registration %d from A", i)
          .isEqualTo(HttpStatus.CREATED);
    }
    String refusedUsername = uniqueUsername();
    ResponseEntity<String> limited = registerFrom(addressA, refusedUsername);

    ErrorEnvelope envelope =
        assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    assertThat(envelope.fieldErrors()).isEmpty();
    String retryAfter = limited.getHeaders().getFirst("Retry-After");
    assertThat(retryAfter).isNotNull();
    assertThat(Long.parseLong(retryAfter)).isBetween(1L, SECONDS_PER_DAY);
    assertThat(usersNamed(refusedUsername)).isZero();
    assertThat(creationsFrom(addressA)).isEqualTo(CAP);

    assertThat(registerFrom(addressB, uniqueUsername()).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(creationsFrom(addressB)).isEqualTo(1);
  }

  @Test
  void capCountsPerUtcDayAndRetryAfterIsSecondsToMidnight() {
    String address = "203.0.113.12";
    Instant lateEvening = Instant.parse("2026-10-17T23:59:30Z");
    testClock.fix(lateEvening);

    for (int i = 1; i <= CAP; i++) {
      assertThat(registerFrom(address, uniqueUsername()).getStatusCode())
          .as("registration %d", i)
          .isEqualTo(HttpStatus.CREATED);
    }
    ResponseEntity<String> limited = registerFrom(address, uniqueUsername());
    assertError(limited, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED);
    assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("30");

    testClock.fix(Instant.parse("2026-10-18T00:00:10Z"));
    ResponseEntity<String> nextDay = registerFrom(address, uniqueUsername());

    assertThat(nextDay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(creationsFrom(address)).isEqualTo(CAP + 1);
  }

  private ResponseEntity<String> registerFrom(String clientAddress, String username) {
    return post(
        "/v1/auth/register",
        authBody(username, PASSWORD),
        null,
        Map.of("X-Forwarded-For", clientAddress));
  }

  private int usersNamed(String username) {
    return jdbc.sql("SELECT count(*) FROM users WHERE username = :username")
        .param("username", username)
        .query(Integer.class)
        .single();
  }

  private int creationsFrom(String clientAddress) {
    return jdbc.sql("SELECT count(*) FROM account_creations WHERE client_ip = :ip")
        .param("ip", clientAddress)
        .query(Integer.class)
        .single();
  }
}
