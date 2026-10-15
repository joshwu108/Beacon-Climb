package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.AbstractPostgresIT;
import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.ErrorEnvelope;
import com.beaconclimb.api.platform.web.RequestIdFilter;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared harness for the identity slice: booted context on a random port against the shared
 * Postgres, RestClient helpers that never throw on 4xx, and contract-schema assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IdentityTestSupport extends AbstractPostgresIT {

  protected static final String PASSWORD = "correct-horse-battery";
  private static final SecureRandom RANDOM = new SecureRandom();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired LoginAttemptLimiter limiter;
  @Autowired JdbcClient jdbc;

  /** The limiter is per JVM and the context is shared, so no class inherits another's failures. */
  @BeforeEach
  void resetLoginLimiter() {
    limiter.clear();
  }

  protected static String uniqueUsername() {
    byte[] bytes = new byte[6];
    RANDOM.nextBytes(bytes);
    return "u" + HexFormat.of().formatHex(bytes);
  }

  protected RestClient client() {
    return RestClient.create("http://127.0.0.1:" + port);
  }

  protected ResponseEntity<String> post(String path, String body, String token) {
    return post(path, body, token, Map.of());
  }

  protected ResponseEntity<String> post(
      String path, String body, String token, Map<String, String> headers) {
    return client()
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .headers(h -> headers.forEach(h::set))
        .headers(h -> bearer(h::setBearerAuth, token))
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  protected ResponseEntity<String> get(String path, String token) {
    return client()
        .get()
        .uri(path)
        .headers(h -> bearer(h::setBearerAuth, token))
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  protected ResponseEntity<String> delete(String path, String token) {
    return client()
        .delete()
        .uri(path)
        .headers(h -> bearer(h::setBearerAuth, token))
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  private static void bearer(java.util.function.Consumer<String> setter, String token) {
    if (token != null) {
      setter.accept(token);
    }
  }

  protected String authBody(String username, String password) {
    return json.writeValueAsString(Map.of("username", username, "password", password));
  }

  /** Registers a fresh user and returns its session token. */
  protected AuthResponse register(String username) {
    ResponseEntity<String> response = post("/v1/auth/register", authBody(username, PASSWORD), null);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return json.readValue(response.getBody(), AuthResponse.class);
  }

  protected UUID userIdOf(String token) {
    ResponseEntity<String> me = get("/v1/me", token);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    return UUID.fromString(tree(me.getBody()).get("user_id").asString());
  }

  protected JsonNode tree(String body) {
    return json.readTree(body);
  }

  /** Every 4xx/5xx body must be a contract ErrorEnvelope with the expected code and request id. */
  protected ErrorEnvelope assertError(
      ResponseEntity<String> response, HttpStatus status, ErrorCode code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getFirst(RequestIdFilter.HEADER)).isNotBlank();
    ContractSchemas.assertConforms("ErrorEnvelope", response.getBody());
    ErrorEnvelope envelope = json.readValue(response.getBody(), ErrorEnvelope.class);
    assertThat(envelope.code()).isEqualTo(code);
    assertThat(envelope.requestId())
        .isEqualTo(response.getHeaders().getFirst(RequestIdFilter.HEADER));
    return envelope;
  }
}
