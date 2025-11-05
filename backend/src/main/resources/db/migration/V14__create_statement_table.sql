-- ================================================================
-- Flyway Migration V14: Create Statement Table
-- ================================================================
-- Purpose: Create statement table for monthly billing cycle statements
-- Source COBOL: CBSTM03A.cbl (Statement Generation Batch)
-- Source COBOL: CBSTM03B.cbl (Statement Formatting Batch)
-- Target Table: statement
-- Migration Type: CREATE (new table)
-- 
-- Description:
-- This table stores account statement records generated during monthly
-- billing cycles. Each statement aggregates transaction activity for
-- a billing period, calculating balances, charges, credits, interest,
-- and payment requirements.
--
-- COBOL File Mapping:
--   STMTDATA-FILE (Sequential statement file) → statement (PostgreSQL table)
--
-- Processing Flow:
--   1. Monthly statement generation job (CBSTM03A) creates statement records
--   2. Statement includes: previous balance, total credits, total debits, interest
--   3. Calculated: new balance, minimum payment, payment due date
--   4. Statement formatting job (CBSTM03B) transforms statements into output formats
--   5. Statements sent to customers via mail/email
--   6. Payment tracking via bill payment processing
--
-- Statement Status Values:
--   GENERATED - Statement created by generation job
--   FORMATTED - Statement formatted for customer delivery
--   SENT - Statement delivered to customer
--   PAID - Statement balance paid in full
--   OVERDUE - Payment due date passed without payment
-- ================================================================

-- Create statement table
CREATE TABLE statement (
    -- Primary key: Auto-generated statement identifier
    statement_id BIGSERIAL PRIMARY KEY,
    
    -- Account reference (foreign key to account table)
    -- COBOL: STMT-ACCT-ID PIC 9(11)
    account_id BIGINT NOT NULL,
    
    -- Statement date (when statement was generated)
    -- COBOL: STMT-DATE PIC X(10)
    statement_date DATE NOT NULL,
    
    -- Billing period start date
    -- COBOL: STMT-PERIOD-START PIC X(10)
    period_start DATE NOT NULL,
    
    -- Billing period end date
    -- COBOL: STMT-PERIOD-END PIC X(10)
    period_end DATE NOT NULL,
    
    -- Previous statement balance (carried forward)
    -- COBOL: STMT-PREV-BAL PIC S9(13)V99 COMP-3
    previous_balance NUMERIC(15, 2),
    
    -- Total credits during billing period (payments, refunds)
    -- COBOL: STMT-TOTAL-CREDITS PIC S9(13)V99 COMP-3
    total_credits NUMERIC(15, 2),
    
    -- Total debits during billing period (charges)
    -- COBOL: STMT-TOTAL-DEBITS PIC S9(13)V99 COMP-3
    total_debits NUMERIC(15, 2),
    
    -- Interest charged for billing period
    -- COBOL: STMT-INTEREST-AMT PIC S9(13)V99 COMP-3
    interest_charged NUMERIC(15, 2),
    
    -- New balance (previous_balance + total_debits - total_credits + interest_charged)
    -- COBOL: STMT-NEW-BAL PIC S9(13)V99 COMP-3
    new_balance NUMERIC(15, 2),
    
    -- Payment due date (typically 21 days after statement_date)
    -- COBOL: STMT-DUE-DATE PIC X(10)
    payment_due_date DATE,
    
    -- Minimum payment required
    -- COBOL: STMT-MIN-PAYMENT PIC S9(13)V99 COMP-3
    minimum_payment NUMERIC(15, 2),
    
    -- Statement processing status
    -- Values: GENERATED, FORMATTED, SENT, PAID, OVERDUE
    status VARCHAR(20) DEFAULT 'GENERATED',
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key constraint
    CONSTRAINT fk_statement_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id)
        ON DELETE RESTRICT
);

-- Create index on account_id for account statement lookup
-- Usage Pattern: SELECT * FROM statement WHERE account_id = ? ORDER BY statement_date DESC
CREATE INDEX idx_statement_account_id 
ON statement(account_id);

-- Create index on statement_date for temporal queries
-- Usage Pattern: SELECT * FROM statement WHERE statement_date BETWEEN ? AND ?
CREATE INDEX idx_statement_date 
ON statement(statement_date);

-- Create index on status for batch processing queries
-- Usage Pattern: SELECT * FROM statement WHERE status = 'GENERATED'
CREATE INDEX idx_statement_status 
ON statement(status);

-- Create composite index on account_id and statement_date for most recent statement lookup
-- Usage Pattern: SELECT * FROM statement WHERE account_id = ? ORDER BY statement_date DESC LIMIT 1
CREATE INDEX idx_statement_account_date 
ON statement(account_id, statement_date DESC);

-- Add comments for documentation
COMMENT ON TABLE statement IS 
'Monthly billing cycle statement records. Source: CBSTM03A.cbl (generation), CBSTM03B.cbl (formatting). Aggregates transaction activity and calculates billing amounts.';

COMMENT ON COLUMN statement.statement_id IS 
'Auto-generated statement identifier (primary key).';

COMMENT ON COLUMN statement.account_id IS 
'Account reference (foreign key to account table). Source: STMT-ACCT-ID PIC 9(11).';

COMMENT ON COLUMN statement.statement_date IS 
'Statement date (when statement was generated). Source: STMT-DATE PIC X(10).';

COMMENT ON COLUMN statement.period_start IS 
'Billing period start date. Source: STMT-PERIOD-START PIC X(10).';

COMMENT ON COLUMN statement.period_end IS 
'Billing period end date. Source: STMT-PERIOD-END PIC X(10).';

COMMENT ON COLUMN statement.previous_balance IS 
'Previous statement balance (carried forward). Source: STMT-PREV-BAL PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.total_credits IS 
'Total credits during billing period (payments, refunds). Source: STMT-TOTAL-CREDITS PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.total_debits IS 
'Total debits during billing period (charges). Source: STMT-TOTAL-DEBITS PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.interest_charged IS 
'Interest charged for billing period. Source: STMT-INTEREST-AMT PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.new_balance IS 
'New balance (previous_balance + total_debits - total_credits + interest_charged). Source: STMT-NEW-BAL PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.payment_due_date IS 
'Payment due date (typically 21 days after statement_date). Source: STMT-DUE-DATE PIC X(10).';

COMMENT ON COLUMN statement.minimum_payment IS 
'Minimum payment required. Source: STMT-MIN-PAYMENT PIC S9(13)V99 COMP-3.';

COMMENT ON COLUMN statement.status IS 
'Statement processing status: GENERATED, FORMATTED, SENT, PAID, OVERDUE.';

COMMENT ON COLUMN statement.created_at IS 
'Audit timestamp: when statement record was created.';

COMMENT ON COLUMN statement.updated_at IS 
'Audit timestamp: when statement record was last updated.';

-- Migration Complete
-- Table: statement
-- Indexes: 4 (idx_statement_account_id, idx_statement_date, idx_statement_status, idx_statement_account_date)
-- Foreign Keys: 1 (fk_statement_account)
