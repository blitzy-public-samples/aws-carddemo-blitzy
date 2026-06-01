package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.DiscloseGroupNotFoundException;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.exception.OverlimitException;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice test for the cross-cutting {@link GlobalExceptionHandler}
 * {@code @RestControllerAdvice}.
 *
 * <p>A {@code @ControllerAdvice} is only invoked when a controller throws an exception
 * <em>during request processing</em>; a direct unit test of the handler methods would
 * bypass Spring's {@code HandlerExceptionResolver} dispatch entirely. The canonical way
 * to integration-test the advice is therefore to register a tiny throw-away controller
 * ({@link ExceptionThrowingController}) whose endpoints each raise exactly one exception,
 * load it together with the production advice via {@link WebMvcTest} + {@link Import}, and
 * assert &mdash; through {@link MockMvc} &mdash; that every exception maps to the correct
 * HTTP status and the uniform {@code ErrorResponse} JSON contract
 * ({@code status}, {@code code}, {@code message}, {@code path}, {@code timestamp}).</p>
 *
 * <h2>Why {@code @AutoConfigureMockMvc(addFilters = false)}</h2>
 * <p>These tests verify exception&rarr;status mapping, not authentication. Disabling the
 * Spring Security filter chain keeps each request focused on the advice: in particular the
 * {@code /test/access-denied} endpoint throws
 * {@link org.springframework.security.access.AccessDeniedException} <em>from the handler
 * method</em> (not from a security filter), so with the filters removed it propagates into
 * the {@code @ControllerAdvice} and is mapped to {@code 403 Forbidden} exactly as a
 * {@code @PreAuthorize} denial on {@code UserController}/{@code BatchAdminController} would
 * be (PR-18).</p>
 *
 * <h2>Why {@code excludeFilters} drops the {@code com.carddemo.security} package</h2>
 * <p>A {@code @WebMvcTest} slice always registers application {@code jakarta.servlet.Filter}
 * beans (so the MVC dispatch pipeline is realistic), and the production
 * {@code JwtAuthenticationFilter} is a {@code @Component} that extends
 * {@code OncePerRequestFilter}. Left untouched it would be instantiated by this slice and,
 * because its collaborator {@code CustomAuthorityMapper} (a plain {@code @Component}, not an
 * MVC stereotype) is <em>not</em> loaded by the slice, the application context would fail to
 * start with an {@code UnsatisfiedDependencyException}. Note that
 * {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters from the MockMvc
 * filter chain &mdash; it does not prevent the filter bean from being <em>created</em>.
 * Excluding the whole {@code com.carddemo.security} package from the slice's component scan
 * via a {@link FilterType#REGEX REGEX} {@link ComponentScan.Filter} keeps the context minimal
 * and focused on the advice; none of the security beans are needed because the
 * {@code /test/access-denied} endpoint raises the {@code AccessDeniedException} directly from
 * the handler method rather than from a security filter.</p>
 *
 * <h2>Exception &rarr; HTTP status coverage (AAP &sect;0.6.12, PR-03, PR-22)</h2>
 * <ul>
 *   <li>{@link InvalidCardException} (CBTRN02C code 100) &rarr; {@code 400} &mdash;
 *       exact message {@code "INVALID CARD NUMBER FOUND"} (CBTRN02C.cbl L386).</li>
 *   <li>{@link AccountNotFoundException} (CBTRN02C code 101) &rarr; {@code 404}.</li>
 *   <li>{@link OverlimitException} (CBTRN02C code 102) &rarr; {@code 422} &mdash;
 *       exact message {@code "OVERLIMIT TRANSACTION"} (CBTRN02C.cbl L411).</li>
 *   <li>{@link ExpiredAccountException} (CBTRN02C code 103) &rarr; {@code 422} &mdash;
 *       exact message {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}
 *       (CBTRN02C.cbl L418).</li>
 *   <li>{@link DiscloseGroupNotFoundException} &rarr; {@code 404} &mdash; the
 *       disclosure-group interest rate could not be located even after the
 *       {@code DEFAULT}-group fallback (CBACT04C DEFAULT miss).</li>
 *   <li>{@link ObjectOptimisticLockingFailureException} &rarr; {@code 409} (PR-22).</li>
 *   <li>{@link org.springframework.security.access.AccessDeniedException} &rarr;
 *       {@code 403}.</li>
 *   <li>Any otherwise-unmapped {@link RuntimeException}/{@link Exception} &rarr;
 *       {@code 500} (catch-all).</li>
 * </ul>
 *
 * <h2>Note on the fixture exception constructors</h2>
 * <p>Each endpoint constructs its exception with the constructor that yields the message
 * the corresponding assertion expects, derived from the real exception APIs:</p>
 * <ul>
 *   <li>{@code AccountNotFoundException(99999999999L)} produces
 *       {@code "Account not found: 99999999999"} (the no-arg constructor would yield the
 *       upper-case COBOL literal {@code "ACCOUNT RECORD NOT FOUND"}, which does not contain
 *       the mixed-case substring {@code "Account"}).</li>
 *   <li>{@code InvalidCardException()} (no-arg) produces exactly
 *       {@code "INVALID CARD NUMBER FOUND"}; the single-{@code String} constructor instead
 *       treats its argument as a card number and appends {@code " (card=...)"}.</li>
 *   <li>{@code OverlimitException()} / {@code ExpiredAccountException()} (no-arg) carry the
 *       exact COBOL literals; neither class exposes a single-{@code String} constructor.
 *       The handler additionally re-emits the canonical {@code COBOL_MESSAGE} constant for
 *       these two, so the client always sees the verbatim COBOL text regardless of how the
 *       exception was built (PR-03).</li>
 * </ul>
 *
 * <p>No service collaborators are involved, so no mocks are declared. All requests are
 * {@code GET}s and the security filters are disabled, so no CSRF token is needed.</p>
 *
 * @see GlobalExceptionHandler
 * @see com.carddemo.exception.ErrorResponse
 */
@WebMvcTest(
        controllers = GlobalExceptionHandlerTest.ExceptionThrowingController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ExceptionThrowingController.class})
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Test-only controller that raises one exception per endpoint so the production
     * {@link GlobalExceptionHandler} advice is exercised through the full Spring MVC
     * dispatch pipeline. It is a {@code static} nested class and is never registered with
     * the running application &mdash; only this {@code @WebMvcTest} slice loads it.
     */
    @RestController
    static class ExceptionThrowingController {

        @GetMapping("/test/account-not-found")
        public void throwAccountNotFound() {
            // Long-id constructor → "Account not found: 99999999999" (contains "Account").
            throw new AccountNotFoundException(99999999999L);
        }

        @GetMapping("/test/invalid-card")
        public void throwInvalidCard() {
            // No-arg constructor → exact COBOL literal "INVALID CARD NUMBER FOUND".
            throw new InvalidCardException();
        }

        @GetMapping("/test/overlimit")
        public void throwOverlimit() {
            // No-arg constructor → exact COBOL literal "OVERLIMIT TRANSACTION".
            throw new OverlimitException();
        }

        @GetMapping("/test/expired")
        public void throwExpired() {
            // No-arg constructor → exact COBOL literal
            // "TRANSACTION RECEIVED AFTER ACCT EXPIRATION".
            throw new ExpiredAccountException();
        }

        @GetMapping("/test/disclose-not-found")
        public void throwDisclose() {
            throw new DiscloseGroupNotFoundException("No DEFAULT entry for type=01 cat=01");
        }

        @GetMapping("/test/optimistic-lock")
        public void throwOptimisticLock() {
            throw new ObjectOptimisticLockingFailureException("Account", 100000001L);
        }

        @GetMapping("/test/access-denied")
        public void throwAccessDenied() {
            // org.springframework.security.access.AccessDeniedException (NOT java.nio).
            throw new AccessDeniedException("Access denied");
        }

        @GetMapping("/test/unhandled")
        public void throwUnhandled() {
            throw new RuntimeException("Something unexpected");
        }
    }

    @Test
    @DisplayName("AccountNotFoundException → 404 Not Found with code 101 payload")
    void shouldReturn404ForAccountNotFound() throws Exception {
        mockMvc.perform(get("/test/account-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Account")))
                .andExpect(jsonPath("$.path").value("/test/account-not-found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("InvalidCardException → 400 Bad Request with exact CBTRN02C message")
    void shouldReturn400ForInvalidCard() throws Exception {
        mockMvc.perform(get("/test/invalid-card"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").value("INVALID CARD NUMBER FOUND"))
                .andExpect(jsonPath("$.path").value("/test/invalid-card"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("OverlimitException → 422 Unprocessable Entity with exact CBTRN02C message")
    void shouldReturn422ForOverlimit() throws Exception {
        mockMvc.perform(get("/test/overlimit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("OVERLIMIT TRANSACTION"))
                .andExpect(jsonPath("$.path").value("/test/overlimit"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("ExpiredAccountException → 422 with exact CBTRN02C expiration message")
    void shouldReturn422ForExpiredAccount() throws Exception {
        mockMvc.perform(get("/test/expired"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"))
                .andExpect(jsonPath("$.path").value("/test/expired"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * The disclosure-group lookup miss (including the {@code DEFAULT} fallback) is surfaced
     * as {@code 404 Not Found}: the requested interest rate could not be located. The
     * production {@link GlobalExceptionHandler} is aligned to this mapping per the
     * exception&rarr;HTTP contract (AAP &sect;0.6.12 family) and the
     * {@code shouldReturn404ForDiscloseGroupNotFound} verification mandated for this advice.
     */
    @Test
    @DisplayName("DiscloseGroupNotFoundException → 404 Not Found (CBACT04C DEFAULT miss)")
    void shouldReturn404ForDiscloseGroupNotFound() throws Exception {
        mockMvc.perform(get("/test/disclose-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("DEFAULT")))
                .andExpect(jsonPath("$.path").value("/test/disclose-not-found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException → 409 Conflict (PR-22)")
    void shouldReturn409ForOptimisticLock() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/test/optimistic-lock"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("AccessDeniedException → 403 Forbidden (closes COUSR auth gap)")
    void shouldReturn403ForAccessDenied() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/test/access-denied"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Unhandled exception → 500 Internal Server Error with generic message")
    void shouldReturn500ForUnhandled() throws Exception {
        mockMvc.perform(get("/test/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/test/unhandled"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
