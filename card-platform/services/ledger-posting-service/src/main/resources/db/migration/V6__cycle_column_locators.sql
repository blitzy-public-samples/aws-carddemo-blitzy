-- Ledger posting service, migration V6.
-- Re-issues two column comments whose source citation named the wrong copybook line.
--
-- WHY THIS FILE EXISTS. A review checked the citations in this schema against the copybook and
-- found two off by one. V4__account_state_ownership.sql attributed cycle_credit to
-- ACCT-CURR-CYC-CREDIT at app/cpy/CVACT01Y.cpy:L12 and cycle_debit to ACCT-CURR-CYC-DEBIT at
-- app/cpy/CVACT01Y.cpy:L13. The copybook declares ACCT-REISSUE-DATE PIC X(10) at L12,
-- ACCT-CURR-CYC-CREDIT PIC S9(10)V99 at L13 and ACCT-CURR-CYC-DEBIT PIC S9(10)V99 at L14, so both
-- citations pointed one line above the field they named and the credit citation pointed at a date.
--
-- A citation is the whole value of these comments. Rule 1 requires every mapping to be traceable to
-- the construct it came from, and a locator a reader cannot follow is worse than none: it sends the
-- reader to a field of another type and invites the conclusion that the mapping itself is wrong.
--
-- WHY IT IS A MIGRATION RATHER THAN AN EDIT. V4 has run. Flyway compares the checksum of every
-- applied migration at start-up, so editing an applied file stops every service that already has
-- the schema, and the demo database survives docker compose down on the postgres-data volume.
-- Correcting forward is the same course V5 took over V3's marker identity and the account service's
-- V8 took over a V1 column comment.
--
-- WHAT CHANGES. Two comment strings. No table, column, index, constraint or row is declared,
-- altered or removed here, and no behaviour depends on a comment. Every other sentence of the two
-- comments is carried across unchanged, because the ownership statement V4 established is correct
-- and this migration corrects a locator rather than revisiting it.

COMMENT ON COLUMN account_balance_projection.cycle_credit IS
    'ACCT-CURR-CYC-CREDIT at app/cpy/CVACT01Y.cpy:L13. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L549 when the amount is zero or positive. A billing cycle close is the
     only account change that writes it, moving zero in after app/cbl/CBACT04C.cbl:L353.';

COMMENT ON COLUMN account_balance_projection.cycle_debit IS
    'ACCT-CURR-CYC-DEBIT at app/cpy/CVACT01Y.cpy:L14. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L551 when the amount is negative, which is the sign convention flagged
     for review in docs/business-rule-flags.md. A billing cycle close moves zero in after
     app/cbl/CBACT04C.cbl:L354.';
