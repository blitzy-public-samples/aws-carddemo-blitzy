# Container image for fraud-detection-service.
#
# The build context is this module directory, so target/ must already hold the
# executable Java archive (JAR). Run both steps from card-platform:
#
#     mvn -f pom.xml -pl services/fraud-detection-service -am package
#     docker build -t fraud-detection-service ./services/fraud-detection-service
#
# `docker compose up -d --build` runs the second step only, so the first step
# still has to run before it. Walkthrough: card-platform/docs/onboarding.md
#
# Net new capability with no legacy ancestor.
# Mapping: card-platform/docs/traceability-matrix.md
# Rationale for every version and exclusion: card-platform/docs/decision-log.md

# --------------------------------------------------------------------------- #
# Stage 1. Java Development Kit (JDK) 25 base. Turns the version-bearing
# archive name into a fixed one the runtime stage can name literally.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jdk-ubi10-minimal AS jar-stage

WORKDIR /stage

# The trailing .jar limits the pattern below to the repackaged archive. Maven
# also leaves fraud-detection-service-<version>.jar.original in target/.
COPY target/fraud-detection-service-*.jar application.jar

# --------------------------------------------------------------------------- #
# Stage 2. Java Runtime Environment (JRE) 25 base, which is all the packaged
# archive needs. This base already provides the curl that the compose health
# check calls inside the container, so the image installs no package.
# --------------------------------------------------------------------------- #
FROM eclipse-temurin:25.0.3_9-jre-ubi10-minimal AS runtime

# Fixed numeric identifiers for the group, the user and the /app owner. This
# base carries no nologin binary, so the account's shell is /bin/false.
RUN groupadd --gid 10001 carddemo \
    && useradd --uid 10001 --gid 10001 --no-create-home \
       --shell /bin/false carddemo \
    && install --directory --owner 10001 --group 10001 /app

WORKDIR /app

COPY --from=jar-stage --chown=10001:10001 /stage/application.jar /app/application.jar

# Every instruction that writes to the image has already run.
USER 10001:10001

# The application listens on 8080, and compose maps a host port onto it.
EXPOSE 8080

# The start period covers a cold start plus the Flyway migration.
HEALTHCHECK --interval=15s --timeout=10s --start-period=60s --retries=5 \
    CMD ["curl", "-fsS", "-o", "/dev/null", "http://127.0.0.1:8080/actuator/health"]

# Exec form, so the Java process runs as PID 1 and receives SIGTERM directly.
# Datasource, broker, schema, topic and log settings all arrive as environment
# variables. The java launcher reads JDK_JAVA_OPTIONS, so an operator adds JVM
# flags without a rebuild.
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
