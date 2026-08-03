# Container build for the CardDemo account service.
# Rationale for every choice below: card-platform/docs/decision-log.md

# --------------------------------------------------------------------------- #
# Stage 1 — stage the packaged jar on a Java Development Kit (JDK) image.
#
# docker-compose.yml builds this image from context ./services/account-service,
# so target/ must already hold the jar. Package it from card-platform first:
#
#     mvn -pl services/account-service -am package
#
# The aggregator descriptor and the two shared libraries sit outside this
# context, and no stage here can reach them.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jdk AS build

WORKDIR /build

# bash with pipefail, so a failure anywhere in the pipeline below stops the build.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# The version travels in the file name. The destination name drops it.
COPY target/account-service-*.jar app.jar

# Every repackaged Spring Boot jar carries BOOT-INF/classes/. This listing fails
# the build when target/ held a plain jar or an empty file.
RUN jar --list --file app.jar | grep -q '^BOOT-INF/classes/$'

# --------------------------------------------------------------------------- #
# Stage 2 — run the jar on a Java Runtime Environment (JRE) image, which carries
# no compiler.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jre

# Clock the Java runtime reads at start. The base image already sets a UTF-8 locale.
ENV TZ=UTC

# Ceiling the heap may reach, as a share of the container memory limit. The Java
# runtime reads this variable at start, and docker-compose.yml can replace it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

WORKDIR /app

# Runtime account. Both numeric identifiers are fixed at 10001.
RUN groupadd --gid 10001 carddemo \
    && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home \
       --shell /usr/sbin/nologin carddemo \
    && chown 10001:10001 /app

COPY --from=build --chown=10001:10001 /build/app.jar /app/app.jar

USER 10001:10001

# Port the service serves on inside the container. docker-compose.yml maps a
# host port onto it.
EXPOSE 8080

# Readiness probe. This image carries bash and neither curl nor wget, so the probe
# opens the socket through bash. The start period covers startup and the Flyway
# migration.
HEALTHCHECK --interval=10s --timeout=10s --start-period=60s --retries=24 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]

# SIGTERM reaches the Java process directly in this form.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
