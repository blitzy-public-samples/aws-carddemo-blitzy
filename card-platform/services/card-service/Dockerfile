# card-service image: the card list, card detail and card update endpoints.
# Stage one selects the packaged Java archive (JAR). Stage two runs it as a
# non-root user. Build context: card-platform/services/card-service
# Rationale for every version and exclusion: card-platform/docs/decision-log.md

# --------------------------------------------------------------------------- #
# Stage 1: build. Apache Maven 3.9.16 on Java Development Kit (JDK) 25.
# --------------------------------------------------------------------------- #
FROM maven:3.9.16-eclipse-temurin-25 AS build

WORKDIR /build

# The manifest arrives before the sources, so editing a source file reuses the
# cached layer above.
COPY pom.xml ./
COPY src ./src

# Prerequisite: run `mvn -f card-platform/pom.xml install` from the repository
# root first. The parent POM and the two platform libraries sit above this build
# context, so Maven packages the module on the host and writes the JAR into
# target/. The leading glob makes this copy a no-op when target/ is absent, and
# the step below then names the command to run.
COPY target*/*.jar ./target/

# Selects the one card-service JAR and gives it a fixed name. The build stops
# here when target/ carries no JAR that `java -jar` can start.
RUN set -eu; \
    archive=''; \
    for candidate in target/card-service-*.jar; do \
      if [ -f "${candidate}" ]; then archive="${candidate}"; break; fi; \
    done; \
    if [ -z "${archive}" ]; then \
      echo 'No packaged card-service JAR under target/.' >&2; \
      echo 'Run: mvn -f card-platform/pom.xml install' >&2; \
      exit 1; \
    fi; \
    jar --list --file "${archive}" > /tmp/jar-entries.txt; \
    if ! grep -q '^BOOT-INF/' /tmp/jar-entries.txt; then \
      echo "${archive} holds no BOOT-INF directory, so java -jar cannot start it." >&2; \
      echo 'Run: mvn -f card-platform/pom.xml install' >&2; \
      exit 1; \
    fi; \
    cp "${archive}" /build/app.jar

# --------------------------------------------------------------------------- #
# Stage 2: runtime. Java Runtime Environment (JRE) 25.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25-jre AS runtime

# The base image ships neither curl nor wget. The health probe below runs curl,
# and the package index goes away in the same layer. A pinned curl version
# breaks this layer once the Ubuntu archive drops the superseded package.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Group and user carddemo, both at the fixed numeric identifier 10001. The
# directory /app belongs to that user.
RUN groupadd --system --gid 10001 carddemo \
 && useradd --system --uid 10001 --gid 10001 --shell /usr/sbin/nologin \
      --home-dir /app --no-create-home carddemo \
 && mkdir -p /app \
 && chown 10001:10001 /app

WORKDIR /app

COPY --from=build --chown=10001:10001 /build/app.jar ./app.jar

# Log timestamps use Coordinated Universal Time. Every other setting arrives
# from the environment: docker-compose.yml carries the datasource, broker,
# schema and log-level values.
ENV TZ=UTC

# The container listens on 8080. docker-compose.yml publishes that port on the
# host as CARD_PORT, which .env.example sets to 8086.
EXPOSE 8080

USER 10001:10001

# Spring Boot answers /actuator/health once Flyway has migrated the card schema.
# The start period covers that startup.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD ["curl", "-fsS", "http://localhost:8080/actuator/health"]

# Exec form keeps the Java Virtual Machine at process 1, so it receives SIGTERM
# and stops cleanly. Extra Java flags arrive in JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
