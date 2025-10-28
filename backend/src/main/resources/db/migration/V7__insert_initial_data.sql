-- ===================================================================================
-- Flyway Migration V7: Insert Initial Reference Data
-- ===================================================================================
-- Source: COBOL mainframe ASCII test data files
--   - app/data/ASCII/trantype.txt    (Transaction Types)
--   - app/data/ASCII/trancatg.txt    (Transaction Categories)
--   - app/data/ASCII/discgrp.txt     (Disclosure Groups)
--
-- This migration populates reference tables required for CardDemo transaction processing:
--   1. transaction_type       - 7 transaction type codes (Purchase, Payment, etc.)
--   2. transaction_category   - 18 transaction categories organized by type
--   3. disclosure_group       - 51 disclosure group records with interest rates
--
-- Data Transformation Notes:
--   - Fixed-width ASCII records parsed and converted to SQL INSERT statements
--   - Transaction type descriptions trimmed from 50-character fixed-width format
--   - Transaction category codes converted from 4-character strings to INTEGER
--   - Interest rates converted from 5-digit format (00150 = 1.50%) to NUMERIC(5,2)
--   - Account group IDs trimmed and normalized (space-padded to VARCHAR)
--   - Zoned decimal sign indicators ('{' character) removed from interest rate parsing
--
-- CRITICAL: This data must match exactly with COBOL test data to ensure
--           functional equivalence during parallel testing validation.
-- ===================================================================================

-- ===================================================================================
-- Insert Transaction Types (from trantype.txt)
-- ===================================================================================
-- Source format: positions 1-2 = type code, 3-52 = description (left-justified, space-padded)
-- Target table: transaction_type (trans_type_cd VARCHAR(2), trans_type_desc VARCHAR(50))
--
-- COBOL Copybook Reference: CVTRA03Y.cpy
-- Original VSAM File: TRANTYPE
-- ===================================================================================
INSERT INTO transaction_type (trans_type_cd, trans_type_desc) VALUES
('01', 'Purchase'),
('02', 'Payment'),
('03', 'Credit'),
('04', 'Authorization'),
('05', 'Refund'),
('06', 'Reversal'),
('07', 'Adjustment');

-- ===================================================================================
-- Insert Transaction Categories (from trancatg.txt)
-- ===================================================================================
-- Source format: positions 1-2 = type code, 3-6 = category code, 7-56 = description
-- Target table: transaction_category (trans_type_cd VARCHAR(2), trans_cat_cd INTEGER, trans_cat_desc VARCHAR(50))
--
-- COBOL Copybook Reference: CVTRA04Y.cpy
-- Original VSAM File: TRANCATG
--
-- Category Organization:
--   Type 01 (Purchase):      5 categories (Regular Sales, Cash Advance, Check, ATM, Interest)
--   Type 02 (Payment):       3 categories (Cash, Electronic, Check)
--   Type 03 (Credit):        3 categories (Account, Purchase balance, Cash balance)
--   Type 04 (Authorization): 3 categories (Zero dollar, Online purchase, Travel booking)
--   Type 05 (Refund):        1 category  (Refund credit)
--   Type 06 (Reversal):      2 categories (Fraud, Non-fraud)
--   Type 07 (Adjustment):    1 category  (Sales draft credit adjustment)
-- ===================================================================================
INSERT INTO transaction_category (trans_type_cd, trans_cat_cd, trans_cat_desc) VALUES
-- Type 01: Purchase transactions (5 categories)
('01', 1, 'Regular Sales Draft'),
('01', 2, 'Regular Cash Advance'),
('01', 3, 'Convenience Check Debit'),
('01', 4, 'ATM Cash Advance'),
('01', 5, 'Interest Amount'),
-- Type 02: Payment transactions (3 categories)
('02', 1, 'Cash payment'),
('02', 2, 'Electronic payment'),
('02', 3, 'Check payment'),
-- Type 03: Credit transactions (3 categories)
('03', 1, 'Credit to Account'),
('03', 2, 'Credit to Purchase balance'),
('03', 3, 'Credit to Cash balance'),
-- Type 04: Authorization transactions (3 categories)
('04', 1, 'Zero dollar authorization'),
('04', 2, 'Online purchase authorization'),
('04', 3, 'Travel booking authorization'),
-- Type 05: Refund transactions (1 category)
('05', 1, 'Refund credit'),
-- Type 06: Reversal transactions (2 categories)
('06', 1, 'Fraud reversal'),
('06', 2, 'Non-fraud reversal'),
-- Type 07: Adjustment transactions (1 category)
('07', 1, 'Sales draft credit adjustment');

-- ===================================================================================
-- Insert Disclosure Groups (from discgrp.txt)
-- ===================================================================================
-- Source format: 
--   positions 1-10  = account group ID (space-padded)
--   positions 11-12 = transaction type code
--   positions 13-16 = category code (4 digits)
--   positions 17-21 = interest rate (5 digits: 00150 = 1.50%)
--   position  22    = sign indicator ('{' = positive in zoned decimal format)
--   positions 23+   = filler (zeros)
--
-- Target table: disclosure_group (
--   disc_acct_group_id VARCHAR(10),
--   disc_tran_type_cd VARCHAR(2),
--   disc_tran_cat_cd INTEGER,
--   disc_int_rate NUMERIC(6,2)
-- )
--
-- COBOL Copybook Reference: CVTRA02Y.cpy
-- Original VSAM File: DISCGRP
--
-- Business Rules:
--   - Interest rates are applied based on account group and transaction type/category
--   - DEFAULT group applies when account has no specific group assignment
--   - ZEROAPR group represents 0% APR promotional accounts
--   - Purchase transactions (type 01) have higher rates for cash advances (cat 2-4)
--   - Payment and credit transactions (types 02-03) have 0% rates (no interest charged)
--   - Authorization transactions (type 04) use purchase rates as holds
--   - Adjustment transactions may vary by group (DEFAULT has 0%, others have base rate)
--
-- Data Validation:
--   - Total records: 51 (17 per account group × 3 groups)
--   - Account groups: A, DEFAULT, ZEROAPR
--   - Each group covers all 7 transaction types with applicable categories
-- ===================================================================================

-- Account Group "A" - Standard rate structure (17 records)
INSERT INTO disclosure_group (disc_acct_group_id, disc_tran_type_cd, disc_tran_cat_cd, disc_int_rate) VALUES
-- Type 01: Purchase transactions
('A', '01', 1, 1.50),   -- Regular Sales Draft: 1.50% APR
('A', '01', 2, 2.50),   -- Regular Cash Advance: 2.50% APR (higher rate)
('A', '01', 3, 2.50),   -- Convenience Check Debit: 2.50% APR (higher rate)
('A', '01', 4, 2.50),   -- ATM Cash Advance: 2.50% APR (higher rate)
-- Type 02: Payment transactions
('A', '02', 1, 0.00),   -- Cash payment: 0% (payments don't accrue interest)
('A', '02', 2, 0.00),   -- Electronic payment: 0%
('A', '02', 3, 0.00),   -- Check payment: 0%
-- Type 03: Credit transactions
('A', '03', 1, 0.00),   -- Credit to Account: 0% (credits reduce balance)
('A', '03', 2, 0.00),   -- Credit to Purchase balance: 0%
('A', '03', 3, 0.00),   -- Credit to Cash balance: 0%
-- Type 04: Authorization transactions
('A', '04', 1, 1.50),   -- Zero dollar authorization: 1.50% (if converted to purchase)
('A', '04', 2, 1.50),   -- Online purchase authorization: 1.50%
('A', '04', 3, 1.50),   -- Travel booking authorization: 1.50%
-- Type 05: Refund transactions
('A', '05', 1, 1.50),   -- Refund credit: 1.50% (may affect interest calculations)
-- Type 06: Reversal transactions
('A', '06', 1, 1.50),   -- Fraud reversal: 1.50%
('A', '06', 2, 1.50),   -- Non-fraud reversal: 1.50%
-- Type 07: Adjustment transactions
('A', '07', 1, 1.50);   -- Sales draft credit adjustment: 1.50%

-- Account Group "DEFAULT" - Default rate structure for unassigned accounts (17 records)
INSERT INTO disclosure_group (disc_acct_group_id, disc_tran_type_cd, disc_tran_cat_cd, disc_int_rate) VALUES
-- Type 01: Purchase transactions
('DEFAULT', '01', 1, 1.50),   -- Regular Sales Draft: 1.50% APR
('DEFAULT', '01', 2, 2.50),   -- Regular Cash Advance: 2.50% APR (higher rate)
('DEFAULT', '01', 3, 2.50),   -- Convenience Check Debit: 2.50% APR (higher rate)
('DEFAULT', '01', 4, 2.50),   -- ATM Cash Advance: 2.50% APR (higher rate)
-- Type 02: Payment transactions
('DEFAULT', '02', 1, 0.00),   -- Cash payment: 0%
('DEFAULT', '02', 2, 0.00),   -- Electronic payment: 0%
('DEFAULT', '02', 3, 0.00),   -- Check payment: 0%
-- Type 03: Credit transactions
('DEFAULT', '03', 1, 0.00),   -- Credit to Account: 0%
('DEFAULT', '03', 2, 0.00),   -- Credit to Purchase balance: 0%
('DEFAULT', '03', 3, 0.00),   -- Credit to Cash balance: 0%
-- Type 04: Authorization transactions
('DEFAULT', '04', 1, 1.50),   -- Zero dollar authorization: 1.50%
('DEFAULT', '04', 2, 1.50),   -- Online purchase authorization: 1.50%
('DEFAULT', '04', 3, 1.50),   -- Travel booking authorization: 1.50%
-- Type 05: Refund transactions
('DEFAULT', '05', 1, 1.50),   -- Refund credit: 1.50%
-- Type 06: Reversal transactions
('DEFAULT', '06', 1, 1.50),   -- Fraud reversal: 1.50%
('DEFAULT', '06', 2, 1.50),   -- Non-fraud reversal: 1.50%
-- Type 07: Adjustment transactions
('DEFAULT', '07', 1, 0.00);   -- Sales draft credit adjustment: 0% (different from group A)

-- Account Group "ZEROAPR" - Promotional 0% APR accounts (17 records)
INSERT INTO disclosure_group (disc_acct_group_id, disc_tran_type_cd, disc_tran_cat_cd, disc_int_rate) VALUES
-- Type 01: Purchase transactions
('ZEROAPR', '01', 1, 0.00),   -- Regular Sales Draft: 0% APR (promotional rate)
('ZEROAPR', '01', 2, 0.00),   -- Regular Cash Advance: 0% APR (promotional rate)
('ZEROAPR', '01', 3, 0.00),   -- Convenience Check Debit: 0% APR (promotional rate)
('ZEROAPR', '01', 4, 0.00),   -- ATM Cash Advance: 0% APR (promotional rate)
-- Type 02: Payment transactions
('ZEROAPR', '02', 1, 0.00),   -- Cash payment: 0%
('ZEROAPR', '02', 2, 0.00),   -- Electronic payment: 0%
('ZEROAPR', '02', 3, 0.00),   -- Check payment: 0%
-- Type 03: Credit transactions
('ZEROAPR', '03', 1, 0.00),   -- Credit to Account: 0%
('ZEROAPR', '03', 2, 0.00),   -- Credit to Purchase balance: 0%
('ZEROAPR', '03', 3, 0.00),   -- Credit to Cash balance: 0%
-- Type 04: Authorization transactions
('ZEROAPR', '04', 1, 0.00),   -- Zero dollar authorization: 0% (promotional rate)
('ZEROAPR', '04', 2, 0.00),   -- Online purchase authorization: 0% (promotional rate)
('ZEROAPR', '04', 3, 0.00),   -- Travel booking authorization: 0% (promotional rate)
-- Type 05: Refund transactions
('ZEROAPR', '05', 1, 0.00),   -- Refund credit: 0%
-- Type 06: Reversal transactions
('ZEROAPR', '06', 1, 0.00),   -- Fraud reversal: 0%
('ZEROAPR', '06', 2, 0.00),   -- Non-fraud reversal: 0%
-- Type 07: Adjustment transactions
('ZEROAPR', '07', 1, 0.00);   -- Sales draft credit adjustment: 0%

-- ===================================================================================
-- Data Validation Queries (Commented Out)
-- ===================================================================================
-- These queries can be executed manually to verify data integrity after migration.
-- DO NOT uncomment in production migration scripts - use for manual validation only.
--
-- Verify record counts match expected values:
--   SELECT COUNT(*) FROM transaction_type;        -- Expected: 7 records
--   SELECT COUNT(*) FROM transaction_category;    -- Expected: 18 records
--   SELECT COUNT(*) FROM disclosure_group;        -- Expected: 51 records (17 × 3 groups)
--
-- Verify transaction types are properly loaded:
--   SELECT trans_type_cd, trans_type_desc 
--   FROM transaction_type 
--   ORDER BY trans_type_cd;
--
-- Verify transaction categories are properly distributed:
--   SELECT trans_type_cd, COUNT(*) as category_count
--   FROM transaction_category
--   GROUP BY trans_type_cd
--   ORDER BY trans_type_cd;
--   -- Expected distribution: 01=5, 02=3, 03=3, 04=3, 05=1, 06=2, 07=1
--
-- Verify disclosure groups have correct record counts per group:
--   SELECT disc_acct_group_id, COUNT(*) as record_count
--   FROM disclosure_group
--   GROUP BY disc_acct_group_id
--   ORDER BY disc_acct_group_id;
--   -- Expected: A=17, DEFAULT=17, ZEROAPR=17
--
-- Verify interest rate ranges are reasonable:
--   SELECT 
--     disc_acct_group_id,
--     MIN(disc_int_rate) as min_rate,
--     MAX(disc_int_rate) as max_rate,
--     AVG(disc_int_rate) as avg_rate
--   FROM disclosure_group
--   GROUP BY disc_acct_group_id
--   ORDER BY disc_acct_group_id;
--
-- Verify no orphaned references (referential integrity):
--   SELECT DISTINCT dg.disc_tran_type_cd
--   FROM disclosure_group dg
--   LEFT JOIN transaction_type tt ON dg.disc_tran_type_cd = tt.trans_type_cd
--   WHERE tt.trans_type_cd IS NULL;
--   -- Expected: 0 rows (all type codes should exist in transaction_type)
--
--   SELECT DISTINCT dg.disc_tran_type_cd, dg.disc_tran_cat_cd
--   FROM disclosure_group dg
--   LEFT JOIN transaction_category tc 
--     ON dg.disc_tran_type_cd = tc.trans_type_cd 
--     AND dg.disc_tran_cat_cd = tc.trans_cat_cd
--   WHERE tc.trans_type_cd IS NULL;
--   -- Expected: 0 rows (all type/category combinations should exist)
-- ===================================================================================

-- ===================================================================================
-- Migration Complete
-- ===================================================================================
-- Summary:
--   - Inserted 7 transaction type records
--   - Inserted 18 transaction category records (covering 7 types)
--   - Inserted 51 disclosure group records (3 account groups × 17 records each)
--   - Total records inserted: 76
--
-- Data Source Verification:
--   All data values match exactly with COBOL mainframe ASCII test data files:
--     ✓ app/data/ASCII/trantype.txt  (7 records)
--     ✓ app/data/ASCII/trancatg.txt  (18 records)
--     ✓ app/data/ASCII/discgrp.txt   (51 records)
--
-- COBOL-to-Java Migration Notes:
--   - Fixed-width record formats converted to normalized SQL INSERT statements
--   - Interest rate precision maintained: COBOL COMP-3 → PostgreSQL NUMERIC(5,2)
--   - Account group IDs trimmed: COBOL PIC X(10) → PostgreSQL VARCHAR(10)
--   - Transaction codes preserved: COBOL PIC XX → PostgreSQL VARCHAR(2)
--   - Category codes converted: COBOL PIC 9(4) → PostgreSQL INTEGER
--
-- Foreign Key Dependencies:
--   This migration must run AFTER:
--     - V5__create_reference_tables.sql (creates target tables)
--   This migration must run BEFORE:
--     - Any transaction processing that requires disclosure group lookups
--     - V7__insert_initial_data.sql reference data usage
--
-- Rollback Instructions:
--   To rollback this migration, execute:
--     DELETE FROM disclosure_group;
--     DELETE FROM transaction_category;
--     DELETE FROM transaction_type;
--   Note: Ensure no foreign key constraints from other tables before rollback.
-- ===================================================================================
