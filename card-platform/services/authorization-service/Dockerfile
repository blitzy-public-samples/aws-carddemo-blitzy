# Container image for the authorization service. The service answers
# POST /authorizations and is the only synchronous entry point on the platform.
# Rationale for every version and exclusion: card-platform/docs/decision-log.md

# --------------------------------------------------------------------------- #
# Stage 1 splits the Spring Boot archive into its four layers. The Maven
# reactor writes that archive into this module's target directory.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jdk-noble AS layers

WORKDIR /staging

# The Java archive (JAR) that the Spring Boot repackage goal produces. Maven
# leaves a second file named .jar.original in the same directory.
COPY target/authorization-service-1.0.0-SNAPSHOT.jar application.jar

# Spring Boot 4 extracts layers through the tools jarmode. The command writes
# four directories: dependencies, spring-boot-loader, snapshot-dependencies
# and application.
RUN java -Djarmode=tools -jar application.jar \
        extract --layers --launcher --destination extracted

# --------------------------------------------------------------------------- #
# Stage 2 assembles the runtime image. A Java Runtime Environment carries the
# four layers, a health probe and one unprivileged account.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jre-noble AS runtime

LABEL org.opencontainers.image.title="carddemo-authorization-service" \
      org.opencontainers.image.description="Answers POST /authorizations, applies the four decline rules, and writes one event per call to its outbox." \
      org.opencontainers.image.version="1.0.0-SNAPSHOT" \
      org.opencontainers.image.vendor="CardDemo Card Platform" \
      org.opencontainers.image.licenses="Apache-2.0" \
      com.carddemo.module="card-platform/services/authorization-service" \
      com.carddemo.maven.coordinates="com.carddemo:authorization-service:1.0.0-SNAPSHOT"

# This layer adds curl and clears the package lists. The base image carries no
# Hypertext Transfer Protocol (HTTP) client of its own.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Group and account 10001 own /app.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin carddemo \
 && mkdir -p /app \
 && chown 10001:10001 /app

WORKDIR /app

# The four layers, copied in the order Spring Boot lists them. Dependencies
# come first and application classes come last.
COPY --from=layers --chown=10001:10001 /staging/extracted/dependencies/ ./
COPY --from=layers --chown=10001:10001 /staging/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=10001:10001 /staging/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=10001:10001 /staging/extracted/application/ ./

# Account 10001 runs the entry point below.
USER 10001:10001

# The container clock reads Coordinated Universal Time (UTC). Every other
# setting arrives from the environment at run time.
ENV TZ=UTC

# The server listens on port 8080. docker-compose.yml maps a host port onto it.
EXPOSE 8080

# Health probe target. Compose reads the same endpoint and gates startup on it.
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=12 \
    CMD ["curl", "--fail", "--silent", "--show-error", "http://localhost:8080/actuator/health"]

# The exec form makes the Java process identifier 1. JarLauncher starts the
# extracted archive.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
