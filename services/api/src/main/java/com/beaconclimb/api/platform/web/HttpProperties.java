package com.beaconclimb.api.platform.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.http.*} (BC-0122): request body limit (TM-25), write rate limits (ADR-0005), client
 * address trust and CORS origins for the web client.
 *
 * <ul>
 *   <li>{@code max-body-bytes}: largest accepted request body; above it 413 PAYLOAD_TOO_LARGE.
 *   <li>{@code write-limit-per-minute}: POST/PUT/PATCH/DELETE under /v1/ per authenticated user.
 *   <li>{@code public-write-limit-per-minute}: unauthenticated POSTs (register, session) per client
 *       IP, on top of the login failure limiter.
 *   <li>{@code max-tracked-clients}: size cap of the in-memory bucket map before idle buckets are
 *       evicted.
 *   <li>{@code trust-forwarded-headers}: when true the first {@code X-Forwarded-For} entry is the
 *       client address. Leave false unless a reverse proxy that overwrites that header (the compose
 *       nginx front, when it exists) is the only way to reach the port.
 *   <li>{@code cors-allowed-origins}: exact origins of the web client; no credentials.
 * </ul>
 */
@ConfigurationProperties(prefix = "beacon.http")
public record HttpProperties(
    @DefaultValue("65536") long maxBodyBytes,
    @DefaultValue("60") int writeLimitPerMinute,
    @DefaultValue("30") int publicWriteLimitPerMinute,
    @DefaultValue("10000") int maxTrackedClients,
    @DefaultValue("false") boolean trustForwardedHeaders,
    @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"})
        List<String> corsAllowedOrigins) {}
