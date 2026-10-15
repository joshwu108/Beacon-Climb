package com.beaconclimb.api.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code ConsentRequest}: the client echoes the {@code version} and {@code text_sha256} it showed
 * from {@code GET /v1/consent/current} so the server can prove which text was accepted (C-07).
 */
public record ConsentRequest(
    @JsonProperty("consent_version") String consentVersion,
    ConsentScopes scopes,
    @JsonProperty("text_sha256") String textSha256) {}
