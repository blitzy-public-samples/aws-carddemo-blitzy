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
package com.aws.carddemo.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link ProblemDetailAccessDeniedHandler}, the Spring Security handler
 * that renders authorization failures (an authenticated principal lacking the required
 * role) as an RFC&nbsp;7807 {@code application/problem+json} {@code 403 Forbidden} body.
 *
 * <p>These tests lock down the error-contract-consistency fix (finding F-MINOR-2): a 403
 * raised inside the security filter chain &mdash; beyond the reach of the
 * {@code @RestControllerAdvice GlobalExceptionHandler} &mdash; must still carry the same
 * problem-detail shape (including the per-request {@code correlationId}) as every other
 * error surface of the API. Unlike the 401 entry point, no {@code WWW-Authenticate}
 * challenge is emitted for a 403.</p>
 *
 * <p>The {@link ObjectMapper} under test is built with {@link Jackson2ObjectMapperBuilder}
 * exactly as Spring Boot builds the application mapper, so it registers the
 * {@code ProblemDetailJacksonMixin} that flattens the {@code correlationId} extension
 * property to the top level of the JSON body.</p>
 */
@DisplayName("ProblemDetailAccessDeniedHandler")
class ProblemDetailAccessDeniedHandlerTest {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Boot-equivalent mapper (registers the ProblemDetail mixin for top-level flattening). */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private final ProblemDetailAccessDeniedHandler handler =
            new ProblemDetailAccessDeniedHandler(objectMapper);

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("writes an RFC-7807 403 body with the correlation ID and no Basic challenge")
    void writesRfc7807ForbiddenWithCorrelationId() throws Exception {
        MDC.put(CORRELATION_ID_MDC_KEY, "corr-403-xyz");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("Access is denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        // A 403 is not an authentication challenge, so no WWW-Authenticate header is set.
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("title").asText()).isEqualTo("Forbidden");
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("detail").asText()).isEqualTo("You do not have permission to access this resource.");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/admin/users");
        assertThat(body.path(CORRELATION_ID_MDC_KEY).asText()).isEqualTo("corr-403-xyz");
    }

    @Test
    @DisplayName("omits the correlationId property when the MDC has no correlation ID")
    void omitsCorrelationIdWhenMdcAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.has(CORRELATION_ID_MDC_KEY)).isFalse();
        assertThat(body.path("status").asInt()).isEqualTo(403);
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/admin/users");
    }
}
