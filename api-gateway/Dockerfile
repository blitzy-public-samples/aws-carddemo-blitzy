# syntax=docker/dockerfile:1
# =============================================================================
# api-gateway/Dockerfile — CardDemo API Gateway runtime image
#
# Runtime-only image for the api-gateway Spring Boot service. Referenced by the
# root docker-compose.yml (build context ./api-gateway). The AAP-mandated base
# eclipse-temurin:21-jre carries no Maven/JDK, so this image does NOT compile —
# it only packages the already-built Spring Boot fat jar.
#
# BUILD PREREQUISITE: run the reactor build first so target/*.jar exists, e.g.
#   mvn -pl api-gateway -am clean package      (builds carddemo-common then this)
# THEN:
#   docker compose build api-gateway           (or: docker build ./api-gateway)
# If target/*.jar is absent the COPY below fails by design (documents the order).
#
# PORT: the service listens on 8080 inside the container (compose maps 8080:8080).
# HEALTH: the Dockerfile intentionally defines NO HEALTHCHECK — the root
#   docker-compose.yml owns the liveness/readiness probe against Spring Boot
#   Actuator /actuator/health (also /actuator/prometheus), which is configured
#   in src/main/resources/application.yml. Keeping a single source of truth also
#   keeps this image lean.
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
LABEL org.opencontainers.image.title="carddemo-api-gateway" \
      org.opencontainers.image.description="CardDemo API gateway and role-gated menu routing (COMEN01C/COADM01C)" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre" \
      org.opencontainers.image.base.digest="sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55"

# Dedicated non-root system account to run the service (defense in depth). UID
# 1000 is reserved by the base image's built-in `ubuntu` user, so --system is
# used; compose may still override the runtime user to 1000:1000 and reads the
# jar via its world-readable bit.
RUN groupadd --system carddemo \
    && useradd --system --gid carddemo --home /app --shell /usr/sbin/nologin carddemo

WORKDIR /app

# Copy the repackaged Spring Boot fat jar produced by this module
# (spring-boot-maven-plugin:repackage). The wildcard resolves to the single
# bootable jar in api-gateway/target/. --chown grants the non-root user
# ownership without an extra image layer.
ARG JAR_FILE=target/*.jar
COPY --chown=carddemo:carddemo ${JAR_FILE} app.jar

USER carddemo

EXPOSE 8080

# JAVA_OPTS is an empty hook so heap/GC/flags can be tuned at deploy time
# without rebuilding. SERVER_PORT keeps the Spring Boot port aligned with EXPOSE
# and the compose mapping.
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
# DNS caching. This image is the only one that resolves a name which can hold SEVERAL
# addresses and can lose one at any moment: a downstream service scaled to replicas
# (`docker compose up --scale`, a Kubernetes Deployment). The JVM caches a successful
# lookup for 30 s by default, which OVERRIDES the shorter refresh interval
# DnsServiceInstanceListSupplier applies - it would keep handing back a stopped
# replica's address for up to half a minute, and every request the load balancer sent
# there would fail. Two seconds matches that supplier's own refresh interval.
#
# Both spellings are set deliberately: `networkaddress.cache.ttl` is read as a SECURITY
# property, honoured as a system property only on JDKs that support the alias, while
# `sun.net.inetaddr.ttl` is the system-property fallback InetAddressCachePolicy reads on
# every JDK. Negative lookups are not cached at all, so a replica that is starting is
# picked up as soon as its name resolves.
ENV JAVA_OPTS="-Dnetworkaddress.cache.ttl=2 -Dsun.net.inetaddr.ttl=2 -Dnetworkaddress.cache.negative.ttl=0 -Dsun.net.inetaddr.negative.ttl=0 -XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:CICompilerCount=4 -XX:Tier3InvocationThreshold=100 -XX:Tier3MinInvocationThreshold=50 -XX:Tier3CompileThreshold=200 -XX:Tier4InvocationThreshold=500 -XX:Tier4MinInvocationThreshold=200 -XX:Tier4CompileThreshold=1000"
ENV SERVER_PORT=8080

# Shell form expands $JAVA_OPTS; `exec` makes java PID 1 so it receives SIGTERM
# for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
