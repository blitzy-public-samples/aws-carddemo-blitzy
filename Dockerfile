# =============================================================================
# CardDemo Spring Boot Application - Multi-Stage Dockerfile
# =============================================================================
# This Dockerfile creates an optimized container image for the CardDemo
# application, which is a modernized version of the mainframe CardDemo
# credit card management system migrated to Java 21 Spring Boot.
#
# Architecture:
#   Stage 1 (BUILD): Maven-based compilation using Eclipse Temurin JDK 21
#   Stage 2 (RUNTIME): Minimal OpenJDK 21 slim runtime with security hardening
#
# Key Features:
#   - Multi-stage build for minimal final image size
#   - Dependency layer caching for faster rebuilds
#   - Non-root user execution for security compliance
#   - Health check integration for container orchestration
#   - JVM tuning for cloud-native deployment
#   - Kubernetes-ready with proper resource constraints
# =============================================================================

# =============================================================================
# BUILD STAGE
# =============================================================================
# Uses Maven 3.9.5 with Eclipse Temurin JDK 21 for dependency resolution
# and application compilation. This stage is discarded after build completion.
# -----------------------------------------------------------------------------

FROM maven:3.9.5-eclipse-temurin-21 AS build

# Set metadata for build stage
LABEL stage=builder \
      description="Build stage for CardDemo Spring Boot application"

# Set working directory for build
WORKDIR /build

# -----------------------------------------------------------------------------
# Dependency Resolution Layer (Cached)
# -----------------------------------------------------------------------------
# Copy only pom.xml first to leverage Docker layer caching.
# Dependencies are downloaded in a separate layer that only rebuilds
# when pom.xml changes, significantly speeding up subsequent builds.

COPY pom.xml .

# Download all dependencies in offline mode preparation
# This creates a separate layer that caches Maven dependencies
RUN mvn dependency:go-offline -B

# -----------------------------------------------------------------------------
# Application Compilation Layer
# -----------------------------------------------------------------------------
# Copy entire source code and compile the application.
# This layer rebuilds whenever source code changes.

COPY src ./src

# Build the application, skipping tests in container build
# Tests should be run in CI/CD pipeline before Docker build
# -DskipTests: Skip test execution (tests run in CI/CD)
# -B: Batch mode (non-interactive)
# clean: Remove previous build artifacts
# package: Compile, test, and package into JAR
RUN mvn clean package -DskipTests -B

# Verify JAR was created successfully
RUN ls -lh /build/target/*.jar

# =============================================================================
# RUNTIME STAGE
# =============================================================================
# Minimal production runtime using OpenJDK 21 slim image.
# This stage contains only the compiled JAR and runtime dependencies.
# -----------------------------------------------------------------------------

FROM openjdk:21-jdk-slim AS runtime

# Set comprehensive metadata labels following OCI image spec
LABEL maintainer="CardDemo Migration Team" \
      version="1.0.0" \
      description="CardDemo Spring Boot Application - Modernized Mainframe Credit Card Management System" \
      application="carddemo" \
      component="backend" \
      framework="spring-boot" \
      java.version="21" \
      org.opencontainers.image.title="CardDemo Application" \
      org.opencontainers.image.description="Cloud-native credit card management system migrated from IBM COBOL/CICS" \
      org.opencontainers.image.vendor="CardDemo Migration Team" \
      org.opencontainers.image.version="1.0.0"

# -----------------------------------------------------------------------------
# Security: Create Non-Root User
# -----------------------------------------------------------------------------
# Running as non-root is a security best practice and required by many
# Kubernetes security policies (PodSecurityPolicy, SecurityContext)

RUN groupadd -r carddemo -g 1001 && \
    useradd -r -g carddemo -u 1001 -m -s /sbin/nologin carddemo

# -----------------------------------------------------------------------------
# Application Setup
# -----------------------------------------------------------------------------
# Set working directory for application
WORKDIR /app

# Copy compiled JAR from build stage
# Using wildcard to handle version-specific JAR names
COPY --from=build /build/target/carddemo-*.jar /app/app.jar

# Change ownership to non-root user
# Ensures the application user has proper permissions
RUN chown -R carddemo:carddemo /app

# Install wget for health check capability
# Update package list and install minimal dependencies
# Clean up apt cache to reduce image size
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# Network Configuration
# -----------------------------------------------------------------------------
# Expose port 8080 for HTTP traffic
# This is the default Spring Boot embedded Tomcat port
EXPOSE 8080

# -----------------------------------------------------------------------------
# Health Check Configuration
# -----------------------------------------------------------------------------
# Configure container health check using Spring Boot Actuator endpoint
# This enables Kubernetes liveness and readiness probes
#
# Parameters:
#   --interval=30s: Check every 30 seconds
#   --timeout=3s: Timeout after 3 seconds
#   --start-period=60s: Grace period for application startup
#   --retries=3: Mark unhealthy after 3 consecutive failures

HEALTHCHECK --interval=30s \
            --timeout=3s \
            --start-period=60s \
            --retries=3 \
            CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# -----------------------------------------------------------------------------
# Switch to Non-Root User
# -----------------------------------------------------------------------------
# All subsequent commands and container runtime execute as 'carddemo' user
USER carddemo

# -----------------------------------------------------------------------------
# Runtime Configuration
# -----------------------------------------------------------------------------
# Set JVM options for cloud-native deployment
# Environment variables can override these at runtime

ENV JAVA_OPTS="-Xmx512m \
               -Xms256m \
               -XX:+UseG1GC \
               -XX:MaxGCPauseMillis=200 \
               -XX:+UseStringDeduplication \
               -XX:+OptimizeStringConcat \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=prod"

# JVM Options Explained:
# -Xmx512m: Maximum heap size (512MB) - adjust based on workload
# -Xms256m: Initial heap size (256MB) - reduces startup allocations
# -XX:+UseG1GC: Use G1 garbage collector (optimized for low latency)
# -XX:MaxGCPauseMillis=200: Target max GC pause time of 200ms
# -XX:+UseStringDeduplication: Reduce memory footprint for duplicate strings
# -XX:+OptimizeStringConcat: Optimize string concatenation operations
# -Djava.security.egd=file:/dev/./urandom: Use non-blocking entropy source
# -Dspring.profiles.active=prod: Activate production Spring profile

# -----------------------------------------------------------------------------
# Application Entrypoint
# -----------------------------------------------------------------------------
# ENTRYPOINT defines the executable (cannot be overridden easily)
# CMD provides default arguments (can be overridden at runtime)

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

# Alternative CMD for direct JAR execution (commented out)
# Uncomment below and remove ENTRYPOINT if JAVA_OPTS not needed
# CMD ["java", "-jar", "/app/app.jar"]

# =============================================================================
# BUILD INSTRUCTIONS
# =============================================================================
# To build this image locally:
#   docker build -t carddemo:latest .
#
# To build with custom build arguments:
#   docker build --build-arg MAVEN_VERSION=3.9.5 -t carddemo:latest .
#
# To run the container:
#   docker run -d -p 8080:8080 --name carddemo carddemo:latest
#
# To run with custom JVM options:
#   docker run -d -p 8080:8080 \
#     -e JAVA_OPTS="-Xmx1024m -Xms512m" \
#     --name carddemo carddemo:latest
#
# To run with environment-specific profile:
#   docker run -d -p 8080:8080 \
#     -e SPRING_PROFILES_ACTIVE=dev \
#     --name carddemo carddemo:latest
#
# To check container health:
#   docker inspect --format='{{.State.Health.Status}}' carddemo
#
# To view application logs:
#   docker logs -f carddemo
# =============================================================================

# =============================================================================
# KUBERNETES DEPLOYMENT NOTES
# =============================================================================
# When deploying to Kubernetes, configure the following:
#
# 1. Resource Limits:
#    resources:
#      limits:
#        memory: "768Mi"
#        cpu: "1000m"
#      requests:
#        memory: "512Mi"
#        cpu: "500m"
#
# 2. Liveness Probe:
#    livenessProbe:
#      httpGet:
#        path: /actuator/health/liveness
#        port: 8080
#      initialDelaySeconds: 60
#      periodSeconds: 10
#
# 3. Readiness Probe:
#    readinessProbe:
#      httpGet:
#        path: /actuator/health/readiness
#        port: 8080
#      initialDelaySeconds: 30
#      periodSeconds: 5
#
# 4. Security Context:
#    securityContext:
#      runAsNonRoot: true
#      runAsUser: 1001
#      allowPrivilegeEscalation: false
#      capabilities:
#        drop:
#          - ALL
# =============================================================================
