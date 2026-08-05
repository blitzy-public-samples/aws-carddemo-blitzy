package com.carddemo.account.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures the pre-parser request-body ceiling of the account service.
 */
@DisplayName("RequestBodyCeilingFilter, the account request bound")
class RequestBodyCeilingFilterTest {

    private static final long CEILING = 512L;

    private final RequestBodyCeilingFilter filter =
            new RequestBodyCeilingFilter(properties(CEILING));

    @Test
    @DisplayName("a body at the ceiling reaches the rest of the chain")
    void aBodyAtTheCeilingReachesTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestOfLength((int) CEILING), response, new MockFilterChain());

        assertEquals(HttpStatus.OK.value(), response.getStatus());
        assertEquals("", response.getContentAsString());
    }

    @Test
    @DisplayName("a body past the ceiling is refused with 413 before the chain")
    void aBodyPastTheCeilingIsRefused() throws IOException, jakarta.servlet.ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestOfLength((int) CEILING + 1), response, new MockFilterChain());

        assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), response.getStatus());
        assertEquals(RequestBodyCeilingFilter.REFUSAL_BODY, response.getContentAsString());
        assertFalse(response.getContentAsString().contains("/accounts/"));
    }

    @Test
    @DisplayName("a request declaring no length reaches the parser-level ceiling")
    void aRequestDeclaringNoLengthReachesTheParserCeiling() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/accounts/00000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    private static MockHttpServletRequest requestOfLength(int length) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "PUT", "/accounts/00000000001");
        request.setContent(new byte[length]);
        return request;
    }

    private static AccountProperties properties(long ceiling) {
        return new AccountProperties(
                new AccountProperties.Api(ceiling),
                new AccountProperties.Kafka(
                        new AccountProperties.Kafka.Topics(
                                "account.state-changed", "customer.context-changed",
                                "carddemo.dead-letter")),
                new AccountProperties.Outbox(
                        new AccountProperties.Outbox.Relay(
                                500L, 100, "account-relay",
                                java.time.Duration.ofSeconds(30L), 5000L,
                                java.time.Duration.ofSeconds(10L)), 168L),
                new AccountProperties.ProcessedEvent(168L),
                new AccountProperties.Retention(3_600_000L));
    }
}
