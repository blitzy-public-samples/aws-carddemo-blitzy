# =============================================================================
# billpay-service/Dockerfile
#
# Runtime image for the CardDemo Bill Payment microservice (migrated from the
# legacy CICS program COBIL00C / transaction CB00). Single-stage, runtime-only:
# it copies the pre-built Spring Boot executable jar and runs it on the Java 21
# LTS JRE.
#
# The docker-compose build context is ./billpay-service only, so the Maven
# reactor (parent pom + carddemo-common) is NOT available inside this build.
# Build the jar at the repository root first, then build the image:
#
#     mvn -pl billpay-service -am package
#     docker compose build billpay-service
#
# Runtime configuration (datasource, Redis, active profile) is injected by
# docker-compose via environment variables; nothing is hardcoded here.
# =============================================================================

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
LABEL org.opencontainers.image.title="carddemo-billpay-service" \
      org.opencontainers.image.description="CardDemo bill-payment service (COBIL00C)" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre" \
      org.opencontainers.image.base.digest="sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55"

# Least-privilege runtime account. A system group/user is used so the image
# never runs as root; useradd --system picks a free sub-1000 uid and therefore
# does not collide with the base image's default uid 1000 user.
RUN groupadd --system carddemo \
    && useradd --system --gid carddemo --home /app carddemo

WORKDIR /app

# Copy the repackaged Spring Boot executable (fat) jar. The version wildcard
# matches billpay-service-<version>.jar and deliberately excludes the thin
# billpay-service-<version>.jar.original left behind by the repackage goal.
COPY target/billpay-service-*.jar app.jar

USER carddemo

# Internal service port. docker-compose maps host 8086 -> container 8080,
# Prometheus scrapes :8080/actuator/prometheus, and the healthcheck hits
# :8080/actuator/health.
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

# `exec` replaces the shell so the JVM becomes PID 1 and receives SIGTERM for
# graceful shutdown; the surrounding shell expands $JAVA_OPTS at start-up.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
