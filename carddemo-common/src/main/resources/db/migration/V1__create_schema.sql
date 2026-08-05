-- =============================================================================
-- CardDemo consolidated business schema (AAP 0.4.5 "V1__create_schema.sql").
--
-- Re-platforms the legacy VSAM data sets catalogued in app/catlg/LISTCAT.txt into
-- PostgreSQL relations. Column names, widths, and numeric scales are the
-- authoritative match for the JPA entities in com.carddemo.common.domain, so every
-- service starts cleanly under Hibernate ddl-auto=validate.
--
-- OWNERSHIP: this migration set is applied by ONE owner (batch-service) against the
-- shared `public` schema under ONE history table. Every other service runs with
-- spring.flyway.enabled=false and validates the mapping only. A single ordered
-- migration is what makes cross-table foreign keys (cards -> accounts,
-- card_xref -> customers/accounts, tran_cat_bal -> accounts) creatable without any
-- inter-service ordering race.
--
-- FIDELITY RULES honoured here:
--   * COMP-3 money keeps its exact COBOL scale (AAP 0.6.1): S9(10)V99 -> NUMERIC(12,2),
--     S9(09)V99 -> NUMERIC(11,2), S9(04)V99 -> NUMERIC(6,2). Never widen or narrow.
--   * Source misspellings are preserved verbatim (AAP 0.6.8): acct_expiraion_date,
--     card_expiraion_date.
--   * Composite primary keys keep the copybook key-field ORDER so range/browse
--     semantics match the VSAM keys (TRAN-CAT-KEY, DIS-GROUP-KEY, TRAN-CAT-KEY).
--   * PIC 9(n) identifiers are unsigned and n digits wide in COBOL; a CHECK per
--     identifier column keeps values representable in the legacy record layouts.
--   * X(10) dates and X(26) timestamps keep their wire format as VARCHAR (AAP 0.3.6).
--
-- Every statement is idempotent (IF NOT EXISTS) so the migration is safe to replay
-- and can adopt an already-provisioned schema without redefining objects.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- customers  <- app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500)
-- Entity: com.carddemo.common.domain.Customer
-- COBOL FILLER PIC X(168) is trailing record padding and is not persisted.
-- SSN / government id / EFT account id are PII: stored as AES-256-GCM ciphertext
-- (Base64 token) by carddemo-common CryptoConverter, hence the widened VARCHAR(512).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS customers (
    cust_id                  BIGINT        PRIMARY KEY,        -- CUST-ID                  PIC 9(09)
    cust_first_name          VARCHAR(25)   NOT NULL,           -- CUST-FIRST-NAME          PIC X(25)
    cust_middle_name         VARCHAR(25)   NOT NULL,           -- CUST-MIDDLE-NAME         PIC X(25)
    cust_last_name           VARCHAR(25)   NOT NULL,           -- CUST-LAST-NAME           PIC X(25)
    cust_addr_line_1         VARCHAR(50)   NOT NULL,           -- CUST-ADDR-LINE-1         PIC X(50)
    cust_addr_line_2         VARCHAR(50)   NOT NULL,           -- CUST-ADDR-LINE-2         PIC X(50)
    cust_addr_line_3         VARCHAR(50)   NOT NULL,           -- CUST-ADDR-LINE-3         PIC X(50)
    cust_addr_state_cd       VARCHAR(2)    NOT NULL,           -- CUST-ADDR-STATE-CD       PIC X(02)
    cust_addr_country_cd     VARCHAR(3)    NOT NULL,           -- CUST-ADDR-COUNTRY-CD     PIC X(03)
    cust_addr_zip            VARCHAR(10)   NOT NULL,           -- CUST-ADDR-ZIP            PIC X(10)
    cust_phone_num_1         VARCHAR(15)   NOT NULL,           -- CUST-PHONE-NUM-1         PIC X(15)
    cust_phone_num_2         VARCHAR(15)   NOT NULL,           -- CUST-PHONE-NUM-2         PIC X(15)
    cust_ssn                 VARCHAR(512)  NOT NULL,           -- CUST-SSN                 PIC 9(09)  (PII, encrypted at rest)
    cust_govt_issued_id      VARCHAR(512)  NOT NULL,           -- CUST-GOVT-ISSUED-ID      PIC X(20)  (PII, encrypted at rest)
    cust_dob_yyyy_mm_dd      VARCHAR(10)   NOT NULL,           -- CUST-DOB-YYYY-MM-DD      PIC X(10)  date YYYY-MM-DD
    cust_eft_account_id      VARCHAR(512)  NOT NULL,           -- CUST-EFT-ACCOUNT-ID      PIC X(10)  (PII, encrypted at rest)
    cust_pri_card_holder_ind VARCHAR(1)    NOT NULL,           -- CUST-PRI-CARD-HOLDER-IND PIC X(01)
    cust_fico_credit_score   INTEGER       NOT NULL,           -- CUST-FICO-CREDIT-SCORE   PIC 9(03)
    version                  BIGINT        NOT NULL DEFAULT 0, -- JPA @Version optimistic-lock counter (no legacy analogue)
    CONSTRAINT chk_customers_cust_id CHECK (cust_id BETWEEN 0 AND 999999999),
    CONSTRAINT chk_customers_fico CHECK (cust_fico_credit_score BETWEEN 0 AND 999)
);

-- -----------------------------------------------------------------------------
-- accounts  <- app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300)
-- Entity: com.carddemo.common.domain.Account
-- Money columns are NUMERIC(12,2) for COBOL S9(10)V99 - do not change the scale.
-- acct_expiraion_date preserves the source misspelling (AAP 0.6.8).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS accounts (
    acct_id                BIGINT         PRIMARY KEY,         -- ACCT-ID                PIC 9(11)
    acct_active_status     VARCHAR(1)     NOT NULL,            -- ACCT-ACTIVE-STATUS     PIC X(01)
    acct_curr_bal          NUMERIC(12,2)  NOT NULL,            -- ACCT-CURR-BAL          PIC S9(10)V99
    acct_credit_limit      NUMERIC(12,2)  NOT NULL,            -- ACCT-CREDIT-LIMIT      PIC S9(10)V99
    acct_cash_credit_limit NUMERIC(12,2)  NOT NULL,            -- ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
    acct_open_date         VARCHAR(10)    NOT NULL,            -- ACCT-OPEN-DATE         PIC X(10)  date YYYY-MM-DD
    acct_expiraion_date    VARCHAR(10)    NOT NULL,            -- ACCT-EXPIRAION-DATE    PIC X(10)  [misspelling preserved]
    acct_reissue_date      VARCHAR(10)    NOT NULL,            -- ACCT-REISSUE-DATE      PIC X(10)
    acct_curr_cyc_credit   NUMERIC(12,2)  NOT NULL,            -- ACCT-CURR-CYC-CREDIT   PIC S9(10)V99
    acct_curr_cyc_debit    NUMERIC(12,2)  NOT NULL,            -- ACCT-CURR-CYC-DEBIT    PIC S9(10)V99
    acct_addr_zip          VARCHAR(10)    NOT NULL,            -- ACCT-ADDR-ZIP          PIC X(10)
    acct_group_id          VARCHAR(10),                        -- ACCT-GROUP-ID          PIC X(10)  (blank in the fixture -> NULL)
    version                BIGINT         NOT NULL DEFAULT 0,  -- optimistic-lock @Version (no legacy field; AAP 0.6.2)
    CONSTRAINT chk_accounts_acct_id CHECK (acct_id BETWEEN 0 AND 99999999999)
);

-- -----------------------------------------------------------------------------
-- security_users  <- app/cpy/CSUSR01Y.cpy (SEC-USER-DATA, 80 bytes)
-- Re-platforms VSAM KSDS AWS.M2.CARDDEMO.USRSEC (KEYS(8,0), RECORDSIZE(80,80))
-- consumed by COBOL sign-on COSGN00C (CICS CC00).
-- Entity: com.carddemo.common.domain.SecurityUser
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS security_users (
    sec_usr_id    VARCHAR(8)   PRIMARY KEY,   -- SEC-USR-ID    PIC X(08); VSAM 8-byte key at offset 0
    sec_usr_fname VARCHAR(20)  NOT NULL,      -- SEC-USR-FNAME PIC X(20)
    sec_usr_lname VARCHAR(20)  NOT NULL,      -- SEC-USR-LNAME PIC X(20)
    sec_usr_pwd   VARCHAR(100) NOT NULL,      -- SEC-USR-PWD   PIC X(08) legacy; WIDENED to 100 for the BCrypt hash (never plaintext)
    sec_usr_type  VARCHAR(1)   NOT NULL,      -- SEC-USR-TYPE  PIC X(01); 'A' -> ROLE_ADMIN, 'U' -> ROLE_USER
    CONSTRAINT chk_sec_usr_type CHECK (sec_usr_type IN ('A','U'))
);

-- -----------------------------------------------------------------------------
-- tran_type  <- app/cpy/CVTRA03Y.cpy (TRAN-TYPE-RECORD, RECLN 60)
-- Entity: com.carddemo.common.domain.TranType. FILLER X(08) is not persisted.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tran_type (
    tran_type      VARCHAR(2)  PRIMARY KEY,   -- TRAN-TYPE      PIC X(02)
    tran_type_desc VARCHAR(50)                -- TRAN-TYPE-DESC PIC X(50)
);

-- -----------------------------------------------------------------------------
-- tran_category  <- app/cpy/CVTRA04Y.cpy (TRAN-CAT-RECORD, RECLN 60)
-- Entity: com.carddemo.common.domain.TranCatg @IdClass(TranCatgId).
-- Primary-key column order is the copybook TRAN-CAT-KEY order
-- (TRAN-TYPE-CD, TRAN-CAT-CD) so the browse order matches the VSAM key.
-- FILLER X(04) is not persisted.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tran_category (
    tran_type_cd       VARCHAR(2)  NOT NULL,  -- TRAN-TYPE-CD       PIC X(02)
    tran_cat_cd        INTEGER     NOT NULL,  -- TRAN-CAT-CD        PIC 9(04)
    tran_cat_type_desc VARCHAR(50),           -- TRAN-CAT-TYPE-DESC PIC X(50)
    CONSTRAINT tran_category_pkey PRIMARY KEY (tran_type_cd, tran_cat_cd),
    CONSTRAINT chk_tran_category_cat_cd CHECK (tran_cat_cd BETWEEN 0 AND 9999),
    CONSTRAINT fk_tran_category_type FOREIGN KEY (tran_type_cd)
        REFERENCES tran_type (tran_type)
);

-- -----------------------------------------------------------------------------
-- disclosure_group  <- app/cpy/CVTRA02Y.cpy (DIS-GROUP-RECORD, RECLN 50)
-- Entity: com.carddemo.common.domain.DiscGroup @IdClass(DiscGroupId).
-- Primary-key column order is the copybook DIS-GROUP-KEY order
-- (DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD, DIS-TRAN-CAT-CD).
-- dis_int_rate is the CBACT04C interest multiplicand: S9(04)V99 -> NUMERIC(6,2)
-- (exact scale; AAP 0.6.1). FILLER X(28) is not persisted.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS disclosure_group (
    dis_acct_group_id VARCHAR(10)   NOT NULL, -- DIS-ACCT-GROUP-ID PIC X(10)
    dis_tran_type_cd  VARCHAR(2)    NOT NULL, -- DIS-TRAN-TYPE-CD  PIC X(02)
    dis_tran_cat_cd   INTEGER       NOT NULL, -- DIS-TRAN-CAT-CD   PIC 9(04)
    dis_int_rate      NUMERIC(6,2),           -- DIS-INT-RATE      PIC S9(04)V99
    CONSTRAINT disclosure_group_pkey PRIMARY KEY (dis_acct_group_id, dis_tran_type_cd, dis_tran_cat_cd),
    CONSTRAINT chk_disclosure_group_cat_cd CHECK (dis_tran_cat_cd BETWEEN 0 AND 9999),
    CONSTRAINT fk_disclosure_group_category FOREIGN KEY (dis_tran_type_cd, dis_tran_cat_cd)
        REFERENCES tran_category (tran_type_cd, tran_cat_cd)
);

-- Covering index for the DIS-ACCT-GROUP-ID-less lookup CBACT04C performs when it
-- retries the disclosure group as the literal 'DEFAULT': the type/category pair is
-- the residual predicate once the group id is fixed [app/cbl/CBACT04C.cbl:L437].
CREATE INDEX IF NOT EXISTS idx_disclosure_group_type_cat
    ON disclosure_group (dis_tran_type_cd, dis_tran_cat_cd);

-- -----------------------------------------------------------------------------
-- cards  <- app/cpy/CVACT02Y.cpy (CARD-RECORD, RECLN 150)
-- Entity: com.carddemo.common.domain.Card. card_cvv_cd is sensitive and is stored
-- as an AES-256-GCM Base64 token (hence VARCHAR(512)).
-- card_expiraion_date preserves the source misspelling (AAP 0.6.8).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cards (
    card_num            VARCHAR(16)  PRIMARY KEY,   -- CARD-NUM            PIC X(16)  (PAN; string preserves leading zeros)
    card_acct_id        BIGINT       NOT NULL,      -- CARD-ACCT-ID        PIC 9(11)  FK -> accounts.acct_id
    card_cvv_cd         VARCHAR(512),               -- CARD-CVV-CD         PIC 9(03)  (SENSITIVE; encrypted at rest)
    card_embossed_name  VARCHAR(50)  NOT NULL,      -- CARD-EMBOSSED-NAME  PIC X(50)
    card_expiraion_date VARCHAR(10)  NOT NULL,      -- CARD-EXPIRAION-DATE PIC X(10)  [misspelling preserved]
    card_active_status  VARCHAR(1)   NOT NULL,      -- CARD-ACTIVE-STATUS  PIC X(01)
    CONSTRAINT fk_cards_account FOREIGN KEY (card_acct_id) REFERENCES accounts (acct_id),
    CONSTRAINT chk_cards_acct_id CHECK (card_acct_id BETWEEN 0 AND 99999999999)
);

-- -----------------------------------------------------------------------------
-- card_xref  <- app/cpy/CVACT03Y.cpy (CARD-XREF-RECORD, RECLN 50)
-- The CXACAIX card <-> customer <-> account anchor. Both foreign keys are
-- declarative so referential integrity is enforced by the database (AAP 0.6.4).
-- Entity: com.carddemo.common.domain.CardXref.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS card_xref (
    xref_card_num VARCHAR(16)  PRIMARY KEY,   -- XREF-CARD-NUM PIC X(16)  (PAN; leading zeros)
    xref_cust_id  BIGINT       NOT NULL,      -- XREF-CUST-ID  PIC 9(09)  FK -> customers.cust_id
    xref_acct_id  BIGINT       NOT NULL,      -- XREF-ACCT-ID  PIC 9(11)  FK -> accounts.acct_id
    CONSTRAINT fk_card_xref_cust FOREIGN KEY (xref_cust_id) REFERENCES customers (cust_id),
    CONSTRAINT fk_card_xref_acct FOREIGN KEY (xref_acct_id) REFERENCES accounts (acct_id),
    CONSTRAINT chk_card_xref_cust_id CHECK (xref_cust_id BETWEEN 0 AND 999999999),
    CONSTRAINT chk_card_xref_acct_id CHECK (xref_acct_id BETWEEN 0 AND 99999999999)
);

-- -----------------------------------------------------------------------------
-- transactions  <- app/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350)
-- Entity: com.carddemo.common.domain.Transaction. FILLER X(20) is not persisted.
-- tran_id is PIC X(16) - alphanumeric by contract, so it carries no digits-only
-- constraint; the sequence alignment below therefore filters non-numeric ids.
-- tran_card_num is a LOGICAL reference only (no FK): the posting job must be able
-- to record a transaction whose card is unknown (CBTRN02C reject code 100).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transactions (
    tran_id             VARCHAR(16)   PRIMARY KEY,   -- TRAN-ID            PIC X(16)
    tran_type_cd        VARCHAR(2),                  -- TRAN-TYPE-CD       PIC X(02)
    tran_cat_cd         INTEGER,                     -- TRAN-CAT-CD        PIC 9(04)
    tran_source         VARCHAR(10),                 -- TRAN-SOURCE        PIC X(10)
    tran_desc           VARCHAR(100),                -- TRAN-DESC          PIC X(100)
    tran_amt            NUMERIC(11,2),               -- TRAN-AMT           PIC S9(09)V99 (exact scale; AAP 0.6.1)
    tran_merchant_id    BIGINT,                      -- TRAN-MERCHANT-ID   PIC 9(09)
    tran_merchant_name  VARCHAR(50),                 -- TRAN-MERCHANT-NAME PIC X(50)
    tran_merchant_city  VARCHAR(50),                 -- TRAN-MERCHANT-CITY PIC X(50)
    tran_merchant_zip   VARCHAR(10),                 -- TRAN-MERCHANT-ZIP  PIC X(10)
    tran_card_num       VARCHAR(16),                 -- TRAN-CARD-NUM      PIC X(16)  (logical reference; indexed below)
    tran_orig_ts        VARCHAR(26),                 -- TRAN-ORIG-TS       PIC X(26)
    tran_proc_ts        VARCHAR(26),                 -- TRAN-PROC-TS       PIC X(26)
    CONSTRAINT chk_transactions_cat_cd CHECK (tran_cat_cd IS NULL OR tran_cat_cd BETWEEN 0 AND 9999),
    CONSTRAINT chk_transactions_merchant_id CHECK (tran_merchant_id IS NULL OR tran_merchant_id BETWEEN 0 AND 999999999)
);

-- -----------------------------------------------------------------------------
-- daily_transactions  <- app/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350)
-- The DALYTRAN posting feed read by the CBTRN02C transaction-posting job.
-- Entity: com.carddemo.common.domain.DailyTransaction. FILLER X(20) not persisted.
-- No FK on dalytran_card_num: the feed legitimately carries unknown cards, which
-- the posting job rejects with code 100 rather than refusing at insert time.
-- -----------------------------------------------------------------------------
-- Every field CBTRN02C dereferences while validating a feed record is NOT NULL, and
-- the origination timestamp additionally carries at least its ten date characters
-- (QA Issue 8). DALYTRAN is a fixed-width 350-byte sequential data set: a record
-- physically cannot be missing DALYTRAN-AMT or hold a five-character
-- DALYTRAN-ORIG-TS, so a staged row that does is not a DALYTRAN record at all.
-- Leaving the columns nullable let such a row reach 1500-VALIDATE-TRAN, where it
-- aborted the posting step with a raw NullPointerException /
-- StringIndexOutOfBoundsException on every rerun - blocking every later record in the
-- feed and never producing a reject record. The constraints refuse the row at
-- ingestion instead, where the loader can still fix it. DALYTRAN-PROC-TS stays
-- nullable: 2000-POST-TRANSACTION stamps it at posting time, so an unposted feed
-- record legitimately carries none.
CREATE TABLE IF NOT EXISTS daily_transactions (
    dalytran_id             VARCHAR(16)   PRIMARY KEY,   -- DALYTRAN-ID            PIC X(16)
    dalytran_type_cd        VARCHAR(2)    NOT NULL,      -- DALYTRAN-TYPE-CD       PIC X(02)
    dalytran_cat_cd         INTEGER       NOT NULL,      -- DALYTRAN-CAT-CD        PIC 9(04)
    dalytran_source         VARCHAR(10),                 -- DALYTRAN-SOURCE        PIC X(10)
    dalytran_desc           VARCHAR(100),                -- DALYTRAN-DESC          PIC X(100)
    dalytran_amt            NUMERIC(11,2) NOT NULL,      -- DALYTRAN-AMT           PIC S9(09)V99 (exact scale; AAP 0.6.1)
    dalytran_merchant_id    BIGINT,                      -- DALYTRAN-MERCHANT-ID   PIC 9(09)
    dalytran_merchant_name  VARCHAR(50),                 -- DALYTRAN-MERCHANT-NAME PIC X(50)
    dalytran_merchant_city  VARCHAR(50),                 -- DALYTRAN-MERCHANT-CITY PIC X(50)
    dalytran_merchant_zip   VARCHAR(10),                 -- DALYTRAN-MERCHANT-ZIP  PIC X(10)
    dalytran_card_num       VARCHAR(16)   NOT NULL,      -- DALYTRAN-CARD-NUM      PIC X(16)
    dalytran_orig_ts        VARCHAR(26)   NOT NULL,      -- DALYTRAN-ORIG-TS       PIC X(26) (first 10 chars drive reject 103)
    dalytran_proc_ts        VARCHAR(26),                 -- DALYTRAN-PROC-TS       PIC X(26)
    CONSTRAINT chk_daily_transactions_cat_cd CHECK (dalytran_cat_cd BETWEEN 0 AND 9999),
    CONSTRAINT chk_daily_transactions_merchant_id CHECK (dalytran_merchant_id IS NULL OR dalytran_merchant_id BETWEEN 0 AND 999999999),
    CONSTRAINT chk_daily_transactions_orig_ts CHECK (LENGTH(dalytran_orig_ts) >= 10)
);

-- -----------------------------------------------------------------------------
-- tran_cat_bal  <- app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD, RECLN 50)
-- Entity: com.carddemo.common.domain.TranCatBal @IdClass(TranCatBalId).
-- Primary-key column order is the copybook TRAN-CAT-KEY order
-- (TRANCAT-ACCT-ID, TRANCAT-TYPE-CD, TRANCAT-CD). FILLER X(22) not persisted.
-- CBACT04C reads a category balance and then the OWNING account, so the account
-- reference is enforced declaratively (AAP 0.6.4).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tran_cat_bal (
    trancat_acct_id   BIGINT         NOT NULL,   -- TRANCAT-ACCT-ID PIC 9(11)  FK -> accounts.acct_id
    trancat_type_cd   VARCHAR(2)     NOT NULL,   -- TRANCAT-TYPE-CD PIC X(02)
    trancat_cd        INTEGER        NOT NULL,   -- TRANCAT-CD      PIC 9(04)
    tran_cat_bal      NUMERIC(11,2),             -- TRAN-CAT-BAL    PIC S9(09)V99 (interest multiplicand; AAP 0.6.1)
    CONSTRAINT tran_cat_bal_pkey PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd),
    CONSTRAINT fk_tran_cat_bal_acct FOREIGN KEY (trancat_acct_id) REFERENCES accounts (acct_id),
    CONSTRAINT chk_tran_cat_bal_acct_id CHECK (trancat_acct_id BETWEEN 0 AND 99999999999),
    CONSTRAINT chk_tran_cat_bal_cd CHECK (trancat_cd BETWEEN 0 AND 9999)
);

-- -----------------------------------------------------------------------------
-- Secondary indexes reproducing the VSAM alternate indexes and the entity-declared
-- indexes (@Table(indexes = ...) on Card and CardXref), so a Hibernate schema
-- comparison reports no missing index:
--   CARDAIX  -> idx_cards_card_acct_id      (card list by account)
--   CXACAIX  -> idx_card_xref_acct_id       (cross-reference then account lookup)
--            -> idx_card_xref_cust_id       (customer-side cross-reference lookup)
--   TRANSACT -> idx_transactions_card_num   (transaction browse by card)
-- -----------------------------------------------------------------------------
-- Secondary index backing the disclosure-group lookup by account group id
-- (CBACT04C interest calculation, including the 'DEFAULT' group fallback; AAP 0.6.1).
CREATE INDEX IF NOT EXISTS idx_disclosure_group_acct_group_id
    ON disclosure_group (dis_acct_group_id);

CREATE INDEX IF NOT EXISTS idx_cards_card_acct_id ON cards (card_acct_id);
CREATE INDEX IF NOT EXISTS idx_card_xref_acct_id ON card_xref (xref_acct_id);
CREATE INDEX IF NOT EXISTS idx_card_xref_cust_id ON card_xref (xref_cust_id);
CREATE INDEX IF NOT EXISTS idx_transactions_card_num ON transactions (tran_card_num);

-- -----------------------------------------------------------------------------
-- transaction_id_seq: transaction-id generator backing
-- TransactionRepository.getNextTransactionId() ("SELECT nextval('transaction_id_seq')"),
-- which supplies the 16-digit zero-padded TRAN-ID for the online add flow (COTRN02C)
-- and the bill-pay posting flow (COBIL00C). Replaces the legacy
-- browse-last-then-increment probe (AAP 0.6.5) while preserving the observable
-- 16-digit format.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS transaction_id_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    NO MAXVALUE
    CACHE 1;

-- Advance the sequence past any rows already present so a generated id cannot
-- collide with an existing transaction, reproducing the COBOL "last id + 1"
-- baseline. TRAN-ID is PIC X(16) and therefore may legally hold a non-numeric
-- value, so only digit-only ids participate in the maximum - an alphanumeric id
-- must never break this migration (a bare CAST would raise
-- "invalid input syntax for type bigint"). An empty table leaves the next value
-- at 1 (first transaction -> 0000000000000001).
SELECT setval(
           'transaction_id_seq',
           GREATEST(
               COALESCE((SELECT MAX(CAST(tran_id AS BIGINT))
                           FROM transactions
                          WHERE tran_id ~ '^[0-9]{1,16}$'), 0) + 1,
               1),
           false
       );
