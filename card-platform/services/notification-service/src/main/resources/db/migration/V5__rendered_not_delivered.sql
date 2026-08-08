-- Notification service, migration V5.
-- Says what notification_log actually records: an alert this service rendered and did not send.
--
-- WHY THIS FILE EXISTS. V1__schema.sql called this table "one row per delivery attempt" and named
-- its timestamp column attempted_at. A security review read that and asked which transport made the
-- attempt. There is none. This service has no electronic mail gateway, no short-message gateway, no
-- webhook client and no push client; its own README lists all four under deliberate non-additions.
-- domain/NotificationService renders a document, writes one row, and returns the document to its
-- caller. Every caller is a Kafka listener, and every listener discards the returned text.
--
-- WHY THE WORDING MATTERED MORE THAN THE COLUMN. An operator reading "delivery attempt" in the
-- catalogue, or a row count in an incident review, would conclude that a cardholder had been told
-- about a transaction or a fraud flag. Nothing left the process. That is a worse failure than a
-- missing feature, because it is one nobody looks for: the table exists, the rows accumulate, and
-- every one of them is evidence of something that did not happen.
--
-- WHAT THIS MIGRATION DOES. Three things, none of which changes what is stored.
--
-- 1. attempted_at becomes rendered_at. The value written was always Clock.instant() at the moment
--    the renderer finished, so the new name is the one the value already had.
-- 2. A new outcome column carries RENDERED_NOT_SENT, and a CHECK permits that value and no other.
--    This is the point of the migration. The claim now lives in the data rather than in prose, so a
--    future change that starts sending has to widen a constraint and a test, in a commit a reviewer
--    can see, rather than quietly changing what a row means.
-- 3. The index named for the old column is renamed. A column rename leaves an index definition
--    correct, because PostgreSQL indexes columns by number, so only the name was stale.
--
-- WHAT A DELIVERY PORT WOULD ADD. The review's first-choice resolution is a delivery port with an
-- authenticated transport, acknowledgement, retry and an explicit outcome. That is a new capability
-- rather than a correction: section 0.1.1 of the plan specifies this service as "rendering a
-- cardholder alert", and a transport is outside it. outcome is where such a port would report
-- itself, by widening the CHECK to SENT and FAILED. card-platform/docs/suggested-next-tasks.md
-- carries the task.
--
-- V1 is left as it ran. An applied migration is not edited.

ALTER TABLE notification_log
    RENAME COLUMN attempted_at TO rendered_at;

ALTER INDEX ix_notification_log_attempted_at
    RENAME TO ix_notification_log_rendered_at;

-- Added nullable, backfilled, then made mandatory, which is the same three-step shape
-- V3__processed_event_topic_key.sql used for consumed_topic. Every row already in the table
-- records a rendered alert that was not sent, because nothing has ever sent one.
ALTER TABLE notification_log
    ADD COLUMN outcome VARCHAR(20);

UPDATE notification_log
SET outcome = 'RENDERED_NOT_SENT'
WHERE outcome IS NULL;

ALTER TABLE notification_log
    ALTER COLUMN outcome SET NOT NULL;

-- One permitted value, deliberately. A column every row shares a value with is normally worth
-- removing; this one is a contract. entity/NotificationLogEntity writes the constant and offers no
-- way for a caller to supply another, and this constraint refuses a value written any other way. A
-- deployment that adds a transport widens the list here and in that class together.
ALTER TABLE notification_log
    ADD CONSTRAINT ck_notification_log_outcome
        CHECK (outcome = 'RENDERED_NOT_SENT');

COMMENT ON TABLE notification_log IS
    'retention=90 days; purge_key=rendered_at; personal_data=pseudonymous;
     purge_op=NotificationLogRepository.deleteRenderedBefore. One alert this service RENDERED
     and did NOT send, carrying the card token, the masked card number the alert showed, the
     transaction it reported and the format it was rendered in. No column holds the rendered
     document. This service reaches no mail, message, webhook or push gateway, so no row here
     is evidence that a cardholder was told anything: outcome carries RENDERED_NOT_SENT and a
     CHECK permits nothing else. The row exists to explain what was rendered and when, and it
     expires with that question: carddemo.history.log-retention-days is the window,
     domain/RetentionSweep applies it, and that setting is the authority rather than the
     ninety days named here. An earlier version of this comment described the row as a
     delivery attempt, which was never true.';

COMMENT ON COLUMN notification_log.rendered_at IS
    'The instant domain/NotificationService finished rendering the alert, from its injected
     Clock. Named attempted_at until V5, which claimed a delivery this platform does not
     perform. It is the purge key: domain/RetentionSweep deletes rows older than
     carddemo.history.log-retention-days, and ix_notification_log_rendered_at serves that
     delete.';

COMMENT ON COLUMN notification_log.outcome IS
    'RENDERED_NOT_SENT, and ck_notification_log_outcome permits no other value. Adding a
     delivery port means widening that constraint and entity/NotificationLogEntity together,
     which is a visible change rather than a quiet one.';
