package com.beaconclimb.api.features;

import java.time.Instant;
import java.util.Objects;

/**
 * Where the climber features behind one ranking came from (BC-0310, contract 1.2.0 {@code
 * features_source} / {@code features_cutoff_w}): the {@link FeatureSource} of the {@code
 * user_feature_snapshots} row that was read and that row's explicit cutoff {@code W}. {@code
 * feature_as_of} stays the maximum {@code occurred_at} the row included, so {@code cutoffW >=
 * feature_as_of} whenever both are known.
 *
 * <p>This is the seam between the feature read and the response mapping: it is filled from the row
 * {@link FeatureSelection} chose (BC-0310 part 2: the materialized STREAM row when the stream read
 * path is enabled and the row is at least as fresh as the SYNC row, else SYNC) and persisted on the
 * decision ledger (V11) so {@code getDecision} echoes it.
 */
public record FeatureProvenance(FeatureSource source, Instant cutoffW) {

  public FeatureProvenance {
    Objects.requireNonNull(source, "source");
  }

  /** The provenance of the snapshot row the ranking read. */
  public static FeatureProvenance of(FeatureSnapshot snapshot) {
    return new FeatureProvenance(snapshot.source(), snapshot.cutoffW());
  }
}
