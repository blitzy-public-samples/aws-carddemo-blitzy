# Container image for the CardDemo ledger posting service.
# Build context: card-platform/services/ledger-posting-service
# The build reads target/*.jar. Produce it first with:
#   mvn -f card-platform/pom.xml package
# Rationale for image and runtime choices: card-platform/docs/decision-log.md

# Java Development Kit (JDK) 25 image. Both stages below reference it.
ARG JAVA_IMAGE=eclipse-temurin:25-jdk

# --------------------------------------------------------------------------- #
# Stage 1. The builder splits the executable Java archive (JAR) into the four
# Spring Boot layers.
# --------------------------------------------------------------------------- #
FROM ${JAVA_IMAGE} AS builder

WORKDIR /build

COPY target/*.jar application.jar

# Extraction writes dependencies, spring-boot-loader, snapshot-dependencies and
# application under /build/extracted.
RUN java -Djarmode=tools -jar application.jar extract --layers --launcher --destination extracted

# --------------------------------------------------------------------------- #
# Stage 2. The runtime image carries the extracted layers and runs the service
# under an unprivileged account.
# --------------------------------------------------------------------------- #
FROM ${JAVA_IMAGE} AS runtime

# curl runs the health check below. The same layer clears the package lists.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# One group and one user, both numbered 10001, own the application directory.
RUN groupadd --gid 10001 carddemo \
    && useradd --uid 10001 --gid 10001 --create-home --shell /usr/sbin/nologin carddemo \
    && mkdir -p /app \
    && chown carddemo:carddemo /app

WORKDIR /app

# Layers land dependency-first so a code change rebuilds only the last layer.
COPY --from=builder --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=10001:10001 /build/extracted/application/ ./

USER 10001:10001

# The service listens on 8080. docker-compose.yml publishes it on LEDGER_PORT.
EXPOSE 8080

# The start period covers the schema migrations that Flyway runs at startup.
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=45s \
    CMD ["curl", "-fsS", "http://localhost:8080/actuator/health"]

# Java is process 1 in the container and receives SIGTERM directly.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
