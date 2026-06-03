package com.carddemo.exception;

import java.math.BigDecimal;

/**
 * Exception representing <strong>COBOL validation code 102</strong>
 * ({@code "OVERLIMIT TRANSACTION"}).
 *
 * <p>Thrown when posting a transaction would push the account's credit
 * utilization beyond its credit limit. This mirrors the {@code ELSE} branch of
 * the credit-limit check in paragraph {@code 1500-B-LOOKUP-ACCT} of
 * {@code app/cbl/CBTRN02C.cbl} (lines 403-413).</p>
 *
 * <h2>COBOL source (EXACT preservation per PR-03)</h2>
 * <pre>
 *   COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                       - ACCT-CURR-CYC-DEBIT
 *                       + DALYTRAN-AMT
 *
 *   IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL
 *     CONTINUE
 *   ELSE
 *     MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *     MOVE 'OVERLIMIT TRANSACTION'
 *       TO WS-VALIDATION-FAIL-REASON-DESC
 *   END-IF
 * </pre>
 *
 * <h2>Validation condition (PR-04)</h2>
 * <p>The originating COBOL comparison sets reason code 102 when the credit
 * limit is strictly less than the projected cycle balance:</p>
 * <pre>
 *   ACCT-CREDIT-LIMIT &lt; (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT)
 * </pre>
 * <p>Equivalently, in Java (using {@link java.math.BigDecimal#compareTo} with
 * the identical operand order mandated by PR-04):</p>
 * <pre>
 *   creditLimit.compareTo(
 *       currCycCredit.subtract(currCycDebit).add(tranAmount)) &lt; 0
 * </pre>
 * <p>That comparison is performed by the <em>thrower</em> (for example
 * {@code TransactionValidator} / {@code AccountValidator} /
 * {@code TransactionPostingProcessor} / {@code BillPaymentService}); this
 * exception class is solely a carrier of the COBOL reason {@link #COBOL_CODE
 * code} and {@link #COBOL_MESSAGE message} and intentionally contains no
 * comparison logic of its own.</p>
 *
 * <h2>Precedence note (COBOL last-writer-wins)</h2>
 * <p>In {@code 1500-B-LOOKUP-ACCT} the overlimit check (L407-L413) and the
 * account-expiration check (L414-L420) are <em>two independent, sequential</em>
 * {@code IF...END-IF} blocks rather than an {@code else-if} chain. When a
 * transaction is simultaneously overlimit AND past expiration, the overlimit
 * branch first sets reason 102 and the expiration branch then overwrites it
 * with 103, so the COBOL program's final reason for the both-fail case is 103
 * ({@link ExpiredAccountException}). Reproducing that ordering is the
 * responsibility of the thrower; this class is only constructed when 102 is the
 * effective outcome.</p>
 *
 * <h2>Monetary representation (PR-16)</h2>
 * <p>The diagnostic constructor accepts {@link java.math.BigDecimal} operands
 * (never {@code float}/{@code double}) so that the COBOL {@code PIC S9(n)V99
 * COMP-3} packed-decimal money fields ({@code ACCT-CREDIT-LIMIT} and the
 * computed {@code WS-TEMP-BAL}) are represented exactly with their two-decimal
 * scale preserved.</p>
 *
 * <h2>HTTP mapping</h2>
 * <p>Mapped by {@code GlobalExceptionHandler} to
 * {@code 422 Unprocessable Entity} (the transaction is well-formed but cannot
 * be processed because it would exceed the account's credit limit).</p>
 *
 * <p>Like its parent, this is an <em>unchecked</em> exception (it transitively
 * extends {@link RuntimeException} via {@link TransactionValidationException}),
 * so service, validator, and batch-processor code need not declare it — this
 * mirrors the non-recoverable "move reason code and reject" flow of the
 * original COBOL program.</p>
 *
 * @see TransactionValidationException
 * @see ExpiredAccountException
 * @see "app/cbl/CBTRN02C.cbl L403-L413 (paragraph 1500-B-LOOKUP-ACCT); L411 message literal"
 */
public class OverlimitException extends TransactionValidationException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable}
     * contract inherited from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL {@code WS-VALIDATION-FAIL-REASON} value for an overlimit
     * rejection, as set at {@code app/cbl/CBTRN02C.cbl} L410
     * ({@code MOVE 102 TO WS-VALIDATION-FAIL-REASON}).
     */
    public static final int COBOL_CODE = 102;

    /**
     * The EXACT COBOL failure message from {@code app/cbl/CBTRN02C.cbl} L411,
     * preserved character-for-character per PR-03.
     *
     * <p>There is no trailing period and no spelling or spacing variation; the
     * literal must remain unchanged so that downstream parity checks against the
     * COBOL reference output (see {@code TransactionPostingParityTest}) succeed.</p>
     */
    public static final String COBOL_MESSAGE = "OVERLIMIT TRANSACTION";

    /**
     * Constructs the exception with the COBOL reason code {@code 102} and the
     * EXACT original COBOL message, matching the {@code ELSE} branch of the
     * credit-limit check in {@code 1500-B-LOOKUP-ACCT}.
     *
     * <p>After construction, {@code getCode()} returns {@code 102} and
     * {@code getMessage()} returns {@code "OVERLIMIT TRANSACTION"}.</p>
     */
    public OverlimitException() {
        super(COBOL_CODE, COBOL_MESSAGE);
    }

    /**
     * Constructs the exception with the COBOL reason code {@code 102} and a
     * diagnostic message that appends the offending monetary values for log/test
     * triage.
     *
     * <p>The base COBOL message {@link #COBOL_MESSAGE} is preserved verbatim as
     * the message prefix (per PR-03); the parenthetical detail is a Java-side
     * enhancement only and never alters the COBOL-faithful prefix, so
     * {@code getMessage().startsWith(COBOL_MESSAGE)} always holds.</p>
     *
     * @param creditLimit      the account's credit limit (COBOL
     *                         {@code ACCT-CREDIT-LIMIT}, a {@code BigDecimal} of
     *                         scale 2); may be {@code null}, in which case the
     *                         literal {@code "null"} is rendered
     * @param attemptedBalance the projected cycle balance that would be reached,
     *                         i.e. the COBOL {@code WS-TEMP-BAL} =
     *                         {@code currCycCredit - currCycDebit + tranAmount}
     *                         (a {@code BigDecimal} of scale 2); may be
     *                         {@code null}, in which case the literal
     *                         {@code "null"} is rendered
     */
    public OverlimitException(BigDecimal creditLimit, BigDecimal attemptedBalance) {
        super(COBOL_CODE,
                COBOL_MESSAGE + " (limit=" + creditLimit
                        + ", attempted=" + attemptedBalance + ")");
    }
}
