package com.beaconclimb.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.events.Examples.Example;
import com.beaconclimb.events.ValidationResult.Invalid;
import com.beaconclimb.events.ValidationResult.Valid;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

class EventValidatorTest {

  /** Every committed invalid example and the code the ONE validator must assign it. */
  static final Map<String, ErrorCode> EXPECTED_INVALID =
      Map.ofEntries(
          Map.entry("invalid-attempt-bad-timestamp.json", ErrorCode.SCHEMA_VIOLATION),
          Map.entry("invalid-attempt-grade-numeric-string.json", ErrorCode.SCHEMA_VIOLATION),
          Map.entry("invalid-attempt-missing-revision.json", ErrorCode.SCHEMA_VIOLATION),
          Map.entry("invalid-attempt-notes.json", ErrorCode.NOTES_PRESENT),
          Map.entry("invalid-attempt-renamed-notes-comment.json", ErrorCode.UNKNOWN_ATTRIBUTE),
          Map.entry("invalid-attempt-renamed-notes-free_text.json", ErrorCode.UNKNOWN_ATTRIBUTE),
          Map.entry("invalid-attempt-renamed-notes-note.json", ErrorCode.UNKNOWN_ATTRIBUTE),
          Map.entry("invalid-attempt-renamed-notes-Notes.json", ErrorCode.UNKNOWN_ATTRIBUTE),
          Map.entry("invalid-climber-features-count.json", ErrorCode.SCHEMA_VIOLATION),
          Map.entry("invalid-stream-dlq-attempt.json", ErrorCode.SCHEMA_VIOLATION));

  static final Set<String> EXPECTED_VALID =
      Set.of(
          "valid-attempt-corrected.json",
          "valid-attempt-recorded.json",
          "valid-attempt-retracted.json",
          "valid-climber-features.json",
          "valid-stream-dlq.json");

  private final EventValidator validator = new EventValidator();

  @Test
  @DisplayName("every committed example is covered: valid ones accepted, invalid ones coded")
  void everyCommittedExample() {
    assertThat(Examples.fileNames())
        .containsExactlyInAnyOrderElementsOf(
            Set.copyOf(
                java.util.stream.Stream.concat(
                        EXPECTED_VALID.stream(), EXPECTED_INVALID.keySet().stream())
                    .toList()));
    for (String name : EXPECTED_VALID) {
      Example example = Examples.example(name);
      ValidationResult result =
          validator.validate(example.topic(), Examples.bytes(example.event()));
      assertThat(result).as(name).isInstanceOf(Valid.class);
      assertThat(((Valid) result).payload()).as(name).isEqualTo(example.event());
    }
    for (Map.Entry<String, ErrorCode> expected : EXPECTED_INVALID.entrySet()) {
      Example example = Examples.example(expected.getKey());
      ValidationResult result =
          validator.validate(example.topic(), Examples.bytes(example.event()));
      assertThat(result).as(expected.getKey()).isInstanceOf(Invalid.class);
      assertThat(((Invalid) result).errorCode())
          .as(expected.getKey())
          .isEqualTo(expected.getValue());
    }
  }

  @Test
  @DisplayName(
      "messages name the key or location, never the rejected value (safe to log and store)")
  void messagesNeverQuoteValues() {
    Invalid notes =
        (Invalid)
            validator.validate(
                EventSchemas.ATTEMPTS,
                Examples.bytes(Examples.example("invalid-attempt-notes").event()));
    Invalid renamed =
        (Invalid)
            validator.validate(
                EventSchemas.ATTEMPTS,
                Examples.bytes(Examples.example("invalid-attempt-renamed-notes-comment").event()));
    Invalid grade =
        (Invalid)
            validator.validate(
                EventSchemas.ATTEMPTS,
                Examples.bytes(Examples.example("invalid-attempt-grade-numeric-string").event()));

    assertThat(notes.message()).contains("/attributes/notes").doesNotContain("private note");
    assertThat(renamed.message()).contains("comment").doesNotContain("private note");
    assertThat(grade.message()).contains("/attributes/grade_numeric").doesNotContain("6a");
  }

  @Test
  @DisplayName("a renamed notes key is reported with every unknown key, not only the first")
  void reportsEveryUnknownAttribute() {
    ObjectNode event = Examples.example("valid-attempt-recorded").event();
    ObjectNode attributes = (ObjectNode) event.get("attributes");
    attributes.put("comment", "x");
    attributes.put("free_text", "y");

    Invalid result = (Invalid) validator.validate(EventSchemas.ATTEMPTS, Examples.bytes(event));

    assertThat(result.errorCode()).isEqualTo(ErrorCode.UNKNOWN_ATTRIBUTE);
    assertThat(result.message()).contains("comment").contains("free_text");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not json", "{\"a\":1} trailing", "{\"a\":1,\"a\":2}", "[1,2"})
  @DisplayName("bytes that are not one well-formed JSON document are NOT_JSON")
  void notJson(String text) {
    ValidationResult result = validator.validate(EventSchemas.ATTEMPTS, Examples.utf8(text));

    assertThat(result).isInstanceOf(Invalid.class);
    assertThat(((Invalid) result).errorCode()).isEqualTo(ErrorCode.NOT_JSON);
  }

  @ParameterizedTest
  @ValueSource(strings = {"[1,2]", "\"text\"", "42", "null"})
  @DisplayName("a well-formed document that is not an object is a schema violation")
  void notAnObject(String text) {
    ValidationResult result = validator.validate(EventSchemas.ATTEMPTS, Examples.utf8(text));

    assertThat(result).isInstanceOf(Invalid.class);
    assertThat(((Invalid) result).errorCode()).isEqualTo(ErrorCode.SCHEMA_VIOLATION);
  }

  @Test
  @DisplayName("notes at any depth is NOTES_PRESENT, before and regardless of the schema verdict")
  void notesAtAnyDepth() {
    ObjectNode features = Examples.example("valid-climber-features").event();
    ((ObjectNode) features.get("aggregates")).put("notes", "deep");
    ObjectNode nested = Examples.example("valid-attempt-recorded").event();
    nested.putObject("trace").putArray("items").addObject().put("notes", "deeper");
    ObjectNode topLevel = Examples.example("valid-stream-dlq").event();
    topLevel.put("notes", "top");

    Invalid inFeatures =
        (Invalid) validator.validate(EventSchemas.FEATURES, Examples.bytes(features));
    Invalid inArray = (Invalid) validator.validate(EventSchemas.ATTEMPTS, Examples.bytes(nested));
    Invalid inDlq = (Invalid) validator.validate(EventSchemas.DLQ, Examples.bytes(topLevel));

    assertThat(inFeatures.errorCode()).isEqualTo(ErrorCode.NOTES_PRESENT);
    assertThat(inFeatures.message()).contains("/aggregates/notes");
    assertThat(inArray.errorCode()).isEqualTo(ErrorCode.NOTES_PRESENT);
    assertThat(inArray.message()).contains("/trace/items/0/notes");
    assertThat(inDlq.errorCode()).isEqualTo(ErrorCode.NOTES_PRESENT);
  }

  @Test
  @DisplayName("an unknown top-level member of an attempt is a schema violation, not an attribute")
  void unknownTopLevelMember() {
    ObjectNode event = Examples.example("valid-attempt-recorded").event();
    event.put("extra", 1);

    Invalid result = (Invalid) validator.validate(EventSchemas.ATTEMPTS, Examples.bytes(event));

    assertThat(result.errorCode()).isEqualTo(ErrorCode.SCHEMA_VIOLATION);
    assertThat(result.message()).contains("extra");
  }

  @Test
  @DisplayName("grade_numeric absent, null and numeric are all accepted (consumer-first, BC-0301)")
  void gradeNumericOptional() {
    ObjectNode absent = Examples.example("valid-attempt-recorded").event();
    ((ObjectNode) absent.get("attributes")).remove("grade_numeric");
    ObjectNode nullGrade = Examples.example("valid-attempt-recorded").event();
    ((ObjectNode) nullGrade.get("attributes")).putNull("grade_numeric");

    assertThat(validator.validate(EventSchemas.ATTEMPTS, Examples.bytes(absent)))
        .isInstanceOf(Valid.class);
    assertThat(validator.validate(EventSchemas.ATTEMPTS, Examples.bytes(nullGrade)))
        .isInstanceOf(Valid.class);
  }

  @Test
  @DisplayName("the topic selects the schema: an attempt is not a feature record, and vice versa")
  void topicSelectsSchema() {
    byte[] attempt = Examples.bytes(Examples.example("valid-attempt-recorded").event());
    byte[] features = Examples.bytes(Examples.example("valid-climber-features").event());

    assertThat(validator.validate(EventSchemas.FEATURES, attempt)).isInstanceOf(Invalid.class);
    assertThat(validator.validate(EventSchemas.ATTEMPTS, features)).isInstanceOf(Invalid.class);
    assertThat(validator.validate(EventSchemas.ATTEMPTS, attempt)).isInstanceOf(Valid.class);
    assertThat(validator.validate(EventSchemas.FEATURES, features)).isInstanceOf(Valid.class);
  }

  @Test
  @DisplayName("a topic without a contract is UNKNOWN_TOPIC")
  void unknownTopic() {
    ValidationResult result = validator.validate("stream-reconciliation.v1", Examples.utf8("{}"));

    assertThat(result).isInstanceOf(Invalid.class);
    assertThat(((Invalid) result).errorCode()).isEqualTo(ErrorCode.UNKNOWN_TOPIC);
    assertThat(validator.schemas().topics())
        .containsExactlyInAnyOrder(EventSchemas.ATTEMPTS, EventSchemas.FEATURES, EventSchemas.DLQ);
  }
}
