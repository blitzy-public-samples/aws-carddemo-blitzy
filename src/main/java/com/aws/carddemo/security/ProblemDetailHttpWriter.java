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

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Shared helper that serializes an RFC&nbsp;7807 {@link ProblemDetail} directly onto a
 * servlet {@link HttpServletResponse}, used by the CardDemo Spring Security
 * exception handlers ({@link ProblemDetailAuthenticationEntryPoint} for 401 and
 * {@link ProblemDetailAccessDeniedHandler} for 403).
 *
 * <p><strong>Why a manual writer is required here.</strong> Authentication and
 * authorization failures are raised inside the Spring Security filter chain,
 * <em>before</em> the {@code DispatcherServlet} runs, so they never reach the
 * {@code @RestControllerAdvice} {@code GlobalExceptionHandler}. Left to the
 * framework defaults, a missing credential yields an empty-body {@code 401} and a
 * forbidden request yields Spring Boot's generic
 * {@code {timestamp,status,error,path}} body &mdash; neither of which matches the
 * RFC&nbsp;7807 {@code application/problem+json} contract the rest of the API uses.
 * This helper reproduces that contract at the filter level so <em>every</em> error
 * surface of the API returns the same problem-detail shape, including the
 * per-request correlation ID.</p>
 *
 * <p>The body it writes is field-for-field consistent with
 * {@code GlobalExceptionHandler#problem(...)}: {@code type} defaults to
 * {@code about:blank}, {@code status}/{@code title}/{@code detail} are set from the
 * arguments, {@code instance} is the request URI, and &mdash; when the observability
 * {@code CorrelationIdFilter} has placed a correlation ID in the SLF4J {@link MDC}
 * (it runs at {@code HIGHEST_PRECEDENCE}, ahead of the security filters) &mdash; a
 * {@code correlationId} extension property is added so a client can quote it. The
 * lookup is defensive: a missing ID simply yields a body without that property.</p>
 *
 * <p>This is a package-private, stateless utility (no injected collaborators); the
 * {@link ObjectMapper} is passed in by the caller so the Boot-configured mapper
 * &mdash; which carries the {@code ProblemDetail} Jackson support that flattens the
 * extension properties to top level &mdash; is reused rather than re-created.</p>
 */
final class ProblemDetailHttpWriter {

    /**
     * MDC key under which the observability {@code CorrelationIdFilter} stores the
     * per-request correlation ID. Kept as a local literal (rather than importing the
     * observability package) so the security package carries no compile-time
     * dependency on the observability layer; the value must stay in sync with
     * {@code CorrelationIdFilter.CORRELATION_ID_MDC_KEY} and the
     * {@code GlobalExceptionHandler} constant of the same value.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Utility class; not instantiable. */
    private ProblemDetailHttpWriter() {
    }

    /**
     * Builds an RFC&nbsp;7807 {@link ProblemDetail} for the given status/title/detail,
     * attaches the request URI as {@code instance} and (when present) the correlation
     * ID from the {@link MDC}, and writes it to the response as
     * {@code application/problem+json} (UTF-8) with the matching HTTP status.
     *
     * @param request      the current request (its URI becomes the problem {@code instance})
     * @param response     the response to write the problem detail onto
     * @param objectMapper the Boot-configured mapper used to serialize the problem detail
     * @param status       the HTTP status to report (also drives the response status code)
     * @param title        a short, human-readable summary of the problem type
     * @param detail       a fixed, non-sensitive explanation of this occurrence
     * @throws IOException if writing to the response fails
     */
    static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper objectMapper,
                      HttpStatus status, String title, String detail) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CORRELATION_ID_MDC_KEY, correlationId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}
