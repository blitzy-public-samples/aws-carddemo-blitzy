-- =====================================================================
-- Flyway Migration V1: Create Account Table
-- =====================================================================
-- Source: COBOL copybook CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300)
-- Original VSAM Dataset: ACCTFILE (KSDS - Key-Sequenced Data Set)
-- Estimated Record Count: 50,000 accounts
--
-- Purpose: 
-- Convert mainframe VSAM account master file to PostgreSQL relational table
-- This migration preserves all business logic, data precision, and access patterns
-- from the original COBOL/VSAM implementation.
--
-- COBOL to PostgreSQL Data Type Transformation Rules:
-- =====================================================================
-- COBOL PIC 9(11)              → PostgreSQL BIGINT
--   - Unsigned 11-digit integer, suitable for account identifiers
--   - Range: 0 to 99,999,999,999 (11 digits)
--
-- COBOL PIC X(01)              → PostgreSQL CHAR(1)
--   - Single character field, fixed length
--   - Preserves COBOL character semantics
--
-- COBOL PIC S9(10)V99 COMP-3   → PostgreSQL NUMERIC(12,2)
--   - Signed decimal with 2 decimal places (packed decimal in COBOL)
--   - 10 integer digits + 2 decimal places = NUMERIC(12,2)
--   - Ensures exact precision for financial calculations
--   - No rounding errors (critical for monetary values)
--
-- COBOL PIC X(10)              → PostgreSQL DATE or VARCHAR(10)
--   - Date fields: Convert to DATE type for proper date operations
--   - Format: YYYY-MM-DD (ISO-8601 standard)
--   - Non-date fields: VARCHAR(10) for variable-length strings
--
-- Additional PostgreSQL Fields:
-- =====================================================================
-- created_at    TIMESTAMP      - Audit field for record creation time
-- updated_at    TIMESTAMP      - Audit field for last modification time
-- version       INTEGER        - Optimistic locking version for JPA
--                                (prevents lost updates in concurrent access)
--
-- Business Rules Preserved from COBOL:
-- =====================================================================
-- 1. Account ID (ACCT-ID) is unique primary key (VSAM primary key)
-- 2. All monetary fields maintain exactly 2 decimal places precision
--    (replicates COBOL COMP-3 arithmetic behavior)
-- 3. Status flag is single character (matches COBOL PIC X(01) semantics)
-- 4. Account open date is mandatory (NOT NULL)
-- 5. Expiration and reissue dates are optional (nullable)
-- 6. Current balance defaults to 0.00 (COBOL INITIALIZE equivalent)
-- 7. Current cycle credit/debit default to 0.00
--
-- Index Strategy:
-- =====================================================================
-- - Primary key index on acct_id (replicates VSAM primary key access)
-- - Index on acct_active_status (frequent filter in queries)
-- - Index on acct_group_id (replicates VSAM alternate index for grouping)
-- These indexes ensure query performance meets or exceeds VSAM access times
-- (target: sub-10ms for primary key lookups)
--
-- Performance Considerations:
-- =====================================================================
-- - B-tree indexes on acct_id provide O(log n) lookup performance
-- - NUMERIC type ensures no precision loss in financial calculations
-- - NOT NULL constraints prevent invalid data states
-- - DEFAULT values reduce application logic complexity
--
-- Migration Notes:
-- =====================================================================
-- - Original FILLER field (178 bytes) is not migrated (unused space)
-- - ACCT-EXPIRAION-DATE typo preserved in comment for traceability
-- - Character fields will be trimmed of trailing spaces during data load
-- - Date fields will be validated to ISO-8601 format during migration
-- =====================================================================

CREATE TABLE account (
    -- Primary Account Identifier
    -- Source: ACCT-ID PIC 9(11)
    -- Business Rule: Unique 11-digit account number
    acct_id BIGINT PRIMARY KEY,
    
    -- Account Status Flag
    -- Source: ACCT-ACTIVE-STATUS PIC X(01)
    -- Valid Values: 'Y' (active), 'N' (inactive), 'C' (closed), 'S' (suspended)
    -- Business Rule: Must always have a status, defaults enforced at application layer
    acct_active_status CHAR(1) NOT NULL,
    
    -- Current Account Balance
    -- Source: ACCT-CURR-BAL PIC S9(10)V99
    -- Business Rule: Running balance updated by transaction posting batch job
    -- Precision: Exactly 2 decimal places (matches COBOL COMP-3 precision)
    acct_curr_bal NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- Credit Limit
    -- Source: ACCT-CREDIT-LIMIT PIC S9(10)V99
    -- Business Rule: Maximum allowed balance, validated during transaction processing
    acct_credit_limit NUMERIC(12,2) NOT NULL,
    
    -- Cash Advance Credit Limit
    -- Source: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
    -- Business Rule: Subset of total credit limit for cash advances
    acct_cash_credit_limit NUMERIC(12,2) NOT NULL,
    
    -- Account Open Date
    -- Source: ACCT-OPEN-DATE PIC X(10)
    -- Business Rule: Mandatory field, set at account creation, immutable
    acct_open_date DATE NOT NULL,
    
    -- Account Expiration Date
    -- Source: ACCT-EXPIRAION-DATE PIC X(10) [note: typo in original copybook]
    -- Business Rule: Optional field, used for card expiration tracking
    acct_expiration_date DATE,
    
    -- Account Reissue Date
    -- Source: ACCT-REISSUE-DATE PIC X(10)
    -- Business Rule: Optional field, tracks when card was last reissued
    acct_reissue_date DATE,
    
    -- Current Cycle Credit Total
    -- Source: ACCT-CURR-CYC-CREDIT PIC S9(10)V99
    -- Business Rule: Accumulated credits in current billing cycle, reset monthly
    acct_curr_cyc_credit NUMERIC(12,2) DEFAULT 0.00,
    
    -- Current Cycle Debit Total
    -- Source: ACCT-CURR-CYC-DEBIT PIC S9(10)V99
    -- Business Rule: Accumulated debits in current billing cycle, reset monthly
    acct_curr_cyc_debit NUMERIC(12,2) DEFAULT 0.00,
    
    -- Billing Address Postal Code
    -- Source: ACCT-ADDR-ZIP PIC X(10)
    -- Business Rule: Optional field for billing address, may be null for legacy accounts
    acct_addr_zip VARCHAR(10),
    
    -- Account Group Identifier
    -- Source: ACCT-GROUP-ID PIC X(10)
    -- Business Rule: Links accounts to disclosure groups for regulatory compliance
    acct_group_id VARCHAR(10),
    
    -- Audit Column: Record Creation Timestamp
    -- Added for Spring Boot/JPA audit trail (not in original COBOL)
    -- Automatically set on INSERT
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit Column: Record Last Update Timestamp
    -- Added for Spring Boot/JPA audit trail (not in original COBOL)
    -- Should be updated on every UPDATE operation (handled by application)
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Optimistic Locking Version
    -- Added for JPA @Version optimistic locking (not in original COBOL)
    -- Prevents lost updates in concurrent access scenarios
    -- Incremented automatically by JPA on each update
    version INTEGER DEFAULT 0
);

-- =====================================================================
-- Index Definitions
-- =====================================================================

-- Index on Account Status
-- Purpose: Optimize queries filtering by account status (common operation)
-- Usage: SELECT * FROM account WHERE acct_active_status = 'Y'
-- Equivalent to: VSAM secondary index on status field
CREATE INDEX idx_account_status ON account(acct_active_status);

-- Index on Account Group
-- Purpose: Optimize queries grouping accounts by group ID
-- Usage: SELECT * FROM account WHERE acct_group_id = 'GROUP001'
-- Equivalent to: VSAM alternate index on group field
CREATE INDEX idx_account_group ON account(acct_group_id);

-- =====================================================================
-- Table and Column Documentation (PostgreSQL Comments)
-- =====================================================================

COMMENT ON TABLE account IS 'Account master table converted from VSAM ACCTFILE dataset (CVACT01Y.cpy). Stores credit card account master data including balances, limits, and lifecycle dates. Original VSAM KSDS with 300-byte fixed-length records containing 50,000 accounts.';

COMMENT ON COLUMN account.acct_id IS 'Primary account identifier (11-digit numeric, COBOL PIC 9(11)). Unique key for account identification. VSAM primary key field.';

COMMENT ON COLUMN account.acct_active_status IS 'Account status flag (COBOL PIC X(01)). Valid values: Y=Active, N=Inactive, C=Closed, S=Suspended. Used for filtering active accounts in queries.';

COMMENT ON COLUMN account.acct_curr_bal IS 'Current account balance (COBOL PIC S9(10)V99 COMP-3). Updated by daily transaction posting batch job. Maintains exact 2 decimal place precision for financial accuracy.';

COMMENT ON COLUMN account.acct_credit_limit IS 'Credit limit amount (COBOL PIC S9(10)V99 COMP-3). Maximum allowed credit balance for the account. Validated during transaction authorization.';

COMMENT ON COLUMN account.acct_cash_credit_limit IS 'Cash advance credit limit (COBOL PIC S9(10)V99 COMP-3). Subset of total credit limit specifically for cash advances. Must be <= acct_credit_limit.';

COMMENT ON COLUMN account.acct_open_date IS 'Account opening date (COBOL PIC X(10) converted to DATE). Immutable field set at account creation. Used for account age calculations.';

COMMENT ON COLUMN account.acct_expiration_date IS 'Account expiration date (COBOL PIC X(10) converted to DATE). Optional field for tracking card expiration. Null for accounts without expiration.';

COMMENT ON COLUMN account.acct_reissue_date IS 'Card reissue date (COBOL PIC X(10) converted to DATE). Optional field tracking when card was last reissued. Updated during card replacement.';

COMMENT ON COLUMN account.acct_curr_cyc_credit IS 'Current billing cycle credits (COBOL PIC S9(10)V99 COMP-3). Accumulated credit transactions in current cycle. Reset to 0.00 at cycle end by batch job.';

COMMENT ON COLUMN account.acct_curr_cyc_debit IS 'Current billing cycle debits (COBOL PIC S9(10)V99 COMP-3). Accumulated debit transactions in current cycle. Reset to 0.00 at cycle end by batch job.';

COMMENT ON COLUMN account.acct_addr_zip IS 'Billing address postal code (COBOL PIC X(10)). Optional field for billing address. May contain US ZIP codes or international postal codes.';

COMMENT ON COLUMN account.acct_group_id IS 'Account group identifier (COBOL PIC X(10)). Links to disclosure group for regulatory compliance. Used for statement generation and fee calculations.';

COMMENT ON COLUMN account.created_at IS 'Record creation timestamp (audit field). Automatically set on INSERT. Not present in original COBOL structure.';

COMMENT ON COLUMN account.updated_at IS 'Record last update timestamp (audit field). Should be updated on every UPDATE. Not present in original COBOL structure.';

COMMENT ON COLUMN account.version IS 'Optimistic locking version for JPA (Spring Data). Incremented on each update to prevent lost updates in concurrent access. Implements @Version annotation behavior.';

-- =====================================================================
-- Migration Complete
-- =====================================================================
-- This migration creates the foundational account table structure.
-- Subsequent migrations will:
-- - V2: Create card table with foreign key to account
-- - V3: Create customer table
-- - V4: Create transaction tables
-- - V5: Create reference data tables
-- - V6: Create additional indexes and constraints
-- - V7: Load initial reference data
--
-- Data Migration: 
-- Execute infrastructure/scripts/migrate-data.sh to load existing
-- VSAM account records into this PostgreSQL table after structure creation.
-- =====================================================================
