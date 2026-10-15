package com.beaconclimb.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RouteCursorTest {

  private static final UUID ID = UUID.fromString("0192f0a1-5b2c-7d3e-8f4a-0b1c2d3e4f50");

  @Test
  void roundTripsGradedAndUngradedKeys() {
    RouteCursor graded = new RouteCursor(new BigDecimal("20.60"), ID);
    assertThat(decoded(graded.encode())).isEqualTo("20.6|" + ID);
    assertThat(RouteCursor.decode(graded.encode()))
        .contains(new RouteCursor(new BigDecimal("20.6"), ID));

    RouteCursor ungraded = new RouteCursor(null, ID);
    assertThat(decoded(ungraded.encode())).isEqualTo("|" + ID);
    assertThat(RouteCursor.decode(ungraded.encode())).contains(ungraded);
    assertThat(ungraded.encode()).doesNotContain("=");
  }

  @Test
  void rejectsEverythingThatDoesNotRoundTrip() {
    assertThat(RouteCursor.decode("not-base64!")).isEmpty();
    assertThat(RouteCursor.decode("")).isEmpty();
    assertThat(RouteCursor.decode(null)).isEmpty();
    assertThat(RouteCursor.decode(encode("20.6|nope"))).isEmpty();
    assertThat(RouteCursor.decode(encode("abc|" + ID))).isEmpty();
    assertThat(RouteCursor.decode(encode("20.6|" + ID + "|x"))).isEmpty();
    assertThat(RouteCursor.decode(encode(ID.toString()))).isEmpty();
    assertThat(RouteCursor.decode(encode("20.60|" + ID))).as("non-canonical grade").isEmpty();
    assertThat(RouteCursor.decode(encode("20.6|" + ID.toString().toUpperCase()))).isEmpty();
    String padded = Base64.getUrlEncoder().encodeToString(("20.6|" + ID).getBytes());
    assertThat(padded).endsWith("=");
    assertThat(RouteCursor.decode(padded)).as("padding is not canonical").isEmpty();
  }

  private static String encode(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static String decoded(String cursor) {
    return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
  }
}
