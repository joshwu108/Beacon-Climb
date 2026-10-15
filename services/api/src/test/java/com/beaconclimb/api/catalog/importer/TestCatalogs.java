package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.platform.json.Jcs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Builds throwaway catalog files from contracts/catalog/examples/synthetic-mini.jsonl: ids get a
 * unique suffix so tests never collide in the shared database, the manifest is regenerated and
 * content_sha256 recomputed with {@link Jcs} over the (possibly edited) record lines. Files that
 * claim {@code synthetic=false} are written to a temp dir only and never committed.
 */
final class TestCatalogs {

  /** Mutable spec for one generated file. */
  record Spec(
      String suffix,
      boolean synthetic,
      String license,
      UnaryOperator<String> lineEdit,
      List<String> extraLines,
      String contentSha256Override) {

    static Spec synthetic(String suffix) {
      return new Spec(suffix, true, "SYNTHETIC", UnaryOperator.identity(), List.of(), null);
    }

    Spec nonSynthetic(String license) {
      return new Spec(suffix, false, license, lineEdit, extraLines, contentSha256Override);
    }

    Spec withExtraLines(List<String> lines) {
      return new Spec(suffix, synthetic, license, lineEdit, lines, contentSha256Override);
    }

    Spec withContentSha256(String sha) {
      return new Spec(suffix, synthetic, license, lineEdit, extraLines, sha);
    }
  }

  private TestCatalogs() {}

  static Path write(Path dir, Path mini, Spec spec) throws IOException {
    List<String> source = Files.readAllLines(mini, StandardCharsets.UTF_8);
    List<String> records = new ArrayList<>();
    for (String line : source.subList(1, source.size())) {
      records.add(spec.lineEdit().apply(rename(line, spec)));
    }
    for (String extra : spec.extraLines()) {
      records.add(rename(extra, spec));
    }
    int boards = 0;
    int routes = 0;
    List<String> canonical = new ArrayList<>();
    for (String record : records) {
      String c = Jcs.canonicalize(record);
      canonical.add(c);
      boards += c.contains("\"type\":\"board\"") ? 1 : 0;
      routes += c.contains("\"type\":\"route\"") ? 1 : 0;
    }
    String sha =
        spec.contentSha256Override() != null
            ? spec.contentSha256Override()
            : Jcs.sha256Hex(String.join("\n", canonical).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("type", "manifest");
    manifest.put("schema_version", 1);
    manifest.put("source", "test-" + spec.suffix());
    manifest.put("version", "v-" + spec.suffix());
    manifest.put("content_sha256", sha);
    manifest.put("extracted_at", "2026-10-15T00:00:00Z");
    manifest.put("synthetic", spec.synthetic());
    manifest.put("license_review_status", spec.license());
    manifest.put("record_counts", Map.of("boards", boards, "routes", routes));
    List<String> lines = new ArrayList<>();
    lines.add(CatalogRecords.MAPPER.writeValueAsString(manifest));
    lines.addAll(records);
    Path file = dir.resolve(spec.suffix() + ".jsonl");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return file;
  }

  static String boardId(String suffix) {
    return "synthetic-mini-" + suffix;
  }

  private static String rename(String line, Spec spec) {
    String renamed =
        line.replace("synthetic-mini-1", boardId(spec.suffix()))
            .replace("syn-mini-", "syn-" + spec.suffix() + "-");
    return spec.synthetic()
        ? renamed
        : renamed.replace("\"synthetic\": true", "\"synthetic\": false");
  }
}
