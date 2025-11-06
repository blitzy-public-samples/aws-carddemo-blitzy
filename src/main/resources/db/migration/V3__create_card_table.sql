-- =====================================================================
-- Flyway Migration: V3__create_card_table.sql
-- Description: Create card table from COBOL copybook CVACT02Y.cpy
-- Source: app/cpy/CVACT02Y.cpy (RECLN 150 bytes)
--         app/cpy/CVACT03Y.cpy (Card cross-reference structure)
-- Author: CardDemo Modernization Team
-- Date: 2024
-- =====================================================================

-- Card table stores credit/debit card information associated with accounts
-- Replaces VSAM KSDS file CARDDAT with CXACAIX alternate index cross-reference
-- Primary key: card_number (16-character card number)
-- Foreign key: account_id references account table

CREATE TABLE IF NOT EXISTS card (
    -- CARD-NUM PIC X(16) - 16-character card number (PRIMARY KEY)
    -- Card number format: 16 digits as per payment card industry standards
    card_number VARCHAR(16) NOT NULL,
    
    -- CARD-ACCT-ID PIC 9(11) - 11-digit account ID (FOREIGN KEY)
    -- References account table enforcing referential integrity
    -- Replaces CXACAIX VSAM alternate index cross-reference from CVACT03Y.cpy
    account_id BIGINT NOT NULL,
    
    -- CARD-CVV-CD PIC 9(03) - 3-digit card verification value
    -- CVV/CVC code printed on card for security verification
    cvv_code SMALLINT NOT NULL,
    
    -- CARD-EMBOSSED-NAME PIC X(50) - Cardholder name as embossed on physical card
    -- Maximum 50 characters for name display on card surface
    embossed_name VARCHAR(50) NOT NULL,
    
    -- CARD-EXPIRAION-DATE PIC X(10) - Card expiration date (note COBOL typo 'EXPIRAION')
    -- Converted from COBOL PIC X(10) YYYY-MM-DD string format to PostgreSQL DATE type
    -- Requires DateUtility.java conversion from COBOL date representation
    expiration_date DATE NOT NULL,
    
    -- CARD-ACTIVE-STATUS PIC X(01) - Card active status flag
    -- 'Y' = Active card, can be used for transactions
    -- 'N' = Inactive card, blocked from transactions
    active_status CHAR(1) NOT NULL DEFAULT 'Y',
    
    -- Audit trail columns for tracking record lifecycle
    -- Not present in COBOL structure but required for cloud-native application
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Primary key constraint on card number
    CONSTRAINT pk_card PRIMARY KEY (card_number),
    
    -- Foreign key constraint to account table
    -- ON DELETE CASCADE ensures cards are removed when parent account is deleted
    -- Replaces CXACAIX alternate index cross-reference structure
    CONSTRAINT fk_card_account FOREIGN KEY (account_id) 
        REFERENCES account(account_id) 
        ON DELETE CASCADE 
        ON UPDATE CASCADE,
    
    -- Check constraint for active_status valid values
    -- Enforces data integrity matching COBOL 88-level condition name pattern
    CONSTRAINT chk_card_active_status CHECK (active_status IN ('Y', 'N')),
    
    -- Check constraint for CVV code range (3-digit value)
    -- Ensures CVV is between 0 and 999
    CONSTRAINT chk_card_cvv_range CHECK (cvv_code >= 0 AND cvv_code <= 999),
    
    -- Check constraint for card number format (16 digits)
    -- Validates card number length matches payment card industry standard
    CONSTRAINT chk_card_number_length CHECK (LENGTH(card_number) = 16),
    
    -- Check constraint for expiration date (must be in future at creation)
    -- Prevents creation of already-expired cards
    CONSTRAINT chk_card_expiration_future CHECK (expiration_date >= CURRENT_DATE)
);

-- =====================================================================
-- Indexes for performance optimization
-- =====================================================================

-- Index on account_id for account-to-cards joins
-- Supports card list display with pagination of 7 cards per page (per UI requirements)
-- Used by CardListService.java to retrieve all cards for a given account
-- Replaces VSAM alternate index access pattern from CXACAIX cross-reference
CREATE INDEX idx_card_account_id ON card(account_id);

-- Index on expiration_date for batch expiration processing jobs
-- Supports CardDataLoadJob.java and expiration monitoring batch processes
-- Allows efficient queries to find cards expiring within date ranges
-- Used for automated card renewal and notification processes
CREATE INDEX idx_card_expiration_date ON card(expiration_date);

-- Composite index on account_id and active_status
-- Optimizes queries for active cards by account (common query pattern)
-- Supports CardListService pagination with status filtering
CREATE INDEX idx_card_account_status ON card(account_id, active_status);

-- Index on embossed_name for cardholder name searches
-- Supports administrative searches by cardholder name
-- Used by admin interfaces for customer service operations
CREATE INDEX idx_card_embossed_name ON card(embossed_name);

-- =====================================================================
-- Table comment documenting COBOL source and migration details
-- =====================================================================

COMMENT ON TABLE card IS 
'Card master table migrated from VSAM KSDS file CARDDAT. 
Source: COBOL copybook CVACT02Y.cpy (RECLN 150 bytes).
Cross-reference: CVACT03Y.cpy (Card-Account-Customer xref, RECLN 50 bytes).
VSAM alternate index CXACAIX replaced by foreign key constraint to account table.
Primary key: card_number (16-character card number).
Foreign key: account_id references account(account_id) with CASCADE delete.
Supports pagination: 7 cards per page (per BMS screen COCRDLIM.bms).
Date conversion: COBOL PIC X(10) YYYY-MM-DD format to PostgreSQL DATE via DateUtility.java.
Migration version: V3 (third migration in sequence after customer and account tables).';

-- Column-level comments for documentation and maintenance

COMMENT ON COLUMN card.card_number IS 
'16-character card number (PRIMARY KEY). Source: CARD-NUM PIC X(16) from CVACT02Y.cpy. Format: 16 digits per payment card industry standards.';

COMMENT ON COLUMN card.account_id IS 
'Account ID foreign key (11-digit). Source: CARD-ACCT-ID PIC 9(11) from CVACT02Y.cpy. References account(account_id) with CASCADE operations. Replaces CXACAIX alternate index cross-reference.';

COMMENT ON COLUMN card.cvv_code IS 
'Card Verification Value (3-digit security code). Source: CARD-CVV-CD PIC 9(03) from CVACT02Y.cpy. Range: 0-999.';

COMMENT ON COLUMN card.embossed_name IS 
'Cardholder name as embossed on physical card (max 50 characters). Source: CARD-EMBOSSED-NAME PIC X(50) from CVACT02Y.cpy.';

COMMENT ON COLUMN card.expiration_date IS 
'Card expiration date. Source: CARD-EXPIRAION-DATE PIC X(10) from CVACT02Y.cpy (note COBOL typo). Converted from YYYY-MM-DD string to PostgreSQL DATE type via DateUtility.java.';

COMMENT ON COLUMN card.active_status IS 
'Card active status flag. Source: CARD-ACTIVE-STATUS PIC X(01) from CVACT02Y.cpy. Values: Y=Active, N=Inactive. Default: Y.';

COMMENT ON COLUMN card.created_at IS 
'Record creation timestamp. Not present in COBOL source. Added for audit trail in cloud-native application.';

COMMENT ON COLUMN card.updated_at IS 
'Record last update timestamp. Not present in COBOL source. Added for audit trail in cloud-native application.';

-- =====================================================================
-- Trigger for automatic updated_at timestamp maintenance
-- =====================================================================

-- Function to update the updated_at timestamp
CREATE OR REPLACE FUNCTION update_card_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger to automatically update updated_at on row modification
CREATE TRIGGER trg_card_updated_at
    BEFORE UPDATE ON card
    FOR EACH ROW
    EXECUTE FUNCTION update_card_updated_at();

-- =====================================================================
-- End of migration V3__create_card_table.sql
-- =====================================================================
