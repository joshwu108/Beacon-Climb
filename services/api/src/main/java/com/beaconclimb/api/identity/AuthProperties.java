package com.beaconclimb.api.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.auth.*}: login rate limit (ADR-0005: 10 failures per 15 minutes per username and
 * per client IP) and session TTL (24 h).
 */
@ConfigurationProperties(prefix = "beacon.auth")
public record AuthProperties(
    @DefaultValue("10") int loginLimit,
    @DefaultValue("15m") Duration loginWindow,
    @DefaultValue("24h") Duration sessionTtl) {}
