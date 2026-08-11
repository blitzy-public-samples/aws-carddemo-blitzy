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
package com.carddemo.common.config;

import com.carddemo.common.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * :purpose: Render the documented {@link ErrorResponse} envelope, with status ``503
 *     Service Unavailable``, for a datastore failure raised OUTSIDE the reach of the Spring
 *     dispatcher. The session store is the case this exists for: Spring Session's
 *     ``SessionRepositoryFilter`` runs at ``Integer.MIN_VALUE + 50``, ahead of the dispatcher
 *     and of Spring Security, so when Redis is unreachable the failure never reaches {@link
 *     GlobalExceptionHandler}; the container's own ERROR dispatch then re-enters the same
 *     failing filter, so not even {@link CardDemoErrorController} can render for it and the
 *     caller receives the servlet container's status page instead of the JSON contract.
 * :output: The envelope written directly to the response — status 503, content type
 *     ``application/json``, the generic {@link #MESSAGE} detail, the PAN-redacted request
 *     path, and the trace and correlation ids of the failing request — with the response
 *     headers already set by the outer filters (notably ``X-Correlation-Id``) preserved,
 *     because only the response BUFFER is reset. No exception message, stack frame, driver
 *     detail or datastore address is disclosed.
 * :note: Registered by {@link WebObservabilityConfig} at ``HIGHEST_PRECEDENCE + 20``:
 *     inside {@link CorrelationIdFilter} and {@link RequestLoggingFilter}, so the MDC that
 *     supplies ``correlationId`` and ``traceId`` is populated, and OUTSIDE the session-store
 *     filter, so the failure is caught rather than escaping to the container.
 * :note: Only the two failures that mean the datastore could not be REACHED are converted
 *     -- ``DataAccessResourceFailureException`` (which is what Spring Data Redis'
 *     ``RedisConnectionFailureException`` is) and ``QueryTimeoutException`` (a Lettuce or JDBC
 *     command that timed out). Anything else propagates untouched, so this filter can neither
 *     mask an application defect as an outage nor downgrade a statement the datastore REJECTED
 *     from ``500`` to ``503``; a ``TransactionException`` is answered by {@link
 *     GlobalExceptionHandler} because a transaction is only ever begun inside the dispatcher.
 */
public class DatastoreOutageErrorFilter extends OncePerRequestFilter {

    /**
     * :purpose: Caller-facing detail for an unavailable datastore. Deliberately free of
     *  the datastore's identity, address and driver message.
     */
    static final String MESSAGE = "A required datastore is currently unavailable. Please retry the request.";

    /**
     * :purpose: ``Retry-After`` value, in seconds, advertised with the ``503`` this filter
     *  writes. An outage is transient by definition, and the same value is advertised by
     *  {@link GlobalExceptionHandler} for the identical condition raised inside the
     *  dispatcher, so a caller is told to wait the same interval wherever it was detected.
     */
    public static final String RETRY_AFTER_SECONDS = "10";

    /** :purpose: Logger for datastore outages observed on the request path. */
    private static final Logger log = LoggerFactory.getLogger(DatastoreOutageErrorFilter.class);

    /**
     * :purpose: Bound on how far the cause chain of a thrown exception is walked when
     *  looking for the translated data-access failure, so a self-referential or
     *  pathologically deep chain cannot spin.
     */
    private static final int MAX_CAUSE_DEPTH = 12;

    /**
     * :purpose: Provider of the application's configured JSON mapper, so the envelope is
     *  serialized exactly as the ``@RestControllerAdvice`` path serializes it. Resolved
     *  lazily and tolerated as absent, in which case a local mapper is used.
     */
    private final ObjectProvider<ObjectMapper> objectMapperProvider;

    /**
     * :purpose: Construct the filter with the application's JSON mapper provider.
     * :param objectMapperProvider: provider of the configured
     *  {@link tools.jackson.databind.ObjectMapper}; may resolve to nothing.
     */
    public DatastoreOutageErrorFilter(ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.objectMapperProvider = objectMapperProvider;
    }

    /**
     * :purpose: Run the remainder of the chain and convert an escaping datastore failure
     *  into the documented envelope.
     * :param request: the current HTTP request.
     * :param response: the current HTTP response.
     * :param filterChain: the remaining chain, which includes the session-store filter.
     * :raises ServletException: propagated unchanged when the failure is not a datastore failure.
     * :raises IOException: propagated unchanged when the failure is not a datastore failure,
     *  or raised while writing the envelope.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            RuntimeException failure = findDataAccessFailure(ex);
            if (failure == null) {
                throw ex;
            }
            if (response.isCommitted()) {
                // The status line and part of the body are already on the wire, so the
                // envelope cannot replace them; report it and let the container end the
                // exchange rather than silently truncating.
                log.error("Datastore unavailable for {} after the response was committed: {}",
                        request.getRequestURI(), failure.getClass().getSimpleName(), failure);
                throw ex;
            }
            writeServiceUnavailable(request, response, failure);
        }
    }

    /**
     * :purpose: Locate the Spring-translated data-access failure in a thrown exception,
     *  which a servlet filter may have wrapped in a ``ServletException``.
     * :param thrown: the exception that escaped the chain.
     * :returns: the first ``DataAccessResourceFailureException`` or ``QueryTimeoutException`` in
     *  the cause chain, or ``null`` when the chain holds neither.
     * :note: Only those three are intercepted. A statement the datastore REJECTED (a
     *  ``DataIntegrityViolationException``, for instance) is not an outage and must not be
     *  downgraded from ``500`` to ``503``: a ``503`` tells the caller to retry an identical
     *  request, which for a rejected statement would fail identically.
     */
    private RuntimeException findDataAccessFailure(Throwable thrown) {
        Throwable candidate = thrown;
        for (int depth = 0; candidate != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (candidate instanceof DataAccessResourceFailureException
                    || candidate instanceof QueryTimeoutException) {
                return (RuntimeException) candidate;
            }
            Throwable cause = candidate.getCause();
            if (cause == candidate) {
                return null;
            }
            candidate = cause;
        }
        return null;
    }

    /**
     * :purpose: Write the 503 envelope, discarding whatever partial body the failed
     *  chain had buffered while keeping the headers the outer filters already set.
     * :param request: the failing request, used for the envelope's path and ids.
     * :param response: the response to render into.
     * :param failure: the translated datastore failure, logged but never disclosed.
     * :raises IOException: if the envelope cannot be written to the response.
     */
    private void writeServiceUnavailable(HttpServletRequest request,
                                         HttpServletResponse response,
                                         RuntimeException failure) throws IOException {
        log.error("Datastore unavailable for {}: {}", request.getRequestURI(),
                failure.getClass().getSimpleName(), failure);

        // resetBuffer(), NOT reset(): the latter would also discard the headers the
        // correlation-id and security filters already wrote onto this response.
        response.resetBuffer();
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);

        ErrorResponse body = ErrorResponseFactory.build(HttpStatus.SERVICE_UNAVAILABLE, MESSAGE,
                new ServletWebRequest(request));
        byte[] payload = serialize(body);
        response.setContentLength(payload.length);
        write(response, payload);
        response.flushBuffer();
    }

    /**
     * :purpose: Serialize the envelope with the application's mapper so the field set and
     *  ordering match every other error response.
     * :param body: the populated envelope.
     * :returns: the UTF-8 JSON bytes of the envelope.
     */
    private byte[] serialize(ErrorResponse body) {
        ObjectMapper mapper = objectMapperProvider == null ? null : objectMapperProvider.getIfAvailable();
        if (mapper == null) {
            mapper = JsonMapper.builder().build();
        }
        return mapper.writeValueAsBytes(body);
    }

    /**
     * :purpose: Write the payload through whichever output channel this response is still
     *  able to give, because a downstream component may already have taken the writer.
     * :param response: the response to write to.
     * :param payload: the serialized envelope.
     * :raises IOException: if both output channels fail.
     */
    private void write(HttpServletResponse response, byte[] payload) throws IOException {
        try {
            response.getOutputStream().write(payload);
        } catch (IllegalStateException streamUnavailable) {
            // getWriter() was already called on this response; the servlet contract
            // forbids switching to the stream, so finish through the writer.
            response.getWriter().write(new String(payload, StandardCharsets.UTF_8));
        }
    }
}
