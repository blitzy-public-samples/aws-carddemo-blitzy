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


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * :purpose: Answer an unauthenticated request to a protected resource with HTTP
 *     ``401 Unauthorized`` and write one audit record for it. It replaces Spring
 *     Security's default ``Http403ForbiddenEntryPoint`` (which returns an empty
 *     ``403`` and hides genuine failures from operators) on every CardDemo chain.
 * :output: A ``401`` response carrying the shared refusal envelope, plus one ``WARN``
 *     audit record naming the source address, method, path, and correlation id.
 * :note: The envelope is minimal in CONTENT and identical in SHAPE to every other error
 *     response: a fixed message, the request path with any PAN redacted, and the trace and
 *     correlation ids. It names no resource and gives no reason beyond the status, so an
 *     unauthenticated caller still learns nothing it did not already know — while a client
 *     can finally say why the screen was returned to sign-on, and an operator has the id
 *     that ties the refusal to the audit record above.
 */
public class AuditingAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /**
     * :purpose: Log the denial and send ``401``.
     * :param request: the request that could not be authenticated.
     * :param response: the response to complete with ``401``.
     * :param authException: the triggering Spring Security exception.
     * :raises IOException: if the error response cannot be written.
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        SecurityAuditLogger.authenticationRequired(request,
                authException == null ? "unauthenticated" : authException.getClass().getSimpleName());
        RefusalEnvelopeWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                RefusalEnvelopeWriter.CODE_AUTHENTICATION_REQUIRED,
                RefusalEnvelopeWriter.MSG_AUTHENTICATION_REQUIRED);
    }
}
