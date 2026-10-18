package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.json.Jcs;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * createAttempt (ADR-0002 test ids BC-0109-IDS, -JCS, -TIME, -IDEMPOTENCY, -OUTBOX): server ids,
 * fingerprint equality, offset normalisation and skew, replay versus conflict, the header, consent,
 * route and schema failures, and the shipped fixtures.
 */
class AttemptCreateIT extends AttemptsTestSupport {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "examples").toAbsolutePath().normalize();
  private static final Instant PAST = Instant.parse("2026-10-14T14:00:00Z");

  @Test
  void createReturnsServerUuid7AndRejectsClientAttemptId() {
    Account account = registerWithConsent();
    String route = visibleRouteId();

    ResponseEntity<String> created =
        postAttempt(account.token(), "k-ids", attemptBody(route, PAST));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ContractSchemas.assertConforms("Attempt", created.getBody());
    JsonNode body = tree(created.getBody());
    UUID attemptId = UUID.fromString(body.get("attempt_id").asString());
    assertThat(attemptId.version()).isEqualTo(7);
    assertThat(body.get("revision").asInt()).isEqualTo(1);
    assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
    assertThat(body.get("route_variant_id").asString()).isEqualTo(route);
    assertThat(body.get("route").get("route_variant_id").asString()).isEqualTo(route);
    assertThat(body.get("received_at").asString()).endsWith("Z");
    assertThat(body.get("completion_fraction").isNull()).isTrue();
    assertThat(body.get("notes").isNull()).isTrue();
    assertThat(attemptRows(attemptId)).isEqualTo(1);
    assertThat(outboxRows(attemptId)).hasSize(1);
    assertEventConforms(outboxRows(attemptId).get(0).get("payload_json").toString());

    Map<String, Object> withId = attemptBody(route, PAST);
    withId.put("attempt_id", "018f0000-0000-7000-8000-000000000002");
    ErrorEnvelope envelope =
        assertError(
            postAttempt(account.token(), "k-ids-2", withId),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_FIELD);
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("attempt_id", "additionalProperties", "unknown field"));
  }

  @Test
  void semanticallyEqualBodiesShareOneFingerprintAndReplay() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    String first =
        "{\"sent\":true,\"attempts_count\":2.0,\"occurred_at\":\""
            + PAST
            + "\","
            + "\"route_variant_id\":\""
            + route
            + "\"}";
    String second =
        "{\"route_variant_id\":\""
            + route
            + "\",\"occurred_at\":\""
            + PAST
            + "\","
            + "\"attempts_count\":2,\"sent\":true}";
    assertThat(Jcs.fingerprint(first)).isEqualTo(Jcs.fingerprint(second));
    assertThat(Jcs.fingerprint("{\"b\":1.0,\"a\":1}"))
        .isEqualTo(Jcs.fingerprint("{\"a\":1,\"b\":1}"));

    ResponseEntity<String> created =
        post(
            ATTEMPTS,
            first,
            account.token(),
            Map.of("Idempotency-Key", "k-jcs", RequestIdFilter.HEADER, "req-1"));
    ResponseEntity<String> replayed =
        post(
            ATTEMPTS,
            second,
            account.token(),
            Map.of("Idempotency-Key", "k-jcs", RequestIdFilter.HEADER, "req-2"));

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tree(replayed.getBody())).isEqualTo(tree(created.getBody()));
    assertThat(replayed.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("req-2");
    ContractSchemas.assertConforms("Attempt", replayed.getBody());
    UUID attemptId = UUID.fromString(tree(created.getBody()).get("attempt_id").asString());
    assertThat(attemptRows(attemptId)).isEqualTo(1);
    assertThat(outboxRows(attemptId)).hasSize(1);
  }

  @Test
  void occurredAtIsNormalisedToUtcAndMoreThanFiveMinutesAheadIsRejected() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    testClock.fix(Instant.parse("2026-10-14T14:00:00Z"));

    Map<String, Object> offset = attemptBody(route, PAST);
    offset.put("occurred_at", "2026-10-14T09:00:00-05:00");
    ResponseEntity<String> created = postAttempt(account.token(), "k-time-1", offset);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(tree(created.getBody()).get("occurred_at").asString())
        .isEqualTo("2026-10-14T14:00:00Z");
    assertThat(tree(created.getBody()).get("received_at").asString())
        .isEqualTo("2026-10-14T14:00:00Z");

    Map<String, Object> exactlyFive = attemptBody(route, PAST);
    exactlyFive.put("occurred_at", "2026-10-14T14:05:00Z");
    assertThat(postAttempt(account.token(), "k-time-2", exactlyFive).getStatusCode())
        .isEqualTo(HttpStatus.CREATED);

    Map<String, Object> future = attemptBody(route, PAST);
    future.put("occurred_at", "2026-10-14T14:05:01Z");
    ErrorEnvelope envelope =
        assertError(
            postAttempt(account.token(), "k-time-3", future),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.OCCURRED_AT_IN_FUTURE);
    assertThat(envelope.fieldErrors()).extracting(FieldError::field).containsExactly("occurred_at");
    assertThat(envelope.message()).isEqualTo("occurred_at is more than five minutes after receipt");

    Map<String, Object> noOffset = attemptBody(route, PAST);
    noOffset.put("occurred_at", "2026-10-14T14:00:00");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-time-4", noOffset),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("occurred_at", "format"));
  }

  @Test
  void sameKeyWithDifferentBodyIsIdempotencyConflict() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    Map<String, Object> body = attemptBody(route, PAST);
    ResponseEntity<String> created = postAttempt(account.token(), "k1", body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    UUID attemptId = UUID.fromString(tree(created.getBody()).get("attempt_id").asString());

    body.put("sent", false);
    ErrorEnvelope envelope =
        assertError(
            postAttempt(account.token(), "k1", body),
            HttpStatus.CONFLICT,
            ErrorCode.IDEMPOTENCY_CONFLICT);

    assertThat(envelope.message()).isEqualTo("Key was used with a different request");
    assertThat(envelope.fieldErrors()).isEmpty();
    assertThat(envelope.currentRevision()).isNull();
    assertThat(attemptRows(attemptId)).isEqualTo(1);
    assertThat(outboxRows(attemptId)).hasSize(1);
    assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", account.userId()))
        .isEqualTo(1);
  }

  @Test
  void idempotencyKeyHeaderIsRequiredAndPatternChecked() {
    Account account = registerWithConsent();
    Map<String, Object> body = attemptBody(visibleRouteId(), PAST);

    ErrorEnvelope missing =
        assertError(
            postAttempt(account.token(), null, body),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(missing.fieldErrors())
        .containsExactly(new FieldError("Idempotency-Key", "required", "header is required"));

    ErrorEnvelope bad =
        assertError(
            postAttempt(account.token(), "bad key!", body),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(bad.fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("Idempotency-Key", "pattern"));
    assertThat(postAttempt(account.token(), "x".repeat(129), body).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", account.userId()))
        .isZero();
  }

  @Test
  void withoutActiveConsentCreateIsForbidden() {
    Account never = registerWithoutConsent();
    Map<String, Object> body = attemptBody(visibleRouteId(), PAST);
    assertError(
        postAttempt(never.token(), "k-consent", body),
        HttpStatus.FORBIDDEN,
        ErrorCode.CONSENT_REQUIRED);

    Account revoked = registerWithConsent();
    revokeConsent(revoked.token());
    assertError(
        postAttempt(revoked.token(), "k-consent", body),
        HttpStatus.FORBIDDEN,
        ErrorCode.CONSENT_REQUIRED);
    assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", revoked.userId()))
        .isZero();
  }

  @Test
  void unknownHiddenOrMalformedRouteVariantIsRejected() {
    Account account = registerWithConsent();

    ErrorEnvelope unknown =
        assertError(
            postAttempt(account.token(), "k-r1", attemptBody("no-such-route:40", PAST)),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_ROUTE_VARIANT);
    assertThat(unknown.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("route_variant_id");
    assertError(
        postAttempt(account.token(), "k-r2", attemptBody(CatalogSeed.FIXTURE_RETIRED_ID, PAST)),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.UNKNOWN_ROUTE_VARIANT);
    ErrorEnvelope malformed =
        assertError(
            postAttempt(account.token(), "k-r3", attemptBody("not-a-variant-id", PAST)),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(malformed.fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("route_variant_id", "pattern"));
  }

  @Test
  void schemaViolationsAreCollectedAsFieldErrors() {
    Account account = registerWithConsent();
    String route = visibleRouteId();

    Map<String, Object> zero = attemptBody(route, PAST);
    zero.put("attempts_count", 0);
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v1", zero),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .containsExactly(new FieldError("attempts_count", "minimum", "must be >= 1"));

    Map<String, Object> ranges = attemptBody(route, PAST);
    ranges.put("notes", "n".repeat(501));
    ranges.put("completion_fraction", 1.5);
    ranges.put("perceived_effort", 11);
    ranges.remove("sent");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v2", ranges),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactlyInAnyOrder(
            Tuple.tuple("sent", "required"),
            Tuple.tuple("notes", "maxLength"),
            Tuple.tuple("completion_fraction", "maximum"),
            Tuple.tuple("perceived_effort", "maximum"));

    Map<String, Object> types = attemptBody(route, PAST);
    types.put("attempts_count", 1.5);
    types.put("sent", "yes");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v3", types),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactlyInAnyOrder(
            Tuple.tuple("attempts_count", "type"), Tuple.tuple("sent", "type"));

    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v4", "{\"sent\":true,"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("malformed");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v5", "{\"sent\":true,\"sent\":false}"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("malformed");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v6", ""),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("required");
    assertThat(
            assertError(
                    postAttempt(account.token(), "k-v7", "[1]"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::code)
        .containsExactly("type");
    assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", account.userId()))
        .isZero();
  }

  @Test
  void optionalMembersRoundTripWithThreeDecimalFraction() {
    Account account = registerWithConsent();
    Map<String, Object> body = attemptBody(visibleRouteId(), PAST);
    body.put("completion_fraction", 0.3333);
    body.put("perceived_effort", 7);
    body.put("notes", "crux at move 4");

    ResponseEntity<String> created = postAttempt(account.token(), "k-opt", body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode createdBody = tree(created.getBody());
    assertThat(createdBody.get("completion_fraction").asDouble()).isEqualTo(0.333);
    assertThat(createdBody.get("perceived_effort").asInt()).isEqualTo(7);
    assertThat(createdBody.get("notes").asString()).isEqualTo("crux at move 4");

    ResponseEntity<String> read =
        get(ATTEMPTS + "/" + createdBody.get("attempt_id").asString(), account.token());
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Attempt", read.getBody());
    assertThat(tree(read.getBody())).isEqualTo(createdBody);
    ResponseEntity<String> replayed = postAttempt(account.token(), "k-opt", body);
    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tree(replayed.getBody())).isEqualTo(createdBody);
  }

  @Test
  void shippedFixturesConformToTheirSchemas() throws IOException {
    Map<String, String> fixtures =
        Map.ofEntries(
            Map.entry("createAttempt/response.json", "Attempt"),
            Map.entry("createAttempt/response-200.json", "Attempt"),
            Map.entry("createAttempt/response-409.json", "ErrorEnvelope"),
            Map.entry("createAttempt/response-422.json", "ErrorEnvelope"),
            Map.entry("createAttempt/response-422-future.json", "ErrorEnvelope"),
            Map.entry("getAttempt/response.json", "Attempt"),
            Map.entry("patchAttempt/response.json", "Attempt"),
            Map.entry("patchAttempt/response-409.json", "ErrorEnvelope"),
            Map.entry("patchAttempt/response-422.json", "ErrorEnvelope"),
            Map.entry("listAttempts/response.json", "AttemptList"));
    for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
      JsonNode body =
          json.readTree(
                  Files.readString(EXAMPLES.resolve(fixture.getKey()), StandardCharsets.UTF_8))
              .get("body");
      ContractSchemas.assertConforms(fixture.getValue(), json.writeValueAsString(body));
    }
    List<String> requests =
        List.of(
            "createAttempt/request.json",
            "createAttempt/request-409.json",
            "createAttempt/request-422.json");
    for (String name : requests) {
      JsonNode body =
          json.readTree(Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8))
              .get("body");
      assertThat(body.get("route_variant_id").asString()).isEqualTo("synthetic-route-001:40");
    }
  }
}
