package com.beaconclimb.api;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared Postgres 17 container for integration tests. It is initialised with the very same
 * infra/compose/postgres/init.sql the dev stack uses, so tests run with the least-privilege roles
 * (beacon_migrate for Flyway, beacon_app for the application) rather than a superuser. The
 * container is a JVM-wide singleton: started once, reused by every test class, stopped by Ryuk when
 * the JVM exits.
 */
public abstract class AbstractPostgresIT {

  static final String MIGRATE_PASSWORD = "test-migrate-password";
  static final String APP_PASSWORD = "test-app-password";
  public static final PostgreSQLContainer POSTGRES;

  static {
    Path initSql =
        Path.of("..", "..", "infra", "compose", "postgres", "init.sql")
            .toAbsolutePath()
            .normalize();
    POSTGRES =
        new PostgreSQLContainer("postgres:17")
            .withDatabaseName("beacon")
            .withUsername("beacon")
            .withPassword("test-superuser-password")
            .withEnv("BEACON_MIGRATE_DB_PASSWORD", MIGRATE_PASSWORD)
            .withEnv("BEACON_APP_DB_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                MountableFile.forHostPath(initSql), "/docker-entrypoint-initdb.d/init.sql");
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", () -> "beacon_app");
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.user", () -> "beacon_migrate");
    registry.add("spring.flyway.password", () -> MIGRATE_PASSWORD);
  }
}
