package com.beaconclimb.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One stream-dlq.v1 record (contracts/events/stream-dlq.v1.schema.json): the ORIGINAL bytes of a
 * rejected record, base64 inside the value and never re-serialised, plus why it failed and which
 * attempt this was. {@link #json()} is the Kafka value, {@link #headers()} the headers to send with
 * it ({@link DlqHeaders}), {@link #originalKey()} the Kafka key, so a DLQ record partitions like
 * the original did. Build one with {@link #builder()}.
 */
public final class DlqRecord {

  public static final int SCHEMA_VERSION = 1;

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final UUID eventId;
  private final String originalTopic;
  private final String originalKey;
  private final byte[] originalValue;
  private final Integer originalPartition;
  private final Long originalOffset;
  private final Map<String, String> originalHeaders;
  private final String errorCode;
  private final String errorMessage;
  private final int attempt;
  private final Instant failedAt;
  private final String consumer;

  private DlqRecord(Builder b) {
    this.eventId = b.eventId == null ? Uuid7.next() : b.eventId;
    this.originalTopic = require(b.originalTopic, "originalTopic");
    this.originalKey = b.originalKey == null ? "" : b.originalKey;
    this.originalValue = require(b.originalValue, "originalValue").clone();
    this.originalPartition = b.originalPartition;
    this.originalOffset = b.originalOffset;
    this.originalHeaders = DlqHeaders.withoutDlq(b.originalHeaders);
    this.errorCode = require(b.errorCode, "errorCode");
    this.errorMessage = require(b.errorMessage, "errorMessage");
    this.attempt = b.attempt != null ? b.attempt : DlqHeaders.attemptOf(b.originalHeaders);
    if (attempt < DlqHeaders.FIRST_ATTEMPT) {
      throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
    }
    this.failedAt =
        (b.failedAt == null ? Instant.now() : b.failedAt).truncatedTo(ChronoUnit.MILLIS);
    this.consumer = require(b.consumer, "consumer");
  }

  public static Builder builder() {
    return new Builder();
  }

  public UUID eventId() {
    return eventId;
  }

  public String originalTopic() {
    return originalTopic;
  }

  /** The original Kafka key; the empty string stands for a null key. */
  public String originalKey() {
    return originalKey;
  }

  public byte[] originalValue() {
    return originalValue.clone();
  }

  public Integer originalPartition() {
    return originalPartition;
  }

  public Long originalOffset() {
    return originalOffset;
  }

  public String errorCode() {
    return errorCode;
  }

  public String errorMessage() {
    return errorMessage;
  }

  public int attempt() {
    return attempt;
  }

  public Instant failedAt() {
    return failedAt;
  }

  public String consumer() {
    return consumer;
  }

  /** The stream-dlq.v1 value, UTF-8 JSON in schema order. */
  public byte[] json() {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("schema_version", SCHEMA_VERSION);
    node.put("event_id", eventId.toString());
    node.put("original_topic", originalTopic);
    node.put("original_key", originalKey);
    node.put("original_value_base64", Base64.getEncoder().encodeToString(originalValue));
    node.put("error_code", errorCode);
    node.put("error_message", errorMessage);
    node.put("attempt", attempt);
    node.put("failed_at", DateTimeFormatter.ISO_INSTANT.format(failedAt));
    return MAPPER.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The original record's own headers (without any earlier {@code dlq.*}) followed by the DLQ
   * headers; partition and offset only when the builder knew them.
   */
  public Map<String, String> headers() {
    Map<String, String> headers = new LinkedHashMap<>(originalHeaders);
    headers.put(DlqHeaders.ORIGINAL_TOPIC, originalTopic);
    if (originalPartition != null) {
      headers.put(DlqHeaders.ORIGINAL_PARTITION, Integer.toString(originalPartition));
    }
    if (originalOffset != null) {
      headers.put(DlqHeaders.ORIGINAL_OFFSET, Long.toString(originalOffset));
    }
    headers.put(DlqHeaders.ATTEMPT, Integer.toString(attempt));
    headers.put(DlqHeaders.ERROR_CODE, errorCode);
    headers.put(DlqHeaders.CONSUMER, consumer);
    return headers;
  }

  @Override
  public String toString() {
    return "DlqRecord[event_id="
        + eventId
        + ", original_topic="
        + originalTopic
        + ", error_code="
        + errorCode
        + ", attempt="
        + attempt
        + "]";
  }

  private static <T> T require(T value, String name) {
    return Objects.requireNonNull(value, name + " is required");
  }

  public static final class Builder {
    private UUID eventId;
    private String originalTopic;
    private String originalKey;
    private byte[] originalValue;
    private Integer originalPartition;
    private Long originalOffset;
    private Map<String, String> originalHeaders = Map.of();
    private String errorCode;
    private String errorMessage;
    private Integer attempt;
    private Instant failedAt;
    private String consumer;

    private Builder() {}

    public Builder eventId(UUID eventId) {
      this.eventId = eventId;
      return this;
    }

    public Builder originalTopic(String originalTopic) {
      this.originalTopic = originalTopic;
      return this;
    }

    public Builder originalKey(String originalKey) {
      this.originalKey = originalKey;
      return this;
    }

    public Builder originalValue(byte[] originalValue) {
      this.originalValue = originalValue;
      return this;
    }

    public Builder originalPartition(Integer originalPartition) {
      this.originalPartition = originalPartition;
      return this;
    }

    public Builder originalOffset(Long originalOffset) {
      this.originalOffset = originalOffset;
      return this;
    }

    /** The rejected record's Kafka headers; {@code dlq.attempt} decides the attempt number. */
    public Builder originalHeaders(Map<String, String> originalHeaders) {
      this.originalHeaders = originalHeaders == null ? Map.of() : originalHeaders;
      return this;
    }

    public Builder error(ErrorCode errorCode, String errorMessage) {
      return error(errorCode.name(), errorMessage);
    }

    public Builder error(String errorCode, String errorMessage) {
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder error(ValidationResult.Invalid invalid) {
      return error(invalid.errorCode(), invalid.message());
    }

    /** Overrides the attempt derived from the headers (tests and explicit re-queues only). */
    public Builder attempt(int attempt) {
      this.attempt = attempt;
      return this;
    }

    public Builder failedAt(Instant failedAt) {
      this.failedAt = failedAt;
      return this;
    }

    /** Which consumer rejected the record: {@code stream} or {@code api}. */
    public Builder consumer(String consumer) {
      this.consumer = consumer;
      return this;
    }

    public DlqRecord build() {
      return new DlqRecord(this);
    }
  }
}
