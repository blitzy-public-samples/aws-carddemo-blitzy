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
package com.carddemo.common.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * :purpose: Answer a request the Spring Security request firewall refuses with the same
 *     error envelope every other failure carries. ``FilterChainProxy`` catches the
 *     ``RequestRejectedException`` itself and delegates to whichever
 *     {@link RequestRejectedHandler} is installed, so this — not a wrapping servlet filter
 *     — is the point where the refusal can still be answered.
 * :output: A ``400`` response carrying the shared refusal envelope.
 * :note: Replaces Spring Security's default ``HttpStatusRequestRejectedHandler``, which
 *     calls ``sendError``. That defers the body to the container's ERROR dispatch, which
 *     Tomcat runs after the filter chain has unwound — by which point the correlation-id
 *     filter has cleared the MDC, so the refusal arrived with no ``message``, no
 *     ``errorCode``, no ``traceId`` and no ``correlationId``: a four-member document
 *     produced by a component that knows nothing about this application. Completing the
 *     response here keeps it inside the request scope where those ids still exist.
 * :note: The envelope names nothing about WHY the request was refused. A firewall rejection
 *     means the request was malformed at a level the application never interprets — an
 *     encoded path traversal, a control character in the URL, a header the parser would not
 *     accept — and naming the check that refused it would let a caller map the boundary one
 *     probe at a time. The reason is logged instead.
 */
public class RequestRejectedEnvelopeHandler implements RequestRejectedHandler {

    /** :purpose: Logger for the refused request, carrying the reason the caller is not told. */
    private static final Logger log = LoggerFactory.getLogger(RequestRejectedEnvelopeHandler.class);

    /**
     * :purpose: Complete the refused request with the shared envelope.
     * :param request: the refused request; used for its path only.
     * :param response: the response to complete.
     * :param requestRejectedException: the rejection, whose reason is logged, not returned.
     * :raises IOException: if the envelope cannot be written.
     * :raises ServletException: never raised; declared by the interface.
     */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       RequestRejectedException requestRejectedException)
            throws IOException, ServletException {
        log.warn("Request rejected by the request firewall: method={} reason={}",
                request.getMethod(), requestRejectedException.getMessage());
        // The firewall decides before FilterChainProxy enters the security chain, so
        // HeaderWriterFilter has not run and nothing else will supply a cache directive or the
        // sniffing guard on this response; the writer's outside-the-chain entry point does.
        RefusalEnvelopeWriter.writeOutsideSecurityChain(request, response, HttpStatus.BAD_REQUEST,
                RefusalEnvelopeWriter.CODE_REQUEST_REJECTED,
                RefusalEnvelopeWriter.MSG_REQUEST_REJECTED);
    }
}
