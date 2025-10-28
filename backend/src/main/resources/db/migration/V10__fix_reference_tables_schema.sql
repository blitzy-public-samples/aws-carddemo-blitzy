-- Flyway Migration V10: Fix reference table schema issues
--
-- Purpose: 
-- 1. Rename trans_cat_desc to trans_cat_type_desc in transaction_category table
-- 2. Add missing version columns to transaction_category and transaction_type tables
--
-- Root Causes:
-- 1. V5 migration created column as trans_cat_desc in transaction_category table,
--    but TransactionCategory.java entity expects trans_cat_type_desc
--    per COBOL copybook CVTRA04Y.cpy field TRAN-CAT-TYPE-DESC PIC X(50).
--
-- 2. V5 migration omitted version column from transaction_category and transaction_type tables,
--    but their respective Java entities require version for @Version optimistic locking
--    (replicates VSAM RBA check per Agent Action Plan Section 0.7.2).
--
-- These mismatches cause Hibernate schema validation errors:
-- - "Schema-validation: missing column [trans_cat_type_desc] in table [transaction_category]"
-- - "Schema-validation: missing column [version] in table [transaction_category]"
-- - "Schema-validation: missing column [version] in table [transaction_type]"
--
-- Impact: All 45 repository tests fail with ApplicationContext loading error.
--
-- References:
-- - TransactionCategory.java line 72: @Column(name = "trans_cat_type_desc", length = 50, nullable = false)
-- - TransactionCategory.java line 96: @Column(name = "version", nullable = false)
-- - TransactionType.java @Version annotation: @Column(name = "version", nullable = false)
-- - COBOL copybook: app/cpy/CVTRA04Y.cpy field TRAN-CAT-TYPE-DESC PIC X(50)
-- - Agent Action Plan Section 0.4.9: Entity field mapping requirements
-- - Agent Action Plan Section 0.7.2: Optimistic locking replicates VSAM RBA check
--

-- Fix 1: Rename column in transaction_category table to match entity field name
ALTER TABLE transaction_category RENAME COLUMN trans_cat_desc TO trans_cat_type_desc;

-- Fix 2: Add missing version column to transaction_category table for JPA optimistic locking
ALTER TABLE transaction_category ADD COLUMN version INTEGER DEFAULT 0 NOT NULL;

-- Fix 3: Add missing version column to transaction_type table for JPA optimistic locking
-- Note: disclosure_group, transaction_category_balance, and user_security already have version columns in V5
ALTER TABLE transaction_type ADD COLUMN version INTEGER DEFAULT 0 NOT NULL;
