-- V1__create_transactions_table.sql
-- Transaction master table. Owned by transaction-service (flyway_schema_history_transaction).
-- Source: app/cpy/CVTRA05Y.cpy (TRAN-RECORD, RECLN 350). Entity: com.carddemo.common.domain.Transaction.
-- FILLER X(20) is not persisted. Column types must match the entity exactly (ddl-auto=validate).
CREATE TABLE transactions (
    tran_id             VARCHAR(16)   PRIMARY KEY,   -- TRAN-ID X(16)
    tran_type_cd        VARCHAR(2),                  -- TRAN-TYPE-CD X(02)
    tran_cat_cd         INTEGER,                     -- TRAN-CAT-CD 9(04)
    tran_source         VARCHAR(10),                 -- TRAN-SOURCE X(10)
    tran_desc           VARCHAR(100),                -- TRAN-DESC X(100)
    tran_amt            NUMERIC(11,2),               -- TRAN-AMT S9(09)V99 (exact scale; AAP 0.6.1)
    tran_merchant_id    BIGINT,                      -- TRAN-MERCHANT-ID 9(09)
    tran_merchant_name  VARCHAR(50),                 -- TRAN-MERCHANT-NAME X(50)
    tran_merchant_city  VARCHAR(50),                 -- TRAN-MERCHANT-CITY X(50)
    tran_merchant_zip   VARCHAR(10),                 -- TRAN-MERCHANT-ZIP X(10)
    tran_card_num       VARCHAR(16),                 -- TRAN-CARD-NUM X(16); logical FK -> cards.card_num (index below)
    tran_orig_ts        VARCHAR(26),                 -- TRAN-ORIG-TS X(26)
    tran_proc_ts        VARCHAR(26)                  -- TRAN-PROC-TS X(26)
);

-- Secondary index for transaction-by-card browse (reproduces VSAM alt-access; AAP 0.3.4).
CREATE INDEX idx_transactions_card_num ON transactions (tran_card_num);
