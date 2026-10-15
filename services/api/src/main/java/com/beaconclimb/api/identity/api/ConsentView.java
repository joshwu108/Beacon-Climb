package com.beaconclimb.api.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code Consent}: a granted consent; {@code revoked_at} is null while it is active. */
public record ConsentView(
    String version,
    @JsonProperty("consented_at") String consentedAt,
    @JsonProperty("revoked_at") String revokedAt,
    ConsentScopes scopes,
    @JsonProperty("text_sha256") String textSha256) {}
