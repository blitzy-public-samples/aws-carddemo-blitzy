-- Authorization service, migration V12.
-- Records the accounts whose replica copy is missing a change, and retires the age-based
-- freshness bound that stood in for this table.
--
-- WHY THIS FILE EXISTS. This service authorizes against two replicas: card_xref, kept current by
-- CardUpdated, and account_credit_snapshot, kept current by AccountStateChanged. Both producers
-- publish on a state change and on nothing else. The service asked "how old is this row's last
-- observation" and refused a call past carddemo.replica.max-staleness, which read a correct copy of
-- an unedited card as a stale one: every card the platform holds and nobody edits crossed the bound
-- in a day, and every call for it then answered 503 while readiness still reported UP. The question
-- was wrong rather than the bound. What matters is whether a published change is missing, not how
-- long ago the last one arrived.
--
-- WHAT REPLACES IT. Two measurements, neither of which reads a row's age.
--
--   1. Stream synchronization. messaging/KafkaReplicaSynchronization reads, for each replica topic,
--      whether a listener exists, is running, holds partitions, and reports lag within
--      carddemo.replica.lag-ceiling. A caught-up consumer of a quiet topic reports zero lag, so an
--      unedited card stays authorizable for as long as it stays unedited.
--
--   2. This table. A record that was delivered and could not be applied has its offset advanced once
--      its diagnostic is away, so lag returns to zero while one account's copy is behind. The
--      consumer that failed writes the account here, in its own transaction so the row survives the
--      rollback of the attempt, and deletes it when a later record for that account applies. A
--      decision that reads either replica row consults this table and refuses the call while a row
--      stands, which is the case the old bound was reaching for and never actually detected: a
--      failed application left observed_at exactly as recent as a successful one.
--
-- WHY A TABLE AND NOT A COUNTER. The obligation has to outlive the process. A counter in memory
-- forgets on restart, and the account it forgot then authorizes against a credit limit or an expiry
-- this service knows it failed to apply. A row also names the account, so an operator can see which
-- accounts are affected instead of only that something failed.
--
-- KEY. One row per account per stream. The same account can be behind on both streams
-- independently, and the pair is what a delete has to name so clearing one stream does not clear the
-- other.

CREATE TABLE replica_gap
(
    -- The account whose copy is missing a change. Width from XREF-ACCT-ID PIC 9(11) at
    -- app/cpy/CVACT03Y.cpy:L7, which is the width every message key on this platform carries.
    -- Text rather than numeric because it is compared against a message key and a path value, both
    -- of which are text, and because a leading zero is part of the identifier.
    aggregate_id    CHAR(11)    NOT NULL,

    -- The topic whose record could not be applied. Held rather than derived so a gap on one replica
    -- does not hide or clear a gap on the other.
    stream          VARCHAR(64) NOT NULL,

    -- When this account first failed on this stream, and when it last did. The pair is diagnostic
    -- only: nothing decides anything from either value, which is the whole point of this table
    -- replacing an age comparison.
    first_failed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_failed_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- How many deliveries for this account have failed on this stream. An operator reading a rising
    -- count knows the failure is persistent rather than a single lost race.
    failure_count   INTEGER     NOT NULL DEFAULT 1,

    -- The class name of the last failure, and nothing else from it. A failure message can quote the
    -- record it was raised for, and a record on either replica topic carries a credit limit, a cycle
    -- balance and an account identifier. A class name carries none of that.
    last_failure    VARCHAR(64),

    CONSTRAINT pk_replica_gap PRIMARY KEY (aggregate_id, stream),

    CONSTRAINT ck_replica_gap_aggregate_id CHECK (aggregate_id ~ '^[0-9]{11}$'),
    CONSTRAINT ck_replica_gap_stream CHECK (length(trim(stream)) > 0),
    CONSTRAINT ck_replica_gap_failure_count CHECK (failure_count >= 1),
    CONSTRAINT ck_replica_gap_order CHECK (last_failed_at >= first_failed_at)
);

COMMENT ON TABLE replica_gap IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. One row per account per
     replica stream whose record this service could not apply. domain/AuthorizationService refuses a
     decision that would read either replica row of an account named here, so the row lives exactly
     as long as the gap it names: messaging/AccountStateChangedConsumer and
     messaging/CardUpdatedConsumer delete it when a later record for that account applies. No time
     window expires it, deliberately -- a window would let a decision read a copy this service knows
     is missing a change, which is the defect this table exists to close.';

COMMENT ON COLUMN replica_gap.aggregate_id IS
    'The account whose copy is missing a change, eleven digits, from XREF-ACCT-ID PIC 9(11) at
     app/cpy/CVACT03Y.cpy:L7.';

COMMENT ON COLUMN replica_gap.stream IS
    'The replica topic whose record could not be applied.';

COMMENT ON COLUMN replica_gap.last_failure IS
    'The class name of the last failure. No failure message and no value from the record.';
