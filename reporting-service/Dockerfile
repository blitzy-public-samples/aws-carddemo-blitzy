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
FROM maven:3.9.16-eclipse-temurin-21 AS build

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
FROM eclipse-temurin:21-jre

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

USER spring

# Container-aware memory and GC policy. The value is IDENTICAL in all nine
# service images and resolves against the memory limit the runtime declares
# (docker-compose `deploy.resources.limits.memory`, Kubernetes
# `resources.limits.memory`), so one artifact has one footprint everywhere.
# Overridable at run time for a differently sized deployment.
# Measurements and rationale: docs/decision-log.md section 40.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# `sh -c "exec java ..."` expands JAVA_OPTS, and `exec` replaces the shell so the
# JVM is PID 1 and receives SIGTERM directly for a graceful Spring Boot shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
