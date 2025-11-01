# ============================================================================
# CardDemo Backend Dockerfile
# Multi-stage Docker build for Spring Boot application
# ============================================================================
# Stage 1: Maven Build Stage
# Stage 2: Optimized Runtime Stage with Java 21 JRE
#
# Build Command:
#   docker build -t carddemo-backend:latest -f backend/Dockerfile ./backend
#
# Run Command:
#   docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev carddemo-backend:latest
# ============================================================================

# ============================================================================
# Stage 1: Builder - Maven Build Stage
# ============================================================================
# Use Maven 3.9.6 with Eclipse Temurin Java 21 JDK for compilation
FROM maven:3.9.6-eclipse-temurin-21 AS builder

# Set build-time metadata labels
LABEL stage=builder
LABEL maintainer="CardDemo Development Team"
LABEL description="Maven build stage for CardDemo Spring Boot application"

# Set working directory for build
WORKDIR /app

# Copy Maven POM file first for dependency caching
# This layer will be cached unless pom.xml changes
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy application source code
COPY src ./src

# Build the application
# - clean: Remove previous builds
# - package: Compile and package as executable JAR
# - DskipTests: Skip tests in Docker build (run separately in CI/CD)
# - B: Batch mode for cleaner logs
RUN mvn clean package -DskipTests -B

# Extract JAR layers for optimized Docker layer caching
# Spring Boot layertools separates:
# - dependencies: Third-party libraries (rarely change)
# - spring-boot-loader: Spring Boot loader classes
# - snapshot-dependencies: SNAPSHOT dependencies
# - application: Application code (changes frequently)
WORKDIR /app/target
RUN java -Djarmode=layertools -jar *.jar extract

# ============================================================================
# Stage 2: Runtime - Production Image
# ============================================================================
# Use Eclipse Temurin Java 21 JRE Alpine for minimal image size
FROM eclipse-temurin:21-jre-alpine

# Set runtime metadata labels
LABEL maintainer="CardDemo Development Team"
LABEL description="CardDemo Spring Boot Backend Application"
LABEL version="1.0.0"
LABEL application="carddemo-backend"
LABEL java.version="21"
LABEL spring.boot.version="3.2.1"

# Install dumb-init for proper signal handling
# dumb-init ensures SIGTERM signals are properly forwarded to Java process
# This is critical for graceful shutdown in Kubernetes
RUN apk add --no-cache dumb-init=1.2.5-r3

# Install wget for health check support
RUN apk add --no-cache wget=1.21.4-r0

# Create non-root user and group for security best practices
# Running as non-root prevents privilege escalation attacks
RUN addgroup -S spring && adduser -S spring -G spring

# Switch to non-root user
USER spring:spring

# Set working directory
WORKDIR /app

# Copy extracted JAR layers from builder stage in dependency order
# Layers are copied from least frequently changed to most frequently changed
# This maximizes Docker layer cache hit rate during rebuilds

# Layer 1: Third-party dependencies (rarely change)
COPY --from=builder --chown=spring:spring /app/target/dependencies/ ./

# Layer 2: Spring Boot loader classes
COPY --from=builder --chown=spring:spring /app/target/spring-boot-loader/ ./

# Layer 3: SNAPSHOT dependencies (if any)
COPY --from=builder --chown=spring:spring /app/target/snapshot-dependencies/ ./

# Layer 4: Application code (changes most frequently)
COPY --from=builder --chown=spring:spring /app/target/application/ ./

# Expose application port
# Spring Boot default port is 8080
EXPOSE 8080

# Configure health check using Spring Boot Actuator
# Health check verifies application is responding and healthy
# - interval: Check every 30 seconds
# - timeout: Wait 3 seconds for response
# - start-period: Allow 60 seconds for application startup
# - retries: Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Set environment variables with secure defaults
ENV JAVA_OPTS="" \
    SPRING_PROFILES_ACTIVE="prod" \
    SERVER_PORT="8080"

# Use dumb-init as PID 1 for proper signal handling
# This ensures graceful shutdown when container receives SIGTERM
ENTRYPOINT ["/usr/bin/dumb-init", "--"]

# Run Spring Boot application with JVM optimizations
# JVM Flags Explanation:
# - UseContainerSupport: Enable container-aware resource detection
# - MaxRAMPercentage: Use up to 75% of container memory for heap
# - UseG1GC: Use G1 garbage collector (recommended for Spring Boot)
# - HeapDumpOnOutOfMemoryError: Generate heap dump for debugging OOM errors
# - HeapDumpPath: Location to write heap dump file
# - Djava.security.egd: Use non-blocking entropy source for faster startup
CMD ["java", \
     "-XX:+UseContainerSupport", \
     "-XX:MaxRAMPercentage=75.0", \
     "-XX:+UseG1GC", \
     "-XX:+HeapDumpOnOutOfMemoryError", \
     "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
     "-Djava.security.egd=file:/dev/./urandom", \
     "org.springframework.boot.loader.launch.JarLauncher"]

# ============================================================================
# Build Arguments (Optional)
# ============================================================================
# These can be passed during docker build with --build-arg flag
#
# Example:
#   docker build --build-arg SPRING_PROFILES_ACTIVE=dev -t carddemo-backend .
#
# Available arguments:
# - MAVEN_OPTS: Additional Maven build options
# - JAR_FILE: Override JAR file name if non-standard
# ============================================================================

# ============================================================================
# Runtime Environment Variables
# ============================================================================
# These can be passed during docker run with -e flag or in Kubernetes deployment
#
# Required:
# - SPRING_DATASOURCE_URL: PostgreSQL JDBC URL
# - SPRING_DATASOURCE_USERNAME: Database username
# - SPRING_DATASOURCE_PASSWORD: Database password
# - SPRING_REDIS_HOST: Redis server hostname
# - SPRING_REDIS_PORT: Redis server port
#
# Optional:
# - SPRING_PROFILES_ACTIVE: Active Spring profile (dev/prod)
# - SERVER_PORT: Application port (default: 8080)
# - JAVA_OPTS: Additional JVM options
# - LOGGING_LEVEL_ROOT: Root logging level (INFO/DEBUG/WARN)
# - SPRING_JPA_SHOW_SQL: Show SQL queries (true/false)
#
# Example:
#   docker run -e SPRING_PROFILES_ACTIVE=dev \
#              -e SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
#              -e SPRING_DATASOURCE_USERNAME=carduser \
#              -e SPRING_DATASOURCE_PASSWORD=cardpass \
#              -e SPRING_REDIS_HOST=localhost \
#              -e SPRING_REDIS_PORT=6379 \
#              -p 8080:8080 carddemo-backend:latest
# ============================================================================

# ============================================================================
# Image Size Optimization
# ============================================================================
# Builder stage: ~800MB (Maven + JDK + dependencies + source)
# Runtime stage: ~200MB (JRE + application only)
#
# Layer caching strategy reduces rebuild time:
# - POM dependencies: Cached unless pom.xml changes
# - Application code: Only rebuilt when source changes
# - Multi-stage build discards build tools from final image
# ============================================================================

# ============================================================================
# Security Hardening
# ============================================================================
# 1. Non-root user: Runs as 'spring' user (not root)
# 2. Minimal base: Alpine Linux reduces attack surface
# 3. No shell: Application runs directly without shell
# 4. Read-only filesystem compatible: No writes to container filesystem
# 5. Capability dropping compatible: No special Linux capabilities required
# 6. Security scanning: Image can be scanned with Trivy, Snyk, etc.
# ============================================================================

# ============================================================================
# Kubernetes Integration
# ============================================================================
# This Dockerfile is designed for Kubernetes deployment:
# - Health checks: Liveness and readiness probes use /actuator/health
# - Graceful shutdown: dumb-init handles SIGTERM properly
# - Resource limits: JVM respects container memory limits
# - Logging: Logs to stdout/stderr for Kubernetes log collection
# - Configuration: Uses environment variables for 12-factor app compliance
#
# Referenced in: kubernetes/backend-deployment.yaml
# ============================================================================

# ============================================================================
# Docker Compose Integration
# ============================================================================
# This Dockerfile is referenced in docker-compose.yml for local development:
#
# services:
#   backend:
#     build:
#       context: ./backend
#       dockerfile: Dockerfile
#     ports:
#       - "8080:8080"
#     environment:
#       - SPRING_PROFILES_ACTIVE=dev
#       - SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/carddemo
#     depends_on:
#       - postgres
#       - redis
# ============================================================================
