# Container image for the CardDemo ledger posting service. It consumes one topic and publishes another.
#
# The build context is card-platform, the aggregator directory, so this build
# reaches the aggregator descriptor and both shared libraries. Stage 1 packages
# the module inside the image, so nothing has to be built on the host first:
#
#     docker build -f services/ledger-posting-service/Dockerfile -t ledger-posting-service .
#
# docker-compose.yml passes that same context and this dockerfile path, so
# `cp .env.example .env && docker compose up -d --build` needs no earlier step.
#
# Posting arithmetic: app/cbl/CBTRN02C.cbl paragraphs 2000, 2500, 2700, 2800 and 2900.

# The one build image and the one runtime image every service in this mono-repo
# shares. Apache Maven 3.9.16 on Java Development Kit (JDK) 25 builds, and the
# Java Runtime Environment (JRE) 25.0.4 security baseline runs. The runtime
# reference carries a digest as well as a tag, so a re-tagged upstream image
# cannot enter the shipped layer. The build stage ships nothing. Neither image
# installs a package, so no package pin can go stale.
ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-25@sha256:7e461cec477077c1d9e50b13df8aef9018764410f4c4cd7c34803f10c4c99e4c
ARG RUNTIME_IMAGE=bellsoft/liberica-openjre-debian:25.0.4-9@sha256:1671b2ce27ad4ab213800a07c4631aa35528418636be5bce32d027f1926b8b0d

# --------------------------------------------------------------------------- #
# Stage 1. Resolves dependencies, packages this module together with the two
# libraries it depends on, and splits the repackaged archive into the four
# Spring Boot layers.
# --------------------------------------------------------------------------- #
FROM ${BUILD_IMAGE} AS build

# The reactor path and the artifact identifier this image packages. Every
# instruction below reads these two values, and none repeats a version.
ARG MODULE=services/ledger-posting-service
ARG ARTIFACT_ID=ledger-posting-service

WORKDIR /build

# Every descriptor in the reactor arrives before any source file. Maven needs
# all of them to load the reactor, and this layer stays valid while only
# sources change.
COPY pom.xml ./
COPY libs/event-contracts/pom.xml libs/event-contracts/
COPY libs/cobol-compat/pom.xml libs/cobol-compat/
COPY services/authorization-service/pom.xml services/authorization-service/
COPY services/ledger-posting-service/pom.xml services/ledger-posting-service/
COPY services/fraud-detection-service/pom.xml services/fraud-detection-service/
COPY services/notification-service/pom.xml services/notification-service/
COPY services/account-service/pom.xml services/account-service/
COPY services/card-service/pom.xml services/card-service/
COPY equivalence-tests/pom.xml equivalence-tests/

# Populates the local repository for this module and its two library
# dependencies. The cache mount carries the repository between builds, and a
# cold cache resolves from Maven Central.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn --batch-mode -pl "${MODULE}" --also-make dependency:go-offline -DskipTests

# Sources arrive last, so editing one reuses every layer above.
COPY libs libs
COPY services/ledger-posting-service services/ledger-posting-service

# The reactor test phases run in continuous integration, ahead of this build.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn --batch-mode -pl "${MODULE}" --also-make package -DskipTests

# Stops the build unless the package produced exactly one archive for this
# module, and unless that archive is a repackaged Spring Boot one. Maven leaves
# a second file named .jar.original, which the pattern below does not match.
RUN set -eu; \
    set -- "${MODULE}"/target/"${ARTIFACT_ID}"-*.jar; \
    if [ ! -f "$1" ]; then set --; fi; \
    if [ "$#" -ne 1 ]; then \
        echo "Expected exactly one ${ARTIFACT_ID} archive under ${MODULE}/target, found $#: $*" >&2; \
        exit 1; \
    fi; \
    jar --list --file "$1" > /tmp/archive-entries.txt; \
    if ! grep -q '^BOOT-INF/' /tmp/archive-entries.txt; then \
        echo "$1 carries no BOOT-INF directory, so java cannot launch it." >&2; \
        exit 1; \
    fi; \
    cp "$1" /build/application.jar

# Extraction writes dependencies, spring-boot-loader, snapshot-dependencies and
# application under /build/extracted.
RUN java -Djarmode=tools -jar /build/application.jar \
        extract --layers --launcher --destination /build/extracted

# --------------------------------------------------------------------------- #
# Stage 2. The shipped image. It carries the extracted layers and a Java
# Runtime Environment. No compiler, no build tool and no source reach it.
# --------------------------------------------------------------------------- #
FROM ${RUNTIME_IMAGE} AS runtime

LABEL org.opencontainers.image.title="carddemo-ledger-posting-service" \
      org.opencontainers.image.description="Consumes transaction.authorized, reproduces the batch posting arithmetic per event, and publishes transaction.posted." \
      org.opencontainers.image.vendor="CardDemo Card Platform" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/blitzy-public-samples/aws-carddemo-blitzy" \
      com.carddemo.module="card-platform/services/ledger-posting-service"

# One group and one account, both at the fixed numeric identifier 10001, so a
# Kubernetes runAsUser of 10001 matches this image. /app belongs to that
# account. This image installs no package, so no package pin can go stale.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home \
      --no-log-init --shell /usr/sbin/nologin carddemo \
 && install --directory --owner 10001 --group 10001 --mode 0755 /app

WORKDIR /app

# The four layers, in the order Spring Boot lists them. Dependencies land first
# and application classes last, so a source change rewrites only the last layer.
COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./

# Every instruction that writes to the image has already run.
USER 10001:10001

# The container clock reads Coordinated Universal Time. The heap ceiling follows
# the container memory limit. docker-compose.yml replaces either value, and
# every other setting arrives from the environment at run time.
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# The server listens on 8080. docker-compose.yml maps a host port onto it.
EXPOSE 8080

# /actuator answers on this port alone, which docker-compose.yml publishes on the
# loopback address, so metrics and Prometheus scraping stay off the service port.
ENV MANAGEMENT_PORT=9080
EXPOSE 9080

# One probe form across all six images. The base image carries curl. The shell form
# substitutes MANAGEMENT_PORT, which the exec form would not. Spring Boot answers 200
# on /actuator/health once Flyway has migrated the schema, and 503 while a health
# indicator reports down. The start period covers a cold start, that migration and any
# consumer group join.
HEALTHCHECK --interval=15s --timeout=10s --start-period=60s --retries=12 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health"

# The exec form leaves the Java Virtual Machine at process 1, so SIGTERM
# reaches it directly. JarLauncher starts the extracted layers.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
