# BC-0120: production-style image of the web client for the compose `core` profile.
# Build context is the monorepo root (pnpm workspace lockfile lives there):
#   docker build -f apps/web/Dockerfile .
# apps/web/Dockerfile.dockerignore (BuildKit) keeps the context to the files listed there,
# so .env, data/, .git and node_modules never reach the builder.
#
# Stage 1 builds dist/ with the pinned toolchain (mise.toml / CI: node 22.23.3, pnpm 10.34.7).
# VITE_API_BASE_URL is baked in at build time. Default: the web origin itself, because
# nginx (stage 2) reverse-proxies /v1/ and /health/ to the api container, so the browser
# talks same-origin and CORS never applies (see nginx/default.conf and the runbook).
FROM node:22.23.3-alpine AS build
ARG VITE_API_BASE_URL=http://127.0.0.1:3000
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_USE_MOCKS=false \
    CI=true
RUN corepack enable && corepack prepare pnpm@10.34.7 --activate
WORKDIR /src
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/package.json
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile
# contracts/ is read by the type-checked test files (tsc -b covers src/ and e2e/).
COPY contracts contracts
COPY apps/web apps/web
RUN pnpm --filter @beacon/web build \
    # The MSW worker is only for VITE_USE_MOCKS=true; never ship it.
    && rm -f apps/web/dist/mockServiceWorker.js

# Stage 2: nginx 1.27 running as the unprivileged `nginx` user (uid 101) on port 8080.
# nginx-unprivileged keeps pid and temp paths under /tmp, so the container runs with a
# read-only root filesystem plus a tmpfs on /tmp (docker-compose.yml).
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY apps/web/nginx/security-headers.conf /etc/nginx/conf.d/security-headers.inc
COPY --from=build /src/apps/web/dist /usr/share/nginx/html
EXPOSE 8080
