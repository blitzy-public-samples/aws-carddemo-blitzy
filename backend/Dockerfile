# syntax=docker/dockerfile:1
# =============================================================================
# CardDemo — Backend (FastAPI) container image.
#
# Traceability: greenfield target for the CardDemo COBOL -> modern migration
# (AAP 0.4.1 / 0.5.4 / 0.6.3). There is NO legacy source file for this artifact.
#
# Build context: the root docker-compose.yml declares
#   backend.build.context = ./backend  and  backend.build.dockerfile = Dockerfile
# so this Dockerfile is built with the backend/ directory as its context and
# EVERY `COPY` path below is relative to backend/ (e.g. `COPY requirements.txt .`
# copies backend/requirements.txt).
#
# Runtime contract (must match docker-compose.yml + README):
#   * Serves the FastAPI application factory result exported at module level in
#     backend/app/main.py, addressed as `app.main:app`.
#   * Listens on 0.0.0.0:8000 (compose maps ${BACKEND_HOST_PORT:-8000}:8000).
#   * All secrets/configuration (DATABASE_URL, SYNC_DATABASE_URL, SECRET_KEY,
#     ENVIRONMENT, ...) are supplied at container start via environment
#     variables / .env — NEVER baked into this image (Ochs Rule #3).
# =============================================================================
FROM python:3.13-slim

# -----------------------------------------------------------------------------
# Environment: predictable Python behavior in containers + quiet, cache-free pip.
#   PYTHONUNBUFFERED         flush stdout/stderr immediately (real-time logs).
#   PYTHONDONTWRITEBYTECODE  do not emit .pyc files into the read-only image.
#   PIP_NO_CACHE_DIR         keep the image slim (no pip wheel cache).
#   PIP_DISABLE_PIP_VERSION_CHECK  skip the "new pip available" network check.
# NOTE: no application secrets are declared here (Ochs Rule #3) — configuration
#       arrives at runtime from the environment, not from the image.
# -----------------------------------------------------------------------------
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# -----------------------------------------------------------------------------
# System dependencies: intentionally NONE.
#
# Every pinned dependency in requirements.txt ships self-contained cp313
# manylinux wheels, so no compiler or system libpq is required on python-slim:
#   * psycopg2-binary (2.9.10) bundles its own libpq in the wheel.
#   * asyncpg (0.30.0) implements the PostgreSQL wire protocol in a C extension
#     shipped as a wheel (no libpq needed).
#   * sqlalchemy / greenlet / bcrypt / reportlab all publish cp313 wheels.
#
# Keeping the image free of build tools reduces size and attack surface. If a
# future pin ever lacks a wheel, install build deps in ONE cleaned layer:
#   RUN apt-get update \
#       && apt-get install -y --no-install-recommends build-essential libpq-dev \
#       && rm -rf /var/lib/apt/lists/*
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Least privilege (Ochs Rule #3): create the dedicated non-root runtime user
# BEFORE copying any source, so every COPY below assigns ownership inline via
# `--chown`. Creating the user first is precisely what lets us DROP a separate
# `RUN chown -R appuser:appuser /app` step: that recursive chown previously
# rewrote the whole /app tree into a SECOND image layer, duplicating the COPY
# layer (~150 MB) for no runtime benefit (QA finding F1). With `--chown` the
# ownership is set as part of the single copy, so the source exists exactly once
# in the image. pip still installs as root (it writes to the system
# site-packages under /usr/local); appuser only needs read/execute on those
# packages at runtime, which the default permissions already grant.
# -----------------------------------------------------------------------------
RUN useradd --create-home --uid 1000 appuser

# -----------------------------------------------------------------------------
# Dependency layer (cache-friendly): copy ONLY the manifest and install first,
# BEFORE the application source. Application code changes then do not invalidate
# this (slow) pip layer, so rebuilds reuse the cached dependencies. Installed as
# root into the system site-packages and read by appuser at runtime.
# -----------------------------------------------------------------------------
COPY --chown=appuser:appuser requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# -----------------------------------------------------------------------------
# Application source. Copies the backend build context (app/, alembic/,
# alembic.ini, pyproject.toml, ...) into /app with appuser ownership applied
# inline by `--chown`. After this:
#   * `app.main:app` resolves (source at /app/app/main.py), and
#   * `alembic` is runnable from /app (where alembic.ini lives).
#
# The build context is trimmed by `backend/.dockerignore` (QA findings F1/F2):
# it excludes the host virtual environment (.venv/), byte-code and tool caches
# (__pycache__/, .ruff_cache/, .pytest_cache/), the test suite (tests/), and any
# secret .env file — so none of them ever enter an image layer. Excluding every
# `.env*` structurally prevents a build-context secret from being baked into the
# image (Ochs Rule #3: configuration arrives from the runtime environment).
# -----------------------------------------------------------------------------
COPY --chown=appuser:appuser . .

# Switch to the non-root user for the remainder of the build and for runtime.
USER appuser

# Documented service port (compose publishes ${BACKEND_HOST_PORT:-8000}:8000).
EXPOSE 8000

# -----------------------------------------------------------------------------
# Default command (exec form for correct PID 1 signal handling).
# Serves the FastAPI app factory result `app.main:app` on 0.0.0.0:8000 — this
# matches docker-compose.yml and the README exactly.
#
#   * No `--reload`: hot reload is a local/compose-override concern, not a
#     production image default.
#   * Migrations are NOT run here. Schema migrations are a separate, explicit
#     compose step (e.g. `docker compose run --rm backend alembic upgrade head`)
#     so image startup stays idempotent and side-effect free.
# -----------------------------------------------------------------------------
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
