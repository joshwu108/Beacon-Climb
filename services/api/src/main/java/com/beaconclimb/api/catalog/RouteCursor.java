package com.beaconclimb.api.catalog;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Keyset cursor for {@code listRoutes} (planning/06 section 5): the last row of a page as {@code
 * base64url("<grade_numeric or empty>|<variant_id>")} without padding. Pages are ordered by {@code
 * (grade_numeric ASC NULLS LAST, variant_id ASC)}, so an empty grade means the page ended inside
 * the ungraded tail. Decoding is strict: anything that does not round-trip is an invalid cursor.
 */
public record RouteCursor(BigDecimal gradeNumeric, UUID variantId) {

  private static final Pattern UUID_CANONICAL =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern GRADE = Pattern.compile("-?[0-9]{1,5}(\\.[0-9]{1,2})?");
  private static final int MAX_LENGTH = 128;

  public RouteCursor {
    if (variantId == null) {
      throw new IllegalArgumentException("variantId is required");
    }
  }

  public String encode() {
    String grade = gradeNumeric == null ? "" : gradeNumeric.stripTrailingZeros().toPlainString();
    String raw = grade + "|" + variantId;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Empty for any malformed, padded, non-UTF-8 or unparseable cursor. */
  public static Optional<RouteCursor> decode(String cursor) {
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
    String grade = raw.substring(0, bar);
    String id = raw.substring(bar + 1);
    if (!UUID_CANONICAL.matcher(id).matches()) {
      return Optional.empty();
    }
    if (!grade.isEmpty() && !GRADE.matcher(grade).matches()) {
      return Optional.empty();
    }
    BigDecimal parsed = grade.isEmpty() ? null : new BigDecimal(grade);
    RouteCursor decoded = new RouteCursor(parsed, UUID.fromString(id));
    return decoded.encode().equals(cursor) ? Optional.of(decoded) : Optional.empty();
  }
}
