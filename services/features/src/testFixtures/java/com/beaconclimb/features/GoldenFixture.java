package com.beaconclimb.features;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One file of {@code src/test/resources/golden/climber-features-v1} (format: services/features/
 * README.md): an input attempt set as it could reach any implementation (duplicates, superseded
 * revisions and RETRACTED rows included), the cutoff {@code W}, and what the shared computer
 * produced for it. Every implementation of the contract must reproduce {@code expected} from {@link
 * #activeRows()}: the highest revision per {@code attempt_id} (the first listed among equal
 * revisions), minus the rows whose current revision is RETRACTED.
 *
 * @param expected null while a fixture is being generated
 */
public record GoldenFixture(
    String name, String description, Instant cutoffW, List<Attempt> attempts, Expected expected) {

  /** One input row; {@code status} is ACTIVE or RETRACTED, {@code gradeNumeric} nullable. */
  public record Attempt(
      UUID attemptId,
      int revision,
      String routeVariantId,
      Instant occurredAt,
      boolean sent,
      Double gradeNumeric,
      String status) {

    public static final String ACTIVE = "ACTIVE";
    public static final String RETRACTED = "RETRACTED";

    public boolean isRetracted() {
      return RETRACTED.equals(status);
    }

    public AttemptFeatureRow toRow() {
      return new AttemptFeatureRow(
          attemptId, revision, routeVariantId, occurredAt, sent, gradeNumeric);
    }
  }

  /**
   * @param featureAsOf null when the included set is empty
   * @param aggregatesJson the canonical (JCS) text, compared byte for byte
   */
  public record Expected(Instant featureAsOf, String provenanceChecksum, String aggregatesJson) {}

  public static final String FILE_SUFFIX = ".json";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  public GoldenFixture {
    attempts = List.copyOf(attempts);
  }

  /** The de-duplicated ACTIVE rows the contract calls {@code A(u, W)} before the cutoff filter. */
  public List<AttemptFeatureRow> activeRows() {
    Map<UUID, Attempt> latest = new LinkedHashMap<>();
    for (Attempt attempt : attempts) {
      latest.merge(
          attempt.attemptId(),
          attempt,
          (kept, next) -> next.revision() > kept.revision() ? next : kept);
    }
    List<AttemptFeatureRow> rows = new ArrayList<>();
    for (Attempt attempt : latest.values()) {
      if (!attempt.isRetracted()) {
        rows.add(attempt.toRow());
      }
    }
    return List.copyOf(rows);
  }

  /** The file name, so parameterised test names stay readable. */
  @Override
  public String toString() {
    return name;
  }

  public GoldenFixture withExpected(Expected expected) {
    return new GoldenFixture(name, description, cutoffW, attempts, expected);
  }

  /** Every fixture of {@code directory}, sorted by file name. */
  public static List<GoldenFixture> readAll(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
          .sorted()
          .map(GoldenFixture::read)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + directory, e);
    }
  }

  public static GoldenFixture read(Path file) {
    try {
      return fromJson(MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  public static GoldenFixture fromJson(JsonNode root) {
    List<Attempt> attempts = new ArrayList<>();
    for (JsonNode node : root.get("attempts")) {
      JsonNode grade = node.get("grade_numeric");
      attempts.add(
          new Attempt(
              UUID.fromString(node.get("attempt_id").asString()),
              node.get("revision").asInt(),
              node.get("route_variant_id").asString(),
              Instant.parse(node.get("occurred_at").asString()),
              node.get("sent").asBoolean(),
              grade == null || grade.isNull() ? null : grade.doubleValue(),
              node.get("status").asString()));
    }
    JsonNode expectedNode = root.get("expected");
    Expected expected = null;
    if (expectedNode != null && !expectedNode.isNull()) {
      JsonNode asOf = expectedNode.get("feature_as_of");
      expected =
          new Expected(
              asOf.isNull() ? null : Instant.parse(asOf.asString()),
              expectedNode.get("provenance_checksum").asString(),
              expectedNode.get("aggregates_json").asString());
    }
    return new GoldenFixture(
        root.get("name").asString(),
        root.get("description").asString(),
        Instant.parse(root.get("cutoff_w").asString()),
        attempts,
        expected);
  }
}
