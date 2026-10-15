package com.beaconclimb.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RouteVariantIdTest {

  @Test
  void splitsAtTheLastColonWithCanonicalAngles() {
    assertThat(RouteVariantId.parse("syn-route-0001:40"))
        .contains(new RouteVariantId("syn-route-0001", 40));
    assertThat(RouteVariantId.parse("abc:-15")).contains(new RouteVariantId("abc", -15));
    assertThat(RouteVariantId.parse("abc:0")).contains(new RouteVariantId("abc", 0));
    assertThat(RouteVariantId.format("syn-route-0001", 40)).isEqualTo("syn-route-0001:40");
  }

  @Test
  void rejectsNonCanonicalOrMalformedIds() {
    for (String id :
        new String[] {
          "nonsense",
          "syn-route-0001:040",
          "syn-route-0001:+40",
          "syn-route-0001:-0",
          "syn-route-0001:4000",
          "syn-route-0001:",
          ":40",
          "a:b:40",
          "a b:40",
          "x".repeat(65) + ":40",
          ""
        }) {
      assertThat(RouteVariantId.parse(id)).as(id).isEmpty();
    }
    assertThat(RouteVariantId.parse(null)).isEmpty();
  }
}
