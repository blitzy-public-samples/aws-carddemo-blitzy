-- Card service, migration V11.
-- Records the formal exception under which this schema keeps a card verification value, and points
-- the two subject-data comments at the procedure that now exists.
--
-- Statements: three COMMENT statements. No table, column, index, constraint or row is declared,
-- altered or removed, and the retention and purge_key tokens
-- equivalence-tests/RetentionSweepContractTest reads are re-issued unchanged.
--
-- What a security review found. card_verification_value holds three cleartext digits of
-- authentication data, V2__seed.sql loads a value into all fifty rows, and no delivered business
-- rule reads the column. The review asked for the column, the mapping, the seed values and the
-- preservation test to be removed by a forward migration, and offered a second route: keep the
-- column under a formal exception carrying encryption, minimal access, audit and destruction
-- controls.
--
-- What makes removal unavailable. Removal is not available to this engagement. Section 0.4.1 of the Agent
-- Action Plan requires this service to store the value, section 0.6.4 states that it "is persisted
-- because the card record defines it and is never emitted", and section 0.2.2 places payment-card
-- industry controls beyond the documented masking deviation outside the engagement. The plan is the
-- agreed contract, so the second route is the one taken, and this file is the exception record the
-- review asked for. card-platform/docs/business-rule-flags.md entry D4 carries the open question in
-- full, and card-platform/docs/suggested-next-tasks.md carries the removal as a task.
--
-- The four controls, each verified by something rather than promised.
--
--   Encryption at rest. The storage this column lives on has to be encrypted. Both persistent
--   claims in card-platform/deploy/k8s declare the requirement, the overlay under
--   deploy/k8s/overlays/encrypted-storage binds an encrypted storage class in one place, and
--   card-platform/deploy/k8s/README.md carries key ownership, rotation and the restore proof. The
--   demonstration compose stack holds synthetic fixture values only.
--
--   Minimal access. Nothing reads the value. entity/CardEntity declares no accessor, carries
--   @JsonIgnore on the field and prints a withheld marker in toString. No query, no request body, no
--   response body, no event schema and no configuration file names the column. openapi.yaml names it
--   once, in a comment stating that it is not published.
--
--   Audit. The controls above are asserted rather than described.
--   services/card-service CardholderDataExposureTest reads the field through every path a value
--   could leave by, and equivalence-tests SubjectDataGovernanceContractTest holds the published
--   exception, the reader inventory and the destruction procedure to the delivered tree. A change
--   that introduces a reader fails the build.
--
--   Destruction. card-platform/services/card-service/README.md carries the procedure, under
--   "Stored card verification value". It is four edits and one migration, and it names every
--   artifact each edit touches.
--
-- Review. The exception is open until the project owner answers the question in
-- business-rule-flags.md entry D4. A deployment that answers it by dropping the column runs the
-- destruction procedure. Nothing here expires on a date, because a date this engagement invented
-- would be a date nobody agreed to.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md, under
-- "The stored card verification value keeps a formal exception".

COMMENT ON TABLE card IS
    'retention=relationship; purge_key=none; personal_data=yes. The card record contains the
     embossed cardholder name, full card number and card verification value.
     card_verification_value lives here and nowhere else on this platform. It is held under a formal
     exception this migration records: AAP sections 0.4.1 and 0.6.4 require that this service store
     it and never emit it, section 0.2.2 excludes the controls that would remove it, and the four
     compensating controls are encryption at rest, no reader anywhere, build-enforced audit of both,
     and a published destruction procedure. entity/CardEntity declares no accessor for the column.
     card-platform/docs/business-rule-flags.md entry D4 carries the open question of dropping it, and
     card-platform/services/card-service/README.md carries the destruction procedure a deployment
     runs to answer that question yes.';

COMMENT ON COLUMN card.card_verification_value IS
    'personal_data=yes; authentication_data=yes. CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7.
     CHAR(3) rather than NUMERIC(3,0) because the Picture clause is a display field: a numeric column
     returns 7 for a stored 007, which is a different card verification value. NO application path
     reads this column. No query names it, no request or response body carries it, no event schema
     declares it, and entity/CardEntity exposes no accessor. Held under the formal exception the
     header of this migration records, whose four controls are encryption at rest, minimal access,
     build-enforced audit and a published destruction procedure.';

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference replica, kept current by the events this service publishes when a card changes. Its
     card, account and customer identifiers link the row to one person, and a row lives as long as
     the card it names. NO AUTOMATED ERASURE OR EXPORT WORKFLOW EXISTS on this platform, and one
     documented procedure now does: card-platform/docs/data-model.md, under "Subject data: purpose,
     retention, export and erasure", names every store a request has to reach, this replica among
     them, and carries the statements that reach them in order. This service deletes no
     cross-reference row on its own: domain/RetentionSweep purges published outbox rows and nothing
     else. card-platform/docs/suggested-next-tasks.md carries the orchestration that would run the
     procedure without an operator.';
