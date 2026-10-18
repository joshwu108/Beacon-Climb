package com.beaconclimb.api.features;

import com.beaconclimb.features.FeatureAggregates;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code user_feature_snapshots} row (ADR-0004): {@code cutoffW} is the explicit cutoff the
 * recompute was asked for, {@code featureAsOf} the max {@code occurred_at} actually included (null
 * when no attempt qualified). {@code sourcePartition} and {@code sourceOffset} are null for SYNC.
 */
public record FeatureSnapshot(
    UUID userId,
    int featureSchemaVersion,
    Instant cutoffW,
    Instant featureAsOf,
    FeatureAggregates aggregates,
    String provenanceChecksum,
    FeatureSource source,
    Integer sourcePartition,
    Long sourceOffset,
    Instant computedAt) {}
