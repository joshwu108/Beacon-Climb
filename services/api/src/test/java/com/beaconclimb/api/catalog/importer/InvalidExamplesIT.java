package com.beaconclimb.api.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.catalog.importer.ImportRefusedException.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * Every negative fixture under contracts/catalog/examples/invalid is either refused before any
 * write (manifest / digest problems) or imports with the offending lines rejected; none imports
 * cleanly. Expected outcomes follow the README table.
 */
class InvalidExamplesIT extends ImporterTestSupport {

  static Stream<Arguments> refusedFixtures() {
    return Stream.of(
        Arguments.of("first-line-board", Reason.FILE_INVALID, "MANIFEST_FIRST"),
        Arguments.of("manifest-missing-synthetic", Reason.FILE_INVALID, "SCHEMA_INVALID"),
        Arguments.of(
            "manifest-synthetic-false-license-synthetic", Reason.FILE_INVALID, "SCHEMA_INVALID"),
        Arguments.of("record-counts-mismatch", Reason.FILE_INVALID, "RECORD_COUNTS_MISMATCH"),
        Arguments.of("wrong-content-sha256", Reason.CONTENT_SHA256_MISMATCH, "CONTENT_SHA256"));
  }

  static Stream<Arguments> rejectedLineFixtures() {
    return Stream.of(
        Arguments.of("route-unknown-role", List.of(3), "SCHEMA_INVALID"),
        Arguments.of("route-colon-in-uuid", List.of(3), "SCHEMA_INVALID"),
        Arguments.of("route-unknown-hold", List.of(3), "UNKNOWN_HOLD"),
        Arguments.of("route-angle-not-available", List.of(4), "ANGLE_NOT_AVAILABLE"),
        Arguments.of("route-before-board", List.of(2, 3, 4), "UNKNOWN_BOARD"));
  }

  @ParameterizedTest(name = "{0} is refused: {1}")
  @MethodSource("refusedFixtures")
  void refusedBeforeAnyWrite(String name, Reason reason, String messagePart) {
    Path file = INVALID_DIR.resolve(name + ".jsonl.txt");

    assertThatThrownBy(() -> importFile(file, true))
        .isInstanceOf(ImportRefusedException.class)
        .hasMessageContaining(messagePart)
        .extracting(e -> ((ImportRefusedException) e).reason())
        .isEqualTo(reason);
  }

  @ParameterizedTest(name = "{0} rejects lines {1}")
  @MethodSource("rejectedLineFixtures")
  void offendingLinesAreRejectedAndReported(String name, List<Integer> lines, String firstReason)
      throws IOException {
    Path file = INVALID_DIR.resolve(name + ".jsonl.txt");

    ImportResult result = importFile(file, false);

    assertThat(result.rejected()).isEqualTo(lines.size());
    assertThat(result.exitCode()).isEqualTo(ImportResult.EXIT_REJECTED_ROWS);
    List<JsonNode> report = readReport(result.reportFile());
    assertThat(report.stream().map(e -> e.get("line").asInt()).toList()).isEqualTo(lines);
    assertThat(report.get(0).get("reason").stringValue()).startsWith(firstReason);
    assertThat(Files.size(result.reportFile())).isPositive();
  }
}
