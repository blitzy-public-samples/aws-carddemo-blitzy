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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Force the deferred CSRF token to be resolved on every request so the
 *     ``XSRF-TOKEN`` cookie is always issued to the SPA. Spring Security loads the
 *     token lazily; without this filter the cookie is written only when something
 *     actually reads the token, which is why no client could ever obtain one and every
 *     state-changing request would be rejected.
 * :output: The ``XSRF-TOKEN`` cookie on every response, which the browser echoes as
 *     the ``X-XSRF-TOKEN`` header (the axios default) on subsequent requests.
 */
public class CsrfCookieMaterializingFilter extends OncePerRequestFilter {

    /**
     * :purpose: Resolve the token, then continue the chain.
     * :param request: the current HTTP request carrying the deferred token attribute.
     * :param response: the current HTTP response that receives the cookie.
     * :param filterChain: the remainder of the filter chain.
     * :raises ServletException: if a downstream filter or the servlet fails.
     * :raises IOException: if request or response I/O fails.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            // Reading the value resolves the deferred token, which makes the repository
            // write the cookie.
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
