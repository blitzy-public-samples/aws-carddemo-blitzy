/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.card.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.carddemo.common.config.CorrelationIdContext;
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
 * :purpose: Unit tests for :class:`CorrelationIdFilter` covering the observability
 *     correlation-id plumbing: an inbound ``X-Correlation-Id`` header is honored, in
 *     scope for the whole filter chain, and echoed on the response; an absent header
 *     yields a generated UUID; and the SLF4J MDC is cleared once the request completes.
 * :note: Plain JUnit 5 with Spring servlet mocks; no Spring context is started and the
 *     filter is instantiated directly.
 */
@DisplayName("CorrelationIdFilter")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * :purpose: Clear the MDC and correlation-id context before each test so a case never
     *     inherits an id left on the pooled thread by a previous test.
     */
    @BeforeEach
    void setUp() {
        MDC.clear();
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: Clear the MDC and correlation-id context after each test to prevent an id
     *     leaking across pooled threads.
     */
    @AfterEach
    void tearDown() {
        MDC.clear();
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: An inbound ``X-Correlation-Id`` header is bound to the correlation-id
     *     context and the MDC for the duration of the chain and is echoed on the response.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("inbound header is in scope during the chain and echoed on the response")
    void inboundHeaderInScopeDuringChainAndEchoed() throws ServletException, IOException {
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
     * :purpose: With no inbound header the filter generates a non-blank UUID correlation
     *     id and writes it to the response header.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("absent header yields a generated UUID on the response")
    void absentHeaderGeneratesUuid() throws ServletException, IOException {
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
     * :purpose: After the request completes the MDC and correlation-id context are cleared
     *     so ids do not leak across pooled request threads.
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
     * :purpose: The downstream filter chain is always invoked so request processing
     *     continues after the correlation id is established.
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

        assertNotNull(chain.getRequest(),
                "the filter must delegate to the downstream chain");
    }

    /**
     * :purpose: Test filter chain that records the correlation id visible in the context
     *     and the MDC at the instant the chain is invoked.
     */
    private static final class CapturingFilterChain implements FilterChain {

        private String contextCorrelationId;
        private String mdcCorrelationId;

        /**
         * :purpose: Capture the in-scope correlation id from the context and the MDC.
         * :param request: the delegated servlet request.
         * :param response: the delegated servlet response.
         */
        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.contextCorrelationId = CorrelationIdContext.getCorrelationId();
            this.mdcCorrelationId = MDC.get(CorrelationIdContext.CORRELATION_ID_KEY);
        }
    }
}
