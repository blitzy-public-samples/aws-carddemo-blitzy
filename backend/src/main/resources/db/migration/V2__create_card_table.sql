-- =====================================================================
-- Flyway Migration V2: Create Card Table
-- =====================================================================
-- Source: COBOL copybook CVACT02Y.cpy (CARD-RECORD structure, 150 bytes)
-- Purpose: Creates the card master table in PostgreSQL, migrating from
--          VSAM CARDFILE dataset
--
-- Migration Date: 2024
-- COBOL Source: app/cpy/CVACT02Y.cpy
-- Target Table: card
--
-- Business Context:
-- This table stores credit card master data including card numbers,
-- associated accounts, cardholder names, expiration dates, and status.
-- Multiple cards can be associated with a single account.
--
-- =====================================================================

-- ---------------------------------------------------------------------
-- COBOL to PostgreSQL Data Type Mapping
-- ---------------------------------------------------------------------
-- CARD-NUM (PIC X(16))              → VARCHAR(16)  [Primary Key, PII]
-- CARD-ACCT-ID (PIC 9(11))          → BIGINT       [Foreign Key to account]
-- CARD-CVV-CD (PIC 9(03))           → NOT STORED   [PCI-DSS compliance]
-- CARD-EMBOSSED-NAME (PIC X(50))    → VARCHAR(50)
-- CARD-EXPIRAION-DATE (PIC X(10))   → DATE         [Typo fixed in column name]
-- CARD-ACTIVE-STATUS (PIC X(01))    → CHAR(1)
-- FILLER (PIC X(59))                → NOT MIGRATED [Unused padding]
--
-- Additional Audit Columns:
-- created_at                        → TIMESTAMP    [Record creation timestamp]
-- updated_at                        → TIMESTAMP    [Record last update timestamp]
-- version                           → INTEGER      [JPA optimistic locking]
-- ---------------------------------------------------------------------

-- =====================================================================
-- SECURITY CONSIDERATIONS (CRITICAL)
-- =====================================================================
-- 1. CARD-NUM is sensitive PII data and must be protected:
--    - In production: Implement column-level encryption or tokenization
--    - Consider using PostgreSQL pgcrypto extension for encryption
--    - Alternative: Use external secure vault (HashiCorp Vault, AWS KMS)
--
-- 2. CVV codes (CARD-CVV-CD) are NOT stored per PCI-DSS requirements:
--    - PCI-DSS Section 3.2: Do not store sensitive authentication data
--    - CVV codes must never be stored after authorization
--    - This field is intentionally omitted from the table
--
-- 3. Card expiration dates are sensitive data:
--    - Required for transaction processing
--    - Must be protected with appropriate access controls
--
-- 4. Access Control Requirements:
--    - Implement role-based access control (RBAC) at application layer
--    - Log all access to card data for audit purposes
--    - Mask card numbers in application logs and user interfaces
-- =====================================================================

-- =====================================================================
-- Business Rules Preserved from COBOL System
-- =====================================================================
-- 1. Card number is unique identifier (VSAM primary key)
-- 2. Each card must be associated with a valid account
-- 3. Multiple cards can exist for a single account
-- 4. Card status is single character flag (A=Active, I=Inactive, etc.)
-- 5. Expiration date is mandatory for all cards
-- 6. Card account relationship cannot be changed once established
--    (enforced by ON UPDATE CASCADE for account ID changes)
-- 7. Cards cannot be deleted if account still exists (ON DELETE RESTRICT)
-- =====================================================================

CREATE TABLE card (
    -- Primary card identifier (16-digit card number)
    -- Source: CARD-NUM PIC X(16)
    -- VSAM Primary Key: Yes
    -- PII Data: Yes (requires encryption/tokenization in production)
    card_num VARCHAR(16) PRIMARY KEY,
    
    -- Foreign key to account table
    -- Source: CARD-ACCT-ID PIC 9(11)
    -- Business Rule: Card must be associated with valid account
    card_acct_id BIGINT NOT NULL,
    
    -- Cardholder name embossed on physical card
    -- Source: CARD-EMBOSSED-NAME PIC X(50)
    -- May differ from customer legal name
    card_embossed_name VARCHAR(50),
    
    -- Card expiration date (MM/YYYY format in application layer)
    -- Source: CARD-EXPIRAION-DATE PIC X(10) [typo in COBOL fixed]
    -- Business Rule: Required for transaction authorization
    -- Note: COBOL stored as string "YYYY-MM-DD", PostgreSQL uses native DATE
    card_expiration_date DATE NOT NULL,
    
    -- Card status flag
    -- Source: CARD-ACTIVE-STATUS PIC X(01)
    -- Values: 'A'=Active, 'I'=Inactive, 'L'=Lost, 'S'=Stolen, 'E'=Expired
    -- Business Rule: Only active cards can process transactions
    card_active_status CHAR(1) NOT NULL,
    
    -- Audit timestamp: record creation time
    -- Added for Spring Boot/JPA auditing
    -- Automatically set on INSERT
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    -- Audit timestamp: record last modification time
    -- Added for Spring Boot/JPA auditing
    -- Should be updated by application on every UPDATE
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    -- Optimistic locking version for JPA
    -- Incremented on each UPDATE to prevent lost updates
    -- Used by @Version annotation in Card.java entity
    version INTEGER DEFAULT 0 NOT NULL,
    
    -- Foreign key constraint to account table (created in V1 migration)
    -- Business Rule: Card cannot exist without valid account
    -- ON DELETE RESTRICT: Cannot delete account if cards exist
    -- ON UPDATE CASCADE: Account ID changes propagate to cards
    CONSTRAINT fk_card_account FOREIGN KEY (card_acct_id) 
        REFERENCES account(acct_id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,
    
    -- Check constraint: card_active_status must be valid value
    -- Enforces referential integrity for status codes
    CONSTRAINT chk_card_status CHECK (card_active_status IN ('A', 'I', 'L', 'S', 'E', 'C')),
    
    -- Check constraint: version must be non-negative
    CONSTRAINT chk_card_version CHECK (version >= 0)
);

-- =====================================================================
-- Index Definitions (Replicate VSAM Access Patterns)
-- =====================================================================

-- Index on account ID for reverse lookup (equivalent to VSAM alternate index)
-- Query pattern: "Find all cards for account X"
-- Used by: CardController.getCardsByAccount(), batch processing
-- Performance: Enables fast lookup of cards by account (sub-10ms)
CREATE INDEX idx_card_acct ON card(card_acct_id);

-- Index on card status for filtering active/inactive cards
-- Query pattern: "Find all cards with status X"
-- Used by: Batch jobs processing expired cards, card list displays
-- Performance: Enables fast filtering by status
CREATE INDEX idx_card_status ON card(card_active_status);

-- Index on expiration date for batch processing
-- Query pattern: "Find cards expiring in next 30 days"
-- Used by: CBACT04C batch job (card expiration processing)
-- Performance: Enables efficient date range queries
CREATE INDEX idx_card_expiration ON card(card_expiration_date);

-- Composite index for common query: active cards by account
-- Query pattern: "Find all active cards for account X"
-- Used by: Transaction authorization, account inquiry screens
-- Performance: Covers query without table access (index-only scan)
CREATE INDEX idx_card_acct_status ON card(card_acct_id, card_active_status);

-- =====================================================================
-- Table and Column Comments (Documentation)
-- =====================================================================

COMMENT ON TABLE card IS 
'Card master table converted from VSAM CARDFILE dataset (CVACT02Y.cpy). '
'Stores credit card master data including card numbers, account associations, '
'cardholder names, expiration dates, and status codes. Original COBOL record '
'length: 150 bytes. CVV codes are intentionally NOT stored per PCI-DSS compliance.';

COMMENT ON COLUMN card.card_num IS 
'Primary card number (16-digit, PII data). Source: COBOL PIC X(16). '
'VSAM primary key. MUST be encrypted/tokenized in production environment. '
'Format: Typically follows ISO/IEC 7812 numbering standard.';

COMMENT ON COLUMN card.card_acct_id IS 
'Foreign key to account table. Source: COBOL PIC 9(11). '
'Business rule: Each card must be associated with exactly one account, '
'but an account may have multiple cards. ON DELETE RESTRICT prevents '
'account deletion if cards exist.';

COMMENT ON COLUMN card.card_embossed_name IS 
'Cardholder name embossed on physical card. Source: COBOL PIC X(50). '
'May differ from customer legal name in customer table. '
'Nullable to support virtual/digital cards without embossing.';

COMMENT ON COLUMN card.card_expiration_date IS 
'Card expiration date. Source: COBOL PIC X(10) CARD-EXPIRAION-DATE (typo fixed). '
'Original COBOL format: "YYYY-MM-DD" string. PostgreSQL uses native DATE type. '
'Business rule: Required for transaction authorization. Cards expire at end of month.';

COMMENT ON COLUMN card.card_active_status IS 
'Card status flag. Source: COBOL PIC X(01). '
'Valid values: A=Active, I=Inactive, L=Lost, S=Stolen, E=Expired, C=Closed. '
'Business rule: Only active (A) cards can process transactions.';

COMMENT ON COLUMN card.created_at IS 
'Record creation timestamp. Not in original COBOL structure. '
'Added for audit trail and Spring Boot JPA @CreatedDate support. '
'Automatically set on INSERT.';

COMMENT ON COLUMN card.updated_at IS 
'Record last modification timestamp. Not in original COBOL structure. '
'Added for audit trail and Spring Boot JPA @LastModifiedDate support. '
'Should be updated by application on every UPDATE operation.';

COMMENT ON COLUMN card.version IS 
'Optimistic locking version for JPA. Not in original COBOL structure. '
'Incremented on each UPDATE. Used by @Version annotation in Card.java entity '
'to prevent lost updates in concurrent modification scenarios. '
'Equivalent to VSAM RBA checking in COBOL system.';

-- =====================================================================
-- Migration Validation Queries
-- =====================================================================
-- Use these queries to validate successful migration:
--
-- 1. Verify table structure:
--    SELECT column_name, data_type, character_maximum_length, is_nullable
--    FROM information_schema.columns
--    WHERE table_name = 'card'
--    ORDER BY ordinal_position;
--
-- 2. Verify indexes:
--    SELECT indexname, indexdef
--    FROM pg_indexes
--    WHERE tablename = 'card';
--
-- 3. Verify foreign key constraint:
--    SELECT conname, contype, confdeltype, confupdtype
--    FROM pg_constraint
--    WHERE conrelid = 'card'::regclass;
--
-- 4. Verify record count after data load:
--    SELECT COUNT(*) FROM card;
--    Expected: ~100,000 cards (per Section 0.2.7 VSAM Dataset Inventory)
--
-- 5. Verify account relationship:
--    SELECT c.card_num, c.card_acct_id, a.acct_id
--    FROM card c
--    LEFT JOIN account a ON c.card_acct_id = a.acct_id
--    WHERE a.acct_id IS NULL;
--    Expected: 0 rows (all cards must have valid accounts)
-- =====================================================================

-- End of migration V2
