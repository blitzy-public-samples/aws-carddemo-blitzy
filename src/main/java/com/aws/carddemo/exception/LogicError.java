package com.aws.carddemo.exception;

/**
 * Typed {@link FileStatusException} representing COBOL <em>FILE STATUS</em> {@code '92'}
 * (a logic error).
 *
 * <p>A FILE STATUS of {@code '92'} signals an invalid or illegal I/O operation sequence &mdash;
 * for example a {@code READ}, {@code REWRITE}, or {@code DELETE} issued against a file in a state
 * that does not permit it. In the legacy application this condition is not matched by a dedicated
 * literal; instead every status that is neither {@code '00'} (success) nor {@code '10'}
 * (end-of-file) &mdash; {@code '92'} included &mdash; falls through the generic error branch, which
 * performs {@code 9910-DISPLAY-IO-STATUS} and then {@code 9999-ABEND-PROGRAM}. This type makes that
 * otherwise-generic path explicit while preserving its <strong>fail-fast</strong> semantics: the
 * condition is surfaced immediately and never swallowed, equivalent to the COBOL abend.</p>
 *
 * <p>Like its superclass this exception is <strong>unchecked</strong> and carries the originating
 * two-character status code via {@link #getFileStatus()}, which returns {@link #FILE_STATUS}
 * ({@code "92"}) for every instance so downstream handlers ({@code GlobalExceptionHandler} online,
 * skip/reject policy in batch) can branch exactly as the legacy programs did.</p>
 *
 * <p>Origin: FILE STATUS '92' logic-error &rarr; COBOL generic error/abend path
 * ({@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM}), representative
 * legacy/cbl/CBTRN02C.cbl. See Technical Specification &sect;0.6.5.</p>
 */
public class LogicError extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because {@link FileStatusException}
     * is {@link java.io.Serializable} through {@link RuntimeException} and the build compiles with
     * {@code -Xlint:all} and {@code failOnWarning}; omitting it would raise the {@code serial} lint
     * warning and fail the zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL FILE STATUS code represented by this exception: {@code "92"} (logic error). Kept as
     * a {@link String} to preserve the two-byte alphanumeric FILE STATUS semantics (significant
     * leading characters, non-numeric implementation-defined {@code "9x"} codes).
     */
    public static final String FILE_STATUS = "92";

    /**
     * Creates a logic-error exception with the given description, tagged with FILE STATUS
     * {@code "92"}.
     *
     * @param message a human-readable description of the illegal I/O operation sequence
     */
    public LogicError(String message) {
        super(FILE_STATUS, message);
    }

    /**
     * Creates a logic-error exception with the given description and underlying cause, tagged with
     * FILE STATUS {@code "92"}. The cause overload lets a handler wrap a Spring
     * {@code DataAccessException} (or any other trigger) without losing the original stack trace.
     *
     * @param message a human-readable description of the illegal I/O operation sequence
     * @param cause   the underlying throwable that triggered this exception
     */
    public LogicError(String message, Throwable cause) {
        super(FILE_STATUS, message, cause);
    }
}
