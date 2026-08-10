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
# Digest resolves to Temurin 21.0.11+10, the newest published 21-jre build
# (Adoptium has no 21.0.12 container image); rationale in docs/decision-log.md.
FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55

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

# Container-aware memory and GC policy. The value is IDENTICAL in all nine
# service images and resolves against the memory limit the runtime declares
# (docker-compose `deploy.resources.limits.memory`, Kubernetes
# `resources.limits.memory`), so one artifact has one footprint everywhere.
# Overridable at run time for a differently sized deployment.
# Measurements and rationale: docs/decision-log.md section 40.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# `exec` replaces the shell so the JVM becomes PID 1 and receives SIGTERM for
# graceful shutdown; the surrounding shell expands $JAVA_OPTS at start-up.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
