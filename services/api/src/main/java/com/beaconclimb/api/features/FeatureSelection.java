package com.beaconclimb.api.features;

import com.beaconclimb.api.features.FeatureSnapshotRepository.Both;
import java.util.Objects;
import java.util.Optional;

/**
 * The read rule of BC-0310 part 2 (stream design note "read path (B8)", ADR-0004,
 * climber-features.v1.md section 6): which {@code user_feature_snapshots} row a ranking reads.
 *
 * <ol>
 *   <li>{@code beacon.stream.enabled=false}: the SYNC row, reason {@link
 *       FeatureFallbackReason#STREAM_DISABLED}, whatever STREAM row may be left over. Disabled
 *       means the stream read path is off as a whole; a stale materialized row from an earlier
 *       {@code make stream-up} must not reappear in rankings once the broker is gone.
 *   <li>Otherwise the STREAM row when it exists and its {@code cutoff_w} is at least the SYNC row's
 *       (or there is no SYNC row): the fresher explicit cutoff wins, ties go to STREAM (equal
 *       cutoffs mean the same row set, and parity holds them to the same bytes).
 *   <li>Otherwise the SYNC row with {@link FeatureFallbackReason#NO_STREAM_ROW} or {@link
 *       FeatureFallbackReason#STREAM_OLDER_THAN_SYNC}.
 * </ol>
 *
 * Empty when neither row exists (the caller recomputes at {@code W = now}, which writes the SYNC
 * row, and the selection is then SYNC / NO_STREAM_ROW).
 *
 * @param snapshot the chosen row
 * @param fallbackReason null when {@code snapshot} is the STREAM row
 */
public record FeatureSelection(FeatureSnapshot snapshot, FeatureFallbackReason fallbackReason) {

  public FeatureSelection {
    Objects.requireNonNull(snapshot, "snapshot");
    if ((snapshot.source() == FeatureSource.STREAM) != (fallbackReason == null)) {
      throw new IllegalArgumentException(
          "a fallback reason is set exactly when the SYNC row was chosen");
    }
  }

  public static Optional<FeatureSelection> select(Both rows, boolean streamEnabled) {
    Optional<FeatureSnapshot> sync = rows.sync();
    Optional<FeatureSnapshot> stream = rows.stream();
    if (!streamEnabled) {
      return sync.map(row -> new FeatureSelection(row, FeatureFallbackReason.STREAM_DISABLED));
    }
    if (stream.isPresent()
        && (sync.isEmpty() || !stream.get().cutoffW().isBefore(sync.get().cutoffW()))) {
      return Optional.of(new FeatureSelection(stream.get(), null));
    }
    if (sync.isEmpty()) {
      return Optional.empty();
    }
    FeatureFallbackReason reason =
        stream.isEmpty()
            ? FeatureFallbackReason.NO_STREAM_ROW
            : FeatureFallbackReason.STREAM_OLDER_THAN_SYNC;
    return Optional.of(new FeatureSelection(sync.get(), reason));
  }

  /** The SYNC row a recompute just wrote because neither row existed. */
  public static FeatureSelection recomputed(FeatureSnapshot syncRow, boolean streamEnabled) {
    return new FeatureSelection(
        syncRow,
        streamEnabled
            ? FeatureFallbackReason.NO_STREAM_ROW
            : FeatureFallbackReason.STREAM_DISABLED);
  }

  public FeatureProvenance provenance() {
    return FeatureProvenance.of(snapshot);
  }
}
