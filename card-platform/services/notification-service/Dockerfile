# Container image for the CardDemo notification service. It consumes one topic and publishes none.
# The build context is this module directory, so every COPY path below resolves inside
# card-platform/services/notification-service/. The archive under target/ arrives from `mvn -f
# card-platform/pom.xml package`, which runs ahead of this image build. Rendering and totalling:
# app/cbl/CBSTM03A.CBL:L86-L159. Read model: app/cpy/COSTM01.CPY. Read-model key:
# app/jcl/CREASTMT.JCL.

# The one runtime image every service in this mono-repo shares. A Java Runtime
# Environment (JRE) 25.0.4 runs the archive. The digest pins the layer, and the
# image installs no package, so no package pin can go stale.
ARG RUNTIME_IMAGE=bellsoft/liberica-openjre-debian:25.0.4-9@sha256:1671b2ce27ad4ab213800a07c4631aa35528418636be5bce32d027f1926b8b0d

# Stage 1. Receives the archive the host build left under target/ and fixes its
# name. No build tool, no compiler and no source tree enter this build.
FROM ${RUNTIME_IMAGE} AS archive

WORKDIR /staging

# The archive the host build left under target/. The Spring Boot plugin leaves a
# second file ending in .jar.original, and this pattern does not match it. The
# pattern is copied into the directory rather than onto a name, because a name
# would take whichever match sorted last and ship it in silence: a stale archive
# from an earlier version, or a sources archive, would then become the image.
COPY target/*.jar ./

# Exactly one archive, or no image. The check below is the only thing standing
# between an un-cleaned target/ and an image running the wrong code.
RUN set -eu; \
    set -- /staging/*.jar; \
    if [ "$#" -ne 1 ]; then \
      echo "Expected one archive under target/, found $#: $*" >&2; \
      echo "Run mvn -f card-platform/pom.xml clean package and build again." >&2; \
      exit 1; \
    fi; \
    mv "$1" /staging/app.jar

# Stage 2. The shipped image. It carries the archive and a Java Runtime
# Environment, and nothing else.
FROM ${RUNTIME_IMAGE} AS runtime

LABEL org.opencontainers.image.title="carddemo-notification-service" \
      org.opencontainers.image.description="Consumes transaction.posted under one consumer group of its own, maintains a card-keyed read model, and renders cardholder alerts." \
      org.opencontainers.image.vendor="CardDemo Card Platform" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/blitzy-public-samples/aws-carddemo-blitzy" \
      com.carddemo.module="card-platform/services/notification-service"

# One group and one account, both at the fixed numeric identifier 10001, so a
# Kubernetes runAsUser of 10001 matches this image. /app belongs to that account.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --home-dir /app --no-create-home \
      --no-log-init --shell /usr/sbin/nologin carddemo \
 && install --directory --owner 10001 --group 10001 --mode 0755 /app

WORKDIR /app

# The one archive this image runs, owned by the account that runs it.
COPY --from=archive --chown=10001:10001 /staging/app.jar /app/app.jar

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
