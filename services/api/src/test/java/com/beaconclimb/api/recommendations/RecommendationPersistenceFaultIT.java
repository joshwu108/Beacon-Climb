package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.attempts.AttemptsTestSupport;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.ranking.Ranked;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0124 items 3 to 5: the write path of {@link RecommendationService#recommend} under faults. The
 * snapshot store, the ledger and the contract schema are replaced by switchable doubles in one
 * shared context: a real {@link LocalFsSnapshotStore} behind a delegate that can be swapped for a
 * full one or made to return a wrong digest, a {@link DecisionLedgerRepository} subclass that can
 * fail on {@code insertExposures}, and a {@link DecisionRecordSchema} subclass that can reject the
 * snapshot or the record. In every fault the user ends with no decision and no exposure rows, and
 * no snapshot file this request created survives.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
      ApiApplication.class,
      AttemptsTestSupport.TestClockConfig.class,
      RecommendationPersistenceFaultIT.FaultConfig.class
    },
    properties = {"beacon.http.write-limit-per-minute=1000", "beacon.http.public-burst=1000"})
class RecommendationPersistenceFaultIT extends RecommendationsTestSupport {

  private static final Instant T = Instant.parse("2026-10-14T14:00:00Z");
  private static final String ZERO_DIGEST = "0".repeat(64);

  /** Wires the two doubles as the primary beans of their types. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FaultConfig {
    @Bean
    @Primary
    SwitchableStore switchableStore(DecisionsProperties properties) {
      return new SwitchableStore(
          new LocalFsSnapshotStore(Path.of(properties.dir()), properties.maxFiles()));
    }

    @Bean
    @Primary
    FaultyLedger faultyLedger(JdbcClient jdbc, JsonMapper json) {
      return new FaultyLedger(jdbc, json);
    }

    @Bean
    @Primary
    FaultySchema faultySchema() {
      return new FaultySchema();
    }
  }

  /**
   * The real schema whose {@code requireValidSnapshot} or {@code requireValidRecord} can be made to
   * throw the {@link IllegalStateException} a contract violation raises. It also notes the digest
   * the validated snapshot would be stored under, so a test can prove nothing was written there
   * even when the request never reached the store.
   */
  static class FaultySchema extends DecisionRecordSchema {
    enum Mode {
      NONE,
      SNAPSHOT,
      RECORD
    }

    private volatile Mode mode = Mode.NONE;
    volatile String lastSnapshotDigest;

    void mode(Mode mode) {
      this.mode = mode;
    }

    void reset() {
      mode = Mode.NONE;
      lastSnapshotDigest = null;
    }

    @Override
    public void requireValidSnapshot(JsonNode snapshot) {
      lastSnapshotDigest =
          Jcs.sha256Hex(Jcs.canonicalize(snapshot).getBytes(StandardCharsets.UTF_8));
      if (mode == Mode.SNAPSHOT) {
        throw new IllegalStateException("injected snapshot validation failure");
      }
      super.requireValidSnapshot(snapshot);
    }

    @Override
    public void requireValidRecord(JsonNode record) {
      if (mode == Mode.RECORD) {
        throw new IllegalStateException("injected record validation failure");
      }
      super.requireValidRecord(record);
    }
  }

  /** Delegates to a real store; {@code corruptDigest} answers a wrong digest and stores nothing. */
  static final class SwitchableStore implements SnapshotStore {
    private final SnapshotStore normal;
    volatile SnapshotStore delegate;
    volatile boolean corruptDigest;
    volatile String lastPutDigest;

    SwitchableStore(SnapshotStore normal) {
      this.normal = normal;
      this.delegate = normal;
    }

    void reset() {
      delegate = normal;
      corruptDigest = false;
      lastPutDigest = null;
    }

    @Override
    public String put(byte[] canonicalBytes) {
      lastPutDigest = Jcs.sha256Hex(canonicalBytes);
      if (corruptDigest) {
        return ZERO_DIGEST;
      }
      return delegate.put(canonicalBytes);
    }

    @Override
    public Optional<byte[]> get(String digest) {
      return delegate.get(digest);
    }

    @Override
    public boolean exists(String digest) {
      return delegate.exists(digest);
    }

    @Override
    public void delete(String digest) {
      delegate.delete(digest);
    }
  }

  /**
   * The real ledger whose exposure insert can be made to fail inside the transaction. Not final and
   * switched through a method: {@code @Repository} beans are CGLIB-proxied for exception
   * translation, so a field written on the injected proxy would never reach the target.
   */
  static class FaultyLedger extends DecisionLedgerRepository {
    private volatile boolean failExposures;

    FaultyLedger(JdbcClient jdbc, JsonMapper json) {
      super(jdbc, json);
    }

    void failExposures(boolean fail) {
      failExposures = fail;
    }

    @Override
    public void insertExposures(UUID decisionId, List<Ranked> ranked) {
      if (failExposures) {
        throw new IllegalStateException("injected ledger failure");
      }
      super.insertExposures(decisionId, ranked);
    }
  }

  @Autowired SwitchableStore switchableStore;
  @Autowired FaultyLedger ledger;
  @Autowired FaultySchema faultySchema;

  @BeforeEach
  void pinClock() {
    testClock.fix(T);
  }

  @AfterEach
  void resetFaults() {
    switchableStore.reset();
    ledger.failExposures(false);
    faultySchema.reset();
  }

  @Test
  void storeQuotaReachedIs503AndWritesNothing() {
    Path quotaDir = tempDir("decisions-quota");
    try {
      LocalFsSnapshotStore full = new LocalFsSnapshotStore(quotaDir, 1);
      full.put("{}".getBytes(StandardCharsets.UTF_8));
      assertThat(full.fileCount()).isEqualTo(1);
      switchableStore.delegate = full;
      Account account = registerReady("INTERMEDIATE");

      ResponseEntity<String> response =
          recommend(
              account.token(), recommendationBody("TECHNIQUE", 3, UUID.randomUUID(), List.of()));

      assertError(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
      assertThat(decisionsOf(account.userId())).isZero();
      assertThat(exposuresOf(account.userId())).isZero();
      assertThat(jsonFilesUnder(quotaDir)).isEqualTo(1);
      assertThat(full.exists(switchableStore.lastPutDigest)).isFalse();
    } finally {
      deleteRecursively(quotaDir);
    }
  }

  @Test
  void snapshotValidationFailureIs500AndWritesNothing() {
    faultySchema.mode(FaultySchema.Mode.SNAPSHOT);
    Account account = registerReady("INTERMEDIATE");
    long filesBefore = jsonFilesUnder(DECISIONS_DIR);

    ResponseEntity<String> response =
        recommend(account.token(), recommendationBody("STRENGTH", 3, UUID.randomUUID(), List.of()));

    assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    String digest = faultySchema.lastSnapshotDigest;
    assertThat(digest).matches("^[0-9a-f]{64}$");
    assertThat(switchableStore.lastPutDigest).as("store never called").isNull();
    assertThat(store.exists(digest)).isFalse();
    assertThat(Files.exists(snapshotPath(digest))).isFalse();
    assertThat(jsonFilesUnder(DECISIONS_DIR)).isEqualTo(filesBefore);
    assertThat(decisionsOf(account.userId())).isZero();
    assertThat(exposuresOf(account.userId())).isZero();
  }

  @Test
  void recordValidationFailureIs500AndLeavesNoSnapshotFile() {
    faultySchema.mode(FaultySchema.Mode.RECORD);
    Account account = registerReady("ADVANCED");
    long filesBefore = jsonFilesUnder(DECISIONS_DIR);

    ResponseEntity<String> response =
        recommend(
            account.token(), recommendationBody("ENDURANCE", 3, UUID.randomUUID(), List.of()));

    assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    String digest = faultySchema.lastSnapshotDigest;
    assertThat(digest).matches("^[0-9a-f]{64}$");
    assertThat(store.exists(digest)).isFalse();
    assertThat(Files.exists(snapshotPath(digest))).isFalse();
    assertThat(jsonFilesUnder(DECISIONS_DIR)).isEqualTo(filesBefore);
    assertThat(decisionsOf(account.userId())).isZero();
    assertThat(exposuresOf(account.userId())).isZero();
  }

  @Test
  void storeReturningAnotherDigestIs500AndNoLedgerRowIsWritten() {
    switchableStore.corruptDigest = true;
    Account account = registerReady("ADVANCED");

    ResponseEntity<String> response =
        recommend(account.token(), recommendationBody("STRENGTH", 4, UUID.randomUUID(), List.of()));

    assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    assertThat(decisionsOf(account.userId())).isZero();
    assertThat(exposuresOf(account.userId())).isZero();
    assertThat(switchableStore.lastPutDigest).isNotEqualTo(ZERO_DIGEST);
    assertThat(store.exists(switchableStore.lastPutDigest)).isFalse();
  }

  @Test
  void ledgerFailureDeletesTheSnapshotFileThisRequestCreated() {
    ledger.failExposures(true);
    Account account = registerReady("BEGINNER");

    ResponseEntity<String> response =
        recommend(
            account.token(), recommendationBody("ENDURANCE", 3, UUID.randomUUID(), List.of()));

    assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    String digest = switchableStore.lastPutDigest;
    assertThat(digest).matches("^[0-9a-f]{64}$");
    assertThat(store.exists(digest)).isFalse();
    assertThat(
            Files.exists(DECISIONS_DIR.resolve(digest.substring(0, 2)).resolve(digest + ".json")))
        .isFalse();
    assertThat(decisionsOf(account.userId())).isZero();
    assertThat(exposuresOf(account.userId())).isZero();
  }

  @Test
  void ledgerFailureKeepsAPreExistingIdenticalSnapshotFile() {
    Account account = registerReady("NOVICE");
    Map<String, Object> body = recommendationBody("PROJECT", 3, UUID.randomUUID(), List.of());
    JsonNode first = recommendOk(account.token(), body);
    String digest =
        (String)
            decisionRow(UUID.fromString(first.get("decision_id").asString()))
                .get("candidate_set_digest");
    assertThat(store.exists(digest)).isTrue();
    ledger.failExposures(true);

    ResponseEntity<String> response = recommend(account.token(), body);

    assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    assertThat(switchableStore.lastPutDigest).isEqualTo(digest);
    assertThat(store.exists(digest)).isTrue();
    assertThat(snapshotFile(digest)).isNotEmpty();
    assertThat(decisionsOf(account.userId())).isEqualTo(1);
  }

  private int decisionsOf(UUID userId) {
    return count("SELECT count(*) FROM recommendation_decisions WHERE user_id = :id", userId);
  }

  private int exposuresOf(UUID userId) {
    return count(
        "SELECT count(*) FROM recommendation_exposures e JOIN recommendation_decisions d"
            + " ON d.decision_id = e.decision_id WHERE d.user_id = :id",
        userId);
  }

  private static long jsonFilesUnder(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).count();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path snapshotPath(String digest) {
    return DECISIONS_DIR.resolve(digest.substring(0, 2)).resolve(digest + ".json");
  }

  private static Path tempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path root) {
    try (Stream<Path> paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(RecommendationPersistenceFaultIT::deleteQuietly);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
