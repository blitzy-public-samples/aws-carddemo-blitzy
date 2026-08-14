-- Fraud detection service, migration V8.
-- Adds the index the relay's aggregate-head claim reads.
--
-- What this file declares. outbox/OutboxRelay claims the due head row of each account, so a batch never
-- holds two rows of one account and no two events of one account are ever in flight together. The
-- account identifier is the Kafka message key, so that restriction is what keeps every consumer of an
-- account reading its events in the order this service wrote them. repository/OutboxEventRepository
-- expresses it as a correlated NOT EXISTS over aggregate_id, relay_state, created_at and event_id,
-- and a performance review found no index over those columns: the check re-read the backlog of an
-- account for every candidate row, so claim work grew with the square of the backlog rather than
-- with the batch.
--
-- The columns this index covers. aggregate_id selects the account, created_at then event_id are the order the
-- subquery compares on, and the partial predicate holds the index to the rows the check considers.
-- PENDING and CLAIMED are the two non-terminal states: a PUBLISHED row is done and an ABANDONED row
-- is never offered again, so neither can precede a head and neither belongs in this index. The index
-- is therefore the size of the unpublished backlog rather than the size of the table, which is the
-- same shape as ix_outbox_event_pending beside it.
--
-- NOTHING ELSE CHANGES. No column, constraint, default or row is touched, and no existing index is
-- dropped. Flyway applies this file once; a schema already carrying the index is not possible,
-- because this file introduces the name.
CREATE INDEX ix_outbox_event_aggregate_head
    ON outbox_event (aggregate_id, created_at, event_id)
 WHERE relay_state IN ('PENDING', 'CLAIMED');

COMMENT ON INDEX ix_outbox_event_aggregate_head IS
    'Serves the correlated aggregate-head check of repository/OutboxEventRepository.claimDueRows.
     One in-flight event per account is what preserves per-account order on the topic, and this
     index is what makes proving it cheap.';
