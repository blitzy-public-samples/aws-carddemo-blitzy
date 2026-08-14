-- Gives account_balance_projection the provenance of the account state it last replicated.
--
-- The table arrived in V1__schema.sql with four columns and two writers: V2__seed.sql loaded the
-- fifty rows of app/data/ASCII/acctdata.txt, and the posting path added to them. Nothing told it
-- when the account service moved the original, so an account opened after deployment had no row and
-- a billing cycle closed at app/cbl/CBACT04C.cbl:L353-L354 never reached the two accumulators here.
-- messaging/AccountStateChangedConsumer is the writer that closes both gaps, and it needs somewhere
-- to record which change it applied.
--
-- source_occurred_at is the ordering value and it is the producer's clock, not this service's. One
-- statement in AccountBalanceProjectionRepository.applyStateChange compares it before it writes, so
-- a redelivery arriving behind a newer change discards itself instead of moving a cycle balance
-- backwards. A stale accumulator is not a loud failure: the source reads one ACCTDAT record at
-- app/cbl/CBTRN02C.cbl:L545, and a replica that regressed would quietly accumulate onto a number the
-- account service had already superseded.
--
-- A row whose source_occurred_at is NULL is a row V2__seed.sql wrote, and the first change for that
-- account supersedes it.
--
-- The posting path carries both values forward unchanged. A posting is a delta this service owns and
-- it does not come from the account service's clock, so it advances neither column; clearing them
-- would let a change this projection had already applied apply a second time.
--
-- The CHECK holds both halves of the provenance together, matching the same constraint on
-- account_credit_snapshot in the authorization service: a row carries both or neither.
ALTER TABLE account_balance_projection ADD COLUMN source_event_id UUID;

ALTER TABLE account_balance_projection ADD COLUMN source_occurred_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE account_balance_projection
    ADD CONSTRAINT ck_account_balance_projection_provenance
        CHECK ((source_event_id IS NULL) = (source_occurred_at IS NULL));

COMMENT ON COLUMN account_balance_projection.source_event_id IS
    'AccountStateChanged event that last replaced this row, NULL for a seeded row no change has
     superseded. Held beside source_occurred_at so a replicated row can be traced to its cause.';

COMMENT ON COLUMN account_balance_projection.source_occurred_at IS
    'When that change occurred, on the account service clock. The ordering value: a change not after
     the stored one is discarded. NULL for a seeded row, which any change supersedes.';

COMMENT ON TABLE account_balance_projection IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account balance
     and billing-cycle projection. account_id links it to a named customer. The account service
     owns the record; AccountStateChanged replaces the three value columns and the posting path
     adds to them.';
