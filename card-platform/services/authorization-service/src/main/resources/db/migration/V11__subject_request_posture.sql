-- Authorization service, migration V11.
-- Removes the card_xref comment's claim that an erasure propagates into this replica.
--
-- WHY THIS FILE EXISTS. A security review found the account service's customer table promising
-- "erase on request" while nothing on this platform implemented an export or an erasure. Correcting
-- that one sentence was not enough, because three other schemas described the same absent workflow
-- from their own side. The account service corrected its customer comment in
-- V7__account_customer_link.sql, the notification service corrected cardholder_context in its
-- V6__subject_request_posture.sql, and this file corrects the third: the replica this service reads
-- on the first hop of every authorization.
--
-- WHAT card_xref SAID. Its comment ended "erasure follows the card service deleting the card". That
-- names a mechanism in two parts, and neither part exists. The card service deletes no card row and
-- no cross-reference row: the only delete statements in services/card-service are the outbox and
-- marker purges in its domain/RetentionSweep. And no removal reaches this replica even in principle,
-- because messaging/CardUpdatedConsumer applies one shape only, an upsert of the card-to-account
-- pairing carried by CardUpdated, and messaging/AccountStateChangedConsumer does the same for the
-- credit snapshot. Neither consumer has a delete branch, and no card-removed topic exists to carry
-- one. A reader planning for a subject request would have found a sentence and no code.
--
-- WHAT IT SAYS NOW. The row lives as long as the card the card service holds, which is the true
-- statement the old comment was reaching for, followed by the position rather than a promise.
--
-- WHY THE CAPABILITY IS NOT BUILT HERE. An export and erasure workflow is a platform capability
-- rather than a comment: it needs an authenticated and authorized entry point, a legal-hold
-- exception, an auditable tombstone, an idempotent event reaching every replica and read model, and
-- a completion report. This replica is one of the five stores such an event would have to reach.
-- card-platform/docs/suggested-next-tasks.md carries the task and names every one of them.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 is left as it ran, and its
-- retention and purge_key tokens are re-issued unchanged so the horizon this table declares -- which
-- is none -- reads the same to equivalence-tests/RetentionSweepContractTest as it did before.

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference, the first hop of every authorization, keyed by card number from
     app/jcl/XREFFILE.jcl:L39-L47 with the account index from app/jcl/XREFFILE.jcl:L72-L77. A row
     lives as long as the card the card service holds, and no window expires it. NO ERASURE OR
     EXPORT WORKFLOW EXISTS on this platform: this comment records the position rather than a
     promise, and an earlier version of it said erasure followed the card service deleting the card,
     which was true of neither service. The card service deletes no card and no cross-reference row,
     and messaging/CardUpdatedConsumer applies an upsert with no delete branch.
     card-platform/docs/suggested-next-tasks.md carries the erasure and export task and names every
     store it would have to reach, this replica among them.';
