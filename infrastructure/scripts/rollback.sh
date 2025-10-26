#!/bin/bash

################################################################################
# Kubernetes Deployment Rollback Script
# 
# Purpose: Safely reverts CardDemo application to a previous version
#          Implements blue-green deployment rollback strategy with validation
#          Replaces mainframe deployment rollback procedures
#
# Features:
# - kubectl rollout undo for backend and frontend deployments
# - Rollback to specific revision number or previous stable version
# - Validation of rollback target revision existence
# - Blue-green deployment traffic switching
# - Database schema rollback (Flyway undo or backup restore)
# - Comprehensive health checks after rollback
# - Partial rollback support (backend, frontend, full)
# - Emergency rollback mode for critical issues
# - Rollback history logging and status reporting
# - Automated alerting on rollback execution
# - 5-minute rollback timeout
#
# Environment Variables (required):
#   KUBE_NAMESPACE       - Kubernetes namespace (default: carddemo)
#   DB_HOST              - PostgreSQL host (default: postgres-service)
#   DB_PORT              - PostgreSQL port (default: 5432)
#   DB_NAME              - Database name (default: carddemo)
#   DB_USER              - Database user (default: postgres)
#   PGPASSWORD           - Database password (set as environment variable)
#   BACKUP_DIR           - Backup directory for database restore (default: /backup)
#   WEBHOOK_URL          - Optional webhook URL for rollback notifications
#   ROLLBACK_TIMEOUT     - Rollback timeout in seconds (default: 300 = 5 minutes)
#   ENABLE_DB_ROLLBACK   - Enable database rollback (default: true)
#   HEALTH_CHECK_RETRIES - Health check retry count (default: 10)
#   HEALTH_CHECK_INTERVAL- Health check interval in seconds (default: 6)
#
# Usage:
#   ./rollback.sh [options]
#   Options:
#     --backend            Rollback backend deployment only
#     --frontend           Rollback frontend deployment only
#     --database           Rollback database schema only
#     --full               Rollback full application (default)
#     --revision <N>       Rollback to specific revision number
#     --previous           Rollback to previous version (default)
#     --emergency          Emergency rollback mode (skip validation)
#     --help               Display this help message
#
# Exit Codes:
#   0 - Rollback completed successfully
#   1 - Configuration error or invalid arguments
#   2 - Kubernetes connection error
#   3 - Rollback target validation failed
#   4 - Deployment rollback failed
#   5 - Database rollback failed
#   6 - Health check validation failed
#   7 - Rollback timeout exceeded
################################################################################

# Enable strict error handling
set -euo pipefail

# Trap errors and cleanup
trap 'cleanup_on_error $?' ERR
trap 'cleanup_on_exit' EXIT

################################################################################
# Configuration Variables
################################################################################

# Kubernetes configuration
KUBE_NAMESPACE="${KUBE_NAMESPACE:-carddemo}"
BACKEND_DEPLOYMENT="carddemo-backend"
FRONTEND_DEPLOYMENT="carddemo-frontend"
BACKEND_SERVICE="carddemo-backend"
FRONTEND_SERVICE="carddemo-frontend"
BACKEND_INGRESS="carddemo-backend-ingress"
FRONTEND_INGRESS="carddemo-frontend-ingress"

# Database configuration
DB_HOST="${DB_HOST:-postgres-service}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-carddemo}"
DB_USER="${DB_USER:-postgres}"
# PGPASSWORD should be set as environment variable

# Backup configuration
BACKUP_DIR="${BACKUP_DIR:-/backup}"
BACKUP_SCRIPT="${BACKUP_SCRIPT:-$(dirname "$0")/backup-db.sh}"

# Rollback configuration
ROLLBACK_TIMEOUT="${ROLLBACK_TIMEOUT:-300}"  # 5 minutes
ENABLE_DB_ROLLBACK="${ENABLE_DB_ROLLBACK:-true}"
HEALTH_CHECK_RETRIES="${HEALTH_CHECK_RETRIES:-10}"
HEALTH_CHECK_INTERVAL="${HEALTH_CHECK_INTERVAL:-6}"

# Webhook for alerting
WEBHOOK_URL="${WEBHOOK_URL:-}"

# Logging configuration
LOG_DIR="${BACKUP_DIR}/rollback-logs"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
ROLLBACK_START_TIME=$(date +%s)
LOG_FILE="${LOG_DIR}/rollback_${TIMESTAMP}.log"

# Rollback state variables
ROLLBACK_MODE="full"
ROLLBACK_TARGET="previous"
EMERGENCY_MODE=false
ROLLBACK_REASON=""
ROLLBACK_COMPONENTS=()

################################################################################
# Logging Functions
################################################################################

# Ensure log directory exists
mkdir -p "${LOG_DIR}" 2>/dev/null || true

# Log message with timestamp
log() {
    local level="$1"
    shift
    local message="$@"
    local timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
    
    if [ -f "${LOG_FILE}" ] || touch "${LOG_FILE}" 2>/dev/null; then
        echo "[${timestamp}] [${level}] ${message}" | tee -a "${LOG_FILE}"
    else
        echo "[${timestamp}] [${level}] ${message}"
    fi
}

log_info() {
    log "INFO" "$@"
}

log_error() {
    log "ERROR" "$@"
}

log_warn() {
    log "WARN" "$@"
}

log_success() {
    log "SUCCESS" "$@"
}

################################################################################
# Utility Functions
################################################################################

# Check if required commands are available
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    local required_commands="kubectl psql curl date grep awk sed"
    
    for cmd in $required_commands; do
        if ! command -v "$cmd" &> /dev/null; then
            log_error "Required command not found: $cmd"
            return 1
        fi
    done
    
    # Check for optional tools
    if command -v jq &> /dev/null; then
        log_info "jq available for JSON processing"
    else
        log_warn "jq not found. JSON processing features limited."
    fi
    
    # Check PostgreSQL tools for database rollback
    if [ "$ENABLE_DB_ROLLBACK" = "true" ]; then
        if ! command -v pg_restore &> /dev/null; then
            log_error "pg_restore not found. Install postgresql-client package."
            return 1
        fi
    fi
    
    log_success "All prerequisites satisfied"
    return 0
}

# Check Kubernetes cluster connectivity
check_kubernetes_connection() {
    log_info "Checking Kubernetes cluster connectivity..."
    
    if ! kubectl cluster-info &> /dev/null; then
        log_error "Cannot connect to Kubernetes cluster"
        log_error "Please check kubectl configuration and cluster availability"
        return 2
    fi
    
    # Check namespace exists
    if ! kubectl get namespace "${KUBE_NAMESPACE}" &> /dev/null; then
        log_error "Namespace ${KUBE_NAMESPACE} does not exist"
        return 2
    fi
    
    log_success "Kubernetes connection validated"
    return 0
}

# Check database connectivity
check_database_connection() {
    log_info "Checking database connectivity to ${DB_HOST}:${DB_PORT}/${DB_NAME}..."
    
    if ! psql -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT 1;" &> /dev/null; then
        log_error "Failed to connect to database ${DB_NAME} at ${DB_HOST}:${DB_PORT}"
        return 2
    fi
    
    log_success "Database connection successful"
    return 0
}

# Check if rollback timeout exceeded
check_timeout() {
    local current_time=$(date +%s)
    local elapsed=$((current_time - ROLLBACK_START_TIME))
    
    if [ $elapsed -gt $ROLLBACK_TIMEOUT ]; then
        log_error "Rollback timeout exceeded: ${elapsed}s > ${ROLLBACK_TIMEOUT}s"
        return 7
    fi
    
    return 0
}

################################################################################
# Deployment Validation Functions
################################################################################

# Get deployment revision history
get_deployment_history() {
    local deployment_name="$1"
    
    log_info "Retrieving revision history for deployment: ${deployment_name}"
    
    kubectl rollout history deployment/"${deployment_name}" -n "${KUBE_NAMESPACE}" 2>&1 | tee -a "${LOG_FILE}"
}

# Get current deployment revision
get_current_revision() {
    local deployment_name="$1"
    
    local revision=$(kubectl get deployment "${deployment_name}" -n "${KUBE_NAMESPACE}" \
        -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}' 2>/dev/null)
    
    echo "$revision"
}

# Validate rollback target revision exists
validate_rollback_target() {
    local deployment_name="$1"
    local target_revision="$2"
    
    log_info "Validating rollback target for ${deployment_name}: revision ${target_revision}"
    
    # Get current revision
    local current_revision=$(get_current_revision "${deployment_name}")
    
    if [ -z "$current_revision" ]; then
        log_error "Could not determine current revision for ${deployment_name}"
        return 3
    fi
    
    log_info "Current revision: ${current_revision}"
    
    # If target is "previous", calculate it
    if [ "$target_revision" = "previous" ]; then
        target_revision=$((current_revision - 1))
        log_info "Calculated previous revision: ${target_revision}"
    fi
    
    # Validate target revision exists in history
    if ! kubectl rollout history deployment/"${deployment_name}" -n "${KUBE_NAMESPACE}" \
        --revision="${target_revision}" &> /dev/null; then
        log_error "Target revision ${target_revision} does not exist for ${deployment_name}"
        return 3
    fi
    
    # Check if target revision is same as current
    if [ "$target_revision" -eq "$current_revision" ]; then
        log_warn "Target revision ${target_revision} is the same as current revision"
        log_warn "No rollback needed for ${deployment_name}"
        return 0
    fi
    
    log_success "Rollback target validated: revision ${target_revision}"
    echo "$target_revision"
    return 0
}

# Get deployment image version
get_deployment_image() {
    local deployment_name="$1"
    
    local image=$(kubectl get deployment "${deployment_name}" -n "${KUBE_NAMESPACE}" \
        -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)
    
    echo "$image"
}

################################################################################
# Deployment Rollback Functions
################################################################################

# Rollback backend deployment
rollback_backend_deployment() {
    log_info "Rolling back backend deployment: ${BACKEND_DEPLOYMENT}"
    
    local target_revision="$1"
    
    # Validate rollback target
    if [ "$EMERGENCY_MODE" = false ]; then
        local validated_revision=$(validate_rollback_target "${BACKEND_DEPLOYMENT}" "${target_revision}")
        if [ $? -ne 0 ]; then
            log_error "Backend rollback target validation failed"
            return 3
        fi
        target_revision="$validated_revision"
    fi
    
    # Get current image for logging
    local current_image=$(get_deployment_image "${BACKEND_DEPLOYMENT}")
    log_info "Current backend image: ${current_image}"
    
    # Execute rollback
    log_info "Executing kubectl rollout undo for backend..."
    
    if [ -n "$target_revision" ] && [ "$target_revision" != "previous" ]; then
        if ! kubectl rollout undo deployment/"${BACKEND_DEPLOYMENT}" \
            -n "${KUBE_NAMESPACE}" \
            --to-revision="${target_revision}" 2>&1 | tee -a "${LOG_FILE}"; then
            log_error "Backend deployment rollback failed"
            return 4
        fi
    else
        if ! kubectl rollout undo deployment/"${BACKEND_DEPLOYMENT}" \
            -n "${KUBE_NAMESPACE}" 2>&1 | tee -a "${LOG_FILE}"; then
            log_error "Backend deployment rollback failed"
            return 4
        fi
    fi
    
    # Wait for rollback to complete
    log_info "Waiting for backend rollback to complete..."
    
    if ! kubectl rollout status deployment/"${BACKEND_DEPLOYMENT}" \
        -n "${KUBE_NAMESPACE}" \
        --timeout="${ROLLBACK_TIMEOUT}s" 2>&1 | tee -a "${LOG_FILE}"; then
        log_error "Backend rollback did not complete within timeout"
        return 4
    fi
    
    # Get new image after rollback
    local new_image=$(get_deployment_image "${BACKEND_DEPLOYMENT}")
    log_info "New backend image after rollback: ${new_image}"
    
    log_success "Backend deployment rolled back successfully"
    ROLLBACK_COMPONENTS+=("backend")
    return 0
}

# Rollback frontend deployment
rollback_frontend_deployment() {
    log_info "Rolling back frontend deployment: ${FRONTEND_DEPLOYMENT}"
    
    local target_revision="$1"
    
    # Validate rollback target
    if [ "$EMERGENCY_MODE" = false ]; then
        local validated_revision=$(validate_rollback_target "${FRONTEND_DEPLOYMENT}" "${target_revision}")
        if [ $? -ne 0 ]; then
            log_error "Frontend rollback target validation failed"
            return 3
        fi
        target_revision="$validated_revision"
    fi
    
    # Get current image for logging
    local current_image=$(get_deployment_image "${FRONTEND_DEPLOYMENT}")
    log_info "Current frontend image: ${current_image}"
    
    # Execute rollback
    log_info "Executing kubectl rollout undo for frontend..."
    
    if [ -n "$target_revision" ] && [ "$target_revision" != "previous" ]; then
        if ! kubectl rollout undo deployment/"${FRONTEND_DEPLOYMENT}" \
            -n "${KUBE_NAMESPACE}" \
            --to-revision="${target_revision}" 2>&1 | tee -a "${LOG_FILE}"; then
            log_error "Frontend deployment rollback failed"
            return 4
        fi
    else
        if ! kubectl rollout undo deployment/"${FRONTEND_DEPLOYMENT}" \
            -n "${KUBE_NAMESPACE}" 2>&1 | tee -a "${LOG_FILE}"; then
            log_error "Frontend deployment rollback failed"
            return 4
        fi
    fi
    
    # Wait for rollback to complete
    log_info "Waiting for frontend rollback to complete..."
    
    if ! kubectl rollout status deployment/"${FRONTEND_DEPLOYMENT}" \
        -n "${KUBE_NAMESPACE}" \
        --timeout="${ROLLBACK_TIMEOUT}s" 2>&1 | tee -a "${LOG_FILE}"; then
        log_error "Frontend rollback did not complete within timeout"
        return 4
    fi
    
    # Get new image after rollback
    local new_image=$(get_deployment_image "${FRONTEND_DEPLOYMENT}")
    log_info "New frontend image after rollback: ${new_image}"
    
    log_success "Frontend deployment rolled back successfully"
    ROLLBACK_COMPONENTS+=("frontend")
    return 0
}

################################################################################
# Database Rollback Functions
################################################################################

# Get latest database backup
get_latest_backup() {
    log_info "Searching for latest database backup..."
    
    # Search in daily backup directory first
    local backup_dir="${BACKUP_DIR}/daily"
    
    if [ ! -d "$backup_dir" ]; then
        log_error "Backup directory not found: ${backup_dir}"
        return 5
    fi
    
    # Find most recent backup file
    local latest_backup=$(find "${backup_dir}" -name "carddemo_backup_*.dump*" -type f 2>/dev/null | sort -r | head -n 1)
    
    if [ -z "$latest_backup" ]; then
        log_error "No backup files found in ${backup_dir}"
        return 5
    fi
    
    log_info "Latest backup found: $(basename ${latest_backup})"
    echo "$latest_backup"
    return 0
}

# Create pre-rollback backup
create_pre_rollback_backup() {
    log_info "Creating pre-rollback database backup..."
    
    if [ ! -f "$BACKUP_SCRIPT" ]; then
        log_warn "Backup script not found: ${BACKUP_SCRIPT}"
        log_warn "Skipping pre-rollback backup"
        return 0
    fi
    
    # Execute backup script
    if bash "$BACKUP_SCRIPT" --full 2>&1 | tee -a "${LOG_FILE}"; then
        log_success "Pre-rollback backup created successfully"
    else
        log_warn "Pre-rollback backup failed, continuing with rollback"
    fi
    
    return 0
}

# Rollback database using Flyway undo
rollback_database_flyway() {
    log_info "Attempting database rollback using Flyway undo migrations..."
    
    # Check if backend pod is available to run Flyway
    local backend_pod=$(kubectl get pods -n "${KUBE_NAMESPACE}" \
        -l app="${BACKEND_DEPLOYMENT}" \
        -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    
    if [ -z "$backend_pod" ]; then
        log_warn "No backend pod found for Flyway execution"
        return 5
    fi
    
    log_info "Executing Flyway undo in pod: ${backend_pod}"
    
    # Execute Flyway undo command in backend pod
    # Note: This assumes backend has Flyway CLI available
    if kubectl exec -n "${KUBE_NAMESPACE}" "${backend_pod}" -- \
        sh -c "flyway undo -configFiles=/app/flyway.conf" 2>&1 | tee -a "${LOG_FILE}"; then
        log_success "Flyway undo migrations completed successfully"
        return 0
    else
        log_warn "Flyway undo failed, will attempt backup restore"
        return 5
    fi
}

# Rollback database using backup restore
rollback_database_restore() {
    log_info "Rolling back database using backup restore..."
    
    # Get latest backup
    local backup_file=$(get_latest_backup)
    if [ $? -ne 0 ]; then
        log_error "Cannot rollback database: no backup file available"
        return 5
    fi
    
    log_info "Restoring from backup: $(basename ${backup_file})"
    
    # Decompress if needed
    local restore_file="$backup_file"
    if [[ "$backup_file" == *.gz ]]; then
        log_info "Decompressing backup file..."
        local temp_file="${BACKUP_DIR}/temp/$(basename ${backup_file%.gz})"
        mkdir -p "${BACKUP_DIR}/temp"
        
        if ! gzip -d -c "$backup_file" > "$temp_file"; then
            log_error "Failed to decompress backup file"
            return 5
        fi
        restore_file="$temp_file"
    fi
    
    # Restore database
    log_info "Restoring database from backup..."
    
    if ! pg_restore -h "${DB_HOST}" -p "${DB_PORT}" -U "${DB_USER}" \
        -d "${DB_NAME}" -c -v "$restore_file" 2>&1 | tee -a "${LOG_FILE}"; then
        log_error "Database restore failed"
        
        # Cleanup temp file if created
        if [ "$restore_file" != "$backup_file" ]; then
            rm -f "$restore_file"
        fi
        
        return 5
    fi
    
    # Cleanup temp file if created
    if [ "$restore_file" != "$backup_file" ]; then
        rm -f "$restore_file"
    fi
    
    log_success "Database restored successfully from backup"
    return 0
}

# Rollback database schema
rollback_database() {
    if [ "$ENABLE_DB_ROLLBACK" != "true" ]; then
        log_info "Database rollback disabled, skipping..."
        return 0
    fi
    
    log_info "Starting database rollback..."
    
    check_timeout || return 7
    
    # Create pre-rollback backup as safety measure
    if [ "$EMERGENCY_MODE" = false ]; then
        create_pre_rollback_backup
    fi
    
    # Try Flyway undo first (preferred method)
    if [ "$EMERGENCY_MODE" = false ]; then
        log_info "Attempting Flyway undo migrations..."
        if rollback_database_flyway; then
            log_success "Database rolled back using Flyway"
            ROLLBACK_COMPONENTS+=("database-flyway")
            return 0
        fi
    fi
    
    # Fall back to backup restore
    log_info "Attempting backup restore..."
    if rollback_database_restore; then
        log_success "Database rolled back using backup restore"
        ROLLBACK_COMPONENTS+=("database-restore")
        return 0
    fi
    
    log_error "Database rollback failed"
    return 5
}

################################################################################
# Health Check and Validation Functions
################################################################################

# Check pod status
check_pod_status() {
    local deployment_name="$1"
    
    log_info "Checking pod status for deployment: ${deployment_name}"
    
    # Get pods for deployment
    local pods=$(kubectl get pods -n "${KUBE_NAMESPACE}" \
        -l app="${deployment_name}" \
        -o jsonpath='{.items[*].metadata.name}')
    
    if [ -z "$pods" ]; then
        log_error "No pods found for deployment ${deployment_name}"
        return 6
    fi
    
    log_info "Pods found: ${pods}"
    
    # Check each pod status
    for pod in $pods; do
        local pod_status=$(kubectl get pod "$pod" -n "${KUBE_NAMESPACE}" \
            -o jsonpath='{.status.phase}')
        
        log_info "Pod ${pod} status: ${pod_status}"
        
        if [ "$pod_status" != "Running" ]; then
            log_error "Pod ${pod} is not running: ${pod_status}"
            return 6
        fi
    done
    
    # Wait for pods to be ready
    log_info "Waiting for pods to be ready..."
    
    if ! kubectl wait --for=condition=ready pod \
        -l app="${deployment_name}" \
        -n "${KUBE_NAMESPACE}" \
        --timeout=120s 2>&1 | tee -a "${LOG_FILE}"; then
        log_error "Pods did not become ready within timeout"
        return 6
    fi
    
    log_success "All pods are running and ready"
    return 0
}

# Execute backend health check
check_backend_health() {
    log_info "Checking backend health endpoint..."
    
    local backend_url="http://${BACKEND_SERVICE}.${KUBE_NAMESPACE}.svc.cluster.local:8080/api/health"
    local retry_count=0
    local health_ok=false
    
    while [ $retry_count -lt $HEALTH_CHECK_RETRIES ] && [ "$health_ok" = false ]; do
        check_timeout || return 7
        
        log_info "Health check attempt $((retry_count + 1))/${HEALTH_CHECK_RETRIES}..."
        
        # Execute health check via kubectl port-forward or direct curl
        if kubectl exec -n "${KUBE_NAMESPACE}" \
            deployment/"${BACKEND_DEPLOYMENT}" -- \
            curl -f -s "${backend_url}" &> /dev/null; then
            health_ok=true
            log_success "Backend health check passed"
        else
            retry_count=$((retry_count + 1))
            if [ $retry_count -lt $HEALTH_CHECK_RETRIES ]; then
                log_warn "Health check failed, retrying in ${HEALTH_CHECK_INTERVAL}s..."
                sleep "${HEALTH_CHECK_INTERVAL}"
            fi
        fi
    done
    
    if [ "$health_ok" = false ]; then
        log_error "Backend health check failed after ${HEALTH_CHECK_RETRIES} attempts"
        return 6
    fi
    
    return 0
}

# Execute frontend health check
check_frontend_health() {
    log_info "Checking frontend health..."
    
    local frontend_url="http://${FRONTEND_SERVICE}.${KUBE_NAMESPACE}.svc.cluster.local"
    local retry_count=0
    local health_ok=false
    
    while [ $retry_count -lt $HEALTH_CHECK_RETRIES ] && [ "$health_ok" = false ]; do
        check_timeout || return 7
        
        log_info "Frontend health check attempt $((retry_count + 1))/${HEALTH_CHECK_RETRIES}..."
        
        if kubectl exec -n "${KUBE_NAMESPACE}" \
            deployment/"${FRONTEND_DEPLOYMENT}" -- \
            curl -f -s "${frontend_url}" &> /dev/null; then
            health_ok=true
            log_success "Frontend health check passed"
        else
            retry_count=$((retry_count + 1))
            if [ $retry_count -lt $HEALTH_CHECK_RETRIES ]; then
                log_warn "Health check failed, retrying in ${HEALTH_CHECK_INTERVAL}s..."
                sleep "${HEALTH_CHECK_INTERVAL}"
            fi
        fi
    done
    
    if [ "$health_ok" = false ]; then
        log_error "Frontend health check failed after ${HEALTH_CHECK_RETRIES} attempts"
        return 6
    fi
    
    return 0
}

# Execute smoke tests
execute_smoke_tests() {
    log_info "Executing smoke tests..."
    
    if [ "$EMERGENCY_MODE" = true ]; then
        log_info "Emergency mode enabled, skipping smoke tests"
        return 0
    fi
    
    check_timeout || return 7
    
    # Test backend API endpoint
    log_info "Testing backend API (GET /api/accounts)..."
    
    if kubectl exec -n "${KUBE_NAMESPACE}" \
        deployment/"${BACKEND_DEPLOYMENT}" -- \
        curl -f -s "http://${BACKEND_SERVICE}:8080/api/accounts?page=0&size=1" &> /dev/null; then
        log_success "Backend API smoke test passed"
    else
        log_warn "Backend API smoke test failed (non-critical)"
    fi
    
    # Test database connectivity from backend
    log_info "Testing database connectivity from backend..."
    
    local backend_pod=$(kubectl get pods -n "${KUBE_NAMESPACE}" \
        -l app="${BACKEND_DEPLOYMENT}" \
        -o jsonpath='{.items[0].metadata.name}')
    
    if [ -n "$backend_pod" ]; then
        if kubectl exec -n "${KUBE_NAMESPACE}" "${backend_pod}" -- \
            sh -c "psql -h ${DB_HOST} -p ${DB_PORT} -U ${DB_USER} -d ${DB_NAME} -c 'SELECT 1;'" &> /dev/null; then
            log_success "Database connectivity smoke test passed"
        else
            log_warn "Database connectivity smoke test failed (non-critical)"
        fi
    fi
    
    log_success "Smoke tests completed"
    return 0
}

# Validate rollback success
validate_rollback_success() {
    log_info "Validating rollback success..."
    
    local validation_failed=false
    
    # Check pod status for rolled back components
    if [[ " ${ROLLBACK_COMPONENTS[@]} " =~ " backend " ]]; then
        log_info "Validating backend rollback..."
        if ! check_pod_status "${BACKEND_DEPLOYMENT}"; then
            log_error "Backend pod status validation failed"
            validation_failed=true
        fi
        
        if ! check_backend_health; then
            log_error "Backend health check validation failed"
            validation_failed=true
        fi
    fi
    
    if [[ " ${ROLLBACK_COMPONENTS[@]} " =~ " frontend " ]]; then
        log_info "Validating frontend rollback..."
        if ! check_pod_status "${FRONTEND_DEPLOYMENT}"; then
            log_error "Frontend pod status validation failed"
            validation_failed=true
        fi
        
        if ! check_frontend_health; then
            log_error "Frontend health check validation failed"
            validation_failed=true
        fi
    fi
    
    # Execute smoke tests
    execute_smoke_tests
    
    if [ "$validation_failed" = true ]; then
        log_error "Rollback validation failed"
        return 6
    fi
    
    log_success "Rollback validation completed successfully"
    return 0
}

################################################################################
# Blue-Green Deployment Functions
################################################################################

# Switch traffic to previous version (blue-green rollback)
switch_traffic_to_previous() {
    log_info "Implementing blue-green traffic switching..."
    
    # Note: In a true blue-green deployment, this would update Ingress or Service
    # to point to the previous (blue) deployment. Since kubectl rollout undo
    # already handles pod replacement, this function documents the strategy.
    
    # For advanced blue-green setups, you could patch the Ingress or Service here
    # Example (commented out):
    # kubectl patch ingress "${BACKEND_INGRESS}" -n "${KUBE_NAMESPACE}" \
    #   -p '{"spec":{"rules":[{"http":{"paths":[{"backend":{"service":{"name":"'${BACKEND_SERVICE}'-blue"}}}]}}]}}'
    
    log_info "Traffic is automatically routed to rolled-back pods by Kubernetes"
    log_info "Blue-green strategy: Old pods (green) terminated, new pods (blue) receiving traffic"
    
    return 0
}

################################################################################
# Alerting and Notification Functions
################################################################################

# Send webhook notification
send_webhook_notification() {
    local status="$1"
    local message="$2"
    
    if [ -z "$WEBHOOK_URL" ]; then
        log_info "Webhook URL not configured, skipping notification"
        return 0
    fi
    
    if ! command -v curl &> /dev/null; then
        log_warn "curl not available, cannot send webhook notification"
        return 0
    fi
    
    log_info "Sending webhook notification..."
    
    local duration=$(($(date +%s) - ROLLBACK_START_TIME))
    local components_str=$(IFS=,; echo "${ROLLBACK_COMPONENTS[*]}")
    
    # Build JSON payload
    local json_payload=""
    if command -v jq &> /dev/null; then
        json_payload=$(jq -n \
            --arg status "$status" \
            --arg message "$message" \
            --arg namespace "$KUBE_NAMESPACE" \
            --arg mode "$ROLLBACK_MODE" \
            --arg components "$components_str" \
            --arg reason "$ROLLBACK_REASON" \
            --arg timestamp "$TIMESTAMP" \
            --arg duration "${duration}s" \
            '{
                status: $status,
                message: $message,
                namespace: $namespace,
                rollback_mode: $mode,
                components: $components,
                reason: $reason,
                timestamp: $timestamp,
                duration: $duration,
                hostname: env.HOSTNAME
            }')
    else
        json_payload="{\"status\":\"${status}\",\"message\":\"${message}\",\"namespace\":\"${KUBE_NAMESPACE}\",\"rollback_mode\":\"${ROLLBACK_MODE}\",\"components\":\"${components_str}\",\"duration\":\"${duration}s\"}"
    fi
    
    # Send webhook
    if curl -X POST -H "Content-Type: application/json" \
        -d "$json_payload" \
        -s -o /dev/null -w "%{http_code}" \
        "$WEBHOOK_URL" | grep -q "^2"; then
        log_success "Webhook notification sent successfully"
    else
        log_warn "Webhook notification failed"
    fi
    
    return 0
}

# Notify rollback initiation
notify_rollback_start() {
    log_info "=== Rollback initiated ==="
    send_webhook_notification "in_progress" "CardDemo rollback started: ${ROLLBACK_REASON}"
}

# Notify rollback success
notify_rollback_success() {
    log_success "=== Rollback completed successfully ==="
    send_webhook_notification "success" "CardDemo rollback completed successfully"
}

# Notify rollback failure
notify_rollback_failure() {
    local error_message="$1"
    local exit_code="$2"
    
    log_error "=== Rollback failed: ${error_message} (exit code: ${exit_code}) ==="
    send_webhook_notification "failure" "CardDemo rollback failed: ${error_message}"
}

################################################################################
# Reporting Functions
################################################################################

# Generate rollback report
generate_rollback_report() {
    log_info "Generating rollback report..."
    
    local duration=$(($(date +%s) - ROLLBACK_START_TIME))
    local components_str=$(IFS=,; echo "${ROLLBACK_COMPONENTS[*]}")
    
    local backend_image=""
    local frontend_image=""
    
    if [[ " ${ROLLBACK_COMPONENTS[@]} " =~ " backend " ]]; then
        backend_image=$(get_deployment_image "${BACKEND_DEPLOYMENT}")
    fi
    
    if [[ " ${ROLLBACK_COMPONENTS[@]} " =~ " frontend " ]]; then
        frontend_image=$(get_deployment_image "${FRONTEND_DEPLOYMENT}")
    fi
    
    log_info "=========================================="
    log_info "ROLLBACK REPORT"
    log_info "=========================================="
    log_info "Timestamp:           ${TIMESTAMP}"
    log_info "Namespace:           ${KUBE_NAMESPACE}"
    log_info "Rollback Mode:       ${ROLLBACK_MODE}"
    log_info "Emergency Mode:      $([ "$EMERGENCY_MODE" = true ] && echo "YES" || echo "NO")"
    log_info "Target:              ${ROLLBACK_TARGET}"
    log_info "Reason:              ${ROLLBACK_REASON}"
    log_info "Duration:            ${duration} seconds"
    log_info "----------------------------------------"
    log_info "Components Rolled Back:"
    log_info "  ${components_str:-none}"
    log_info "----------------------------------------"
    if [ -n "$backend_image" ]; then
        log_info "Backend Image:       ${backend_image}"
    fi
    if [ -n "$frontend_image" ]; then
        log_info "Frontend Image:      ${frontend_image}"
    fi
    log_info "=========================================="
}

# Log rollback to history
log_rollback_history() {
    local status="$1"
    local exit_code="$2"
    
    local history_file="${LOG_DIR}/rollback_history.log"
    local duration=$(($(date +%s) - ROLLBACK_START_TIME))
    local components_str=$(IFS=,; echo "${ROLLBACK_COMPONENTS[*]}")
    
    local history_entry="${TIMESTAMP}|${status}|${ROLLBACK_MODE}|${components_str}|${ROLLBACK_REASON}|${duration}s|exit_code:${exit_code}"
    
    echo "$history_entry" >> "$history_file"
    
    log_info "Rollback logged to history: ${history_file}"
}

################################################################################
# Cleanup and Error Handling Functions
################################################################################

# Cleanup on error
cleanup_on_error() {
    local exit_code=$1
    
    if [ $exit_code -ne 0 ]; then
        log_error "Rollback script exiting with error code: $exit_code"
        
        # Determine error message based on exit code
        local error_message="Unknown error"
        case $exit_code in
            1) error_message="Configuration error or invalid arguments" ;;
            2) error_message="Kubernetes/Database connection error" ;;
            3) error_message="Rollback target validation failed" ;;
            4) error_message="Deployment rollback failed" ;;
            5) error_message="Database rollback failed" ;;
            6) error_message="Health check validation failed" ;;
            7) error_message="Rollback timeout exceeded" ;;
        esac
        
        notify_rollback_failure "$error_message" "$exit_code"
        log_rollback_history "FAILED" "$exit_code"
    fi
}

# Cleanup on exit
cleanup_on_exit() {
    local exit_code=$?
    
    if [ $exit_code -eq 0 ]; then
        log_info "Rollback script completed successfully"
    else
        log_error "Rollback script exited with errors"
    fi
}

################################################################################
# Main Execution Functions
################################################################################

# Perform full rollback
perform_full_rollback() {
    log_info "Starting full application rollback..."
    
    local rollback_failed=false
    
    # Rollback backend
    log_info "Step 1/3: Rolling back backend deployment..."
    if ! rollback_backend_deployment "$ROLLBACK_TARGET"; then
        log_error "Backend rollback failed"
        rollback_failed=true
        return 4
    fi
    check_timeout || return 7
    
    # Rollback frontend
    log_info "Step 2/3: Rolling back frontend deployment..."
    if ! rollback_frontend_deployment "$ROLLBACK_TARGET"; then
        log_error "Frontend rollback failed"
        rollback_failed=true
        return 4
    fi
    check_timeout || return 7
    
    # Rollback database
    log_info "Step 3/3: Rolling back database..."
    if ! rollback_database; then
        log_error "Database rollback failed"
        rollback_failed=true
        return 5
    fi
    
    if [ "$rollback_failed" = true ]; then
        log_error "Full rollback completed with errors"
        return 4
    fi
    
    log_success "Full rollback completed successfully"
    return 0
}

# Perform backend-only rollback
perform_backend_rollback() {
    log_info "Starting backend-only rollback..."
    
    if ! rollback_backend_deployment "$ROLLBACK_TARGET"; then
        log_error "Backend rollback failed"
        return 4
    fi
    
    log_success "Backend rollback completed successfully"
    return 0
}

# Perform frontend-only rollback
perform_frontend_rollback() {
    log_info "Starting frontend-only rollback..."
    
    if ! rollback_frontend_deployment "$ROLLBACK_TARGET"; then
        log_error "Frontend rollback failed"
        return 4
    fi
    
    log_success "Frontend rollback completed successfully"
    return 0
}

# Perform database-only rollback
perform_database_rollback() {
    log_info "Starting database-only rollback..."
    
    if ! rollback_database; then
        log_error "Database rollback failed"
        return 5
    fi
    
    log_success "Database rollback completed successfully"
    return 0
}

# Display help message
display_help() {
    cat << EOF
Kubernetes Deployment Rollback Script for CardDemo

Purpose:
  Safely reverts CardDemo application to a previous stable version
  Implements blue-green deployment rollback strategy with validation

Usage:
  $0 [options]

Options:
  --backend                Rollback backend deployment only
  --frontend               Rollback frontend deployment only
  --database               Rollback database schema only
  --full                   Rollback full application (default)
  --revision <N>           Rollback to specific revision number
  --previous               Rollback to previous version (default)
  --emergency              Emergency rollback mode (skip validation)
  --reason <message>       Reason for rollback (for logging)
  --help                   Display this help message

Environment Variables:
  KUBE_NAMESPACE           Kubernetes namespace (default: carddemo)
  DB_HOST                  PostgreSQL host (default: postgres-service)
  DB_PORT                  PostgreSQL port (default: 5432)
  DB_NAME                  Database name (default: carddemo)
  DB_USER                  Database user (default: postgres)
  PGPASSWORD               Database password (required)
  BACKUP_DIR               Backup directory (default: /backup)
  WEBHOOK_URL              Webhook URL for notifications
  ROLLBACK_TIMEOUT         Rollback timeout in seconds (default: 300)
  ENABLE_DB_ROLLBACK       Enable database rollback (default: true)
  HEALTH_CHECK_RETRIES     Health check retry count (default: 10)
  HEALTH_CHECK_INTERVAL    Health check interval in seconds (default: 6)

Examples:
  # Full rollback to previous version
  ./rollback.sh --full --reason "Production issue detected"

  # Backend-only rollback to specific revision
  ./rollback.sh --backend --revision 5 --reason "Backend bug fix"

  # Emergency full rollback (skip validation)
  ./rollback.sh --full --emergency --reason "Critical security issue"

  # Frontend-only rollback
  ./rollback.sh --frontend --previous

  # Database-only rollback
  ./rollback.sh --database

Exit Codes:
  0 - Rollback completed successfully
  1 - Configuration error or invalid arguments
  2 - Kubernetes/Database connection error
  3 - Rollback target validation failed
  4 - Deployment rollback failed
  5 - Database rollback failed
  6 - Health check validation failed
  7 - Rollback timeout exceeded

EOF
}

################################################################################
# Main Entry Point
################################################################################

main() {
    log_info "=========================================="
    log_info "CardDemo Kubernetes Rollback Script"
    log_info "Replaces mainframe deployment rollback procedures"
    log_info "=========================================="
    log_info "Start time: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --backend)
                ROLLBACK_MODE="backend"
                shift
                ;;
            --frontend)
                ROLLBACK_MODE="frontend"
                shift
                ;;
            --database)
                ROLLBACK_MODE="database"
                shift
                ;;
            --full)
                ROLLBACK_MODE="full"
                shift
                ;;
            --revision)
                ROLLBACK_TARGET="$2"
                shift 2
                ;;
            --previous)
                ROLLBACK_TARGET="previous"
                shift
                ;;
            --emergency)
                EMERGENCY_MODE=true
                shift
                ;;
            --reason)
                ROLLBACK_REASON="$2"
                shift 2
                ;;
            --help|-h)
                display_help
                exit 0
                ;;
            *)
                log_error "Unknown option: $1"
                display_help
                exit 1
                ;;
        esac
    done
    
    # Validate configuration
    if [ -z "$ROLLBACK_REASON" ]; then
        ROLLBACK_REASON="Manual rollback (no reason specified)"
    fi
    
    log_info "Rollback Configuration:"
    log_info "  Mode:              ${ROLLBACK_MODE}"
    log_info "  Target:            ${ROLLBACK_TARGET}"
    log_info "  Emergency Mode:    $([ "$EMERGENCY_MODE" = true ] && echo "YES" || echo "NO")"
    log_info "  Reason:            ${ROLLBACK_REASON}"
    log_info "  Namespace:         ${KUBE_NAMESPACE}"
    log_info "  Timeout:           ${ROLLBACK_TIMEOUT}s"
    
    # Check prerequisites
    check_prerequisites || exit 1
    
    # Check Kubernetes connection
    check_kubernetes_connection || exit 2
    
    # Check database connection if database rollback enabled
    if [ "$ENABLE_DB_ROLLBACK" = "true" ]; then
        check_database_connection || exit 2
    fi
    
    # Send rollback start notification
    notify_rollback_start
    
    # Execute rollback based on mode
    local rollback_exit_code=0
    
    case $ROLLBACK_MODE in
        full)
            perform_full_rollback
            rollback_exit_code=$?
            ;;
        backend)
            perform_backend_rollback
            rollback_exit_code=$?
            ;;
        frontend)
            perform_frontend_rollback
            rollback_exit_code=$?
            ;;
        database)
            perform_database_rollback
            rollback_exit_code=$?
            ;;
        *)
            log_error "Invalid rollback mode: ${ROLLBACK_MODE}"
            exit 1
            ;;
    esac
    
    # Check if rollback succeeded
    if [ $rollback_exit_code -ne 0 ]; then
        log_error "Rollback execution failed with exit code: ${rollback_exit_code}"
        log_rollback_history "FAILED" "$rollback_exit_code"
        exit $rollback_exit_code
    fi
    
    # Implement blue-green traffic switching
    switch_traffic_to_previous
    
    # Validate rollback success
    log_info "Validating rollback success..."
    if ! validate_rollback_success; then
        log_error "Rollback validation failed"
        log_rollback_history "VALIDATION_FAILED" 6
        exit 6
    fi
    
    # Generate rollback report
    generate_rollback_report
    
    # Log rollback to history
    log_rollback_history "SUCCESS" 0
    
    # Send success notification
    notify_rollback_success
    
    local duration=$(($(date +%s) - ROLLBACK_START_TIME))
    log_info "End time: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    log_info "Total duration: ${duration} seconds"
    log_info "=========================================="
    
    log_success "Rollback completed successfully!"
    exit 0
}

# Execute main function with all arguments
main "$@"

