# syntax=docker/dockerfile:1
# =============================================================================
# reporting-service/Dockerfile
#
# Container image for the CardDemo reporting-service Spring Boot 4.1.0
# microservice (report requests <- CORPT00C; batch statement generation
# <- CBSTM03A / CBSTM03B). It runs the executable Spring Boot fat jar produced
# by reporting-service/pom.xml (spring-boot-maven-plugin:repackage).
#
# BUILD CONTEXT MUST BE THE REPOSITORY ROOT.
# reporting-service is not self-contained: it inherits the root parent POM and
# depends on the sibling module carddemo-common, so the build stage needs the
# whole reactor (root pom.xml + carddemo-common/ + reporting-service/). Build
# from the repository root, pointing at this file explicitly:
#     docker build -f reporting-service/Dockerfile -t carddemo/reporting-service .
# The matching docker-compose.yml service build entry must therefore be:
#     build:
#       context: .
#       dockerfile: reporting-service/Dockerfile
# (A service-local "context: ./reporting-service" cannot see ../pom.xml or the
# carddemo-common sibling and will fail — the compose entry is coordinated to
# root context alongside this file.)
#
# A repository-root .dockerignore now keeps this root context small: it excludes
# "**/target", "**/node_modules", ".git", the local environment files and the
# retained legacy app/ sources, none of which this build reads. The nine
# module-context builds carry their own <module>/.dockerignore.
#
# The service listens on container port 8080 so Prometheus can scrape
# /actuator/prometheus and the Actuator /actuator/health endpoint backs the
# compose / Kubernetes probes. No Dockerfile HEALTHCHECK is declared here because
# health is owned by the docker-compose bash /dev/tcp probe and the Kubernetes
# readiness/liveness probes, which would override an image-level one anyway. (For
# the record, the eclipse-temurin:21-jre base IS Ubuntu-based and does ship curl
# 8.18.0, wget 1.25.0 and bash; the absence of a HEALTHCHECK is not a tooling
# limitation.) Rationale for non-trivial choices lives in docs/decision-log.md
# (Docstring and Comments rule, AAP 0.7.2).
# =============================================================================

# --- Stage 1: build the executable fat jar with the Maven reactor ------------
# Apache Maven 3.9.16 on Eclipse Temurin (JDK 21), pinned per AAP 0.5.1.
# Digest-pinned build image so the compiler/toolchain cannot drift between builds.
FROM maven:3.9.16-eclipse-temurin-21@sha256:c07f7ccfb8ca6c9fa29ee523f00afa7d2ca6132c92f8652c4aebb5ee3491f502 AS build

WORKDIR /build

# Copy the aggregator POM plus EVERY module POM first, so the reactor model can
# be built and the dependency-resolution layer is cached until a POM changes.
# The root pom.xml is an aggregator that lists all ten modules, so all of their
# POMs must be present for Maven to load the reactor — even though
# "-pl reporting-service -am" only BUILDS this module and its upstream
# dependency (carddemo-common). Sources for the other modules are never copied.
COPY pom.xml ./
COPY carddemo-common/pom.xml ./carddemo-common/
COPY auth-service/pom.xml ./auth-service/
COPY user-service/pom.xml ./user-service/
COPY account-service/pom.xml ./account-service/
COPY card-service/pom.xml ./card-service/
COPY transaction-service/pom.xml ./transaction-service/
COPY billpay-service/pom.xml ./billpay-service/
COPY reporting-service/pom.xml ./reporting-service/
COPY batch-service/pom.xml ./batch-service/
COPY api-gateway/pom.xml ./api-gateway/
RUN mvn -B -ntp -pl reporting-service -am -DskipTests dependency:go-offline

# Copy the sources for the two reactor modules that are actually built. The
# other modules keep only their POM (required for the reactor) and are never
# built into this image.
COPY carddemo-common/ ./carddemo-common/
COPY reporting-service/ ./reporting-service/

# Build reporting-service and also-make (-am) its dependency carddemo-common.
# Tests are skipped in the image build (they run separately in CI via mvn test);
# spring-boot-maven-plugin:repackage produces the executable fat jar.
RUN mvn -B -ntp -pl reporting-service -am -DskipTests clean package

# --- Stage 2: slim runtime image ---------------------------------------------
# Runtime base pinned to eclipse-temurin:21-jre per AAP 0.5.1 (Service base
# image). JRE-only keeps the image small.
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
LABEL org.opencontainers.image.title="carddemo-reporting-service" \
      org.opencontainers.image.description="CardDemo report-request and statement-generation service (CORPT00C/CBSTM03A)" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${CARDDEMO_IMAGE_VERSION}" \
      org.opencontainers.image.revision="${CARDDEMO_IMAGE_REVISION}" \
      org.opencontainers.image.created="${CARDDEMO_IMAGE_CREATED}" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.base.name="eclipse-temurin:21-jre" \
      org.opencontainers.image.base.digest="sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55"

# Run as a dedicated non-root system user (security best practice; the image
# never runs as root). The docker-compose.yml backend anchor additionally pins
# "user: 1000:1000" and a read-only root filesystem at runtime, and the copied
# jar is world-readable, so it is readable under any runtime UID.
RUN groupadd --system spring \
    && useradd --system --gid spring spring

WORKDIR /app

# Copy ONLY the executable fat jar from the build stage (default finalName; the
# "*.jar" glob avoids pinning the version and matches exactly the repackaged
# jar — the thin jar is renamed to *.jar.original and is not matched).
COPY --from=build --chown=spring:spring /build/reporting-service/target/*.jar /app/app.jar

# REST + Actuator (health / readiness / liveness / prometheus) listen on 8080.
EXPOSE 8080

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

USER spring

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
# JVM is PID 1 and receives SIGTERM directly for a graceful Spring Boot shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
