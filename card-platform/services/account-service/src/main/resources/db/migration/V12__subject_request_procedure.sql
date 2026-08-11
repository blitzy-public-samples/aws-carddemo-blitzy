-- Account service, migration V12.
-- Points the three subject-data comments of this schema at the procedure that now exists.
--
-- Statements: two COMMENT ON TABLE and one COMMENT ON COLUMN. No table, column, index, constraint or
-- row is declared, altered or removed, and the retention and purge_key tokens
-- equivalence-tests/RetentionSweepContractTest reads are re-issued unchanged.
--
-- What changed, and what makes it a migration. V7 and V8 replaced comments that promised an erasure
-- route with comments stating the position: no route existed. A security review then asked for the
-- route. What it asked for is now delivered as a documented, executable procedure rather than as an
-- orchestration: card-platform/docs/data-model.md, under "Subject data: purpose, retention, export
-- and erasure", carries the jurisdiction-parameterised policy, the inventory of every store holding
-- subject data, the export statements, the erasure statements in the order they have to run, the
-- completion record a run leaves behind, and what a retained broker record and a backup mean for a
-- request. These three comments name it, because a row that says a capability is absent while a
-- procedure exists is as stale as one that promised a capability nobody built.
--
-- THIS SCHEMA IS WHERE A REQUEST STARTS. The customer row is the only table on this platform that
-- describes an identifiable person, and account rows carry no customer identifier at all
-- (app/cpy/CVACT01Y.cpy:L4-L17 declares none), so account_customer_link is the hop that turns a
-- named customer into the account identifier every other store keys on. The procedure therefore
-- reads this schema first and erases it last.
--
-- What is still absent. No endpoint, event, command or scheduled task erases or exports a subject. A
-- request is an operator running the procedure, and the four schemas it reaches are four separate
-- databases no service may reach into. card-platform/docs/suggested-next-tasks.md carries the
-- orchestration.
--
-- Rationale, alternatives considered and accepted risks: card-platform/docs/decision-log.md, under
-- "A subject request is a procedure before it is an orchestration".

COMMENT ON TABLE customer IS
    'retention=relationship; purge_key=none; personal_data=yes. The only table on this platform that
     describes an identifiable person: name, postal address, two telephone numbers, Social Security
     Number, government-issued identifier, date of birth and electronic funds transfer account.
     Erasure is a deliberate act on a named customer and never a timer. Account rows carry no
     customer identifier at all (app/cpy/CVACT01Y.cpy:L4-L17 declares none), so the link runs through
     account_customer_link here and through the card_xref replicas the authorization and card services
     hold. NO AUTOMATED ERASURE OR EXPORT WORKFLOW EXISTS on this platform, and one documented
     procedure now does: card-platform/docs/data-model.md, under "Subject data: purpose, retention,
     export and erasure", starts from this row, names every store a request has to reach, and carries
     the statements that reach them in order. card-platform/docs/suggested-next-tasks.md carries the
     orchestration that would run it without an operator.';

COMMENT ON TABLE account_customer_link IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. The account and customer
     identifiers the update path pairs, from XREF-ACCT-ID and XREF-CUST-ID at
     app/cpy/CVACT03Y.cpy:L6-L7. This table holds NO card number: XREF-CARD-NUM was the source
     primary key and no query here reads a card, so replicating it into this schema stored a Primary
     Account Number with no reader. It is the hop a subject request turns a named customer into an
     account identifier with, which is why the procedure in card-platform/docs/data-model.md reads it
     before it erases anything and erases it after every store keyed on that account. NO AUTOMATED
     ERASURE OR EXPORT WORKFLOW EXISTS; the procedure is documented and run by an operator, and
     card-platform/docs/suggested-next-tasks.md carries the orchestration.';

COMMENT ON COLUMN customer.social_security_number IS
    'personal_data=yes. Held because app/cpy/CVCUS01Y.cpy:L17 declares it and the edit at
     app/cbl/COACTUPC.cbl:L2431-L2491 reads it. It reaches no event, no log line and no response
     body. NO AUTOMATED ERASURE OR EXPORT WORKFLOW EXISTS on this platform: the documented procedure
     in card-platform/docs/data-model.md clears this value with the rest of the customer row, and
     nothing performs one on its own. card-platform/docs/suggested-next-tasks.md carries the
     orchestration.';
