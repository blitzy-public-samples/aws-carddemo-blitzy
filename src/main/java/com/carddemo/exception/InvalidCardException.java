package com.carddemo.exception;

/**
 * Exception representing <strong>COBOL validation code 100</strong>
 * ({@code "INVALID CARD NUMBER FOUND"}).
 *
 * <p>Thrown when a card-number lookup against the {@code card_xref} cross-reference
 * table returns no record (a Spring Data {@code Optional.empty()} result). This
 * mirrors the {@code INVALID KEY} branch of paragraph {@code 1500-A-LOOKUP-XREF}
 * in {@code app/cbl/CBTRN02C.cbl} (lines 380-392), the first link in the
 * {@code 1500-VALIDATE-TRAN} validation chain (card cross-reference lookup &rarr;
 * code 100, followed by account lookup &rarr; code 101).</p>
 *
 * <h2>COBOL source (EXACT preservation per PR-03)</h2>
 * <pre>
 *   1500-A-LOOKUP-XREF.
 *       MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
 *       READ XREF-FILE INTO CARD-XREF-RECORD
 *          INVALID KEY
 *            MOVE 100 TO WS-VALIDATION-FAIL-REASON
 *            MOVE 'INVALID CARD NUMBER FOUND'
 *              TO WS-VALIDATION-FAIL-REASON-DESC
 *          NOT INVALID KEY
 *              CONTINUE
 *       END-READ
 *       EXIT.
 * </pre>
 *
 * <h2>Validation condition (PR-03)</h2>
 * <p>The originating condition is the VSAM {@code INVALID KEY} status (file status
 * {@code '23'}, record-not-found) returned by the keyed {@code READ} of
 * {@code XREF-FILE} on the daily-transaction card number. In Java this is the
 * {@code Optional.empty()} result of {@code cardXrefRepository.findById(cardNumber)}.
 * The lookup itself is performed by the <em>thrower</em> (for example
 * {@code TransactionValidator}, {@code TransactionPostingProcessor}, or
 * {@code CardService}); this exception class is solely a carrier of the COBOL
 * reason {@link #COBOL_CODE code} and {@link #COBOL_MESSAGE message} and
 * intentionally contains no lookup logic of its own.</p>
 *
 * <h2>Chain-position note (COBOL short-circuit)</h2>
 * <p>In paragraph {@code 1500-VALIDATE-TRAN} (L370-L378) the account lookup
 * {@code 1500-B-LOOKUP-ACCT} runs <em>only</em> when the cross-reference lookup
 * succeeded ({@code IF WS-VALIDATION-FAIL-REASON = 0}). Consequently code 100 has
 * the highest precedence: when the card number is invalid, the COBOL program never
 * evaluates the account / credit-limit / expiration checks (codes 101/102/103).
 * Reproducing that short-circuit ordering is the responsibility of the thrower;
 * this class is only constructed when 100 is the effective outcome.</p>
 *
 * <h2>HTTP mapping</h2>
 * <p>Mapped by {@code GlobalExceptionHandler} to {@code 400 Bad Request}: the
 * request references a card number that does not exist in the cross-reference
 * table, so it is treated as a malformed / unprocessable client request.</p>
 *
 * <p>Like its parent, this is an <em>unchecked</em> exception (it transitively
 * extends {@link RuntimeException} via {@link TransactionValidationException}), so
 * service, validator, and batch-processor code need not declare it — this mirrors
 * the non-recoverable "move reason code and reject" flow of the original COBOL
 * program.</p>
 *
 * @see TransactionValidationException
 * @see "app/cbl/CBTRN02C.cbl L380-L392 (paragraph 1500-A-LOOKUP-XREF); L386 message literal"
 */
public class InvalidCardException extends TransactionValidationException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable}
     * contract inherited (transitively) from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL {@code WS-VALIDATION-FAIL-REASON} value for an invalid-card
     * rejection, as set at {@code app/cbl/CBTRN02C.cbl} L385
     * ({@code MOVE 100 TO WS-VALIDATION-FAIL-REASON}).
     */
    public static final int COBOL_CODE = 100;

    /**
     * The EXACT COBOL failure message from {@code app/cbl/CBTRN02C.cbl} L386,
     * preserved character-for-character per PR-03.
     *
     * <p>There is no trailing period and no spelling or spacing variation; the
     * literal must remain unchanged so that downstream parity checks against the
     * COBOL reference output (see {@code TransactionPostingParityTest}) succeed.</p>
     */
    public static final String COBOL_MESSAGE = "INVALID CARD NUMBER FOUND";

    /**
     * No-argument constructor — uses the COBOL reason code {@code 100} and the
     * EXACT original COBOL message, matching the {@code INVALID KEY} branch of
     * paragraph {@code 1500-A-LOOKUP-XREF}.
     *
     * <p>After construction, {@code getCode()} returns {@code 100} and
     * {@code getMessage()} returns {@code "INVALID CARD NUMBER FOUND"}.</p>
     */
    public InvalidCardException() {
        super(COBOL_CODE, COBOL_MESSAGE);
    }

    /**
     * Card-number constructor — produces a diagnostic message that appends the
     * offending card number for log/test triage while retaining reason code
     * {@link #COBOL_CODE} ({@code 100}).
     *
     * <p>The base COBOL message {@link #COBOL_MESSAGE} is preserved verbatim as
     * the message prefix (per PR-03); the parenthetical card-number detail is a
     * Java-side enhancement only and never alters the COBOL-faithful prefix, so
     * {@code getMessage().startsWith(COBOL_MESSAGE)} always holds.</p>
     *
     * <p>A {@code null} argument is permitted; it renders as the literal text
     * {@code "null"} (for example {@code "INVALID CARD NUMBER FOUND (card=null)"}),
     * which is an acceptable, non-failing diagnostic message.</p>
     *
     * @param cardNumber the offending card number that was not found in the
     *                   cross-reference table (COBOL {@code DALYTRAN-CARD-NUM} /
     *                   {@code FD-XREF-CARD-NUM}); may be {@code null}
     */
    public InvalidCardException(String cardNumber) {
        super(COBOL_CODE, COBOL_MESSAGE + " (card=" + cardNumber + ")");
    }
}
