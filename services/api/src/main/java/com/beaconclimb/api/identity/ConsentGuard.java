package com.beaconclimb.api.identity;

import com.beaconclimb.api.platform.web.ApiException;
import com.beaconclimb.api.platform.web.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Gate for writes that require consent (TM-30, privacy policy section 4.3): attempt and
 * recommendation writes (BC-0109, BC-0111) call {@link #requireActiveConsent(UUID)} and get 403
 * CONSENT_REQUIRED when the user holds no unrevoked consent for the current document version. Not
 * wired to any endpoint in BC-0107.
 */
@Service
public class ConsentGuard {

  private final ConsentRepository consents;
  private final ConsentDocumentService documents;

  public ConsentGuard(ConsentRepository consents, ConsentDocumentService documents) {
    this.consents = consents;
    this.documents = documents;
  }

  public void requireActiveConsent(UUID userId) {
    if (!consents.hasActive(userId, documents.current().version())) {
      throw new ApiException(
          ErrorCode.CONSENT_REQUIRED,
          HttpStatus.FORBIDDEN,
          "Consent to the current consent document is required");
    }
  }
}
