package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures the request-rate ceilings of the account service.
 *
 * <p>The clock is read through one overridable method, so every window boundary here is reached by
 * advancing a number rather than by waiting for a second to pass.
 */
@DisplayName("RequestRateCeilingFilter, the authorization request-rate ceilings")
class RequestRateCeilingFilterTest {

    private static final String READ_PATH = "/authorizations";
    private static final String WRITE_PATH = "/authorizations";
    private static final String PROBE_PATH = "/actuator/health";
    private static final long WINDOW_SECONDS = 60L;
    private static final long WINDOW_MILLIS = WINDOW_SECONDS * 1000L;

    @Nested
    @DisplayName("the ceiling on requests from one source")
    class SourceCeiling {

        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final TestableFilter filter = filter(3L, 100L, 100L, 8, this.meters);

        @Test
        @DisplayName("requests up to the ceiling reach the chain and the next is refused")
        void requestsUpToTheCeilingReachTheChain() throws Exception {
            CountingChain chain = new CountingChain();

            for (int request = 0; request < 3; request++) {
                MockHttpServletResponse admitted = new MockHttpServletResponse();
                filter.doFilter(read("10.0.0.1"), admitted, chain);
                assertEquals(HttpStatus.OK.value(), admitted.getStatus());
            }
            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.1"), refused, chain);

            assertRefused(refused);
            assertEquals(3, chain.calls, "a refused request must not reach the rest of the chain");
            assertEquals(1.0D,
                    refusals(this.meters, RequestRateCeilingFilter.SOURCE_STAGE));
        }

        @Test
        @DisplayName("the window rolls and the same source is admitted again")
        void theWindowRollsAndTheSameSourceIsAdmittedAgain() throws Exception {
            CountingChain chain = new CountingChain();
            for (int request = 0; request < 4; request++) {
                filter.doFilter(read("10.0.0.1"), new MockHttpServletResponse(), chain);
            }

            filter.advance(WINDOW_MILLIS);
            MockHttpServletResponse admitted = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.1"), admitted, chain);

            assertEquals(HttpStatus.OK.value(), admitted.getStatus());
        }

        @Test
        @DisplayName("two sources are counted apart from each other")
        void twoSourcesAreCountedApart() throws Exception {
            CountingChain chain = new CountingChain();
            for (int request = 0; request < 3; request++) {
                filter.doFilter(read("10.0.0.1"), new MockHttpServletResponse(), chain);
            }

            MockHttpServletResponse other = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.2"), other, chain);

            assertEquals(HttpStatus.OK.value(), other.getStatus());
        }

        @Test
        @DisplayName("the refusal names the seconds until the window rolls")
        void theRefusalNamesTheSecondsUntilTheWindowRolls() throws Exception {
            CountingChain chain = new CountingChain();
            for (int request = 0; request < 3; request++) {
                filter.doFilter(read("10.0.0.1"), new MockHttpServletResponse(), chain);
            }

            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.1"), refused, chain);

            assertEquals(Long.toString(WINDOW_SECONDS),
                    refused.getHeader(HttpHeaders.RETRY_AFTER));
        }
    }

    @Nested
    @DisplayName("the ceiling on requests presenting one identity")
    class IdentityCeiling {

        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final TestableFilter filter = filter(2L, 100L, 100L, 8, this.meters);

        @Test
        @DisplayName("one credential spread across three addresses is still bounded")
        void oneCredentialSpreadAcrossThreeAddressesIsBounded() throws Exception {
            CountingChain chain = new CountingChain();

            filter.doFilter(authenticated("10.0.0.1", "user0001"),
                    new MockHttpServletResponse(), chain);
            filter.doFilter(authenticated("10.0.0.2", "user0001"),
                    new MockHttpServletResponse(), chain);
            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(authenticated("10.0.0.3", "user0001"), refused, chain);

            assertRefused(refused);
            assertEquals(1.0D, refusals(this.meters, RequestRateCeilingFilter.IDENTITY_STAGE));
            assertEquals(0.0D, refusals(this.meters, RequestRateCeilingFilter.SOURCE_STAGE));
        }

        @Test
        @DisplayName("a second identity from the same address is counted apart")
        void aSecondIdentityFromTheSameAddressIsCountedApart() throws Exception {
            CountingChain chain = new CountingChain();
            filter.doFilter(authenticated("10.0.0.1", "user0001"),
                    new MockHttpServletResponse(), chain);
            filter.doFilter(authenticated("10.0.0.2", "user0001"),
                    new MockHttpServletResponse(), chain);

            MockHttpServletResponse admitted = new MockHttpServletResponse();
            filter.doFilter(authenticated("10.0.0.3", "admin001"), admitted, chain);

            assertEquals(HttpStatus.OK.value(), admitted.getStatus());
        }

        @ParameterizedTest
        @ValueSource(strings = {"Basic not-base-64!!", "Basic ", "Bearer abcdef",
                "Basic dXNlcm5hbWUtd2l0aC1ubyBjb2xvbg=="})
        @DisplayName("a credential no identity can be read from is admitted on the source alone")
        void aCredentialNoIdentityCanBeReadFromIsAdmittedOnTheSourceAlone(String header)
                throws Exception {
            CountingChain chain = new CountingChain();

            for (int request = 0; request < 3; request++) {
                MockHttpServletRequest carrying = read("10.0.0.9");
                carrying.addHeader(HttpHeaders.AUTHORIZATION, header);
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(carrying, response, chain);
                assertEquals(request < 2 ? HttpStatus.OK.value()
                                : HttpStatus.TOO_MANY_REQUESTS.value(),
                        response.getStatus(), header);
            }

            assertEquals(0.0D, refusals(this.meters, RequestRateCeilingFilter.IDENTITY_STAGE));
        }
    }

    @Nested
    @DisplayName("the stricter ceiling on a state-changing request")
    class WriteCeiling {

        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final TestableFilter filter = filter(100L, 2L, 100L, 8, this.meters);

        @ParameterizedTest
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("a write past the write ceiling is refused while the source ceiling has room")
        void aWritePastTheWriteCeilingIsRefused(String method) throws Exception {
            TestableFilter fresh = filter(100L, 2L, 100L, 8, new SimpleMeterRegistry());
            CountingChain chain = new CountingChain();

            fresh.doFilter(write(method, "10.0.0.1"), new MockHttpServletResponse(), chain);
            fresh.doFilter(write(method, "10.0.0.1"), new MockHttpServletResponse(), chain);
            MockHttpServletResponse refused = new MockHttpServletResponse();
            fresh.doFilter(write(method, "10.0.0.1"), refused, chain);

            assertRefused(refused);
        }

        @Test
        @DisplayName("a read is never counted against the write ceiling")
        void aReadIsNeverCountedAgainstTheWriteCeiling() throws Exception {
            CountingChain chain = new CountingChain();

            for (int request = 0; request < 10; request++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(read("10.0.0.1"), response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            assertEquals(0.0D, refusals(this.meters, RequestRateCeilingFilter.WRITE_STAGE));
        }

        @Test
        @DisplayName("the refusal is counted under the write ceiling and not the source one")
        void theRefusalIsCountedUnderTheWriteCeiling() throws Exception {
            CountingChain chain = new CountingChain();
            for (int request = 0; request < 3; request++) {
                filter.doFilter(write("POST", "10.0.0.1"), new MockHttpServletResponse(), chain);
            }

            assertEquals(1.0D, refusals(this.meters, RequestRateCeilingFilter.WRITE_STAGE));
            assertEquals(0.0D, refusals(this.meters, RequestRateCeilingFilter.SOURCE_STAGE));
        }
    }

    @Nested
    @DisplayName("the ceiling on failed authentications from one source")
    class AuthenticationFailureCeiling {

        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final TestableFilter filter = filter(100L, 100L, 2L, 8, this.meters);

        @Test
        @DisplayName("a source that has failed the ceiling is refused before the next guess")
        void aSourceThatHasFailedTheCeilingIsRefused() throws Exception {
            CountingChain unauthorized = new CountingChain(HttpStatus.UNAUTHORIZED.value());

            filter.doFilter(authenticated("10.0.0.1", "guess1"),
                    new MockHttpServletResponse(), unauthorized);
            filter.doFilter(authenticated("10.0.0.1", "guess2"),
                    new MockHttpServletResponse(), unauthorized);
            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(authenticated("10.0.0.1", "guess3"), refused, unauthorized);

            assertRefused(refused);
            assertEquals(2, unauthorized.calls,
                    "the third guess must be refused before it reaches the password encoder");
            assertEquals(1.0D,
                    refusals(this.meters, RequestRateCeilingFilter.AUTHENTICATION_STAGE));
        }

        @Test
        @DisplayName("a 401 for a request carrying no credential is not a guess")
        void aRefusalForARequestCarryingNoCredentialIsNotAGuess() throws Exception {
            CountingChain unauthorized = new CountingChain(HttpStatus.UNAUTHORIZED.value());

            for (int request = 0; request < 6; request++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(read("10.0.0.2"), response, unauthorized);
                assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
            }

            assertEquals(6, unauthorized.calls);
            assertEquals(0.0D,
                    refusals(this.meters, RequestRateCeilingFilter.AUTHENTICATION_STAGE));
        }

        @Test
        @DisplayName("an accepted credential is never counted as a failure")
        void anAcceptedCredentialIsNeverCountedAsAFailure() throws Exception {
            CountingChain accepted = new CountingChain();

            for (int request = 0; request < 6; request++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                filter.doFilter(authenticated("10.0.0.3", "user0001"), response, accepted);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            assertEquals(0.0D,
                    refusals(this.meters, RequestRateCeilingFilter.AUTHENTICATION_STAGE));
        }

        @Test
        @DisplayName("the window rolls and the source may authenticate again")
        void theWindowRollsAndTheSourceMayAuthenticateAgain() throws Exception {
            CountingChain unauthorized = new CountingChain(HttpStatus.UNAUTHORIZED.value());
            for (int request = 0; request < 3; request++) {
                filter.doFilter(authenticated("10.0.0.4", "guess"),
                        new MockHttpServletResponse(), unauthorized);
            }

            filter.advance(WINDOW_MILLIS);
            MockHttpServletResponse admitted = new MockHttpServletResponse();
            filter.doFilter(authenticated("10.0.0.4", "guess"), admitted, unauthorized);

            assertEquals(HttpStatus.UNAUTHORIZED.value(), admitted.getStatus(),
                    "the request reached the chain again rather than being refused with 429");
        }
    }

    @Nested
    @DisplayName("the bound on requests in flight")
    class ConcurrencyBound {

        private final MeterRegistry meters = new SimpleMeterRegistry();
        private final TestableFilter filter = filter(100L, 100L, 100L, 1, this.meters);

        @Test
        @DisplayName("a second request entering while the first is in flight is refused")
        void aSecondRequestEnteringWhileTheFirstIsInFlightIsRefused() throws Exception {
            MockHttpServletResponse nested = new MockHttpServletResponse();
            FilterChain reentrant = (request, response) -> {
                try {
                    filter.doFilter(read("10.0.0.2"), nested, new CountingChain());
                } catch (ServletException raised) {
                    throw new IllegalStateException(raised);
                }
                ((MockHttpServletResponse) response).setStatus(HttpStatus.OK.value());
            };

            MockHttpServletResponse outer = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.1"), outer, reentrant);

            assertEquals(HttpStatus.OK.value(), outer.getStatus());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), nested.getStatus());
            assertEquals("1", nested.getHeader(HttpHeaders.RETRY_AFTER));
            assertEquals(1.0D, refusals(this.meters, RequestRateCeilingFilter.CONCURRENCY_STAGE));
        }

        @Test
        @DisplayName("the count is released even when the chain raises")
        void theCountIsReleasedEvenWhenTheChainRaises() throws Exception {
            FilterChain raising = (request, response) -> {
                throw new IOException("the rest of the chain failed");
            };

            assertThrows(IOException.class,
                    () -> filter.doFilter(read("10.0.0.1"), new MockHttpServletResponse(),
                            raising));

            MockHttpServletResponse admitted = new MockHttpServletResponse();
            filter.doFilter(read("10.0.0.1"), admitted, new CountingChain());
            assertEquals(HttpStatus.OK.value(), admitted.getStatus());
        }
    }

    @Nested
    @DisplayName("the shape of the control")
    class ShapeOfTheControl {

        @Test
        @DisplayName("the management surface is exempt, so a probe is never refused")
        void theManagementSurfaceIsExempt() throws Exception {
            TestableFilter narrow = filter(1L, 1L, 1L, 1, new SimpleMeterRegistry());
            CountingChain chain = new CountingChain();

            for (int probe = 0; probe < 20; probe++) {
                MockHttpServletResponse response = new MockHttpServletResponse();
                narrow.doFilter(read("10.0.0.1", PROBE_PATH), response, chain);
                assertEquals(HttpStatus.OK.value(), response.getStatus());
            }

            assertEquals(20, chain.calls);
        }

        @Test
        @DisplayName("the refusal is one fixed answer naming nothing the caller supplied")
        void theRefusalNamesNothingTheCallerSupplied() throws Exception {
            TestableFilter narrow = filter(1L, 1L, 1L, 8, new SimpleMeterRegistry());
            CountingChain chain = new CountingChain();
            filter(1L, 1L, 1L, 8, new SimpleMeterRegistry());
            narrow.doFilter(authenticated("10.0.0.1", "user0001"),
                    new MockHttpServletResponse(), chain);

            MockHttpServletResponse refused = new MockHttpServletResponse();
            narrow.doFilter(authenticated("10.0.0.1", "user0001"), refused, chain);

            String body = refused.getContentAsString();
            assertEquals(RequestRateCeilingFilter.REFUSAL_BODY, body);
            assertFalse(body.contains("10.0.0.1"), body);
            assertFalse(body.contains("user0001"), body);
            assertFalse(body.contains("/authorizations"), body);
        }

        @Test
        @DisplayName("one counter per ceiling is registered before the first refusal")
        void oneCounterPerCeilingIsRegisteredBeforeTheFirstRefusal() {
            MeterRegistry registry = new SimpleMeterRegistry();
            filter(100L, 100L, 100L, 8, registry);

            assertEquals(RequestRateCeilingFilter.STAGES.size(), registry.getMeters().size());
            for (String stage : RequestRateCeilingFilter.STAGES) {
                assertEquals(0.0D, refusals(registry, stage), stage);
            }
            assertEquals("carddemo.authorization.requests.throttled",
                    RequestRateCeilingFilter.THROTTLED_METER);
        }

        @Test
        @DisplayName("the map of counters stays bounded, and an expired window is purged")
        void theMapOfCountersStaysBounded() throws Exception {
            TestableFilter wide = filter(1_000_000L, 1_000_000L, 1_000_000L, 4096,
                    new SimpleMeterRegistry());
            CountingChain chain = new CountingChain();

            for (int source = 0; source < RequestRateCeilingFilter.MAX_TRACKED_KEYS + 500;
                    source++) {
                wide.doFilter(read("10." + (source / 65536) + "." + (source / 256 % 256) + "."
                        + (source % 256)), new MockHttpServletResponse(), chain);
            }
            assertTrue(wide.trackedKeyCount() <= RequestRateCeilingFilter.MAX_TRACKED_KEYS + 1,
                    "tracked keys: " + wide.trackedKeyCount());

            wide.advance(2L * WINDOW_MILLIS);
            wide.doFilter(read("192.168.0.1"), new MockHttpServletResponse(), chain);

            assertTrue(wide.trackedKeyCount() <= 2,
                    "an expired window must be purged: " + wide.trackedKeyCount());
        }

        @ParameterizedTest
        @CsvSource({
                "0,1,1,1,1,carddemo.api.rate-limit.window-seconds",
                "60,0,1,1,1,carddemo.api.rate-limit.requests-per-window",
                "60,1,0,1,1,carddemo.api.rate-limit.write-requests-per-window",
                "60,1,1,0,1,carddemo.api.rate-limit.authentication-failures-per-window",
                "60,1,1,1,0,carddemo.api.rate-limit.concurrent-requests"})
        @DisplayName("a ceiling below one stops start-up naming its property and no value")
        void aCeilingBelowOneStopsStartUp(long window, long requests, long writes, long failures,
                int concurrent, String property) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new RequestRateCeilingFilter(window, requests, writes, failures,
                            concurrent, "/actuator", new SimpleMeterRegistry()));

            assertEquals(property + " counts up from one", refused.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        @DisplayName("a blank management path stops start-up naming the property")
        void aBlankManagementPathStopsStartUp(String path) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new RequestRateCeilingFilter(60L, 1L, 1L, 1L, 1, path,
                            new SimpleMeterRegistry()));

            assertTrue(refused.getMessage().contains("management.endpoints.web.base-path"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("the filter is ordered ahead of the security chain")
        void theFilterIsOrderedAheadOfTheSecurityChain() {
            Order order = RequestRateCeilingFilter.class.getAnnotation(Order.class);

            assertTrue(order != null, "the filter must declare an order");
            assertEquals(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1, order.value());
            assertTrue(order.value() < SecurityFilterProperties.DEFAULT_FILTER_ORDER,
                    "a rate refusal has to cost less than the bcrypt verification it refuses, so "
                            + "this filter runs ahead of the security chain");
        }
    }

    /** Builds a filter whose clock a test advances. */
    private static TestableFilter filter(long requestsPerWindow, long writeRequestsPerWindow,
            long authenticationFailuresPerWindow, int concurrentRequests, MeterRegistry meters) {
        return new TestableFilter(requestsPerWindow, writeRequestsPerWindow,
                authenticationFailuresPerWindow, concurrentRequests, meters);
    }

    /** Builds a read from one address. */
    private static MockHttpServletRequest read(String source) {
        return read(source, READ_PATH);
    }

    /** Builds a read of one path from one address. */
    private static MockHttpServletRequest read(String source, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(source);
        return request;
    }

    /** Builds a state-changing request from one address. */
    private static MockHttpServletRequest write(String method, String source) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, WRITE_PATH);
        request.setRemoteAddr(source);
        return request;
    }

    /** Builds a read carrying one Basic credential. */
    private static MockHttpServletRequest authenticated(String source, String username) {
        MockHttpServletRequest request = read(source);
        String credential = username + ":not-a-real-password";
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
                .encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    /** Reads the refusal count of one ceiling. */
    private static double refusals(MeterRegistry registry, String stage) {
        return registry.get(RequestRateCeilingFilter.THROTTLED_METER)
                .tag(RequestRateCeilingFilter.STAGE_TAG, stage)
                .counter()
                .count();
    }

    /** Asserts the response is the one fixed refusal this filter writes. */
    private static void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        assertTrue(response.getContentType()
                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
                response.getContentType());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertTrue(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER)) >= 1L,
                response.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(RequestRateCeilingFilter.REFUSAL_BODY, response.getContentAsString());
    }

    /** A filter whose clock stands still until a test advances it. */
    private static final class TestableFilter extends RequestRateCeilingFilter {

        private long now = 1_700_000_000_000L;

        TestableFilter(long requestsPerWindow, long writeRequestsPerWindow,
                long authenticationFailuresPerWindow, int concurrentRequests,
                MeterRegistry meters) {
            super(WINDOW_SECONDS, requestsPerWindow, writeRequestsPerWindow,
                    authenticationFailuresPerWindow, concurrentRequests, "/actuator", meters);
        }

        @Override
        long nowMillis() {
            return this.now;
        }

        void advance(long millis) {
            this.now += millis;
        }
    }

    /** A chain that counts its calls and sets one status. */
    private static final class CountingChain implements FilterChain {

        private final int status;
        private int calls;

        CountingChain() {
            this(HttpStatus.OK.value());
        }

        CountingChain(int status) {
            this.status = status;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            this.calls++;
            ((MockHttpServletResponse) response).setStatus(this.status);
        }
    }
}
