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
# CardDemo Test Data Loading Script
# 
# Purpose: Load test data from ASCII flat files into PostgreSQL database
# Replaces: Mainframe VSAM REPRO and data loading JCL scripts
#           (CUSTFILE.jcl, ACCTFILE.jcl, CARDFILE.jcl, TRANFILE.jcl, 
#            LOADDATA.jcl)
#
# This script:
# 1. Validates prerequisites (database and schema exist)
# 2. Optionally clears existing data (CLEAR_EXISTING flag)
# 3. Loads data from app/data/ASCII/ directory using PostgreSQL COPY
# 4. Validates data integrity and referential constraints
# 5. Provides detailed progress reporting and statistics
#
# Data Files Loaded:
# - custdata.txt    → customer table (50 records)
# - acctdata.txt    → account table (50 records)
# - carddata.txt    → card table (50 records)
# - dailytran.txt   → transaction table (478 records)
# - cardxref.txt    → card_xref table (cross-references)
# - tcatbal.txt     → transaction_category_balance table
#
# Note: Reference data (transaction_type, transaction_category, discount_group)
#       is loaded by V9__load_reference_data.sql migration and should not be
#       reloaded by this script.
#
# Usage: ./load-test-data.sh
#        CLEAR_EXISTING=true ./load-test-data.sh  (clear before loading)
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

# CardDemo Database Configuration
readonly CARDDEMO_DB="${CARDDEMO_DB:-carddemo}"
readonly CARDDEMO_USER="${CARDDEMO_USER:-carddemo}"
readonly CARDDEMO_PASSWORD="${CARDDEMO_PASSWORD:-carddemo123}"

# Control Flags
readonly CLEAR_EXISTING="${CLEAR_EXISTING:-false}"
readonly SKIP_VALIDATION="${SKIP_VALIDATION:-false}"

# Script Directory and Paths
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly DATA_DIR="${DATA_DIR:-${PROJECT_ROOT}/app/data/ASCII}"
readonly LOG_FILE="${SCRIPT_DIR}/load-test-data.log"

# Expected Record Counts (for validation)
readonly EXPECTED_CUSTOMER_COUNT=50
readonly EXPECTED_ACCOUNT_COUNT=50
readonly EXPECTED_CARD_COUNT=50
readonly EXPECTED_TRANSACTION_COUNT=478
readonly EXPECTED_CARDXREF_COUNT=50
readonly EXPECTED_TCATBAL_COUNT=50

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
        log_error "Test data loading failed with exit code: $exit_code"
        log_error "Check log file for details: ${LOG_FILE}"
        log_error "Database may contain partial data - consider running with CLEAR_EXISTING=true"
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

check_database_connection() {
    log_info "Checking database connectivity..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    if ! psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "SELECT 1" &> /dev/null; then
        log_error "Cannot connect to database ${CARDDEMO_DB} at ${POSTGRES_HOST}:${POSTGRES_PORT}"
        log_error "Please ensure:"
        log_error "  1. PostgreSQL is running"
        log_error "  2. Database '${CARDDEMO_DB}' exists"
        log_error "  3. User '${CARDDEMO_USER}' has access"
        log_error "  4. Run ./scripts/init-db.sh first if not done"
        exit 1
    fi
    
    log_info "Database connection successful"
}

check_schema_exists() {
    log_info "Validating database schema..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Check if required tables exist
    local required_tables=("customer" "account" "card" "transaction" "user_security" "transaction_type" "transaction_category")
    local missing_tables=()
    
    for table in "${required_tables[@]}"; do
        local table_exists
        table_exists=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';" | tr -d ' ')
        
        if [ "$table_exists" = "0" ]; then
            missing_tables+=("$table")
        fi
    done
    
    if [ ${#missing_tables[@]} -gt 0 ]; then
        log_error "Required tables are missing: ${missing_tables[*]}"
        log_error "Please run ./scripts/init-db.sh to create the database schema"
        exit 1
    fi
    
    log_info "All required tables exist"
}

check_data_files_exist() {
    log_info "Checking data files..."
    
    if [ ! -d "${DATA_DIR}" ]; then
        log_error "Data directory not found: ${DATA_DIR}"
        exit 1
    fi
    
    local required_files=("custdata.txt" "acctdata.txt" "carddata.txt" "dailytran.txt")
    local missing_files=()
    
    for file in "${required_files[@]}"; do
        if [ ! -f "${DATA_DIR}/${file}" ]; then
            missing_files+=("$file")
        fi
    done
    
    if [ ${#missing_files[@]} -gt 0 ]; then
        log_error "Required data files are missing in ${DATA_DIR}:"
        for file in "${missing_files[@]}"; do
            log_error "  - $file"
        done
        exit 1
    fi
    
    log_info "All required data files found"
}

##############################################################################
# Data Preparation Functions
##############################################################################

clear_existing_data() {
    if [ "${CLEAR_EXISTING}" = "true" ]; then
        log_warn "CLEAR_EXISTING flag is set - clearing existing test data..."
        
        export PGPASSWORD="${CARDDEMO_PASSWORD}"
        
        # Truncate tables in correct order (respecting foreign key constraints)
        psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" <<EOF 2>> "${LOG_FILE}"
-- Truncate transactional tables first (leaf nodes in FK tree)
TRUNCATE TABLE transaction CASCADE;

-- Then tables that reference accounts
TRUNCATE TABLE card CASCADE;

-- Then accounts (references customers)
TRUNCATE TABLE account CASCADE;

-- Finally customers (root of FK tree)
TRUNCATE TABLE customer CASCADE;

-- Clear cross-reference tables if they exist
TRUNCATE TABLE card_xref CASCADE;
TRUNCATE TABLE transaction_category_balance CASCADE;

-- Note: Do NOT truncate reference tables (transaction_type, transaction_category, discount_group)
-- as they contain static reference data from V9 migration
EOF
        
        log_warn "Existing test data cleared successfully"
    else
        log_info "Skipping data clear (CLEAR_EXISTING not set)"
    fi
}

##############################################################################
# Data Loading Functions
##############################################################################

load_data_from_sql() {
    log_info "Using SQL file for data loading (text files are in fixed-width format)..."
    
    # Try corrected file first, then fall back to original test-data.sql
    local sql_file="${DATA_DIR}/test-data-corrected.sql"
    if [ ! -f "$sql_file" ]; then
        sql_file="${DATA_DIR}/test-data.sql"
        log_warn "test-data-corrected.sql not found, trying test-data.sql"
    fi
    
    if [ ! -f "$sql_file" ]; then
        log_error "No SQL data file found"
        log_error "Fixed-width format parsing not yet implemented"
        log_error "Please create test-data-corrected.sql with schema-aligned column names"
        exit 1
    fi
    
    log_info "Using SQL file: $(basename $sql_file)"
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Execute the SQL file
    if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -f "$sql_file" >> "${LOG_FILE}" 2>&1; then
        log_info "${GREEN}✓${NC} Successfully loaded data from SQL file"
        return 0
    else
        log_error "Failed to load data from SQL file"
        exit 1
    fi
}

load_customer_data() {
    log_info "Loading customer data..."
    
    local customer_file="${DATA_DIR}/custdata.txt"
    
    if [ ! -f "$customer_file" ]; then
        log_error "Customer data file not found: $customer_file"
        exit 1
    fi
    
    # Count records to load
    local file_count
    file_count=$(wc -l < "$customer_file" | tr -d ' ')
    log_info "Records in file: $file_count"
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Use psql's \copy command to load data from client-side file
    # \copy must be executed as a single command, so we use psql -c instead of -f
    local copy_cmd="\\copy customer (customer_id, first_name, middle_name, last_name, address_line1, address_line2, address_line3, state_code, country_code, zip_code, phone_number1, phone_number2, ssn, credit_score, fico_score, birth_date, government_id, government_id_type) FROM '${customer_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
    
    # Execute \copy command
    if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
        local loaded_count
        loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM customer;" | tr -d ' ')
        log_info "${GREEN}✓${NC} Loaded $loaded_count customer records"
        return 0
    else
        log_error "Failed to load customer data"
        exit 1
    fi
}

load_account_data() {
    log_info "Loading account data..."
    
    local account_file="${DATA_DIR}/acctdata.txt"
    
    if [ ! -f "$account_file" ]; then
        log_error "Account data file not found: $account_file"
        exit 1
    fi
    
    local file_count
    file_count=$(wc -l < "$account_file" | tr -d ' ')
    log_info "Records in file: $file_count"
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Use psql's \copy command to load data from client-side file
    local copy_cmd="\\copy account (account_id, customer_id, account_status, credit_limit, current_balance, cash_credit_limit, account_open_date, account_expiration_date, account_reissue_date, current_cycle_credit, current_cycle_debit, account_group_id) FROM '${account_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
    
    if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
        local loaded_count
        loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM account;" | tr -d ' ')
        log_info "${GREEN}✓${NC} Loaded $loaded_count account records"
        return 0
    else
        log_error "Failed to load account data"
        exit 1
    fi
}

load_card_data() {
    log_info "Loading card data..."
    
    local card_file="${DATA_DIR}/carddata.txt"
    
    if [ ! -f "$card_file" ]; then
        log_error "Card data file not found: $card_file"
        exit 1
    fi
    
    local file_count
    file_count=$(wc -l < "$card_file" | tr -d ' ')
    log_info "Records in file: $file_count"
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Use psql's \copy command to load data from client-side file
    local copy_cmd="\\copy card (card_number, account_id, cvv_code, embossed_name, card_expiration_date, card_status) FROM '${card_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
    
    if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
        local loaded_count
        loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM card;" | tr -d ' ')
        log_info "${GREEN}✓${NC} Loaded $loaded_count card records"
        return 0
    else
        log_error "Failed to load card data"
        exit 1
    fi
}

load_transaction_data() {
    log_info "Loading transaction data..."
    
    local transaction_file="${DATA_DIR}/dailytran.txt"
    
    if [ ! -f "$transaction_file" ]; then
        log_warn "Transaction data file not found: $transaction_file"
        log_warn "Skipping transaction data load"
        return 0
    fi
    
    local file_count
    file_count=$(wc -l < "$transaction_file" | tr -d ' ')
    log_info "Records in file: $file_count"
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Use psql's \copy command to load data from client-side file
    local copy_cmd="\\copy transaction (transaction_id, transaction_type_code, transaction_category_code, transaction_source, transaction_description, transaction_amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_number, transaction_timestamp, authorization_code) FROM '${transaction_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
    
    if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
        local loaded_count
        loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM transaction;" | tr -d ' ')
        log_info "${GREEN}✓${NC} Loaded $loaded_count transaction records"
        return 0
    else
        log_error "Failed to load transaction data"
        exit 1
    fi
}

load_cross_reference_data() {
    log_info "Loading cross-reference data..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Load card_xref if file exists
    local cardxref_file="${DATA_DIR}/cardxref.txt"
    if [ -f "$cardxref_file" ]; then
        log_info "Loading card cross-reference data..."
        
        local file_count
        file_count=$(wc -l < "$cardxref_file" | tr -d ' ')
        log_info "Records in file: $file_count"
        
        # Check if table exists first
        local table_exists
        table_exists=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='card_xref';" | tr -d ' ')
        
        if [ "$table_exists" = "1" ]; then
            # Use psql's \copy command to load data from client-side file
            local copy_cmd="\\copy card_xref FROM '${cardxref_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
            
            if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
                local loaded_count
                loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM card_xref;" | tr -d ' ')
                log_info "${GREEN}✓${NC} Loaded $loaded_count card_xref records"
            else
                log_warn "Failed to load card_xref data (table may have schema issues)"
            fi
        else
            log_warn "card_xref table does not exist - skipping"
        fi
    else
        log_info "Card cross-reference file not found - skipping"
    fi
    
    # Load transaction_category_balance if file exists
    local tcatbal_file="${DATA_DIR}/tcatbal.txt"
    if [ -f "$tcatbal_file" ]; then
        log_info "Loading transaction category balance data..."
        
        local file_count
        file_count=$(wc -l < "$tcatbal_file" | tr -d ' ')
        log_info "Records in file: $file_count"
        
        # Check if table exists first
        local table_exists
        table_exists=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='transaction_category_balance';" | tr -d ' ')
        
        if [ "$table_exists" = "1" ]; then
            # Use psql's \copy command to load data from client-side file
            local copy_cmd="\\copy transaction_category_balance FROM '${tcatbal_file}' WITH (FORMAT text, DELIMITER E'\\t', NULL '')"
            
            if psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -c "$copy_cmd" >> "${LOG_FILE}" 2>&1; then
                local loaded_count
                loaded_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c "SELECT COUNT(*) FROM transaction_category_balance;" | tr -d ' ')
                log_info "${GREEN}✓${NC} Loaded $loaded_count transaction_category_balance records"
            else
                log_warn "Failed to load transaction_category_balance data (table may have schema issues)"
            fi
        else
            log_warn "transaction_category_balance table does not exist - skipping"
        fi
    else
        log_info "Transaction category balance file not found - skipping"
    fi
}

##############################################################################
# Data Validation Functions
##############################################################################

validate_referential_integrity() {
    if [ "${SKIP_VALIDATION}" = "true" ]; then
        log_warn "Skipping referential integrity validation (SKIP_VALIDATION flag set)"
        return 0
    fi
    
    log_info "Validating referential integrity..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    local validation_failed=0
    
    # Check for orphaned accounts (accounts without valid customers)
    local orphaned_accounts
    orphaned_accounts=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM account a LEFT JOIN customer c ON a.customer_id = c.customer_id WHERE c.customer_id IS NULL;" | tr -d ' ')
    
    if [ "$orphaned_accounts" != "0" ]; then
        log_error "Found $orphaned_accounts orphaned accounts (accounts without valid customers)"
        validation_failed=1
    else
        log_info "${GREEN}✓${NC} All accounts have valid customer references"
    fi
    
    # Check for orphaned cards (cards without valid accounts)
    local orphaned_cards
    orphaned_cards=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM card c LEFT JOIN account a ON c.account_id = a.account_id WHERE a.account_id IS NULL;" | tr -d ' ')
    
    if [ "$orphaned_cards" != "0" ]; then
        log_error "Found $orphaned_cards orphaned cards (cards without valid accounts)"
        validation_failed=1
    else
        log_info "${GREEN}✓${NC} All cards have valid account references"
    fi
    
    # Check for orphaned transactions (transactions without valid cards)
    local transaction_count
    transaction_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM transaction;" | tr -d ' ')
    
    if [ "$transaction_count" != "0" ]; then
        local orphaned_transactions
        orphaned_transactions=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
            "SELECT COUNT(*) FROM transaction t LEFT JOIN card c ON t.card_number = c.card_number WHERE c.card_number IS NULL;" | tr -d ' ')
        
        if [ "$orphaned_transactions" != "0" ]; then
            log_error "Found $orphaned_transactions orphaned transactions (transactions without valid cards)"
            validation_failed=1
        else
            log_info "${GREEN}✓${NC} All transactions have valid card references"
        fi
    fi
    
    if [ $validation_failed -eq 1 ]; then
        log_error "Referential integrity validation failed"
        exit 1
    fi
    
    log_info "Referential integrity validation passed"
}

validate_record_counts() {
    if [ "${SKIP_VALIDATION}" = "true" ]; then
        log_warn "Skipping record count validation (SKIP_VALIDATION flag set)"
        return 0
    fi
    
    log_info "Validating record counts..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Note: Record counts are informational only, not strict validation
    # Actual counts may vary from expected counts
    
    local customer_count
    customer_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM customer;" | tr -d ' ')
    log_info "Customer records: $customer_count"
    
    local account_count
    account_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM account;" | tr -d ' ')
    log_info "Account records: $account_count"
    
    local card_count
    card_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM card;" | tr -d ' ')
    log_info "Card records: $card_count"
    
    local transaction_count
    transaction_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM transaction;" | tr -d ' ')
    log_info "Transaction records: $transaction_count"
}

validate_data_quality() {
    if [ "${SKIP_VALIDATION}" = "true" ]; then
        log_warn "Skipping data quality validation (SKIP_VALIDATION flag set)"
        return 0
    fi
    
    log_info "Validating data quality..."
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    # Check for NULL values in critical NOT NULL fields
    local null_customer_ids
    null_customer_ids=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM customer WHERE customer_id IS NULL OR first_name IS NULL OR last_name IS NULL;" | tr -d ' ')
    
    if [ "$null_customer_ids" != "0" ]; then
        log_error "Found $null_customer_ids customer records with NULL in required fields"
        exit 1
    else
        log_info "${GREEN}✓${NC} All customer required fields are populated"
    fi
    
    # Verify account balances are numeric
    local invalid_balances
    invalid_balances=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM account WHERE current_balance IS NULL OR credit_limit IS NULL;" | tr -d ' ')
    
    if [ "$invalid_balances" != "0" ]; then
        log_error "Found $invalid_balances account records with NULL balances"
        exit 1
    else
        log_info "${GREEN}✓${NC} All account balance fields are valid"
    fi
    
    # Verify card expiration dates are in future or reasonable past
    local expired_cards_count
    expired_cards_count=$(psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" -t -c \
        "SELECT COUNT(*) FROM card WHERE card_expiration_date < CURRENT_DATE - INTERVAL '10 years';" | tr -d ' ')
    
    if [ "$expired_cards_count" != "0" ]; then
        log_warn "Found $expired_cards_count cards with expiration dates more than 10 years in the past"
    fi
    
    log_info "Data quality validation passed"
}

##############################################################################
# Statistics and Reporting Functions
##############################################################################

display_statistics() {
    log_section "Data Loading Statistics"
    
    export PGPASSWORD="${CARDDEMO_PASSWORD}"
    
    echo ""
    echo -e "${GREEN}Database: ${CARDDEMO_DB}${NC}"
    echo -e "${GREEN}Host: ${POSTGRES_HOST}:${POSTGRES_PORT}${NC}"
    echo ""
    
    # Generate comprehensive statistics
    psql -h "${POSTGRES_HOST}" -p "${POSTGRES_PORT}" -U "${CARDDEMO_USER}" -d "${CARDDEMO_DB}" <<EOF | tee -a "${LOG_FILE}"
-- Table Record Counts
SELECT 
    'TABLE RECORD COUNTS' as section,
    '' as detail
UNION ALL
SELECT 
    '  customer', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM customer
UNION ALL
SELECT 
    '  account', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM account
UNION ALL
SELECT 
    '  card', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM card
UNION ALL
SELECT 
    '  transaction', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM transaction
UNION ALL
SELECT 
    '  transaction_type', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM transaction_type
UNION ALL
SELECT 
    '  transaction_category', 
    LPAD(COUNT(*)::text, 10, ' ')
FROM transaction_category;

-- Account Status Distribution
SELECT 
    '' as empty_line,
    '' as detail
UNION ALL
SELECT 
    'ACCOUNT STATUS DISTRIBUTION' as section,
    '' as detail
UNION ALL
SELECT 
    '  ' || COALESCE(account_status, 'NULL'),
    LPAD(COUNT(*)::text, 10, ' ')
FROM account
GROUP BY account_status;

-- Card Status Distribution
SELECT 
    '' as empty_line,
    '' as detail
UNION ALL
SELECT 
    'CARD STATUS DISTRIBUTION' as section,
    '' as detail
UNION ALL
SELECT 
    '  ' || COALESCE(card_status, 'NULL'),
    LPAD(COUNT(*)::text, 10, ' ')
FROM card
GROUP BY card_status;
EOF
}

display_summary() {
    log_section "Test Data Loading Completed Successfully"
    
    echo ""
    echo -e "${GREEN}✓ All test data loaded successfully${NC}"
    echo ""
    echo -e "${GREEN}Data Source:${NC} ${DATA_DIR}"
    echo -e "${GREEN}Database:${NC} ${CARDDEMO_DB}"
    echo -e "${GREEN}Host:${NC} ${POSTGRES_HOST}:${POSTGRES_PORT}"
    echo ""
    echo -e "${GREEN}Next Steps:${NC}"
    echo "  1. Start the backend application:"
    echo "     cd backend && mvn spring-boot:run"
    echo ""
    echo "  2. Start the frontend application:"
    echo "     cd frontend && npm run dev"
    echo ""
    echo "  3. Access the application:"
    echo "     http://localhost:3000"
    echo ""
    echo -e "${GREEN}Log File:${NC} ${LOG_FILE}"
    echo ""
}

##############################################################################
# Main Execution Flow
##############################################################################

main() {
    # Initialize log file
    echo "CardDemo Test Data Loading - $(date)" > "${LOG_FILE}"
    
    log_section "CardDemo Test Data Loading"
    
    log_info "Starting test data loading process..."
    log_info "Data directory: ${DATA_DIR}"
    log_info "Database: ${CARDDEMO_DB}"
    log_info "Log file: ${LOG_FILE}"
    
    # Step 1: Validate prerequisites
    log_section "Step 1: Validating Prerequisites"
    check_command_exists "psql"
    check_command_exists "wc"
    check_command_exists "grep"
    check_database_connection
    check_schema_exists
    check_data_files_exist
    
    # Step 2: Clear existing data if requested
    log_section "Step 2: Data Preparation"
    clear_existing_data
    
    # Step 3: Check if test-data.sql exists and use it (handles fixed-width format)
    log_section "Step 3: Data Loading"
    if [ -f "${DATA_DIR}/test-data.sql" ]; then
        log_info "Found test-data.sql - using SQL-based data loading"
        load_data_from_sql
    else
        log_info "Using text file-based data loading"
        
        # Step 3a: Load customer data (root of FK tree)
        log_section "Step 3a: Loading Customer Data"
        load_customer_data
        
        # Step 3b: Load account data (depends on customer)
        log_section "Step 3b: Loading Account Data"
        load_account_data
        
        # Step 3c: Load card data (depends on account)
        log_section "Step 3c: Loading Card Data"
        load_card_data
        
        # Step 3d: Load transaction data (depends on card)
        log_section "Step 3d: Loading Transaction Data"
        load_transaction_data
        
        # Step 3e: Load cross-reference data
        log_section "Step 3e: Loading Cross-Reference Data"
        load_cross_reference_data
    fi
    
    # Step 4: Validate referential integrity
    log_section "Step 4: Validating Referential Integrity"
    validate_referential_integrity
    
    # Step 5: Validate record counts
    log_section "Step 5: Validating Record Counts"
    validate_record_counts
    
    # Step 6: Validate data quality
    log_section "Step 6: Validating Data Quality"
    validate_data_quality
    
    # Step 7: Display statistics
    display_statistics
    
    # Step 8: Display summary
    display_summary
    
    log_info "Test data loading completed successfully!"
    
    # Clear error trap for successful exit
    trap - ERR EXIT
    
    exit 0
}

##############################################################################
# Script Entry Point
##############################################################################

# Execute main function
main "$@"

