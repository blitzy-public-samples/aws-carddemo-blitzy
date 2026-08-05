package com.carddemo.card.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures the pre-parser request-body ceiling of the card service.
 */
@DisplayName("RequestBodyCeilingFilter, the card request bound")
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
    @DisplayName("a body past the ceiling is refused before the chain")
    void aBodyPastTheCeilingIsRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestOfLength((int) CEILING + 1), response, new MockFilterChain());

        assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), response.getStatus());
        assertEquals(RequestBodyCeilingFilter.REFUSAL_BODY, response.getContentAsString());
        assertFalse(response.getContentAsString().contains("/cards/"));
    }

    @Test
    @DisplayName("a request declaring no length reaches the parser-level ceiling")
    void aRequestDeclaringNoLengthReachesTheParserCeiling() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/cards/opaque");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    private static MockHttpServletRequest requestOfLength(int length) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/cards/opaque");
        request.setContent(new byte[length]);
        return request;
    }

    private static CardProperties properties(long ceiling) {
        return new CardProperties(
                new CardProperties.Api(ceiling),
                new CardProperties.Kafka(new CardProperties.Kafka.Topics("card.updated")),
                new CardProperties.Outbox(new CardProperties.Outbox.Relay(
                        500L, 100, "card-relay", java.time.Duration.ofMinutes(2L)), 168L),
                new CardProperties.ProcessedEvent(168L),
                new CardProperties.Retention(3_600_000L));
    }
}
