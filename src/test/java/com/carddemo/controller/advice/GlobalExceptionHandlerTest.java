package com.carddemo.controller.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.entity.Transaction;
import com.carddemo.exception.ErrorResponse;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.OverlimitException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link GlobalExceptionHandler}, exercising the handler methods directly with
 * a {@link MockHttpServletRequest} (no Spring context required).
 *
 * <p>Covers the CP3 remediation findings:</p>
 * <ul>
 *   <li><strong>F6 / PR-03</strong> &mdash; the overlimit (102) and expired-account (103)
 *       handlers return the EXACT COBOL message constant in the client payload even when the
 *       exception was built with a diagnostic constructor.</li>
 *   <li><strong>F5</strong> &mdash; {@link AccessDeniedException} maps to 403 with the
 *       standard {@link ErrorResponse}.</li>
 *   <li><strong>F8</strong> &mdash; the {@code IllegalStateException}/
 *       {@code IllegalArgumentException} handlers mask embedded card numbers while preserving
 *       legitimate business messages.</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(uri);
        return req;
    }

    @Nested
    @DisplayName("F6/PR-03 — exact COBOL message on client payload")
    class ExactCobolMessages {

        @Test
        @DisplayName("overlimit (no-arg) → 422, code 102, exact COBOL message")
        void overlimitNoArg() {
            ResponseEntity<ErrorResponse> resp =
                    handler.handleOverlimit(new OverlimitException(), request("/api/transactions"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo("102");
            assertThat(resp.getBody().message()).isEqualTo(OverlimitException.COBOL_MESSAGE);
        }

        @Test
        @DisplayName("overlimit (diagnostic ctor) → client still gets EXACT COBOL message, no diagnostics")
        void overlimitDiagnosticCtor() {
            OverlimitException ex =
                    new OverlimitException(new BigDecimal("1000.00"), new BigDecimal("1500.00"));
            // The diagnostic constructor appends detail to getMessage()...
            assertThat(ex.getMessage()).contains("limit=");

            ResponseEntity<ErrorResponse> resp =
                    handler.handleOverlimit(ex, request("/api/transactions"));

            // ...but the client payload must be the exact COBOL literal only.
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().message()).isEqualTo("OVERLIMIT TRANSACTION");
            assertThat(resp.getBody().message()).doesNotContain("limit=");
            assertThat(resp.getBody().message()).doesNotContain("attempted");
        }

        @Test
        @DisplayName("expired (diagnostic ctor) → client still gets EXACT COBOL message, no diagnostics")
        void expiredDiagnosticCtor() {
            ExpiredAccountException ex =
                    new ExpiredAccountException("2020-01-01", "2022-07-18");
            assertThat(ex.getMessage()).contains("expiration=");

            ResponseEntity<ErrorResponse> resp =
                    handler.handleExpiredAccount(ex, request("/api/transactions"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().code()).isEqualTo("103");
            assertThat(resp.getBody().message())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
            assertThat(resp.getBody().message()).doesNotContain("expiration=");
        }
    }

    @Nested
    @DisplayName("F5 — AccessDeniedException → 403")
    class AccessDenied {

        @Test
        @DisplayName("maps to 403 Forbidden with ACCESS_DENIED code and generic message")
        void mapsTo403() {
            ResponseEntity<ErrorResponse> resp = handler.handleAccessDenied(
                    new AccessDeniedException("Access Denied"), request("/api/admin/users"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().status()).isEqualTo(403);
            assertThat(resp.getBody().code()).isEqualTo("ACCESS_DENIED");
            assertThat(resp.getBody().message()).isEqualTo("Access is denied");
            assertThat(resp.getBody().path()).isEqualTo("/api/admin/users");
        }
    }

    @Nested
    @DisplayName("F8 — card-number masking on illegal-state / illegal-argument payloads")
    class CardMasking {

        @Test
        @DisplayName("illegal state with embedded card number → body is masked, no full PAN")
        void illegalStateMasksCardNumber() {
            IllegalStateException ex = new IllegalStateException(
                    "No customer found for xref card 4111111111111111 during statement build");

            ResponseEntity<ErrorResponse> resp =
                    handler.handleIllegalState(ex, request("/api/statements"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().message()).doesNotContain("4111111111111111");
            assertThat(resp.getBody().message()).contains("************1111");
        }

        @Test
        @DisplayName("illegal state business message with no PAN is preserved verbatim")
        void illegalStatePreservesBusinessMessage() {
            IllegalStateException ex = new IllegalStateException("You have nothing to pay...");

            ResponseEntity<ErrorResponse> resp =
                    handler.handleIllegalState(ex, request("/api/accounts/1/payments"));

            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().message()).isEqualTo("You have nothing to pay...");
        }

        @Test
        @DisplayName("illegal argument with embedded card number → body is masked")
        void illegalArgumentMasksCardNumber() {
            IllegalArgumentException ex =
                    new IllegalArgumentException("Bad card 5500000000000004 supplied");

            ResponseEntity<ErrorResponse> resp =
                    handler.handleIllegalArgument(ex, request("/api/cards"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().message()).doesNotContain("5500000000000004");
            assertThat(resp.getBody().message()).contains("************0004");
        }
    }

    @Nested
    @DisplayName("Invalid Pageable sort property → 400 INVALID_SORT (not 500)")
    class InvalidSortProperty {

        @Test
        @DisplayName("PropertyReferenceException maps to 400 with INVALID_SORT and echoes the bad property")
        void mapsTo400() {
            // Mirrors Spring Data's behaviour for GET /api/transactions?sort=bogus — an unknown
            // sort property on the target entity. Without a dedicated handler this would surface as
            // a misleading 500 on purely client-controllable input.
            PropertyReferenceException ex = new PropertyReferenceException(
                    "bogus", TypeInformation.of(Transaction.class), List.of());

            ResponseEntity<ErrorResponse> resp =
                    handler.handleInvalidSortProperty(ex, request("/api/transactions"));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody()).isNotNull();
            assertThat(resp.getBody().status()).isEqualTo(400);
            assertThat(resp.getBody().code()).isEqualTo("INVALID_SORT");
            assertThat(resp.getBody().message()).contains("bogus");
            assertThat(resp.getBody().path()).isEqualTo("/api/transactions");
        }
    }
}
