package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** BC-0107 registerUser: 201 with a session, 409 USERNAME_TAKEN, 422 field errors, request ids. */
class AuthRegistrationIT extends IdentityTestSupport {

  @Test
  void registerOpensSessionWithTwentyFourHourExpiry() {
    Instant before = Instant.now();
    ResponseEntity<String> response =
        post("/v1/auth/register", authBody(uniqueUsername(), PASSWORD), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isNotBlank();
    ContractSchemas.assertConforms("AuthResponse", response.getBody());
    assertThat(tree(response.getBody()).propertyNames()).containsExactly("token", "expires_at");
    AuthResponse auth = json.readValue(response.getBody(), AuthResponse.class);
    assertThat(auth.token()).matches(SessionTokens.WELL_FORMED);
    Instant expiresAt = Instant.parse(auth.expiresAt());
    assertThat(expiresAt)
        .isBetween(before.plus(Duration.ofHours(23)), before.plus(Duration.ofHours(25)));
  }

  @Test
  void duplicateUsernameIsConflictWithFixtureShape() {
    String username = uniqueUsername();
    register(username);

    ResponseEntity<String> response = post("/v1/auth/register", authBody(username, PASSWORD), null);

    ErrorEnvelope envelope = assertError(response, HttpStatus.CONFLICT, ErrorCode.USERNAME_TAKEN);
    assertThat(envelope.message()).isEqualTo("Username is already registered");
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("username", "unique", "already registered"));
  }

  @Test
  void invalidUsernameIsValidationFailureWithPatternFieldError() {
    ResponseEntity<String> response = post("/v1/auth/register", authBody("Demo!", PASSWORD), null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.message()).isEqualTo("Request failed validation");
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("username", "pattern", "must match ^[a-z0-9_]{3,32}$"));
  }

  @Test
  void shortPasswordIsValidationFailureWithMinLengthFieldError() {
    ResponseEntity<String> response =
        post("/v1/auth/register", authBody(uniqueUsername(), ""), null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("password", "minLength", "must be at least 8 characters"));
  }

  @Test
  void overlongPasswordIsRejectedWithoutHashing() {
    ResponseEntity<String> response =
        post("/v1/auth/register", authBody(uniqueUsername(), "x".repeat(129)), null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("password", "maxLength", "must be at most 128 characters"));
  }

  @Test
  void missingFieldsAreReportedTogether() {
    ResponseEntity<String> response = post("/v1/auth/register", "{}", null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("username", "password");
    assertThat(envelope.fieldErrors()).extracting(FieldError::code).containsOnly("required");
  }

  @Test
  void unknownMemberIsUnknownField() {
    String body =
        json.writeValueAsString(
            Map.of("username", uniqueUsername(), "password", PASSWORD, "role", "ADMIN"));

    ResponseEntity<String> response = post("/v1/auth/register", body, null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNKNOWN_FIELD);
    assertThat(envelope.fieldErrors())
        .containsExactly(new FieldError("role", "additionalProperties", "unknown field"));
  }

  @Test
  void malformedJsonIsValidationFailure() {
    ResponseEntity<String> response = post("/v1/auth/register", "{\"username\": ", null);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors()).extracting(FieldError::field).containsExactly("body");
  }

  @Test
  void incomingRequestIdIsEchoedIntoHeaderAndEnvelope() {
    ResponseEntity<String> response =
        post(
            "/v1/auth/register",
            authBody("Demo!", PASSWORD),
            null,
            Map.of(RequestIdFilter.HEADER, "fixture-request-2"));

    assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER))
        .isEqualTo("fixture-request-2");
    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.requestId()).isEqualTo("fixture-request-2");
  }

  @Test
  void unsafeIncomingRequestIdIsReplacedByUuid() {
    ResponseEntity<String> response =
        post(
            "/v1/auth/register",
            authBody(uniqueUsername(), PASSWORD),
            null,
            Map.of(RequestIdFilter.HEADER, "bad id <script>"));

    String requestId = response.getHeaders().getFirst(RequestIdFilter.HEADER);
    assertThat(requestId).isNotEqualTo("bad id <script>");
    assertThat(UUID.fromString(requestId)).isNotNull();
  }
}
