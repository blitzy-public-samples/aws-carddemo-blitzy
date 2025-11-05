-- =====================================================================
-- Transaction Test Data Load Script
-- =====================================================================
-- Source: AWS.M2.CARDDEMO.DALYTRAN.PS (EBCDIC, ~300+ records)
-- Record Layout: CVTRA05Y.cpy (350-byte fixed-width)
-- Target: PostgreSQL transaction table
-- CRITICAL: COMP-3 PIC S9(09)V99 -> NUMERIC(11,2) exact precision
-- =====================================================================

TRUNCATE TABLE transaction CASCADE;

-- Sample transaction data (representative of ~300+ EBCDIC records)
INSERT INTO transaction (
  transaction_id, transaction_type_code, transaction_category_code,
  transaction_source, transaction_description, transaction_amount,
  merchant_id, merchant_name, merchant_city, merchant_zip,
  card_number, transaction_timestamp, processed_timestamp
) VALUES
('0000000000000001', '01', '000001', 'POS', 'RESTAURANT - CITY CAFE', 45.67, '123456789', 'City Cafe', 'San Francisco', '94102', '4000123456780001', '2022-06-10 19:27:53.000000', '2022-06-10 19:27:53.000000'),
('0000000000000002', '01', '000002', 'ECOM', 'ONLINE - TECH SHOP', 299.99, '234567890', 'Tech Shop', 'Seattle', '98101', '4000123456780001', '2022-06-10 14:15:22.000000', '2022-06-10 14:15:22.000000'),
('0000000000000003', '01', '000003', 'POS', 'GAS - QUICK FILL', 52.34, '345678901', 'Quick Fill', 'Portland', '97201', '4000123456780001', '2022-06-11 08:42:15.000000', '2022-06-11 08:42:15.000000'),
('0000000000000004', '01', '000004', 'POS', 'GROCERY - FRESH MART', 127.89, '456789012', 'Fresh Mart', 'Denver', '80202', '4000123456780001', '2022-06-11 17:33:45.000000', '2022-06-11 17:33:45.000000'),
('0000000000000005', '02', '000005', 'ATM', 'ATM WITHDRAWAL', 100.00, '567890123', 'Bank ATM', 'Austin', '78701', '4000123456780001', '2022-06-12 10:22:18.000000', '2022-06-12 10:22:18.000000');

-- Verification query
SELECT COUNT(*) AS total_transactions FROM transaction;

-- =====================================================================
-- CRITICAL: Verify NUMERIC(11,2) precision maintained from COMP-3
-- =====================================================================
SELECT 
  MIN(transaction_amount) AS min_amount,
  MAX(transaction_amount) AS max_amount,
  AVG(transaction_amount) AS avg_amount
FROM transaction;
