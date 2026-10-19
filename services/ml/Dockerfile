# BC-0212: image of the ML inference service (services/ml) for the compose `ml` profile.
# Build context is the repo root because the service depends on the training package in ml/
# (features and model classes must be byte-identical to training):
#   docker build -f services/ml/Dockerfile .
# services/ml/Dockerfile.dockerignore allowlists ml/ and services/ml/ only, so .env, data/
# (licensed data and model weights), planning/ and .git never reach the builder. Models are
# mounted read-only at run time from data/models/registry/ (gitignored), never baked in.
FROM ghcr.io/astral-sh/uv:0.12.19 AS uv

FROM python:3.12.14-slim AS build
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_LINK_MODE=copy UV_COMPILE_BYTECODE=1 UV_PYTHON_DOWNLOADS=never
WORKDIR /src
COPY ml/pyproject.toml ml/uv.lock ml/
COPY ml/beacon_ml ml/beacon_ml
COPY services/ml/pyproject.toml services/ml/uv.lock services/ml/
WORKDIR /src/services/ml
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
COPY services/ml/app app

FROM python:3.12.14-slim
COPY --from=build /src /src
WORKDIR /src/services/ml
ENV PATH=/src/services/ml/.venv/bin:$PATH PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 65534:65534
EXPOSE 8090
CMD ["python", "-I", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
