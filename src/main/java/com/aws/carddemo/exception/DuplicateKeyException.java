package com.aws.carddemo.exception;

/**
 * Duplicate-key member of the AWS CardDemo FILE STATUS / CICS RESP exception hierarchy.
 *
 * <p>This type carries COBOL <em>FILE STATUS</em> {@code "22"} and the equivalent CICS
 * <em>DUPREC</em>/<em>DUPKEY</em> condition. It is raised when an insert or write would violate
 * primary-key uniqueness &mdash; for example, adding a transaction or user whose key already
 * exists. Preserving the status value lets downstream handlers branch exactly as the legacy
 * programs did on the duplicate-record path.</p>
 *
 * <p>The {@linkplain #FILE_STATUS status code} is exposed as a public constant so the exception
 * bridge, the {@code GlobalExceptionHandler}, and tests can reference {@code "22"} symbolically
 * instead of repeating a magic string.</p>
 *
 * <p><strong>Naming note:</strong> Spring Data defines its own
 * {@code org.springframework.dao.DuplicateKeyException}. This class is the CardDemo
 * domain-hierarchy sibling in {@code com.aws.carddemo.exception} and is intentionally
 * <em>not</em> related to the Spring type: it neither imports nor extends it. The translation
 * from Spring's {@code DuplicateKeyException}/{@code DataIntegrityViolationException} to this
 * class is performed centrally by {@code GlobalExceptionHandler} using the {@linkplain
 * #DuplicateKeyException(String, Throwable) cause constructor}, keeping this class dependency-free
 * beyond its {@link FileStatusException} superclass.</p>
 *
 * <p>Like its superclass, the exception is <strong>unchecked</strong> (it ultimately extends
 * {@link RuntimeException}), consistent with Spring's {@code org.springframework.dao.DataAccessException}
 * hierarchy.</p>
 *
 * <p>Origin: CICS DFHRESP(DUPREC)/DFHRESP(DUPKEY) write handling &mdash; legacy/cbl/COTRN02C.cbl
 * ("Tran ID already exist...") and legacy/cbl/COUSR01C.cbl ("User ID already exist...") add-record
 * paths; FILE STATUS {@code '22'} duplicate-key for the batch/JPA equivalent. See Technical
 * Specification &sect;0.6.5.</p>
 */
public class DuplicateKeyException extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because {@link FileStatusException}
     * is {@link java.io.Serializable} (via {@link RuntimeException}) and the build compiles with
     * {@code -Xlint:all} and {@code failOnWarning}; omitting it would raise the {@code serial}
     * lint warning and fail the zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL FILE STATUS code represented by this exception: {@code "22"} (duplicate key).
     * Retained as a two-character {@link String} to preserve leading-zero and alphanumeric
     * semantics, and exposed so callers, the exception bridge, and tests avoid a magic string.
     */
    public static final String FILE_STATUS = "22";

    /**
     * Creates a duplicate-key exception with a human-readable description, tagged with FILE STATUS
     * {@code "22"}.
     *
     * @param message a human-readable description of the duplicate-key failure
     */
    public DuplicateKeyException(String message) {
        super(FILE_STATUS, message);
    }

    /**
     * Creates a duplicate-key exception with a description and the underlying cause, tagged with
     * FILE STATUS {@code "22"}. The cause overload lets {@code GlobalExceptionHandler} wrap a
     * Spring {@code org.springframework.dao.DuplicateKeyException} or
     * {@code DataIntegrityViolationException} without losing the original stack trace.
     *
     * @param message a human-readable description of the duplicate-key failure
     * @param cause   the underlying throwable that triggered this exception
     */
    public DuplicateKeyException(String message, Throwable cause) {
        super(FILE_STATUS, message, cause);
    }
}
