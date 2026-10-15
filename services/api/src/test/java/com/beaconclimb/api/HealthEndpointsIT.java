package com.beaconclimb.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * BC-0009b: the contract paths /health/live and /health/ready must resolve on a booted context
 * backed by a real Postgres 17 (Testcontainers), so a misconfigured actuator fails the build
 * instead of the compose stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HealthEndpointsIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

  @LocalServerPort int port;

  private ResponseEntity<String> get(String path) {
    return RestClient.create("http://127.0.0.1:" + port)
        .get()
        .uri(path)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(String.class);
  }

  @Test
  void livenessResolvesAtContractPath() {
    ResponseEntity<String> response = get("/health/live");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
  }

  @Test
  void readinessIncludesDatabaseAndResolvesAtContractPath() {
    ResponseEntity<String> response = get("/health/ready");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("{\"status\":\"UP\"}");
  }

  @Test
  void legacyActuatorPathIsNotExposed() {
    assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }
}
