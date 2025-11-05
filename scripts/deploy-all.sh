#!/bin/bash
# CardDemo Kubernetes Deployment Script
# Complete one-command deployment orchestration for CardDemo application
# Deploys backend, frontend, PostgreSQL database, Redis cache, and batch processing CronJobs
# Replaces manual mainframe CICS region startup with cloud-native Kubernetes orchestration

set -e  # Exit on error
set -u  # Exit on undefined variable
set -o pipefail  # Pipe failures cause script to fail

# Color codes for console output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'  # No Color

# Deployment configuration with environment variable overrides
NAMESPACE=${NAMESPACE:-carddemo}
KUBE_CONTEXT=${KUBE_CONTEXT:-}
DEPLOY_BACKEND=${DEPLOY_BACKEND:-true}
DEPLOY_FRONTEND=${DEPLOY_FRONTEND:-true}
DEPLOY_DATABASE=${DEPLOY_DATABASE:-true}
DEPLOY_REDIS=${DEPLOY_REDIS:-true}
DEPLOY_CRONJOBS=${DEPLOY_CRONJOBS:-true}
DRY_RUN=${DRY_RUN:-false}
WAIT_FOR_READY=${WAIT_FOR_READY:-true}
TIMEOUT=${TIMEOUT:-300}

# Logging
DEPLOY_LOG="deploy-$(date +%Y%m%d-%H%M%S).log"
exec 1> >(tee -a "$DEPLOY_LOG")
exec 2>&1

# Error handling function
error_exit() {
    echo -e "${RED}Error: $1${NC}" >&2
    echo ""
    echo "Deployment failed! Check $DEPLOY_LOG for details."
    echo ""
    echo "Rollback commands:"
    echo "  kubectl rollout undo deployment/carddemo-backend -n $NAMESPACE"
    echo "  kubectl rollout undo deployment/carddemo-frontend -n $NAMESPACE"
    echo "  kubectl delete namespace $NAMESPACE  # Complete removal"
    exit 1
}

# Trap errors
trap 'error_exit "Deployment script failed at line $LINENO"' ERR

# Print deployment banner
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}CardDemo Kubernetes Deployment${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Deployment Configuration:"
echo "  Namespace:       $NAMESPACE"
echo "  Backend:         $DEPLOY_BACKEND"
echo "  Frontend:        $DEPLOY_FRONTEND"
echo "  Database:        $DEPLOY_DATABASE"
echo "  Redis:           $DEPLOY_REDIS"
echo "  CronJobs:        $DEPLOY_CRONJOBS"
echo "  Dry Run:         $DRY_RUN"
echo "  Wait for Ready:  $WAIT_FOR_READY"
echo "  Timeout:         ${TIMEOUT}s"
echo ""

# ============================================
# PREREQUISITES VALIDATION
# ============================================
echo -e "${BLUE}Checking prerequisites...${NC}"

# Check kubectl availability
if ! command -v kubectl &> /dev/null; then
    error_exit "kubectl not found. Please install Kubernetes CLI (kubectl 1.28+)"
fi

KUBECTL_VERSION=$(kubectl version --client --short 2>/dev/null || kubectl version --client | grep "Client Version" || echo "unknown")
echo "kubectl version: $KUBECTL_VERSION"

# Check cluster connectivity
if ! kubectl cluster-info &> /dev/null; then
    error_exit "Cannot connect to Kubernetes cluster. Run: kubectl config get-contexts"
fi

CLUSTER_NAME=$(kubectl config current-context)
echo "Connected to cluster: $CLUSTER_NAME"

# Switch context if specified
if [ -n "$KUBE_CONTEXT" ]; then
    echo "Switching to context: $KUBE_CONTEXT"
    kubectl config use-context "$KUBE_CONTEXT" || error_exit "Failed to switch context to $KUBE_CONTEXT"
fi

# Check Docker images exist (if not using registry)
if [ "$DRY_RUN" != "true" ]; then
    echo "Checking Docker images..."
    if ! docker images 2>/dev/null | grep -q carddemo-backend; then
        echo -e "${YELLOW}Warning: carddemo-backend image not found locally${NC}"
        echo "Run: ./scripts/build-all.sh BUILD_DOCKER=true"
        echo "Or ensure images are available in your container registry"
    fi
    if ! docker images 2>/dev/null | grep -q carddemo-frontend; then
        echo -e "${YELLOW}Warning: carddemo-frontend image not found locally${NC}"
        echo "Run: ./scripts/build-all.sh BUILD_DOCKER=true"
    fi
fi

echo -e "${GREEN}Prerequisites validated${NC}"
echo ""

# ============================================
# NAMESPACE CREATION
# ============================================
echo -e "${BLUE}Creating namespace: $NAMESPACE${NC}"

if kubectl get namespace "$NAMESPACE" &> /dev/null; then
    echo "Namespace $NAMESPACE already exists"
else
    if [ "$DRY_RUN" = "true" ]; then
        echo "[DRY RUN] Would create namespace: $NAMESPACE"
    else
        kubectl create namespace "$NAMESPACE" || error_exit "Failed to create namespace $NAMESPACE"
        echo -e "${GREEN}Namespace created: $NAMESPACE${NC}"
    fi
fi

# Set default namespace for subsequent commands
kubectl config set-context --current --namespace="$NAMESPACE" || error_exit "Failed to set namespace context"
echo ""

# ============================================
# CONFIGMAPS AND SECRETS DEPLOYMENT
# ============================================
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deploying Configuration${NC}"
echo -e "${GREEN}========================================${NC}"

# Apply ConfigMaps
if [ -f "kubernetes/configmap.yaml" ]; then
    echo -e "${BLUE}Applying ConfigMaps...${NC}"
    if [ "$DRY_RUN" = "true" ]; then
        kubectl apply -f kubernetes/configmap.yaml --dry-run=client -n "$NAMESPACE"
    else
        kubectl apply -f kubernetes/configmap.yaml -n "$NAMESPACE" || error_exit "Failed to apply ConfigMaps"
        echo -e "${GREEN}ConfigMaps applied successfully${NC}"
    fi
else
    echo -e "${YELLOW}Warning: kubernetes/configmap.yaml not found${NC}"
fi

# Apply Secrets
if [ -f "kubernetes/secrets.yaml" ]; then
    echo -e "${BLUE}Applying Secrets...${NC}"
    if [ "$DRY_RUN" = "true" ]; then
        kubectl apply -f kubernetes/secrets.yaml --dry-run=client -n "$NAMESPACE"
    else
        kubectl apply -f kubernetes/secrets.yaml -n "$NAMESPACE" || error_exit "Failed to apply Secrets"
        echo -e "${GREEN}Secrets applied successfully${NC}"
    fi
else
    echo -e "${YELLOW}Warning: kubernetes/secrets.yaml not found${NC}"
fi

echo ""

# ============================================
# POSTGRESQL DATABASE DEPLOYMENT
# ============================================
if [ "$DEPLOY_DATABASE" = "true" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Deploying PostgreSQL Database${NC}"
    echo -e "${GREEN}========================================${NC}"
    
    # Create PersistentVolumeClaim
    echo -e "${BLUE}Creating PostgreSQL PVC...${NC}"
    if [ -f "kubernetes/postgres-pvc.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/postgres-pvc.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/postgres-pvc.yaml -n "$NAMESPACE" || error_exit "Failed to create PostgreSQL PVC"
            echo -e "${GREEN}PostgreSQL PVC created${NC}"
        fi
    else
        error_exit "kubernetes/postgres-pvc.yaml not found"
    fi
    
    # Deploy PostgreSQL
    echo -e "${BLUE}Deploying PostgreSQL...${NC}"
    if [ -f "kubernetes/postgres-deployment.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/postgres-deployment.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/postgres-deployment.yaml -n "$NAMESPACE" || error_exit "Failed to deploy PostgreSQL"
        fi
    else
        error_exit "kubernetes/postgres-deployment.yaml not found"
    fi
    
    if [ -f "kubernetes/postgres-service.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/postgres-service.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/postgres-service.yaml -n "$NAMESPACE" || error_exit "Failed to create PostgreSQL service"
        fi
    else
        error_exit "kubernetes/postgres-service.yaml not found"
    fi
    
    # Wait for PostgreSQL to be ready
    if [ "$WAIT_FOR_READY" = "true" ] && [ "$DRY_RUN" != "true" ]; then
        echo "Waiting for PostgreSQL to be ready (timeout: ${TIMEOUT}s)..."
        if kubectl wait --for=condition=ready pod -l app=postgres --timeout="${TIMEOUT}s" -n "$NAMESPACE" 2>/dev/null; then
            echo -e "${GREEN}PostgreSQL is ready${NC}"
        else
            echo -e "${YELLOW}Warning: PostgreSQL not ready within timeout. Continuing anyway...${NC}"
        fi
    fi
    
    echo ""
fi

# ============================================
# REDIS CACHE DEPLOYMENT
# ============================================
if [ "$DEPLOY_REDIS" = "true" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Deploying Redis Cache${NC}"
    echo -e "${GREEN}========================================${NC}"
    
    echo -e "${BLUE}Deploying Redis...${NC}"
    if [ -f "kubernetes/redis-deployment.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/redis-deployment.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/redis-deployment.yaml -n "$NAMESPACE" || error_exit "Failed to deploy Redis"
        fi
    else
        error_exit "kubernetes/redis-deployment.yaml not found"
    fi
    
    if [ -f "kubernetes/redis-service.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/redis-service.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/redis-service.yaml -n "$NAMESPACE" || error_exit "Failed to create Redis service"
        fi
    else
        error_exit "kubernetes/redis-service.yaml not found"
    fi
    
    # Wait for Redis to be ready
    if [ "$WAIT_FOR_READY" = "true" ] && [ "$DRY_RUN" != "true" ]; then
        echo "Waiting for Redis to be ready (timeout: ${TIMEOUT}s)..."
        if kubectl wait --for=condition=ready pod -l app=redis --timeout="${TIMEOUT}s" -n "$NAMESPACE" 2>/dev/null; then
            echo -e "${GREEN}Redis is ready${NC}"
        else
            echo -e "${YELLOW}Warning: Redis not ready within timeout. Continuing anyway...${NC}"
        fi
    fi
    
    echo ""
fi

# ============================================
# BACKEND SERVICE DEPLOYMENT
# ============================================
if [ "$DEPLOY_BACKEND" = "true" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Deploying Backend Service${NC}"
    echo -e "${GREEN}========================================${NC}"
    
    echo -e "${BLUE}Deploying Spring Boot backend...${NC}"
    if [ -f "kubernetes/backend-deployment.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/backend-deployment.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/backend-deployment.yaml -n "$NAMESPACE" || error_exit "Failed to deploy backend"
        fi
    else
        error_exit "kubernetes/backend-deployment.yaml not found"
    fi
    
    if [ -f "kubernetes/backend-service.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/backend-service.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/backend-service.yaml -n "$NAMESPACE" || error_exit "Failed to create backend service"
        fi
    else
        error_exit "kubernetes/backend-service.yaml not found"
    fi
    
    # Wait for backend to be ready
    if [ "$WAIT_FOR_READY" = "true" ] && [ "$DRY_RUN" != "true" ]; then
        echo "Waiting for backend to be ready (timeout: ${TIMEOUT}s)..."
        if kubectl wait --for=condition=ready pod -l app=carddemo-backend --timeout="${TIMEOUT}s" -n "$NAMESPACE" 2>/dev/null; then
            echo -e "${GREEN}Backend is ready${NC}"
            
            # Check health endpoint
            echo "Checking backend health..."
            BACKEND_POD=$(kubectl get pods -l app=carddemo-backend -n "$NAMESPACE" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")
            if [ -n "$BACKEND_POD" ]; then
                if kubectl exec "$BACKEND_POD" -n "$NAMESPACE" -- wget -q -O- http://localhost:8080/actuator/health 2>/dev/null; then
                    echo -e "${GREEN}Backend health check passed${NC}"
                else
                    echo -e "${YELLOW}Warning: Backend health check failed${NC}"
                fi
            fi
        else
            echo -e "${YELLOW}Warning: Backend not ready within timeout. Continuing anyway...${NC}"
        fi
    fi
    
    echo ""
fi

# ============================================
# FRONTEND SERVICE DEPLOYMENT
# ============================================
if [ "$DEPLOY_FRONTEND" = "true" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Deploying Frontend Service${NC}"
    echo -e "${GREEN}========================================${NC}"
    
    echo -e "${BLUE}Deploying React frontend...${NC}"
    if [ -f "kubernetes/frontend-deployment.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/frontend-deployment.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/frontend-deployment.yaml -n "$NAMESPACE" || error_exit "Failed to deploy frontend"
        fi
    else
        error_exit "kubernetes/frontend-deployment.yaml not found"
    fi
    
    if [ -f "kubernetes/frontend-service.yaml" ]; then
        if [ "$DRY_RUN" = "true" ]; then
            kubectl apply -f kubernetes/frontend-service.yaml --dry-run=client -n "$NAMESPACE"
        else
            kubectl apply -f kubernetes/frontend-service.yaml -n "$NAMESPACE" || error_exit "Failed to create frontend service"
        fi
    else
        error_exit "kubernetes/frontend-service.yaml not found"
    fi
    
    # Wait for frontend to be ready
    if [ "$WAIT_FOR_READY" = "true" ] && [ "$DRY_RUN" != "true" ]; then
        echo "Waiting for frontend to be ready (timeout: ${TIMEOUT}s)..."
        if kubectl wait --for=condition=ready pod -l app=carddemo-frontend --timeout="${TIMEOUT}s" -n "$NAMESPACE" 2>/dev/null; then
            echo -e "${GREEN}Frontend is ready${NC}"
        else
            echo -e "${YELLOW}Warning: Frontend not ready within timeout. Continuing anyway...${NC}"
        fi
    fi
    
    echo ""
fi

# ============================================
# INGRESS CONFIGURATION
# ============================================
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Configuring Ingress${NC}"
echo -e "${GREEN}========================================${NC}"

if [ -f "kubernetes/ingress.yaml" ]; then
    echo -e "${BLUE}Applying Ingress rules...${NC}"
    if [ "$DRY_RUN" = "true" ]; then
        kubectl apply -f kubernetes/ingress.yaml --dry-run=client -n "$NAMESPACE"
    else
        kubectl apply -f kubernetes/ingress.yaml -n "$NAMESPACE" || echo -e "${YELLOW}Warning: Failed to apply Ingress${NC}"
        
        # Get Ingress URL
        sleep 5  # Wait for Ingress to be created
        INGRESS_URL=$(kubectl get ingress carddemo-ingress -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
        if [ "$INGRESS_URL" != "pending" ] && [ -n "$INGRESS_URL" ]; then
            echo -e "${GREEN}Ingress URL: http://$INGRESS_URL${NC}"
        else
            echo -e "${YELLOW}Ingress IP pending... Check later with: kubectl get ingress -n $NAMESPACE${NC}"
        fi
    fi
else
    echo -e "${YELLOW}Warning: kubernetes/ingress.yaml not found${NC}"
fi

echo ""

# ============================================
# BATCH CRONJOBS DEPLOYMENT
# ============================================
if [ "$DEPLOY_CRONJOBS" = "true" ]; then
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Deploying Batch CronJobs${NC}"
    echo -e "${GREEN}========================================${NC}"
    
    echo -e "${BLUE}Deploying Spring Batch CronJobs...${NC}"
    
    CRONJOB_COUNT=0
    # Apply all CronJob manifests
    if [ -d "kubernetes/cronjobs" ]; then
        for cronjob in kubernetes/cronjobs/*.yaml; do
            if [ -f "$cronjob" ]; then
                CRONJOB_NAME=$(basename "$cronjob" .yaml)
                echo "  Deploying: $CRONJOB_NAME"
                if [ "$DRY_RUN" = "true" ]; then
                    kubectl apply -f "$cronjob" --dry-run=client -n "$NAMESPACE"
                else
                    kubectl apply -f "$cronjob" -n "$NAMESPACE" || echo -e "${YELLOW}Warning: Failed to deploy $CRONJOB_NAME${NC}"
                fi
                CRONJOB_COUNT=$((CRONJOB_COUNT + 1))
            fi
        done
        
        # List deployed CronJobs
        if [ "$DRY_RUN" != "true" ] && [ $CRONJOB_COUNT -gt 0 ]; then
            echo ""
            echo "Deployed CronJobs:"
            kubectl get cronjobs -n "$NAMESPACE"
        fi
    else
        echo -e "${YELLOW}Warning: kubernetes/cronjobs directory not found${NC}"
    fi
    
    echo ""
fi

# ============================================
# DEPLOYMENT STATUS
# ============================================
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment Status${NC}"
echo -e "${GREEN}========================================${NC}"

if [ "$DRY_RUN" != "true" ]; then
    echo ""
    echo -e "${BLUE}Pods:${NC}"
    kubectl get pods -o wide -n "$NAMESPACE" || echo "No pods found"
    
    echo ""
    echo -e "${BLUE}Services:${NC}"
    kubectl get services -n "$NAMESPACE" || echo "No services found"
    
    echo ""
    echo -e "${BLUE}Deployments:${NC}"
    kubectl get deployments -n "$NAMESPACE" || echo "No deployments found"
    
    echo ""
    echo -e "${BLUE}ConfigMaps:${NC}"
    kubectl get configmaps -n "$NAMESPACE" || echo "No configmaps found"
    
    echo ""
    echo -e "${BLUE}Secrets:${NC}"
    kubectl get secrets -n "$NAMESPACE" || echo "No secrets found"
    
    if [ "$DEPLOY_CRONJOBS" = "true" ]; then
        echo ""
        echo -e "${BLUE}CronJobs:${NC}"
        kubectl get cronjobs -n "$NAMESPACE" || echo "No cronjobs found"
    fi
fi

echo ""

# ============================================
# HEALTH CHECK VALIDATION
# ============================================
if [ "$WAIT_FOR_READY" = "true" ] && [ "$DRY_RUN" != "true" ]; then
    echo -e "${BLUE}Running health checks...${NC}"
    
    # Check all pods are running
    TOTAL_PODS=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
    RUNNING_PODS=$(kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | grep -c Running || echo "0")
    
    echo "Pods running: $RUNNING_PODS / $TOTAL_PODS"
    
    if [ "$TOTAL_PODS" -gt 0 ] && [ "$RUNNING_PODS" -eq "$TOTAL_PODS" ]; then
        echo -e "${GREEN}All pods are running successfully!${NC}"
    elif [ "$TOTAL_PODS" -gt 0 ]; then
        echo -e "${YELLOW}Warning: Not all pods are running. Check with: kubectl get pods -n $NAMESPACE${NC}"
        
        # Show pods that are not running
        echo ""
        echo "Non-running pods:"
        kubectl get pods -n "$NAMESPACE" --no-headers 2>/dev/null | grep -v Running || echo "None"
    else
        echo -e "${YELLOW}Warning: No pods found in namespace $NAMESPACE${NC}"
    fi
    
    echo ""
fi

# ============================================
# ACCESS INFORMATION
# ============================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Application Access:"
echo ""

# Get service URLs
if [ "$DRY_RUN" != "true" ]; then
    # Frontend URL
    FRONTEND_PORT=$(kubectl get svc carddemo-frontend-service -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "N/A")
    if [ "$FRONTEND_PORT" != "N/A" ]; then
        NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="ExternalIP")].address}' 2>/dev/null || echo "")
        if [ -z "$NODE_IP" ]; then
            NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "localhost")
        fi
        echo "  Frontend: http://$NODE_IP:$FRONTEND_PORT"
    else
        # Try ClusterIP service
        FRONTEND_CLUSTER_IP=$(kubectl get svc carddemo-frontend-service -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo "")
        if [ -n "$FRONTEND_CLUSTER_IP" ] && [ "$FRONTEND_CLUSTER_IP" != "None" ]; then
            FRONTEND_CLUSTER_PORT=$(kubectl get svc carddemo-frontend-service -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || echo "80")
            echo "  Frontend (ClusterIP): http://$FRONTEND_CLUSTER_IP:$FRONTEND_CLUSTER_PORT"
            echo "    Use: kubectl port-forward svc/carddemo-frontend-service $FRONTEND_CLUSTER_PORT:$FRONTEND_CLUSTER_PORT -n $NAMESPACE"
        fi
    fi
    
    # Backend URL
    BACKEND_PORT=$(kubectl get svc carddemo-backend-service -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null || echo "N/A")
    if [ "$BACKEND_PORT" != "N/A" ]; then
        NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="ExternalIP")].address}' 2>/dev/null || echo "")
        if [ -z "$NODE_IP" ]; then
            NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || echo "localhost")
        fi
        echo "  Backend API: http://$NODE_IP:$BACKEND_PORT/api"
        echo "  Swagger UI: http://$NODE_IP:$BACKEND_PORT/swagger-ui.html"
        echo "  Actuator: http://$NODE_IP:$BACKEND_PORT/actuator"
    else
        # Try ClusterIP service
        BACKEND_CLUSTER_IP=$(kubectl get svc carddemo-backend-service -n "$NAMESPACE" -o jsonpath='{.spec.clusterIP}' 2>/dev/null || echo "")
        if [ -n "$BACKEND_CLUSTER_IP" ] && [ "$BACKEND_CLUSTER_IP" != "None" ]; then
            BACKEND_CLUSTER_PORT=$(kubectl get svc carddemo-backend-service -n "$NAMESPACE" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || echo "8080")
            echo "  Backend API (ClusterIP): http://$BACKEND_CLUSTER_IP:$BACKEND_CLUSTER_PORT/api"
            echo "    Use: kubectl port-forward svc/carddemo-backend-service $BACKEND_CLUSTER_PORT:$BACKEND_CLUSTER_PORT -n $NAMESPACE"
        fi
    fi
    
    # Ingress URL
    INGRESS_HOST=$(kubectl get ingress carddemo-ingress -n "$NAMESPACE" -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "")
    if [ -n "$INGRESS_HOST" ]; then
        echo "  Ingress: http://$INGRESS_HOST"
    fi
fi

echo ""
echo "Useful Commands:"
echo "  View logs:           kubectl logs -f deployment/carddemo-backend -n $NAMESPACE"
echo "  Scale backend:       kubectl scale deployment/carddemo-backend --replicas=3 -n $NAMESPACE"
echo "  Port forward:        kubectl port-forward svc/carddemo-frontend-service 3000:3000 -n $NAMESPACE"
echo "  Get all resources:   kubectl get all -n $NAMESPACE"
echo "  Describe pod:        kubectl describe pod <pod-name> -n $NAMESPACE"
echo "  Delete deployment:   kubectl delete namespace $NAMESPACE"
echo ""

# ============================================
# ROLLBACK CAPABILITY
# ============================================
# Save deployment timestamp for rollback tracking
DEPLOYMENT_TIMESTAMP=$(date +%Y%m%d-%H%M%S)
echo "$DEPLOYMENT_TIMESTAMP" > .last-deployment 2>/dev/null || true

echo "Deployment timestamp: $DEPLOYMENT_TIMESTAMP"
echo ""
echo "To rollback this deployment:"
echo "  kubectl rollout undo deployment/carddemo-backend -n $NAMESPACE"
echo "  kubectl rollout undo deployment/carddemo-frontend -n $NAMESPACE"
echo "  kubectl rollout undo deployment/postgres -n $NAMESPACE"
echo "  kubectl rollout undo deployment/redis -n $NAMESPACE"
echo ""
echo "To check rollout history:"
echo "  kubectl rollout history deployment/carddemo-backend -n $NAMESPACE"
echo ""

# ============================================
# FINAL SUCCESS MESSAGE
# ============================================
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Deployment Summary${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "✓ Namespace: $NAMESPACE"
if [ "$DEPLOY_DATABASE" = "true" ]; then
    echo "✓ PostgreSQL Database deployed"
fi
if [ "$DEPLOY_REDIS" = "true" ]; then
    echo "✓ Redis Cache deployed"
fi
if [ "$DEPLOY_BACKEND" = "true" ]; then
    echo "✓ Backend Service deployed"
fi
if [ "$DEPLOY_FRONTEND" = "true" ]; then
    echo "✓ Frontend Service deployed"
fi
if [ "$DEPLOY_CRONJOBS" = "true" ]; then
    echo "✓ Batch CronJobs deployed"
fi
echo ""
echo "Deployment log saved to: $DEPLOY_LOG"
echo ""
echo -e "${GREEN}CardDemo application is now running on Kubernetes!${NC}"
echo ""

# Exit successfully
exit 0
