# syntax=docker/dockerfile:1
# =============================================================================
# user-service/Dockerfile
#
# Runtime image for the CardDemo User Management microservice: the Spring Boot
# 4.1.0 re-platforming of the legacy COBOL programs COUSR00C / COUSR01C /
# COUSR02C / COUSR03C (CICS transactions CU00-CU03, VSAM KSDS USRSEC).
#
# The root docker-compose.yml builds this image with build context
# ./user-service, so the sibling carddemo-common source is not in the context
# and the Maven reactor cannot be compiled here. The pre-built Spring Boot
# executable jar (produced by `mvn clean install` at the repository root,
# finalName=user-service) is copied from this module's target/ directory.
# See docs/decision-log.md.
# =============================================================================
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="carddemo-user-service" \
      org.opencontainers.image.description="CardDemo administrator-only user management microservice" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

# Dedicated non-root system account (UID stays below the base image's ubuntu/1000).
RUN groupadd --system spring && useradd --system --gid spring spring

# Repackaged executable boot jar (finalName=user-service), copied from target/.
# Mode 0644 keeps it world-readable so it also runs under an arbitrary runtime
# UID (compose launches the backends as user 1000:1000).
ARG JAR_FILE=target/user-service.jar
COPY --chown=spring:spring --chmod=0644 ${JAR_FILE} app.jar

USER spring:spring

# Internal HTTP port (server.port in application.yml); compose maps a host port.
EXPOSE 8080

# Curl-free health probe over bash's /dev/tcp; asserts the Actuator payload is UP.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /actuator/health HTTP/1.0\r\n\r\n" >&3 && cat <&3 | grep -q UP'

# Container-aware memory and GC policy. The value is IDENTICAL in all nine
# service images and resolves against the memory limit the runtime declares
# (docker-compose `deploy.resources.limits.memory`, Kubernetes
# `resources.limits.memory`), so one artifact has one footprint everywhere.
# Overridable at run time for a differently sized deployment.
# Measurements and rationale: docs/decision-log.md section 40.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# `sh -c "exec java ..."` expands JAVA_OPTS, and `exec` replaces the shell so the
# JVM is PID 1 and receives signals. Datasource, Redis and profile settings are
# injected via environment by compose / Kubernetes.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
