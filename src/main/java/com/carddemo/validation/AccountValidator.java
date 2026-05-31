package com.carddemo.validation;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.OverlimitException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Account-level business rule validator preserving the credit-limit (code 102)
 * and expiration date (code 103) checks from {@code app/cbl/CBTRN02C.cbl}
 * paragraph {@code 1500-B-LOOKUP-ACCT} lines L393-L422.
 *
 * <p>Invoked by {@link TransactionValidator} after the card cross-reference
 * lookup (code 100) and account lookup (code 101) have both succeeded. It
 * receives an already-loaded {@link Account} entity and the candidate
 * {@link DailyTransaction} and verifies that the transaction can be posted to
 * the account.</p>
 *
 * <p>The validator throws an exception on the FIRST failing check (Java
 * throw-on-first-error semantic), which differs from the COBOL "last failure
 * wins" semantic. In {@code 1500-B-LOOKUP-ACCT} the overlimit check (L407-L413)
 * and the expiration check (L414-L420) are two independent, sequential
 * {@code IF...END-IF} blocks; when a transaction fails both, the COBOL program
 * ends with reason 103 because the second {@code MOVE} overwrites
 * {@code WS-VALIDATION-FAIL-REASON}. This Java port instead surfaces the
 * overlimit failure (102) first. The deviation is intentional and accepted
 * because a single transaction seldom fails both checks simultaneously, the
 * idiomatic validator pattern is throw-on-first-error, and the error CODES,
 * MESSAGES (PR-03), and FORMULAS (PR-04, PR-05) are all preserved EXACTLY.</p>
 *
 * <p><b>Type-adaptation note (integration with committed entities).</b> The
 * COBOL working-storage holds {@code ACCT-EXPIRAION-DATE} as {@code PIC X(10)}
 * and {@code DALYTRAN-ORIG-TS} as {@code PIC X(26)} (raw character strings). The
 * committed JPA entities normalize these to typed temporals:
 * {@link Account#getExpirationDate()} returns a {@link java.time.LocalDate} and
 * {@link DailyTransaction#getOrigTimestamp()} returns a
 * {@link java.time.LocalDateTime}. To preserve the COBOL string comparison
 * {@code ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} EXACTLY, this validator
 * renders both temporals back to their ISO {@code yyyy-MM-dd} text form (the
 * account expiration date directly, and the first ten characters of the daily
 * transaction's DB2 timestamp via its date component) and compares them
 * lexicographically. For ISO {@code yyyy-MM-dd} strings, lexicographic ordering
 * is identical to chronological ordering, so PR-05 is preserved bit-for-bit
 * relative to the original COBOL substring comparison.</p>
 *
 * <p><b>Preservation Rules satisfied:</b></p>
 * <ul>
 *   <li><b>PR-03</b> &mdash; Codes 102/103 with EXACT message strings
 *       "OVERLIMIT TRANSACTION" and "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
 *       (note the abbreviation "ACCT" &mdash; preserved verbatim from COBOL,
 *       carried by {@link OverlimitException#COBOL_MESSAGE} and
 *       {@link ExpiredAccountException#COBOL_MESSAGE}).</li>
 *   <li><b>PR-04</b> &mdash; Credit-limit formula preserved exactly:
 *       FAIL when {@code creditLimit < (currCycCredit - currCycDebit + amount)}
 *       with the COBOL operand order ({@code subtract} then {@code add}).</li>
 *   <li><b>PR-05</b> &mdash; Expiration check preserved exactly:
 *       FAIL when {@code expirationDate < origTimestamp[0..10]} (yyyy-MM-dd).</li>
 *   <li><b>PR-16</b> &mdash; All monetary comparisons use
 *       {@link BigDecimal#compareTo(BigDecimal)} (NEVER {@code equals};
 *       NEVER {@code float}/{@code double}).</li>
 *   <li><b>PR-28</b> &mdash; Jakarta EE namespace (no {@code javax.*} imports;
 *       this stateless component needs no persistence/validation annotations).</li>
 *   <li><b>PR-29</b> &mdash; No {@code @Autowired} field injection. This class is
 *       stateless, so no injection is needed.</li>
 * </ul>
 *
 * @see TransactionValidator
 * @see com.carddemo.exception.OverlimitException
 * @see com.carddemo.exception.ExpiredAccountException
 * @see "app/cbl/CBTRN02C.cbl L393-L422 (paragraph 1500-B-LOOKUP-ACCT)"
 */
@Component
public class AccountValidator {

    // Stateless Spring component: no fields, no constructor, no injection (PR-29).

    /**
     * Validates that the given account can process the candidate transaction.
     *
     * <p>Performs two checks in strict order:</p>
     * <ol>
     *   <li><b>Credit limit (code 102)</b> &mdash; CBTRN02C L403-L413.
     *       Computes {@code projectedBalance = currCycCredit - currCycDebit + amount}
     *       and throws {@link OverlimitException} if
     *       {@code creditLimit < projectedBalance}.</li>
     *   <li><b>Expiration (code 103)</b> &mdash; CBTRN02C L414-L420.
     *       Extracts the {@code yyyy-MM-dd} date component of {@code origTimestamp}
     *       (the first 10 characters of the COBOL {@code DALYTRAN-ORIG-TS (1:10)})
     *       and throws {@link ExpiredAccountException} if
     *       {@code expirationDate < tranDate}.</li>
     * </ol>
     *
     * @param account the already-loaded account (precondition: non-null, obtained from a
     *                successful repository lookup in {@code TransactionValidator}, with
     *                non-null monetary fields and a non-null {@code expirationDate})
     * @param tran    the candidate daily transaction (precondition: non-null with non-null
     *                {@code amount} and {@code origTimestamp}; {@code cardNum} already verified
     *                by {@code TransactionValidator})
     * @throws OverlimitException      (code 102) when
     *                                 {@code creditLimit < (currCycCredit - currCycDebit + amount)}
     * @throws ExpiredAccountException (code 103) when
     *                                 {@code expirationDate < origTimestamp[0..10]}
     */
    public void validateForTransaction(Account account, DailyTransaction tran) {
        // ============================================================
        // STEP 1: Credit Limit Check (CBTRN02C L403-L413, code 102, PR-04)
        // ============================================================
        // COBOL:
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                       - ACCT-CURR-CYC-DEBIT
        //                       + DALYTRAN-AMT
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //   ELSE MOVE 102 ... 'OVERLIMIT TRANSACTION'
        //
        // Compute the projected cycle balance using exact BigDecimal arithmetic (PR-16).
        // The operand order matches the COBOL COMPUTE exactly: subtract the debit total,
        // then add the candidate transaction amount.
        BigDecimal projectedBalance = account.getCurrCycCredit()
                .subtract(account.getCurrCycDebit())
                .add(tran.getAmount());

        // PR-04: FAIL when ACCT-CREDIT-LIMIT < WS-TEMP-BAL.
        // Use .compareTo() per PR-16 (NEVER .equals() for BigDecimal: equals() is
        // scale-sensitive and would treat 100.00 and 100.0 as different values).
        if (account.getCreditLimit().compareTo(projectedBalance) < 0) {
            // Diagnostic constructor preserves the EXACT COBOL_MESSAGE prefix
            // "OVERLIMIT TRANSACTION" (PR-03) and appends "(limit=..., attempted=...)"
            // for richer error tracing by the GlobalExceptionHandler.
            throw new OverlimitException(account.getCreditLimit(), projectedBalance);
        }

        // ============================================================
        // STEP 2: Expiration Check (CBTRN02C L414-L420, code 103, PR-05)
        // ============================================================
        // COBOL:
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE
        //   ELSE MOVE 103 ... 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
        //
        // The COBOL operands are character strings: ACCT-EXPIRAION-DATE is PIC X(10)
        // (yyyy-MM-dd) and DALYTRAN-ORIG-TS is PIC X(26) in DB2 external format
        // "yyyy-MM-dd-HH.mm.ss.SSS0000"; the (1:10) reference extracts its first 10
        // characters (yyyy-MM-dd). The committed entities normalize these to typed
        // temporals (LocalDate / LocalDateTime), so we render them back to their ISO
        // yyyy-MM-dd text form to preserve the COBOL string comparison EXACTLY:
        //   - account.getExpirationDate()  -> LocalDate     -> "yyyy-MM-dd"
        //   - tran.getOrigTimestamp()      -> LocalDateTime -> date -> "yyyy-MM-dd"
        // LocalDate.toString() is guaranteed ISO-8601 (uuuu-MM-dd), so this yields
        // exactly the same 10-character value the COBOL (1:10) substring produced.
        String expirationDate = account.getExpirationDate().toString();
        String tranDate = tran.getOrigTimestamp().toLocalDate().toString();

        // PR-05: FAIL when ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS (1:10).
        // Both operands are ISO yyyy-MM-dd strings, so lexicographic compareTo yields
        // the same ordering as the original COBOL character comparison (and as
        // chronological order).
        if (expirationDate.compareTo(tranDate) < 0) {
            // Diagnostic constructor preserves the EXACT COBOL_MESSAGE prefix
            // "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" (PR-03 — the "ACCT"
            // abbreviation is verbatim COBOL and is NOT expanded) and appends
            // "(expiration=..., tranDate=...)" for richer error tracing.
            throw new ExpiredAccountException(expirationDate, tranDate);
        }
    }
}
