package com.beaconclimb.api.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.UriUtils;

/**
 * The one request path every filter matches on (BC-0123 F2). Servlet filters see the raw request
 * URI while Spring MVC routes on the decoded path with {@code ;params} removed, so {@code
 * /v1;x/auth/register} or {@code /%761/me} reached a handler without passing the auth or rate-limit
 * filter. {@link #canonical} accepts a raw URI only when decoding cannot change its structure: no
 * path parameters, backslashes, empty or dot segments, and no percent-encoding of a character that
 * is unreserved (RFC 3986 section 2.3, e.g. {@code %76} for {@code v}) or structural ({@code / \ ;
 * % .}) or a control character. Encoded reserved characters that clients legitimately escape, such
 * as {@code %3A} in a route variant id, stay allowed and are decoded. {@code
 * PathNormalizationFilter} stores the result on the request; {@link #of} reads it.
 */
public final class RequestPaths {

  static final String ATTRIBUTE = RequestPaths.class.getName() + ".canonical";
  private static final Pattern ESCAPE = Pattern.compile("%([0-9A-Fa-f]{2})");
  private static final String FORBIDDEN_DECODED = "/\\;%.-_~";

  private RequestPaths() {}

  /** The canonical path stored by the normalization filter, else the raw URI (never null). */
  public static String of(HttpServletRequest request) {
    Object stored = request.getAttribute(ATTRIBUTE);
    if (stored != null) {
      return stored.toString();
    }
    return canonical(request.getRequestURI()).orElse(request.getRequestURI());
  }

  /** The decoded path when the raw URI is undisguised; empty when it must be refused. */
  public static Optional<String> canonical(String rawUri) {
    if (rawUri == null || !rawUri.startsWith("/") || hasRawDisguise(rawUri)) {
      return Optional.empty();
    }
    if (hasForbiddenEscape(rawUri)) {
      return Optional.empty();
    }
    String decoded;
    try {
      decoded = UriUtils.decode(rawUri, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (hasRawDisguise(decoded) || hasDotSegment(decoded)) {
      return Optional.empty();
    }
    return Optional.of(decoded);
  }

  private static boolean hasRawDisguise(String path) {
    if (path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) {
      return true;
    }
    for (int i = 0; i < path.length(); i++) {
      char c = path.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return true;
      }
    }
    return false;
  }

  /** A percent-escape of an unreserved, structural or control character, or {@code %} itself. */
  private static boolean hasForbiddenEscape(String rawUri) {
    Matcher matcher = ESCAPE.matcher(rawUri);
    while (matcher.find()) {
      int value = Integer.parseInt(matcher.group(1), 16);
      if (value >= 0x80) {
        continue;
      }
      char c = (char) value;
      boolean unreservedAlnum = Character.isLetterOrDigit(c);
      if (unreservedAlnum || FORBIDDEN_DECODED.indexOf(c) >= 0 || c < 0x20 || c == 0x7F) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasDotSegment(String path) {
    for (String segment : path.split("/", -1)) {
      if (segment.equals(".") || segment.equals("..")) {
        return true;
      }
    }
    return false;
  }
}
