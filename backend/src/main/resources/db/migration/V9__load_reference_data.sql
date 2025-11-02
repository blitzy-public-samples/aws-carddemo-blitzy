-- =====================================================================================
-- Flyway Migration V9: Load Reference Data
-- =====================================================================================
-- Description: Loads reference data from ASCII test data files into PostgreSQL tables
--              - Transaction Types (7 records from trantype.txt)
--              - Transaction Categories (18 records from trancatg.txt)
--              - Discount Groups (51 records from discgrp.txt)
--
-- Source Files: app/data/ASCII/trantype.txt
--               app/data/ASCII/trancatg.txt
--               app/data/ASCII/discgrp.txt
--
-- Migration Strategy: Uses INSERT with ON CONFLICT for idempotency
-- Data Preservation: Maintains exact data layouts from COBOL copybooks
-- Encoding: ASCII source files, no EBCDIC conversion needed
-- =====================================================================================

-- Begin transaction for atomicity
BEGIN;

-- =====================================================================================
-- Section 1: Transaction Types Reference Data
-- =====================================================================================
-- Source: app/data/ASCII/trantype.txt (7 records)
-- Format: [2-digit code][50-char description][8 padding zeros]
-- Target Table: transaction_type (type_code, type_description)
-- =====================================================================================

INSERT INTO transaction_type (type_code, type_description, created_at, updated_at) VALUES
  ('01', 'Purchase', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('02', 'Payment', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('03', 'Credit', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('04', 'Authorization', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('05', 'Refund', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('06', 'Reversal', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('07', 'Adjustment', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (type_code) DO NOTHING;

-- Validation: Verify 7 transaction types inserted
DO $$
DECLARE
  record_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO record_count FROM transaction_type;
  IF record_count < 7 THEN
    RAISE EXCEPTION 'Transaction type validation failed: Expected 7 records, found %', record_count;
  END IF;
  RAISE NOTICE 'Transaction types loaded successfully: % records', record_count;
END $$;

-- =====================================================================================
-- Section 2: Transaction Categories Reference Data
-- =====================================================================================
-- Source: app/data/ASCII/trancatg.txt (18 records)
-- Format: [6-digit category code][50-char description][4 padding zeros]
-- Target Table: transaction_category (category_code, category_description)
-- Category Code Structure: [2-digit type][4-digit subcategory]
-- =====================================================================================

INSERT INTO transaction_category (category_code, category_description, transaction_type_code, created_at, updated_at) VALUES
  -- Type 01: Purchase categories (5 records)
  ('010001', 'Regular Sales Draft', '01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('010002', 'Regular Cash Advance', '01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('010003', 'Convenience Check Debit', '01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('010004', 'ATM Cash Advance', '01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('010005', 'Interest Amount', '01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 02: Payment categories (3 records)
  ('020001', 'Cash payment', '02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('020002', 'Electronic payment', '02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('020003', 'Check payment', '02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 03: Credit categories (3 records)
  ('030001', 'Credit to Account', '03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('030002', 'Credit to Purchase balance', '03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('030003', 'Credit to Cash balance', '03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 04: Authorization categories (3 records)
  ('040001', 'Zero dollar authorization', '04', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('040002', 'Online purchase authorization', '04', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('040003', 'Travel booking authorization', '04', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 05: Refund categories (1 record)
  ('050001', 'Refund credit', '05', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 06: Reversal categories (2 records)
  ('060001', 'Fraud reversal', '06', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('060002', 'Non-fraud reversal', '06', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Type 07: Adjustment categories (1 record)
  ('070001', 'Sales draft credit adjustment', '07', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (category_code) DO NOTHING;

-- Validation: Verify 18 transaction categories inserted
DO $$
DECLARE
  record_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO record_count FROM transaction_category;
  IF record_count < 18 THEN
    RAISE EXCEPTION 'Transaction category validation failed: Expected 18 records, found %', record_count;
  END IF;
  RAISE NOTICE 'Transaction categories loaded successfully: % records', record_count;
END $$;

-- =====================================================================================
-- Section 3: Discount Group Reference Data
-- =====================================================================================
-- Source: app/data/ASCII/discgrp.txt (51 records)
-- Format: [Group ID 10 chars][Account Group ID 11 digits][Type 2][Category 4][Rate 5][Sign 1][Padding]
-- Target Table: discount_group (group_id, account_group_id, transaction_type_code, 
--                               transaction_category_code, discount_percentage)
-- Groups: A (17 records), DEFAULT (17 records), ZEROAPR (17 records)
-- Rate Format: 5 digits with last 2 as decimal places (e.g., 00150 = 1.50%)
-- Sign: '{' represents positive in COBOL COMP-3 encoding
-- =====================================================================================

INSERT INTO discount_group (group_id, account_group_id, transaction_type_code, transaction_category_code, discount_percentage, created_at, updated_at) VALUES
  -- ==================================================================================
  -- Group A: Premium discount rates (17 records)
  -- ==================================================================================
  ('A', '00000000001', '01', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000001', '01', '0002', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000001', '01', '0003', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000001', '01', '0004', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000002', '02', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000002', '02', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000002', '02', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000003', '03', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000003', '03', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000003', '03', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000004', '04', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000004', '04', '0002', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000004', '04', '0003', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000005', '05', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000006', '06', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000006', '06', '0002', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('A', '00000000007', '07', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- ==================================================================================
  -- Group DEFAULT: Standard discount rates (17 records)
  -- ==================================================================================
  ('DEFAULT', '01', '01', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '01', '01', '0002', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '01', '01', '0003', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '01', '01', '0004', 2.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '02', '02', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '02', '02', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '02', '02', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '03', '03', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '03', '03', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '03', '03', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '04', '04', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '04', '04', '0002', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '04', '04', '0003', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '05', '05', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '06', '06', '0001', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '06', '06', '0002', 1.50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('DEFAULT', '07', '07', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- ==================================================================================
  -- Group ZEROAPR: Zero interest promotional rates (17 records)
  -- ==================================================================================
  ('ZEROAPR', '01', '01', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '01', '01', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '01', '01', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '01', '01', '0004', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '02', '02', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '02', '02', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '02', '02', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '03', '03', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '03', '03', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '03', '03', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '04', '04', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '04', '04', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '04', '04', '0003', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '05', '05', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '06', '06', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '06', '06', '0002', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('ZEROAPR', '07', '07', '0001', 0.00, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (group_id, account_group_id, transaction_type_code, transaction_category_code) DO NOTHING;

-- Validation: Verify 51 discount group records inserted
DO $$
DECLARE
  record_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO record_count FROM discount_group;
  IF record_count < 51 THEN
    RAISE EXCEPTION 'Discount group validation failed: Expected 51 records, found %', record_count;
  END IF;
  RAISE NOTICE 'Discount groups loaded successfully: % records', record_count;
END $$;

-- =====================================================================================
-- Section 4: Final Validation Summary
-- =====================================================================================

DO $$
DECLARE
  type_count INTEGER;
  category_count INTEGER;
  discount_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO type_count FROM transaction_type;
  SELECT COUNT(*) INTO category_count FROM transaction_category;
  SELECT COUNT(*) INTO discount_count FROM discount_group;
  
  RAISE NOTICE '========================================';
  RAISE NOTICE 'Reference Data Load Summary:';
  RAISE NOTICE '========================================';
  RAISE NOTICE 'Transaction Types: % records', type_count;
  RAISE NOTICE 'Transaction Categories: % records', category_count;
  RAISE NOTICE 'Discount Groups: % records', discount_count;
  RAISE NOTICE 'Total Reference Records: % records', (type_count + category_count + discount_count);
  RAISE NOTICE '========================================';
  RAISE NOTICE 'Migration V9 completed successfully';
  RAISE NOTICE '========================================';
END $$;

-- Commit transaction
COMMIT;

-- =====================================================================================
-- End of Migration V9
-- =====================================================================================
