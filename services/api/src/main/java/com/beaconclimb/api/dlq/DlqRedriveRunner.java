package com.beaconclimb.api.dlq;

import com.beaconclimb.api.dlq.DlqRedriveService.Entry;
import com.beaconclimb.api.dlq.DlqRedriveService.Options;
import com.beaconclimb.api.dlq.DlqRedriveService.Report;
import com.beaconclimb.api.dlq.DlqRedriveService.Result;
import com.beaconclimb.api.outbox.StreamProperties;
import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/**
 * {@code --spring.profiles.active=redrive --redrive.events=<id,id>|ALL [--redrive.dry-run=true]
 * [--redrive.topic=<topic>]} republishes DLQ records; {@code --redrive.list=true} prints the DLQ
 * count and the last {@value DlqRedriveService#STATUS_TAIL} records instead. Audit lines go to
 * stdout, one per selected record, in the fixed format of {@link DlqRedriveService.Audit#line()}.
 * Exit 0 only when every selected record was published (or dry-run, or listed); 1 when any was
 * skipped; 2 for a usage error (printed to stderr).
 */
public final class DlqRedriveRunner implements ApplicationRunner, ExitCodeGenerator {

  static final String ALL = "ALL";
  static final int EXIT_OK = 0;
  static final int EXIT_SKIPPED = 1;
  static final int EXIT_USAGE = 2;

  private final DlqRedriveService service;
  private final DlqRedriveProperties options;
  private final StreamProperties stream;
  private final PrintStream out;
  private final PrintStream err;
  private int exitCode = EXIT_USAGE;

  DlqRedriveRunner(
      DlqRedriveService service,
      DlqRedriveProperties options,
      StreamProperties stream,
      PrintStream out,
      PrintStream err) {
    this.service = service;
    this.options = options;
    this.stream = stream;
    this.out = out;
    this.err = err;
  }

  @Override
  public void run(ApplicationArguments args) {
    String topic;
    try {
      topic = topic();
    } catch (IllegalArgumentException e) {
      err.println("redrive: " + e.getMessage());
      return;
    }
    if (options.list()) {
      exitCode = list(topic);
      return;
    }
    Set<String> ids;
    try {
      ids = eventIds();
    } catch (IllegalArgumentException e) {
      err.println("redrive: " + e.getMessage());
      return;
    }
    Report report = service.redrive(new Options(topic, ids, options.dryRun()), out::println);
    out.println(
        "redrive summary topic="
            + topic
            + " scanned="
            + report.scanned()
            + " selected="
            + report.audits().size()
            + " published="
            + report.count(Result.PUBLISHED)
            + " dry_run="
            + report.count(Result.DRY_RUN)
            + " skipped="
            + report.count(Result.SKIPPED));
    exitCode = report.allSucceeded() ? EXIT_OK : EXIT_SKIPPED;
  }

  private int list(String topic) {
    List<Entry> entries = service.list(topic);
    out.println("dlq-status topic=" + topic + " records=" + entries.size());
    int from = Math.max(0, entries.size() - DlqRedriveService.STATUS_TAIL);
    for (Entry entry : entries.subList(from, entries.size())) {
      out.println(entry.line());
    }
    return EXIT_OK;
  }

  /**
   * The DLQ topic: {@code --redrive.topic} when given, otherwise the configured stream-dlq.v1; a
   * configured name that is not stream-dlq.v1 is refused without the explicit flag so a
   * misconfigured environment cannot redrive the wrong topic by accident.
   */
  private String topic() {
    if (options.topic() != null && !options.topic().isBlank()) {
      return options.topic().trim();
    }
    String configured = stream.topics().dlq();
    if (!DlqRedriveService.DEFAULT_TOPIC.equals(configured)) {
      throw new IllegalArgumentException(
          "refusing to read '"
              + configured
              + "'; only "
              + DlqRedriveService.DEFAULT_TOPIC
              + " is read by default, pass --redrive.topic=<topic> explicitly");
    }
    return configured;
  }

  private Set<String> eventIds() {
    String raw = options.events() == null ? "" : options.events().trim();
    if (raw.isEmpty()) {
      throw new IllegalArgumentException(
          "--redrive.events=<comma-separated DLQ event_ids>|ALL is required (or --redrive.list=true)");
    }
    if (ALL.equals(raw.toUpperCase(Locale.ROOT))) {
      return Set.of();
    }
    Set<String> ids = new LinkedHashSet<>();
    for (String id : raw.split(",")) {
      String trimmed = id.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      try {
        UUID.fromString(trimmed);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("'" + trimmed + "' is not a UUID event_id");
      }
      ids.add(trimmed);
    }
    if (ids.isEmpty()) {
      throw new IllegalArgumentException("--redrive.events lists no event_id");
    }
    return ids;
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
