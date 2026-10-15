package com.beaconclimb.api.platform.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BC-0109-JCS: the Java canonicaliser consumes contracts/jcs/fixtures.v1.json unchanged. Every
 * valid case must canonicalise, be idempotent and hash as listed; every invalid case must throw.
 */
class JcsTest {

  private static final Path FIXTURES =
      Path.of("..", "..", "contracts", "jcs", "fixtures.v1.json").toAbsolutePath().normalize();
  private static final int EXPECTED_VALID = 29;
  private static final int EXPECTED_INVALID = 16;

  private static JsonNode fixtures() throws IOException {
    String text = Files.readString(FIXTURES, StandardCharsets.UTF_8);
    return JsonMapper.builder().build().readTree(text);
  }

  @Test
  void fixtureFileHasTheContractedCaseCounts() throws IOException {
    JsonNode root = fixtures();
    assertThat(root.get("valid").size()).isEqualTo(EXPECTED_VALID);
    assertThat(root.get("invalid").size()).isEqualTo(EXPECTED_INVALID);
  }

  @TestFactory
  Stream<DynamicTest> validFixturesCanonicaliseAndHash() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode f : fixtures().get("valid")) {
      String name = f.get("name").stringValue();
      String input = f.get("input").stringValue();
      String canonical = f.get("canonical").stringValue();
      String sha256 = f.get("sha256").stringValue();
      tests.add(
          DynamicTest.dynamicTest(
              "valid: " + name,
              () -> {
                assertThat(Jcs.canonicalize(input)).as("%s canonical", name).isEqualTo(canonical);
                assertThat(Jcs.canonicalize(canonical))
                    .as("%s idempotent", name)
                    .isEqualTo(canonical);
                assertThat(Jcs.fingerprint(input)).as("%s sha256", name).isEqualTo(sha256);
              }));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> invalidFixturesAreRejected() throws IOException {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode f : fixtures().get("invalid")) {
      String name = f.get("name").stringValue();
      String input = f.get("input").stringValue();
      String reason = f.get("reason").stringValue();
      tests.add(
          DynamicTest.dynamicTest(
              "invalid: " + name,
              () ->
                  assertThatThrownBy(() -> Jcs.canonicalize(input))
                      .as("%s (%s)", name, reason)
                      .isInstanceOf(Jcs.JcsException.class)));
    }
    return tests.stream();
  }

  @Test
  void adr0002CrossLanguageCase() {
    assertThat(Jcs.canonicalize("{\"b\":1.0,\"a\":1}")).isEqualTo("{\"a\":1,\"b\":1}");
    assertThat(Jcs.canonicalize("{\"\\ufb33\":1,\"\\ud83d\\ude00\":2,\"\\u20ac\":3}"))
        .isEqualTo("{\"\u20ac\":3,\"\ud83d\ude00\":2,\"\ufb33\":1}");
  }

  @Test
  void numberFormattingCoversEcmaScriptBoundaries() {
    assertThat(Jcs.formatNumber(123456789012345678901.0)).isEqualTo("123456789012345680000");
    assertThat(Jcs.formatNumber(1e18)).isEqualTo("1000000000000000000");
    assertThat(Jcs.formatNumber(0.000001)).isEqualTo("0.000001");
    assertThat(Jcs.formatNumber(1.5e-7)).isEqualTo("1.5e-7");
    assertThat(Jcs.formatNumber(1.5e300)).isEqualTo("1.5e+300");
    assertThat(Jcs.formatNumber(-0.0)).isEqualTo("0");
    assertThat(Jcs.formatNumber(17.5)).isEqualTo("17.5");
    assertThat(Jcs.formatNumber(0.042)).isEqualTo("0.042");
  }
}
