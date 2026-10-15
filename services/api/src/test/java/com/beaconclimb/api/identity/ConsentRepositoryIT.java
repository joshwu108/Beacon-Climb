package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The SQL predicates of ConsentRepository scope every read and write to the given user_id. */
class ConsentRepositoryIT extends IdentityTestSupport {

  @Autowired UserRepository users;
  @Autowired ConsentRepository consents;

  @Test
  void consentOfUserBIsInvisibleAndUntouchableThroughUserA() {
    UUID a = Uuid7.next();
    UUID b = Uuid7.next();
    users.insert(a, uniqueUsername(), "argon2id$placeholder");
    users.insert(b, uniqueUsername(), "argon2id$placeholder");
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    consents.upsert(
        Uuid7.next(), b, "v1", "{\"service\": true, \"research\": false}", "a".repeat(64), now);

    assertThat(consents.findLatest(a)).isEmpty();
    assertThat(consents.hasActive(a, "v1")).isFalse();
    assertThat(consents.findLatest(b))
        .hasValueSatisfying(record -> assertThat(record.isActive()).isTrue());
    assertThat(consents.hasActive(b, "v1")).isTrue();

    assertThat(consents.revokeActive(a, now)).isZero();
    assertThat(consents.hasActive(b, "v1")).isTrue();

    assertThat(consents.revokeActive(b, now)).isEqualTo(1);
    assertThat(consents.hasActive(b, "v1")).isFalse();
    assertThat(consents.findLatest(b))
        .hasValueSatisfying(record -> assertThat(record.revokedAt()).isEqualTo(now));
  }

  @Test
  void regrantOfSameVersionClearsRevocationInsteadOfDuplicating() {
    UUID user = Uuid7.next();
    users.insert(user, uniqueUsername(), "argon2id$placeholder");
    Instant first = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
    Instant second = first.plusSeconds(30);
    consents.upsert(
        Uuid7.next(),
        user,
        "v1",
        "{\"service\": true, \"research\": false}",
        "a".repeat(64),
        first);
    consents.revokeActive(user, first.plusSeconds(10));

    consents.upsert(
        Uuid7.next(),
        user,
        "v1",
        "{\"service\": true, \"research\": true}",
        "a".repeat(64),
        second);

    Integer rows =
        jdbc.sql("SELECT count(*) FROM consents WHERE user_id = :id")
            .param("id", user)
            .query(Integer.class)
            .single();
    assertThat(rows).isEqualTo(1);
    assertThat(consents.findLatest(user))
        .hasValueSatisfying(
            record -> {
              assertThat(record.revokedAt()).isNull();
              assertThat(record.consentedAt()).isEqualTo(second);
              assertThat(record.scopesJson()).contains("\"research\": true");
            });
  }
}
