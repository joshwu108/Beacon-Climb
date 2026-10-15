package com.beaconclimb.api.recommendations.decisions;

import com.beaconclimb.api.ranking.ReasonCode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The "Why this route" dictionary (BC-0112): exactly one fixed sentence per {@link ReasonCode},
 * nothing else. {@link #render} joins the sentences of the given codes in the given order, so the
 * text of a decision is a pure function of its reason codes and never contains client input, notes
 * or free-form model output. The sentences make no claim about physical difficulty or ability: a
 * grade target is a planning placeholder derived from the self-assessed band.
 */
public final class Explanations {

  static final String SEPARATOR = " ";

  private static final Map<ReasonCode, String> TEMPLATES = templates();

  private Explanations() {}

  public static String render(List<ReasonCode> codes) {
    return codes.stream().map(Explanations::sentence).collect(Collectors.joining(SEPARATOR));
  }

  public static String sentence(ReasonCode code) {
    String sentence = TEMPLATES.get(code);
    if (sentence == null) {
      throw new IllegalStateException("no explanation template for " + code);
    }
    return sentence;
  }

  public static Map<ReasonCode, String> templates() {
    Map<ReasonCode, String> map = new EnumMap<>(ReasonCode.class);
    map.put(
        ReasonCode.COMPATIBLE_ANGLE, "This route is set for the board and angle you asked for.");
    map.put(
        ReasonCode.GOAL_CHALLENGE_MATCH,
        "Its grade is close to the target grade for your goal and self-assessed band.");
    map.put(ReasonCode.NOVEL_ROUTE, "You have not attempted it recently.");
    map.put(
        ReasonCode.PREFERRED_STYLE,
        "Its style tags match the styles associated with your session goal.");
    map.put(
        ReasonCode.WELL_OBSERVED_GRADE,
        "Its grade is backed by enough observations to count as stable.");
    map.put(
        ReasonCode.COOLDOWN_SKIPPED,
        "Routes you attempted within the cooldown window were skipped.");
    map.put(
        ReasonCode.LOW_INVENTORY_WIDENED,
        "Too few routes matched, so routes inside the cooldown window were allowed back in.");
    map.put(
        ReasonCode.COLD_START_HEURISTIC,
        "There is not enough attempt history yet, so the target grade comes from your self-assessed"
            + " band.");
    return Collections.unmodifiableMap(map);
  }
}
