package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beaconclimb.api.identity.api.AuthRequest;
import com.beaconclimb.api.platform.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BC-0123 F11: thirty concurrent wrong-password logins for one username run exactly ten Argon2
 * verifications (the ADR-0005 limit), because the failure slot is reserved before hashing; the
 * other twenty are 429 without touching the hasher.
 */
class AuthServiceLoginConcurrencyTest {

  private static final int PARALLEL = 30;
  private static final int LIMIT = 10;

  /** Counts verifications instead of running Argon2; every password is wrong. */
  private static final class CountingHasher extends PasswordHasher {
    final AtomicInteger verifications = new AtomicInteger();

    @Override
    public boolean matches(CharSequence rawPassword, String storedHash) {
      verifications.incrementAndGet();
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  @Test
  void thirtyConcurrentBadLoginsRunAtMostTenVerifications() throws Exception {
    CountingHasher hasher = new CountingHasher();
    UserRepository users = mock(UserRepository.class);
    when(users.findActiveByUsername(anyString())).thenReturn(Optional.empty());
    AuthProperties properties =
        new AuthProperties(
            LIMIT, Duration.ofMinutes(15), Duration.ofHours(24), 3, Duration.ofSeconds(2), 20);
    AuthService service =
        new AuthService(
            users,
            mock(SessionRepository.class),
            hasher,
            new LoginAttemptLimiter(LIMIT, Duration.ofMinutes(15), Clock.systemUTC()),
            mock(AccountCreationCap.class),
            mock(AccountCreationRepository.class),
            properties,
            mock(TransactionTemplate.class),
            Clock.systemUTC());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
    try {
      List<Future<HttpStatus>> futures = new ArrayList<>();
      for (int i = 0; i < PARALLEL; i++) {
        futures.add(pool.submit(badLogin(service, start)));
      }
      start.countDown();
      List<HttpStatus> statuses = new ArrayList<>();
      for (Future<HttpStatus> future : futures) {
        statuses.add(future.get());
      }

      assertThat(hasher.verifications.get()).isEqualTo(LIMIT);
      assertThat(statuses.stream().filter(HttpStatus.UNAUTHORIZED::equals).count())
          .isEqualTo(LIMIT);
      assertThat(statuses.stream().filter(HttpStatus.TOO_MANY_REQUESTS::equals).count())
          .isEqualTo(PARALLEL - LIMIT);
    } finally {
      pool.shutdownNow();
    }
  }

  private static Callable<HttpStatus> badLogin(AuthService service, CountDownLatch start) {
    return () -> {
      start.await();
      try {
        service.login(new AuthRequest("alice", "wrong-password"), "10.0.0.1");
        throw new AssertionError("login must not succeed");
      } catch (ApiException e) {
        return e.status();
      }
    };
  }
}
