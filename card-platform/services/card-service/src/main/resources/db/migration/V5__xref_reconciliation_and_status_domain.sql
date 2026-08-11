-- Card service, migration V5. Two statements: one CHECK on card.active_status, and two comments.
--
-- 1. card_xref: the comment names the path that reconciles the replica.
--
-- domain/CardCrossReferenceReconciler reads the replica row for a card and compares its account_id
-- against CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6, the mapping this service owns.
-- domain/CardUpdateService calls it inside the one transaction that saves the card row and writes
-- the outbox row, so the card row, the replica and the queued event all move or none do:
--
--   agreeing row     observed_at moves, carddemo.card.xref.agreed increments
--   diverging row    account_id is corrected, carddemo.card.xref.corrected increments
--   no row at all    nothing is written, carddemo.card.xref.missing increments
--
-- The missing case writes nothing because customer_id is NOT NULL, from
-- XREF-CUST-ID PIC 9(09) at app/cpy/CVACT03Y.cpy:L6, and app/cpy/CVACT02Y.cpy carries no customer
-- identifier. Source position: app/cbl/COCRDUPC.cbl:L356 reads *COPY CVACT03Y. commented out, so
-- the source update program never opened the cross-reference, and app/cbl/COCRDLIC.cbl names that
-- copybook nowhere.
--
-- 2. card.active_status: the column carries the domain the API states.
--
-- V1:L30-L31 declares active_status CHAR(1) NOT NULL with no value constraint. The domain is the
-- two values of 88 FLG-YES-NO-VALID, tested at app/cbl/COCRDUPC.cbl:L861-L871, which
-- api/dto/CardUpdateRequest already admits on the update path. The CHECK below holds every writer
-- to the same pair, a direct load included. app/data/ASCII/carddata.txt carries Y and N only and
-- V2__seed.sql loads those fifty rows unchanged, so the constraint validates against the existing
-- table.
--
-- Rationale for both, and the alternatives weighed: card-platform/docs/decision-log.md. No column
-- is added, dropped or retyped and no row is touched. V1 and V4 are left as they ran.

ALTER TABLE card
    ADD CONSTRAINT ck_card_active_status CHECK (active_status IN ('Y', 'N'));

COMMENT ON CONSTRAINT ck_card_active_status ON card IS
    'active_status holds Y or N and nothing else, from 88 FLG-YES-NO-VALID and the tests at
     app/cbl/COCRDUPC.cbl:L861-L871. api/dto/CardUpdateRequest enforces the same pair on the
     update path and api/dto/CardSummary and api/dto/CardDetailResponse refuse a third value when
     they are built, so a read cannot answer outside the domain either. Before V5 the column was
     CHAR(1) with no constraint, so a direct load could put a third value in a row the API then
     returned.';

COMMENT ON TABLE card_xref IS
    'retention=relationship; purge_key=none; personal_data=pseudonymous. Card-to-account cross-
     reference replica of the mapping this service owns in the card table. RECONCILED, NOT
     EVENT-DRIVEN: domain/CardCrossReferenceReconciler reads the row for a card and brings its
     account_id into step with the card row, inside the transaction domain/CardUpdateService uses to
     save that row and queue its event. An earlier version of this comment said the replica was kept
     current by the events this service publishes, which nothing did and no event could: CardUpdated
     carries a masked card number and this table is keyed on the full sixteen characters. A card
     number holding no row here is counted on carddemo.card.xref.missing and no row is invented,
     because customer_id is mandatory and app/cpy/CVACT02Y.cpy carries no customer identifier. Its
     card, account and customer identifiers link the row to one person, and a row lives as long as
     the card it names. NO ERASURE OR EXPORT WORKFLOW EXISTS on this platform: this comment records
     the position rather than a promise. This service deletes no cross-reference row;
     domain/RetentionSweep purges published outbox rows and processed-event markers and nothing else.
     card-platform/docs/suggested-next-tasks.md carries the erasure and export task and names every
     store it would have to reach, this replica among them.';
