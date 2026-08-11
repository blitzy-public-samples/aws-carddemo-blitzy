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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * :purpose: Verifies the request-body cap that keeps an unauthenticated caller from
 *     sending an unbounded payload (CWE-770): a declared oversized ``Content-Length``
 *     is rejected before the body is read, a chunked body is counted while it streams
 *     and is also rejected, a body within the cap is dispatched untouched and remains
 *     fully readable, and an unrelated failure is never mistaken for an oversized body.
 */
class RequestSizeLimitFilterTest {

    private static final long CAP = 64L;

    /**
     * :purpose: Build a POST request with a body and an explicit declared length.
     */
    private MockHttpServletRequest requestWithLength(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reports");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    /**
     * :purpose: Build a POST request whose length is undeclared, as a chunked upload is.
     */
    private MockHttpServletRequest chunkedRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reports") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader("Transfer-Encoding", "chunked");
        return request;
    }

    @Test
    @DisplayName("a declared Content-Length above the cap is rejected with 413 before dispatch")
    void declaredOversizedBodyIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithLength(new byte[(int) CAP + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(chain.getRequest()).as("the request must not be dispatched").isNull();
    }

    @Test
    @DisplayName("a body within the cap is dispatched and stays readable in full")
    void bodyWithinCapIsDispatched() throws ServletException, IOException {
        byte[] body = "{\"reportType\":\"MONTHLY\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = requestWithLength(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an undeclared (chunked) body above the cap is rejected with 413 while it streams")
    void chunkedOversizedBodyIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = chunkedRequest(new byte[(int) CAP * 4]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Stands in for a message converter: it consumes the stream and rethrows whatever
        // the read raised, wrapped, exactly as Spring MVC does.
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp) throws IOException {
                try {
                    req.getInputStream().readAllBytes();
                } catch (RuntimeException ex) {
                    throw new IOException("failed to read request", ex);
                }
            }
        });

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    @Test
    @DisplayName("an undeclared body within the cap streams through untouched")
    void chunkedBodyWithinCapIsDispatched() throws ServletException, IOException {
        byte[] body = "{\"reportType\":\"MONTHLY\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = chunkedRequest(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(new String(chain.getRequest().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(new String(body, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an unrelated downstream failure is propagated, never reported as 413")
    void unrelatedFailureIsPropagated() {
        MockHttpServletRequest request = chunkedRequest("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp) {
                throw new IllegalStateException("downstream boom");
            }
        });

        assertThatThrownBy(() -> new RequestSizeLimitFilter(CAP).doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream boom");
        assertThat(response.getStatus()).isNotEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    @Test
    @DisplayName("a non-positive cap falls back to the documented default")
    void nonPositiveCapFallsBackToDefault() throws ServletException, IOException {
        MockHttpServletRequest request = requestWithLength(new byte[1024]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new RequestSizeLimitFilter(0).doFilter(request, response, chain);

        assertThat(RequestSizeLimitFilter.DEFAULT_MAX_BODY_BYTES).isEqualTo(256L * 1024L);
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("the cap also applies when the body is consumed through the character reader")
    void readerPathIsAlsoCapped() throws ServletException, IOException {
        MockHttpServletRequest request = chunkedRequest(new byte[(int) CAP * 4]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse resp) throws IOException {
                // Drain through the character reader rather than the byte stream, so the
                // reader path of the wrapper is the one that trips the cap.
                char[] sink = new char[(int) CAP * 4];
                while (req.getReader().read(sink) != -1) {
                    continue;
                }
            }
        });

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    /**
     * :purpose: Clear the correlation-id context so it cannot leak into another test.
     */
    @AfterEach
    void clearCorrelationId() {
        CorrelationIdContext.clear();
    }

    @Test
    @DisplayName("the refused response keeps the correlation id the observability filter set")
    void refusedResponseKeepsTheCorrelationId() throws Exception {
        CorrelationIdContext.setCorrelationId("11111111-2222-3333-4444-555555555555");
        MockHttpServletRequest request = requestWithLength(new byte[(int) CAP + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // The observability filter runs first and has already written the header; the
        // reset performed while refusing the request must not lose it.
        response.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, "11111111-2222-3333-4444-555555555555");
        MockFilterChain chain = new MockFilterChain();

        new RequestSizeLimitFilter(CAP).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("no correlation id is invented when none was established")
    void doesNotInventACorrelationId() throws Exception {
        MockHttpServletRequest request = requestWithLength(new byte[(int) CAP + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestSizeLimitFilter(CAP).doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
    }
}
