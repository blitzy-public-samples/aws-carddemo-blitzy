-- Migration: V15__create_transaction_aggregate_table.sql
-- Description: Create transaction_aggregate table for transaction category aggregation
-- Author: Blitzy Code Generation Platform
-- Date: 2025-01-08
-- Related Entity: com.carddemo.entity.TransactionAggregate
-- Related Source: app/cpy/CVTRA04Y.cpy (COBOL copybook)
--
-- Purpose:
-- Creates transaction_aggregate table to store aggregated transaction balances by account,
-- transaction type, and category. This table supports batch processing for transaction
-- aggregation (TransactionAggregationJob replacing CBTRN03C.cbl) and provides optimized
-- data retrieval for category summary reports (COTRN01C category summary screen).
--
-- Table Structure:
-- - Composite primary key: (account_id, transaction_type_code, transaction_category_code)
-- - Foreign keys to account, transaction_type, and transaction_category tables
-- - Three indexes for efficient querying by account, type, and category
--
-- COBOL Source Mapping:
-- CVTRA04Y.cpy TRAN-CAT-KEY compound key → composite primary key columns
--   TRANCAT-ACCT-ID PIC 9(11)           → account_id BIGINT
--   TRANCAT-TYPE-CD PIC X(02)           → transaction_type_code VARCHAR(2)
--   TRANCAT-CD (was PIC 9(04))          → transaction_category_code VARCHAR(6)
-- TRAN-CAT-BAL PIC S9(09)V99            → category_balance NUMERIC(11,2)
--
-- Critical Requirements (Section 0.9):
-- - Maintains COBOL COMP-3 precision with NUMERIC(11,2) for category_balance
-- - Enforces referential integrity via foreign key constraints
-- - Supports batch aggregation processing within 4-hour window
-- - Enables efficient category summary reporting under 200ms response time

-- Create transaction_aggregate table with composite primary key
CREATE TABLE transaction_aggregate (
    -- Composite primary key components (3 columns)
    
    -- From CVTRA04Y.cpy: TRANCAT-ACCT-ID PIC 9(11)
    -- Account identifier (11-digit numeric)
    -- First component of composite key, references account table
    account_id BIGINT NOT NULL,
    
    -- From CVTRA04Y.cpy: TRANCAT-TYPE-CD PIC X(02)
    -- Transaction type code (2 characters: "PU", "CA", "PM", "RF", "FE", "IN")
    -- Second component of composite key, references transaction_type table
    transaction_type_code VARCHAR(2) NOT NULL,
    
    -- From CVTRA04Y.cpy: TRANCAT-CD (originally PIC 9(04), now VARCHAR(6))
    -- Transaction category code (6-character string: first 2 match type code)
    -- Third component of composite key, part of composite FK to transaction_category
    -- Note: Changed from PIC 9(04) to VARCHAR(6) to match Transaction entity
    transaction_category_code VARCHAR(6) NOT NULL,
    
    -- Aggregated balance fields
    
    -- From CVTRA04Y.cpy: TRAN-CAT-BAL PIC S9(09)V99
    -- Aggregated category balance for this account/type/category combination
    -- CRITICAL: NUMERIC(11,2) maintains COBOL COMP-3 packed decimal precision
    -- Scale: 2 decimal places (matches COBOL V99)
    -- Precision: 11 digits total (9 integer + 2 decimal, matches S9(09)V99)
    -- Signed: Supports negative balances (refunds, credits)
    -- Range: -999999999.99 to +999999999.99
    -- Calculated by TransactionAggregationJob (Spring Batch replacing CBTRN03C.cbl)
    category_balance NUMERIC(11,2) NOT NULL DEFAULT 0.00,
    
    -- Audit and tracking fields (not in original COBOL, added per Section 0.9)
    
    -- Timestamp when this aggregation record was last updated
    -- Tracks when batch processing last refreshed this aggregation
    -- Enables identification of stale data requiring refresh
    -- Required for audit trail and regulatory compliance
    last_updated TIMESTAMP,
    
    -- Timestamp when this aggregation record was first created
    -- Set once on initial creation, immutable afterwards
    -- Enables tracking of aggregation record lifecycle
    created_at TIMESTAMP,
    
    -- Count of transactions aggregated into this balance
    -- Not in original COBOL but supports validation and reporting
    -- Tracks how many individual transactions contributed to category_balance
    -- Useful for average transaction size calculations and validation
    transaction_count INTEGER,
    
    -- Composite primary key constraint
    -- Enforces uniqueness for each account/type/category combination
    CONSTRAINT pk_transaction_aggregate PRIMARY KEY (
        account_id,
        transaction_type_code,
        transaction_category_code
    ),
    
    -- Foreign key to account table
    -- Ensures every aggregation belongs to a valid account
    -- ON DELETE RESTRICT prevents account deletion if aggregations exist
    CONSTRAINT fk_trans_agg_account FOREIGN KEY (account_id)
        REFERENCES account (account_id)
        ON DELETE RESTRICT,
    
    -- Foreign key to transaction_type table
    -- Ensures transaction type code is valid reference data
    -- ON DELETE RESTRICT prevents type deletion if aggregations exist
    CONSTRAINT fk_trans_agg_type FOREIGN KEY (transaction_type_code)
        REFERENCES transaction_type (transaction_type_code)
        ON DELETE RESTRICT,
    
    -- Foreign key to transaction_category table
    -- Ensures transaction category code is valid reference data
    -- ON DELETE RESTRICT prevents category deletion if aggregations exist
    -- Note: References only transaction_category_code (primary key), not composite key
    CONSTRAINT fk_trans_agg_category FOREIGN KEY (transaction_category_code)
        REFERENCES transaction_category (transaction_category_code)
        ON DELETE RESTRICT
);

-- Create indexes for efficient querying (matching @Index annotations in entity)

-- Index on account_id for account-level aggregation queries
-- Supports queries: "Get all category aggregations for this account"
-- Used by: Account detail view, account summary reports
CREATE INDEX idx_trans_agg_account ON transaction_aggregate (account_id);

-- Index on transaction_type_code for type-level aggregation queries
-- Supports queries: "Get all aggregations for this transaction type"
-- Used by: Type-level reporting, batch processing validation
CREATE INDEX idx_trans_agg_type ON transaction_aggregate (transaction_type_code);

-- Index on transaction_category_code for category-level queries
-- Supports queries: "Get all aggregations for this category across accounts"
-- Used by: Category-level reporting, trending analysis
CREATE INDEX idx_trans_agg_category ON transaction_aggregate (transaction_category_code);

-- Add table comment for documentation
COMMENT ON TABLE transaction_aggregate IS 
'Transaction category aggregation table storing summarized balances by account, type, and category. '
'Replaces COBOL CVTRA04Y.cpy structure. Updated by TransactionAggregationJob batch processing (CBTRN03C.cbl). '
'Supports category summary reporting for COTRN01C transaction category screen.';

-- Add column comments for clarity
COMMENT ON COLUMN transaction_aggregate.account_id IS 
'Account identifier (11-digit). Maps from COBOL TRANCAT-ACCT-ID PIC 9(11). Foreign key to account table.';

COMMENT ON COLUMN transaction_aggregate.transaction_type_code IS 
'Transaction type code (2 chars). Maps from COBOL TRANCAT-TYPE-CD PIC X(02). Foreign key to transaction_type table.';

COMMENT ON COLUMN transaction_aggregate.transaction_category_code IS 
'Transaction category code (6 chars). Maps from COBOL TRANCAT-CD (originally PIC 9(04)). Part of composite FK to transaction_category.';

COMMENT ON COLUMN transaction_aggregate.category_balance IS 
'Aggregated category balance. Maps from COBOL TRAN-CAT-BAL PIC S9(09)V99. Maintains COMP-3 precision with NUMERIC(11,2). Range: -999999999.99 to +999999999.99.';

COMMENT ON COLUMN transaction_aggregate.last_updated IS 
'Timestamp when aggregation was last updated by batch processing. Supports audit trail and stale data detection.';

COMMENT ON COLUMN transaction_aggregate.created_at IS 
'Timestamp when aggregation record was first created. Immutable after creation.';

COMMENT ON COLUMN transaction_aggregate.transaction_count IS 
'Count of transactions aggregated into category_balance. Supports validation and reporting.';
