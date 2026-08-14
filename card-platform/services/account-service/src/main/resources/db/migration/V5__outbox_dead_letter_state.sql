-- Account service, migration V5.
-- Makes the terminal diagnostic of an abandoned outbox row a durable obligation rather than one
-- attempt taken at the moment the relay gives up.
--
-- outbox_event arrived in V1__schema.sql with relay_state, attempt_count and next_attempt_at, which
-- together decide when the relay stops attempting a row. ABANDONED is terminal and the claim query
-- in OutboxEventRepository.claimDueRows returns only PENDING rows, so the sweep that abandons a row
-- is the last sweep that looks at it. outbox/OutboxRelay published one dead letter inside that sweep
-- and did not wait for the broker, so a broker that refused it left the row terminal, unpublished
-- and named nowhere. The only record of a lost account state change was a warning in one container's
-- log.
--
-- The source answer to a write it cannot complete is 9999-ABEND-PROGRAM at
-- app/cbl/CBTRN02C.cbl:L707-L711: four statements that display one message, move 999 into an abend
-- code and call CEE3ABD. The operator is left the job log, and the job log is exactly the record
-- that is lost when the address space goes. Naming the row on a topic is the target form of that
-- paragraph, and it is only an improvement if the naming actually happens.
--
-- dead_letter_state is that obligation. The relay writes REQUIRED in the same transaction as the
-- abandonment, so the two facts cannot separate, and writes PUBLISHED only after the broker
-- acknowledges the diagnostic. A row still holding REQUIRED after a broker outage, a restart or a
-- redeployment is retried on a later sweep, which is what a durable column buys over an awaited
-- send alone.
--
-- Every existing row is NOT_REQUIRED, which is correct for all three states V1 could leave behind.
-- A PENDING or CLAIMED row has attempts left. A PUBLISHED row is delivered. An ABANDONED row
-- written before this migration already had its one unrecorded attempt, and marking it REQUIRED
-- would republish a diagnostic for an event whose row the operator has already seen.
ALTER TABLE outbox_event
    ADD COLUMN dead_letter_state VARCHAR(16) NOT NULL DEFAULT 'NOT_REQUIRED';

ALTER TABLE outbox_event ADD COLUMN dead_letter_published_at TIMESTAMP(6) WITH TIME ZONE;

-- The three names entity/OutboxEventEntity.DeadLetterState declares, and no fourth.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_state
        CHECK (dead_letter_state IN ('NOT_REQUIRED', 'REQUIRED', 'PUBLISHED'));

-- The state and the timestamp move together, so a reader cannot find an acknowledged diagnostic
-- with no moment or an owed one that carries a moment already.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_published_at
        CHECK ((dead_letter_published_at IS NOT NULL) = (dead_letter_state = 'PUBLISHED'));

-- A diagnostic is owed by an abandoned row and by no other row. PENDING and CLAIMED rows have
-- attempts left and PUBLISHED rows were delivered, so neither owes anything, and a NOT_REQUIRED
-- abandoned row can only be one this migration converted.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_event_dead_letter_pairing
        CHECK (dead_letter_state = 'NOT_REQUIRED' OR relay_state = 'ABANDONED');

-- Partial index over the owed diagnostics alone, which is the set every sweep reads. It stays the
-- size of the backlog of abandoned rows rather than the size of the table, and it is empty while the
-- relay is healthy. last_attempt_at is the ordering, so the oldest unnamed row is named first.
CREATE INDEX ix_outbox_event_dead_letter_required
    ON outbox_event (last_attempt_at) WHERE dead_letter_state = 'REQUIRED';

COMMENT ON COLUMN outbox_event.dead_letter_state IS
    'NOT_REQUIRED, REQUIRED or PUBLISHED. REQUIRED is a durable obligation to name this abandoned
     row on the dead-letter topic, written in the same transaction as the abandonment and cleared
     only by a broker acknowledgement.';

COMMENT ON COLUMN outbox_event.dead_letter_published_at IS
    'When the broker acknowledged the terminal diagnostic for this row. NULL while one is owed and
     NULL when none is owed.';
