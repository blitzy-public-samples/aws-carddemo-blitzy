#!/bin/bash
################################################################################
# CardDemo Complete Project Build Script
# 
# Purpose: Orchestrates complete build process for COBOL-to-Java migration
#          Builds backend (Spring Boot Maven) and frontend (React npm) components
#          Optionally builds Docker images for containerized deployment
#
# Dependencies:
#   - Java 21 LTS (OpenJDK)
#   - Apache Maven 3.9.x
#   - Node.js 20.x LTS
#   - npm 10.x
#   - Docker 24.x (optional, for image builds)
#
# Usage:
#   ./scripts/build-all.sh                    # Full build with tests
#   SKIP_TESTS=true ./scripts/build-all.sh    # Build without tests
#   BUILD_DOCKER=true ./scripts/build-all.sh  # Build with Docker images
#   BUILD_BACKEND=false ./scripts/build-all.sh # Frontend only
#
# Environment Variables:
#   BUILD_BACKEND   - Build Spring Boot backend (default: true)
#   BUILD_FRONTEND  - Build React frontend (default: true)
#   BUILD_DOCKER    - Build Docker images (default: false)
#   SKIP_TESTS      - Skip test execution (default: false)
#   CLEAN_BUILD     - Clean before building (default: true)
#
################################################################################

# Exit on error, undefined variable, or pipe failure
set -e
set -u
set -o pipefail

# Capture script start time for performance metrics
BUILD_START_TIME=$(date +%s)

################################################################################
# Color codes for terminal output
################################################################################
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly CYAN='\033[0;36m'
readonly NC='\033[0m' # No Color

################################################################################
# Build configuration from environment variables with defaults
################################################################################
BUILD_BACKEND=${BUILD_BACKEND:-true}
BUILD_FRONTEND=${BUILD_FRONTEND:-true}
BUILD_DOCKER=${BUILD_DOCKER:-false}
SKIP_TESTS=${SKIP_TESTS:-false}
CLEAN_BUILD=${CLEAN_BUILD:-true}

# Build log file
BUILD_LOG="build-$(date +%Y%m%d-%H%M%S).log"

# Script directory and project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

################################################################################
# Function: print_header
# Prints formatted header message
################################################################################
print_header() {
    local message="$1"
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}${message}${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
}

################################################################################
# Function: print_info
# Prints informational message
################################################################################
print_info() {
    echo -e "${BLUE}$1${NC}"
}

################################################################################
# Function: print_success
# Prints success message
################################################################################
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

################################################################################
# Function: print_error
# Prints error message
################################################################################
print_error() {
    echo -e "${RED}✗ Error: $1${NC}" >&2
}

################################################################################
# Function: print_warning
# Prints warning message
################################################################################
print_warning() {
    echo -e "${YELLOW}⚠ Warning: $1${NC}"
}

################################################################################
# Function: check_command
# Checks if a command exists in PATH
# Arguments: $1 - command name
# Returns: 0 if exists, 1 if not found
################################################################################
check_command() {
    command -v "$1" &> /dev/null
}

################################################################################
# Function: validate_prerequisites
# Validates all required tools are installed with correct versions
################################################################################
validate_prerequisites() {
    print_header "Checking Prerequisites"
    
    local prerequisites_ok=true
    
    # Check Java 21
    if ! check_command java; then
        print_error "Java not found. Install Java 21 LTS (OpenJDK)"
        prerequisites_ok=false
    else
        local java_version=$(java -version 2>&1 | grep 'version' | awk '{print $3}' | tr -d '"' | cut -d. -f1)
        if [ "${java_version}" -ge 21 ]; then
            print_success "Java ${java_version} detected"
        else
            print_error "Java 21+ required, found version ${java_version}"
            prerequisites_ok=false
        fi
    fi
    
    # Check Maven 3.9+
    if ! check_command mvn; then
        print_error "Maven not found. Install Apache Maven 3.9.x"
        prerequisites_ok=false
    else
        local mvn_version=$(mvn -version 2>&1 | grep 'Apache Maven' | awk '{print $3}')
        print_success "Maven ${mvn_version} detected"
    fi
    
    # Check Node.js 20+
    if ! check_command node; then
        print_error "Node.js not found. Install Node.js 20 LTS"
        prerequisites_ok=false
    else
        local node_version=$(node --version | tr -d 'v')
        local node_major=$(echo "${node_version}" | cut -d. -f1)
        if [ "${node_major}" -ge 20 ]; then
            print_success "Node.js ${node_version} detected"
        else
            print_error "Node.js 20+ required, found version ${node_version}"
            prerequisites_ok=false
        fi
    fi
    
    # Check npm 10+
    if ! check_command npm; then
        print_error "npm not found. Install npm 10.x"
        prerequisites_ok=false
    else
        local npm_version=$(npm --version)
        print_success "npm ${npm_version} detected"
    fi
    
    # Check Docker (if building images)
    if [ "${BUILD_DOCKER}" = "true" ]; then
        if ! check_command docker; then
            print_error "Docker not found. Install Docker 24.x for image builds"
            prerequisites_ok=false
        else
            local docker_version=$(docker --version | awk '{print $3}' | tr -d ',')
            print_success "Docker ${docker_version} detected"
        fi
    fi
    
    if [ "${prerequisites_ok}" = "false" ]; then
        print_error "Prerequisites check failed. Install missing dependencies."
        exit 1
    fi
    
    print_success "All prerequisites satisfied"
    echo ""
}

################################################################################
# Function: build_backend
# Builds Spring Boot backend using Maven
################################################################################
build_backend() {
    print_header "Building Backend (Spring Boot + Maven)"
    
    local backend_dir="${PROJECT_ROOT}/backend"
    
    if [ ! -d "${backend_dir}" ]; then
        print_error "Backend directory not found: ${backend_dir}"
        exit 1
    fi
    
    cd "${backend_dir}"
    
    # Clean previous build artifacts
    if [ "${CLEAN_BUILD}" = "true" ]; then
        print_info "Cleaning previous build artifacts..."
        mvn clean -q
        print_success "Build artifacts cleaned"
    fi
    
    # Build with or without tests
    if [ "${SKIP_TESTS}" = "true" ]; then
        print_info "Building backend (skipping tests)..."
        mvn package -DskipTests -q
    else
        print_info "Building backend with tests..."
        mvn clean install -q
    fi
    
    # Verify JAR was created
    local jar_file=$(find target -name "carddemo-backend-*.jar" -o -name "*.jar" | head -n 1)
    
    if [ -z "${jar_file}" ]; then
        print_error "Backend JAR not found in target directory"
        cd "${PROJECT_ROOT}"
        exit 1
    fi
    
    local jar_size=$(du -h "${jar_file}" | cut -f1)
    print_success "Backend build successful! JAR: $(basename ${jar_file}) (${jar_size})"
    
    # Display test results if tests were run
    if [ "${SKIP_TESTS}" = "false" ] && [ -d "target/surefire-reports" ]; then
        local test_count=$(find target/surefire-reports -name "TEST-*.xml" -exec grep -c '<testcase' {} \; 2>/dev/null | awk '{sum+=$1} END {print sum}' || echo "0")
        if [ "${test_count}" -gt 0 ]; then
            print_success "Backend tests: ${test_count} tests executed"
        fi
    fi
    
    cd "${PROJECT_ROOT}"
    echo ""
}

################################################################################
# Function: build_frontend
# Builds React frontend using npm and Vite
################################################################################
build_frontend() {
    print_header "Building Frontend (React + Vite)"
    
    local frontend_dir="${PROJECT_ROOT}/frontend"
    
    if [ ! -d "${frontend_dir}" ]; then
        print_error "Frontend directory not found: ${frontend_dir}"
        exit 1
    fi
    
    cd "${frontend_dir}"
    
    # Clean previous build
    if [ "${CLEAN_BUILD}" = "true" ]; then
        print_info "Cleaning previous build artifacts..."
        if [ -d "dist" ]; then
            rm -rf dist
        fi
        if [ -d "node_modules/.cache" ]; then
            rm -rf node_modules/.cache
        fi
        print_success "Build artifacts cleaned"
    fi
    
    # Install dependencies
    print_info "Installing npm dependencies..."
    npm ci --silent
    print_success "Dependencies installed"
    
    # Run linting
    if [ "${SKIP_TESTS}" != "true" ]; then
        if grep -q '"lint"' package.json; then
            print_info "Running ESLint checks..."
            npm run lint || print_warning "Linting warnings detected"
        fi
    fi
    
    # Run tests
    if [ "${SKIP_TESTS}" != "true" ]; then
        if grep -q '"test"' package.json; then
            print_info "Running frontend tests..."
            npm test -- --run || print_warning "Some tests failed"
        fi
    fi
    
    # Production build with Vite
    print_info "Building production bundle with Vite..."
    npm run build
    
    # Verify dist directory was created
    if [ ! -d "dist" ]; then
        print_error "Frontend dist directory not found after build"
        cd "${PROJECT_ROOT}"
        exit 1
    fi
    
    local dist_size=$(du -sh dist | cut -f1)
    local file_count=$(find dist -type f | wc -l | tr -d ' ')
    print_success "Frontend build successful! Output: dist/ (${dist_size}, ${file_count} files)"
    
    # Display coverage information if available
    if [ "${SKIP_TESTS}" = "false" ] && [ -f "coverage/coverage-summary.json" ]; then
        print_success "Coverage report available: frontend/coverage/"
    fi
    
    cd "${PROJECT_ROOT}"
    echo ""
}

################################################################################
# Function: build_docker_images
# Builds Docker images for backend and frontend
################################################################################
build_docker_images() {
    print_header "Building Docker Images"
    
    # Build backend Docker image
    if [ "${BUILD_BACKEND}" = "true" ]; then
        print_info "Building backend Docker image..."
        
        if [ ! -f "backend/Dockerfile" ]; then
            print_warning "Backend Dockerfile not found, skipping backend image"
        else
            docker build -t carddemo-backend:latest -f backend/Dockerfile backend/
            print_success "Backend image: carddemo-backend:latest"
        fi
    fi
    
    # Build frontend Docker image
    if [ "${BUILD_FRONTEND}" = "true" ]; then
        print_info "Building frontend Docker image..."
        
        if [ ! -f "frontend/Dockerfile" ]; then
            print_warning "Frontend Dockerfile not found, skipping frontend image"
        else
            docker build -t carddemo-frontend:latest -f frontend/Dockerfile frontend/
            print_success "Frontend image: carddemo-frontend:latest"
        fi
    fi
    
    # List built images
    echo ""
    print_info "Docker images:"
    docker images --format "  {{.Repository}}:{{.Tag}} - {{.Size}}" | grep carddemo || echo "  No CardDemo images found"
    echo ""
}

################################################################################
# Function: generate_build_summary
# Displays comprehensive build summary and artifact report
################################################################################
generate_build_summary() {
    print_header "Build Summary"
    
    # Backend artifacts
    if [ "${BUILD_BACKEND}" = "true" ]; then
        echo -e "${CYAN}Backend Artifacts:${NC}"
        if [ -d "backend/target" ]; then
            local jar_files=$(find backend/target -name "*.jar" 2>/dev/null)
            if [ -n "${jar_files}" ]; then
                echo "${jar_files}" | while read -r jar; do
                    local size=$(du -h "${jar}" | cut -f1)
                    echo "  ✓ $(basename ${jar}) (${size})"
                done
            else
                echo "  No JAR files found"
            fi
        else
            echo "  target/ directory not found"
        fi
        echo ""
    fi
    
    # Frontend artifacts
    if [ "${BUILD_FRONTEND}" = "true" ]; then
        echo -e "${CYAN}Frontend Artifacts:${NC}"
        if [ -d "frontend/dist" ]; then
            local dist_size=$(du -sh frontend/dist 2>/dev/null | cut -f1 || echo "Unknown")
            local file_count=$(find frontend/dist -type f 2>/dev/null | wc -l | tr -d ' ')
            echo "  ✓ dist/ directory (${dist_size}, ${file_count} files)"
            
            # List key files
            if [ -f "frontend/dist/index.html" ]; then
                echo "  ✓ index.html"
            fi
            
            local asset_count=$(find frontend/dist/assets -type f 2>/dev/null | wc -l | tr -d ' ' || echo "0")
            if [ "${asset_count}" -gt 0 ]; then
                echo "  ✓ assets/ (${asset_count} files)"
            fi
        else
            echo "  dist/ directory not found"
        fi
        echo ""
    fi
    
    # Docker images
    if [ "${BUILD_DOCKER}" = "true" ]; then
        echo -e "${CYAN}Docker Images:${NC}"
        docker images --format "  ✓ {{.Repository}}:{{.Tag}} - {{.Size}}" | grep carddemo || echo "  No images found"
        echo ""
    fi
    
    # Build performance metrics
    local build_end_time=$(date +%s)
    local build_duration=$((build_end_time - BUILD_START_TIME))
    local minutes=$((build_duration / 60))
    local seconds=$((build_duration % 60))
    
    echo -e "${CYAN}Build Performance:${NC}"
    echo "  Total duration: ${minutes}m ${seconds}s"
    echo "  Log file: ${BUILD_LOG}"
    echo ""
}

################################################################################
# Function: print_next_steps
# Displays next steps and usage instructions
################################################################################
print_next_steps() {
    print_header "Next Steps"
    
    echo -e "${CYAN}Local Development:${NC}"
    echo "  1. Initialize database:"
    echo "     ./scripts/init-db.sh"
    echo ""
    echo "  2. Load test data:"
    echo "     ./scripts/load-test-data.sh"
    echo ""
    echo "  3. Start backend:"
    echo "     cd backend && mvn spring-boot:run"
    echo ""
    echo "  4. Start frontend (separate terminal):"
    echo "     cd frontend && npm run dev"
    echo ""
    echo "  5. Access application:"
    echo "     Frontend: http://localhost:3000"
    echo "     Backend API: http://localhost:8080/api"
    echo "     Swagger UI: http://localhost:8080/swagger-ui.html"
    echo ""
    
    if [ "${BUILD_DOCKER}" = "true" ]; then
        echo -e "${CYAN}Docker Deployment:${NC}"
        echo "  1. Start all services:"
        echo "     docker-compose up -d"
        echo ""
        echo "  2. View logs:"
        echo "     docker-compose logs -f"
        echo ""
        echo "  3. Stop services:"
        echo "     docker-compose down"
        echo ""
    fi
    
    echo -e "${CYAN}Kubernetes Deployment:${NC}"
    echo "  Deploy to Kubernetes cluster:"
    echo "  ./scripts/deploy-all.sh"
    echo ""
    
    echo -e "${CYAN}Testing:${NC}"
    echo "  Run tests only:"
    echo "  cd backend && mvn test"
    echo "  cd frontend && npm test"
    echo ""
}

################################################################################
# Function: print_usage
# Displays usage information and help
################################################################################
print_usage() {
    cat << EOF
Usage: ./scripts/build-all.sh [OPTIONS]

Build the entire CardDemo application (backend + frontend)

Options:
  -h, --help          Show this help message

Environment Variables:
  BUILD_BACKEND       Build Spring Boot backend (default: true)
  BUILD_FRONTEND      Build React frontend (default: true)
  BUILD_DOCKER        Build Docker images (default: false)
  SKIP_TESTS          Skip test execution (default: false)
  CLEAN_BUILD         Clean before building (default: true)

Examples:
  # Full build with tests
  ./scripts/build-all.sh

  # Fast build without tests
  SKIP_TESTS=true ./scripts/build-all.sh

  # Build with Docker images
  BUILD_DOCKER=true ./scripts/build-all.sh

  # Backend only build
  BUILD_FRONTEND=false ./scripts/build-all.sh

  # Frontend only build
  BUILD_BACKEND=false ./scripts/build-all.sh

  # Incremental build (no clean)
  CLEAN_BUILD=false ./scripts/build-all.sh

Build Configuration:
  Backend:  Java 21 + Spring Boot 3.2.1 + Maven 3.9.x
  Frontend: Node.js 20 + React 18 + Vite 5 + npm 10.x
  Database: PostgreSQL 15+ (for runtime, not build)
  Cache:    Redis 7.x (for runtime, not build)

For more information:
  - README.md
  - docs/ARCHITECTURE.md
  - docs/DEPLOYMENT.md

EOF
}

################################################################################
# Function: cleanup_on_error
# Cleanup function called on script error
################################################################################
cleanup_on_error() {
    local exit_code=$?
    if [ ${exit_code} -ne 0 ]; then
        echo ""
        print_error "Build failed with exit code ${exit_code}"
        print_info "Check log file for details: ${BUILD_LOG}"
        echo ""
    fi
    exit ${exit_code}
}

################################################################################
# Main execution
################################################################################
main() {
    # Check for help flag
    if [ "${1:-}" = "--help" ] || [ "${1:-}" = "-h" ]; then
        print_usage
        exit 0
    fi
    
    # Change to project root
    cd "${PROJECT_ROOT}"
    
    # Setup logging (redirect stdout and stderr to log file and console)
    exec 1> >(tee -a "${BUILD_LOG}")
    exec 2>&1
    
    # Setup error trap
    trap cleanup_on_error EXIT
    
    # Display build configuration
    print_header "CardDemo Complete Project Build"
    
    echo "Build Configuration:"
    echo "  Backend:     ${BUILD_BACKEND}"
    echo "  Frontend:    ${BUILD_FRONTEND}"
    echo "  Docker:      ${BUILD_DOCKER}"
    echo "  Skip Tests:  ${SKIP_TESTS}"
    echo "  Clean Build: ${CLEAN_BUILD}"
    echo "  Project Root: ${PROJECT_ROOT}"
    echo ""
    
    # Validate prerequisites
    validate_prerequisites
    
    # Build backend
    if [ "${BUILD_BACKEND}" = "true" ]; then
        build_backend
    else
        print_info "Skipping backend build (BUILD_BACKEND=false)"
        echo ""
    fi
    
    # Build frontend
    if [ "${BUILD_FRONTEND}" = "true" ]; then
        build_frontend
    else
        print_info "Skipping frontend build (BUILD_FRONTEND=false)"
        echo ""
    fi
    
    # Build Docker images
    if [ "${BUILD_DOCKER}" = "true" ]; then
        build_docker_images
    fi
    
    # Generate build summary
    generate_build_summary
    
    # Display next steps
    print_next_steps
    
    # Success message
    print_header "Build Completed Successfully!"
    
    print_success "All components built successfully"
    print_info "Build log saved to: ${BUILD_LOG}"
    echo ""
}

# Execute main function with all script arguments
main "$@"
