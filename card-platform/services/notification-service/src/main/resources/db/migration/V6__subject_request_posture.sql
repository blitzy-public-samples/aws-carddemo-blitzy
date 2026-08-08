-- Notification service, migration V6.
-- Removes the last schema comment on this platform that implied a data-subject erasure workflow.
--
-- WHY THIS FILE EXISTS. A security review found the account service's customer table promising
-- "erase on request" while no controller, event, service or repository implemented an export or an
-- erasure anywhere. The account service corrected its own comment in
-- V7__account_customer_link.sql, and that correction names every store an erasure would have to
-- reach, this read model among them. This file corrects the other half of the same sentence.
--
-- WHAT cardholder_context SAID. Its comment ended "observed_at serves a purge of accounts the
-- account service has erased". That reads as a mechanism: erasures happen upstream and this column
-- is how they are applied here. Neither half is true. No upstream erasure exists, no event carries
-- one, this service subscribes to no such topic, and nothing in domain/RetentionSweep or any
-- repository of this service deletes a cardholder_context row. A reader planning for a subject
-- request would have found a column and no workflow.
--
-- WHAT IT SAYS NOW. observed_at is what it has always been: the moment the projection last changed,
-- used to reject a CustomerContextChanged event older than the row it would overwrite. That
-- ordering guard is real and messaging/CustomerContextChangedConsumer implements it. If an erasure
-- workflow is ever built, observed_at is a plausible thing for it to read, and that is a different
-- statement from the one this comment used to make.
--
-- WHY THE CAPABILITY IS NOT BUILT HERE. An export and erasure workflow is a platform capability
-- rather than a comment: it needs an authenticated and authorized entry point, a legal-hold
-- exception, an auditable tombstone, an idempotent event reaching every replica and read model, and
-- a completion report. Four schemas hold a copy of something an erasure would have to touch.
-- card-platform/docs/suggested-next-tasks.md carries the task and names every store.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 is left as it ran.

COMMENT ON TABLE cardholder_context IS
    'retention=relationship; purge_key=none; personal_data=yes. Projection of the ten
     cardholder fields one alert reports, owned by the account service and applied here from
     CustomerContextChanged. A row lives as long as the customer relationship and no window
     expires it. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform, here or upstream: this
     comment records the position rather than a promise, and an earlier version of it described
     observed_at as serving a purge of erased accounts, which was never implemented anywhere.
     observed_at is the ordering guard: messaging/CustomerContextChangedConsumer refuses an
     event older than the row it would overwrite, so a replay cannot move a projection
     backwards. card-platform/docs/suggested-next-tasks.md carries the erasure and export task
     and names every store it would have to reach.';
