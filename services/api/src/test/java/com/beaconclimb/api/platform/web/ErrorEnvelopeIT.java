package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.IdentityTestSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * BC-0122: every 4xx/5xx the application produces is a contract ErrorEnvelope whose request_id
 * equals the X-Request-Id header, including Spring's own 404/405/406/415, Jackson failures, type
 * mismatches, oversized bodies (declared and chunked), ResponseStatusException and an unexpected
 * exception. Chosen mappings: 405 is 404 (no method enumeration), 415 is 422 (contract declares no
 * 415), an unknown path under /v1/ without a token is 401 (no route enumeration without a token).
 */
class ErrorEnvelopeIT extends IdentityTestSupport {

  private record Case(
      String name, Supplier<ResponseEntity<String>> request, HttpStatus status, ErrorCode code) {}

  private String token;

  @BeforeEach
  void authenticate() {
    token = register(uniqueUsername()).token();
  }

  @TestFactory
  Stream<DynamicTest> everyFailureIsAnEnvelope() {
    List<Case> cases =
        List.of(
            new Case(
                "404 unknown path outside /v1 without token",
                () -> get("/nope", null),
                HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND),
            new Case(
                "404 unknown path under /v1 with token",
                () -> get("/v1/nope", token),
                HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND),
            new Case(
                "401 unknown path under /v1 without token",
                () -> get("/v1/nope", null),
                HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHENTICATED),
            new Case(
                "405 wrong method is 404",
                () -> exchange(HttpMethod.PATCH, "/v1/me", "{}", token, Map.of()),
                HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND),
            new Case(
                "malformed JSON",
                () -> put("/v1/me/preferences", "{\"board_id\":", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "unknown field",
                () -> post("/v1/me/consent", "{\"nope\":1}", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.UNKNOWN_FIELD),
            new Case(
                "wrong JSON type",
                () -> post("/v1/me/consent", "{\"scopes\":\"x\"}", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "missing JSON content type is 422",
                () ->
                    exchange(
                        HttpMethod.PUT,
                        "/v1/me/preferences",
                        "{}",
                        token,
                        Map.of("Content-Type", MediaType.TEXT_PLAIN_VALUE)),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "406 unacceptable Accept still answers JSON",
                () ->
                    exchange(HttpMethod.GET, "/v1/me", null, token, Map.of("Accept", "text/plain")),
                HttpStatus.NOT_ACCEPTABLE,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "413 declared oversized body",
                () -> put("/v1/me/preferences", oversizedBody(), token),
                HttpStatus.CONTENT_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE),
            new Case(
                "500 unexpected exception",
                () -> get("/v1/_faults/boom", token),
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR),
            new Case(
                "path variable type mismatch",
                () -> get("/v1/_faults/int/abc", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "missing query parameter",
                () -> get("/v1/_faults/param", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "query parameter type mismatch",
                () -> get("/v1/_faults/param?n=x", token),
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.VALIDATION_FAILED),
            new Case(
                "ResponseStatusException 503",
                () -> get("/v1/_faults/unavailable", token),
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE),
            new Case(
                "direct /error is 404",
                () -> get("/error", null),
                HttpStatus.NOT_FOUND,
                ErrorCode.NOT_FOUND));
    return cases.stream()
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.name(),
                    () -> {
                      ResponseEntity<String> response = c.request().get();
                      assertError(response, c.status(), c.code());
                      assertThat(response.getHeaders().getContentType())
                          .isNotNull()
                          .satisfies(
                              type ->
                                  assertThat(type.isCompatibleWith(MediaType.APPLICATION_JSON))
                                      .isTrue());
                    }));
  }

  @Test
  void internalErrorCarriesNoExceptionText() {
    ResponseEntity<String> response = get("/v1/_faults/boom", token);

    ErrorEnvelope envelope =
        assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    assertThat(envelope.message()).isEqualTo("Internal error");
    assertThat(response.getBody())
        .doesNotContain(FaultController.BOOM_MARKER)
        .doesNotContain("Exception")
        .doesNotContain("at com.beaconclimb");
  }

  @Test
  void responseStatusReasonNeverReachesTheClient() {
    ResponseEntity<String> response = get("/v1/_faults/unavailable", token);

    assertError(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).doesNotContain(FaultController.REASON_MARKER);
  }

  @Test
  void jacksonFailuresNameTheMemberAndKeyword() {
    ErrorEnvelope malformed =
        assertError(
            put("/v1/me/preferences", "{\"board_id\":", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(malformed.fieldErrors())
        .containsExactly(new FieldError("body", "malformed", "request body is not valid JSON"));

    ErrorEnvelope unknown =
        assertError(
            post("/v1/me/consent", "{\"scopes\":{\"service\":true,\"extra\":1}}", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.UNKNOWN_FIELD);
    assertThat(unknown.fieldErrors())
        .containsExactly(new FieldError("scopes.extra", "additionalProperties", "unknown field"));

    ErrorEnvelope type =
        assertError(
            post("/v1/me/consent", "{\"scopes\":\"x\"}", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(type.fieldErrors()).extracting(FieldError::field).containsExactly("scopes");
    assertThat(type.fieldErrors()).extracting(FieldError::code).containsExactly("type");

    ErrorEnvelope parameter =
        assertError(
            get("/v1/_faults/param", token),
            HttpStatus.UNPROCESSABLE_CONTENT,
            ErrorCode.VALIDATION_FAILED);
    assertThat(parameter.fieldErrors())
        .containsExactly(new FieldError("n", "required", "parameter is required"));
  }

  @Test
  void incomingRequestIdIsEchoedInHeaderAndEnvelope() {
    String requestId = "probe-" + uniqueUsername();

    ResponseEntity<String> response =
        exchange(
            HttpMethod.GET, "/v1/nope", null, token, Map.of(RequestIdFilter.HEADER, requestId));

    assertError(response, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo(requestId);
    JsonNode body = tree(response.getBody());
    assertThat(body.get("request_id").asString()).isEqualTo(requestId);
  }

  @Test
  void bodyAtTheLimitIsAcceptedAndOneByteOverIsRefused() {
    String atLimit = padded(65536);
    String over = padded(65537);

    ResponseEntity<String> accepted = put("/v1/me/preferences", atLimit, token);
    ResponseEntity<String> refused = put("/v1/me/preferences", over, token);

    assertError(accepted, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNKNOWN_FIELD);
    assertError(refused, HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE);
  }

  /** A chunked body has no Content-Length, so only the bounded stream can stop it. */
  @Test
  void chunkedOversizedBodyIsRefused() throws IOException, InterruptedException {
    byte[] body = oversizedBody().getBytes();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/me/preferences"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .PUT(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(body)))
            .build();

    HttpResponse<String> response =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build()
            .send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    com.beaconclimb.api.identity.ContractSchemas.assertConforms("ErrorEnvelope", response.body());
    JsonNode envelope = tree(response.body());
    assertThat(envelope.get("code").asString()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.name());
    assertThat(envelope.get("request_id").asString())
        .isEqualTo(response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow());
  }

  private static String oversizedBody() {
    return padded(65 * 1024);
  }

  /** A JSON object of exactly {@code bytes} bytes whose only member is unknown to every schema. */
  private static String padded(int bytes) {
    String prefix = "{\"padding\":\"";
    String suffix = "\"}";
    return prefix + "x".repeat(bytes - prefix.length() - suffix.length()) + suffix;
  }

  private ResponseEntity<String> exchange(
      HttpMethod method, String path, String body, String bearer, Map<String, String> headers) {
    var spec =
        client()
            .method(method)
            .uri(path)
            .headers(
                h -> {
                  if (bearer != null) {
                    h.setBearerAuth(bearer);
                  }
                  if (body != null && !headers.containsKey("Content-Type")) {
                    h.setContentType(MediaType.APPLICATION_JSON);
                  }
                  headers.forEach(h::set);
                });
    if (body != null) {
      spec = spec.body(body);
    }
    return spec.retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }
}
