"""Beacon Climb ML inference service (BC-0206).

Serves one registry version of Model A, chosen by ``BEACON_ML_MODEL_DIR`` (a folder under
``data/models/registry/``). At startup the folder goes through ``beacon_ml.registry.verify``
(file hashes, artifact digest, fixture predictions); until that succeeds ``/health/ready`` is
503 and every model endpoint answers 503 MODEL_NOT_READY. Nothing is ever served from a
REJECTED artifact.

Endpoints (contract: contracts/ml-inference.yaml, exported from this app):
  GET  /health, /health/live, /health/ready
  GET  /v1/models                -> the loaded version and its status
  POST /v1/grade-predictions     -> predicted difficulty per route (Aurora difficulty units)
  POST /v1/route-embeddings      -> 32-d embeddings; 409 when the active kind has none

Predictions are model estimates of a community grade, not safety or ability advice.
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Literal

from beacon_ml import registry
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

log = logging.getLogger("beacon.ml")
MAX_ROUTES = 500
MAX_HOLDS = 50
GRADE_MIN, GRADE_MAX = 0.0, 40.0


class Hold(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: Annotated[str, Field(min_length=1, max_length=64)]
    x: Annotated[float, Field(ge=-1000, le=1000)]
    y: Annotated[float, Field(ge=-1000, le=1000)]
    role: Literal["START", "HAND", "FOOT", "FINISH"]


class Route(BaseModel):
    model_config = ConfigDict(extra="forbid")
    angle_deg: Annotated[int, Field(ge=-10, le=70)]
    holds: Annotated[list[Hold], Field(min_length=1, max_length=MAX_HOLDS)]


class RoutesRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    routes: Annotated[list[Route], Field(min_length=1, max_length=MAX_ROUTES)]


class GradePrediction(BaseModel):
    predicted_difficulty: float


class GradePredictions(BaseModel):
    model_version: str
    input_schema_version: str
    predictions: list[GradePrediction]


class RouteEmbeddings(BaseModel):
    model_version: str
    dimension: int
    embeddings: list[list[float]]


class ModelInfo(BaseModel):
    model_version: str | None
    kind: str | None
    status: Literal["READY", "REJECTED", "NOT_CONFIGURED"]
    reason: str | None
    training_manifest_id: str | None
    has_embeddings: bool


class State:
    verified: registry.Verified | None = None
    configured: bool = False


def load_model(model_dir: str | None) -> State:
    state = State()
    if not model_dir:
        log.warning("BEACON_ML_MODEL_DIR is not set; the service stays not ready")
        return state
    state.configured = True
    state.verified = registry.verify(Path(model_dir))
    log.info(
        "model %s status=%s reason=%s",
        state.verified.manifest.get("model_version"),
        state.verified.status,
        state.verified.reason,
    )
    return state


@asynccontextmanager
async def lifespan(app: FastAPI):
    app.state.model = load_model(os.environ.get("BEACON_ML_MODEL_DIR"))
    yield


app = FastAPI(title="Beacon Climb ML", version="1.0.0", lifespan=lifespan)


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def _ready(request: Request) -> registry.Verified | None:
    state: State = request.app.state.model
    v = state.verified
    return v if v is not None and v.status == "READY" else None


@app.get("/health")
@app.get("/health/live")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/health/ready", responses={503: {"description": "No READY model loaded"}})
def ready(request: Request):
    if _ready(request) is None:
        return _error(503, "MODEL_NOT_READY", "no READY model is loaded")
    return {"status": "UP"}


@app.get("/v1/models", response_model=ModelInfo)
def models(request: Request) -> ModelInfo:
    state: State = request.app.state.model
    v = state.verified
    if v is None:
        return ModelInfo(
            model_version=None,
            kind=None,
            status="NOT_CONFIGURED",
            reason=None,
            training_manifest_id=None,
            has_embeddings=False,
        )
    m = v.manifest
    return ModelInfo(
        model_version=m.get("model_version"),
        kind=m.get("kind"),
        status=v.status,
        reason=v.reason,
        training_manifest_id=m.get("training_manifest_id"),
        has_embeddings=v.embed is not None,
    )


def _rows(body: RoutesRequest) -> list[dict]:
    return [r.model_dump() for r in body.routes]


@app.post(
    "/v1/grade-predictions",
    response_model=GradePredictions,
    responses={503: {"description": "No READY model loaded"}},
)
def grade_predictions(body: RoutesRequest, request: Request):
    v = _ready(request)
    if v is None:
        return _error(503, "MODEL_NOT_READY", "no READY model is loaded")
    raw = v.predict(_rows(body))
    values = [min(GRADE_MAX, max(GRADE_MIN, float(x))) for x in raw]
    return GradePredictions(
        model_version=v.manifest["model_version"],
        input_schema_version=v.manifest["input_schema_version"],
        predictions=[GradePrediction(predicted_difficulty=x) for x in values],
    )


@app.post(
    "/v1/route-embeddings",
    response_model=RouteEmbeddings,
    responses={
        409: {"description": "Active model has no embeddings"},
        503: {"description": "No READY model loaded"},
    },
)
def route_embeddings(body: RoutesRequest, request: Request):
    v = _ready(request)
    if v is None:
        return _error(503, "MODEL_NOT_READY", "no READY model is loaded")
    if v.embed is None:
        return _error(
            409,
            "MODEL_HAS_NO_EMBEDDINGS",
            f"model kind {v.manifest['kind']} does not produce embeddings",
        )
    vectors = v.embed(_rows(body))
    return RouteEmbeddings(
        model_version=v.manifest["model_version"],
        dimension=int(vectors.shape[1]),
        embeddings=[[float(x) for x in row] for row in vectors],
    )
