package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0123 on notes: an idempotent replay carries the row's current notes, so a note that was
 * blanked by a correction is not resurrected by retrying the original submission (F12), and a NUL
 * character in notes is 422 VALIDATION_FAILED instead of a driver error (I1).
 */
class AttemptNotesIT extends AttemptsTestSupport {

  @Test
  void replayReturnsTheCurrentNotesNotTheStoredOnes() {
    Account account = registerWithConsent();
    String key = UUID.randomUUID().toString();
    Map<String, Object> body = attemptBody(visibleRouteId(), Instant.now().minusSeconds(60));
    body.put("notes", "secret");

    ResponseEntity<String> created = postAttempt(account.token(), key, body);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    JsonNode createdBody = tree(created.getBody());
    assertThat(createdBody.get("notes").asString()).isEqualTo("secret");
    String attemptId = createdBody.get("attempt_id").asString();

    ResponseEntity<String> blanked =
        patchAttempt(account.token(), attemptId, "\"1\"", "{\"notes\":\"\"}");
    assertThat(blanked.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(tree(blanked.getBody()).get("notes").asString()).isEmpty();

    ResponseEntity<String> replayed = postAttempt(account.token(), key, body);

    assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode replayedBody = tree(replayed.getBody());
    assertThat(replayedBody.get("notes").asString()).isEmpty();
    assertThat(replayedBody.get("revision").asInt()).isEqualTo(1);
    assertThat(replayedBody.get("attempt_id").asString()).isEqualTo(attemptId);
    assertThat(storedResponseBody(UUID.fromString(attemptId)).get("notes").asString())
        .as("the persisted 201 body is never rewritten; the replay substitutes the current notes")
        .isEqualTo("secret");
    assertThat(attemptRows(UUID.fromString(attemptId))).isEqualTo(1);
  }

  @Test
  void nulCharacterInNotesIsRejectedOnCreateAndPatch() {
    Account account = registerWithConsent();
    Map<String, Object> body = attemptBody(visibleRouteId(), Instant.now().minusSeconds(60));
    body.put("notes", "crux\u0000move");

    ErrorEnvelope create =
        assertError(
            postAttempt(account.token(), UUID.randomUUID().toString(), body),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(create.fieldErrors())
        .containsExactly(new FieldError("notes", "pattern", "must not contain NUL characters"));

    String attemptId = createAttempt(account, visibleRouteId());
    ErrorEnvelope patch =
        assertError(
            patchAttempt(account.token(), attemptId, "\"1\"", "{\"notes\":\"\\u0000\"}"),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(patch.fieldErrors())
        .containsExactly(new FieldError("notes", "pattern", "must not contain NUL characters"));
    assertThat(revisionRows(UUID.fromString(attemptId))).hasSize(1);
  }

  private JsonNode storedResponseBody(UUID attemptId) {
    return tree(
        jdbc.sql("SELECT response_body_json::text FROM attempts WHERE attempt_id = :id")
            .param("id", attemptId)
            .query(String.class)
            .single());
  }
}
