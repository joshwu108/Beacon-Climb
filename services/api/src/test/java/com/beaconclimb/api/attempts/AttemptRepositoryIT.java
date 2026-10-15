package com.beaconclimb.api.attempts;

import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.Uuid7;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BC-0124 item 6: the idempotent insert of ADR-0002 is deterministic at the repository level. Two
 * inserts with the same {@code (user_id, submission_idempotency_key)} and different attempt ids are
 * one row: the second returns false without raising, and the first row is the one that stays.
 */
class AttemptRepositoryIT extends AttemptsTestSupport {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-14T13:00:00Z");

  @Autowired AttemptRepository attempts;

  @Test
  void secondInsertWithTheSameUserAndKeyReturnsFalseWithoutAnExceptionAndKeepsOneRow() {
    Account account = registerWithConsent();
    String route = visibleRouteId();
    UUID variantId = variantIdOf(route);
    String key = "k-repo-" + UUID.randomUUID();
    AttemptRow first = row(account.userId(), variantId, route, Uuid7.next());
    AttemptRow second = row(account.userId(), variantId, route, Uuid7.next());

    boolean firstInserted = attempts.insertIfAbsent(first, key, "0".repeat(64), "{}");
    boolean secondInserted = attempts.insertIfAbsent(second, key, "1".repeat(64), "{}");

    assertThat(firstInserted).isTrue();
    assertThat(secondInserted).isFalse();
    assertThat(count("SELECT count(*) FROM attempts WHERE user_id = :id", account.userId()))
        .isEqualTo(1);
    assertThat(attempts.findByKey(account.userId(), key))
        .map(AttemptRow::attemptId)
        .contains(first.attemptId());
    assertThat(attemptRows(second.attemptId())).isZero();

    Account other = registerWithConsent();
    assertThat(
            attempts.insertIfAbsent(
                row(other.userId(), variantId, route, Uuid7.next()), key, "0".repeat(64), "{}"))
        .isTrue();
  }

  private UUID variantIdOf(String publicId) {
    String[] parts = publicId.split(":");
    return jdbc.sql(
            "SELECT v.variant_id FROM route_variants v JOIN routes r ON r.route_id = v.route_id"
                + " WHERE r.provider_route_uuid = :uuid AND v.angle_deg = :angle")
        .param("uuid", parts[0])
        .param("angle", Integer.parseInt(parts[1]))
        .query(UUID.class)
        .single();
  }

  private static AttemptRow row(UUID userId, UUID variantId, String route, UUID attemptId) {
    return new AttemptRow(
        attemptId,
        userId,
        variantId,
        route,
        OCCURRED_AT,
        OCCURRED_AT.plusSeconds(1),
        1,
        true,
        null,
        null,
        null,
        1,
        AttemptStatus.ACTIVE,
        "0".repeat(64),
        "{}",
        null);
  }
}
