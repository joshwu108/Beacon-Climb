package com.beaconclimb.api.features;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * {@code --spring.profiles.active=recompute [--recompute.user=<uuid>|all]
 * [--recompute.w=<instant>]} (BC-0113, {@code make recompute-features}). Prints one line per user:
 * {@code recomputed user_id=<id> cutoff_w=<W> feature_as_of=<instant|null> checksum=<hex>}. Exit 0
 * when every user succeeded, 2 for a bad argument, 1 when any recompute failed (the rest still
 * run).
 */
@Configuration
@Profile("recompute")
@EnableConfigurationProperties(RecomputeProperties.class)
class FeatureRecomputeRunner implements ApplicationRunner, ExitCodeGenerator {

  static final String ALL_USERS = "all";
  private static final Logger log = LoggerFactory.getLogger(FeatureRecomputeRunner.class);
  private static final int EXIT_OK = 0;
  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_USAGE = 2;

  private final FeatureRecomputeService features;
  private final RecomputeProperties properties;
  private final Clock clock;
  private int exitCode = EXIT_USAGE;

  FeatureRecomputeRunner(
      FeatureRecomputeService features, RecomputeProperties properties, Clock clock) {
    this.features = features;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    Instant cutoffW;
    List<UUID> users;
    try {
      cutoffW = cutoff();
      users = users();
    } catch (IllegalArgumentException e) {
      System.err.println("recompute: " + e.getMessage());
      return;
    }
    exitCode = EXIT_OK;
    for (UUID userId : users) {
      try {
        FeatureSnapshot snapshot = features.recompute(userId, cutoffW);
        System.out.println(line(snapshot));
      } catch (RuntimeException e) {
        log.error("recompute failed user_id={}", userId, e);
        System.err.println("recompute failed user_id=" + userId + ": " + e.getMessage());
        exitCode = EXIT_FAILURE;
      }
    }
  }

  static String line(FeatureSnapshot snapshot) {
    return "recomputed user_id="
        + snapshot.userId()
        + " cutoff_w="
        + snapshot.cutoffW()
        + " feature_as_of="
        + snapshot.featureAsOf()
        + " checksum="
        + snapshot.provenanceChecksum();
  }

  private Instant cutoff() {
    if (properties.w() == null || properties.w().isBlank()) {
      return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
    try {
      return Instant.parse(properties.w());
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(
          "--recompute.w must be an RFC 3339 instant such as 2026-10-14T14:00:00Z", e);
    }
  }

  private List<UUID> users() {
    if (ALL_USERS.equalsIgnoreCase(properties.user())) {
      return features.recomputableUserIds();
    }
    try {
      return List.of(UUID.fromString(properties.user()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("--recompute.user must be a UUID or 'all'", e);
    }
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
