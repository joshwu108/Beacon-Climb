package com.beaconclimb.api.attempts;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keyset cursor for {@code listAttempts}: the last row of a page as {@code base64url("<occurred_at
 * ISO instant>|<attempt_id>")} without padding, matching the {@code (occurred_at DESC, attempt_id
 * DESC)} order (contract A11). Decoding is strict: anything that does not round-trip byte for byte
 * is an invalid cursor (422 INVALID_CURSOR).
 */
public record AttemptCursor(Instant occurredAt, UUID attemptId) {

  private static final Pattern UUID_CANONICAL =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final int MAX_LENGTH = 128;

  public AttemptCursor {
    if (occurredAt == null || attemptId == null) {
      throw new IllegalArgumentException("occurredAt and attemptId are required");
    }
  }

  public String encode() {
    String raw = occurredAt + "|" + attemptId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Empty for any malformed, padded, non-canonical or unparseable cursor. */
  public static Optional<AttemptCursor> decode(String cursor) {
    if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
      return Optional.empty();
    }
    byte[] bytes;
    try {
      bytes = Base64.getUrlDecoder().decode(cursor);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    String raw = new String(bytes, StandardCharsets.UTF_8);
    int bar = raw.indexOf('|');
    if (bar < 0 || raw.indexOf('|', bar + 1) >= 0) {
      return Optional.empty();
    }
    String id = raw.substring(bar + 1);
    if (!UUID_CANONICAL.matcher(id).matches()) {
      return Optional.empty();
    }
    Instant occurredAt;
    try {
      occurredAt = Instant.parse(raw.substring(0, bar));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
    AttemptCursor decoded = new AttemptCursor(occurredAt, UUID.fromString(id));
    return decoded.encode().equals(cursor) ? Optional.of(decoded) : Optional.empty();
  }
}
