package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.AbstractPostgresIT;
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
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * BC-0109 service-level invariants (no HTTP): over any sequence of creates, corrections, stale
 * retries and retractions for one user, every attempt's {@code revision} equals its number of
 * {@code attempt_revisions} rows and its number of outbox rows, a retracted attempt never changes
 * again, and the JCS fingerprint of a body is independent of member order and integer spelling. The
 * service is wired by hand against the shared Postgres because jqwik does not take part in the
 * Spring test lifecycle.
 */
class AttemptInvariantsPropertyTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T14:00:00Z");

  private static AttemptService service;
  private static JdbcClient jdbc;
  private static UUID userId;
  private static List<String> routes;

  @BeforeContainer
  static void wireService() {
    String url = AbstractPostgresIT.POSTGRES.getJdbcUrl();
    Flyway.configure()
        .dataSource(url, "beacon_migrate", AbstractPostgresIT.MIGRATE_PASSWORD)
        .load()
        .migrate();
    DataSource app =
        new DriverManagerDataSource(url, "beacon_app", AbstractPostgresIT.APP_PASSWORD);
    jdbc = JdbcClient.create(app);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(app));
    Clock clock = Clock.systemUTC();
    JsonMapper json = JsonMapper.builder().build();
    CatalogSeed.seed(
        new CatalogImporter(new CatalogSchema(), new CatalogRepository(jdbc), tx, clock), jdbc);
    routes =
        jdbc.sql(
                "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                    + " JOIN routes r ON r.route_id = v.route_id WHERE v.validation_status = 'ACTIVE'"
                    + " AND r.board_id = :board AND (r.active_from IS NULL OR r.active_from <= now())"
                    + " AND (r.active_to IS NULL OR r.active_to > now())"
                    + " ORDER BY r.provider_route_uuid, v.angle_deg LIMIT 5")
            .param("board", CatalogSeed.BOARD_ID)
            .query(String.class)
            .list();
    userId = seedConsentedUser(json, clock);
    service =
        new AttemptService(
            new AttemptRepository(jdbc),
            new AttemptAuditRepository(jdbc),
            new AttemptEvents(new AttemptEventSchema(), json),
            new CatalogQueryRepository(jdbc),
            new ConsentGuard(new ConsentRepository(jdbc), new ConsentDocumentService()),
            tx,
            json,
            clock);
  }

  @Property(tries = 25)
  void revisionCountsAuditRowsAndOutboxRowsStayAligned(
      @ForAll @Size(min = 1, max = 14) List<@IntRange(min = 0, max = 39) Integer> codes) {
    List<AttemptView> attempts = new ArrayList<>();
    for (int code : codes) {
      int kind = code % 4;
      if (attempts.isEmpty() || kind == 0) {
        attempts.add(service.create(userId, UUID.randomUUID().toString(), body(code)).view());
        continue;
      }
      int index = (code / 4) % attempts.size();
      AttemptView current = attempts.get(index);
      if (current.status() == AttemptStatus.RETRACTED) {
        assertThatThrownBy(() -> patch(current, "{\"sent\":true}"))
            .isInstanceOfSatisfying(
                ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        continue;
      }
      if (kind == 1) {
        attempts.set(index, patch(current, "{\"sent\":" + (code % 2 == 0) + "}"));
      } else if (kind == 2) {
        assertThatThrownBy(
                () ->
                    service.patch(
                        userId,
                        current.attemptId().toString(),
                        "\"" + (current.revision() + 1) + "\"",
                        "{\"sent\":true}"))
            .isInstanceOfSatisfying(
                ApiException.class,
                ex -> assertThat(ex.currentRevision()).isEqualTo(current.revision()));
      } else {
        AttemptView retracted = patch(current, "{\"retract\":true}");
        assertThat(retracted.status()).isEqualTo(AttemptStatus.RETRACTED);
        attempts.set(index, retracted);
      }
    }
    for (AttemptView attempt : attempts) {
      int revision =
          count("SELECT revision FROM attempts WHERE attempt_id = :id", attempt.attemptId());
      assertThat(revision).isEqualTo(attempt.revision());
      assertThat(
              count(
                  "SELECT count(*) FROM attempt_revisions WHERE attempt_id = :id",
                  attempt.attemptId()))
          .isEqualTo(revision);
      assertThat(
              count(
                  "SELECT count(*) FROM attempt_event_outbox WHERE aggregate_id = :id",
                  attempt.attemptId()))
          .isEqualTo(revision);
      assertThat(service.get(userId, attempt.attemptId().toString())).isEqualTo(attempt);
    }
  }

  @Property(tries = 200)
  void fingerprintIgnoresMemberOrderAndIntegerSpelling(
      @ForAll("members") Map<String, Integer> members, @ForAll long seed) {
    List<String> keys = new ArrayList<>(members.keySet());
    List<String> shuffled = new ArrayList<>(keys);
    Collections.shuffle(shuffled, new Random(seed));

    String plain = render(keys, members, false);
    String reordered = render(shuffled, members, true);

    assertThat(Jcs.fingerprint(plain)).isEqualTo(Jcs.fingerprint(reordered));
  }

  @Provide
  Arbitrary<Map<String, Integer>> members() {
    Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
    return Arbitraries.maps(keys, Arbitraries.integers().between(-1_000_000, 1_000_000))
        .ofMinSize(1)
        .ofMaxSize(6);
  }

  private static String render(
      List<String> order, Map<String, Integer> members, boolean asDecimal) {
    StringBuilder out = new StringBuilder("{");
    for (int i = 0; i < order.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append('"').append(order.get(i)).append("\":").append(members.get(order.get(i)));
      if (asDecimal) {
        out.append(".0");
      }
    }
    return out.append('}').toString();
  }

  private static AttemptView patch(AttemptView current, String body) {
    return service.patch(
        userId, current.attemptId().toString(), "\"" + current.revision() + "\"", body);
  }

  private static String body(int code) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("route_variant_id", routes.get(code % routes.size()));
    body.put("occurred_at", OCCURRED_AT.minusSeconds(code).toString());
    body.put("attempts_count", 1 + code % 3);
    body.put("sent", code % 2 == 0);
    if (code % 5 == 0) {
      body.put("notes", "property note " + code);
    }
    return JsonMapper.builder().build().writeValueAsString(body);
  }

  private static int count(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Integer.class).single();
  }

  private static UUID seedConsentedUser(JsonMapper json, Clock clock) {
    UUID id = Uuid7.next(clock);
    jdbc.sql(
            "INSERT INTO users (id, username, password_hash) VALUES (:id, :name, 'argon2id$placeholder')")
        .param("id", id)
        .param("name", "property-" + id)
        .update();
    ConsentDocument document = new ConsentDocumentService().current();
    new ConsentRepository(jdbc)
        .upsert(
            Uuid7.next(clock),
            id,
            document.version(),
            json.writeValueAsString(Map.of("service", true, "research", false)),
            document.textSha256(),
            clock.instant());
    return id;
  }
}
