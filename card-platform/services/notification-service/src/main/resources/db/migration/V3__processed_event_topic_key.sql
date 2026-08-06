-- Makes the consumed topic part of the marker's identity, so four listeners can claim one event.
--
-- WHY THIS FILE EXISTS. V1__schema.sql keyed processed_event on event_id alone and recorded
-- consumed_topic beside it as description. Four listener groups share the table:
-- messaging/TransactionAuthorizedConsumer, messaging/TransactionPostedConsumer,
-- messaging/FraudFlaggedConsumer and messaging/CustomerContextChangedConsumer. Each reads a
-- different topic, and the event identifiers on those topics are assigned independently by three
-- different producing services. Two events on two topics may therefore carry the same identifier
-- without either producer being at fault.
--
-- WHAT WENT WRONG. The insert in ProcessedEventRepository.claimEvent carried
-- ON CONFLICT (event_id) DO NOTHING, so the second of two same-identifier events lost the claim to
-- the first and its listener wrote nothing. That is exactly the outcome an idempotency guard is
-- meant to produce for a REDELIVERY, and exactly the wrong outcome for a DIFFERENT event: the
-- statement row, the alert or the cardholder projection the second event carried was dropped in
-- silence. No exception was raised, nothing reached a dead-letter topic, and the only trace was one
-- increment of the duplicates-skipped counter, which reads identically for a real duplicate. The
-- read model was left short a row that no replay could restore, because the marker that suppressed
-- it stays.
--
-- WHAT IDENTIFIES A DELIVERY. A delivery is identified by the event AND the stream it arrived on.
-- The composite primary key below says so, and it is the guard as well as the key: an insert that
-- collides is still how a consumer learns the event was already handled on that topic, so the
-- guard cannot be checked and then raced past. Duplicate suppression within one topic is unchanged,
-- which is the property every consumer relies on; only the cross-topic collision stops being one.
--
-- WHY NOT A CONSUMER GROUP OR A CONSUMER NAME. The topic is what the delivery carries in its own
-- RECEIVED_TOPIC header, so a listener records what it observed rather than what it was configured
-- as. Two listeners never read one topic in this service, so the topic already separates them, and a
-- key built from configuration would change identity whenever a group was renamed.
--
-- THE BACKFILL. consumed_topic was nullable and a marker written before it existed carries NULL. A
-- primary key column cannot be NULL, so those rows take one sentinel value. '(no topic header)'
-- holds spaces and parentheses, and a Kafka topic name holds only [a-zA-Z0-9._-], so the sentinel
-- can never collide with a real topic name. entity/ProcessedEventEntity.NO_CONSUMED_TOPIC declares
-- the same text, and it is the value a delivery reaching a listener with no topic header records.
UPDATE processed_event
SET consumed_topic = '(no topic header)'
WHERE consumed_topic IS NULL;

ALTER TABLE processed_event
    ALTER COLUMN consumed_topic SET NOT NULL;

-- The old key is dropped and the new one added in one statement each, in this order, because a
-- table cannot carry two primary keys. The rows are unique under the wider key wherever they were
-- unique under the narrower one, so no row is lost between the two statements.
ALTER TABLE processed_event
    DROP CONSTRAINT pk_processed_event;

ALTER TABLE processed_event
    ADD CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumed_topic);

-- A blank topic name names no topic and would key a row nothing can find again.
-- entity/ProcessedEventEntity refuses one on the way in; this refuses one already stored.
ALTER TABLE processed_event
    ADD CONSTRAINT ck_processed_event_consumed_topic
        CHECK (btrim(consumed_topic) <> '');

-- The retention scan in ProcessedEventRepository.deleteMarkersProcessedBefore selects by
-- processed_at and deletes by the primary key, so it now names both key columns in the subquery it
-- builds. Deleting by event_id alone would remove a marker of the same identifier on another topic
-- whose own processed_at is newer than the horizon. ix_processed_event_processed_at from
-- V1__schema.sql still serves the ordering.
COMMENT ON CONSTRAINT pk_processed_event ON processed_event IS
    'One event identifier per consumed topic. Four listener groups share this table and three producing services assign identifiers independently, so the identifier alone does not identify a delivery.';
