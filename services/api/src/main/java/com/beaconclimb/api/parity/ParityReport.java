package com.beaconclimb.api.parity;

import com.beaconclimb.api.platform.web.Timestamps;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The result of one parity run (BC-0306; format in scripts/parity/README.md). The summary carries
 * counts only; {@link Detail} carries the user id (never a username), the verdict and the
 * provenance values of the rows compared. {@code syntheticOnly} is what the runner refuses on:
 * every compared user must be a synthetic, e2e or demo account before a report is written.
 *
 * @param usersTotal non-erased users considered ({@code all}, or the one asked for)
 * @param nonSyntheticCompared how many compared users are not synthetic/e2e/demo (0 in any written
 *     report)
 */
public record ParityReport(
    Instant generatedAt,
    String commit,
    int usersTotal,
    List<Detail> details,
    int nonSyntheticCompared) {

  /** Verdict per user; {@code NO_STREAM_ROW} is skipped and counted, the others are compared. */
  public enum Status {
    EQUAL,
    DIFF,
    SAME_W_MISMATCH,
    NO_STREAM_ROW
  }

  /**
   * One user. {@code cutoffW} is the STREAM row's W (the batch ran at it); {@code syncCutoffW} is
   * the SYNC row's W when one exists. {@code diffKeys} are the top-level aggregate keys whose
   * canonical values differ between the batch and the STREAM row; {@code checksumDiffers} says
   * whether the provenance checksums differ. All null / empty for {@code NO_STREAM_ROW}.
   */
  public record Detail(
      UUID userId,
      Status status,
      Instant cutoffW,
      Instant syncCutoffW,
      Instant streamFeatureAsOf,
      Instant batchFeatureAsOf,
      Integer streamPartition,
      Long streamOffset,
      List<String> diffKeys,
      boolean checksumDiffers) {

    public Detail {
      diffKeys = List.copyOf(diffKeys);
    }

    public static Detail noStreamRow(UUID userId) {
      return new Detail(
          userId, Status.NO_STREAM_ROW, null, null, null, null, null, null, List.of(), false);
    }
  }

  public ParityReport {
    details = List.copyOf(details);
  }

  public long count(Status status) {
    return details.stream().filter(d -> d.status() == status).count();
  }

  public long usersWithStreamRow() {
    return details.size() - count(Status.NO_STREAM_ROW);
  }

  public long compared() {
    return usersWithStreamRow();
  }

  public boolean syntheticOnly() {
    return nonSyntheticCompared == 0;
  }

  /** Exit 0 of the runner: no DIFF and no SAME_W_MISMATCH. */
  public boolean passed() {
    return count(Status.DIFF) == 0 && count(Status.SAME_W_MISMATCH) == 0;
  }

  public JsonNode toJson() {
    ObjectNode root = JsonNodeFactory.instance.objectNode();
    root.put("generated_at", Timestamps.format(generatedAt));
    root.put("commit", commit);
    root.put("users_total", usersTotal);
    root.put("users_with_stream_row", usersWithStreamRow());
    root.put("compared", compared());
    root.put("equal", count(Status.EQUAL));
    root.put("diffs", count(Status.DIFF));
    root.put("same_w_mismatch", count(Status.SAME_W_MISMATCH));
    root.put("skipped_no_stream_row", count(Status.NO_STREAM_ROW));
    root.put("synthetic_only", syntheticOnly());
    ArrayNode list = root.putArray("details");
    for (Detail detail : details) {
      list.add(detailJson(detail));
    }
    return root;
  }

  private static ObjectNode detailJson(Detail detail) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("user_id", detail.userId().toString());
    node.put("status", detail.status().name());
    putInstant(node, "cutoff_w", detail.cutoffW());
    putInstant(node, "sync_cutoff_w", detail.syncCutoffW());
    putInstant(node, "stream_feature_as_of", detail.streamFeatureAsOf());
    putInstant(node, "batch_feature_as_of", detail.batchFeatureAsOf());
    if (detail.streamPartition() == null) {
      node.putNull("stream_partition");
    } else {
      node.put("stream_partition", detail.streamPartition());
    }
    if (detail.streamOffset() == null) {
      node.putNull("stream_offset");
    } else {
      node.put("stream_offset", detail.streamOffset());
    }
    ArrayNode keys = node.putArray("diff_keys");
    detail.diffKeys().forEach(keys::add);
    node.put("checksum_differs", detail.checksumDiffers());
    return node;
  }

  private static void putInstant(ObjectNode node, String key, Instant value) {
    if (value == null) {
      node.putNull(key);
    } else {
      node.put(key, Timestamps.format(value));
    }
  }

  /** The one-line summary the runner prints. */
  public String summaryLine(String out) {
    return "parity summary users="
        + usersTotal
        + " with_stream_row="
        + usersWithStreamRow()
        + " compared="
        + compared()
        + " equal="
        + count(Status.EQUAL)
        + " diffs="
        + count(Status.DIFF)
        + " same_w_mismatch="
        + count(Status.SAME_W_MISMATCH)
        + " skipped_no_stream_row="
        + count(Status.NO_STREAM_ROW)
        + " synthetic_only="
        + syntheticOnly()
        + " out="
        + out;
  }
}
