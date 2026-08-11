# syntax=docker/dockerfile:1
# =============================================================================
# db/Dockerfile
#
# CardDemo PostgreSQL runtime image. It is the official postgres:18 image with
# the one component that carries the image's entire fixable-vulnerability
# surface removed.
#
# Why this image exists
# ---------------------
# The official postgres:18 image ships /usr/local/bin/gosu, a STATICALLY linked
# Go binary. Its embedded Go standard library is what a vulnerability scan
# reports against this image: every fixable CRITICAL/HIGH finding on postgres:18
# resolves to that single file (CVE-2025-68121 plus fourteen HIGH Go-stdlib
# advisories at the time of writing), and neither a newer postgres:18 digest nor
# the -alpine variant is free of it - both were scanned and both carry the same
# gosu build, so no upstream tag or digest can close the finding.
#
# gosu exists only to let a container that was STARTED as root drop to the
# postgres account: docker-entrypoint.sh reaches it in exactly one branch,
# `if [ "$(id -u)" = '0' ]; then exec gosu postgres "$BASH_SOURCE" "$@"`. This
# deployment never takes that branch - docker-compose.yml runs the container as
# `user: "999:999"` and k8s/deployment-postgres.yaml sets runAsUser: 999 - so the
# binary is unreachable code in both topologies. Removing it eliminates the
# findings outright instead of suppressing them, and removes a privilege-changing
# helper from an image that must never change privileges.
#
# Consequence to know about: this image can ONLY be started as a non-root user
# whose uid can read the data directory (999, the image's own postgres account).
# Started as root it would fail in the entrypoint rather than silently running
# the cluster as root - the safe direction for that mistake.
#
# Rationale and the scan evidence: docs/decision-log.md.
# =============================================================================

# Digest-pinned: the `18` tag is mutable, so the digest is what makes this build
# reproducible and makes a base swap visible in review. Kept in step with
# k8s/deployment-postgres.yaml, which pins the same digest.
FROM postgres:18@sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636

# Remove the unreachable privilege-dropping helper (see the header). The
# assertion that follows fails the build if a future base image relocates it, so
# the removal can never become a silent no-op.
RUN set -eux; \
    rm -f /usr/local/bin/gosu; \
    ! command -v gosu > /dev/null 2>&1

ARG CARDDEMO_IMAGE_VERSION=1.0.0
ARG CARDDEMO_IMAGE_REVISION=unspecified
ARG CARDDEMO_IMAGE_CREATED=unspecified
LABEL org.opencontainers.image.title="carddemo-postgres" \
      org.opencontainers.image.description="CardDemo PostgreSQL 18 image, hardened by removing the unreachable gosu setuid helper" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="postgres:18" \
      org.opencontainers.image.base.digest="sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636"

# Unchanged from the base image: uid 999 is the image's own postgres account, and
# it is the uid docker-compose.yml and the Kubernetes manifest both run as.
USER 999:999
