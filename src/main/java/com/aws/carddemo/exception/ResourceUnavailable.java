package com.aws.carddemo.exception;

/**
 * Typed subtype of {@link FileStatusException} for the COBOL <em>FILE STATUS</em> {@code "93"}
 * (resource not available / file not open) code and the equivalent CICS {@code NOTOPEN} condition.
 *
 * <p>In the mainframe application this situation arose when an {@code OPEN} against a VSAM or
 * sequential dataset failed, leaving the file unavailable for the operation that followed; the
 * program guarded the open and abended rather than proceeding against an unopened file. In the
 * migrated stack the analogous trigger is a data-resource failure -- for example a
 * {@code DataSource}/connection-pool exhaustion or a database that is unreachable -- rather than a
 * literal VSAM open. Carrying the {@code "93"} status preserves the legacy error path so downstream
 * handlers branch identically.</p>
 *
 * <p>The condition is <strong>transient</strong> at the infrastructure level (the resource may
 * become available again on retry), but it is surfaced here as an <strong>error</strong> because
 * the in-flight operation cannot complete. The {@code GlobalExceptionHandler} bridge maps Spring's
 * {@code org.springframework.dao.DataAccessResourceFailureException} and
 * {@code org.springframework.jdbc.CannotGetJdbcConnectionException} to this type and typically
 * surfaces it as HTTP 503 (Service Unavailable) for online requests, while batch jobs treat it as a
 * fatal step failure.</p>
 *
 * <p>Like its superclass this exception is <strong>unchecked</strong> (it inherits from
 * {@link RuntimeException} via {@link FileStatusException}), consistent with Spring's
 * {@code org.springframework.dao.DataAccessException} hierarchy. Instances report
 * {@link #getFileStatus()} of {@code "93"}.</p>
 *
 * <p>Origin: FILE STATUS {@code '93'} not-open / CICS {@code NOTOPEN}, representative file-open
 * guard legacy/cbl/CBTRN02C.cbl (0300-DALYREJS-OPEN). See Technical Specification &sect;0.6.5.</p>
 */
public class ResourceUnavailable extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because {@link RuntimeException}
     * implements {@link java.io.Serializable} and the build compiles with {@code -Xlint:all} and
     * {@code failOnWarning}; omitting it would raise the {@code serial} lint warning and fail the
     * zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The two-character COBOL FILE STATUS code represented by this exception: {@code "93"}
     * (resource not available / file not open), equivalent to the CICS {@code NOTOPEN} condition.
     * Retained as a {@link String} to preserve the leading zero-significance and non-numeric
     * semantics of a COBOL FILE STATUS field.
     */
    public static final String FILE_STATUS = "93";

    /**
     * Creates an exception for an unavailable data resource, carrying the fixed FILE STATUS
     * {@code "93"} and a human-readable description.
     *
     * @param message a human-readable description of the unavailable resource or failed open
     */
    public ResourceUnavailable(String message) {
        super(FILE_STATUS, message);
    }

    /**
     * Creates an exception for an unavailable data resource, carrying the fixed FILE STATUS
     * {@code "93"}, a human-readable description, and the underlying cause. The cause overload lets
     * the {@code GlobalExceptionHandler} bridge wrap a Spring
     * {@code DataAccessResourceFailureException} or {@code CannotGetJdbcConnectionException} without
     * losing the original stack trace.
     *
     * @param message a human-readable description of the unavailable resource or failed open
     * @param cause   the underlying throwable that triggered this exception
     */
    public ResourceUnavailable(String message, Throwable cause) {
        super(FILE_STATUS, message, cause);
    }
}
