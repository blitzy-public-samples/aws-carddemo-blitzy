package com.aws.carddemo.exception;

/**
 * Signals COBOL {@code FILE STATUS = '10'} (end-of-file) and the CICS {@code ENDFILE} condition for
 * the migrated AWS CardDemo application.
 *
 * <p>This type is a <strong>normal end-of-input control signal, not a hard error or abend</strong>.
 * In the legacy batch programs the sequential read routines treat a {@code '10'} status as the
 * terminating condition of a read loop rather than a failure: the status sets the {@code APPL-EOF}
 * condition (an 88-level with {@code VALUE 16}), which moves {@code 'Y'} into {@code END-OF-FILE}
 * and lets the {@code PERFORM UNTIL END-OF-FILE = 'Y'} loop end cleanly, distinct from the abend
 * path taken for any other non-success status. This exception carries that same semantic into the
 * Java tier so batch readers and steps can recognise end-of-input and stop without treating it as
 * an exceptional error path, mirroring the COBOL {@code APPL-EOF} behaviour.</p>
 *
 * <p>Because it extends {@link FileStatusException} (which is unchecked), this exception is itself
 * unchecked. Callers that rely on it as a control signal should catch it explicitly at the
 * read-loop boundary; it must not be routed to {@code GlobalExceptionHandler} as an error line. If
 * it does reach the online handler it is surfaced as an informational end-of-data outcome, not an
 * error, mirroring the legacy behaviour where reading past the last record is an expected,
 * non-failing event (the CICS {@code DFHRESP(ENDFILE)} browse-paging condition).</p>
 *
 * <p>The {@link #FILE_STATUS} constant exposes the canonical two-character COBOL code {@code "10"}
 * so batch step configurations and tests can reference it without magic strings, preserving direct
 * traceability to the COBOL literal.</p>
 *
 * <p>Origin: FILE STATUS '10' EOF handling - legacy/cbl/CBACT01C.cbl (1000-ACCTFILE-GET-NEXT),
 * legacy/cbl/CBTRN02C.cbl (1000-DALYTRAN-GET-NEXT); CICS DFHRESP(ENDFILE) in online browse. See
 * Technical Specification &sect;0.6.5.</p>
 */
public class EndOfFileException extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because the exception hierarchy is
     * {@link java.io.Serializable} (via {@link RuntimeException}) and the build compiles with
     * {@code -Xlint:all} and {@code failOnWarning}; omitting it would raise the {@code serial} lint
     * warning and fail the zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The canonical two-character COBOL FILE STATUS code represented by this exception type,
     * {@code "10"} (end-of-file). Exposed as a public constant so batch readers, step
     * configurations, and tests can reference the code without magic strings, keeping traceability
     * to the COBOL literal {@code '10'}.
     */
    public static final String FILE_STATUS = "10";

    /**
     * Creates an end-of-file signal with a default, factual message.
     *
     * <p>Convenience for batch readers that reach the end of a sequential input and do not need to
     * supply a custom description.</p>
     */
    public EndOfFileException() {
        super(FILE_STATUS, "End of file reached");
    }

    /**
     * Creates an end-of-file signal carrying a caller-supplied description.
     *
     * @param message a human-readable description of the end-of-input condition
     */
    public EndOfFileException(String message) {
        super(FILE_STATUS, message);
    }

    /**
     * Creates an end-of-file signal carrying a description and the underlying cause.
     *
     * @param message a human-readable description of the end-of-input condition
     * @param cause   the underlying throwable that surfaced the end-of-file condition
     */
    public EndOfFileException(String message, Throwable cause) {
        super(FILE_STATUS, message, cause);
    }
}
