# =============================================================================
# CardDemo - multi-stage container image
# -----------------------------------------------------------------------------
# Functionally-equivalent Java 25 (LTS) + Spring Boot 3.5.x re-platform of the
# AWS CardDemo mainframe application (migrated from COBOL / CICS / VSAM / JCL).
#
# This image is produced in two stages:
#
#   1. build   - Compiles the application with the pinned Maven Wrapper on a
#                Java 25 (Eclipse Temurin) JDK and produces the executable
#                Spring Boot "fat" jar: target/carddemo-1.0.0.jar
#                (pom.xml: artifactId=carddemo, version=1.0.0,
#                 finalName=${artifactId}-${version}).
#
#   2. runtime - Runs that jar on a slim Java 25 (Eclipse Temurin) JRE as an
#                unprivileged (non-root) user. No build tooling is shipped.
#
# SECURITY: no secrets are baked into any layer. Every credential/connection
# value (DB_URL, DB_USERNAME, DB_PASSWORD) and the active Spring profile
# (SPRING_PROFILES_ACTIVE) is injected at runtime via environment variables -
# see docker-compose.yml and the README "Run locally" section.
#
# Build:
#     docker build -t carddemo:latest .
#
# Run (all configuration supplied at runtime; nothing sensitive in the image):
#     docker run --rm -p 8080:8080 \
#       -e SPRING_PROFILES_ACTIVE=local \
#       -e DB_URL=jdbc:postgresql://postgres:5432/carddemo \
#       -e DB_USERNAME="$DB_USERNAME" \
#       -e DB_PASSWORD="$DB_PASSWORD" \
#       carddemo:latest
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
# Eclipse Temurin 25 (LTS) JDK. The `25-jdk` tag tracks the latest 25.x patch
# (25.0.3+9 at time of writing, matching the toolchain used for local builds).
# For byte-for-byte base-image reproducibility, pin to the exact patch tag
# instead, e.g. `eclipse-temurin:25.0.3_9-jdk`.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# ---- Dependency layer (cached) ---------------------------------------------
# Copy ONLY the Maven Wrapper and the POM first, so Docker can cache the (large)
# dependency-resolution layer and reuse it on every subsequent build in which
# only application sources - not pom.xml - have changed.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Make the wrapper executable (some checkouts drop the +x bit) and warm the
# local Maven repository from the POM. `dependency:go-offline` is an OPTIONAL
# cache optimisation only: the authoritative dependency download happens in the
# package step below (which is NOT run offline), so an incomplete pre-fetch here
# can never break the build.
RUN chmod +x ./mvnw \
 && ./mvnw -B -q dependency:go-offline

# ---- Application layer ------------------------------------------------------
# Copy the sources last and build the executable jar. Tests are intentionally
# skipped in the image build - they run in CI (`./mvnw -B clean verify`) against
# a real PostgreSQL 16 via Testcontainers - which keeps the image build fast and
# free of any database dependency.
COPY src/ src/
RUN ./mvnw -B -DskipTests clean package


# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
# Slim Java 25 (LTS) JRE - runtime only, no compiler or build tooling.
FROM eclipse-temurin:25-jre AS runtime

# OCI image metadata (informational; no runtime effect).
LABEL org.opencontainers.image.title="CardDemo" \
      org.opencontainers.image.description="Java 25 + Spring Boot 3 re-platform of the AWS CardDemo mainframe credit-card account management system" \
      org.opencontainers.image.vendor="Amazon.com, Inc. or its affiliates" \
      org.opencontainers.image.licenses="Apache-2.0"

# Run as an unprivileged, system (no-login) account - never as root. A fixed
# UID/GID keeps behaviour deterministic and plays well with Kubernetes
# `runAsUser` / read-only root filesystem policies.
RUN groupadd --system --gid 1001 carddemo \
 && useradd  --system --uid 1001 --gid carddemo \
             --home-dir /app --no-create-home --shell /usr/sbin/nologin carddemo

WORKDIR /app

# Copy ONLY the built artifact from the build stage. The glob resolves to a
# single file, carddemo-1.0.0.jar (the Spring Boot repackage replaces the main
# jar; the plain jar is renamed carddemo-1.0.0.jar.original, which the `*.jar`
# glob does not match). Ownership is handed to the non-root runtime user.
COPY --from=build --chown=carddemo:carddemo /app/target/carddemo-*.jar /app/app.jar

# Drop privileges for everything that follows.
USER carddemo

# Spring Boot (embedded Tomcat) serves HTTP and the Actuator endpoints
# (/actuator/health, /actuator/prometheus) on port 8080 by default.
EXPOSE 8080

# ---- Runtime configuration (env-driven; NO secrets baked into the image) ----
# Sensible, container-aware JVM defaults. On Java 10+ the JVM is container-aware
# by default (UseContainerSupport is on), so the heap is sized from the
# container's memory limit; failing fast on OutOfMemoryError lets the
# orchestrator restart the container cleanly. The JVM reads JAVA_TOOL_OPTIONS
# automatically, so callers can append/override flags at runtime without
# changing the entrypoint.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# The variables below are supplied by docker-compose / the orchestrator at run
# time and are intentionally given NO default values here - secrets must never
# be baked into an image layer:
#   SPRING_PROFILES_ACTIVE  - active Spring profile, e.g. "local"
#   DB_URL                  - JDBC URL, e.g. jdbc:postgresql://postgres:5432/carddemo
#   DB_USERNAME             - database user (supplied at runtime)
#   DB_PASSWORD             - database password (supplied at runtime; never committed)

# Exec form -> `java` runs as PID 1 and receives SIGTERM directly, enabling
# Spring Boot's graceful shutdown. JAVA_TOOL_OPTIONS (above) is applied
# automatically by the JVM.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
