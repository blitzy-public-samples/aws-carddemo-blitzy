package com.carddemo.validation;

import com.carddemo.entity.Account;
import com.carddemo.entity.DailyTransaction;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.OverlimitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Account-level business rule validator preserving the credit-limit (code 102)
 * and expiration date (code 103) checks from {@code app/cbl/CBTRN02C.cbl}
 * paragraph {@code 1500-B-LOOKUP-ACCT} lines L393-L422.
 *
 * <p>Designed to be invoked after the card cross-reference lookup (code 100) and
 * account lookup (code 101) have both succeeded. In the batch posting flow this
 * sequencing is performed by {@code TransactionPostingProcessor}; a dedicated
 * {@code TransactionValidator} orchestrator that chains the full
 * {@code 1500-VALIDATE-TRAN} sequence is a planned CP4 component. It receives an
 * already-loaded {@link Account} entity and the candidate {@link DailyTransaction}
 * and verifies that the transaction can be posted to the account.</p>
 *
 * <p><b>COBOL last-writer-wins precedence (PR-03).</b> In
 * {@code 1500-B-LOOKUP-ACCT} the overlimit check (L407-L413) and the expiration
 * check (L414-L420) are two independent, sequential {@code IF...END-IF} blocks
 * with no early exit between them; when a transaction fails both, the COBOL
 * program ends with reason 103 because the second {@code MOVE} overwrites
 * {@code WS-VALIDATION-FAIL-REASON}. This validator preserves that semantic
 * EXACTLY: it evaluates BOTH checks without throwing early, retains the LAST
 * failing reason (expiration code 103 takes precedence over overlimit code 102
 * when both fail), and only then throws the matching exception. The exceptions are
 * thrown via their no-argument constructors so that {@link Throwable#getMessage()}
 * is the EXACT COBOL literal ({@link OverlimitException#COBOL_MESSAGE} /
 * {@link ExpiredAccountException#COBOL_MESSAGE}) with no appended diagnostic text;
 * the numeric limit/date diagnostics are recorded server-side at {@code DEBUG}
 * only. The error CODES, MESSAGES (PR-03), and FORMULAS (PR-04, PR-05) are all
 * preserved EXACTLY.</p>
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
 * @see com.carddemo.batch.TransactionPostingProcessor
 * @see com.carddemo.exception.OverlimitException
 * @see com.carddemo.exception.ExpiredAccountException
 * @see "app/cbl/CBTRN02C.cbl L393-L422 (paragraph 1500-B-LOOKUP-ACCT)"
 */
@Component
@Slf4j
public class AccountValidator {

    // Stateless Spring component: no fields, no constructor, no injection (PR-29).

    /**
     * Validates that the given account can process the candidate transaction,
     * preserving the COBOL {@code 1500-B-LOOKUP-ACCT} last-writer-wins precedence.
     *
     * <p>Both checks are evaluated unconditionally (no early throw), mirroring the
     * two independent {@code IF...END-IF} blocks in CBTRN02C L403-L420:</p>
     * <ol>
     *   <li><b>Credit limit (code 102)</b> &mdash; CBTRN02C L403-L413.
     *       Computes {@code projectedBalance = currCycCredit - currCycDebit + amount}
     *       and records an overlimit failure if {@code creditLimit < projectedBalance}.</li>
     *   <li><b>Expiration (code 103)</b> &mdash; CBTRN02C L414-L420.
     *       Extracts the {@code yyyy-MM-dd} date component of {@code origTimestamp}
     *       (the first 10 characters of the COBOL {@code DALYTRAN-ORIG-TS (1:10)})
     *       and records an expiration failure if {@code expirationDate < tranDate}.</li>
     * </ol>
     *
     * <p>After both checks, the LAST recorded failure decides the outcome: when the
     * transaction fails both, the expiration failure (code 103) overwrites the
     * overlimit failure (code 102), exactly as the COBOL second {@code MOVE}
     * overwrites {@code WS-VALIDATION-FAIL-REASON}. The matching exception is then
     * thrown via its no-argument constructor so the client-facing message is the
     * EXACT COBOL literal; the numeric limit / date diagnostics are recorded
     * server-side at {@code DEBUG} only (they are amounts/dates, never PAN/PII).</p>
     *
     * @param account the already-loaded account (precondition: non-null, obtained from a
     *                successful repository lookup by the caller, with non-null monetary
     *                fields and a non-null {@code expirationDate})
     * @param tran    the candidate daily transaction (precondition: non-null with non-null
     *                {@code amount} and {@code origTimestamp}; {@code cardNum} already verified
     *                by the caller)
     * @throws OverlimitException      (code 102) when ONLY the credit-limit check fails:
     *                                 {@code creditLimit < (currCycCredit - currCycDebit + amount)}
     * @throws ExpiredAccountException (code 103) when the expiration check fails
     *                                 ({@code expirationDate < origTimestamp[0..10]}),
     *                                 INCLUDING the both-fail case (code 103 wins)
     */
    public void validateForTransaction(Account account, DailyTransaction tran) {
        // Accumulate the failure reason exactly as COBOL WS-VALIDATION-FAIL-REASON: both
        // checks below run unconditionally (no early throw), and the SECOND check
        // (expiration, 103) overwrites the FIRST (overlimit, 102) when both fail —
        // reproducing the two independent IF...END-IF blocks of 1500-B-LOOKUP-ACCT
        // (CBTRN02C L407-L420). 0 means "no failure".
        int failReason = 0;

        // ============================================================
        // CHECK 1: Credit Limit (CBTRN02C L403-L413, code 102, PR-04)
        // ============================================================
        // COBOL:
        //   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
        //                       - ACCT-CURR-CYC-DEBIT
        //                       + DALYTRAN-AMT
        //   IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL CONTINUE
        //   ELSE MOVE 102 ... 'OVERLIMIT TRANSACTION'
        //
        // Projected cycle balance via exact BigDecimal arithmetic (PR-16), operand order
        // matching the COBOL COMPUTE exactly: subtract the debit total, then add the amount.
        BigDecimal projectedBalance = account.getCurrCycCredit()
                .subtract(account.getCurrCycDebit())
                .add(tran.getAmount());

        // PR-04: FAIL when ACCT-CREDIT-LIMIT < WS-TEMP-BAL. compareTo per PR-16 (NEVER
        // equals(): scale-sensitive). Record the failure; do NOT throw yet.
        if (account.getCreditLimit().compareTo(projectedBalance) < 0) {
            failReason = OverlimitException.COBOL_CODE; // 102
            // Diagnostic detail (amounts, not PAN/PII) is logged server-side only — it is
            // never surfaced on the client-facing exception message (PR-03 / F4).
            log.debug("Overlimit (code {}): creditLimit={} < projectedBalance={}",
                    OverlimitException.COBOL_CODE, account.getCreditLimit(), projectedBalance);
        }

        // ============================================================
        // CHECK 2: Expiration (CBTRN02C L414-L420, code 103, PR-05)
        // ============================================================
        // COBOL:
        //   IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) CONTINUE
        //   ELSE MOVE 103 ... 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
        //
        // ACCT-EXPIRAION-DATE is PIC X(10) (yyyy-MM-dd); DALYTRAN-ORIG-TS is PIC X(26) in DB2
        // external format; (1:10) extracts its first 10 chars (yyyy-MM-dd). The committed
        // entities normalize these to LocalDate/LocalDateTime, so we render both back to ISO
        // yyyy-MM-dd text to preserve the COBOL string comparison EXACTLY. LocalDate.toString()
        // is guaranteed ISO-8601 (uuuu-MM-dd), matching the COBOL (1:10) substring.
        String expirationDate = account.getExpirationDate().toString();
        String tranDate = tran.getOrigTimestamp().toLocalDate().toString();

        // PR-05: FAIL when ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS (1:10). Both operands are
        // ISO yyyy-MM-dd, so lexicographic compareTo == chronological == the COBOL char
        // comparison. This assignment overwrites any prior 102 (last-writer-wins, matching the
        // COBOL second MOVE to WS-VALIDATION-FAIL-REASON). Do NOT throw yet.
        if (expirationDate.compareTo(tranDate) < 0) {
            failReason = ExpiredAccountException.COBOL_CODE; // 103 (overwrites 102 if both failed)
            log.debug("Expiration (code {}): expirationDate={} < tranDate={}",
                    ExpiredAccountException.COBOL_CODE, expirationDate, tranDate);
        }

        // ============================================================
        // ACT on the final WS-VALIDATION-FAIL-REASON (COBOL evaluates it AFTER both blocks).
        // No-arg constructors carry the EXACT COBOL_MESSAGE so no diagnostic text leaks to the
        // client (PR-03 / F4). Expiration (103) is checked first so it wins on the both-fail case.
        // ============================================================
        if (failReason == ExpiredAccountException.COBOL_CODE) {
            throw new ExpiredAccountException();
        }
        if (failReason == OverlimitException.COBOL_CODE) {
            throw new OverlimitException();
        }
    }
}
