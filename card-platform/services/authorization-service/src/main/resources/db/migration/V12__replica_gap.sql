-- Authorization service, migration V12. Declares replica_gap: one row per account per stream whose
-- replica copy is missing a delivered change.
--
-- Statements: one CREATE TABLE, one COMMENT ON TABLE and three COMMENT ON COLUMN.
--
-- The mechanics. This service authorizes against two replicas: card_xref, written by CardUpdated, and
-- account_credit_snapshot, written by AccountStateChanged. A record that was delivered and could not be
-- applied has its offset advanced once its diagnostic is away, so consumer lag returns to zero while
-- one account's copy is behind. The consumer that failed writes the account here, in a transaction of
-- its own so the row survives the rollback of the attempt, and deletes it when a later record for that
-- account applies. A decision that reads either replica row consults this table and refuses the call
-- while a row stands. Consumer lag itself is measured separately, by
-- messaging/KafkaReplicaSynchronization against carddemo.replica.lag-ceiling.
--
-- The key is (aggregate_id, stream). One account can be behind on both streams independently, and the
-- pair is what a delete names so clearing one stream does not clear the other.
--
-- This migration also retires carddemo.replica.max-staleness, the age bound that stood in for the
-- table. Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md.

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
