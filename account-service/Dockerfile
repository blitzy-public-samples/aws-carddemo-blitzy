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
# (AAP section 0.6.6). The container runs with the "docker" Spring profile, which
# reads the datasource strictly from environment variables supplied at runtime
# (see application-docker.yml), and the application process runs as an
# unprivileged (non-root) user.
#
# The runtime stage applies OS security patches (apt-get upgrade) on the mandated
# eclipse-temurin:17-jre base and exposes a RUNTIME_BASE build argument for digest
# pinning. Release pipelines MUST scan the final image (e.g. Trivy / Grype / ECR
# image scanning) and pin the remediated digest, iterating until no applicable
# unresolved OS or JRE vulnerability remains. See the runtime stage below.
# =============================================================================

# RUNTIME_BASE is a GLOBAL build argument: it MUST be declared before the first FROM
# so it can be consumed by the runtime stage's FROM below (an ARG used in a FROM has
# to be global). It lets a release pipeline pin the scanned/remediated runtime base
# image by digest while defaulting to the AAP-mandated eclipse-temurin:17-jre family.
# See the runtime stage for the digest-pinning example.
ARG RUNTIME_BASE=eclipse-temurin:17-jre


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
#
# SUPPLY-CHAIN / DIGEST PINNING: the base is exposed as the RUNTIME_BASE build
# argument so a release pipeline can pin the exact *scanned and remediated* image
# by digest while keeping the AAP-mandated eclipse-temurin:17-jre family as the
# default, e.g.:
#     docker build \
#       --build-arg RUNTIME_BASE=eclipse-temurin:17-jre@sha256:<remediated-digest> \
#       -t account-service:release account-service/
# Because a moving tag can resolve to an unpatched digest, releases SHOULD pin the
# digest of an image that has passed a vulnerability scan (see the OS security
# patching step below and the SECURITY note at the top of this file). RUNTIME_BASE
# is declared as a global build arg near the top of this file.
FROM ${RUNTIME_BASE} AS runtime

# OCI image metadata (informational only; does not affect runtime behavior).
LABEL org.opencontainers.image.title="account-service" \
      org.opencontainers.image.description="CardDemo Account Management microservice (Feature F-003): account inquiry and update REST API" \
      org.opencontainers.image.vendor="AWS CardDemo Modernization" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo"

WORKDIR /app

# OS SECURITY PATCHING (remediates M9): the mandated eclipse-temurin:17-jre base is
# an Ubuntu image whose published layers can lag behind the distribution's security
# updates, so a freshly pulled tag may still contain vulnerable OS package versions
# (e.g. tar, gzip, curl-family, wget) even though fixes already exist in the Ubuntu
# archive. Running `apt-get upgrade` at build time pulls those patched versions
# regardless of the (possibly unchanged) base-image digest -- the one thing that
# rebuilding the same tag alone cannot do. `wget` is additionally purged because the
# runtime never fetches over HTTP (the entrypoint is only `java -jar`), trimming the
# attack surface per "remove unnecessary utilities where possible"; `--auto-remove`
# drops any now-orphaned dependencies. The apt lists are deleted afterwards to keep
# the image minimal. This runs as root, before privileges are dropped below.
# NOTE: this requires network egress to the Ubuntu package archive at build time
# (available in CI); on an already fully patched base it is simply a no-op upgrade.
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get purge -y --auto-remove wget \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

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

# Default to the "docker" Spring profile so the container reads its datasource
# configuration from environment variables (see application-docker.yml). This is
# only a DEFAULT and can be overridden at `docker run` time; no secrets or
# connection URLs are baked into the image.
ENV SPRING_PROFILES_ACTIVE=docker

# The embedded Tomcat server listens on HTTP port 8080 (AAP section 0.4.1).
EXPOSE 8080

# Exec-form entrypoint so the JVM runs as PID 1 and receives OS signals
# (e.g. SIGTERM) directly, enabling graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
