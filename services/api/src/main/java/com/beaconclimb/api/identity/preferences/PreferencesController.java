package com.beaconclimb.api.identity.preferences;

import com.beaconclimb.api.identity.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code putPreferences}: the authenticated user's onboarding preferences. The user comes from the
 * bearer token only. Consent is not required by the contract for this operation (401/413/422/429
 * only), so {@code ConsentGuard} is not applied here.
 */
@RestController
public class PreferencesController {

  private final PreferencesService preferences;

  public PreferencesController(PreferencesService preferences) {
    this.preferences = preferences;
  }

  @PutMapping(
      path = "/v1/me/preferences",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public PreferencesView put(AuthenticatedUser user, @RequestBody PreferencesRequest body) {
    return preferences.put(user.userId(), body);
  }
}
