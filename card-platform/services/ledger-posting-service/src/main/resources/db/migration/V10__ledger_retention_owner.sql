-- Names an owner for the three tables this service never deletes from.
--
-- V1 declared what does NOT happen to them: retention=relationship, purge_key=none, and no timer
-- deletes a financial record. That much was right and still is. What it never said is who decides
-- what does happen, and by when. A comment that records only an absence reads, to the next person,
-- as a question already settled. It was not settled; it was deferred.
--
-- The three tables below grow by one row per posted transaction and never shrink. At demonstration
-- volume that is invisible. Under sustained use it is the largest table in the platform, and the
-- decision it needs is not a technical one this migration is entitled to make: how long a posted
-- transaction is retained is fixed by the jurisdiction the deployment operates in, and deleting one
-- early is a worse failure than keeping it too long.
--
-- So this migration assigns the obligation rather than discharging it. Each comment below now names
-- the owner, what the owner has to decide, and the fact that the decision is outstanding. The task
-- is tracked in card-platform/docs/suggested-next-tasks.md.
--
-- It is a migration rather than an edit to V1 because V1 has run.

COMMENT ON TABLE transaction IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Posted transaction,
     including amount, merchant detail, masked card and transaction identifier. Those values
     can resolve to a named customer through the owning services. A financial record is not
     purged on a timer, and domain/RetentionSweep does not touch this table.
     ARCHIVAL OWNER: UNASSIGNED. This is the one table in the platform that grows without bound
     by design, at one row per posted transaction. Before sustained use, the records-retention
     owner of the deployment must fix the statutory period, choose between archive-then-delete
     and monthly partition detach, and name who runs it. Both routes are open: proc_ts carries
     the posting instant this service assigns, so it is the range key either one would scan.
     Tracked in card-platform/docs/suggested-next-tasks.md. Until that owner is named, treat this
     table as permanent storage and size the volume for it.';

COMMENT ON TABLE transaction_category_balance IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account category
     balance. account_id links the financial aggregate to a named customer through the account
     service. Derived from transaction rows and as durable as they are.
     ARCHIVAL OWNER: UNASSIGNED, and bounded by the accounts and categories in use rather than by
     the transaction count, so it grows far more slowly than transaction. It holds a running total
     rather than a history: a row removed here loses a balance and cannot be rebuilt from what
     remains, so it follows the decision made for transaction and is never purged ahead of it.';

COMMENT ON TABLE account_balance_projection IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Per-account balance
     and billing-cycle projection. account_id links it to a named customer.
     ARCHIVAL OWNER: UNASSIGNED, and one row per account rather than one per transaction, so it
     does not grow with volume at all. It is the balance the authorization path reads through its
     replica, so a removed row is a live account that can no longer be authorized. It is retired
     with the account it describes and on no other schedule.';
