package com.beaconclimb.api.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.AbstractPostgresIT;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * BC-0123c (F6b): the {@code migrate} profile boots without a web server, forces Flyway on even
 * when {@code BEACON_FLYWAY_ENABLED=false} is in the environment, leaves no migration pending and
 * reports every applied version; and Postgres refuses an empty schema-owner password, so the {@code
 * ${BEACON_MIGRATE_DB_PASSWORD}} placeholder can never fall back to a working value (TM-27).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"spring.profiles.active=migrate", "BEACON_FLYWAY_ENABLED=false"})
class MigrateProfileIT extends AbstractPostgresIT {

  @Autowired ApplicationContext context;
  @Autowired Environment environment;
  @Autowired Flyway flyway;

  @Test
  void migrateProfileHasNoWebServerForcesFlywayOnAndLeavesNothingPending() {
    assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
    assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
    assertThat(context.getBeansOfType(MigrateRunner.class)).hasSize(1);
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().current().getVersion().toString()).isEqualTo("9");
  }

  @Test
  void runnerReportListsEveryAppliedVersionAndReportsNothingPending() {
    String report = MigrateRunner.report(flyway.info());

    assertThat(report)
        .contains("migrated version=1 script=V1__identity_catalog_provenance.sql")
        .contains("migrated version=8 script=V8__account_creations.sql")
        .contains("migrated version=9 script=V9__feature_snapshots_stream.sql")
        .endsWith("migrations applied=9 pending=0");
  }

  /** An empty migrate password must fail loudly, never silently migrate (TM-27). */
  @Test
  void emptyMigratePasswordIsRefusedByPostgres() {
    Flyway unauthenticated =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), "beacon_migrate", "")
            .locations("classpath:db/migration")
            .load();

    assertThatThrownBy(unauthenticated::migrate)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("password");
  }
}
