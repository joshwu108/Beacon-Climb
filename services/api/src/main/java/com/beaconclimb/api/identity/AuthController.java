package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.AuthRequest;
import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ClientAddress;
import com.beaconclimb.api.platform.web.HttpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code registerUser}, {@code createSession} and {@code deleteSession} (contract 1.1.0). */
@RestController
public class AuthController {

  private final AuthService auth;
  private final boolean trustForwardedHeaders;

  public AuthController(AuthService auth, HttpProperties http) {
    this.auth = auth;
    this.trustForwardedHeaders = http.trustForwardedHeaders();
  }

  @PostMapping(
      path = "/v1/auth/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@RequestBody AuthRequest body) {
    return auth.register(body);
  }

  @PostMapping(
      path = "/v1/auth/session",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse createSession(@RequestBody AuthRequest body, HttpServletRequest request) {
    // Per-IP limiting uses the socket peer unless beacon.http.trust-forwarded-headers is on
    // (BC-0122, ClientAddress): only safe behind a proxy that overwrites X-Forwarded-For.
    return auth.login(body, ClientAddress.of(request, trustForwardedHeaders));
  }

  @DeleteMapping("/v1/auth/session")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSession(AuthenticatedUser user) {
    auth.logout(user);
  }
}
