package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.ConsentRepository.ConsentRecord;
import com.beaconclimb.api.identity.api.ConsentDocument;
import com.beaconclimb.api.identity.api.ConsentRequest;
import com.beaconclimb.api.identity.api.ConsentScopes;
import com.beaconclimb.api.identity.api.ConsentView;
import com.beaconclimb.api.platform.web.ApiException;
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
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grants, revokes and reads consent for the authenticated user. A grant must name the current
 * document version and text hash (422 otherwise); it upserts on {@code (user_id, consent_version)}
 * so a re-grant after revocation clears {@code revoked_at} (TM-09). Revocation is idempotent.
 */
@Service
public class ConsentService {

  private static final Logger log = LoggerFactory.getLogger(ConsentService.class);
  private static final Pattern SHA256_HEX = Pattern.compile("^[a-f0-9]{64}$");

  private final ConsentRepository consents;
  private final ConsentDocumentService documents;
  private final JsonMapper json;
  private final Clock clock;

  public ConsentService(
      ConsentRepository consents, ConsentDocumentService documents, JsonMapper json, Clock clock) {
    this.consents = consents;
    this.documents = documents;
    this.json = json;
    this.clock = clock;
  }

  public void grant(UUID userId, ConsentRequest request) {
    ConsentDocument current = documents.current();
    List<FieldError> errors = validate(request, current);
    if (!errors.isEmpty()) {
      throw ApiException.validationFailed(errors);
    }
    ConsentScopes scopes = new ConsentScopes(true, request.scopes().researchGranted());
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    consents.upsert(
        Uuid7.next(clock),
        userId,
        current.version(),
        json.writeValueAsString(scopes),
        current.textSha256(),
        now);
    log.info(
        "consent granted user_id={} version={} research={}",
        userId,
        current.version(),
        scopes.researchGranted());
  }

  public void revoke(UUID userId) {
    int revoked = consents.revokeActive(userId, clock.instant().truncatedTo(ChronoUnit.SECONDS));
    log.info("consent revoked user_id={} rows={}", userId, revoked);
  }

  public Optional<ConsentView> latest(UUID userId) {
    return consents.findLatest(userId).map(this::toView);
  }

  private ConsentView toView(ConsentRecord record) {
    ConsentScopes scopes =
        record.scopesJson() == null
            ? ConsentScopes.serviceOnly()
            : json.readValue(record.scopesJson(), ConsentScopes.class);
    return new ConsentView(
        record.version(),
        Timestamps.format(record.consentedAt()),
        Timestamps.format(record.revokedAt()),
        new ConsentScopes(true, scopes.researchGranted()),
        record.textSha256());
  }

  static List<FieldError> validate(ConsentRequest request, ConsentDocument current) {
    List<FieldError> errors = new ArrayList<>();
    if (request.consentVersion() == null) {
      errors.add(new FieldError("consent_version", "required", "is required"));
    } else if (!request.consentVersion().equals(current.version())) {
      errors.add(
          new FieldError(
              "consent_version", "const", "must equal the current version " + current.version()));
    }
    if (request.scopes() == null) {
      errors.add(new FieldError("scopes", "required", "is required"));
    } else if (request.scopes().service() == null) {
      errors.add(new FieldError("scopes.service", "required", "is required"));
    } else if (!request.scopes().service()) {
      errors.add(new FieldError("scopes.service", "const", "must be true"));
    }
    if (request.textSha256() == null) {
      errors.add(new FieldError("text_sha256", "required", "is required"));
    } else if (!SHA256_HEX.matcher(request.textSha256()).matches()) {
      errors.add(new FieldError("text_sha256", "pattern", "must match ^[a-f0-9]{64}$"));
    } else if (!request.textSha256().equals(current.textSha256())) {
      errors.add(new FieldError("text_sha256", "const", "does not match the current consent text"));
    }
    return List.copyOf(errors);
  }
}
