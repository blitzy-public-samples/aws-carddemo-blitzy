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
package com.carddemo.gateway.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * :purpose: Publish the current CSRF token so a client that cannot read the
 *     ``XSRF-TOKEN`` cookie (for example a non-browser client or a test harness) can
 *     still obtain the value it must echo in the ``X-XSRF-TOKEN`` header on
 *     state-changing requests. Browsers do not need this endpoint: the cookie is
 *     issued on every response.
 * :output: ``GET /csrf`` returning the token's header name and value.
 */
@RestController
public class CsrfController {

    /**
     * :purpose: Return the CSRF token bound to the current request.
     * :param csrfToken: the token resolved by Spring Security for this request.
     * :returns: the token view carrying the expected header name and token value.
     */
    @GetMapping("/csrf")
    public CsrfTokenView csrf(CsrfToken csrfToken) {
        return new CsrfTokenView(csrfToken.getHeaderName(), csrfToken.getParameterName(), csrfToken.getToken());
    }

    /**
     * :purpose: Response view of a CSRF token.
     * :param headerName: request header the token must be sent in.
     * :param parameterName: form parameter the token may be sent in.
     * :param token: the token value.
     */
    public record CsrfTokenView(String headerName, String parameterName, String token) {
    }
}
