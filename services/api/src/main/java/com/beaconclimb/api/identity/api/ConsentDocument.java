package com.beaconclimb.api.identity.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** {@code ConsentDocument}: the current consent text, its version and SHA-256, and the scopes. */
public record ConsentDocument(
    String version,
    @JsonProperty("text_sha256") String textSha256,
    String text,
    @JsonProperty("scopes_available") List<String> scopesAvailable) {}
