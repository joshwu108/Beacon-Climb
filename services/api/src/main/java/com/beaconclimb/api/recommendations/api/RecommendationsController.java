package com.beaconclimb.api.recommendations.api;

import com.beaconclimb.api.identity.AuthenticatedUser;
import com.beaconclimb.api.recommendations.RecommendationRequest;
import com.beaconclimb.api.recommendations.RecommendationService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code createRecommendations} (BC-0111). The user comes from the bearer token only; the write
 * rate limiter (60 per minute per user) applies before this handler. Every call that reaches the
 * ranker writes a new decision (TM-09): retries are not de-duplicated, the ranking is.
 */
@RestController
public class RecommendationsController {

  private final RecommendationService recommendations;

  public RecommendationsController(RecommendationService recommendations) {
    this.recommendations = recommendations;
  }

  @PostMapping(
      path = "/v1/me/sessions/recommendations",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public RecommendationResponse create(
      AuthenticatedUser user, @RequestBody RecommendationRequest body) {
    return recommendations.recommend(user.userId(), body);
  }
}
