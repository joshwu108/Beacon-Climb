package com.beaconclimb.api.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** X-Forwarded-For is only honoured when explicitly trusted, and then only its first entry. */
class ClientAddressTest {

  @Test
  void socketPeerByDefaultEvenWhenForwardedForIsPresent() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader(ClientAddress.FORWARDED_FOR, "203.0.113.9, 10.0.0.1");

    assertThat(ClientAddress.of(request, false)).isEqualTo("127.0.0.1");
  }

  @Test
  void firstForwardedEntryWhenTrusted() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader(ClientAddress.FORWARDED_FOR, " 203.0.113.9 , 10.0.0.1");

    assertThat(ClientAddress.of(request, true)).isEqualTo("203.0.113.9");
  }

  @Test
  void blankOrMissingForwardedFallsBackToThePeer() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    assertThat(ClientAddress.of(request, true)).isEqualTo("127.0.0.1");

    request.addHeader(ClientAddress.FORWARDED_FOR, " , 10.0.0.1");
    assertThat(ClientAddress.of(request, true)).isEqualTo("127.0.0.1");
  }
}
