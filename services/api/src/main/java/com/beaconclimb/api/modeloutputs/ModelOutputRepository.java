package com.beaconclimb.api.modeloutputs;

import com.beaconclimb.api.catalog.RouteVariantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the batch-precomputed Model A outputs ({@code route_model_outputs}, V13, BC-0207) the
 * model-aware policy and "more like this" use (BC-0210). Keys are public route variant ids.
 *
 * <p>Rows are immutable per {@code (variant_id, model_version)}, so a non-empty result for a {@code
 * (model_version, board, angle)} is cached for the life of the process; an empty result is not
 * cached, so outputs precomputed after start-up are picked up on the next request. Only {@code
 * READY} model versions are read.
 */
@Repository
public class ModelOutputRepository {

  /** A registered model version and its artifact digest. */
  public record ModelVersion(String modelVersion, String artifactDigest) {}

  private record Key(String modelVersion, String boardId, int angleDeg) {}

  private static final String SCOPE =
      " FROM route_model_outputs o"
          + " JOIN model_versions m ON m.model_version = o.model_version AND m.status = 'READY'"
          + " JOIN route_variants v ON v.variant_id = o.variant_id"
          + " JOIN routes r ON r.route_id = v.route_id"
          + " WHERE o.model_version = :model AND r.board_id = :board AND v.angle_deg = :angle"
          + " ORDER BY r.provider_route_uuid, v.angle_deg";

  private final JdbcClient jdbc;
  private final Map<Key, Map<String, Double>> grades = new ConcurrentHashMap<>();
  private final Map<Key, Map<String, float[]>> embeddings = new ConcurrentHashMap<>();

  public ModelOutputRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The READY registration of a model version, if any. */
  public Optional<ModelVersion> readyVersion(String modelVersion) {
    if (modelVersion == null) {
      return Optional.empty();
    }
    return jdbc.sql(
            "SELECT model_version, artifact_digest FROM model_versions"
                + " WHERE model_version = :model AND status = 'READY'")
        .param("model", modelVersion)
        .query((rs, n) -> new ModelVersion(rs.getString(1), rs.getString(2).trim()))
        .optional();
  }

  /** Public id to predicted difficulty for every variant of the board at the angle. */
  public Map<String, Double> predictedGrades(String modelVersion, String boardId, int angleDeg) {
    Key key = new Key(modelVersion, boardId, angleDeg);
    Map<String, Double> cached = grades.get(key);
    if (cached != null) {
      return cached;
    }
    Map<String, Double> loaded = new LinkedHashMap<>();
    jdbc.sql("SELECT r.provider_route_uuid, v.angle_deg, o.predicted_difficulty" + SCOPE)
        .param("model", modelVersion)
        .param("board", boardId)
        .param("angle", angleDeg)
        .query(
            rs -> {
              loaded.put(publicId(rs), rs.getBigDecimal(3).doubleValue());
            });
    return remember(grades, key, loaded);
  }

  /** Public id to embedding for every variant of the board at the angle that has one. */
  public Map<String, float[]> embeddings(String modelVersion, String boardId, int angleDeg) {
    Key key = new Key(modelVersion, boardId, angleDeg);
    Map<String, float[]> cached = embeddings.get(key);
    if (cached != null) {
      return cached;
    }
    Map<String, float[]> loaded = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT r.provider_route_uuid, v.angle_deg, o.embedding"
                + SCOPE.replace("ORDER BY", "AND o.embedding IS NOT NULL ORDER BY"))
        .param("model", modelVersion)
        .param("board", boardId)
        .param("angle", angleDeg)
        .query(
            rs -> {
              loaded.put(publicId(rs), floats(rs.getArray(3)));
            });
    return remember(embeddings, key, loaded);
  }

  /**
   * Embeddings of the given public ids (any angle) in the given order, skipping ids without one;
   * used for the user's recent routes (at most 20).
   */
  public List<float[]> embeddingsOf(String modelVersion, List<String> publicIds) {
    List<float[]> out = new ArrayList<>();
    for (String publicId : publicIds) {
      Optional<RouteVariantId> id = RouteVariantId.parse(publicId);
      if (id.isEmpty()) {
        continue;
      }
      jdbc.sql(
              "SELECT o.embedding FROM route_model_outputs o"
                  + " JOIN model_versions m ON m.model_version = o.model_version"
                  + " AND m.status = 'READY'"
                  + " JOIN route_variants v ON v.variant_id = o.variant_id"
                  + " JOIN routes r ON r.route_id = v.route_id"
                  + " WHERE o.model_version = :model AND r.provider_route_uuid = :uuid"
                  + " AND v.angle_deg = :angle AND o.embedding IS NOT NULL")
          .param("model", modelVersion)
          .param("uuid", id.get().providerRouteUuid())
          .param("angle", id.get().angleDeg())
          .query((rs, n) -> floats(rs.getArray(1)))
          .optional()
          .ifPresent(out::add);
    }
    return out;
  }

  /** Drops every cached map (tests; an operator after rewriting outputs out of band). */
  public void clearCache() {
    grades.clear();
    embeddings.clear();
  }

  private static <V> Map<String, V> remember(
      Map<Key, Map<String, V>> cache, Key key, Map<String, V> loaded) {
    Map<String, V> frozen = Collections.unmodifiableMap(loaded);
    if (!frozen.isEmpty()) {
      cache.putIfAbsent(key, frozen);
    }
    return frozen;
  }

  private static String publicId(ResultSet rs) throws SQLException {
    return RouteVariantId.format(rs.getString(1), rs.getInt(2));
  }

  private static float[] floats(Array array) throws SQLException {
    Object[] values = (Object[]) array.getArray();
    float[] out = new float[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = ((Number) values[i]).floatValue();
    }
    return out;
  }
}
