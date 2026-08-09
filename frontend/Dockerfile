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
FROM node:24 AS build

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

# --- Stage 2: serve the static bundle with nginx -----------------------------
# Pinned to an explicit version: `alpine` is a floating tag that
# moves with every nginx release, so two builds of the same commit could ship
# different servers.
FROM nginx:1.29-alpine

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

# nginx listens on container port 80; the root docker-compose.yml maps the host
# port (3000) to this container port (3000:80).
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
