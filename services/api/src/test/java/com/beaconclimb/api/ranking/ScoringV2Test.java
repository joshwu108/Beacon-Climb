package com.beaconclimb.api.ranking;

import static com.beaconclimb.api.ranking.RankingFixtures.candidate;
import static com.beaconclimb.api.ranking.RankingFixtures.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beaconclimb.api.identity.preferences.Goal;
import com.beaconclimb.api.identity.preferences.GradeBand;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** BC-0210 heuristic-v2 scoring: blend, embedding novelty, and the v1 path left unchanged. */
class ScoringV2Test {

  private static final Policy V1 = new PolicyLoader().load();
  private static final Policy V2 = new PolicyLoader(PolicyLoader.V2_RESOURCE).load();

  @Test
  void v2PolicyCarriesTheOwnersModelSettings() {
    assertThat(V2.policyVersion()).isEqualTo("heuristic-v2");
    assertThat(V2.model().blendK()).isEqualTo(V2.minObs());
    assertThat(V2.model().gradeModelVersion()).isEqualTo("model-a-gbm-20261019");
    assertThat(V2.model().embeddingModelVersion()).isEqualTo("model-a-deepsets-20261019");
    assertThat(V1.usesModel()).isFalse();
    assertThat(V2.weights()).isEqualTo(V1.weights());
    assertThat(V2.gradeWindow()).isEqualTo(V1.gradeWindow());
  }

  @Test
  void blendLeansOnTheModelOnlyForThinlyClimbedRoutes() {
    ModelSignals signals =
        new ModelSignals(Map.of("a:40", 20.0, "b:40", 20.0, "c:40", 20.0), Map.of(), List.of());
    // k = 10. No ascents: the prediction. 10 ascents: halfway. 990 ascents: almost the community.
    assertThat(Scoring.gradeUsed(candidate("a:40", 16.0, 0, List.of()), signals, V2.model()))
        .isEqualTo(20.0);
    assertThat(Scoring.gradeUsed(candidate("b:40", 16.0, 10, List.of()), signals, V2.model()))
        .isEqualTo(18.0);
    assertThat(Scoring.gradeUsed(candidate("c:40", 16.0, 990, List.of()), signals, V2.model()))
        .isEqualTo((990 * 16.0 + 10 * 20.0) / 1000);
  }

  @Test
  void missingPredictionKeepsTheCommunityGradeAndUngradedUsesThePrediction() {
    ModelSignals signals = new ModelSignals(Map.of("p:40", 21.0), Map.of(), List.of());
    assertThat(Scoring.gradeUsed(candidate("x:40", 16.0, 3, List.of()), signals, V2.model()))
        .isEqualTo(16.0);
    assertThat(Scoring.gradeUsed(candidate("p:40", null, 0, List.of()), signals, V2.model()))
        .isEqualTo(21.0);
    assertThat(Scoring.gradeUsed(candidate("y:40", null, 0, List.of()), signals, V2.model()))
        .isNull();
  }

  @Test
  void cosineDistanceIsZeroForTheSameDirectionAndTwoForTheOpposite() {
    float[] a = {1, 2, 3};
    assertThat(Scoring.cosineDistance(a, new float[] {2, 4, 6})).isEqualTo(0.0);
    assertThat(Scoring.cosineDistance(a, new float[] {-1, -2, -3})).isEqualTo(2.0);
    assertThat(Scoring.cosineDistance(new float[] {1, 0}, new float[] {0, 1})).isEqualTo(1.0);
    assertThat(Scoring.cosineDistance(new float[] {0, 0}, new float[] {0, 1})).isEqualTo(1.0);
    assertThatThrownBy(() -> Scoring.cosineDistance(a, new float[] {1}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noveltyIsZeroForRecentRoutesAndScalesWithDistanceOtherwise() {
    float[] recent = {1, 0};
    ModelSignals signals =
        new ModelSignals(
            Map.of(),
            Map.of("near:40", new float[] {1, 0.1f}, "far:40", new float[] {0, 1}),
            List.of(recent));
    UserContext ctx =
        context(Goal.TECHNIQUE, GradeBand.INTERMEDIATE, List.of("seen:40"), Set.of(), null);
    ScoreInputs seen = Scoring.inputs(candidate("seen:40", 18.0, 5, List.of()), ctx, V2, signals);
    ScoreInputs near = Scoring.inputs(candidate("near:40", 18.0, 5, List.of()), ctx, V2, signals);
    ScoreInputs far = Scoring.inputs(candidate("far:40", 18.0, 5, List.of()), ctx, V2, signals);
    ScoreInputs unknown =
        Scoring.inputs(candidate("none:40", 18.0, 5, List.of()), ctx, V2, signals);

    assertThat(seen.novelty()).isZero();
    double d = Scoring.cosineDistance(new float[] {1, 0.1f}, recent);
    assertThat(near.novelty()).isEqualTo(Math.min(1.0, d / 0.5)).isLessThan(0.1);
    assertThat(far.novelty()).isEqualTo(1.0);
    assertThat(unknown.novelty()).isEqualTo(1.0);
  }

  @Test
  void challengeFitUsesTheBlendedGrade() {
    UserContext ctx = context(Goal.TECHNIQUE, GradeBand.INTERMEDIATE);
    double target = V2.targetGrade(GradeBand.INTERMEDIATE, Goal.TECHNIQUE);
    ModelSignals signals = new ModelSignals(Map.of("r:40", target), Map.of(), List.of());
    Candidate thin = candidate("r:40", target + 8, 0, List.of());

    assertThat(Scoring.inputs(thin, ctx, V2, signals).challengeFit()).isEqualTo(1.0);
    assertThat(Scoring.inputs(thin, ctx, V1, signals).challengeFit())
        .isEqualTo(1.0 - Math.min(1.0, 8 / V1.gradeWindow()));
  }

  /** A v1 policy ignores any signals, and v2 without signals computes v1's exact inputs. */
  @Property(tries = 500)
  void withoutModelOutputsV2ScoresExactlyLikeV1(
      @ForAll("candidates") Candidate candidate, @ForAll("recent") List<String> recent) {
    UserContext ctx = context(Goal.STRENGTH, GradeBand.ADVANCED, recent, Set.of(), null);
    ScoreInputs v1 = Scoring.inputs(candidate, ctx, V1);
    ModelSignals noise =
        new ModelSignals(
            Map.of(candidate.routeVariantId(), 33.0),
            Map.of(candidate.routeVariantId(), new float[] {1, 2}),
            List.of(new float[] {2, 1}));

    assertThat(Scoring.inputs(candidate, ctx, V1, noise)).isEqualTo(v1);
    assertThat(Scoring.inputs(candidate, ctx, V2, ModelSignals.EMPTY)).isEqualTo(v1);
    assertThat(Scoring.score(Scoring.inputs(candidate, ctx, V2, ModelSignals.EMPTY), V2.weights()))
        .isEqualTo(Scoring.score(v1, V1.weights()));
  }

  @Provide
  Arbitrary<Candidate> candidates() {
    return Arbitraries.integers()
        .between(0, 40)
        .flatMap(
            i ->
                Arbitraries.doubles()
                    .between(10, 33)
                    .ofScale(2)
                    .flatMap(
                        grade ->
                            Arbitraries.integers()
                                .between(0, 500)
                                .map(
                                    obs ->
                                        candidate(
                                            "r" + i + ":40",
                                            grade,
                                            obs,
                                            obs % 2 == 0 ? List.of("crimpy") : List.of()))));
  }

  @Provide
  Arbitrary<List<String>> recent() {
    return Arbitraries.integers().between(0, 40).map(i -> "r" + i + ":40").list().ofMaxSize(5);
  }
}
