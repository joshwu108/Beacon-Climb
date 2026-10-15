package com.beaconclimb.api.features;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code --recompute.*} options of the {@code recompute} profile.
 *
 * @param user one user id, or {@code all} for every non-erased account
 * @param w the explicit cutoff W as an RFC 3339 instant; the clock's now when absent
 */
@ConfigurationProperties(prefix = "recompute")
public record RecomputeProperties(@DefaultValue("all") String user, String w) {}
