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
 * <p>This is the seam between the feature read and the response mapping: part 1 (BC-0310) fills it
 * from the single live row, which the attempts hook and the recompute write with source SYNC; part
 * 2 (after BC-0305) changes only where it is filled from, preferring the materialized STREAM row
 * when it is at least as fresh as the SYNC row.
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
