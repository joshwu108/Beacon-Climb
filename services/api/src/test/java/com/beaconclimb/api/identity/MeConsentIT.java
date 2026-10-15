package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.identity.api.ConsentDocument;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.FieldError;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0107 getMe, getConsentDocument, createConsent, deleteConsent and ConsentGuard: consent null
 * until granted, revoke sets revoked_at and makes the guard refuse with 403 CONSENT_REQUIRED,
 * re-grant upserts, mismatching version or hash is 422, user A never sees user B (TM-30).
 */
class MeConsentIT extends IdentityTestSupport {

  @Autowired ConsentGuard guard;

  @Test
  void meStartsWithNullConsentAndNullPreferences() {
    String username = uniqueUsername();
    AuthResponse auth = register(username);

    ResponseEntity<String> me = get("/v1/me", auth.token());

    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Me", me.getBody());
    JsonNode body = tree(me.getBody());
    assertThat(body.propertyNames())
        .containsExactly("user_id", "username", "consent", "preferences");
    assertThat(body.get("username").asString()).isEqualTo(username);
    assertThat(body.get("consent").isNull()).isTrue();
    assertThat(body.get("preferences").isNull()).isTrue();
    assertThat(UUID.fromString(body.get("user_id").asString()).version()).isEqualTo(7);
  }

  @Test
  void consentDocumentIsPublicAndHashesItsOwnText() throws Exception {
    ResponseEntity<String> response = get("/v1/consent/current", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("ConsentDocument", response.getBody());
    ConsentDocument document = json.readValue(response.getBody(), ConsentDocument.class);
    assertThat(document.version()).isEqualTo("v1");
    assertThat(document.scopesAvailable()).containsExactly("service", "research");
    byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest(document.text().getBytes(StandardCharsets.UTF_8));
    assertThat(document.textSha256()).isEqualTo(HexFormat.of().formatHex(digest));
    assertThat(document.text()).contains("version `v1`").doesNotContainIgnoringCase("medical");
  }

  @Test
  void grantRevokeAndRegrantRoundTripThroughMeAndGuard() {
    AuthResponse auth = register(uniqueUsername());
    UUID userId = userIdOf(auth.token());
    ConsentDocument document = currentDocument();
    assertThatThrownBy(() -> guard.requireActiveConsent(userId)).isInstanceOf(ApiException.class);

    ResponseEntity<String> granted =
        post("/v1/me/consent", consentBody(document, false), auth.token());
    assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    JsonNode consent = consentOfMe(auth.token());
    assertThat(consent.get("version").asString()).isEqualTo(document.version());
    assertThat(consent.get("revoked_at").isNull()).isTrue();
    assertThat(consent.get("scopes").get("service").asBoolean()).isTrue();
    assertThat(consent.get("scopes").get("research").asBoolean()).isFalse();
    assertThat(consent.get("text_sha256").asString()).isEqualTo(document.textSha256());
    guard.requireActiveConsent(userId);

    assertThat(delete("/v1/me/consent", auth.token()).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(consentOfMe(auth.token()).get("revoked_at").isNull()).isFalse();
    assertThatThrownBy(() -> guard.requireActiveConsent(userId))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(ex.code()).isEqualTo(ErrorCode.CONSENT_REQUIRED);
            });
    assertThat(delete("/v1/me/consent", auth.token()).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<String> regranted =
        post("/v1/me/consent", consentBody(document, true), auth.token());
    assertThat(regranted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    JsonNode again = consentOfMe(auth.token());
    assertThat(again.get("revoked_at").isNull()).isTrue();
    assertThat(again.get("scopes").get("research").asBoolean()).isTrue();
    guard.requireActiveConsent(userId);
  }

  @Test
  void revokeWithoutConsentIsIdempotentNoContent() {
    AuthResponse auth = register(uniqueUsername());

    assertThat(delete("/v1/me/consent", auth.token()).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(consentOfMe(auth.token()).isNull()).isTrue();
  }

  @Test
  void wrongTextHashIsValidationFailureOnTextSha256() {
    AuthResponse auth = register(uniqueUsername());
    ConsentDocument document = currentDocument();
    String body =
        json.writeValueAsString(
            Map.of(
                "consent_version", document.version(),
                "scopes", Map.of("service", true),
                "text_sha256", "0".repeat(64)));

    ResponseEntity<String> response = post("/v1/me/consent", body, auth.token());

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors())
        .containsExactly(
            new FieldError("text_sha256", "const", "does not match the current consent text"));
    assertThat(consentOfMe(auth.token()).isNull()).isTrue();
  }

  @Test
  void wrongVersionAndFalseServiceScopeAreValidationFailures() {
    AuthResponse auth = register(uniqueUsername());
    ConsentDocument document = currentDocument();
    String body =
        json.writeValueAsString(
            Map.of(
                "consent_version", "v0",
                "scopes", Map.of("service", false, "research", true),
                "text_sha256", document.textSha256()));

    ResponseEntity<String> response = post("/v1/me/consent", body, auth.token());

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.VALIDATION_FAILED);
    assertThat(envelope.fieldErrors())
        .extracting(FieldError::field, FieldError::code)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("consent_version", "const"),
            org.assertj.core.groups.Tuple.tuple("scopes.service", "const"));
  }

  @Test
  void unknownNestedMemberIsUnknownFieldWithDottedPath() {
    AuthResponse auth = register(uniqueUsername());
    ConsentDocument document = currentDocument();
    String body =
        json.writeValueAsString(
            Map.of(
                "consent_version", document.version(),
                "scopes", Map.of("service", true, "marketing", true),
                "text_sha256", document.textSha256()));

    ResponseEntity<String> response = post("/v1/me/consent", body, auth.token());

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNKNOWN_FIELD);
    assertThat(envelope.fieldErrors())
        .extracting(FieldError::field)
        .containsExactly("scopes.marketing");
  }

  @Test
  void userAneverSeesUserB() {
    String usernameA = uniqueUsername();
    String usernameB = uniqueUsername();
    AuthResponse a = register(usernameA);
    AuthResponse b = register(usernameB);
    UUID idA = userIdOf(a.token());
    UUID idB = userIdOf(b.token());
    assertThat(idA).isNotEqualTo(idB);
    assertThat(
            post("/v1/me/consent", consentBody(currentDocument(), true), b.token()).getStatusCode())
        .isEqualTo(HttpStatus.NO_CONTENT);

    JsonNode meA = tree(get("/v1/me", a.token()).getBody());

    assertThat(meA.get("user_id").asString()).isEqualTo(idA.toString());
    assertThat(meA.get("username").asString()).isEqualTo(usernameA);
    assertThat(meA.get("consent").isNull()).isTrue();
    assertThatThrownBy(() -> guard.requireActiveConsent(idA)).isInstanceOf(ApiException.class);
    guard.requireActiveConsent(idB);
  }

  private ConsentDocument currentDocument() {
    return json.readValue(get("/v1/consent/current", null).getBody(), ConsentDocument.class);
  }

  private String consentBody(ConsentDocument document, boolean research) {
    return json.writeValueAsString(
        Map.of(
            "consent_version", document.version(),
            "scopes", Map.of("service", true, "research", research),
            "text_sha256", document.textSha256()));
  }

  private JsonNode consentOfMe(String token) {
    ResponseEntity<String> me = get("/v1/me", token);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    ContractSchemas.assertConforms("Me", me.getBody());
    return tree(me.getBody()).get("consent");
  }
}
