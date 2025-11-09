-- ===================================================================================
-- Flyway Migration: V1__create_customer_table.sql
-- ===================================================================================
-- Description: Creates the customer table from COBOL copybook CVCUS01Y.cpy
-- Source: app/cpy/CVCUS01Y.cpy (RECLN 500 bytes - VSAM KSDS CUSTDAT file)
-- Purpose: Customer master data for CardDemo credit card management application
-- Migration: Mainframe COBOL/VSAM to PostgreSQL database
-- ===================================================================================

-- Drop table if exists (for clean migration in development)
DROP TABLE IF EXISTS customer CASCADE;

-- Create customer table with exact field mappings from COBOL copybook
CREATE TABLE customer (
    -- Primary Key: CUST-ID PIC 9(09) - 9-digit customer identifier
    customer_id BIGINT PRIMARY KEY,
    
    -- Customer Name Fields: PIC X(25) - Customer full name components
    first_name VARCHAR(25) NOT NULL,
    middle_name VARCHAR(25),
    last_name VARCHAR(25) NOT NULL,
    
    -- Address Fields: PIC X(50) - Multi-line address with state, country, postal code
    address_line_1 VARCHAR(50),
    address_line_2 VARCHAR(50),
    address_line_3 VARCHAR(50),
    state_code CHAR(2),
    country_code CHAR(3),
    postal_code VARCHAR(10),
    
    -- Contact Information: PIC X(15) - Primary and secondary phone numbers
    phone_number_1 VARCHAR(15),
    phone_number_2 VARCHAR(15),
    
    -- Personal Identification: PIC 9(09) and PIC X(20) - SSN and government ID
    -- Note: SSN requires PII protection and masking in application layer
    ssn BIGINT,
    government_issued_id VARCHAR(20),
    
    -- Personal Information: PIC X(10) - Date of birth in YYYY-MM-DD format
    -- Converted from COBOL string format to PostgreSQL DATE type
    date_of_birth DATE,
    
    -- Financial Information: PIC X(10) - Electronic funds transfer account ID
    eft_account_id VARCHAR(10),
    
    -- Cardholder Status: PIC X(01) - Primary cardholder indicator (Y/N)
    primary_cardholder_ind CHAR(1),
    
    -- Credit Information: PIC 9(03) - FICO credit score (300-850 range)
    fico_credit_score SMALLINT,
    
    -- Audit Trail Fields: Track record creation and modification timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT chk_primary_cardholder_ind CHECK (primary_cardholder_ind IN ('Y', 'N')),
    CONSTRAINT chk_fico_credit_score CHECK (fico_credit_score IS NULL OR (fico_credit_score >= 300 AND fico_credit_score <= 850)),
    CONSTRAINT chk_state_code_length CHECK (state_code IS NULL OR LENGTH(TRIM(state_code)) = 2),
    CONSTRAINT chk_country_code_length CHECK (country_code IS NULL OR LENGTH(TRIM(country_code)) = 3)
);

-- Create indexes for common query patterns (matching VSAM key access patterns)
CREATE INDEX idx_customer_last_name ON customer(last_name);
CREATE INDEX idx_customer_ssn ON customer(ssn) WHERE ssn IS NOT NULL;
CREATE INDEX idx_customer_dob ON customer(date_of_birth);
CREATE INDEX idx_customer_fico_score ON customer(fico_credit_score) WHERE fico_credit_score IS NOT NULL;

-- Add table comment documenting COBOL source for traceability
COMMENT ON TABLE customer IS 'Customer master data table migrated from COBOL copybook CVCUS01Y.cpy (RECLN 500 bytes). Source: VSAM KSDS CUSTDAT file from CardDemo mainframe application. Contains customer personal information, contact details, and credit data.';

-- Add column comments for documentation and maintenance
COMMENT ON COLUMN customer.customer_id IS 'Primary key: 9-digit customer identifier from CUST-ID PIC 9(09)';
COMMENT ON COLUMN customer.first_name IS 'Customer first name from CUST-FIRST-NAME PIC X(25)';
COMMENT ON COLUMN customer.middle_name IS 'Customer middle name from CUST-MIDDLE-NAME PIC X(25)';
COMMENT ON COLUMN customer.last_name IS 'Customer last name from CUST-LAST-NAME PIC X(25)';
COMMENT ON COLUMN customer.address_line_1 IS 'Address line 1 from CUST-ADDR-LINE-1 PIC X(50)';
COMMENT ON COLUMN customer.address_line_2 IS 'Address line 2 from CUST-ADDR-LINE-2 PIC X(50)';
COMMENT ON COLUMN customer.address_line_3 IS 'Address line 3 from CUST-ADDR-LINE-3 PIC X(50)';
COMMENT ON COLUMN customer.state_code IS 'US state code (2 characters) from CUST-ADDR-STATE-CD PIC X(02)';
COMMENT ON COLUMN customer.country_code IS 'Country code (3 characters) from CUST-ADDR-COUNTRY-CD PIC X(03)';
COMMENT ON COLUMN customer.postal_code IS 'Postal/ZIP code from CUST-ADDR-ZIP PIC X(10)';
COMMENT ON COLUMN customer.phone_number_1 IS 'Primary phone number from CUST-PHONE-NUM-1 PIC X(15)';
COMMENT ON COLUMN customer.phone_number_2 IS 'Secondary phone number from CUST-PHONE-NUM-2 PIC X(15)';
COMMENT ON COLUMN customer.ssn IS 'Social Security Number (9 digits) from CUST-SSN PIC 9(09). PII - requires masking in application layer';
COMMENT ON COLUMN customer.government_issued_id IS 'Government-issued ID from CUST-GOVT-ISSUED-ID PIC X(20)';
COMMENT ON COLUMN customer.date_of_birth IS 'Date of birth from CUST-DOB-YYYY-MM-DD PIC X(10). Converted from COBOL YYYY-MM-DD string format';
COMMENT ON COLUMN customer.eft_account_id IS 'Electronic funds transfer account ID from CUST-EFT-ACCOUNT-ID PIC X(10)';
COMMENT ON COLUMN customer.primary_cardholder_ind IS 'Primary cardholder indicator (Y/N) from CUST-PRI-CARD-HOLDER-IND PIC X(01)';
COMMENT ON COLUMN customer.fico_credit_score IS 'FICO credit score (300-850) from CUST-FICO-CREDIT-SCORE PIC 9(03)';
COMMENT ON COLUMN customer.created_at IS 'Record creation timestamp for audit trail';
COMMENT ON COLUMN customer.updated_at IS 'Record last update timestamp for audit trail';

-- ===================================================================================
-- Migration Notes:
-- ===================================================================================
-- 1. VSAM KSDS primary key (CUST-ID) maps to customer_id BIGINT PRIMARY KEY
-- 2. All PIC X(n) alphanumeric fields map to VARCHAR(n) or CHAR(n) for fixed length
-- 3. All PIC 9(n) numeric fields map to BIGINT or SMALLINT based on size
-- 4. COBOL date field CUST-DOB-YYYY-MM-DD PIC X(10) converts to PostgreSQL DATE
-- 5. FILLER PIC X(168) padding bytes are not migrated (maintained 500-byte alignment)
-- 6. Audit fields (created_at, updated_at) added for change tracking
-- 7. Check constraints enforce data integrity for indicator fields and credit scores
-- 8. Indexes created to match VSAM access patterns for query performance
-- 9. SSN field contains PII and must be masked/encrypted at application layer
-- 10. Total COBOL record length: 500 bytes (including 168 bytes FILLER)
-- ===================================================================================
