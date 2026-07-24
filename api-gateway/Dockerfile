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

FROM eclipse-temurin:21-jre

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
ENV JAVA_OPTS=""
ENV SERVER_PORT=8080

# Shell form expands $JAVA_OPTS; `exec` makes java PID 1 so it receives SIGTERM
# for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
