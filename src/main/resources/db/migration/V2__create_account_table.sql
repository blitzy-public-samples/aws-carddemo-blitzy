-- ================================================================
-- Flyway Migration Script: V2__create_account_table.sql
-- ================================================================
-- Purpose: Create account table from COBOL copybook CVACT01Y.cpy
-- Source: app/cpy/CVACT01Y.cpy (RECLN 300 bytes)
-- Description: Account master table storing credit card account information
--              Replaces VSAM KSDS file ACCTDAT with PostgreSQL relational table
-- Migration: Mainframe COBOL to Java 21 Spring Boot microservices
-- ================================================================

-- ================================================================
-- Table: account
-- ================================================================
-- Maps COBOL ACCOUNT-RECORD structure to PostgreSQL table
-- Replaces VSAM ACCTDAT file with 11-digit account_id as primary key
-- Implements foreign key relationship to customer table replacing VSAM XREF
-- ================================================================

CREATE TABLE account (
    -- ============================================================
    -- Primary Key: Account Identifier
    -- COBOL: ACCT-ID PIC 9(11)
    -- ============================================================
    account_id BIGINT PRIMARY KEY,
    
    -- ============================================================
    -- Foreign Key: Customer Relationship
    -- Replaces VSAM cross-reference file relationships
    -- ============================================================
    customer_id BIGINT NOT NULL,
    
    -- ============================================================
    -- Account Status
    -- COBOL: ACCT-ACTIVE-STATUS PIC X(01)
    -- Valid values: 'Y' (Active), 'N' (Inactive)
    -- ============================================================
    active_status CHAR(1) NOT NULL DEFAULT 'Y',
    
    -- ============================================================
    -- Monetary Fields - COBOL COMP-3 Packed Decimal
    -- PIC S9(10)V99 → NUMERIC(12,2)
    -- Precision: 12 total digits (10 integer + 2 decimal)
    -- Scale: 2 decimal places
    -- Requires BigDecimal with RoundingMode.HALF_UP in Java
    -- ============================================================
    
    -- COBOL: ACCT-CURR-BAL PIC S9(10)V99 COMP-3
    -- Current account balance (signed decimal)
    current_balance NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3
    -- Maximum credit limit for the account
    credit_limit NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3
    -- Maximum cash advance limit
    cash_credit_limit NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- COBOL: ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3
    -- Current billing cycle credit amount
    current_cycle_credit NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- COBOL: ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3
    -- Current billing cycle debit amount
    current_cycle_debit NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    
    -- ============================================================
    -- Date Fields
    -- COBOL: PIC X(10) in YYYY-MM-DD string format
    -- PostgreSQL: DATE type for proper date arithmetic
    -- Java: LocalDate conversion via DateUtility.java
    -- ============================================================
    
    -- COBOL: ACCT-OPEN-DATE PIC X(10)
    -- Date the account was opened
    open_date DATE NOT NULL,
    
    -- COBOL: ACCT-EXPIRAION-DATE PIC X(10) (note: COBOL source has typo)
    -- Account expiration date
    expiration_date DATE,
    
    -- COBOL: ACCT-REISSUE-DATE PIC X(10)
    -- Date the account was last reissued
    reissue_date DATE,
    
    -- ============================================================
    -- Additional Account Attributes
    -- COBOL: PIC X(10) text fields
    -- ============================================================
    
    -- COBOL: ACCT-ADDR-ZIP PIC X(10)
    -- Postal code for account billing address
    postal_code VARCHAR(10),
    
    -- COBOL: ACCT-GROUP-ID PIC X(10)
    -- Account group identifier for categorization
    group_id VARCHAR(10),
    
    -- ============================================================
    -- Audit Trail Fields
    -- Not present in COBOL source - added for cloud-native audit
    -- ============================================================
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- ============================================================
    -- Constraints
    -- ============================================================
    
    -- Active status validation
    CONSTRAINT chk_account_active_status CHECK (active_status IN ('Y', 'N')),
    
    -- Balance constraints - prevent negative credit limits
    CONSTRAINT chk_account_credit_limit CHECK (credit_limit >= 0),
    CONSTRAINT chk_account_cash_credit_limit CHECK (cash_credit_limit >= 0),
    
    -- Logical constraint - cash limit should not exceed credit limit
    CONSTRAINT chk_account_cash_within_credit CHECK (cash_credit_limit <= credit_limit),
    
    -- Date logical constraints
    CONSTRAINT chk_account_dates CHECK (expiration_date IS NULL OR expiration_date >= open_date),
    
    -- Foreign Key to customer table (replaces VSAM XREF cross-reference file)
    CONSTRAINT fk_account_customer FOREIGN KEY (customer_id) 
        REFERENCES customer(customer_id) 
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

-- ================================================================
-- Indexes for Performance
-- ================================================================
-- Replicate VSAM KSDS key access patterns with PostgreSQL indexes
-- ================================================================

-- Primary key index (automatically created by PRIMARY KEY constraint)
-- Matches VSAM primary key on ACCT-ID

-- Index on customer_id for customer-to-accounts joins
-- Matches VSAM alternate index pattern for cross-reference lookups
CREATE INDEX idx_account_customer_id ON account(customer_id);

-- Index on active_status for filtering active/inactive accounts
-- Supports queries filtering by account status
CREATE INDEX idx_account_active_status ON account(active_status);

-- Composite index for customer_id + active_status
-- Optimizes common query pattern: find active accounts for a customer
CREATE INDEX idx_account_customer_active ON account(customer_id, active_status);

-- Index on open_date for temporal queries and reporting
CREATE INDEX idx_account_open_date ON account(open_date);

-- Index on expiration_date for expiration monitoring queries
CREATE INDEX idx_account_expiration_date ON account(expiration_date) WHERE expiration_date IS NOT NULL;

-- ================================================================
-- Table Comments
-- ================================================================

COMMENT ON TABLE account IS 
'Account master table migrated from COBOL copybook CVACT01Y.cpy (RECLN 300 bytes). '
'Replaces VSAM KSDS file ACCTDAT with PostgreSQL relational structure. '
'Stores credit card account information including balances, limits, and billing cycle data. '
'Foreign key relationship to customer table replaces VSAM cross-reference file.';

COMMENT ON COLUMN account.account_id IS 
'Primary key - 11-digit account identifier. Maps from COBOL ACCT-ID PIC 9(11).';

COMMENT ON COLUMN account.customer_id IS 
'Foreign key to customer table. Replaces VSAM XREF cross-reference relationship.';

COMMENT ON COLUMN account.active_status IS 
'Account status flag. Y=Active, N=Inactive. Maps from COBOL ACCT-ACTIVE-STATUS PIC X(01).';

COMMENT ON COLUMN account.current_balance IS 
'Current account balance. Maps from COBOL ACCT-CURR-BAL PIC S9(10)V99 COMP-3 packed decimal. '
'NUMERIC(12,2) preserves exact precision with scale=2 for monetary calculations. '
'Requires BigDecimal with RoundingMode.HALF_UP in Java to match COBOL arithmetic.';

COMMENT ON COLUMN account.credit_limit IS 
'Maximum credit limit. Maps from COBOL ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3. '
'NUMERIC(12,2) with scale=2 for exact monetary precision.';

COMMENT ON COLUMN account.cash_credit_limit IS 
'Maximum cash advance limit. Maps from COBOL ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3. '
'NUMERIC(12,2) with scale=2 for exact monetary precision.';

COMMENT ON COLUMN account.current_cycle_credit IS 
'Current billing cycle credit amount. Maps from COBOL ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3.';

COMMENT ON COLUMN account.current_cycle_debit IS 
'Current billing cycle debit amount. Maps from COBOL ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3.';

COMMENT ON COLUMN account.open_date IS 
'Account opening date. Maps from COBOL ACCT-OPEN-DATE PIC X(10) in YYYY-MM-DD format. '
'Converted to PostgreSQL DATE type for proper date arithmetic and Java LocalDate compatibility.';

COMMENT ON COLUMN account.expiration_date IS 
'Account expiration date. Maps from COBOL ACCT-EXPIRAION-DATE PIC X(10) (note: source has typo). '
'Nullable field converted to PostgreSQL DATE type.';

COMMENT ON COLUMN account.reissue_date IS 
'Account reissue date. Maps from COBOL ACCT-REISSUE-DATE PIC X(10). '
'Converted to PostgreSQL DATE type.';

COMMENT ON COLUMN account.postal_code IS 
'Billing address postal code. Maps from COBOL ACCT-ADDR-ZIP PIC X(10).';

COMMENT ON COLUMN account.group_id IS 
'Account group identifier for categorization. Maps from COBOL ACCT-GROUP-ID PIC X(10).';

COMMENT ON COLUMN account.created_at IS 
'Audit trail - record creation timestamp. Not present in COBOL source, added for cloud-native audit.';

COMMENT ON COLUMN account.updated_at IS 
'Audit trail - record last update timestamp. Not present in COBOL source, added for cloud-native audit.';

-- ================================================================
-- Trigger for updated_at Timestamp
-- ================================================================
-- Automatically update updated_at timestamp on row modifications
-- ================================================================

CREATE OR REPLACE FUNCTION update_account_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_account_updated_at
    BEFORE UPDATE ON account
    FOR EACH ROW
    EXECUTE FUNCTION update_account_updated_at();

-- ================================================================
-- End of Migration: V2__create_account_table.sql
-- ================================================================
-- Verification Checklist:
-- [✓] All COBOL fields mapped to PostgreSQL columns
-- [✓] NUMERIC(12,2) precision for all COMP-3 packed decimal fields
-- [✓] DATE types for all date fields with LocalDate conversion
-- [✓] Foreign key constraint to customer table
-- [✓] CHECK constraints for data validation
-- [✓] Indexes matching VSAM key access patterns
-- [✓] Audit trail fields (created_at, updated_at)
-- [✓] Comprehensive table and column comments
-- [✓] Automatic updated_at trigger
-- ================================================================
