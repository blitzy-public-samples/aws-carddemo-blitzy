###############################################################################
# AWS CardDemo - application container image
#
# Multi-stage Docker build for the CardDemo Spring Boot service (the z/OS COBOL /
# CICS credit-card system migrated to Java 25 + Spring Boot 3.5.x). See the Agent
# Action Plan §0.2.1 / §0.3.1 (mandated "Dockerfile (CREATE; app image)") and
# §0.5.1 (Java 25 Temurin base; PostgreSQL runtime).
#
# Design
#   * Stage 1 ("build")   - A Java 25 JDK plus the committed Maven wrapper compile
#                           and repackage the executable Spring Boot jar.
#                           Dependencies are resolved in a dedicated, cache-
#                           friendly layer (invalidated only when pom.xml or the
#                           wrapper change) before the application sources are
#                           copied, so source-only edits reuse the cached layer.
#   * Stage 2 ("runtime") - A slim Java 25 JRE runs the jar as an unprivileged
#                           user. No Maven, build tooling, or dependency cache is
#                           carried into this layer, keeping the final image small.
#
# Security / 12-factor
#   * NO credentials or secrets are baked into the image. The datasource
#     connection (SPRING_DATASOURCE_URL / SPRING_DATASOURCE_USERNAME /
#     SPRING_DATASOURCE_PASSWORD) and the seed sign-on credentials
#     (CARDDEMO_ADMIN_PASSWORD / CARDDEMO_USER_PASSWORD) are supplied at run time
#     via environment variables (AAP §0.6.6).
#   * The application process runs as the non-root user "appuser".
#
# Build context
#   * Only the Maven wrapper, pom.xml and src/ are copied explicitly (never
#     `COPY . .`), so target/, legacy/, samples/ and .git/ never enter the image
#     and no .dockerignore is required for correctness.
#
# Build:  docker build -t carddemo:local .
# Run:    docker run --rm -p 8080:8080 \
#             -e SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/carddemo \
#             -e SPRING_DATASOURCE_USERNAME=carddemo \
#             -e SPRING_DATASOURCE_PASSWORD=*** \
#             -e CARDDEMO_ADMIN_PASSWORD=*** -e CARDDEMO_USER_PASSWORD=*** \
#             carddemo:local
###############################################################################


# =============================================================================
# Stage 1 - build: compile + repackage the executable Spring Boot jar (Java 25)
# =============================================================================
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# 1) Copy ONLY the build descriptor and the Maven wrapper first. Resolving
#    dependencies in their own layer lets Docker reuse the cached dependency
#    layer on subsequent builds whenever only the application sources changed.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure the wrapper is executable regardless of the host checkout permissions.
RUN chmod +x ./mvnw

# 2) Warm the local Maven repository: this downloads the Maven distribution
#    pinned by the wrapper plus all project dependencies. -B = batch / non-
#    interactive (no ANSI prompts, fails fast in automation).
RUN ./mvnw -B dependency:go-offline

# 3) Copy the application sources and build the executable boot jar.
#    Tests are intentionally skipped here: the suite uses Testcontainers, which
#    needs a Docker daemon that is not available during an image build; tests run
#    in CI and locally via `./mvnw verify`. The quality gates (JaCoCo coverage,
#    Spotless, OWASP dependency-check) are bound to the `verify` phase and so do
#    not run during `package` - keeping the image build self-contained.
COPY src/ ./src/
RUN ./mvnw -B -DskipTests clean package


# =============================================================================
# Stage 2 - runtime: run the jar on a slim Java 25 JRE as a non-root user
# =============================================================================
# A dedicated JRE 25 image keeps the runtime layer small. If a `25-jre` tag is
# ever unavailable for the target platform, this line may fall back to
# `eclipse-temurin:25-jdk` (documented fallback; Java 25 is required in both
# stages per AAP §0.5.1).
FROM eclipse-temurin:25-jre AS runtime

# OCI image metadata (informational only - contains no secrets).
LABEL org.opencontainers.image.title="AWS CardDemo" \
      org.opencontainers.image.description="CardDemo credit-card management system migrated from z/OS COBOL to Java 25 + Spring Boot" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.base.name="docker.io/library/eclipse-temurin:25-jre"

WORKDIR /app

# Create an unprivileged user/group and hand it ownership of the work dir.
# Temurin images are Ubuntu-based, so groupadd/useradd are available. Fixed
# uid/gid (1001) keep ownership predictable across rebuilds and hosts.
RUN groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup \
         --home-dir /app --no-create-home --shell /usr/sbin/nologin appuser \
    && chown -R appuser:appgroup /app

# Copy the single executable boot jar produced by the build stage to a stable
# name. `*.jar` matches only carddemo-<version>.jar (Spring Boot's repackaged
# executable jar); the non-executable `*.jar.original` is excluded by the glob.
COPY --from=build --chown=appuser:appgroup /workspace/target/*.jar /app/app.jar

# Spring MVC's embedded server listens on 8080 by default (no server.port
# override in application.yml). Align the container's exposed port with that.
EXPOSE 8080

# Drop privileges: the JVM runs as the unprivileged "appuser".
USER appuser

# No HEALTHCHECK is defined here: the application does not include
# spring-boot-starter-actuator, so there is no dedicated health endpoint and
# probing an arbitrary route could yield false negatives during startup. To add
# one later, include the actuator starter and, for example:
#   HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
#     CMD ["sh","-c","wget -qO- http://localhost:8080/actuator/health || exit 1"]

# Launch the app. `sh -c "exec java ..."` makes the JVM PID 1 (so it receives
# SIGTERM from `docker stop` for a graceful Spring shutdown), expands $JAVA_OPTS
# for ad-hoc JVM tuning, and forwards any `docker run` arguments to Spring Boot
# (e.g. `--spring.profiles.active=prod`). JAVA_TOOL_OPTIONS, if set, is honored
# automatically by the JVM. All tuning/secrets are supplied at run time via the
# environment - nothing sensitive is embedded in the image.
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar \"$@\"","--"]
