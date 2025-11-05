-- Transaction Category Reference Data for Test Suite
-- Migrated from COBOL copybook CVTRA04Y.cpy (TRAN-CAT-RECORD structure)
-- This test data supports TransactionCategoryRepositoryTest validation

-- Delete existing data to ensure clean test state
DELETE FROM transaction_category;

-- Insert transaction category reference data matching COBOL VSAM test data
-- TRAN-TYPE-CD PIC X(02) → transaction_type_code CHAR(2)
-- TRAN-CAT-CD PIC 9(04) → Combined with type to form transaction_category_code CHAR(6)
-- TRAN-CAT-TYPE-DESC PIC X(50) → category_description VARCHAR(50)

-- Purchase (01) categories - 5 total
INSERT INTO transaction_category (transaction_type_code, transaction_category_code, category_description) VALUES
  ('01', '015010', 'Retail Merchandise'),
  ('01', '015411', 'Grocery Stores'),
  ('01', '015541', 'Service Stations'),
  ('01', '015812', 'Restaurants'),
  ('01', '015999', 'Miscellaneous Retail');

-- Cash Advance (02) categories - 2 total
INSERT INTO transaction_category (transaction_type_code, transaction_category_code, category_description) VALUES
  ('02', '026010', 'ATM Cash Withdrawal'),
  ('02', '026011', 'Cash Advance Fee');

-- Payment (03) categories - 1 total
INSERT INTO transaction_category (transaction_type_code, transaction_category_code, category_description) VALUES
  ('03', '030000', 'Bill Payment');

-- Fee (04) categories - 3 total
INSERT INTO transaction_category (transaction_type_code, transaction_category_code, category_description) VALUES
  ('04', '047010', 'Annual Fee'),
  ('04', '047020', 'Late Payment Fee'),
  ('04', '047030', 'Over Limit Fee');

-- Interest (05) categories - 4 total
INSERT INTO transaction_category (transaction_type_code, transaction_category_code, category_description) VALUES
  ('05', '058010', 'Purchase Interest'),
  ('05', '058020', 'Cash Advance Interest'),
  ('05', '058030', 'Balance Transfer Interest'),
  ('05', '058040', 'Penalty Interest');

-- Total: 15 categories (5 + 2 + 1 + 3 + 4 = 15)
-- This matches the expected count in testFindAll_ReturnsAllCategories()
