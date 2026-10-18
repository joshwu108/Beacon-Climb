package com.beaconclimb.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The committed golden fixtures are what the computer produces today: a drift between the files and
 * the code fails here, and the files are what the api's copy (follow-up) and the stream operator
 * ({@code :stream} GoldenFixtureReplayTest) are held to.
 */
class GoldenFixturesTest {

  static final Path DIRECTORY =
      Path.of("src", "test", "resources", "golden", "climber-features-v1");
  static final int MINIMUM_CASES = 12;

  static Stream<GoldenFixture> fixtures() {
    return GoldenFixture.readAll(DIRECTORY).stream();
  }

  @Test
  @DisplayName("at least twelve committed cases, each with an expected block and a unique name")
  void committedSet() {
    List<GoldenFixture> all = GoldenFixture.readAll(DIRECTORY);

    assertThat(all).hasSizeGreaterThanOrEqualTo(MINIMUM_CASES);
    assertThat(all).extracting(GoldenFixture::name).doesNotHaveDuplicates();
    assertThat(all).allSatisfy(fixture -> assertThat(fixture.expected()).isNotNull());
    assertThat(GoldenFixtureGenerator.cases()).hasSameSizeAs(all);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName("the computer reproduces the committed aggregates text, checksum and feature_as_of")
  void computerReproducesFixture(GoldenFixture fixture) {
    FeatureComputer.Result result =
        FeatureComputer.compute(fixture.activeRows(), fixture.cutoffW());

    assertThat(result.aggregates().toJson()).isEqualTo(fixture.expected().aggregatesJson());
    assertThat(result.checksum()).isEqualTo(fixture.expected().provenanceChecksum());
    assertThat(result.featureAsOf()).isEqualTo(fixture.expected().featureAsOf());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName("the committed file is byte-identical to what the generator writes for the case")
  void fileMatchesGenerator(GoldenFixture fixture) {
    GoldenFixture generated =
        GoldenFixtureGenerator.cases().stream()
            .filter(candidate -> candidate.name().equals(fixture.name()))
            .findFirst()
            .orElseThrow();

    String expectedText = GoldenFixtureGenerator.toJson(GoldenFixtureGenerator.computed(generated));
    assertThat(read(fixture.name())).isEqualTo(expectedText);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixtures")
  @DisplayName("the independent reference restatement agrees with every fixture")
  void referenceAgrees(GoldenFixture fixture) {
    List<ReferenceFeatures.Row> rows =
        fixture.activeRows().stream()
            .map(
                row ->
                    new ReferenceFeatures.Row(
                        row.attemptId(),
                        row.revision(),
                        row.routeVariantId(),
                        row.occurredAt(),
                        row.sent(),
                        row.gradeNumeric()))
            .toList();

    ReferenceFeatures.Expected expected =
        ReferenceFeatures.compute(
            rows, fixture.cutoffW(), tools.jackson.databind.json.JsonMapper.builder().build());

    assertThat(expected.aggregatesJson()).isEqualTo(fixture.expected().aggregatesJson());
    assertThat(expected.checksum()).isEqualTo(fixture.expected().provenanceChecksum());
  }

  private static String read(String name) {
    try {
      return java.nio.file.Files.readString(DIRECTORY.resolve(name + GoldenFixture.FILE_SUFFIX));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
