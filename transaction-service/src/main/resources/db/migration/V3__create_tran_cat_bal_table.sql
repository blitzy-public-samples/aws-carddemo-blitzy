-- V3__create_tran_cat_bal_table.sql
-- Transaction-category balance. Owned by transaction-service. Compound primary key.
-- Source: app/cpy/CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD, RECLN 50). Entity: com.carddemo.common.domain.TranCatBal @IdClass(TranCatBalId).
-- FILLER X(22) not persisted. Authoritative table name is tran_cat_bal (ddl-auto=validate).
CREATE TABLE tran_cat_bal (
    trancat_acct_id   BIGINT,          -- TRANCAT-ACCT-ID 9(11)  (11-digit id -> BIGINT)
    trancat_type_cd   VARCHAR(2),      -- TRANCAT-TYPE-CD X(02)
    trancat_cd        INTEGER,         -- TRANCAT-CD 9(04)
    tran_cat_bal      NUMERIC(11,2),   -- TRAN-CAT-BAL S9(09)V99 (exact scale; interest multiplicand, AAP 0.6.1)
    PRIMARY KEY (trancat_acct_id, trancat_type_cd, trancat_cd)
);
