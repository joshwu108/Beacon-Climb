package com.beaconclimb.api.catalog.importer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rejected-record report: one JSON object per rejected line, {@code {line, reason, record_type,
 * id}}, written to {@code <reportDir>/<UTC timestamp>.jsonl}. The file is created on the first
 * rejection only, so a clean import leaves no report behind. Never contains row contents.
 */
final class RejectionReport implements AutoCloseable {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  private final Path file;
  private BufferedWriter writer;
  private int count;

  RejectionReport(Path reportDir, Clock clock) {
    this.file = reportDir.resolve(STAMP.format(clock.instant()) + ".jsonl");
  }

  void reject(int line, String reason, String recordType, String id) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("line", line);
    entry.put("reason", reason);
    entry.put("record_type", recordType);
    entry.put("id", id);
    try {
      writer().write(CatalogRecords.MAPPER.writeValueAsString(entry));
      writer.write('\n');
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write rejection report " + file, e);
    }
    count++;
  }

  int count() {
    return count;
  }

  /** The report path, or null when nothing was rejected (no file was created). */
  Path fileIfWritten() {
    return count == 0 ? null : file;
  }

  private BufferedWriter writer() throws IOException {
    if (writer == null) {
      Files.createDirectories(file.getParent());
      writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }
    return writer;
  }

  @Override
  public void close() throws IOException {
    if (writer != null) {
      writer.close();
    }
  }
}
