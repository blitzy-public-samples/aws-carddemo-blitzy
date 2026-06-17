package com.carddemo.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.dto.ErrorResponse;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Focused unit test for the three exception handlers added to {@link GlobalExceptionHandler} to close
 * the QA CKPT-2 error-handling gaps, where Spring MVC protocol exceptions and a data-layer constraint
 * violation previously fell through the catch-all and surfaced as HTTP&nbsp;500:
 *
 * <ul>
 *   <li>{@link DataIntegrityViolationException} &rarr; <strong>409</strong> (Critical&nbsp;#1 safety
 *       net; the {@code TransactionService} pre-validation makes 404 the primary path, so this
 *       handler is no longer reachable through the normal {@code POST /transactions} flow and is
 *       therefore pinned directly here);</li>
 *   <li>{@link HttpMediaTypeNotSupportedException} &rarr; <strong>415</strong> (Minor&nbsp;#3);</li>
 *   <li>{@link HttpRequestMethodNotSupportedException} &rarr; <strong>405</strong> with an
 *       {@code Allow} header (Minor&nbsp;#3).</li>
 * </ul>
 *
 * <p>The handler has no collaborators, so it is exercised directly (no Spring context) for fast,
 * deterministic assertions on the exact status, body, headers, and the no-internal-detail-leak
 * guarantee (AAP &sect;0.6.8).</p>
 */
class GlobalExceptionHandlerTest {

    /** The advice under test &mdash; a plain object with only a static logger, so {@code new} suffices. */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DataIntegrityViolationException -> 409 with a generic body that leaks no SQL/constraint detail")
    void dataIntegrityViolation_maps409_noLeak() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transactions");
        // Simulate a Hibernate/JDBC FK violation whose message carries sensitive internal detail.
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "could not execute statement; constraint [fk_tran_cat]; "
                        + "SQL [insert into transactions ...]");

        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrityViolation(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // 409
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.path()).isEqualTo("/transactions");
        // No internal detail is leaked to the client: no SQL, no constraint name, no Hibernate text.
        assertThat(body.message())
                .doesNotContain("fk_tran_cat")
                .doesNotContain("SQL")
                .doesNotContain("constraint")
                .doesNotContain("insert into");
    }

    @Test
    @DisplayName("HttpMediaTypeNotSupportedException -> 415 with a generic, non-leaking message")
    void mediaTypeNotSupported_maps415() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/transactions");
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleMediaTypeNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE); // 415
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(415);
        assertThat(body.path()).isEqualTo("/transactions");
        assertThat(body.message()).isNotBlank();
    }

    @Test
    @DisplayName("HttpRequestMethodNotSupportedException -> 405 with an Allow header listing supported methods")
    void methodNotSupported_maps405_withAllowHeader() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("DELETE", "/transactions/0000000000000001");
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET"));

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED); // 405
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(405);
        assertThat(body.path()).isEqualTo("/transactions/0000000000000001");
        // RFC 9110 §15.5.6: a 405 response advertises the supported methods via the Allow header.
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    @Test
    @DisplayName("NoResourceFoundException (unmatched route) -> 404 with a generic, path-free message (S-1)")
    void noResourceFound_maps404_noLeak() {
        // QA CKPT-5 S-1: an authenticated request to an unmapped URL previously fell through to the
        // catch-all and surfaced as HTTP 500; it must now map to HTTP 404 with the standard envelope.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/nonexistentpath");
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "nonexistentpath");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND); // 404, not 500
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(404);
        assertThat(body.error()).isEqualTo("Not Found");
        assertThat(body.path()).isEqualTo("/nonexistentpath");
        assertThat(body.message()).isNotBlank();
        // The curated message must NOT echo the framework's "No static resource ..." text nor the
        // raw path into the body (AAP §0.6.8 non-leakage discipline).
        assertThat(body.message()).doesNotContain("No static resource");
        assertThat(body.message()).doesNotContain("nonexistentpath");
    }
}
