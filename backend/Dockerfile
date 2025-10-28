# =============================================================================
# CardDemo Backend - Multi-Stage Docker Build
# =============================================================================
# Converted from: IBM z/OS mainframe COBOL application
# Purpose: Containerize Spring Boot 3.4.5 application with Java 21
# Architecture: Multi-stage build for optimized image size and security
# Stage 1: Maven build with JDK 21
# Stage 2: Lightweight Alpine JRE 21 runtime
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build Stage - Maven Compilation
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build

# Set working directory for build
WORKDIR /build

# Copy Maven POM file first for dependency layer caching
# This layer is cached until pom.xml changes, optimizing subsequent builds
COPY pom.xml .

# Download all dependencies in offline mode
# This creates a separate cached layer for dependencies
RUN mvn dependency:go-offline -B

# Copy application source code
# This layer rebuilds only when source code changes
COPY src ./src

# Build the application JAR
# -DskipTests: Skip tests during Docker build (run separately in CI/CD)
# -B: Batch mode for non-interactive builds
# clean: Remove previous build artifacts
# package: Compile and package as executable JAR
RUN mvn clean package -DskipTests -B

# Extract layered JAR for optimal Docker layer caching
# Spring Boot layertools separates dependencies from application code
# Layers: dependencies, spring-boot-loader, snapshot-dependencies, application
WORKDIR /build/target
RUN java -Djarmode=layertools -jar *.jar extract

# -----------------------------------------------------------------------------
# Stage 2: Runtime Stage - Lightweight Alpine JRE
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Install curl for health checks (Alpine package manager)
# --no-cache: Don't cache package index to reduce image size
RUN apk add --no-cache curl

# Create non-root user for security best practices
# -r: System user (UID/GID 1000)
# -s: Shell (no login shell needed)
# carddemo: Username matching application context
RUN addgroup -g 1000 carddemo && \
    adduser -u 1000 -G carddemo -s /bin/sh -D carddemo

# Set working directory for application
WORKDIR /app

# Copy layered JAR artifacts from build stage in optimal order
# Order matters for Docker layer caching - least changing layers first
# 1. Dependencies (changes infrequently - when pom.xml changes)
COPY --from=build /build/target/dependencies/ ./

# 2. Spring Boot loader (changes rarely - only with Spring Boot version upgrade)
COPY --from=build /build/target/spring-boot-loader/ ./

# 3. Snapshot dependencies (changes occasionally - during development)
COPY --from=build /build/target/snapshot-dependencies/ ./

# 4. Application code (changes frequently - with every code change)
COPY --from=build /build/target/application/ ./

# Change ownership of all files to non-root user
RUN chown -R carddemo:carddemo /app

# Switch to non-root user for security
# All subsequent commands and container runtime execute as this user
USER carddemo

# Expose Spring Boot application port
# Default port for REST API endpoints
EXPOSE 8080

# Environment variables for runtime configuration
# SPRING_PROFILES_ACTIVE: Default to production profile (override in docker-compose for dev/test)
# JAVA_OPTS: JVM memory settings
#   -Xms512m: Initial heap size (512MB)
#   -Xmx2048m: Maximum heap size (2GB)
#   Adjust based on container resource limits in Kubernetes
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-Xms512m -Xmx2048m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Health check configuration
# Validates Spring Boot Actuator health endpoint is responding
# --interval=30s: Check every 30 seconds
# --timeout=3s: Fail if check takes longer than 3 seconds
# --start-period=60s: Grace period for application startup
# --retries=3: Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/health || exit 1

# Container entry point
# Uses Spring Boot JarLauncher for layered JAR execution
# $JAVA_OPTS: Expands to JVM memory settings
# -Djava.security.egd: Use non-blocking entropy source for faster startup
# org.springframework.boot.loader.launch.JarLauncher: Spring Boot 3.x launcher class
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom org.springframework.boot.loader.launch.JarLauncher"]

# =============================================================================
# Docker Build and Run Instructions
# =============================================================================
# Build image:
#   docker build -t carddemo-backend:latest -f backend/Dockerfile backend/
#
# Run container (standalone):
#   docker run -p 8080:8080 \
#     -e SPRING_PROFILES_ACTIVE=dev \
#     -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/carddemo \
#     -e SPRING_DATASOURCE_USERNAME=carddemo \
#     -e SPRING_DATASOURCE_PASSWORD=secret \
#     carddemo-backend:latest
#
# Run with Docker Compose (recommended):
#   docker-compose up backend
#
# Image layers:
#   - Base: eclipse-temurin:21-jre-alpine (~150MB)
#   - Dependencies: Maven dependencies (~100MB)
#   - Application: Spring Boot application (~50MB)
#   - Total: ~300MB (vs ~800MB with full JDK)
#
# Security features:
#   - Non-root user execution (UID/GID 1000)
#   - Minimal runtime image (no build tools)
#   - No shell access beyond busybox
#   - Health check validation
#
# Performance optimization:
#   - Layered JAR for efficient caching
#   - Dependency layer cached until pom.xml changes
#   - Application layer rebuilt only on code changes
#   - Container support for accurate memory limits
# =============================================================================
