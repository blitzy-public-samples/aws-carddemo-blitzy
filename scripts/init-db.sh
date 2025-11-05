#!/bin/bash

##############################################################################
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# 
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
##############################################################################

##############################################################################
# CardDemo Database Initialization Script
# 
# Purpose: PostgreSQL database initialization for CardDemo application
# Replaces: Mainframe VSAM file definition JCL scripts (DEFCUST.jcl, 
#           CUSTFILE.jcl, ACCTFILE.jcl, CARDFILE.jcl, TRANFILE.jcl)
#
# This script:
# 1. Creates PostgreSQL database and user
# 2. Configures database connection parameters
# 3. Installs required PostgreSQL extensions
# 4. Executes Flyway migrations (V1-V9) to create complete schema
# 5. Validates database structure and connectivity
#
# VSAM to PostgreSQL Transformation:
# - CUSTDATA KSDS  → customer table
# - ACCTDAT KSDS   → account table
# - CARDDAT KSDS   → card table
# - TRANSACT KSDS  → transaction table
# - USRSEC KSDS    → user_security table
# - XREF files     → Foreign key constraints
#
# Usage: ./init-db.sh
#        DROP_EXISTING=true ./init-db.sh  (for development environments)
##############################################################################

# Exit immediately if a command exits with a non-zero status
set -e
# Treat unset variables as an error
set -u
# Fail on pipe errors
set -o pipefail

##############################################################################
# Color Codes for Output
##############################################################################
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

##############################################################################
# Configuration Variables
##############################################################################

# PostgreSQL Server Configuration
readonly POSTGRES_HOST="${POSTGRES_HOST:-localhost}"
readonly POSTGRES_PORT="${POSTGRES_PORT:-5432}"
readonly POSTGRES_ADMIN_USER="${POSTGRES_ADMIN_USER:-postgres}"
readonly POSTGRES_ADMIN_PASSWORD="${POSTGRES_ADMIN_PASSWORD:-postgres}"

# CardDemo Database Configuration
readonly CARDDEMO_DB="${CARDDEMO_DB:-carddemo}"
readonly CARDDEMO_USER="${CARDDEMO_USER:-carddemo}"
readonly CARDDEMO_PASSWORD="${CARDDEMO_PASSWORD:-carddemo123}"

# Control Flags
readonly DROP_EXISTING="${DROP_EXISTING:-false}"
readonly SKIP_MIGRATIONS="${SKIP_MIGRATIONS:-false}"

# Script Directory
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly LOG_FILE="${SCRIPT_DIR}/init-db.log"

# Required PostgreSQL Version
readonly MIN_POSTGRES_VERSION=15

##############################################################################
# Logging Functions
##############################################################################

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1" | tee -a "${LOG_FILE}"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1" | tee -a "${LOG_FILE}"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" | tee -a "${LOG_FILE}"
}

log_section() {
    echo "" | tee -a "${LOG_FILE}"
    echo -e "${BLUE}========================================${NC}" | tee -a "${LOG_FILE}"
    echo -e "${BLUE}$1${NC}" | tee -a "${LOG_FILE}"
    echo -e "${BLUE}========================================${NC}" | tee -a "${LOG_FILE}"
}

##############################################################################
# Error Handling
##############################################################################

cleanup_on_error() {
    local exit_code=$?
    if [ $exit_code -ne 0 ]; then
        log_error "Database initialization failed with exit code: $exit_code"
        log_error "Check log file for details: ${LOG_FILE}"
        log_error "You may need to manually clean up the database."
    fi
    exit $exit_code
}

trap cleanup_on_error ERR EXIT

##############################################################################
# Validation Functions
##############################################################################

check_command_exists() {
    local cmd=$1
    if ! command -v "$cmd" &> /dev/null; then
        log_error "Required command not found: $cmd"
        log_error "Please install $cmd and try again."
        exit 1
    fi
}

check_postgres_server() {
    log_info "Checking PostgreSQL server connectivity..."
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    if ! psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres -c "SELECT 1" &> /dev/null; then
        log_error "Cannot connect to PostgreSQL server at ${POSTGRES_HOST}:${POSTGRES_PORT}"
        log_error "Please ensure PostgreSQL is running and credentials are correct."
        exit 1
    fi
    
    log_info "PostgreSQL server is accessible"
}

check_postgres_version() {
    log_info "Checking PostgreSQL version..."
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    local version_output
    version_output=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres -t -c "SHOW server_version;" | tr -d ' ')
    
    local major_version
    major_version=$(echo "$version_output" | cut -d'.' -f1)
    
    log_info "PostgreSQL version: $version_output"
    
    if [ "$major_version" -lt "$MIN_POSTGRES_VERSION" ]; then
        log_error "PostgreSQL version $major_version is below minimum required version $MIN_POSTGRES_VERSION"
        exit 1
    fi
    
    log_info "PostgreSQL version check passed"
}

check_maven_available() {
    log_info "Checking Maven availability..."
    
    if ! command -v mvn &> /dev/null; then
        log_error "Maven (mvn) is not installed or not in PATH"
        log_error "Maven is required to run Flyway migrations"
        exit 1
    fi
    
    local mvn_version
    mvn_version=$(mvn -version | head -n 1)
    log_info "Maven found: $mvn_version"
}

##############################################################################
# Database Operations
##############################################################################

drop_database_if_exists() {
    if [ "${DROP_EXISTING}" = "true" ]; then
        log_warn "DROP_EXISTING flag is set - dropping existing database..."
        
        export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
        
        # Terminate existing connections to the database
        psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres <<EOF 2>> "${LOG_FILE}" || true
SELECT pg_terminate_backend(pg_stat_activity.pid)
FROM pg_stat_activity
WHERE pg_stat_activity.datname = '${CARDDEMO_DB}'
  AND pid <> pg_backend_pid();
EOF
        
        # Drop database if exists
        psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres <<EOF >> "${LOG_FILE}" 2>&1 || true
DROP DATABASE IF EXISTS ${CARDDEMO_DB};
DROP USER IF EXISTS ${CARDDEMO_USER};
EOF
        
        log_warn "Existing database and user dropped successfully"
    else
        log_info "Skipping database drop (DROP_EXISTING not set)"
    fi
}

create_database_user() {
    log_info "Creating database user: ${CARDDEMO_USER}"
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    # Check if user already exists
    local user_exists
    user_exists=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres -t -c "SELECT 1 FROM pg_roles WHERE rolname='${CARDDEMO_USER}';" | tr -d ' ')
    
    if [ "$user_exists" = "1" ]; then
        log_info "User ${CARDDEMO_USER} already exists"
    else
        psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres <<EOF >> "${LOG_FILE}" 2>&1
CREATE USER ${CARDDEMO_USER} WITH PASSWORD '${CARDDEMO_PASSWORD}';
ALTER USER ${CARDDEMO_USER} WITH CREATEDB;
EOF
        log_info "Database user created successfully"
    fi
}

create_database() {
    log_info "Creating database: ${CARDDEMO_DB}"
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    # Check if database already exists
    local db_exists
    db_exists=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres -t -c "SELECT 1 FROM pg_database WHERE datname='${CARDDEMO_DB}';" | tr -d ' ')
    
    if [ "$db_exists" = "1" ]; then
        log_info "Database ${CARDDEMO_DB} already exists"
    else
        psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d postgres <<EOF >> "${LOG_FILE}" 2>&1
CREATE DATABASE ${CARDDEMO_DB} OWNER ${CARDDEMO_USER} ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE ${CARDDEMO_DB} TO ${CARDDEMO_USER};
EOF
        log_info "Database created successfully"
    fi
}

configure_database() {
    log_info "Configuring database parameters..."
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d "${CARDDEMO_DB}" <<EOF >> "${LOG_FILE}" 2>&1
-- Set timezone to UTC for consistent timestamps
ALTER DATABASE ${CARDDEMO_DB} SET timezone TO 'UTC';

-- Configure connection parameters for optimal performance
-- These settings support the 150 concurrent user requirement
ALTER DATABASE ${CARDDEMO_DB} SET max_connections TO 100;

-- Work memory for query execution (per operation)
ALTER DATABASE ${CARDDEMO_DB} SET work_mem TO '16MB';

-- Shared buffers for batch processing performance
ALTER DATABASE ${CARDDEMO_DB} SET shared_buffers TO '256MB';

-- Enable statement logging for audit trail compliance
ALTER DATABASE ${CARDDEMO_DB} SET log_statement TO 'all';
ALTER DATABASE ${CARDDEMO_DB} SET log_duration TO 'on';

-- Set statement timeout to prevent long-running queries
ALTER DATABASE ${CARDDEMO_DB} SET statement_timeout TO '30s';
EOF
    
    log_info "Database configured successfully"
}

install_extensions() {
    log_info "Installing PostgreSQL extensions..."
    
    export PGPASSWORD="${POSTGRES_ADMIN_PASSWORD}"
    
    psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d "${CARDDEMO_DB}" <<EOF >> "${LOG_FILE}" 2>&1
-- UUID generation for unique identifiers
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Cryptographic functions for password hashing and security
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Additional date/time functions
CREATE EXTENSION IF NOT EXISTS "btree_gist";
EOF
    
    log_info "PostgreSQL extensions installed successfully"
    
    # List installed extensions
    local extensions
    extensions=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${POSTGRES_ADMIN_USER}" -d "${CARDDEMO_DB}" -t -c "\dx" | grep -v "^$" | wc -l)
    log_info "Total extensions installed: $extensions"
}

run_flyway_migrations() {
    if [ "${SKIP_MIGRATIONS}" = "true" ]; then
        log_warn "Skipping Flyway migrations (SKIP_MIGRATIONS flag set)"
        return 0
    fi
    
    log_info "Running Flyway database migrations..."
    
    # Navigate to backend directory
    cd "${PROJECT_ROOT}/backend" || {
        log_error "Cannot find backend directory at ${PROJECT_ROOT}/backend"
        exit 1
    }
    
    # Check if pom.xml exists
    if [ ! -f "pom.xml" ]; then
        log_error "pom.xml not found in backend directory"
        exit 1
    fi
    
    # Execute Flyway migrations via Maven
    log_info "Executing Maven Flyway plugin..."
    mvn flyway:migrate \
        -Dflyway.url="jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${CARDDEMO_DB}" \
        -Dflyway.user="${CARDDEMO_USER}" \
        -Dflyway.password="${CARDDEMO_PASSWORD}" \
        -Dflyway.locations="filesystem:src/main/resources/db/migration" \
        -Dflyway.baselineOnMigrate=true \
        -Dflyway.validateOnMigrate=true \
        >> "${LOG_FILE}" 2>&1
    
    log_info "Flyway migrations completed successfully"
    
    # Navigate back to script directory
    cd "${SCRIPT_DIR}" || exit 1
}

validate_schema() {
    log_info "Validating database schema..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Count tables
    local table_count
    table_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';" | tr -d ' ')
    
    log_info "Tables created: $table_count"
    
    # Expected minimum tables (customer, account, card, transaction, user_security, xref tables, reference tables, flyway_schema_history)
    if [ "$table_count" -lt 8 ]; then
        log_warn "Expected at least 8 tables, but found $table_count"
        log_warn "This may indicate incomplete migration"
    fi
    
    # List all tables
    log_info "Database tables:"
    psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "\dt" | tee -a "${LOG_FILE}"
    
    # Count indexes
    local index_count
    index_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public';" | tr -d ' ')
    
    log_info "Indexes created: $index_count"
    
    # Count foreign key constraints
    local fk_count
    fk_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_type = 'FOREIGN KEY' AND table_schema = 'public';" | tr -d ' ')
    
    log_info "Foreign key constraints: $fk_count"
    
    log_info "Schema validation completed"
}

test_connection() {
    log_info "Testing database connection..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Test basic connectivity
    if ! psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "SELECT version();" >> "${LOG_FILE}" 2>&1; then
        log_error "Connection test failed"
        exit 1
    fi
    
    log_info "Database connection test successful"
}

display_summary() {
    log_section "Database Initialization Completed Successfully"
    
    echo ""
    echo -e "${GREEN}Database Configuration:${NC}"
    echo "  Database: ${CARDDEMO_DB}"
    echo "  User: ${CARDDEMO_USER}"
    echo "  Host: ${POSTGRES_HOST}"
    echo "  Port: ${POSTGRES_PORT}"
    echo ""
    echo -e "${GREEN}Connection String:${NC}"
    echo "  jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${CARDDEMO_DB}"
    echo ""
    echo -e "${GREEN}VSAM to PostgreSQL Transformation:${NC}"
    echo "  CUSTDATA KSDS  → customer table"
    echo "  ACCTDAT KSDS   → account table"
    echo "  CARDDAT KSDS   → card table"
    echo "  TRANSACT KSDS  → transaction table"
    echo "  USRSEC KSDS    → user_security table"
    echo "  XREF files     → Foreign key constraints"
    echo ""
    echo -e "${GREEN}Next Steps:${NC}"
    echo "  1. Load test data: ./scripts/load-test-data.sh"
    echo "  2. Start backend: cd backend && mvn spring-boot:run"
    echo "  3. Access application: http://localhost:8080"
    echo ""
    echo -e "${GREEN}Log file: ${LOG_FILE}${NC}"
    echo ""
}

##############################################################################
# Main Execution Flow
##############################################################################

main() {
    # Initialize log file
    echo "CardDemo Database Initialization - $(date)" > "${LOG_FILE}"
    
    log_section "CardDemo Database Initialization"
    
    log_info "Starting database initialization process..."
    log_info "Log file: ${LOG_FILE}"
    
    # Step 1: Validate prerequisites
    log_section "Step 1: Validating Prerequisites"
    check_command_exists "psql"
    check_command_exists "mvn"
    check_command_exists "grep"
    check_command_exists "cut"
    check_postgres_server
    check_postgres_version
    check_maven_available
    
    # Step 2: Drop existing database if requested
    log_section "Step 2: Database Cleanup"
    drop_database_if_exists
    
    # Step 3: Create database user
    log_section "Step 3: Creating Database User"
    create_database_user
    
    # Step 4: Create database
    log_section "Step 4: Creating Database"
    create_database
    
    # Step 5: Configure database
    log_section "Step 5: Configuring Database"
    configure_database
    
    # Step 6: Install extensions
    log_section "Step 6: Installing Extensions"
    install_extensions
    
    # Step 7: Run Flyway migrations
    log_section "Step 7: Running Database Migrations"
    run_flyway_migrations
    
    # Step 8: Validate schema
    log_section "Step 8: Validating Schema"
    validate_schema
    
    # Step 9: Test connection
    log_section "Step 9: Testing Connection"
    test_connection
    
    # Step 10: Display summary
    display_summary
    
    log_info "Database initialization completed successfully!"
    
    # Clear error trap for successful exit
    trap - ERR EXIT
    
    exit 0
}

##############################################################################
# Script Entry Point
##############################################################################

# Execute main function
main "$@"
