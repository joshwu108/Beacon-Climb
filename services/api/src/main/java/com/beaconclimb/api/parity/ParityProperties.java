package com.beaconclimb.api.parity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code --parity.*} options of the {@code parity} profile (BC-0306, {@code make test-parity}).
 *
 * @param user one user id, or {@code all} for every non-erased account
 * @param out path of the JSON report; {@code docs/evidence/BC-0306/parity-<timestamp>.json} when
 *     absent
 * @param commit the git commit the oracle ran against, recorded verbatim in the report ({@code
 *     scripts/parity/parity.sh} passes {@code git rev-parse HEAD}); {@code unknown} when absent
 */
@ConfigurationProperties(prefix = "parity")
public record ParityProperties(
    @DefaultValue("all") String user, String out, @DefaultValue("unknown") String commit) {}
