package com.beaconclimb.api.db;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code --spring.profiles.active=migrate} (BC-0123c, F6b; compose service {@code migrate}). Boot's
 * {@code FlywayMigrationInitializer} has already applied every pending migration as the
 * schema-owner role by the time this runner executes, so it only confirms the outcome: it prints
 * one {@code migrated version=<v> script=<name>} line per applied migration plus a {@code
 * migrations applied=<n> pending=0} summary, and fails startup (non-zero exit) if anything is still
 * pending or the history holds a failed migration. With {@code web-application-type: none} the JVM
 * exits 0 as soon as {@code main} returns; the long-running api then starts with {@code
 * BEACON_FLYWAY_ENABLED=false} and without the migrate password.
 */
@Configuration
@Profile("migrate")
class MigrateRunner implements ApplicationRunner {

  private final Flyway flyway;

  MigrateRunner(Flyway flyway) {
    this.flyway = flyway;
  }

  @Override
  public void run(ApplicationArguments args) {
    System.out.println(report(flyway.info()));
  }

  static String report(MigrationInfoService info) {
    MigrationInfo[] applied = info.applied();
    MigrationInfo[] pending = info.pending();
    boolean failed = Arrays.stream(applied).anyMatch(m -> m.getState().isFailed());
    if (pending.length > 0 || failed) {
      throw new IllegalStateException(
          "migrate: schema is not current (pending=" + pending.length + ", failed=" + failed + ")");
    }
    String lines =
        Arrays.stream(applied)
            .map(m -> "migrated version=" + m.getVersion() + " script=" + m.getScript())
            .collect(Collectors.joining("\n"));
    return lines + "\nmigrations applied=" + applied.length + " pending=0";
  }
}
