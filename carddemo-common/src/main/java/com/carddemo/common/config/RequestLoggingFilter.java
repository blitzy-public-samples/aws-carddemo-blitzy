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

import com.carddemo.common.security.SensitiveDataMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * :purpose: Emit exactly one structured access-log record per HTTP request so every request the
 *           service handles is observable, and so an unhandled failure is reported while the
 *           correlation id and trace id are still in scope.
 * :output: One log event per request carrying the HTTP method, the request URI (query string
 *          excluded), the response status and the wall-clock duration in milliseconds. Because it
 *          runs inside {@link CorrelationIdFilter}'s scope, the structured encoder attaches the
 *          ``correlationId``, ``traceId`` and ``spanId`` MDC entries to the record automatically.
 *          Level is INFO for a 1xx-4xx outcome and ERROR for 5xx or for an escaping exception.
 * :note: Two defects motivate this filter. First, no service logged anything at all for a request,
 *        so a correlation id only ever appeared when some application code happened to log.
 *        Second, an exception escaping the dispatcher was reported by the container AFTER the
 *        correlation filter's ``finally`` had already cleared the MDC, so the single most
 *        diagnostic line in the whole system carried no correlation id. Catching, logging and
 *        rethrowing here produces that diagnostic line inside the MDC scope; the container's own
 *        duplicate line is left untouched.
 * :note: The Kubernetes/Compose probe endpoints (``/actuator/health`` and its groups) are skipped.
 *        They are polled every few seconds by three separate probes per service and carry no
 *        diagnostic value, so logging them would bury real traffic. Every other path — including
 *        ``/actuator/prometheus`` and every business route — is logged.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    /**
     * :purpose: Access logger, deliberately named after this class so an operator can raise or
     *           silence request logging on its own with
     *           ``logging.level.com.carddemo.common.config.RequestLoggingFilter``.
     */
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** :purpose: Probe path prefix excluded from the access log (see the class note). */
    private static final String HEALTH_PATH_PREFIX = "/actuator/health";

    /** :purpose: Lowest status code treated as a server-side failure for log-level selection. */
    private static final int SERVER_ERROR_THRESHOLD = 500;

    /**
     * :purpose: Suppress access logging for the liveness/readiness/health probe endpoints.
     * :param request: the current request.
     * :returns: ``true`` when the request targets a probe endpoint and must not be logged.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && uri.startsWith(HEALTH_PATH_PREFIX);
    }

    /**
     * :purpose: Time the downstream chain and emit the single access-log record for the request,
     *           reporting an escaping exception at ERROR before rethrowing it unchanged.
     * :param request: the current request.
     * :param response: the current response, read for its status after the chain completes.
     * :param filterChain: the remainder of the filter chain.
     * :raises ServletException: rethrown unchanged from the downstream chain.
     * :raises IOException: rethrown unchanged from the downstream chain.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            // Logged here, not in the container, so the record still carries the MDC
            // correlation/trace context. The exception itself is propagated untouched so the
            // container's error dispatch and the configured error handling are unaffected.
            log.error("{} {} failed after {}ms: {}",
                    request.getMethod(),
                    loggedUri(request),
                    elapsedMillis(startNanos),
                    ex.getClass().getName(),
                    ex);
            throw ex;
        }
        int status = response.getStatus();
        long durationMs = elapsedMillis(startNanos);
        String uri = loggedUri(request);
        if (status >= SERVER_ERROR_THRESHOLD) {
            log.error("{} {} -> {} in {}ms", request.getMethod(), uri, status, durationMs);
        } else {
            log.info("{} {} -> {} in {}ms", request.getMethod(), uri, status, durationMs);
        }
    }

    /**
     * :purpose: Render the request URI for the access log with every PAN-shaped digit run
     *     reduced to its last four digits. The card screens address a card by its number
     *     (``/cards/{cardNum}``), so logging the URI verbatim wrote a full PAN into the access
     *     log of every card request -- and of the gateway in front of it (CWE-532) -- while the
     *     domain-error path was already masking it. The account id (11 digits) and every other
     *     identifier are shorter than the shortest PAN and stay intact for support.
     * :param request: the current request.
     * :returns: the request URI with any PAN masked.
     */
    private static String loggedUri(HttpServletRequest request) {
        return SensitiveDataMasker.maskPan(request.getRequestURI());
    }

    /**
     * :purpose: Convert a start timestamp into an elapsed-millisecond count.
     * :param startNanos: the ``System.nanoTime()`` value captured before the chain ran.
     * :returns: the elapsed time in whole milliseconds.
     */
    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
