package com.beaconclimb.api.platform.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code beacon.http.*} (BC-0122, BC-0123): request body limit (TM-25), write rate limits
 * (ADR-0005), client address trust and CORS origins for the web client.
 *
 * <ul>
 *   <li>{@code max-body-bytes}: largest accepted request body; above it 413 PAYLOAD_TOO_LARGE.
 *   <li>{@code write-limit-per-minute}: POST/PUT/PATCH/DELETE under /v1/ per authenticated user.
 *   <li>{@code public-write-limit-per-minute}: refill rate of the per-client-address bucket for the
 *       unauthenticated POSTs (register, session), on top of the login failure limiter.
 *   <li>{@code public-burst}: capacity of that bucket, i.e. how many public POSTs one address may
 *       send at once before it must wait for the refill (F1: each costs an Argon2 hash).
 *   <li>{@code max-tracked-clients}: size cap of the in-memory bucket map before idle buckets are
 *       evicted.
 *   <li>{@code trusted-proxies}: IP literals or CIDR ranges of reverse proxies whose {@code
 *       X-Forwarded-For} is believed ({@link TrustedProxies}); empty by default, so the socket peer
 *       is the client address everywhere (F3).
 *   <li>{@code cors-allowed-origins}: exact origins of the web client; no credentials.
 * </ul>
 */
@ConfigurationProperties(prefix = "beacon.http")
public record HttpProperties(
    @DefaultValue("65536") long maxBodyBytes,
    @DefaultValue("60") int writeLimitPerMinute,
    @DefaultValue("30") int publicWriteLimitPerMinute,
    @DefaultValue("5") int publicBurst,
    @DefaultValue("10000") int maxTrackedClients,
    @DefaultValue List<String> trustedProxies,
    @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"})
        List<String> corsAllowedOrigins) {}
