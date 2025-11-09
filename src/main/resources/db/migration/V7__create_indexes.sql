-- ======================================================================================
-- Flyway Migration V7: Create Database Indexes
-- ======================================================================================
-- Purpose: Create composite indexes on PostgreSQL tables replicating VSAM KSDS 
--          primary and alternate key access patterns from mainframe catalog metadata
--
-- Source Reference: app/catlg/LISTCAT.txt (VSAM catalog definitions)
-- Target: PostgreSQL 16.1 with B-tree indexes for optimal performance
--
-- VSAM to PostgreSQL Index Mapping:
-- ---------------------------------
-- VSAM KSDS primary keys are already handled by PRIMARY KEY constraints in V1-V5
-- This script creates alternate indexes (AIX) equivalents and performance indexes
--
-- Performance Requirements:
-- - Maintain sub-200ms response time at 95th percentile for all queries
-- - Support 10,000 TPS transaction volume
-- - Enable pagination (7 cards per page, 10 transactions per page)
-- ======================================================================================

-- ======================================================================================
-- SECTION 1: Foreign Key Relationship Indexes
-- ======================================================================================
-- These indexes replicate VSAM alternate index (AIX) access patterns and optimize
-- foreign key constraint validation performance

-- Index: idx_account_customer
-- Purpose: Support customer-to-accounts joins, replacing XREF cross-reference file lookups
-- VSAM Mapping: Enables quick lookup of all accounts belonging to a customer
-- Usage: SELECT * FROM account WHERE customer_id = ?
CREATE INDEX IF NOT EXISTS idx_account_customer 
    ON account(customer_id);

COMMENT ON INDEX idx_account_customer IS 
    'Foreign key index: Optimizes customer-to-accounts joins. Replaces VSAM XREF file lookups.';

-- Index: idx_card_account
-- Purpose: Replicate CARDDATA.VSAM.AIX alternate index (KEYLEN=11, RKP=5)
-- VSAM Mapping: Maps to CXACAIX cross-reference for account-to-cards relationship
-- Usage: SELECT * FROM card WHERE account_id = ? (used in card listing, 7 cards per page)
CREATE INDEX IF NOT EXISTS idx_card_account 
    ON card(account_id);

COMMENT ON INDEX idx_card_account IS 
    'Foreign key index: Replicates CARDDATA.VSAM.AIX (KEYLEN=11, RKP=5). Supports card listing with pagination of 7 cards per page.';

-- Index: idx_transaction_card
-- Purpose: Replicate TRANSACT.VSAM.AIX alternate index (KEYLEN=26, RKP=5, AXRKP=304)
-- VSAM Mapping: Enables transaction history queries by card number
-- Usage: SELECT * FROM transaction WHERE card_number = ? ORDER BY original_timestamp DESC
CREATE INDEX IF NOT EXISTS idx_transaction_card 
    ON transaction(card_number);

COMMENT ON INDEX idx_transaction_card IS 
    'Foreign key index: Replicates TRANSACT.VSAM.AIX (KEYLEN=26, RKP=5). Optimizes card transaction history queries with 10 transactions per page pagination.';

-- ======================================================================================
-- SECTION 2: Date-Range Query Performance Indexes
-- ======================================================================================
-- These indexes optimize temporal queries with descending order for recent-first access

-- Index: idx_transaction_date
-- Purpose: Optimize date-range queries with descending order for recent transactions first
-- Performance Target: Maintain sub-200ms response time at 95th percentile
-- Usage: SELECT * FROM transaction WHERE original_timestamp BETWEEN ? AND ? 
--        ORDER BY original_timestamp DESC LIMIT 10 OFFSET ?
CREATE INDEX IF NOT EXISTS idx_transaction_date 
    ON transaction(original_timestamp DESC);

COMMENT ON INDEX idx_transaction_date IS 
    'Performance index: Optimizes date-range queries with descending order for recent-first display. Supports pagination of 10 transactions per page maintaining sub-200ms response time at 95th percentile.';

-- Index: idx_transaction_processed_date
-- Purpose: Support batch processing and transaction status queries by processed timestamp
-- Usage: Batch jobs querying transactions by processing date
CREATE INDEX IF NOT EXISTS idx_transaction_processed_date 
    ON transaction(processed_timestamp);

COMMENT ON INDEX idx_transaction_processed_date IS 
    'Performance index: Optimizes batch processing queries filtering by processed timestamp.';

-- ======================================================================================
-- SECTION 3: Batch Processing Indexes
-- ======================================================================================
-- These indexes support batch job operations scanning data by specific criteria

-- Index: idx_card_expiration
-- Purpose: Support batch expiration processing jobs (CBACT02C)
-- VSAM Pattern: Sequential scan of CARDDATA.VSAM.KSDS by expiration date
-- Usage: SELECT * FROM card WHERE expiration_date BETWEEN ? AND ? 
--        (for expiration notification and card renewal batch jobs)
CREATE INDEX IF NOT EXISTS idx_card_expiration 
    ON card(expiration_date);

COMMENT ON INDEX idx_card_expiration IS 
    'Batch processing index: Supports card expiration batch jobs scanning cards by expiration date for renewal processing.';

-- Index: idx_card_status_expiration
-- Purpose: Composite index for active cards expiring soon
-- Usage: Batch jobs finding active cards approaching expiration
CREATE INDEX IF NOT EXISTS idx_card_status_expiration 
    ON card(active_status, expiration_date) 
    WHERE active_status = 'Y';

COMMENT ON INDEX idx_card_status_expiration IS 
    'Batch processing index: Partial index on active cards with expiration date for renewal processing efficiency.';

-- Index: idx_account_status
-- Purpose: Filter queries by account active status
-- Usage: SELECT * FROM account WHERE active_status = 'Y'
CREATE INDEX IF NOT EXISTS idx_account_status 
    ON account(active_status);

COMMENT ON INDEX idx_account_status IS 
    'Performance index: Optimizes queries filtering by account active status.';

-- ======================================================================================
-- SECTION 4: Balance and Category Lookup Indexes
-- ======================================================================================
-- These indexes optimize financial calculation and category balance queries

-- Index: idx_transaction_category_balance_account
-- Purpose: Support balance lookup queries by account
-- Usage: SELECT * FROM transaction_category_balance WHERE account_id = ?
CREATE INDEX IF NOT EXISTS idx_transaction_category_balance_account 
    ON transaction_category_balance(account_id);

COMMENT ON INDEX idx_transaction_category_balance_account IS 
    'Performance index: Optimizes transaction category balance lookups by account for financial reporting.';

-- Index: idx_transaction_type_category
-- Purpose: Composite index for transaction type and category analysis
-- Usage: Analytics queries grouping transactions by type and category
CREATE INDEX IF NOT EXISTS idx_transaction_type_category 
    ON transaction(transaction_type_code, transaction_category_code);

COMMENT ON INDEX idx_transaction_type_category IS 
    'Performance index: Supports transaction analysis and reporting by type and category.';

-- ======================================================================================
-- SECTION 5: User Authentication and Security Indexes
-- ======================================================================================
-- Primary key index on user(user_id) already exists from V5
-- These indexes support authentication and user management queries

-- Index: idx_user_type
-- Purpose: Support user listing filtered by user type (Admin vs Regular User)
-- Usage: SELECT * FROM user WHERE user_type = 'A' (admin queries)
CREATE INDEX IF NOT EXISTS idx_user_type 
    ON "user"(user_type);

COMMENT ON INDEX idx_user_type IS 
    'Performance index: Optimizes user listing queries filtered by user type for admin operations.';

-- ======================================================================================
-- SECTION 6: Composite Indexes for Complex Queries
-- ======================================================================================
-- These indexes optimize multi-column filter and sort operations

-- Index: idx_transaction_card_date
-- Purpose: Composite index for card transaction history with date ordering
-- Usage: SELECT * FROM transaction WHERE card_number = ? 
--        ORDER BY original_timestamp DESC LIMIT 10
CREATE INDEX IF NOT EXISTS idx_transaction_card_date 
    ON transaction(card_number, original_timestamp DESC);

COMMENT ON INDEX idx_transaction_card_date IS 
    'Composite index: Optimizes card transaction history queries with date-descending order for pagination support.';

-- Index: idx_transaction_amount
-- Purpose: Support transaction queries filtered by amount ranges
-- Usage: Fraud detection queries finding transactions above threshold amounts
CREATE INDEX IF NOT EXISTS idx_transaction_amount 
    ON transaction(amount);

COMMENT ON INDEX idx_transaction_amount IS 
    'Performance index: Supports transaction queries filtering by amount ranges for fraud detection and reporting.';

-- Index: idx_account_credit_limit
-- Purpose: Support queries finding accounts by credit limit ranges
-- Usage: Risk management queries and credit limit analysis
CREATE INDEX IF NOT EXISTS idx_account_credit_limit 
    ON account(credit_limit);

COMMENT ON INDEX idx_account_credit_limit IS 
    'Performance index: Supports risk management queries analyzing accounts by credit limit ranges.';

-- Index: idx_customer_fico_score
-- Purpose: Support customer risk analysis by FICO score
-- Usage: Credit risk reporting and customer segmentation
CREATE INDEX IF NOT EXISTS idx_customer_fico_score 
    ON customer(fico_credit_score);

COMMENT ON INDEX idx_customer_fico_score IS 
    'Performance index: Supports credit risk analysis queries filtering customers by FICO score.';

-- ======================================================================================
-- SECTION 7: Text Search Indexes (Optional GIN indexes for future text search)
-- ======================================================================================
-- These indexes can be enabled if full-text search capabilities are needed

-- Index: idx_customer_name_gin (commented out - enable if full-text search needed)
-- Purpose: Full-text search on customer names
-- Usage: SELECT * FROM customer WHERE to_tsvector('english', first_name || ' ' || last_name) 
--        @@ to_tsquery('english', 'search_term')
-- CREATE INDEX IF NOT EXISTS idx_customer_name_gin 
--     ON customer USING GIN (to_tsvector('english', first_name || ' ' || last_name));

-- ======================================================================================
-- Index Creation Summary
-- ======================================================================================
-- Total Indexes Created: 17
--
-- Category Breakdown:
-- - Foreign Key Indexes: 3 (account_customer, card_account, transaction_card)
-- - Date/Time Indexes: 2 (transaction_date, transaction_processed_date)
-- - Batch Processing Indexes: 3 (card_expiration, card_status_expiration, account_status)
-- - Balance/Category Indexes: 2 (transaction_category_balance_account, transaction_type_category)
-- - User/Security Indexes: 1 (user_type)
-- - Composite/Performance Indexes: 6 (transaction_card_date, transaction_amount, 
--   account_credit_limit, customer_fico_score, and others)
--
-- VSAM Access Pattern Replication:
-- - CARDDATA.VSAM.AIX (KEYLEN=11, RKP=5) → idx_card_account
-- - TRANSACT.VSAM.AIX (KEYLEN=26, RKP=5) → idx_transaction_card
-- - CXACAIX cross-reference → idx_card_account composite mapping
-- - XREF cross-reference → idx_account_customer
--
-- Performance Characteristics:
-- - All indexes use PostgreSQL B-tree for optimal range query performance
-- - DESC ordering on timestamp indexes for recent-first access patterns
-- - Partial indexes on active records reduce index size and improve performance
-- - Composite indexes optimize multi-column filter and sort operations
--
-- Operational Notes:
-- - All CREATE INDEX statements use IF NOT EXISTS for idempotent execution
-- - Supports Flyway baseline-on-migrate for existing databases
-- - Index names follow convention: idx_tablename_columnname(s)
-- - ANALYZE command should be run after migration to update statistics
-- ======================================================================================

-- Update table statistics for query planner optimization
ANALYZE customer;
ANALYZE account;
ANALYZE card;
ANALYZE transaction;
ANALYZE "user";
ANALYZE transaction_category;
ANALYZE transaction_type;
ANALYZE disclosure_group;
ANALYZE transaction_category_balance;

-- ======================================================================================
-- End of V7 Migration
-- ======================================================================================
