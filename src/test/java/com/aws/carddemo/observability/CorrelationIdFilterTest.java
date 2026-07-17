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
package com.aws.carddemo.observability;

import static com.aws.carddemo.observability.CorrelationIdFilter.CORRELATION_ID_HEADER;
import static com.aws.carddemo.observability.CorrelationIdFilter.CORRELATION_ID_MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;

/**
 * Unit tests for {@link CorrelationIdFilter}, focused on the correlation-ID
 * sanitization contract (QA finding F-5): a supplied {@value CorrelationIdFilter#CORRELATION_ID_HEADER}
 * header is honored only when it matches a bounded allow-list, and any absent,
 * blank, oversize, or otherwise-invalid value is replaced with a generated UUID.
 * The resolved value is always echoed on the response and removed from the MDC
 * once the request completes.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * Runs the filter against a request whose {@value CorrelationIdFilter#CORRELATION_ID_HEADER}
     * header is {@code inbound} (or absent when {@code inbound} is {@code null}),
     * capturing the value that was visible in the MDC while the chain ran.
     *
     * @param inbound the raw inbound header value, or {@code null} to omit the header
     * @return the correlation ID echoed on the response header (equal to the value
     *         seen in the MDC during the chain)
     */
    private String runFilter(String inbound) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (inbound != null) {
            request.addHeader(CORRELATION_ID_HEADER, inbound);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(CORRELATION_ID_MDC_KEY));

        filter.doFilter(request, response, chain);

        String echoed = response.getHeader(CORRELATION_ID_HEADER);
        // The MDC value during the chain must match the echoed response header.
        assertThat(mdcDuringChain.get()).isEqualTo(echoed);
        // The MDC must be cleared once the request completes (no thread leakage).
        assertThat(MDC.get(CORRELATION_ID_MDC_KEY)).isNull();
        return echoed;
    }

    private static void assertIsUuid(String value) {
        assertThatCode(() -> UUID.fromString(value))
                .as("expected a generated UUID but was: %s", value)
                .doesNotThrowAnyException();
    }

    @Test
    void honorsValidSuppliedId() throws Exception {
        assertThat(runFilter("QA-VALID-123")).isEqualTo("QA-VALID-123");
    }

    @Test
    void honorsMaxLength64Id() throws Exception {
        String id = "a".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
        assertThat(runFilter(id)).isEqualTo(id);
    }

    @Test
    void honorsUuidSuppliedId() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(runFilter(id)).isEqualTo(id);
    }

    @Test
    void generatesUuidWhenHeaderAbsent() throws Exception {
        assertIsUuid(runFilter(null));
    }

    @Test
    void generatesUuidWhenHeaderBlank() throws Exception {
        assertIsUuid(runFilter("   "));
    }

    @Test
    void rejectsScriptPayloadAndGeneratesUuid() throws Exception {
        String result = runFilter("<script>alert(1)</script>");
        assertThat(result).doesNotContain("<script>");
        assertIsUuid(result);
    }

    @Test
    void rejectsSqlInjectionPayloadAndGeneratesUuid() throws Exception {
        String result = runFilter("' OR 1=1 --");
        assertThat(result).doesNotContain("'").doesNotContain(" ");
        assertIsUuid(result);
    }

    @Test
    void rejectsNonAsciiPayloadAndGeneratesUuid() throws Exception {
        String result = runFilter("caf\u00e9-\uD83D\uDE80-id");
        assertThat(result).isNotEqualTo("caf\u00e9-\uD83D\uDE80-id");
        assertIsUuid(result);
    }

    @Test
    void rejectsOversizeIdAndGeneratesUuid() throws Exception {
        String oversize = "a".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH + 1);
        String result = runFilter(oversize);
        assertThat(result).isNotEqualTo(oversize);
        assertIsUuid(result);
    }

    @Test
    void rejectsLogForgingBracketPayloadAndGeneratesUuid() throws Exception {
        String result = runFilter("AAA] INFO forged [cid=BBB");
        assertThat(result).doesNotContain("[").doesNotContain("]").doesNotContain(" ");
        assertIsUuid(result);
    }

    /**
     * The filter captures the in-flight request URI for the current thread (consumed by the
     * observation predicate to exclude infrastructure endpoints, QA finding F-OBS-1) and always
     * clears it once the request completes, so it never leaks onto a pooled request thread.
     */
    @Test
    void capturesRequestPathDuringChainAndClearsItAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/prometheus");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> pathDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> pathDuringChain.set(CorrelationIdFilter.currentRequestPath());

        filter.doFilter(request, response, chain);

        assertThat(pathDuringChain.get()).isEqualTo("/actuator/prometheus");
        assertThat(CorrelationIdFilter.currentRequestPath()).isNull();
    }

    /**
     * On a container {@code ERROR} dispatch the live request URI is the error page (e.g.
     * {@code /error}); the filter must capture the ORIGINAL request URI (preserved under
     * {@link RequestDispatcher#ERROR_REQUEST_URI}) so the infrastructure-endpoint exclusion still
     * applies to the error dispatch of, say, an unauthenticated {@code /actuator/metrics} request
     * (QA finding F-OBS-1). The filter runs on error dispatches because
     * {@code shouldNotFilterErrorDispatch()} returns {@code false}.
     */
    @Test
    void onErrorDispatchCapturesOriginalRequestPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setRequestURI("/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> pathDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> pathDuringChain.set(CorrelationIdFilter.currentRequestPath());

        filter.doFilter(request, response, chain);

        assertThat(pathDuringChain.get()).isEqualTo("/actuator/metrics");
        assertThat(CorrelationIdFilter.currentRequestPath()).isNull();
    }

    /**
     * When this filter already assigned and echoed a correlation ID on the response during an
     * earlier dispatch of the same request (the ERROR-dispatch case), that ID is reused so a single
     * logical request keeps one identifier across all of its dispatches.
     */
    @Test
    void reusesCorrelationIdAlreadyAssignedOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(CORRELATION_ID_HEADER, "PRIOR-DISPATCH-ID");
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringChain.set(MDC.get(CORRELATION_ID_MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain.get()).isEqualTo("PRIOR-DISPATCH-ID");
        assertThat(response.getHeader(CORRELATION_ID_HEADER)).isEqualTo("PRIOR-DISPATCH-ID");
    }
}
