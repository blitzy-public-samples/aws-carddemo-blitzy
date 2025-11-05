-- Transaction Type Reference Data for Test Suite
-- Migrated from COBOL copybook CVTRA03Y.cpy (TRAN-TYPE-RECORD structure)
-- This test data supports TransactionTypeRepositoryTest validation

-- Delete existing data to ensure clean test state
DELETE FROM transaction_type;

-- Insert transaction type reference data matching COBOL VSAM test data
-- TRAN-TYPE PIC X(02) → transaction_type_code CHAR(2)
-- TRAN-TYPE-DESC PIC X(50) → type_description VARCHAR(50)

INSERT INTO transaction_type (transaction_type_code, type_description) VALUES
  ('01', 'Purchase'),
  ('02', 'Cash Advance'),
  ('03', 'Payment'),
  ('04', 'Fee'),
  ('05', 'Interest Charge');
