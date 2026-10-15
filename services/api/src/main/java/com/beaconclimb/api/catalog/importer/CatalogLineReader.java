package com.beaconclimb.api.catalog.importer;

import com.beaconclimb.api.catalog.importer.ImportRefusedException.Reason;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams a JSON Lines file one line at a time (LF only, per contracts/catalog/README.md). A CR or
 * a blank line is not a catalog v1 file and refuses the import; nothing is buffered beyond the
 * current line.
 */
final class CatalogLineReader implements AutoCloseable {

  /** One physical line with its 1-based number. */
  record Line(int number, String text) {}

  private final BufferedReader reader;
  private final StringBuilder buffer = new StringBuilder();
  private int number;

  CatalogLineReader(Path file) throws IOException {
    this.reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
  }

  /** The next line, or null at end of file (an optional trailing LF yields no extra line). */
  Line next() throws IOException {
    buffer.setLength(0);
    int c = reader.read();
    if (c == -1) {
      return null;
    }
    while (c != -1 && c != '\n') {
      buffer.append((char) c);
      c = reader.read();
    }
    number++;
    String text = buffer.toString();
    if (!text.isEmpty() && text.charAt(text.length() - 1) == '\r') {
      throw new ImportRefusedException(
          Reason.FILE_INVALID, "CRLF_NOT_ALLOWED line " + number + " ends with CR");
    }
    if (text.isBlank()) {
      throw new ImportRefusedException(Reason.FILE_INVALID, "BLANK_LINE line " + number);
    }
    return new Line(number, text);
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
