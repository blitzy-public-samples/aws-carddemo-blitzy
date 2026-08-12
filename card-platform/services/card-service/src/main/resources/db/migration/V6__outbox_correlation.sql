-- Card service, migration V6.
-- Carries the two correlation identifiers of one unit of work on the outbox row, so the relay can
-- attach them to the record it publishes.
--
-- What this file declares. An observability review found that a reader could not follow one
-- authorization across the events it produces. Every derived event stamped a fresh eventId and named
-- no parent, so joining a ledger record to the authorization that caused it meant matching domain
-- values by hand. A dead-letter record named the event that failed and not the call behind it.
--
-- The identifiers as columns rather than payload properties. AAP 0.3.1 fixes the event envelope
-- at five properties, and every schema document under
-- card-platform/libs/event-contracts/src/main/resources/schemas closes its top-level property set.
-- The identifiers therefore travel as Kafka record headers, which no schema governs, and these two
-- columns are what lets them survive the outbox hop: the relay sweeps rows on its own schedule, long
-- after the thread that wrote the row has gone.
--
-- This service publishes CardUpdated. A change made through PUT /cards/{cardToken} carries the
-- correlation identifier of that request and no causation, because a caller rather than an event
-- asked for it.
--
-- BOTH COLUMNS ARE NULLABLE. A row written before this migration ran carries neither, and a row
-- written outside a request and outside a delivery carries neither. An absent identifier contributes
-- no header rather than a header holding nothing, so a consumer that reads a header always reads a
-- value.

ALTER TABLE outbox_event
    ADD COLUMN correlation_id UUID;

ALTER TABLE outbox_event
    ADD COLUMN causation_id UUID;

COMMENT ON COLUMN outbox_event.correlation_id IS
    'Root correlation identifier of the unit of work that wrote this row, published as the '
    'carddemo-correlation-id record header. Repeats across a fan-out by design, and is never the '
    'duplicate-delivery key.';

COMMENT ON COLUMN outbox_event.causation_id IS
    'eventId of the event whose handling wrote this row, published as the carddemo-causation-id '
    'record header. Null where a caller rather than an event asked for the change.';
