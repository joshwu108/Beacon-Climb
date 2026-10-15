package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.MeResponse;
import com.beaconclimb.api.identity.preferences.PreferencesService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getMe}: the authenticated user's profile. The user comes from the bearer token only, so
 * there is no id to enumerate; {@code preferences} is null until the user has put some (BC-0108).
 */
@RestController
public class MeController {

  private final ConsentService consents;
  private final PreferencesService preferences;

  public MeController(ConsentService consents, PreferencesService preferences) {
    this.consents = consents;
    this.preferences = preferences;
  }

  @GetMapping(path = "/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
  public MeResponse getMe(AuthenticatedUser user) {
    return new MeResponse(
        user.userId(),
        user.username(),
        consents.latest(user.userId()).orElse(null),
        preferences.find(user.userId()).orElse(null));
  }
}
