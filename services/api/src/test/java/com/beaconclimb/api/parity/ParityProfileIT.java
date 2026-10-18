package com.beaconclimb.api.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.listener.MessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0306: the {@code parity} profile ({@code make test-parity}) boots without a web server,
 * without Kafka and without Flyway, runs {@link ParityRunner} once on startup for the synthetic
 * user seeded below (an empty attempt set with a STREAM row carrying the empty-set aggregates at
 * {@code W}) and ends with exit code 0 and the report on disk. The schema is migrated here
 * explicitly because the profile does not do it: the oracle only reads.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.profiles.active=parity",
      "parity.user=" + ParityProfileIT.USER,
      "parity.out=" + ParityProfileIT.OUT,
      "parity.commit=profile-it",
      // Logback is JVM-global and the profile's plain WARN console config would leak into the
      // cached contexts of later test classes (StructuredLoggingIT reads the ECS encoder), so
      // this test keeps the suite's logging; the CLI's own yaml is still loaded (asserted below).
      "logging.level.root=INFO",
      "logging.structured.format.console=ecs"
    })
class ParityProfileIT extends AbstractPostgresIT {

  static final String USER = "018f0000-0000-7000-8000-0000000000e1";
  static final String OUT = "build/parity-profile-it/report.json";
  private static final String W = "2026-10-14T14:00:00+00:00";
  private static final String SHA256_OF_EMPTY =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
  private static final String EMPTY_AGGREGATES =
      "{\"attempts_30d\":0,\"attempts_7d\":0,\"attempts_90d\":0,\"distinct_sessions_30d\":0,"
          + "\"last_session_at\":null,\"max_sent_grade_numeric_90d\":null,"
          + "\"recent_variant_ids\":[],\"send_rate_by_grade_band_90d\":{},\"sends_30d\":0,"
          + "\"sends_7d\":0,\"sends_90d\":0}";

  static {
    String url = POSTGRES.getJdbcUrl();
    Flyway.configure().dataSource(url, "beacon_migrate", MIGRATE_PASSWORD).load().migrate();
    JdbcClient jdbc =
        JdbcClient.create(new DriverManagerDataSource(url, "beacon_app", APP_PASSWORD));
    jdbc.sql(
            "INSERT INTO users (id, username, password_hash) VALUES (:id, 'synthetic_parity_profile',"
                + " 'argon2id$placeholder') ON CONFLICT (id) DO NOTHING")
        .param("id", UUID.fromString(USER))
        .update();
    jdbc.sql(
            "INSERT INTO user_feature_snapshots (user_id, feature_schema_version, cutoff_w,"
                + " feature_as_of, aggregates_json, provenance_checksum, source, source_partition,"
                + " source_offset, computed_at) VALUES (:id, 1, :w, :w, CAST(:aggregates AS jsonb),"
                + " :checksum, 'STREAM', 0, 0, :w)"
                + " ON CONFLICT (user_id, feature_schema_version, source) DO NOTHING")
        .param("id", UUID.fromString(USER))
        .param("w", java.time.OffsetDateTime.parse(W))
        .param("aggregates", EMPTY_AGGREGATES)
        .param("checksum", SHA256_OF_EMPTY)
        .update();
    try {
      Files.deleteIfExists(Path.of(OUT));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired ApplicationContext context;
  @Autowired Environment environment;
  @Autowired ParityRunner runner;

  @Test
  void parityProfileHasNoWebServerNoKafkaNoFlywayAndWroteAPassingReport() throws IOException {
    assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
    assertThat(context.getBeanNamesForType(MessageListenerContainer.class)).isEmpty();
    assertThat(runner.getExitCode()).isZero();

    JsonNode report =
        JsonMapper.builder()
            .build()
            .readTree(Files.readString(Path.of(OUT), StandardCharsets.UTF_8));
    assertThat(report.get("commit").asString()).isEqualTo("profile-it");
    assertThat(report.get("users_total").asInt()).isEqualTo(1);
    assertThat(report.get("compared").asInt()).isEqualTo(1);
    assertThat(report.get("equal").asInt()).isEqualTo(1);
    assertThat(report.get("diffs").asInt()).isZero();
    assertThat(report.get("same_w_mismatch").asInt()).isZero();
    assertThat(report.get("synthetic_only").asBoolean()).isTrue();
    JsonNode detail = report.get("details").get(0);
    assertThat(detail.get("user_id").asString()).isEqualTo(USER);
    assertThat(detail.get("status").asString()).isEqualTo("EQUAL");
    assertThat(detail.get("cutoff_w").asString()).isEqualTo("2026-10-14T14:00:00Z");
    assertThat(detail.get("stream_feature_as_of").asString()).isEqualTo("2026-10-14T14:00:00Z");
    assertThat(detail.get("batch_feature_as_of").isNull()).isTrue();
    assertThat(detail.get("sync_cutoff_w").isNull()).isTrue();
    assertThat(detail.get("stream_offset").asLong()).isZero();
    assertThat(report.toString()).doesNotContain("synthetic_parity_profile");
  }
}
