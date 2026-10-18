package com.beaconclimb.api.parity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.parity.ParityReport.Detail;
import com.beaconclimb.api.parity.ParityReport.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The oracle CLI's control flow without a database: exit codes per verdict, the synthetic-only
 * refusal (no file written, counts only on stderr), the report members, the summary line, the
 * default output path and argument errors.
 */
class ParityRunnerTest {

  private static final Instant NOW = Instant.parse("2026-10-18T10:00:00.123Z");
  private static final UUID USER_A = UUID.fromString("018f0000-0000-7000-8000-00000000000a");
  private static final UUID USER_B = UUID.fromString("018f0000-0000-7000-8000-00000000000b");
  private static final Instant W = Instant.parse("2026-10-18T09:59:58Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path tempDir;

  /** Returns a canned report (or throws) instead of touching Postgres. */
  private static final class FakeService extends ParityService {
    ParityReport report;
    RuntimeException failure;
    Optional<UUID> askedFor;

    FakeService() {
      super(null, null, null, null);
    }

    @Override
    public ParityReport run(Optional<UUID> onlyUser, String commit) {
      askedFor = onlyUser;
      if (failure != null) {
        throw failure;
      }
      return report;
    }
  }

  private static Detail equal(UUID userId) {
    return new Detail(userId, Status.EQUAL, W, W, W, W, 2, 41L, List.of(), false);
  }

  private static Detail diff(UUID userId) {
    return new Detail(
        userId, Status.DIFF, W, W, W, W, 2, 42L, List.of("sends_7d", "sends_90d"), true);
  }

  private static ParityReport report(int nonSynthetic, Detail... details) {
    return new ParityReport(NOW, "abc123", details.length + 1, List.of(details), nonSynthetic);
  }

  @Test
  void allEqualWritesTheReportPrintsTheSummaryAndExitsZero() throws IOException {
    FakeService service = new FakeService();
    service.report = report(0, equal(USER_A), Detail.noStreamRow(USER_B));
    Path out = tempDir.resolve("nested").resolve("report.json");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ParityRunner runner =
        runner(service, "all", out.toString(), stdout, new ByteArrayOutputStream());

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isZero();
    assertThat(service.askedFor).isEmpty();
    JsonNode written = JSON.readTree(Files.readString(out, StandardCharsets.UTF_8));
    assertThat(written.get("generated_at").asString()).isEqualTo("2026-10-18T10:00:00.123Z");
    assertThat(written.get("commit").asString()).isEqualTo("abc123");
    assertThat(written.get("users_total").asInt()).isEqualTo(3);
    assertThat(written.get("users_with_stream_row").asInt()).isEqualTo(1);
    assertThat(written.get("compared").asInt()).isEqualTo(1);
    assertThat(written.get("equal").asInt()).isEqualTo(1);
    assertThat(written.get("diffs").asInt()).isZero();
    assertThat(written.get("same_w_mismatch").asInt()).isZero();
    assertThat(written.get("skipped_no_stream_row").asInt()).isEqualTo(1);
    assertThat(written.get("synthetic_only").asBoolean()).isTrue();
    assertThat(written.get("details")).hasSize(2);
    JsonNode first = written.get("details").get(0);
    assertThat(first.get("user_id").asString()).isEqualTo(USER_A.toString());
    assertThat(first.get("status").asString()).isEqualTo("EQUAL");
    assertThat(first.get("cutoff_w").asString()).isEqualTo("2026-10-18T09:59:58Z");
    assertThat(first.get("stream_partition").asInt()).isEqualTo(2);
    assertThat(first.get("stream_offset").asLong()).isEqualTo(41L);
    assertThat(first.get("diff_keys")).isEmpty();
    assertThat(first.get("checksum_differs").asBoolean()).isFalse();
    JsonNode second = written.get("details").get(1);
    assertThat(second.get("status").asString()).isEqualTo("NO_STREAM_ROW");
    assertThat(second.get("cutoff_w").isNull()).isTrue();
    assertThat(second.get("stream_offset").isNull()).isTrue();
    assertThat(written.toString()).doesNotContain("username");
    assertThat(stdout.toString(StandardCharsets.UTF_8).strip())
        .isEqualTo(
            "parity summary users=3 with_stream_row=1 compared=1 equal=1 diffs=0"
                + " same_w_mismatch=0 skipped_no_stream_row=1 synthetic_only=true out="
                + out);
  }

  @Test
  void aDiffOrASameWMismatchStillWritesTheReportButExitsOne() throws IOException {
    FakeService service = new FakeService();
    service.report = report(0, equal(USER_A), diff(USER_B));
    Path out = tempDir.resolve("diff.json");
    ParityRunner runner =
        runner(
            service,
            "all",
            out.toString(),
            new ByteArrayOutputStream(),
            new ByteArrayOutputStream());

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isEqualTo(1);
    JsonNode written = JSON.readTree(Files.readString(out, StandardCharsets.UTF_8));
    assertThat(written.get("diffs").asInt()).isEqualTo(1);
    JsonNode detail = written.get("details").get(1);
    assertThat(detail.get("diff_keys").valueStream().map(JsonNode::asString))
        .containsExactly("sends_7d", "sends_90d");
    assertThat(detail.get("checksum_differs").asBoolean()).isTrue();

    Detail mismatch =
        new Detail(USER_A, Status.SAME_W_MISMATCH, W, W, W, W, 0, 1L, List.of(), false);
    service.report = report(0, mismatch);
    ParityRunner second =
        runner(
            service,
            "all",
            out.toString(),
            new ByteArrayOutputStream(),
            new ByteArrayOutputStream());
    second.run(new DefaultApplicationArguments());
    assertThat(second.getExitCode()).isEqualTo(1);
    assertThat(JSON.readTree(Files.readString(out)).get("same_w_mismatch").asInt()).isEqualTo(1);
  }

  @Test
  void nonSyntheticComparedUserRefusesWithoutWritingAndPrintsTheCountOnly() {
    FakeService service = new FakeService();
    service.report = report(1, equal(USER_A), equal(USER_B));
    Path out = tempDir.resolve("refused.json");
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ParityRunner runner = runner(service, "all", out.toString(), stdout, stderr);

    runner.run(new DefaultApplicationArguments());

    assertThat(runner.getExitCode()).isEqualTo(2);
    assertThat(Files.exists(out)).isFalse();
    assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(stderr.toString(StandardCharsets.UTF_8))
        .contains("refusing to write the report")
        .contains("1 compared user(s) are not synthetic_/e2e_/demo accounts")
        .doesNotContain(USER_A.toString())
        .doesNotContain(USER_B.toString());
  }

  @Test
  void singleUserIsPassedThroughAndBadUserOrFailedRunIsExitTwo() {
    FakeService service = new FakeService();
    service.report = report(0, equal(USER_A));
    ParityRunner one =
        runner(
            service,
            USER_A.toString(),
            tempDir.resolve("one.json").toString(),
            new ByteArrayOutputStream(),
            new ByteArrayOutputStream());
    one.run(new DefaultApplicationArguments());
    assertThat(one.getExitCode()).isZero();
    assertThat(service.askedFor).contains(USER_A);

    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ParityRunner bad =
        runner(
            service,
            "nobody",
            tempDir.resolve("bad.json").toString(),
            new ByteArrayOutputStream(),
            stderr);
    bad.run(new DefaultApplicationArguments());
    assertThat(bad.getExitCode()).isEqualTo(2);
    assertThat(stderr.toString(StandardCharsets.UTF_8))
        .contains("--parity.user must be a UUID or 'all'");
    assertThat(Files.exists(tempDir.resolve("bad.json"))).isFalse();

    service.failure = new IllegalStateException("connection refused");
    ParityRunner failed =
        runner(
            service,
            "all",
            tempDir.resolve("failed.json").toString(),
            new ByteArrayOutputStream(),
            stderr);
    failed.run(new DefaultApplicationArguments());
    assertThat(failed.getExitCode()).isEqualTo(2);
    assertThat(Files.exists(tempDir.resolve("failed.json"))).isFalse();
  }

  @Test
  void defaultOutputIsATimestampedFileUnderTheEvidenceDirectory() {
    assertThat(ParityRunner.outPath(null, clock()))
        .isEqualTo(Path.of("docs", "evidence", "BC-0306", "parity-20261018T100000Z.json"));
    assertThat(ParityRunner.outPath(" ", clock()))
        .isEqualTo(Path.of("docs", "evidence", "BC-0306", "parity-20261018T100000Z.json"));
    assertThat(ParityRunner.outPath("reports/x.json", clock()))
        .isEqualTo(Path.of("reports", "x.json"));
  }

  private static ParityRunner runner(
      ParityService service,
      String user,
      String out,
      ByteArrayOutputStream stdout,
      ByteArrayOutputStream stderr) {
    return new ParityRunner(
        service,
        new ParityProperties(user, out, "abc123"),
        clock(),
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }
}
