package com.beaconclimb.api.recommendations.decisions;

import com.beaconclimb.api.identity.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code getDecision} (BC-0112): owner-only read of one served decision.
 *
 * <p>BC-0310 part 1: {@code features_source} is derived (always SYNC) and {@code features_cutoff_w}
 * read from the ledger column; part 2 (after BC-0305) persists the source per decision via
 * BC-0305's migration or the decision-record v2 contract (BC-0401).
 */
@RestController
public class DecisionController {

  private final DecisionService decisions;

  public DecisionController(DecisionService decisions) {
    this.decisions = decisions;
  }

  @GetMapping(path = "/v1/me/decisions/{decision_id}", produces = MediaType.APPLICATION_JSON_VALUE)
  public DecisionView get(AuthenticatedUser user, @PathVariable("decision_id") String decisionId) {
    return decisions.get(user.userId(), decisionId);
  }
}
