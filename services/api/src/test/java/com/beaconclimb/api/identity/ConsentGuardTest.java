package com.beaconclimb.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ConsentGuard refuses with 403 CONSENT_REQUIRED unless an active consent for the current version
 * exists.
 */
class ConsentGuardTest {

  private final ConsentRepository consents = mock(ConsentRepository.class);
  private final ConsentDocumentService documents = new ConsentDocumentService();
  private final ConsentGuard guard = new ConsentGuard(consents, documents);
  private final UUID userId = Uuid7.next();

  @Test
  void activeConsentForCurrentVersionPasses() {
    when(consents.hasActive(userId, ConsentDocumentService.CURRENT_VERSION)).thenReturn(true);

    assertThatCode(() -> guard.requireActiveConsent(userId)).doesNotThrowAnyException();
  }

  @Test
  void revokedOrMissingConsentIsForbiddenConsentRequired() {
    when(consents.hasActive(userId, ConsentDocumentService.CURRENT_VERSION)).thenReturn(false);

    assertThatThrownBy(() -> guard.requireActiveConsent(userId))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(ex.code()).isEqualTo(ErrorCode.CONSENT_REQUIRED);
              assertThat(ex.fieldErrors()).isEmpty();
            });
  }
}
