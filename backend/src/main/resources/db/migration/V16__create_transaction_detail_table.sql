-- Flyway Migration V16: Create Transaction Detail Table
-- Purpose: Create transaction_detail table for storing additional transactional metadata
-- and audit information as defined in TransactionDetail.java entity
-- Author: Blitzy Platform - CardDemo Migration
-- Date: 2025-11-05

-- Create transaction_detail table
CREATE TABLE transaction_detail (
    transaction_detail_id BIGSERIAL NOT NULL,
    transaction_id VARCHAR(16) NOT NULL,
    authorization_code VARCHAR(6),
    response_code VARCHAR(2),
    processor_name VARCHAR(50),
    terminal_id VARCHAR(20),
    extended_description VARCHAR(500),
    merchant_category_code VARCHAR(4),
    currency_code VARCHAR(3) NOT NULL DEFAULT 'USD',
    exchange_rate NUMERIC(10,6),
    settlement_date DATE,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP,
    CONSTRAINT pk_transaction_detail PRIMARY KEY (transaction_detail_id),
    CONSTRAINT uk_transaction_detail_transaction_id UNIQUE (transaction_id),
    CONSTRAINT fk_transaction_detail_transaction 
        FOREIGN KEY (transaction_id) 
        REFERENCES transaction(transaction_id) 
        ON DELETE CASCADE
);

-- Create index on authorization_code for lookup queries
CREATE INDEX idx_transaction_detail_authorization_code 
    ON transaction_detail(authorization_code);

-- Create index on settlement_date for reconciliation queries
CREATE INDEX idx_transaction_detail_settlement_date 
    ON transaction_detail(settlement_date);

-- Create index on merchant_category_code for category analysis
CREATE INDEX idx_transaction_detail_merchant_category_code 
    ON transaction_detail(merchant_category_code);

-- Add comments for documentation
COMMENT ON TABLE transaction_detail IS 'Stores additional transactional metadata and audit information for transactions';
COMMENT ON COLUMN transaction_detail.transaction_detail_id IS 'Primary key - auto-generated identifier';
COMMENT ON COLUMN transaction_detail.transaction_id IS 'Foreign key to transaction table - unique one-to-one relationship';
COMMENT ON COLUMN transaction_detail.authorization_code IS 'Payment processor authorization code (6 characters)';
COMMENT ON COLUMN transaction_detail.response_code IS 'Payment processor response code (2 characters)';
COMMENT ON COLUMN transaction_detail.processor_name IS 'Payment processor handling transaction (50 characters)';
COMMENT ON COLUMN transaction_detail.terminal_id IS 'POS terminal or ATM device ID (20 characters)';
COMMENT ON COLUMN transaction_detail.extended_description IS 'Additional transaction description (500 characters)';
COMMENT ON COLUMN transaction_detail.merchant_category_code IS 'ISO 18245 Merchant Category Code (4 characters)';
COMMENT ON COLUMN transaction_detail.currency_code IS 'ISO 4217 currency code (3 characters) - defaults to USD';
COMMENT ON COLUMN transaction_detail.exchange_rate IS 'Currency conversion rate (precision 10, scale 6)';
COMMENT ON COLUMN transaction_detail.settlement_date IS 'Date transaction was settled (may differ from processing date)';
COMMENT ON COLUMN transaction_detail.created_date IS 'Record creation timestamp (audit trail)';
COMMENT ON COLUMN transaction_detail.updated_date IS 'Record last modification timestamp (audit trail)';
