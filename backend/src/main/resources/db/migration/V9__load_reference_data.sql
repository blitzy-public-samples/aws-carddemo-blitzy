-- =====================================================================
-- Flyway Migration V9: Load Reference Data for CardDemo Application
-- =====================================================================
-- Description: Initializes foundational reference data required for
--              transaction processing in the CardDemo credit card
--              management system. This migration must execute before
--              any test or production data loading.
--
-- Source Files (COBOL Mainframe ASCII format):
--   - app/data/ASCII/trantype.txt  (7 transaction type records)
--   - app/data/ASCII/trancatg.txt  (18 transaction category records)
--   - app/data/ASCII/discgrp.txt   (51 discount group configuration records)
--
-- Target Environment: All environments (development, test, production)
--
-- Dependencies: Requires V1-V8 migrations (table schemas) to be applied
--
-- Author: Blitzy Platform - COBOL to Java Migration
-- Date: 2024
-- =====================================================================

-- =====================================================================
-- PART 1: DROP EXISTING TABLES (Idempotent Execution)
-- =====================================================================

-- Drop tables in reverse dependency order to avoid foreign key violations
DROP TABLE IF EXISTS discount_group CASCADE;
DROP TABLE IF EXISTS transaction_category CASCADE;
DROP TABLE IF EXISTS transaction_type CASCADE;

-- =====================================================================
-- PART 2: CREATE TABLE STRUCTURES
-- =====================================================================

-- ---------------------------------------------------------------------
-- Transaction Type Reference Table
-- ---------------------------------------------------------------------
-- COBOL Source: trantype.txt (7 canonical transaction types)
-- Purpose: Defines the high-level transaction type classification
-- Record Format: [0:2] type_code, [2:72] description, [72:80] filler
-- ---------------------------------------------------------------------

CREATE TABLE transaction_type (
    transaction_type_code VARCHAR(2) NOT NULL,
    type_description VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT pk_transaction_type PRIMARY KEY (transaction_type_code),
    CONSTRAINT chk_type_code_format CHECK (transaction_type_code ~ '^[0-9]{2}$')
);

COMMENT ON TABLE transaction_type IS 'Transaction type reference data migrated from COBOL trantype.txt';
COMMENT ON COLUMN transaction_type.transaction_type_code IS 'Two-digit transaction type code (01-07)';
COMMENT ON COLUMN transaction_type.type_description IS 'Transaction type description from COBOL PIC X(50)';

-- ---------------------------------------------------------------------
-- Transaction Category Reference Table
-- ---------------------------------------------------------------------
-- COBOL Source: trancatg.txt (18 transaction category records)
-- Purpose: Defines detailed transaction categories within each type
-- Record Format: [0:6] category_code, [6:56] description, [56:60] filler
-- ---------------------------------------------------------------------

CREATE TABLE transaction_category (
    transaction_category_code VARCHAR(6) NOT NULL,
    transaction_type_code VARCHAR(2) NOT NULL,
    category_description VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT pk_transaction_category PRIMARY KEY (transaction_category_code),
    CONSTRAINT fk_category_type FOREIGN KEY (transaction_type_code) 
        REFERENCES transaction_type(transaction_type_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    CONSTRAINT chk_category_code_format CHECK (transaction_category_code ~ '^[0-9]{6}$'),
    CONSTRAINT chk_category_type_match CHECK (
        SUBSTRING(transaction_category_code, 1, 2) = transaction_type_code
    )
);

COMMENT ON TABLE transaction_category IS 'Transaction category reference data migrated from COBOL trancatg.txt';
COMMENT ON COLUMN transaction_category.transaction_category_code IS 'Six-digit category code (first 2 digits match type code)';
COMMENT ON COLUMN transaction_category.transaction_type_code IS 'Parent transaction type code';
COMMENT ON COLUMN transaction_category.category_description IS 'Category description from COBOL PIC X(50)';

-- ---------------------------------------------------------------------
-- Discount Group Configuration Table
-- ---------------------------------------------------------------------
-- COBOL Source: discgrp.txt (51 discount group records in 3 blocks)
-- Purpose: Defines discount percentages applied to transaction categories
--          based on account group membership
-- Record Formats:
--   'A' records: [0:1]'A', [1:12]account_group, [12:14]type, [14:18]category_suffix,
--                [18:22]discount_pct, [22]'{', [23:]padding
--   'DEFAULT'/'ZEROAPR': [0:10]keyword, [10:12]type, [12:16]category_suffix,
--                        [16:20]discount_pct, [20]'{', [21:]padding
-- ---------------------------------------------------------------------

CREATE TABLE discount_group (
    discount_group_id SERIAL PRIMARY KEY,
    discount_group_code VARCHAR(10) NOT NULL,
    transaction_type_code CHAR(2) NOT NULL,
    transaction_category_code CHAR(6) NOT NULL,
    discount_percentage NUMERIC(5,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT fk_discount_type FOREIGN KEY (transaction_type_code)
        REFERENCES transaction_type(transaction_type_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    CONSTRAINT fk_discount_category FOREIGN KEY (transaction_category_code)
        REFERENCES transaction_category(transaction_category_code)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    CONSTRAINT chk_discount_code CHECK (discount_group_code IN ('A', 'DEFAULT', 'ZEROAPR')),
    CONSTRAINT chk_discount_percentage CHECK (discount_percentage >= 0.00 AND discount_percentage <= 100.00),
    CONSTRAINT uk_discount_group_combo UNIQUE (discount_group_code, transaction_type_code, transaction_category_code)
);

COMMENT ON TABLE discount_group IS 'Discount group configuration migrated from COBOL discgrp.txt';
COMMENT ON COLUMN discount_group.discount_group_code IS 'Discount group identifier (A, DEFAULT, ZEROAPR)';
COMMENT ON COLUMN discount_group.transaction_type_code IS 'Transaction type code for discount rule';
COMMENT ON COLUMN discount_group.transaction_category_code IS 'Transaction category code for discount rule';
COMMENT ON COLUMN discount_group.discount_percentage IS 'Discount percentage as NUMERIC(5,2) matching COBOL COMP-3 precision';

-- =====================================================================
-- PART 3: INSERT REFERENCE DATA
-- =====================================================================

-- ---------------------------------------------------------------------
-- Transaction Type Data (7 records from trantype.txt)
-- ---------------------------------------------------------------------
-- Source File: app/data/ASCII/trantype.txt
-- Record Count: 7
-- Format: Fixed-width 80-character records
-- Parsing: [0:2] code, [2:72] description (left-justified, space-padded)
-- ---------------------------------------------------------------------

INSERT INTO transaction_type (transaction_type_code, type_description) VALUES
('01', 'Purchase'),
('02', 'Payment'),
('03', 'Credit'),
('04', 'Authorization'),
('05', 'Refund'),
('06', 'Reversal'),
('07', 'Adjustment');

-- ---------------------------------------------------------------------
-- Transaction Category Data (18 records from trancatg.txt)
-- ---------------------------------------------------------------------
-- Source File: app/data/ASCII/trancatg.txt
-- Record Count: 18
-- Format: Variable-length records (60+ characters)
-- Parsing: [0:6] category_code, [6:56] description (left-justified)
-- Category Code Structure: TTCCCC where TT=type (01-07), CCCC=category (0001-9999)
-- ---------------------------------------------------------------------

INSERT INTO transaction_category (transaction_category_code, transaction_type_code, category_description) VALUES
-- Type 01: Purchase (5 categories)
('010001', '01', 'Regular Sales Draft'),
('010002', '01', 'Regular Cash Advance'),
('010003', '01', 'Convenience Check Debit'),
('010004', '01', 'ATM Cash Advance'),
('010005', '01', 'Interest Amount'),

-- Type 02: Payment (3 categories)
('020001', '02', 'Cash payment'),
('020002', '02', 'Electronic payment'),
('020003', '02', 'Check payment'),

-- Type 03: Credit (3 categories)
('030001', '03', 'Credit to Account'),
('030002', '03', 'Credit to Purchase balance'),
('030003', '03', 'Credit to Cash balance'),

-- Type 04: Authorization (3 categories)
('040001', '04', 'Zero dollar authorization'),
('040002', '04', 'Online purchase authorization'),
('040003', '04', 'Travel booking authorization'),

-- Type 05: Refund (1 category)
('050001', '05', 'Refund credit'),

-- Type 06: Reversal (2 categories)
('060001', '06', 'Fraud reversal'),
('060002', '06', 'Non-fraud reversal'),

-- Type 07: Adjustment (1 category)
('070001', '07', 'Sales draft credit adjustment');

-- ---------------------------------------------------------------------
-- Discount Group Configuration (51 records from discgrp.txt)
-- ---------------------------------------------------------------------
-- Source File: app/data/ASCII/discgrp.txt
-- Record Count: 51 (17 'A' + 17 'DEFAULT' + 17 'ZEROAPR')
-- Format: Variable-length records with sentinel '{' character
-- Parsing:
--   'A' records: [0:1]'A', [1:12]account_group, [12:14]type, [14:18]category_suffix,
--                [18:22]discount_pct(4 digits), [22]'{'
--   'DEFAULT'/'ZEROAPR': [0:10]keyword, [10:12]type, [12:16]category_suffix,
--                        [16:20]discount_pct(4 digits), [20]'{'
-- Discount Conversion: 4-digit integer to decimal (0150 -> 1.50%)
-- Category Code Assembly: type + category_suffix (01 + 0001 -> 010001)
-- ---------------------------------------------------------------------

-- Block 1: Account Group 'A' Discount Rules (17 records, lines 1-17)
INSERT INTO discount_group (discount_group_code, transaction_type_code, transaction_category_code, discount_percentage) VALUES
('A', '01', '010001', 1.50),   -- Purchase: Regular Sales Draft, 1.50%
('A', '01', '010002', 2.50),   -- Purchase: Regular Cash Advance, 2.50%
('A', '01', '010003', 2.50),   -- Purchase: Convenience Check Debit, 2.50%
('A', '01', '010004', 2.50),   -- Purchase: ATM Cash Advance, 2.50%
('A', '02', '020001', 0.00),   -- Payment: Cash payment, 0.00%
('A', '02', '020002', 0.00),   -- Payment: Electronic payment, 0.00%
('A', '02', '020003', 0.00),   -- Payment: Check payment, 0.00%
('A', '03', '030001', 0.00),   -- Credit: Credit to Account, 0.00%
('A', '03', '030002', 0.00),   -- Credit: Credit to Purchase balance, 0.00%
('A', '03', '030003', 0.00),   -- Credit: Credit to Cash balance, 0.00%
('A', '04', '040001', 1.50),   -- Authorization: Zero dollar authorization, 1.50%
('A', '04', '040002', 1.50),   -- Authorization: Online purchase authorization, 1.50%
('A', '04', '040003', 1.50),   -- Authorization: Travel booking authorization, 1.50%
('A', '05', '050001', 1.50),   -- Refund: Refund credit, 1.50%
('A', '06', '060001', 1.50),   -- Reversal: Fraud reversal, 1.50%
('A', '06', '060002', 1.50),   -- Reversal: Non-fraud reversal, 1.50%
('A', '07', '070001', 1.50);   -- Adjustment: Sales draft credit adjustment, 1.50%

-- Block 2: DEFAULT Discount Group Rules (17 records, lines 18-34)
INSERT INTO discount_group (discount_group_code, transaction_type_code, transaction_category_code, discount_percentage) VALUES
('DEFAULT', '01', '010001', 1.50),   -- Purchase: Regular Sales Draft, 1.50%
('DEFAULT', '01', '010002', 2.50),   -- Purchase: Regular Cash Advance, 2.50%
('DEFAULT', '01', '010003', 2.50),   -- Purchase: Convenience Check Debit, 2.50%
('DEFAULT', '01', '010004', 2.50),   -- Purchase: ATM Cash Advance, 2.50%
('DEFAULT', '02', '020001', 0.00),   -- Payment: Cash payment, 0.00%
('DEFAULT', '02', '020002', 0.00),   -- Payment: Electronic payment, 0.00%
('DEFAULT', '02', '020003', 0.00),   -- Payment: Check payment, 0.00%
('DEFAULT', '03', '030001', 0.00),   -- Credit: Credit to Account, 0.00%
('DEFAULT', '03', '030002', 0.00),   -- Credit: Credit to Purchase balance, 0.00%
('DEFAULT', '03', '030003', 0.00),   -- Credit: Credit to Cash balance, 0.00%
('DEFAULT', '04', '040001', 1.50),   -- Authorization: Zero dollar authorization, 1.50%
('DEFAULT', '04', '040002', 1.50),   -- Authorization: Online purchase authorization, 1.50%
('DEFAULT', '04', '040003', 1.50),   -- Authorization: Travel booking authorization, 1.50%
('DEFAULT', '05', '050001', 1.50),   -- Refund: Refund credit, 1.50%
('DEFAULT', '06', '060001', 1.50),   -- Reversal: Fraud reversal, 1.50%
('DEFAULT', '06', '060002', 1.50),   -- Reversal: Non-fraud reversal, 1.50%
('DEFAULT', '07', '070001', 0.00);   -- Adjustment: Sales draft credit adjustment, 0.00%

-- Block 3: ZEROAPR Promotional Group Rules (17 records, lines 35-51)
INSERT INTO discount_group (discount_group_code, transaction_type_code, transaction_category_code, discount_percentage) VALUES
('ZEROAPR', '01', '010001', 0.00),   -- Purchase: Regular Sales Draft, 0.00%
('ZEROAPR', '01', '010002', 0.00),   -- Purchase: Regular Cash Advance, 0.00%
('ZEROAPR', '01', '010003', 0.00),   -- Purchase: Convenience Check Debit, 0.00%
('ZEROAPR', '01', '010004', 0.00),   -- Purchase: ATM Cash Advance, 0.00%
('ZEROAPR', '02', '020001', 0.00),   -- Payment: Cash payment, 0.00%
('ZEROAPR', '02', '020002', 0.00),   -- Payment: Electronic payment, 0.00%
('ZEROAPR', '02', '020003', 0.00),   -- Payment: Check payment, 0.00%
('ZEROAPR', '03', '030001', 0.00),   -- Credit: Credit to Account, 0.00%
('ZEROAPR', '03', '030002', 0.00),   -- Credit: Credit to Purchase balance, 0.00%
('ZEROAPR', '03', '030003', 0.00),   -- Credit: Credit to Cash balance, 0.00%
('ZEROAPR', '04', '040001', 0.00),   -- Authorization: Zero dollar authorization, 0.00%
('ZEROAPR', '04', '040002', 0.00),   -- Authorization: Online purchase authorization, 0.00%
('ZEROAPR', '04', '040003', 0.00),   -- Authorization: Travel booking authorization, 0.00%
('ZEROAPR', '05', '050001', 0.00),   -- Refund: Refund credit, 0.00%
('ZEROAPR', '06', '060001', 0.00),   -- Reversal: Fraud reversal, 0.00%
('ZEROAPR', '06', '060002', 0.00),   -- Reversal: Non-fraud reversal, 0.00%
('ZEROAPR', '07', '070001', 0.00);   -- Adjustment: Sales draft credit adjustment, 0.00%

-- =====================================================================
-- PART 4: CREATE INDEXES FOR PERFORMANCE
-- =====================================================================

-- Index for transaction category lookups by type
CREATE INDEX idx_transaction_category_type 
    ON transaction_category(transaction_type_code);

-- Index for discount group lookups by group code
CREATE INDEX idx_discount_group_code 
    ON discount_group(discount_group_code);

-- Index for discount group lookups by type and category
CREATE INDEX idx_discount_type_category 
    ON discount_group(transaction_type_code, transaction_category_code);

-- =====================================================================
-- PART 5: DATA VERIFICATION
-- =====================================================================

-- Verify transaction type record count (expected: 7)
SELECT 
    'TRANSACTION_TYPE' AS table_name,
    COUNT(*) AS actual_count,
    7 AS expected_count,
    CASE WHEN COUNT(*) = 7 THEN 'PASS' ELSE 'FAIL' END AS status
FROM transaction_type;

-- Verify transaction category record count (expected: 18)
SELECT 
    'TRANSACTION_CATEGORY' AS table_name,
    COUNT(*) AS actual_count,
    18 AS expected_count,
    CASE WHEN COUNT(*) = 18 THEN 'PASS' ELSE 'FAIL' END AS status
FROM transaction_category;

-- Verify discount group record count (expected: 51)
SELECT 
    'DISCOUNT_GROUP' AS table_name,
    COUNT(*) AS actual_count,
    51 AS expected_count,
    CASE WHEN COUNT(*) = 51 THEN 'PASS' ELSE 'FAIL' END AS status
FROM discount_group;

-- Verify discount group distribution by group code
SELECT 
    discount_group_code,
    COUNT(*) AS record_count,
    CASE 
        WHEN discount_group_code = 'A' AND COUNT(*) = 17 THEN 'PASS'
        WHEN discount_group_code = 'DEFAULT' AND COUNT(*) = 17 THEN 'PASS'
        WHEN discount_group_code = 'ZEROAPR' AND COUNT(*) = 17 THEN 'PASS'
        ELSE 'FAIL'
    END AS status
FROM discount_group
GROUP BY discount_group_code
ORDER BY discount_group_code;

-- Verify all category codes in discount_group have valid foreign key references
SELECT 
    'FOREIGN_KEY_INTEGRITY' AS check_name,
    COUNT(*) AS orphaned_records,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS status
FROM discount_group dg
LEFT JOIN transaction_category tc 
    ON dg.transaction_category_code = tc.transaction_category_code
WHERE tc.transaction_category_code IS NULL;

-- Display sample data for validation
SELECT 
    tt.transaction_type_code,
    tt.type_description,
    COUNT(tc.transaction_category_code) AS category_count
FROM transaction_type tt
LEFT JOIN transaction_category tc 
    ON tt.transaction_type_code = tc.transaction_type_code
GROUP BY tt.transaction_type_code, tt.type_description
ORDER BY tt.transaction_type_code;

-- =====================================================================
-- END OF MIGRATION V9
-- =====================================================================
