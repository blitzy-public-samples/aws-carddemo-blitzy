package com.carddemo.account.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Measures the cross-site refusal of the account service.
 *
 * <p>The route the review of this surface named is the bodyless
 * {@code POST /accounts/{accountId}/cycle-close}: an HTML form can submit it, and a browser
 * attaches a cached HTTP Basic credential to that submission without asking. Each test below is one
 * shape of that call, or one shape of a legitimate one that has to keep working.
 */
@DisplayName("CrossSiteRequestFilter, the account cross-site refusal")
class CrossSiteRequestFilterTest {

    /** The route the forged call of the review targets. */
    private static final String WRITE_PATH = "/accounts/00000000050/cycle-close";

    /** A read of the same service, which no test here refuses. */
    private static final String READ_PATH = "/accounts/00000000050";

    private final MeterRegistry meters = new SimpleMeterRegistry();

    private final CrossSiteRequestFilter filter = new CrossSiteRequestFilter(
            CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, this.meters);

    @Nested
    @DisplayName("a first-party call")
    class FirstPartyCalls {

        @ParameterizedTest
        @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE", "get", "head"})
        @DisplayName("a safe method reaches the chain carrying nothing at all")
        void aSafeMethodReachesTheChain(String method) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest(method, READ_PATH), response,
                    new MockFilterChain());

            assertEquals(HttpStatus.OK.value(), response.getStatus());
            assertEquals("", response.getContentAsString());
        }

        @ParameterizedTest
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "post"})
        @DisplayName("a state-changing method carrying the header reaches the chain")
        void aStateChangingMethodCarryingTheHeaderReachesTheChain(String method) throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest(method, WRITE_PATH);
            request.addHeader(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }

        @ParameterizedTest
        @ValueSource(strings = {"same-origin", "same-site", "SAME-ORIGIN", " same-site "})
        @DisplayName("a first-party Sec-Fetch-Site reaches the chain")
        void aFirstPartyFetchSiteReachesTheChain(String site) throws Exception {
            MockHttpServletRequest request = firstPartyWrite();
            request.addHeader(CrossSiteRequestFilter.FETCH_SITE_HEADER, site);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }

        @Test
        @DisplayName("an Origin naming this service reaches the chain")
        void anOriginNamingThisServiceReachesTheChain() throws Exception {
            MockHttpServletRequest request = firstPartyWrite();
            request.setScheme("http");
            request.setServerName("localhost");
            request.setServerPort(8085);
            request.addHeader(HttpHeaders.ORIGIN, "http://localhost:8085");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }

        @Test
        @DisplayName("an Origin on the default port of its scheme reaches the chain")
        void anOriginOnTheDefaultPortReachesTheChain() throws Exception {
            MockHttpServletRequest request = firstPartyWrite();
            request.setScheme("https");
            request.setServerName("accounts.example.test");
            request.setServerPort(443);
            request.addHeader(HttpHeaders.ORIGIN, "https://accounts.example.test");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }

        @Test
        @DisplayName("no first-party call increments the refusal counter")
        void noFirstPartyCallIncrementsTheCounter() throws Exception {
            filter.doFilter(firstPartyWrite(), new MockHttpServletResponse(),
                    new MockFilterChain());
            filter.doFilter(new MockHttpServletRequest("GET", READ_PATH),
                    new MockHttpServletResponse(), new MockFilterChain());

            assertEquals(0.0D, refusals());
        }
    }

    @Nested
    @DisplayName("a forged call")
    class ForgedCalls {

        @ParameterizedTest
        @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
        @DisplayName("a state-changing method carrying no request header is refused")
        void aStateChangingMethodCarryingNoHeaderIsRefused(String method) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(new MockHttpServletRequest(method, WRITE_PATH), response,
                    new MockFilterChain());

            assertRefused(response);
        }

        @Test
        @DisplayName("a blank request header is no header")
        void aBlankRequestHeaderIsNoHeader() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", WRITE_PATH);
            request.addHeader(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "   ");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertRefused(response);
        }

        @ParameterizedTest
        @ValueSource(strings = {"cross-site", "none", "CROSS-SITE", "unknown"})
        @DisplayName("a Sec-Fetch-Site that is not first-party is refused, header or no header")
        void aForeignFetchSiteIsRefused(String site) throws Exception {
            MockHttpServletRequest request = firstPartyWrite();
            request.addHeader(CrossSiteRequestFilter.FETCH_SITE_HEADER, site);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertRefused(response);
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://attacker.example", "http://localhost:9999", "null",
                "http://localhost"})
        @DisplayName("an Origin naming somewhere else is refused, header or no header")
        void aForeignOriginIsRefused(String origin) throws Exception {
            MockHttpServletRequest request = firstPartyWrite();
            request.setScheme("http");
            request.setServerName("localhost");
            request.setServerPort(8085);
            request.addHeader(HttpHeaders.ORIGIN, origin);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertRefused(response);
        }

        @Test
        @DisplayName("the refusal names no route, no identifier and no header value")
        void theRefusalNamesNothingTheCallerSupplied() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", WRITE_PATH);
            request.addHeader(HttpHeaders.ORIGIN, "https://attacker.example");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            String body = response.getContentAsString();
            assertFalse(body.contains("/accounts"), body);
            assertFalse(body.contains("00000000050"), body);
            assertFalse(body.contains("attacker"), body);
            assertFalse(body.contains(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER), body);
        }

        @Test
        @DisplayName("every refusal is counted, and the chain is never reached")
        void everyRefusalIsCountedAndTheChainIsNeverReached() throws Exception {
            MockFilterChain chain = new MockFilterChain();

            for (int attempt = 0; attempt < 3; attempt++) {
                filter.doFilter(new MockHttpServletRequest("POST", WRITE_PATH),
                        new MockHttpServletResponse(), chain);
            }

            assertEquals(3.0D, refusals());
            assertNull(chain.getRequest(),
                    "a refused request must not reach the rest of the chain");
        }
    }

    @Nested
    @DisplayName("the shape of the control")
    class ShapeOfTheControl {

        @Test
        @DisplayName("the shipped header name is the one the configuration documents")
        void theShippedHeaderNameIsTheOneTheConfigurationDocuments() {
            assertEquals("X-CardDemo-Request", CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER);
            assertEquals("X-CardDemo-Request", filter.requiredHeader());
        }

        @Test
        @DisplayName("a configured header name is trimmed and honoured")
        void aConfiguredHeaderNameIsHonoured() throws Exception {
            CrossSiteRequestFilter configured =
                    new CrossSiteRequestFilter(" X-Gateway-Request ", new SimpleMeterRegistry());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", WRITE_PATH);
            request.addHeader("X-Gateway-Request", "1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            configured.doFilter(request, response, new MockFilterChain());

            assertEquals("X-Gateway-Request", configured.requiredHeader());
            assertEquals(HttpStatus.OK.value(), response.getStatus());
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank header name stops start-up naming the property")
        void aBlankHeaderNameStopsStartUp(String configured) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new CrossSiteRequestFilter(configured, new SimpleMeterRegistry()));

            assertTrue(refused.getMessage()
                            .contains("carddemo.api.cross-site.required-header"),
                    refused.getMessage());
        }

        @Test
        @DisplayName("the filter carries no order, so it runs behind the security chain")
        void theFilterCarriesNoOrderSoItRunsBehindTheSecurityChain() {
            assertNull(CrossSiteRequestFilter.class.getAnnotation(Order.class),
                    "an order here would move the refusal ahead of authentication, and an "
                            + "unauthenticated caller would then receive 403 rather than 401");
            assertNull(RequestBodyCeilingFilter.class.getAnnotation(Order.class),
                    "the two request-shape filters share one placement");
        }

        @Test
        @DisplayName("the counter is registered under this service's own name")
        void theCounterIsRegisteredUnderThisServicesOwnName() {
            assertEquals("carddemo.account.requests.cross.site.refused",
                    CrossSiteRequestFilter.REFUSED_METER);
            assertTrue(this.meters().getMeters().stream()
                            .anyMatch(meter -> meter.getId().getName()
                                    .equals(CrossSiteRequestFilter.REFUSED_METER)),
                    "the counter must exist before the first refusal, so a dashboard reads zero");
        }

        private MeterRegistry meters() {
            return CrossSiteRequestFilterTest.this.meters;
        }
    }

    /** Builds a write that satisfies the header requirement and declares nothing else. */
    private static MockHttpServletRequest firstPartyWrite() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", WRITE_PATH);
        request.addHeader(CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER, "1");
        return request;
    }

    /** Asserts the response is the one fixed refusal this filter writes. */
    private static void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getStatus());
        assertTrue(response.getContentType()
                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
                response.getContentType());
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL));
        assertEquals(CrossSiteRequestFilter.REFUSAL_BODY, response.getContentAsString());
    }

    /** Reads the refusal count. */
    private double refusals() {
        return this.meters.get(CrossSiteRequestFilter.REFUSED_METER).counter().count();
    }
}
