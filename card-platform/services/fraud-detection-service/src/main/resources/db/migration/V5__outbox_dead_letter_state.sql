-- Fraud detection service, migration V5.
-- Makes the terminal diagnostic of an abandoned outbox row a durable obligation rather than one
-- unrecorded attempt taken at the moment the relay gives up.
--
-- The gap this closes. outbox_event arrived in V1__schema.sql with relay_state, attempt_count and
-- next_attempt_at, which together decide when the relay stops attempting a row. ABANDONED is
-- terminal, and the claim query returns PENDING rows only, so the pass that abandons a row was the
-- last pass that looked at it. outbox/OutboxRelay does publish a diagnostic for the two failures it
-- can recognise on sight -- a payload no schema governs, and an event type it cannot map to a topic
-- -- but repeated transient failure takes a different path: each attempt is recorded, the attempt
-- ceiling is reached, the row becomes ABANDONED, and nothing is published. A FraudFlagged assessment
-- could therefore be given up on with the only record of it a warning in one container's log, which
-- is the record that disappears with the container.
--
-- This matters more here than the volume suggests. Fraud detection is the ADDITIVE capability of this
-- platform: it has no COBOL ancestor, so there is no batch job an operator can re-run to recover a
-- lost assessment and no reject dataset holding what was missed. The event this service publishes is
-- the only record that its rules ever ran on a transaction. An abandoned row that names itself
-- nowhere is a risk assessment that silently never happened.
--
-- The authorization and account services already answer this way, in their own V8 and V5. This
-- migration brings the fraud outbox to the same contract, which makes three of the six. The card,
-- ledger and notification relays do not carry it, so an operator reading the dead-letter topic still
-- sees the abandoned rows of three services and not of six. Extending it is recorded in
-- card-platform/docs/suggested-next-tasks.md rather than claimed here.
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
