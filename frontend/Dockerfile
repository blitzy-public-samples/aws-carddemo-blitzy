# =============================================================================
# CardDemo Frontend Dockerfile
# =============================================================================
# Multi-stage Docker build for React 18 frontend application
# 
# Purpose:
#   Containerize the React 18 single-page application that replaces 17 legacy
#   BMS 3270 terminal screens with a modern web UI. This multi-stage build
#   creates an optimized production image using Node.js 20 Alpine for building
#   and Nginx 1.25 Alpine for serving static assets.
#
# Architecture:
#   Stage 1 (builder): Node.js 20 Alpine - Install dependencies and build with Vite
#   Stage 2 (production): Nginx 1.25 Alpine - Serve static files with SPA routing
#
# Features:
#   - Multi-stage build for minimal production image size (~50MB vs ~500MB)
#   - Node.js 20 LTS with Vite 5.0.11 for fast production builds
#   - Nginx 1.25 for high-performance static file serving
#   - SPA routing with fallback to index.html
#   - Gzip compression for JS/CSS/HTML/JSON assets
#   - Security headers (X-Frame-Options, CSP, X-Content-Type-Options)
#   - API reverse proxy to backend service
#   - Health check endpoint for Kubernetes orchestration
#   - Non-root user execution for security compliance
#   - Cache control optimization for static assets
#
# Build Requirements (from Agent Action Plan Section 0.5 & 0.7):
#   - Docker 24.x
#   - Node.js 20 LTS
#   - npm 10.x
#   - React 18.2.0
#   - Vite 5.0.11
#
# Build Arguments:
#   NODE_ENV: Environment mode (default: production)
#   REACT_APP_API_URL: Backend API base URL for build-time substitution
#
# Build Command:
#   docker build -t carddemo-frontend:latest .
#   docker build -t carddemo-frontend:latest \
#     --build-arg REACT_APP_API_URL=http://api.example.com .
#
# Run Command:
#   docker run -p 3000:3000 \
#     -e BACKEND_API_URL=http://backend:8080 \
#     carddemo-frontend:latest
#
# References:
#   - Agent Action Plan Section 0.3: Infrastructure & Deployment Layer
#   - Agent Action Plan Section 0.5: Technology Stack Specifications
#   - Agent Action Plan Section 0.6: Configuration Management
# =============================================================================

# =============================================================================
# Stage 1: Build Stage
# =============================================================================
# Use Node.js 20 Alpine as base image for minimal footprint
# Alpine Linux provides a minimal container image (~5MB base)
FROM node:20-alpine AS builder

# Set build-time environment variables
ARG NODE_ENV=production
ARG REACT_APP_API_URL=/api

ENV NODE_ENV=${NODE_ENV}
ENV REACT_APP_API_URL=${REACT_APP_API_URL}

# Set working directory for build
WORKDIR /app

# Copy package files first for better layer caching
# Docker caches layers - dependencies change less frequently than source code
COPY package.json package-lock.json ./

# Install dependencies using clean install for reproducibility
# npm ci provides faster, more reliable builds than npm install
# --silent reduces build log verbosity
# --only=production would skip devDependencies, but we need build tools
RUN npm ci --silent

# Copy source code and configuration files
COPY public ./public
COPY src ./src
COPY index.html ./
COPY vite.config.js ./

# Build production bundle with Vite
# Vite performs tree-shaking, minification, and code splitting
# Output directory: /app/dist (Vite default)
RUN npm run build

# Optional: Verify build output
RUN ls -la /app/dist

# =============================================================================
# Stage 2: Production Stage
# =============================================================================
# Use Nginx Alpine as base image for serving static files
# Nginx provides high-performance HTTP server in minimal footprint (~25MB)
FROM nginx:1.25-alpine

# Add metadata labels for container registry
LABEL maintainer="CardDemo Team"
LABEL description="CardDemo React Frontend - Migrated from COBOL/CICS BMS screens"
LABEL version="1.0.0"

# Install wget for health checks (not included in Alpine by default)
RUN apk add --no-cache wget

# Copy custom Nginx configuration for SPA routing and security
# This configuration handles:
# - SPA routing (all routes fallback to index.html)
# - Gzip compression
# - Security headers
# - API reverse proxy
# - Cache control
# - Health check endpoint
COPY nginx.conf /etc/nginx/nginx.conf

# Copy built static files from builder stage
# Source: /app/dist from builder stage
# Destination: /usr/share/nginx/html (Nginx default document root)
COPY --from=builder /app/dist /usr/share/nginx/html

# Create non-root user for security compliance
# Running containers as non-root is a security best practice
# Create appgroup and appuser with minimal privileges
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Change ownership of Nginx directories to non-root user
# Required directories for Nginx operation:
# - /usr/share/nginx/html: Document root for static files
# - /var/cache/nginx: Nginx cache directory
# - /var/log/nginx: Nginx log directory
# - /var/run: PID file location
RUN chown -R appuser:appgroup /usr/share/nginx/html && \
    chown -R appuser:appgroup /var/cache/nginx && \
    chown -R appuser:appgroup /var/log/nginx && \
    touch /var/run/nginx.pid && \
    chown -R appuser:appgroup /var/run/nginx.pid

# Set proper permissions for static files
# 755: Owner can read/write/execute, group and others can read/execute
RUN chmod -R 755 /usr/share/nginx/html

# Switch to non-root user
# All subsequent commands and container runtime will use this user
USER appuser

# Expose port 3000 for frontend application
# This is the default port configured in nginx.conf
# Can be mapped to different host port: docker run -p 8080:3000
EXPOSE 3000

# Health check for Kubernetes readiness and liveness probes
# Kubernetes uses this to determine container health
# - interval: Check every 30 seconds
# - timeout: Fail if no response within 3 seconds
# - start-period: Wait 10 seconds before first check (allows startup time)
# - retries: Mark unhealthy after 3 consecutive failures
# Endpoint /health returns 200 OK (configured in nginx.conf)
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:3000/health || exit 1

# Start Nginx in foreground mode
# -g "daemon off;" prevents Nginx from daemonizing
# Docker requires the main process to run in foreground
CMD ["nginx", "-g", "daemon off;"]

# =============================================================================
# Image Size Optimization Summary
# =============================================================================
# Builder stage: ~500MB (Node.js + npm dependencies + source code)
# Production stage: ~50MB (Nginx + static files only)
# Multi-stage build eliminates:
# - Node.js runtime (~180MB)
# - npm dependencies (~200MB)
# - Source code (~10MB)
# - Build tools and cache (~100MB)
#
# Security Features:
# - Minimal attack surface (Alpine Linux base)
# - No Node.js runtime in production (eliminates Node.js vulnerabilities)
# - Non-root user execution (prevents privilege escalation)
# - Security headers (XSS, clickjacking, MIME-sniffing protection)
# - Health checks (enables container orchestration)
#
# Performance Features:
# - Nginx high-performance static file serving
# - Gzip compression (reduces bandwidth by ~70%)
# - Efficient caching (1 year for immutable assets, no-cache for index.html)
# - SPA routing (client-side navigation without server round-trips)
#
# Production Readiness:
# - Kubernetes-compatible health checks
# - Configurable API backend via environment variables
# - Proper error pages and logging
# - CORS support via Nginx configuration
# =============================================================================
