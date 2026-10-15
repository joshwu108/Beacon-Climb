package com.beaconclimb.api.features;

/** Who wrote a {@code user_feature_snapshots} row: this recompute or the BC-0305 materializer. */
public enum FeatureSource {
  SYNC,
  STREAM
}
