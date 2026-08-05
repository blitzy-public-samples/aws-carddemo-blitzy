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
package com.carddemo.auth.controller;

import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.service.AuthenticationService;
import com.carddemo.common.config.CorrelationIdContext;
import com.carddemo.common.security.SensitiveDataMasker;
import com.carddemo.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * :purpose: REST entry point for the CardDemo authentication microservice;
 *  exposes ``POST /auth/signon`` (CICS transaction ``CC00``, program
 *  ``COSGN00C``). Validates the sign-on request body, delegates credential
 *  verification to {@link AuthenticationService}, and returns the sign-on
 *  result on success or a ``401`` error body on authentication failure. This is
 *  a thin adapter: it holds no business logic and defines no user-facing message
 *  strings.
 */
@RestController
@RequestMapping("/auth")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    /**
     * :purpose: Construct the controller with its sign-on collaborator.
     * :param authenticationService: service that performs credential
     *  verification and publishes the externalized session context.
     */
    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * :purpose: Authenticate a sign-on request (CICS ``CC00``) by delegating to
     *  {@link AuthenticationService}.
     * :param request: sign-on credentials (user id and password).
     * :param httpRequest: current request; the service creates its session only
     *  after the credentials verify, so a rejected sign-on leaves none behind.
     * :returns: the sign-on response (user id, user type, redirect target) with
     *  HTTP 200.
     * :note: The parameter is the servlet request, not an ``HttpSession``: an
     *     ``HttpSession`` argument is resolved with ``getSession(true)`` before the
     *     handler body runs, so a session is created only on the success path here.
     *     See docs/decision-log.md.
     */
    @PostMapping("/signon")
    public SignonResponseDto signon(@Valid @RequestBody SignonRequestDto request,
                                    HttpServletRequest httpRequest) {
        return authenticationService.signon(request, httpRequest);
    }

    /**
     * :purpose: Shape the ``401 Unauthorized`` failure body for a
     *  {@link ResponseStatusException} raised by {@link AuthenticationService},
     *  surfacing the verbatim reason to the client.
     * :param ex: the status-bearing exception thrown by the service.
     * :param request: the current web request, used to derive the request path.
     * :returns: an {@link ErrorResponse} carrying the exception's status, reason
     *  phrase, verbatim message, request path, trace id and correlation id.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                ex.getReason(),
                extractPath(request));
        body.setTraceId(resolveTraceId());
        body.setCorrelationId(CorrelationIdContext.getCorrelationId());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /**
     * :purpose: Extract the request URI from the web-request description and redact any
     *     PAN-shaped segment, so the envelope's ``path`` can never echo a card number.
     * :param request: the current web request.
     * :returns: the PAN-redacted request path (for example ``/auth/signon``).
     */
    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        String uri = description != null && description.startsWith("uri=")
                ? description.substring(4)
                : description;
        return SensitiveDataMasker.maskPan(uri);
    }

    /**
     * :purpose: Resolve the DISTRIBUTED-TRACE id of the current request from the ``traceId`` MDC
     *     entry published by Micrometer Tracing.
     * :returns: the current trace id, or ``null`` when the request was not traced.
     * :note: Returns ``null`` rather than falling back to the correlation id; the envelope
     *     carries the two ids in their own ``traceId`` and ``correlationId`` fields.
     */
    /**
     * :purpose: Resolve the DISTRIBUTED-TRACE id of the current request from the ``traceId`` MDC
     *     entry published by Micrometer Tracing.
     * :returns: the current trace id, or ``null`` when the request was not traced.
     * :note: Deliberately no fallback to the correlation id: the envelope reports the two ids in
     *     their own fields (``traceId`` and ``correlationId``) so each value resolves where it
     *     actually exists - the trace backend and the log stream respectively.
     */
    private String resolveTraceId() {
        String traceId = MDC.get("traceId");
        return (traceId == null || traceId.isBlank()) ? null : traceId;
    }
}
