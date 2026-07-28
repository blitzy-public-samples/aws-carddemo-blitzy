# syntax=docker/dockerfile:1
# =============================================================================
# CardDemo - Frontend (Next.js App Router + Material UI) container image.
#
# Traceability: greenfield target for the CardDemo COBOL -> modern migration
# (AAP 0.4.1 / 0.5.4 / 0.6.3). There is NO legacy source file for this artifact;
# it is authored new alongside the untouched legacy `app/` tree.
#
# Build context: the root docker-compose.yml declares
#   frontend.build.context = ./frontend  and  frontend.build.dockerfile = Dockerfile
# so this image is built with the frontend/ directory as its context and EVERY
# `COPY` path below is relative to frontend/ (e.g. `COPY package.json ./` copies
# frontend/package.json, and `COPY . .` copies the whole frontend/ tree).
#
# Runtime contract (must match docker-compose.yml + README):
#   * Serves the Next.js production server on 0.0.0.0:3000
#     (compose publishes ${FRONTEND_HOST_PORT:-3000}:3000; EXPOSE 3000 below).
#   * Uses Next.js "standalone" output - next.config.js sets output:'standalone',
#     so `next build` emits .next/standalone/server.js plus .next/static. The
#     runtime launches that server with `node server.js` (NOT `next start`) for
#     a minimal image that carries only the dependencies the server needs.
#   * NEXT_PUBLIC_API_URL is the ONLY configuration baked into the image. It is
#     PUBLIC by design: Next.js inlines every NEXT_PUBLIC_* value into the client
#     JavaScript bundle at build time, so it MUST be present before the build and
#     is necessarily visible in the browser. It holds the backend ORIGIN only
#     (no /api/v1 suffix - the axios client in src/lib/apiClient.ts appends that).
#     NEVER bake real secrets here (Ochs Rule #3): server-only secrets belong to
#     the backend's own configuration, never to a NEXT_PUBLIC_ variable.
#
# Node runtime: node:22-alpine across ALL stages. Node 22 is the active LTS and
# the project's declared runtime (package.json engines: ">=20"; the environment
# standardizes on Node 22.x). Pinning ONE major across every stage avoids
# native-module ABI mismatches (e.g. the SWC compiler Next.js relies on).
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1 - deps: resolve node_modules from the manifest ONLY.
#
# Copying just the manifest (and optional lockfile) before any source keeps this
# comparatively slow install layer cached: edits to application source do not
# invalidate it, so subsequent rebuilds reuse the resolved dependency tree.
# -----------------------------------------------------------------------------
FROM node:22-alpine AS deps
WORKDIR /app

# libc6-compat supplies the glibc compatibility shim that some native toolchains
# (notably the SWC binary Next.js uses) expect on musl-based Alpine. Installed
# defensively so native dependencies resolve reliably.
RUN apk add --no-cache libc6-compat

# Copy the manifest and the lockfile. package-lock.json is now TRACKED in the
# repository (QA finding M-08), so a clean checkout always carries it; the
# trailing `*` is retained only as a defensive no-op so the COPY never fails if a
# lockfile is somehow absent. The lockfile pins the full transitive tree, giving
# reproducible, locked installs.
COPY package.json package-lock.json* ./

# Reproducible LOCKED install: `npm ci` installs exactly the tree recorded in
# package-lock.json (and fails if it is out of sync with package.json). The
# fallback to `npm install` is kept only for the defensive no-lockfile edge case
# above. Written as a multi-line shell block per the Ochs formatting rule
# (loops/conditionals are always multi-line, 4-space indented).
RUN if [ -f package-lock.json ]; then \
        npm ci; \
    else \
        npm install; \
    fi


# -----------------------------------------------------------------------------
# Stage 2 - builder: compile the Next.js application into standalone output.
# -----------------------------------------------------------------------------
FROM node:22-alpine AS builder
WORKDIR /app

# Reuse the dependency tree resolved in the deps stage.
COPY --from=deps /app/node_modules ./node_modules

# Bring in the full frontend source (src/, next.config.js, tsconfig.json, ...).
COPY . .

# NEXT_PUBLIC_* values are INLINED into the client bundle during `next build`,
# so NEXT_PUBLIC_API_URL must exist as an ENV *before* the build runs. The
# default targets the host-published backend origin used under docker-compose;
# a different origin may be supplied at build time with
# `--build-arg NEXT_PUBLIC_API_URL=<origin>`. This value is public by design and
# is the backend ORIGIN only (no /api/v1 suffix). NEVER pass secrets this way.
ARG NEXT_PUBLIC_API_URL=http://localhost:8000
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}

# Opt out of Next.js anonymous telemetry during the image build.
ENV NEXT_TELEMETRY_DISABLED=1

# Guarantee a public/ directory exists so the runner's COPY of it never fails,
# even though this greenfield frontend currently ships no static assets. The
# build then emits .next/standalone and .next/static because next.config.js sets
# output:'standalone'.
RUN mkdir -p public \
    && npm run build


# -----------------------------------------------------------------------------
# Stage 3 - runner: minimal, non-root production runtime.
#
# Only the standalone server, the statically served assets, and public/ are
# copied here - NOT the full node_modules, because the standalone output already
# bundles the minimal dependency subset the server needs. The result is a small,
# fast-starting image with a reduced attack surface.
# -----------------------------------------------------------------------------
FROM node:22-alpine AS runner
WORKDIR /app

# OS security patches (QA finding I-01). node:22-alpine is pinned to a TAG
# (AAP 0.6.1), so the runner inherits whatever Alpine package versions were
# current when that tag was last published, accruing OS-level CVEs over time
# (musl, busybox, openssl/libcrypto, ...). `apk upgrade --no-cache` upgrades every
# ALREADY-INSTALLED package to the latest patched version in the pinned Alpine
# branch WITHOUT persisting the package index (--no-cache leaves no /var/cache/apk
# residue), adding NO new packages and keeping the runner minimal. Only the runner
# ships (the deps/builder stages are discarded by the multi-stage build), so this
# is the stage whose CVEs the production image actually carries. Residual base CVEs
# with no fixed Alpine package yet remain a base-image / pipeline concern.
RUN apk upgrade --no-cache

# Production runtime flags: enable production behavior in Next.js/React and keep
# telemetry disabled.
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

# Least privilege (Ochs Rule #3): create a dedicated non-root user and group for
# the runtime. BusyBox `addgroup`/`adduser` on Alpine accept these long options.
# `--ingroup nodejs` sets nextjs's PRIMARY group to nodejs (gid 1001); WITHOUT it
# BusyBox `adduser --system` defaults the primary group to `nogroup` (65533),
# leaving the copied application files group-owned by nogroup (QA finding I-02).
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 --ingroup nodejs nextjs

# Copy the standalone server (server.js + package.json + minimal node_modules) to
# the image root, then the static and public assets that standalone output does
# NOT embed. `--chown` hands ownership to the non-root runtime user so it can
# read its own files.
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

# Drop root for everything that follows.
USER nextjs

# Documented service port (compose publishes ${FRONTEND_HOST_PORT:-3000}:3000).
EXPOSE 3000

# The Next.js standalone server reads PORT and HOSTNAME. HOSTNAME=0.0.0.0 is
# REQUIRED so the server binds all interfaces and is reachable from outside the
# container; the implicit default (localhost) would accept in-container traffic
# only and make the published port unreachable.
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# -----------------------------------------------------------------------------
# Image-level container healthcheck (QA finding I-05: defense-in-depth).
#
# Mirrors the readiness probe the root docker-compose.yml already defines for
# this service, lifted into the IMAGE so a plain `docker run` (no compose) still
# gets a health signal; under compose the compose-level healthcheck overrides it.
# node:22-alpine ships BusyBox wget (no curl): `-q -O /dev/null` discards the body
# and wget exits non-zero on any HTTP error status -> unhealthy, or 0 on a 200 ->
# healthy. It probes /signon (a concrete 200 page) rather than `/`, which
# redirects to /signon. Host is 127.0.0.1 (NOT localhost): the standalone server
# binds IPv4 0.0.0.0 (HOSTNAME=0.0.0.0), while BusyBox wget would resolve
# `localhost` to IPv6 ::1 first and be refused. `--start-period` covers startup.
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=5 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:3000/signon"]

# Launch the standalone server directly. Exec form gives it PID 1 so it receives
# termination signals correctly. This is the standalone entrypoint, NOT
# `next start`.
CMD ["node", "server.js"]
