package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0109-CONCURRENT-20: twenty simultaneous POSTs with one user, key and body over HTTP yield
 * exactly one 201 and nineteen 200s with the same body, one attempts row and one outbox row; the
 * same key under another user is an independent 201.
 */
class AttemptConcurrencyIT extends AttemptsTestSupport {

  private static final int THREADS = 20;

  @Test
  void twentyIdenticalConcurrentPostsCreateOneAttemptAndOneEvent() throws Exception {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    String body =
        json.writeValueAsString(attemptBody(route, Instant.parse("2026-10-14T14:00:00Z")));
    String key = "k-concurrent-" + UUID.randomUUID();
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await(10, TimeUnit.SECONDS);
                  return postAttempt(account.token(), key, body);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<ResponseEntity<String>> responses = new ArrayList<>();
      for (Future<ResponseEntity<String>> future : futures) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }

      long created =
          responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
      long replayed = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count();
      assertThat(created)
          .as("statuses %s", responses.stream().map(ResponseEntity::getStatusCode).toList())
          .isEqualTo(1);
      assertThat(replayed).isEqualTo(THREADS - 1);
      JsonNode first = tree(responses.get(0).getBody());
      for (ResponseEntity<String> response : responses) {
        assertThat(tree(response.getBody())).isEqualTo(first);
      }
      UUID attemptId = UUID.fromString(first.get("attempt_id").asString());
      assertThat(attemptRows(attemptId)).isEqualTo(1);
      assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", account.userId()))
          .isEqualTo(1);
      assertThat(revisionRows(attemptId)).hasSize(1);
      assertThat(outboxRows(attemptId)).hasSize(1);

      Account other = registerWithConsent();
      ResponseEntity<String> independent = postAttempt(other.token(), key, body);
      assertThat(independent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
      assertThat(tree(independent.getBody()).get("attempt_id").asString())
          .isNotEqualTo(attemptId.toString());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentPatchesWithTheSameRevisionApplyExactlyOnce() throws Exception {
    Account account = registerWithConsent();
    String attemptId = createAttempt(account, visibleRouteId());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < 5; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  return patchAttempt(account.token(), attemptId, "\"1\"", "{\"sent\":false}");
                }));
      }
      start.countDown();
      List<HttpStatus> statuses = new ArrayList<>();
      for (Future<ResponseEntity<String>> future : futures) {
        statuses.add(HttpStatus.valueOf(future.get(30, TimeUnit.SECONDS).getStatusCode().value()));
      }
      assertThat(statuses).containsOnly(HttpStatus.OK, HttpStatus.CONFLICT);
      assertThat(statuses.stream().filter(s -> s == HttpStatus.OK).count()).isEqualTo(1);
      UUID id = UUID.fromString(attemptId);
      assertThat(revisionRows(id)).hasSize(2);
      assertThat(outboxRows(id)).hasSize(2);
      assertThat(
              tree(get(ATTEMPTS + "/" + attemptId, account.token()).getBody())
                  .get("revision")
                  .asInt())
          .isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }
}
