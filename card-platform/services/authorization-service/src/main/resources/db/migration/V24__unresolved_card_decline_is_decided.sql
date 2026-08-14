-- Authorization service, migration V24.
-- Restates the three comments V20 wrote, because the outcome they describe is decided again.
--
-- Statements: three COMMENT statements. No table, column, index, constraint or row is declared,
-- altered or removed. The retention, purge_key, personal_data and purge_op tokens
-- equivalence-tests/RetentionSweepContractTest and SubjectDataGovernanceContractTest read are
-- re-issued unchanged.
--
-- Nothing in the schema needs altering, and these are the declarations that already admit the
-- outcome. V5__authorization_decision.sql declared account_id nullable and wrote
-- ck_authorization_decision_approved_account so that only a decline may leave it empty.
-- V15__unresolved_decline_is_published.sql left ck_authorization_decision_event requiring event_id,
-- which every decision now carries. ck_outbox_event_aggregate_id has admitted both key forms since
-- V4__outbox_transaction_key.sql.
--
-- What the platform does, which is what these comments now state. domain/AuthorizationService
-- allocates the transaction identifier, writes one authorization_decision row whose account_id is
-- null, and writes one outbox_event row under schemas/transaction-declined-v2.json keyed on that
-- identifier, both inside one local transaction. The caller receives 422 carrying reject code 0100
-- and the verbatim text of app/cbl/CBTRN02C.cbl:L386, with no accountId beside it.
--
-- The source reaches the same outcome with no account in hand. app/cbl/CBTRN02C.cbl:L385-L387
-- assigns reject reason 0100 inside the INVALID KEY limb of the keyed read at :L383, before :L394 has
-- an XREF-ACCT-ID to move, and app/cpy/CVTRA06Y.cpy declares twelve fields of which none is an
-- account. :L446-L465 still writes the 430-byte reject record for it, and its eighty-byte trailer
-- carries a reason and a text and no account.
--
-- The subject is the value transaction_id_seq issued for the decision: corroborated by the sequence
-- that issued it, deterministic under a retried publish, and naming no cardholder, no account and no
-- card. V19 had used the account a caller declared in the request body instead, which no stored row
-- of this platform corroborates, and V20 withdrew that by refusing the call outright.
--
-- What the audit trail holds. The decision row is the record of this outcome and the only one:
-- unresolved_card_attempt stays withdrawn, since every column of it recorded the same call twice. A
-- caller probing card numbers appears as decision rows carrying reject code 0100 and as the 0100
-- series of carddemo.authorization.decisions, both holding a masked card number, a card token and no
-- account.
--
-- What a consumer sees. The declined topic carries two key widths, told apart by width alone: eleven
-- decimal digits is an account and sixteen printable characters is a transaction identifier. The
-- ledger reads every version and renders its reject row from the nine descriptive values
-- REJECT-TRAN-DATA holds; version 2 declares none of them, so that delivery stores its idempotency
-- marker and writes no row. Those values describe a transaction the read rejected and exist only on
-- the request, and card-platform/docs/suggested-next-tasks.md carries the contract a deployment would
-- add to persist them.
--
-- Design decisions: card-platform/docs/decision-log.md, under "One event
-- for the outcome whose card resolves nothing, keyed on the identifier this service minted".

COMMENT ON TABLE authorization_decision IS
    'retention=carddemo.retention.decision-retention-days; purge_key=decided_at;
     personal_data=pseudonymous. One decision this service answered with, and the identity that asked
     for it. Every row names the account the cross-reference resolved, except the one outcome where
     that read resolved none: a decline carrying reject code 0100 at app/cbl/CBTRN02C.cbl:L385-L387
     leaves account_id null, because no stored row named an account and an account taken from the
     request body would be a value nothing corroborates.
     ck_authorization_decision_approved_account keeps an approval out of that state. An approval
     publishes schemas/transaction-authorized-v2.json and a decline publishes
     schemas/transaction-declined-v3.json, each keyed on the account it applies to, while the
     account-less decline publishes schemas/transaction-declined-v2.json keyed on its own transaction
     identifier. event_id ties every row to the outbox row it published through, so no decision of
     this service exists without one. The card number is masked to twelve mask characters and the last
     four digits, and no full Primary Account Number and no card verification value reaches any column
     here.
     purge_op=AuthorizationDecisionRepository.deleteDecidedBefore, called by domain/RetentionSweep.';

COMMENT ON COLUMN authorization_decision.account_id IS
    'The account this decision applies to, at the width of XREF-ACCT-ID PIC 9(11) at
     app/cpy/CVACT03Y.cpy:L7, and always the value app/cbl/CBTRN02C.cbl:L394 moves out of the resolved
     cross-reference row. Null on exactly one outcome: a decline carrying reject code 0100, which
     app/cbl/CBTRN02C.cbl:L385-L387 assigns inside the INVALID KEY limb of the read at :L383, so no
     row named an account. Nothing else may fill it. An account a caller declares beside a card number
     is corroborated by no stored row of this platform, and a decision keyed on one would attribute an
     outcome to an account that had nothing to do with the card presented.';

COMMENT ON COLUMN outbox_event.aggregate_id IS
    'The Kafka message key, in one of the two forms ck_outbox_event_aggregate_id admits, and which one
     a row carries follows from what a stored row named. Eleven decimal digits is the account
     identifier of XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy:L7, and it carries every event of an
     account on one partition in publish order, which is the ordering the ledger applies to a balance.
     The key travels as text, so a leading zero belongs to the value: account fifty renders as
     00000000050. Sixteen printable characters is the transaction identifier of TRAN-ID PIC X(16) at
     app/cpy/CVTRA05Y.cpy:L5, and one contract writes it: schemas/transaction-declined-v2.json, the
     decline of a card whose cross-reference read resolved no account. That read named no account to
     key on, and outbox/OutboxWriter admits the form for that contract alone and refuses it for every
     other event this service writes, so no event a consumer partitions by account can reach a topic
     under it. Rows written before V19__unresolved_decline_names_its_account.sql carry the same
     transaction form for the same reason.';
