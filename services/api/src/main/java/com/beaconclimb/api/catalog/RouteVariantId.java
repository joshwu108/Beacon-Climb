package com.beaconclimb.api.catalog;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Public route variant id {@code <provider_route_uuid>:<angle_deg>} (ADR-0002, contract pattern
 * {@code ^[^:\s]{1,64}:-?[0-9]{1,3}$}). Split at the last colon; the angle must be the canonical
 * decimal form (no leading zeros, no plus sign, no {@code -0}) so each variant has exactly one
 * public id. Anything else is simply "not found" to the client.
 */
public record RouteVariantId(String providerRouteUuid, int angleDeg) {

  private static final Pattern PREFIX = Pattern.compile("[^:\\s]{1,64}");
  private static final Pattern ANGLE = Pattern.compile("-?[0-9]{1,3}");
  private static final int MAX_LENGTH = 80;

  public static Optional<RouteVariantId> parse(String publicId) {
    if (publicId == null || publicId.length() > MAX_LENGTH) {
      return Optional.empty();
    }
    int colon = publicId.lastIndexOf(':');
    if (colon < 0) {
      return Optional.empty();
    }
    String prefix = publicId.substring(0, colon);
    String angle = publicId.substring(colon + 1);
    if (!PREFIX.matcher(prefix).matches() || !ANGLE.matcher(angle).matches()) {
      return Optional.empty();
    }
    int value = Integer.parseInt(angle);
    if (!Integer.toString(value).equals(angle)) {
      return Optional.empty();
    }
    return Optional.of(new RouteVariantId(prefix, value));
  }

  public static String format(String providerRouteUuid, int angleDeg) {
    return providerRouteUuid + ":" + angleDeg;
  }
}
