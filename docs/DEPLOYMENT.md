# CardDemo Deployment Guide

## Table of Contents

1. [Deployment Overview](#1-deployment-overview)
2. [Prerequisites](#2-prerequisites)
3. [Local Development Deployment](#3-local-development-deployment)
4. [Container Image Building](#4-container-image-building)
5. [Kubernetes Deployment](#5-kubernetes-deployment)
6. [Horizontal Pod Autoscaling](#6-horizontal-pod-autoscaling)
7. [Monitoring and Observability](#7-monitoring-and-observability)
8. [Health Checks and Readiness](#8-health-checks-and-readiness)
9. [CI/CD Deployment Automation](#9-cicd-deployment-automation)
10. [Troubleshooting](#10-troubleshooting)
11. [Production Considerations](#11-production-considerations)

---

## 1. Deployment Overview

The CardDemo application has been modernized from a legacy IBM mainframe COBOL/CICS/VSAM stack to a cloud-native Java Spring Boot architecture. This deployment guide provides comprehensive instructions for deploying the application across multiple environments.

### Architecture Summary

- **Frontend**: React 18 single-page application served by Nginx
- **Backend**: Spring Boot 3.2 REST API with embedded Tomcat
- **Database**: PostgreSQL 15+ with Flyway migrations
- **Session Store**: Redis 7.2 for distributed session management
- **Batch Processing**: Spring Batch jobs scheduled via Kubernetes CronJobs
- **Container Orchestration**: Docker containers managed by Kubernetes

### Multi-Environment Strategy

| Environment | Purpose | Kubernetes Cluster | Database | Replicas |
|------------|---------|-------------------|----------|----------|
| **Development** | Local testing | Minikube/Docker Desktop | PostgreSQL (local) | 1 per service |
| **Staging** | Pre-production validation | EKS/GKE/AKS (staging) | PostgreSQL RDS/CloudSQL | 2 per service |
| **Production** | Live system | EKS/GKE/AKS (production) | PostgreSQL RDS/CloudSQL (HA) | 3-10 per service |

### High Availability and Disaster Recovery

- **Multi-zone deployment**: Pods distributed across availability zones
- **Database replication**: PostgreSQL with read replicas
- **Automated backups**: Daily full backups with hourly incrementals
- **Recovery objectives**: RTO 4 hours, RPO 1 hour
- **Health monitoring**: Prometheus metrics with Grafana visualization

### Deployment Tools Required

- **Docker 24.x+**: Container runtime and image building
- **Kubernetes 1.28+**: Container orchestration platform
- **kubectl**: Kubernetes CLI for cluster management
- **Helm 3.x** (optional): Kubernetes package manager
- **Maven 3.9+**: Backend build tool
- **Node.js 20 LTS**: Frontend build tool

---

## 2. Prerequisites

### 2.1 Required Tools and Versions

Ensure the following tools are installed with the specified versions:

#### Container and Orchestration Tools

```bash
# Docker Desktop (includes Docker and kubectl)
docker --version  # Should be 24.x or later
docker-compose --version  # Should be 2.x or later

# Kubernetes CLI
kubectl version --client  # Should be 1.28 or later

# Helm (optional)
helm version  # Should be 3.x or later
```

#### Build Tools

```bash
# Java Development Kit
java -version  # Should be Java 21 LTS

# Maven
mvn -version  # Should be 3.9 or later

# Node.js and npm
node --version  # Should be 20.x LTS
npm --version  # Should be 10.x or later
```

### 2.2 Access Requirements

Before deploying, ensure you have access to:

#### Container Registry

- **Docker Hub**: Public registry for open-source images
- **AWS ECR**: Elastic Container Registry for AWS deployments
- **GCR**: Google Container Registry for GCP deployments
- **ACR**: Azure Container Registry for Azure deployments

Create registry credentials:
```bash
# Docker Hub
docker login

# AWS ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <aws_account_id>.dkr.ecr.us-east-1.amazonaws.com

# GCR
gcloud auth configure-docker

# ACR
az acr login --name <registry-name>
```

#### Kubernetes Cluster Access

Configure kubectl for cluster access:

```bash
# Minikube (local)
minikube start --driver=docker --memory=8192 --cpus=4
kubectl config use-context minikube

# AWS EKS
aws eks update-kubeconfig --name carddemo-cluster --region us-east-1

# GCP GKE
gcloud container clusters get-credentials carddemo-cluster --region us-central1

# Azure AKS
az aks get-credentials --resource-group carddemo-rg --name carddemo-cluster
```

#### GitHub Repository Access

```bash
# Clone the repository
git clone https://github.com/your-org/aws-carddemo-blitzy.git
cd aws-carddemo-blitzy

# Verify branch
git branch
```

#### Database Credentials

Prepare PostgreSQL credentials for each environment:
- **Username**: carddemo
- **Password**: (use strong password, stored in Kubernetes Secrets)
- **Database**: carddemo
- **Port**: 5432

#### Redis Configuration

- **Host**: redis-service (Kubernetes internal DNS)
- **Port**: 6379
- **Password**: (optional, configure in production)

---

## 3. Local Development Deployment

### 3.1 Using Docker Compose

Docker Compose provides the simplest way to run the complete CardDemo stack locally for development and testing.

#### Prerequisites Check

```bash
# Verify Docker is running
docker info

# Verify docker-compose is available
docker-compose --version
```

#### Starting the Application

Navigate to the project root and start all services:

```bash
# Start all services in detached mode
docker-compose up -d

# View logs from all services
docker-compose logs -f

# View logs from specific service
docker-compose logs -f backend
docker-compose logs -f frontend

# Check service status
docker-compose ps
```

Expected output:
```
NAME                    SERVICE             STATUS              PORTS
carddemo-backend        backend             running             0.0.0.0:8080->8080/tcp
carddemo-frontend       frontend            running             0.0.0.0:3000->80/tcp
carddemo-postgres       postgres            running             0.0.0.0:5432->5432/tcp
carddemo-redis          redis               running             0.0.0.0:6379->6379/tcp
```

#### Service Endpoints

Once all services are running, access them at:

- **Frontend Application**: http://localhost:3000
- **Backend API**: http://localhost:8080/api
- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **Spring Boot Actuator**: http://localhost:8080/actuator/health
- **PostgreSQL**: localhost:5432 (username: carddemo, password: carddemo)
- **Redis**: localhost:6379

#### Stopping Services

```bash
# Stop all containers
docker-compose down

# Stop and remove volumes (cleans database state)
docker-compose down -v

# Stop, remove volumes, and remove images
docker-compose down -v --rmi all
```

### 3.2 Database Initialization

#### Automatic Flyway Migrations

Flyway migrations run automatically when the backend starts. The following migrations are applied in order:

1. `V1__create_customer_table.sql`
2. `V2__create_account_table.sql`
3. `V3__create_card_table.sql`
4. `V4__create_transaction_table.sql`
5. `V5__create_user_security_table.sql`
6. `V6__create_xref_tables.sql`
7. `V7__create_indexes.sql`
8. `V8__create_foreign_keys.sql`
9. `V9__load_reference_data.sql`

#### Manual Database Access

Access PostgreSQL directly to verify schema creation:

```bash
# Connect to PostgreSQL container
docker exec -it carddemo-postgres psql -U carddemo -d carddemo

# List all tables
\dt

# View migration history
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;

# Check table row counts
SELECT 'customer' as table_name, COUNT(*) as row_count FROM customer
UNION ALL
SELECT 'account', COUNT(*) FROM account
UNION ALL
SELECT 'card', COUNT(*) FROM card
UNION ALL
SELECT 'transaction', COUNT(*) FROM transaction;

# Exit psql
\q
```

#### Loading Test Data

Execute the test data loading script:

```bash
# Access backend container
docker exec -it carddemo-backend bash

# Navigate to scripts directory
cd /app/scripts

# Run test data loader
./load-test-data.sh

# Verify data loaded
psql -U carddemo -d carddemo -c "SELECT COUNT(*) FROM customer;"
psql -U carddemo -d carddemo -c "SELECT COUNT(*) FROM account;"

# Exit container
exit
```

#### Database Troubleshooting

If migrations fail or database is in an inconsistent state:

```bash
# Check backend logs for migration errors
docker-compose logs backend | grep -i flyway

# Repair Flyway schema (if needed)
docker exec -it carddemo-backend bash
cd /app
mvn flyway:repair

# Manually re-run migrations
mvn flyway:migrate

# Drop and recreate database (development only)
docker-compose down -v
docker-compose up -d postgres
# Wait for PostgreSQL to be ready, then start backend
docker-compose up -d backend
```

---

## 4. Container Image Building

### 4.1 Backend Docker Image

The backend uses a multi-stage Docker build to optimize image size and build time.

#### Dockerfile Structure

Location: `backend/Dockerfile`

```dockerfile
# Stage 1: Maven build stage
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app

# Copy POM and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Add non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy JAR from build stage
COPY --from=build /app/target/carddemo-backend-*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Expose port
EXPOSE 8080

# JVM optimization flags
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# Run application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

#### Building the Backend Image

```bash
# Navigate to backend directory
cd backend

# Build image
docker build -t carddemo-backend:1.0.0 .

# Tag for registry
docker tag carddemo-backend:1.0.0 your-registry/carddemo-backend:1.0.0

# Push to registry
docker push your-registry/carddemo-backend:1.0.0

# Verify image
docker images | grep carddemo-backend
docker inspect carddemo-backend:1.0.0
```

#### Testing Backend Image Locally

```bash
# Run backend container standalone
docker run -d \
  --name carddemo-backend-test \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/carddemo \
  -e SPRING_DATASOURCE_USERNAME=carddemo \
  -e SPRING_DATASOURCE_PASSWORD=carddemo \
  -e SPRING_PROFILES_ACTIVE=dev \
  carddemo-backend:1.0.0

# Check logs
docker logs -f carddemo-backend-test

# Test health endpoint
curl http://localhost:8080/actuator/health

# Stop and remove
docker stop carddemo-backend-test
docker rm carddemo-backend-test
```

### 4.2 Frontend Docker Image

The frontend uses a multi-stage build: Node.js for building the React app, then Nginx for serving static files.

#### Dockerfile Structure

Location: `frontend/Dockerfile`

```dockerfile
# Stage 1: Node build stage
FROM node:20-alpine AS build
WORKDIR /app

# Copy package files and install dependencies
COPY package*.json ./
RUN npm ci --only=production

# Copy source and build
COPY . .
RUN npm run build

# Stage 2: Nginx serve stage
FROM nginx:1.25-alpine
WORKDIR /usr/share/nginx/html

# Remove default nginx files
RUN rm -rf ./*

# Copy built React app from build stage
COPY --from=build /app/build .

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s \
  CMD wget --no-verbose --tries=1 --spider http://localhost:80/ || exit 1

# Expose port
EXPOSE 80

# Run nginx
CMD ["nginx", "-g", "daemon off;"]
```

#### Nginx Configuration

Location: `frontend/nginx.conf`

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # Gzip compression
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;

    # React Router support
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxy (optional, for local development)
    location /api {
        proxy_pass http://backend-service:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
    }

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    # Cache static assets
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

#### Building the Frontend Image

```bash
# Navigate to frontend directory
cd frontend

# Build image
docker build -t carddemo-frontend:1.0.0 .

# Tag for registry
docker tag carddemo-frontend:1.0.0 your-registry/carddemo-frontend:1.0.0

# Push to registry
docker push your-registry/carddemo-frontend:1.0.0

# Verify image size (should be ~50MB)
docker images | grep carddemo-frontend
```

#### Testing Frontend Image Locally

```bash
# Run frontend container
docker run -d \
  --name carddemo-frontend-test \
  -p 3000:80 \
  carddemo-frontend:1.0.0

# Access in browser
open http://localhost:3000

# Check nginx logs
docker logs -f carddemo-frontend-test

# Stop and remove
docker stop carddemo-frontend-test
docker rm carddemo-frontend-test
```

### 4.3 Image Optimization Best Practices

- **Use multi-stage builds**: Separates build dependencies from runtime
- **Use Alpine base images**: Smaller image size (~50MB vs ~500MB)
- **Layer caching**: Order Dockerfile commands for optimal caching
- **Security scanning**: Scan images for vulnerabilities before deployment
- **Image tagging**: Use semantic versioning (1.0.0) and git SHA tags

```bash
# Scan image for vulnerabilities (using Trivy)
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image carddemo-backend:1.0.0

# Scan frontend image
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image carddemo-frontend:1.0.0
```

---

## 5. Kubernetes Deployment

### 5.1 Namespace Creation

Create a dedicated namespace for the CardDemo application to isolate resources:

```bash
# Create namespace
kubectl create namespace carddemo

# Set as default namespace for current context
kubectl config set-context --current --namespace=carddemo

# Verify namespace
kubectl get namespace carddemo
kubectl config view --minify | grep namespace
```

### 5.2 Secrets Configuration

Kubernetes Secrets store sensitive data such as database credentials, JWT secrets, and API keys.

#### Database Credentials Secret

```bash
# Create PostgreSQL credentials secret
kubectl create secret generic postgres-credentials \
  --from-literal=username=carddemo \
  --from-literal=password=$(openssl rand -base64 32) \
  --from-literal=database=carddemo \
  -n carddemo

# Verify secret created
kubectl get secret postgres-credentials -n carddemo
kubectl describe secret postgres-credentials -n carddemo
```

#### JWT Secret for Token Signing

```bash
# Generate 256-bit secret key
JWT_SECRET=$(openssl rand -base64 32)

# Create JWT secret
kubectl create secret generic jwt-secret \
  --from-literal=secret-key="$JWT_SECRET" \
  -n carddemo

# Verify
kubectl get secret jwt-secret -n carddemo
```

#### Docker Registry Credentials

For private container registries:

```bash
# Create Docker registry secret
kubectl create secret docker-registry regcred \
  --docker-server=your-registry.io \
  --docker-username=your-username \
  --docker-password=your-password \
  --docker-email=your-email@example.com \
  -n carddemo

# Verify
kubectl get secret regcred -n carddemo
```

#### Viewing Secret Values (for debugging)

```bash
# Get secret in YAML format
kubectl get secret postgres-credentials -n carddemo -o yaml

# Decode secret value
kubectl get secret postgres-credentials -n carddemo \
  -o jsonpath='{.data.password}' | base64 --decode
echo  # Add newline
```

### 5.3 ConfigMap Creation

ConfigMaps store non-sensitive configuration data:

#### Application ConfigMap

Location: `kubernetes/configmap.yaml`

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: carddemo-config
  namespace: carddemo
data:
  # Spring profiles
  SPRING_PROFILES_ACTIVE: "prod"
  
  # PostgreSQL connection
  POSTGRES_HOST: "postgres-service"
  POSTGRES_PORT: "5432"
  POSTGRES_DB: "carddemo"
  
  # Redis connection
  REDIS_HOST: "redis-service"
  REDIS_PORT: "6379"
  
  # Logging
  LOG_LEVEL: "INFO"
  LOG_FORMAT: "JSON"
  
  # Application settings
  SERVER_PORT: "8080"
  SESSION_TIMEOUT: "24h"
  
  # JWT settings
  JWT_EXPIRATION: "86400"
  
  # Database connection pool
  HIKARI_MINIMUM_IDLE: "10"
  HIKARI_MAXIMUM_POOL_SIZE: "50"
  
  # Batch processing
  BATCH_CHUNK_SIZE: "1000"
```

Apply ConfigMap:

```bash
kubectl apply -f kubernetes/configmap.yaml -n carddemo

# Verify
kubectl get configmap carddemo-config -n carddemo
kubectl describe configmap carddemo-config -n carddemo
```

### 5.4 PostgreSQL Deployment

Deploy PostgreSQL with persistent storage to ensure data durability.

#### Persistent Volume Claim

Location: `kubernetes/postgres-pvc.yaml`

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
  namespace: carddemo
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 20Gi
  storageClassName: standard  # Adjust based on cloud provider
```

**Storage Classes by Provider:**
- AWS EKS: `gp3` or `gp2`
- GCP GKE: `standard` or `standard-rwo`
- Azure AKS: `managed` or `managed-premium`

#### PostgreSQL Deployment Manifest

Location: `kubernetes/postgres-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: carddemo
spec:
  serviceName: postgres-service
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:15.5-alpine
        ports:
        - containerPort: 5432
          name: postgres
        env:
        - name: POSTGRES_DB
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: database
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
        - name: PGDATA
          value: /var/lib/postgresql/data/pgdata
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          exec:
            command:
            - pg_isready
            - -U
            - carddemo
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command:
            - pg_isready
            - -U
            - carddemo
          initialDelaySeconds: 5
          periodSeconds: 5
      volumes:
      - name: postgres-storage
        persistentVolumeClaim:
          claimName: postgres-pvc
```

#### PostgreSQL Service

Location: `kubernetes/postgres-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-service
  namespace: carddemo
spec:
  selector:
    app: postgres
  ports:
  - protocol: TCP
    port: 5432
    targetPort: 5432
  clusterIP: None  # Headless service for StatefulSet
```

#### Deploy PostgreSQL

```bash
# Create PVC
kubectl apply -f kubernetes/postgres-pvc.yaml -n carddemo

# Deploy PostgreSQL
kubectl apply -f kubernetes/postgres-deployment.yaml -n carddemo

# Create Service
kubectl apply -f kubernetes/postgres-service.yaml -n carddemo

# Verify deployment
kubectl get statefulset postgres -n carddemo
kubectl get pods -l app=postgres -n carddemo
kubectl get pvc -n carddemo

# Check logs
kubectl logs -f postgres-0 -n carddemo

# Test connection
kubectl exec -it postgres-0 -n carddemo -- psql -U carddemo -d carddemo -c "SELECT 1;"
```

### 5.5 Redis Deployment

Deploy Redis for session management and caching.

#### Redis Deployment Manifest

Location: `kubernetes/redis-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: carddemo
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
      - name: redis
        image: redis:7.2-alpine
        command:
        - redis-server
        - --maxmemory
        - "2gb"
        - --maxmemory-policy
        - allkeys-lru
        - --save
        - "900 1"
        - --save
        - "300 10"
        ports:
        - containerPort: 6379
          name: redis
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "500m"
        livenessProbe:
          exec:
            command:
            - redis-cli
            - ping
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          exec:
            command:
            - redis-cli
            - ping
          initialDelaySeconds: 5
          periodSeconds: 5
```

#### Redis Service

Location: `kubernetes/redis-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: redis-service
  namespace: carddemo
spec:
  selector:
    app: redis
  ports:
  - protocol: TCP
    port: 6379
    targetPort: 6379
  type: ClusterIP
```

#### Deploy Redis

```bash
# Deploy Redis
kubectl apply -f kubernetes/redis-deployment.yaml -n carddemo
kubectl apply -f kubernetes/redis-service.yaml -n carddemo

# Verify
kubectl get deployment redis -n carddemo
kubectl get pods -l app=redis -n carddemo

# Test Redis
kubectl exec -it deployment/redis -n carddemo -- redis-cli ping
# Should return: PONG
```

### 5.6 Backend Deployment

Deploy the Spring Boot backend application.

#### Backend Deployment Manifest

Location: `kubernetes/backend-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: carddemo-backend
  namespace: carddemo
  labels:
    app: carddemo-backend
    version: v1
spec:
  replicas: 3
  selector:
    matchLabels:
      app: carddemo-backend
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: carddemo-backend
        version: v1
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      imagePullSecrets:
      - name: regcred
      containers:
      - name: backend
        image: your-registry/carddemo-backend:1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        env:
        # Spring profiles
        - name: SPRING_PROFILES_ACTIVE
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: SPRING_PROFILES_ACTIVE
        # Database connection
        - name: SPRING_DATASOURCE_URL
          value: "jdbc:postgresql://$(POSTGRES_HOST):$(POSTGRES_PORT)/$(POSTGRES_DB)"
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-credentials
              key: password
        - name: POSTGRES_HOST
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: POSTGRES_HOST
        - name: POSTGRES_PORT
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: POSTGRES_PORT
        - name: POSTGRES_DB
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: POSTGRES_DB
        # Redis connection
        - name: SPRING_REDIS_HOST
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: REDIS_HOST
        - name: SPRING_REDIS_PORT
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: REDIS_PORT
        # JWT configuration
        - name: JWT_SECRET
          valueFrom:
            secretKeyRef:
              name: jwt-secret
              key: secret-key
        - name: JWT_EXPIRATION
          valueFrom:
            configMapKeyRef:
              name: carddemo-config
              key: JWT_EXPIRATION
        # JVM options
        - name: JAVA_OPTS
          value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -Xlog:gc:file=/tmp/gc.log"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 60
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
            scheme: HTTP
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3
        volumeMounts:
        - name: logs
          mountPath: /app/logs
      volumes:
      - name: logs
        emptyDir: {}
```

#### Backend Service

Location: `kubernetes/backend-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: backend-service
  namespace: carddemo
  labels:
    app: carddemo-backend
spec:
  selector:
    app: carddemo-backend
  ports:
  - protocol: TCP
    port: 8080
    targetPort: 8080
    name: http
  type: ClusterIP
  sessionAffinity: None
```

#### Deploy Backend

```bash
# Apply backend deployment
kubectl apply -f kubernetes/backend-deployment.yaml -n carddemo

# Apply backend service
kubectl apply -f kubernetes/backend-service.yaml -n carddemo

# Check deployment status
kubectl get deployment carddemo-backend -n carddemo
kubectl rollout status deployment/carddemo-backend -n carddemo

# Check pods
kubectl get pods -l app=carddemo-backend -n carddemo

# View logs
kubectl logs -f deployment/carddemo-backend -n carddemo

# Test health endpoint
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  curl -s http://localhost:8080/actuator/health | jq
```

### 5.7 Frontend Deployment

Deploy the React frontend application.

#### Frontend Deployment Manifest

Location: `kubernetes/frontend-deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: carddemo-frontend
  namespace: carddemo
  labels:
    app: carddemo-frontend
    version: v1
spec:
  replicas: 2
  selector:
    matchLabels:
      app: carddemo-frontend
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  template:
    metadata:
      labels:
        app: carddemo-frontend
        version: v1
    spec:
      imagePullSecrets:
      - name: regcred
      containers:
      - name: frontend
        image: your-registry/carddemo-frontend:1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 80
          name: http
          protocol: TCP
        env:
        - name: REACT_APP_API_URL
          value: "http://backend-service:8080/api"
        resources:
          requests:
            memory: "128Mi"
            cpu: "100m"
          limits:
            memory: "256Mi"
            cpu: "200m"
        livenessProbe:
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 10
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
```

#### Frontend Service

Location: `kubernetes/frontend-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: frontend-service
  namespace: carddemo
  labels:
    app: carddemo-frontend
spec:
  selector:
    app: carddemo-frontend
  ports:
  - protocol: TCP
    port: 80
    targetPort: 80
    name: http
  type: ClusterIP
```

#### Deploy Frontend

```bash
# Apply frontend deployment
kubectl apply -f kubernetes/frontend-deployment.yaml -n carddemo

# Apply frontend service
kubectl apply -f kubernetes/frontend-service.yaml -n carddemo

# Check deployment
kubectl get deployment carddemo-frontend -n carddemo
kubectl rollout status deployment/carddemo-frontend -n carddemo

# Check pods
kubectl get pods -l app=carddemo-frontend -n carddemo

# View logs
kubectl logs -f deployment/carddemo-frontend -n carddemo
```

### 5.8 Ingress Configuration

Configure Ingress for external access to the application.

#### Install Nginx Ingress Controller

```bash
# Install Nginx Ingress Controller
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/cloud/deploy.yaml

# Verify installation
kubectl get pods -n ingress-nginx
kubectl get svc -n ingress-nginx
```

#### Ingress Manifest

Location: `kubernetes/ingress.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: carddemo-ingress
  namespace: carddemo
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "300"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "300"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - carddemo.yourdomain.com
    secretName: carddemo-tls
  rules:
  - host: carddemo.yourdomain.com
    http:
      paths:
      # Backend API routes
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: backend-service
            port:
              number: 8080
      # Spring Boot Actuator
      - path: /actuator
        pathType: Prefix
        backend:
          service:
            name: backend-service
            port:
              number: 8080
      # Swagger UI
      - path: /swagger-ui
        pathType: Prefix
        backend:
          service:
            name: backend-service
            port:
              number: 8080
      # Frontend application
      - path: /
        pathType: Prefix
        backend:
          service:
            name: frontend-service
            port:
              number: 80
```

#### Deploy Ingress

```bash
# Apply ingress
kubectl apply -f kubernetes/ingress.yaml -n carddemo

# Get ingress details
kubectl get ingress carddemo-ingress -n carddemo

# Get external IP/hostname
kubectl get ingress carddemo-ingress -n carddemo \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}'

# Describe ingress for troubleshooting
kubectl describe ingress carddemo-ingress -n carddemo
```

### 5.9 Batch Job CronJobs

Deploy Spring Batch jobs as Kubernetes CronJobs for scheduled processing.

#### Interest Calculation CronJob

Location: `kubernetes/cronjobs/interest-calculation-cronjob.yaml`

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: interest-calculation
  namespace: carddemo
spec:
  schedule: "0 2 * * *"  # Daily at 2 AM UTC
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      template:
        metadata:
          labels:
            app: interest-calculation-job
        spec:
          restartPolicy: OnFailure
          containers:
          - name: interest-calculation
            image: your-registry/carddemo-backend:1.0.0
            command: ["java"]
            args:
              - "-jar"
              - "app.jar"
              - "--spring.batch.job.names=interestCalculationJob"
              - "--spring.profiles.active=batch"
            env:
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://postgres-service:5432/carddemo"
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: username
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: password
            resources:
              requests:
                memory: "1Gi"
                cpu: "500m"
              limits:
                memory: "2Gi"
                cpu: "1000m"
```

#### Daily Transaction Processing CronJob

Location: `kubernetes/cronjobs/daily-transaction-cronjob.yaml`

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: daily-transaction-processing
  namespace: carddemo
spec:
  schedule: "0 3 * * *"  # Daily at 3 AM UTC
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      template:
        metadata:
          labels:
            app: daily-transaction-job
        spec:
          restartPolicy: OnFailure
          containers:
          - name: daily-transaction
            image: your-registry/carddemo-backend:1.0.0
            command: ["java"]
            args:
              - "-jar"
              - "app.jar"
              - "--spring.batch.job.names=dailyTransactionProcessingJob"
              - "--spring.profiles.active=batch"
            env:
            - name: SPRING_DATASOURCE_URL
              value: "jdbc:postgresql://postgres-service:5432/carddemo"
            - name: SPRING_DATASOURCE_USERNAME
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: username
            - name: SPRING_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-credentials
                  key: password
            resources:
              requests:
                memory: "1Gi"
                cpu: "500m"
              limits:
                memory: "2Gi"
                cpu: "1000m"
```

#### Deploy All CronJobs

```bash
# Deploy all CronJobs
kubectl apply -f kubernetes/cronjobs/ -n carddemo

# List all CronJobs
kubectl get cronjobs -n carddemo

# View specific CronJob
kubectl describe cronjob interest-calculation -n carddemo

# Manually trigger a CronJob
kubectl create job --from=cronjob/interest-calculation \
  interest-calculation-manual-$(date +%s) -n carddemo

# View job execution history
kubectl get jobs -n carddemo

# View job logs
JOB_NAME=$(kubectl get jobs -n carddemo -l app=interest-calculation-job \
  -o jsonpath='{.items[0].metadata.name}')
kubectl logs job/$JOB_NAME -n carddemo
```

---

## 6. Horizontal Pod Autoscaling

Configure Horizontal Pod Autoscaler (HPA) to automatically scale pods based on CPU and memory usage.

### 6.1 Prerequisites

Ensure Metrics Server is installed:

```bash
# Install Metrics Server
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

# Verify Metrics Server is running
kubectl get deployment metrics-server -n kube-system

# Test metrics
kubectl top nodes
kubectl top pods -n carddemo
```

### 6.2 Backend HPA Configuration

```bash
# Create HPA for backend
kubectl autoscale deployment carddemo-backend \
  --cpu-percent=70 \
  --min=3 \
  --max=10 \
  -n carddemo

# Or apply HPA manifest
cat <<EOF | kubectl apply -f -
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: carddemo-backend-hpa
  namespace: carddemo
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: carddemo-backend
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 0
      policies:
      - type: Percent
        value: 100
        periodSeconds: 15
      - type: Pods
        value: 2
        periodSeconds: 15
      selectPolicy: Max
EOF
```

### 6.3 Frontend HPA Configuration

```bash
# Create HPA for frontend
kubectl autoscale deployment carddemo-frontend \
  --cpu-percent=70 \
  --min=2 \
  --max=5 \
  -n carddemo
```

### 6.4 Monitor HPA

```bash
# View HPA status
kubectl get hpa -n carddemo

# Watch HPA in real-time
kubectl get hpa -n carddemo --watch

# Describe HPA for details
kubectl describe hpa carddemo-backend-hpa -n carddemo

# View HPA events
kubectl get events -n carddemo --field-selector involvedObject.name=carddemo-backend-hpa
```

### 6.5 Load Testing HPA

Test autoscaling with load generation:

```bash
# Start a load generator pod
kubectl run load-generator \
  --image=busybox \
  --restart=Never \
  -n carddemo \
  -- /bin/sh -c "while true; do wget -q -O- http://backend-service:8080/actuator/health; done"

# Watch pod scaling
kubectl get hpa carddemo-backend-hpa -n carddemo --watch

# Clean up load generator
kubectl delete pod load-generator -n carddemo
```

---

## 7. Monitoring and Observability

### 7.1 Prometheus Metrics Collection

Deploy Prometheus for comprehensive metrics collection.

#### Install Prometheus Operator

```bash
# Add Prometheus Helm repository
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# Install Prometheus Operator with Grafana
helm install prometheus prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --create-namespace \
  --set prometheus.prometheusSpec.retention=30d \
  --set prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage=50Gi

# Verify installation
kubectl get pods -n monitoring
kubectl get svc -n monitoring
```

#### Access Prometheus UI

```bash
# Port forward to Prometheus
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090

# Open browser
open http://localhost:9090
```

#### Configure ServiceMonitor for CardDemo

Location: `kubernetes/monitoring/servicemonitor.yaml`

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: carddemo-backend-monitor
  namespace: monitoring
  labels:
    app: carddemo-backend
spec:
  selector:
    matchLabels:
      app: carddemo-backend
  namespaceSelector:
    matchNames:
    - carddemo
  endpoints:
  - port: http
    path: /actuator/prometheus
    interval: 30s
```

Apply ServiceMonitor:

```bash
kubectl apply -f kubernetes/monitoring/servicemonitor.yaml
```

### 7.2 Grafana Dashboards

Access and configure Grafana for visualization.

#### Access Grafana

```bash
# Get Grafana admin password
kubectl get secret -n monitoring prometheus-grafana \
  -o jsonpath="{.data.admin-password}" | base64 --decode
echo  # Add newline

# Port forward to Grafana
kubectl port-forward -n monitoring svc/prometheus-grafana 3000:80

# Login credentials
# Username: admin
# Password: <from above command>

# Open browser
open http://localhost:3000
```

#### Import CardDemo Dashboard

Create custom dashboard for CardDemo metrics:

Location: `kubernetes/monitoring/grafana-dashboard.json`

```json
{
  "dashboard": {
    "title": "CardDemo Application Metrics",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [
          {
            "expr": "rate(http_server_requests_seconds_count{namespace=\"carddemo\"}[5m])"
          }
        ]
      },
      {
        "title": "Response Time (95th percentile)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{namespace=\"carddemo\"}[5m]))"
          }
        ]
      },
      {
        "title": "Error Rate",
        "targets": [
          {
            "expr": "rate(http_server_requests_seconds_count{namespace=\"carddemo\",status=~\"5..\"}[5m])"
          }
        ]
      },
      {
        "title": "JVM Heap Usage",
        "targets": [
          {
            "expr": "jvm_memory_used_bytes{namespace=\"carddemo\",area=\"heap\"} / jvm_memory_max_bytes{namespace=\"carddemo\",area=\"heap\"} * 100"
          }
        ]
      },
      {
        "title": "Database Connection Pool",
        "targets": [
          {
            "expr": "hikaricp_connections_active{namespace=\"carddemo\"}"
          }
        ]
      }
    ]
  }
}
```

Import dashboard in Grafana UI:
1. Click "+" icon → Import
2. Upload `grafana-dashboard.json`
3. Select Prometheus data source
4. Click Import

### 7.3 ELK Stack Logging

Deploy Elasticsearch, Logstash, and Kibana for centralized logging.

#### Install Elastic Stack

```bash
# Add Elastic Helm repository
helm repo add elastic https://helm.elastic.co
helm repo update

# Install Elasticsearch
helm install elasticsearch elastic/elasticsearch \
  --namespace logging \
  --create-namespace \
  --set replicas=3 \
  --set volumeClaimTemplate.resources.requests.storage=30Gi

# Install Kibana
helm install kibana elastic/kibana \
  --namespace logging \
  --set service.type=ClusterIP

# Install Logstash
helm install logstash elastic/logstash \
  --namespace logging

# Verify installation
kubectl get pods -n logging
```

#### Configure Logstash Pipeline

Create Logstash configuration for CardDemo logs:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: logstash-config
  namespace: logging
data:
  logstash.conf: |
    input {
      beats {
        port => 5044
      }
    }
    filter {
      json {
        source => "message"
      }
      date {
        match => ["timestamp", "ISO8601"]
        target => "@timestamp"
      }
    }
    output {
      elasticsearch {
        hosts => ["elasticsearch-master:9200"]
        index => "carddemo-%{+YYYY.MM.dd}"
      }
    }
```

#### Access Kibana

```bash
# Port forward to Kibana
kubectl port-forward -n logging svc/kibana-kibana 5601:5601

# Open browser
open http://localhost:5601

# Configure index pattern: carddemo-*
```

#### Configure Application Logging

Update backend to send structured logs:

```yaml
# backend/src/main/resources/logback-spring.xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"application":"carddemo"}</customFields>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

### 7.4 Application Performance Monitoring

Key metrics to monitor:

- **Request throughput**: Requests per second
- **Response times**: p50, p95, p99 latencies
- **Error rates**: 4xx and 5xx responses
- **JVM metrics**: Heap usage, GC pauses, thread count
- **Database metrics**: Connection pool usage, query times
- **Redis metrics**: Cache hit/miss ratio, memory usage
- **Kubernetes metrics**: Pod CPU/memory, restarts, availability

---

## 8. Health Checks and Readiness

Verify all services are healthy and ready to accept traffic.

### 8.1 Application Health Checks

#### Check All Pods

```bash
# Get all pods in carddemo namespace
kubectl get pods -n carddemo

# Expected output:
# NAME                                 READY   STATUS    RESTARTS   AGE
# carddemo-backend-xxx                 1/1     Running   0          5m
# carddemo-frontend-xxx                1/1     Running   0          5m
# postgres-0                           1/1     Running   0          10m
# redis-xxx                            1/1     Running   0          10m
```

#### Check Services

```bash
# Get all services
kubectl get svc -n carddemo

# Test service DNS resolution
kubectl run test-dns --image=busybox:1.28 --rm -it --restart=Never -n carddemo -- \
  nslookup backend-service

kubectl run test-dns --image=busybox:1.28 --rm -it --restart=Never -n carddemo -- \
  nslookup postgres-service
```

### 8.2 Backend Health Checks

```bash
# Port forward to backend
kubectl port-forward -n carddemo svc/backend-service 8080:8080 &

# Test liveness endpoint
curl http://localhost:8080/actuator/health/liveness
# Expected: {"status":"UP"}

# Test readiness endpoint
curl http://localhost:8080/actuator/health/readiness
# Expected: {"status":"UP"}

# Test full health endpoint with details
curl http://localhost:8080/actuator/health | jq
# Expected JSON with database, diskSpace, redis status

# Stop port forward
killall kubectl
```

### 8.3 Database Connectivity

```bash
# Test database connection from backend pod
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  curl -s http://localhost:8080/actuator/health | jq '.components.db'

# Direct database test
kubectl exec -it postgres-0 -n carddemo -- \
  psql -U carddemo -d carddemo -c "SELECT version();"

kubectl exec -it postgres-0 -n carddemo -- \
  psql -U carddemo -d carddemo -c "SELECT COUNT(*) FROM customer;"
```

### 8.4 Redis Connectivity

```bash
# Test Redis connection
kubectl exec -it deployment/redis -n carddemo -- redis-cli ping
# Expected: PONG

# Test Redis from backend
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  curl -s http://localhost:8080/actuator/health | jq '.components.redis'

# Check Redis memory usage
kubectl exec -it deployment/redis -n carddemo -- redis-cli INFO memory
```

### 8.5 Frontend Health Checks

```bash
# Port forward to frontend
kubectl port-forward -n carddemo svc/frontend-service 3000:80 &

# Test frontend loads
curl -I http://localhost:3000
# Expected: HTTP/1.1 200 OK

# Stop port forward
killall kubectl
```

### 8.6 Ingress Health Checks

```bash
# Check ingress controller
kubectl get pods -n ingress-nginx

# Check ingress resource
kubectl describe ingress carddemo-ingress -n carddemo

# Test ingress endpoint (replace with your domain)
curl -I https://carddemo.yourdomain.com
# Expected: HTTP/2 200
```

### 8.7 Overall System Health Script

Create a comprehensive health check script:

```bash
#!/bin/bash
# health-check.sh

echo "=== CardDemo System Health Check ==="
echo ""

echo "1. Checking namespace..."
kubectl get namespace carddemo

echo ""
echo "2. Checking pods..."
kubectl get pods -n carddemo

echo ""
echo "3. Checking services..."
kubectl get svc -n carddemo

echo ""
echo "4. Checking backend health..."
BACKEND_POD=$(kubectl get pod -n carddemo -l app=carddemo-backend -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n carddemo $BACKEND_POD -- curl -s http://localhost:8080/actuator/health | jq '.status'

echo ""
echo "5. Checking database..."
kubectl exec -n carddemo postgres-0 -- psql -U carddemo -d carddemo -c "SELECT 1" -t

echo ""
echo "6. Checking Redis..."
REDIS_POD=$(kubectl get pod -n carddemo -l app=redis -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n carddemo $REDIS_POD -- redis-cli ping

echo ""
echo "7. Checking HPA..."
kubectl get hpa -n carddemo

echo ""
echo "8. Checking CronJobs..."
kubectl get cronjobs -n carddemo

echo ""
echo "=== Health Check Complete ==="
```

Run health check:

```bash
chmod +x health-check.sh
./health-check.sh
```

---

## 9. CI/CD Deployment Automation

Automate deployment using GitHub Actions for continuous integration and continuous deployment.

### 9.1 GitHub Actions Workflow

Create deployment workflow that builds, tests, and deploys the application.

Location: `.github/workflows/deploy.yml`

```yaml
name: Deploy to Kubernetes

on:
  push:
    branches:
      - main
  workflow_dispatch:
    inputs:
      environment:
        description: 'Environment to deploy'
        required: true
        default: 'staging'
        type: choice
        options:
          - staging
          - production

env:
  AWS_REGION: us-east-1
  ECR_REPOSITORY_BACKEND: carddemo-backend
  ECR_REPOSITORY_FRONTEND: carddemo-frontend
  CLUSTER_NAME: carddemo-cluster

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v3
      
      - name: Set up Java 21
        uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'maven'
      
      - name: Set up Node.js 20
        uses: actions/setup-node@v3
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json
      
      - name: Run backend tests
        run: |
          cd backend
          mvn clean test
      
      - name: Run frontend tests
        run: |
          cd frontend
          npm ci
          npm test
      
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v2
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}
      
      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v1
      
      - name: Build and push backend image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          cd backend
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:$IMAGE_TAG .
          docker tag $ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:$IMAGE_TAG \
                     $ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:latest
          docker push $ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:latest
      
      - name: Build and push frontend image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          cd frontend
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:$IMAGE_TAG .
          docker tag $ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:$IMAGE_TAG \
                     $ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:latest
          docker push $ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:latest
      
      - name: Update Kubernetes manifests
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          sed -i "s|your-registry/carddemo-backend:.*|$ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:$IMAGE_TAG|g" \
            kubernetes/backend-deployment.yaml
          sed -i "s|your-registry/carddemo-frontend:.*|$ECR_REGISTRY/$ECR_REPOSITORY_FRONTEND:$IMAGE_TAG|g" \
            kubernetes/frontend-deployment.yaml
          sed -i "s|your-registry/carddemo-backend:.*|$ECR_REGISTRY/$ECR_REPOSITORY_BACKEND:$IMAGE_TAG|g" \
            kubernetes/cronjobs/*.yaml
      
      - name: Update kubeconfig
        run: |
          aws eks update-kubeconfig --name $CLUSTER_NAME --region $AWS_REGION
      
      - name: Deploy to Kubernetes
        run: |
          # Apply ConfigMaps and Secrets (if changed)
          kubectl apply -f kubernetes/configmap.yaml -n carddemo
          
          # Deploy backend
          kubectl apply -f kubernetes/backend-deployment.yaml -n carddemo
          kubectl apply -f kubernetes/backend-service.yaml -n carddemo
          
          # Deploy frontend
          kubectl apply -f kubernetes/frontend-deployment.yaml -n carddemo
          kubectl apply -f kubernetes/frontend-service.yaml -n carddemo
          
          # Deploy CronJobs
          kubectl apply -f kubernetes/cronjobs/ -n carddemo
      
      - name: Wait for backend rollout
        run: |
          kubectl rollout status deployment/carddemo-backend -n carddemo --timeout=5m
      
      - name: Wait for frontend rollout
        run: |
          kubectl rollout status deployment/carddemo-frontend -n carddemo --timeout=5m
      
      - name: Verify deployment
        run: |
          # Check pod status
          kubectl get pods -n carddemo
          
          # Test backend health
          BACKEND_POD=$(kubectl get pod -n carddemo -l app=carddemo-backend \
            -o jsonpath='{.items[0].metadata.name}')
          kubectl exec -n carddemo $BACKEND_POD -- \
            curl -f http://localhost:8080/actuator/health/readiness
      
      - name: Notify deployment success
        if: success()
        run: |
          echo "Deployment successful!"
          echo "Backend image: ${{ steps.login-ecr.outputs.registry }}/${{ env.ECR_REPOSITORY_BACKEND }}:${{ github.sha }}"
          echo "Frontend image: ${{ steps.login-ecr.outputs.registry }}/${{ env.ECR_REPOSITORY_FRONTEND }}:${{ github.sha }}"
```

### 9.2 Environment-Specific Deployments

Create separate workflows for staging and production:

`.github/workflows/deploy-staging.yml`:
```yaml
name: Deploy to Staging
on:
  push:
    branches:
      - develop
```

`.github/workflows/deploy-production.yml`:
```yaml
name: Deploy to Production
on:
  push:
    branches:
      - main
    tags:
      - 'v*'
```

### 9.3 Required GitHub Secrets

Configure the following secrets in GitHub repository settings:

```
AWS_ACCESS_KEY_ID          # AWS credentials for ECR and EKS access
AWS_SECRET_ACCESS_KEY      # AWS credentials
KUBECONFIG_STAGING         # Kubernetes config for staging
KUBECONFIG_PRODUCTION      # Kubernetes config for production
POSTGRES_PASSWORD_STAGING  # Database password for staging
POSTGRES_PASSWORD_PROD     # Database password for production
JWT_SECRET_STAGING         # JWT secret for staging
JWT_SECRET_PROD            # JWT secret for production
```

### 9.4 Deployment Approvals

Add manual approval for production deployments:

```yaml
jobs:
  deploy-production:
    runs-on: ubuntu-latest
    environment:
      name: production
      url: https://carddemo.yourdomain.com
    steps:
      # ... deployment steps
```

Configure environment protection rules in GitHub:
1. Go to Settings → Environments
2. Add "production" environment
3. Enable "Required reviewers"
4. Add deployment branch restrictions

### 9.5 Rollback Procedure

Create rollback workflow:

`.github/workflows/rollback.yml`:
```yaml
name: Rollback Deployment

on:
  workflow_dispatch:
    inputs:
      revision:
        description: 'Revision number to rollback to'
        required: true
        type: string

jobs:
  rollback:
    runs-on: ubuntu-latest
    steps:
      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v2
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1
      
      - name: Update kubeconfig
        run: |
          aws eks update-kubeconfig --name carddemo-cluster --region us-east-1
      
      - name: Rollback backend deployment
        run: |
          kubectl rollout undo deployment/carddemo-backend \
            --to-revision=${{ inputs.revision }} -n carddemo
      
      - name: Rollback frontend deployment
        run: |
          kubectl rollout undo deployment/carddemo-frontend \
            --to-revision=${{ inputs.revision }} -n carddemo
      
      - name: Verify rollback
        run: |
          kubectl rollout status deployment/carddemo-backend -n carddemo
          kubectl rollout status deployment/carddemo-frontend -n carddemo
```

---

## 10. Troubleshooting

Common issues and solutions for CardDemo deployment.

### 10.1 Pod Not Starting

#### Symptoms
- Pod stuck in `Pending`, `CrashLoopBackOff`, or `ImagePullBackOff` state

#### Diagnosis

```bash
# Check pod status
kubectl get pods -n carddemo

# Describe pod for events
kubectl describe pod <pod-name> -n carddemo

# Check pod logs
kubectl logs <pod-name> -n carddemo

# Check previous logs if pod restarted
kubectl logs <pod-name> -n carddemo --previous
```

#### Common Causes and Solutions

**ImagePullBackOff**:
```bash
# Verify image exists in registry
docker pull your-registry/carddemo-backend:1.0.0

# Check registry credentials
kubectl get secret regcred -n carddemo -o yaml

# Recreate registry secret if needed
kubectl delete secret regcred -n carddemo
kubectl create secret docker-registry regcred \
  --docker-server=your-registry.io \
  --docker-username=your-username \
  --docker-password=your-password \
  -n carddemo
```

**CrashLoopBackOff** (Application Error):
```bash
# Check application logs
kubectl logs deployment/carddemo-backend -n carddemo

# Common issues:
# 1. Database connection failure - verify postgres-credentials secret
# 2. Missing environment variables - check configmap
# 3. Application startup error - check application.yml configuration
```

**Insufficient Resources**:
```bash
# Check node resources
kubectl describe nodes

# Check resource requests
kubectl describe pod <pod-name> -n carddemo | grep -A 5 Requests

# Solution: Adjust resource requests or add nodes to cluster
```

### 10.2 Database Connection Issues

#### Symptoms
- Backend logs show "Connection refused" or "Connection timeout"
- Health check shows database down

#### Diagnosis

```bash
# Check PostgreSQL pod status
kubectl get pods -l app=postgres -n carddemo

# Check PostgreSQL logs
kubectl logs postgres-0 -n carddemo

# Test database connectivity from backend pod
kubectl exec -it deployment/carddemo-backend -n carddemo -- bash
curl -v telnet://postgres-service:5432
# Should connect successfully

# Test PostgreSQL authentication
psql -h postgres-service -U carddemo -d carddemo
```

#### Common Solutions

**Service Not Found**:
```bash
# Verify PostgreSQL service exists
kubectl get svc postgres-service -n carddemo

# Check service endpoints
kubectl get endpoints postgres-service -n carddemo

# Recreate service if needed
kubectl delete svc postgres-service -n carddemo
kubectl apply -f kubernetes/postgres-service.yaml -n carddemo
```

**Authentication Failure**:
```bash
# Verify credentials secret
kubectl get secret postgres-credentials -n carddemo

# Check if backend is using correct credentials
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  env | grep DATASOURCE

# Recreate secret with correct credentials
kubectl delete secret postgres-credentials -n carddemo
kubectl create secret generic postgres-credentials \
  --from-literal=username=carddemo \
  --from-literal=password=YOUR_PASSWORD \
  -n carddemo

# Restart backend to pick up new credentials
kubectl rollout restart deployment/carddemo-backend -n carddemo
```

**Connection Pool Exhaustion**:
```bash
# Check HikariCP metrics
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  curl http://localhost:8080/actuator/metrics/hikaricp.connections.active | jq

# Increase max pool size in ConfigMap
# Edit kubernetes/configmap.yaml and increase HIKARI_MAXIMUM_POOL_SIZE
kubectl apply -f kubernetes/configmap.yaml -n carddemo
kubectl rollout restart deployment/carddemo-backend -n carddemo
```

### 10.3 Ingress Not Working

#### Symptoms
- Cannot access application from external URL
- 404 or 503 errors from ingress

#### Diagnosis

```bash
# Check ingress controller pods
kubectl get pods -n ingress-nginx

# Check ingress resource
kubectl get ingress carddemo-ingress -n carddemo

# Describe ingress for events
kubectl describe ingress carddemo-ingress -n carddemo

# Check ingress controller logs
kubectl logs -n ingress-nginx deployment/ingress-nginx-controller
```

#### Common Solutions

**Ingress Address Not Assigned**:
```bash
# Check if LoadBalancer service has external IP
kubectl get svc -n ingress-nginx

# If pending, check cloud provider load balancer provisioning
# AWS: Check ELB in AWS Console
# GCP: Check Load Balancers in GCP Console
```

**Backend Service Unreachable**:
```bash
# Test backend service directly
kubectl port-forward svc/backend-service 8080:8080 -n carddemo &
curl http://localhost:8080/actuator/health
killall kubectl

# If backend works but ingress doesn't, check ingress paths
kubectl get ingress carddemo-ingress -n carddemo -o yaml
```

**TLS Certificate Issues**:
```bash
# Check certificate secret
kubectl get secret carddemo-tls -n carddemo

# Describe certificate for status
kubectl describe certificate carddemo-tls -n carddemo

# Check cert-manager logs if using cert-manager
kubectl logs -n cert-manager deployment/cert-manager
```

### 10.4 High Memory or CPU Usage

#### Symptoms
- Pods being OOMKilled or throttled
- Application performance degradation

#### Diagnosis

```bash
# Check pod resource usage
kubectl top pods -n carddemo

# Check pod resource limits
kubectl describe pod <pod-name> -n carddemo | grep -A 10 Limits

# Check HPA status
kubectl get hpa -n carddemo
```

#### Solutions

**JVM Heap Too Large**:
```bash
# Adjust JVM memory settings
# Edit backend deployment JAVA_OPTS
kubectl edit deployment carddemo-backend -n carddemo

# Set MaxRAMPercentage to 75% of container memory limit
# Example for 2Gi limit: -XX:MaxRAMPercentage=75.0
```

**Memory Leak**:
```bash
# Generate heap dump for analysis
kubectl exec -it deployment/carddemo-backend -n carddemo -- \
  jcmd 1 GC.heap_dump /tmp/heapdump.hprof

# Copy heap dump locally
kubectl cp carddemo/<pod-name>:/tmp/heapdump.hprof ./heapdump.hprof

# Analyze with Eclipse MAT or VisualVM
```

**CPU Throttling**:
```bash
# Check if CPU limits are too low
kubectl top pods -n carddemo

# Increase CPU limits
kubectl edit deployment carddemo-backend -n carddemo
# Adjust resources.limits.cpu
```

### 10.5 Batch Jobs Not Running

#### Symptoms
- CronJobs not executing on schedule
- Jobs failing consistently

#### Diagnosis

```bash
# List CronJobs
kubectl get cronjobs -n carddemo

# Check CronJob schedule
kubectl describe cronjob interest-calculation -n carddemo

# View recent jobs
kubectl get jobs -n carddemo

# Check specific job logs
kubectl logs job/<job-name> -n carddemo
```

#### Solutions

**Incorrect Schedule**:
```bash
# Verify cron schedule format (UTC timezone)
kubectl get cronjob interest-calculation -n carddemo -o yaml | grep schedule

# Test cron schedule syntax at https://crontab.guru

# Update schedule if needed
kubectl edit cronjob interest-calculation -n carddemo
```

**Job Resource Limits Too Low**:
```bash
# Check job pod status
kubectl get pods -n carddemo | grep interest-calculation

# Describe failed pod
kubectl describe pod <job-pod-name> -n carddemo

# Increase memory/CPU limits in CronJob spec
kubectl edit cronjob interest-calculation -n carddemo
```

**Database Connection from Job**:
```bash
# Manually run job for testing
kubectl create job --from=cronjob/interest-calculation \
  test-job-$(date +%s) -n carddemo

# Check job logs
kubectl logs job/test-job-* -n carddemo

# Verify database credentials in job spec
kubectl get cronjob interest-calculation -n carddemo -o yaml | grep -A 10 env
```

### 10.6 Slow Response Times

#### Symptoms
- API response times exceed 200ms target
- Frontend slow to load

#### Diagnosis

```bash
# Check Prometheus metrics for response times
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090 &
# Open http://localhost:9090
# Query: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{namespace="carddemo"}[5m]))

# Check database query performance
kubectl exec -it postgres-0 -n carddemo -- psql -U carddemo -d carddemo
# Enable slow query log
ALTER SYSTEM SET log_min_duration_statement = 100;
SELECT pg_reload_conf();
```

#### Solutions

**Database Query Optimization**:
```sql
-- Check for missing indexes
SELECT schemaname, tablename, indexname
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename;

-- Analyze slow queries
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
ORDER BY mean_exec_time DESC
LIMIT 10;
```

**Connection Pool Tuning**:
```bash
# Increase HikariCP pool size
kubectl edit configmap carddemo-config -n carddemo
# Increase HIKARI_MAXIMUM_POOL_SIZE to 50

# Restart backend
kubectl rollout restart deployment/carddemo-backend -n carddemo
```

**Enable Response Compression**:
```bash
# Enable gzip compression in Spring Boot
# Add to application.yml:
server:
  compression:
    enabled: true
    mime-types: application/json,application/xml,text/html,text/xml,text/plain
```

### 10.7 Rollback Procedures

When issues occur after deployment, rollback to previous version.

#### View Deployment History

```bash
# View rollout history
kubectl rollout history deployment/carddemo-backend -n carddemo

# View specific revision
kubectl rollout history deployment/carddemo-backend -n carddemo --revision=3
```

#### Rollback to Previous Version

```bash
# Rollback to previous revision
kubectl rollout undo deployment/carddemo-backend -n carddemo

# Rollback to specific revision
kubectl rollout undo deployment/carddemo-backend -n carddemo --to-revision=2

# Check rollback status
kubectl rollout status deployment/carddemo-backend -n carddemo
```

#### Rollback Both Frontend and Backend

```bash
# Rollback backend
kubectl rollout undo deployment/carddemo-backend -n carddemo

# Rollback frontend
kubectl rollout undo deployment/carddemo-frontend -n carddemo

# Verify both rollbacks
kubectl get pods -n carddemo
kubectl rollout status deployment/carddemo-backend -n carddemo
kubectl rollout status deployment/carddemo-frontend -n carddemo
```

#### Database Migration Rollback

If database migrations need to be rolled back:

```bash
# Access backend container
kubectl exec -it deployment/carddemo-backend -n carddemo -- bash

# Use Flyway repair (development only)
mvn flyway:repair

# Manually rollback specific migration
psql -U carddemo -d carddemo
DELETE FROM flyway_schema_history WHERE version = 'X';
# Then manually revert schema changes

# PRODUCTION: Restore from backup instead
```

---

## 11. Production Considerations

Critical considerations for deploying CardDemo to production environments.

### 11.1 High Availability

Ensure the application can withstand failures and maintain uptime.

#### Multi-Zone Deployment

Deploy pods across multiple availability zones:

```yaml
# Pod Anti-Affinity for Backend
apiVersion: apps/v1
kind: Deployment
metadata:
  name: carddemo-backend
spec:
  template:
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - carddemo-backend
            topologyKey: topology.kubernetes.io/zone
```

#### Minimum Replica Counts

- **Backend**: Minimum 3 replicas across 3 zones
- **Frontend**: Minimum 2 replicas across 2 zones
- **PostgreSQL**: Primary + 2 read replicas
- **Redis**: Redis Cluster with 3 master + 3 replica nodes

#### Database High Availability

For production PostgreSQL:

```bash
# AWS RDS Multi-AZ
aws rds create-db-instance \
  --db-instance-identifier carddemo-prod \
  --db-instance-class db.r6g.xlarge \
  --engine postgres \
  --engine-version 15.5 \
  --master-username carddemo \
  --master-user-password $DB_PASSWORD \
  --allocated-storage 100 \
  --storage-type gp3 \
  --multi-az \
  --backup-retention-period 30

# GCP Cloud SQL High Availability
gcloud sql instances create carddemo-prod \
  --database-version=POSTGRES_15 \
  --tier=db-custom-4-16384 \
  --region=us-central1 \
  --availability-type=REGIONAL \
  --backup-start-time=02:00
```

### 11.2 Backup and Disaster Recovery

Implement comprehensive backup strategy.

#### PostgreSQL Automated Backups

```bash
# AWS RDS automated backups (configured above)
# Retention: 30 days
# Backup window: 02:00-03:00 UTC

# Manual snapshot before major changes
aws rds create-db-snapshot \
  --db-instance-identifier carddemo-prod \
  --db-snapshot-identifier carddemo-prod-pre-migration-$(date +%Y%m%d)

# Restore from snapshot
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier carddemo-prod-restored \
  --db-snapshot-identifier carddemo-prod-pre-migration-20240115
```

#### Kubernetes Backup with Velero

```bash
# Install Velero
velero install \
  --provider aws \
  --bucket carddemo-backups \
  --secret-file ./credentials-velero \
  --backup-location-config region=us-east-1

# Schedule daily backups
velero schedule create carddemo-daily \
  --schedule="0 2 * * *" \
  --include-namespaces carddemo \
  --ttl 720h0m0s

# Manual backup before deployment
velero backup create carddemo-pre-deploy-$(date +%Y%m%d)

# Restore from backup
velero restore create --from-backup carddemo-pre-deploy-20240115
```

#### Recovery Time and Point Objectives

- **RTO (Recovery Time Objective)**: 4 hours
  - Time to restore full system from backups
  - Includes database restore, application deployment, verification
  
- **RPO (Recovery Point Objective)**: 1 hour
  - Maximum data loss acceptable
  - Achieved through hourly transaction log backups

#### Disaster Recovery Plan

1. **Database Restoration**:
   ```bash
   # Restore RDS from latest snapshot
   aws rds restore-db-instance-from-db-snapshot ...
   ```

2. **Application Redeployment**:
   ```bash
   # Restore from Velero backup
   velero restore create --from-backup carddemo-latest
   ```

3. **Verification**:
   ```bash
   # Run health checks
   ./health-check.sh
   # Verify data integrity
   # Run smoke tests
   ```

### 11.3 Security Hardening

Implement security best practices for production.

#### Network Policies

Restrict pod-to-pod communication:

```yaml
# Network Policy for Backend
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-network-policy
  namespace: carddemo
spec:
  podSelector:
    matchLabels:
      app: carddemo-backend
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: carddemo-frontend
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: postgres
    ports:
    - protocol: TCP
      port: 5432
  - to:
    - podSelector:
        matchLabels:
          app: redis
    ports:
    - protocol: TCP
      port: 6379
```

#### Pod Security Standards

```yaml
# Pod Security Policy
apiVersion: v1
kind: Namespace
metadata:
  name: carddemo
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

#### Secrets Encryption at Rest

```bash
# Enable secrets encryption in Kubernetes
# Create EncryptionConfiguration
cat <<EOF > encryption-config.yaml
apiVersion: apiserver.config.k8s.io/v1
kind: EncryptionConfiguration
resources:
  - resources:
    - secrets
    providers:
    - aescbc:
        keys:
        - name: key1
          secret: $(head -c 32 /dev/urandom | base64)
    - identity: {}
EOF

# Configure API server to use encryption config
# (Cloud provider specific - consult documentation)
```

#### TLS Certificates

Use cert-manager for automatic certificate management:

```bash
# Install cert-manager
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Create ClusterIssuer for Let's Encrypt
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: admin@yourdomain.com
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          class: nginx
EOF
```

#### Image Scanning

Scan container images for vulnerabilities:

```bash
# Scan with Trivy
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image \
  --severity HIGH,CRITICAL \
  your-registry/carddemo-backend:1.0.0

# Fail build if vulnerabilities found
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  aquasec/trivy image \
  --exit-code 1 \
  --severity CRITICAL \
  your-registry/carddemo-backend:1.0.0
```

### 11.4 Performance Tuning

Optimize application performance for production workloads.

#### JVM Tuning

```yaml
env:
- name: JAVA_OPTS
  value: |
    -XX:+UseContainerSupport
    -XX:MaxRAMPercentage=75.0
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:ParallelGCThreads=4
    -XX:ConcGCThreads=2
    -XX:InitiatingHeapOccupancyPercent=45
    -Xlog:gc*:file=/tmp/gc.log:time,uptime:filecount=5,filesize=10M
```

#### Database Connection Pool

```yaml
# Optimal HikariCP settings for production
HIKARI_MINIMUM_IDLE: "10"
HIKARI_MAXIMUM_POOL_SIZE: "50"
HIKARI_CONNECTION_TIMEOUT: "20000"
HIKARI_IDLE_TIMEOUT: "300000"
HIKARI_MAX_LIFETIME: "1200000"
```

#### PostgreSQL Performance Tuning

```sql
-- Production PostgreSQL settings
ALTER SYSTEM SET shared_buffers = '4GB';
ALTER SYSTEM SET effective_cache_size = '12GB';
ALTER SYSTEM SET maintenance_work_mem = '1GB';
ALTER SYSTEM SET checkpoint_completion_target = 0.9;
ALTER SYSTEM SET wal_buffers = '16MB';
ALTER SYSTEM SET default_statistics_target = 100;
ALTER SYSTEM SET random_page_cost = 1.1;
ALTER SYSTEM SET effective_io_concurrency = 200;
ALTER SYSTEM SET work_mem = '10MB';
ALTER SYSTEM SET min_wal_size = '2GB';
ALTER SYSTEM SET max_wal_size = '8GB';

SELECT pg_reload_conf();
```

#### Redis Memory Configuration

```bash
# Redis maxmemory and eviction policy
kubectl exec -it deployment/redis -n carddemo -- redis-cli CONFIG SET maxmemory 2gb
kubectl exec -it deployment/redis -n carddemo -- redis-cli CONFIG SET maxmemory-policy allkeys-lru
```

#### Application-Level Caching

Enable Spring Cache:

```yaml
spring:
  cache:
    type: redis
    redis:
      time-to-live: 300000  # 5 minutes
      cache-null-values: false
  data:
    redis:
      repositories:
        enabled: true
```

### 11.5 Monitoring and Alerting

Set up comprehensive monitoring and alerting.

#### Prometheus Alert Rules

Location: `kubernetes/monitoring/alert-rules.yaml`

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: carddemo-alerts
  namespace: monitoring
spec:
  groups:
  - name: carddemo-application
    interval: 30s
    rules:
    - alert: HighResponseTime
      expr: |
        histogram_quantile(0.95, 
          rate(http_server_requests_seconds_bucket{namespace="carddemo"}[5m])
        ) > 0.2
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "High response time detected"
        description: "95th percentile response time is {{ $value }}s"
    
    - alert: HighErrorRate
      expr: |
        rate(http_server_requests_seconds_count{namespace="carddemo",status=~"5.."}[5m])
        / rate(http_server_requests_seconds_count{namespace="carddemo"}[5m])
        > 0.05
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "High error rate detected"
        description: "Error rate is {{ $value | humanizePercentage }}"
    
    - alert: PodCrashLooping
      expr: rate(kube_pod_container_status_restarts_total{namespace="carddemo"}[15m]) > 0
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "Pod is crash looping"
        description: "Pod {{ $labels.pod }} is restarting frequently"
    
    - alert: DatabaseConnectionPoolExhausted
      expr: |
        hikaricp_connections_active{namespace="carddemo"}
        / hikaricp_connections_max{namespace="carddemo"}
        > 0.9
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "Database connection pool nearly exhausted"
        description: "Pool usage is {{ $value | humanizePercentage }}"
```

#### Alertmanager Configuration

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: alertmanager-config
  namespace: monitoring
data:
  alertmanager.yml: |
    global:
      resolve_timeout: 5m
    route:
      group_by: ['alertname', 'cluster', 'service']
      group_wait: 10s
      group_interval: 10s
      repeat_interval: 12h
      receiver: 'default'
      routes:
      - match:
          severity: critical
        receiver: 'pagerduty'
      - match:
          severity: warning
        receiver: 'slack'
    receivers:
    - name: 'default'
      email_configs:
      - to: 'ops@yourdomain.com'
    - name: 'slack'
      slack_configs:
      - api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'
        channel: '#carddemo-alerts'
    - name: 'pagerduty'
      pagerduty_configs:
      - service_key: 'YOUR_PAGERDUTY_KEY'
```

### 11.6 Documentation Requirements

Maintain comprehensive operational documentation:

- **Runbooks**: Step-by-step procedures for common operations
- **Architecture Diagrams**: Keep diagrams up-to-date with deployments
- **Configuration Management**: Document all ConfigMaps and Secrets
- **Incident Response**: Procedures for handling production incidents
- **Capacity Planning**: Resource usage trends and scaling thresholds
- **Change Management**: Track all production changes with approval process

---

## Conclusion

This deployment guide provides comprehensive instructions for deploying the CardDemo application from local development through production Kubernetes clusters. Key takeaways:

- **Local Development**: Use Docker Compose for rapid development and testing
- **Container Building**: Multi-stage Docker builds optimize image size and security
- **Kubernetes Deployment**: Progressive deployment from namespace creation through ingress
- **Monitoring**: Comprehensive observability with Prometheus, Grafana, and ELK
- **Automation**: CI/CD pipelines automate build, test, and deployment
- **Production Readiness**: High availability, security hardening, and disaster recovery

For additional documentation, refer to:
- [Architecture Documentation](./ARCHITECTURE.md)
- [API Documentation](./API.md)
- [Migration Guide](./MIGRATION_GUIDE.md)
- [Testing Guide](./TESTING.md)

**Support**: For deployment issues or questions, contact the DevOps team or open an issue in the GitHub repository.
