-- =====================================================================================
-- Flyway Migration V11: Create Account Group Table
-- =====================================================================================
-- Description: Creates the account_group reference data table for interest rate
--              management and account group classification.
--
-- Purpose: Support interest calculation batch jobs (CBACT04C InterestCalculationJob)
--          with differentiated interest rates based on account group, transaction
--          type, and transaction category per Section 0.5.
--
-- Source: AccountGroup.java entity (transformed from COBOL copybook CVTRA02Y.cpy)
-- COBOL Structure: DIS-GROUP-RECORD with composite key DIS-GROUP-KEY
--
-- Dependencies:
--   - Reference data table for interest rate lookup
--   - Loaded via V9__load_reference_data.sql
--
-- Author: CardDemo Development Team
-- Date: 2025-11-05
-- Version: 1.0
-- =====================================================================================

-- =====================================================================================
-- Table: account_group
-- =====================================================================================
-- Purpose: Reference data table mapping account groups to interest rates based on
--          transaction type and category combinations
-- Supports: Interest calculation, account management, batch processing
-- =====================================================================================

CREATE TABLE account_group (
  -- =============================================================
  -- Composite Primary Key (Part 1/3)
  -- =============================================================
  -- Account group identifier
  -- Maps COBOL field: DIS-ACCT-GROUP-ID PIC X(10)
  -- Maximum length: 10 characters
  -- Part of composite primary key with transaction_type_code and transaction_category_code
  account_group_id VARCHAR(10) NOT NULL,
  
  -- =============================================================
  -- Composite Primary Key (Part 2/3)
  -- =============================================================
  -- Transaction type code
  -- Maps COBOL field: DIS-TRAN-TYPE-CD PIC X(02)
  -- Maximum length: 2 characters
  -- References TransactionType entity via this code field
  transaction_type_code VARCHAR(2) NOT NULL,
  
  -- =============================================================
  -- Composite Primary Key (Part 3/3)
  -- =============================================================
  -- Transaction category code
  -- Format: 6-character string where first 2 characters match transaction type code
  -- Maximum length: 6 characters
  -- References TransactionCategory entity via this code field
  transaction_category_code VARCHAR(6) NOT NULL,
  
  -- =============================================================
  -- Interest Rate (CRITICAL PRECISION)
  -- =============================================================
  -- Interest rate applicable to this account group and transaction type/category combination
  -- Precision and scale match COBOL PIC S9(04)V99:
  -- - Precision: 6 (4 integer digits + 2 decimal digits)
  -- - Scale: 2 (2 decimal places)
  -- - Rounding: HALF_UP (matches COBOL COMP-3 rounding semantics)
  --
  -- Per Section 0.2 and 0.9 requirements for COBOL COMP-3 equivalence
  -- All arithmetic operations must explicitly set scale and rounding mode
  interest_rate NUMERIC(6, 2) NOT NULL,
  
  -- =============================================================
  -- Constraints
  -- =============================================================
  -- Composite primary key constraint
  CONSTRAINT pk_account_group PRIMARY KEY (
    account_group_id,
    transaction_type_code,
    transaction_category_code
  )
);

-- =====================================================================================
-- Indexes for Performance Optimization
-- =====================================================================================

-- Index on account_group_id for filtering by account group
-- Supports: SELECT * FROM account_group WHERE account_group_id = ?
-- Used by: Interest calculation queries, account group lookups
-- Note: Renamed from idx_account_group_id to avoid conflict with index on account table
CREATE INDEX idx_acctgrp_group_id 
ON account_group(account_group_id);

-- Index on transaction_type_code for filtering by transaction type
-- Supports: SELECT * FROM account_group WHERE transaction_type_code = ?
-- Used by: Transaction type specific interest rate queries
CREATE INDEX idx_acctgrp_type_code 
ON account_group(transaction_type_code);

-- =====================================================================================
-- Column Comments for Documentation
-- =====================================================================================

COMMENT ON TABLE account_group IS 
'Reference data table mapping account groups to interest rates based on transaction type 
and category combinations. Used by InterestCalculationJob (CBACT04C) for differentiated 
interest rate application. Transformed from COBOL copybook CVTRA02Y.cpy (DIS-GROUP-RECORD).';

COMMENT ON COLUMN account_group.account_group_id IS 
'Account group identifier. Maps COBOL field DIS-ACCT-GROUP-ID PIC X(10). Part of composite primary key.';

COMMENT ON COLUMN account_group.transaction_type_code IS 
'Transaction type code. Maps COBOL field DIS-TRAN-TYPE-CD PIC X(02). Part of composite primary key. 
References TransactionType entity.';

COMMENT ON COLUMN account_group.transaction_category_code IS 
'Transaction category code. 6-character string format. Part of composite primary key. 
References TransactionCategory entity.';

COMMENT ON COLUMN account_group.interest_rate IS 
'Interest rate for this account group and transaction type/category combination. 
NUMERIC(6,2) preserving COBOL PIC S9(04)V99 precision. Applied with HALF_UP rounding.';

COMMENT ON INDEX idx_acctgrp_group_id IS 
'Index for account group filtering in interest calculation and account management queries.';

COMMENT ON INDEX idx_acctgrp_type_code IS 
'Index for transaction type specific interest rate lookup queries.';

-- =====================================================================================
-- Migration Verification
-- =====================================================================================
-- Expected outcome: account_group table created with 4 columns and 2 indexes
-- Verify with: SELECT count(*) FROM information_schema.columns WHERE table_name = 'account_group';
-- Expected: 4 columns
-- =====================================================================================
