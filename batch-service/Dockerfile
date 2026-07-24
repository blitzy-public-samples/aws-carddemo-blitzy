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
FROM eclipse-temurin:21-jre AS builder
WORKDIR /app

# Copy the single repackaged boot jar. The *.jar glob matches only
# batch-service-<version>.jar; the *.jar.original left by the repackage goal is
# not matched because its extension is .original, not .jar.
COPY target/*.jar app.jar

# Explode the jar into the standard layers (dependencies, spring-boot-loader,
# snapshot-dependencies, application) plus the launcher classes.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# --- Stage 2 (runtime): minimal JRE image running the exploded layers --------
FROM eclipse-temurin:21-jre
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
USER 1000:1000

# Container port. server.port=8080 is set in the service's application.yml; this
# EXPOSE documents the port that compose maps and health-checks, and that
# Prometheus scrapes (/actuator/health, /actuator/prometheus).
EXPOSE 8080

# Exec form: the JVM runs as PID 1 and receives SIGTERM directly, enabling
# Spring Boot graceful shutdown within the batch window (AAP 0.7.6). Additional
# JVM flags can be supplied at runtime through the standard JAVA_TOOL_OPTIONS
# environment variable, which the JVM honors automatically (no shell wrapper,
# so signal handling is preserved). Datasource/Redis settings are injected as
# environment variables by docker-compose; no secrets are baked into the image.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
