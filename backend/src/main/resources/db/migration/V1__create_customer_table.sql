-- ============================================================================
-- Flyway Migration V1: Create Customer Table
-- ============================================================================
-- Description: Creates the customer table from CUSTDAT VSAM KSDS file
-- Source: CVCUS01Y.cpy copybook (RECLN 500)
-- VSAM Specs: KEYLEN=9, MAXLRECL=500, REC-TOTAL=50
-- Migration: COBOL to PostgreSQL transformation
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Create customer table
-- ----------------------------------------------------------------------------
CREATE TABLE customer (
    -- Primary Key: CUST-ID PIC 9(09) - VSAM primary key (KEYLEN=9)
    -- Migration Note: Changed from VARCHAR(9) to BIGINT to match Long type in Customer.java entity
    customer_id BIGINT PRIMARY KEY,
    
    -- Customer Name Fields: CUST-FIRST-NAME PIC X(25)
    first_name VARCHAR(25) NOT NULL,
    
    -- Customer Name Fields: CUST-MIDDLE-NAME PIC X(25)
    middle_name VARCHAR(25),
    
    -- Customer Name Fields: CUST-LAST-NAME PIC X(25)
    last_name VARCHAR(25) NOT NULL,
    
    -- Address Fields: CUST-ADDR-LINE-1 PIC X(50)
    address_line_1 VARCHAR(50),
    
    -- Address Fields: CUST-ADDR-LINE-2 PIC X(50)
    address_line_2 VARCHAR(50),
    
    -- Address Fields: CUST-ADDR-LINE-3 PIC X(50)
    address_line_3 VARCHAR(50),
    
    -- Address Fields: CUST-ADDR-STATE-CD PIC X(02)
    state_code VARCHAR(2),
    
    -- Address Fields: CUST-ADDR-COUNTRY-CD PIC X(03)
    country_code VARCHAR(3),
    
    -- Address Fields: CUST-ADDR-ZIP PIC X(10)
    zip_code VARCHAR(10),
    
    -- Contact Fields: CUST-PHONE-NUM-1 PIC X(15)
    phone_number_1 VARCHAR(15),
    
    -- Contact Fields: CUST-PHONE-NUM-2 PIC X(15)
    phone_number_2 VARCHAR(15),
    
    -- Identification Fields: CUST-SSN PIC 9(09)
    -- Stored as VARCHAR to preserve leading zeros
    ssn VARCHAR(9),
    
    -- Identification Fields: CUST-GOVT-ISSUED-ID PIC X(20)
    government_issued_id VARCHAR(20),
    
    -- Date Fields: CUST-DOB-YYYY-MM-DD PIC X(10)
    -- Converted from COBOL string format (YYYY-MM-DD) to PostgreSQL DATE type
    date_of_birth DATE,
    
    -- Financial Fields: CUST-EFT-ACCOUNT-ID PIC X(10)
    eft_account_id VARCHAR(10),
    
    -- Indicator Fields: CUST-PRI-CARD-HOLDER-IND PIC X(01)
    primary_card_holder_indicator VARCHAR(1),
    
    -- Credit Score Fields: CUST-FICO-CREDIT-SCORE PIC 9(03)
    -- Valid FICO scores range from 300 to 850
    fico_credit_score INTEGER,
    
    -- Audit Fields: Added for modern database management (not in COBOL)
    -- Timestamp for record creation
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Timestamp for last record update
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints: FICO credit score validation
    CONSTRAINT chk_customer_fico_score 
        CHECK (fico_credit_score IS NULL OR 
               (fico_credit_score >= 300 AND fico_credit_score <= 850)),
    
    -- Constraints: Customer ID must be exactly 9 digits (100000000 to 999999999)
    -- Migration Note: Changed from regex check to numeric range check for BIGINT type
    CONSTRAINT chk_customer_id_format 
        CHECK (customer_id >= 100000000 AND customer_id <= 999999999),
    
    -- Constraints: SSN must be exactly 9 digits when provided
    CONSTRAINT chk_customer_ssn_format 
        CHECK (ssn IS NULL OR ssn ~ '^[0-9]{9}$'),
    
    -- Constraints: State code must be 2 uppercase letters when provided
    CONSTRAINT chk_customer_state_code 
        CHECK (state_code IS NULL OR state_code ~ '^[A-Z]{2}$'),
    
    -- Constraints: Primary cardholder indicator must be single character
    CONSTRAINT chk_customer_cardholder_ind 
        CHECK (primary_card_holder_indicator IS NULL OR 
               LENGTH(primary_card_holder_indicator) = 1)
);

-- ----------------------------------------------------------------------------
-- Table and Column Comments for Documentation
-- ----------------------------------------------------------------------------
COMMENT ON TABLE customer IS 'Customer master records migrated from CUSTDAT VSAM KSDS file. Source: CVCUS01Y.cpy copybook (RECLN 500). VSAM specifications: KEYLEN=9, MAXLRECL=500, REC-TOTAL=50. Contains customer demographic, contact, and financial information.';

COMMENT ON COLUMN customer.customer_id IS 'Unique customer identifier (9 digits). Source: CUST-ID PIC 9(09). VSAM primary key field (KEYLEN=9).';

COMMENT ON COLUMN customer.first_name IS 'Customer first name (up to 25 characters). Source: CUST-FIRST-NAME PIC X(25). Required field for all customer records.';

COMMENT ON COLUMN customer.middle_name IS 'Customer middle name (up to 25 characters). Source: CUST-MIDDLE-NAME PIC X(25). Optional field, may be NULL.';

COMMENT ON COLUMN customer.last_name IS 'Customer last name (up to 25 characters). Source: CUST-LAST-NAME PIC X(25). Required field for all customer records.';

COMMENT ON COLUMN customer.address_line_1 IS 'First line of customer address (up to 50 characters). Source: CUST-ADDR-LINE-1 PIC X(50).';

COMMENT ON COLUMN customer.address_line_2 IS 'Second line of customer address (up to 50 characters). Source: CUST-ADDR-LINE-2 PIC X(50).';

COMMENT ON COLUMN customer.address_line_3 IS 'Third line of customer address (up to 50 characters). Source: CUST-ADDR-LINE-3 PIC X(50).';

COMMENT ON COLUMN customer.state_code IS 'Two-letter state code (uppercase). Source: CUST-ADDR-STATE-CD PIC X(02). Example: CA, NY, TX.';

COMMENT ON COLUMN customer.country_code IS 'Three-letter country code. Source: CUST-ADDR-COUNTRY-CD PIC X(03). Example: USA, CAN, MEX.';

COMMENT ON COLUMN customer.zip_code IS 'Postal/ZIP code (up to 10 characters). Source: CUST-ADDR-ZIP PIC X(10). Supports both 5-digit and 9-digit ZIP codes.';

COMMENT ON COLUMN customer.phone_number_1 IS 'Primary phone number (up to 15 characters). Source: CUST-PHONE-NUM-1 PIC X(15). May include country code, area code, and extension.';

COMMENT ON COLUMN customer.phone_number_2 IS 'Secondary phone number (up to 15 characters). Source: CUST-PHONE-NUM-2 PIC X(15). Optional alternate contact number.';

COMMENT ON COLUMN customer.ssn IS 'Social Security Number (9 digits). Source: CUST-SSN PIC 9(09). Stored as VARCHAR to preserve leading zeros. Contains sensitive PII - handle according to data privacy regulations.';

COMMENT ON COLUMN customer.government_issued_id IS 'Government-issued identification number (up to 20 characters). Source: CUST-GOVT-ISSUED-ID PIC X(20). May contain driver license, passport, or other government ID.';

COMMENT ON COLUMN customer.date_of_birth IS 'Customer date of birth. Source: CUST-DOB-YYYY-MM-DD PIC X(10). Converted from COBOL string format (YYYY-MM-DD) to PostgreSQL DATE type.';

COMMENT ON COLUMN customer.eft_account_id IS 'Electronic Funds Transfer account identifier (up to 10 characters). Source: CUST-EFT-ACCOUNT-ID PIC X(10). Links to external banking system for payment processing.';

COMMENT ON COLUMN customer.primary_card_holder_indicator IS 'Indicates if customer is primary cardholder (1 character). Source: CUST-PRI-CARD-HOLDER-IND PIC X(01). Values: Y=Primary, N=Secondary, or other business-defined codes.';

COMMENT ON COLUMN customer.fico_credit_score IS 'FICO credit score (300-850 range). Source: CUST-FICO-CREDIT-SCORE PIC 9(03). Valid range enforced by CHECK constraint. NULL allowed for unknown scores.';

COMMENT ON COLUMN customer.created_at IS 'Timestamp when customer record was created. Audit field added for modern database management (not in original COBOL). Automatically set to current timestamp on INSERT.';

COMMENT ON COLUMN customer.updated_at IS 'Timestamp when customer record was last updated. Audit field added for modern database management (not in original COBOL). Automatically updated to current timestamp on UPDATE via trigger.';

-- ----------------------------------------------------------------------------
-- Trigger Function: Automatic updated_at Timestamp
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION update_customer_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    -- Set updated_at to current timestamp whenever a row is updated
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_customer_timestamp() IS 'Trigger function to automatically update the updated_at timestamp whenever a customer record is modified. Ensures audit trail integrity for change tracking.';

-- ----------------------------------------------------------------------------
-- Trigger: Before Update on Customer Table
-- ----------------------------------------------------------------------------
CREATE TRIGGER trg_customer_updated_at
    BEFORE UPDATE ON customer
    FOR EACH ROW
    EXECUTE FUNCTION update_customer_timestamp();

COMMENT ON TRIGGER trg_customer_updated_at ON customer IS 'Automatically updates the updated_at timestamp before any UPDATE operation. Maintains accurate audit trail for record modifications.';

-- ----------------------------------------------------------------------------
-- Index Creation Notes
-- ----------------------------------------------------------------------------
-- Primary index on customer_id is automatically created by PRIMARY KEY constraint
-- Additional performance indexes will be created in V7__create_indexes.sql:
--   - Index on (last_name, first_name) for name searches
--   - Index on ssn for identification lookups (with appropriate security controls)
--   - Index on fico_credit_score for credit analysis queries
--   - Index on created_at for temporal queries
-- ----------------------------------------------------------------------------

-- ============================================================================
-- End of Migration V1
-- ============================================================================
