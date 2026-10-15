package com.beaconclimb.api.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.auth.*}: login rate limit (ADR-0005: 10 failures per 15 minutes per username and
 * per client IP), session TTL (24 h) and the Argon2 concurrency gate (BC-0123 F1): at most {@code
 * max-concurrent-hashes} password hashes run at once, each using 64 MiB of heap, and a request
 * waits at most {@code hash-wait} for a slot before it is refused with 503 SERVICE_UNAVAILABLE.
 */
@ConfigurationProperties(prefix = "beacon.auth")
public record AuthProperties(
    @DefaultValue("10") int loginLimit,
    @DefaultValue("15m") Duration loginWindow,
    @DefaultValue("24h") Duration sessionTtl,
    @DefaultValue("3") int maxConcurrentHashes,
    @DefaultValue("2s") Duration hashWait) {}
