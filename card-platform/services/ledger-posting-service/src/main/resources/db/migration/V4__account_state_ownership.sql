-- Record in the database which writer owns each value column of account_balance_projection, now that
-- an arriving account change no longer replaces any of them.
--
-- The statement this corrects. V3__account_state_replica.sql set a table comment ending "the account
-- service owns the record; AccountStateChanged replaces the three value columns and the posting path
-- adds to them." The second clause described a real behaviour and that behaviour was wrong. Replacing
-- current_balance, cycle_credit and cycle_debit from an inbound account change discarded every
-- movement the posting path had derived since the account service last read the record, silently: no
-- log line, no metric, no dead letter. messaging/AccountStateChangedConsumer no longer does it.
--
-- Why the comment is corrected here and not there. V3 has already run everywhere this service is
-- deployed, and Flyway checksums an applied migration precisely so that its text cannot be rewritten
-- afterwards. Editing V3 does not correct history, it invalidates it: the next start fails validation
-- with a checksum mismatch and the service does not come up. So V3 keeps the wording that was true on
-- the day it ran, and this migration carries the wording that is true now.
--
-- What owns what. Two readings of one account exist and they are not interchangeable.
--
--   * The account service owns the account record itself, and an AccountStateChanged carries its
--     reading of the balance. That reading is behind, because the account service consumes no
--     TransactionPosted, so nothing there learns of a posting.
--   * This projection's three value columns are derived here, by the balance and accumulator
--     arithmetic of app/cbl/CBTRN02C.cbl:L545-L560, and that reading is the current one.
--
-- An arriving change therefore never overwrites them. AccountBalanceProjectionRepository exposes only
-- the two effects an account change is entitled to have: insertMissingProjection opens a row for an
-- account this service holds none for, and closeBillingCycle moves zero into the two accumulators for
-- a cycle close, reproducing app/cbl/CBACT04C.cbl:L353-L354 and nothing else in that program. An
-- ordinary field update writes nothing at all, because every field it could carry is owned elsewhere.
--
-- No schema changes. Only comments are written, so this migration is safe to run against a table the
-- posting path is actively using and it holds no lock any longer than the catalogue update needs.

COMMENT ON TABLE account_balance_projection IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account balance
     and billing-cycle projection. account_id links it to a named customer. The account service
     owns the account record, and this projection owns the three value columns: the posting path
     at app/cbl/CBTRN02C.cbl:L545-L560 derives them, and an arriving AccountStateChanged replaces
     none of them. See V4__account_state_ownership.sql for why the reading held here is the
     current one.';

COMMENT ON COLUMN account_balance_projection.current_balance IS
    'ACCT-CURR-BAL at app/cpy/CVACT01Y.cpy:L7. Derived here alone, by the add at
     app/cbl/CBTRN02C.cbl:L547. No account change writes it: not a field update, and not a billing
     cycle close, which at app/cbl/CBACT04C.cbl:L353-L354 leaves the balance untouched.';

COMMENT ON COLUMN account_balance_projection.cycle_credit IS
    'ACCT-CURR-CYC-CREDIT at app/cpy/CVACT01Y.cpy:L12. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L549 when the amount is zero or positive. A billing cycle close is the
     only account change that writes it, moving zero in after app/cbl/CBACT04C.cbl:L353.';

COMMENT ON COLUMN account_balance_projection.cycle_debit IS
    'ACCT-CURR-CYC-DEBIT at app/cpy/CVACT01Y.cpy:L13. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L551 when the amount is negative, which is the sign convention flagged
     for review in docs/business-rule-flags.md. A billing cycle close moves zero in after
     app/cbl/CBACT04C.cbl:L354.';
