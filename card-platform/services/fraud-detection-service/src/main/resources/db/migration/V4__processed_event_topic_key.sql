-- Fraud detection service, migration V4. Re-keys processed_event on (event_id, consumed_topic).
--
-- Statements, in order: back-fill a NULL consumed_topic with the sentinel '(no topic header)',
-- which entity/ProcessedEventEntity.NO_CONSUMED_TOPIC also declares and which no Kafka topic name
-- can match because a topic name holds only [a-zA-Z0-9._-]; set the column NOT NULL; drop the old
-- primary key; add pk_processed_event over both columns; add ck_processed_event_consumed_topic; and
-- issue COMMENT ON CONSTRAINT, which is where the identity rule is recorded for a reader of the
-- catalogue.
--
-- One listener writes this table: messaging/TransactionAuthorizedConsumer, reading
-- transaction.authorized.
--
-- Design decisions: card-platform/docs/decision-log.md, under
-- "What identifies one delivery of one event". Corrections to an applied migration arrive as a new
-- migration because Flyway compares the checksum of every applied file at start-up.

UPDATE processed_event
SET consumed_topic = '(no topic header)'
WHERE consumed_topic IS NULL;

ALTER TABLE processed_event
    ALTER COLUMN consumed_topic SET NOT NULL;

-- The old key is dropped and the new one added in one statement each, in this order, because a
-- table cannot carry two primary keys. V1__schema.sql names the old key pk_processed_event, so it
-- is dropped by that name. The rows are unique under the wider key wherever they were unique under
-- the narrower one, so no row is lost between the two statements.
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
-- whose own processed_at is newer than the horizon, which would unguard a delivery the retention
-- rule was not asked to forget. ix_processed_event_processed_at from V1__schema.sql still serves the
-- ordering.
COMMENT ON CONSTRAINT pk_processed_event ON processed_event IS
    'One event identifier per consumed topic. Producing services assign identifiers independently, so the identifier alone does not identify a delivery.';
