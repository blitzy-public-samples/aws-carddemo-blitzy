# auth-service/Dockerfile - runtime image for the Authentication microservice.
# Prerequisite: build the executable jar first via `mvn clean install` at the
# repository root (builds carddemo-common, then this module). The ./auth-service
# build context excludes the sibling carddemo-common source, so this image copies
# the pre-built target/auth-service.jar instead of compiling here.

FROM eclipse-temurin:21-jre

WORKDIR /app

# Dedicated non-root system user (uid/gid < 1000; avoids the base image's ubuntu:1000).
RUN groupadd --system spring \
    && useradd --system --gid spring spring

# Repackaged Spring Boot jar (POM finalName=auth-service); mode 0644 stays
# world-readable for the uid-1000 read-only-rootfs runtime used by docker-compose and k8s.
COPY --chown=spring:spring target/auth-service.jar /app/app.jar

USER spring

EXPOSE 8080

# Tuning hook for memory/GC flags without rebuilding the image.
ENV JAVA_OPTS=""

# exec => the JVM becomes PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
