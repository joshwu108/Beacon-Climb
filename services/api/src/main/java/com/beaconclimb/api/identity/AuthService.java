package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.UserRepository.Credentials;
import com.beaconclimb.api.identity.api.AuthRequest;
import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import com.beaconclimb.api.platform.web.FieldError;
import com.beaconclimb.api.platform.web.Timestamps;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and logout (ADR-0005). Logs carry {@code user_id} and the outcome only: never
 * a username, password, token or request body (TM-16).
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,32}$");
  static final int PASSWORD_MIN = 8;
  static final int PASSWORD_MAX = 128;

  private final UserRepository users;
  private final SessionRepository sessions;
  private final PasswordHasher hasher;
  private final LoginAttemptLimiter limiter;
  private final AuthProperties properties;
  private final Clock clock;

  public AuthService(
      UserRepository users,
      SessionRepository sessions,
      PasswordHasher hasher,
      LoginAttemptLimiter limiter,
      AuthProperties properties,
      Clock clock) {
    this.users = users;
    this.sessions = sessions;
    this.hasher = hasher;
    this.limiter = limiter;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public AuthResponse register(AuthRequest request) {
    requireValid(request);
    UUID userId = Uuid7.next(clock);
    String hash = hasher.hash(request.password());
    try {
      users.insert(userId, request.username(), hash);
    } catch (DuplicateKeyException e) {
      log.info("registration outcome=USERNAME_TAKEN");
      throw new ApiException(
          ErrorCode.USERNAME_TAKEN,
          HttpStatus.CONFLICT,
          "Username is already registered",
          List.of(new FieldError("username", "unique", "already registered")));
    }
    log.info("registration outcome=OK user_id={}", userId);
    return openSession(userId);
  }

  /**
   * Verifies the password against the stored hash, or against a dummy hash when the username is
   * unknown, so both failures cost one Argon2 verification. The limiter runs first so a blocked
   * caller cannot burn hashing CPU (TM-22).
   */
  public AuthResponse login(AuthRequest request, String clientIp) {
    requireValid(request);
    limiter
        .retryAfter(request.username(), clientIp)
        .ifPresent(
            wait -> {
              log.info("login outcome=RATE_LIMITED");
              throw ApiException.rateLimited(wait.toSeconds());
            });
    Optional<Credentials> user = users.findActiveByUsername(request.username());
    String hash = user.map(Credentials::passwordHash).orElse(hasher.dummyHash());
    boolean matches = hasher.matches(request.password(), hash);
    if (user.isEmpty() || !matches) {
      limiter.recordFailure(request.username(), clientIp);
      log.info("login outcome=INVALID_CREDENTIALS");
      throw new ApiException(
          ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }
    log.info("login outcome=OK user_id={}", user.get().id());
    return openSession(user.get().id());
  }

  public void logout(AuthenticatedUser user) {
    sessions.revoke(user.tokenHash(), clock.instant());
    log.info("logout outcome=OK user_id={}", user.userId());
  }

  private AuthResponse openSession(UUID userId) {
    String token = SessionTokens.generate();
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = now.plus(properties.sessionTtl());
    sessions.insert(SessionTokens.sha256Hex(token), userId, now, expiresAt);
    return new AuthResponse(token, Timestamps.format(expiresAt));
  }

  private static void requireValid(AuthRequest request) {
    List<FieldError> errors = validate(request);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
  }

  static List<FieldError> validate(AuthRequest request) {
    List<FieldError> errors = new ArrayList<>();
    if (request.username() == null) {
      errors.add(new FieldError("username", "required", "is required"));
    } else if (!USERNAME.matcher(request.username()).matches()) {
      errors.add(new FieldError("username", "pattern", "must match " + USERNAME.pattern()));
    }
    if (request.password() == null) {
      errors.add(new FieldError("password", "required", "is required"));
    } else if (request.password().length() < PASSWORD_MIN) {
      errors.add(
          new FieldError(
              "password", "minLength", "must be at least " + PASSWORD_MIN + " characters"));
    } else if (request.password().length() > PASSWORD_MAX) {
      errors.add(
          new FieldError(
              "password", "maxLength", "must be at most " + PASSWORD_MAX + " characters"));
    }
    return List.copyOf(errors);
  }
}
