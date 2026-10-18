package com.beaconclimb.api.dlq;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.outbox.KafkaTestContainer;
import com.beaconclimb.api.outbox.StreamTestSupport;
import com.beaconclimb.events.DlqHeaders;
import com.beaconclimb.events.DlqRecord;
import com.beaconclimb.events.ErrorCode;
import com.beaconclimb.events.EventSchemas;
import com.beaconclimb.events.EventValidator;
import com.beaconclimb.events.ValidationResult;
import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * BC-0302 (a): the api-side DLQ writer against the Testcontainers broker. A record the shared
 * validator rejects is published to stream-dlq.v1 as a schema-valid DLQ record whose base64 is the
 * ORIGINAL bytes, keyed by the original key, with the DLQ headers next to the original ones.
 */
class DlqPublisherIT extends StreamTestSupport {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "events", "examples").toAbsolutePath().normalize();
  private static final String USER_ID = "018f0000-0000-7000-8000-000000000001";

  @Autowired DlqPublisher dlqPublisher;
  @Autowired EventValidator validator;

  @Test
  @DisplayName("notes present -> NOTES_PRESENT DLQ record with original bytes, key and headers")
  void notesPresentLandsInDlq() throws IOException {
    byte[] rejected = example("invalid-attempt-notes.json");
    ValidationResult verdict = validator.validate(EventSchemas.ATTEMPTS, rejected);
    assertThat(verdict).isInstanceOf(Invalid.class);
    ConsumerRecord<String, byte[]> source =
        consumerRecord(
            rejected, 3, 42L, Map.of("event_id", "018f0000-0000-7000-8000-000000000005"));

    DlqRecord published = dlqPublisher.publish(source, (Invalid) verdict);

    ConsumerRecord<String, byte[]> onTopic = findOnDlq(published.eventId().toString());
    assertThat(onTopic.key()).isEqualTo(USER_ID);
    JsonNode json = validDlq(onTopic.value());
    assertThat(json.get("original_topic").asString()).isEqualTo("attempts.v1");
    assertThat(json.get("original_key").asString()).isEqualTo(USER_ID);
    assertThat(Base64.getDecoder().decode(json.get("original_value_base64").asString()))
        .isEqualTo(rejected);
    assertThat(json.get("error_code").asString()).isEqualTo("NOTES_PRESENT");
    assertThat(json.get("error_message").asString())
        .contains("/attributes/notes")
        .doesNotContain("private note");
    assertThat(json.get("attempt").asInt()).isEqualTo(1);
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.ORIGINAL_TOPIC))
        .isEqualTo("attempts.v1");
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.ORIGINAL_PARTITION)).isEqualTo("3");
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.ORIGINAL_OFFSET)).isEqualTo("42");
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.ATTEMPT)).isEqualTo("1");
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.ERROR_CODE))
        .isEqualTo("NOTES_PRESENT");
    assertThat(KafkaTestContainer.header(onTopic, DlqHeaders.CONSUMER)).isEqualTo("api");
    assertThat(KafkaTestContainer.header(onTopic, "event_id"))
        .isEqualTo("018f0000-0000-7000-8000-000000000005");
    assertThat(
            meters.get(DlqPublisher.PUBLISHED).tag("error_code", "NOTES_PRESENT").counter().count())
        .isGreaterThanOrEqualTo(1.0);
  }

  @Test
  @DisplayName("unknown attribute -> UNKNOWN_ATTRIBUTE; a redriven record fails as attempt 2")
  void unknownAttributeAndRedrivenAttempt() throws IOException {
    byte[] rejected = example("invalid-attempt-renamed-notes-comment.json");
    Invalid verdict = (Invalid) validator.validate(EventSchemas.ATTEMPTS, rejected);
    assertThat(verdict.errorCode()).isEqualTo(ErrorCode.UNKNOWN_ATTRIBUTE);

    DlqRecord first = dlqPublisher.publish(consumerRecord(rejected, 0, 7L, Map.of()), verdict);
    DlqRecord second =
        dlqPublisher.publish(
            consumerRecord(
                rejected,
                0,
                9L,
                Map.of(
                    DlqHeaders.ATTEMPT, "2", DlqHeaders.REDRIVEN_FROM, first.eventId().toString())),
            verdict);

    JsonNode firstJson = validDlq(findOnDlq(first.eventId().toString()).value());
    ConsumerRecord<String, byte[]> secondOnTopic = findOnDlq(second.eventId().toString());
    JsonNode secondJson = validDlq(secondOnTopic.value());
    assertThat(firstJson.get("error_code").asString()).isEqualTo("UNKNOWN_ATTRIBUTE");
    assertThat(firstJson.get("error_message").asString()).contains("comment");
    assertThat(firstJson.get("attempt").asInt()).isEqualTo(1);
    assertThat(secondJson.get("attempt").asInt()).isEqualTo(2);
    assertThat(KafkaTestContainer.header(secondOnTopic, DlqHeaders.ATTEMPT)).isEqualTo("2");
    assertThat(KafkaTestContainer.header(secondOnTopic, DlqHeaders.REDRIVEN_FROM)).isNull();
  }

  private JsonNode validDlq(byte[] value) {
    ValidationResult result = validator.validate(EventSchemas.DLQ, value);
    assertThat(result).as(new String(value, StandardCharsets.UTF_8)).isInstanceOf(Valid.class);
    return ((Valid) result).payload();
  }

  private static ConsumerRecord<String, byte[]> findOnDlq(String dlqEventId) {
    Optional<ConsumerRecord<String, byte[]>> found =
        KafkaTestContainer.readToEnd(KafkaTestContainer.DLQ_TOPIC).stream()
            .filter(r -> new String(r.value(), StandardCharsets.UTF_8).contains(dlqEventId))
            .findFirst();
    assertThat(found).as("DLQ record " + dlqEventId).isPresent();
    return found.get();
  }

  private static ConsumerRecord<String, byte[]> consumerRecord(
      byte[] value, int partition, long offset, Map<String, String> headers) {
    RecordHeaders recordHeaders = new RecordHeaders();
    headers.forEach((k, v) -> recordHeaders.add(k, v.getBytes(StandardCharsets.UTF_8)));
    return new ConsumerRecord<>(
        KafkaTestContainer.ATTEMPTS_TOPIC,
        partition,
        offset,
        -1L,
        TimestampType.NO_TIMESTAMP_TYPE,
        -1,
        -1,
        USER_ID,
        value,
        recordHeaders,
        Optional.empty());
  }

  private static byte[] example(String name) throws IOException {
    JsonNode wrapper =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8));
    return wrapper.get("event").toString().getBytes(StandardCharsets.UTF_8);
  }
}
