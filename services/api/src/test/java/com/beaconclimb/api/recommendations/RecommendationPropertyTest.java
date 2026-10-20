package com.beaconclimb.api.recommendations;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.catalog.importer.CatalogRepository;
import com.beaconclimb.api.catalog.importer.CatalogSchema;
import com.beaconclimb.api.features.FeatureRecomputeService;
import com.beaconclimb.api.features.FeatureSnapshotRepository;
import com.beaconclimb.api.identity.ConsentDocumentService;
import com.beaconclimb.api.identity.ConsentGuard;
import com.beaconclimb.api.identity.ConsentRepository;
import com.beaconclimb.api.identity.Uuid7;
import com.beaconclimb.api.identity.api.ConsentDocument;
import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import com.beaconclimb.api.identity.preferences.PreferencesRepository;
import com.beaconclimb.api.identity.preferences.PreferencesService;
import com.beaconclimb.api.identity.preferences.PreferencesView;
import com.beaconclimb.api.modeloutputs.ModelOutputRepository;
import com.beaconclimb.api.ranking.PolicyLoader;
import com.beaconclimb.api.ranking.PolicyRegistry;
import com.beaconclimb.api.ranking.ReasonCode;
import com.beaconclimb.api.recommendations.api.RecommendationResponse;
import com.beaconclimb.api.recommendations.api.RecommendationView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0111 "no impossible route id": for a random goal, {@code max_routes}, band and exclusion set
 * the real service (wired by hand against the shared Postgres and a temporary snapshot store) only
 * serves ids that are visible ACTIVE variants of the requested board and angle, never an excluded
 * one, never a duplicate, at consecutive positions and never more than asked for.
 *
 * <p>BC-0124 MEDIUM-8 (BC-0124c): the second property seeds warm users, 1 to 50 ACTIVE attempts
 * over the last 120 days, sent and failed, across every angle of the board, recomputes their
 * climber-features.v1 snapshot as the attempts hook does, and asserts the ranking the service
 * serves is exactly what the policy documents (via {@link ReferenceHeuristic}, independent of the
 * production ranker): {@code cold_start} false and {@code feature_as_of} the newest attempt;
 * candidates attempted within {@code cooldown_hours} are withheld unless the goal is PROJECT or the
 * low-inventory widening applies; novelty is 0 for the user's twenty most recent variants; the
 * target grade is {@code band_targets[band] + goal_offsets[goal]}; every served score bit,
 * position, reason-code list and the sha256 tie order match; and the same session id reproduces the
 * same ids and score bits.
 */
class RecommendationPropertyTest {

  private static final Instant NOW = Instant.parse("2026-10-14T14:00:00Z");
  private static final int ANGLE = 40;
  private static final int MINUTES_IN_120_DAYS = 120 * 24 * 60;

  /** A candidate of the ranked angle as the catalog describes it. */
  private record PoolRow(double grade, int observations, List<String> tags) {}

  /** One generated attempt: which angle's pool, which variant of it, sent or not, how long ago. */
  record AttemptSpec(int angle, int routeCode, boolean sent, int minutesAgo) {}

  /** One seeded attempt as it reached the table. */
  private record SeededAttempt(
      String routeVariantId, int angle, Instant occurredAt, boolean sent) {}

  private static RecommendationService service;
  private static FeatureRecomputeService features;
  private static JdbcClient jdbc;
  private static JsonMapper json;
  private static Clock clock;
  private static PreferencesService preferences;
  private static List<String> candidates;
  private static Set<String> visible;
  private static Map<String, PoolRow> pool;
  private static List<String> poolIds;
  private static Map<Integer, List<String>> attemptableByAngle;
  private static Map<String, UUID> variantIds;
  private static ReferenceHeuristic.Config config;

  @BeforeContainer
  static void wire() throws IOException {
    String url = AbstractPostgresIT.POSTGRES.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, "beacon_migrate", AbstractPostgresIT.MIGRATE_PASSWORD)
        .load()
        .migrate();
    DataSource app =
        new DriverManagerDataSource(url, "beacon_app", AbstractPostgresIT.APP_PASSWORD);
    jdbc = JdbcClient.create(app);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(app));
    clock = Clock.fixed(NOW, ZoneOffset.UTC);
    json = JsonMapper.builder().build();
    CatalogSeed.seed(
        new CatalogImporter(new CatalogSchema(), new CatalogRepository(jdbc), tx, clock), jdbc);
    // Pinned to heuristic-v1: the property compares against ReferenceHeuristic (v1).
    PolicyRegistry registry =
        new PolicyRegistry(new PolicyLoader(), jdbc, tx, clock, "heuristic-v1");
    registry.register();
    FeatureSnapshotRepository snapshots = new FeatureSnapshotRepository(jdbc);
    preferences = new PreferencesService(new PreferencesRepository(jdbc, json), clock);
    DecisionLedgerRepository ledger = new DecisionLedgerRepository(jdbc, json);
    features = new FeatureRecomputeService(jdbc, snapshots, tx, clock);
    service =
        new RecommendationService(
            new ConsentGuard(new ConsentRepository(jdbc), new ConsentDocumentService()),
            new DecisionQuota(ledger, new DecisionsProperties("unused", 100000, 100000)),
            preferences,
            registry,
            snapshots,
            features,
            new CandidateRepository(jdbc, json),
            new LocalFsSnapshotStore(Files.createTempDirectory("decisions-property"), 100000),
            new DecisionRecordSchema(),
            ledger,
            tx,
            clock,
            false,
            new ModelOutputRepository(jdbc));
    visible =
        new HashSet<>(
            jdbc.sql(
                    "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                        + " JOIN routes r ON r.route_id = v.route_id"
                        + " WHERE v.validation_status = 'ACTIVE' AND r.board_id = :board"
                        + " AND v.angle_deg = :angle"
                        + " AND (r.active_from IS NULL OR r.active_from <= :now)"
                        + " AND (r.active_to IS NULL OR r.active_to > :now)")
                .param("board", CatalogSeed.BOARD_ID)
                .param("angle", ANGLE)
                .param("now", NOW.atOffset(ZoneOffset.UTC))
                .query(String.class)
                .list());
    candidates = new ArrayList<>(visible);
    candidates.sort(String::compareTo);
    loadPool();
    loadAttemptable();
    config = ReferenceHeuristic.Config.fromJson(json.readTree(policyResource()));
  }

  @Property(tries = 200)
  void servedIdsAreVisibleActiveVariantsOfTheBoardAndAngleAndNeverExcluded(
      @ForAll Goal goal,
      @ForAll GradeBand band,
      @ForAll @IntRange(min = 1, max = 20) int maxRoutes,
      @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 999) Integer> excludeCodes,
      @ForAll("sessions") UUID sessionId) {
    UUID userId = seedReadyUser(band);
    List<String> exclude = excludeList(excludeCodes);

    RecommendationResponse response =
        service.recommend(userId, request(goal, maxRoutes, sessionId, exclude));

    List<String> served =
        response.recommendations().stream().map(RecommendationView::routeVariantId).toList();
    assertThat(served).hasSizeLessThanOrEqualTo(maxRoutes).doesNotHaveDuplicates();
    assertThat(served).allSatisfy(id -> assertThat(visible).contains(id));
    assertThat(served).allSatisfy(id -> assertThat(exclude).doesNotContain(id));
    assertThat(served)
        .hasSize(
            Math.min(
                maxRoutes, candidates.size() - new HashSet<>(exclude).size() + ghosts(exclude)));
    for (int i = 0; i < response.recommendations().size(); i++) {
      RecommendationView item = response.recommendations().get(i);
      assertThat(item.position()).isEqualTo(i + 1);
      assertThat(item.route().routeVariantId()).isEqualTo(item.routeVariantId());
      assertThat(item.route().boardId()).isEqualTo(CatalogSeed.BOARD_ID);
      assertThat(item.route().angleDeg()).isEqualTo(ANGLE);
    }
    assertThat(response.coldStart()).isTrue();
    assertThat(response.featureAsOf()).isEqualTo(NOW.toString());
  }

  @Property(tries = 200)
  void warmUsersAreRankedExactlyAsThePolicyDocumentsFromTheirAttemptHistory(
      @ForAll Goal goal,
      @ForAll GradeBand band,
      @ForAll @IntRange(min = 1, max = 20) int maxRoutes,
      @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 999) Integer> excludeCodes,
      @ForAll("sessions") UUID sessionId,
      @ForAll("histories") List<AttemptSpec> history) {
    UUID userId = seedReadyUser(band);
    List<SeededAttempt> attempts = seedHistory(userId, history);
    features.recompute(userId, NOW);
    List<String> exclude = excludeList(excludeCodes);
    RecommendationRequest request = request(goal, maxRoutes, sessionId, exclude);

    RecommendationResponse response = service.recommend(userId, request);
    RecommendationResponse again = service.recommend(userId, request);

    // Warm features: at least one ACTIVE attempt, so the user is not cold and feature_as_of is
    // the newest occurred_at included.
    Instant newest =
        attempts.stream()
            .map(SeededAttempt::occurredAt)
            .max(Comparator.naturalOrder())
            .orElseThrow();
    assertThat(response.coldStart()).isFalse();
    assertThat(response.featureAsOf()).isEqualTo(newest.toString());

    // Eligibility: the candidate pool minus the exclusions, minus the cooldown (last attempt on
    // the variant strictly after now - cooldown_hours) unless the goal is PROJECT; the widening
    // re-admits the cooled ones when fewer than low_inventory_min strict survivors remain.
    Set<String> excluded = new HashSet<>(exclude);
    Map<String, Instant> lastAttemptAt = lastAttemptAtRankedAngle(attempts);
    Instant threshold = NOW.minus(Duration.ofHours(config.cooldownHours()));
    List<String> strict = new ArrayList<>();
    List<String> cooledOnly = new ArrayList<>();
    for (String id : poolIds) {
      if (excluded.contains(id)) {
        continue;
      }
      Instant last = lastAttemptAt.get(id);
      boolean inCooldown = last != null && last.isAfter(threshold);
      if (goal != Goal.PROJECT && inCooldown) {
        cooledOnly.add(id);
      } else {
        strict.add(id);
      }
    }
    boolean widened = ReferenceHeuristic.widened(config, strict.size(), cooledOnly.size());
    List<String> eligible = new ArrayList<>();
    for (String id : poolIds) {
      if (strict.contains(id) || (widened && cooledOnly.contains(id))) {
        eligible.add(id);
      }
    }

    // Scoring inputs: novelty from the twenty most recent distinct variants over all angles,
    // preference from the route tags, target from the band and goal, confidence from min_obs.
    List<String> recent = recentVariantIds(attempts);
    double target = config.targetGrade(band.name(), goal.name());
    List<ReferenceHeuristic.Input> inputs = new ArrayList<>();
    for (String id : eligible) {
      PoolRow row = pool.get(id);
      inputs.add(
          new ReferenceHeuristic.Input(
              id,
              row.grade(),
              row.observations(),
              ReferenceHeuristic.novelty(recent, id),
              ReferenceHeuristic.preferenceMatch(
                  row.tags(), config.preferredTagsFor(goal.name()))));
    }
    List<ReferenceHeuristic.Result> expected =
        ReferenceHeuristic.rank(
            config, userId, sessionId, target, inputs, maxRoutes, widened, false);

    List<RecommendationView> served = response.recommendations();
    assertThat(served).hasSize(Math.min(maxRoutes, eligible.size())).hasSize(expected.size());
    for (int i = 0; i < served.size(); i++) {
      RecommendationView item = served.get(i);
      ReferenceHeuristic.Result want = expected.get(i);
      assertThat(item.routeVariantId()).as("position %d", i + 1).isEqualTo(want.routeVariantId());
      assertThat(item.position()).isEqualTo(i + 1);
      assertThat(Double.doubleToRawLongBits(item.rankScore()))
          .as("score bits of %s at position %d", item.routeVariantId(), i + 1)
          .isEqualTo(Double.doubleToRawLongBits(want.score()));
      assertThat(item.reasonCodes().stream().map(ReasonCode::name).toList())
          .as("reason codes of %s", item.routeVariantId())
          .isEqualTo(want.reasonCodes());
      assertThat(item.reasonCodes()).doesNotContain(ReasonCode.COLD_START_HEURISTIC);
      if (goal != Goal.PROJECT && !widened) {
        Instant last = lastAttemptAt.get(item.routeVariantId());
        assertThat(last == null || !last.isAfter(threshold))
            .as("%s was attempted within the cooldown", item.routeVariantId())
            .isTrue();
      }
    }
    assertThat(again.recommendations().stream().map(RecommendationView::routeVariantId).toList())
        .isEqualTo(served.stream().map(RecommendationView::routeVariantId).toList());
    assertThat(again.recommendations().stream().map(v -> Double.doubleToRawLongBits(v.rankScore())))
        .containsExactlyElementsOf(
            served.stream().map(v -> Double.doubleToRawLongBits(v.rankScore())).toList());
  }

  @Provide
  Arbitrary<UUID> sessions() {
    return Arbitraries.create(UUID::randomUUID);
  }

  /**
   * One to fifty attempts: mostly at the ranked angle so cooldown and novelty bite, the rest at the
   * board's other angles; sent or failed; at distinct minutes over the last 120 days.
   */
  @Provide
  Arbitrary<List<AttemptSpec>> histories() {
    Arbitrary<AttemptSpec> spec =
        Combinators.combine(
                Arbitraries.of(40, 40, 40, 20, 30, 45, 50),
                Arbitraries.integers().between(0, 999),
                Arbitraries.of(true, false),
                Arbitraries.integers().between(0, MINUTES_IN_120_DAYS))
            .as(AttemptSpec::new);
    return spec.list().ofMinSize(1).ofMaxSize(50).uniqueElements(AttemptSpec::minutesAgo);
  }

  /** Mostly real candidates, sometimes ids that exist nowhere; both must simply never be served. */
  private static List<String> excludeList(List<Integer> excludeCodes) {
    List<String> exclude = new ArrayList<>();
    for (int code : excludeCodes) {
      exclude.add(
          code % 10 == 0
              ? "ghost-" + code + ":" + ANGLE
              : candidates.get(code % candidates.size()));
    }
    return exclude;
  }

  /** Ghost ids are not candidates, so they do not reduce the pool. */
  private static int ghosts(List<String> exclude) {
    return (int) new HashSet<>(exclude).stream().filter(id -> id.startsWith("ghost-")).count();
  }

  private static RecommendationRequest request(
      Goal goal, int maxRoutes, UUID sessionId, List<String> exclude) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("board_id", CatalogSeed.BOARD_ID);
    body.put("angle_deg", ANGLE);
    body.put("goal", goal.name());
    body.put("max_routes", maxRoutes);
    body.put("session_id", sessionId.toString());
    body.put("exclude_route_variant_ids", exclude);
    return json.readValue(json.writeValueAsString(body), RecommendationRequest.class);
  }

  private static UUID seedReadyUser(GradeBand band) {
    UUID id = Uuid7.next(clock);
    jdbc.sql(
            "INSERT INTO users (id, username, password_hash) VALUES (:id, :name, 'argon2id$placeholder')")
        .param("id", id)
        .param("name", "recommend-" + id)
        .update();
    ConsentDocument document = new ConsentDocumentService().current();
    new ConsentRepository(jdbc)
        .upsert(
            Uuid7.next(clock),
            id,
            document.version(),
            json.writeValueAsString(Map.of("service", true, "research", false)),
            document.textSha256(),
            clock.instant().truncatedTo(ChronoUnit.MILLIS));
    new PreferencesRepository(jdbc, json)
        .upsert(
            id,
            new PreferencesView(CatalogSeed.BOARD_ID, ANGLE, band, List.of(Goal.TECHNIQUE)),
            clock.instant());
    return id;
  }

  /** Writes the generated history as ACTIVE revision-1 rows of {@code attempts}. */
  private static List<SeededAttempt> seedHistory(UUID userId, List<AttemptSpec> history) {
    List<SeededAttempt> seeded = new ArrayList<>();
    OffsetDateTime received = NOW.atOffset(ZoneOffset.UTC);
    for (AttemptSpec spec : history) {
      List<String> ids = attemptableByAngle.get(spec.angle());
      String id = ids.get(spec.routeCode() % ids.size());
      Instant occurredAt = NOW.minus(Duration.ofMinutes(spec.minutesAgo()));
      jdbc.sql(
              "INSERT INTO attempts (attempt_id, user_id, variant_id, route_variant_public_id,"
                  + " occurred_at, received_at, attempts_count, sent, revision, status,"
                  + " submission_idempotency_key, request_fingerprint, response_body_json,"
                  + " updated_at) VALUES (:id, :user_id, :variant_id, :public_id, :occurred_at,"
                  + " :received, 1, :sent, 1, 'ACTIVE', :key, :fingerprint, CAST('{}' AS jsonb),"
                  + " :received)")
          .param("id", Uuid7.next(clock))
          .param("user_id", userId)
          .param("variant_id", variantIds.get(id))
          .param("public_id", id)
          .param("occurred_at", occurredAt.atOffset(ZoneOffset.UTC))
          .param("received", received)
          .param("sent", spec.sent())
          .param("key", "property-" + UUID.randomUUID())
          .param("fingerprint", "0".repeat(64))
          .update();
      seeded.add(new SeededAttempt(id, spec.angle(), occurredAt, spec.sent()));
    }
    return seeded;
  }

  /** Latest occurred_at per variant of the ranked angle: the cooldown input. */
  private static Map<String, Instant> lastAttemptAtRankedAngle(List<SeededAttempt> attempts) {
    Map<String, Instant> last = new HashMap<>();
    for (SeededAttempt attempt : attempts) {
      if (attempt.angle() == ANGLE) {
        last.merge(attempt.routeVariantId(), attempt.occurredAt(), (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return last;
  }

  /** The twenty most recent distinct variant ids, newest attempt first, over every angle. */
  private static List<String> recentVariantIds(List<SeededAttempt> attempts) {
    List<SeededAttempt> newestFirst = new ArrayList<>(attempts);
    newestFirst.sort(Comparator.comparing(SeededAttempt::occurredAt).reversed());
    List<String> recent = new ArrayList<>();
    for (SeededAttempt attempt : newestFirst) {
      if (!recent.contains(attempt.routeVariantId())) {
        recent.add(attempt.routeVariantId());
      }
      if (recent.size() == 20) {
        break;
      }
    }
    return recent;
  }

  /** The candidate pool of the ranked angle: visible, graded, placed ACTIVE variants. */
  private static void loadPool() {
    Map<String, PoolRow> rows = new TreeMap<>();
    jdbc.sql(
            "SELECT r.provider_route_uuid || ':' || v.angle_deg AS id, v.grade_numeric,"
                + " v.grade_observation_count, r.tags_json::text AS tags"
                + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE v.validation_status = 'ACTIVE' AND r.board_id = :board"
                + " AND v.angle_deg = :angle AND v.grade_numeric IS NOT NULL"
                + " AND (r.active_from IS NULL OR r.active_from <= :now)"
                + " AND (r.active_to IS NULL OR r.active_to > :now)"
                + " AND EXISTS (SELECT 1 FROM route_placements p WHERE p.variant_id = v.variant_id)")
        .param("board", CatalogSeed.BOARD_ID)
        .param("angle", ANGLE)
        .param("now", NOW.atOffset(ZoneOffset.UTC))
        .query(
            rs -> {
              List<String> tags = new ArrayList<>();
              json.readTree(rs.getString("tags")).forEach(tag -> tags.add(tag.asString()));
              rows.put(
                  rs.getString("id"),
                  new PoolRow(
                      rs.getBigDecimal("grade_numeric").doubleValue(),
                      rs.getInt("grade_observation_count"),
                      tags));
            });
    pool = rows;
    poolIds = new ArrayList<>(rows.keySet());
  }

  /** Every visible ACTIVE variant of the board, by angle, with its variant id for the insert. */
  private static void loadAttemptable() {
    Map<Integer, List<String>> byAngle = new TreeMap<>();
    Map<String, UUID> ids = new HashMap<>();
    jdbc.sql(
            "SELECT r.provider_route_uuid || ':' || v.angle_deg AS id, v.angle_deg, v.variant_id"
                + " FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE v.validation_status = 'ACTIVE' AND r.board_id = :board"
                + " AND (r.active_from IS NULL OR r.active_from <= :now)"
                + " AND (r.active_to IS NULL OR r.active_to > :now)"
                + " ORDER BY v.angle_deg, r.provider_route_uuid")
        .param("board", CatalogSeed.BOARD_ID)
        .param("now", NOW.atOffset(ZoneOffset.UTC))
        .query(
            rs -> {
              byAngle
                  .computeIfAbsent(rs.getInt("angle_deg"), angle -> new ArrayList<>())
                  .add(rs.getString("id"));
              ids.put(rs.getString("id"), rs.getObject("variant_id", UUID.class));
            });
    attemptableByAngle = byAngle;
    variantIds = ids;
    for (int angle : List.of(20, 30, 40, 45, 50)) {
      assertThat(byAngle.get(angle)).as("angle %d", angle).isNotEmpty();
    }
  }

  /** The shipped policy text, read as a plain resource rather than through the policy loader. */
  private static String policyResource() throws IOException {
    try (InputStream in =
        RecommendationPropertyTest.class
            .getClassLoader()
            .getResourceAsStream(PolicyLoader.DEFAULT_RESOURCE)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
