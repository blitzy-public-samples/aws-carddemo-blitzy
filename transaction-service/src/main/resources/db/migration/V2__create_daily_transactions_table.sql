-- V2__create_daily_transactions_table.sql
-- Daily-transaction posting feed. Owned by transaction-service.
-- Source: app/cpy/CVTRA06Y.cpy (DALYTRAN-RECORD, RECLN 350). Entity: com.carddemo.common.domain.DailyTransaction.
-- FILLER X(20) not persisted. No FK on dalytran_card_num (feed may carry reject-able cards; CBTRN02C code 100).
CREATE TABLE daily_transactions (
    dalytran_id             VARCHAR(16)   PRIMARY KEY,   -- DALYTRAN-ID X(16)
    dalytran_type_cd        VARCHAR(2),                  -- DALYTRAN-TYPE-CD X(02)
    dalytran_cat_cd         INTEGER,                     -- DALYTRAN-CAT-CD 9(04)
    dalytran_source         VARCHAR(10),                 -- DALYTRAN-SOURCE X(10)
    dalytran_desc           VARCHAR(100),                -- DALYTRAN-DESC X(100)
    dalytran_amt            NUMERIC(11,2),               -- DALYTRAN-AMT S9(09)V99 (exact scale; AAP 0.6.1)
    dalytran_merchant_id    BIGINT,                      -- DALYTRAN-MERCHANT-ID 9(09)
    dalytran_merchant_name  VARCHAR(50),                 -- DALYTRAN-MERCHANT-NAME X(50)
    dalytran_merchant_city  VARCHAR(50),                 -- DALYTRAN-MERCHANT-CITY X(50)
    dalytran_merchant_zip   VARCHAR(10),                 -- DALYTRAN-MERCHANT-ZIP X(10)
    dalytran_card_num       VARCHAR(16),                 -- DALYTRAN-CARD-NUM X(16)
    dalytran_orig_ts        VARCHAR(26),                 -- DALYTRAN-ORIG-TS X(26) (first 10 chars drive reject 103)
    dalytran_proc_ts        VARCHAR(26)                  -- DALYTRAN-PROC-TS X(26)
);
