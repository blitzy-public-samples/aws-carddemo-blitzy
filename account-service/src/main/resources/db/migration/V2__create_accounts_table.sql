-- account-service Flyway V2: create the accounts table.
-- Source record layout: app/cpy/CVACT01Y.cpy (ACCOUNT-RECORD, RECLN 300).
-- Column names/types match carddemo-common Account.java (@Table "accounts")
-- so Hibernate ddl-auto=validate passes. Money columns are NUMERIC(12,2)
-- (COBOL S9(10)V99) - do not change the scale (financial-precision rule).
CREATE TABLE accounts (
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
    acct_group_id          VARCHAR(10),                        -- ACCT-GROUP-ID          PIC X(10)  (blank in seed -> nullable)
    version                BIGINT         NOT NULL DEFAULT 0   -- optimistic-lock @Version (no legacy field; AAP 0.6.2)
);
