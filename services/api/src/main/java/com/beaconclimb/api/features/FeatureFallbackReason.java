package com.beaconclimb.api.features;

/**
 * Why a ranking read the SYNC row instead of the materialized STREAM row (BC-0310 part 2). Stored
 * on the decision ledger ({@code recommendation_decisions.feature_fallback_reason}, V11); null when
 * the STREAM row was served.
 */
public enum FeatureFallbackReason {
  /** No STREAM row exists for the user (the stream has not materialized one yet, or ever). */
  NO_STREAM_ROW,
  /** A STREAM row exists but its {@code cutoff_w} is behind the SYNC row's. */
  STREAM_OLDER_THAN_SYNC,
  /** {@code beacon.stream.enabled=false}: the stream read path is off, whatever rows exist. */
  STREAM_DISABLED
}
