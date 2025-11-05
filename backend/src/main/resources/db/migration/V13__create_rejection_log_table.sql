-- ================================================================
-- Flyway Migration V13: Create Rejection Log Table
-- ================================================================
-- Purpose: Create rejection log table for tracking validation failures
-- Source COBOL: CBTRN02C.cbl (Daily Transaction Processing Batch)
-- Source Copybook: REJECT-RECORD structure (lines 446-465)
-- Target Table: rejection_log
-- Migration Type: CREATE (new table)
-- 
-- Description:
-- This table stores transaction records that fail validation during
-- daily transaction batch processing. Each rejected transaction is
-- logged with the complete original data and detailed validation
-- failure information for audit trail and potential reprocessing.
--
-- COBOL File Mapping:
--   DALYREJS-FILE (Sequential reject file) → rejection_log (PostgreSQL table)
--
-- Processing Flow:
--   1. Transaction fails validation in daily processing
--   2. Original transaction data preserved in rejection_log
--   3. Validation failure reason code and description recorded
--   4. Reject report generated from rejection_log entries
--   5. Manual review and potential resubmission/correction
-- 
-- Rejection Reason Codes:
--   100 - INVALID CARD NUMBER FOUND (card not found in XREF file)
--   101 - ACCOUNT RECORD NOT FOUND
--   102 - OVERLIMIT TRANSACTION (credit limit exceeded)
--   103 - TRANSACTION RECEIVED AFTER ACCT EXPIRATION
--   104 - INVALID TRANSACTION AMOUNT
--   105 - FUTURE-DATED TRANSACTION
--   106 - DUPLICATE TRANSACTION ID
--   107 - INVALID MERCHANT
--   109 - ACCOUNT RECORD NOT FOUND (during update phase)
-- ================================================================

-- Create rejection_log table
CREATE TABLE rejection_log (
    -- Primary key: Auto-generated surrogate key
    id BIGSERIAL PRIMARY KEY,
    
    -- Transaction ID from the rejected daily transaction
    -- COBOL: DALYTRAN-ID PIC X(16)
    transaction_id VARCHAR(16) NOT NULL,
    
    -- Timestamp when the rejection was processed
    -- Used for audit trail and rejection analysis
    processing_timestamp TIMESTAMP NOT NULL,
    
    -- Validation failure reason code (4 digits)
    -- COBOL: WS-VALIDATION-FAIL-REASON PIC 9(04)
    validation_failure_reason_code INTEGER NOT NULL,
    
    -- Validation failure description (76 characters)
    -- COBOL: WS-VALIDATION-FAIL-REASON-DESC PIC X(76)
    validation_failure_description VARCHAR(76) NOT NULL,
    
    -- Complete original transaction data (350 bytes)
    -- COBOL: REJECT-TRAN-DATA PIC X(350)
    -- Preserved for audit trail and potential reprocessing
    original_transaction_data VARCHAR(350),
    
    -- Card number from the rejected transaction
    -- COBOL: DALYTRAN-CARD-NUM PIC X(16)
    -- Extracted for queryability
    card_number VARCHAR(16),
    
    -- Transaction amount from the rejected transaction
    -- COBOL: DALYTRAN-AMT PIC S9(09)V99 COMP-3
    -- BigDecimal with scale 2 for COMP-3 equivalence
    transaction_amount NUMERIC(11, 2),
    
    -- Transaction type code
    -- COBOL: DALYTRAN-TYPE-CD PIC X(02)
    transaction_type_code VARCHAR(2),
    
    -- Transaction category code
    -- COBOL: DALYTRAN-CAT-CD PIC 9(04)
    transaction_category_code INTEGER,
    
    -- Merchant ID from the rejected transaction
    -- COBOL: DALYTRAN-MERCHANT-ID PIC 9(09)
    merchant_id VARCHAR(50),
    
    -- Merchant name from the rejected transaction
    -- COBOL: DALYTRAN-MERCHANT-NAME PIC X(50)
    merchant_name VARCHAR(50),
    
    -- Original timestamp from the transaction
    -- COBOL: DALYTRAN-ORIG-TS PIC X(26)
    -- Stored as string to preserve original format
    original_timestamp VARCHAR(26)
);

-- Create index on transaction_id for lookup by transaction
-- Usage Pattern: SELECT * FROM rejection_log WHERE transaction_id = ?
CREATE INDEX idx_rejection_transaction_id 
ON rejection_log(transaction_id);

-- Create index on processing_timestamp for temporal queries
-- Usage Pattern: SELECT * FROM rejection_log WHERE processing_timestamp BETWEEN ? AND ?
CREATE INDEX idx_rejection_processing_timestamp 
ON rejection_log(processing_timestamp);

-- Create index on validation_failure_reason_code for rejection analysis
-- Usage Pattern: SELECT COUNT(*), validation_failure_reason_code FROM rejection_log GROUP BY validation_failure_reason_code
CREATE INDEX idx_rejection_reason_code 
ON rejection_log(validation_failure_reason_code);

-- Add comments for documentation
COMMENT ON TABLE rejection_log IS 
'Rejection log for daily transaction validation failures. Source: CBTRN02C.cbl DALYREJS-FILE. Preserves original transaction data with validation failure details.';

COMMENT ON COLUMN rejection_log.id IS 
'Auto-generated surrogate key for rejection log entries.';

COMMENT ON COLUMN rejection_log.transaction_id IS 
'Transaction ID from the rejected daily transaction. Source: DALYTRAN-ID PIC X(16).';

COMMENT ON COLUMN rejection_log.processing_timestamp IS 
'Timestamp when the rejection was processed. Used for audit trail and rejection analysis.';

COMMENT ON COLUMN rejection_log.validation_failure_reason_code IS 
'Validation failure reason code (4 digits). Source: WS-VALIDATION-FAIL-REASON PIC 9(04).';

COMMENT ON COLUMN rejection_log.validation_failure_description IS 
'Validation failure description (76 characters). Source: WS-VALIDATION-FAIL-REASON-DESC PIC X(76).';

COMMENT ON COLUMN rejection_log.original_transaction_data IS 
'Complete original transaction data (350 bytes). Source: REJECT-TRAN-DATA PIC X(350). Preserved for audit trail and potential reprocessing.';

COMMENT ON COLUMN rejection_log.card_number IS 
'Card number from the rejected transaction. Source: DALYTRAN-CARD-NUM PIC X(16). Extracted for queryability.';

COMMENT ON COLUMN rejection_log.transaction_amount IS 
'Transaction amount from the rejected transaction. Source: DALYTRAN-AMT PIC S9(09)V99 COMP-3. BigDecimal with scale 2 for COMP-3 equivalence.';

COMMENT ON COLUMN rejection_log.transaction_type_code IS 
'Transaction type code. Source: DALYTRAN-TYPE-CD PIC X(02).';

COMMENT ON COLUMN rejection_log.transaction_category_code IS 
'Transaction category code. Source: DALYTRAN-CAT-CD PIC 9(04).';

COMMENT ON COLUMN rejection_log.merchant_id IS 
'Merchant ID from the rejected transaction. Source: DALYTRAN-MERCHANT-ID PIC 9(09).';

COMMENT ON COLUMN rejection_log.merchant_name IS 
'Merchant name from the rejected transaction. Source: DALYTRAN-MERCHANT-NAME PIC X(50).';

COMMENT ON COLUMN rejection_log.original_timestamp IS 
'Original timestamp from the transaction. Source: DALYTRAN-ORIG-TS PIC X(26). Stored as string to preserve original format.';

-- Migration Complete
-- Table: rejection_log
-- Indexes: 3 (idx_rejection_transaction_id, idx_rejection_processing_timestamp, idx_rejection_reason_code)
