package com.beaconclimb.api;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * BC-0009b/BC-0009c: the contract paths /health/live and /health/ready must resolve on a booted
 * context backed by a real Postgres 17 using the least-privilege runtime role, so a misconfigured
 * actuator or role setup fails the build instead of the compose stack.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointsIT extends AbstractPostgresIT {

  @LocalServerPort int port;
  @Autowired DataSource dataSource;

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

  @Test
  void runtimeConnectionUsesNonSuperuserAppRole() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("beacon_app");
    assertThat(
            jdbc.queryForObject(
                "select rolsuper from pg_roles where rolname = current_user", Boolean.class))
        .isFalse();
    assertThat(jdbc.queryForObject("select has_schema_privilege('public','CREATE')", Boolean.class))
        .isFalse();
  }
}
