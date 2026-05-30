package com.carddemo.exception;

/**
 * Parent exception for the {@code CBTRN02C} transaction-validation code hierarchy.
 *
 * <p>Holds the COBOL {@code WS-VALIDATION-FAIL-REASON} value (also referred to as
 * {@code WS-RESP-CD} in the Agent Action Plan) as an {@code int code} field, enabling
 * {@code GlobalExceptionHandler} to populate {@code ErrorResponse.code} with the
 * COBOL-faithful numeric reason code. This preserves the original batch validation
 * semantics of {@code CBTRN02C} exactly, per refactoring rule PR-03.</p>
 *
 * <h2>Validation-code hierarchy (from {@code CBTRN02C} paragraph {@code 1500-VALIDATE-TRAN}):</h2>
 * <table border="1">
 *   <caption>Mapping of COBOL reason codes to specialized child exceptions</caption>
 *   <tr><th>Code</th><th>Child exception</th><th>Exact COBOL message</th></tr>
 *   <tr><td>0</td><td>(none — validation success)</td><td>&nbsp;</td></tr>
 *   <tr><td>100</td><td>{@code InvalidCardException}</td>
 *       <td>{@code "INVALID CARD NUMBER FOUND"}</td></tr>
 *   <tr><td>101</td><td>{@code AccountNotFoundException}</td>
 *       <td>{@code "ACCOUNT RECORD NOT FOUND"}</td></tr>
 *   <tr><td>102</td><td>{@code OverlimitException}</td>
 *       <td>{@code "OVERLIMIT TRANSACTION"}</td></tr>
 *   <tr><td>103</td><td>{@code ExpiredAccountException}</td>
 *       <td>{@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}</td></tr>
 *   <tr><td>109</td><td>(use this parent directly)</td>
 *       <td>{@code "ACCOUNT RECORD NOT FOUND"} (REWRITE-time, account update)</td></tr>
 * </table>
 *
 * <h2>COBOL source (WORKING-STORAGE definitions — {@code app/cbl/CBTRN02C.cbl} L181-L182):</h2>
 * <pre>
 *   05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 *   05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * </pre>
 *
 * <p>The reason code is populated by the validation chain in paragraph
 * {@code 1500-VALIDATE-TRAN} (L370-L422), which performs {@code 1500-A-LOOKUP-XREF}
 * (card cross-reference lookup → code 100) followed by {@code 1500-B-LOOKUP-ACCT}
 * (account lookup → code 101, credit-limit check → code 102, expiration check →
 * code 103). Code 109 originates from {@code 2800-UPDATE-ACCOUNT-REC} (L556) on a
 * REWRITE {@code INVALID KEY} condition.</p>
 *
 * <p><strong>This class is intentionally NOT {@code abstract}.</strong> It can be
 * instantiated directly for ad-hoc validation reason codes that are not covered by a
 * specialized child exception (for example, code 109 from {@code CBTRN02C} L556:
 * {@code new TransactionValidationException(109, "ACCOUNT RECORD NOT FOUND")}).</p>
 *
 * <p><strong>HTTP mapping</strong> (handled by {@code GlobalExceptionHandler}): an
 * unspecialized instance of this class maps to {@code 400 Bad Request} by default;
 * the specialized child exceptions are mapped to their own HTTP status codes via
 * dedicated {@code @ExceptionHandler} methods (e.g. account-not-found → 404,
 * overlimit / expired → 422).</p>
 *
 * <p>This is an <em>unchecked</em> exception (extends {@link RuntimeException}) so that
 * service and batch processor code need not declare it, mirroring the non-recoverable
 * "move reason code and reject" flow of the original COBOL program.</p>
 *
 * @see app/cbl/CBTRN02C.cbl L181-L182 (WORKING-STORAGE WS-VALIDATION-TRAILER),
 *      L370-L422 (1500-VALIDATE-TRAN paragraph), L556 (code 109)
 */
public class TransactionValidationException extends RuntimeException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable}
     * contract inherited from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL {@code WS-VALIDATION-FAIL-REASON} reason code ({@code PIC 9(04)}).
     *
     * <p>Typical values: {@code 0} = success, {@code 100} = invalid card,
     * {@code 101} = account not found, {@code 102} = overlimit, {@code 103} =
     * account expired, {@code 109} = account not found at REWRITE time.</p>
     *
     * <p>Declared {@code final} to guarantee immutability, in line with Java
     * exception best practice (an exception's identifying state should not change
     * after construction).</p>
     */
    private final int code;

    /**
     * Primary constructor binding a COBOL reason code to a failure message.
     *
     * @param code    the COBOL {@code WS-VALIDATION-FAIL-REASON} value
     *                (e.g. 100, 101, 102, 103, 109)
     * @param message the validation-failure message; for the standard CBTRN02C
     *                codes this is the exact original COBOL string (PR-03)
     */
    public TransactionValidationException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Constructor with an underlying cause, for exception chaining when wrapping a
     * lower-level lookup or framework exception that triggered the validation failure.
     *
     * @param code    the COBOL {@code WS-VALIDATION-FAIL-REASON} value
     * @param message the validation-failure message
     * @param cause   the underlying cause (saved for later retrieval by
     *                {@link Throwable#getCause()}); a {@code null} value is permitted
     */
    public TransactionValidationException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * Message-only constructor that defaults the reason {@link #code} to {@code 0}.
     *
     * <p>Use this for generic validation failures that do not correspond to a specific
     * {@code CBTRN02C} reason code.</p>
     *
     * @param message the validation-failure message
     */
    public TransactionValidationException(String message) {
        super(message);
        this.code = 0;
    }

    /**
     * Returns the COBOL {@code WS-VALIDATION-FAIL-REASON} reason code associated with
     * this validation failure.
     *
     * <p>Called by {@code GlobalExceptionHandler} to populate {@code ErrorResponse.code}
     * (typically via {@code String.valueOf(ex.getCode())}), preserving the COBOL-faithful
     * numeric code on the REST error payload.</p>
     *
     * @return the validation reason code (typically 0, 100, 101, 102, 103, or 109)
     */
    public int getCode() {
        return code;
    }
}
