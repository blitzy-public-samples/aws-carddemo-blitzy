package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures the request body ceiling: what passes, what is refused, and what the refusal says.
 *
 * <p>ADDITIVE. A 3270 request cannot be oversized: every field of every mapset under
 * {@code app/bms/} has a fixed width and {@code app/csd/CARDDEMO.CSD} sizes the Communication Area a
 * transaction receives. A JavaScript Object Notation (JSON) body has no such bound, so these tests
 * measure a target-side addition.
 */
@DisplayName("RequestBodyCeilingFilter, the bound on a request body")
class RequestBodyCeilingFilterTest {

    /** A small ceiling, so a test body can exceed it without being large. */
    private static final long CEILING = 512;

    /** The class under test, holding the small ceiling. */
    private final RequestBodyCeilingFilter filter = new RequestBodyCeilingFilter(CEILING);

    @Test
    @DisplayName("a body inside the ceiling reaches the rest of the chain")
    void aBodyInsideTheCeilingReachesTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();

        filter.doFilter(requestOfLength((int) CEILING), response, chain);

        assertEquals(HttpStatus.OK.value(), response.getStatus(),
                "a body exactly at the ceiling was refused, and the bound is inclusive");
        assertEquals("", response.getContentAsString(), "the filter wrote a body of its own");
    }

    @Test
    @DisplayName("a body past the ceiling is refused with 413 and never reaches the chain")
    void aBodyPastTheCeilingIsRefused() throws IOException, jakarta.servlet.ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(requestOfLength((int) CEILING + 1), response, chain);

        assertEquals(HttpStatus.CONTENT_TOO_LARGE.value(), response.getStatus(),
                "an oversized body reached the parser");
        assertEquals(RequestBodyCeilingFilter.REFUSAL_BODY, response.getContentAsString(),
                "the refusal carries the one fixed text");
        assertFalse(response.getContentAsString().contains("/authorizations"),
                "the refusal names no route");
    }

    @Test
    @DisplayName("a request declaring no length is passed on, because the parser bounds it")
    void aRequestDeclaringNoLengthIsPassedOn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authorizations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(HttpStatus.OK.value(), response.getStatus(),
                "a chunked request was refused here rather than bounded by "
                        + "config/JsonReadCeilingConfig, which is where it belongs");
    }

    @Test
    @DisplayName("a ceiling below one byte stops start-up and names its property")
    void aCeilingBelowOneByteStopsStartUp() {
        assertTrue(assertThrows(IllegalStateException.class,
                        () -> new RequestBodyCeilingFilter(0)).getMessage()
                        .contains("carddemo.api.max-request-body-bytes"),
                "the refusal does not name the property a deployment has to correct");
    }

    /**
     * Builds one request declaring a body of the length given.
     *
     * @param length the value of {@code Content-Length}
     * @return the request
     */
    private static MockHttpServletRequest requestOfLength(int length) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/authorizations");
        request.setContent(new byte[length]);
        return request;
    }
}
