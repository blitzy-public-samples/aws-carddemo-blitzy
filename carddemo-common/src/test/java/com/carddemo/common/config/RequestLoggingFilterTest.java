/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * :purpose: Unit tests for :class:`RequestLoggingFilter`, the shared access log that closes the
 *     request-logging contract. Asserts that one record is emitted per
 *     request, that the level reflects the outcome, that an escaping exception is reported while the
 *     correlation id is still in the MDC, and that probe endpoints are excluded.
 * :note: Uses a Logback ``ListAppender`` attached to the filter's own logger so the assertions read
 *     the real emitted events rather than captured stdout.
 */
@DisplayName("RequestLoggingFilter")
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    private ListAppender<ILoggingEvent> appender;

    private ch.qos.logback.classic.Logger filterLogger;

    /**
     * :purpose: Attach a capturing appender to the filter's logger and clear the MDC.
     */
    @BeforeEach
    void setUp() {
        MDC.clear();
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        filterLogger = context.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        filterLogger.addAppender(appender);
        filterLogger.setLevel(Level.INFO);
    }

    /**
     * :purpose: Detach the capturing appender and clear the MDC.
     */
    @AfterEach
    void tearDown() {
        filterLogger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    /**
     * :purpose: A successful request produces exactly one INFO record naming the method, the URI and
     *     the response status.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("successful request logs one INFO access record")
    void successfulRequestLogsInfo() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "exactly one access-log record per request");
        ILoggingEvent event = events.get(0);
        assertEquals(Level.INFO, event.getLevel(), "a non-5xx outcome logs at INFO");
        String formatted = event.getFormattedMessage();
        assertTrue(formatted.startsWith("GET /accounts/1 -> 200 in"),
                "record must name the method, uri and status: " + formatted);
    }

    /**
     * :purpose: A server-error response is logged at ERROR so an operator can alert on it.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("5xx response logs at ERROR")
    void serverErrorLogsAtError() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/billpay");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(1, appender.list.size(), "exactly one access-log record per request");
        assertEquals(Level.ERROR, appender.list.get(0).getLevel(), "a 5xx outcome logs at ERROR");
    }

    /**
     * :purpose: An exception escaping the chain is logged at ERROR with the correlation id still in
     *     the MDC, and is then rethrown unchanged so the container's error handling is unaffected.
     */
    @Test
    @DisplayName("escaping exception is logged with the correlation id in scope and rethrown")
    void escapingExceptionLoggedWithMdcAndRethrown() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(CorrelationIdContext.CORRELATION_ID_KEY, "corr-9");
        FilterChain boom = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, response, boom));
        assertEquals("boom", thrown.getMessage(), "the original exception must propagate unchanged");

        assertEquals(1, appender.list.size(), "the failure must be reported exactly once");
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.ERROR, event.getLevel(), "an escaping exception logs at ERROR");
        assertEquals("corr-9", event.getMDCPropertyMap().get(CorrelationIdContext.CORRELATION_ID_KEY),
                "the ERROR record must carry the correlation id of the failing request");
        assertNotNull(event.getThrowableProxy(), "the stack trace must be attached to the record");

        // One value per placeholder: an extra argument shifted the URI into the duration slot
        // and dropped the exception class, producing "failed after /accounts/2ms: 25".
        String formatted = event.getFormattedMessage();
        assertTrue(formatted.startsWith("GET /accounts/2 failed after "),
                "the method and URI must occupy the first two slots: " + formatted);
        assertTrue(formatted.endsWith("ms: java.lang.IllegalStateException"),
                "the failure record must end with the elapsed time and the exception class; an extra"
                        + " argument previously pushed the duration into the exception slot: " + formatted);
        assertEquals(1, countOccurrences(formatted, "/accounts/2"),
                "the path must be rendered once, through the masking helper: " + formatted);
    }

    /**
     * :purpose: The failure record must NOT carry the raw request URI: the record written when an
     *     exception escapes the chain logs the same PAN-masked URI the success and 5xx records log,
     *     so an exception on a card route cannot write a full PAN to disk (CWE-532).
     */
    @Test
    @DisplayName("a card number in the URI is masked in the escaping-exception record")
    void cardNumberIsMaskedWhenTheChainThrows() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/cards/9006000000000001");
        FilterChain boom = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(), boom));

        String formatted = appender.list.get(0).getFormattedMessage();
        assertFalse(formatted.contains("9006000000000001"),
                "the full PAN must not appear in the failure record: " + formatted);
        assertTrue(formatted.startsWith("GET /cards/************0001 failed after "),
                "the failure record must carry the masked URI, the PAN reduced to its last four"
                        + " digits: " + formatted);
        assertEquals(1, countOccurrences(formatted, "/cards/"),
                "the path must be rendered once, through the masking helper: " + formatted);
    }

    /**
     * :purpose: Count the non-overlapping occurrences of a token in a log message.
     * :param text: the formatted log message.
     * :param token: the token to count.
     * :returns: the number of occurrences.
     */
    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = text.indexOf(token);
        while (from >= 0) {
            count++;
            from = text.indexOf(token, from + token.length());
        }
        return count;
    }

    /**
     * :purpose: The probe endpoints are excluded so three probes per service polling every few
     *     seconds do not bury real traffic in the log stream.
     */
    @Test
    @DisplayName("health probe endpoints are not logged")
    void healthEndpointsSkipped() {
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")),
                "/actuator/health must be skipped");
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness")),
                "health group endpoints must be skipped");
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/prometheus")),
                "the metrics endpoint must still be logged");
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/accounts/1")),
                "business routes must be logged");
    }

    /**
     * :purpose: A card number in the request path must not reach the access log. The card screens
     *     address a card BY its number, so logging the URI verbatim wrote a full PAN into the log
     *     of every card request (CWE-532).
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("a card number in the URI is masked in the access log")
    void cardNumberInUriIsMasked() throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/cards/9006000000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        String formatted = appender.list.get(0).getFormattedMessage();
        assertFalse(formatted.contains("9006000000000001"),
                "the full PAN must not appear in the access log: " + formatted);
        assertTrue(formatted.startsWith("GET /cards/************0001 -> 200 in"),
                "the PAN must be reduced to its last four digits: " + formatted);
    }

    /**
     * :purpose: The same masking applies to the ERROR record written for a 5xx outcome.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("a card number in the URI is masked on the 5xx access-log path too")
    void cardNumberInUriIsMaskedOnErrorPath() throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("PUT", "/cards/9006000000000004");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(request, response, new MockFilterChain());

        String formatted = appender.list.get(0).getFormattedMessage();
        assertEquals(Level.ERROR, appender.list.get(0).getLevel(), "a 5xx outcome logs at ERROR");
        assertFalse(formatted.contains("9006000000000004"),
                "the full PAN must not appear in the access log: " + formatted);
        assertTrue(formatted.contains("/cards/************0004"),
                "the PAN must be reduced to its last four digits: " + formatted);
    }

    /**
     * :purpose: Identifiers shorter than the shortest PAN — an 11-digit account id — stay intact,
     *     so support and correlation are unaffected by the masking.
     * :raises ServletException: if the filter raises a servlet error.
     * :raises IOException: if the filter raises an I/O error.
     */
    @Test
    @DisplayName("an 11-digit account id is left intact")
    void accountIdIsNotMasked() throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/accounts/90000000061");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, new MockFilterChain());

        assertTrue(appender.list.get(0).getFormattedMessage()
                        .startsWith("GET /accounts/90000000061 -> 200 in"),
                "an account id is not PAN-shaped and must stay readable");
    }

    /**
     * :purpose: The ERROR record for an escaping exception on a CARD path must carry the masked
     *     URI, the elapsed time in the duration slot and the exception class in the trailing
     *     slot -- and no unmasked PAN anywhere.
     * :note: This pins a defect that regulatory review would flag: a fifth argument (the RAW
     *     request URI) was passed to the four-placeholder pattern, so the record read
     *     ``GET /cards/************0001 failed after /cards/9000000000000001ms: 2012`` -- the
     *     full 16-digit PAN written to disk beside its own masked form (CWE-532), the duration
     *     rendered as the exception and the exception class dropped altogether.
     */
    @Test
    @DisplayName("an escaping exception on a card path logs the masked URI, the duration and the exception class")
    void escapingExceptionOnCardPathLogsMaskedUriDurationAndExceptionClass() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/cards/9000000000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain boom = new FilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                throw new IllegalStateException("upstream frozen");
            }
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, boom));

        assertEquals(1, appender.list.size(), "the failure must be reported exactly once");
        String message = appender.list.get(0).getFormattedMessage();
        assertFalse(message.contains("9000000000000001"),
                "the unmasked PAN must never reach a log record: " + message);
        assertTrue(message.startsWith("GET /cards/************0001 failed after "),
                "the record must open with the method and the masked URI: " + message);
        assertTrue(message.endsWith("ms: java.lang.IllegalStateException"),
                "the duration must land in the duration slot and the exception class in the"
                        + " trailing slot: " + message);
    }
}
