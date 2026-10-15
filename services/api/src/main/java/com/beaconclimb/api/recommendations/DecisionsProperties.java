package com.beaconclimb.api.recommendations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.decisions.*} (BC-0111): where the local snapshot store keeps its files ({@code
 * BEACON_DECISIONS_DIR}) and how many it may hold before a recommendation request is refused with
 * 503 SERVICE_UNAVAILABLE (TM-25).
 */
@ConfigurationProperties(prefix = "beacon.decisions")
public record DecisionsProperties(
    @DefaultValue("data/decisions") String dir, @DefaultValue("100000") long maxFiles) {}
