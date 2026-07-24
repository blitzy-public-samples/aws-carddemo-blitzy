# transaction-service runtime image.
# Runs the pre-built Spring Boot executable (repackaged) fat jar produced by the
# Maven reactor as a non-root user on container port 8080.

FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="carddemo-transaction-service" \
      org.opencontainers.image.description="CardDemo Transaction Service (online transaction inquiry/add and posting batch)" \
      org.opencontainers.image.source="https://github.com/aws-samples/aws-mainframe-modernization-carddemo"

WORKDIR /app

# Non-root runtime account (system uid; uid 1000 is reserved by the base image).
RUN groupadd --system spring \
    && useradd --system --gid spring spring \
    && chown spring:spring /app

# Pre-built repackaged fat jar from `mvn -pl transaction-service -am clean install`.
# The glob matches only the executable jar (the plain jar is renamed *.jar.original).
ARG JAR_FILE=target/transaction-service-*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar

USER spring:spring

EXPOSE 8080

# Tunable JVM flags (heap/GC) without rebuilding the image.
ENV JAVA_OPTS=""

# Exec-form via `sh -c` expands $JAVA_OPTS; `exec` makes the JVM PID 1 for correct
# SIGTERM handling / graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
