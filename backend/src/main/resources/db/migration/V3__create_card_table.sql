-- ================================================================
-- Flyway Migration: V3__create_card_table.sql
-- Description: Create card table from CARDDAT VSAM KSDS
-- Source: CVACT02Y.cpy copybook (KEYLEN=16, MAXLRECL=150, REC-TOTAL=50)
-- Migration Date: 2024
-- ================================================================

-- ================================================================
-- Card Table Definition
-- ================================================================
-- Transformation from VSAM CARDDAT KSDS to PostgreSQL relational table
-- Original VSAM specification:
--   - KEYLEN: 16 (primary key on CARD-NUM)
--   - MAXLRECL: 150 bytes
--   - Approximate records: 50
-- ================================================================

CREATE TABLE card (
  -- ================================================================
  -- Primary Key Field
  -- ================================================================
  -- Source: CARD-NUM PIC X(16) from CVACT02Y.cpy
  -- VSAM primary key field (KEYLEN=16)
  -- Card number is 16-digit unique identifier
  card_number VARCHAR(16) NOT NULL,
  
  -- ================================================================
  -- Foreign Key Field
  -- ================================================================
  -- Source: CARD-ACCT-ID PIC 9(11) from CVACT02Y.cpy
  -- Links card to account table
  -- Note: Stored as VARCHAR to preserve leading zeros if present
  account_id VARCHAR(11) NOT NULL,
  
  -- ================================================================
  -- Card Security and Details
  -- ================================================================
  -- Source: CARD-CVV-CD PIC 9(03) from CVACT02Y.cpy
  -- Card Verification Value (3-digit security code)
  -- Stored as VARCHAR to preserve leading zeros
  cvv_code VARCHAR(3) NOT NULL,
  
  -- Source: CARD-EMBOSSED-NAME PIC X(50) from CVACT02Y.cpy
  -- Name printed/embossed on physical card
  -- Maximum 50 characters as per COBOL specification
  embossed_name VARCHAR(50) NOT NULL,
  
  -- Source: CARD-EXPIRAION-DATE PIC X(10) from CVACT02Y.cpy
  -- Note: Original copybook has typo "EXPIRAION" instead of "EXPIRATION"
  -- Converted from COBOL date string format to PostgreSQL DATE type
  -- Expected COBOL format: YYYY-MM-DD or similar 10-char representation
  card_expiration_date DATE NOT NULL,
  
  -- ================================================================
  -- Card Status Field
  -- ================================================================
  -- Source: CARD-ACTIVE-STATUS PIC X(01) from CVACT02Y.cpy
  -- Valid values per Section 0.9 (88-level condition preservation):
  --   'A' = Active (default)
  --   'E' = Expired
  --   'B' = Blocked
  --   'C' = Cancelled
  -- CHECK constraint enforces valid status codes
  card_status VARCHAR(1) NOT NULL DEFAULT 'A',
  
  -- ================================================================
  -- Audit Fields (not in original COBOL)
  -- ================================================================
  -- Added per modern best practices for change tracking
  -- Required for regulatory compliance and audit trail per Section 0.9
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  
  -- ================================================================
  -- Primary Key Constraint
  -- ================================================================
  CONSTRAINT pk_card PRIMARY KEY (card_number),
  
  -- ================================================================
  -- Check Constraints
  -- ================================================================
  -- Enforces valid card status values matching COBOL 88-level conditions
  CONSTRAINT chk_card_status CHECK (card_status IN ('A', 'E', 'B', 'C'))
);

-- ================================================================
-- Table and Column Comments for Documentation
-- ================================================================
-- Provides comprehensive metadata for database documentation

COMMENT ON TABLE card IS 
'Card records migrated from CARDDAT VSAM KSDS file. Original VSAM specifications: KEYLEN=16, MAXLRECL=150, approximately 50 records. Structure defined by CVACT02Y.cpy copybook. Maintains functional equivalence with mainframe card management system.';

COMMENT ON COLUMN card.card_number IS 
'Card number (16 characters). Source: CARD-NUM PIC X(16) from CVACT02Y.cpy. VSAM primary key field (KEYLEN=16). Unique identifier for each card record.';

COMMENT ON COLUMN card.account_id IS 
'Account ID (11 digits). Source: CARD-ACCT-ID PIC 9(11) from CVACT02Y.cpy. Foreign key reference to account table. Links card to owning account.';

COMMENT ON COLUMN card.cvv_code IS 
'Card Verification Value (3 digits). Source: CARD-CVV-CD PIC 9(03) from CVACT02Y.cpy. Security code for card validation in transactions.';

COMMENT ON COLUMN card.embossed_name IS 
'Name embossed on card (up to 50 characters). Source: CARD-EMBOSSED-NAME PIC X(50) from CVACT02Y.cpy. Cardholder name as it appears on physical card.';

COMMENT ON COLUMN card.card_expiration_date IS 
'Card expiration date. Source: CARD-EXPIRAION-DATE PIC X(10) from CVACT02Y.cpy. Converted from COBOL date string to PostgreSQL DATE type. Card becomes invalid after this date.';

COMMENT ON COLUMN card.card_status IS 
'Card status code (1 character). Source: CARD-ACTIVE-STATUS PIC X(01) from CVACT02Y.cpy. Valid values: A=Active, E=Expired, B=Blocked, C=Cancelled. Matches COBOL 88-level conditions per Section 0.9.';

COMMENT ON COLUMN card.created_at IS 
'Record creation timestamp. Audit field added for compliance and change tracking. Not present in original COBOL structure.';

COMMENT ON COLUMN card.updated_at IS 
'Record last update timestamp. Audit field added for compliance and change tracking. Automatically updated via trigger. Not present in original COBOL structure.';

-- ================================================================
-- Indexes for Performance Optimization
-- ================================================================
-- Primary index on card_number is automatically created by PRIMARY KEY constraint
-- Additional indexes for common query patterns

-- Secondary index on account_id for efficient foreign key lookups
-- Used when querying all cards for a specific account (CardListService)
-- Matches VSAM alternate index access patterns per Section 0.3
CREATE INDEX idx_card_account_id ON card(account_id);

-- Composite index for card status and expiration date queries
-- Supports efficient queries for active/expired card identification
-- Used in batch processing and card validation operations
CREATE INDEX idx_card_status_expiry ON card(card_status, card_expiration_date);

-- ================================================================
-- Trigger Function for Automatic Timestamp Updates
-- ================================================================
-- Maintains updated_at timestamp automatically on row modifications
-- Ensures audit trail accuracy per Section 0.9 requirements

CREATE OR REPLACE FUNCTION update_card_timestamp()
RETURNS TRIGGER AS $$
BEGIN
  -- Set updated_at to current timestamp whenever row is modified
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ================================================================
-- Trigger Definition
-- ================================================================
-- Fires before each UPDATE operation to maintain updated_at timestamp

CREATE TRIGGER trg_card_updated_at
  BEFORE UPDATE ON card
  FOR EACH ROW
  EXECUTE FUNCTION update_card_timestamp();

-- ================================================================
-- End of Migration V3
-- ================================================================
-- This migration creates the card table with full functional equivalence
-- to the CARDDAT VSAM KSDS file from the mainframe CardDemo application.
-- Foreign key constraints will be added in V8__create_foreign_keys.sql
-- per the migration strategy defined in Section 0.6.
-- ================================================================
