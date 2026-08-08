-- Card service, migration V4.
-- Removes the card_xref comment's claim that an erasure request reaches this replica.
--
-- WHY THIS FILE EXISTS. A security review found the account service's customer table promising
-- "erase on request" while nothing on this platform implemented an export or an erasure. Three other
-- schemas described the same absent workflow from their own side. The account service corrected its
-- customer comment in V7__account_customer_link.sql, the notification service corrected
-- cardholder_context in its V6__subject_request_posture.sql, the authorization service corrected its
-- replica in its V11__subject_request_posture.sql, and this file corrects the last one.
--
-- WHAT card_xref SAID. Its comment ended "an erasure request has to reach it". Read on its own that
-- describes a live obligation: requests arrive, and this row is one of the places they are applied.
-- Neither half happens. No endpoint on this service or any other accepts a subject request, no event
-- carries one, and this service deletes no cross-reference row: the only delete statements in this
-- module are the outbox and marker purges in domain/RetentionSweep. A reader planning for a subject
-- request would have found a sentence and no code.
--
-- WHAT IT SAYS NOW. The obligation is stated as the conditional it is, and the absence is stated
-- outright, so the sentence is useful to whoever builds the workflow and misleading to nobody before
-- then.
--
-- WHY THE CAPABILITY IS NOT BUILT HERE. An export and erasure workflow is a platform capability
-- rather than a comment: it needs an authenticated and authorized entry point, a legal-hold
-- exception, an auditable tombstone, an idempotent event reaching every replica and read model, and
-- a completion report. card-platform/docs/suggested-next-tasks.md carries the task and names every
-- store it would have to reach.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 is left as it ran, and its
-- retention and purge_key tokens are re-issued unchanged so the horizon this table declares -- which
-- is none -- reads the same to equivalence-tests/RetentionSweepContractTest as it did before. The
-- card table's own comment is left alone: it speaks about dropping the card_verification_value
-- column, which is a schema change a deployment may choose, and not about a data-subject workflow.

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference replica, kept current by the events this service publishes when a card changes. Its
     card, account and customer identifiers link the row to one person, and a row lives as long as
     the card it names. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform: this comment records
     the position rather than a promise, and an earlier version of it said an erasure request has to
     reach this row, which described an obligation nothing discharges. This service deletes no
     cross-reference row; domain/RetentionSweep purges published outbox rows and processed-event
     markers and nothing else. card-platform/docs/suggested-next-tasks.md carries the erasure and
     export task and names every store it would have to reach, this replica among them.';
