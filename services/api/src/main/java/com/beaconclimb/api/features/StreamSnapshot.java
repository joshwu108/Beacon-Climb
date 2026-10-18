package com.beaconclimb.api.features;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The STREAM row of {@code user_feature_snapshots} as the BC-0305 materializer writes it. Unlike
 * {@link FeatureSnapshot} the aggregates are kept as the canonical JSON text received in the
 * climber-features.v1 record's {@code aggregates} member, never rebuilt from a typed object: the
 * parity oracle (BC-0306) compares that text with {@code FeatureAggregates.toJson()} of the SYNC
 * recompute, so nothing on the api side may re-spell it before storage.
 *
 * @param userId the keyed user
 * @param cutoffW the record's {@code source_watermark} (the explicit cutoff W)
 * @param featureAsOf the record's {@code feature_as_of}
 * @param aggregatesJson the {@code aggregates} member text exactly as received
 * @param provenanceChecksum the record's {@code provenance_checksum}
 * @param sourcePartition the feature-topic partition the record was consumed from
 * @param sourceOffset the offset within that partition
 * @param computedAt wall-clock time of the write
 */
public record StreamSnapshot(
    UUID userId,
    Instant cutoffW,
    Instant featureAsOf,
    String aggregatesJson,
    String provenanceChecksum,
    int sourcePartition,
    long sourceOffset,
    Instant computedAt) {

  public StreamSnapshot {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(cutoffW, "cutoffW");
    Objects.requireNonNull(featureAsOf, "featureAsOf");
    Objects.requireNonNull(aggregatesJson, "aggregatesJson");
    Objects.requireNonNull(provenanceChecksum, "provenanceChecksum");
    Objects.requireNonNull(computedAt, "computedAt");
  }
}
