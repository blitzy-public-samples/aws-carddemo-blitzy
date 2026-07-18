package com.aws.carddemo.exception;

/**
 * Base of the AWS CardDemo FILE STATUS / CICS RESP exception hierarchy.
 *
 * <p>This type is the single Java carrier for the legacy COBOL <em>FILE STATUS</em> codes and the
 * CICS <em>EIBRESP</em>/{@code RESP} response values that were checked after every VSAM and
 * sequential-file operation in the mainframe application (now migrated to PostgreSQL data access).
 * Each legacy operation records a two-character status/response value and branches on it; this
 * exception preserves that value so downstream handlers can branch identically. Error-path
 * behavior is therefore reproduced exactly, with no swallowing and no new error categories.</p>
 *
 * <p>The exception is <strong>unchecked</strong> (it extends {@link RuntimeException}), consistent
 * with Spring's own {@code org.springframework.dao.DataAccessException} hierarchy. It is
 * <strong>concrete</strong> and may be thrown directly: it represents the COBOL {@code WHEN OTHER}
 * / generic non-mapped status path (the "else, generic error" branch), while specific mapped
 * status codes are represented by dedicated subtypes in this package. Together they provide full
 * status coverage with no gaps.</p>
 *
 * <p>The {@link #getFileStatus() fileStatus} value is a {@link String} because a COBOL FILE STATUS
 * is a two-byte <em>alphanumeric</em> field: it may be non-numeric (for example the
 * implementation-defined {@code "9x"} codes) and its leading zeros are significant (for example
 * {@code "00"} success, {@code "10"} end-of-file, {@code "23"} record-not-found). It is immutable
 * once assigned.</p>
 *
 * <p>Origin: FILE STATUS / EIBRESP handling convention across {@code legacy/cbl/**} (representative:
 * legacy/cbl/CBTRN02C.cbl 9910-DISPLAY-IO-STATUS; legacy/cbl/COSGN00C.cbl READ-USER-SEC-FILE RESP
 * handling). See Technical Specification &sect;0.6.5.</p>
 */
public class FileStatusException extends RuntimeException {

    /**
     * Serialization version identifier. Declared explicitly because {@link RuntimeException}
     * implements {@link java.io.Serializable} and the build compiles with {@code -Xlint:all} and
     * {@code failOnWarning}; omitting it would raise the {@code serial} lint warning and fail the
     * zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The originating two-character COBOL FILE STATUS code (for example {@code "00"}, {@code "10"},
     * {@code "23"}), or the equivalent code assigned when the trigger was a CICS {@code RESP} value
     * or a Spring {@code DataAccessException}.
     */
    private final String fileStatus;

    /**
     * Creates an exception carrying the originating status/response code and a description.
     *
     * @param fileStatus the originating two-character COBOL FILE STATUS (or mapped CICS
     *                   {@code RESP}) code; retained verbatim to preserve leading zeros and
     *                   non-numeric status bytes
     * @param message    a human-readable description of the failure
     */
    public FileStatusException(String fileStatus, String message) {
        super(message);
        this.fileStatus = fileStatus;
    }

    /**
     * Creates an exception carrying the originating status/response code, a description, and the
     * underlying cause. The cause overload lets {@code GlobalExceptionHandler} wrap a Spring
     * {@code DataAccessException} (or any other trigger) without losing the original stack trace.
     *
     * @param fileStatus the originating two-character COBOL FILE STATUS (or mapped CICS
     *                   {@code RESP}) code; retained verbatim to preserve leading zeros and
     *                   non-numeric status bytes
     * @param message    a human-readable description of the failure
     * @param cause      the underlying throwable that triggered this exception
     */
    public FileStatusException(String fileStatus, String message, Throwable cause) {
        super(message, cause);
        this.fileStatus = fileStatus;
    }

    /**
     * Returns the originating two-character COBOL FILE STATUS (or mapped CICS {@code RESP}) code
     * carried by this exception, enabling downstream handlers to branch exactly as the legacy
     * programs did.
     *
     * @return the two-character status/response code
     */
    public String getFileStatus() {
        return fileStatus;
    }
}
