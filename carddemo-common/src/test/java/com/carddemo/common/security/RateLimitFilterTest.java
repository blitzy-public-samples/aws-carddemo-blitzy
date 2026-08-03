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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * :purpose: Unit tests for {@link RateLimitFilter}, the per-source-address request
 *     budget applied at the network edge to bound credential stuffing and endpoint
 *     probing: budget enforcement, the ``429`` shape with ``Retry-After``, path-prefix
 *     scoping, per-address keying, the disable switch, and the deliberate refusal to
 *     trust attacker-controlled forwarded headers.
 */
@DisplayName("RateLimitFilter — per-source-address request budget")
class RateLimitFilterTest {

    /**
     * :purpose: Filter chain that counts how many requests reached the application.
     */
    private static final class CountingChain implements FilterChain {

        private int invocations;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invocations++;
        }
    }

    private static MockHttpServletRequest request(String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static int invoke(RateLimitFilter filter,
                              HttpServletRequest request,
                              CountingChain chain) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }

    @Test
    @DisplayName("requests within the budget are passed through untouched")
    void passesRequestsWithinBudget() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3, "/");
        CountingChain chain = new CountingChain();

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(invoke(filter, request("/auth/signon", "10.0.0.1"), chain))
                    .isEqualTo(HttpStatus.OK.value());
        }
        assertThat(chain.invocations).isEqualTo(3);
    }

    @Test
    @DisplayName("the request beyond the budget is answered 429 with Retry-After and never dispatched")
    void rejectsBeyondBudgetWithRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, "/");
        CountingChain chain = new CountingChain();

        invoke(filter, request("/auth/signon", "10.0.0.1"), chain);
        invoke(filter, request("/auth/signon", "10.0.0.1"), chain);

        MockHttpServletRequest third = request("/auth/signon", "10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(third, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(response.getHeader("Retry-After")))
                .isBetween(1L, RateLimitFilter.WINDOW.toSeconds());
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.invocations).as("the throttled request must not reach the application").isEqualTo(2);
    }

    @Test
    @DisplayName("the budget is counted per source address, so one client cannot throttle another")
    void countsBudgetPerSourceAddress() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, "/");
        CountingChain chain = new CountingChain();

        assertThat(invoke(filter, request("/menu", "10.0.0.1"), chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(invoke(filter, request("/menu", "10.0.0.1"), chain))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(invoke(filter, request("/menu", "10.0.0.2"), chain))
                .as("a different address has its own budget")
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("a path prefix scopes the budget to the matching requests only")
    void scopesTheBudgetToThePathPrefix() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, "/auth/");
        CountingChain chain = new CountingChain();

        assertThat(invoke(filter, request("/auth/signon", "10.0.0.1"), chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(invoke(filter, request("/auth/signon", "10.0.0.1"), chain))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(invoke(filter, request("/menu", "10.0.0.1"), chain))
                .as("an unmatched path is outside this budget")
                .isEqualTo(HttpStatus.OK.value());
        assertThat(invoke(filter, request("/accounts/1", "10.0.0.1"), chain))
                .isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("a budget below one disables throttling entirely")
    void budgetBelowOneDisablesThrottling() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(0, "/");
        CountingChain chain = new CountingChain();

        for (int attempt = 0; attempt < 25; attempt++) {
            assertThat(invoke(filter, request("/menu", "10.0.0.1"), chain)).isEqualTo(HttpStatus.OK.value());
        }
        assertThat(chain.invocations).isEqualTo(25);
    }

    @Test
    @DisplayName("forwarded headers are not trusted: a spoofed X-Forwarded-For cannot reset the budget")
    void doesNotTrustForwardedHeaders() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, "/");
        CountingChain chain = new CountingChain();

        invoke(filter, request("/auth/signon", "10.0.0.1"), chain);

        MockHttpServletRequest spoofed = request("/auth/signon", "10.0.0.1");
        spoofed.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(invoke(filter, spoofed, chain))
                .as("the real remote address is the only key")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("a request without a remote address is still counted, under a single key")
    void countsRequestsWithoutARemoteAddress() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, "/");
        CountingChain chain = new CountingChain();

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/menu");
        first.setRequestURI("/menu");
        first.setRemoteAddr("");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/menu");
        second.setRequestURI("/menu");
        second.setRemoteAddr("");

        assertThat(invoke(filter, first, chain)).isEqualTo(HttpStatus.OK.value());
        assertThat(invoke(filter, second, chain)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("two stacked instances each enforce their own budget in one chain")
    void stackedInstancesEachEnforceTheirOwnBudget() throws Exception {
        // The gateway stacks a broad budget and a much tighter sign-on budget. Both must
        // count: a shared OncePerRequestFilter marker would make the second instance skip
        // itself and leave the sign-on budget unenforced.
        RateLimitFilter global = new RateLimitFilter(10, "/");
        RateLimitFilter signon = new RateLimitFilter(1, "/auth/");
        CountingChain application = new CountingChain();
        FilterChain signonChain = (req, res) -> signon.doFilter(req, res, application);

        MockHttpServletResponse first = new MockHttpServletResponse();
        global.doFilter(request("/auth/signon", "10.0.0.1"), first, signonChain);
        assertThat(first.getStatus()).isEqualTo(HttpStatus.OK.value());

        MockHttpServletResponse second = new MockHttpServletResponse();
        global.doFilter(request("/auth/signon", "10.0.0.1"), second, signonChain);
        assertThat(second.getStatus())
                .as("the tighter sign-on budget must still be enforced behind the broad one")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(second.getHeader("Retry-After")).isNotNull();

        MockHttpServletResponse other = new MockHttpServletResponse();
        global.doFilter(request("/menu", "10.0.0.1"), other, signonChain);
        assertThat(other.getStatus())
                .as("a path outside the sign-on prefix is still within the broad budget")
                .isEqualTo(HttpStatus.OK.value());

        assertThat(application.invocations).isEqualTo(2);
    }

    @Test
    @DisplayName("each scope gets its own once-per-request marker")
    void scopesTheOncePerRequestMarkerByPathPrefix() throws Exception {
        RateLimitFilter global = new RateLimitFilter(1, "/");
        RateLimitFilter signon = new RateLimitFilter(1, "/auth/");
        java.util.List<String> markersSeenByTheApplication = new java.util.ArrayList<>();
        FilterChain application = (req, res) ->
                ((HttpServletRequest) req).getAttributeNames().asIterator()
                        .forEachRemaining(markersSeenByTheApplication::add);

        MockHttpServletResponse response = new MockHttpServletResponse();
        global.doFilter(request("/auth/signon", "10.0.0.1"), response,
                (req, res) -> signon.doFilter(req, res, application));

        assertThat(markersSeenByTheApplication)
                .contains(RateLimitFilter.class.getName() + ":/.FILTERED",
                          RateLimitFilter.class.getName() + ":/auth/.FILTERED");
    }

    @Test
    @DisplayName("an already-committed response is not rewritten when the budget is exhausted")
    void doesNotRewriteACommittedResponse() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, "/");
        CountingChain chain = new CountingChain();

        invoke(filter, request("/menu", "10.0.0.1"), chain);

        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(HttpStatus.OK.value());
        response.flushBuffer();
        HttpServletResponse committed = response;
        filter.doFilter(request("/menu", "10.0.0.1"), committed, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.invocations).as("the request is still refused").isEqualTo(1);
    }
}
