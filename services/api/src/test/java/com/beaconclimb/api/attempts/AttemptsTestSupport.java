package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.ApiApplication;
import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.identity.IdentityTestSupport;
import com.beaconclimb.api.identity.api.AuthResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Harness for the attempts endpoints: the identity harness plus an adjustable application clock, a
 * write limit high enough for the concurrency and cursor-walk tests (the per-test limiter reset
 * still applies), the demo board seeded once per class, consent helpers and raw PATCH/POST calls
 * with explicit headers.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {ApiApplication.class, AttemptsTestSupport.TestClockConfig.class},
    properties = "beacon.http.write-limit-per-minute=1000")
public abstract class AttemptsTestSupport extends IdentityTestSupport {

  /** Replaces the production UTC clock with one a test can pin. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class TestClockConfig {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  /** A registered user with active consent. */
  protected record Account(String token, UUID userId) {}

  protected static final String ATTEMPTS = "/v1/me/attempts";
  private static final Set<Class<?>> SEEDED = ConcurrentHashMap.newKeySet();

  @Autowired protected CatalogImporter importer;
  @Autowired protected TestClock testClock;
  @Autowired protected AttemptService service;

  protected final AttemptEventSchema eventSchema = new AttemptEventSchema();

  @BeforeEach
  void seedDemoBoard() {
    if (SEEDED.add(getClass())) {
      CatalogSeed.seed(importer, jdbc);
    }
  }

  @AfterEach
  void releaseClock() {
    testClock.reset();
  }

  /** Public ids of visible demo-board variants in a deterministic order. */
  protected List<String> visibleRouteIds(int count) {
    return jdbc.sql(
            "SELECT r.provider_route_uuid || ':' || v.angle_deg FROM route_variants v"
                + " JOIN routes r ON r.route_id = v.route_id WHERE v.validation_status = 'ACTIVE'"
                + " AND r.board_id = :board AND (r.active_from IS NULL OR r.active_from <= now())"
                + " AND (r.active_to IS NULL OR r.active_to > now())"
                + " ORDER BY r.provider_route_uuid, v.angle_deg LIMIT :limit")
        .param("board", CatalogSeed.BOARD_ID)
        .param("limit", count)
        .query(String.class)
        .list();
  }

  protected String visibleRouteId() {
    return visibleRouteIds(1).get(0);
  }

  protected Account registerWithConsent() {
    AuthResponse auth = register(uniqueUsername());
    grantConsent(auth.token());
    return new Account(auth.token(), userIdOf(auth.token()));
  }

  protected Account registerWithoutConsent() {
    AuthResponse auth = register(uniqueUsername());
    return new Account(auth.token(), userIdOf(auth.token()));
  }

  protected void grantConsent(String token) {
    JsonNode document = tree(get("/v1/consent/current", null).getBody());
    String body =
        json.writeValueAsString(
            Map.of(
                "consent_version", document.get("version").asString(),
                "scopes", Map.of("service", true),
                "text_sha256", document.get("text_sha256").asString()));
    assertThat(post("/v1/me/consent", body, token).getStatusCode().is2xxSuccessful()).isTrue();
  }

  protected void revokeConsent(String token) {
    assertThat(delete("/v1/me/consent", token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  /** A valid AttemptCreate body; callers override or add members through the returned map. */
  protected Map<String, Object> attemptBody(String routeVariantId, Instant occurredAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("route_variant_id", routeVariantId);
    body.put("occurred_at", occurredAt.toString());
    body.put("attempts_count", 2);
    body.put("sent", true);
    return body;
  }

  protected ResponseEntity<String> postAttempt(String token, String key, Map<String, Object> body) {
    return postAttempt(token, key, json.writeValueAsString(body));
  }

  protected ResponseEntity<String> postAttempt(String token, String key, String body) {
    Map<String, String> headers =
        key == null ? Map.of() : Map.of(AttemptService.IDEMPOTENCY_KEY_HEADER, key);
    return post(ATTEMPTS, body, token, headers);
  }

  protected ResponseEntity<String> patchAttempt(
      String token, String attemptId, String ifMatch, String body) {
    return client()
        .patch()
        .uri(ATTEMPTS + "/" + attemptId)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(
            h -> {
              h.setBearerAuth(token);
              if (ifMatch != null) {
                h.set(AttemptService.IF_MATCH_HEADER, ifMatch);
              }
            })
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  protected String createAttempt(Account account, String routeVariantId) {
    ResponseEntity<String> created =
        postAttempt(
            account.token(),
            UUID.randomUUID().toString(),
            attemptBody(routeVariantId, Instant.now().minusSeconds(60)));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return tree(created.getBody()).get("attempt_id").asString();
  }

  protected int attemptRows(UUID attemptId) {
    return count("SELECT count(*) FROM attempts WHERE attempt_id = :id", attemptId);
  }

  protected List<Map<String, Object>> revisionRows(UUID attemptId) {
    return jdbc.sql(
            "SELECT revision, change_type, snapshot_json::text AS snapshot_json FROM"
                + " attempt_revisions WHERE attempt_id = :id ORDER BY revision")
        .param("id", attemptId)
        .query()
        .listOfRows();
  }

  protected List<Map<String, Object>> outboxRows(UUID attemptId) {
    return jdbc.sql(
            "SELECT event_id, aggregate_revision, event_type, schema_version,"
                + " payload_json::text AS payload_json, published_at FROM attempt_event_outbox"
                + " WHERE aggregate_id = :id ORDER BY aggregate_revision")
        .param("id", attemptId)
        .query()
        .listOfRows();
  }

  protected int count(String sql, UUID id) {
    return jdbc.sql(sql).param("id", id).query(Integer.class).single();
  }

  protected void assertEventConforms(String payloadJson) {
    assertThat(eventSchema.validate(tree(payloadJson)))
        .as("%s must conform to attempts.v1", payloadJson)
        .isEmpty();
  }
}
