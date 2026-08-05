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
package com.carddemo.common.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * :purpose: Unit tests for the SHARED :class:`CorrelationIdFilter` that every CardDemo service now
 *     registers through ``WebObservabilityConfig``. Covers the documented resolution precedence
 *     (inbound ``X-Correlation-Id`` first, then the W3C ``traceparent`` trace id, then a generated
 *     id), the sanitize-then-echo contract, and MDC clearing after the request.
 * :note: These cases consolidate the coverage that previously lived in per-service copies of the
 *     filter (account-service and card-service each had their own class and test); the duplicates
 *     ignored ``traceparent`` entirely, which is the defect QA Issue 7 reported.
 * :note: Plain JUnit 5 with Spring servlet mocks; no Spring context is started.
 */
@DisplayName("CorrelationIdFilter (shared)")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * :purpose: Clear the MDC before each case so no id is inherited from a previous test.
     */
    @BeforeEach
    void setUp() {
        MDC.clear();
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: Clear the MDC after each case so no id leaks onto the pooled test thread.
     */
    @AfterEach
    void tearDown() {
        MDC.clear();
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: An inbound ``X-Correlation-Id`` is in scope for the whole chain and echoed back.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("inbound X-Correlation-Id is in scope during the chain and echoed")
    void inboundHeaderInScopeAndEchoed() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "test-correlation-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("test-correlation-123", chain.contextCorrelationId,
                "correlation id must be in the context while the chain runs");
        assertEquals("test-correlation-123", chain.mdcCorrelationId,
                "correlation id must be in the MDC while the chain runs");
        assertEquals("test-correlation-123",
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
                "inbound correlation id must be echoed on the response");
    }

    /**
     * :purpose: With no ``X-Correlation-Id`` the filter adopts the trace id from a W3C
     *     ``traceparent`` header, so a request entering the stack from a traced caller shares one id
     *     across the log stream and the trace backend.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("traceparent trace id is adopted when no correlation header is present")
    void traceparentUsedAsFallback() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.TRACEPARENT_HEADER,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", chain.mdcCorrelationId,
                "the W3C trace id must become the correlation id");
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
                "the adopted trace id must be echoed on the response");
    }

    /**
     * :purpose: An all-zero (invalid) W3C trace id is rejected and a fresh id generated instead.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("all-zero traceparent trace id is rejected in favour of a generated id")
    void invalidTraceparentIgnored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.TRACEPARENT_HEADER,
                "00-00000000000000000000000000000000-0000000000000000-00");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(generated, "an id must still be produced");
        assertDoesNotThrow(() -> UUID.fromString(generated),
                "the fallback id must be a generated UUID, not the invalid trace id");
    }

    /**
     * :purpose: The inbound header takes precedence over ``traceparent`` when both are present.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("X-Correlation-Id wins over traceparent")
    void correlationHeaderWinsOverTraceparent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "business-id-9");
        request.addHeader(CorrelationIdFilter.TRACEPARENT_HEADER,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("business-id-9",
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
                "an explicit business correlation id must not be overridden by the trace id");
    }

    /**
     * :purpose: A hostile inbound value is sanitized before it reaches the MDC and the response, so
     *     CR/LF log forging cannot be injected through the header.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("control characters are stripped from an inbound id before it is echoed")
    void inboundHeaderIsSanitized() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc\r\nFAKE-LINE 123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals("abcFAKE-LINE123", chain.mdcCorrelationId,
                "CR/LF and spaces must be stripped from the stored id");
        assertEquals("abcFAKE-LINE123",
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER),
                "the echoed header must match the sanitized stored value");
    }

    /**
     * :purpose: With no inbound headers a non-blank UUID id is generated and echoed.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("absent headers yield a generated UUID on the response")
    void absentHeadersGenerateUuid() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(generated, "a correlation id must be generated when none is supplied");
        assertFalse(generated.isBlank(), "generated correlation id must not be blank");
        assertDoesNotThrow(() -> UUID.fromString(generated),
                "generated correlation id must be a valid UUID");
    }

    /**
     * :purpose: The MDC is cleared once the request completes so pooled container threads never
     *     carry one request's id into the next.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("MDC is cleared after the request completes")
    void mdcClearedAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(MDC.get(CorrelationIdContext.CORRELATION_ID_KEY),
                "MDC must not retain the correlation id after the request");
        assertNull(CorrelationIdContext.getCorrelationId(),
                "correlation-id context must be cleared after the request");
    }

    /**
     * :purpose: The downstream chain is always invoked so request processing continues.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("filter chain is always invoked")
    void filterChainAlwaysInvoked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(chain.getRequest(), "the filter must delegate to the downstream chain");
    }

    /**
     * :purpose: Test chain that records the correlation id visible in the MDC at the instant the
     *     chain is invoked.
     */
    private static final class CapturingFilterChain implements FilterChain {

        /** :purpose: Correlation id visible via CorrelationIdContext inside the chain. */
        private String contextCorrelationId;

        /** :purpose: Correlation id visible via the SLF4J MDC inside the chain. */
        private String mdcCorrelationId;

        /**
         * :purpose: Capture the in-scope correlation id from the MDC.
         * :param request: the delegated servlet request.
         * :param response: the delegated servlet response.
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.contextCorrelationId = CorrelationIdContext.getCorrelationId();
            this.mdcCorrelationId = MDC.get(CorrelationIdContext.CORRELATION_ID_KEY);
        }
    }
    /**
     * :purpose: With no ``X-Correlation-Id`` present, a W3C ``traceparent`` header
     *     supplies the correlation id from its trace-id field, so a correlated trace
     *     entering from an upstream hop is not replaced by an unrelated random id.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("traceparent trace-id is used when X-Correlation-Id is absent")
    void traceparentTraceIdUsedWhenHeaderAbsent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.TRACEPARENT_HEADER,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736",
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));
    }

    /**
     * :purpose: An all-zero (invalid) W3C trace-id is rejected, so the filter falls
     *     through to a freshly generated correlation id rather than propagating a
     *     meaningless one.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("all-zero traceparent trace-id is rejected in favor of a generated id")
    void allZeroTraceIdIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.TRACEPARENT_HEADER,
                "00-00000000000000000000000000000000-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(echoed);
        assertFalse(echoed.chars().allMatch(c -> c == '0'));
    }

    /**
     * :purpose: An inbound header carrying control characters or exceeding the length
     *     bound is sanitized, and the echoed header matches the sanitized value that
     *     reached the MDC, so the log stream and the response never disagree.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("an unsafe inbound header is sanitized and the echo matches the MDC value")
    void unsafeInboundHeaderIsSanitizedAndEchoMatchesMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "bad value " + "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];

        filter.doFilter(request, response, (req, res) ->
                seenInChain[0] = CorrelationIdContext.getCorrelationId());

        String echoed = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertNotNull(echoed);
        assertEquals(seenInChain[0], echoed);
        assertEquals(echoed, CorrelationIdContext.sanitize(echoed));
    }

}
