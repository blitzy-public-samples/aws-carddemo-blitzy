-- =============================================================================
-- Flyway Migration V6: Create Card-Account Cross-Reference and Performance Indexes
-- =============================================================================
-- Source: COBOL copybook CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50)
--
-- Purpose:
-- This migration creates the card-account-customer cross-reference table and
-- comprehensive performance indexes that replicate VSAM primary and alternate
-- key access patterns to ensure query performance matches mainframe VSAM
-- response times (sub-10ms for primary key lookups).
--
-- Migration includes:
-- 1. Card-Account-Customer cross-reference table with composite primary key
-- 2. Foreign key constraints maintaining referential integrity
-- 3. Performance indexes replicating VSAM alternate key access paths
-- 4. Composite indexes supporting common query patterns for:
--    - Transaction processing and authorization
--    - Statement generation and billing
--    - Batch processing jobs
--    - Reporting and analytics queries
--
-- VSAM Performance Requirements:
-- - Primary key lookups: < 10ms (B-tree index on primary key)
-- - Alternate key lookups: < 50ms (additional B-tree indexes)
-- - Range scans: Optimized with composite indexes
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Card-Account Cross-Reference Table (CVACT03Y.cpy, RECLN 50)
-- -----------------------------------------------------------------------------
-- Transformation from COBOL copybook:
-- - XREF-CARD-NUM: PIC X(16) → VARCHAR(16) (foreign key to card)
-- - XREF-CUST-ID: PIC 9(09) → BIGINT (foreign key to customer)
-- - XREF-ACCT-ID: PIC 9(11) → BIGINT (foreign key to account)
--
-- Business Rules:
-- - Links card to both account and customer (many-to-many relationship)
-- - Primary key is composite (xref_card_num, xref_acct_id)
-- - Enables lookup of all cards for an account or all accounts for a card
-- - Supports customer-to-account relationship through card linkage
-- - CASCADE delete ensures referential integrity when parent records removed
-- -----------------------------------------------------------------------------
CREATE TABLE card_account_xref (
    xref_card_num VARCHAR(16) NOT NULL,
    xref_acct_id BIGINT NOT NULL,
    xref_cust_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key (replicates VSAM KSDS primary key)
    PRIMARY KEY (xref_card_num, xref_acct_id),
    
    -- Foreign key constraints with CASCADE to maintain referential integrity
    CONSTRAINT fk_xref_card FOREIGN KEY (xref_card_num)
        REFERENCES card(card_num)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    
    CONSTRAINT fk_xref_account FOREIGN KEY (xref_acct_id)
        REFERENCES account(acct_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    
    CONSTRAINT fk_xref_customer FOREIGN KEY (xref_cust_id)
        REFERENCES customer(cust_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);

-- -----------------------------------------------------------------------------
-- Cross-Reference Table Indexes (VSAM Alternate Index Equivalents)
-- -----------------------------------------------------------------------------
-- These indexes replicate VSAM alternate index access paths for:
-- - Account-to-card lookups: Find all cards for a given account
-- - Customer-to-card lookups: Find all cards for a given customer
-- Performance target: < 50ms for alternate key lookups
-- -----------------------------------------------------------------------------
CREATE INDEX idx_xref_account ON card_account_xref(xref_acct_id);
CREATE INDEX idx_xref_customer ON card_account_xref(xref_cust_id);

-- -----------------------------------------------------------------------------
-- Account Table Additional Indexes
-- -----------------------------------------------------------------------------
-- Composite index for account queries by status and group
-- Supports common query patterns in COACTUPC and COACTVWC programs
-- Use case: Admin screens filtering active accounts by group
CREATE INDEX idx_account_status_group ON account(acct_active_status, acct_group_id);

-- -----------------------------------------------------------------------------
-- Card Table Additional Indexes
-- -----------------------------------------------------------------------------
-- NOTE: idx_card_acct_status already created in V2__create_card_table.sql (line 159)
-- No additional card table indexes needed in this migration

-- -----------------------------------------------------------------------------
-- Transaction Table Critical Performance Indexes
-- -----------------------------------------------------------------------------
-- NOTE: idx_transaction_card_date already created in V4__create_transaction_tables.sql (line 186)
-- This index is critical for statement generation (COBIL00C) and transaction history (COTRN00C)

-- Composite index for date range queries in batch processing
-- Use case: Daily batch jobs (CBTRN01C-CBTRN03C) processing transactions by date
-- Supports efficient scanning of transaction windows (e.g., daily, monthly)
CREATE INDEX idx_transaction_date_range ON transaction(trans_orig_ts, trans_proc_ts);

-- Composite index for transaction type and category reporting
-- Use case: Transaction category summarization (CBTRN03C), reporting (CORPT00C)
-- Supports aggregation queries grouping by type and category
CREATE INDEX idx_transaction_type_cat ON transaction(trans_type_cd, trans_cat_cd);

-- -----------------------------------------------------------------------------
-- Daily Transaction Table Indexes (Batch Processing Support)
-- -----------------------------------------------------------------------------
-- NOTE: idx_daily_transaction_date already created in V4__create_transaction_tables.sql (line 194)
-- This index supports daily transaction validation and posting (CBTRN01C, CBTRN02C)

-- -----------------------------------------------------------------------------
-- Customer Table Search Indexes
-- -----------------------------------------------------------------------------
-- Composite index for customer name search
-- Use case: Customer lookup screens, admin functions (COUSR00C-COUSR03C)
-- Supports last name + first name search with customer ID for tie-breaking
-- Index structure optimized for "starts with" searches (e.g., last name begins with 'SMI')
CREATE INDEX idx_customer_lastname_firstname ON customer(cust_last_name, cust_first_name, cust_id);

-- -----------------------------------------------------------------------------
-- Transaction Category Balance Indexes
-- -----------------------------------------------------------------------------
-- Composite index for transaction category balance queries by account
-- Use case: Account balance calculations, category-level limits (CBACT02C)
-- Supports efficient retrieval of all category balances for an account
CREATE INDEX idx_tcat_bal_acct_type ON transaction_category_balance(tcat_acct_id, tcat_type_cd);

-- =============================================================================
-- Table and Column Documentation (PostgreSQL Comments)
-- =============================================================================
-- These comments provide metadata for database administrators and developers
-- documenting the COBOL-to-PostgreSQL transformation
-- =============================================================================

-- Card-Account Cross-Reference Table Comments
COMMENT ON TABLE card_account_xref IS 'Card-Account-Customer cross-reference table (CVACT03Y.cpy). Links cards to accounts and customers in many-to-many relationships. Replicates VSAM XREFFILE dataset.';
COMMENT ON COLUMN card_account_xref.xref_card_num IS 'Card number (COBOL PIC X(16)). Foreign key to card table.';
COMMENT ON COLUMN card_account_xref.xref_acct_id IS 'Account identifier (COBOL PIC 9(11)). Foreign key to account table.';
COMMENT ON COLUMN card_account_xref.xref_cust_id IS 'Customer identifier (COBOL PIC 9(09)). Foreign key to customer table.';
COMMENT ON COLUMN card_account_xref.created_at IS 'Record creation timestamp. Not present in COBOL - added for audit trail.';
COMMENT ON COLUMN card_account_xref.updated_at IS 'Record last update timestamp. Not present in COBOL - added for audit trail.';

-- Index Performance Documentation
COMMENT ON INDEX idx_xref_account IS 'VSAM alternate index equivalent: Account-to-card lookup. Target: < 50ms response time.';
COMMENT ON INDEX idx_xref_customer IS 'VSAM alternate index equivalent: Customer-to-card lookup. Target: < 50ms response time.';
COMMENT ON INDEX idx_account_status_group IS 'Replicates VSAM alternate index for account status and group queries. Supports COACTUPC/COACTVWC programs.';
COMMENT ON INDEX idx_transaction_date_range IS 'Batch processing date range index. Supports CBTRN01C-CBTRN03C daily transaction jobs.';
COMMENT ON INDEX idx_transaction_type_cat IS 'Transaction reporting and categorization index. Supports CBTRN03C summarization and CORPT00C reporting.';
COMMENT ON INDEX idx_customer_lastname_firstname IS 'Customer name search index. Optimized for "starts with" searches in COUSR00C-COUSR03C programs.';
COMMENT ON INDEX idx_tcat_bal_acct_type IS 'Transaction category balance lookup index. Supports account-level category balance queries in CBACT02C.';

-- =============================================================================
-- Migration Validation Notes
-- =============================================================================
-- Post-migration validation steps:
-- 1. Verify all foreign key constraints are properly enforced
-- 2. Run EXPLAIN ANALYZE on critical queries to validate index usage
-- 3. Benchmark primary key lookups to ensure < 10ms response time
-- 4. Benchmark alternate index queries to ensure < 50ms response time
-- 5. Load test with production-scale data volumes (100K cards, 1M transactions)
-- 6. Monitor query plans for table scans - all queries should use indexes
-- =============================================================================
