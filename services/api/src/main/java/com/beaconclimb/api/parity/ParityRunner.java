package com.beaconclimb.api.parity;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code --spring.profiles.active=parity [--parity.user=<uuid>|all] [--parity.out=<path>]
 * [--parity.commit=<sha>]} (BC-0306, {@code make test-parity}, {@code scripts/parity/parity.sh}):
 * the host-JVM CLI pattern of {@code make recompute-features}. Runs {@link ParityService#run},
 * writes the JSON report (format: scripts/parity/README.md) and prints one {@code parity summary
 * ...} line.
 *
 * <p>Exit codes: 0 when every compared user is EQUAL (no DIFF, no SAME_W_MISMATCH; a run with no
 * STREAM row at all is 0 with {@code compared=0}); 1 when any user is DIFF or SAME_W_MISMATCH; 2
 * for a bad argument, a failed run, or the synthetic-only refusal: when any compared user is not a
 * {@code synthetic_*}, {@code e2e_*} or {@code demo} account the report is not written and the
 * count of such users is printed (the report would otherwise record ids of real users' feature rows
 * in docs/evidence). The oracle never writes to any table (ParityServiceIT asserts it).
 */
@Configuration
@Profile("parity")
@EnableConfigurationProperties(ParityProperties.class)
class ParityRunner implements ApplicationRunner, ExitCodeGenerator {

  static final String ALL_USERS = "all";
  static final Path DEFAULT_OUT_DIR = Path.of("docs", "evidence", "BC-0306");
  private static final Logger log = LoggerFactory.getLogger(ParityRunner.class);
  private static final int EXIT_OK = 0;
  private static final int EXIT_PARITY_FAILED = 1;
  private static final int EXIT_ERROR = 2;
  private static final DateTimeFormatter FILE_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
  private static final JsonMapper PRETTY =
      JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

  private final ParityService service;
  private final ParityProperties properties;
  private final Clock clock;
  private final PrintStream out;
  private final PrintStream err;
  private int exitCode = EXIT_ERROR;

  @Autowired
  ParityRunner(ParityService service, ParityProperties properties, Clock clock) {
    this(service, properties, clock, System.out, System.err);
  }

  ParityRunner(
      ParityService service,
      ParityProperties properties,
      Clock clock,
      PrintStream out,
      PrintStream err) {
    this.service = service;
    this.properties = properties;
    this.clock = clock;
    this.out = out;
    this.err = err;
  }

  @Override
  public void run(ApplicationArguments args) {
    Optional<UUID> user;
    Path target;
    try {
      user = user();
      target = outPath(properties.out(), clock);
    } catch (IllegalArgumentException e) {
      err.println("parity: " + e.getMessage());
      exitCode = EXIT_ERROR;
      return;
    }
    ParityReport report;
    try {
      report = service.run(user, properties.commit());
    } catch (RuntimeException e) {
      log.error("parity failed", e);
      err.println("parity: run failed: " + e.getMessage());
      exitCode = EXIT_ERROR;
      return;
    }
    if (!report.syntheticOnly()) {
      err.println(
          "parity: refusing to write the report: "
              + report.nonSyntheticCompared()
              + " compared user(s) are not synthetic_/e2e_/demo accounts (counts only: "
              + report.summaryLine("<not written>")
              + ")");
      exitCode = EXIT_ERROR;
      return;
    }
    try {
      write(report, target);
    } catch (IOException e) {
      err.println("parity: cannot write " + target + ": " + e.getMessage());
      exitCode = EXIT_ERROR;
      return;
    }
    out.println(report.summaryLine(target.toString()));
    exitCode = report.passed() ? EXIT_OK : EXIT_PARITY_FAILED;
  }

  static void write(ParityReport report, Path target) throws IOException {
    Path parent = target.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        target, PRETTY.writeValueAsString(report.toJson()) + "\n", StandardCharsets.UTF_8);
  }

  private Optional<UUID> user() {
    if (ALL_USERS.equalsIgnoreCase(properties.user())) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(properties.user()));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("--parity.user must be a UUID or 'all'", e);
    }
  }

  /** {@code configured} when given, else a timestamped file under {@link #DEFAULT_OUT_DIR}. */
  static Path outPath(String configured, Clock clock) {
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    String stamp = FILE_STAMP.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    return DEFAULT_OUT_DIR.resolve("parity-" + stamp + ".json");
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
