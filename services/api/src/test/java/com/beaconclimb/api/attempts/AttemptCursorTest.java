package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The listAttempts cursor round-trips exactly and rejects every non-canonical encoding. */
class AttemptCursorTest {

  private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-000000000002");

  @Test
  void roundTripsMillisecondInstantAndUuid() {
    AttemptCursor cursor = new AttemptCursor(Instant.parse("2026-10-14T14:00:00.123Z"), ID);

    assertThat(AttemptCursor.decode(cursor.encode())).contains(cursor);
    assertThat(cursor.encode()).doesNotContain("=");
  }

  @Test
  void rejectsPaddedNonCanonicalOrMalformedCursors() {
    String raw = "2026-10-14T14:00:00Z|" + ID;
    String padded =
        Base64.getUrlEncoder().encodeToString((raw + "x").getBytes(StandardCharsets.UTF_8));
    String nonCanonicalInstant =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(("2026-10-14T14:00:00.000Z|" + ID).getBytes(StandardCharsets.UTF_8));
    String upperCaseId =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((raw.toUpperCase()).getBytes(StandardCharsets.UTF_8));

    assertThat(AttemptCursor.decode(null)).isEmpty();
    assertThat(AttemptCursor.decode("")).isEmpty();
    assertThat(AttemptCursor.decode("!!!")).isEmpty();
    assertThat(AttemptCursor.decode(padded)).isEmpty();
    assertThat(AttemptCursor.decode(nonCanonicalInstant)).isEmpty();
    assertThat(AttemptCursor.decode(upperCaseId)).isEmpty();
    assertThat(AttemptCursor.decode("a".repeat(200))).isEmpty();
  }
}
