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
                case "spring-generic"  -> throw new org.springframework.dao.QueryTimeoutException("query timeout");
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
