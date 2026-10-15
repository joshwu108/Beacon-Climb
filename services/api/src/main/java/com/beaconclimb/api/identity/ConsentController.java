package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.ConsentDocument;
import com.beaconclimb.api.identity.api.ConsentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code getConsentDocument} (public), {@code createConsent} and {@code deleteConsent}. */
@RestController
public class ConsentController {

  private final ConsentDocumentService documents;
  private final ConsentService consents;

  public ConsentController(ConsentDocumentService documents, ConsentService consents) {
    this.documents = documents;
    this.consents = consents;
  }

  @GetMapping(path = "/v1/consent/current", produces = MediaType.APPLICATION_JSON_VALUE)
  public ConsentDocument current() {
    return documents.current();
  }

  @PostMapping(path = "/v1/me/consent", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void grant(AuthenticatedUser user, @RequestBody ConsentRequest body) {
    consents.grant(user.userId(), body);
  }

  @DeleteMapping("/v1/me/consent")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(AuthenticatedUser user) {
    consents.revoke(user.userId());
  }
}
