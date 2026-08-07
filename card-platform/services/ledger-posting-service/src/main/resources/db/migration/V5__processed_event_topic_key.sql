-- Makes the consumed topic part of the marker's identity, so one event can be claimed per topic.
--
-- WHY THIS FILE EXISTS. V1__schema.sql keyed processed_event on event_id alone and recorded consumed_topic beside
-- it as description. Two listener groups share this table and read two different topics:
-- messaging/TransactionAuthorizedConsumer reads transaction.authorized, published by the
-- authorization service, and messaging/AccountStateChangedConsumer reads account.state-changed,
-- published by the account service. Each producer assigns its own event identifiers, and neither
-- knows what the other assigns, so two different events may carry one identifier without either
-- producer being at fault. Keyed on the identifier alone, this service was the one where that
-- collision was reproducible.
--
-- WHAT WENT WRONG. A posting claims its marker at messaging/TransactionAuthorizedConsumer, and a later
-- account.state-changed event carrying the same identifier reached
-- ProcessedEventRepository.claimEvent, whose ON CONFLICT (event_id) DO NOTHING reported the row
-- already taken. messaging/AccountStateChangedConsumer read that as a redelivery and applied
-- nothing: the replica row the event carried was never written, and a BILLING_CYCLE_CLOSED event
-- lost that way leaves the cycle accumulators of the balance projection standing where the
-- account service has already zeroed them. Nothing raised, nothing reached a dead-letter topic,
-- and the only trace was one increment of the duplicates-skipped counter, which reads identically
-- for a real duplicate. No replay restores the lost work, because the marker that suppressed it
-- stays.
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
-- processed_at and deletes by the primary key, so it now names both key columns. Deleting by
-- event_id alone would remove a marker of the same identifier on another topic whose own
-- processed_at is newer than the horizon, which would unguard a delivery the retention rule was
-- not asked to forget. ix_processed_event_processed_at from V1__schema.sql still serves the
-- ordering.
COMMENT ON CONSTRAINT pk_processed_event ON processed_event IS
    'One event identifier per consumed topic. Two listener groups read two topics whose identifiers two producing services assign independently, so the identifier alone does not identify a delivery.';
