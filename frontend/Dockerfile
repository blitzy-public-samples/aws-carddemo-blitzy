# syntax=docker/dockerfile:1
# =============================================================================
# frontend/Dockerfile
#
# Multi-stage build+serve image for the CardDemo React 19 single-page
# application (the modern presentation layer that replaces the 17 legacy BMS
# 3270 mapsets). Referenced by the root docker-compose.yml with build context
# ./frontend; the companion .dockerignore keeps that context small and correct.
#
#   Stage 1 (build):   compile the Vite production bundle on node:24.
#   Stage 2 (runtime): serve the static bundle with nginx and SPA history
#                      fallback so client-side routes resolve on deep links.
# =============================================================================

# --- Stage 1: build the Vite production bundle -------------------------------
# Digest-pinned build image so the bundler toolchain cannot drift between builds.
FROM node:24@sha256:934240a162082fd8b8a2f90cd5114446443f1eba1c5378f6687167ca405e6584 AS build

WORKDIR /app

# Install dependencies in their own layer, before copying sources, so the layer
# is reused until package.json / package-lock.json change. `npm ci` performs a
# clean, deterministic install from the committed lockfile.
COPY package*.json ./
RUN npm ci

# Copy the remaining build inputs. The host node_modules and dist are excluded
# by .dockerignore, so a fresh, platform-correct dependency tree is used.
COPY . .

# REST base URL baked into the client bundle at build time. Vite exposes
# VITE_-prefixed variables through import.meta.env; the ENV promotes the build
# ARG into the build process environment so `vite build` bakes it in. The
# default targets the local api-gateway; docker-compose overrides it (e.g. /api)
# for the same-origin, nginx-reverse-proxied deployment topology.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
# The sign-on screen's AppID:/SysID: fields (app/bms/COSGN00.bms APPLID/SYSID, each
# LENGTH=8). COSGN00C fills BOTH with EXEC CICS ASSIGN from the region serving the screen
# (L198-L203) before its first SEND, so neither is ever blank in front of an operator and
# the deployment has to supply both. CARDDEMO is the application's own identity in
# app/csd/CARDDEMO.CSD and fits either 8-character field exactly; a deployment that has a
# distinct region name overrides these build args with it.
ARG VITE_APP_ID=CARDDEMO
ENV VITE_APP_ID=$VITE_APP_ID
ARG VITE_SYS_ID=CARDDEMO
ENV VITE_SYS_ID=$VITE_SYS_ID

RUN npm run build

# Pre-compress the bundle. The runtime stage is stock nginx:alpine, built WITHOUT
# ngx_brotli, so nginx cannot Brotli-compress a response on the fly and every asset
# crossed the wire as gzip however good a Brotli the browser advertised. nginx CAN
# serve an already-compressed file, which is exactly what its bundled gzip_static
# module does for gzip, so the script writes a .br and a .gz sibling for every emitted
# .js and .css and frontend/nginx.conf selects between them per request. Running it
# here rather than per request also makes maximum effort affordable: Brotli quality 11
# and gzip level 9 execute once per build instead of on every hit. The script exits
# non-zero when the build output holds no script or stylesheet, so a change in the
# bundle's shape fails this build instead of silently shipping assets whose compressed
# siblings the served configuration expects to find.
RUN node scripts/precompress.mjs dist

# --- Stage 2: serve the static bundle with nginx -----------------------------
# Pinned to an explicit version AND digest: `alpine` is a floating tag that
# moves with every nginx release, so two builds of the same commit could ship
# different servers, and a version tag alone is still re-pointable upstream.
# 1.31.3 is the fixed release for the 1.29.x HTTP/3 and SSL-session advisories;
# see docs/decision-log.md for the upgrade rationale.
FROM nginx:1.31.3-alpine@sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752

# Static bundle emitted by the build stage (Vite outDir `dist`).
COPY --from=build /app/dist /usr/share/nginx/html

# Full main nginx configuration (declares the events{} and http{} contexts); it
# replaces the stock /etc/nginx/nginx.conf and provides the SPA history fallback
# plus the same-origin /api/ reverse proxy to the api-gateway.
COPY nginx.conf /etc/nginx/nginx.conf

# Opt in to the base image's 15-local-resolvers.envsh, which exports
# NGINX_LOCAL_RESOLVERS from the container's own /etc/resolv.conf. The start-up hook
# below prefers that list and falls back to reading /etc/resolv.conf itself, so the
# nginx `resolver` is materialized in both cases.
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1

# Start-up hook that materializes the nginx `resolver` nginx.conf includes, which is what
# lets the /api/ upstream be re-resolved per request instead of once at configuration
# load. The nginx image's entrypoint runs every executable /docker-entrypoint.d/*.sh
# before it execs nginx, so the mode bit is part of the contract and is set here rather
# than relying on the checkout's file mode.
COPY --chmod=0755 docker-entrypoint.d/15-carddemo-resolver.sh /docker-entrypoint.d/15-carddemo-resolver.sh

# OCI provenance metadata. Without it the image inherits the nginx base image's
# labels and `docker inspect` cannot attribute the artifact to this source or
# version.
ARG CARDDEMO_IMAGE_VERSION=1.0.0
ARG CARDDEMO_IMAGE_REVISION=unspecified
ARG CARDDEMO_IMAGE_CREATED=unspecified
LABEL org.opencontainers.image.title="carddemo-frontend" \
      org.opencontainers.image.description="CardDemo React 19 SPA (17 BMS 3270 screens) served by nginx" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="nginx:1.31.3-alpine" \
      org.opencontainers.image.base.digest="sha256:4a73073bd557c65b759505da037898b61f1be6cbcc3c2c3aeac22d2a470c1752"

# Unprivileged runtime. The nginx image starts its MASTER process as root and
# only drops the workers to the `nginx` user, so the container's PID 1 held uid 0
# with the whole static bundle and the proxy configuration in its address space.
# Declaring the image's own nginx account (uid/gid 101) as the runtime user means
# no process in this container ever holds uid 0; nginx.conf pairs with it by
# listening on 8080, keeping its pid and scratch paths under /tmp, and omitting
# the `user` directive a non-privileged master cannot honour.
#
# The base image's /docker-entrypoint.sh and the resolver hook below are
# uid-agnostic (the hook writes only under /tmp), so the start-up contract is
# unchanged. The bundle is copied read-only and needs no ownership change: it is
# world-readable and nginx only ever reads it.
USER 101:101

# nginx listens on the unprivileged container port 8080 (binding 80 would need
# CAP_NET_BIND_SERVICE, and every capability is dropped); the root
# docker-compose.yml maps the host port 3000 to it (3000:8080), and
# k8s/deployment-frontend.yaml declares the same containerPort.
EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
