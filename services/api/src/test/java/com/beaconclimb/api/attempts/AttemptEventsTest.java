package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0109-OUTBOX / TM-11: every event type the service emits validates against the bundled
 * attempts.v1 schema, the attributes are the closed allowlist, notes never appear, a contract
 * violation is a server error, and the committed contract examples agree with the bundled copy.
 */
class AttemptEventsTest {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "events", "examples").toAbsolutePath().normalize();
  private static final Set<String> ATTRIBUTE_ALLOWLIST =
      Set.of(
          "sent",
          "attempts_count",
          "status",
          "completion_fraction",
          "perceived_effort",
          "grade_numeric");

  private final JsonMapper json = JsonMapper.builder().build();
  private final AttemptEventSchema schema = new AttemptEventSchema();
  private final AttemptEvents events = new AttemptEvents(schema, json);

  @ParameterizedTest
  @EnumSource(AttemptChange.class)
  void everyChangeTypeProducesAConformingPayloadWithoutNotes(AttemptChange change) {
    int revision = change == AttemptChange.RECORDED ? 1 : 3;
    AttemptStatus status =
        change == AttemptChange.RETRACTED ? AttemptStatus.RETRACTED : AttemptStatus.ACTIVE;
    AttemptRow row = row(revision, status, 0.75, 7, "secret note text", 5.5);
    UUID eventId = UUID.fromString("018f0000-0000-7000-8000-000000000005");

    String payload =
        events.payload(change, eventId, row, Instant.parse("2026-10-14T14:00:01Z"), "trace-1");

    JsonNode event = json.readTree(payload);
    assertThat(schema.validate(event)).isEmpty();
    assertThat(event.get("event_type").asString()).isEqualTo(change.eventType());
    assertThat(event.get("attempt_revision").asInt()).isEqualTo(revision);
    assertThat(event.get("event_id").asString()).isEqualTo(eventId.toString());
    assertThat(event.get("trace_id").asString()).isEqualTo("trace-1");
    assertThat(event.get("producer").asString()).isEqualTo("api");
    assertThat(event.get("ingested_at").asString()).isEqualTo("2026-10-14T14:00:01Z");
    assertThat(event.get("attributes").get("status").asString()).isEqualTo(status.name());
    assertThat(event.get("attributes").get("completion_fraction").asDouble()).isEqualTo(0.75);
    assertThat(event.get("attributes").get("perceived_effort").asInt()).isEqualTo(7);
    assertThat(event.get("attributes").get("grade_numeric").asDouble()).isEqualTo(5.5);
    assertThat(event.get("attributes").propertyNames()).isSubsetOf(ATTRIBUTE_ALLOWLIST);
    assertThat(payload).doesNotContain("note");
  }

  /** completion_fraction and perceived_effort are omitted; grade_numeric is an explicit null. */
  @Test
  void optionalAttributesAreOmittedWhenNullAndAnUngradedVariantIsAnExplicitNull() {
    AttemptRow row = row(1, AttemptStatus.ACTIVE, null, null, null, null);

    JsonNode event =
        json.readTree(
            events.payload(AttemptChange.RECORDED, UUID.randomUUID(), row, Instant.now(), "t"));

    assertThat(schema.validate(event)).isEmpty();
    assertThat(event.get("attributes").propertyNames())
        .containsExactlyInAnyOrder("sent", "attempts_count", "status", "grade_numeric");
    assertThat(event.get("attributes").get("grade_numeric").isNull()).isTrue();
  }

  @Test
  void contractViolationIsAServerErrorNotAClientError() {
    AttemptRow recordedAtRevisionTwo = row(2, AttemptStatus.ACTIVE, null, null, null, null);

    assertThatThrownBy(
            () ->
                events.payload(
                    AttemptChange.RECORDED,
                    UUID.randomUUID(),
                    recordedAtRevisionTwo,
                    Instant.now(),
                    "t"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("attempts.v1");
  }

  @Test
  void committedContractExamplesAgreeWithTheBundledSchema() throws IOException {
    List<String> valid =
        List.of(
            "valid-attempt-recorded.json",
            "valid-attempt-corrected.json",
            "valid-attempt-retracted.json");
    List<String> invalid =
        List.of(
            "invalid-attempt-notes.json",
            "invalid-attempt-renamed-notes-note.json",
            "invalid-attempt-renamed-notes-Notes.json",
            "invalid-attempt-renamed-notes-comment.json",
            "invalid-attempt-renamed-notes-free_text.json",
            "invalid-attempt-missing-revision.json",
            "invalid-attempt-bad-timestamp.json",
            "invalid-attempt-grade-numeric-string.json");
    for (String name : valid) {
      assertThat(schema.validate(example(name))).as(name).isEmpty();
    }
    for (String name : invalid) {
      assertThat(schema.validate(example(name))).as(name).isNotEmpty();
    }
  }

  private JsonNode example(String name) throws IOException {
    return json.readTree(Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8))
        .get("event");
  }

  private static AttemptRow row(
      int revision,
      AttemptStatus status,
      Double fraction,
      Integer effort,
      String notes,
      Double gradeNumeric) {
    return new AttemptRow(
        UUID.fromString("018f0000-0000-7000-8000-000000000002"),
        UUID.fromString("018f0000-0000-7000-8000-000000000001"),
        UUID.randomUUID(),
        "synthetic-route-001:35",
        Instant.parse("2026-10-14T14:00:00Z"),
        Instant.parse("2026-10-14T14:00:01Z"),
        2,
        false,
        fraction,
        effort,
        notes,
        revision,
        status,
        "0".repeat(64),
        "{}",
        new RouteVariantSummaryView("synthetic-route-001:35", "b", 35, "n", gradeNumeric, null, 0));
  }
}
