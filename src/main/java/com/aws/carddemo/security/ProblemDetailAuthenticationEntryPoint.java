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
 * <p>The API authenticates with HTTP&nbsp;Basic, but this entry point deliberately does
 * <em>not</em> emit a {@code WWW-Authenticate: Basic} challenge header on the 401. A
 * browser treats that standard challenge as an instruction to display its own native
 * credential dialog, which intercepts the response before an XHR-based client (most
 * visibly the springdoc Swagger UI) can read it &mdash; the "Try it out" call then hangs
 * in a perpetual loading state until the native dialog is dismissed. Omitting the
 * challenge lets Swagger UI (and any {@code fetch}/XHR consumer) render this RFC&nbsp;7807
 * {@code 401} body inline exactly as it renders the 403/404/400 problem responses, while
 * the legitimate HTTP&nbsp;Basic clients are unaffected: they send the {@code Authorization}
 * header proactively (the Swagger "Authorize" dialog and {@code curl -u} do not rely on a
 * server challenge to decide to authenticate). The {@code 401} therefore carries the same
 * problem-detail body &mdash; including the per-request {@code correlationId} &mdash; as
 * the rest of the API, only without the browser-triggering challenge. No credential, and
 * no client-supplied value, is echoed into the body or any log line.</p>
 *
 * @see ProblemDetailAccessDeniedHandler
 * @see ProblemDetailHttpWriter
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

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
     * Commences the authentication failure response: writes an RFC&nbsp;7807
     * {@code 401 Unauthorized} problem-detail body carrying the correlation ID.
     *
     * <p>No {@code WWW-Authenticate: Basic} challenge header is set: a browser would
     * otherwise intercept the standard Basic challenge with its own native credential
     * dialog and block XHR/{@code fetch} consumers (e.g. Swagger UI's "Try it out") from
     * ever reading this body. Suppressing the challenge lets those clients render the
     * problem detail inline; genuine HTTP&nbsp;Basic clients send the {@code Authorization}
     * header proactively and do not depend on the challenge (see the class Javadoc).</p>
     *
     * @param request       the request that failed authentication
     * @param response      the response to render the 401 onto
     * @param authException the cause of the authentication failure (never echoed)
     * @throws IOException if writing to the response fails
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ProblemDetailHttpWriter.write(request, response, objectMapper, HttpStatus.UNAUTHORIZED,
                "Unauthorized", "Authentication is required to access this resource.");
    }
}
