-- Test data for AccountXrefRepositoryTest
-- This script creates sample account cross-reference records for customer-account relationship testing
-- Replaces VSAM XREF file and CXACAIX alternate index functionality from mainframe

-- Clean up existing test data
-- Customer ID: PIC 9(09) → NUMERIC(9,0) → max 999999999 (9 digits)
DELETE FROM account_xref WHERE customer_id >= 100000001 AND customer_id <= 100000002;

-- Insert test account cross-reference records
-- Note: Both customer_id and account_id must match existing records from customers.sql and accounts.sql
-- Customer ID: PIC 9(09) → 9 digits, Account ID: PIC 9(11) → 11 digits
-- Using spec-compliant accounts 10000000021, 10000000022, 10000000023 linked to 9-digit customer IDs
INSERT INTO account_xref (
  customer_id, account_id, created_date, updated_date
) VALUES
  -- Customer 100000001 (John Smith) → Account 10000000021
  -- Used in primary test cases (testFindById_ValidCompositeKey)
  (100000001, 10000000021, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Customer 100000001 (John Smith) → Account 10000000022
  -- Used for findByCustomerId tests (multiple accounts per customer)
  (100000001, 10000000022, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  
  -- Customer 100000002 (Jane Doe) → Account 10000000023
  -- Used for findByCustomerId and findByAccountId tests
  (100000002, 10000000023, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
