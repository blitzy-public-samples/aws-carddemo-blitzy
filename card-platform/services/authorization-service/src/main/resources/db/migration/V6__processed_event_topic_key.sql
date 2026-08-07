-- Makes the consumed topic part of the marker's identity, so one event can be claimed per topic.
--
-- WHY THIS FILE EXISTS. V1__schema.sql keyed processed_event on event_id alone and recorded consumed_topic beside
-- it as description. Two listener groups share this table and read two different topics:
-- messaging/CardUpdatedConsumer reads card.updated, published by the card service, and
-- messaging/AccountStateChangedConsumer reads account.state-changed, published by the account
-- service. Each producer assigns its own event identifiers, and neither knows what the other
-- assigns, so two different events may carry one identifier without either producer being at
-- fault.
--
-- WHAT WENT WRONG. Both listeners read the marker with existsById(eventId) before acting and write it with save.
-- Keyed on the identifier alone, the second of two same-identifier events on two topics reads a
-- marker its own topic never wrote, concludes it has already handled the event, and applies
-- nothing. That is exactly the outcome an idempotency guard is meant to produce for a REDELIVERY,
-- and exactly the wrong outcome for a DIFFERENT event: the replica row that event carried is
-- dropped in silence. The two replicas this service keeps are what the decline rules read, so a
-- dropped row leaves a rule authorizing against a value the owning service has already changed.
-- Nothing raises and nothing reaches a dead-letter topic, and no replay restores the lost work,
-- because the marker that suppressed it stays.
--
-- WHAT IDENTIFIES A DELIVERY. A delivery is identified by the event AND the stream it arrived on.
-- The composite primary key below says so, and it is the guard as well as the key: an insert that
-- collides is still how a consumer learns the event was already handled on that topic, so the guard
-- cannot be checked and then raced past. Duplicate suppression within one topic is unchanged, which
-- is the property every consumer relies on; only the cross-topic collision stops being one.
--
-- WHY NOT A CONSUMER GROUP OR A CONSUMER NAME. The topic is what a delivery carries in its own
-- RECEIVED_TOPIC header, so a listener records what it observed rather than what it was configured
-- as. A key built from configuration would change identity whenever a group was renamed.
--
-- THE BACKFILL. consumed_topic was nullable and a marker written before this migration may carry
-- NULL. A primary key column cannot be NULL, so those rows take one sentinel value.
-- '(no topic header)' holds spaces and parentheses, and a Kafka topic name holds only
-- [a-zA-Z0-9._-], so the sentinel can never collide with a real topic name.
-- entity/ProcessedEventEntity.NO_CONSUMED_TOPIC declares the same text, and it is the value a
-- delivery reaching a listener with no topic header records.
--
-- services/notification-service/src/main/resources/db/migration/V3__processed_event_topic_key.sql
-- made the same change first, for the service whose four listeners exposed the defect.
UPDATE processed_event
SET consumed_topic = '(no topic header)'
WHERE consumed_topic IS NULL;

ALTER TABLE processed_event
    ALTER COLUMN consumed_topic SET NOT NULL;

-- The old key is dropped and the new one added in one statement each, in this order, because a
-- table cannot carry two primary keys. V1__schema.sql declares the old key inline as
-- PRIMARY KEY (event_id) and names it nothing, so
-- PostgreSQL named it processed_event_pkey and it is dropped by that name. The four other
-- services of this platform name theirs pk_processed_event; the name below is the one this
-- schema actually carries, and the new key takes the platform name so all six agree from here
-- on. The rows are unique under the wider key wherever they were unique under the narrower one, so
-- no row is lost between the two statements.
ALTER TABLE processed_event
    DROP CONSTRAINT processed_event_pkey;

ALTER TABLE processed_event
    ADD CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumed_topic);

-- A blank topic name names no topic and would key a row nothing can find again.
-- entity/ProcessedEventEntity refuses one on the way in; this refuses one already stored.
ALTER TABLE processed_event
    ADD CONSTRAINT ck_processed_event_consumed_topic
        CHECK (btrim(consumed_topic) <> '');

-- The retention scan in ProcessedEventRepository.deleteMarkersProcessedBefore selects by
-- processed_at and deletes by the primary key, so it now names both key columns. Deleting by
-- event_id alone would remove a marker of the same identifier on another topic whose own
-- processed_at is newer than the horizon, which would unguard a delivery the retention rule was
-- not asked to forget. ix_processed_event_processed_at from V1__schema.sql still serves the
-- ordering.
COMMENT ON CONSTRAINT pk_processed_event ON processed_event IS
    'One event identifier per consumed topic. Two listener groups read two topics whose identifiers two producing services assign independently, so the identifier alone does not identify a delivery.';
