package com.beaconclimb.api.features;

import java.time.Instant;
import java.util.UUID;

/**
 * The slice of one canonical {@code attempts} row (its current revision) the recompute needs,
 * joined to the variant's current {@code grade_numeric} (null when ungraded). Notes and every other
 * owner-only member never reach this type (TM-10).
 */
public record AttemptFeatureRow(
    UUID attemptId,
    int revision,
    String routeVariantId,
    Instant occurredAt,
    boolean sent,
    Double gradeNumeric) {}
