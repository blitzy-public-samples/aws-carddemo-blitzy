-- =====================================================================================
-- Flyway Migration V10: Create Account Balance Table
-- =====================================================================================
-- Description: Creates the account_balance table for maintaining historical and current
--              account balance snapshots, enabling point-in-time balance queries, balance
--              history tracking, and reconciliation reporting.
--
-- Purpose: Support account balance calculation batch jobs (CBACT03C), interest
--          calculation (CBACT04C), and statement generation (CBSTM03A) per Section 0.5.
--
-- Source: AccountBalance.java entity (modern normalized relational design)
-- Note: This is a NEW table for Java implementation, no direct COBOL copybook equivalent.
--       Replaces mainframe balance tracking with normalized relational approach.
--
-- Dependencies:
--   - Requires account table from V2__create_account_table.sql
--   - Foreign key to account(account_id) with CASCADE delete
--
-- Author: CardDemo Development Team
-- Date: 2025-11-05
-- Version: 1.0
-- =====================================================================================

-- =====================================================================================
-- Table: account_balance
-- =====================================================================================
-- Purpose: Store point-in-time balance snapshots for accounts with full audit trail
-- Supports: Balance history, reconciliation, statement generation, interest calculation
-- =====================================================================================

CREATE TABLE account_balance (
  -- =============================================================
  -- Primary Key
  -- =============================================================
  -- Unique identifier for each balance record
  -- Auto-increment identity column
  account_balance_id BIGINT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
  
  -- =============================================================
  -- Foreign Key to Account
  -- =============================================================
  -- 11-digit account identifier linking to parent account
  -- Establishes many-to-one relationship
  -- Source: ACCT-ID PIC 9(11) from COBOL programs
  account_id BIGINT NOT NULL,
  
  -- =============================================================
  -- Balance Classification
  -- =============================================================
  -- Balance type classification: 'C'=Current, 'A'=Available, 'P'=Pending, 'H'=Historical
  -- Single character for compact storage and VSAM file compatibility
  -- Converted to BalanceType enum in Java via BalanceTypeConverter
  -- Migration Note: VARCHAR(1) instead of CHAR(1) to match JPA @Column annotation expectation
  balance_type VARCHAR(1) NOT NULL,
  
  -- =============================================================
  -- Balance Amounts (CRITICAL PRECISION)
  -- =============================================================
  -- All monetary fields: NUMERIC(12,2) matching COBOL PIC S9(10)V99 COMP-3
  -- Precision preserved using BigDecimal with RoundingMode.HALF_UP
  -- Per Section 0.2 and 0.9 requirements for COBOL COMP-3 equivalence
  
  -- The balance value for this record (required)
  balance_amount NUMERIC(12, 2) NOT NULL,
  
  -- Sum of all credit transactions for the period (deposits, refunds, payments)
  credit_amount NUMERIC(12, 2) NOT NULL,
  
  -- Sum of all debit transactions for the period (purchases, fees, charges)
  debit_amount NUMERIC(12, 2) NOT NULL,
  
  -- Balance at start of tracking period (nullable for first record)
  opening_balance NUMERIC(12, 2),
  
  -- Balance at end of tracking period (calculated: opening + credit - debit)
  closing_balance NUMERIC(12, 2),
  
  -- Interest calculated or accrued for the period (nullable if not applicable)
  interest_amount NUMERIC(12, 2),
  
  -- =============================================================
  -- Effective Date
  -- =============================================================
  -- Business date of balance snapshot
  -- For HISTORICAL: end-of-day date
  -- For CURRENT: date of last balance update
  -- Enables point-in-time balance queries
  effective_date DATE NOT NULL,
  
  -- =============================================================
  -- Audit Fields
  -- =============================================================
  -- Record creation timestamp (required for regulatory compliance)
  created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- Record last modification timestamp (nullable, updated on modifications)
  updated_date TIMESTAMP,
  
  -- =============================================================
  -- Constraints
  -- =============================================================
  -- Enforce valid balance type values
  CONSTRAINT chk_balance_type CHECK (balance_type IN ('C', 'A', 'P', 'H')),
  
  -- Foreign key to account table with cascade delete
  CONSTRAINT fk_account_balance_account FOREIGN KEY (account_id)
    REFERENCES account(account_id)
    ON DELETE CASCADE
);

-- =====================================================================================
-- Indexes for Performance Optimization
-- =====================================================================================

-- Composite index for account-specific balance queries with date range
-- Supports: SELECT * FROM account_balance WHERE account_id = ? AND effective_date BETWEEN ? AND ?
-- Used by: Statement generation, balance history queries
CREATE INDEX idx_account_id_effective_date 
ON account_balance(account_id, effective_date);

-- Single index on effective_date for date-range queries across all accounts
-- Supports: SELECT * FROM account_balance WHERE effective_date = ?
-- Used by: Daily balance snapshot queries, batch processing
CREATE INDEX idx_effective_date 
ON account_balance(effective_date);

-- Index on balance_type for filtering by balance category
-- Supports: SELECT * FROM account_balance WHERE balance_type = 'H'
-- Used by: Historical balance queries, reconciliation reports
CREATE INDEX idx_balance_type 
ON account_balance(balance_type);

-- =====================================================================================
-- Column Comments for Documentation
-- =====================================================================================

COMMENT ON TABLE account_balance IS 
'Balance snapshot tracking table for maintaining historical and current account balance records. 
Supports point-in-time queries, audit trail, and reconciliation. Created for normalized relational 
design in Java/Spring Boot migration from COBOL/VSAM.';

COMMENT ON COLUMN account_balance.account_balance_id IS 
'Primary key - Auto-generated unique identifier for each balance record.';

COMMENT ON COLUMN account_balance.account_id IS 
'Foreign key to account table. 11-digit account identifier from COBOL ACCT-ID PIC 9(11).';

COMMENT ON COLUMN account_balance.balance_type IS 
'Balance classification: C=Current posted balance, A=Available spendable balance, 
P=Pending authorization holds, H=Historical end-of-day snapshot.';

COMMENT ON COLUMN account_balance.balance_amount IS 
'Balance value for this record. NUMERIC(12,2) preserving COBOL PIC S9(10)V99 COMP-3 precision.';

COMMENT ON COLUMN account_balance.credit_amount IS 
'Sum of credit transactions (deposits, refunds, payments) for the period. COMP-3 precision preserved.';

COMMENT ON COLUMN account_balance.debit_amount IS 
'Sum of debit transactions (purchases, fees, charges) for the period. COMP-3 precision preserved.';

COMMENT ON COLUMN account_balance.opening_balance IS 
'Balance at start of tracking period. Null for first balance record of an account.';

COMMENT ON COLUMN account_balance.closing_balance IS 
'Balance at end of period: opening + credit - debit. Calculated with BigDecimal precision.';

COMMENT ON COLUMN account_balance.interest_amount IS 
'Interest calculated/accrued for period by CBACT04C InterestCalculationJob. Null if not applicable.';

COMMENT ON COLUMN account_balance.effective_date IS 
'Business date of balance snapshot. For historical records: end-of-day date. For current: last update date.';

COMMENT ON COLUMN account_balance.created_date IS 
'Record creation timestamp for audit trail. Required for regulatory compliance per Section 0.9.';

COMMENT ON COLUMN account_balance.updated_date IS 
'Record last modification timestamp. Updated on corrections or adjustments.';

COMMENT ON INDEX idx_account_id_effective_date IS 
'Composite index for account-specific balance queries with date range filtering.';

COMMENT ON INDEX idx_effective_date IS 
'Date-based index for daily balance snapshot queries and batch processing.';

COMMENT ON INDEX idx_balance_type IS 
'Balance type filtering index for historical balance and reconciliation queries.';

-- =====================================================================================
-- Migration Verification
-- =====================================================================================
-- Expected outcome: account_balance table created with 11 columns and 3 indexes
-- Verify with: SELECT count(*) FROM information_schema.columns WHERE table_name = 'account_balance';
-- Expected: 11 columns
-- =====================================================================================
