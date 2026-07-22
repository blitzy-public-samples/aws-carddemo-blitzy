package com.aws.carddemo.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Unit tests for {@link GlobalExceptionHandler}, the {@code @ControllerAdvice} that maps the
 * migrated AWS CardDemo FILE STATUS / CICS {@code EIBRESP} typed-exception hierarchy to HTTP
 * responses rendering the BMS-equivalent error line, and that bridges Spring's
 * {@code org.springframework.dao.DataAccessException} subclasses and JPA's
 * {@code jakarta.persistence.EntityNotFoundException} into the same behaviour.
 *
 * <p>These tests assert only the <em>observable</em> outcome of each {@code @ExceptionHandler}: the
 * HTTP status, the logical view name ({@code "error"}), and the presence of the
 * {@code "errorMessage"} model attribute (the BMS {@code ERRMSGO} {@code PIC X(78)} analogue). They
 * never assert on the internal mechanism (whether the status is carried by
 * {@code ModelAndView.setStatus(...)} or a {@code @ResponseStatus}), so the contract stays stable
 * across refactors of the handler internals.</p>
 *
 * <p>This is a <strong>pure Surefire unit test</strong> (named {@code *Test}). It uses
 * {@link MockMvcBuilders#standaloneSetup(Object...)} with a tiny throwaway {@link BoomController}
 * and the real {@link GlobalExceptionHandler} registered as controller advice. It starts
 * <em>no</em> Spring {@code ApplicationContext}, is not a {@code @SpringBootTest}, and touches no
 * database, Docker, or Testcontainers. It intentionally does not extend any Postgres integration
 * base class and imports no application configuration ({@code SecurityConfig}, {@code WebConfig},
 * and so on).</p>
 *
 * <p>The test lives in package {@code com.aws.carddemo.exception} on purpose: the simple names
 * {@code DuplicateKeyException} and {@code RecordNotFoundException} resolve to <em>our</em> classes,
 * so Spring's and JPA's colliding types are referenced exclusively by fully-qualified name and are
 * never imported. Two dedicated tests lock down that intentional collision.</p>
 *
 * <p>The end-of-file signal ({@link EndOfFileException}, FILE STATUS {@code '10'}) is verified to be
 * treated as an informational {@code 200 OK} outcome rather than an error, mirroring the legacy
 * behaviour where reaching the end of a browse or read loop is a normal, non-failing event.</p>
 *
 * <p>Origin oracle: legacy/cbl/COSGN00C.cbl READ-USER-SEC-FILE (EVALUATE WS-RESP-CD &rarr; ERRMSGO
 * online error line); legacy/cbl/CBTRN02C.cbl 1000-DALYTRAN-GET-NEXT FILE STATUS EVALUATE +
 * 2500-WRITE-REJECT-REC batch reject policy. See Technical Specification &sect;0.6.5.</p>
 */
class GlobalExceptionHandlerTest {

    /**
     * Standalone {@link MockMvc} wired to the throwaway {@link BoomController} and the real
     * {@link GlobalExceptionHandler} advice. Rebuilt fresh before every test.
     */
    private MockMvc mockMvc;

    /**
     * Builds the standalone {@link MockMvc} before each test: a single throwaway controller plus the
     * production {@link GlobalExceptionHandler} registered as {@code @ControllerAdvice}.
     *
     * <p>The default {@code InternalResourceViewResolver} added by {@code standaloneSetup} resolves
     * the {@code "error"} view to a forward that differs from the {@code /boom/...} request URI, so
     * there is no circular-view-path error and no JSP is executed. The status carried on the
     * returned {@code ModelAndView} is applied by {@code DispatcherServlet.render()}, so the
     * {@code status()} matchers below observe the mapped HTTP code.</p>
     */
    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * Throwaway controller with a single endpoint that throws a selected exception on demand,
     * exercising every {@code @ExceptionHandler} in {@link GlobalExceptionHandler}.
     *
     * <p>Our typed exceptions are thrown by their simple names, which resolve to the
     * {@code com.aws.carddemo.exception} classes (same package, no import). The colliding Spring and
     * JPA types are thrown by fully-qualified name so the test proves the handler distinguishes them
     * from our identically named types. The method returns {@code void} and every branch throws, so
     * no view is ever resolved from the mapping itself.</p>
     */
    @Controller
    static class BoomController {

        /**
         * Throws the exception selected by the {@code kind} path variable.
         *
         * @param kind selector for the exception to throw
         */
        @GetMapping("/boom/{kind}")
        void boom(@PathVariable("kind") String kind) {
            switch (kind) {
                case "our-notfound"    -> throw new RecordNotFoundException("record not found");
                case "our-dup"         -> throw new DuplicateKeyException("duplicate key");
                case "our-unavailable" -> throw new ResourceUnavailable("resource unavailable");
                case "our-logic"       -> throw new LogicError("logic error");
                case "our-eof"         -> throw new EndOfFileException("end of file reached");
                case "our-base"        -> throw new FileStatusException("99", "generic file-status error");
                case "spring-empty"    -> throw new org.springframework.dao.EmptyResultDataAccessException(1);
                case "jpa-notfound"    -> throw new jakarta.persistence.EntityNotFoundException("entity not found");
                case "spring-dup"      -> throw new org.springframework.dao.DuplicateKeyException("spring duplicate key");
                case "spring-integrity" -> throw new org.springframework.dao.DataIntegrityViolationException("integrity violation");
                case "spring-resource" -> throw new org.springframework.dao.DataAccessResourceFailureException("resource failure");
                case "spring-tx-cannot-create" -> throw new org.springframework.transaction.CannotCreateTransactionException("Could not open JPA EntityManager for transaction");
                case "spring-generic"  -> throw new org.springframework.dao.QueryTimeoutException("query timeout");
                // Uncategorized data-access failure wrapping a driver SQLException with SQLSTATE 08006
                // (connection_failure) - the abrupt-connection-loss shape of Hibernate's JpaSystemException
                // that reaches the catch-all handler (finding P4-ERR-01).
                case "conn-loss-08" -> throw new org.springframework.jdbc.UncategorizedSQLException(
                        "read", "SELECT 1",
                        new java.sql.SQLException("connection failure", "08006"));
                // Uncategorized data-access failure wrapping SQLSTATE 57P01 (admin_shutdown).
                case "conn-loss-57p01" -> throw new org.springframework.jdbc.UncategorizedSQLException(
                        "read", "SELECT 1",
                        new java.sql.SQLException("terminating connection due to administrator command", "57P01"));
                // Data-integrity violation whose real cause is SQLSTATE 22021 (character_not_in_repertoire,
                // e.g. an embedded NUL) - must NOT be misclassified as a duplicate 409 (finding P13-INPUT-01).
                case "integrity-nul-22021" -> throw new org.springframework.dao.DataIntegrityViolationException(
                        "invalid byte sequence",
                        new java.sql.SQLException("invalid byte sequence for encoding UTF8: 0x00", "22021"));
                // Transaction-completion failures (the UnexpectedRollbackException escape, finding
                // P13-INPUT-01): classified by the wrapped SQLSTATE - 22021 -> 400, 08006 -> 503, none -> 500.
                case "tx-rollback-nul-22021" -> throw new org.springframework.transaction.UnexpectedRollbackException(
                        "Transaction silently rolled back because it has been marked as rollback-only",
                        new java.sql.SQLException("invalid byte sequence for encoding UTF8: 0x00", "22021"));
                case "tx-rollback-conn-08" -> throw new org.springframework.transaction.UnexpectedRollbackException(
                        "Transaction rolled back",
                        new java.sql.SQLException("connection failure", "08006"));
                case "tx-rollback-plain" -> throw new org.springframework.transaction.UnexpectedRollbackException(
                        "Transaction silently rolled back");
                default -> throw new IllegalArgumentException("unknown kind: " + kind);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Our typed FILE STATUS / CICS RESP exceptions -> HTTP status + shared "error" view.
    // ------------------------------------------------------------------------------------------

    /**
     * FILE STATUS {@code "23"} / CICS {@code NOTFND}: {@link RecordNotFoundException} renders the
     * error view with HTTP {@code 404 Not Found}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void recordNotFound_returns404() throws Exception {
        mockMvc.perform(get("/boom/our-notfound"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * FILE STATUS {@code "22"} / CICS {@code DUPREC}: our {@link DuplicateKeyException} renders the
     * error view with HTTP {@code 409 Conflict}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void duplicateKey_returns409() throws Exception {
        mockMvc.perform(get("/boom/our-dup"))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * FILE STATUS {@code "93"} / CICS {@code NOTOPEN}: {@link ResourceUnavailable} renders the error
     * view with HTTP {@code 503 Service Unavailable}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void resourceUnavailable_returns503() throws Exception {
        mockMvc.perform(get("/boom/our-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * FILE STATUS {@code "92"} (logic error, the COBOL generic {@code 9910-DISPLAY-IO-STATUS} +
     * {@code 9999-ABEND-PROGRAM} path): {@link LogicError} renders the error view with HTTP
     * {@code 500 Internal Server Error}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void logicError_returns500() throws Exception {
        mockMvc.perform(get("/boom/our-logic"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * FILE STATUS {@code "10"} (end-of-file, the {@code APPL-EOF} normal terminating condition of a
     * read loop in legacy/cbl/CBTRN02C.cbl 1000-DALYTRAN-GET-NEXT): {@link EndOfFileException} is
     * informational, so the handler renders the error view with HTTP {@code 200 OK} rather than a
     * failure status.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void endOfFile_returns200_informational() throws Exception {
        mockMvc.perform(get("/boom/our-eof"))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Base {@link FileStatusException} carrying an unmapped code (here {@code "99"}, the COBOL
     * generic {@code WHEN OTHER} branch): renders the error view with HTTP
     * {@code 500 Internal Server Error}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void fileStatusBase_returns500() throws Exception {
        mockMvc.perform(get("/boom/our-base"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    // ------------------------------------------------------------------------------------------
    // Spring DataAccessException / JPA bridge (referenced by fully-qualified name, never imported).
    // ------------------------------------------------------------------------------------------

    /**
     * Bridge: {@code org.springframework.dao.EmptyResultDataAccessException} is translated to
     * {@link RecordNotFoundException} (CICS {@code NOTFND} semantics) and renders the error view
     * with HTTP {@code 404 Not Found}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springEmptyResult_bridgesTo404() throws Exception {
        mockMvc.perform(get("/boom/spring-empty"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: {@code jakarta.persistence.EntityNotFoundException} is translated to
     * {@link RecordNotFoundException} (CICS {@code NOTFND} semantics) and renders the error view
     * with HTTP {@code 404 Not Found}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void jpaEntityNotFound_bridgesTo404() throws Exception {
        mockMvc.perform(get("/boom/jpa-notfound"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: {@code org.springframework.dao.DuplicateKeyException} (distinct from our identically
     * named type) is translated to our {@link DuplicateKeyException} (FILE STATUS {@code "22"} /
     * CICS {@code DUPREC}) and renders the error view with HTTP {@code 409 Conflict}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springDuplicateKey_bridgesTo409() throws Exception {
        mockMvc.perform(get("/boom/spring-dup"))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: {@code org.springframework.dao.DataIntegrityViolationException} is translated to our
     * {@link DuplicateKeyException} (FILE STATUS {@code "22"} / CICS {@code DUPREC}) and renders the
     * error view with HTTP {@code 409 Conflict}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springDataIntegrityViolation_bridgesTo409() throws Exception {
        mockMvc.perform(get("/boom/spring-integrity"))
                .andExpect(status().isConflict())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: {@code org.springframework.dao.DataAccessResourceFailureException} is translated to
     * {@link ResourceUnavailable} (FILE STATUS {@code "93"} / CICS {@code NOTOPEN}) and renders the
     * error view with HTTP {@code 503 Service Unavailable}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springResourceFailure_bridgesTo503() throws Exception {
        mockMvc.perform(get("/boom/spring-resource"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: {@code org.springframework.transaction.CannotCreateTransactionException} &mdash; the
     * transaction-manager failure raised when a connection cannot be borrowed to <em>begin</em> a
     * transaction (the synchronous batch-launch analogue of the read path's connection failure)
     * &mdash; is translated to {@link ResourceUnavailable} (FILE STATUS {@code "93"} / CICS
     * {@code NOTOPEN}) and renders the error view with HTTP {@code 503 Service Unavailable}, so a
     * database outage surfaces identically whether it strikes a read path or a batch launch.
     *
     * <p>Regression guard for the batch-launch DB-outage finding: {@code CannotCreateTransactionException}
     * extends {@code org.springframework.transaction.TransactionException}, not
     * {@code org.springframework.dao.DataAccessException}, so before the dedicated handler was added it
     * escaped every data-access bridge and fell through to a generic {@code 500}. This test locks the
     * mapped {@code 503} in place. See Technical Specification &sect;0.6.5.</p>
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springCannotCreateTransaction_bridgesTo503() throws Exception {
        mockMvc.perform(get("/boom/spring-tx-cannot-create"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Bridge: a generic {@code org.springframework.dao.DataAccessException} subtype that is not one
     * of the specifically handled types &mdash; here {@code QueryTimeoutException} &mdash; resolves
     * to the least-specific data-access handler and renders the error view with HTTP
     * {@code 500 Internal Server Error}. This deliberately avoids depending on any optional
     * {@code @ExceptionHandler(Exception.class)} catch-all, which may be omitted in production.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void springGenericDataAccess_bridgesTo500() throws Exception {
        mockMvc.perform(get("/boom/spring-generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    // ------------------------------------------------------------------------------------------
    // SQLSTATE-driven classification (findings P4-ERR-01, P13-INPUT-01).
    // ------------------------------------------------------------------------------------------

    /**
     * Regression guard for finding P4-ERR-01: an uncategorized {@code DataAccessException} whose
     * driver cause carries SQLSTATE {@code 08006} (connection_failure) &mdash; the shape of Hibernate's
     * {@code JpaSystemException} when an already-established pooled connection is dropped abruptly
     * &mdash; is classified as resource-unavailable and renders the error view with HTTP
     * {@code 503 Service Unavailable}, matching the orderly {@code DataAccessResourceFailureException}
     * path rather than falling through to a generic {@code 500}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void connectionLoss08_bridgesTo503() throws Exception {
        mockMvc.perform(get("/boom/conn-loss-08"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Companion to {@link #connectionLoss08_bridgesTo503()}: a PostgreSQL operator-intervention
     * shutdown (SQLSTATE {@code 57P01} admin_shutdown) wrapped in an uncategorized
     * {@code DataAccessException} is likewise mapped to HTTP {@code 503 Service Unavailable}
     * (finding P4-ERR-01).
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void adminShutdown57P01_bridgesTo503() throws Exception {
        mockMvc.perform(get("/boom/conn-loss-57p01"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Regression guard for finding P13-INPUT-01: a {@code DataIntegrityViolationException} whose real
     * cause is SQLSTATE {@code 22021} (character_not_in_repertoire &mdash; e.g. an embedded NUL) is
     * classified as malformed input and renders the error view with HTTP {@code 400 Bad Request},
     * <strong>not</strong> the misleading duplicate-key {@code 409 Conflict} the blanket
     * data-integrity mapping would otherwise produce. No FILE STATUS is set (this is not a mapped
     * VSAM/CICS status), so the {@code fileStatus} attribute is absent.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void integrityNul22021_bridgesTo400_notDuplicate() throws Exception {
        mockMvc.perform(get("/boom/integrity-nul-22021"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"))
                .andExpect(model().attributeDoesNotExist("fileStatus"));
    }

    /**
     * Regression guard for finding P13-INPUT-01 (the {@code UnexpectedRollbackException} escape): a
     * transaction-completion failure whose cause is SQLSTATE {@code 22021} is classified as malformed
     * input and renders HTTP {@code 400 Bad Request} rather than escaping as a bare
     * {@code 500 Internal Server Error}.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void txRollbackNul22021_bridgesTo400() throws Exception {
        mockMvc.perform(get("/boom/tx-rollback-nul-22021"))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * Companion to {@link #txRollbackNul22021_bridgesTo400()}: a transaction-completion failure whose
     * cause is a connection-loss SQLSTATE ({@code 08006}) is mapped to HTTP {@code 503 Service
     * Unavailable} (finding P13-INPUT-01 / P4-ERR-01).
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void txRollbackConnectionLoss_bridgesTo503() throws Exception {
        mockMvc.perform(get("/boom/tx-rollback-conn-08"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    /**
     * A transaction-completion failure with no discoverable SQLSTATE falls back to a sanitized HTTP
     * {@code 500 Internal Server Error} &mdash; still a controlled error-view render, never an
     * unhandled container stack trace (finding P13-INPUT-01).
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void txRollbackNoSqlState_bridgesTo500() throws Exception {
        mockMvc.perform(get("/boom/tx-rollback-plain"))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    // ------------------------------------------------------------------------------------------
    // Intentional fully-qualified-name naming-collision distinctness.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves our {@code com.aws.carddemo.exception.DuplicateKeyException} is a distinct type from
     * Spring's {@code org.springframework.dao.DuplicateKeyException}, yet both are routed to HTTP
     * {@code 409 Conflict} by their own dedicated handlers. The simple name in this test resolves to
     * our type; Spring's type is named only by fully-qualified name.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void ourDuplicateKeyIsDistinctFromSpring() throws Exception {
        assertThat(DuplicateKeyException.class)
                .isNotEqualTo(org.springframework.dao.DuplicateKeyException.class);
        assertThat(DuplicateKeyException.class.getName())
                .isEqualTo("com.aws.carddemo.exception.DuplicateKeyException");
        assertThat(org.springframework.dao.DuplicateKeyException.class.getName())
                .isEqualTo("org.springframework.dao.DuplicateKeyException");

        // Both distinct types nonetheless converge on 409 via distinct @ExceptionHandler methods.
        mockMvc.perform(get("/boom/our-dup")).andExpect(status().isConflict());
        mockMvc.perform(get("/boom/spring-dup")).andExpect(status().isConflict());
    }

    /**
     * Proves our {@code com.aws.carddemo.exception.RecordNotFoundException} is distinct from both
     * Spring's {@code org.springframework.dao.EmptyResultDataAccessException} and JPA's
     * {@code jakarta.persistence.EntityNotFoundException}, yet all three are routed to HTTP
     * {@code 404 Not Found}. The simple name resolves to our type; the framework types are named
     * only by fully-qualified name.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void ourRecordNotFoundIsDistinctFromSpringAndJpa() throws Exception {
        assertThat(RecordNotFoundException.class)
                .isNotEqualTo(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(RecordNotFoundException.class)
                .isNotEqualTo(jakarta.persistence.EntityNotFoundException.class);

        // Our miss and both bridged framework misses converge on 404.
        mockMvc.perform(get("/boom/our-notfound")).andExpect(status().isNotFound());
        mockMvc.perform(get("/boom/spring-empty")).andExpect(status().isNotFound());
        mockMvc.perform(get("/boom/jpa-notfound")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------
    // CICS RESP condition bridge (documentary), legacy/cbl/COSGN00C.cbl EVALUATE WS-RESP-CD.
    // ------------------------------------------------------------------------------------------

    /**
     * Documents and exercises the CICS {@code EIBRESP}/{@code RESP} conditions from
     * legacy/cbl/COSGN00C.cbl READ-USER-SEC-FILE ({@code EVALUATE WS-RESP-CD}) mapping onto the same
     * typed hierarchy and HTTP contract as the FILE STATUS codes. See Technical Specification
     * &sect;0.6.5.
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void cicsResponseConditionsBridgeToSameHierarchy() throws Exception {
        // CICS NOTFND (WS-RESP-CD 13, "User not found. Try again ...") -> RecordNotFoundException -> 404.
        mockMvc.perform(get("/boom/our-notfound")).andExpect(status().isNotFound());
        // CICS DUPREC -> DuplicateKeyException -> 409.
        mockMvc.perform(get("/boom/our-dup")).andExpect(status().isConflict());
        // CICS NOTOPEN -> ResourceUnavailable -> 503.
        mockMvc.perform(get("/boom/our-unavailable")).andExpect(status().isServiceUnavailable());
        // CICS NORMAL (WS-RESP-CD 0) -> no exception thrown; nothing to route here (documented only).
    }

    // ------------------------------------------------------------------------------------------
    // Response is a server-rendered view (ModelAndView), not a JSON @ResponseBody payload.
    // ------------------------------------------------------------------------------------------

    /**
     * Proves the handler renders a server-side view (the BMS-equivalent error line) rather than
     * serializing a JSON body: the result carries a {@code ModelAndView} whose view name is
     * {@code "error"} and whose model holds the {@code "errorMessage"} line, plus the originating
     * {@code "fileStatus"} code ({@code "23"} for a record-not-found).
     *
     * @throws Exception if the mock request cannot be performed
     */
    @Test
    void responseIsModelAndViewNotJson() throws Exception {
        var result = mockMvc.perform(get("/boom/our-notfound"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andExpect(model().attributeExists("errorMessage"))
                .andExpect(model().attributeExists("fileStatus"))
                .andReturn();

        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("error");
    }
}
