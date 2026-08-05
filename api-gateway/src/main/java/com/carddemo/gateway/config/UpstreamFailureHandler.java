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
package com.carddemo.gateway.config;

import com.carddemo.common.dto.ErrorResponse;
import com.carddemo.common.security.SensitiveDataMasker;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

/**
 * :purpose: Translate a failure to reach a routed upstream service into ``503 Service
 *  Unavailable``. Routing raises {@link ResourceAccessException} when the upstream refuses
 *  the connection, is not listening, or times out; unhandled, that surfaced as ``500
 *  Internal Server Error``, which tells the caller the gateway itself is broken and is not
 *  retryable by convention, when in fact the gateway is healthy and the condition is
 *  transient.
 * :output: A ``503`` carrying the shared {@link ErrorResponse} JSON contract with a
 *  generic description and the request path. The upstream host, port and exception detail
 *  are logged, never returned, so the response reveals nothing about the internal
 *  topology.
 * :note: Ordered ahead of the shared ``GlobalExceptionHandler`` so this more specific
 *  mapping wins for routing failures while every other exception keeps its existing
 *  behaviour. A retryable condition also carries ``Retry-After``.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UpstreamFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamFailureHandler.class);

    /** Seconds a client is advised to wait before retrying a routed request. */
    private static final String RETRY_AFTER_SECONDS = "5";

    /** Description returned to the caller; deliberately free of internal detail. */
    private static final String UPSTREAM_UNAVAILABLE_MESSAGE =
            "The requested service is temporarily unavailable. Please try again.";

    /**
     * :purpose: Map an unreachable or unresponsive upstream to ``503``.
     * :param exception: the routing failure raised while contacting the upstream.
     * :param request: the current request, used only for its path.
     * :returns: a ``503`` response carrying the shared error contract and ``Retry-After``.
     */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleUpstreamUnavailable(ResourceAccessException exception,
                                                                   HttpServletRequest request) {
        String path = request.getRequestURI();
        // The upstream target and the underlying cause are operator information: logged
        // here, never placed in the response body. A card path embeds the PAN, so the path is
        // redacted for both the log record and the envelope.
        String redactedPath = SensitiveDataMasker.maskPan(path);
        LOGGER.error("Upstream service unavailable for {}: {}",
                redactedPath, exception.getMessage());
        // here, never placed in the response body.
        // Masked for the LOG only: a card path embeds the PAN and must not be retained in a
        // log file; the response body keeps the URI the caller supplied.
        LOGGER.error("Upstream service unavailable for {}: {}",
                SensitiveDataMasker.maskPan(path), exception.getMessage());
        ErrorResponse body = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(),
                UPSTREAM_UNAVAILABLE_MESSAGE,
                redactedPath);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(org.springframework.http.HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(body);
    }

}
