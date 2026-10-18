package com.beaconclimb.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class AttemptEventParserTest {

  @Test
  @DisplayName("parses the committed ATTEMPT_RECORDED example; grade_numeric absent is null")
  void parsesRecordedExampleWithoutGrade() {
    ObjectNode example = ExampleEvents.example("valid-attempt-recorded");
    // The committed example may carry the optional grade_numeric; this case is its absence.
    ((ObjectNode) example.get("attributes")).remove("grade_numeric");
    byte[] raw = ExampleEvents.bytes(example);

    AttemptEvent event = AttemptEventParser.parse(raw);

    assertThat(event.eventId()).isEqualTo("018f0000-0000-7000-8000-000000000005");
    assertThat(event.eventType()).isEqualTo("ATTEMPT_RECORDED");
    assertThat(event.userId()).isEqualTo(ExampleEvents.USER_ID);
    assertThat(event.attemptId()).isEqualTo("018f0000-0000-7000-8000-000000000002");
    assertThat(event.attemptRevision()).isEqualTo(1);
    assertThat(event.routeVariantId()).isEqualTo("synthetic-route-001:35");
    assertThat(event.occurredAtMillis())
        .isEqualTo(Instant.parse("2026-10-14T14:00:00Z").toEpochMilli());
    assertThat(event.sent()).isTrue();
    assertThat(event.status()).isEqualTo("ACTIVE");
    assertThat(event.gradeNumeric()).isNull();
    assertThat(event.raw()).isEqualTo(raw);
  }

  @ParameterizedTest
  @ValueSource(strings = {"valid-attempt-corrected", "valid-attempt-retracted"})
  @DisplayName("every committed valid example parses")
  void parsesEveryValidExample(String name) {
    AttemptEvent event = AttemptEventParser.parse(ExampleEvents.bytes(ExampleEvents.example(name)));

    assertThat(event.userId()).isEqualTo(ExampleEvents.USER_ID);
  }

  @Test
  @DisplayName("the optional attributes.grade_numeric is read when present (number or null)")
  void readsOptionalGradeNumeric() {
    ObjectNode withGrade = ExampleEvents.example("valid-attempt-recorded");
    ((ObjectNode) withGrade.get("attributes")).put("grade_numeric", 17.5);
    ObjectNode nullGrade = ExampleEvents.example("valid-attempt-recorded");
    ((ObjectNode) nullGrade.get("attributes")).putNull("grade_numeric");

    assertThat(AttemptEventParser.parse(ExampleEvents.bytes(withGrade)).gradeNumeric())
        .isEqualTo(17.5);
    assertThat(AttemptEventParser.parse(ExampleEvents.bytes(nullGrade)).gradeNumeric()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"notes", "note", "Notes", "comment", "free_text", "grade"})
  @DisplayName("an attribute outside the closed allowlist is a parse error (C-01, TM-11)")
  void rejectsUnknownAttribute(String key) {
    ObjectNode event = ExampleEvents.example("valid-attempt-recorded");
    ((ObjectNode) event.get("attributes")).put(key, "anything");

    assertThatThrownBy(() -> AttemptEventParser.parse(ExampleEvents.bytes(event)))
        .isInstanceOf(AttemptEventParser.ParseException.class)
        .hasMessageContaining("unknown attributes key '" + key + "'");
  }

  @Test
  @DisplayName("an unknown top-level member is a parse error")
  void rejectsUnknownTopLevelKey() {
    ObjectNode event = ExampleEvents.example("valid-attempt-recorded");
    event.put("extra", 1);

    assertThatThrownBy(() -> AttemptEventParser.parse(ExampleEvents.bytes(event)))
        .isInstanceOf(AttemptEventParser.ParseException.class)
        .hasMessageContaining("unknown event key 'extra'");
  }

  @Test
  @DisplayName(
      "the committed invalid examples (missing revision, notes, bad timestamp) are rejected")
  void rejectsCommittedInvalidExamples() {
    for (String name :
        new String[] {
          "invalid-attempt-missing-revision",
          "invalid-attempt-notes",
          "invalid-attempt-bad-timestamp"
        }) {
      byte[] raw = ExampleEvents.bytes(ExampleEvents.example(name));
      assertThatThrownBy(() -> AttemptEventParser.parse(raw))
          .as(name)
          .isInstanceOf(AttemptEventParser.ParseException.class);
    }
  }

  @Test
  @DisplayName(
      "non-JSON bytes and non-object documents are parse errors, never exceptions of another kind")
  void rejectsGarbage() {
    assertThatThrownBy(() -> AttemptEventParser.parse("not json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AttemptEventParser.ParseException.class);
    assertThatThrownBy(() -> AttemptEventParser.parse("[1,2]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(AttemptEventParser.ParseException.class)
        .hasMessageContaining("JSON object");
  }

  @Test
  @DisplayName("a wrong event_type, status or revision is a parse error")
  void rejectsBadEnumsAndRevision() {
    ObjectNode badType = ExampleEvents.example("valid-attempt-recorded");
    badType.put("event_type", "ATTEMPT_DELETED");
    ObjectNode badStatus = ExampleEvents.example("valid-attempt-recorded");
    ((ObjectNode) badStatus.get("attributes")).put("status", "PENDING");
    ObjectNode badRevision = ExampleEvents.example("valid-attempt-recorded");
    badRevision.put("attempt_revision", 0);

    for (ObjectNode event : new ObjectNode[] {badType, badStatus, badRevision}) {
      assertThatThrownBy(() -> AttemptEventParser.parse(ExampleEvents.bytes(event)))
          .isInstanceOf(AttemptEventParser.ParseException.class);
    }
  }
}
