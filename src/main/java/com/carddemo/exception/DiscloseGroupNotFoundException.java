package com.carddemo.exception;

/**
 * Exception signalling that a disclosure-group interest-rate lookup failed even
 * after the {@code DEFAULT}-group fallback retry was attempted.
 *
 * <p>This mirrors the {@code PERFORM 9999-ABEND-PROGRAM} branch of paragraph
 * {@code 1200-A-GET-DEFAULT-INT-RATE} in the interest-calculator batch program
 * {@code app/cbl/CBACT04C.cbl} (lines 443-460). The COBOL program reaches that
 * branch when the {@code DEFAULT} disclosure-group read also returns a
 * non-{@code '00'} file status (i.e. the record is missing) — a data-integrity /
 * reference-data configuration error rather than a per-record validation rule
 * violation.</p>
 *
 * <h2>COBOL source ({@code app/cbl/CBACT04C.cbl} L415-L460)</h2>
 * <pre>
 * 1200-GET-INTEREST-RATE.
 *     READ DISCGRP-FILE INTO DIS-GROUP-RECORD
 *          INVALID KEY
 *             DISPLAY 'DISCLOSURE GROUP RECORD MISSING'
 *             DISPLAY 'TRY WITH DEFAULT GROUP CODE'
 *     END-READ.
 *     ...
 *     IF  DISCGRP-STATUS  = '23'
 *         MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID
 *         PERFORM 1200-A-GET-DEFAULT-INT-RATE
 *     END-IF
 *     EXIT.
 *
 * 1200-A-GET-DEFAULT-INT-RATE.
 *     READ DISCGRP-FILE INTO DIS-GROUP-RECORD
 *     IF  DISCGRP-STATUS  = '00'
 *         MOVE 0 TO APPL-RESULT
 *     ELSE
 *         MOVE 12 TO APPL-RESULT          *&gt; failure path
 *     END-IF
 *     IF  APPL-AOK
 *         CONTINUE
 *     ELSE
 *         DISPLAY 'ERROR READING DEFAULT DISCLOSURE GROUP'
 *         MOVE DISCGRP-STATUS  TO IO-STATUS
 *         PERFORM 9910-DISPLAY-IO-STATUS
 *         PERFORM 9999-ABEND-PROGRAM      *&gt; ABEND on miss (this exception)
 *     END-IF
 *     EXIT.
 * </pre>
 *
 * <h2>DEFAULT fallback pattern (refactoring rule PR-02)</h2>
 * <ol>
 *   <li>Look up the disclosure group by {@code (groupId, typeCd, catCd)}.</li>
 *   <li>If that returns {@code Optional.empty()}, retry with
 *       {@code groupId = "DEFAULT"} and the same {@code typeCd} / {@code catCd}.</li>
 *   <li>If the {@code DEFAULT} lookup <em>also</em> returns {@code Optional.empty()},
 *       throw this exception.</li>
 * </ol>
 *
 * <p><strong>The fallback retry itself is the responsibility of the THROWER</strong>
 * (per PR-02): {@code InterestCalculationTasklet} or a delegate
 * {@code DiscloseGroupLookupService} performs the two-step lookup and constructs this
 * exception only once both lookups have missed. This class merely <em>signals</em> the
 * total failure; it deliberately contains no lookup or fallback logic of its own.</p>
 *
 * <h2>Inheritance</h2>
 * <p>Extends {@link RuntimeException} <strong>directly</strong> — <em>not</em>
 * {@link TransactionValidationException}. The disclosure-group miss is a
 * data-integrity / reference-data configuration error (the seeded
 * {@code disclosure_groups} table is missing a required {@code DEFAULT} row), so it is
 * intentionally outside the {@code CBTRN02C} transaction-validation code hierarchy
 * (codes 100/101/102/103). Consequently this class carries <strong>no</strong> numeric
 * COBOL reason-code field.</p>
 *
 * <p>It is an <em>unchecked</em> exception so that batch-step and service code need not
 * declare it, mirroring the non-recoverable {@code 9999-ABEND-PROGRAM} flow of the
 * original COBOL program.</p>
 *
 * <h2>HTTP mapping</h2>
 * <p>Handled by {@code GlobalExceptionHandler} ({@code @ControllerAdvice}) and mapped to
 * {@code 500 Internal Server Error}, reflecting that a missing {@code DEFAULT}
 * disclosure-group row is a server-side reference-data defect rather than a client
 * request error.</p>
 *
 * @see app/cbl/CBACT04C.cbl L415-L460 (1200-GET-INTEREST-RATE,
 *      1200-A-GET-DEFAULT-INT-RATE)
 */
public class DiscloseGroupNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable}
     * contract inherited from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Primary constructor with a descriptive message.
     *
     * <p>The supplied message should identify the lookup keys that failed (for example
     * the group id, transaction type code and category code) so that operators can
     * correct the missing reference data.</p>
     *
     * @param message a descriptive message identifying the failed lookup
     */
    public DiscloseGroupNotFoundException(String message) {
        super(message);
    }

    /**
     * Constructor with message and cause — for exception chaining when the miss is
     * wrapping an underlying repository, JPA or framework error that surfaced during the
     * disclosure-group lookup.
     *
     * @param message a descriptive message identifying the failed lookup
     * @param cause   the underlying cause (saved for later retrieval by
     *                {@link Throwable#getCause()}); a {@code null} value is permitted and
     *                indicates that the cause is nonexistent or unknown
     */
    public DiscloseGroupNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Convenience constructor for the common three-key lookup pattern. It builds a
     * self-describing message of the form:
     * <pre>
     * Disclosure group not found (including DEFAULT fallback): groupId=GROUP1, typeCd=05, catCd=0001
     * </pre>
     *
     * @param groupId the account group identifier that was tried first (the account's
     *                {@code ACCT-GROUP-ID}), or {@code "DEFAULT"} when reporting that the
     *                fallback lookup also failed
     * @param typeCd  the transaction type code (e.g. {@code "05"})
     * @param catCd   the transaction category code (e.g. {@code "0001"})
     */
    public DiscloseGroupNotFoundException(String groupId, String typeCd, String catCd) {
        super("Disclosure group not found (including DEFAULT fallback): "
              + "groupId=" + groupId + ", typeCd=" + typeCd + ", catCd=" + catCd);
    }
}
