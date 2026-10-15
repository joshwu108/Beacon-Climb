package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * BC-0123 F1: forty registrations at once never produce a 500 (the review ran the api out of heap
 * with about twelve), only 201, 409, 429 or 503, and the hasher never ran more than {@code
 * beacon.auth.max-concurrent-hashes} Argon2 hashes at the same time. The public burst is raised so
 * every request reaches the hasher instead of the per-address bucket; the bucket is covered by
 * {@code RateLimitIT}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "beacon.http.public-burst=1000")
class AuthRegistrationConcurrencyIT extends IdentityTestSupport {

  private static final int PARALLEL = 40;

  @Autowired PasswordHasher hasher;
  @Autowired AuthProperties authProperties;

  @Test
  void fortyParallelRegistrationsNeverFailWithFiveHundredAndHashesStayBounded() throws Exception {
    assertThat(authProperties.maxConcurrentHashes()).isEqualTo(3);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
    try {
      List<Future<HttpStatus>> futures = new ArrayList<>();
      for (int i = 0; i < PARALLEL; i++) {
        futures.add(pool.submit(registration(start)));
      }
      start.countDown();
      List<HttpStatus> statuses = new ArrayList<>();
      for (Future<HttpStatus> future : futures) {
        statuses.add(future.get());
      }

      assertThat(statuses)
          .allSatisfy(
              status ->
                  assertThat(status)
                      .isIn(
                          HttpStatus.CREATED,
                          HttpStatus.CONFLICT,
                          HttpStatus.TOO_MANY_REQUESTS,
                          HttpStatus.SERVICE_UNAVAILABLE));
      assertThat(statuses).contains(HttpStatus.CREATED);
      assertThat(hasher.maxObservedConcurrency())
          .isBetween(1, authProperties.maxConcurrentHashes());
    } finally {
      pool.shutdownNow();
    }
  }

  private Callable<HttpStatus> registration(CountDownLatch start) {
    String body = authBody(uniqueUsername(), PASSWORD);
    return () -> {
      start.await();
      ResponseEntity<String> response = post("/v1/auth/register", body, null);
      HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());
      if (status == HttpStatus.SERVICE_UNAVAILABLE) {
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertError(
            response,
            HttpStatus.SERVICE_UNAVAILABLE,
            com.beaconclimb.api.platform.web.ErrorCode.SERVICE_UNAVAILABLE);
      }
      return status;
    };
  }
}
