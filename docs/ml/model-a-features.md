# Model A features v1 (`model-a-features-v1`, BC-0202)

Implementation: `ml/beacon_ml/features/model_a.py`. Golden fixtures (synthetic, hand-written):
`ml/beacon_ml/features/fixtures/model_a_features.v1.json`; any other implementation (Java, TS)
must reproduce every `expected` vector within 1e-9.

**Inputs:** a climb's `holds` (`{id, x, y, role}`, raw Kilter layout-1 units: x −20…164,
y 4…176, y up) and `angle_deg`. No other field is a feature; `ascents`, `route_uuid` and
`hold_set_hash` are metadata (`docs/data-cards/model-a-kilter.md`).

**Conventions:** hand holds H = roles START, HAND, FINISH; feet F = FOOT; S = START holds;
T = FINISH holds. d(a, b) is Euclidean distance. mean/std are population statistics over the
listed values. Any value that cannot be computed (no feet, no finish, fewer than two hand
holds…) is the sentinel **−1**.

| Feature | Formula |
|---|---|
| `angle_deg` | wall angle |
| `n_holds`, `n_start`, `n_hand`, `n_foot`, `n_finish` | number of holds overall and per role (`n_hand` counts role HAND only) |
| `hand_x_min`, `hand_x_max`, `hand_y_min`, `hand_y_max` | extent of H |
| `hand_width`, `hand_height` | `x_max − x_min`, `y_max − y_min` over H |
| `hand_x_std`, `hand_y_std` | std of x and of y over H |
| `start_x`, `start_y`, `finish_x`, `finish_y` | centroid of S, centroid of T |
| `start_finish_dx`, `_dy`, `_dist` | finish centroid − start centroid, and its length |
| `chain_gap_max`, `_mean`, `_std` | sort H by (y, x); distances between consecutive holds |
| `chain_dy_max`, `chain_dx_max` | largest vertical step and largest absolute horizontal step along that chain |
| `nn_mean`, `nn_max` | for each hand hold, distance to its nearest other hand hold |
| `pair_mean`, `pair_std`, `pair_max` | all pairwise distances within H |
| `hand_density` | \|H\| / (`hand_width` × `hand_height`); −1 when that area is 0 |
| `foot_y_mean` | mean y of F |
| `foot_to_hand_mean` | mean over feet of the distance to the nearest hand hold |
| `foot_below_start` | `start_y − foot_y_mean` |

**Hold identity (`HoldVocabulary`):** one indicator per (hold, hand use) and per (hold, foot
use), vocabulary fitted on the training split only; holds never seen in training produce no
column. This captures which physical holds a climb uses, which geometry alone cannot.

**Normalised coordinates** for neural models: `x' = (x + 20) / 184`, `y' = (y − 4) / 172`.

Changing any formula, the order of `GEOMETRY_FEATURES` or the sentinel is a new feature version
(`model-a-features-v2`) with regenerated fixtures; models record the version they were trained on.
