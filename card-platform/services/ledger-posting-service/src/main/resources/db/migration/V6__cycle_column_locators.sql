-- Ledger posting service, migration V6. Corrects the copybook locator in two column comments.
--
-- Statements: two COMMENT ON COLUMN. No table, column, index, constraint or row is declared, altered
-- or removed, and every other sentence V4__account_state_ownership.sql wrote is carried across.
--
-- V4 cited cycle_credit as app/cpy/CVACT01Y.cpy:L12 and cycle_debit as :L13. The copybook declares
-- ACCT-REISSUE-DATE PIC X(10) at L12, ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at L13 and
-- ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at L14, so both locators pointed one line above their field and
-- the credit locator pointed at a date. The comments below carry L13 and L14.
--
-- A correction to an applied migration arrives as a new migration because Flyway compares the
-- checksum of every applied file at start-up. Design decisions:
-- card-platform/docs/decision-log.md.

COMMENT ON COLUMN account_balance_projection.cycle_credit IS
    'ACCT-CURR-CYC-CREDIT at app/cpy/CVACT01Y.cpy:L13. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L549 when the amount is zero or positive. A billing cycle close is the
     only account change that writes it, moving zero in after app/cbl/CBACT04C.cbl:L353.';

COMMENT ON COLUMN account_balance_projection.cycle_debit IS
    'ACCT-CURR-CYC-DEBIT at app/cpy/CVACT01Y.cpy:L14. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L551 when the amount is negative, which is the sign convention flagged
     for review in docs/business-rule-flags.md. A billing cycle close moves zero in after
     app/cbl/CBACT04C.cbl:L354.';
