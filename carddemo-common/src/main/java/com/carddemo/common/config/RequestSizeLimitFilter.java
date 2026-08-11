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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Bound the size of an inbound request body so an unauthenticated caller cannot
 *     exhaust heap, threads, or log volume with an oversized payload (CWE-770). CardDemo
 *     request bodies model 3270 screen fields and are at most a few kilobytes, so the default
 *     cap is generous while still rejecting abusive payloads immediately.
 * :output: The original response for a request within the cap, or an empty ``413 Content
 *     Too Large`` for a request that declares or streams more than the configured number of
 *     bytes.
 * :note: Both shapes are covered: a declared ``Content-Length`` is checked before the body
 *     is read at all, and a chunked request (no declared length) is counted while it streams,
 *     so the limit cannot be bypassed by omitting the header.
 * :note: A streamed body is normally consumed by a message converter inside the
 *     dispatcher, which wraps the abort signal in its own exception and lets Spring resolve it
 *     there, so it never reaches this filter. {@link GlobalExceptionHandler} therefore
 *     classifies that wrapped form through {@link #isSizeExceededSignal} and reports the same
 *     ``413``; this filter's own handler covers the remaining case where the signal escapes
 *     the dispatcher.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    /**
     * :purpose: Default maximum accepted request-body size in bytes (256 KiB).
     */
    public static final long DEFAULT_MAX_BODY_BYTES = 256L * 1024L;

    private final long maxBodyBytes;

    /**
     * :purpose: Create the filter with the default cap.
     */
    public RequestSizeLimitFilter() {
        this(DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * :purpose: Create the filter with an explicit cap.
     * :param maxBodyBytes: maximum accepted body size in bytes; values below one fall
     *     back to {@link #DEFAULT_MAX_BODY_BYTES}.
     */
    public RequestSizeLimitFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes < 1 ? DEFAULT_MAX_BODY_BYTES : maxBodyBytes;
    }

    /**
     * :purpose: Reject an oversized request before it is dispatched, and cap the bytes
     *     a chunked request may stream.
     * :param request: the current HTTP request.
     * :param response: the current HTTP response.
     * :param filterChain: the remainder of the filter chain.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            reject(response, request.getRequestURI());
            return;
        }
        if (declaredLength >= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        // No Content-Length (chunked transfer): count the bytes as they are read and
        // fail the read once the cap is exceeded.
        LimitedRequestWrapper wrapper = new LimitedRequestWrapper(request, maxBodyBytes);
        try {
            filterChain.doFilter(wrapper, response);
        } catch (IOException | ServletException | RuntimeException ex) {
            // The read happens inside the handler, so the signal reaches this filter
            // wrapped by whatever consumed the stream (a message converter, for example).
            // The whole cause chain is inspected so an oversized chunked body is still
            // answered with 413 rather than surfacing as a 500.
            if (!isSizeExceededSignal(ex)) {
                throw ex;
            }
            reject(response, request.getRequestURI());
        }
    }

    /**
     * :purpose: Detect the oversized-body signal anywhere in a cause chain.
     * :param throwable: the exception that escaped the chain.
     * :output: ``true`` when the chain was aborted because the body exceeded the cap.
     * :note: Also used by {@link GlobalExceptionHandler}: a message converter that wraps
     *     the signal produces an exception Spring resolves inside the dispatcher, so it
     *     never reaches this filter's own handler and must be classified there instead.
     */
    static boolean isSizeExceededSignal(Throwable throwable) {
        Throwable current = throwable;
        // Bounded walk: a malformed chain cannot make this loop forever.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof RequestSizeExceededException) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /**
     * :purpose: Complete the response with ``413`` without starting the container
     *     ``ERROR`` dispatch.
     * :param response: the response to complete.
     * :param requestPath: the request path echoed in the error envelope.
     */
    private void reject(HttpServletResponse response, String requestPath) {
        respond(response, HttpStatus.CONTENT_TOO_LARGE, requestPath);
    }

    /**
     * :purpose: Complete the response with the supplied status without starting the
     *     container ``ERROR`` dispatch.
     * :param response: the response to complete.
     * :param status: the status to report.
     * :param requestPath: the request path echoed in the error envelope.
     */
    private void respond(HttpServletResponse response, HttpStatus status, String requestPath) {
        if (response.isCommitted()) {
            return;
        }
        // reset() discards any partially written content, and with it the correlation id
        // the observability filter had already placed on the response. A refused request
        // is exactly the kind of event an operator has to correlate with the audit log,
        // so the id is restored after the reset.
        String correlationId = CorrelationIdContext.getCorrelationId();
        response.reset();
        if (correlationId != null && !correlationId.isBlank()) {
            response.setHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        }
        // The refusal is answered with the documented envelope rather than a bare status
        // with no body, so a client parses ONE error shape for every failure of this API.
        ErrorEnvelopeWriter.write(response, status, requestPath);
    }


    /**
     * :purpose: Signals that a streamed request body exceeded the configured cap.
     */
    static final class RequestSizeExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * :purpose: Create the signal with a fixed, non-sensitive message.
         */
        RequestSizeExceededException() {
            super("Request body exceeds the configured maximum size");
        }
    }
}
