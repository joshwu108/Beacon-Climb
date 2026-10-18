package com.beaconclimb.events;

import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import com.networknt.schema.Error;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer-side validation of event bytes (BC-0302, ADR-0004): one decision for the Flink job, the
 * api materializer and the redrive CLI. Checks run in this order and the first failure is the
 * {@link ErrorCode}:
 *
 * <ol>
 *   <li>{@link ErrorCode#UNKNOWN_TOPIC}: no contract for the topic;
 *   <li>{@link ErrorCode#NOT_JSON}: not one well-formed JSON document (strict: duplicate keys and
 *       trailing tokens are errors);
 *   <li>{@link ErrorCode#NOTES_PRESENT}: a member called {@code notes} at any depth, checked before
 *       the schema so the privacy signal is never masked by a generic violation;
 *   <li>{@link ErrorCode#UNKNOWN_ATTRIBUTE}: {@code attributes} has a key outside the closed
 *       allowlist (the {@code additionalProperties} error at {@code /attributes});
 *   <li>{@link ErrorCode#SCHEMA_VIOLATION}: anything else the schema rejects.
 * </ol>
 *
 * Messages name locations and keys, never the rejected values, so they can be logged and stored in
 * the DLQ record. Thread-safe; build one per process (schema compilation is the expensive part).
 */
public final class EventValidator {

  public static final String NOTES_MEMBER = "notes";
  static final int MAX_ERRORS_IN_MESSAGE = 5;
  private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final EventSchemas schemas;

  public EventValidator() {
    this(EventSchemas.load());
  }

  public EventValidator(EventSchemas schemas) {
    this.schemas = schemas;
  }

  public EventSchemas schemas() {
    return schemas;
  }

  public ValidationResult validate(String topic, byte[] bytes) {
    if (!schemas.knows(topic)) {
      return new Invalid(ErrorCode.UNKNOWN_TOPIC, "no event schema for topic '" + topic + "'");
    }
    JsonNode document;
    try {
      document = MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return new Invalid(ErrorCode.NOT_JSON, "not a JSON document: " + firstLine(e.getMessage()));
    }
    if (document == null || document.isMissingNode()) {
      return new Invalid(ErrorCode.NOT_JSON, "not a JSON document: empty input");
    }
    return validate(topic, document);
  }

  /** The same decision for an already parsed document (the api validates trees it built). */
  public ValidationResult validate(String topic, JsonNode document) {
    if (!schemas.knows(topic)) {
      return new Invalid(ErrorCode.UNKNOWN_TOPIC, "no event schema for topic '" + topic + "'");
    }
    String notesPath = findMember(document, NOTES_MEMBER, "");
    if (notesPath != null) {
      return new Invalid(
          ErrorCode.NOTES_PRESENT, "member 'notes' is never allowed (found at " + notesPath + ")");
    }
    List<Error> errors = schemas.errors(topic, document);
    if (errors.isEmpty()) {
      return new Valid(document);
    }
    List<String> unknownAttributes = unknownAttributes(errors);
    if (!unknownAttributes.isEmpty()) {
      return new Invalid(
          ErrorCode.UNKNOWN_ATTRIBUTE,
          "attributes key(s) outside the closed allowlist: "
              + String.join(", ", unknownAttributes));
    }
    return new Invalid(ErrorCode.SCHEMA_VIOLATION, describe(errors));
  }

  /** The raw schema errors for callers that report locations themselves (the api write path). */
  public List<Error> errors(String topic, JsonNode document) {
    return schemas.errors(topic, document);
  }

  /** Keys reported by an additionalProperties error whose instance is {@code /attributes}. */
  private static List<String> unknownAttributes(List<Error> errors) {
    List<String> keys = new ArrayList<>();
    for (Error error : errors) {
      if (ADDITIONAL_PROPERTIES.equals(error.getKeyword())
          && "/attributes".equals(error.getInstanceLocation().toString())
          && error.getProperty() != null) {
        keys.add(error.getProperty());
      }
    }
    return keys;
  }

  private static String describe(List<Error> errors) {
    String joined =
        errors.stream()
            .limit(MAX_ERRORS_IN_MESSAGE)
            .map(e -> e.getInstanceLocation() + ": " + e.getMessage())
            .collect(Collectors.joining("; "));
    return errors.size() > MAX_ERRORS_IN_MESSAGE
        ? joined + "; (" + (errors.size() - MAX_ERRORS_IN_MESSAGE) + " more)"
        : joined;
  }

  /** JSON-pointer path of the first object member named {@code name} at any depth, else null. */
  static String findMember(JsonNode node, String name, String path) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        String childPath = path + "/" + entry.getKey();
        if (entry.getKey().equals(name)) {
          return childPath;
        }
        String found = findMember(entry.getValue(), name, childPath);
        if (found != null) {
          return found;
        }
      }
    } else if (node.isArray()) {
      int index = 0;
      for (JsonNode child : node) {
        String found = findMember(child, name, path + "/" + index++);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "parse error";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
