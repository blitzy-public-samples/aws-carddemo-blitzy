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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * {@link AccessDeniedHandler} that renders an authorization failure (an authenticated
 * principal lacking the required role) as an RFC&nbsp;7807
 * {@code application/problem+json} {@code 403 Forbidden} response instead of Spring
 * Boot's default {@code {timestamp,status,error,path}} body.
 *
 * <p>Authorization failures are raised inside the Spring Security filter chain &mdash;
 * before the {@code DispatcherServlet} &mdash; so the {@code GlobalExceptionHandler}
 * cannot shape this response. Wiring this handler into the
 * {@code ExceptionTranslationFilter} makes every {@code 403} (for example a
 * {@code ROLE_USER} principal calling an {@code /api/v1/admin/**} endpoint that
 * requires {@code ROLE_ADMIN}) carry the same problem-detail body &mdash; including
 * the per-request {@code correlationId} &mdash; as the rest of the CardDemo API.</p>
 *
 * <p>A fixed, non-revealing detail is returned; neither the principal's identity nor
 * the required authority is disclosed in the body or any log line.</p>
 *
 * @see ProblemDetailAuthenticationEntryPoint
 * @see ProblemDetailHttpWriter
 */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    /**
     * Boot-configured Jackson mapper used to serialize the {@code ProblemDetail}. It
     * carries Spring's {@code ProblemDetail} support, so the {@code correlationId}
     * extension property is flattened to the top level of the JSON body.
     */
    private final ObjectMapper objectMapper;

    /**
     * Creates the handler with the shared, Boot-configured Jackson mapper.
     *
     * @param objectMapper the application {@link ObjectMapper}; must not be {@code null}
     */
    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Handles an access-denied failure by writing an RFC&nbsp;7807 {@code 403
     * Forbidden} problem-detail body carrying the correlation ID.
     *
     * @param request               the request that was denied
     * @param response              the response to render the 403 onto
     * @param accessDeniedException the cause of the authorization failure (never echoed)
     * @throws IOException if writing to the response fails
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetailHttpWriter.write(request, response, objectMapper, HttpStatus.FORBIDDEN,
                "Forbidden", "You do not have permission to access this resource.");
    }
}
