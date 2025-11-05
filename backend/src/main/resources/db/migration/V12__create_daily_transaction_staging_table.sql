-- ================================================================
-- Flyway Migration V12: Create Daily Transaction Staging Table
-- ================================================================
-- Purpose: Create staging table for daily transaction batch processing
-- Source COBOL: CBTRN02C.cbl (Daily Transaction Processing Batch)
-- Source Copybook: CVTRA06Y.cpy (DALYTRAN-RECORD structure)
-- Target Table: daily_transaction_staging
-- Migration Type: CREATE (new table)
-- 
-- Description:
-- This table serves as a temporary staging area for daily transaction
-- batch processing. Transactions are loaded with status 'PENDING',
-- validated, and then either posted to the permanent transaction table
-- (status → 'POSTED') or rejected with validation errors (status → 'REJECTED').
--
-- COBOL File Mapping:
--   DALYTRAN-FILE (Sequential file) → daily_transaction_staging (PostgreSQL table)
--
-- Processing Flow:
--   1. Load daily transaction file into staging table (status = 'PENDING')
--   2. Validate each transaction record
--   3. Post valid transactions to transaction table (status = 'POSTED')
--   4. Reject invalid transactions with reason code (status = 'REJECTED')
--   5. Generate reject report for failed transactions
--   6. Archive or purge processed staging records
-- ================================================================

-- Create daily_transaction_staging table
CREATE TABLE daily_transaction_staging (
    -- Primary key: Transaction unique identifier
    -- COBOL: DALYTRAN-ID PIC X(16)
    transaction_id VARCHAR(16) PRIMARY KEY,
    
    -- Transaction type code: 'DR' = Debit (charge), 'CR' = Credit (payment/refund)
    -- COBOL: DALYTRAN-TYPE-CD PIC X(02)
    type_code VARCHAR(2) NOT NULL,
    
    -- Transaction category code (1-9999)
    -- COBOL: DALYTRAN-CAT-CD PIC 9(04)
    category_code VARCHAR(6) NOT NULL,
    
    -- Transaction source/channel: 'POS', 'ATM', 'ONLINE', 'PHONE', 'MAIL', etc.
    -- COBOL: DALYTRAN-SOURCE PIC X(10)
    source VARCHAR(10) NOT NULL,
    
    -- Transaction description
    -- COBOL: DALYTRAN-DESC PIC X(100)
    description VARCHAR(100),
    
    -- Transaction amount (COMP-3 precision: S9(09)V99)
    -- Precision: 11 total digits, 2 decimal places
    -- Positive = charge to customer, Negative = credit (payment/refund)
    -- COBOL: DALYTRAN-AMT PIC S9(09)V99 COMP-3
    amount NUMERIC(11, 2) NOT NULL,
    
    -- Merchant identifier (stored as String to preserve leading zeros)
    -- COBOL: DALYTRAN-MERCHANT-ID PIC 9(09)
    merchant_id VARCHAR(15),
    
    -- Merchant name
    -- COBOL: DALYTRAN-MERCHANT-NAME PIC X(50)
    merchant_name VARCHAR(50),
    
    -- Merchant city
    -- COBOL: DALYTRAN-MERCHANT-CITY PIC X(50)
    merchant_city VARCHAR(50),
    
    -- Merchant ZIP code
    -- COBOL: DALYTRAN-MERCHANT-ZIP PIC X(10)
    merchant_zip VARCHAR(10),
    
    -- Card number used for the transaction
    -- COBOL: DALYTRAN-CARD-NUM PIC X(16)
    card_number VARCHAR(16) NOT NULL,
    
    -- Original transaction timestamp from source system
    -- COBOL: DALYTRAN-ORIG-TS PIC X(26)
    -- Format: YYYY-MM-DD-HH.MM.SS.nnnnnn
    original_timestamp TIMESTAMP NOT NULL,
    
    -- Processing timestamp when record was validated/posted
    -- COBOL: DALYTRAN-PROC-TS PIC X(26)
    processed_timestamp TIMESTAMP,
    
    -- Processing status indicator
    -- Values: 'PENDING' (loaded), 'POSTED' (validated and posted), 'REJECTED' (failed validation)
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    
    -- Validation failure reason code (if rejected)
    -- Corresponds to WS-VALIDATION-FAIL-REASON in COBOL
    reject_reason_code INTEGER,
    
    -- Validation failure description (if rejected)
    -- Corresponds to WS-VALIDATION-FAIL-REASON-DESC in COBOL
    reject_reason_desc VARCHAR(100),
    
    -- Audit fields
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT chk_staging_type_code 
        CHECK (type_code IN ('DR', 'CR')),
    
    CONSTRAINT chk_staging_status 
        CHECK (status IN ('PENDING', 'POSTED', 'REJECTED')),
    
    CONSTRAINT chk_staging_amount_precision 
        CHECK (amount >= -999999999.99 AND amount <= 999999999.99)
);

-- Create index on status and transaction_id for batch processing queries
-- Usage Pattern: SELECT * FROM daily_transaction_staging WHERE status = 'PENDING' ORDER BY transaction_id
CREATE INDEX idx_staging_status_txn_id 
ON daily_transaction_staging(status, transaction_id);

-- Create index on card_number for validation lookups
-- Usage Pattern: SELECT COUNT(*) FROM daily_transaction_staging WHERE card_number = ? AND status = 'PENDING'
CREATE INDEX idx_staging_card_num 
ON daily_transaction_staging(card_number);

-- Create index on original_timestamp for temporal queries
-- Usage Pattern: SELECT * FROM daily_transaction_staging WHERE original_timestamp BETWEEN ? AND ?
CREATE INDEX idx_staging_orig_ts 
ON daily_transaction_staging(original_timestamp);

-- Add comments for documentation
COMMENT ON TABLE daily_transaction_staging IS 
'Staging table for daily transaction batch processing. Source: CBTRN02C.cbl DALYTRAN-FILE. Transactions are loaded, validated, and either posted or rejected.';

COMMENT ON COLUMN daily_transaction_staging.transaction_id IS 
'Transaction unique identifier (primary key). Source: DALYTRAN-ID PIC X(16).';

COMMENT ON COLUMN daily_transaction_staging.type_code IS 
'Transaction type code: DR=Debit (charge), CR=Credit (payment/refund). Source: DALYTRAN-TYPE-CD PIC X(02).';

COMMENT ON COLUMN daily_transaction_staging.category_code IS 
'Transaction category code (1-9999). Source: DALYTRAN-CAT-CD PIC 9(04).';

COMMENT ON COLUMN daily_transaction_staging.source IS 
'Transaction source/channel: POS, ATM, ONLINE, PHONE, MAIL, etc. Source: DALYTRAN-SOURCE PIC X(10).';

COMMENT ON COLUMN daily_transaction_staging.description IS 
'Transaction description. Source: DALYTRAN-DESC PIC X(100).';

COMMENT ON COLUMN daily_transaction_staging.amount IS 
'Transaction amount with COMP-3 precision (S9(09)V99). Positive=charge, Negative=credit. Source: DALYTRAN-AMT PIC S9(09)V99 COMP-3.';

COMMENT ON COLUMN daily_transaction_staging.merchant_id IS 
'Merchant identifier (preserves leading zeros). Source: DALYTRAN-MERCHANT-ID PIC 9(09).';

COMMENT ON COLUMN daily_transaction_staging.merchant_name IS 
'Merchant name. Source: DALYTRAN-MERCHANT-NAME PIC X(50).';

COMMENT ON COLUMN daily_transaction_staging.merchant_city IS 
'Merchant city. Source: DALYTRAN-MERCHANT-CITY PIC X(50).';

COMMENT ON COLUMN daily_transaction_staging.merchant_zip IS 
'Merchant ZIP code. Source: DALYTRAN-MERCHANT-ZIP PIC X(10).';

COMMENT ON COLUMN daily_transaction_staging.card_number IS 
'Card number used for transaction. Source: DALYTRAN-CARD-NUM PIC X(16).';

COMMENT ON COLUMN daily_transaction_staging.original_timestamp IS 
'Original transaction timestamp from source system. Source: DALYTRAN-ORIG-TS PIC X(26).';

COMMENT ON COLUMN daily_transaction_staging.processed_timestamp IS 
'Processing timestamp when record was validated/posted. Source: DALYTRAN-PROC-TS PIC X(26).';

COMMENT ON COLUMN daily_transaction_staging.status IS 
'Processing status: PENDING (loaded), POSTED (validated and posted), REJECTED (failed validation).';

COMMENT ON COLUMN daily_transaction_staging.reject_reason_code IS 
'Validation failure reason code (if rejected). Source: WS-VALIDATION-FAIL-REASON.';

COMMENT ON COLUMN daily_transaction_staging.reject_reason_desc IS 
'Validation failure description (if rejected). Source: WS-VALIDATION-FAIL-REASON-DESC.';

-- Migration Complete
-- Table: daily_transaction_staging
-- Indexes: 3 (idx_staging_status_txn_id, idx_staging_card_num, idx_staging_orig_ts)
-- Constraints: 3 (chk_staging_type_code, chk_staging_status, chk_staging_amount_precision)
