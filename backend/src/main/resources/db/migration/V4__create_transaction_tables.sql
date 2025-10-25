-- =====================================================================
-- Flyway Migration V4: Create Transaction Tables
-- =====================================================================
-- Source: COBOL copybooks CVTRA05Y.cpy (TRAN-RECORD) and 
--         CVTRA06Y.cpy (DALYTRAN-RECORD)
-- Record Length: 350 bytes each
--
-- Purpose: 
--   Create transaction and daily_transaction tables for CardDemo
--   credit card transaction management system, migrated from VSAM
--   KSDS datasets to PostgreSQL relational tables.
--
-- Transformation Notes:
-- ---------------------
-- COBOL Field                    PostgreSQL Column         Type Mapping
-- ------------------------------ ------------------------- ---------------------------
-- TRAN-ID (PIC X(16))           trans_id                  VARCHAR(16) PRIMARY KEY
-- TRAN-TYPE-CD (PIC X(02))      trans_type_cd             VARCHAR(2) NOT NULL
-- TRAN-CAT-CD (PIC 9(04))       trans_cat_cd              INTEGER NOT NULL
-- TRAN-SOURCE (PIC X(10))       trans_source              VARCHAR(10)
-- TRAN-DESC (PIC X(100))        trans_desc                VARCHAR(100)
-- TRAN-AMT (PIC S9(09)V99)      trans_amt                 NUMERIC(11,2) NOT NULL
-- TRAN-MERCHANT-ID (PIC 9(09))  trans_merchant_id         BIGINT
-- TRAN-MERCHANT-NAME (PIC X(50)) trans_merchant_name      VARCHAR(50)
-- TRAN-MERCHANT-CITY (PIC X(50)) trans_merchant_city      VARCHAR(50)
-- TRAN-MERCHANT-ZIP (PIC X(10))  trans_merchant_zip       VARCHAR(10)
-- TRAN-CARD-NUM (PIC X(16))     trans_card_num            VARCHAR(16) NOT NULL FK
-- TRAN-ORIG-TS (PIC X(26))      trans_orig_ts             TIMESTAMP NOT NULL
-- TRAN-PROC-TS (PIC X(26))      trans_proc_ts             TIMESTAMP DEFAULT NOW()
-- FILLER (PIC X(20))            [not mapped]              N/A
-- [audit fields added]          created_at                TIMESTAMP DEFAULT NOW()
-- [JPA optimistic locking]      version                   INTEGER DEFAULT 0
--
-- Business Rules Preserved from COBOL:
-- -------------------------------------
-- 1. Transaction ID is unique primary key (VSAM primary key)
-- 2. Transaction MUST be associated with valid card (foreign key constraint)
-- 3. Transaction amount maintains exact 2 decimal places precision 
--    (COBOL COMP-3 PIC S9(09)V99 → PostgreSQL NUMERIC(11,2))
-- 4. Both original timestamp and processing timestamp are captured
-- 5. Transaction type and category codes reference lookup tables
-- 6. Daily transaction table has identical structure for batch processing
-- 7. Foreign key ON DELETE RESTRICT prevents card deletion with transactions
-- 8. Foreign key ON UPDATE CASCADE maintains referential integrity
--
-- Performance Considerations:
-- ---------------------------
-- - Indexes on trans_card_num for card-based queries (account statements)
-- - Indexes on trans_orig_ts for date range queries (monthly billing)
-- - Indexes on trans_type_cd and trans_cat_cd for reporting
-- - Index on trans_merchant_id for merchant analysis
-- - Index on trans_proc_ts for batch processing queries
--
-- Migration from VSAM:
-- --------------------
-- - VSAM KSDS primary key (TRAN-ID) → PostgreSQL PRIMARY KEY
-- - VSAM alternate index on TRAN-CARD-NUM → PostgreSQL INDEX
-- - VSAM alternate index on TRAN-ORIG-TS → PostgreSQL INDEX
-- - VSAM record-level locking → PostgreSQL row-level locking + JPA @Version
--
-- =====================================================================

-- =====================================================================
-- Table: transaction
-- =====================================================================
-- Description: Main transaction table storing all card transactions
--              Converted from VSAM TRANSACT dataset (CVTRA05Y.cpy)
-- Volume: Expected 1,000,000+ records with high INSERT/SELECT frequency
-- =====================================================================

CREATE TABLE transaction (
    -- Primary key (VSAM primary key)
    trans_id VARCHAR(16) PRIMARY KEY,
    
    -- Foreign key to card table (mandatory relationship)
    trans_card_num VARCHAR(16) NOT NULL,
    
    -- Transaction classification
    trans_type_cd VARCHAR(2) NOT NULL,
    trans_cat_cd INTEGER NOT NULL,
    trans_source VARCHAR(10),
    trans_desc VARCHAR(100),
    
    -- Transaction amount (COBOL COMP-3 precision: PIC S9(09)V99)
    -- NUMERIC(11,2) ensures exact 2 decimal places for financial calculations
    trans_amt NUMERIC(11,2) NOT NULL,
    
    -- Merchant information
    trans_merchant_id BIGINT,
    trans_merchant_name VARCHAR(50),
    trans_merchant_city VARCHAR(50),
    trans_merchant_zip VARCHAR(10),
    
    -- Timestamps (COBOL PIC X(26) format)
    trans_orig_ts TIMESTAMP NOT NULL,
    trans_proc_ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit columns (added for cloud-native architecture)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Optimistic locking for JPA (Spring Data)
    version INTEGER DEFAULT 0,
    
    -- Foreign key constraint to card table
    CONSTRAINT fk_transaction_card FOREIGN KEY (trans_card_num)
        REFERENCES card(card_num)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

-- =====================================================================
-- Table: daily_transaction
-- =====================================================================
-- Description: Daily transaction staging table for batch processing
--              Converted from VSAM DALYTRAN dataset (CVTRA06Y.cpy)
--              Used by Spring Batch jobs (CBTRN01C, CBTRN02C, CBTRN03C)
-- Volume: Expected 10,000 records per day, purged after processing
-- =====================================================================

CREATE TABLE daily_transaction (
    -- Primary key (VSAM primary key)
    trans_id VARCHAR(16) PRIMARY KEY,
    
    -- Foreign key to card table (mandatory relationship)
    trans_card_num VARCHAR(16) NOT NULL,
    
    -- Transaction classification
    trans_type_cd VARCHAR(2) NOT NULL,
    trans_cat_cd INTEGER NOT NULL,
    trans_source VARCHAR(10),
    trans_desc VARCHAR(100),
    
    -- Transaction amount (COBOL COMP-3 precision: PIC S9(09)V99)
    -- NUMERIC(11,2) ensures exact 2 decimal places for financial calculations
    trans_amt NUMERIC(11,2) NOT NULL,
    
    -- Merchant information
    trans_merchant_id BIGINT,
    trans_merchant_name VARCHAR(50),
    trans_merchant_city VARCHAR(50),
    trans_merchant_zip VARCHAR(10),
    
    -- Timestamps (COBOL PIC X(26) format)
    trans_orig_ts TIMESTAMP NOT NULL,
    trans_proc_ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit columns (added for cloud-native architecture)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Optimistic locking for JPA (Spring Data)
    version INTEGER DEFAULT 0,
    
    -- Foreign key constraint to card table
    CONSTRAINT fk_daily_transaction_card FOREIGN KEY (trans_card_num)
        REFERENCES card(card_num)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

-- =====================================================================
-- Indexes for transaction table
-- =====================================================================
-- Purpose: Support high-volume transaction queries with sub-200ms response
--          Replicate VSAM alternate index performance characteristics
-- =====================================================================

-- Index on card number (most frequent query pattern for account statements)
CREATE INDEX idx_transaction_card ON transaction(trans_card_num);

-- Index on original transaction date (date range queries for billing cycles)
CREATE INDEX idx_transaction_date ON transaction(trans_orig_ts);

-- Index on transaction type (reporting and categorization queries)
CREATE INDEX idx_transaction_type ON transaction(trans_type_cd);

-- Index on transaction category (reporting and analysis queries)
CREATE INDEX idx_transaction_category ON transaction(trans_cat_cd);

-- Index on merchant ID (merchant analysis and reconciliation)
CREATE INDEX idx_transaction_merchant ON transaction(trans_merchant_id);

-- Index on processing timestamp (batch processing and audit queries)
CREATE INDEX idx_transaction_proc_date ON transaction(trans_proc_ts);

-- Composite index for common query pattern: card + date range
CREATE INDEX idx_transaction_card_date ON transaction(trans_card_num, trans_orig_ts);

-- =====================================================================
-- Indexes for daily_transaction table
-- =====================================================================
-- Purpose: Support batch processing queries (lighter index set for staging)
-- =====================================================================

-- Index on card number (batch posting by card)
CREATE INDEX idx_daily_transaction_card ON daily_transaction(trans_card_num);

-- Index on original transaction date (batch processing by date)
CREATE INDEX idx_daily_transaction_date ON daily_transaction(trans_orig_ts);

-- Index on transaction type (batch categorization)
CREATE INDEX idx_daily_transaction_type ON daily_transaction(trans_type_cd);

-- =====================================================================
-- Table and Column Comments (PostgreSQL Documentation)
-- =====================================================================

COMMENT ON TABLE transaction IS 
'Transaction table converted from VSAM TRANSACT dataset (CVTRA05Y.cpy). ' ||
'Stores all card transactions with foreign key relationship to card table. ' ||
'Expected volume: 1M+ records with high INSERT/SELECT frequency. ' ||
'Performance target: sub-200ms query response for card statement generation.';

COMMENT ON TABLE daily_transaction IS 
'Daily transaction staging table for batch processing (CVTRA06Y.cpy). ' ||
'Used by Spring Batch jobs CBTRN01C (validation), CBTRN02C (posting), CBTRN03C (summarization). ' ||
'Expected volume: 10K records per day, purged after successful batch processing. ' ||
'Processing window: 01:00-04:00 daily (3-hour batch cycle).';

COMMENT ON COLUMN transaction.trans_id IS 
'Unique transaction identifier (COBOL PIC X(16)). ' ||
'Primary key replicated from VSAM KSDS primary key.';

COMMENT ON COLUMN transaction.trans_card_num IS 
'Card number foreign key (COBOL PIC X(16)). ' ||
'References card.card_num with ON DELETE RESTRICT to prevent orphaned transactions.';

COMMENT ON COLUMN transaction.trans_amt IS 
'Transaction amount (COBOL PIC S9(09)V99 COMP-3). ' ||
'NUMERIC(11,2) maintains exact 2 decimal precision for financial calculations. ' ||
'Supports signed amounts: negative for credits/refunds, positive for debits/purchases.';

COMMENT ON COLUMN transaction.trans_orig_ts IS 
'Original transaction timestamp (COBOL PIC X(26)). ' ||
'Captured at point of sale or authorization request. ' ||
'Used for billing cycle determination and dispute resolution.';

COMMENT ON COLUMN transaction.trans_proc_ts IS 
'Processing timestamp (COBOL PIC X(26)). ' ||
'Captured when transaction is posted to account. ' ||
'Defaults to CURRENT_TIMESTAMP if not explicitly set.';

COMMENT ON COLUMN transaction.version IS 
'Optimistic locking version for JPA (Spring Data). ' ||
'Incremented on each update to prevent lost update anomalies. ' ||
'Replaces VSAM RBA-based optimistic locking from mainframe.';

COMMENT ON COLUMN daily_transaction.trans_id IS 
'Unique transaction identifier for staging (COBOL PIC X(16)). ' ||
'Primary key for batch processing, moved to transaction table after validation.';

-- =====================================================================
-- End of Migration V4
-- =====================================================================
