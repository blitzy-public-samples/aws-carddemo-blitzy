package com.aws.carddemo.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * Application-wide exception handler for the migrated AWS CardDemo online (web) tier.
 *
 * <p>This {@link ControllerAdvice} is the Java analogue of the COBOL/CICS online error path. When a
 * service or repository throws one of the typed {@link FileStatusException} subtypes &mdash; the
 * carriers of the legacy VSAM/sequential <em>FILE STATUS</em> codes and CICS {@code EIBRESP}/
 * {@code RESP} responses &mdash; this advice renders the shared CardDemo error screen and places a
 * concise message on the {@value #ATTR_ERROR_MESSAGE} model attribute. That attribute mirrors the
 * BMS {@code ERRMSGO} field (a {@code PIC X(78)} error line present on every 3270 map), so the
 * server-rendered Thymeleaf template reproduces the mainframe behaviour of
 * {@code MOVE <msg> TO ERRMSGO OF <map>} followed by {@code SEND MAP}. Every handler returns a
 * {@link ModelAndView} (a view plus a model), never a JSON body: the migration is a server-rendered
 * Thymeleaf application and introduces no new REST/JSON interface.</p>
 *
 * <p>The advice also <em>bridges</em> framework data-access failures into the same screen behaviour.
 * Spring's {@code org.springframework.dao.DataAccessException} subclasses, JPA's
 * {@code jakarta.persistence.EntityNotFoundException}, and the transaction-infrastructure failure
 * {@code org.springframework.transaction.CannotCreateTransactionException} (a connection that cannot
 * be borrowed to <em>begin</em> a transaction, for example on a synchronous batch launch) are
 * translated to the equivalent CardDemo status semantics (record-not-found, duplicate/constraint
 * violation, resource-unavailable, or a generic data error) and surfaced identically, preserving the
 * underlying cause so no diagnostic detail is lost. Each handler logs before returning; nothing is silently discarded. The HTTP status
 * carried on the {@link ModelAndView} records the outcome category: {@code 404} not-found,
 * {@code 409} duplicate, {@code 503} resource-unavailable, {@code 500} logic/base/generic, and
 * {@code 200} for the informational end-of-data signal.</p>
 *
 * <p>The end-of-file signal ({@link EndOfFileException}, legacy FILE STATUS {@code '10'}) is treated
 * as a <em>normal</em> outcome rather than a failure: reaching the end of a browse or paging
 * sequence is an expected, non-error event on the mainframe, so if it reaches this advice it is
 * surfaced informationally with {@link HttpStatus#OK}.</p>
 *
 * <h2>Batch tier</h2>
 * <p>Spring Batch jobs do <strong>not</strong> use this advice; it is scoped to
 * {@code org.springframework.stereotype.Controller} beans only. The batch tier applies a
 * skip/reject policy instead. Business-validation rejects are written to the {@code DALYREJS}
 * reject file as a 430-byte record (a 350-byte copy of the transaction plus an 80-byte trailer of a
 * {@code PIC 9(04)} reason code and a {@code PIC X(76)} description &mdash; for example reason
 * {@code 102} &ldquo;OVERLIMIT TRANSACTION&rdquo;) through a {@code FlatFileItemWriter}. Unexpected
 * I/O statuses &mdash; mapped to {@link FileStatusException} subtypes such as {@link LogicError} or
 * {@link ResourceUnavailable} &mdash; propagate to fail the step, the equivalent of the COBOL
 * {@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} path. A normal end-of-file
 * ({@link EndOfFileException}, FILE STATUS {@code '10'}) ends the read loop cleanly. This paragraph
 * is documentation only; no batch code lives in this class.</p>
 *
 * <p>Origin: online error-line handling legacy/cbl/COSGN00C.cbl (EVALUATE WS-RESP-CD &rarr; ERRMSGO);
 * batch reject policy legacy/cbl/CBTRN02C.cbl (2500-WRITE-REJECT-REC, DALYREJS 430B). See Technical
 * Specification &sect;0.6.5 and &sect;0.6.8.</p>
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Logger for this advice. Correlation and trace identifiers are injected into every line
     * automatically by {@code logback-spring.xml} together with Micrometer tracing, so handlers log
     * plain messages and rely on that infrastructure for request correlation.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Logical view name of the shared CardDemo error screen. This is also Spring Boot's default
     * error view name, which guarantees a working fallback even before the web tier's
     * {@code templates/error.html} is present.
     */
    private static final String ERROR_VIEW = "error";

    /**
     * Model attribute carrying the on-screen error line. Mirrors the BMS {@code ERRMSGO}
     * ({@code PIC X(78)}) field; the Thymeleaf error template renders it as the message line.
     */
    private static final String ATTR_ERROR_MESSAGE = "errorMessage";

    /**
     * Model attribute carrying the originating two-character FILE STATUS / mapped {@code RESP} code
     * (from {@link FileStatusException#getFileStatus()}) for optional display and diagnostics.
     */
    private static final String ATTR_FILE_STATUS = "fileStatus";

    /**
     * Model attribute carrying the instant the error was rendered, for optional display and
     * diagnostics.
     */
    private static final String ATTR_TIMESTAMP = "timestamp";

    /**
     * Sanitized on-screen line for a request that carried a byte the database cannot store &mdash;
     * specifically an untranslatable character (SQLSTATE {@code 22021}, for example an embedded NUL /
     * COBOL {@code LOW-VALUES}). Rendered with HTTP {@code 400 Bad Request} and no FILE STATUS: this is
     * malformed input, not a duplicate key and not a server logic error. It carries no echo of the
     * offending value. Used as defence-in-depth behind the keyed-lookup boundary rejection (finding
     * P13-INPUT-01).
     */
    private static final String MSG_INVALID_INPUT =
            "The request contained characters that cannot be processed.";

    /**
     * Handles a record-not-found condition (CardDemo FILE STATUS {@code "23"} / CICS
     * {@code NOTFND}). Logged at {@code WARN} because a missing keyed record is an expected,
     * user-recoverable outcome (for example a mistyped account or card number).
     *
     * @param ex the record-not-found exception carrying the failed-lookup description
     * @return the error screen with the message line set and HTTP {@code 404 Not Found}
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ModelAndView handleRecordNotFound(RecordNotFoundException ex) {
        log.warn("Record not found [FILE STATUS {}]: {}", ex.getFileStatus(), ex.getMessage());
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.NOT_FOUND);
    }

    /**
     * Handles a duplicate-key condition (CardDemo FILE STATUS {@code "22"} / CICS {@code DUPREC}).
     * Logged at {@code WARN} because attempting to add an already-existing key is an expected,
     * user-recoverable outcome (for example adding a transaction or user that already exists).
     *
     * @param ex the duplicate-key exception carrying the conflicting-write description
     * @return the error screen with the message line set and HTTP {@code 409 Conflict}
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ModelAndView handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("Duplicate key [FILE STATUS {}]: {}", ex.getFileStatus(), ex.getMessage());
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.CONFLICT);
    }

    /**
     * Handles an unavailable data resource (CardDemo FILE STATUS {@code "93"} / CICS
     * {@code NOTOPEN}), such as a connection-pool exhaustion or an unreachable database. Logged at
     * {@code ERROR} with the full stack trace because the in-flight operation cannot complete.
     *
     * @param ex the resource-unavailable exception carrying the failure description
     * @return the error screen with the message line set and HTTP {@code 503 Service Unavailable}
     */
    @ExceptionHandler(ResourceUnavailable.class)
    public ModelAndView handleResourceUnavailable(ResourceUnavailable ex) {
        log.error("Resource unavailable [FILE STATUS {}]: {}", ex.getFileStatus(), ex.getMessage(), ex);
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Handles a logic error (CardDemo FILE STATUS {@code "92"} &mdash; an invalid or illegal I/O
     * operation sequence). This is the online equivalent of the COBOL generic
     * {@code 9910-DISPLAY-IO-STATUS} + {@code 9999-ABEND-PROGRAM} path, so it is logged at
     * {@code ERROR} with the full stack trace.
     *
     * @param ex the logic-error exception carrying the failure description
     * @return the error screen with the message line set and HTTP {@code 500 Internal Server Error}
     */
    @ExceptionHandler(LogicError.class)
    public ModelAndView handleLogicError(LogicError ex) {
        log.error("Logic error [FILE STATUS {}]: {}", ex.getFileStatus(), ex.getMessage(), ex);
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Handles an end-of-file signal (CardDemo FILE STATUS {@code "10"} / CICS {@code ENDFILE}).
     * Reaching the end of a browse or paging sequence is a normal, non-failing event on the
     * mainframe, so it is logged at {@code DEBUG} and surfaced informationally with
     * {@link HttpStatus#OK} rather than as a hard error. It normally never reaches this advice
     * because browse/paging logic recognises it inline.
     *
     * @param ex the end-of-file signal carrying an informational description
     * @return the error screen with the informational line set and HTTP {@code 200 OK}
     */
    @ExceptionHandler(EndOfFileException.class)
    public ModelAndView handleEndOfFile(EndOfFileException ex) {
        log.debug("End-of-file signal reached the online handler [FILE STATUS {}]: {}",
                ex.getFileStatus(), ex.getMessage());
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.OK);
    }

    /**
     * Handles any {@link FileStatusException} not matched by a more specific handler above &mdash;
     * the base of the CardDemo status hierarchy, representing the COBOL generic {@code WHEN OTHER}
     * (non-mapped status) branch. Because Spring resolves the most specific {@code @ExceptionHandler},
     * the subtype handlers take precedence and this method sees only unmapped base instances. Logged
     * at {@code ERROR} with the full stack trace.
     *
     * @param ex the base file-status exception carrying the originating code and description
     * @return the error screen with the message line set and HTTP {@code 500 Internal Server Error}
     */
    @ExceptionHandler(FileStatusException.class)
    public ModelAndView handleFileStatus(FileStatusException ex) {
        log.error("Unmapped FILE STATUS [{}]: {}", ex.getFileStatus(), ex.getMessage(), ex);
        return errorView(ex.getMessage(), ex.getFileStatus(), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Bridges a framework not-found failure into CardDemo record-not-found behaviour. Both Spring's
     * {@code org.springframework.dao.EmptyResultDataAccessException} and JPA's
     * {@code jakarta.persistence.EntityNotFoundException} are translated to a
     * {@link RecordNotFoundException} (preserving the original cause) and rendered identically to a
     * natively thrown miss. The parameter type is {@link RuntimeException} because that is the
     * closest common supertype of the two mapped types ({@code EntityNotFoundException} is not a
     * {@code DataAccessException}); Spring routes only the two annotated types here. Logged at
     * {@code WARN}.
     *
     * @param ex the framework not-found exception being bridged
     * @return the error screen with the message line set and HTTP {@code 404 Not Found}
     */
    @ExceptionHandler({ org.springframework.dao.EmptyResultDataAccessException.class,
            jakarta.persistence.EntityNotFoundException.class })
    public ModelAndView handleDataAccessNotFound(RuntimeException ex) {
        RecordNotFoundException translated = new RecordNotFoundException("Requested record not found.", ex);
        log.warn("Record not found (bridged from {}) [FILE STATUS {}]: {}",
                ex.getClass().getName(), translated.getFileStatus(), translated.getMessage());
        return errorView(translated.getMessage(), translated.getFileStatus(), HttpStatus.NOT_FOUND);
    }

    /**
     * Bridges a framework duplicate-key or constraint violation into CardDemo duplicate-key
     * behaviour. Spring's {@code org.springframework.dao.DuplicateKeyException} and
     * {@code org.springframework.dao.DataIntegrityViolationException} are translated to a
     * {@link DuplicateKeyException} (preserving the original cause) and rendered identically to a
     * natively thrown duplicate. These Spring types are referenced by fully-qualified name because
     * their simple names collide with CardDemo's own {@link DuplicateKeyException}. Logged at
     * {@code WARN}.
     *
     * @param ex the framework data-integrity exception being bridged
     * @return the error screen with the message line set and HTTP {@code 409 Conflict}
     */
    @ExceptionHandler({ org.springframework.dao.DuplicateKeyException.class,
            org.springframework.dao.DataIntegrityViolationException.class })
    public ModelAndView handleDataAccessDuplicate(org.springframework.dao.DataAccessException ex) {
        String sqlState = sqlStateOf(ex);
        // An untranslatable-character violation (SQLSTATE 22021 - for example an embedded NUL / COBOL
        // LOW-VALUES that PostgreSQL cannot store) is malformed input, NOT a duplicate key. Carving it
        // out here prevents the misleading "Record already exists." / FILE STATUS 22 / HTTP 409 that the
        // blanket DataIntegrityViolationException mapping would otherwise produce (finding P13-INPUT-01).
        // The primary defence is boundary rejection in the keyed-lookup services; this is
        // defence-in-depth for any residual path that still reaches the database.
        if (isUntranslatableCharacter(sqlState)) {
            log.warn("Invalid character in request (bridged from {}, SQLSTATE {}): {}",
                    ex.getClass().getName(), sqlState, ex.getMessage());
            return errorView(MSG_INVALID_INPUT, null, HttpStatus.BAD_REQUEST);
        }
        // Otherwise - a unique-key violation (SQLSTATE 23505), Spring's own DuplicateKeyException, or
        // any other integrity constraint - reproduce the CardDemo duplicate-key behaviour unchanged:
        // FILE STATUS "22" / CICS DUPREC / HTTP 409 Conflict.
        DuplicateKeyException translated = new DuplicateKeyException("Record already exists.", ex);
        log.warn("Duplicate/constraint violation (bridged from {}) [FILE STATUS {}]: {}",
                ex.getClass().getName(), translated.getFileStatus(), translated.getMessage());
        return errorView(translated.getMessage(), translated.getFileStatus(), HttpStatus.CONFLICT);
    }

    /**
     * Bridges a framework data-resource failure (for example a lost or unobtainable database
     * connection) into CardDemo resource-unavailable behaviour. Spring's
     * {@code org.springframework.dao.DataAccessResourceFailureException} is translated to a
     * {@link ResourceUnavailable} (preserving the original cause). Logged at {@code ERROR} with the
     * full stack trace.
     *
     * @param ex the framework resource-failure exception being bridged
     * @return the error screen with the message line set and HTTP {@code 503 Service Unavailable}
     */
    @ExceptionHandler(org.springframework.dao.DataAccessResourceFailureException.class)
    public ModelAndView handleDataAccessResourceFailure(
            org.springframework.dao.DataAccessResourceFailureException ex) {
        ResourceUnavailable translated = new ResourceUnavailable("Data service unavailable. Please retry.", ex);
        log.error("Data resource failure (bridged) [FILE STATUS {}]: {}",
                translated.getFileStatus(), translated.getMessage(), ex);
        return errorView(translated.getMessage(), translated.getFileStatus(), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Bridges a transaction-infrastructure failure raised while <em>beginning</em> a transaction
     * &mdash; Spring's {@code org.springframework.transaction.CannotCreateTransactionException}
     * &mdash; into CardDemo resource-unavailable behaviour, so a database outage surfaces
     * identically to the pure read path.
     *
     * <p>A repository read whose connection cannot be obtained is translated by the persistence layer
     * into a {@code org.springframework.dao.DataAccessResourceFailureException} (a
     * {@link org.springframework.dao.DataAccessException}) and handled above &mdash; hence the read
     * path already yields {@code 503}. A <em>synchronous batch launch</em> fails one layer earlier:
     * {@code JobLauncher.run(...)} asks the {@code JobRepository}'s {@code PlatformTransactionManager}
     * to begin a transaction, and when the connection cannot be borrowed (database unreachable, or the
     * connection pool exhausted) the transaction manager raises a
     * {@code CannotCreateTransactionException}. That type extends
     * {@code org.springframework.transaction.TransactionException}, <strong>not</strong>
     * {@code DataAccessException}, so without this handler it escapes every data-access bridge above
     * and falls through to the container's generic {@code 500}. Mapping it here to
     * {@link ResourceUnavailable} (FILE STATUS {@code "93"} / CICS {@code NOTOPEN}) restores parity:
     * the same underlying &ldquo;cannot open the data resource&rdquo; condition yields the same
     * sanitized HTTP {@code 503 Service Unavailable} and identical on-screen message, whether it
     * arises on a read or on a batch-launch request. Referenced by fully-qualified name (no import),
     * consistent with the data-access bridges above. Logged at {@code ERROR} with the full stack
     * trace because the in-flight operation cannot complete.</p>
     *
     * <p>Origin: FILE STATUS {@code '93'} not-open / CICS {@code NOTOPEN} fatal open guard
     * legacy/cbl/CBTRN02C.cbl (0300-DALYREJS-OPEN &rarr; 9999-ABEND-PROGRAM); the modern trigger is a
     * transaction-begin connection failure on the report-submit batch-launch path
     * (legacy/cbl/CORPT00C.cbl WIRTE-JOBSUB-TDQ &rarr; {@code JobLauncher.run}). See Technical
     * Specification &sect;0.6.5.</p>
     *
     * @param ex the transaction-creation failure being bridged (the connection could not be obtained
     *           to begin the transaction)
     * @return the error screen with the message line set and HTTP {@code 503 Service Unavailable}
     */
    @ExceptionHandler(org.springframework.transaction.CannotCreateTransactionException.class)
    public ModelAndView handleCannotCreateTransaction(
            org.springframework.transaction.CannotCreateTransactionException ex) {
        ResourceUnavailable translated = new ResourceUnavailable("Data service unavailable. Please retry.", ex);
        log.error("Transaction could not be started (bridged from {}) [FILE STATUS {}]: {}",
                ex.getClass().getName(), translated.getFileStatus(), translated.getMessage(), ex);
        return errorView(translated.getMessage(), translated.getFileStatus(), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * Bridges a transaction-completion failure &mdash; any remaining Spring
     * {@code org.springframework.transaction.TransactionException} not matched by the more specific
     * {@link #handleCannotCreateTransaction(org.springframework.transaction.CannotCreateTransactionException)
     * transaction-begin} handler above &mdash; into the correct CardDemo status semantics by inspecting
     * the originating SQLSTATE, so a failure that occurs while <em>committing</em> never escapes as a
     * bare container {@code 500}.
     *
     * <p>The motivating case (finding P13-INPUT-01) is {@code UnexpectedRollbackException}: a service
     * method annotated {@link org.springframework.transaction.annotation.Transactional Transactional}
     * performs a keyed read/write that the database aborts (for example an embedded NUL yields SQLSTATE
     * {@code 22021}), catches the resulting {@code DataAccessException} to re-display its screen inline,
     * and then returns normally &mdash; but the underlying transaction was already marked rollback-only
     * by the aborted statement, so the framework's commit throws {@code UnexpectedRollbackException} (a
     * {@code TransactionException}, <strong>not</strong> a {@code DataAccessException}). Without this
     * handler that would fall through to the generic {@code 500}. The primary defence is boundary
     * rejection of unstorable input in the keyed-lookup services; this handler is defence-in-depth that
     * classifies whatever reaches it by SQLSTATE: connection-loss ({@code 08*} / {@code 57P01..03})
     * &rarr; {@link ResourceUnavailable} / FILE STATUS {@code "93"} / HTTP {@code 503}; untranslatable
     * character ({@code 22021}) &rarr; HTTP {@code 400}; anything else &rarr; a sanitized {@code 500}.
     * Referenced by fully-qualified name (no import), consistent with the other framework bridges.</p>
     *
     * @param ex the transaction-completion failure being bridged
     * @return the error screen with the mapped message line and HTTP status for the classified cause
     */
    @ExceptionHandler(org.springframework.transaction.TransactionException.class)
    public ModelAndView handleTransactionException(org.springframework.transaction.TransactionException ex) {
        String sqlState = sqlStateOf(ex);
        if (isConnectionLoss(sqlState)) {
            ResourceUnavailable translated =
                    new ResourceUnavailable("Data service unavailable. Please retry.", ex);
            log.error("Transaction failed to complete (bridged from {}, SQLSTATE {}) [FILE STATUS {}]: {}",
                    ex.getClass().getName(), sqlState, translated.getFileStatus(),
                    translated.getMessage(), ex);
            return errorView(translated.getMessage(), translated.getFileStatus(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (isUntranslatableCharacter(sqlState)) {
            log.warn("Invalid character in request (bridged from {}, SQLSTATE {}): {}",
                    ex.getClass().getName(), sqlState, ex.getMessage());
            return errorView(MSG_INVALID_INPUT, null, HttpStatus.BAD_REQUEST);
        }
        log.error("Transaction failure (bridged from {}): {}",
                ex.getClass().getName(), ex.getMessage(), ex);
        return errorView("Unable to process the request due to a data error.", null,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Bridges any remaining Spring {@code org.springframework.dao.DataAccessException} not matched by
     * a more specific bridge handler above &mdash; the least-specific data-access catch-all, mapped
     * to base {@link FileStatusException} semantics. This is the online analogue of the COBOL
     * {@code READ-USER-SEC-FILE} generic {@code WHEN OTHER} branch (&ldquo;Unable to verify the
     * User&hellip;&rdquo;): an unexpected data-access status during a keyed read. Logged at
     * {@code ERROR} with the full stack trace.
     *
     * @param ex the unmatched framework data-access exception
     * @return the error screen with a generic message line and HTTP {@code 500 Internal Server Error}
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ModelAndView handleDataAccess(org.springframework.dao.DataAccessException ex) {
        String sqlState = sqlStateOf(ex);
        // A connection-class failure (SQLSTATE class 08, e.g. 08006 connection_failure / 08003 / 08001)
        // or a PostgreSQL operator-intervention shutdown (57P01 admin_shutdown, 57P02 crash_shutdown,
        // 57P03 cannot_connect_now) that reaches here wrapped in an UNCATEGORIZED DataAccessException -
        // typically Hibernate's JpaSystemException raised when an already-established pooled connection
        // is dropped abruptly - is a resource-unavailable condition, not a generic logic error. Map it
        // to FILE STATUS "93" / CICS NOTOPEN / HTTP 503 so the abrupt-loss path matches the orderly
        // DataAccessResourceFailureException path handled above (finding P4-ERR-01).
        if (isConnectionLoss(sqlState)) {
            ResourceUnavailable translated =
                    new ResourceUnavailable("Data service unavailable. Please retry.", ex);
            log.error("Data resource failure (bridged from {}, SQLSTATE {}) [FILE STATUS {}]: {}",
                    ex.getClass().getName(), sqlState, translated.getFileStatus(),
                    translated.getMessage(), ex);
            return errorView(translated.getMessage(), translated.getFileStatus(),
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        // An untranslatable-character violation (SQLSTATE 22021) is malformed input -> HTTP 400 Bad
        // Request, never a bare 500 (finding P13-INPUT-01, defence-in-depth).
        if (isUntranslatableCharacter(sqlState)) {
            log.warn("Invalid character in request (bridged from {}, SQLSTATE {}): {}",
                    ex.getClass().getName(), sqlState, ex.getMessage());
            return errorView(MSG_INVALID_INPUT, null, HttpStatus.BAD_REQUEST);
        }
        log.error("Unhandled data-access failure (bridged from {}): {}",
                ex.getClass().getName(), ex.getMessage(), ex);
        return errorView("Unable to process the request due to a data error.", null,
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Extracts the originating two-to-five-character SQLSTATE from a framework exception by walking its
     * cause chain (and, for {@link java.sql.SQLException}, its {@link java.sql.SQLException#getNextException()
     * next-exception} chain), returning the first non-blank SQLSTATE found.
     *
     * <p>Spring and Hibernate wrap the driver's {@code SQLException} several layers deep (for example
     * {@code JpaSystemException} &rarr; Hibernate {@code JDBCException} &rarr; {@code PSQLException}), so
     * the SQLSTATE that classifies the failure is not on the top-level exception. The traversal is a
     * cycle-safe depth-first search over both {@code getCause()} and {@code getNextException()} using an
     * identity-based visited set, so a self-referential or shared cause cannot loop.</p>
     *
     * @param ex the framework exception to inspect; may be {@code null}
     * @return the first non-blank SQLSTATE discovered in the cause/next chain, or {@code null} when none
     *         is present (for example a failure with no underlying {@code SQLException})
     */
    private static String sqlStateOf(Throwable ex) {
        java.util.Set<Throwable> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Deque<Throwable> stack = new java.util.ArrayDeque<>();
        if (ex != null) {
            stack.push(ex);
        }
        while (!stack.isEmpty()) {
            Throwable current = stack.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (current instanceof java.sql.SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state;
                }
                java.sql.SQLException next = sqlException.getNextException();
                if (next != null) {
                    stack.push(next);
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                stack.push(cause);
            }
        }
        return null;
    }

    /**
     * Reports whether a SQLSTATE denotes a lost or unobtainable database connection: SQLSTATE class
     * {@code 08} (<em>Connection Exception</em>, e.g. {@code 08006} connection_failure, {@code 08003},
     * {@code 08001}, {@code 08004}) or the PostgreSQL class {@code 57} operator-intervention shutdown
     * codes {@code 57P01} (admin_shutdown), {@code 57P02} (crash_shutdown), and {@code 57P03}
     * (cannot_connect_now). These map to CardDemo FILE STATUS {@code "93"} / HTTP {@code 503}.
     *
     * @param sqlState the SQLSTATE to classify; may be {@code null}
     * @return {@code true} when {@code sqlState} denotes a connection-loss/unavailable condition
     */
    private static boolean isConnectionLoss(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return false;
        }
        if (sqlState.startsWith("08")) {
            return true;
        }
        return "57P01".equals(sqlState) || "57P02".equals(sqlState) || "57P03".equals(sqlState);
    }

    /**
     * Reports whether a SQLSTATE denotes an untranslatable character in the request &mdash; SQLSTATE
     * {@code 22021} (<em>character_not_in_repertoire</em>), raised for example when a bound parameter
     * contains an embedded NUL (COBOL {@code LOW-VALUES}) that a PostgreSQL text column cannot store.
     * This is malformed input and maps to HTTP {@code 400}, never to a duplicate-key {@code 409} or a
     * generic {@code 500}.
     *
     * @param sqlState the SQLSTATE to classify; may be {@code null}
     * @return {@code true} when {@code sqlState} is {@code "22021"}
     */
    private static boolean isUntranslatableCharacter(String sqlState) {
        return "22021".equals(sqlState);
    }

    /**
     * Builds the shared error {@link ModelAndView}: it selects the {@value #ERROR_VIEW} view, sets
     * the {@value #ATTR_ERROR_MESSAGE} line (the BMS {@code ERRMSGO} analogue), optionally sets the
     * {@value #ATTR_FILE_STATUS} code and always sets the {@value #ATTR_TIMESTAMP}, and records the
     * supplied HTTP status. Reused by every handler so the rendered outcome is identical regardless
     * of which exception triggered it.
     *
     * @param message    the concise on-screen error line (kept within the BMS 78-character width)
     * @param fileStatus the originating two-character FILE STATUS code, or {@code null} when there is
     *                   no mapped code (the attribute is then omitted)
     * @param status     the HTTP status to record on the response
     * @return the populated {@link ModelAndView}
     */
    private ModelAndView errorView(String message, String fileStatus, HttpStatus status) {
        ModelAndView mav = new ModelAndView(ERROR_VIEW);
        mav.addObject(ATTR_ERROR_MESSAGE, message);
        if (fileStatus != null && !fileStatus.isBlank()) {
            mav.addObject(ATTR_FILE_STATUS, fileStatus);
        }
        mav.addObject(ATTR_TIMESTAMP, java.time.Instant.now());
        mav.setStatus(status);
        return mav;
    }
}
