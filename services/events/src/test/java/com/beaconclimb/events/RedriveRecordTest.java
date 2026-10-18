package com.beaconclimb.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.events.ValidationResult.Invalid;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class RedriveRecordTest {

  static final String USER_ID = "018f0000-0000-7000-8000-000000000001";

  private final EventValidator validator = new EventValidator();
  private final byte[] rejected =
      Examples.bytes(Examples.example("invalid-attempt-renamed-notes-comment").event());

  private DlqRecord firstFailure() {
    Invalid invalid = (Invalid) validator.validate(EventSchemas.ATTEMPTS, rejected);
    return DlqRecord.builder()
        .originalTopic(EventSchemas.ATTEMPTS)
        .originalKey(USER_ID)
        .originalValue(rejected)
        .originalPartition(5)
        .originalOffset(7L)
        .originalHeaders(Map.of("event_id", "018f0000-0000-7000-8000-000000000005"))
        .error(invalid)
        .consumer("stream")
        .build();
  }

  @Test
  @DisplayName(
      "DLQ -> redrive -> second failure: bytes identical, attempt 1 -> 2 -> 2, redriven_from set")
  void roundTrip() {
    DlqRecord first = firstFailure();

    RedriveRecord redrive = RedriveRecord.from(validator, first.json(), first.headers());

    assertThat(redrive.topic()).isEqualTo("attempts.v1");
    assertThat(redrive.key()).isEqualTo(USER_ID);
    assertThat(redrive.value()).isEqualTo(rejected);
    assertThat(redrive.dlqEventId()).isEqualTo(first.eventId().toString());
    assertThat(redrive.attempt()).isEqualTo(2);
    assertThat(redrive.headers())
        .containsEntry("event_id", "018f0000-0000-7000-8000-000000000005")
        .containsEntry(DlqHeaders.ORIGINAL_TOPIC, "attempts.v1")
        .containsEntry(DlqHeaders.ORIGINAL_PARTITION, "5")
        .containsEntry(DlqHeaders.ORIGINAL_OFFSET, "7")
        .containsEntry(DlqHeaders.ERROR_CODE, "UNKNOWN_ATTRIBUTE")
        .containsEntry(DlqHeaders.CONSUMER, "stream")
        .containsEntry(DlqHeaders.ATTEMPT, "2")
        .containsEntry(DlqHeaders.REDRIVEN_FROM, first.eventId().toString());

    // The redriven record fails again at the consumer: the next DLQ record is attempt 2.
    DlqRecord second =
        DlqRecord.builder()
            .originalTopic(redrive.topic())
            .originalKey(redrive.key())
            .originalValue(redrive.value())
            .originalHeaders(redrive.headers())
            .error((Invalid) validator.validate(redrive.topic(), redrive.value()))
            .consumer("stream")
            .build();
    assertThat(second.attempt()).isEqualTo(2);
    assertThat(second.originalValue()).isEqualTo(rejected);
    assertThat(second.headers())
        .containsEntry(DlqHeaders.ATTEMPT, "2")
        .containsEntry("event_id", "018f0000-0000-7000-8000-000000000005")
        .doesNotContainKey(DlqHeaders.REDRIVEN_FROM);
    assertThat(RedriveRecord.from(validator, second.json(), second.headers()).attempt())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("a DLQ record without headers still redrives from its value alone")
  void redrivesWithoutHeaders() {
    DlqRecord first = firstFailure();

    RedriveRecord redrive = RedriveRecord.from(validator, first.json(), Map.of());

    assertThat(redrive.value()).isEqualTo(rejected);
    assertThat(redrive.headers())
        .containsEntry(DlqHeaders.ORIGINAL_TOPIC, "attempts.v1")
        .containsEntry(DlqHeaders.ERROR_CODE, "UNKNOWN_ATTRIBUTE")
        .containsEntry(DlqHeaders.ATTEMPT, "2")
        .containsEntry(DlqHeaders.REDRIVEN_FROM, first.eventId().toString())
        .doesNotContainKey(DlqHeaders.ORIGINAL_PARTITION);
  }

  @Test
  @DisplayName("an empty original_key becomes a null Kafka key again")
  void emptyKeyIsNull() {
    DlqRecord record =
        DlqRecord.builder()
            .originalTopic(EventSchemas.ATTEMPTS)
            .originalKey(null)
            .originalValue(rejected)
            .error(ErrorCode.NOT_JSON, "m")
            .consumer("api")
            .build();

    assertThat(RedriveRecord.from(validator, record.json(), record.headers()).key()).isNull();
  }

  @Test
  @DisplayName("a non-conforming DLQ value or undecodable base64 is refused, never guessed")
  void refusesBadDlqRecords() {
    ObjectNode badAttempt = Examples.example("invalid-stream-dlq-attempt").event();
    ObjectNode badBase64 = Examples.example("valid-stream-dlq").event();
    badBase64.put("original_value_base64", "not*base64");

    assertThatThrownBy(() -> RedriveRecord.from(validator, Examples.bytes(badAttempt), Map.of()))
        .isInstanceOf(RedriveRecord.InvalidDlqRecordException.class)
        .extracting(e -> ((RedriveRecord.InvalidDlqRecordException) e).errorCode())
        .isEqualTo(ErrorCode.SCHEMA_VIOLATION);
    assertThatThrownBy(() -> RedriveRecord.from(validator, Examples.utf8("junk"), Map.of()))
        .isInstanceOf(RedriveRecord.InvalidDlqRecordException.class)
        .extracting(e -> ((RedriveRecord.InvalidDlqRecordException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_JSON);
    assertThatThrownBy(() -> RedriveRecord.from(validator, Examples.bytes(badBase64), Map.of()))
        .isInstanceOf(RedriveRecord.InvalidDlqRecordException.class)
        .hasMessageContaining("base64");
  }
}
