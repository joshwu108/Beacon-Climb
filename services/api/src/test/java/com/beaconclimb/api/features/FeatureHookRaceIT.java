package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.features.FeatureAggregates;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BC-0124 HIGH-2 "concurrent hook race" (BC-0124c): several attempt writes of one user go through
 * the real HTTP path at the same moment, so {@link FeatureRecomputeHook} runs concurrently in the
 * request threads. After every round the live SYNC snapshot must sit at the newest of the hooks'
 * cutoffs (the monotonic upsert rule) and hold exactly what a recompute at that cutoff yields,
 * because the hook that took the newest cutoff did so after every other write of the round had
 * committed. Ten rounds give the interleavings a real chance.
 *
 * <p>The application clock ticks strictly (see {@link
 * com.beaconclimb.api.attempts.TestClock#tick()}), so the hooks' cutoffs are distinct. Two hooks
 * landing on the very same millisecond, where the rule cannot order them, remain the documented
 * residual gap of the night handoff and are not claimed here.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
      ApiApplication.class,
      AttemptsTestSupport.TestClockConfig.class,
      FeatureHookRaceIT.RecordingConfig.class
    },
    properties = {"beacon.http.write-limit-per-minute=1000", "beacon.http.public-burst=1000"})
class FeatureHookRaceIT extends AttemptsTestSupport {

  private static final int ROUNDS = 10;
  private static final int WRITERS = 3;

  /** The real recompute service, remembering every call so the test can see the hooks' cutoffs. */
  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingConfig {
    @Bean
    @Primary
    RecordingFeatures recordingFeatures(
        JdbcClient jdbc, FeatureSnapshotRepository snapshots, TransactionTemplate tx, Clock clock) {
      return new RecordingFeatures(jdbc, snapshots, tx, clock);
    }
  }

  record Recompute(UUID userId, Instant cutoffW, boolean stored) {}

  static class RecordingFeatures extends FeatureRecomputeService {
    final List<Recompute> recomputes = new CopyOnWriteArrayList<>();

    RecordingFeatures(
        JdbcClient jdbc, FeatureSnapshotRepository snapshots, TransactionTemplate tx, Clock clock) {
      super(jdbc, snapshots, tx, clock);
    }

    @Override
    public Outcome recompute(UUID userId, Instant cutoffW) {
      Outcome outcome = super.recompute(userId, cutoffW);
      recomputes.add(new Recompute(userId, cutoffW, outcome.stored()));
      return outcome;
    }
  }

  @Autowired RecordingFeatures features;
  @Autowired FeatureSnapshotRepository snapshots;

  @Test
  void concurrentAttemptWritesLeaveTheSnapshotAtTheNewestHookCutoff() throws Exception {
    testClock.tick();
    Account account = registerWithConsent();
    List<String> routes = visibleRouteIds(ROUNDS * WRITERS);
    assertThat(routes).hasSize(ROUNDS * WRITERS);
    ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
    Instant previousMax = Instant.EPOCH;
    try {
      for (int round = 0; round < ROUNDS; round++) {
        int from = features.recomputes.size();
        List<Future<ResponseEntity<String>>> futures = race(pool, account, routes, round);
        for (Future<ResponseEntity<String>> future : futures) {
          ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
          assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        }

        List<Recompute> hooks = new ArrayList<>(features.recomputes).subList(from, from + WRITERS);
        assertThat(features.recomputes).hasSize(from + WRITERS);
        assertThat(hooks).allSatisfy(hook -> assertThat(hook.userId()).isEqualTo(account.userId()));
        assertThat(hooks).extracting(Recompute::cutoffW).doesNotHaveDuplicates();
        Instant maxW =
            hooks.stream().map(Recompute::cutoffW).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(maxW).as("round %d", round).isAfter(previousMax);
        assertThat(hooks)
            .filteredOn(hook -> hook.cutoffW().equals(maxW))
            .singleElement()
            .extracting(Recompute::stored)
            .isEqualTo(true);

        FeatureSnapshot stored =
            snapshots.find(account.userId(), FeatureAggregates.SCHEMA_VERSION).orElseThrow();
        assertThat(stored.cutoffW()).as("round %d", round).isEqualTo(maxW);
        assertThat(stored.source()).isEqualTo(FeatureSource.SYNC);
        FeatureSnapshot expected = features.recompute(account.userId(), maxW).snapshot();
        assertThat(stored.aggregates().toJson())
            .as("round %d", round)
            .isEqualTo(expected.aggregates().toJson());
        assertThat(stored.provenanceChecksum()).isEqualTo(expected.provenanceChecksum());
        assertThat(stored.featureAsOf()).isEqualTo(expected.featureAsOf());
        int written = (round + 1) * WRITERS;
        assertThat(stored.aggregates().attempts90d()).isEqualTo(written);
        assertThat(stored.aggregates().recentVariantIds())
            .hasSize(Math.min(written, FeatureAggregates.RECENT_VARIANTS_LIMIT));
        previousMax = maxW;
      }
    } finally {
      pool.shutdownNow();
    }
  }

  /** {@code WRITERS} distinct attempts for the user, released by one latch so they overlap. */
  private List<Future<ResponseEntity<String>>> race(
      ExecutorService pool, Account account, List<String> routes, int round) throws Exception {
    CountDownLatch ready = new CountDownLatch(WRITERS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
    for (int i = 0; i < WRITERS; i++) {
      int index = round * WRITERS + i;
      Map<String, Object> body =
          attemptBody(routes.get(index), Instant.now().minus(Duration.ofHours(1L + index)));
      body.put("sent", i % 2 == 0);
      String json = this.json.writeValueAsString(body);
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return postAttempt(account.token(), UUID.randomUUID().toString(), json);
              }));
    }
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    return futures;
  }
}
