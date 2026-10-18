package com.beaconclimb.features;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * An independent restatement of contracts/features/climber-features.v1.md written as plain loops
 * over the canonical rows, used as the oracle of {@link FeatureRecomputePropertyTest}. It shares
 * nothing with {@link FeatureComputer} except the JCS serialiser.
 */
final class ReferenceFeatures {

  record Row(
      UUID attemptId, int revision, String variant, Instant at, boolean sent, Double grade) {}

  record Expected(String aggregatesJson, String checksum, Instant featureAsOf) {}

  private static final long HOUR_MS = 3_600_000L;
  private static final long DAY_MS = 24 * HOUR_MS;

  private ReferenceFeatures() {}

  static Expected compute(List<Row> all, Instant w, JsonMapper json) {
    List<Row> rows = new ArrayList<>();
    for (Row row : all) {
      if (row.at().toEpochMilli() <= w.toEpochMilli()) {
        rows.add(row);
      }
    }
    rows.sort(
        Comparator.comparingLong((Row r) -> r.at().toEpochMilli())
            .thenComparing(Row::attemptId)
            .reversed());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("attempts_7d", countIn(rows, w, 7, false));
    out.put("attempts_30d", countIn(rows, w, 30, false));
    out.put("attempts_90d", countIn(rows, w, 90, false));
    out.put("sends_7d", countIn(rows, w, 7, true));
    out.put("sends_30d", countIn(rows, w, 30, true));
    out.put("sends_90d", countIn(rows, w, 90, true));
    out.put("send_rate_by_grade_band_90d", bands(rows, w));
    out.put("recent_variant_ids", recent(rows));
    Instant asOf = rows.isEmpty() ? null : rows.get(0).at();
    out.put("last_session_at", asOf == null ? null : DateTimeFormatter.ISO_INSTANT.format(asOf));
    out.put("max_sent_grade_numeric_90d", maxSent(rows, w));
    out.put("distinct_sessions_30d", sessions(rows, w));
    return new Expected(Jcs.canonicalize(json.writeValueAsString(out)), checksum(rows), asOf);
  }

  private static boolean inWindow(Row row, Instant w, int days) {
    long at = row.at().toEpochMilli();
    return at > w.toEpochMilli() - days * DAY_MS && at <= w.toEpochMilli();
  }

  private static int countIn(List<Row> rows, Instant w, int days, boolean sentOnly) {
    int n = 0;
    for (Row row : rows) {
      if (inWindow(row, w, days) && (!sentOnly || row.sent())) {
        n++;
      }
    }
    return n;
  }

  private static Map<String, Map<String, Integer>> bands(List<Row> rows, Instant w) {
    Map<String, Map<String, Integer>> bands = new TreeMap<>();
    for (Row row : rows) {
      if (!inWindow(row, w, 90)) {
        continue;
      }
      Map<String, Integer> counts =
          bands.computeIfAbsent(
              band(row.grade()), k -> new LinkedHashMap<>(Map.of("attempts", 0, "sends", 0)));
      counts.merge("attempts", 1, Integer::sum);
      if (row.sent()) {
        counts.merge("sends", 1, Integer::sum);
      }
    }
    return bands;
  }

  private static String band(Double grade) {
    if (grade == null) {
      return "UNGRADED";
    }
    if (grade < 14) {
      return "NOVICE";
    }
    if (grade < 18) {
      return "BEGINNER";
    }
    if (grade < 22) {
      return "INTERMEDIATE";
    }
    if (grade < 26) {
      return "ADVANCED";
    }
    return "EXPERT";
  }

  private static List<String> recent(List<Row> newestFirst) {
    List<String> ids = new ArrayList<>();
    for (Row row : newestFirst) {
      if (!ids.contains(row.variant())) {
        ids.add(row.variant());
      }
      if (ids.size() == 20) {
        break;
      }
    }
    return ids;
  }

  private static Double maxSent(List<Row> rows, Instant w) {
    Double max = null;
    for (Row row : rows) {
      if (inWindow(row, w, 90) && row.sent() && row.grade() != null) {
        max = max == null ? row.grade() : Math.max(max, row.grade());
      }
    }
    return max;
  }

  private static int sessions(List<Row> newestFirst, Instant w) {
    List<Row> ascending = new ArrayList<>();
    for (int i = newestFirst.size() - 1; i >= 0; i--) {
      if (inWindow(newestFirst.get(i), w, 30)) {
        ascending.add(newestFirst.get(i));
      }
    }
    int sessions = ascending.isEmpty() ? 0 : 1;
    for (int i = 1; i < ascending.size(); i++) {
      long gap = ascending.get(i).at().toEpochMilli() - ascending.get(i - 1).at().toEpochMilli();
      if (gap > 3 * HOUR_MS) {
        sessions++;
      }
    }
    return sessions;
  }

  private static String checksum(List<Row> rows) {
    List<String> pairs = new ArrayList<>();
    for (Row row : rows) {
      pairs.add(row.attemptId().toString() + ":" + row.revision());
    }
    pairs.sort(null);
    return Jcs.sha256Hex(String.join(",", pairs).getBytes(StandardCharsets.UTF_8));
  }
}
