-- Ledger posting service, migration V7.
-- Records what the four monetary columns of this schema do with a sum wider than the field.
--
-- WHY THIS FILE EXISTS. Runtime testing posted 0.01 and then 999999999.99 to one account, type and
-- category. Both amounts are inside DALYTRAN-AMT PIC S9(09)V99 at app/cpy/CVTRA06Y.cpy:L10 and both
-- authorizations were approved, and their sum of 1000000000.00 is one integer digit wider than
-- TRAN-CAT-BAL PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9 holds. The store raised numeric field
-- overflow, SQLSTATE 22003, which rolled back the consumer transaction and sent the approved
-- authorization to the dead-letter topic, so one approved transaction reached no account.
--
-- WHAT THE SOURCE DOES. The two ADD statements that reach this field are at
-- app/cbl/CBTRN02C.cbl:L508 on the create branch and :L527 on the update branch. Neither carries an
-- ON SIZE ERROR phrase, and the phrase appears in none of the twenty-eight programs under app/cbl/,
-- so each store keeps the low-order nine integer digits of the sum, holds the sign, and continues.
-- The same is true of the three ADD statements at :L547, :L549 and :L551 against the ten integer
-- digits of ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT at app/cpy/CVACT01Y.cpy:L7,
-- :L13 and :L14. Agent Action Plan rule T7 requires a source defect to be reproduced and flagged
-- rather than corrected, and docs/business-rule-flags.md carries the flag.
--
-- WHERE THE STORE NOW LIVES. The sum for category_balance is formed inside the statement
-- repository/TransactionCategoryBalanceRepository issues, so the truncation is expressed there as
-- MOD by ten raised to the nine integer digits the field holds. The three account figures are summed
-- in Java, so domain/AccountBalanceUpdater stores them through
-- com.carddemo.cobol.CobolDecimal.truncateToPictureField, which drops the same digits and keeps the
-- same sign. A dropped digit reaches a warning line there; a statement cannot write one, and
-- repository/TransactionCategoryBalanceRepositoryTest reads the truncated value back from a real
-- database instead.
--
-- WHY THE COLUMNS ARE NOT WIDENED. Agent Action Plan section 0.3.1 derives NUMERIC(11,2) from
-- PIC S9(09)V99 and NUMERIC(12,2) from PIC S9(10)V99, and a copybook field sits behind all four
-- columns, so a wider column would store a value the source field cannot hold. That is the opposite
-- of the fraud service's velocity_window.total_amount, which V3__velocity_total_headroom.sql widened
-- precisely because no copybook field sits behind it.
--
-- WHY IT IS A MIGRATION RATHER THAN AN EDIT. V1 has run. Flyway compares the checksum of every
-- applied migration at start-up, so editing an applied file stops every service that already has the
-- schema, and the demo database survives docker compose down on the postgres-data volume. Correcting
-- forward is the same course V5 took over V3's marker identity, V6 took over two V4 citations, and
-- the account service's V8 took over a V1 column comment.
--
-- WHAT CHANGES. Four comment strings. No table, column, index, constraint or row is declared,
-- altered or removed here, and no behaviour depends on a comment. The V6 text of the two cycle
-- columns is carried across unchanged and extended with the store sentence.

COMMENT ON COLUMN transaction_category_balance.category_balance IS
    'TRAN-CAT-BAL PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9, so the column holds nine integer
     digits. app/cbl/CBTRN02C.cbl:L508 adds the amount on the create branch and :L527 adds it on
     the update branch, neither with an ON SIZE ERROR phrase. A sum past nine integer digits
     therefore keeps its low-order nine and its sign, which the MOD in the statement
     repository/TransactionCategoryBalanceRepository issues reproduces. Flagged for review in
     docs/business-rule-flags.md.';

COMMENT ON COLUMN account_balance_projection.current_balance IS
    'ACCT-CURR-BAL PIC S9(10)V99 at app/cpy/CVACT01Y.cpy:L7. Derived here alone, by the add at
     app/cbl/CBTRN02C.cbl:L547. No account change writes it: not a field update, and not a billing
     cycle close, which at app/cbl/CBACT04C.cbl:L353-L354 leaves the balance untouched. The field
     holds ten integer digits and the ADD carries no ON SIZE ERROR phrase, so a wider sum keeps its
     low-order ten and its sign, which domain/AccountBalanceUpdater reproduces through
     CobolDecimal.truncateToPictureField. Flagged for review in docs/business-rule-flags.md.';

COMMENT ON COLUMN account_balance_projection.cycle_credit IS
    'ACCT-CURR-CYC-CREDIT at app/cpy/CVACT01Y.cpy:L13. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L549 when the amount is zero or positive. A billing cycle close is the
     only account change that writes it, moving zero in after app/cbl/CBACT04C.cbl:L353. The field
     holds ten integer digits and the ADD carries no ON SIZE ERROR phrase, so a wider sum keeps its
     low-order ten and its sign.';

COMMENT ON COLUMN account_balance_projection.cycle_debit IS
    'ACCT-CURR-CYC-DEBIT at app/cpy/CVACT01Y.cpy:L14. The posting path adds to it at
     app/cbl/CBTRN02C.cbl:L551 when the amount is negative, which is the sign convention flagged
     for review in docs/business-rule-flags.md. A billing cycle close moves zero in after
     app/cbl/CBACT04C.cbl:L354. The field holds ten integer digits and the ADD carries no ON SIZE
     ERROR phrase, so a wider sum keeps its low-order ten and its sign.';
