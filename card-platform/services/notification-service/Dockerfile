# Container image for the CardDemo notification service.
#
# Build the Java archive (JAR) first, from the repository root:
#   mvn -f card-platform/pom.xml package
#
# Rationale for every version and exclusion: card-platform/docs/decision-log.md

# --------------------------------------------------------------------------- #
# Stage 1. Checks the archive built on the host and renames it to app.jar.
# The build context is ./services/notification-service. Paths outside this
# module are unreachable. This stage compiles nothing.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Matches the repackaged archive only. Spring Boot writes the plain archive to
# target/notification-service-*.jar.original, which this pattern skips.
ARG JAR_FILE=target/notification-service-*.jar

COPY ${JAR_FILE} staged/

# Stops the build unless exactly one archive arrived, then renames it. The jar
# tool of the Java Development Kit (JDK) looks for the BOOT-INF directory. Only
# a repackaged Spring Boot archive carries that directory.
RUN set -eu; \
    set -- staged/*.jar; \
    if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then \
        echo "Expected one archive matching ${JAR_FILE}, found $#: $*" >&2; \
        exit 1; \
    fi; \
    mv "$1" app.jar; \
    jar --list --file app.jar > listing.txt; \
    grep -q '^BOOT-INF/' listing.txt || { \
        echo "app.jar holds no BOOT-INF directory." >&2; \
        echo "Run: mvn -f card-platform/pom.xml package" >&2; \
        exit 1; \
    }

# --------------------------------------------------------------------------- #
# Stage 2. The shipped image. It carries a Java Runtime Environment (JRE), curl
# and the archive. No compiler, no build tool and no source reach this stage.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25-jre AS runtime

# The compose health check reads /actuator/health with curl. This base image
# ships neither curl nor wget. The package lists go in the same layer.
RUN set -eu; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl; \
    rm -rf /var/lib/apt/lists/*

# Non-root account with a fixed numeric identifier, so a Kubernetes runAsUser
# setting of 10001 matches this image.
RUN set -eu; \
    groupadd --gid 10001 app; \
    useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home \
            --no-log-init --shell /usr/sbin/nologin app; \
    install -d -o app -g app -m 0755 /app

WORKDIR /app

ENV TZ=UTC

# The service listens here. The compose file maps this port to the host.
EXPOSE 8080

# The start period covers the Flyway migration and both consumer group joins.
HEALTHCHECK --interval=10s --timeout=10s --retries=24 --start-period=60s \
    CMD ["curl", "-fsS", "-o", "/dev/null", "http://localhost:8080/actuator/health"]

# Last filesystem layer. The archive changes on every build.
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER 10001:10001

# The Java virtual machine (JVM) reads JAVA_TOOL_OPTIONS from the environment.
# Pass extra flags with a compose environment entry or docker run -e.
# The exec form leaves the JVM as process 1. A stop signal reaches it directly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
