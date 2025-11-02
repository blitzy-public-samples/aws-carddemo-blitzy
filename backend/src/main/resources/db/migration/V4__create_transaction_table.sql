-- =====================================================================
-- Flyway Migration V4: Create Transaction Table
-- =====================================================================
-- Description: Creates transaction table from TRANSACT VSAM KSDS
-- Source: CVTRA05Y.cpy copybook (TRANSACT VSAM KSDS)
-- VSAM Specs: KEYLEN=16, MAXLRECL=350, REC-TOTAL=311
-- Purpose: Store credit card transaction records with full audit trail
-- Dependencies: Requires V3 (card table) for foreign key constraint
-- =====================================================================

-- =====================================================================
-- Main Transaction Table
-- =====================================================================
-- Maps from TRAN-RECORD in CVTRA05Y.cpy (350 byte record)
-- Preserves COBOL COMP-3 decimal precision for financial amounts
-- Maintains referential integrity with card table
-- =====================================================================

CREATE TABLE transaction (
    -- ---------------------------------------------------------------
    -- Primary Key: Transaction ID
    -- From CVTRA05Y.cpy: TRAN-ID PIC X(16)
    -- VSAM primary key field (KEYLEN=16)
    -- ---------------------------------------------------------------
    transaction_id VARCHAR(16) NOT NULL,
    
    -- ---------------------------------------------------------------
    -- Transaction Classification Fields
    -- ---------------------------------------------------------------
    
    -- From CVTRA05Y.cpy: TRAN-TYPE-CD PIC X(02)
    -- Transaction type code (e.g., '01'=Purchase, '02'=Refund, etc.)
    transaction_type_code VARCHAR(2) NOT NULL,
    
    -- From CVTRA05Y.cpy: TRAN-CAT-CD PIC 9(04)
    -- Transaction category code (6 chars to handle leading zeros and future expansion)
    transaction_category_code VARCHAR(6) NOT NULL,
    
    -- From CVTRA05Y.cpy: TRAN-SOURCE PIC X(10)
    -- Transaction source (e.g., 'POS', 'ONLINE', 'ATM', 'PHONE')
    transaction_source VARCHAR(10) NOT NULL,
    
    -- From CVTRA05Y.cpy: TRAN-DESC PIC X(100)
    -- Human-readable transaction description
    transaction_description VARCHAR(100),
    
    -- ---------------------------------------------------------------
    -- Financial Amount Field - CRITICAL PRECISION REQUIREMENT
    -- ---------------------------------------------------------------
    
    -- From CVTRA05Y.cpy: TRAN-AMT PIC S9(09)V99 (COMP-3 packed decimal)
    -- CRITICAL: Maintains exact COBOL COMP-3 precision
    -- Signed 9 digits + 2 decimal places = NUMERIC(11,2)
    -- Range: -999999999.99 to +999999999.99
    -- This precision is MANDATORY for financial calculation integrity
    transaction_amount NUMERIC(11, 2) NOT NULL,
    
    -- ---------------------------------------------------------------
    -- Merchant Information Fields
    -- ---------------------------------------------------------------
    
    -- From CVTRA05Y.cpy: TRAN-MERCHANT-ID PIC 9(09)
    -- Merchant identification number (9 digits)
    merchant_id VARCHAR(9),
    
    -- From CVTRA05Y.cpy: TRAN-MERCHANT-NAME PIC X(50)
    -- Merchant business name
    merchant_name VARCHAR(50),
    
    -- From CVTRA05Y.cpy: TRAN-MERCHANT-CITY PIC X(50)
    -- Merchant city location
    merchant_city VARCHAR(50),
    
    -- From CVTRA05Y.cpy: TRAN-MERCHANT-ZIP PIC X(10)
    -- Merchant ZIP/postal code
    merchant_zip VARCHAR(10),
    
    -- ---------------------------------------------------------------
    -- Card Reference - Foreign Key Relationship
    -- ---------------------------------------------------------------
    
    -- From CVTRA05Y.cpy: TRAN-CARD-NUM PIC X(16)
    -- Card number (16 digits) - foreign key to card table
    -- This establishes the transaction-to-card-to-account relationship
    card_number VARCHAR(16) NOT NULL,
    
    -- ---------------------------------------------------------------
    -- Timestamp Fields
    -- ---------------------------------------------------------------
    
    -- From CVTRA05Y.cpy: TRAN-ORIG-TS PIC X(26)
    -- Original transaction timestamp (when transaction occurred)
    -- Converted from COBOL PIC X(26) format to PostgreSQL TIMESTAMP
    transaction_timestamp TIMESTAMP NOT NULL,
    
    -- From CVTRA05Y.cpy: TRAN-PROC-TS PIC X(26)
    -- Processing timestamp (when transaction was processed/posted)
    -- May be NULL for pending transactions
    processed_timestamp TIMESTAMP,
    
    -- ---------------------------------------------------------------
    -- Audit Trail Fields
    -- ---------------------------------------------------------------
    -- These fields are NOT in the original COBOL copybook
    -- Added for modern audit trail and compliance requirements
    -- ---------------------------------------------------------------
    
    -- Record creation timestamp (immutable)
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Record last update timestamp (auto-updated via trigger)
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- ---------------------------------------------------------------
    -- Primary Key Constraint
    -- ---------------------------------------------------------------
    CONSTRAINT pk_transaction PRIMARY KEY (transaction_id),
    
    -- ---------------------------------------------------------------
    -- Check Constraints for Data Integrity
    -- ---------------------------------------------------------------
    
    -- Ensure transaction amount is within valid NUMERIC(11,2) range
    -- Matches COBOL PIC S9(09)V99 capacity
    CONSTRAINT chk_transaction_amount 
        CHECK (transaction_amount BETWEEN -999999999.99 AND 999999999.99),
    
    -- Ensure transaction type code is not empty
    CONSTRAINT chk_transaction_type_code_not_empty 
        CHECK (transaction_type_code <> ''),
    
    -- Ensure transaction category code is not empty
    CONSTRAINT chk_transaction_category_code_not_empty 
        CHECK (transaction_category_code <> ''),
    
    -- Ensure transaction source is not empty
    CONSTRAINT chk_transaction_source_not_empty 
        CHECK (transaction_source <> ''),
    
    -- Ensure card number is exactly 16 digits (standard credit card length)
    CONSTRAINT chk_card_number_length 
        CHECK (LENGTH(card_number) = 16),
    
    -- Ensure transaction timestamp is not in the future
    CONSTRAINT chk_transaction_timestamp_not_future 
        CHECK (transaction_timestamp <= CURRENT_TIMESTAMP),
    
    -- Ensure processed timestamp is after or equal to transaction timestamp
    CONSTRAINT chk_processed_after_transaction 
        CHECK (processed_timestamp IS NULL OR processed_timestamp >= transaction_timestamp)
);

-- =====================================================================
-- Table and Column Comments for Documentation
-- =====================================================================
-- These comments provide comprehensive documentation for database
-- administrators and developers maintaining the system
-- =====================================================================

COMMENT ON TABLE transaction IS 
'Transaction records from TRANSACT VSAM KSDS (KEYLEN=16, MAXLRECL=350, 311 records). Based on CVTRA05Y.cpy copybook structure. Stores all credit card transaction data including purchases, refunds, and other transaction types with full merchant details and audit trail.';

COMMENT ON COLUMN transaction.transaction_id IS 
'Transaction ID (16 characters), from TRAN-ID PIC X(16). VSAM primary key field. Unique identifier for each transaction in the system.';

COMMENT ON COLUMN transaction.transaction_type_code IS 
'Transaction type code (2 characters), from TRAN-TYPE-CD PIC X(02). Examples: 01=Purchase, 02=Refund, 03=Cash Advance, 04=Payment, 05=Fee, 06=Interest, 07=Adjustment.';

COMMENT ON COLUMN transaction.transaction_category_code IS 
'Transaction category code (4-6 digits), from TRAN-CAT-CD PIC 9(04). Represents merchant category code (MCC) for transaction classification and reporting.';

COMMENT ON COLUMN transaction.transaction_source IS 
'Transaction source (10 characters), from TRAN-SOURCE PIC X(10). Indicates origin of transaction: POS, ONLINE, ATM, PHONE, MAIL, etc.';

COMMENT ON COLUMN transaction.transaction_description IS 
'Transaction description (100 characters), from TRAN-DESC PIC X(100). Human-readable description of the transaction for customer statements.';

COMMENT ON COLUMN transaction.transaction_amount IS 
'Transaction amount with 2 decimal precision, from TRAN-AMT PIC S9(09)V99 COMP-3 packed decimal. CRITICAL: Maintains exact COBOL precision using NUMERIC(11,2). Range: -999999999.99 to +999999999.99. Positive for charges, negative for credits.';

COMMENT ON COLUMN transaction.merchant_id IS 
'Merchant identification number (9 digits), from TRAN-MERCHANT-ID PIC 9(09). Unique identifier for the merchant/retailer.';

COMMENT ON COLUMN transaction.merchant_name IS 
'Merchant business name (50 characters), from TRAN-MERCHANT-NAME PIC X(50). Name of the merchant as it appears on statements.';

COMMENT ON COLUMN transaction.merchant_city IS 
'Merchant city location (50 characters), from TRAN-MERCHANT-CITY PIC X(50). City where merchant is located.';

COMMENT ON COLUMN transaction.merchant_zip IS 
'Merchant ZIP/postal code (10 characters), from TRAN-MERCHANT-ZIP PIC X(10). Postal code for merchant location.';

COMMENT ON COLUMN transaction.card_number IS 
'Card number (16 digits), from TRAN-CARD-NUM PIC X(16). Foreign key reference to card table. Links transaction to specific credit card.';

COMMENT ON COLUMN transaction.transaction_timestamp IS 
'Original transaction timestamp, from TRAN-ORIG-TS PIC X(26). Date and time when transaction originally occurred at point of sale or online.';

COMMENT ON COLUMN transaction.processed_timestamp IS 
'Processing timestamp, from TRAN-PROC-TS PIC X(26). Date and time when transaction was processed and posted to account. NULL for pending transactions.';

COMMENT ON COLUMN transaction.created_at IS 
'Record creation timestamp (audit field). Automatically set when record is inserted. Immutable.';

COMMENT ON COLUMN transaction.updated_at IS 
'Record last update timestamp (audit field). Automatically updated via trigger whenever record is modified.';

-- =====================================================================
-- Foreign Key Constraints
-- =====================================================================
-- Establishes referential integrity with card table
-- =====================================================================

-- Foreign key to card table
-- ON DELETE RESTRICT prevents deletion of cards with associated transactions
-- This preserves transaction history even if cards are deactivated
ALTER TABLE transaction
    ADD CONSTRAINT fk_transaction_card
    FOREIGN KEY (card_number)
    REFERENCES card(card_number)
    ON DELETE RESTRICT
    ON UPDATE CASCADE;

-- =====================================================================
-- Performance Indexes
-- =====================================================================
-- Indexes designed to support common query patterns and maintain
-- performance equivalent to VSAM key-sequenced access
-- =====================================================================

-- Index on transaction_timestamp for date range queries
-- Supports queries like: "Show all transactions for last month"
-- Critical for statement generation and reporting batch jobs
CREATE INDEX idx_transaction_timestamp 
    ON transaction(transaction_timestamp DESC);

-- Index on card_number for card-specific transaction lookups
-- Supports queries like: "Show all transactions for card 4111111111111111"
-- Already has foreign key constraint, but explicit index improves query performance
CREATE INDEX idx_transaction_card_number 
    ON transaction(card_number);

-- Composite index on card_number and transaction_timestamp
-- Optimizes the most common query pattern: transactions for a card in date range
-- Supports efficient pagination of transaction lists (10 per page per requirements)
CREATE INDEX idx_transaction_card_timestamp 
    ON transaction(card_number, transaction_timestamp DESC);

-- Index on processed_timestamp for batch processing queries
-- Supports queries to find unprocessed or recently processed transactions
-- Critical for daily transaction processing batch jobs
CREATE INDEX idx_transaction_processed_timestamp 
    ON transaction(processed_timestamp) 
    WHERE processed_timestamp IS NOT NULL;

-- Index on transaction_type_code for reporting and analytics
-- Supports queries that filter by transaction type for reports
CREATE INDEX idx_transaction_type_code 
    ON transaction(transaction_type_code);

-- Index on transaction_category_code for merchant category reporting
-- Supports category aggregation queries as per COTRN01C requirements
CREATE INDEX idx_transaction_category_code 
    ON transaction(transaction_category_code);

-- Composite index on card_number and transaction_amount for balance calculations
-- Supports efficient sum operations for account balance updates
CREATE INDEX idx_transaction_card_amount 
    ON transaction(card_number, transaction_amount);

-- =====================================================================
-- Trigger for Automatic Timestamp Updates
-- =====================================================================
-- Automatically maintains updated_at timestamp for audit trail
-- =====================================================================

-- Function to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_transaction_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    -- Set updated_at to current timestamp whenever record is updated
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger that executes before each UPDATE operation
CREATE TRIGGER trg_transaction_updated_at
    BEFORE UPDATE ON transaction
    FOR EACH ROW
    EXECUTE FUNCTION update_transaction_timestamp();

-- =====================================================================
-- Migration Validation Comments
-- =====================================================================
-- Field Mapping Summary from CVTRA05Y.cpy to transaction table:
-- 
-- COBOL Field             | SQL Column                  | Notes
-- ------------------------|-----------------------------|--------------------------
-- TRAN-ID                 | transaction_id              | PK, VARCHAR(16)
-- TRAN-TYPE-CD            | transaction_type_code       | VARCHAR(2)
-- TRAN-CAT-CD             | transaction_category_code   | VARCHAR(6), was PIC 9(04)
-- TRAN-SOURCE             | transaction_source          | VARCHAR(10)
-- TRAN-DESC               | transaction_description     | VARCHAR(100)
-- TRAN-AMT                | transaction_amount          | NUMERIC(11,2), was S9(09)V99
-- TRAN-MERCHANT-ID        | merchant_id                 | VARCHAR(9)
-- TRAN-MERCHANT-NAME      | merchant_name               | VARCHAR(50)
-- TRAN-MERCHANT-CITY      | merchant_city               | VARCHAR(50)
-- TRAN-MERCHANT-ZIP       | merchant_zip                | VARCHAR(10)
-- TRAN-CARD-NUM           | card_number                 | VARCHAR(16), FK to card
-- TRAN-ORIG-TS            | transaction_timestamp       | TIMESTAMP
-- TRAN-PROC-TS            | processed_timestamp         | TIMESTAMP
-- FILLER                  | (not mapped)                | 20 bytes unused
-- (new)                   | created_at                  | Audit timestamp
-- (new)                   | updated_at                  | Audit timestamp
-- 
-- VSAM Specifications:
-- - KEYLEN: 16 bytes (transaction_id)
-- - MAXLRECL: 350 bytes total
-- - REC-TOTAL: 311 existing records
-- 
-- Critical Precision Requirements:
-- - TRAN-AMT PIC S9(09)V99 COMP-3 → NUMERIC(11,2)
--   This ensures exact decimal precision for financial calculations
--   Range: -999,999,999.99 to +999,999,999.99
-- 
-- Performance Requirements:
-- - Support 10,000 TPS transaction throughput
-- - Transaction queries < 200ms at 95th percentile
-- - Efficient date range queries for statement generation
-- - Pagination support for transaction lists (10 per page)
-- 
-- Referential Integrity:
-- - Foreign key to card table via card_number
-- - CASCADE updates, RESTRICT deletes to preserve history
-- - Supports transaction-to-card-to-account-to-customer relationships
-- 
-- Security and Compliance:
-- - Complete audit trail with created_at and updated_at timestamps
-- - Immutable transaction_id primary key
-- - All financial amounts logged with exact precision
-- - Full merchant details for regulatory compliance
-- =====================================================================
