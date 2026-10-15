package com.beaconclimb.api.identity;

import com.beaconclimb.api.identity.api.AuthRequest;
import com.beaconclimb.api.identity.api.AuthResponse;
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

  public AuthController(AuthService auth) {
    this.auth = auth;
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
    // Per-IP limiting uses the socket peer; a reverse proxy must be configured for forwarded
    // headers at deployment time (BC-0122) before X-Forwarded-For can be trusted.
    return auth.login(body, request.getRemoteAddr());
  }

  @DeleteMapping("/v1/auth/session")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSession(AuthenticatedUser user) {
    auth.logout(user);
  }
}
