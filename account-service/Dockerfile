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
FROM eclipse-temurin:21-jre

# Concise OCI image metadata.
LABEL org.opencontainers.image.title="carddemo-account-service" \
      org.opencontainers.image.description="CardDemo account view and optimistic-lock update service (COACTVWC/COACTUPC)" \
      org.opencontainers.image.vendor="CardDemo" \
      org.opencontainers.image.licenses="Apache-2.0"

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
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && cat <&3 | grep -q UP'

# Container-aware memory and GC policy. The value is IDENTICAL in all nine
# service images and resolves against the memory limit the runtime declares
# (docker-compose `deploy.resources.limits.memory`, Kubernetes
# `resources.limits.memory`), so one artifact has one footprint everywhere.
# Overridable at run time for a differently sized deployment.
# Measurements and rationale: docs/decision-log.md section 40.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Exec-form entrypoint: `sh -c "exec java ..."` expands JAVA_OPTS, and `exec`
# replaces the shell so the JVM becomes PID 1 and receives SIGTERM directly for
# graceful shutdown. Datasource, Redis, and the active profile are supplied via
# environment variables (docker-compose.yml / Kubernetes) and read by
# application.yml — nothing is hard-coded here.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
