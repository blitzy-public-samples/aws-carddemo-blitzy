-- Notification service, migration V5. Says what notification_log records: an alert this service
-- rendered and did not send.
--
-- Statements, in order: rename attempted_at to rendered_at; rename the index named for the old column;
-- add outcome; back-fill it; make it NOT NULL; add ck_notification_log_outcome admitting
-- RENDERED_NOT_SENT and nothing else; and issue one COMMENT ON TABLE and two COMMENT ON COLUMN. No row
-- changes its meaning: the value attempted_at held was always Clock.instant() at the moment the
-- renderer finished, and a column rename leaves an index definition correct because PostgreSQL indexes
-- columns by number.
--
-- What the service does. domain/NotificationService renders a document, writes one row and returns the
-- document to its caller; every caller is a Kafka listener and every listener discards the text. This
-- service holds no electronic mail gateway, no short-message gateway, no webhook client and no push
-- client, and its README lists all four under deliberate non-additions. The claim therefore lives in
-- the constraint rather than in prose: a change that starts sending has to widen
-- ck_notification_log_outcome to SENT and FAILED in a commit a reviewer can see.
--
-- A delivery port with an authenticated transport, acknowledgement and retry is a new capability rather
-- than a correction; AAP 0.1.1 specifies this service as rendering a cardholder alert, and
-- card-platform/docs/suggested-next-tasks.md carries the task. V1 is left as it ran.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md.

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
