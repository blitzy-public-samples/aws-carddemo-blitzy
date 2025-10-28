-- =====================================================================
-- Migration: V9__fix_daily_transaction_column_names.sql
-- Description: Rename daily_transaction table columns from trans_* 
--              prefix to dalytran_* prefix to match COBOL copybook
--              CVTRA06Y.cpy and DailyTransaction JPA entity
-- Date: 2025-10-27
-- Author: Blitzy Platform - CardDemo Modernization
-- =====================================================================
--
-- Root Cause:
-- -----------
-- V4 migration script incorrectly created daily_transaction table
-- with trans_* column names (copied from transaction table structure),
-- but the COBOL copybook CVTRA06Y.cpy uses DALYTRAN-* field names
-- and the DailyTransaction JPA entity expects dalytran_* columns.
--
-- This mismatch causes Hibernate schema validation error:
-- "Schema-validation: missing column [dalytran_id] in table [daily_transaction]"
--
-- COBOL Source Fields (CVTRA06Y.cpy):
-- ------------------------------------
-- DALYTRAN-ID, DALYTRAN-TYPE-CD, DALYTRAN-CAT-CD, DALYTRAN-SOURCE,
-- DALYTRAN-DESC, DALYTRAN-AMT, DALYTRAN-MERCHANT-ID, DALYTRAN-MERCHANT-NAME,
-- DALYTRAN-MERCHANT-CITY, DALYTRAN-MERCHANT-ZIP, DALYTRAN-CARD-NUM,
-- DALYTRAN-ORIG-TS, DALYTRAN-PROC-TS
--
-- All fields use DALYTRAN- prefix, not TRAN- prefix.
--
-- Impact:
-- -------
-- - All repository tests fail with ApplicationContext loading error
-- - DailyTransaction entity cannot be mapped to database table
-- - Batch processing jobs cannot access daily_transaction staging table
--
-- Solution:
-- ---------
-- Rename all columns from trans_* to dalytran_* prefix to match
-- COBOL copybook and JPA entity expectations.
-- =====================================================================

-- Drop existing indexes before renaming columns
DROP INDEX IF EXISTS idx_daily_transaction_card;
DROP INDEX IF EXISTS idx_daily_transaction_date;
DROP INDEX IF EXISTS idx_daily_transaction_type;

-- Drop existing foreign key constraint before renaming columns
ALTER TABLE daily_transaction DROP CONSTRAINT IF EXISTS fk_daily_transaction_card;

-- Rename all columns from trans_* to dalytran_* prefix
ALTER TABLE daily_transaction RENAME COLUMN trans_id TO dalytran_id;
ALTER TABLE daily_transaction RENAME COLUMN trans_card_num TO dalytran_card_num;
ALTER TABLE daily_transaction RENAME COLUMN trans_type_cd TO dalytran_type_cd;
ALTER TABLE daily_transaction RENAME COLUMN trans_cat_cd TO dalytran_cat_cd;
ALTER TABLE daily_transaction RENAME COLUMN trans_source TO dalytran_source;
ALTER TABLE daily_transaction RENAME COLUMN trans_desc TO dalytran_desc;
ALTER TABLE daily_transaction RENAME COLUMN trans_amt TO dalytran_amt;
ALTER TABLE daily_transaction RENAME COLUMN trans_merchant_id TO dalytran_merchant_id;
ALTER TABLE daily_transaction RENAME COLUMN trans_merchant_name TO dalytran_merchant_name;
ALTER TABLE daily_transaction RENAME COLUMN trans_merchant_city TO dalytran_merchant_city;
ALTER TABLE daily_transaction RENAME COLUMN trans_merchant_zip TO dalytran_merchant_zip;
ALTER TABLE daily_transaction RENAME COLUMN trans_orig_ts TO dalytran_orig_ts;
ALTER TABLE daily_transaction RENAME COLUMN trans_proc_ts TO dalytran_proc_ts;

-- Fix column type for dalytran_merchant_id
-- COBOL PIC 9(09) must be VARCHAR(9) to preserve leading zeros (e.g., "000123456")
-- V4 migration incorrectly created this as BIGINT, which cannot preserve leading zeros
-- Entity expects VARCHAR(9) per DailyTransaction.java line 326-327
ALTER TABLE daily_transaction ALTER COLUMN dalytran_merchant_id TYPE VARCHAR(9);

-- Recreate foreign key constraint with new column name
ALTER TABLE daily_transaction 
    ADD CONSTRAINT fk_daily_transaction_card 
    FOREIGN KEY (dalytran_card_num) 
    REFERENCES card(card_num) 
    ON DELETE RESTRICT 
    ON UPDATE CASCADE;

-- Recreate indexes with new column names
CREATE INDEX idx_daily_transaction_card ON daily_transaction(dalytran_card_num);
CREATE INDEX idx_daily_transaction_date ON daily_transaction(dalytran_orig_ts);
CREATE INDEX idx_daily_transaction_type ON daily_transaction(dalytran_type_cd);

-- Update table comment to clarify correct column naming
COMMENT ON TABLE daily_transaction IS 
'Daily transaction staging table for batch processing (CVTRA06Y.cpy). Uses DALYTRAN-* prefix matching COBOL copybook field names. Used by Spring Batch jobs CBTRN01C (validation), CBTRN02C (posting), CBTRN03C (summarization). Expected volume: 10K records per day, purged after successful batch processing. Processing window: 01:00-04:00 daily (3-hour batch cycle).';

-- Update column comments with correct naming
COMMENT ON COLUMN daily_transaction.dalytran_id IS 
'Unique transaction identifier for staging (COBOL PIC X(16) DALYTRAN-ID). Primary key for batch processing, moved to transaction table after validation.';

COMMENT ON COLUMN daily_transaction.dalytran_card_num IS 
'Card number foreign key (COBOL PIC X(16) DALYTRAN-CARD-NUM). References card.card_num with ON DELETE RESTRICT to prevent orphaned transactions.';

COMMENT ON COLUMN daily_transaction.dalytran_amt IS 
'Transaction amount (COBOL PIC S9(09)V99 COMP-3 DALYTRAN-AMT). NUMERIC(11,2) maintains exact 2 decimal precision for financial calculations during batch processing.';

COMMENT ON COLUMN daily_transaction.dalytran_orig_ts IS 
'Original transaction timestamp (COBOL PIC X(26) DALYTRAN-ORIG-TS). Captured at point of sale or authorization request during batch file processing.';

COMMENT ON COLUMN daily_transaction.dalytran_proc_ts IS 
'Processing timestamp (COBOL PIC X(26) DALYTRAN-PROC-TS). Captured when transaction is staged in daily_transaction table. Defaults to CURRENT_TIMESTAMP if not explicitly set during batch load.';

-- =====================================================================
-- Validation Query
-- =====================================================================
-- Run this query to verify all columns renamed correctly:
-- 
-- SELECT column_name, data_type, character_maximum_length, is_nullable
-- FROM information_schema.columns
-- WHERE table_name = 'daily_transaction'
-- ORDER BY ordinal_position;
-- 
-- Expected columns: dalytran_id, dalytran_card_num, dalytran_type_cd,
-- dalytran_cat_cd, dalytran_source, dalytran_desc, dalytran_amt,
-- dalytran_merchant_id, dalytran_merchant_name, dalytran_merchant_city,
-- dalytran_merchant_zip, dalytran_orig_ts, dalytran_proc_ts,
-- created_at, version
-- =====================================================================

-- =====================================================================
-- End of Migration V9
-- =====================================================================
