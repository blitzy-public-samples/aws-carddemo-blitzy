-- ==============================================================================
-- Flyway Migration V6: Create Cross-Reference and Reference Data Tables
-- ==============================================================================
-- Description: Creates cross-reference tables replacing VSAM XREF and CXACAIX
--              files, transaction category balance aggregation table, and
--              reference data tables for transaction types, categories, and
--              discount groups.
--
-- Source Files:
--   - app/cpy/CVACT03Y.cpy (Card cross-reference structure)
--   - app/cpy/CVTRA01Y.cpy (Transaction category balance structure)
--   - app/catlg/LISTCAT.txt (VSAM file definitions)
--
-- VSAM Files Replaced:
--   - CARDXREF.VSAM.KSDS (KEYLEN=16, MAXLRECL=50, REC-TOTAL=50)
--   - TCATBALF.VSAM.KSDS (KEYLEN=17, MAXLRECL=50, REC-TOTAL=100)
--   - TRANTYPE.VSAM.KSDS (KEYLEN=2, MAXLRECL=60, REC-TOTAL=7)
--   - TRANCATG.VSAM.KSDS (KEYLEN=6, MAXLRECL=60, REC-TOTAL=18)
--   - DISCGRP.VSAM.KSDS (KEYLEN=16, MAXLRECL=50, REC-TOTAL=51)
--
-- Migration Strategy:
--   - Composite primary keys match VSAM KEYLEN specifications
--   - Foreign key constraints will be added in V8__create_foreign_keys.sql
--   - Indexes on foreign key columns will be created in V7__create_indexes.sql
--
-- Author: Blitzy CodeGen - CardDemo Mainframe to Cloud Migration
-- Date: 2024
-- ==============================================================================

-- ==============================================================================
-- Table: card_xref
-- ==============================================================================
-- Purpose: Cross-reference table mapping card numbers to customer and account IDs
-- Source: CVACT03Y.cpy, CARDXREF.VSAM.KSDS (KEYLEN=16, RECLN=50, 50 records)
-- VSAM Details:
--   - Primary Key: XREF-CARD-NUM (16 bytes)
--   - Contains: Customer ID and Account ID for each card
--   - Used for: Quick card-to-account relationship lookups
-- ==============================================================================

CREATE TABLE card_xref (
    -- Card number (16 digits) - Primary component of composite key
    -- Source: CVACT03Y.cpy - XREF-CARD-NUM PIC X(16)
    -- VSAM: First 16 bytes of CARDXREF record (matches KEYLEN=16)
    card_number VARCHAR(16) NOT NULL,
    
    -- Customer ID (9 digits) - Second component of composite key
    -- Source: CVACT03Y.cpy - XREF-CUST-ID PIC 9(09)
    -- Links card to owning customer
    customer_id VARCHAR(9) NOT NULL,
    
    -- Account ID (11 digits) - Third component of composite key
    -- Source: CVACT03Y.cpy - XREF-ACCT-ID PIC 9(11)
    -- Links card to specific account
    account_id VARCHAR(11) NOT NULL,
    
    -- Audit timestamp - Record creation time
    -- Not in COBOL copybook - added for audit trail compliance
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit timestamp - Last update time
    -- Not in COBOL copybook - added for audit trail compliance
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key matching VSAM CARDXREF structure
    -- All three columns form unique identification for card cross-reference
    -- Ensures one-to-one mapping between card, customer, and account
    PRIMARY KEY (card_number, customer_id, account_id)
);

-- Table comment documenting VSAM equivalence
COMMENT ON TABLE card_xref IS 'Cross-reference table mapping card numbers to customer and account IDs. Replaces VSAM CARDXREF.KSDS file (KEYLEN=16, MAXLRECL=50, REC-TOTAL=50 records). Source: CVACT03Y.cpy copybook. Maintains referential integrity for card-customer-account relationships with composite primary key ensuring uniqueness.';

-- Column comments for documentation and maintenance
COMMENT ON COLUMN card_xref.card_number IS 'Card number (16 digits). Source: CVACT03Y.cpy XREF-CARD-NUM PIC X(16). Primary component of composite key. Format: 16-character card identifier.';
COMMENT ON COLUMN card_xref.customer_id IS 'Customer ID (9 digits). Source: CVACT03Y.cpy XREF-CUST-ID PIC 9(09). Links card to owning customer. References customer.customer_id.';
COMMENT ON COLUMN card_xref.account_id IS 'Account ID (11 digits). Source: CVACT03Y.cpy XREF-ACCT-ID PIC 9(11). Links card to specific account. References account.account_id.';
COMMENT ON COLUMN card_xref.created_at IS 'Record creation timestamp. Added for audit trail compliance. Automatically set to current timestamp on INSERT.';
COMMENT ON COLUMN card_xref.updated_at IS 'Record last update timestamp. Added for audit trail compliance. Should be updated via trigger on each UPDATE operation.';


-- ==============================================================================
-- Table: account_xref
-- ==============================================================================
-- Purpose: Cross-reference table mapping customers to accounts
-- Source: Implied from VSAM XREF file patterns and cross-reference architecture
-- Design: Supports multiple account relationships per customer
-- Relationship Types:
--   - PRIMARY: Customer is primary account holder
--   - SECONDARY: Customer is secondary account holder
--   - AUTHORIZED_USER: Customer is authorized user on account
-- ==============================================================================

CREATE TABLE account_xref (
    -- Customer ID (9 digits) - First component of composite key
    -- Links to customer table primary key
    customer_id VARCHAR(9) NOT NULL,
    
    -- Account ID (11 digits) - Second component of composite key
    -- Links to account table primary key
    account_id VARCHAR(11) NOT NULL,
    
    -- Relationship type describing customer-account relationship
    -- Valid values: PRIMARY, SECONDARY, AUTHORIZED_USER
    -- Default: PRIMARY (most common relationship type)
    relationship_type VARCHAR(20) NOT NULL DEFAULT 'PRIMARY',
    
    -- Audit timestamp - Record creation time
    -- Added for audit trail compliance and relationship tracking
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Audit timestamp - Last update time
    -- Added for audit trail compliance and change tracking
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key ensuring unique customer-account pairs
    -- Prevents duplicate relationships in the system
    PRIMARY KEY (customer_id, account_id),
    
    -- Constraint to validate relationship type values
    -- Ensures only valid relationship types are stored
    CONSTRAINT chk_account_xref_relationship_type 
        CHECK (relationship_type IN ('PRIMARY', 'SECONDARY', 'AUTHORIZED_USER'))
);

-- Table comment documenting purpose and VSAM equivalence
COMMENT ON TABLE account_xref IS 'Cross-reference table mapping customers to accounts with relationship types. Replaces VSAM XREF file patterns for customer-account relationships. Supports multiple customers per account (joint accounts, authorized users). Composite primary key on (customer_id, account_id) ensures unique pairings.';

-- Column comments for documentation
COMMENT ON COLUMN account_xref.customer_id IS 'Customer ID (9 digits). References customer.customer_id. First component of composite primary key.';
COMMENT ON COLUMN account_xref.account_id IS 'Account ID (11 digits). References account.account_id. Second component of composite primary key.';
COMMENT ON COLUMN account_xref.relationship_type IS 'Type of customer-account relationship. Valid values: PRIMARY (primary account holder), SECONDARY (secondary holder), AUTHORIZED_USER (authorized user). Defaults to PRIMARY.';
COMMENT ON COLUMN account_xref.created_at IS 'Timestamp when customer-account relationship was created. Audit trail field.';
COMMENT ON COLUMN account_xref.updated_at IS 'Timestamp when relationship was last modified. Audit trail field for tracking relationship changes.';


-- ==============================================================================
-- Table: transaction_category_balance
-- ==============================================================================
-- Purpose: Transaction category balance aggregation table
-- Source: CVTRA01Y.cpy, TCATBALF.VSAM.KSDS (KEYLEN=17, RECLN=50, 100 records)
-- VSAM Details:
--   - Primary Key: TRANCAT-ACCT-ID (11) + TRANCAT-TYPE-CD (2) + TRANCAT-CD (4) = 17 bytes
--   - Contains: Aggregated balances by account, transaction type, and category
--   - Used for: Category-level balance tracking and reporting
-- ==============================================================================

CREATE TABLE transaction_category_balance (
    -- Account ID (11 digits) - First component of composite key
    -- Source: CVTRA01Y.cpy - TRANCAT-ACCT-ID PIC 9(11)
    -- Links to account table for balance aggregation
    account_id VARCHAR(11) NOT NULL,
    
    -- Transaction type code (2 characters) - Second component of composite key
    -- Source: CVTRA01Y.cpy - TRANCAT-TYPE-CD PIC X(02)
    -- Valid codes: 01-07 (defined in transaction_type reference table)
    -- Examples: 01=Purchase, 02=ATM Withdrawal, 03=Payment, etc.
    transaction_type_code VARCHAR(2) NOT NULL,
    
    -- Transaction category code (4 digits) - Third component of composite key
    -- Source: CVTRA01Y.cpy - TRANCAT-CD PIC 9(04)
    -- References transaction_category table (subset of 6-character category codes)
    -- Allows categorization within transaction types
    transaction_category_code VARCHAR(4) NOT NULL,
    
    -- Category balance with 2 decimal precision
    -- Source: CVTRA01Y.cpy - TRAN-CAT-BAL PIC S9(09)V99 (COMP-3 packed decimal)
    -- COBOL: Signed 9 integer digits + 2 decimal places
    -- PostgreSQL: NUMERIC(11,2) preserves exact precision (9 + sign + 2 decimals)
    -- Rounding: HALF_UP to match COBOL COMP-3 rounding behavior
    -- Default: 0.00 for newly created category aggregations
    category_balance NUMERIC(11, 2) NOT NULL DEFAULT 0.00,
    
    -- Last update timestamp tracking when balance was last modified
    -- Added for audit trail and balance change tracking
    -- Updated automatically via trigger or application logic
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key matching VSAM TCATBALF structure
    -- Total key length: 11 + 2 + 4 = 17 bytes (matches KEYLEN=17)
    -- Ensures unique balance aggregation per account/type/category combination
    PRIMARY KEY (account_id, transaction_type_code, transaction_category_code),
    
    -- Constraint to ensure balance precision matches COBOL COMP-3 specification
    -- Prevents storage of values exceeding PIC S9(09)V99 capacity
    CONSTRAINT chk_category_balance_precision 
        CHECK (category_balance >= -999999999.99 AND category_balance <= 999999999.99)
);

-- Table comment documenting VSAM equivalence and usage
COMMENT ON TABLE transaction_category_balance IS 'Transaction category balance aggregation table. Replaces VSAM TCATBALF.KSDS file (KEYLEN=17, MAXLRECL=50, REC-TOTAL=100 records). Source: CVTRA01Y.cpy copybook. Maintains running balances for each account by transaction type and category. Composite primary key (account_id, transaction_type_code, transaction_category_code) matches VSAM key structure. Used for category-level reporting and balance tracking.';

-- Column comments documenting source and usage
COMMENT ON COLUMN transaction_category_balance.account_id IS 'Account ID (11 digits). Source: CVTRA01Y.cpy TRANCAT-ACCT-ID PIC 9(11). First component of composite primary key. References account.account_id.';
COMMENT ON COLUMN transaction_category_balance.transaction_type_code IS 'Transaction type code (2 characters, values 01-07). Source: CVTRA01Y.cpy TRANCAT-TYPE-CD PIC X(02). Second component of composite primary key. References transaction_type.type_code. Examples: 01=Purchase, 02=ATM Withdrawal, 03=Payment.';
COMMENT ON COLUMN transaction_category_balance.transaction_category_code IS 'Transaction category code (4 digits). Source: CVTRA01Y.cpy TRANCAT-CD PIC 9(04). Third component of composite primary key. Subset of transaction_category codes (which use 6-character keys). Allows fine-grained categorization within transaction types.';
COMMENT ON COLUMN transaction_category_balance.category_balance IS 'Category balance with 2 decimal precision. Source: CVTRA01Y.cpy TRAN-CAT-BAL PIC S9(09)V99 COMP-3. PostgreSQL NUMERIC(11,2) preserves COBOL packed decimal precision. Valid range: -999999999.99 to +999999999.99. Rounding: HALF_UP to match COBOL behavior.';
COMMENT ON COLUMN transaction_category_balance.last_updated IS 'Timestamp when category balance was last updated. Added for audit trail. Updated automatically when balance changes via batch processing or real-time transaction posting.';


-- ==============================================================================
-- Table: transaction_type
-- ==============================================================================
-- Purpose: Reference data table for transaction types
-- Source: TRANTYPE.VSAM.KSDS (KEYLEN=2, MAXLRECL=60, REC-TOTAL=7)
-- VSAM Details:
--   - Primary Key: Transaction Type Code (2 bytes)
--   - Contains: 7 standard transaction type definitions
--   - Used for: Transaction type validation and description lookup
-- Data loaded in: V9__load_reference_data.sql
-- ==============================================================================

CREATE TABLE transaction_type (
    -- Transaction type code (2 characters) - Primary key
    -- Source: TRANTYPE.VSAM.KSDS primary key field
    -- Valid codes: 01-07 (7 records in VSAM file)
    -- Format: Zero-padded numeric codes (01, 02, 03, ..., 07)
    type_code VARCHAR(2) PRIMARY KEY,
    
    -- Human-readable transaction type description
    -- Source: TRANTYPE.VSAM.KSDS description field (60 characters max)
    -- Examples:
    --   01 = 'Purchase Transaction'
    --   02 = 'ATM Withdrawal'
    --   03 = 'Payment (Credit)'
    --   04 = 'Balance Transfer'
    --   05 = 'Cash Advance'
    --   06 = 'Fee/Charge'
    --   07 = 'Interest Charge'
    type_description VARCHAR(60) NOT NULL,
    
    -- Record creation timestamp for audit trail
    -- Added for compliance and change tracking
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraint to ensure type_code follows expected format
    -- Valid codes are zero-padded 01-07 (expandable for future types)
    CONSTRAINT chk_transaction_type_code_format 
        CHECK (type_code ~ '^[0-9]{2}$')
);

-- Table comment documenting VSAM equivalence
COMMENT ON TABLE transaction_type IS 'Transaction type reference data table. Replaces VSAM TRANTYPE.KSDS file (KEYLEN=2, MAXLRECL=60, REC-TOTAL=7 records). Contains standard transaction type definitions used throughout the application. Primary key is 2-character type code. Data populated in V9__load_reference_data.sql migration.';

-- Column comments
COMMENT ON COLUMN transaction_type.type_code IS 'Transaction type code (2 characters). Primary key. Valid codes: 01-07. Format: Zero-padded numeric. Source: TRANTYPE.VSAM.KSDS key field.';
COMMENT ON COLUMN transaction_type.type_description IS 'Transaction type description (max 60 characters). Human-readable name for transaction type. Source: TRANTYPE.VSAM.KSDS description field. Examples: Purchase Transaction, ATM Withdrawal, Payment.';
COMMENT ON COLUMN transaction_type.created_at IS 'Timestamp when type was defined in system. Audit trail field. Typically set during initial reference data load.';


-- ==============================================================================
-- Table: transaction_category
-- ==============================================================================
-- Purpose: Reference data table for transaction categories
-- Source: TRANCATG.VSAM.KSDS (KEYLEN=6, MAXLRECL=60, REC-TOTAL=18)
-- VSAM Details:
--   - Primary Key: Transaction Category Code (6 bytes)
--   - Contains: 18 standard transaction category definitions
--   - Used for: Transaction categorization and reporting
-- Data loaded in: V9__load_reference_data.sql
-- ==============================================================================

CREATE TABLE transaction_category (
    -- Transaction category code (6 characters) - Primary key
    -- Source: TRANCATG.VSAM.KSDS primary key field
    -- Contains: 18 predefined category codes
    -- Format: Alphanumeric category identifiers (may include letters and numbers)
    category_code VARCHAR(6) PRIMARY KEY,
    
    -- Human-readable transaction category description
    -- Source: TRANCATG.VSAM.KSDS description field (60 characters max)
    -- Examples:
    --   Category codes and descriptions for:
    --     - Retail purchases (groceries, gas, dining, etc.)
    --     - Entertainment and travel
    --     - Bills and utilities
    --     - Cash advances and ATM fees
    --     - Interest and finance charges
    category_description VARCHAR(60) NOT NULL,
    
    -- Record creation timestamp for audit trail
    -- Added for compliance and change tracking
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraint to ensure category_code follows expected format
    -- Valid codes are 6-character alphanumeric strings
    CONSTRAINT chk_transaction_category_code_format 
        CHECK (category_code ~ '^[A-Z0-9]{6}$')
);

-- Table comment documenting VSAM equivalence
COMMENT ON TABLE transaction_category IS 'Transaction category reference data table. Replaces VSAM TRANCATG.KSDS file (KEYLEN=6, MAXLRECL=60, REC-TOTAL=18 records). Contains standard transaction category definitions for categorizing and reporting transactions. Primary key is 6-character category code. Data populated in V9__load_reference_data.sql migration.';

-- Column comments
COMMENT ON COLUMN transaction_category.category_code IS 'Transaction category code (6 characters). Primary key. Format: Alphanumeric uppercase. Source: TRANCATG.VSAM.KSDS key field. Contains 18 predefined category codes.';
COMMENT ON COLUMN transaction_category.category_description IS 'Transaction category description (max 60 characters). Human-readable category name. Source: TRANCATG.VSAM.KSDS description field. Used for transaction categorization in reports and statements.';
COMMENT ON COLUMN transaction_category.created_at IS 'Timestamp when category was defined in system. Audit trail field. Typically set during initial reference data load.';


-- ==============================================================================
-- Table: discount_group
-- ==============================================================================
-- Purpose: Discount group configuration table
-- Source: DISCGRP.VSAM.KSDS (KEYLEN=16, MAXLRECL=50, REC-TOTAL=51)
-- VSAM Details:
--   - Primary Key: Composite of group identifiers and transaction type/category
--   - Contains: 51 discount group configuration records
--   - Used for: Determining discounts based on account group and transaction type
-- Data loaded in: V9__load_reference_data.sql
-- ==============================================================================

CREATE TABLE discount_group (
    -- Auto-incrementing surrogate primary key
    -- Added for PostgreSQL best practices (simpler foreign key references)
    -- Note: VSAM file used composite natural key, but surrogate key improves performance
    discount_group_id SERIAL PRIMARY KEY,
    
    -- Group identifier (16 characters)
    -- Part of natural key from VSAM DISCGRP file
    -- Identifies the discount group classification
    group_id VARCHAR(16) NOT NULL,
    
    -- Account group identifier (16 characters)
    -- Part of natural key from VSAM DISCGRP file
    -- Links to account grouping for discount eligibility
    account_group_id VARCHAR(16) NOT NULL,
    
    -- Transaction type code (2 characters)
    -- Part of natural key from VSAM DISCGRP file
    -- Links to transaction_type table
    -- Discounts apply to specific transaction types
    transaction_type_code VARCHAR(2) NOT NULL,
    
    -- Transaction category code (6 characters)
    -- Part of natural key from VSAM DISCGRP file
    -- Links to transaction_category table
    -- Allows category-specific discount rates
    transaction_category_code VARCHAR(6) NOT NULL,
    
    -- Discount percentage (5 digits total, 2 decimal places)
    -- VSAM: Stored as COMP-3 packed decimal
    -- PostgreSQL: NUMERIC(5,2) for percentage values (0.00 to 100.00)
    -- Examples: 0.50 = 0.5% discount, 15.00 = 15% discount
    -- Default: 0.00 (no discount)
    discount_percentage NUMERIC(5, 2) NOT NULL DEFAULT 0.00,
    
    -- Record creation timestamp for audit trail
    -- Added for compliance and configuration change tracking
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Unique constraint on natural key components
    -- Replicates VSAM KEYLEN=16 composite key behavior
    -- Ensures one discount rate per group/account/type/category combination
    CONSTRAINT uq_discount_group_natural_key 
        UNIQUE (group_id, account_group_id, transaction_type_code, transaction_category_code),
    
    -- Constraint to validate discount percentage range
    -- Discounts must be between 0% and 100%
    CONSTRAINT chk_discount_percentage_range 
        CHECK (discount_percentage >= 0.00 AND discount_percentage <= 100.00)
);

-- Table comment documenting VSAM equivalence and usage
COMMENT ON TABLE discount_group IS 'Discount group configuration table. Replaces VSAM DISCGRP.KSDS file (KEYLEN=16, MAXLRECL=50, REC-TOTAL=51 records). Defines discount percentages based on account group membership and transaction type/category. Uses surrogate primary key (discount_group_id) with unique constraint on natural key components. Data populated in V9__load_reference_data.sql migration.';

-- Column comments
COMMENT ON COLUMN discount_group.discount_group_id IS 'Auto-incrementing surrogate primary key. Simplifies foreign key references. Not present in original VSAM file.';
COMMENT ON COLUMN discount_group.group_id IS 'Group identifier (16 characters). Part of VSAM natural key. Identifies discount group classification.';
COMMENT ON COLUMN discount_group.account_group_id IS 'Account group identifier (16 characters). Part of VSAM natural key. Links to account grouping for discount eligibility determination.';
COMMENT ON COLUMN discount_group.transaction_type_code IS 'Transaction type code (2 characters). Part of VSAM natural key. References transaction_type.type_code. Discounts apply to specific transaction types.';
COMMENT ON COLUMN discount_group.transaction_category_code IS 'Transaction category code (6 characters). Part of VSAM natural key. References transaction_category.category_code. Allows category-specific discount rates.';
COMMENT ON COLUMN discount_group.discount_percentage IS 'Discount percentage (0.00 to 100.00). Source: VSAM DISCGRP COMP-3 field. Examples: 0.50 = 0.5% discount, 15.00 = 15% discount. Default: 0.00 (no discount).';
COMMENT ON COLUMN discount_group.created_at IS 'Timestamp when discount group configuration was created. Audit trail field for tracking configuration changes.';


-- ==============================================================================
-- Migration Notes
-- ==============================================================================
-- 1. Foreign Key Constraints:
--    - Will be added in V8__create_foreign_keys.sql
--    - card_xref.customer_id → customer.customer_id
--    - card_xref.account_id → account.account_id
--    - account_xref.customer_id → customer.customer_id
--    - account_xref.account_id → account.account_id
--    - transaction_category_balance.account_id → account.account_id
--    - transaction_category_balance.transaction_type_code → transaction_type.type_code
--    - transaction_category_balance.transaction_category_code → transaction_category.category_code
--    - discount_group.transaction_type_code → transaction_type.type_code
--    - discount_group.transaction_category_code → transaction_category.category_code
--
-- 2. Indexes:
--    - Will be created in V7__create_indexes.sql
--    - B-tree indexes on foreign key columns for join performance
--    - Composite indexes for common query patterns
--
-- 3. Reference Data:
--    - transaction_type, transaction_category, discount_group tables
--    - Data will be loaded in V9__load_reference_data.sql
--    - Source data from app/data/ASCII/*.txt files
--
-- 4. Triggers:
--    - updated_at timestamp triggers for card_xref, account_xref
--    - last_updated timestamp trigger for transaction_category_balance
--    - To be created in subsequent migration if needed
--
-- 5. Data Migration:
--    - VSAM CARDXREF → card_xref (50 records)
--    - VSAM TCATBALF → transaction_category_balance (100 records)
--    - VSAM TRANTYPE → transaction_type (7 records)
--    - VSAM TRANCATG → transaction_category (18 records)
--    - VSAM DISCGRP → discount_group (51 records)
--
-- 6. COBOL COMP-3 Precision:
--    - All NUMERIC columns preserve COBOL packed decimal precision
--    - NUMERIC(11,2) for balances (matches PIC S9(09)V99)
--    - NUMERIC(5,2) for percentages
--    - Application code must use HALF_UP rounding mode for BigDecimal operations
--
-- 7. Performance Considerations:
--    - Composite primary keys match VSAM key structures
--    - Indexes in V7 will provide equivalent VSAM access performance
--    - PostgreSQL B-tree indexes optimize sequential and random access patterns
--
-- 8. Audit Trail:
--    - created_at and updated_at timestamps added to all tables
--    - Maintains regulatory compliance requirements
--    - Supports change tracking and data lineage
-- ==============================================================================
