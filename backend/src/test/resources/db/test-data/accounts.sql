-- Test data for AccountRepositoryTest
-- This script creates sample account records matching the 300-byte COBOL ACCOUNT-RECORD structure
-- from CVACT01Y.cpy copybook for VSAM ACCTDAT KSDS file migration testing

-- Clean up existing test data (accounts must be deleted before customers due to foreign key)
DELETE FROM account WHERE account_id >= 10000000001 AND account_id <= 10000000010;

-- Insert test account records
-- Note: customer_id values must match existing customers from customers.sql
INSERT INTO account (
  account_id, customer_id, active_status,
  current_balance, credit_limit, cash_credit_limit,
  open_date, expiration_date, reissue_date,
  current_cycle_credit, current_cycle_debit,
  address_zip, account_group_id
) VALUES
  -- Test Account 1: Customer 1 (John Smith) - Active account with positive balance
  -- Used in primary test cases (testFindById_ValidAccountId, testUpdate_ExistingAccount)
  (10000000001, 1000000001, 'Y',
   1250.75, 5000.00, 1000.00,
   '2020-01-15', '2027-01-15', '2024-12-01',
   500.00, 1750.75,
   '75001', 'GROUP001'),
  
  -- Test Account 2: Customer 1 (John Smith) - Active account with negative balance (credit)
  -- Used for findByCustomerId tests (multiple accounts per customer) and balance range queries
  (10000000002, 1000000001, 'Y',
   -150.50, 10000.00, 2000.00,
   '2019-06-10', '2028-06-10', '2023-05-15',
   2000.00, 2150.50,
   '75001', 'GROUP002'),
  
  -- Test Account 3: Customer 2 (Jane Doe) - Active account with high balance
  -- Used for findByCreditLimitGreaterThan and aggregation tests
  (10000000003, 1000000002, 'Y',
   3500.00, 15000.00, 3000.00,
   '2021-03-20', '2026-03-20', '2025-02-15',
   1500.00, 5000.00,
   '90001', 'GROUP001');
