# =============================================================================
# account-service - Multi-Stage Container Build
# =============================================================================
# CardDemo Account Management microservice (Feature F-003): the Spring Boot 3.x
# REST re-implementation of the legacy COBOL/CICS account view (COACTVWC / CAVW)
# and account update (COACTUPC / CAUP) programs.
#
# This Dockerfile is intentionally MULTI-STAGE:
#   * Stage 1 ("build")   - a full Maven + JDK 17 toolchain compiles the module
#                           and repackages it into an executable Spring Boot jar.
#   * Stage 2 ("runtime") - a minimal JRE-only image receives ONLY the finished
#                           jar. The entire build toolchain (Maven, the JDK, the
#                           local Maven repository, and every intermediate build
#                           artifact) is discarded, yielding a small, hardened
#                           runtime image.
#
# Build context: the account-service/ module directory. The sibling .dockerignore
# keeps the context small (it excludes target/, .git, IDE files, README, etc.) so
# the application is always compiled FRESH inside the build stage rather than
# shipping any host-produced artifact.
#
# Build:  docker build -t account-service:local account-service/
# Run:    docker run --rm -p 8080:8080 \
#             -e SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/carddemo \
#             -e SPRING_DATASOURCE_USERNAME=<user> \
#             -e SPRING_DATASOURCE_PASSWORD=<password> \
#             account-service:local
#
# SECURITY: No credentials or datasource URLs are ever baked into the image
# (AAP section 0.6.6). The container DEFAULTS to the "docker" Spring profile. The
# PLANNED application-docker.yml (not yet added at this checkpoint) will make that
# profile read the datasource STRICTLY from environment variables with no local
# fallback; until it exists the image inherits application.yml's local-dev
# fallbacks, so the SPRING_DATASOURCE_* env vars must be supplied at runtime. The
# application process runs as an unprivileged (non-root) user.
# =============================================================================


# -----------------------------------------------------------------------------
# Stage 1 - Build: compile and repackage the executable Spring Boot jar.
# -----------------------------------------------------------------------------
# maven:3.9-eclipse-temurin-17 provides Apache Maven 3.9.x on an Eclipse Temurin
# JDK 17 - matching <java.version>17</java.version> declared in pom.xml. A JDK
# (not a JRE) is required in this stage because compilation (javac) happens here.
FROM maven:3.9-eclipse-temurin-17 AS build

# Perform all build work inside an isolated working directory.
WORKDIR /build

# Copy ONLY the POM first, then resolve dependencies. Because the POM changes far
# less often than source code, Docker can cache this expensive dependency-
# resolution layer and reuse it on subsequent builds whenever pom.xml is
# unchanged. `dependency:go-offline` is a best-effort pre-fetch of the project's
# compile, runtime, and plugin dependencies into the image's local Maven
# repository, so the later `package` step needs little or no additional download.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Copy the application sources and build the executable (repackaged) jar.
# Tests - including Testcontainers-based integration tests that require a Docker
# daemon - are intentionally skipped during image assembly; they run in CI, not
# while building the image. The spring-boot-maven-plugin `repackage` goal (bound
# to the `package` phase by spring-boot-starter-parent) rewrites
# target/account-service-<version>.jar into a self-executing fat jar and renames
# the original thin jar to *.jar.original.
COPY src ./src
RUN mvn -B -q clean package -DskipTests


# -----------------------------------------------------------------------------
# Stage 2 - Runtime: minimal JRE image that runs the packaged jar.
# -----------------------------------------------------------------------------
# eclipse-temurin:17-jre is the MANDATED runtime base (AAP section 0.4.1 /
# section 0.5.1): a JRE-only image (no compiler, no Maven) that keeps the shipped
# image small and reduces the attack surface.
FROM eclipse-temurin:17-jre AS runtime

# OCI image metadata (informational only; does not affect runtime behavior).
LABEL org.opencontainers.image.title="account-service" \
      org.opencontainers.image.description="CardDemo Account Management microservice (Feature F-003): account inquiry and update REST API" \
      org.opencontainers.image.vendor="AWS CardDemo Modernization" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo"

WORKDIR /app

# Create a dedicated, unprivileged group and user to run the application
# (defense-in-depth - the JVM must never run as root). The fixed GID/UID 1001 keeps
# ownership stable across rebuilds and matches the `--chown=1001:1001` COPY and the
# `USER 1001` directive below. This step is intentionally FAIL-CLOSED: there is no
# `|| true`, so any failure to create the identity (for example a UID/GID collision
# introduced by a future base image) aborts the build rather than silently shipping
# an image whose runtime user is undefined.
RUN groupadd --gid 1001 spring \
 && useradd --uid 1001 --gid 1001 --no-create-home --shell /usr/sbin/nologin spring

# Copy ONLY the finished executable jar from the build stage - the build
# toolchain and all intermediate output stay behind and never reach this image.
# The `*.jar` glob tolerates the project version (0.0.1-SNAPSHOT) and matches
# EXACTLY ONE artifact: the boot-repackaged executable jar. The plain thin jar is
# renamed *.jar.original by the repackage goal and is therefore NOT matched.
# `--chown=1001:1001` makes the runtime user the owner of its own artifact.
COPY --from=build --chown=1001:1001 /build/target/*.jar /app/app.jar

# Drop root privileges for everything that follows (i.e. the running container).
# Uses the numeric UID of the "spring" identity deterministically created above.
USER 1001

# Default to the "docker" Spring profile. Once the PLANNED application-docker.yml
# is added (see the SECURITY note above; it is NOT present at this checkpoint), that
# profile will read the datasource strictly from environment variables. This is only
# a DEFAULT and can be overridden at `docker run` time; no secrets or connection URLs
# are baked into the image.
ENV SPRING_PROFILES_ACTIVE=docker

# The embedded Tomcat server listens on HTTP port 8080 (AAP section 0.4.1).
EXPOSE 8080

# Exec-form entrypoint so the JVM runs as PID 1 and receives OS signals
# (e.g. SIGTERM) directly, enabling graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
