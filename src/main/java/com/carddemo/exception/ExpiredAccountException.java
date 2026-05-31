package com.carddemo.exception;

/**
 * Exception representing <strong>COBOL validation code 103</strong>
 * ({@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}).
 *
 * <p>Thrown when a transaction's origination-timestamp date (the first ten
 * characters of the 26-character DB2 timestamp, i.e. {@code yyyy-MM-dd}) is
 * later than the account's expiration date. This mirrors the {@code ELSE}
 * branch of the expiration check in paragraph {@code 1500-B-LOOKUP-ACCT} of
 * {@code app/cbl/CBTRN02C.cbl} (lines 414-420).</p>
 *
 * <h2>COBOL source (EXACT preservation per PR-03)</h2>
 * <pre>
 *   IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)
 *     CONTINUE
 *   ELSE
 *     MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *     MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *       TO WS-VALIDATION-FAIL-REASON-DESC
 *   END-IF
 * </pre>
 *
 * <h2>Validation condition (PR-05)</h2>
 * <p>The originating comparison is
 * {@code ACCT-EXPIRAION-DATE < DALYTRAN-ORIG-TS(1:10)} — equivalently, in Java,
 * {@code expirationDate.compareTo(tranOrigTimestamp.substring(0, 10)) < 0}.
 * That comparison is performed by the <em>thrower</em> (for example
 * {@code AccountValidator} / {@code TransactionValidator} /
 * {@code TransactionPostingProcessor}); this exception class is solely a
 * carrier of the COBOL reason {@link #COBOL_CODE code} and
 * {@link #COBOL_MESSAGE message} and intentionally contains no comparison
 * logic of its own.</p>
 *
 * <h2>Field-name spelling note (PR-14)</h2>
 * <p>The original COBOL field {@code ACCT-EXPIRAION-DATE} is misspelled
 * ("EXPIRAION" instead of "EXPIRATION"). That misspelling is preserved verbatim
 * in the reference COBOL source but is corrected to {@code expirationDate} in
 * Java field names per PR-14. The <em>message string</em> "ACCT EXPIRATION" is
 * spelled correctly in the COBOL literal and is reproduced EXACTLY here per
 * PR-03 — note that "ACCT" is an intentional COBOL abbreviation of "ACCOUNT"
 * and must NOT be expanded.</p>
 *
 * <h2>HTTP mapping</h2>
 * <p>Mapped by {@code GlobalExceptionHandler} to
 * {@code 422 Unprocessable Entity} (the transaction is well-formed but cannot
 * be processed because the account has expired).</p>
 *
 * <p>Like its parent, this is an <em>unchecked</em> exception (it transitively
 * extends {@link RuntimeException} via {@link TransactionValidationException}),
 * so service, validator, and batch-processor code need not declare it — this
 * mirrors the non-recoverable "move reason code and reject" flow of the
 * original COBOL program.</p>
 *
 * @see TransactionValidationException
 * @see "app/cbl/CBTRN02C.cbl L414-L420 (paragraph 1500-B-LOOKUP-ACCT); L418 message literal"
 */
public class ExpiredAccountException extends TransactionValidationException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable}
     * contract inherited from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL {@code WS-VALIDATION-FAIL-REASON} value for an expired-account
     * rejection, as set at {@code app/cbl/CBTRN02C.cbl} L417
     * ({@code MOVE 103 TO WS-VALIDATION-FAIL-REASON}).
     */
    public static final int COBOL_CODE = 103;

    /**
     * The EXACT COBOL failure message from {@code app/cbl/CBTRN02C.cbl} L418,
     * preserved character-for-character per PR-03.
     *
     * <p>The abbreviation "ACCT" (not "ACCOUNT") is intentional and is part of
     * the original literal; it must remain unchanged so that downstream parity
     * checks against the COBOL reference output succeed.</p>
     */
    public static final String COBOL_MESSAGE =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Constructs the exception with the COBOL reason code {@code 103} and the
     * EXACT original COBOL message, matching the {@code ELSE} branch of the
     * expiration check in {@code 1500-B-LOOKUP-ACCT}.
     *
     * <p>After construction, {@code getCode()} returns {@code 103} and
     * {@code getMessage()} returns
     * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}.</p>
     */
    public ExpiredAccountException() {
        super(COBOL_CODE, COBOL_MESSAGE);
    }

    /**
     * Constructs the exception with the COBOL reason code {@code 103} and a
     * diagnostic message that appends the offending dates for log/test triage.
     *
     * <p>The base COBOL message {@link #COBOL_MESSAGE} is preserved verbatim as
     * the message prefix (per PR-03); the parenthetical date detail is a
     * Java-side enhancement only and never alters the COBOL-faithful prefix,
     * so {@code getMessage().startsWith(COBOL_MESSAGE)} always holds.</p>
     *
     * @param expirationDate the account's expiration date string
     *                       ({@code yyyy-MM-dd}); derived from the COBOL
     *                       {@code ACCT-EXPIRAION-DATE} field
     * @param tranDate       the transaction's origination date string
     *                       ({@code yyyy-MM-dd}, the first ten characters of the
     *                       26-character DB2 timestamp
     *                       {@code DALYTRAN-ORIG-TS(1:10)})
     */
    public ExpiredAccountException(String expirationDate, String tranDate) {
        super(COBOL_CODE,
                COBOL_MESSAGE + " (expiration=" + expirationDate
                        + ", tranDate=" + tranDate + ")");
    }
}
