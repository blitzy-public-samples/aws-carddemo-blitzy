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
package com.aws.carddemo.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@link AuthenticationEntryPoint} that renders an authentication failure (missing or
 * invalid credentials) as an RFC&nbsp;7807 {@code application/problem+json}
 * {@code 401 Unauthorized} response instead of Spring Security's default empty body.
 *
 * <p>Spring Security invokes an entry point from inside the filter chain &mdash;
 * before the {@code DispatcherServlet} &mdash; so the {@code GlobalExceptionHandler}
 * cannot shape this response. Wiring this entry point onto both the HTTP&nbsp;Basic
 * filter (for a failed credential) and the {@code ExceptionTranslationFilter} (for an
 * anonymous request to a protected resource) makes every {@code 401} carry the same
 * problem-detail body &mdash; including the per-request {@code correlationId} &mdash;
 * as the rest of the CardDemo API.</p>
 *
 * <p>Because the API authenticates with HTTP&nbsp;Basic, the standard
 * {@code WWW-Authenticate: Basic} challenge header is preserved so the response
 * remains a well-formed {@code 401} (RFC&nbsp;7235); only the previously empty body is
 * augmented. No credential, and no client-supplied value, is echoed into the body or
 * any log line.</p>
 *
 * @see ProblemDetailAccessDeniedHandler
 * @see ProblemDetailHttpWriter
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * The HTTP&nbsp;Basic authentication realm advertised in the
     * {@code WWW-Authenticate} challenge. A fixed, non-sensitive label identifying the
     * protection space.
     */
    private static final String REALM = "CardDemo";

    /**
     * Boot-configured Jackson mapper used to serialize the {@code ProblemDetail}. It
     * carries Spring's {@code ProblemDetail} support, so the {@code correlationId}
     * extension property is flattened to the top level of the JSON body.
     */
    private final ObjectMapper objectMapper;

    /**
     * Creates the entry point with the shared, Boot-configured Jackson mapper.
     *
     * @param objectMapper the application {@link ObjectMapper}; must not be {@code null}
     */
    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Commences an authentication scheme: sets the HTTP&nbsp;Basic
     * {@code WWW-Authenticate} challenge header and writes an RFC&nbsp;7807
     * {@code 401 Unauthorized} problem-detail body carrying the correlation ID.
     *
     * @param request       the request that failed authentication
     * @param response      the response to render the 401 onto
     * @param authException the cause of the authentication failure (never echoed)
     * @throws IOException if writing to the response fails
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
        ProblemDetailHttpWriter.write(request, response, objectMapper, HttpStatus.UNAUTHORIZED,
                "Unauthorized", "Authentication is required to access this resource.");
    }
}
