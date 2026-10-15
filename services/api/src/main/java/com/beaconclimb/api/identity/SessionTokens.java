package com.beaconclimb.api.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Opaque bearer tokens (ADR-0005): 32 bytes from {@link SecureRandom}, base64url without padding
 * (43 characters). Only {@code sha256(token)} is ever stored or compared.
 */
public final class SessionTokens {

  static final int TOKEN_BYTES = 32;

  /** Shape of a well-formed token; anything else is rejected before touching the database. */
  public static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_-]{43}$");

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

  private SessionTokens() {}

  public static String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return BASE64URL.encodeToString(bytes);
  }

  public static String sha256Hex(String token) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
