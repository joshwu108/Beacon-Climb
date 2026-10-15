package com.beaconclimb.api.platform.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RFC 8785 JSON Canonicalization Scheme (ADR-0002). Parsing rejects duplicate keys, non-finite
 * numbers, trailing data and every non-JSON extension; serialisation sorts object members by UTF-16
 * code units ({@link String#compareTo}), formats numbers like ECMAScript {@code Number::toString}
 * and escapes strings exactly as {@code JSON.stringify}. Fixtures: contracts/jcs/fixtures.v1.json
 * (test ID BC-0109-JCS).
 */
public final class Jcs {

  /** Thrown for any input that is not a canonicalisable JSON text. */
  public static final class JcsException extends RuntimeException {
    public JcsException(String message, Throwable cause) {
      super(message, cause);
    }

    public JcsException(String message) {
      super(message);
    }
  }

  private static final JsonMapper STRICT =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final double EXPONENT_UPPER = 1e21;
  private static final int EXPONENT_LOWER_POSITION = -6;
  private static final int POSITIONAL_UPPER_POSITION = 21;

  private Jcs() {}

  /** Parses {@code json} strictly and returns a Jackson tree; never a missing node. */
  public static JsonNode parse(String json) {
    if (json == null || json.isBlank()) {
      throw new JcsException("no JSON value");
    }
    JsonNode node;
    try {
      node = STRICT.readTree(json);
    } catch (RuntimeException e) {
      throw new JcsException("not canonicalisable JSON: " + e.getMessage(), e);
    }
    if (node == null || node.isMissingNode()) {
      throw new JcsException("no JSON value");
    }
    return node;
  }

  public static String canonicalize(String json) {
    return canonicalize(parse(json));
  }

  public static String canonicalize(JsonNode node) {
    StringBuilder out = new StringBuilder();
    write(node, out);
    return out.toString();
  }

  public static byte[] canonicalBytes(String json) {
    return canonicalize(json).getBytes(StandardCharsets.UTF_8);
  }

  public static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256().digest(bytes));
  }

  /** SHA-256 hex of the canonical UTF-8 bytes of {@code json}. */
  public static String fingerprint(String json) {
    return sha256Hex(canonicalBytes(json));
  }

  public static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    switch (node.getNodeType()) {
      case OBJECT -> writeObject(node, out);
      case ARRAY -> writeArray(node, out);
      case STRING -> writeString(node.stringValue(), out);
      case NUMBER -> out.append(formatNumber(node.doubleValue()));
      case BOOLEAN -> out.append(node.booleanValue());
      case NULL -> out.append("null");
      default -> throw new JcsException("unsupported JSON node " + node.getNodeType());
    }
  }

  private static void writeObject(JsonNode node, StringBuilder out) {
    List<String> keys = new ArrayList<>();
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      keys.add(entry.getKey());
    }
    keys.sort(String::compareTo);
    out.append('{');
    for (int i = 0; i < keys.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      writeString(keys.get(i), out);
      out.append(':');
      write(node.get(keys.get(i)), out);
    }
    out.append('}');
  }

  private static void writeArray(JsonNode node, StringBuilder out) {
    out.append('[');
    for (int i = 0; i < node.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      write(node.get(i), out);
    }
    out.append(']');
  }

  /** The JSON.stringify escape set: control characters, quote and backslash only. */
  static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append("\\u00").append(HexFormat.of().toHexDigits((byte) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /** ECMAScript Number::toString (ECMA-262 6.1.6.1.20) over the shortest round-trip digits. */
  static String formatNumber(double value) {
    if (!Double.isFinite(value)) {
      throw new JcsException("number is not a finite binary64");
    }
    if (value == 0) {
      return "0";
    }
    if (Math.abs(value) < EXPONENT_UPPER && value == Math.rint(value)) {
      return formatIntegral(value);
    }
    ShortestDigits digits = ShortestDigits.of(Math.abs(value));
    String sign = value < 0 ? "-" : "";
    return sign + digits.toEcmaScript();
  }

  private static String formatIntegral(double value) {
    if (Math.abs(value) < 1e18) {
      return Long.toString((long) value);
    }
    ShortestDigits digits = ShortestDigits.of(Math.abs(value));
    return (value < 0 ? "-" : "") + digits.toEcmaScript();
  }

  /** Decimal digits {@code s} (k digits, no leading or trailing zeros) and the point position n. */
  record ShortestDigits(String digits, int pointPosition) {

    static ShortestDigits of(double positive) {
      String repr = Double.toString(positive);
      int e = repr.indexOf('E');
      String mantissa = e < 0 ? repr : repr.substring(0, e);
      int exponent = e < 0 ? 0 : Integer.parseInt(repr.substring(e + 1));
      int dot = mantissa.indexOf('.');
      String intPart = dot < 0 ? mantissa : mantissa.substring(0, dot);
      String frac = dot < 0 ? "" : mantissa.substring(dot + 1);
      String all = intPart + frac;
      int n = intPart.length() + exponent;
      int lead = 0;
      while (lead < all.length() - 1 && all.charAt(lead) == '0') {
        lead++;
      }
      String trimmed = all.substring(lead);
      int end = trimmed.length();
      while (end > 1 && trimmed.charAt(end - 1) == '0') {
        end--;
      }
      return new ShortestDigits(trimmed.substring(0, end), n - lead);
    }

    String toEcmaScript() {
      int k = digits.length();
      int n = pointPosition;
      if (k <= n && n <= POSITIONAL_UPPER_POSITION) {
        return digits + "0".repeat(n - k);
      }
      if (0 < n && n <= POSITIONAL_UPPER_POSITION) {
        return digits.substring(0, n) + "." + digits.substring(n);
      }
      if (EXPONENT_LOWER_POSITION < n && n <= 0) {
        return "0." + "0".repeat(-n) + digits;
      }
      int exponent = n - 1;
      String exponentText = (exponent < 0 ? "e-" : "e+") + Math.abs(exponent);
      if (k == 1) {
        return digits + exponentText;
      }
      return digits.charAt(0) + "." + digits.substring(1) + exponentText;
    }
  }
}
