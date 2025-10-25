-- Flyway Migration V5: Create Reference Tables
-- Source: COBOL copybooks CVTRA01Y-04Y.cpy (reference data) and CSUSR01Y.cpy (user security)
--
-- Reference Tables:
-- 1. transaction_type - Transaction type codes and descriptions (CVTRA03Y.cpy)
-- 2. transaction_category - Transaction category codes and descriptions (CVTRA04Y.cpy)
-- 3. disclosure_group - Disclosure group data with interest rates (CVTRA02Y.cpy)
-- 4. transaction_category_balance - Category balance by account (CVTRA01Y.cpy)
-- 5. user_security - User authentication and authorization (CSUSR01Y.cpy)

-- Transaction Type Reference Table (CVTRA03Y.cpy, RECLN 60)
-- Transformation: TRAN-TYPE PIC X(02) → VARCHAR(2), TRAN-TYPE-DESC PIC X(50) → VARCHAR(50)
CREATE TABLE transaction_type (
    trans_type_cd VARCHAR(2) PRIMARY KEY,
    trans_type_desc VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Transaction Category Reference Table (CVTRA04Y.cpy, RECLN 60)
-- Transformation: TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04) → composite key
CREATE TABLE transaction_category (
    trans_type_cd VARCHAR(2) NOT NULL,
    trans_cat_cd INTEGER NOT NULL,
    trans_cat_desc VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (trans_type_cd, trans_cat_cd),
    
    -- Foreign key to transaction_type table
    CONSTRAINT fk_trans_category_type FOREIGN KEY (trans_type_cd)
        REFERENCES transaction_type(trans_type_cd)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

-- Disclosure Group Reference Table (CVTRA02Y.cpy, RECLN 50)
-- Transformation: DIS-ACCT-GROUP-ID PIC X(10) + DIS-TRAN-TYPE-CD PIC X(02) + DIS-TRAN-CAT-CD PIC 9(04) → composite key
-- DIS-INT-RATE PIC S9(04)V99 → NUMERIC(6,2)
CREATE TABLE disclosure_group (
    disc_acct_group_id VARCHAR(10) NOT NULL,
    disc_trans_type_cd VARCHAR(2) NOT NULL,
    disc_trans_cat_cd INTEGER NOT NULL,
    disc_int_rate NUMERIC(6,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (disc_acct_group_id, disc_trans_type_cd, disc_trans_cat_cd)
);

-- Transaction Category Balance Table (CVTRA01Y.cpy, RECLN 50)
-- Transformation: TRANCAT-ACCT-ID PIC 9(11) + TRANCAT-TYPE-CD PIC X(02) + TRANCAT-CD PIC 9(04) → composite key
-- TRAN-CAT-BAL PIC S9(09)V99 → NUMERIC(11,2)
CREATE TABLE transaction_category_balance (
    tcat_acct_id BIGINT NOT NULL,
    tcat_type_cd VARCHAR(2) NOT NULL,
    tcat_cat_cd INTEGER NOT NULL,
    tcat_bal NUMERIC(11,2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0,
    
    PRIMARY KEY (tcat_acct_id, tcat_type_cd, tcat_cat_cd),
    
    -- Foreign key to account table
    CONSTRAINT fk_tcat_bal_account FOREIGN KEY (tcat_acct_id)
        REFERENCES account(acct_id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,
    
    -- Foreign key to transaction_category table
    CONSTRAINT fk_tcat_bal_category FOREIGN KEY (tcat_type_cd, tcat_cat_cd)
        REFERENCES transaction_category(trans_type_cd, trans_cat_cd)
        ON DELETE RESTRICT
        ON UPDATE CASCADE
);

-- User Security Table (CSUSR01Y.cpy)
-- Transformed from SEC-USER-DATA structure (80 bytes)
-- Transformation: SEC-USR-ID PIC X(08) → VARCHAR(8)
-- SEC-USR-PWD PIC X(08) → VARCHAR(100) (extended for BCrypt hash)
-- SEC-USR-FNAME/LNAME PIC X(20) → VARCHAR(25)
-- SEC-USR-TYPE PIC X(01) → CHAR(1)
CREATE TABLE user_security (
    user_id VARCHAR(8) PRIMARY KEY,
    user_pwd_hash VARCHAR(100) NOT NULL,
    user_first_name VARCHAR(25),
    user_last_name VARCHAR(25),
    user_type CHAR(1) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_ts TIMESTAMP,
    version INTEGER DEFAULT 0
);

-- Add indexes for performance
CREATE INDEX idx_user_type ON user_security(user_type);
CREATE INDEX idx_tcat_bal_account ON transaction_category_balance(tcat_acct_id);

-- Add comments for documentation
COMMENT ON TABLE transaction_type IS 'Transaction type reference table (CVTRA03Y.cpy)';
COMMENT ON TABLE transaction_category IS 'Transaction category reference table (CVTRA04Y.cpy)';
COMMENT ON TABLE disclosure_group IS 'Disclosure group with interest rates (CVTRA02Y.cpy)';
COMMENT ON TABLE transaction_category_balance IS 'Transaction category balance by account (CVTRA01Y.cpy)';
COMMENT ON TABLE user_security IS 'User authentication and authorization table (CSUSR01Y.cpy)';
COMMENT ON COLUMN user_security.user_pwd_hash IS 'BCrypt hashed password (COBOL stored plain-text, now secure)';
COMMENT ON COLUMN user_security.user_type IS 'User type: A=Admin, U=User (COBOL PIC X(01))';
