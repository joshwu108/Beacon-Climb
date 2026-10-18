package com.beaconclimb.events;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Kafka headers carried next to a stream-dlq.v1 value (contracts/events/README.md "DLQ
 * headers"). The value holds what the schema requires; the headers add the source coordinates and
 * the consumer name so an operator can see where a record came from without decoding it. On a
 * redriven record the same headers travel back to the original topic with {@link #ATTEMPT}
 * incremented and {@link #REDRIVEN_FROM} added, which is how the next failure knows its attempt
 * number.
 */
public final class DlqHeaders {

  public static final String PREFIX = "dlq.";
  public static final String ORIGINAL_TOPIC = PREFIX + "original_topic";
  public static final String ORIGINAL_PARTITION = PREFIX + "original_partition";
  public static final String ORIGINAL_OFFSET = PREFIX + "original_offset";
  public static final String ATTEMPT = PREFIX + "attempt";
  public static final String ERROR_CODE = PREFIX + "error_code";
  public static final String CONSUMER = PREFIX + "consumer";

  /** Only on a redriven record: the {@code event_id} of the DLQ record it was republished from. */
  public static final String REDRIVEN_FROM = PREFIX + "redriven_from";

  public static final int FIRST_ATTEMPT = 1;

  private DlqHeaders() {}

  /**
   * The attempt number a failing record is on: its {@link #ATTEMPT} header when it carries one (a
   * redrive sets it to the previous attempt plus one), otherwise {@link #FIRST_ATTEMPT}. An
   * unparseable or sub-1 header counts as the first attempt rather than failing the DLQ write.
   */
  public static int attemptOf(Map<String, String> headers) {
    String raw = headers == null ? null : headers.get(ATTEMPT);
    if (raw == null) {
      return FIRST_ATTEMPT;
    }
    try {
      int attempt = Integer.parseInt(raw.trim());
      return attempt >= FIRST_ATTEMPT ? attempt : FIRST_ATTEMPT;
    } catch (NumberFormatException e) {
      return FIRST_ATTEMPT;
    }
  }

  /** A copy of {@code headers} without any {@code dlq.*} entry, insertion order kept. */
  public static Map<String, String> withoutDlq(Map<String, String> headers) {
    Map<String, String> copy = new LinkedHashMap<>();
    if (headers != null) {
      headers.forEach(
          (name, value) -> {
            if (!name.startsWith(PREFIX)) {
              copy.put(name, value);
            }
          });
    }
    return copy;
  }
}
