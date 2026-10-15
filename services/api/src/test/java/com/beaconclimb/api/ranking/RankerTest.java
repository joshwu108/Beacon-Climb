package com.beaconclimb.api.ranking;

import static com.beaconclimb.api.ranking.RankingFixtures.ANGLE;
import static com.beaconclimb.api.ranking.RankingFixtures.BOARD;
import static com.beaconclimb.api.ranking.RankingFixtures.NOW;
import static com.beaconclimb.api.ranking.RankingFixtures.attempted;
import static com.beaconclimb.api.ranking.RankingFixtures.candidate;
import static com.beaconclimb.api.ranking.RankingFixtures.context;
import static com.beaconclimb.api.ranking.RankingFixtures.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import com.beaconclimb.api.ranking.FilterOutcome.FilterReason;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** One witness per reason code, per hard filter, the widening rule and the arithmetic order. */
class RankerTest {

  private static final Policy POLICY = policy();

  /** INTERMEDIATE 19 + TECHNIQUE -1. */
  private static final UserContext WARM =
      context(Goal.TECHNIQUE, GradeBand.INTERMEDIATE, List.of("old:40"), Set.of(), 17.0);

  @Test
  void targetGradeIsBandTargetPlusGoalOffset() {
    assertThat(POLICY.targetGrade(GradeBand.INTERMEDIATE, Goal.TECHNIQUE)).isEqualTo(18.0);
    assertThat(POLICY.targetGrade(GradeBand.NOVICE, Goal.PROJECT)).isEqualTo(15.0);
    assertThat(POLICY.targetGrade(GradeBand.EXPERT, Goal.ENDURANCE)).isEqualTo(25.0);
  }

  @Test
  void compatibleAngleIsAlwaysPresentAndGoalChallengeMatchNeedsFitOfAtLeastHalf() {
    List<Ranked> ranked =
        Ranker.rank(
                List.of(
                    candidate("fit:40", 18.0),
                    candidate("far:40", 24.0),
                    candidate("edge:40", 23.0)),
                WARM,
                POLICY,
                10)
            .ranked();

    assertThat(ranked)
        .allSatisfy(r -> assertThat(r.reasonCodes()).startsWith(ReasonCode.COMPATIBLE_ANGLE));
    assertThat(reasons(ranked, "fit:40")).contains(ReasonCode.GOAL_CHALLENGE_MATCH);
    assertThat(reasons(ranked, "edge:40")).contains(ReasonCode.GOAL_CHALLENGE_MATCH);
    assertThat(reasons(ranked, "far:40")).doesNotContain(ReasonCode.GOAL_CHALLENGE_MATCH);
  }

  @Test
  void novelRouteIsAbsentWhenTheVariantIsInRecentIds() {
    List<Ranked> ranked =
        Ranker.rank(List.of(candidate("old:40", 18.0), candidate("new:40", 18.0)), WARM, POLICY, 10)
            .ranked();

    assertThat(reasons(ranked, "new:40")).contains(ReasonCode.NOVEL_ROUTE);
    assertThat(reasons(ranked, "old:40")).doesNotContain(ReasonCode.NOVEL_ROUTE);
    assertThat(ranked.get(0).routeVariantId()).isEqualTo("new:40");
  }

  @Test
  void preferredStyleFollowsTheGoalTagMap() {
    List<Ranked> ranked =
        Ranker.rank(
                List.of(
                    candidate("tech:40", 18.0, 0, List.of("technical", "balancy")),
                    candidate("crimp:40", 18.0, 0, List.of("crimpy"))),
                WARM,
                POLICY,
                10)
            .ranked();

    assertThat(reasons(ranked, "tech:40")).contains(ReasonCode.PREFERRED_STYLE);
    assertThat(reasons(ranked, "crimp:40")).doesNotContain(ReasonCode.PREFERRED_STYLE);
  }

  @Test
  void wellObservedGradeNeedsMinObsObservations() {
    List<Ranked> ranked =
        Ranker.rank(
                List.of(
                    candidate("ten:40", 18.0, 10, List.of()),
                    candidate("nine:40", 18.0, 9, List.of()),
                    candidate("many:40", 18.0, 50, List.of())),
                WARM,
                POLICY,
                10)
            .ranked();

    assertThat(reasons(ranked, "ten:40")).contains(ReasonCode.WELL_OBSERVED_GRADE);
    assertThat(reasons(ranked, "many:40")).contains(ReasonCode.WELL_OBSERVED_GRADE);
    assertThat(reasons(ranked, "nine:40")).doesNotContain(ReasonCode.WELL_OBSERVED_GRADE);
    assertThat(inputs(ranked, "nine:40").confidenceBonus()).isEqualTo(0.9);
  }

  @Test
  void coldStartUserGetsColdStartHeuristicAndNoGoalChallengeMatch() {
    UserContext cold = context(Goal.TECHNIQUE, GradeBand.INTERMEDIATE);

    RankResult result = Ranker.rank(List.of(candidate("fit:40", 18.0)), cold, POLICY, 10);

    assertThat(result.coldStart()).isTrue();
    assertThat(result.ranked().get(0).reasonCodes())
        .containsExactly(
            ReasonCode.COMPATIBLE_ANGLE, ReasonCode.NOVEL_ROUTE, ReasonCode.COLD_START_HEURISTIC);
    assertThat(result.resultReasons()).containsExactly(ReasonCode.COLD_START_HEURISTIC);
  }

  @Test
  void cooldownRejectsRecentAttemptsUnlessGoalIsProject() {
    Candidate recent = attempted("recent:40", 18.0, NOW.minus(Duration.ofHours(47)));
    Candidate stale = attempted("stale:40", 18.0, NOW.minus(Duration.ofHours(48)));
    List<Candidate> candidates =
        List.of(
            recent,
            stale,
            candidate("a:40", 18.0),
            candidate("b:40", 18.0),
            candidate("c:40", 18.0));

    RankResult technique = Ranker.rank(candidates, WARM, POLICY, 10);
    RankResult project =
        Ranker.rank(candidates, context(Goal.PROJECT, GradeBand.INTERMEDIATE), POLICY, 10);

    assertThat(ids(technique.ranked())).doesNotContain("recent:40").contains("stale:40");
    assertThat(technique.rejected())
        .containsExactly(new FilterOutcome("recent:40", FilterReason.COOLDOWN));
    assertThat(technique.resultReasons()).containsExactly(ReasonCode.COOLDOWN_SKIPPED);
    assertThat(technique.lowInventoryWidened()).isFalse();
    assertThat(ids(project.ranked())).contains("recent:40");
    assertThat(project.rejected()).isEmpty();
  }

  @Test
  void lowInventoryWidensByReadmittingCooldownRejectionsOnly() {
    Candidate recent = attempted("recent:40", 18.0, NOW.minus(Duration.ofHours(1)));
    Candidate retired =
        new Candidate("retired:40", BOARD, ANGLE, 18.0, 0, "RETIRED", List.of(), null);
    List<Candidate> candidates =
        List.of(candidate("a:40", 18.0), candidate("b:40", 18.0), recent, retired);

    RankResult result = Ranker.rank(candidates, WARM, POLICY, 10);

    assertThat(result.lowInventoryWidened()).isTrue();
    assertThat(ids(result.ranked())).containsExactlyInAnyOrder("a:40", "b:40", "recent:40");
    assertThat(result.ranked())
        .allSatisfy(r -> assertThat(r.reasonCodes()).contains(ReasonCode.LOW_INVENTORY_WIDENED));
    assertThat(result.rejected())
        .containsExactly(new FilterOutcome("retired:40", FilterReason.INACTIVE));
    assertThat(result.resultReasons()).containsExactly(ReasonCode.LOW_INVENTORY_WIDENED);
  }

  @Test
  void hardFiltersReportTheFirstFailingRuleInOrder() {
    List<Candidate> candidates =
        List.of(
            new Candidate("board:40", "other", ANGLE, 18.0, 0, Candidate.ACTIVE, List.of(), null),
            new Candidate("angle:35", BOARD, 35, 18.0, 0, Candidate.ACTIVE, List.of(), null),
            new Candidate("invalid:40", BOARD, ANGLE, 18.0, 0, "INVALID", List.of(), null),
            candidate("excluded:40", 18.0));
    UserContext context =
        context(Goal.TECHNIQUE, GradeBand.INTERMEDIATE, List.of(), Set.of("excluded:40"), null);

    RankResult result = Ranker.rank(candidates, context, POLICY, 10);

    assertThat(result.ranked()).isEmpty();
    assertThat(result.rejected())
        .containsExactly(
            new FilterOutcome("board:40", FilterReason.INCOMPATIBLE_BOARD),
            new FilterOutcome("angle:35", FilterReason.INCOMPATIBLE_ANGLE),
            new FilterOutcome("invalid:40", FilterReason.INACTIVE),
            new FilterOutcome("excluded:40", FilterReason.EXCLUDED));
    assertThat(result.lowInventoryWidened()).isFalse();
  }

  @Test
  void ungradedCandidateHasZeroChallengeFitAndNoChallengeMatch() {
    List<Ranked> ranked =
        Ranker.rank(List.of(candidate("ungraded:40", null)), WARM, POLICY, 10).ranked();

    assertThat(inputs(ranked, "ungraded:40").challengeFit()).isZero();
    assertThat(reasons(ranked, "ungraded:40")).doesNotContain(ReasonCode.GOAL_CHALLENGE_MATCH);
  }

  @Test
  void scoreIsTheLeftToRightSumOfTheFiveWeightedTerms() {
    Candidate candidate = candidate("x:40", 21.0, 5, List.of("technical"));

    Ranked ranked = Ranker.rank(List.of(candidate), WARM, POLICY, 1).ranked().get(0);

    Policy.Weights w = POLICY.weights();
    ScoreInputs in = ranked.inputs();
    assertThat(in).isEqualTo(new ScoreInputs(18.0, 1.0 - Math.min(1.0, 3.0 / 10.0), 1.0, 1.0, 0.5));
    double expected = w.challenge() * in.challengeFit();
    expected = expected + w.skillGap() * 0.0;
    expected = expected + w.novelty() * in.novelty();
    expected = expected + w.preference() * in.preferenceMatch();
    expected = expected + w.confidence() * in.confidenceBonus();
    assertThat(Double.doubleToRawLongBits(ranked.score()))
        .isEqualTo(Double.doubleToRawLongBits(expected));
  }

  @Test
  void emptyInputAndZeroMaxRoutesAreTotal() {
    assertThat(Ranker.rank(List.of(), WARM, POLICY, 5).ranked()).isEmpty();
    assertThat(Ranker.rank(List.of(candidate("a:40", 18.0)), WARM, POLICY, 0).ranked()).isEmpty();
  }

  private static List<ReasonCode> reasons(List<Ranked> ranked, String id) {
    return find(ranked, id).reasonCodes();
  }

  private static ScoreInputs inputs(List<Ranked> ranked, String id) {
    return find(ranked, id).inputs();
  }

  private static Ranked find(List<Ranked> ranked, String id) {
    return ranked.stream().filter(r -> r.routeVariantId().equals(id)).findFirst().orElseThrow();
  }

  private static List<String> ids(List<Ranked> ranked) {
    return ranked.stream().map(Ranked::routeVariantId).toList();
  }
}
