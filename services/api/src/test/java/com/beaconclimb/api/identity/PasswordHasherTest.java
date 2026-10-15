package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SEC-16 (TM-22): stored hashes carry exactly the configured argon2id parameters. */
class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher();

  @Test
  void storedHashCarriesConfiguredArgon2idParameters() {
    String hash = hasher.hash("correct-horse-battery");

    assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=1$");
    assertThat(hasher.matches("correct-horse-battery", hash)).isTrue();
    assertThat(hasher.matches("correct-horse-batterx", hash)).isFalse();
  }

  @Test
  void dummyHashUsesTheSameParametersAndMatchesNothingKnown() {
    assertThat(hasher.dummyHash()).startsWith(PasswordHasher.EXPECTED_PREFIX);
    assertThat(hasher.matches("", hasher.dummyHash())).isFalse();
    assertThat(hasher.matches("password", hasher.dummyHash())).isFalse();
  }
}
