# Container image for the CardDemo authorization service. It is the only synchronous entry point on the platform.
#
# The build context is card-platform, the directory this module's build has to see: the
# aggregator descriptor, both shared libraries and this module. Anything outside it is
# excluded by services/authorization-service/Dockerfile.dockerignore, which BuildKit reads
# in place of the root ignore file because it sits beside this Dockerfile.
#
# Two stages. The first is a Java Development Kit 25 with Apache Maven 3.9.16 and it
# builds the archive from source. The second is a Java Runtime Environment and it
# receives that one archive. Nothing else crosses between them, which is what makes an
# in-image build and a small runtime image the same decision rather than opposing ones.
#
# Decision rules: app/cbl/CBTRN02C.cbl paragraphs 1500-A and 1500-B. Request
# contract: app/cbl/COTRN02C.cbl. Authentication fork: app/cbl/COSGN00C.cbl.

# The build stage image. Apache Maven 3.9.16 on an Eclipse Temurin Java Development Kit
# 25, which is the toolchain card-platform/pom.xml requires and the enforcer plugin
# checks: requireMavenVersion [3.9.16,3.10.0) and requireJavaVersion [25,26). The digest
# pins the layer, so the compiler that produces this archive cannot change under it.
ARG BUILDER_IMAGE=maven:3.9.16-eclipse-temurin-25@sha256:1b1fc6d0168ea616afd1c861d6f32ec37c9ec2ffe88a0351b3771dd4ad86b0d8

# The one runtime image every service in this mono-repo shares. A Java Runtime
# Environment (JRE) 25.0.4 runs the archive. The digest pins the layer, and the
# image installs no package, so no package pin can go stale.
ARG RUNTIME_IMAGE=bellsoft/liberica-openjre-debian:25.0.4-9@sha256:1671b2ce27ad4ab213800a07c4631aa35528418636be5bce32d027f1926b8b0d

# Stage 1. Compiles this module and the two libraries it depends on, then leaves one
# archive under its target/ directory. This is where a compiler and a build tool live,
# and they stay here: the runtime stage below copies a single file out of it.
FROM ${BUILDER_IMAGE} AS builder

WORKDIR /build

# The whole context, which the ignore file beside this Dockerfile has already reduced to
# the aggregator descriptor, every module descriptor, the two libraries and this module.
# Another service's sources are not in it, so this image cannot be built from them.
COPY . .

# -pl this module -am builds the aggregator, libs/event-contracts, libs/cobol-compat and
# this module, and nothing else. maven.test.skip leaves the test sources uncompiled: the
# suite is the pipeline's job and running it here would put a database and a broker inside
# an image build. The cache mount holds the local repository between builds, so the five
# other service images resolve what this one downloaded instead of downloading it again.
RUN --mount=type=cache,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -pl services/authorization-service -am -Dmaven.test.skip=true package

# Exactly one archive, or no image. The Spring Boot plugin leaves a second file ending in
# .jar.original beside the executable one, and this pattern does not match it. Without the
# check a directory holding two archives would ship whichever the shell sorted last, in
# silence.
RUN set -eu; \
    set -- /build/services/authorization-service/target/*.jar; \
    if [ "$#" -ne 1 ]; then \
      echo "Expected one archive under services/authorization-service/target/, found $#: $*" >&2; \
      exit 1; \
    fi; \
    mv "$1" /build/services/authorization-service/target/app.jar
# Stage 2. The shipped image. It carries the archive and a Java Runtime
# Environment, and nothing else.
FROM ${RUNTIME_IMAGE} AS runtime

LABEL org.opencontainers.image.title="carddemo-authorization-service" \
      org.opencontainers.image.description="Answers POST /authorizations, applies the four decline rules, and writes the decision and one event to its outbox in a single local transaction." \
      org.opencontainers.image.vendor="CardDemo Card Platform" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/blitzy-public-samples/aws-carddemo-blitzy" \
      com.carddemo.module="card-platform/services/authorization-service"

# One group and one account, both at the fixed numeric identifier 10001, so a
# Kubernetes runAsUser of 10001 matches this image. /app belongs to that account.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home \
      --no-log-init --shell /usr/sbin/nologin carddemo \
 && install --directory --owner 10001 --group 10001 --mode 0755 /app

WORKDIR /app

# The one archive this image runs, taken from the builder stage above and owned by
# the account that runs it. No compiler, no build tool, no dependency cache and no
# source file crosses this line: a multi-stage build carries only what is copied.
COPY --from=builder --chown=10001:10001 /build/services/authorization-service/target/app.jar \
     /app/app.jar

# Every instruction that writes to the image has already run.
USER 10001:10001

# This image runs with a read-only root filesystem. Nothing under / is written
# after start: /app holds only the archive above, and the one writable path the
# runtime needs is /tmp, which the caller supplies as a size-limited in-memory
# mount. docker-compose.yml sets `read_only: true` with a `tmpfs` entry for /tmp,
# and deploy/k8s sets `readOnlyRootFilesystem: true` with an emptyDir of the same
# size. java.io.tmpdir is pinned below so the Java virtual machine and the
# embedded servlet container write their work directories there and nowhere else.
ENV TMPDIR=/tmp

# The container clock reads Coordinated Universal Time. The heap ceiling follows
# the container memory limit. docker-compose.yml replaces either value, and every
# other setting arrives from the environment at run time.
ENV TZ=UTC
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.io.tmpdir=/tmp"

# The Representational State Transfer (REST) surface. docker-compose.yml maps a
# host port onto it, and /actuator answers on neither this port nor any other
# port a client of the service reaches.
EXPOSE 8080

# /actuator answers here alone, so a caller of the service port reaches no
# runtime, database or broker internals. docker-compose.yml and
# deploy/k8s/30-configmap.yaml both carry the same value, and both publish this
# port on the loopback address only.
ENV MANAGEMENT_PORT=9080
EXPOSE 9080

# The base image carries curl. The shell form substitutes MANAGEMENT_PORT, which
# the exec form would not. Spring Boot answers 200 on /actuator/health once
# Flyway has migrated the schema, and 503 while a health indicator reports down.
# The start period covers a cold start, that migration and a consumer group join.
HEALTHCHECK --interval=15s --timeout=10s --start-period=60s --retries=12 \
    CMD curl -fsS -o /dev/null "http://127.0.0.1:${MANAGEMENT_PORT}/actuator/health"

# The exec form leaves the Java Virtual Machine at process 1, so SIGTERM reaches
# it directly.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
