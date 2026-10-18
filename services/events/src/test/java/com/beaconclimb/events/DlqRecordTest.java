package com.beaconclimb.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DlqRecordTest {

  static final String USER_ID = "018f0000-0000-7000-8000-000000000001";
  static final Instant FAILED_AT = Instant.parse("2026-10-18T10:00:00.123456Z");

  private final EventValidator validator = new EventValidator();
  private final byte[] rejected = Examples.bytes(Examples.example("invalid-attempt-notes").event());

  @Test
  @DisplayName(
      "a DLQ record is schema-valid, keeps the original bytes base64 and carries the headers")
  void buildsSchemaValidRecord() {
    Invalid invalid = (Invalid) validator.validate(EventSchemas.ATTEMPTS, rejected);
    Map<String, String> originalHeaders = new LinkedHashMap<>();
    originalHeaders.put("event_id", "018f0000-0000-7000-8000-000000000005");
    originalHeaders.put("event_type", "ATTEMPT_RECORDED");
    originalHeaders.put("schema_version", "1");
    UUID eventId = Uuid7.next();

    DlqRecord record =
        DlqRecord.builder()
            .eventId(eventId)
            .originalTopic(EventSchemas.ATTEMPTS)
            .originalKey(USER_ID)
            .originalValue(rejected)
            .originalPartition(3)
            .originalOffset(42L)
            .originalHeaders(originalHeaders)
            .error(invalid)
            .consumer("stream")
            .failedAt(FAILED_AT)
            .build();

    ValidationResult result = validator.validate(EventSchemas.DLQ, record.json());
    assertThat(result).isInstanceOf(Valid.class);
    JsonNode json = ((Valid) result).payload();
    assertThat(json.get("schema_version").asInt()).isEqualTo(1);
    assertThat(json.get("event_id").asString()).isEqualTo(eventId.toString());
    assertThat(json.get("original_topic").asString()).isEqualTo("attempts.v1");
    assertThat(json.get("original_key").asString()).isEqualTo(USER_ID);
    assertThat(Base64.getDecoder().decode(json.get("original_value_base64").asString()))
        .isEqualTo(rejected);
    assertThat(json.get("error_code").asString()).isEqualTo("NOTES_PRESENT");
    assertThat(json.get("error_message").asString()).isEqualTo(invalid.message());
    assertThat(json.get("attempt").asInt()).isEqualTo(1);
    assertThat(json.get("failed_at").asString()).isEqualTo("2026-10-18T10:00:00.123Z");
    assertThat(json.propertyNames())
        .containsExactly(
            "schema_version",
            "event_id",
            "original_topic",
            "original_key",
            "original_value_base64",
            "error_code",
            "error_message",
            "attempt",
            "failed_at");
    assertThat(record.headers())
        .containsExactly(
            Map.entry("event_id", "018f0000-0000-7000-8000-000000000005"),
            Map.entry("event_type", "ATTEMPT_RECORDED"),
            Map.entry("schema_version", "1"),
            Map.entry(DlqHeaders.ORIGINAL_TOPIC, "attempts.v1"),
            Map.entry(DlqHeaders.ORIGINAL_PARTITION, "3"),
            Map.entry(DlqHeaders.ORIGINAL_OFFSET, "42"),
            Map.entry(DlqHeaders.ATTEMPT, "1"),
            Map.entry(DlqHeaders.ERROR_CODE, "NOTES_PRESENT"),
            Map.entry(DlqHeaders.CONSUMER, "stream"));
  }

  @Test
  @DisplayName(
      "the attempt comes from the incoming dlq.attempt header; earlier dlq.* headers are replaced")
  void attemptFromIncomingHeader() {
    Map<String, String> redriven =
        Map.of(
            "event_id",
            "x",
            DlqHeaders.ATTEMPT,
            "2",
            DlqHeaders.REDRIVEN_FROM,
            "018f0000-0000-7000-8000-0000000000aa",
            DlqHeaders.ERROR_CODE,
            "NOTES_PRESENT",
            DlqHeaders.CONSUMER,
            "api");

    DlqRecord record =
        DlqRecord.builder()
            .originalTopic(EventSchemas.ATTEMPTS)
            .originalKey(USER_ID)
            .originalValue(rejected)
            .originalHeaders(redriven)
            .error(ErrorCode.NOTES_PRESENT, "again")
            .consumer("stream")
            .build();

    assertThat(record.attempt()).isEqualTo(2);
    assertThat(record.headers())
        .containsEntry(DlqHeaders.ATTEMPT, "2")
        .containsEntry(DlqHeaders.CONSUMER, "stream")
        .containsEntry("event_id", "x")
        .doesNotContainKey(DlqHeaders.REDRIVEN_FROM)
        .doesNotContainKey(DlqHeaders.ORIGINAL_PARTITION);
    assertThat(DlqHeaders.attemptOf(Map.of(DlqHeaders.ATTEMPT, "junk"))).isEqualTo(1);
    assertThat(DlqHeaders.attemptOf(Map.of(DlqHeaders.ATTEMPT, "0"))).isEqualTo(1);
    assertThat(DlqHeaders.attemptOf(Map.of())).isEqualTo(1);
    assertThat(DlqHeaders.attemptOf(null)).isEqualTo(1);
  }

  @Test
  @DisplayName("a null Kafka key is stored as the empty string; non-JSON bytes are kept verbatim")
  void nullKeyAndRawBytes() {
    byte[] junk = new byte[] {0, (byte) 0xff, 1, 2, 3};

    DlqRecord record =
        DlqRecord.builder()
            .originalTopic(EventSchemas.ATTEMPTS)
            .originalKey(null)
            .originalValue(junk)
            .error(ErrorCode.NOT_JSON, "not a JSON document")
            .consumer("api")
            .build();

    JsonNode json = ((Valid) validator.validate(EventSchemas.DLQ, record.json())).payload();
    assertThat(json.get("original_key").asString()).isEmpty();
    assertThat(Base64.getDecoder().decode(json.get("original_value_base64").asString()))
        .isEqualTo(junk);
    assertThat(record.originalValue()).isEqualTo(junk);
    assertThat(new String(record.json(), StandardCharsets.UTF_8)).contains("\"attempt\":1");
  }

  @Test
  @DisplayName(
      "the builder refuses a record without topic, value, error or consumer, or attempt < 1")
  void builderRequirements() {
    assertThatThrownBy(() -> DlqRecord.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("originalTopic");
    assertThatThrownBy(
            () ->
                DlqRecord.builder()
                    .originalTopic("t")
                    .originalValue(new byte[0])
                    .error(ErrorCode.NOT_JSON, "m")
                    .consumer("api")
                    .attempt(0)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("attempt");
    assertThatThrownBy(
            () -> DlqRecord.builder().originalTopic("t").originalValue(new byte[0]).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("errorCode");
  }
}
