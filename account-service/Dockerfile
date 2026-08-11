# syntax=docker/dockerfile:1
# =============================================================================
# account-service/Dockerfile
#
# Runtime-only container image for the CardDemo account-service (Spring Boot
# 4.1.0) — the REST re-platforming of the legacy COBOL programs COACTVWC
# (account view, CICS CAVW) and COACTUPC (account update with optimistic
# locking, CICS CAUP) over the VSAM KSDS ACCTFILE / CUSTFILE.
#
# Referenced by the repository-root docker-compose.yml with
#   build: { context: ./account-service }
#
# BUILD ORDERING (required): this image copies the already-built Spring Boot
# executable jar from target/. Build the backend reactor first (it compiles
# carddemo-common, then this module), THEN build the image:
#
#   mvn clean install         # at the repository root -> target/account-service-*.jar
#   docker compose up --build # (or `docker build ./account-service`) after the jar exists
#
# An in-image Maven build is not used: the build context is ./account-service,
# which excludes the parent ../pom.xml and the sibling ../carddemo-common, so a
# reactor build could not resolve the parent BOM or the shared dependency.
#
# The image stays minimal and hardened: a JRE (no build tooling), a non-root
# runtime user, no baked-in credentials, and a health probe that depends on
# nothing beyond the shell (see the HEALTHCHECK below).
# =============================================================================

# Exact, pinned base image (AAP 0.5.1): the Eclipse Temurin 21 JRE. A JRE (not a
# JDK) keeps the image small; the tag is pinned and never `latest`.
# Digest-pinned base image: the `21-jre` tag is mutable, so the digest is what
# makes this build reproducible and makes a base-image swap visible in review.
# The digest carries Temurin 21.0.11+10 (Adoptium publishes no 21.0.12 container
# image); the block below replaces that runtime with the GA 21.0.12+8 JRE, so the
# image ships the approved patch level. Rationale in docs/decision-log.md.
FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55

# ---------------------------------------------------------------------------
# Java runtime patch level. The digest above is the newest eclipse-temurin
# `21-jre` image Adoptium publishes, and it carries Temurin 21.0.11+10: Docker
# Hub has no 21.0.12 tag at all, so a tag or digest swap cannot deliver the
# current patch level. The GA 21.0.12+8 JRE is therefore installed over the
# image's own runtime, straight from the Adoptium release asset and verified
# against its published SHA-256 before it is unpacked, so the image ships the
# approved patch level while keeping the same trusted base OS layer.
# Rationale: docs/decision-log.md.
# ---------------------------------------------------------------------------
ARG TEMURIN_JRE_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jre_x64_linux_hotspot_21.0.12_8.tar.gz
ARG TEMURIN_JRE_SHA256=8a379a67c91a3ae61ffb33d46e0a40c7ba35e70713c4db31cfca30492f792eff
ARG TEMURIN_JAVA_VERSION=jdk-21.0.12+8
RUN set -eux; \
    curl -fsSL "${TEMURIN_JRE_URL}" -o /tmp/temurin-jre.tar.gz; \
    printf '%s  %s\n' "${TEMURIN_JRE_SHA256}" /tmp/temurin-jre.tar.gz | sha256sum -c -; \
    rm -rf "${JAVA_HOME}"; \
    mkdir -p "${JAVA_HOME}"; \
    tar -xzf /tmp/temurin-jre.tar.gz -C "${JAVA_HOME}" --strip-components=1; \
    rm -f /tmp/temurin-jre.tar.gz; \
    java -version 2>&1 | grep -F "21.0.12+8"
# JAVA_VERSION is part of the base image's own contract and is what an operator
# (and the security gate) reads to identify the runtime, so it is restated here
# instead of being left describing the runtime that was replaced.
ENV JAVA_VERSION=${TEMURIN_JAVA_VERSION}

# Build metadata surfaced through the OCI labels below. Defaults keep a plain
# `docker build` working; docker-compose.yml and the CI build pass the real values.
ARG CARDDEMO_IMAGE_VERSION=1.0.0
ARG CARDDEMO_IMAGE_REVISION=unspecified
ARG CARDDEMO_IMAGE_CREATED=unspecified

# OCI provenance metadata. Without these the image inherits the BASE image's
# labels, so `docker inspect` reported every service as "ubuntu" 26.04 with
# Canonical's description - an image that cannot be attributed to the source it
# was built from, or to a version.
LABEL org.opencontainers.image.title="carddemo-account-service" \
      org.opencontainers.image.description="CardDemo account-service runtime image" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre" \
      org.opencontainers.image.base.digest="sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55"


# All runtime files live under /app.
WORKDIR /app

# Non-root runtime user: the JVM must never run as root. A dedicated system
# group/user owns the jar; the runtime drops to it via USER below. Container
# hardening only — no business behavior changes (AAP 0.7.6).
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy the pre-built Spring Boot fat jar. The build ARG decouples the image from
# the module version, so a version bump needs no Dockerfile edit. The glob
# resolves to the single repackaged executable jar (target/account-service-*.jar)
# and never the plain target/*.jar.original. Ownership is set to the non-root
# user that runs it.
ARG JAR_FILE=target/account-service-*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar

# Drop privileges for the runtime.
USER spring

# The service and its Actuator endpoints listen on 8080 inside the container;
# docker-compose routes to this port and health-checks /actuator/health here.
EXPOSE 8080

# Self-contained liveness probe for standalone `docker run` (docker-compose
# defines and thus overrides its own). The probe uses bash's /dev/tcp
# pseudo-device to issue a minimal HTTP request and assert the Actuator payload
# reports UP. This is NOT because the base image lacks an HTTP client -
# eclipse-temurin:21-jre is Ubuntu-based and ships curl 8.18.0, wget 1.25.0 and
# bash - but because asserting on the response BODY catches a 200 whose aggregate
# health status is DOWN, which a bare `curl -f` would accept.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && cat <&3 | grep -q UP'

# Container-aware memory and GC policy, and the compilation policy that decides how
# quickly this service reaches its steady-state speed. The value is IDENTICAL in all
# nine service images and resolves against the memory limit the runtime declares
# (docker-compose `deploy.resources.limits.memory`, Kubernetes
# `resources.limits.memory`), so one artifact has one footprint everywhere.
# Overridable at run time for a differently sized deployment.
#
# The Tier3/Tier4 thresholds are lowered from their defaults (which need thousands of
# invocations before a method is queued for the optimising compiler) because this
# deployment commits to a 95th percentile under 200 ms at 150 concurrent users from the
# moment readiness reports UP (AAP 0.7.1), not a minute later. Measured here: a freshly
# healthy service served the same traffic an order of magnitude slower than the same
# container minutes afterwards, purely because its request path was still interpreted or
# C1-compiled. CICompilerCount is raised to 4 so those compilations are actually
# performed while the readiness warm-up is driving the path and no request is competing
# for the CPU. The trade is more compiler work early and slightly less profile data per
# compilation, which is the correct trade for a service judged on its first requests.
# Measurements and rationale: docs/decision-log.md section 40.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:CICompilerCount=4 -XX:Tier3InvocationThreshold=100 -XX:Tier3MinInvocationThreshold=50 -XX:Tier3CompileThreshold=200 -XX:Tier4InvocationThreshold=500 -XX:Tier4MinInvocationThreshold=200 -XX:Tier4CompileThreshold=1000"

# Exec-form entrypoint: `sh -c "exec java ..."` expands JAVA_OPTS, and `exec`
# replaces the shell so the JVM becomes PID 1 and receives SIGTERM directly for
# graceful shutdown. Datasource, Redis, and the active profile are supplied via
# environment variables (docker-compose.yml / Kubernetes) and read by
# application.yml — nothing is hard-coded here.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
