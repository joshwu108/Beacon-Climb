package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * patchAttempt and getAttempt (ADR-0002 test ids BC-0109-REVISION, -RETRACT, -OWNERSHIP, -OUTBOX):
 * If-Match revision gating with current_revision on conflict, retraction as a new revision, no
 * change after retraction, one audit row and one conforming event per revision, and ownership
 * decided before anything about the row is revealed (TM-02).
 */
class AttemptPatchIT extends AttemptsTestSupport {

  /** BC-0124 item 7 (V7, TM-24): the app role can append revisions but never rewrite them. */
  @Test
  void appRoleCannotUpdateOrDeleteRevisionRows() {
    Account account = registerWithConsent();
    UUID id = UUID.fromString(createAttempt(account, visibleRouteId()));
    assertThat(revisionRows(id)).hasSize(1);

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE attempt_revisions SET change_type = change_type WHERE attempt_id = :id")
                    .param("id", id)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM attempt_revisions WHERE attempt_id = :id")
                    .param("id", id)
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(revisionRows(id)).hasSize(1);
  }

  @Test
  void correctionThenRetractionWriteOneRevisionAndOneEventEach() {
    Account account = registerWithConsent();
    String attemptId = createAttempt(account, visibleRouteId());
    UUID id = UUID.fromString(attemptId);

    ResponseEntity<String> corrected =
        patchWithRequestId(account.token(), attemptId, "\"1\"", "{\"sent\":false}", "req-correct");
    assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Attempt", corrected.getBody());
    JsonNode correctedBody = tree(corrected.getBody());
    assertThat(correctedBody.get("revision").asInt()).isEqualTo(2);
    assertThat(correctedBody.get("sent").asBoolean()).isFalse();
    assertThat(correctedBody.get("status").asString()).isEqualTo("ACTIVE");

    ErrorEnvelope stale =
        assertError(
            patchAttempt(account.token(), attemptId, "\"1\"", "{\"sent\":false}"),
            HttpStatus.CONFLICT,
            ErrorCode.REVISION_CONFLICT);
    assertThat(stale.currentRevision()).isEqualTo(2);
    assertThat(stale.message()).isEqualTo("Attempt revision changed");
    assertThat(stale.fieldErrors()).isEmpty();

    ResponseEntity<String> retracted =
        patchWithRequestId(
            account.token(), attemptId, "\"2\"", "{\"retract\":true}", "req-retract");
    assertThat(retracted.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Attempt", retracted.getBody());
    assertThat(tree(retracted.getBody()).get("revision").asInt()).isEqualTo(3);
    assertThat(tree(retracted.getBody()).get("status").asString()).isEqualTo("RETRACTED");
    assertThat(tree(retracted.getBody()).get("sent").asBoolean()).isFalse();

    ErrorEnvelope afterRetraction =
        assertError(
            patchAttempt(account.token(), attemptId, "\"3\"", "{\"sent\":true}"),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(afterRetraction.fieldErrors())
        .containsExactly(
            new FieldError("status", "retracted", "Retracted attempts cannot be changed"));
    assertError(
        patchAttempt(account.token(), attemptId, "\"3\"", "{\"retract\":true}"),
        HttpStatus.UNPROCESSABLE_CONTENT,
        ErrorCode.VALIDATION_FAILED);

    ResponseEntity<String> read = get(ATTEMPTS + "/" + attemptId, account.token());
    assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Attempt", read.getBody());
    assertThat(tree(read.getBody())).isEqualTo(tree(retracted.getBody()));

    List<Map<String, Object>> revisions = revisionRows(id);
    assertThat(revisions)
        .extracting(r -> r.get("revision"), r -> r.get("change_type"))
        .containsExactly(
            Tuple.tuple(1, "RECORDED"), Tuple.tuple(2, "CORRECTED"), Tuple.tuple(3, "RETRACTED"));
    for (Map<String, Object> revision : revisions) {
      JsonNode snapshot = tree(revision.get("snapshot_json").toString());
      assertThat(snapshot.get("revision").asInt()).isEqualTo(revision.get("revision"));
      assertThat(snapshot.has("route")).isFalse();
      assertThat(snapshot.get("attempt_id").asString()).isEqualTo(attemptId);
    }
    List<Map<String, Object>> events = outboxRows(id);
    assertThat(events)
        .extracting(e -> e.get("aggregate_revision"), e -> e.get("event_type"))
        .containsExactly(
            Tuple.tuple(1, "ATTEMPT_RECORDED"),
            Tuple.tuple(2, "ATTEMPT_CORRECTED"),
            Tuple.tuple(3, "ATTEMPT_RETRACTED"));
    for (Map<String, Object> event : events) {
      String payload = event.get("payload_json").toString();
      assertEventConforms(payload);
      JsonNode node = tree(payload);
      assertThat(node.get("attempt_revision").asInt()).isEqualTo(event.get("aggregate_revision"));
      assertThat(node.get("event_id").asString()).isEqualTo(event.get("event_id").toString());
      assertThat(UUID.fromString(node.get("event_id").asString()).version()).isEqualTo(7);
      assertThat(node.get("user_id").asString()).isEqualTo(account.userId().toString());
      assertThat(event.get("schema_version")).isEqualTo(1);
      assertThat(event.get("published_at")).isNull();
    }
    assertThat(tree(events.get(1).get("payload_json").toString()).get("trace_id").asString())
        .isEqualTo("req-correct");
    assertThat(tree(events.get(2).get("payload_json").toString()).get("trace_id").asString())
        .isEqualTo("req-retract");
    assertThat(
            tree(events.get(2).get("payload_json").toString())
                .get("attributes")
                .get("status")
                .asString())
        .isEqualTo("RETRACTED");
    assertThat(
            tree(events.get(1).get("payload_json").toString())
                .get("attributes")
                .get("sent")
                .asBoolean())
        .isFalse();
  }

  @Test
  void otherUsersSeeNotFoundBeforeIfMatchIsEvaluated() {
    Account owner = registerWithConsent();
    Account other = registerWithConsent();
    String attemptId = createAttempt(owner, visibleRouteId());

    assertError(
        get(ATTEMPTS + "/" + attemptId, other.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(other.token(), attemptId, "\"1\"", "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(other.token(), attemptId, "\"999\"", "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(other.token(), attemptId, "bogus", "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(other.token(), attemptId, null, "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(owner.token(), UUID.randomUUID().toString(), "\"1\"", "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);
    assertError(
        get(ATTEMPTS + "/not-a-uuid", owner.token()), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertError(
        patchAttempt(owner.token(), "not-a-uuid", "\"1\"", "{\"sent\":false}"),
        HttpStatus.NOT_FOUND,
        ErrorCode.NOT_FOUND);

    JsonNode untouched = tree(get(ATTEMPTS + "/" + attemptId, owner.token()).getBody());
    assertThat(untouched.get("revision").asInt()).isEqualTo(1);
    assertThat(untouched.get("sent").asBoolean()).isTrue();
    assertThat(revisionRows(UUID.fromString(attemptId))).hasSize(1);
  }

  @Test
  void ifMatchIsRequiredAndMustBeAQuotedPositiveDecimal() {
    Account account = registerWithConsent();
    String attemptId = createAttempt(account, visibleRouteId());

    ErrorEnvelope missing =
        assertError(
            patchAttempt(account.token(), attemptId, null, "{\"sent\":false}"),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(missing.fieldErrors())
        .containsExactly(new FieldError("If-Match", "required", "header is required"));
    for (String bad : List.of("1", "\"0\"", "\"01\"", "W/\"1\"", "\"\"", "*")) {
      ErrorEnvelope envelope =
          assertError(
              patchAttempt(account.token(), attemptId, bad, "{\"sent\":false}"),
              HttpStatus.UNPROCESSABLE_CONTENT,
              ErrorCode.VALIDATION_FAILED);
      assertThat(envelope.fieldErrors())
          .as(bad)
          .extracting(FieldError::field, FieldError::code)
          .containsExactly(Tuple.tuple("If-Match", "pattern"));
    }
    ErrorEnvelope huge =
        assertError(
            patchAttempt(account.token(), attemptId, "\"99999999999\"", "{\"sent\":false}"),
            HttpStatus.CONFLICT,
            ErrorCode.REVISION_CONFLICT);
    assertThat(huge.currentRevision()).isEqualTo(1);
    assertThat(revisionRows(UUID.fromString(attemptId))).hasSize(1);
  }

  @Test
  void patchBodyRulesAreFieldErrors() {
    Account account = registerWithConsent();
    String attemptId = createAttempt(account, visibleRouteId());

    assertThat(patchError(account, attemptId, "{}", ErrorCode.VALIDATION_FAILED).fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("body", "minProperties"));
    assertThat(
            patchError(
                    account,
                    attemptId,
                    "{\"retract\":true,\"sent\":false}",
                    ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("retract", "exclusive"));
    assertThat(
            patchError(account, attemptId, "{\"retract\":false}", ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("retract", "const"));
    assertThat(
            patchError(account, attemptId, "{\"attempts_count\":0}", ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .containsExactly(new FieldError("attempts_count", "minimum", "must be >= 1"));
    assertThat(
            patchError(
                    account,
                    attemptId,
                    "{\"occurred_at\":\"2026-10-14T14:00:00Z\"}",
                    ErrorCode.UNKNOWN_FIELD)
                .fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("occurred_at");
    assertThat(
            patchError(account, attemptId, "{\"notes\":null}", ErrorCode.VALIDATION_FAILED)
                .fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(Tuple.tuple("notes", "type"));
    assertThat(revisionRows(UUID.fromString(attemptId))).hasSize(1);

    ResponseEntity<String> full =
        patchAttempt(
            account.token(),
            attemptId,
            "\"1\"",
            "{\"attempts_count\":5,\"completion_fraction\":0.5,\"perceived_effort\":9,\"notes\":\"better\"}");
    assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = tree(full.getBody());
    assertThat(body.get("attempts_count").asInt()).isEqualTo(5);
    assertThat(body.get("completion_fraction").asDouble()).isEqualTo(0.5);
    assertThat(body.get("perceived_effort").asInt()).isEqualTo(9);
    assertThat(body.get("notes").asString()).isEqualTo("better");
    assertThat(body.get("sent").asBoolean()).isTrue();
    assertThat(body.get("revision").asInt()).isEqualTo(2);
  }

  private ErrorEnvelope patchError(Account account, String attemptId, String body, ErrorCode code) {
    return assertError(
        patchAttempt(account.token(), attemptId, "\"1\"", body),
        HttpStatus.UNPROCESSABLE_CONTENT,
        code);
  }

  private ResponseEntity<String> patchWithRequestId(
      String token, String attemptId, String ifMatch, String body, String requestId) {
    return client()
        .patch()
        .uri(ATTEMPTS + "/" + attemptId)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set(AttemptService.IF_MATCH_HEADER, ifMatch);
              h.set(RequestIdFilter.HEADER, requestId);
            })
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }
}
