package com.beaconclimb.api.identity.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.catalog.CatalogSeed;
import com.beaconclimb.api.catalog.importer.CatalogImporter;
import com.beaconclimb.api.identity.ContractSchemas;
import com.beaconclimb.api.identity.IdentityTestSupport;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0108 putPreferences against the seeded SYNTHETIC demo board (available angles 20, 30, 40, 45,
 * 50): happy path persists and shows on getMe, every validation branch is a 422 with contract field
 * errors, a second PUT overwrites, and user B can never touch user A's row.
 */
class PreferencesEndpointIT extends IdentityTestSupport {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "examples", "putPreferences").toAbsolutePath().normalize();
  private static final String PATH = "/v1/me/preferences";

  @Autowired CatalogImporter importer;

  /**
   * The importer tests delete the demo board, so every test re-seeds (a re-import is idempotent).
   */
  @BeforeEach
  void seedDemoBoard() {
    CatalogSeed.seed(importer, jdbc);
  }

  @Test
  void happyPathPersistsAndShowsOnMe() {
    String token = register(uniqueUsername()).token();
    assertThat(tree(get("/v1/me", token).getBody()).get("preferences").isNull()).isTrue();

    ResponseEntity<String> response =
        put(PATH, body(CatalogSeed.BOARD_ID, 40, "BEGINNER", List.of("TECHNIQUE")), token);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Preferences", response.getBody());
    JsonNode saved = tree(response.getBody());
    assertThat(saved.propertyNames())
        .containsExactly("board_id", "angle_deg", "initial_grade_band", "goals");
    assertThat(saved.get("angle_deg").asInt()).isEqualTo(40);

    ResponseEntity<String> me = get("/v1/me", token);
    ContractSchemas.assertConforms("Me", me.getBody());
    assertThat(tree(me.getBody()).get("preferences")).isEqualTo(saved);
    UUID userId = UUID.fromString(tree(me.getBody()).get("user_id").asString());
    assertThat(updatedAt(userId)).isNotNull();
  }

  @Test
  void secondPutOverwritesTheSingleRow() {
    String token = register(uniqueUsername()).token();
    put(PATH, body(CatalogSeed.BOARD_ID, 40, "BEGINNER", List.of("TECHNIQUE")), token);

    ResponseEntity<String> second =
        put(
            PATH,
            body(CatalogSeed.BOARD_ID, 45, "INTERMEDIATE", List.of("PROJECT", "STRENGTH")),
            token);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode preferences = tree(get("/v1/me", token).getBody()).get("preferences");
    assertThat(preferences.get("angle_deg").asInt()).isEqualTo(45);
    assertThat(preferences.get("initial_grade_band").asString()).isEqualTo("INTERMEDIATE");
    assertThat(preferences.get("goals")).hasSize(2);
    UUID userId = userIdOf(token);
    assertThat(
            jdbc.sql("SELECT count(*) FROM user_preferences WHERE user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  void unknownBoardIsUnknownBoardWithFieldError() {
    String token = register(uniqueUsername()).token();

    ErrorEnvelope envelope =
        assertError(
            put(PATH, body("no-such-board", 40, "BEGINNER", List.of()), token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_BOARD);

    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("board_id", "unknown", "no such board"));
  }

  @Test
  void unavailableAngleIsUnsupportedAngleWithFieldError() {
    String token = register(uniqueUsername()).token();

    ErrorEnvelope envelope =
        assertError(
            put(PATH, body(CatalogSeed.BOARD_ID, 35, "BEGINNER", List.of("TECHNIQUE")), token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNSUPPORTED_ANGLE);

    assertThat(envelope.message()).isEqualTo("Angle is not available on this board");
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("angle_deg", "enum", "must be one of 20, 30, 40, 45, 50"));
    assertThat(tree(get("/v1/me", token).getBody()).get("preferences").isNull()).isTrue();
  }

  @Test
  void shapeViolationsAreReportedTogether() {
    String token = register(uniqueUsername()).token();
    String body =
        json.writeValueAsString(
            Map.of(
                "board_id",
                CatalogSeed.BOARD_ID,
                "angle_deg",
                "forty",
                "initial_grade_band",
                "WIZARD",
                "goals",
                List.of("TECHNIQUE", "TECHNIQUE", "STRENGTH", "ENDURANCE", "NAPPING")));

    ErrorEnvelope envelope =
        assertError(
            put(PATH, body, token), HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);

    assertThat(envelope.fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("angle_deg", "type"),
            org.assertj.core.groups.Tuple.tuple("initial_grade_band", "enum"),
            org.assertj.core.groups.Tuple.tuple("goals", "maxItems"),
            org.assertj.core.groups.Tuple.tuple("goals[4]", "enum"),
            org.assertj.core.groups.Tuple.tuple("goals", "uniqueItems"));
  }

  @Test
  void missingMembersAreRequired() {
    String token = register(uniqueUsername()).token();

    ErrorEnvelope envelope =
        assertError(
            put(PATH, "{}", token), HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);

    assertThat(envelope.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("board_id", "angle_deg", "initial_grade_band", "goals");
    assertThat(envelope.fieldErrors()).allMatch(error -> error.code().equals("required"));
  }

  @Test
  void unknownMemberIsUnknownField() {
    String token = register(uniqueUsername()).token();
    String body =
        json.writeValueAsString(
            Map.of(
                "board_id",
                CatalogSeed.BOARD_ID,
                "angle_deg",
                40,
                "initial_grade_band",
                "BEGINNER",
                "goals",
                List.of(),
                "layout",
                "x"));

    ErrorEnvelope envelope =
        assertError(
            put(PATH, body, token), HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNKNOWN_FIELD);

    assertThat(envelope.fieldErrors()).extracting(FieldError::field).containsExactly("layout");
  }

  @Test
  void requiresAuthentication() {
    assertError(
        put(PATH, body(CatalogSeed.BOARD_ID, 40, "BEGINNER", List.of()), null),
        HttpStatus.UNAUTHORIZED,
        ErrorCode.UNAUTHENTICATED);
  }

  @Test
  void userBCannotAlterUserA() {
    String tokenA = register(uniqueUsername()).token();
    String tokenB = register(uniqueUsername()).token();
    put(PATH, body(CatalogSeed.BOARD_ID, 40, "BEGINNER", List.of("TECHNIQUE")), tokenA);

    put(PATH, body(CatalogSeed.BOARD_ID, 50, "EXPERT", List.of("PROJECT")), tokenB);

    JsonNode a = tree(get("/v1/me", tokenA).getBody()).get("preferences");
    JsonNode b = tree(get("/v1/me", tokenB).getBody()).get("preferences");
    assertThat(a.get("angle_deg").asInt()).isEqualTo(40);
    assertThat(a.get("initial_grade_band").asString()).isEqualTo("BEGINNER");
    assertThat(b.get("angle_deg").asInt()).isEqualTo(50);
  }

  @Test
  void shippedFixturesConformAndThe422FixtureReplaysExactly() throws IOException {
    ContractSchemas.assertConforms("Preferences", fixture("request.json").get("body").toString());
    ContractSchemas.assertConforms("Preferences", fixture("response.json").get("body").toString());
    ContractSchemas.assertConforms(
        "ErrorEnvelope", fixture("response-422.json").get("body").toString());
    String token = register(uniqueUsername()).token();

    ResponseEntity<String> live =
        put(PATH, fixture("request-422.json").get("body").toString(), token);

    ErrorEnvelope envelope =
        assertError(live, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    JsonNode expected = fixture("response-422.json").get("body");
    assertThat(envelope.message()).isEqualTo(expected.get("message").asString());
    assertThat(tree(live.getBody()).get("field_errors")).isEqualTo(expected.get("field_errors"));
  }

  /**
   * The shipped request fixture asks for 35 degrees, which the shipped demo board does not offer,
   * so replaying it against real data is the contract's UNSUPPORTED_ANGLE rather than the 200 of
   * response.json. Recorded here so the mismatch is visible to the contract owner.
   */
  @Test
  void fixtureRequestAngleIsNotOnTheDemoBoard() throws IOException {
    String token = register(uniqueUsername()).token();

    ResponseEntity<String> live = put(PATH, fixture("request.json").get("body").toString(), token);

    assertError(live, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNSUPPORTED_ANGLE);
  }

  private String body(String boardId, int angle, String band, List<String> goals) {
    return json.writeValueAsString(
        Map.of(
            "board_id", boardId, "angle_deg", angle, "initial_grade_band", band, "goals", goals));
  }

  private JsonNode fixture(String name) throws IOException {
    return json.readTree(Files.readString(EXAMPLES.resolve(name), StandardCharsets.UTF_8));
  }

  private Object updatedAt(UUID userId) {
    return jdbc.sql("SELECT updated_at FROM user_preferences WHERE user_id = :id")
        .param("id", userId)
        .query(Object.class)
        .single();
  }
}
