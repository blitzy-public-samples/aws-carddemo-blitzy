-- Authorization service, migration V10. Declares the retention window of two tables the sweep purges.
--
-- Statements: two COMMENT ON TABLE. No column, index, constraint or row is declared, altered or
-- removed. V1 and V3 are left as they ran.
--
-- Both comments now name carddemo.retention.decision-retention-days, the one setting
-- domain/RetentionSweep reads for both deletes, rather than a number of their own.
-- authorization_decision is purged by AuthorizationDecisionRepository.deleteDecidedBefore on
-- decided_at and previously read 'retention=relationship'; unresolved_card_attempt is purged by
-- UnresolvedCardAttemptRepository.deleteAttemptedBefore on attempted_at and previously carried no
-- table comment at all. equivalence-tests/RetentionSweepContractTest compares both declarations
-- against the sweep.
--
-- Whether a year is the right window for a declined-authorization audit trail is a business question,
-- and card-platform/docs/suggested-next-tasks.md carries it. Rationale, alternatives considered and
-- accepted risks: card-platform/docs/decision-log.md.

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
