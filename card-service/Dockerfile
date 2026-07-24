# syntax=docker/dockerfile:1
# =============================================================================
# card-service/Dockerfile
#
# Runtime-only image for the CardDemo Card microservice (Spring Boot 4.1.0).
#
# The docker-compose build context is ./card-service ONLY, so the sibling
# carddemo-common library and the repository-root parent pom.xml are not visible
# here and an in-image multi-module Maven build is impossible by design. This
# image therefore just packages the executable fat jar produced by the reactor.
#
# PRECONDITION — build the jar from the repository ROOT first, e.g.
#     mvn clean install              (or: mvn -pl card-service -am package)
# which emits card-service/target/card-service-<version>.jar, then build/run:
#     docker compose up --build      (or: docker build ./card-service)
#
# The rationale for the single-stage, runtime-only design is recorded in
# docs/decision-log.md rather than repeated inline (Docstring/Comments rule).
# =============================================================================

# Spec-literal runtime base image (AAP 0.5.1): exact tag, no substitution.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="card-service" \
      org.opencontainers.image.description="CardDemo Card microservice runtime image"

WORKDIR /app

# Dedicated non-root account for defence-in-depth. The numeric USER below lets
# Kubernetes verify runAsNonRoot from the image config without name resolution,
# while this /etc/passwd entry keeps `id` and the JVM user.name resolvable.
RUN groupadd --gid 1001 appuser \
 && useradd --uid 1001 --gid 1001 --no-create-home --shell /usr/sbin/nologin appuser

# Copy the single reactor-built fat jar and normalize its name. The glob targets
# card-service-*.jar specifically so the *.jar.original thin jar left by the
# Spring Boot repackage plugin is never matched. --chmod=0644 keeps the jar
# world-readable so it stays readable even when docker-compose overrides the
# runtime user (user: "1000:1000") under a read-only root filesystem.
COPY --chown=appuser:appuser --chmod=0644 target/card-service-*.jar app.jar

USER 1001

# Container port; matches docker-compose and the Actuator health probe.
EXPOSE 8080

# Exec form so the JVM is PID 1 and receives SIGTERM/SIGINT directly. Datasource,
# Redis, and Spring profile values arrive as environment variables at run time.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
