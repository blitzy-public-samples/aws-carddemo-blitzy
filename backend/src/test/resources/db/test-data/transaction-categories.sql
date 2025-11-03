-- Transaction Category Reference Data for Test Suite
-- Migrated from COBOL copybook CVTRA04Y.cpy (TRAN-CAT-RECORD structure)
-- This test data supports TransactionCategoryRepositoryTest validation

-- Delete existing data to ensure clean test state
DELETE FROM transaction_category;

-- Insert transaction category reference data matching COBOL VSAM test data
-- TRAN-TYPE-CD PIC X(02) → type_code VARCHAR(2)
-- TRAN-CAT-CD PIC 9(04) → category_code INTEGER
-- TRAN-CAT-TYPE-DESC PIC X(50) → category_description VARCHAR(50)

-- Purchase (01) categories - 5 total
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
  ('01', 5010, 'Retail Merchandise'),
  ('01', 5411, 'Grocery Stores'),
  ('01', 5541, 'Service Stations'),
  ('01', 5812, 'Restaurants'),
  ('01', 5999, 'Miscellaneous Retail');

-- Cash Advance (02) categories - 2 total
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
  ('02', 6010, 'ATM Cash Withdrawal'),
  ('02', 6011, 'Cash Advance Fee');

-- Payment (03) categories - 1 total
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
  ('03', 0, 'Bill Payment');

-- Fee (04) categories - 3 total
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
  ('04', 7010, 'Annual Fee'),
  ('04', 7020, 'Late Payment Fee'),
  ('04', 7030, 'Over Limit Fee');

-- Interest (05) categories - 4 total
INSERT INTO transaction_category (type_code, category_code, category_description) VALUES
  ('05', 8010, 'Purchase Interest'),
  ('05', 8020, 'Cash Advance Interest'),
  ('05', 8030, 'Balance Transfer Interest'),
  ('05', 8040, 'Penalty Interest');

-- Total: 15 categories (5 + 2 + 1 + 3 + 4 = 15)
-- This matches the expected count in testFindAll_ReturnsAllCategories()
