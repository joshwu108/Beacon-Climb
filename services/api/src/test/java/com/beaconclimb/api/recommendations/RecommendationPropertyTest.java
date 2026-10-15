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
import com.beaconclimb.api.ranking.PolicyLoader;
import com.beaconclimb.api.ranking.PolicyRegistry;
import com.beaconclimb.api.recommendations.api.RecommendationResponse;
import com.beaconclimb.api.recommendations.api.RecommendationView;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
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
 */
class RecommendationPropertyTest {

  private static final Instant NOW = Instant.parse("2026-10-14T14:00:00Z");
  private static final int ANGLE = 40;

  private static RecommendationService service;
  private static JdbcClient jdbc;
  private static JsonMapper json;
  private static Clock clock;
  private static PreferencesService preferences;
  private static List<String> candidates;
  private static Set<String> visible;

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
    PolicyRegistry registry = new PolicyRegistry(new PolicyLoader(), jdbc, tx, clock);
    registry.register();
    FeatureSnapshotRepository snapshots = new FeatureSnapshotRepository(jdbc);
    preferences = new PreferencesService(new PreferencesRepository(jdbc, json), clock);
    DecisionLedgerRepository ledger = new DecisionLedgerRepository(jdbc, json);
    service =
        new RecommendationService(
            new ConsentGuard(new ConsentRepository(jdbc), new ConsentDocumentService()),
            new DecisionQuota(ledger, new DecisionsProperties("unused", 100000, 100000)),
            preferences,
            registry,
            snapshots,
            new FeatureRecomputeService(jdbc, snapshots, tx, clock),
            new CandidateRepository(jdbc, json),
            new LocalFsSnapshotStore(Files.createTempDirectory("decisions-property"), 100000),
            new DecisionRecordSchema(),
            ledger,
            tx,
            clock);
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
  }

  @Property(tries = 200)
  void servedIdsAreVisibleActiveVariantsOfTheBoardAndAngleAndNeverExcluded(
      @ForAll Goal goal,
      @ForAll GradeBand band,
      @ForAll @IntRange(min = 1, max = 20) int maxRoutes,
      @ForAll @Size(max = 40) List<@IntRange(min = 0, max = 999) Integer> excludeCodes,
      @ForAll("sessions") UUID sessionId) {
    UUID userId = seedReadyUser(band);
    List<String> exclude = new ArrayList<>();
    for (int code : excludeCodes) {
      // Mostly real candidates, sometimes ids that exist nowhere; both must simply never be served.
      exclude.add(
          code % 10 == 0
              ? "ghost-" + code + ":" + ANGLE
              : candidates.get(code % candidates.size()));
    }

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

  @Provide
  Arbitrary<UUID> sessions() {
    return Arbitraries.create(UUID::randomUUID);
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
}
