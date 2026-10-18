package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.AuthRequest;
import com.beaconclimb.api.identity.api.AuthResponse;
import com.beaconclimb.api.platform.web.ClientAddress;
import com.beaconclimb.api.platform.web.TrustedProxies;
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
  private final TrustedProxies trustedProxies;

  public AuthController(AuthService auth, TrustedProxies trustedProxies) {
    this.auth = auth;
    this.trustedProxies = trustedProxies;
  }

  @PostMapping(
      path = "/v1/auth/register",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse register(@RequestBody AuthRequest body, HttpServletRequest request) {
    // Same client address as the public write bucket and the login limiter (BC-0123 F3): the
    // per-day account-creation cap (BC-0123c, F5 residual) is keyed on it.
    return auth.register(body, ClientAddress.of(request, trustedProxies));
  }

  @PostMapping(
      path = "/v1/auth/session",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResponse createSession(@RequestBody AuthRequest body, HttpServletRequest request) {
    // Per-IP limiting uses the socket peer unless that peer is one of
    // beacon.http.trusted-proxies, in which case the first X-Forwarded-For entry is the client
    // (BC-0123 F3, ClientAddress): only safe behind a proxy that overwrites the header.
    return auth.login(body, ClientAddress.of(request, trustedProxies));
  }

  @DeleteMapping("/v1/auth/session")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSession(AuthenticatedUser user) {
    auth.logout(user);
  }
}
