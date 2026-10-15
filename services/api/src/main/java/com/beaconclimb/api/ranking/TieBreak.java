package com.beaconclimb.api.ranking;

import com.beaconclimb.api.platform.json.Jcs;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * ADR-0003 tie order: equal scores sort by ascending lowercase SHA-256 hex of the UTF-8 text {@code
 * policy_version|user_id|session_id|route_variant_id}.
 */
public final class TieBreak {

  private TieBreak() {}

  public static String key(
      String policyVersion, UUID userId, UUID sessionId, String routeVariantId) {
    String text = policyVersion + "|" + userId + "|" + sessionId + "|" + routeVariantId;
    return Jcs.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
  }
}
