-- The processing moment a caller declares, stored on the decision row.
--
-- app/cbl/COTRN02C.cbl reads two ten-character dates from its capture screen. VALIDATE-INPUT-DATA-
-- FIELDS tests TORIGDTI at app/cbl/COTRN02C.cbl:L389-L405 and TPROCDTI at :L409-L423, both through
-- CALL 'CSUTLDTC', and ADD-TRANSACTION then moves each into the record it writes:
-- MOVE TORIGDTI OF COTRN2AI TO TRAN-ORIG-TS at :L469 and MOVE TPROCDTI OF COTRN2AI TO TRAN-PROC-TS
-- at :L470. Both target fields are PIC X(26), declared at app/cpy/CVTRA05Y.cpy:L16 and :L17.
--
-- The origin moment already reaches a column of this platform: it travels on the authorized event as
-- authorizedAt and the ledger stores it. The processing moment did not, because the ledger stamps its
-- own when it posts, reproducing MOVE WS-TIMESTAMP TO TRAN-PROC-TS at app/cbl/CBTRN02C.cbl:L438. The
-- declared value was therefore validated and then discarded, which made a required request field one
-- no stored row and no published event could be read back from.
--
-- This column holds it, at the width the record field declares. It is nullable, because the twenty-
-- six character record form is derived from a ten-character date and a request that carries neither
-- accepted form is refused before a row is written.
--
-- No decision reads this column. Reject reason 0103 compares the first ten characters of the origin
-- moment against ACCT-EXPIRAION-DATE at app/cbl/CBTRN02C.cbl:L414-L420 and reads nothing else, so
-- adding the column changes no outcome.

ALTER TABLE authorization_decision
    ADD COLUMN declared_processing_timestamp VARCHAR(26);

COMMENT ON COLUMN authorization_decision.declared_processing_timestamp IS
    'The processing moment the caller declared, at the width TRAN-PROC-TS PIC X(26) declares at '
    'app/cpy/CVTRA05Y.cpy:L17. app/cbl/COTRN02C.cbl:L470 moves the capture screen field into that '
    'record field. The ledger stamps its own value when it posts, at app/cbl/CBTRN02C.cbl:L438, so '
    'this column is the only record of what the caller declared.';
