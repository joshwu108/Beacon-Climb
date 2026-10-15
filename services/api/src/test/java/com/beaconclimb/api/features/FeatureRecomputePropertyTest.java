package com.beaconclimb.api.features;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import com.beaconclimb.api.attempts.AttemptAuditRepository;
import com.beaconclimb.api.attempts.AttemptEventSchema;
import com.beaconclimb.api.attempts.AttemptEvents;
import com.beaconclimb.api.attempts.AttemptRepository;
import com.beaconclimb.api.attempts.AttemptService;
import com.beaconclimb.api.attempts.AttemptStatus;
import com.beaconclimb.api.attempts.api.AttemptView;
import com.beaconclimb.api.catalog.CatalogQueryRepository;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.catalog.importer.CatalogRepository;
import com.beaconclimb.api.catalog.importer.CatalogSchema;
import com.beaconclimb.api.identity.ConsentDocumentService;
import com.beaconclimb.api.identity.ConsentGuard;
import com.beaconclimb.api.identity.ConsentRepository;
import com.beaconclimb.api.identity.Uuid7;
import com.beaconclimb.api.identity.api.ConsentDocument;
import com.beaconclimb.api.platform.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
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
 * BC-0113 property: a random multiset of attempt events (creates, idempotent replays of the same
 * key, corrections, stale retries that conflict, retractions) applied through the real service
 * yields the same snapshot as the pure reference in {@link ReferenceFeatures} computed from the
 * de-duplicated canonical rows read back from Postgres. The reference re-states
 * contracts/features/climber-features.v1.md independently of {@link FeatureComputer}.
 */
class FeatureRecomputePropertyTest {

  private static final Instant W = Instant.parse("2026-10-14T14:00:00Z");

  /** Hour offsets back from W, chosen so that windows, ties and session gaps all occur. */
  private static final int[] OFFSET_HOURS = {
    0, 1, 2, 3, 4, 7, 24, 48, 167, 168, 169, 400, 719, 720, 721, 1500, 2159, 2160, 2161, 2400
  };

  private static AttemptService service;
  private static FeatureRecomputeService features;
  private static JdbcClient jdbc;
  private static JsonMapper json;
  private static Clock clock;
  private static List<String> routes;

  @BeforeContainer
  static void wire() {
    String url = AbstractPostgresIT.POSTGRES.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, "beacon_migrate", AbstractPostgresIT.MIGRATE_PASSWORD)
        .load()
        .migrate();
    DataSource app =
        new DriverManagerDataSource(url, "beacon_app", AbstractPostgresIT.APP_PASSWORD);
    jdbc = JdbcClient.create(app);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(app));
    clock = Clock.fixed(W, java.time.ZoneOffset.UTC);
    json = JsonMapper.builder().build();
    CatalogSeed.seed(
        new CatalogImporter(new CatalogSchema(), new CatalogRepository(jdbc), tx, clock), jdbc);
    routes =
        jdbc.sql(
                "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                    + " JOIN routes r ON r.route_id = v.route_id WHERE v.validation_status = 'ACTIVE'"
                    + " AND r.board_id = :board AND (r.active_from IS NULL OR r.active_from <= now())"
                    + " AND (r.active_to IS NULL OR r.active_to > now())"
                    + " ORDER BY v.grade_numeric, r.provider_route_uuid LIMIT 400")
            .param("board", CatalogSeed.BOARD_ID)
            .query(String.class)
            .list();
    features = new FeatureRecomputeService(jdbc, new FeatureSnapshotRepository(jdbc), tx, clock);
    service =
        new AttemptService(
            new AttemptRepository(jdbc),
            new AttemptAuditRepository(jdbc),
            new AttemptEvents(new AttemptEventSchema(), json),
            new CatalogQueryRepository(jdbc),
            new ConsentGuard(new ConsentRepository(jdbc), new ConsentDocumentService()),
            tx,
            json,
            clock,
            List.of(new FeatureRecomputeHook(features, clock)));
  }

  @Property(tries = 100)
  void serviceSnapshotEqualsReferenceOverDeduplicatedRows(
      @ForAll @Size(min = 0, max = 24) List<@IntRange(min = 0, max = 9999) Integer> codes) {
    UUID userId = seedConsentedUser();
    List<AttemptView> attempts = new ArrayList<>();
    List<Submission> submissions = new ArrayList<>();
    for (int code : codes) {
      apply(userId, code, attempts, submissions);
    }

    FeatureSnapshot snapshot = features.recompute(userId, W).snapshot();
    FeatureSnapshot stored =
        new FeatureSnapshotRepository(jdbc)
            .find(userId, FeatureAggregates.SCHEMA_VERSION)
            .orElseThrow();
    List<ReferenceFeatures.Row> rows = canonicalRows(userId);
    ReferenceFeatures.Expected expected = ReferenceFeatures.compute(rows, W, json);

    assertThat(snapshot.aggregates().toJson()).isEqualTo(expected.aggregatesJson());
    assertThat(snapshot.provenanceChecksum()).isEqualTo(expected.checksum());
    assertThat(snapshot.featureAsOf()).isEqualTo(expected.featureAsOf());
    assertThat(stored).isEqualTo(snapshot);
    assertThat(stored.aggregates().toJson()).isEqualTo(expected.aggregatesJson());
  }

  /** The idempotency key and body code of one create, so a duplicate delivery can repeat it. */
  private record Submission(String key, int code) {}

  /** code decides the operation; the service, not the test, decides what is canonical. */
  private static void apply(
      UUID userId, int code, List<AttemptView> attempts, List<Submission> submissions) {
    int kind = code % 5;
    if (attempts.isEmpty() || kind == 0) {
      Submission submission = new Submission(UUID.randomUUID().toString(), code);
      submissions.add(submission);
      attempts.add(service.create(userId, submission.key(), body(code)).view());
      return;
    }
    int index = (code / 5) % attempts.size();
    AttemptView current = attempts.get(index);
    if (kind == 1) {
      // Duplicate delivery: same key and body is a replay that changes nothing.
      Submission original = submissions.get(index);
      assertThat(service.create(userId, original.key(), body(original.code())).created()).isFalse();
      return;
    }
    if (current.status() == AttemptStatus.RETRACTED) {
      return;
    }
    if (kind == 2) {
      attempts.set(index, patch(userId, current, "{\"sent\":" + (code % 2 == 0) + "}"));
    } else if (kind == 3) {
      // Out-of-order revision: a stale If-Match is refused and leaves the row untouched.
      try {
        service.patch(
            userId,
            current.attemptId().toString(),
            "\"" + (current.revision() + 1) + "\"",
            "{\"sent\":true}");
      } catch (ApiException expected) {
        assertThat(expected.currentRevision()).isEqualTo(current.revision());
      }
    } else {
      attempts.set(index, patch(userId, current, "{\"retract\":true}"));
    }
  }

  private static AttemptView patch(UUID userId, AttemptView current, String body) {
    return service.patch(
        userId, current.attemptId().toString(), "\"" + current.revision() + "\"", body);
  }

  /** The body is a pure function of the code so a replay with the same key is byte-identical. */
  private static String body(int code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("route_variant_id", routes.get((code * 7) % routes.size()));
    Instant at = W.minus(Duration.ofHours(OFFSET_HOURS[(code / 5) % OFFSET_HOURS.length]));
    body.put("occurred_at", at.minusSeconds((code % 7) * 600L).toString());
    body.put("attempts_count", 1 + code % 3);
    body.put("sent", code % 2 == 0);
    return json.writeValueAsString(body);
  }

  private static List<ReferenceFeatures.Row> canonicalRows(UUID userId) {
    return jdbc.sql(
            "SELECT a.attempt_id, a.revision, a.route_variant_public_id, a.occurred_at, a.sent,"
                + " v.grade_numeric FROM attempts a JOIN route_variants v ON v.variant_id ="
                + " a.variant_id WHERE a.user_id = :id AND a.status = 'ACTIVE'")
        .param("id", userId)
        .query(
            (rs, n) ->
                new ReferenceFeatures.Row(
                    rs.getObject("attempt_id", UUID.class),
                    rs.getInt("revision"),
                    rs.getString("route_variant_public_id"),
                    rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getBoolean("sent"),
                    rs.getBigDecimal("grade_numeric") == null
                        ? null
                        : rs.getBigDecimal("grade_numeric").doubleValue()))
        .list();
  }

  private static UUID seedConsentedUser() {
    UUID id = Uuid7.next(clock);
    jdbc.sql(
            "INSERT INTO users (id, username, password_hash) VALUES (:id, :name, 'argon2id$placeholder')")
        .param("id", id)
        .param("name", "features-" + id)
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
    return id;
  }
}
