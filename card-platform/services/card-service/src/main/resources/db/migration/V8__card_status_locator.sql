-- Card service, migration V8.
-- Corrects the COBOL locator in the ck_card_active_status comment.
--
-- What this file declares. One COMMENT ON CONSTRAINT. No table, column, index, constraint or row is
-- declared, altered or removed, and every other sentence V5__xref_reconciliation_and_status_domain.sql
-- wrote about the constraint is carried across unchanged.
--
-- The locator this corrects. V5 cited the status test as lines 1861 to 1863 of
-- app/cbl/COCRDUPC.cbl, and that file ends at line 1560, so the range named no line of it. The
-- band it meant is the one the copybook status field is edited in: 1240-EDIT-CARDSTATUS at
-- app/cbl/COCRDUPC.cbl:L845-L874. Inside it MOVE CCUP-NEW-CRDSTCD TO FLG-YES-NO-CHECK is at L861 and
-- IF FLG-YES-NO-VALID at L863, so the band that tests the two values is
-- app/cbl/COCRDUPC.cbl:L861-L871, which is what the comment below names.
--
-- The database this file corrects. The V5 file carries the corrected locator too, because
-- a citation a reader can follow has to resolve in the file it names. A database that already applied
-- V5 as it first stood refuses the corrected bytes until its checksums are rewritten, and rewriting
-- them with flyway repair leaves the catalogue holding the text V5 first wrote. This file is what
-- corrects that database. A fresh database applies both and ends in the same state, because a comment
-- statement replaces whatever text the constraint carried.
-- Design decisions: card-platform/docs/decision-log.md.

COMMENT ON CONSTRAINT ck_card_active_status ON card IS
    'active_status holds Y or N and nothing else, from 88 FLG-YES-NO-VALID and the tests at
     app/cbl/COCRDUPC.cbl:L861-L871. api/dto/CardUpdateRequest enforces the same pair on the
     update path and api/dto/CardSummary and api/dto/CardDetailResponse refuse a third value when
     they are built, so a read cannot answer outside the domain either. Before V5 the column was
     CHAR(1) with no constraint, so a direct load could put a third value in a row the API then
     returned.';
