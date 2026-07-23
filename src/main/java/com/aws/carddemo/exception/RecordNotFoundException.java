package com.aws.carddemo.exception;

/**
 * Signals that a keyed read or lookup found no matching record.
 *
 * <p>This is the single Java carrier for the three equivalent legacy "record not found" signals
 * that the mainframe application checked after a keyed read: the COBOL VSAM/sequential
 * <em>FILE STATUS</em> value {@code "23"} (and its logically identical companion {@code "13"}), and
 * the CICS <em>EIBRESP</em>/{@code RESP} response {@code NOTFND} (numeric value {@code 13}). All
 * three collapse to this one type so that downstream handlers branch identically regardless of which
 * legacy access method raised the miss. It is raised whenever a lookup by primary key or alternate
 * index returns nothing &mdash; account, card, customer, user, transaction, disclosure group,
 * transaction-category balance, and so on.</p>
 *
 * <p>The canonical {@link #FILE_STATUS} carried by this exception is {@code "23"}. The batch programs
 * observed both {@code "23"} and {@code "13"} as not-found on a keyed read, and the online programs
 * observed the equivalent CICS {@code NOTFND} condition; the migration maps all of them here and
 * records the equivalence in the traceability matrix. Choosing {@code "23"} as the single retained
 * value keeps the {@link #getFileStatus() fileStatus} deterministic while preserving the not-found
 * <em>behavior</em> exactly.</p>
 *
 * <p>This exception is <strong>unchecked</strong> (it inherits from {@link FileStatusException},
 * which extends {@link RuntimeException}), consistent with Spring's
 * {@code org.springframework.dao.DataAccessException} hierarchy. Do not import or extend
 * {@code jakarta.persistence.EntityNotFoundException} or
 * {@code org.springframework.dao.EmptyResultDataAccessException} here: the translation from those
 * framework types into this domain exception is performed centrally in {@code GlobalExceptionHandler}
 * (online) or by a batch skip/reject policy, using the {@linkplain #RecordNotFoundException(String,
 * Throwable) cause} overload to preserve the original stack trace.</p>
 *
 * <p>Origin: CICS {@code NOTFND} (RESP 13) &mdash; legacy/cbl/COSGN00C.cbl READ-USER-SEC-FILE
 * ({@code EVALUATE WS-RESP-CD WHEN 13} &rarr; "User not found. Try again ..."); FILE STATUS
 * {@code "23"} &mdash; legacy/cbl/CBACT04C.cbl 1200-GET-INTEREST-RATE
 * ({@code DISCGRP-STATUS = '23'} disclosure-group lookup miss) and legacy/cbl/CBTRN02C.cbl
 * ({@code TCATBALF-STATUS = '23'} transaction-category-balance not yet existing). See Technical
 * Specification &sect;0.6.5.</p>
 */
public class RecordNotFoundException extends FileStatusException {

    /**
     * Serialization version identifier. Declared explicitly because {@link FileStatusException} is
     * ultimately {@link java.io.Serializable} (via {@link RuntimeException}) and the build compiles
     * with {@code -Xlint:all} and {@code failOnWarning}; omitting it would raise the {@code serial}
     * lint warning and fail the zero-warning build.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The canonical COBOL FILE STATUS code represented by this exception: {@code "23"}
     * (record not found on a keyed read). The logically identical FILE STATUS {@code "13"} and the
     * CICS {@code NOTFND} response (RESP {@code 13}) map to this same type; {@code "23"} is retained
     * as the single deterministic value so that {@link #getFileStatus()} is stable for downstream
     * branching. Retained as a {@link String} to preserve the significant leading zero of the
     * two-byte alphanumeric FILE STATUS field.
     */
    public static final String FILE_STATUS = "23";

    /**
     * Creates a record-not-found exception with the canonical FILE STATUS {@code "23"} and a
     * description of the failed lookup.
     *
     * @param message a human-readable description of the failed lookup (for example, which key was
     *                searched and in which store)
     */
    public RecordNotFoundException(String message) {
        super(FILE_STATUS, message);
    }

    /**
     * Creates a record-not-found exception with the canonical FILE STATUS {@code "23"}, a
     * description, and the underlying cause. This overload lets {@code GlobalExceptionHandler} (or a
     * batch skip/reject policy) wrap a framework not-found trigger &mdash; for example a Spring
     * {@code org.springframework.dao.EmptyResultDataAccessException} or a
     * {@code jakarta.persistence.EntityNotFoundException} &mdash; without losing the original stack
     * trace.
     *
     * @param message a human-readable description of the failed lookup
     * @param cause   the underlying throwable that signaled the missing record
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(FILE_STATUS, message, cause);
    }
}
