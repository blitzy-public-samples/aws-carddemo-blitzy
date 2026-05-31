package com.carddemo.exception;

/**
 * Exception representing COBOL validation code {@code 101} (account-not-found).
 *
 * <p>Thrown when an account lookup by {@code acct_id} returns no record. Mirrors the
 * {@code INVALID KEY} branch of paragraph {@code 1500-B-LOOKUP-ACCT} in
 * {@code app/cbl/CBTRN02C.cbl} (lines 393-400), which is the second link in the
 * {@code 1500-VALIDATE-TRAN} validation chain (card cross-reference lookup &rarr; code
 * 100, followed by account lookup &rarr; code 101).</p>
 *
 * <h2>COBOL source (preserved per refactoring rule PR-03):</h2>
 * <pre>
 *   READ ACCOUNT-FILE INTO ACCOUNT-RECORD
 *      INVALID KEY
 *        MOVE 101 TO WS-VALIDATION-FAIL-REASON
 *        MOVE 'ACCOUNT RECORD NOT FOUND'
 *          TO WS-VALIDATION-FAIL-REASON-DESC
 * </pre>
 *
 * <p>The COBOL reason code {@code 101} is carried through to the REST error payload by
 * the inherited {@link TransactionValidationException#getCode()} accessor, preserving the
 * original batch validation semantics of {@code CBTRN02C} exactly.</p>
 *
 * <p><b>Message note:</b> The no-argument constructor uses the exact original COBOL
 * literal {@code "ACCOUNT RECORD NOT FOUND"}. The {@code Long} and {@code String}
 * constructors intentionally <em>enhance</em> the human-readable message to include the
 * missing account identifier (for example {@code "Account not found: 12345"}), because
 * Java callers benefit from knowing <em>which</em> account was missing. The original COBOL
 * literal is always available, unchanged, via the {@link #COBOL_MESSAGE} constant.</p>
 *
 * <h2>Usage flows:</h2>
 * <ul>
 *   <li><b>Batch</b> &mdash; {@code CBTRN02C} transaction-posting parity: raised when the
 *       daily transaction references an account that does not exist.</li>
 *   <li><b>Online</b> &mdash; service classes such as {@code AccountService.findById}
 *       raise it when an HTTP path parameter references a non-existent account.</li>
 * </ul>
 *
 * <p><b>HTTP mapping</b> (handled by {@code GlobalExceptionHandler}):
 * {@code 404 Not Found}.</p>
 *
 * <p>This is an <em>unchecked</em> exception by virtue of its parent extending
 * {@link RuntimeException}, mirroring the non-recoverable "move reason code and reject"
 * flow of the original COBOL program.</p>
 *
 * @see TransactionValidationException
 */
public class AccountNotFoundException extends TransactionValidationException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable} contract
     * inherited (transitively) from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * COBOL {@code WS-VALIDATION-FAIL-REASON} reason code for account-not-found.
     *
     * <p>Sourced from {@code app/cbl/CBTRN02C.cbl} line 397
     * ({@code MOVE 101 TO WS-VALIDATION-FAIL-REASON}).</p>
     */
    public static final int COBOL_CODE = 101;

    /**
     * Original COBOL message literal from {@code app/cbl/CBTRN02C.cbl} line 398
     * ({@code MOVE 'ACCOUNT RECORD NOT FOUND'}), preserved verbatim for reference and for
     * the no-argument constructor.
     */
    public static final String COBOL_MESSAGE = "ACCOUNT RECORD NOT FOUND";

    /**
     * No-argument constructor &mdash; uses the exact COBOL message
     * {@link #COBOL_MESSAGE} and reason code {@link #COBOL_CODE} ({@code 101}).
     *
     * <p>Matches the COBOL behaviour of moving the literal
     * {@code 'ACCOUNT RECORD NOT FOUND'} into the validation-failure description.</p>
     */
    public AccountNotFoundException() {
        super(COBOL_CODE, COBOL_MESSAGE);
    }

    /**
     * Numeric-identifier constructor &mdash; produces an enhanced Java message that
     * embeds the missing account identifier while retaining reason code
     * {@link #COBOL_CODE} ({@code 101}).
     *
     * <p>A {@code null} argument is permitted; it renders as the literal text
     * {@code "null"} (for example {@code "Account not found: null"}), which is an
     * acceptable, non-failing diagnostic message.</p>
     *
     * @param accountId the account ID that was not found (nullable)
     */
    public AccountNotFoundException(Long accountId) {
        super(COBOL_CODE, "Account not found: " + accountId);
    }

    /**
     * String-identifier constructor &mdash; for batch code that holds the account ID as a
     * {@code String} (for example, from fixed-width record parsing before conversion to a
     * {@code Long}). Produces an enhanced Java message embedding the supplied identifier
     * while retaining reason code {@link #COBOL_CODE} ({@code 101}).
     *
     * <p>A {@code null} argument is permitted; it renders as the literal text
     * {@code "null"}, which is an acceptable, non-failing diagnostic message.</p>
     *
     * @param accountId the account ID string that was not found (nullable)
     */
    public AccountNotFoundException(String accountId) {
        super(COBOL_CODE, "Account not found: " + accountId);
    }
}
