-- =====================================================================
-- Flyway Migration V6: Create Reference Data Tables
-- =====================================================================
-- Description: Creates four PostgreSQL reference data tables from COBOL
--              copybooks CVTRA01Y-04Y for transaction category balances,
--              disclosure groups, transaction types, and transaction categories
--
-- Source Files:
--   - app/cpy/CVTRA01Y.cpy (RECLN = 50)
--   - app/cpy/CVTRA02Y.cpy (RECLN = 50)
--   - app/cpy/CVTRA03Y.cpy (RECLN = 60)
--   - app/cpy/CVTRA04Y.cpy (RECLN = 60)
--
-- Migration Strategy:
--   1. Create transaction_type table (parent for foreign keys)
--   2. Create transaction_category table (references transaction_type)
--   3. Create transaction_category_balance table (references account)
--   4. Create disclosure_group table (standalone reference table)
--
-- Data Type Mappings from COBOL to PostgreSQL:
--   PIC 9(n)         → BIGINT or SMALLINT (based on size)
--   PIC X(n)         → CHAR(n) or VARCHAR(n)
--   PIC S9(m)V9(n)   → NUMERIC(m+n, n) with explicit scale
--
-- =====================================================================

-- =====================================================================
-- Table 1: transaction_type
-- =====================================================================
-- Source: app/cpy/CVTRA03Y.cpy (RECLN = 60)
-- Purpose: Reference table for transaction type codes and descriptions
-- COBOL Structure:
--   01  TRAN-TYPE-RECORD.
--       05  TRAN-TYPE          PIC X(02).
--       05  TRAN-TYPE-DESC     PIC X(50).
-- =====================================================================

CREATE TABLE transaction_type (
    -- Primary key: Transaction type code (2-character identifier)
    -- Source: TRAN-TYPE PIC X(02)
    type_code CHAR(2) NOT NULL,
    
    -- Transaction type description (human-readable name)
    -- Source: TRAN-TYPE-DESC PIC X(50)
    description VARCHAR(50) NOT NULL,
    
    -- Audit columns for tracking data changes
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Primary key constraint
    CONSTRAINT pk_transaction_type PRIMARY KEY (type_code)
);

-- Create index for description lookups
CREATE INDEX idx_transaction_type_description ON transaction_type(description);

-- Add table comment
COMMENT ON TABLE transaction_type IS 
'Transaction type reference table from COBOL copybook CVTRA03Y.cpy (RECLN = 60). Defines valid transaction type codes and their descriptions for categorizing card transactions.';

COMMENT ON COLUMN transaction_type.type_code IS 
'Transaction type code (2-character identifier) from COBOL field TRAN-TYPE PIC X(02)';

COMMENT ON COLUMN transaction_type.description IS 
'Transaction type description from COBOL field TRAN-TYPE-DESC PIC X(50)';

-- =====================================================================
-- Table 2: transaction_category
-- =====================================================================
-- Source: app/cpy/CVTRA04Y.cpy (RECLN = 60)
-- Purpose: Reference table for transaction category codes within each type
-- COBOL Structure:
--   01  TRAN-CAT-RECORD.
--       05  TRAN-CAT-KEY.
--           10  TRAN-TYPE-CD       PIC X(02).
--           10  TRAN-CAT-CD        PIC 9(04).
--       05  TRAN-CAT-TYPE-DESC     PIC X(50).
-- =====================================================================

CREATE TABLE transaction_category (
    -- Composite primary key part 1: Transaction type code
    -- Source: TRAN-TYPE-CD PIC X(02)
    type_code CHAR(2) NOT NULL,
    
    -- Composite primary key part 2: Category code (4-digit numeric)
    -- Source: TRAN-CAT-CD PIC 9(04)
    -- Using SMALLINT as 4-digit numeric fits within SMALLINT range (0-9999)
    category_code SMALLINT NOT NULL,
    
    -- Transaction category description
    -- Source: TRAN-CAT-TYPE-DESC PIC X(50)
    description VARCHAR(50) NOT NULL,
    
    -- Audit columns
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key constraint
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_code, category_code),
    
    -- Foreign key to transaction_type table
    -- Ensures referential integrity between categories and types
    CONSTRAINT fk_transaction_category_type 
        FOREIGN KEY (type_code) 
        REFERENCES transaction_type(type_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

-- Create index for description lookups
CREATE INDEX idx_transaction_category_description ON transaction_category(description);

-- Create index for category_code lookups within types
CREATE INDEX idx_transaction_category_code ON transaction_category(type_code, category_code);

-- Add table comment
COMMENT ON TABLE transaction_category IS 
'Transaction category reference table from COBOL copybook CVTRA04Y.cpy (RECLN = 60). Defines subcategories within each transaction type for detailed transaction classification.';

COMMENT ON COLUMN transaction_category.type_code IS 
'Transaction type code from COBOL field TRAN-TYPE-CD PIC X(02), references transaction_type';

COMMENT ON COLUMN transaction_category.category_code IS 
'Category code from COBOL field TRAN-CAT-CD PIC 9(04), 4-digit numeric identifier';

COMMENT ON COLUMN transaction_category.description IS 
'Category description from COBOL field TRAN-CAT-TYPE-DESC PIC X(50)';

-- =====================================================================
-- Table 3: transaction_category_balance
-- =====================================================================
-- Source: app/cpy/CVTRA01Y.cpy (RECLN = 50)
-- Purpose: Tracks balance amounts by account, type, and category
-- COBOL Structure:
--   01  TRAN-CAT-BAL-RECORD.
--       05  TRAN-CAT-KEY.
--           10 TRANCAT-ACCT-ID     PIC 9(11).
--           10 TRANCAT-TYPE-CD     PIC X(02).
--           10 TRANCAT-CD          PIC 9(04).
--       05  TRAN-CAT-BAL           PIC S9(09)V99.
-- =====================================================================

CREATE TABLE transaction_category_balance (
    -- Composite primary key part 1: Account identifier
    -- Source: TRANCAT-ACCT-ID PIC 9(11)
    -- 11-digit numeric maps to BIGINT
    account_id BIGINT NOT NULL,
    
    -- Composite primary key part 2: Transaction type code
    -- Source: TRANCAT-TYPE-CD PIC X(02)
    type_code CHAR(2) NOT NULL,
    
    -- Composite primary key part 3: Category code
    -- Source: TRANCAT-CD PIC 9(04)
    category_code SMALLINT NOT NULL,
    
    -- Balance amount for this account/type/category combination
    -- Source: TRAN-CAT-BAL PIC S9(09)V99
    -- COBOL S9(09)V99 = signed 9 integer digits + 2 decimal places
    -- Maps to NUMERIC(11,2) for exact decimal precision
    -- Scale=2 ensures proper rounding matching COBOL COMP-3 behavior
    balance NUMERIC(11, 2) NOT NULL DEFAULT 0.00,
    
    -- Audit columns
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key constraint
    CONSTRAINT pk_transaction_category_balance 
        PRIMARY KEY (account_id, type_code, category_code),
    
    -- Foreign key to account table
    -- Enforces referential integrity with CASCADE delete
    -- When an account is deleted, all its category balances are also deleted
    CONSTRAINT fk_trans_cat_bal_account 
        FOREIGN KEY (account_id) 
        REFERENCES account(account_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    
    -- Foreign key to transaction_category table
    -- Ensures only valid type/category combinations are used
    CONSTRAINT fk_trans_cat_bal_category 
        FOREIGN KEY (type_code, category_code) 
        REFERENCES transaction_category(type_code, category_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    
    -- Check constraint to ensure balance precision
    -- Prevents values with more than 2 decimal places
    CONSTRAINT chk_trans_cat_bal_precision 
        CHECK (balance = ROUND(balance, 2))
);

-- Create index for account-based lookups
CREATE INDEX idx_trans_cat_bal_account ON transaction_category_balance(account_id);

-- Create index for type/category lookups across accounts
CREATE INDEX idx_trans_cat_bal_type_cat ON transaction_category_balance(type_code, category_code);

-- Add table comment
COMMENT ON TABLE transaction_category_balance IS 
'Transaction category balance tracking table from COBOL copybook CVTRA01Y.cpy (RECLN = 50). Maintains running balances by account, transaction type, and category for financial reporting and analysis.';

COMMENT ON COLUMN transaction_category_balance.account_id IS 
'Account identifier from COBOL field TRANCAT-ACCT-ID PIC 9(11), references account table';

COMMENT ON COLUMN transaction_category_balance.type_code IS 
'Transaction type code from COBOL field TRANCAT-TYPE-CD PIC X(02)';

COMMENT ON COLUMN transaction_category_balance.category_code IS 
'Category code from COBOL field TRANCAT-CD PIC 9(04)';

COMMENT ON COLUMN transaction_category_balance.balance IS 
'Balance amount from COBOL field TRAN-CAT-BAL PIC S9(09)V99, NUMERIC(11,2) with scale=2 for exact decimal precision matching COBOL COMP-3';

-- =====================================================================
-- Table 4: disclosure_group
-- =====================================================================
-- Source: app/cpy/CVTRA02Y.cpy (RECLN = 50)
-- Purpose: Interest rate disclosure groups by account group and transaction category
-- COBOL Structure:
--   01  DIS-GROUP-RECORD.
--       05  DIS-GROUP-KEY.
--           10 DIS-ACCT-GROUP-ID    PIC X(10).
--           10 DIS-TRAN-TYPE-CD     PIC X(02).
--           10 DIS-TRAN-CAT-CD      PIC 9(04).
--       05  DIS-INT-RATE            PIC S9(04)V99.
-- =====================================================================

CREATE TABLE disclosure_group (
    -- Composite primary key part 1: Account group identifier
    -- Source: DIS-ACCT-GROUP-ID PIC X(10)
    account_group_id VARCHAR(10) NOT NULL,
    
    -- Composite primary key part 2: Transaction type code
    -- Source: DIS-TRAN-TYPE-CD PIC X(02)
    transaction_type_code CHAR(2) NOT NULL,
    
    -- Composite primary key part 3: Transaction category code
    -- Source: DIS-TRAN-CAT-CD PIC 9(04)
    transaction_category_code SMALLINT NOT NULL,
    
    -- Interest rate for this disclosure group
    -- Source: DIS-INT-RATE PIC S9(04)V99
    -- COBOL S9(04)V99 = signed 4 integer digits + 2 decimal places
    -- Maps to NUMERIC(6,2) for percentage rates (e.g., 19.99%)
    -- Scale=2 ensures proper precision for interest rate calculations
    interest_rate NUMERIC(6, 2) NOT NULL,
    
    -- Audit columns
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Composite primary key constraint
    CONSTRAINT pk_disclosure_group 
        PRIMARY KEY (account_group_id, transaction_type_code, transaction_category_code),
    
    -- Foreign key to transaction_category table
    -- Ensures valid type/category combinations
    CONSTRAINT fk_disclosure_group_category 
        FOREIGN KEY (transaction_type_code, transaction_category_code) 
        REFERENCES transaction_category(type_code, category_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    
    -- Check constraint to ensure valid interest rate range
    -- Interest rates should be between 0% and 99.99%
    CONSTRAINT chk_disclosure_interest_rate_range 
        CHECK (interest_rate >= 0.00 AND interest_rate <= 99.99),
    
    -- Check constraint to ensure interest rate precision
    CONSTRAINT chk_disclosure_interest_rate_precision 
        CHECK (interest_rate = ROUND(interest_rate, 2))
);

-- Create index for account group lookups
CREATE INDEX idx_disclosure_group_account ON disclosure_group(account_group_id);

-- Create index for type/category lookups
CREATE INDEX idx_disclosure_group_type_cat ON disclosure_group(transaction_type_code, transaction_category_code);

-- Add table comment
COMMENT ON TABLE disclosure_group IS 
'Disclosure group reference table from COBOL copybook CVTRA02Y.cpy (RECLN = 50). Defines interest rates by account group and transaction category for regulatory disclosure requirements.';

COMMENT ON COLUMN disclosure_group.account_group_id IS 
'Account group identifier from COBOL field DIS-ACCT-GROUP-ID PIC X(10)';

COMMENT ON COLUMN disclosure_group.transaction_type_code IS 
'Transaction type code from COBOL field DIS-TRAN-TYPE-CD PIC X(02)';

COMMENT ON COLUMN disclosure_group.transaction_category_code IS 
'Transaction category code from COBOL field DIS-TRAN-CAT-CD PIC 9(04)';

COMMENT ON COLUMN disclosure_group.interest_rate IS 
'Interest rate from COBOL field DIS-INT-RATE PIC S9(04)V99, NUMERIC(6,2) with scale=2 for percentage rate precision (e.g., 19.99%)';

-- =====================================================================
-- Migration Complete
-- =====================================================================
-- Summary:
--   ✓ Created transaction_type table (2 columns + audit)
--   ✓ Created transaction_category table (3 columns + audit)
--   ✓ Created transaction_category_balance table (4 columns + audit)
--   ✓ Created disclosure_group table (4 columns + audit)
--   ✓ Applied all primary key constraints
--   ✓ Applied all foreign key constraints
--   ✓ Created all indexes for query optimization
--   ✓ Added comprehensive comments for documentation
--   ✓ Enforced NUMERIC precision with scale=2 for monetary fields
--   ✓ Maintained COBOL data structure traceability
-- =====================================================================
