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
package com.carddemo.common.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.FilterChain;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :purpose: Verify {@link DatastoreOutageErrorFilter} answers a datastore failure that
 *   escapes the filter chain — the session-store outage that never reaches the Spring
 *   dispatcher — with the documented ``ErrorResponse`` envelope and HTTP 503, and that
 *   it leaves every other failure alone.
 * :output: Assertions over the rendered status, content type, envelope fields, the
 *   preservation of headers written by outer filters, and the pass-through of
 *   non-datastore exceptions.
 */
class DatastoreOutageErrorFilterTest {

    /** :purpose: Response header the correlation filter sets before this filter runs. */
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** :purpose: Correlation id used to prove the MDC reaches the rendered envelope. */
    private static final String CORRELATION_ID = "QA-D-0001";

    /** :purpose: Mapper used to read the rendered body back. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * :purpose: Clear the correlation MDC entry after each scenario so ids never leak
     *   between tests.
     */
    @AfterEach
    void clearCorrelationId() {
        CorrelationIdContext.clear();
    }

    /**
     * :purpose: Build the filter with no mapper provider, exercising its local-mapper
     *   fallback as well as the rendering path.
     * :returns: the filter under test.
     */
    private DatastoreOutageErrorFilter newFilter() {
        return new DatastoreOutageErrorFilter(null);
    }

    /**
     * :purpose: A Redis/PostgreSQL connection failure escaping the chain renders the
     *   envelope with 503 and application/json, carrying the request path plus the
     *   correlation id, and disclosing nothing about the failure itself.
     */
    @Test
    @DisplayName("renders the 503 JSON envelope for a datastore connection failure")
    void rendersEnvelopeForConnectionFailure() throws ServletException, IOException {
        CorrelationIdContext.setCorrelationId(CORRELATION_ID);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(CORRELATION_HEADER, CORRELATION_ID);
        FilterChain failing = (req, res) -> {
            throw new DataAccessResourceFailureException("Unable to connect to Redis at redis:6379");
        };

        newFilter().doFilter(request, response, failing);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        // An outage is transient, so the caller is told when to retry -- the same interval the
        // exception advice advertises for the identical condition inside the dispatcher.
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER))
                .isEqualTo(DatastoreOutageErrorFilter.RETRY_AFTER_SECONDS);
        // Headers written by the outer filters survive, because only the buffer is reset.
        assertThat(response.getHeader(CORRELATION_HEADER)).isEqualTo(CORRELATION_ID);

        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("error").asString()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        assertThat(body.get("message").asString()).isEqualTo(DatastoreOutageErrorFilter.MESSAGE);
        assertThat(body.get("path").asString()).isEqualTo("/accounts/1");
        assertThat(body.get("correlationId").asString()).isEqualTo(CORRELATION_ID);
        assertThat(body.has("timestamp")).isTrue();
        assertThat(response.getContentAsString())
                .doesNotContain("redis:6379")
                .doesNotContain("DataAccessResourceFailureException");
    }

    /**
     * :purpose: A command timeout — what a Lettuce read against a stopped Redis actually
     *   produces — is treated as the same outage, and a wrapping ``ServletException`` does
     *   not hide it.
     */
    @Test
    @DisplayName("unwraps a ServletException to find a command timeout")
    void unwrapsWrappedQueryTimeout() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/transactions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new ServletException(new QueryTimeoutException("Redis command timed out"));
        };

        newFilter().doFilter(request, response, failing);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("message").asString()).isEqualTo(DatastoreOutageErrorFilter.MESSAGE);
        assertThat(body.get("path").asString()).isEqualTo("/transactions");
    }

    /**
     * :purpose: A successful request is untouched: the filter adds no header, no status
     *   change and no body of its own.
     */
    @Test
    @DisplayName("passes a successful request through unchanged")
    void passesSuccessfulRequestThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsString()).isEmpty();
    }

    /**
     * :purpose: An application defect must NOT be reported as an outage, or a real bug
     *   would reach clients as "retry shortly" with a 503.
     */
    @Test
    @DisplayName("propagates a non-datastore failure unchanged")
    void propagatesNonDatastoreFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> newFilter().doFilter(request, response, failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentType()).isNull();
    }

    /**
     * :purpose: A statement the datastore itself REJECTED is not an outage: a ``503`` would tell
     *   the caller to retry an identical request that must fail identically, so the failure is
     *   propagated and answered as a ``500`` by the advice instead.
     */
    @Test
    @DisplayName("a data integrity violation is NOT absorbed")
    void dataIntegrityViolationIsNotAbsorbed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        };

        assertThatThrownBy(() -> newFilter().doFilter(request, response, failing))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentType()).isNull();
    }

    /**
     * :purpose: A card number in the request path is masked in the envelope, so the one log-
     *   and body-bearing record of an outage on a card route does not persist a PAN (CWE-532).
     * :raises ServletException: propagated from the filter under test.
     * :raises IOException: propagated from reading the rendered body back.
     */
    @Test
    @DisplayName("a card number in the path is masked in the envelope")
    void cardNumberInThePathIsMasked() throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/cards/4111111111111111");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new QueryTimeoutException("Redis command timed out");
        };

        newFilter().doFilter(request, response, failing);

        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("path").asString()).doesNotContain("4111111111111111");
        assertThat(response.getContentAsString()).doesNotContain("4111111111111111");
    }

    /**
     * :purpose: Once the response is committed the envelope cannot replace what is already
     *   on the wire, so the failure is re-thrown for the container instead of the body
     *   being silently corrupted.
     * :raises IOException: propagated from reading the buffered response body back.
     */
    @Test
    @DisplayName("re-throws when the response is already committed")
    void reThrowsWhenResponseCommitted() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            res.getOutputStream().write("partial".getBytes());
            ((MockHttpServletResponse) res).setCommitted(true);
            throw new QueryTimeoutException("Redis command timed out");
        };

        assertThatThrownBy(() -> newFilter().doFilter(request, response, failing))
                .isInstanceOf(QueryTimeoutException.class);
        assertThat(response.getContentAsString()).isEqualTo("partial");
    }
}
