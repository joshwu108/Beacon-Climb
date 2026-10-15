package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * BC-0123 F3: X-Forwarded-For is honoured only when the socket peer is a configured trusted proxy
 * (exact address or CIDR, IPv4 and IPv6), and then only its first entry.
 */
class ClientAddressTest {

  @Test
  void socketPeerByDefaultEvenWhenForwardedForIsPresent() {
    MockHttpServletRequest request = request("127.0.0.1", "203.0.113.9, 10.0.0.1");

    assertThat(ClientAddress.of(request, TrustedProxies.NONE)).isEqualTo("127.0.0.1");
    assertThat(ClientAddress.of(request, TrustedProxies.parse(List.of()))).isEqualTo("127.0.0.1");
  }

  @Test
  void firstForwardedEntryWhenThePeerIsATrustedAddress() {
    TrustedProxies trusted = TrustedProxies.parse(List.of("172.18.0.3"));

    assertThat(ClientAddress.of(request("172.18.0.3", " 203.0.113.9 , 10.0.0.1"), trusted))
        .isEqualTo("203.0.113.9");
    assertThat(ClientAddress.of(request("172.18.0.4", "203.0.113.9"), trusted))
        .isEqualTo("172.18.0.4");
  }

  @Test
  void cidrRangesMatchIpv4AndIpv6Peers() {
    TrustedProxies trusted =
        TrustedProxies.parse(List.of("10.0.0.0/8", "fd00::/8", "192.0.2.8/30"));

    assertThat(trusted.contains("10.255.1.2")).isTrue();
    assertThat(trusted.contains("11.0.0.1")).isFalse();
    assertThat(trusted.contains("fd12:3456::1")).isTrue();
    assertThat(trusted.contains("fe80::1")).isFalse();
    assertThat(trusted.contains("192.0.2.11")).isTrue();
    assertThat(trusted.contains("192.0.2.12")).isFalse();
    assertThat(trusted.contains("not-an-ip")).isFalse();
    assertThat(trusted.contains(null)).isFalse();
    assertThat(ClientAddress.of(request("10.1.2.3", "198.51.100.7"), trusted))
        .isEqualTo("198.51.100.7");
  }

  @Test
  void blankOrMissingForwardedFallsBackToTheTrustedPeer() {
    TrustedProxies trusted = TrustedProxies.parse(List.of("127.0.0.1"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    assertThat(ClientAddress.of(request, trusted)).isEqualTo("127.0.0.1");

    request.addHeader(ClientAddress.FORWARDED_FOR, " , 10.0.0.1");
    assertThat(ClientAddress.of(request, trusted)).isEqualTo("127.0.0.1");
  }

  @Test
  void hostnamesAndBadPrefixesAreRefusedAtStartup() {
    assertThatThrownBy(() -> TrustedProxies.parse(List.of("proxy.internal")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TrustedProxies.parse(List.of("10.0.0.0/33")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TrustedProxies.parse(List.of("10.0.0.0/x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MockHttpServletRequest request(String peer, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(peer);
    request.addHeader(ClientAddress.FORWARDED_FOR, forwardedFor);
    return request;
  }
}
