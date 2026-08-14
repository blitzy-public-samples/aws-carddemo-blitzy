-- Ledger posting service, migration V7. Records what four monetary columns do with a sum wider than
-- the copybook field behind them.
--
-- Statements: four COMMENT ON COLUMN. No table, column, index, constraint or row is declared, altered
-- or removed, and the V6 text of the two cycle columns is carried across with the store sentence
-- added.
--
-- The mechanics the comments record. TRAN-CAT-BAL PIC S9(09)V99 at app/cpy/CVTRA01Y.cpy:L9 holds nine
-- integer digits; ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT at
-- app/cpy/CVACT01Y.cpy:L7, :L13 and :L14 hold ten. The ADD statements that reach them are at
-- app/cbl/CBTRN02C.cbl:L508 and :L527 for the category balance and :L547, :L549 and :L551 for the
-- three account figures, and none carries an ON SIZE ERROR phrase -- the phrase appears in none of the
-- twenty-eight programs under app/cbl/ -- so each store keeps the low-order digits of the sum and its
-- sign. AAP rule T7 requires a source defect to be reproduced and flagged, and
-- docs/business-rule-flags.md carries the flag.
--
-- Where the truncation is expressed. The category-balance sum is formed inside the statement
-- repository/TransactionCategoryBalanceRepository issues, as MOD by ten raised to nine; the three
-- account figures are summed in Java and stored through
-- com.carddemo.cobol.CobolDecimal.truncateToPictureField, which drops the same digits and keeps the
-- same sign and writes a warning line. repository/TransactionCategoryBalanceRepositoryTest reads the
-- truncated value back from a real database.
--
-- A correction to an applied migration arrives as a new migration because Flyway compares the
-- checksum of every applied file at start-up. Design decisions,
-- including why the columns are not widened: card-platform/docs/decision-log.md.

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
