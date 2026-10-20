package com.beaconclimb.api.ranking;

import com.beaconclimb.api.ranking.FilterOutcome.FilterReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic heuristic ranker (BC-0110): pure and total over valid inputs. Hard filters
 * ({@link CandidateFilter}) run in candidate order; when fewer than {@code low_inventory_min}
 * candidates survive and some were removed only by the cooldown, those are re-admitted (widening)
 * and every served item carries LOW_INVENTORY_WIDENED. Survivors are scored ({@link Scoring}),
 * sorted by score descending then ascending {@link TieBreak} key, and cut to {@code maxRoutes}. No
 * hash-map iteration decides any order; candidate order only breaks nothing because the sort key is
 * total.
 *
 * <p>Duplicate ids (BC-0124 item 8): the ranker does not rely on the caller handing it distinct
 * {@code routeVariantId}s. Candidates are de-duplicated by id before anything else, keeping the
 * first occurrence in candidate order, so every id is filtered, scored and served at most once and
 * the decision record never lists one variant twice.
 */
public final class Ranker {

  private record Scored(Candidate candidate, ScoreInputs inputs, double score, String tieKey) {}

  private Ranker() {}

  public static RankResult rank(
      List<Candidate> candidateInput, UserContext context, Policy policy, int maxRoutes) {
    return rank(candidateInput, context, policy, maxRoutes, ModelSignals.EMPTY);
  }

  /**
   * As above with precomputed model outputs; a policy without a {@code model} block ignores them
   * (heuristic-v1 is unchanged).
   */
  public static RankResult rank(
      List<Candidate> candidateInput,
      UserContext context,
      Policy policy,
      int maxRoutes,
      ModelSignals signals) {
    List<Candidate> candidates = distinctById(candidateInput);
    List<FilterOutcome> rejected = new ArrayList<>();
    List<Candidate> survivors = new ArrayList<>();
    List<Candidate> cooled = new ArrayList<>();
    for (Candidate candidate : candidates) {
      Optional<FilterReason> reason = CandidateFilter.reject(candidate, context, policy);
      if (reason.isEmpty()) {
        survivors.add(candidate);
      } else if (reason.get() == FilterReason.COOLDOWN) {
        cooled.add(candidate);
        rejected.add(new FilterOutcome(candidate.routeVariantId(), reason.get()));
      } else {
        rejected.add(new FilterOutcome(candidate.routeVariantId(), reason.get()));
      }
    }
    boolean widened = survivors.size() < policy.lowInventoryMin() && !cooled.isEmpty();
    if (widened) {
      survivors = readmit(candidates, survivors, cooled);
      rejected.removeIf(outcome -> outcome.reason() == FilterReason.COOLDOWN);
    }
    List<Scored> scored = new ArrayList<>(survivors.size());
    for (Candidate candidate : survivors) {
      scored.add(score(candidate, context, policy, signals));
    }
    scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::tieKey));
    boolean coldStart = context.isColdStart();
    List<Ranked> ranked = new ArrayList<>();
    int served = Math.min(Math.max(maxRoutes, 0), scored.size());
    for (int i = 0; i < served; i++) {
      ranked.add(ranked(scored.get(i), i + 1, widened, coldStart));
    }
    return new RankResult(ranked, rejected, widened, coldStart);
  }

  /** The first candidate per {@code routeVariantId}, in candidate order. */
  private static List<Candidate> distinctById(List<Candidate> candidates) {
    Set<String> seen = new HashSet<>();
    List<Candidate> distinct = new ArrayList<>(candidates.size());
    for (Candidate candidate : candidates) {
      if (seen.add(candidate.routeVariantId())) {
        distinct.add(candidate);
      }
    }
    return distinct;
  }

  /** The widened survivor list in original candidate order. */
  private static List<Candidate> readmit(
      List<Candidate> candidates, List<Candidate> survivors, List<Candidate> cooled) {
    List<Candidate> widened = new ArrayList<>(survivors.size() + cooled.size());
    for (Candidate candidate : candidates) {
      if (survivors.contains(candidate) || cooled.contains(candidate)) {
        widened.add(candidate);
      }
    }
    return widened;
  }

  private static Scored score(
      Candidate candidate, UserContext context, Policy policy, ModelSignals signals) {
    ScoreInputs inputs = Scoring.inputs(candidate, context, policy, signals);
    return new Scored(
        candidate,
        inputs,
        Scoring.score(inputs, policy.weights()),
        TieBreak.key(
            policy.policyVersion(),
            context.userId(),
            context.sessionId(),
            candidate.routeVariantId()));
  }

  /**
   * Reason codes in contract order. GOAL_CHALLENGE_MATCH is withheld for a cold-start user: the
   * target grade is then a band placeholder, not evidence, and COLD_START_HEURISTIC says so (this
   * also reproduces the synthetic fixture of ADR-0003).
   */
  private static Ranked ranked(Scored scored, int position, boolean widened, boolean coldStart) {
    ScoreInputs in = scored.inputs();
    EnumSet<ReasonCode> reasons = EnumSet.of(ReasonCode.COMPATIBLE_ANGLE);
    if (!coldStart && in.challengeFit() >= Scoring.CHALLENGE_MATCH_MIN_FIT) {
      reasons.add(ReasonCode.GOAL_CHALLENGE_MATCH);
    }
    if (in.novelty() == 1.0) {
      reasons.add(ReasonCode.NOVEL_ROUTE);
    }
    if (in.preferenceMatch() == 1.0) {
      reasons.add(ReasonCode.PREFERRED_STYLE);
    }
    if (in.confidenceBonus() == 1.0) {
      reasons.add(ReasonCode.WELL_OBSERVED_GRADE);
    }
    if (widened) {
      reasons.add(ReasonCode.LOW_INVENTORY_WIDENED);
    }
    if (coldStart) {
      reasons.add(ReasonCode.COLD_START_HEURISTIC);
    }
    return new Ranked(
        scored.candidate().routeVariantId(), position, scored.score(), List.copyOf(reasons), in);
  }
}
