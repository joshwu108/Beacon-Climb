package com.beaconclimb.api.features;

import com.beaconclimb.api.features.FeatureAggregates.BandCounts;
import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure computation of climber-features.v1 from the canonical attempt rows of one user
 * (contracts/features/climber-features.v1.md). Inputs are the current revision of every ACTIVE
 * attempt with {@code occurred_at <= W}; the caller applies that predicate in SQL and this class
 * re-sorts defensively, so the result depends only on the row set and {@code W}, never on arrival
 * order. A window of N days is {@code (W - N*24h, W]}: the lower edge exclusive, the upper
 * inclusive. Two consecutive attempts more than three hours apart start a new session.
 *
 * <p>Revisions (BC-0124 item 9): "one row per attempt at its current revision" is a precondition
 * the SQL caller satisfies but this class does not rely on. When several rows share an {@code
 * attempt_id} only the highest {@code revision} counts (equal revisions: the first seen), so an
 * identical duplicate or a stale lower revision that reaches the computer changes nothing.
 */
public final class FeatureComputer {

  /** The aggregates with the two provenance values stored beside them. */
  public record Result(FeatureAggregates aggregates, Instant featureAsOf, String checksum) {}

  static final Duration WINDOW_7D = Duration.ofDays(7);
  static final Duration WINDOW_30D = Duration.ofDays(30);
  static final Duration WINDOW_90D = Duration.ofDays(90);
  static final Duration SESSION_GAP = Duration.ofHours(3);

  /** Newest first, ties by attempt_id descending: the order recent_variant_ids is taken in. */
  static final Comparator<AttemptFeatureRow> NEWEST_FIRST =
      Comparator.comparing(AttemptFeatureRow::occurredAt)
          .thenComparing(AttemptFeatureRow::attemptId)
          .reversed();

  private FeatureComputer() {}

  public static Result compute(List<AttemptFeatureRow> rows, Instant cutoffW) {
    List<AttemptFeatureRow> included =
        latestRevisionPerAttempt(rows).stream()
            .filter(row -> !row.occurredAt().isAfter(cutoffW))
            .sorted(NEWEST_FIRST)
            .toList();
    List<AttemptFeatureRow> in7d = within(included, cutoffW, WINDOW_7D);
    List<AttemptFeatureRow> in30d = within(included, cutoffW, WINDOW_30D);
    List<AttemptFeatureRow> in90d = within(included, cutoffW, WINDOW_90D);
    Instant featureAsOf = included.isEmpty() ? null : included.get(0).occurredAt();
    FeatureAggregates aggregates =
        new FeatureAggregates(
            in7d.size(),
            in30d.size(),
            in90d.size(),
            sends(in7d),
            sends(in30d),
            sends(in90d),
            bandCounts(in90d),
            recentVariantIds(included),
            featureAsOf,
            maxSentGrade(in90d),
            sessions(in30d));
    return new Result(aggregates, featureAsOf, provenanceChecksum(included));
  }

  /** One row per attempt_id: the highest revision, the first seen among equal revisions. */
  static List<AttemptFeatureRow> latestRevisionPerAttempt(List<AttemptFeatureRow> rows) {
    Map<UUID, AttemptFeatureRow> latest = new LinkedHashMap<>();
    for (AttemptFeatureRow row : rows) {
      latest.merge(
          row.attemptId(), row, (kept, next) -> next.revision() > kept.revision() ? next : kept);
    }
    return List.copyOf(latest.values());
  }

  /** sha256 of the UTF-8 text {@code sorted("<attempt_id>:<revision>")} joined by commas. */
  public static String provenanceChecksum(List<AttemptFeatureRow> included) {
    List<String> pairs = new ArrayList<>(included.size());
    for (AttemptFeatureRow row : included) {
      pairs.add(row.attemptId() + ":" + row.revision());
    }
    pairs.sort(Comparator.naturalOrder());
    return Jcs.sha256Hex(String.join(",", pairs).getBytes(StandardCharsets.UTF_8));
  }

  private static List<AttemptFeatureRow> within(
      List<AttemptFeatureRow> newestFirst, Instant cutoffW, Duration window) {
    Instant exclusiveStart = cutoffW.minus(window);
    return newestFirst.stream().filter(row -> row.occurredAt().isAfter(exclusiveStart)).toList();
  }

  private static int sends(List<AttemptFeatureRow> rows) {
    return (int) rows.stream().filter(AttemptFeatureRow::sent).count();
  }

  private static SortedMap<String, BandCounts> bandCounts(List<AttemptFeatureRow> rows) {
    SortedMap<String, BandCounts> bands = new TreeMap<>();
    for (AttemptFeatureRow row : rows) {
      String key = GradeBandBuckets.keyFor(row.gradeNumeric());
      BandCounts current = bands.getOrDefault(key, new BandCounts(0, 0));
      bands.put(
          key, new BandCounts(current.attempts() + 1, current.sends() + (row.sent() ? 1 : 0)));
    }
    return bands;
  }

  private static List<String> recentVariantIds(List<AttemptFeatureRow> newestFirst) {
    Set<String> distinct = new LinkedHashSet<>();
    for (AttemptFeatureRow row : newestFirst) {
      if (distinct.size() == FeatureAggregates.RECENT_VARIANTS_LIMIT) {
        break;
      }
      distinct.add(row.routeVariantId());
    }
    return List.copyOf(distinct);
  }

  private static Double maxSentGrade(List<AttemptFeatureRow> rows) {
    return rows.stream()
        .filter(row -> row.sent() && row.gradeNumeric() != null)
        .map(AttemptFeatureRow::gradeNumeric)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }

  /** Oldest first; every gap strictly longer than {@link #SESSION_GAP} opens another session. */
  static int sessions(List<AttemptFeatureRow> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    List<AttemptFeatureRow> oldestFirst = rows.stream().sorted(NEWEST_FIRST.reversed()).toList();
    int sessions = 1;
    for (int i = 1; i < oldestFirst.size(); i++) {
      Duration gap =
          Duration.between(oldestFirst.get(i - 1).occurredAt(), oldestFirst.get(i).occurredAt());
      if (gap.compareTo(SESSION_GAP) > 0) {
        sessions++;
      }
    }
    return sessions;
  }
}
