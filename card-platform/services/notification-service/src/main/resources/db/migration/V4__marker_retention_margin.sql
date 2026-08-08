-- Notification service, migration V4.
-- Corrects the retention statement V1 wrote onto processed_event.
--
-- V1 described that table's window as "retention=7 days ... 168 hours by default" and said the
-- window "belongs at or above the retention of every topic this service consumes". Two of those
-- three statements are no longer true, and the third was the finding.
--
-- The default is 720 hours rather than 168. A security review found the marker horizon set equal to
-- broker log retention, which makes duplicate suppression hold only if a swept marker and an
-- unreadable record happen at the same instant. Three ordinary events break that: segment cleanup
-- is not instant, a restored backup carries a record older than the broker would still hold, and an
-- operator resetting a consumer group replays whatever the log still has. Any one of them leaves a
-- record readable after its marker is gone, and this service then renders and stores a second alert
-- for a transaction the cardholder was already told about.
--
-- "At or above" is therefore replaced by a margin. config/NotificationProperties now carries the
-- broker figure beside the marker horizon and refuses a horizon under twice it at start-up, so the
-- relationship is enforced where the two values arrive rather than described here. The shipped pair
-- is 720 hours of markers against 168 hours of broker log.
--
-- V1 is left as it ran. An applied migration is not edited, so this migration re-issues the comment
-- rather than changing the one that created the table. Nothing else about the table changes: no
-- column, no index, no constraint and no row.

COMMENT ON TABLE processed_event IS
    'retention=30 days; purge_key=processed_at; personal_data=no. Duplicate-delivery marker.
     The window is carddemo.processed-event.marker-retention-hours in
     src/main/resources/application.yml, 720 hours by default and overridable by
     PROCESSED_EVENT_RETENTION_HOURS, and domain/RetentionSweep applies it. That setting is
     the authority: a window restated here as a second number would drift from the one the
     sweep reads. It has to OUTLAST rather than merely match the retention of every topic
     this service consumes, because a record still readable after its marker is swept is
     applied a second time. config/NotificationProperties refuses a horizon under twice
     carddemo.processed-event.broker-retention-hours at start-up, and the shipped pair is 720
     hours of markers against 168 hours of broker log.';
