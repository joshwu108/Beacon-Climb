package com.beaconclimb.api.identity;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * BC-0123 F1: forty registrations at once never produce a 500 (the review ran the api out of heap
 * with about twelve), only 201, 429 or 503, and the hasher never ran more than {@code
 * beacon.auth.max-concurrent-hashes} Argon2 hashes at the same time. Usernames are 48 random bits
 * each, so a 409 cannot occur. The public burst is raised so every request reaches the hasher
 * instead of the per-address bucket; the bucket is covered by {@code RateLimitIT}. Every wait is
 * bounded (request, future and test) so a broken hash gate fails the test instead of hanging it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "beacon.http.public-burst=1000")
class AuthRegistrationConcurrencyIT extends IdentityTestSupport {

  private static final int PARALLEL = 40;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
  private static final long FUTURE_TIMEOUT_SECONDS = 90;

  @Autowired PasswordHasher hasher;
  @Autowired AuthProperties authProperties;

  private final JdkClientHttpRequestFactory requestFactory = timedRequestFactory();

  @Test
  @Timeout(value = 120, unit = SECONDS)
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
        statuses.add(future.get(FUTURE_TIMEOUT_SECONDS, SECONDS));
      }

      assertThat(statuses)
          .allSatisfy(
              status ->
                  assertThat(status)
                      .isIn(
                          HttpStatus.CREATED,
                          HttpStatus.TOO_MANY_REQUESTS,
                          HttpStatus.SERVICE_UNAVAILABLE));
      assertThat(statuses).contains(HttpStatus.CREATED);
      assertThat(hasher.maxObservedConcurrency())
          .isBetween(1, authProperties.maxConcurrentHashes());
    } finally {
      pool.shutdownNow();
    }
  }

  /** The shared helpers use this client, so every request in this test carries both timeouts. */
  @Override
  protected RestClient client() {
    return RestClient.builder()
        .baseUrl("http://127.0.0.1:" + port)
        .requestFactory(requestFactory)
        .build();
  }

  private static JdkClientHttpRequestFactory timedRequestFactory() {
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(READ_TIMEOUT);
    return factory;
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
