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

import com.carddemo.common.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * :purpose: Translate the Spring Security failures that surface INSIDE the
 *  application - an authorization denial raised by a method-security or service
 *  check, and an authentication failure raised while handling a request - into the
 *  documented {@link ErrorResponse} envelope, so a denied or unauthenticated
 *  request is never answered with an empty body.
 * :output: A ``@RestControllerAdvice`` mapping
 *  {@link AccessDeniedException} to ``403 Forbidden`` and
 *  {@link AuthenticationException} to ``401 Unauthorized``, both carrying the
 *  shared envelope with the status reason phrase and the current trace id. No
 *  exception message is echoed, so an authorization probe learns nothing.
 * :note: Guarded by ``@ConditionalOnClass`` because five CardDemo services
 *  deliberately carry no Spring Security on their classpath (the gateway performs
 *  authorization and the shared session holds no security types); the advice
 *  registers only where Spring Security is present. Import it alongside
 *  {@link GlobalExceptionHandler}; it is not auto-configured. Denials produced by
 *  the security FILTER chain never reach an advice and are answered by the chain's
 *  own entry point / access-denied handler.
 */
@RestControllerAdvice
@ConditionalOnClass(name = "org.springframework.security.access.AccessDeniedException")
public class SecurityExceptionHandler {

    /** :purpose: Logger for in-application authorization and authentication failures. */
    private static final Logger log = LoggerFactory.getLogger(SecurityExceptionHandler.class);

    /**
     * :purpose: Map an in-application authorization denial to HTTP 403 with the
     *  documented envelope.
     * :param ex: the access-denied exception.
     * :param request: the current web request.
     * :returns: a ``403 Forbidden`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        HttpStatus status = HttpStatus.FORBIDDEN;
        log.warn("Access denied at {}: {}", ErrorResponseFactory.path(request), ex.getMessage());
        return ResponseEntity.status(status)
                .body(ErrorResponseFactory.build(status, status.getReasonPhrase(), request));
    }

    /**
     * :purpose: Map an authentication failure raised while handling a request to
     *  HTTP 401 with the documented envelope.
     * :param ex: the authentication exception.
     * :param request: the current web request.
     * :returns: a ``401 Unauthorized`` {@link ResponseEntity} wrapping the error body.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailure(AuthenticationException ex,
                                                                     WebRequest request) {
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        log.warn("Authentication failed at {}: {}", ErrorResponseFactory.path(request), ex.getMessage());
        return ResponseEntity.status(status)
                .body(ErrorResponseFactory.build(status, status.getReasonPhrase(), request));
    }
}
