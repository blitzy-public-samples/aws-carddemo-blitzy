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
package com.aws.carddemo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.aws.carddemo.exception.RequestBodyTooLargeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Unit tests for {@link RequestBodySizeLimitFilter} (QA finding F-P7-JSON, decision log D67).
 *
 * <p>Follows the project's established filter-test idiom (see
 * {@code CorrelationIdFilterTest}): the filter is exercised directly against
 * {@link MockHttpServletRequest}/{@link MockHttpServletResponse} and a lambda
 * {@link FilterChain}, with no Spring context, so each behavior is asserted deterministically.
 * The {@link ObjectMapper} is built with {@link Jackson2ObjectMapperBuilder} so the
 * {@code ProblemDetail} Jackson support (flattening the {@code correlationId} extension property
 * to top level) matches the Boot-configured mapper injected in production.</p>
 *
 * <p>Coverage: the fast path (declared {@code Content-Length} over the limit &rarr; 413
 * {@code application/problem+json} written directly, chain never invoked, correlation ID carried);
 * a within-limit body passing through unchanged; and both streaming paths (an understated/unknown
 * length consumed via {@code getInputStream()} and via {@code getReader()}) raising
 * {@link RequestBodyTooLargeException} the moment the limit is crossed.</p>
 */
class RequestBodySizeLimitFilterTest {

    /** Small limit so tiny fixtures exercise the boundary without large buffers. */
    private static final long LIMIT_BYTES = 32L;

    /** MDC key the observability filter uses; mirrored here to assert propagation. */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Boot-equivalent mapper (registers the ProblemDetail mixin) for the 413 body. */
    private static final ObjectMapper OBJECT_MAPPER = Jackson2ObjectMapperBuilder.json().build();

    /** Plain mapper for parsing the written response body back into a tree. */
    private static final ObjectMapper READER = new ObjectMapper();

    private final RequestBodySizeLimitFilter filter =
            new RequestBodySizeLimitFilter(OBJECT_MAPPER, LIMIT_BYTES);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * A request whose declared {@code Content-Length} already exceeds the limit is rejected up
     * front: the chain is never invoked, and a 413 {@code application/problem+json} body is written
     * with the standard fields plus the per-request correlation ID from the MDC.
     */
    @Test
    void fastPathRejectsDeclaredOversizeBodyWith413ProblemJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounts");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 200 bytes; MockHttpServletRequest reports content length as the content array length.
        request.setContent("x".repeat(200).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(CORRELATION_ID_MDC_KEY, "cid-abc-123");

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);

        assertThat(chainCalled).as("oversized request must be rejected before the chain").isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode body = READER.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(413);
        assertThat(body.path("title").asText()).isEqualTo("Payload Too Large");
        assertThat(body.path("detail").asText()).isEqualTo("The request body exceeds the maximum permitted size.");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/accounts");
        assertThat(body.path("correlationId").asText()).isEqualTo("cid-abc-123");
    }

    /**
     * A body within the limit is forwarded unchanged: the chain runs and the wrapped input stream
     * yields exactly the original bytes, proving the size-bounding wrapper is transparent below the
     * threshold and does not corrupt normal request reading.
     */
    @Test
    void withinLimitBodyPassesThroughUnchanged() throws Exception {
        byte[] payload = "{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8); // 9 bytes < 32
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounts");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<byte[]> readBack = new AtomicReference<>();
        FilterChain chain = (req, res) -> readBack.set(((HttpServletRequest) req).getInputStream().readAllBytes());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(readBack.get()).isEqualTo(payload);
    }

    /**
     * When the length is unknown (chunked / understated) so the fast path cannot fire, consuming the
     * body via {@code getInputStream()} past the limit raises {@link RequestBodyTooLargeException}
     * (an {@link java.io.IOException}) &mdash; the signal the HTTP message converter wraps and
     * {@code GlobalExceptionHandler} maps to 413.
     */
    @Test
    void streamingInputStreamPathThrowsWhenBodyExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounts") {
            @Override
            public long getContentLengthLong() {
                return -1; // unknown length: force the streaming path
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("x".repeat(200).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> ((HttpServletRequest) req).getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    /**
     * The {@code getReader()} consumption path is bounded identically to {@code getInputStream()}:
     * an understated/unknown-length oversized body read through the reader raises
     * {@link RequestBodyTooLargeException}.
     */
    @Test
    void streamingReaderPathThrowsWhenBodyExceedsLimit() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/accounts") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent("x".repeat(200).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            BufferedReader reader = ((HttpServletRequest) req).getReader();
            while (reader.read() != -1) {
                // drain until the counting stream trips the limit
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }
}
