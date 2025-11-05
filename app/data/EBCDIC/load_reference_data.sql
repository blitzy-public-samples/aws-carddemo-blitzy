-- ============================================================================
-- Reference Data Load Script - Generated from EBCDIC Files
-- ============================================================================
-- Source Files:
--   - AWS.M2.CARDDEMO.TRANTYPE.PS (420 bytes, 7 transaction type records)
--   - AWS.M2.CARDDEMO.TRANCATG.PS (1080 bytes, 18 category records)
--   - AWS.M2.CARDDEMO.DISCGRP.PS (2550 bytes, 51 discount group records)
--
-- Encoding: EBCDIC CP037 → UTF-8
-- Target: PostgreSQL 15+
-- Integration: Complements V9__load_reference_data.sql per Section 0.4
--
-- Record Structure:
--   TRANTYPE: type_code(2) + type_description(50) + filler(8) = 60 bytes
--   TRANCATG: category_code(6) + category_description(50) + filler(4) = 60 bytes
--   DISCGRP: composite_key(16) + discount_pct(5) + sign(1) + filler(28) = 50 bytes
-- ============================================================================

\echo 'Loading reference data from EBCDIC files...'

-- ============================================================================
-- Transaction Types (7 records)
-- ============================================================================
\echo '  - Loading transaction types...'

TRUNCATE TABLE transaction_type CASCADE;

INSERT INTO transaction_type (type_code, type_description) VALUES
  ('01', 'Purchase'),
  ('02', 'Payment'),
  ('03', 'Credit'),
  ('04', 'Authorization'),
  ('05', 'Refund'),
  ('06', 'Reversal'),
  ('07', 'Adjustment');

\echo '     ✓ Loaded 7 transaction types'

-- ============================================================================
-- Transaction Categories (18 records)
-- ============================================================================
\echo '  - Loading transaction categories...'

TRUNCATE TABLE transaction_category CASCADE;

INSERT INTO transaction_category (category_code, category_description) VALUES
  ('010001', 'Regular Sales Draft'),
  ('010002', 'Regular Cash Advance'),
  ('010003', 'Convenience Check Debit'),
  ('010004', 'ATM Cash Advance'),
  ('010005', 'Interest Amount'),
  ('020001', 'Cash payment'),
  ('020002', 'Electronic payment'),
  ('020003', 'Check payment'),
  ('030001', 'Credit to Account'),
  ('030002', 'Credit to Purchase balance'),
  ('030003', 'Credit to Cash balance'),
  ('040001', 'Zero dollar authorization'),
  ('040002', 'Online purchase authorization'),
  ('040003', 'Travel booking authorization'),
  ('050001', 'Refund credit'),
  ('060001', 'Fraud reversal'),
  ('060002', 'Non-fraud reversal'),
  ('070001', 'Sales draft credit adjustment');

\echo '     ✓ Loaded 18 transaction categories'

-- ============================================================================
-- Discount Groups (51 records: A×17 + DEFAULT×17 + ZEROAPR×17)
-- ============================================================================
\echo '  - Loading discount groups...'

TRUNCATE TABLE discount_group CASCADE;

INSERT INTO discount_group (
  group_id,
  account_group_id,
  transaction_type_code,
  transaction_category_code,
  discount_percentage
) VALUES
  ('A', '00000000001', '00', '000100', 1.50),
  ('A', '00000000001', '00', '000200', 2.50),
  ('A', '00000000001', '00', '000300', 2.50),
  ('A', '00000000001', '00', '000400', 2.50),
  ('A', '00000000002', '00', '000100', 0.00),
  ('A', '00000000002', '00', '000200', 0.00),
  ('A', '00000000002', '00', '000300', 0.00),
  ('A', '00000000003', '00', '000100', 0.00),
  ('A', '00000000003', '00', '000200', 0.00),
  ('A', '00000000003', '00', '000300', 0.00),
  ('A', '00000000004', '00', '000100', 1.50),
  ('A', '00000000004', '00', '000200', 1.50),
  ('A', '00000000004', '00', '000300', 1.50),
  ('A', '00000000005', '00', '000100', 1.50),
  ('A', '00000000006', '00', '000100', 1.50),
  ('A', '00000000006', '00', '000200', 1.50),
  ('A', '00000000007', '00', '000100', 1.50),
  ('DEFAULT', '00000000001', '01', '010001', 1.50),
  ('DEFAULT', '00000000001', '01', '010002', 2.50),
  ('DEFAULT', '00000000001', '01', '010003', 2.50),
  ('DEFAULT', '00000000001', '01', '010004', 2.50),
  ('DEFAULT', '00000000001', '02', '020001', 0.00),
  ('DEFAULT', '00000000001', '02', '020002', 0.00),
  ('DEFAULT', '00000000001', '02', '020003', 0.00),
  ('DEFAULT', '00000000001', '03', '030001', 0.00),
  ('DEFAULT', '00000000001', '03', '030002', 0.00),
  ('DEFAULT', '00000000001', '03', '030003', 0.00),
  ('DEFAULT', '00000000001', '04', '040001', 1.50),
  ('DEFAULT', '00000000001', '04', '040002', 1.50),
  ('DEFAULT', '00000000001', '04', '040003', 1.50),
  ('DEFAULT', '00000000001', '05', '050001', 1.50),
  ('DEFAULT', '00000000001', '06', '060001', 1.50),
  ('DEFAULT', '00000000001', '06', '060002', 1.50),
  ('DEFAULT', '00000000001', '07', '070001', 0.00),
  ('ZEROAPR', '00000000001', '01', '010001', 0.00),
  ('ZEROAPR', '00000000001', '01', '010002', 0.00),
  ('ZEROAPR', '00000000001', '01', '010003', 0.00),
  ('ZEROAPR', '00000000001', '01', '010004', 0.00),
  ('ZEROAPR', '00000000001', '02', '020001', 0.00),
  ('ZEROAPR', '00000000001', '02', '020002', 0.00),
  ('ZEROAPR', '00000000001', '02', '020003', 0.00),
  ('ZEROAPR', '00000000001', '03', '030001', 0.00),
  ('ZEROAPR', '00000000001', '03', '030002', 0.00),
  ('ZEROAPR', '00000000001', '03', '030003', 0.00),
  ('ZEROAPR', '00000000001', '04', '040001', 0.00),
  ('ZEROAPR', '00000000001', '04', '040002', 0.00),
  ('ZEROAPR', '00000000001', '04', '040003', 0.00),
  ('ZEROAPR', '00000000001', '05', '050001', 0.00),
  ('ZEROAPR', '00000000001', '06', '060001', 0.00),
  ('ZEROAPR', '00000000001', '06', '060002', 0.00),
  ('ZEROAPR', '00000000001', '07', '070001', 0.00);

\echo '     ✓ Loaded 51 discount group records'

-- ============================================================================
-- Verification Queries
-- ============================================================================
\echo ''
\echo 'Verifying reference data counts...'

SELECT
  'transaction_type' as table_name,
  COUNT(*) as record_count,
  7 as expected_count
FROM transaction_type
UNION ALL
SELECT
  'transaction_category',
  COUNT(*),
  18
FROM transaction_category
UNION ALL
SELECT
  'discount_group',
  COUNT(*),
  51
FROM discount_group
ORDER BY table_name;

\echo ''
\echo '✓ Reference data load complete'

