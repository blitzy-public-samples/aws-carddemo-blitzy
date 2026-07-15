-- Flyway migration V1 -- account-service (CardDemo Feature F-003)
-- Creates the `accounts` relational table that replaces the legacy 300-byte VSAM
-- ACCOUNT-RECORD (KSDS). Mapped one-to-one from app/cpy/CVACT01Y.cpy (12 real fields,
-- L5-L16) plus one new optimistic-locking control column `version`. The trailing
-- FILLER PIC X(178) (L17) is intentionally dropped (pure padding, no semantics).
--
-- Key semantics from app/jcl/ACCTFILE.jcl: KEYS(11 0) -> 11-digit numeric primary key,
-- preserved as a zero-padded VARCHAR(11) (NOT BIGINT) so leading zeros survive and the
-- key stays join-compatible with the still-COBOL card (CARD-ACCT-ID PIC 9(11)) and
-- cross-reference (XREF-ACCT-ID PIC 9(11)) files. See AAP 0.6.1.
--
-- Flyway is the single source of truth for the schema; Hibernate runs with
-- spring.jpa.hibernate.ddl-auto=validate and only validates the Account @Entity against
-- this table. Column names/types MUST stay in lock-step with Account.java.
--   * Monetary columns are NUMERIC(12,2) (never float/double/real) -> S9(10)V99, BigDecimal scale 2.
--   * active_status is CHAR(1) (bpchar) to match the entity's @JdbcTypeCode(SqlTypes.CHAR).

CREATE TABLE accounts (
    account_id           VARCHAR(11)   NOT NULL,
    active_status        CHAR(1)       NOT NULL,
    current_balance      NUMERIC(12,2) NOT NULL,
    credit_limit         NUMERIC(12,2) NOT NULL,
    cash_credit_limit    NUMERIC(12,2) NOT NULL,
    open_date            DATE          NOT NULL,
    expiration_date      DATE          NOT NULL,
    reissue_date         DATE          NOT NULL,
    current_cycle_credit NUMERIC(12,2) NOT NULL,
    current_cycle_debit  NUMERIC(12,2) NOT NULL,
    address_zip          VARCHAR(10)   NOT NULL,
    group_id             VARCHAR(10)   NOT NULL,
    version              BIGINT        NOT NULL,
    CONSTRAINT pk_accounts PRIMARY KEY (account_id),
    CONSTRAINT ck_accounts_account_id_numeric CHECK (account_id ~ '^[0-9]{11}$')
);
