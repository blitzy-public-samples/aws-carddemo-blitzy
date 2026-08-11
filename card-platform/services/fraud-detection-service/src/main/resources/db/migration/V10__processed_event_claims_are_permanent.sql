-- Fraud detection service, migration V10.
-- Withdraws the retention horizon of processed_event, so a duplicate-delivery claim is permanent.
--
-- What a security review found. The horizon was 720 hours, checked at start-up against twice the
-- 168-hour broker log retention. That relationship bounds only how long the BROKER can redeliver a
-- record. It says nothing about how long the side effect a claim guards stands, and here a claim
-- guards the risk assessment of a transaction and the velocity counters an assessment reads. An
-- assessment is kept for 90 days and a velocity window for its configured span, each far longer
-- than any marker horizon a review found acceptable. A record archived, restored or deliberately
-- replayed after the horizon is new to the guard, and the side effect is applied a second time.
--
-- The behaviour now. Nothing deletes a row of this table. domain/RetentionSweep no longer names it,
-- config/FraudProperties no longer binds a marker horizon, and repository/ProcessedEventRepository
-- no longer carries a bounded delete. A claim and the effect it guards commit in one local
-- transaction in one database, so a consistent backup and a consistent restore carry both or
-- neither, and no window can retire a claim while its effect stands.
--
-- What bounds growth instead: nothing, deliberately. One row holds a UUID, a topic name and a
-- timestamp, and the table gains one row per event a listener of this service consumes. The
-- partitioning work a deployment measuring real volumes would want is recorded in
-- card-platform/docs/suggested-next-tasks.md, and the alternatives weighed are in
-- card-platform/docs/decision-log.md.
--
-- V1 is left as it ran. Its COMMENT ON named the horizon as this table's retention and
-- processed_at as its purge key, and the statement below supersedes both, so a fresh database and
-- a migrated one end on the same catalogue text. The card service records the same practice at V5
-- and V8.
--
-- ix_processed_event_processed_at is dropped with the purge it served. The processed_at column
-- stays, because it is the evidence of when a claim was written, and no statement orders or filters
-- by it any more.
--
-- Idempotent in the sense Flyway needs: this file runs once, and IF EXISTS lets it run against a
-- database at any state the earlier migrations of this service could leave.

DROP INDEX IF EXISTS ix_processed_event_processed_at;

COMMENT ON TABLE processed_event IS
    'retention=permanent; purge_key=none; personal_data=no. Duplicate-delivery marker, and the claim
     a consumer of this service writes in the same local transaction as the side effects it guards.
     Nothing expires a row: V10 withdrew the horizon a security review found too short, because a
     risk assessment and the velocity counters behind it outlives any window a marker horizon could
     name. A replay of a record this table already claims is suppressed however long ago the claim
     was written.';

COMMENT ON COLUMN processed_event.processed_at IS
    'When the claim was written, which is the moment the side effects it guards committed. Evidence
     rather than a purge key: no statement of this platform orders, filters or deletes by it, and
     V10 dropped the index that once served the retention purge.';
