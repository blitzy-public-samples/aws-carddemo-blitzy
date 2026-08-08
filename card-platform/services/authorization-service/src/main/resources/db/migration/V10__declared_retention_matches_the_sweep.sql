-- Authorization service, migration V10.
-- Makes the catalogue say what the retention sweep does, for the two tables where it did not.
--
-- WHY THIS FILE EXISTS. A security review found three tables on this platform whose
-- COMMENT ON TABLE declared a retention window that nothing applied. Fixing those exposed the same
-- defect in the opposite direction here: two tables of this service are deleted on a timer while
-- the catalogue says, or implies, that they are not.
--
-- authorization_decision said 'retention=relationship'. That reads as a row living as long as the
-- cardholder relationship, and domain/RetentionSweep deletes it after
-- carddemo.retention.decision-retention-days, which the shipped configuration sets to 365. An
-- operator reading the catalogue would plan a five-year audit trail and find a one-year one.
--
-- unresolved_card_attempt carried no COMMENT ON TABLE at all, and the same sweep purges it on the
-- same horizon. A table with no comment states nothing, which is a smaller failure than stating the
-- wrong thing, and it is still the one table of this service a reader could not classify.
--
-- WHAT THE WINDOW ACTUALLY IS. One setting drives both deletes, so both comments name it rather
-- than repeating a number that would drift from the one the sweep reads. That is the convention
-- processed_event already follows in V1__schema.sql: 'retention=' names the property, not a value.
--
-- WHY 365 DAYS IS NOT 'relationship'. Both rows are attribution records for a financial decision,
-- so they outlive the outbox row that published it. They do not outlive the customer. Whether a
-- year is the right window for a declined-authorization audit trail is a business decision rather
-- than an engineering one, and card-platform/docs/suggested-next-tasks.md carries it.
--
-- Nothing else changes. No column, no index, no constraint and no row. V1 and V3 are left as they
-- ran, because an applied migration is not edited.

COMMENT ON TABLE authorization_decision IS
    'retention=carddemo.retention.decision-retention-days; purge_key=decided_at;
     personal_data=pseudonymous. One authorization decision and the request identity behind it.
     The card number is masked and the account identifier is a pseudonymous reference to a
     cardholder, so the row describes an identifiable person once it is joined with the
     account service. It is the attribution record for a financial decision, so it outlives
     the outbox row the decision published: the horizon is 365 days by default against 168
     hours for a published outbox row. It does NOT live as long as the cardholder
     relationship, which an earlier version of this comment said.
     purge_op=AuthorizationDecisionRepository.deleteDecidedBefore, called by
     domain/RetentionSweep.';

COMMENT ON TABLE unresolved_card_attempt IS
    'retention=carddemo.retention.decision-retention-days; purge_key=attempted_at;
     personal_data=pseudonymous. One authorization attempt whose card resolved to no account,
     which is reject code 0100 at app/cbl/CBTRN02C.cbl:L385. The card number is masked to
     twelve mask characters and the last four digits, and no full Primary Account Number and
     no card verification value reaches any column here. There is no account identifier to
     hold: the short-circuit at app/cbl/CBTRN02C.cbl:L376-L378 stops the account read from
     running, so the row is pseudonymous through its masked card and its transaction
     identifier alone. It shares the decision horizon because it is the same kind of record,
     the attribution of one refusal.
     purge_op=UnresolvedCardAttemptRepository.deleteAttemptedBefore, called by
     domain/RetentionSweep.';
