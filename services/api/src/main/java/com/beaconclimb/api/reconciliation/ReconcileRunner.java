package com.beaconclimb.api.reconciliation;

import com.beaconclimb.api.reconciliation.ReconcileService.Report;
import com.beaconclimb.api.reconciliation.ReconcileService.UserResult;
import java.io.PrintStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code --spring.profiles.active=reconcile} ({@code make reconcile}, BC-0309): the host-JVM CLI
 * pattern of {@code make recompute-features}. Runs {@link ReconcileService#reconcileAll()}, prints
 * {@code reconciled user_id=<id> rows=<n> cutoff_w=<W> checksum=<hex> resolution=<RECONCILED|
 * ALREADY_CURRENT|ERROR>} per user (an ERROR line carries {@code error=<message>} and no checksum)
 * and one {@code reconcile summary users=<n> rows=<n> reconciled=<n> already_current=<n>
 * errors=<n>} line (a test passes its own stream through the second constructor). Exit 0 when every
 * user succeeded (including a run with nothing open), 1 when any user ended in ERROR (its rows stay
 * open for the next run).
 *
 * <p><b>What this job repairs, and what it does not.</b> Only the SYNC row of {@code
 * user_feature_snapshots} is recomputed, at {@code W = now} from Postgres, which holds the late
 * attempt because the api wrote it. The STREAM row stays whatever the stream job last emitted: its
 * Flink state excluded the late event, and nothing here rewrites that state or republishes a
 * climber-features.v1 record (ADR-0004: a batch rebuild publishes a newer sequence; that rebuild is
 * not part of BC-0309). After a run the SYNC row is at least as fresh as the STREAM row, so the
 * BC-0310 part 2 read rule (prefer STREAM only when its {@code cutoff_w} is at least the SYNC
 * {@code cutoff_w}) serves the repaired SYNC row until the stream catches up with a newer watermark
 * of its own; at that point the STREAM row still lacks the late attempt and parity at its {@code
 * cutoff_w} fails until a rebuild, which is what BC-0306 / BC-0307 "reconciliation" measures.
 */
@Component
@Profile("reconcile")
class ReconcileRunner implements ApplicationRunner, ExitCodeGenerator {

  private static final int EXIT_OK = 0;
  private static final int EXIT_FAILURE = 1;

  private final ReconcileService service;
  private final PrintStream out;
  private int exitCode = EXIT_FAILURE;

  @Autowired
  ReconcileRunner(ReconcileService service) {
    this(service, System.out);
  }

  ReconcileRunner(ReconcileService service, PrintStream out) {
    this.service = service;
    this.out = out;
  }

  @Override
  public void run(ApplicationArguments args) {
    Report report = service.reconcileAll();
    for (UserResult result : report.users()) {
      out.println(line(result));
    }
    out.println(summary(report));
    exitCode = report.anyFailed() ? EXIT_FAILURE : EXIT_OK;
  }

  static String line(UserResult result) {
    StringBuilder line =
        new StringBuilder("reconciled user_id=")
            .append(result.userId())
            .append(" rows=")
            .append(result.rows())
            .append(" cutoff_w=")
            .append(result.cutoffW())
            .append(" checksum=")
            .append(result.checksum())
            .append(" resolution=")
            .append(result.resolution());
    if (result.failed()) {
      line.append(" error=").append(result.error());
    }
    return line.toString();
  }

  static String summary(Report report) {
    return "reconcile summary users="
        + report.users().size()
        + " rows="
        + report.rows()
        + " reconciled="
        + report.count(Resolution.RECONCILED)
        + " already_current="
        + report.count(Resolution.ALREADY_CURRENT)
        + " errors="
        + report.count(Resolution.ERROR);
  }

  @Override
  public int getExitCode() {
    return exitCode;
  }
}
