-- ================================================================
-- Flyway Migration V8: Create Foreign Key Constraints
-- ================================================================
-- Purpose: Establish referential integrity through foreign key constraints
--          matching VSAM XREF cross-reference relationships
--
-- Migration: COBOL-to-Java CardDemo Application
-- Source:    VSAM XREF files and application-level validation logic
-- Target:    PostgreSQL database-level referential integrity
--
-- This migration transforms COBOL application-level cross-reference
-- validation into PostgreSQL foreign key constraints, ensuring 100%
-- referential integrity per Section 0.1 implicit requirements.
--
-- Deletion Policies:
--   - RESTRICT: Preserves records (matches COBOL validation before delete)
--   - CASCADE:  Automatic cleanup (matches COBOL dependent deletion logic)
-- ================================================================

-- ================================================================
-- 1. CUSTOMER-ACCOUNT FOREIGN KEY
-- ================================================================
-- Source: CVACT01Y.cpy implied customer-account relationship
-- Business Rule: Account cannot exist without valid customer
-- Deletion Policy: RESTRICT prevents customer deletion if accounts exist
--                  (matches COBOL validation logic in account programs)
-- ================================================================
ALTER TABLE account
  ADD CONSTRAINT fk_account_customer
  FOREIGN KEY (customer_id)
  REFERENCES customer(customer_id)
  ON DELETE RESTRICT
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_account_customer ON account IS 
'Enforces customer-account relationship from VSAM CUSTDAT-ACCTDAT. RESTRICT prevents customer deletion if accounts exist, matching COBOL validation logic. CASCADE propagates customer_id updates.';

-- ================================================================
-- 2. ACCOUNT-CARD FOREIGN KEY
-- ================================================================
-- Source: CVACT02Y.cpy CARD-ACCT-ID field
-- Business Rule: Card is dependent on account (card belongs to account)
-- Deletion Policy: CASCADE automatically deletes cards when account deleted
--                  (matches COBOL dependent relationship pattern)
-- ================================================================
ALTER TABLE card
  ADD CONSTRAINT fk_card_account
  FOREIGN KEY (account_id)
  REFERENCES account(account_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_card_account ON card IS 
'Enforces account-card relationship from VSAM ACCTDAT-CARDDAT. CASCADE deletes cards when account is deleted (dependent relationship), matching COBOL cleanup logic.';

-- ================================================================
-- NOTE: CARD-TRANSACTION FOREIGN KEY
-- ================================================================
-- The fk_transaction_card constraint is already defined in 
-- V4__create_transaction_table.sql (line 218) and should not be 
-- duplicated here. Transaction history preservation is enforced 
-- by the existing constraint.
-- ================================================================

-- ================================================================
-- 3. CARD CROSS-REFERENCE FOREIGN KEYS
-- ================================================================
-- Source: CVACT03Y.cpy CARD-XREF-RECORD structure
--         Fields: XREF-CARD-NUM, XREF-CUST-ID, XREF-ACCT-ID
-- Business Rule: Cross-reference entries are orphaned without parent entities
-- Deletion Policy: CASCADE removes orphaned cross-references automatically
--                  (matches COBOL XREF file maintenance logic)
-- ================================================================

-- 3a. Card Cross-Reference to Card
ALTER TABLE card_xref
  ADD CONSTRAINT fk_cardxref_card
  FOREIGN KEY (card_number)
  REFERENCES card(card_number)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_cardxref_card ON card_xref IS 
'Enforces card_xref-card relationship from VSAM XREF file. CASCADE removes orphaned cross-references when card deleted, matching COBOL XREF maintenance.';

-- 3b. Card Cross-Reference to Customer
ALTER TABLE card_xref
  ADD CONSTRAINT fk_cardxref_customer
  FOREIGN KEY (customer_id)
  REFERENCES customer(customer_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_cardxref_customer ON card_xref IS 
'Enforces card_xref-customer relationship from VSAM XREF file. CASCADE removes orphaned cross-references when customer deleted.';

-- 4c. Card Cross-Reference to Account
ALTER TABLE card_xref
  ADD CONSTRAINT fk_cardxref_account
  FOREIGN KEY (account_id)
  REFERENCES account(account_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_cardxref_account ON card_xref IS 
'Enforces card_xref-account relationship from VSAM XREF file. CASCADE removes orphaned cross-references when account deleted.';

-- ================================================================
-- 4. ACCOUNT CROSS-REFERENCE FOREIGN KEYS
-- ================================================================
-- Source: VSAM CXACAIX alternate index file (customer-account cross-reference)
-- Business Rule: Account cross-references require valid parent entities
-- Deletion Policy: CASCADE cleanup matching COBOL XREF maintenance patterns
-- ================================================================

-- 4a. Account Cross-Reference to Customer
ALTER TABLE account_xref
  ADD CONSTRAINT fk_acctxref_customer
  FOREIGN KEY (customer_id)
  REFERENCES customer(customer_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_acctxref_customer ON account_xref IS 
'Enforces account_xref-customer relationship from VSAM CXACAIX file. CASCADE removes orphaned cross-references when customer deleted.';

-- 4b. Account Cross-Reference to Account
ALTER TABLE account_xref
  ADD CONSTRAINT fk_acctxref_account
  FOREIGN KEY (account_id)
  REFERENCES account(account_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_acctxref_account ON account_xref IS 
'Enforces account_xref-account relationship from VSAM CXACAIX file. CASCADE removes orphaned cross-references when account deleted.';

-- ================================================================
-- 5. TRANSACTION CATEGORY BALANCE FOREIGN KEY
-- ================================================================
-- Source: CVTRA01Y.cpy transaction-account relationship
-- Business Rule: Category balances are aggregate data tied to account
-- Deletion Policy: CASCADE removes aggregated data when account deleted
--                  (matches COBOL batch aggregation cleanup logic)
-- ================================================================
ALTER TABLE transaction_category_balance
  ADD CONSTRAINT fk_trancatbal_account
  FOREIGN KEY (account_id)
  REFERENCES account(account_id)
  ON DELETE CASCADE
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_trancatbal_account ON transaction_category_balance IS 
'Enforces transaction_category_balance-account relationship. CASCADE removes aggregate data when account deleted, matching COBOL batch cleanup.';

-- ================================================================
-- 7. TRANSACTION TYPE FOREIGN KEYS
-- ================================================================
-- Source: CVTRA05Y.cpy transaction type and category code fields
-- Business Rule: Transactions must reference valid type and category codes
-- Deletion Policy: RESTRICT preserves transaction history integrity
--                  (reference data should not be deleted if in use)
-- ================================================================

-- 7a. Transaction to Transaction Type
ALTER TABLE transaction
  ADD CONSTRAINT fk_transaction_type
  FOREIGN KEY (transaction_type_code)
  REFERENCES transaction_type(type_code)
  ON DELETE RESTRICT
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_transaction_type ON transaction IS 
'Enforces transaction-transaction_type relationship. RESTRICT prevents deletion of transaction types in use, ensuring referential data integrity.';

-- 7b. Transaction to Transaction Category
ALTER TABLE transaction
  ADD CONSTRAINT fk_transaction_category
  FOREIGN KEY (transaction_category_code)
  REFERENCES transaction_category(category_code)
  ON DELETE RESTRICT
  ON UPDATE CASCADE;

COMMENT ON CONSTRAINT fk_transaction_category ON transaction IS 
'Enforces transaction-transaction_category relationship. RESTRICT prevents deletion of categories in use, ensuring referential data integrity.';

-- ================================================================
-- VALIDATION QUERIES (for testing after migration)
-- ================================================================
-- Uncomment these queries to validate foreign key constraints:
--
-- -- List all foreign key constraints
-- SELECT
--     tc.table_name,
--     tc.constraint_name,
--     tc.constraint_type,
--     kcu.column_name,
--     ccu.table_name AS foreign_table_name,
--     ccu.column_name AS foreign_column_name,
--     rc.delete_rule,
--     rc.update_rule
-- FROM information_schema.table_constraints AS tc
-- JOIN information_schema.key_column_usage AS kcu
--   ON tc.constraint_name = kcu.constraint_name
--   AND tc.table_schema = kcu.table_schema
-- JOIN information_schema.constraint_column_usage AS ccu
--   ON ccu.constraint_name = tc.constraint_name
--   AND ccu.table_schema = tc.table_schema
-- JOIN information_schema.referential_constraints AS rc
--   ON rc.constraint_name = tc.constraint_name
--   AND rc.constraint_schema = tc.table_schema
-- WHERE tc.constraint_type = 'FOREIGN KEY'
--   AND tc.table_schema = 'public'
-- ORDER BY tc.table_name, tc.constraint_name;
--
-- -- Verify constraint counts (should have 11 foreign key constraints)
-- SELECT COUNT(*) AS fk_constraint_count
-- FROM information_schema.table_constraints
-- WHERE constraint_type = 'FOREIGN KEY'
--   AND table_schema = 'public';

-- ================================================================
-- MIGRATION VALIDATION CHECKLIST
-- ================================================================
-- [ ] All 11 foreign key constraints created successfully
-- [ ] CASCADE constraints match COBOL dependent deletion patterns
-- [ ] RESTRICT constraints match COBOL validation-before-delete logic
-- [ ] ON UPDATE CASCADE enables parent key modifications
-- [ ] Cross-reference table constraints maintain XREF file semantics
-- [ ] Transaction history preservation via RESTRICT on transaction FKs
-- [ ] Reference data integrity via RESTRICT on type/category FKs
-- [ ] Comments document business rules and COBOL equivalence
-- [ ] 100% referential integrity established per Section 0.9 requirements
-- ================================================================

-- End of V8__create_foreign_keys.sql
