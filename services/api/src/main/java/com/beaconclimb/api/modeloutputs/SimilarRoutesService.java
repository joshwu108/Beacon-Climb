package com.beaconclimb.api.modeloutputs;

import com.beaconclimb.api.catalog.CatalogQueryRepository;
import com.beaconclimb.api.catalog.CatalogQueryRepository.VariantRow;
import com.beaconclimb.api.catalog.CatalogService;
import com.beaconclimb.api.catalog.RouteVariantId;
import com.beaconclimb.api.catalog.api.RouteVariantSummaryView;
import com.beaconclimb.api.modeloutputs.api.SimilarRoutesResponse;
import com.beaconclimb.api.modeloutputs.api.SimilarRoutesResponse.Item;
import com.beaconclimb.api.ranking.ModelSettings;
import com.beaconclimb.api.ranking.PolicyRegistry;
import com.beaconclimb.api.ranking.Scoring;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * "More like this" (BC-0210, {@code listSimilarRoutes}): the visible variants at the same board and
 * angle whose precomputed embeddings are nearest by cosine distance to the requested one. The
 * embedding model is the one named by the default ranking policy. Ties break on the public id, so
 * equal inputs give equal answers. Empty (model_version null) when the policy names no embedding
 * model or this route has no embedding; nothing is computed on the request path besides distances.
 */
@Service
public class SimilarRoutesService {

  private record Neighbour(String publicId, double distance) {}

  private final CatalogQueryRepository catalog;
  private final ModelOutputRepository outputs;
  private final PolicyRegistry policies;
  private final Clock clock;

  public SimilarRoutesService(
      CatalogQueryRepository catalog,
      ModelOutputRepository outputs,
      PolicyRegistry policies,
      Clock clock) {
    this.catalog = catalog;
    this.outputs = outputs;
    this.policies = policies;
    this.clock = clock;
  }

  /** Empty when the route variant does not exist or is not visible (404). */
  public Optional<SimilarRoutesResponse> similar(String publicId, int limit) {
    Optional<RouteVariantId> id = RouteVariantId.parse(publicId);
    if (id.isEmpty()) {
      return Optional.empty();
    }
    Optional<VariantRow> row = catalog.findVisibleVariant(id.get(), clock.instant());
    if (row.isEmpty()) {
      return Optional.empty();
    }
    String canonical = RouteVariantId.format(id.get().providerRouteUuid(), id.get().angleDeg());
    ModelSettings model = policies.active().model();
    String embeddingModel = model == null ? null : model.embeddingModelVersion();
    Optional<ModelOutputRepository.ModelVersion> version = outputs.readyVersion(embeddingModel);
    if (version.isEmpty()) {
      return Optional.of(new SimilarRoutesResponse(canonical, null, List.of()));
    }
    Map<String, float[]> vectors =
        outputs.embeddings(version.get().modelVersion(), row.get().boardId(), id.get().angleDeg());
    float[] own = vectors.get(canonical);
    if (own == null) {
      return Optional.of(new SimilarRoutesResponse(canonical, null, List.of()));
    }
    List<Neighbour> nearest = nearest(canonical, own, vectors);
    List<Item> items = new ArrayList<>();
    for (Neighbour n : nearest) {
      if (items.size() == limit) {
        break;
      }
      Optional<RouteVariantId> nid = RouteVariantId.parse(n.publicId());
      Optional<VariantRow> visible =
          nid.flatMap(v -> catalog.findVisibleVariant(v, clock.instant()));
      if (visible.isPresent()) {
        RouteVariantSummaryView summary = CatalogService.summary(visible.get());
        items.add(new Item(summary, n.distance()));
      }
    }
    return Optional.of(
        new SimilarRoutesResponse(canonical, version.get().modelVersion(), List.copyOf(items)));
  }

  /** Every other variant by ascending distance, then public id. */
  static List<Neighbour> nearest(String self, float[] own, Map<String, float[]> vectors) {
    List<Neighbour> all = new ArrayList<>(vectors.size());
    vectors.forEach(
        (publicId, vector) -> {
          if (!publicId.equals(self)) {
            all.add(new Neighbour(publicId, Scoring.cosineDistance(own, vector)));
          }
        });
    all.sort(Comparator.comparingDouble(Neighbour::distance).thenComparing(Neighbour::publicId));
    return all;
  }
}
