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
import com.carddemo.common.security.SensitiveDataMasker;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.WebRequest;

/**
 * :purpose: Assemble the single {@link ErrorResponse} envelope every CardDemo web
 *  service returns, so the advices and the error controller cannot drift into
 *  different shapes. It exists because the error contract is documented once and
 *  must be produced identically by the domain advice, the framework-exception
 *  advice, the security advice, and the container error dispatch.
 * :output: Static factory helpers that populate the status, reason phrase,
 *  message, request path and trace/correlation id of the envelope.
 */
final class ErrorResponseFactory {

    /** :purpose: MDC key holding the Micrometer Tracing trace id. */
    private static final String MDC_TRACE_ID = "traceId";

    /** :purpose: Prefix Spring uses when describing a request by its URI. */
    private static final String URI_PREFIX = "uri=";

    /**
     * :purpose: Non-instantiable helper holder.
     */
    private ErrorResponseFactory() {
    }

    /**
     * :purpose: Build a populated envelope for a failed request.
     * :param status: the HTTP status mapped for the failure.
     * :param message: the non-sensitive detail message surfaced to the caller.
     * :param request: the current web request, used to derive the request path.
     * :returns: the populated {@link ErrorResponse}.
     */
    static ErrorResponse build(HttpStatus status, String message, WebRequest request) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                message, path(request));
        body.setTraceId(traceId());
        body.setCorrelationId(CorrelationIdContext.getCorrelationId());
        return body;
    }

    /**
     * :purpose: Derive the request URI from a {@link WebRequest} without the servlet API and
     *  redact the card-number segment it may carry, so the envelope's ``path`` can never echo
     *  a card number back to a client, a proxy log, or a browser history entry.
     * :param request: the current web request; may be ``null``.
     * :returns: the request path with the leading ``"uri="`` stripped and a
     *  ``/cards/{cardNumber}`` segment reduced to its last four digits, or the raw
     *  description when the prefix is absent.
     * :note: Only the card-number position is redacted. Masking every PAN-shaped digit run
     *  also hit the 16-character transaction id of ``/transactions/{id}``, so a ``404``
     *  reported a path that had never been requested.
     */
    static String path(WebRequest request) {
        if (request == null) {
            return null;
        }
        String description = request.getDescription(false);
        String uri = description != null && description.startsWith(URI_PREFIX)
                ? description.substring(URI_PREFIX.length())
                : description;
        return SensitiveDataMasker.maskPath(uri);
    }

    /**
     * :purpose: Resolve the DISTRIBUTED-TRACE id of the current request from the ``traceId``
     *  MDC entry published by Micrometer Tracing.
     * :returns: the current trace id, or ``null`` when the request was not traced.
     * :note: Returns ``null`` rather than falling back to the business correlation id; the
     *  envelope carries the two ids in their own ``traceId`` and ``correlationId`` fields.
     */
    static String traceId() {
        String traceId = MDC.get(MDC_TRACE_ID);
        return (traceId == null || traceId.isBlank()) ? null : traceId;
    }
}
