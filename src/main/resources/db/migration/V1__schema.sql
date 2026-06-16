-- V1__schema.sql : 10 tables, 1 sequence, 3 indexes (PostgreSQL 15 + H2 MODE=PostgreSQL compatible)
-- CardDemo relational schema mirroring the 10 legacy VSAM KSDS datasets.
-- Flyway is authoritative (spring.jpa.hibernate.ddl-auto=validate). lower_snake_case, ANSI types only.
-- COBOL FILLER bytes are NOT persisted. The transactions table is created here but never seeded.

-- ---------- Reference / lookup tables (parents, seeded by V2) ----------

CREATE TABLE transaction_type (
    type_cd   CHAR(2)     NOT NULL,
    type_desc VARCHAR(50),
    CONSTRAINT pk_transaction_type PRIMARY KEY (type_cd)
);

CREATE TABLE transaction_category (
    type_cd       CHAR(2) NOT NULL,
    cat_cd        INTEGER NOT NULL,
    cat_type_desc VARCHAR(50),
    CONSTRAINT pk_transaction_category PRIMARY KEY (type_cd, cat_cd),
    CONSTRAINT fk_trancat_type FOREIGN KEY (type_cd)
        REFERENCES transaction_type (type_cd)
);

CREATE TABLE disclosure_group (
    group_id     VARCHAR(10) NOT NULL,
    tran_type_cd CHAR(2)     NOT NULL,
    tran_cat_cd  INTEGER     NOT NULL,
    dis_int_rate NUMERIC(6,2),
    CONSTRAINT pk_disclosure_group PRIMARY KEY (group_id, tran_type_cd, tran_cat_cd)
);

-- ---------- Master / entity tables ----------

CREATE TABLE users (
    user_id    VARCHAR(8)   NOT NULL,
    first_name VARCHAR(20),
    last_name  VARCHAR(20),
    password   VARCHAR(100) NOT NULL,
    user_type  CHAR(1),
    CONSTRAINT pk_users PRIMARY KEY (user_id)
);

CREATE TABLE customers (
    cust_id             BIGINT NOT NULL,
    first_name          VARCHAR(25),
    middle_name         VARCHAR(25),
    last_name           VARCHAR(25),
    addr_line_1         VARCHAR(50),
    addr_line_2         VARCHAR(50),
    addr_line_3         VARCHAR(50),
    addr_state_cd       CHAR(2),
    addr_country_cd     VARCHAR(3),
    addr_zip            VARCHAR(10),
    phone_num_1         VARCHAR(15),
    phone_num_2         VARCHAR(15),
    ssn                 VARCHAR(9),
    govt_issued_id      VARCHAR(20),
    dob                 DATE,
    eft_account_id      VARCHAR(10),
    pri_card_holder_ind CHAR(1),
    fico_credit_score   INTEGER,
    CONSTRAINT pk_customers PRIMARY KEY (cust_id)
);

CREATE TABLE accounts (
    acct_id           BIGINT NOT NULL,
    active_status     CHAR(1),
    curr_bal          NUMERIC(12,2),
    credit_limit      NUMERIC(12,2),
    cash_credit_limit NUMERIC(12,2),
    open_date         DATE,
    expiration_date   DATE,
    reissue_date      DATE,
    curr_cyc_credit   NUMERIC(12,2),
    curr_cyc_debit    NUMERIC(12,2),
    addr_zip          VARCHAR(10),
    group_id          VARCHAR(10),
    version           BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (acct_id)
);

CREATE TABLE cards (
    card_num        VARCHAR(16) NOT NULL,
    card_acct_id    BIGINT      NOT NULL,
    cvv_code        INTEGER,
    embossed_name   VARCHAR(50),
    expiration_date DATE,
    active_status   CHAR(1),
    CONSTRAINT pk_cards PRIMARY KEY (card_num),
    CONSTRAINT fk_cards_acct FOREIGN KEY (card_acct_id)
        REFERENCES accounts (acct_id)
);

CREATE TABLE card_xref (
    xref_card_num VARCHAR(16) NOT NULL,
    xref_cust_id  BIGINT      NOT NULL,
    xref_acct_id  BIGINT      NOT NULL,
    CONSTRAINT pk_card_xref PRIMARY KEY (xref_card_num),
    CONSTRAINT fk_xref_card FOREIGN KEY (xref_card_num) REFERENCES cards (card_num),
    CONSTRAINT fk_xref_cust FOREIGN KEY (xref_cust_id)  REFERENCES customers (cust_id),
    CONSTRAINT fk_xref_acct FOREIGN KEY (xref_acct_id)  REFERENCES accounts (acct_id)
);

CREATE TABLE transaction_category_balance (
    acct_id      BIGINT        NOT NULL,
    type_cd      CHAR(2)       NOT NULL,
    cat_cd       INTEGER       NOT NULL,
    tran_cat_bal NUMERIC(12,2) NOT NULL DEFAULT 0,
    CONSTRAINT pk_tran_cat_bal PRIMARY KEY (acct_id, type_cd, cat_cd),
    CONSTRAINT fk_tcb_acct FOREIGN KEY (acct_id) REFERENCES accounts (acct_id),
    CONSTRAINT fk_tcb_cat  FOREIGN KEY (type_cd, cat_cd)
        REFERENCES transaction_category (type_cd, cat_cd)
);

CREATE TABLE transactions (
    tran_id       VARCHAR(16) NOT NULL,
    type_cd       CHAR(2),
    cat_cd        INTEGER,
    source        VARCHAR(10),
    description   VARCHAR(100),
    amt           NUMERIC(12,2),
    merchant_id   BIGINT,
    merchant_name VARCHAR(50),
    merchant_city VARCHAR(50),
    merchant_zip  VARCHAR(10),
    card_num      VARCHAR(16),
    orig_ts       TIMESTAMP,
    proc_ts       TIMESTAMP,
    acct_id       BIGINT,
    CONSTRAINT pk_transactions PRIMARY KEY (tran_id),
    CONSTRAINT fk_tran_card FOREIGN KEY (card_num) REFERENCES cards (card_num),
    CONSTRAINT fk_tran_acct FOREIGN KEY (acct_id)  REFERENCES accounts (acct_id),
    CONSTRAINT fk_tran_cat  FOREIGN KEY (type_cd, cat_cd)
        REFERENCES transaction_category (type_cd, cat_cd)
);

-- ---------- Sequence: online transaction-id generation (util/TranIdGenerator LPADs to 16 chars) ----------
CREATE SEQUENCE transaction_id_seq START WITH 1 INCREMENT BY 1;

-- ---------- Indexes: replace the 3 VSAM alternate indexes (AAP §0.6.4) ----------
CREATE INDEX idx_cards_acct_id ON cards (card_acct_id);
CREATE INDEX idx_cardxref_acct_id ON card_xref (xref_acct_id);
CREATE INDEX idx_transactions_acct_id_orig_ts ON transactions (acct_id, orig_ts);
