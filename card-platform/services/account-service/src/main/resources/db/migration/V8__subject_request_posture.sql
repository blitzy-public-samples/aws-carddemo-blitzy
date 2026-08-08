-- Account service, migration V8.
-- Removes the last column comment that described an erasure request as something this platform
-- services.
--
-- WHY THIS FILE EXISTS. A security review found this schema's customer table promising "erase on
-- request" while no controller, event, service or repository implemented an export or an erasure.
-- V7__account_customer_link.sql corrected that table comment and named every store such a workflow
-- would have to reach. One sentence was missed, a column below it: social_security_number said "an
-- erasure request clears it with the rest of the row". That is the same promise in miniature, and it
-- sits on the single most sensitive column in this schema, which is exactly where a reader is most
-- likely to trust it.
--
-- WHAT IT SAYS NOW. The two true statements are kept -- the column reaches no event, no log line and
-- no response body, and it is held because app/cpy/CVCUS01Y.cpy:L17 declares it and the edit at
-- app/cbl/COACTUPC.cbl:L2431-L2491 reads it -- and the erasure sentence becomes the conditional it
-- always was, next to the statement that no such workflow exists.
--
-- WHY THE CAPABILITY IS NOT BUILT HERE. An export and erasure workflow is a platform capability
-- rather than a comment: it needs an authenticated and authorized entry point, a legal-hold
-- exception, an auditable tombstone, an idempotent event reaching every replica and read model, and
-- a completion report. card-platform/docs/suggested-next-tasks.md carries the task and names all
-- five stores it would have to reach.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 is left as it ran, and this
-- statement carries no retention or purge_key token because a column comment declares no horizon:
-- equivalence-tests/RetentionSweepContractTest reads COMMENT ON TABLE only.

COMMENT ON COLUMN customer.social_security_number IS
    'personal_data=yes. Held because app/cpy/CVCUS01Y.cpy:L17 declares it and the edit at
     app/cbl/COACTUPC.cbl:L2431-L2491 reads it. It reaches no event, no log line and no response
     body. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform: an erasure would clear this value
     with the rest of the customer row, and nothing here performs one today.
     card-platform/docs/suggested-next-tasks.md carries the task.';
