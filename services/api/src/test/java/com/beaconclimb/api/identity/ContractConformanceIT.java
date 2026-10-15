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

/**
 * Proves contract 1.1.0 conformance without generated controllers: the validator bites on extra
 * members and bad enums, the shipped fixtures pass, and the live bodies of registerUser,
 * createSession, getMe, getConsentDocument and an error response validate against their schemas.
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
            "getMe/response.json", "Me");
    for (Map.Entry<String, String> fixture : fixtures.entrySet()) {
      JsonNode body =
          json.readTree(
                  Files.readString(EXAMPLES.resolve(fixture.getKey()), StandardCharsets.UTF_8))
              .get("body");
      ContractSchemas.assertConforms(fixture.getValue(), json.writeValueAsString(body));
    }
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
