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
        // The refusal carries the shared envelope, not a zero-byte body: a throttled operator
        // must be told that waiting is the remedy. It still names nothing about the request.
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getContentAsString())
                .contains("\"status\":429")
                .contains("\"errorCode\":\"RATE_LIMITED\"")
                .contains("\"message\":\"Too many requests. Please try again shortly.\"")
                .doesNotContain("10.0.0.1", "Exception");
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
                .contains(RateLimitFilter.class.getName() + ":/:SOURCE_ADDRESS.FILTERED",
                          RateLimitFilter.class.getName() + ":/auth/:SOURCE_ADDRESS.FILTERED");
    }

    /**
     * :purpose: Two budgets that share a path prefix but count different identities must BOTH
     *     run. The marker was previously scoped by prefix alone, so the per-caller and
     *     anonymous budgets the gateway stacks on ``/`` would have collided and only the
     *     first would ever have been enforced.
     */
    @Test
    @DisplayName("budgets sharing a prefix but counting different identities both run")
    void scopesTheOncePerRequestMarkerByCountedIdentity() throws Exception {
        RateLimitFilter perCaller =
                new RateLimitFilter(1, "/", RateLimitFilter.CountedIdentity.SIGNED_ON_CALLER);
        RateLimitFilter anonymous =
                new RateLimitFilter(1, "/", RateLimitFilter.CountedIdentity.ANONYMOUS_SOURCE_ADDRESS);
        java.util.List<String> markersSeenByTheApplication = new java.util.ArrayList<>();
        FilterChain application = (req, res) ->
                ((HttpServletRequest) req).getAttributeNames().asIterator()
                        .forEachRemaining(markersSeenByTheApplication::add);

        MockHttpServletResponse response = new MockHttpServletResponse();
        perCaller.doFilter(request("/menu", "10.0.0.1"), response,
                (req, res) -> anonymous.doFilter(req, res, application));

        assertThat(markersSeenByTheApplication)
                .contains(RateLimitFilter.class.getName() + ":/:SIGNED_ON_CALLER.FILTERED",
                          RateLimitFilter.class.getName() + ":/:ANONYMOUS_SOURCE_ADDRESS.FILTERED");
    }

    /**
     * :purpose: A signed-on caller must get its OWN budget rather than share one with
     *     everyone behind the same source address, which is what made the AAP 0.7.1 target
     *     of 150 concurrent users unreachable from a single NAT address.
     */
    @Test
    @DisplayName("two sessions from one address each get the full per-caller budget")
    void perCallerBudgetIsNotSharedAcrossSessionsFromOneAddress() throws Exception {
        RateLimitFilter filter =
                new RateLimitFilter(1, "/", RateLimitFilter.CountedIdentity.SIGNED_ON_CALLER);

        MockHttpServletRequest firstCaller = request("/menu", "10.0.0.1");
        firstCaller.getSession(true);
        MockHttpServletRequest secondCaller = request("/menu", "10.0.0.1");
        secondCaller.getSession(true);

        CountingChain chain = new CountingChain();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstCaller, firstResponse, chain);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondCaller, secondResponse, chain);

        assertThat(firstResponse.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(secondResponse.getStatus())
                .as("a second caller from the same address must not inherit the first's spend")
                .isEqualTo(HttpStatus.OK.value());
        assertThat(chain.invocations).isEqualTo(2);

        // The same caller's SECOND request does exhaust its own budget.
        MockHttpServletRequest firstCallerAgain = request("/menu", "10.0.0.1");
        firstCallerAgain.setSession(firstCaller.getSession(false));
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        filter.doFilter(firstCallerAgain, thirdResponse, chain);
        assertThat(thirdResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(thirdResponse.getHeader("Retry-After")).isNotNull();
    }

    /**
     * :purpose: The two identities are complementary: an anonymous-only budget must ignore a
     *     signed-on request, and a per-caller budget must ignore an anonymous one, so neither
     *     double-counts the other's traffic.
     */
    @Test
    @DisplayName("the caller and anonymous budgets each ignore the other's traffic")
    void complementaryIdentitiesDoNotCountEachOthersRequests() throws Exception {
        RateLimitFilter anonymousOnly =
                new RateLimitFilter(1, "/", RateLimitFilter.CountedIdentity.ANONYMOUS_SOURCE_ADDRESS);
        CountingChain chain = new CountingChain();

        MockHttpServletRequest signedOn = request("/menu", "10.0.0.1");
        signedOn.getSession(true);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest repeat = request("/menu", "10.0.0.1");
            repeat.setSession(signedOn.getSession(false));
            MockHttpServletResponse response = new MockHttpServletResponse();
            anonymousOnly.doFilter(repeat, response, chain);
            assertThat(response.getStatus())
                    .as("a signed-on request must not spend the anonymous budget")
                    .isEqualTo(HttpStatus.OK.value());
        }

        RateLimitFilter callerOnly =
                new RateLimitFilter(1, "/", RateLimitFilter.CountedIdentity.SIGNED_ON_CALLER);
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            callerOnly.doFilter(request("/menu", "10.0.0.1"), response, chain);
            assertThat(response.getStatus())
                    .as("an anonymous request must not spend a caller budget")
                    .isEqualTo(HttpStatus.OK.value());
        }
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
