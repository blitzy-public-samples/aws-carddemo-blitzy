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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

/**
 * Unit tests for {@link ProblemDetailAuthenticationEntryPoint}, the Spring Security entry
 * point that renders authentication failures (missing/invalid credentials) as an
 * RFC&nbsp;7807 {@code application/problem+json} {@code 401 Unauthorized} body.
 *
 * <p>These tests lock down the error-contract-consistency fix (finding F-MINOR-2): a 401
 * raised inside the security filter chain &mdash; beyond the reach of the
 * {@code @RestControllerAdvice GlobalExceptionHandler} &mdash; must still carry the same
 * problem-detail shape (including the per-request {@code correlationId}) as every other
 * error surface of the API. They also lock down the Swagger-UX fix (finding F4): the 401
 * must <em>not</em> carry a {@code WWW-Authenticate: Basic} challenge, because a browser
 * would intercept that standard challenge with its own native credential dialog and
 * prevent XHR/{@code fetch} clients (Swagger UI) from rendering the problem body inline.</p>
 *
 * <p>The {@link ObjectMapper} under test is built with {@link Jackson2ObjectMapperBuilder}
 * exactly as Spring Boot builds the application mapper, so it registers the
 * {@code ProblemDetailJacksonMixin} that flattens the {@code correlationId} extension
 * property to the top level of the JSON body &mdash; the same serialization the running
 * application performs.</p>
 */
@DisplayName("ProblemDetailAuthenticationEntryPoint")
class ProblemDetailAuthenticationEntryPointTest {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Boot-equivalent mapper (registers the ProblemDetail mixin for top-level flattening). */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private final ProblemDetailAuthenticationEntryPoint entryPoint =
            new ProblemDetailAuthenticationEntryPoint(objectMapper);

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("writes an RFC-7807 401 body with the correlation ID and no browser Basic challenge")
    void writesRfc7807UnauthorizedWithCorrelationId() throws Exception {
        MDC.put(CORRELATION_ID_MDC_KEY, "corr-401-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/menu");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthenticationException authException = new BadCredentialsException("bad credentials");

        entryPoint.commence(request, response, authException);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        // F4: no WWW-Authenticate: Basic challenge, so browsers do not pop the native
        // credential dialog that blocks Swagger UI from rendering this body inline.
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("type").asText()).isEqualTo("about:blank");
        assertThat(body.path("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("detail").asText()).isEqualTo("Authentication is required to access this resource.");
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/menu");
        assertThat(body.path(CORRELATION_ID_MDC_KEY).asText()).isEqualTo("corr-401-abc");
    }

    @Test
    @DisplayName("omits the correlationId property when the MDC has no correlation ID")
    void omitsCorrelationIdWhenMdcAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad"));

        assertThat(response.getStatus()).isEqualTo(401);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.has(CORRELATION_ID_MDC_KEY)).isFalse();
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("instance").asText()).isEqualTo("/api/v1/accounts/1");
    }

    @Test
    @DisplayName("omits the browser-triggering WWW-Authenticate: Basic challenge header (finding F4)")
    void omitsBrowserBasicChallengeHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/cards");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad"));

        // The 401 is still well-formed (correct status + RFC-7807 body), but it carries no
        // WWW-Authenticate: Basic challenge. That challenge would make a browser show its own
        // native credential prompt, which blocks Swagger UI's XHR from reading the response and
        // leaves "Try it out" hanging in a perpetual loading state (finding F4).
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }
}
