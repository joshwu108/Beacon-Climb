package com.beaconclimb.api.identity;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing with the explicit ADR-0005 parameters: 16-byte salt, 32-byte hash,
 * parallelism 1, 64 MiB memory (65536 KiB), 3 iterations.
 *
 * <p>These meet or exceed the OWASP Password Storage Cheat Sheet minimum for Argon2id (m = 19 MiB,
 * t = 2, p = 1; checked for BC-0107 against the sheet current at contract 1.1.0): memory is 3.4x
 * the minimum and one extra iteration is used. Spring's {@code defaultsForSpringSecurity_v5_8()} is
 * deliberately not used (threat model TM-22). Tune only with a recorded local benchmark.
 *
 * <p>Every hash and verification passes through a {@link HashGate} (BC-0123 F1): at most {@code
 * beacon.auth.max-concurrent-hashes} run at once, a caller waits up to {@code
 * beacon.auth.hash-wait} for a slot, and is otherwise refused with 503 and {@code Retry-After}.
 *
 * <p>Passwords are never logged and never appear in exception messages; the encoder's output is the
 * only thing persisted ({@code users.password_hash}).
 */
@Component
public class PasswordHasher {

  static final int SALT_LENGTH_BYTES = 16;
  static final int HASH_LENGTH_BYTES = 32;
  static final int PARALLELISM = 1;
  static final int MEMORY_KIB = 65_536;
  static final int ITERATIONS = 3;
  static final int DEFAULT_MAX_CONCURRENT = 3;
  static final Duration DEFAULT_WAIT = Duration.ofSeconds(2);

  /** Prefix every stored hash must carry (SEC-16: stored parameters equal configured ones). */
  static final String EXPECTED_PREFIX =
      "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$";

  private final Argon2PasswordEncoder encoder =
      new Argon2PasswordEncoder(
          SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  private final HashGate gate;
  private final String dummyHash;

  @Autowired
  public PasswordHasher(AuthProperties properties) {
    this(properties.maxConcurrentHashes(), properties.hashWait());
  }

  /** Shipped gate defaults; for tests. */
  public PasswordHasher() {
    this(DEFAULT_MAX_CONCURRENT, DEFAULT_WAIT);
  }

  PasswordHasher(int maxConcurrentHashes, Duration hashWait) {
    this.gate = new HashGate(maxConcurrentHashes, hashWait);
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    this.dummyHash = encoder.encode(Base64.getUrlEncoder().withoutPadding().encodeToString(random));
  }

  public String hash(CharSequence rawPassword) {
    gate.acquire();
    try {
      return encoder.encode(rawPassword);
    } finally {
      gate.release();
    }
  }

  public boolean matches(CharSequence rawPassword, String storedHash) {
    gate.acquire();
    try {
      return encoder.matches(rawPassword, storedHash);
    } finally {
      gate.release();
    }
  }

  /**
   * Hash of a random, unknowable password. Login verifies against it when the username does not
   * exist so that unknown and known usernames cost the same (ADR-0005 constant-time behaviour).
   */
  public String dummyHash() {
    return dummyHash;
  }

  /** The most hashes that ever ran at once through this instance (test evidence for F1). */
  public int maxObservedConcurrency() {
    return gate.maxObservedConcurrency();
  }
}
