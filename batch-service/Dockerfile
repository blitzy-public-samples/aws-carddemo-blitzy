# syntax=docker/dockerfile:1
# =============================================================================
# batch-service/Dockerfile
#
# Container image for the CardDemo batch-service (Spring Boot 4.1.0 / Java 21) —
# the Spring Batch re-platforming of the legacy COBOL batch programs
# (CBACT01C-CBACT04C, CBCUS01C, CBTRN01C, CBTRN03C). Referenced by the root
# docker-compose.yml with build context ./batch-service.
#
# Because that build context is ./batch-service only, the Docker build sees
# neither the parent POM nor the sibling carddemo-common module and therefore
# cannot compile the Maven reactor in-container. Instead it consumes the
# executable boot jar produced by the HOST reactor build (`mvn clean install`
# at the repository root) in target/, exactly as documented for the
# `docker compose up --build` flow.
#
# The runtime base image is exactly eclipse-temurin:21-jre in every stage
# (AAP 0.5.1). Spring Boot 4.x removed the legacy `layertools` jarmode, so the
# layered extraction below uses the current `tools` jarmode with --launcher; it
# produces the identical exploded/JarLauncher runtime layout used for efficient,
# cache-friendly image layers (AAP 0.3.2 multi-stage).
# =============================================================================

# --- Stage 1 (builder): extract the Spring Boot layers from the boot jar -----
# Digest-pinned base image: the `21-jre` tag is mutable, so the digest is what
# makes this build reproducible and makes a base-image swap visible in review.
# The digest carries Temurin 21.0.11+10 (Adoptium publishes no 21.0.12 container
# image); the block below replaces that runtime with the GA 21.0.12+8 JRE, so the
# image ships the approved patch level. Rationale in docs/decision-log.md.
FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55 AS builder
WORKDIR /app

# Copy the single repackaged boot jar. The *.jar glob matches only
# batch-service-<version>.jar; the *.jar.original left by the repackage goal is
# not matched because its extension is .original, not .jar.
COPY target/*.jar app.jar

# Explode the jar into the standard layers (dependencies, spring-boot-loader,
# snapshot-dependencies, application) plus the launcher classes.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 2 (runtime): minimal JRE image running the exploded layers --------
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
LABEL org.opencontainers.image.title="carddemo-batch-service" \
      org.opencontainers.image.description="CardDemo batch service: interest calculation and data-management jobs (CBACT01C-CBACT04C/CBCUS01C)" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre" \
      org.opencontainers.image.base.digest="sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55"
WORKDIR /app

# Copy the extracted layers from least- to most-frequently-changing so Docker
# reuses the lower layers across rebuilds. Files are owned by UID/GID 1000: the
# eclipse-temurin base image already ships a non-root principal at 1000:1000,
# which docker-compose selects at runtime via `user: "1000:1000"`.
COPY --from=builder --chown=1000:1000 /app/extracted/dependencies/ ./
COPY --from=builder --chown=1000:1000 /app/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=1000:1000 /app/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=1000:1000 /app/extracted/application/ ./

# Runtime hardening: never run as root. Numeric form matches the compose
# `user: "1000:1000"` override and keeps a standalone `docker run` non-root too.
# Durable batch output root. docker-compose.yml mounts a NAMED VOLUME here (and the
# Kubernetes manifest a PersistentVolumeClaim) because the files written to it are
# DELIVERABLES a downstream consumer reads, not scratch. Creating the directory in the
# image - owned by the 1000:1000 runtime principal the deployment selects - is what
# makes a FRESH volume writable: Docker initialises an empty named volume from the
# ownership and mode of the image path it covers, and a root-owned 0755 directory would
# lock the non-root process out of its own output on first boot.
RUN mkdir -p /var/carddemo/batch/output /var/carddemo/batch/input \
    && chown -R 1000:1000 /var/carddemo \
    && chmod -R 0775 /var/carddemo

USER 1000:1000

# Container port. server.port=8080 is set in the service's application.yml; this
# EXPOSE documents the port that compose maps and health-checks, and that
# Prometheus scrapes (/actuator/health, /actuator/prometheus).
EXPOSE 8080

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

# `sh -c "exec java ..."` expands JAVA_OPTS, and `exec` replaces the shell so the
# JVM runs as PID 1 and receives SIGTERM directly, enabling Spring Boot graceful
# shutdown within the batch window (AAP 0.7.6). The launcher class (not `-jar`)
# is the entry point because the jar is extracted into layers above.
# Datasource/Redis settings are injected as environment variables by
# docker-compose; no secrets are baked into the image.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
