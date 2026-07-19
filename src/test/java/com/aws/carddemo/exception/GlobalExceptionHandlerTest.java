/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * Plain (no Spring context) unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>{@code GlobalExceptionHandler} is the only Spring-annotated class in the
 * {@code com.aws.carddemo.exception} package, but it carries no injected
 * collaborators, so it is exercised here as an ordinary POJO: the handler is
 * instantiated with {@code new GlobalExceptionHandler()} and each
 * {@code @ExceptionHandler} method is invoked directly as a plain Java call,
 * asserting on the returned RFC&nbsp;7807 {@link ProblemDetail}. No
 * {@code @SpringBootTest}, {@code @WebMvcTest}, {@code MockMvc}, or
 * {@code SpringExtension} is used; only the hard-to-construct framework types
 * ({@link MethodArgumentNotValidException}, {@link BindingResult},
 * {@link ConstraintViolation}, {@link Path}) are mocked with Mockito. Our own
 * exception types live in this same package and are therefore referenced
 * without an import and constructed for real.</p>
 *
 * <p>This advice is the online counterpart of the COBOL
 * {@code EVALUATE WS-RESP-CD} / {@code EVALUATE <file>-STATUS} error handling.
 * The behaviours asserted here preserve those caller-visible outcomes:</p>
 * <ul>
 *   <li>Not found &rarr; 404 &mdash; CICS {@code DFHRESP(NOTFND)} /
 *       {@code FILE STATUS '23'} (e.g. {@code legacy/cbl/COTRN01C.cbl}
 *       L280-289 "Transaction ID NOT found...").</li>
 *   <li>Duplicate key &rarr; 409 &mdash; CICS {@code DFHRESP(DUPKEY)}/
 *       {@code DFHRESP(DUPREC)} / {@code FILE STATUS '22'} (e.g.
 *       {@code legacy/cbl/COUSR01C.cbl} L260-266 "User ID already exist...").</li>
 *   <li>Optimistic lock &rarr; 409 &mdash; the COBOL READ-UPDATE-REWRITE cycle
 *       re-expressed as JPA {@code @Version} optimistic locking (AAP
 *       &sect;0.7.1 H6, a documented integrity improvement).</li>
 *   <li>Bean Validation &rarr; 400 &mdash; the COBOL edit/validation
 *       paragraphs re-expressed as Bean Validation on the request DTO.</li>
 *   <li>Base {@code FileStatusException} / any unknown exception &rarr; 500
 *       &mdash; the COBOL {@code 9999-ABEND-PROGRAM} path.</li>
 * </ul>
 *
 * <p>The batch return-code parity (RC&nbsp;0 clean; RC&nbsp;4 when
 * {@code legacy/cbl/CBTRN02C.cbl} L229-230
 * {@code IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE}; RC&nbsp;8 on abend) is
 * asserted by the {@code com.aws.carddemo.batch} tests, not here.</p>
 *
 * <p><strong>Security is central to these tests (AAP &sect;0.7.3, &sect;0.9.3).</strong>
 * A validation failure must surface the offending field name and constraint
 * message but never the rejected/invalid value (which could be a password or a
 * card CVV), and server-error details must be fixed, generic strings that never
 * echo a raw exception message. The sensitive fixture values used below are
 * asserted to be <em>absent</em> from the response body and are never logged.</p>
 */
class GlobalExceptionHandlerTest {

    /**
     * MDC key under which the observability {@code CorrelationIdFilter} stores the
     * per-request correlation ID; kept in sync with the production handler's
     * private constant of the same value.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * The fixed, non-revealing detail the production handler returns for an
     * optimistic-lock conflict. Duplicated here as the expected value so the test
     * fails if the production message ever changes to something that could leak
     * provider internals.
     */
    private static final String OPTIMISTIC_LOCK_DETAIL =
            "The record was updated by another transaction; please retry.";

    /** The system under test, instantiated as a plain POJO (no Spring context). */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * Clears the SLF4J {@link MDC} before every test so each case starts with no
     * correlation ID, regardless of test ordering or thread reuse across classes.
     */
    @BeforeEach
    void clearMdcBefore() {
        MDC.clear();
    }

    /**
     * Clears the SLF4J {@link MDC} after every test so a correlation ID set by one
     * test never leaks into another test (the MDC is thread-local and Surefire may
     * reuse the executing thread).
     */
    @AfterEach
    void clearMdcAfter() {
        MDC.clear();
    }

    // ---------------------------------------------------------------------
    // 5A. Typed-exception -> status (direct, no mocks)
    // ---------------------------------------------------------------------

    /**
     * A {@link RecordNotFoundException} maps to HTTP 404 and safely echoes its
     * (non-sensitive) message &mdash; parity with CICS {@code DFHRESP(NOTFND)} in
     * {@code legacy/cbl/COTRN01C.cbl}.
     */
    @Test
    void recordNotFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(
                new RecordNotFoundException("Account not found: 1"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Record Not Found");
        assertThat(problem.getDetail()).contains("Account not found: 1");
    }

    /**
     * A {@link DuplicateKeyException} maps to HTTP 409 and safely echoes its
     * (non-sensitive) natural key &mdash; parity with CICS
     * {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)} in
     * {@code legacy/cbl/COUSR01C.cbl}. Note: this is the CardDemo domain
     * {@code DuplicateKeyException} (same package, no import), never Spring Data's
     * {@code org.springframework.dao.DuplicateKeyException}.
     */
    @Test
    void duplicateKeyMapsTo409() {
        ProblemDetail problem = handler.handleDuplicate(
                new DuplicateKeyException("User already exists: USR01"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Duplicate Record");
        assertThat(problem.getDetail()).contains("User already exists: USR01");
    }

    /**
     * The base {@link FileStatusException} (any status not modelled by a more
     * specific subclass) maps to HTTP 500 with a fixed, generic detail &mdash; the
     * COBOL {@code 9999-ABEND-PROGRAM} path. The raw {@code FILE STATUS} code and
     * the internal message must not leak to the client.
     */
    @Test
    void baseFileStatusMapsTo500AndDoesNotLeak() {
        ProblemDetail problem = handler.handleFileStatus(
                new FileStatusException("35", "VSAM open failed on ACCTFILE /secret/path"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Data Access Error");
        assertThat(problem.getDetail()).isEqualTo("A data access error occurred.");
        // No internal leak: neither the raw status code, the internal path, nor
        // the raw message may appear in the client-visible detail.
        assertThat(problem.getDetail())
                .doesNotContain("35")
                .doesNotContain("secret")
                .doesNotContain("VSAM open failed on ACCTFILE /secret/path");
    }

    /**
     * Any unanticipated exception falls through to the catch-all and maps to HTTP
     * 500 with a fixed, generic detail. The raw exception message (which could
     * contain internal or sensitive detail) must not be echoed to the client.
     */
    @Test
    void unexpectedExceptionMapsTo500AndDoesNotLeak() {
        ProblemDetail problem = handler.handleUnexpected(
                new RuntimeException("NPE at com.internal.Secret line 42"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.getDetail())
                .doesNotContain("Secret")
                .doesNotContain("line 42");
    }

    // ---------------------------------------------------------------------
    // 5B. Optimistic-lock -> 409 (both types), fixed safe message
    // ---------------------------------------------------------------------

    /**
     * Spring Data's {@link OptimisticLockingFailureException} maps to HTTP 409 with
     * the fixed, safe detail; the raw provider message (entity/id internals) must
     * not be echoed.
     */
    @Test
    void springOptimisticLockMapsTo409() {
        ProblemDetail problem = handler.handleOptimisticLock(
                new OptimisticLockingFailureException("row was updated by tx 7 on ACCOUNT id=1"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Concurrent Update Conflict");
        assertThat(problem.getDetail()).isEqualTo(OPTIMISTIC_LOCK_DETAIL);
        assertThat(problem.getDetail())
                .doesNotContain("tx 7")
                .doesNotContain("ACCOUNT id=1");
    }

    /**
     * The raw JPA {@link OptimisticLockException} (should it escape without
     * translation) is handled by the same combined handler and maps to HTTP 409
     * with the identical fixed, safe detail; the raw message must not be echoed.
     */
    @Test
    void jakartaOptimisticLockMapsTo409() {
        ProblemDetail problem = handler.handleOptimisticLock(
                new OptimisticLockException("entity Account"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Concurrent Update Conflict");
        assertThat(problem.getDetail()).isEqualTo(OPTIMISTIC_LOCK_DETAIL);
        assertThat(problem.getDetail()).doesNotContain("entity Account");
    }

    /**
     * A database lock-acquisition failure &mdash; the translation of a PostgreSQL
     * deadlock ({@code SQLSTATE 40P01}) when two confirmed updates race for the same
     * rows &mdash; surfaces as Spring's {@link CannotAcquireLockException}. Before QA
     * finding F-CAUP-3 this type had no handler and fell through to a generic HTTP 500;
     * it is now caught by the same handler through its broad root
     * {@link ConcurrencyFailureException} and maps to HTTP 409 with the identical
     * fixed, safe detail. The routing guarantee is asserted structurally
     * ({@code CannotAcquireLockException} IS-A {@code ConcurrencyFailureException}, the
     * type listed on the {@code @ExceptionHandler}) and the mapping is asserted on the
     * handler result; the end-to-end dispatch is exercised by the runtime API check.
     */
    @Test
    void cannotAcquireLockMapsTo409() {
        // Structural routing guarantee: the handler lists ConcurrencyFailureException.class,
        // so every subtype (including CannotAcquireLockException) is dispatched to it.
        assertThat(ConcurrencyFailureException.class)
                .isAssignableFrom(CannotAcquireLockException.class);

        ProblemDetail problem = handler.handleOptimisticLock(
                new CannotAcquireLockException("deadlock detected; SQLSTATE 40P01 on ACCOUNT id=1"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Concurrent Update Conflict");
        assertThat(problem.getDetail()).isEqualTo(OPTIMISTIC_LOCK_DETAIL);
        assertThat(problem.getDetail())
                .doesNotContain("40P01")
                .doesNotContain("ACCOUNT id=1");
    }

    // ---------------------------------------------------------------------
    // 5C. Bean Validation -> 400 with NO value leakage (the key security tests)
    // ---------------------------------------------------------------------

    /**
     * A request-body validation failure maps to HTTP 400 and surfaces only the
     * offending field name and constraint message. The {@link FieldError} below
     * genuinely carries a sensitive rejected value (a password) as its third
     * constructor argument; the handler must never place that value in the
     * response body. This is the executable proof of AAP &sect;0.9.3 for
     * passwords.
     */
    @Test
    void methodArgumentNotValidMapsTo400WithoutRejectedValue() {
        FieldError fieldError = new FieldError(
                "userRequest", "password", "S3cr3tP@ss",
                false, null, null, "must not be blank");
        MethodArgumentNotValidException ex = Mockito.mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = Mockito.mock(BindingResult.class);
        Mockito.when(ex.getBindingResult()).thenReturn(bindingResult);
        Mockito.when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Validation Failed");
        assertThat(problem.getDetail())
                .contains("password")
                .contains("must not be blank");
        // SECURITY: the rejected password value must be absent from the payload.
        assertThat(problem.getDetail()).doesNotContain("S3cr3tP@ss");
    }

    /**
     * A path/query-parameter validation failure maps to HTTP 400 and surfaces only
     * the property path and constraint message. The mocked violation is
     * deliberately configured to expose a (fake) card CVV via
     * {@code getInvalidValue()} to prove that value is still never echoed. This is
     * the executable proof of AAP &sect;0.9.3 for card CVVs.
     */
    @Test
    void constraintViolationMapsTo400WithoutInvalidValue() {
        Path propertyPath = Mockito.mock(Path.class);
        Mockito.when(propertyPath.toString()).thenReturn("cardCvv");
        ConstraintViolation<?> violation = Mockito.mock(ConstraintViolation.class);
        Mockito.when(violation.getPropertyPath()).thenReturn(propertyPath);
        Mockito.when(violation.getMessage()).thenReturn("must be 3 digits");
        // Configured but must never surface: the handler must ignore the value.
        Mockito.when(violation.getInvalidValue()).thenReturn("999");
        ConstraintViolationException ex =
                new ConstraintViolationException("validation failed", Set.of(violation));

        ProblemDetail problem = handler.handleConstraint(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Validation Failed");
        assertThat(problem.getDetail())
                .contains("cardCvv")
                .contains("must be 3 digits");
        // SECURITY: the invalid CVV value must be absent from the payload.
        assertThat(problem.getDetail()).doesNotContain("999");
    }

    // ---------------------------------------------------------------------
    // 5D. Resolution-precedence sanity
    // ---------------------------------------------------------------------

    /**
     * {@link RecordNotFoundException} and {@link DuplicateKeyException} both extend
     * {@link FileStatusException}, yet the dedicated handlers must yield 404/409 and
     * never be downgraded to the base 500 handler. In this pure unit test the
     * precedence is demonstrated by invoking the specific handlers directly (full
     * Spring most-specific-handler resolution is covered by web-layer integration
     * tests, not here).
     */
    @Test
    void subclassHandlersTakePrecedenceOverBase() {
        RecordNotFoundException notFound = new RecordNotFoundException("Account not found: 42");
        DuplicateKeyException duplicate = new DuplicateKeyException("User already exists: USR07");

        // Both are, by inheritance, FileStatusException instances that the base
        // handler would otherwise map to 500.
        assertThat(notFound).isInstanceOf(FileStatusException.class);
        assertThat(duplicate).isInstanceOf(FileStatusException.class);

        assertThat(handler.handleNotFound(notFound).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(handler.handleDuplicate(duplicate).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }

    // ---------------------------------------------------------------------
    // Framework client-error handlers (preserve QA finding F-1 coverage)
    // ---------------------------------------------------------------------

    /**
     * Spring MVC's {@link NoResourceFoundException} ("no handler/static resource
     * for path") maps to HTTP 404 rather than being swallowed by the
     * {@code Exception} catch-all as a 500, so benign not-found traffic does not
     * pollute the server-error metric/alerting.
     */
    @Test
    void mapsNoResourceFoundTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "no-such-thing");

        ProblemDetail problem = handler.handleNoResourceFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getDetail()).isEqualTo("The requested resource was not found.");
    }

    /**
     * Spring Security's {@link RequestRejectedException} (a {@code StrictHttpFirewall}
     * rejection) maps to HTTP 400 and returns a fixed, generic detail. The
     * untrusted, client-supplied value embedded in the exception message must
     * never be echoed back to the client.
     */
    @Test
    void mapsRequestRejectedTo400() {
        RequestRejectedException ex = new RequestRejectedException(
                "The request was rejected because the header has a value \"<untrusted>\" that is not allowed.");

        ProblemDetail problem = handler.handleRequestRejected(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("The request was rejected as malformed.");
        assertThat(problem.getDetail()).doesNotContain("<untrusted>");
    }

    /**
     * An unsupported HTTP method maps to HTTP 405 (rather than a catch-all 500) and
     * the response carries the mandatory {@code Allow} header listing the methods the
     * route supports (RFC&nbsp;7231&nbsp;&sect;6.5.5). The body is the uniform
     * RFC&nbsp;7807 {@link ProblemDetail} served as {@code application/problem+json}.
     * This is the executable proof that {@link HttpRequestMethodNotSupportedException}
     * is no longer swallowed by the {@code Exception} catch-all (QA finding F3).
     */
    @Test
    void mapsMethodNotSupportedTo405WithAllowHeader() {
        HttpRequestMethodNotSupportedException ex =
                Mockito.mock(HttpRequestMethodNotSupportedException.class);
        Mockito.when(ex.getSupportedHttpMethods())
                .thenReturn(Set.of(HttpMethod.GET, HttpMethod.POST));

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getAllow())
                .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        ProblemDetail problem = response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(problem.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(problem.getDetail()).isEqualTo("The HTTP method is not supported for this endpoint.");
    }

    /**
     * When the framework reports no supported methods (a {@code null} set), the 405
     * handler still succeeds and simply omits the {@code Allow} header rather than
     * throwing &mdash; a defensive path that keeps the handler total.
     */
    @Test
    void methodNotSupportedWithNoSupportedMethodsOmitsAllowHeader() {
        HttpRequestMethodNotSupportedException ex =
                Mockito.mock(HttpRequestMethodNotSupportedException.class);
        Mockito.when(ex.getSupportedHttpMethods()).thenReturn(null);

        ResponseEntity<ProblemDetail> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(response.getHeaders().getAllow()).isEmpty();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    }

    /**
     * A malformed/unreadable (or missing) request body maps to HTTP 400 with a fixed,
     * non-revealing detail. The raw parser message can quote payload fragments (a
     * password or card CVV), so it is never echoed &mdash; only the safe constant is
     * returned (QA finding F3).
     */
    @Test
    void mapsMessageNotReadableTo400() {
        HttpMessageNotReadableException ex = Mockito.mock(HttpMessageNotReadableException.class);

        ProblemDetail problem = handler.handleNotReadable(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Malformed Request");
        assertThat(problem.getDetail()).isEqualTo("The request body is missing or malformed.");
    }

    /**
     * An unsupported request {@code Content-Type} maps to HTTP 415 with a fixed,
     * non-revealing detail (the offending content type is not echoed). This is the
     * executable proof that {@link HttpMediaTypeNotSupportedException} is no longer
     * swallowed by the {@code Exception} catch-all (QA finding F3).
     */
    @Test
    void mapsMediaTypeNotSupportedTo415() {
        HttpMediaTypeNotSupportedException ex = Mockito.mock(HttpMediaTypeNotSupportedException.class);

        ProblemDetail problem = handler.handleMediaTypeNotSupported(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(problem.getTitle()).isEqualTo("Unsupported Media Type");
        assertThat(problem.getDetail())
                .isEqualTo("The request Content-Type is not supported by this endpoint.");
    }

    // ---------------------------------------------------------------------
    // Correlation-ID propagation via the shared problem() builder
    // ---------------------------------------------------------------------

    /**
     * When a per-request correlation ID is present in the SLF4J {@link MDC}, the
     * handler attaches it to the {@link ProblemDetail} as an extension property so
     * a client can quote it when reporting a problem.
     */
    @Test
    void attachesCorrelationIdWhenPresentInMdc() {
        MDC.put(CORRELATION_ID_MDC_KEY, "CID-TEST-001");

        ProblemDetail problem = handler.handleNotFound(
                new RecordNotFoundException("Account not found: 7"));

        assertThat(problem.getProperties()).containsEntry(CORRELATION_ID_MDC_KEY, "CID-TEST-001");
    }

    /**
     * When no correlation ID is present in the {@link MDC}, the handler must not
     * attach the extension property (the properties map is simply left without
     * that key, and may be {@code null} when no property was ever set).
     */
    @Test
    void omitsCorrelationIdWhenAbsentFromMdc() {
        ProblemDetail problem = handler.handleRequestRejected(
                new RequestRejectedException("rejected"));

        assertThat(problem.getProperties() == null
                || !problem.getProperties().containsKey(CORRELATION_ID_MDC_KEY)).isTrue();
    }

    // ---------------------------------------------------------------------
    // Data-integrity (foreign-key / unique) and parameter type-mismatch mapping
    // (QA findings F5 and F12 — same "unmapped exception -> generic 500" family)
    // ---------------------------------------------------------------------

    /**
     * A foreign-key (or check / not-null) integrity violation maps to HTTP 400
     * rather than the catch-all 500. This is the executable proof for QA finding
     * F5: adding a transaction whose {@code type_cd}/{@code cat_cd} is absent from
     * the reference tables trips {@code fk_transaction_type} /
     * {@code fk_transaction_category}, and the caller must see a typed client error.
     * The constraint name / SQL text is logged server-side only and never leaks
     * into the response body.
     */
    @Test
    void mapsForeignKeyDataIntegrityViolationTo400() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "insert or update on table \"transaction\" violates foreign key "
                        + "constraint \"fk_transaction_type\"");

        ProblemDetail problem = handler.handleDataIntegrity(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Data Integrity Violation");
        assertThat(problem.getDetail())
                .isEqualTo("The request references data that does not exist or violates a data integrity rule.");
        assertThat(problem.getDetail()).doesNotContain("fk_transaction_type");
        assertThat(problem.getDetail()).doesNotContain("transaction");
    }

    /**
     * A genuine duplicate-key / unique-constraint violation that surfaces as
     * Spring's own {@link org.springframework.dao.DuplicateKeyException} (a subclass
     * of {@link DataIntegrityViolationException}) is preserved as HTTP 409 CONFLICT,
     * so the 409 duplicate-record outcome is never downgraded to 400 by the F5
     * safety-net handler.
     */
    @Test
    void preservesSpringDuplicateKeyViolationAs409() {
        org.springframework.dao.DuplicateKeyException ex =
                new org.springframework.dao.DuplicateKeyException(
                        "duplicate key value violates unique constraint");

        ProblemDetail problem = handler.handleDataIntegrity(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Duplicate Record");
        assertThat(problem.getDetail()).isEqualTo("The record already exists.");
    }

    /**
     * A query-/path-parameter type-conversion failure (for example a non-numeric
     * {@code ?page=abc} bound to an {@code int}) maps to HTTP 400 rather than the
     * catch-all 500 &mdash; the executable proof for QA finding F12. Only the
     * parameter name is echoed; the rejected value is never included.
     */
    @Test
    void mapsMethodArgumentTypeMismatchTo400() {
        MethodArgumentTypeMismatchException ex =
                Mockito.mock(MethodArgumentTypeMismatchException.class);
        Mockito.when(ex.getName()).thenReturn("page");

        ProblemDetail problem = handler.handleTypeMismatch(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getTitle()).isEqualTo("Invalid Parameter");
        assertThat(problem.getDetail()).isEqualTo("The 'page' parameter has an invalid value.");
    }
}
