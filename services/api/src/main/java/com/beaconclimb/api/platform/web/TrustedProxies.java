package com.beaconclimb.api.platform.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The reverse-proxy addresses whose {@code X-Forwarded-For} may be believed ({@code
 * beacon.http.trusted-proxies}, BC-0123 F3): IPv4 or IPv6 literals, each optionally with a {@code
 * /prefix}. Hostnames are refused at startup so no DNS lookup ever decides trust. With the default
 * empty list every peer is untrusted and the socket address is the client.
 */
public final class TrustedProxies {

  public static final TrustedProxies NONE = new TrustedProxies(List.of());

  private static final Pattern LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

  private record Range(byte[] network, int prefixBits) {
    boolean contains(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int remaining = prefixBits % 8;
      if (remaining == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remaining);
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }

  private final List<Range> ranges;

  private TrustedProxies(List<Range> ranges) {
    this.ranges = List.copyOf(ranges);
  }

  /** Parses the configured entries; an entry that is not an IP literal or CIDR fails startup. */
  public static TrustedProxies parse(List<String> entries) {
    return new TrustedProxies(
        entries.stream().map(String::strip).map(TrustedProxies::range).toList());
  }

  public boolean isEmpty() {
    return ranges.isEmpty();
  }

  /** True when the socket peer is inside one of the configured ranges. */
  public boolean contains(String peerAddress) {
    if (ranges.isEmpty() || peerAddress == null) {
      return false;
    }
    byte[] address;
    try {
      address = literal(peerAddress).getAddress();
    } catch (IllegalArgumentException e) {
      return false;
    }
    return ranges.stream().anyMatch(range -> range.contains(address));
  }

  private static Range range(String entry) {
    int slash = entry.indexOf('/');
    String host = slash < 0 ? entry : entry.substring(0, slash);
    byte[] network = literal(host).getAddress();
    int bits = network.length * 8;
    int prefix = slash < 0 ? bits : parsePrefix(entry.substring(slash + 1), bits, entry);
    return new Range(network, prefix);
  }

  private static int parsePrefix(String text, int bits, String entry) {
    int prefix;
    try {
      prefix = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("beacon.http.trusted-proxies: bad prefix in " + entry);
    }
    if (prefix < 0 || prefix > bits) {
      throw new IllegalArgumentException("beacon.http.trusted-proxies: bad prefix in " + entry);
    }
    return prefix;
  }

  private static InetAddress literal(String text) {
    if (!LITERAL.matcher(text).matches()) {
      throw new IllegalArgumentException("beacon.http.trusted-proxies: not an IP literal");
    }
    try {
      return InetAddress.getByName(text);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("beacon.http.trusted-proxies: not an IP literal", e);
    }
  }
}
