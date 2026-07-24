-- account-service Flyway V1: create the customers table.
-- Source record layout: app/cpy/CVCUS01Y.cpy (CUSTOMER-RECORD, RECLN 500).
-- Column names/types match carddemo-common Customer.java (@Table "customers")
-- so Hibernate ddl-auto=validate passes at account-service startup.
CREATE TABLE customers (
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
    cust_ssn                 VARCHAR(512)  NOT NULL,           -- CUST-SSN                 PIC 9(09)  (PII, encrypted at rest via CryptoConverter)
    cust_govt_issued_id      VARCHAR(512)  NOT NULL,           -- CUST-GOVT-ISSUED-ID      PIC X(20)  (PII, encrypted at rest via CryptoConverter)
    cust_dob_yyyy_mm_dd      VARCHAR(10)   NOT NULL,           -- CUST-DOB-YYYY-MM-DD      PIC X(10)  date YYYY-MM-DD
    cust_eft_account_id      VARCHAR(512)  NOT NULL,           -- CUST-EFT-ACCOUNT-ID      PIC X(10)  (PII, encrypted at rest via CryptoConverter)
    cust_pri_card_holder_ind VARCHAR(1)    NOT NULL,           -- CUST-PRI-CARD-HOLDER-IND PIC X(01)
    cust_fico_credit_score   INTEGER       NOT NULL,           -- CUST-FICO-CREDIT-SCORE   PIC 9(03)
    version                  BIGINT        NOT NULL DEFAULT 0  -- JPA @Version optimistic-lock counter (no legacy analogue)
);
-- COBOL FILLER PIC X(168) is trailing record padding and is intentionally not persisted.
