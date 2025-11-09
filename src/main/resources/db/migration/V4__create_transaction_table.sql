-- =============================================================================
-- Flyway Migration: V4__create_transaction_table.sql
-- Description: Creates transaction table from COBOL copybook CVTRA05Y.cpy
-- Source: app/cpy/CVTRA05Y.cpy (RECLN=350 bytes)
-- =============================================================================
-- This migration transforms the COBOL TRAN-RECORD structure into a PostgreSQL
-- table with appropriate data types, constraints, and indexes.
-- 
-- COBOL to PostgreSQL Mapping:
-- - TRAN-ID PIC X(16)              -> transaction_id VARCHAR(16) PRIMARY KEY
-- - TRAN-TYPE-CD PIC X(02)         -> transaction_type_code CHAR(2) NOT NULL
-- - TRAN-CAT-CD PIC 9(04)          -> transaction_category_code SMALLINT
-- - TRAN-SOURCE PIC X(10)          -> source VARCHAR(10)
-- - TRAN-DESC PIC X(100)           -> description VARCHAR(100)
-- - TRAN-AMT PIC S9(09)V99         -> amount NUMERIC(11,2) NOT NULL
-- - TRAN-MERCHANT-ID PIC 9(09)     -> merchant_id INTEGER
-- - TRAN-MERCHANT-NAME PIC X(50)   -> merchant_name VARCHAR(50)
-- - TRAN-MERCHANT-CITY PIC X(50)   -> merchant_city VARCHAR(50)
-- - TRAN-MERCHANT-ZIP PIC X(10)    -> merchant_postal_code VARCHAR(10)
-- - TRAN-CARD-NUM PIC X(16)        -> card_number VARCHAR(16) NOT NULL FK
-- - TRAN-ORIG-TS PIC X(26)         -> original_timestamp TIMESTAMP(6)
-- - TRAN-PROC-TS PIC X(26)         -> processed_timestamp TIMESTAMP(6)
-- 
-- Additional Fields:
-- - created_at TIMESTAMP            -> Audit trail timestamp
-- 
-- Key Design Decisions:
-- 1. NUMERIC(11,2) for amount ensures exact precision matching COBOL S9(09)V99
--    with explicit scale=2 for monetary values and RoundingMode.HALF_UP semantics
-- 2. TIMESTAMP(6) provides microsecond precision matching COBOL 26-char timestamps
-- 3. Foreign key to card(card_number) with CASCADE delete maintains referential
--    integrity, replacing VSAM XREF cross-reference files
-- 4. Indexes on card_number and original_timestamp DESC optimize transaction
--    history queries and date-range pagination (10 transactions per page)
-- =============================================================================

-- Drop table if exists (for development rollback support)
DROP TABLE IF EXISTS transaction CASCADE;

-- Create transaction table
CREATE TABLE transaction (
    -- Primary Key: Transaction unique identifier
    -- Maps from TRAN-ID PIC X(16)
    transaction_id VARCHAR(16) NOT NULL,
    
    -- Transaction Classification Fields
    -- Maps from TRAN-TYPE-CD PIC X(02) - transaction type code
    transaction_type_code CHAR(2) NOT NULL,
    
    -- Maps from TRAN-CAT-CD PIC 9(04) - 4-digit category code
    -- SMALLINT supports range 0-32767, sufficient for 9999 max
    transaction_category_code SMALLINT,
    
    -- Transaction Details
    -- Maps from TRAN-SOURCE PIC X(10) - transaction source system
    source VARCHAR(10),
    
    -- Maps from TRAN-DESC PIC X(100) - transaction description
    description VARCHAR(100),
    
    -- Monetary Amount Field
    -- Maps from TRAN-AMT PIC S9(09)V99
    -- NUMERIC(11,2) provides:
    --   - Precision 11: 9 integer digits + 2 decimal places
    --   - Scale 2: Exact 2 decimal places for cents
    --   - Matches Java BigDecimal with RoundingMode.HALF_UP
    --   - Signed field supporting negative amounts (reversals/refunds)
    -- Valid range: -999999999.99 to +999999999.99
    amount NUMERIC(11,2) NOT NULL,
    
    -- Merchant Information
    -- Maps from TRAN-MERCHANT-ID PIC 9(09)
    merchant_id INTEGER,
    
    -- Maps from TRAN-MERCHANT-NAME PIC X(50)
    merchant_name VARCHAR(50),
    
    -- Maps from TRAN-MERCHANT-CITY PIC X(50)
    merchant_city VARCHAR(50),
    
    -- Maps from TRAN-MERCHANT-ZIP PIC X(10)
    merchant_postal_code VARCHAR(10),
    
    -- Foreign Key: Card Reference
    -- Maps from TRAN-CARD-NUM PIC X(16)
    -- Links to card table, enforcing referential integrity
    -- Replaces VSAM XREF cross-reference file mechanism
    card_number VARCHAR(16) NOT NULL,
    
    -- Timestamp Fields with Microsecond Precision
    -- Maps from TRAN-ORIG-TS PIC X(26)
    -- TIMESTAMP(6) provides microsecond precision (6 fractional seconds)
    -- matching COBOL 26-character timestamp format with microseconds
    -- Requires DateUtility.java conversion from Lillian format to LocalDateTime
    original_timestamp TIMESTAMP(6),
    
    -- Maps from TRAN-PROC-TS PIC X(26)
    -- Timestamp when transaction was processed/posted
    processed_timestamp TIMESTAMP(6),
    
    -- Audit Trail Field
    -- Added for cloud-native audit requirements
    -- Automatically set to current timestamp on insert
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Primary Key Constraint
    CONSTRAINT pk_transaction PRIMARY KEY (transaction_id),
    
    -- Foreign Key Constraint
    -- Enforces referential integrity with card table
    -- ON DELETE CASCADE: If card is deleted, all transactions are deleted
    -- This maintains data consistency replacing VSAM cross-reference logic
    CONSTRAINT fk_transaction_card FOREIGN KEY (card_number)
        REFERENCES card(card_number)
        ON DELETE CASCADE
);

-- =============================================================================
-- Index Definitions
-- =============================================================================

-- Index on card_number for transaction history queries
-- Optimizes queries like: SELECT * FROM transaction WHERE card_number = ?
-- Critical for CardDetailService.java and TransactionListService.java
-- Supports pagination pattern of 10 transactions per page
CREATE INDEX idx_transaction_card_number 
    ON transaction(card_number);

-- Composite index on card_number and original_timestamp for date-range queries
-- Optimizes queries like: 
--   SELECT * FROM transaction 
--   WHERE card_number = ? AND original_timestamp BETWEEN ? AND ?
--   ORDER BY original_timestamp DESC
-- DESC ordering supports pagination from most recent to oldest
-- Critical for TransactionListComponent.jsx date filtering
CREATE INDEX idx_transaction_card_timestamp 
    ON transaction(card_number, original_timestamp DESC);

-- Index on original_timestamp for global date-range reports
-- Optimizes queries like:
--   SELECT * FROM transaction 
--   WHERE original_timestamp BETWEEN ? AND ?
-- Supports ReportGenerationService.java transaction report generation
CREATE INDEX idx_transaction_timestamp 
    ON transaction(original_timestamp DESC);

-- Index on transaction_type_code for type-based filtering
-- Optimizes queries grouping or filtering by transaction type
-- Supports batch processing in DailyTransactionProcessingJob.java
CREATE INDEX idx_transaction_type_code 
    ON transaction(transaction_type_code);

-- Index on transaction_category_code for category-based reporting
-- Optimizes queries grouping by category
-- Supports TransactionCategoryBalance calculations in InterestCalculationJob.java
CREATE INDEX idx_transaction_category_code 
    ON transaction(transaction_category_code);

-- =============================================================================
-- Table Comment
-- =============================================================================

COMMENT ON TABLE transaction IS 'Transaction master table transformed from COBOL copybook CVTRA05Y.cpy (RECLN=350 bytes). Stores all credit card transaction records with merchant details, amounts, and timestamps. Foreign key to card table maintains referential integrity replacing VSAM XREF cross-reference. Indexed for efficient transaction history queries and date-range pagination (10 transactions per page). Amount field uses NUMERIC(11,2) for exact precision matching COBOL S9(09)V99 with BigDecimal semantics. Timestamps use TIMESTAMP(6) microsecond precision matching COBOL 26-character format requiring DateUtility.java Lillian conversion.';

-- =============================================================================
-- Column Comments
-- =============================================================================

COMMENT ON COLUMN transaction.transaction_id IS 
'Transaction unique identifier. Maps from COBOL TRAN-ID PIC X(16). Primary key.';

COMMENT ON COLUMN transaction.transaction_type_code IS 
'Transaction type code (e.g., 01=Purchase, 02=Cash Advance). Maps from COBOL TRAN-TYPE-CD PIC X(02). References transaction_type table.';

COMMENT ON COLUMN transaction.transaction_category_code IS 
'Transaction category code (4-digit numeric). Maps from COBOL TRAN-CAT-CD PIC 9(04). References transaction_category table.';

COMMENT ON COLUMN transaction.source IS 
'Transaction source system identifier. Maps from COBOL TRAN-SOURCE PIC X(10).';

COMMENT ON COLUMN transaction.description IS 
'Transaction description text. Maps from COBOL TRAN-DESC PIC X(100).';

COMMENT ON COLUMN transaction.amount IS 'Transaction amount with exact 2 decimal precision. Maps from COBOL TRAN-AMT PIC S9(09)V99. NUMERIC(11,2) ensures exact monetary precision matching Java BigDecimal with RoundingMode.HALF_UP. Signed field supports negative amounts for reversals and refunds. Valid range: -999999999.99 to +999999999.99.';

COMMENT ON COLUMN transaction.merchant_id IS 
'Merchant identifier (9-digit numeric). Maps from COBOL TRAN-MERCHANT-ID PIC 9(09).';

COMMENT ON COLUMN transaction.merchant_name IS 
'Merchant business name. Maps from COBOL TRAN-MERCHANT-NAME PIC X(50).';

COMMENT ON COLUMN transaction.merchant_city IS 
'Merchant city location. Maps from COBOL TRAN-MERCHANT-CITY PIC X(50).';

COMMENT ON COLUMN transaction.merchant_postal_code IS 
'Merchant postal/ZIP code. Maps from COBOL TRAN-MERCHANT-ZIP PIC X(10).';

COMMENT ON COLUMN transaction.card_number IS 'Card number reference. Maps from COBOL TRAN-CARD-NUM PIC X(16). Foreign key to card(card_number) with CASCADE delete. Enforces referential integrity replacing VSAM XREF cross-reference files.';

COMMENT ON COLUMN transaction.original_timestamp IS 'Original transaction timestamp with microsecond precision. Maps from COBOL TRAN-ORIG-TS PIC X(26). TIMESTAMP(6) provides 6 fractional seconds matching COBOL 26-character timestamp format. Requires DateUtility.java conversion from Lillian format to LocalDateTime.';

COMMENT ON COLUMN transaction.processed_timestamp IS 'Transaction processing timestamp with microsecond precision. Maps from COBOL TRAN-PROC-TS PIC X(26). TIMESTAMP(6) provides 6 fractional seconds. Set by DailyTransactionProcessingJob.java when transaction is posted.';

COMMENT ON COLUMN transaction.created_at IS 'Audit trail timestamp. Automatically set to current timestamp on record creation. Added for cloud-native audit requirements beyond original COBOL structure.';

-- =============================================================================
-- End of Migration
-- =============================================================================
