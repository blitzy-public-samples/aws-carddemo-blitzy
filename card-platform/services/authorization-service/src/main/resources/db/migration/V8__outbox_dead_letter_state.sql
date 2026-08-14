-- Authorization service, migration V8.
-- Makes the terminal diagnostic of an abandoned outbox row a durable obligation rather than one
-- unrecorded attempt taken at the moment the relay gives up.
--
-- The gap this closes. outbox_event arrived in V1__schema.sql with relay_state, attempt_count and
-- next_attempt_at, which together decide when the relay stops attempting a row. ABANDONED is
-- terminal, and the claim query in OutboxEventRepository.claimDueRows returns PENDING rows only, so
-- the pass that abandons a row was the last pass that looked at it. outbox/OutboxRelay recorded that
-- state and published nothing at all: the readiness indicator in config/ReadinessHealthConfig
-- reported an abandoned row and no diagnostic ever named which event it was. One authorization event
-- could therefore be given up on with the only record of it a warning in one container's log, which
-- is the record that disappears with the container.
--
-- The account service already answers this way, in its own V5__outbox_dead_letter_state.sql. This
-- migration brings the authorization outbox to the same contract. The card, ledger and notification
-- relays do not carry it yet, so an operator reading the dead-letter topic sees every abandoned row
-- of the services that do and none of the three that do not. Extending it to those three is recorded
-- in card-platform/docs/suggested-next-tasks.md rather than claimed here.
--
-- The source answer to a write it cannot complete is 9999-ABEND-PROGRAM at
-- app/cbl/CBTRN02C.cbl:L707-L711: four statements that display one message, move 999 into an abend
-- code and call CEE3ABD. That leaves the operator a job log, and the job log is exactly what is lost
-- when the address space goes. Naming the row on a topic is the target form of that paragraph, and it
-- is an improvement only if the naming actually happens.
--
-- dead_letter_state is that obligation. The relay writes REQUIRED in the same transaction as the
-- abandonment, so neither fact can commit without the other, and writes PUBLISHED only once the
-- broker has acknowledged the diagnostic. A row still holding REQUIRED after a broker outage, a
-- restart or a redeployment is offered again on a later pass, which is what a durable column buys
-- over one awaited send.
--
-- Every existing row takes NOT_REQUIRED, which is correct for all four states V1 could leave behind.
-- A PENDING or CLAIMED row has attempts left. A PUBLISHED row was delivered. An ABANDONED row written
-- before this migration was never named, and marking it REQUIRED would publish a diagnostic for an
-- event an operator has already read out of the readiness indicator and the log.
ALTER TABLE outbox_event
    ADD COLUMN dead_letter_state VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED';

ALTER TABLE outbox_event
    ADD COLUMN dead_letter_published_at TIMESTAMP(6) WITH TIME ZONE;

-- The three names entity/OutboxEventEntity.DeadLetterState declares, and no fourth.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_state
        CHECK (dead_letter_state IN ('NOT_REQUIRED', 'REQUIRED', 'PUBLISHED'));

-- The state and the timestamp move together, so a reader cannot find an acknowledged diagnostic with
-- no moment, or an owed one that already carries a moment.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_published_at
        CHECK ((dead_letter_published_at IS NOT NULL) = (dead_letter_state = 'PUBLISHED'));

-- A diagnostic is owed by an abandoned row and by no other row. PENDING and CLAIMED rows have
-- attempts left and PUBLISHED rows were delivered, so neither owes anything, and a NOT_REQUIRED
-- abandoned row can only be one this migration converted.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_pairing
        CHECK (dead_letter_state = 'NOT_REQUIRED' OR relay_state = 'ABANDONED');

-- Partial index over the owed diagnostics alone, which is the set every pass reads. It stays the size
-- of the backlog of abandoned rows rather than the size of the table, and it is empty while the relay
-- is healthy. last_attempt_at is the ordering, so the oldest unnamed row is named first.
CREATE INDEX ix_outbox_event_dead_letter_required
    ON outbox_event (last_attempt_at) WHERE dead_letter_state = 'REQUIRED';

COMMENT ON COLUMN outbox_event.dead_letter_state IS
    'NOT_REQUIRED, REQUIRED or PUBLISHED. REQUIRED is a durable obligation to name this abandoned row
     on the dead-letter topic, written in the same transaction as the abandonment and cleared only by
     a broker acknowledgement.';

COMMENT ON COLUMN outbox_event.dead_letter_published_at IS
    'When the broker acknowledged the terminal diagnostic for this row. NULL while one is owed and
     NULL when none is owed.';
