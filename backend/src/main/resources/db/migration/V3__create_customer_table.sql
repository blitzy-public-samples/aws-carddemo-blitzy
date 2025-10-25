-- ============================================================================
-- Flyway Migration V3: Create Customer Table
-- ============================================================================
-- Source: COBOL copybook CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500)
-- Purpose: Transform VSAM CUSTFILE dataset into PostgreSQL relational table
-- Migration Type: VSAM KSDS → PostgreSQL table with B-tree indexes
--
-- COBOL-to-PostgreSQL Data Type Mappings:
-- ---------------------------------------
-- CUST-ID: PIC 9(09)                    → BIGINT (9-digit customer identifier, primary key)
-- CUST-FIRST-NAME: PIC X(25)            → VARCHAR(25) (first name, required)
-- CUST-MIDDLE-NAME: PIC X(25)           → VARCHAR(25) (middle name, nullable)
-- CUST-LAST-NAME: PIC X(25)             → VARCHAR(25) (last name, required)
-- CUST-ADDR-LINE-1: PIC X(50)           → VARCHAR(50) (address line 1, nullable)
-- CUST-ADDR-LINE-2: PIC X(50)           → VARCHAR(50) (address line 2, nullable)
-- CUST-ADDR-LINE-3: PIC X(50)           → VARCHAR(50) (address line 3, nullable)
-- CUST-ADDR-STATE-CD: PIC X(02)         → VARCHAR(2) (state code, nullable)
-- CUST-ADDR-COUNTRY-CD: PIC X(03)       → VARCHAR(3) (country code, nullable)
-- CUST-ADDR-ZIP: PIC X(10)              → VARCHAR(10) (postal code, nullable)
-- CUST-PHONE-NUM-1: PIC X(15)           → VARCHAR(15) (primary phone, nullable)
-- CUST-PHONE-NUM-2: PIC X(15)           → VARCHAR(15) (secondary phone, nullable)
-- CUST-SSN: PIC 9(09)                   → VARCHAR(9) (SSN - PII data, stored as string)
-- CUST-GOVT-ISSUED-ID: PIC X(20)        → VARCHAR(20) (government ID - PII data)
-- CUST-DOB-YYYY-MM-DD: PIC X(10)        → DATE (date of birth in YYYY-MM-DD format)
-- CUST-EFT-ACCOUNT-ID: PIC X(10)        → VARCHAR(10) (EFT account identifier)
-- CUST-PRI-CARD-HOLDER-IND: PIC X(01)   → VARCHAR(1) (primary card holder flag)
-- CUST-FICO-CREDIT-SCORE: PIC 9(03)     → INTEGER (FICO credit score 300-850)
-- FILLER: PIC X(168)                    → (not migrated - padding for 500-byte record)
--
-- Additional Columns (Cloud-Native Enhancements):
-- -----------------------------------------------
-- created_at: TIMESTAMP                 → Record creation timestamp (audit trail)
-- updated_at: TIMESTAMP                 → Record last update timestamp (audit trail)
-- version: INTEGER                      → Optimistic locking version for JPA (@Version)
--
-- Security Considerations (CRITICAL):
-- -----------------------------------
-- - CUST-SSN is Personally Identifiable Information (PII) - must be encrypted/masked in production
-- - CUST-GOVT-ISSUED-ID is PII data - must be protected with encryption
-- - CUST-DOB-YYYY-MM-DD is sensitive personal information - access control required
-- - Customer name fields are personal data - GDPR/CCPA compliance required
-- - Consider implementing column-level encryption for SSN and government ID fields
-- - Implement row-level security policies for multi-tenant deployments
--
-- Business Rules Preserved from COBOL:
-- -------------------------------------
-- 1. Customer ID is unique primary key (VSAM primary key preserved)
-- 2. First name and last name are mandatory fields (NOT NULL constraints)
-- 3. Middle name is optional (can be NULL)
-- 4. All address and contact fields are optional (COBOL logic allows blanks)
-- 5. SSN must be exactly 9 digits when present (application-level validation)
-- 6. FICO credit score is 3-digit numeric value (300-850 range)
-- 7. Primary card holder indicator is single character flag ('Y'/'N')
--
-- Index Strategy (Replicates VSAM Access Patterns):
-- -------------------------------------------------
-- - Primary key index: customer ID (VSAM primary key → PostgreSQL PRIMARY KEY)
-- - Alternate index 1: SSN (VSAM alternate index → PostgreSQL B-tree index)
-- - Alternate index 2: Customer name (VSAM alternate index → composite index)
-- - Supplemental index: State code (for geographic queries and reporting)
--
-- Performance Considerations:
-- --------------------------
-- - Expected record count: ~50,000 customers
-- - Primary key lookups must maintain sub-10ms response time (VSAM equivalence)
-- - SSN lookups for customer identification must be indexed
-- - Name-based searches for customer service operations must be optimized
-- - State-based queries for geographic reporting must be supported
--
-- Data Migration Notes:
-- ---------------------
-- - Source: VSAM CUSTFILE dataset (KSDS organization)
-- - Target: PostgreSQL table with B-tree indexes
-- - Character encoding: EBCDIC (VSAM) → UTF-8 (PostgreSQL)
-- - Date format: COBOL PIC X(10) YYYY-MM-DD → PostgreSQL DATE type
-- - Numeric SSN: COBOL PIC 9(09) → VARCHAR(9) to preserve leading zeros
-- - Record length: 500 bytes (COBOL) → variable (PostgreSQL, no FILLER needed)
--
-- Version History:
-- ----------------
-- V3: Initial creation - converts CVCUS01Y.cpy CUSTOMER-RECORD to PostgreSQL
-- Source COBOL version: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT
--
-- ============================================================================

-- Create customer master table
CREATE TABLE customer (
    -- Primary Key (VSAM primary key)
    cust_id BIGINT PRIMARY KEY,
    
    -- Customer Name Fields (required fields)
    cust_first_name VARCHAR(25) NOT NULL,
    cust_middle_name VARCHAR(25),
    cust_last_name VARCHAR(25) NOT NULL,
    
    -- Address Fields (all optional in COBOL logic)
    cust_addr_line_1 VARCHAR(50),
    cust_addr_line_2 VARCHAR(50),
    cust_addr_line_3 VARCHAR(50),
    cust_addr_state_cd VARCHAR(2),
    cust_addr_country_cd VARCHAR(3),
    cust_addr_zip VARCHAR(10),
    
    -- Contact Fields (optional)
    cust_phone_num_1 VARCHAR(15),
    cust_phone_num_2 VARCHAR(15),
    
    -- Identity and Security Fields (PII data - requires encryption in production)
    cust_ssn VARCHAR(9),
    cust_govt_issued_id VARCHAR(20),
    cust_dob_yyyy_mm_dd DATE,
    
    -- Financial and Account Fields
    cust_eft_account_id VARCHAR(10),
    cust_pri_card_holder_ind VARCHAR(1),
    cust_fico_credit_score INTEGER,
    
    -- Audit and Concurrency Control Fields (added for cloud-native operation)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0,
    
    -- Constraints
    CONSTRAINT chk_customer_fico_score CHECK (
        cust_fico_credit_score IS NULL OR 
        (cust_fico_credit_score >= 300 AND cust_fico_credit_score <= 850)
    ),
    CONSTRAINT chk_customer_pri_holder CHECK (
        cust_pri_card_holder_ind IS NULL OR 
        cust_pri_card_holder_ind IN ('Y', 'N')
    )
);

-- ============================================================================
-- Index Definitions (Replicate VSAM Access Patterns)
-- ============================================================================

-- Index 1: SSN lookup (VSAM alternate index equivalent)
-- Purpose: Customer identification and lookup by Social Security Number
-- Usage: Authentication, account linking, duplicate detection
-- Note: In production, consider hash index or encrypted index for PII data
CREATE INDEX idx_customer_ssn ON customer(cust_ssn);

-- Index 2: Customer name search (VSAM alternate index equivalent)
-- Purpose: Customer service lookup by name (last name, first name order)
-- Usage: Call center operations, customer search screens, reporting
CREATE INDEX idx_customer_name ON customer(cust_last_name, cust_first_name);

-- Index 3: State code for geographic queries
-- Purpose: Support geographic reporting and analysis
-- Usage: Regional reports, state-level aggregations, compliance reporting
CREATE INDEX idx_customer_state ON customer(cust_addr_state_cd);

-- Index 4: Date of birth for age-based queries
-- Purpose: Support age verification and demographic analysis
-- Usage: Credit decisioning, marketing campaigns, regulatory reporting
CREATE INDEX idx_customer_dob ON customer(cust_dob_yyyy_mm_dd);

-- Index 5: EFT account ID for payment processing
-- Purpose: Support EFT payment lookups and reconciliation
-- Usage: Payment processing, batch payment jobs, account linking
CREATE INDEX idx_customer_eft_account ON customer(cust_eft_account_id);

-- ============================================================================
-- Table and Column Documentation (PostgreSQL Comments)
-- ============================================================================

-- Table-level documentation
COMMENT ON TABLE customer IS 
'Customer master table converted from VSAM CUSTFILE dataset (CVCUS01Y.cpy). '
'Contains customer demographic, contact, and financial information. '
'Record length in COBOL: 500 bytes. Expected row count: ~50,000 customers. '
'Source: CardDemo_v1.0-15-g27d6c6f-68 Date: 2022-07-19 23:16:00 CDT';

-- Primary key documentation
COMMENT ON COLUMN customer.cust_id IS 
'Primary customer identifier (9-digit numeric). COBOL: PIC 9(09). '
'VSAM primary key. Unique identifier for each customer record.';

-- Name field documentation
COMMENT ON COLUMN customer.cust_first_name IS 
'Customer first name. COBOL: PIC X(25). Required field.';

COMMENT ON COLUMN customer.cust_middle_name IS 
'Customer middle name. COBOL: PIC X(25). Optional field.';

COMMENT ON COLUMN customer.cust_last_name IS 
'Customer last name. COBOL: PIC X(25). Required field.';

-- Address field documentation
COMMENT ON COLUMN customer.cust_addr_line_1 IS 
'Address line 1 (street address). COBOL: PIC X(50). Optional.';

COMMENT ON COLUMN customer.cust_addr_line_2 IS 
'Address line 2 (apartment, suite). COBOL: PIC X(50). Optional.';

COMMENT ON COLUMN customer.cust_addr_line_3 IS 
'Address line 3 (additional address info). COBOL: PIC X(50). Optional.';

COMMENT ON COLUMN customer.cust_addr_state_cd IS 
'State code (2-character US state abbreviation). COBOL: PIC X(02). Optional.';

COMMENT ON COLUMN customer.cust_addr_country_cd IS 
'Country code (3-character ISO country code). COBOL: PIC X(03). Optional.';

COMMENT ON COLUMN customer.cust_addr_zip IS 
'Postal/ZIP code. COBOL: PIC X(10). Supports ZIP+4 format. Optional.';

-- Contact field documentation
COMMENT ON COLUMN customer.cust_phone_num_1 IS 
'Primary phone number. COBOL: PIC X(15). Supports international format. Optional.';

COMMENT ON COLUMN customer.cust_phone_num_2 IS 
'Secondary phone number. COBOL: PIC X(15). Optional.';

-- PII field documentation (CRITICAL SECURITY WARNINGS)
COMMENT ON COLUMN customer.cust_ssn IS 
'Social Security Number (PII DATA - MUST BE ENCRYPTED IN PRODUCTION). '
'COBOL: PIC 9(09). Stored as VARCHAR(9) to preserve leading zeros. '
'VSAM alternate index. CRITICAL: Implement column-level encryption and access controls. '
'Compliance: GDPR, CCPA, PCI-DSS requirements apply.';

COMMENT ON COLUMN customer.cust_govt_issued_id IS 
'Government issued ID (PII DATA - MUST BE PROTECTED). '
'COBOL: PIC X(20). Examples: driver license, passport number. '
'CRITICAL: Implement encryption and access logging.';

COMMENT ON COLUMN customer.cust_dob_yyyy_mm_dd IS 
'Date of birth (sensitive personal information). '
'COBOL: PIC X(10) in YYYY-MM-DD format. Converted to PostgreSQL DATE type. '
'Used for age verification and credit decisioning.';

-- Financial field documentation
COMMENT ON COLUMN customer.cust_eft_account_id IS 
'EFT (Electronic Funds Transfer) account identifier. '
'COBOL: PIC X(10). Links customer to bank account for payments. Optional.';

COMMENT ON COLUMN customer.cust_pri_card_holder_ind IS 
'Primary card holder indicator flag. '
'COBOL: PIC X(01). Values: Y (primary), N (secondary). Optional.';

COMMENT ON COLUMN customer.cust_fico_credit_score IS 
'FICO credit score (300-850 range). '
'COBOL: PIC 9(03). Used for credit decisioning and risk assessment. Optional.';

-- Audit field documentation
COMMENT ON COLUMN customer.created_at IS 
'Record creation timestamp (audit trail). '
'Added for cloud-native operation. Default: CURRENT_TIMESTAMP. '
'Not present in original COBOL structure.';

COMMENT ON COLUMN customer.updated_at IS 
'Record last update timestamp (audit trail). '
'Added for cloud-native operation. Default: CURRENT_TIMESTAMP. '
'Not present in original COBOL structure. '
'Should be updated by application on each REWRITE operation.';

COMMENT ON COLUMN customer.version IS 
'Optimistic locking version for JPA (@Version annotation). '
'Added for Spring Data JPA concurrency control. Default: 0. '
'Incremented on each update to detect concurrent modifications. '
'Not present in original COBOL structure.';

-- ============================================================================
-- Data Migration Validation Queries (For Testing)
-- ============================================================================

-- Uncomment these queries after data migration to validate record counts and data quality:

-- Total record count validation
-- SELECT COUNT(*) AS total_customers FROM customer;

-- Check for customers with complete address information
-- SELECT COUNT(*) AS customers_with_address 
-- FROM customer 
-- WHERE cust_addr_line_1 IS NOT NULL;

-- Check for customers with SSN populated
-- SELECT COUNT(*) AS customers_with_ssn 
-- FROM customer 
-- WHERE cust_ssn IS NOT NULL;

-- Verify FICO score range compliance
-- SELECT MIN(cust_fico_credit_score) AS min_fico, 
--        MAX(cust_fico_credit_score) AS max_fico,
--        AVG(cust_fico_credit_score) AS avg_fico
-- FROM customer 
-- WHERE cust_fico_credit_score IS NOT NULL;

-- Check for duplicate SSN values (data quality issue)
-- SELECT cust_ssn, COUNT(*) AS duplicate_count
-- FROM customer
-- WHERE cust_ssn IS NOT NULL
-- GROUP BY cust_ssn
-- HAVING COUNT(*) > 1;

-- Verify primary key uniqueness (should return 0 duplicates)
-- SELECT cust_id, COUNT(*) AS duplicate_count
-- FROM customer
-- GROUP BY cust_id
-- HAVING COUNT(*) > 1;

-- ============================================================================
-- End of Migration V3: Customer Table Creation
-- ============================================================================
