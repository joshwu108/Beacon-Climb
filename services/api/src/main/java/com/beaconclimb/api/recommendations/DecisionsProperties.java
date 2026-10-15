package com.beaconclimb.api.recommendations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.decisions.*} (BC-0111, BC-0123 F5): where the local snapshot store keeps its files
 * ({@code BEACON_DECISIONS_DIR}), how many it may hold before a recommendation request is refused
 * with 503 SERVICE_UNAVAILABLE (TM-25), and how many new decisions one user may create per UTC day
 * before further requests are 429 RATE_LIMITED, so a single account cannot fill the store-wide cap
 * for everyone.
 */
@ConfigurationProperties(prefix = "beacon.decisions")
public record DecisionsProperties(
    @DefaultValue("data/decisions") String dir,
    @DefaultValue("100000") long maxFiles,
    @DefaultValue("500") int maxPerUserPerDay) {}
