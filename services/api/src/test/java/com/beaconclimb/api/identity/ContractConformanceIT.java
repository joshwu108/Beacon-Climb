package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.platform.web.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves contract 1.2.0 conformance without generated controllers: the validator bites on extra
 * members, bad enums and (BC-0310) a recommendation body without {@code features_source}, the
 * shipped fixtures pass, and the live bodies of registerUser, createSession, getMe,
 * getConsentDocument and an error response validate against their schemas. The live
 * createRecommendations and getDecision bodies are validated where they are produced
 * (RecommendationsTestSupport.recommendOk and DecisionEndpointIT), which needs the seeded board.
 */
class ContractConformanceIT extends IdentityTestSupport {

  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "examples").toAbsolutePath().normalize();

  @Test
  void validatorRejectsExtraMembersAndUnknownCodes() {
    String extra = "{\"token\":\"t\",\"expires_at\":\"2026-10-15T14:00:00Z\",\"extra\":1}";
    assertThat(ContractSchemas.validate("AuthResponse", extra))
        .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("additionalProperties"));

    String badCode =
        "{\"code\":\"NOPE\",\"message\":\"m\",\"request_id\":\"r\",\"field_errors\":[]}";
    assertThat(ContractSchemas.validate("ErrorEnvelope", badCode)).isNotEmpty();

    String badUuid =
        "{\"user_id\":\"not-a-uuid\",\"username\":\"u\",\"consent\":null,\"preferences\":null}";
    assertThat(ContractSchemas.validate("Me", badUuid))
        .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("format"));
  }

  /** Contract 1.2.0 (BC-0310): both response schemas require features_source, a closed enum. */
  @Test
  void recommendationSchemasRequireFeaturesSourceFromAClosedEnum() throws IOException {
    for (Map.Entry<String, String> fixture :
        Map.of(
                "createRecommendations/response.json", "RecommendationResponse",
                "getDecision/response.json", "Decision")
            .entrySet()) {
      ObjectNode body = (ObjectNode) fixtureBody(fixture.getKey());
      assertThat(body.get("features_source").asString()).isEqualTo("SYNC");
      assertThat(body.get("features_cutoff_w").asString()).isEqualTo("2026-10-14T14:00:00Z");

      ObjectNode missing = body.deepCopy();
      missing.remove("features_source");
      assertThat(ContractSchemas.validate(fixture.getValue(), json.writeValueAsString(missing)))
          .as("%s without features_source", fixture.getValue())
          .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("required"));

      ObjectNode unknown = body.deepCopy();
      unknown.put("features_source", "BATCH");
      assertThat(ContractSchemas.validate(fixture.getValue(), json.writeValueAsString(unknown)))
          .as("%s with an unknown features_source", fixture.getValue())
          .anySatisfy(error -> assertThat(error.getKeyword()).isEqualTo("enum"));

      ObjectNode noCutoff = body.deepCopy();
      noCutoff.remove("features_cutoff_w");
      assertThat(ContractSchemas.validate(fixture.getValue(), json.writeValueAsString(noCutoff)))
          .as("%s without the optional features_cutoff_w", fixture.getValue())
          .isEmpty();
    }
  }

  @Test
  void shippedFixturesConformToTheirSchemas() throws IOException {
    Map<String, String> fixtures =
        Map.of(
            "getConsentDocument/response.json", "ConsentDocument",
            "registerUser/response.json", "AuthResponse",
            "registerUser/response-409.json", "ErrorEnvelope",
            "registerUser/response-422.json", "ErrorEnvelope",
            "createSession/response.json", "AuthResponse",
            "createSession/response-422.json", "ErrorEnvelope",
            "getMe/response.json", "Me",
            "createRecommendations/response.json", "RecommendationResponse",
            "getDecision/response.json", "Decision");
    for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
      ContractSchemas.assertConforms(
          fixture.getValue(), json.writeValueAsString(fixtureBody(fixture.getKey())));
    }
  }

  private JsonNode fixtureBody(String relativePath) throws IOException {
    return json.readTree(Files.readString(EXAMPLES.resolve(relativePath), StandardCharsets.UTF_8))
        .get("body");
  }

  @Test
  void liveResponsesConformToTheirSchemas() {
    String username = uniqueUsername();
    ResponseEntity<String> registered =
        post("/v1/auth/register", authBody(username, PASSWORD), null);
    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ContractSchemas.assertConforms("AuthResponse", registered.getBody());

    ResponseEntity<String> session = post("/v1/auth/session", authBody(username, PASSWORD), null);
    assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ContractSchemas.assertConforms("AuthResponse", session.getBody());
    String token = tree(session.getBody()).get("token").asString();

    ResponseEntity<String> me = get("/v1/me", token);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Me", me.getBody());

    ResponseEntity<String> document = get("/v1/consent/current", null);
    assertThat(document.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("ConsentDocument", document.getBody());

    ResponseEntity<String> health = get("/health/live", null);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Health", health.getBody());

    assertError(
        post("/v1/auth/session", authBody(username, "wrong-password"), null),
        HttpStatus.UNAUTHORIZED,
        ErrorCode.INVALID_CREDENTIALS);
    assertError(get("/v1/me", null), HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED);
  }
}
