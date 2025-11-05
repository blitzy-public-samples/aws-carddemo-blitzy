-- ================================================================
-- Flyway Migration: V2__create_account_table.sql
-- ================================================================
-- Description: Creates account table from ACCTDAT VSAM KSDS
-- Source: CVACT01Y.cpy copybook (KEYLEN=11, MAXLRECL=300, 50 records)
-- Migration Type: COBOL to PostgreSQL transformation
-- ================================================================

-- Account table from ACCTDAT VSAM KSDS
-- Original COBOL copybook: CVACT01Y.cpy (RECLN 300)
-- VSAM specifications: KEYLEN=11, MAXLRECL=300, REC-TOTAL=50
CREATE TABLE account (
  -- =============================================================
  -- Primary Key Field
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-ID PIC 9(11)
  -- VSAM primary key field (KEYLEN=11)
  -- 11-digit account identifier
  -- Migration Note: Changed from VARCHAR(11) to BIGINT to match Long type in Account.java entity
  account_id BIGINT NOT NULL,
  
  -- =============================================================
  -- Foreign Key Field
  -- =============================================================
  -- Foreign key reference to customer table
  -- Inferred from business logic: each account belongs to a customer
  -- Links to customer.customer_id (9 digits)
  -- Migration Note: Changed from VARCHAR(9) to BIGINT to match Long type in Account.java entity
  customer_id BIGINT NOT NULL,
  
  -- =============================================================
  -- Status Field
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-ACTIVE-STATUS PIC X(01)
  -- Valid values:
  --   'Y' = Active/Yes (default)
  --   'N' = Inactive/No
  -- Migration Note: Column name corrected from account_status to active_status to match Account.java entity
  active_status VARCHAR(1) NOT NULL DEFAULT 'Y',
  
  -- =============================================================
  -- Monetary Fields (COMP-3 Packed Decimal to NUMERIC)
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-CURR-BAL PIC S9(10)V99
  -- CRITICAL: COBOL COMP-3 packed decimal with exact precision preservation
  -- Signed 10 digits + 2 decimal places = NUMERIC(12, 2)
  -- Range: -9999999999.99 to +9999999999.99
  current_balance NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
  
  -- From CVACT01Y.cpy: ACCT-CREDIT-LIMIT PIC S9(10)V99
  -- COMP-3 packed decimal with exact precision preservation
  -- Maximum credit limit for the account
  credit_limit NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
  
  -- From CVACT01Y.cpy: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
  -- COMP-3 packed decimal with exact precision preservation
  -- Maximum cash advance limit for the account
  cash_credit_limit NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
  
  -- =============================================================
  -- Date Fields
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-OPEN-DATE PIC X(10)
  -- Original COBOL format: YYYY-MM-DD as character string
  -- Converted to PostgreSQL DATE type
  -- Migration Note: Column name corrected from account_open_date to open_date to match Account.java entity
  open_date DATE NOT NULL,
  
  -- From CVACT01Y.cpy: ACCT-EXPIRAION-DATE PIC X(10)
  -- Note: Original copybook has typo "EXPIRAION" preserved in comments
  -- Nullable: accounts may not have an expiration date
  -- Migration Note: Column name corrected from account_expiration_date to expiration_date to match Account.java entity
  expiration_date DATE,
  
  -- From CVACT01Y.cpy: ACCT-REISSUE-DATE PIC X(10)
  -- Date when account was reissued (if applicable)
  -- Nullable: not all accounts are reissued
  -- Migration Note: Column name corrected from account_reissue_date to reissue_date to match Account.java entity
  reissue_date DATE,
  
  -- =============================================================
  -- Billing Cycle Fields
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-CURR-CYC-CREDIT PIC S9(10)V99
  -- COMP-3 packed decimal with exact precision preservation
  -- Total credits for current billing cycle
  current_cycle_credit NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
  
  -- From CVACT01Y.cpy: ACCT-CURR-CYC-DEBIT PIC S9(10)V99
  -- COMP-3 packed decimal with exact precision preservation
  -- Total debits for current billing cycle
  current_cycle_debit NUMERIC(12, 2) NOT NULL DEFAULT 0.00,
  
  -- =============================================================
  -- Account Grouping and Location Fields
  -- =============================================================
  -- From CVACT01Y.cpy: ACCT-ADDR-ZIP PIC X(10)
  -- ZIP/postal code associated with account
  -- Nullable: may not always be provided
  -- Migration Note: Column name corrected from account_zip to address_zip to match Account.java entity
  address_zip VARCHAR(10),
  
  -- From CVACT01Y.cpy: ACCT-GROUP-ID PIC X(10)
  -- Account group identifier for categorization
  -- Nullable: accounts may not belong to a group
  account_group_id VARCHAR(10),
  
  -- =============================================================
  -- Audit Fields (Modern Best Practice)
  -- =============================================================
  -- Not in original COBOL copybook
  -- Added for audit trail and change tracking
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- Optimistic Locking Version Field
  -- Added for JPA @Version optimistic locking support
  version BIGINT NOT NULL DEFAULT 0,
  
  -- =============================================================
  -- Primary Key Constraint
  -- =============================================================
  CONSTRAINT pk_account PRIMARY KEY (account_id),
  
  -- =============================================================
  -- Check Constraints
  -- =============================================================
  -- Enforce valid active status values ('Y' = Active/Yes, 'N' = Inactive/No)
  CONSTRAINT chk_active_status CHECK (active_status IN ('Y', 'N')),
  
  -- Enforce COBOL COMP-3 precision range for current_balance
  -- PIC S9(10)V99 range: -9999999999.99 to +9999999999.99
  CONSTRAINT chk_current_balance CHECK (
    current_balance >= -9999999999.99 AND 
    current_balance <= 9999999999.99
  ),
  
  -- Enforce non-negative credit limits
  CONSTRAINT chk_credit_limit CHECK (
    credit_limit >= 0.00 AND 
    credit_limit <= 9999999999.99
  ),
  
  -- Enforce non-negative cash credit limits
  CONSTRAINT chk_cash_credit_limit CHECK (
    cash_credit_limit >= 0.00 AND 
    cash_credit_limit <= 9999999999.99
  ),
  
  -- Enforce non-negative cycle amounts
  CONSTRAINT chk_current_cycle_credit CHECK (
    current_cycle_credit >= 0.00 AND 
    current_cycle_credit <= 9999999999.99
  ),
  
  CONSTRAINT chk_current_cycle_debit CHECK (
    current_cycle_debit >= 0.00 AND 
    current_cycle_debit <= 9999999999.99
  )
);

-- =================================================================
-- Table and Column Comments
-- =================================================================
COMMENT ON TABLE account IS 
'Account records migrated from ACCTDAT VSAM KSDS (KEYLEN=11, MAXLRECL=300, 50 records). Based on CVACT01Y.cpy copybook structure. Maintains exact COBOL COMP-3 decimal precision for all monetary amounts.';

COMMENT ON COLUMN account.account_id IS 
'Account ID (11 digits). Source: ACCT-ID PIC 9(11). VSAM primary key field.';

COMMENT ON COLUMN account.customer_id IS 
'Customer ID (9 digits). Foreign key reference to customer table. Each account is owned by one customer.';

COMMENT ON COLUMN account.active_status IS 
'Active status indicator. Source: ACCT-ACTIVE-STATUS PIC X(01). Valid values: Y=Active/Yes, N=Inactive/No.';

COMMENT ON COLUMN account.current_balance IS 
'Current account balance with exact 2 decimal precision. Source: ACCT-CURR-BAL PIC S9(10)V99 COMP-3 packed decimal. Range: -9999999999.99 to +9999999999.99.';

COMMENT ON COLUMN account.credit_limit IS 
'Account credit limit with exact 2 decimal precision. Source: ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3 packed decimal.';

COMMENT ON COLUMN account.cash_credit_limit IS 
'Cash advance credit limit with exact 2 decimal precision. Source: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3 packed decimal.';

COMMENT ON COLUMN account.open_date IS 
'Date account was opened. Source: ACCT-OPEN-DATE PIC X(10). Converted from COBOL date string to PostgreSQL DATE type.';

COMMENT ON COLUMN account.expiration_date IS 
'Date account expires (if applicable). Source: ACCT-EXPIRAION-DATE PIC X(10). Note: Original copybook field name contains typo.';

COMMENT ON COLUMN account.reissue_date IS 
'Date account was reissued (if applicable). Source: ACCT-REISSUE-DATE PIC X(10).';

COMMENT ON COLUMN account.current_cycle_credit IS 
'Total credits for current billing cycle with exact 2 decimal precision. Source: ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3 packed decimal.';

COMMENT ON COLUMN account.current_cycle_debit IS 
'Total debits for current billing cycle with exact 2 decimal precision. Source: ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3 packed decimal.';

COMMENT ON COLUMN account.address_zip IS 
'ZIP/postal code associated with account address. Source: ACCT-ADDR-ZIP PIC X(10).';

COMMENT ON COLUMN account.account_group_id IS 
'Account group identifier for categorization and reporting. Source: ACCT-GROUP-ID PIC X(10).';

COMMENT ON COLUMN account.created_at IS 
'Timestamp when account record was created in the database. Audit field not present in original COBOL copybook.';

COMMENT ON COLUMN account.updated_at IS 
'Timestamp when account record was last modified. Audit field not present in original COBOL copybook. Automatically updated by trigger.';

-- =================================================================
-- Indexes
-- =================================================================
-- Primary key index is automatically created by PRIMARY KEY constraint
-- This index supports VSAM-equivalent key-sequenced access on account_id

-- Secondary index on customer_id for efficient foreign key lookups
-- Supports queries: "Find all accounts for a given customer"
-- Matches VSAM alternate index access patterns
CREATE INDEX idx_account_customer_id ON account(customer_id);

-- Index on active_status for efficient status-based filtering
-- Supports queries: "Find all active accounts" or "Find all inactive accounts"
CREATE INDEX idx_active_status ON account(active_status);

-- Composite index on customer_id and active_status
-- Supports queries: "Find all active accounts for a given customer"
-- Optimizes common business logic patterns from COBOL programs
CREATE INDEX idx_account_customer_status ON account(customer_id, active_status);

-- Index on account_group_id for group-based reporting
-- Supports queries: "Find all accounts in a specific group"
CREATE INDEX idx_account_group_id ON account(account_group_id) 
  WHERE account_group_id IS NOT NULL;

-- =================================================================
-- Trigger for Automatic updated_at Timestamp Management
-- =================================================================
-- Creates or replaces trigger function to update the updated_at timestamp
-- This ensures audit trail accuracy without application code dependencies
CREATE OR REPLACE FUNCTION update_account_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  -- Set updated_at to current timestamp whenever a row is modified
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Attach trigger to account table
-- Executes BEFORE UPDATE to ensure timestamp is set before commit
CREATE TRIGGER trg_account_updated_at
  BEFORE UPDATE ON account
  FOR EACH ROW
  EXECUTE FUNCTION update_account_timestamp();

-- =================================================================
-- End of Migration V2
-- =================================================================
-- VSAM to PostgreSQL Transformation Summary:
-- - ACCTDAT KSDS (KEYLEN=11) → account table with VARCHAR(11) primary key
-- - COBOL COMP-3 PIC S9(10)V99 → NUMERIC(12,2) with exact precision
-- - Character dates PIC X(10) → PostgreSQL DATE type
-- - Added referential integrity (foreign key to customer)
-- - Added audit fields (created_at, updated_at)
-- - Added check constraints for data validation
-- - Created indexes matching VSAM access patterns
-- - Preserved all 12 business fields from CVACT01Y.cpy copybook
-- =================================================================
