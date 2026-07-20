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
package com.aws.carddemo.config;

import com.aws.carddemo.exception.RequestBodyTooLargeException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Servlet filter that bounds the size of every inbound HTTP request body as a
 * defense-in-depth control (QA finding F-P7-JSON, decision log D67).
 *
 * <p><strong>Why this exists.</strong> The CardDemo online DTOs are tiny &mdash; an
 * account update or a transaction add is a few hundred bytes &mdash; yet, left
 * unbounded, a JSON write endpoint would read an arbitrarily large body fully into
 * memory before Bean Validation ever ran, so a single oversized {@code POST} could
 * exhaust heap. This filter caps the accepted body at
 * {@code carddemo.web.max-request-body-bytes} (default {@value #DEFAULT_MAX_BODY_BYTES}
 * bytes = 1&nbsp;MiB) and reports the excess as {@code 413 Payload Too Large} in the
 * same RFC&nbsp;7807 {@code application/problem+json} shape every other CardDemo error
 * surface uses, carrying the per-request correlation ID.</p>
 *
 * <p><strong>Two complementary checks.</strong></p>
 * <ol>
 *   <li><em>Fast path.</em> When the request declares a {@code Content-Length} already
 *       larger than the limit, the request is rejected up front &mdash; before Spring
 *       Security and before a single body byte is read &mdash; by writing the 413
 *       problem detail directly onto the response.</li>
 *   <li><em>Streaming path.</em> When the length is unknown or understated (chunked
 *       transfer encoding, or a {@code Content-Length} smaller than the bytes actually
 *       sent), the request is wrapped so its input stream counts bytes as they are
 *       consumed and throws {@link RequestBodyTooLargeException} the moment the limit is
 *       crossed. That {@link IOException} surfaces through the HTTP message converter as
 *       a {@code HttpMessageNotReadableException}, which
 *       {@code GlobalExceptionHandler#handleNotReadable} maps to {@code 413} (rather than
 *       its usual {@code 400}) by finding this type in the cause chain.</li>
 * </ol>
 *
 * <p><strong>Ordering.</strong> Registered at {@link Ordered#HIGHEST_PRECEDENCE}{@code +1}
 * &mdash; immediately after the observability {@code CorrelationIdFilter}
 * ({@link Ordered#HIGHEST_PRECEDENCE}) and well before the Spring Security chain &mdash;
 * so the correlation ID is already in the SLF4J {@link MDC} when a rejection is written,
 * and an oversized request is rejected as early as possible. It does not run on container
 * {@code ERROR} dispatches (the {@link OncePerRequestFilter} default), because the body
 * has already been consumed (or rejected) by then.</p>
 *
 * <p><strong>Security.</strong> The filter never reads, buffers, copies, or logs request
 * body content, credentials, or card data; it counts bytes only. The only client-echoed
 * value is the bounded, opaque correlation ID. The complementary
 * {@code server.tomcat.max-swallow-size} setting bounds how much of an aborted oversized
 * body the container will swallow after the 413 is sent.</p>
 *
 * <p>Its only injected collaborator is the Boot-configured {@link ObjectMapper} (reused so
 * the {@link ProblemDetail} Jackson support that flattens extension properties to top level
 * is applied consistently); the limit is supplied by {@code @Value} with a safe default so
 * the filter is fully functional even when the property is absent.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    /**
     * Default maximum request-body size in bytes (1&nbsp;MiB) used when
     * {@code carddemo.web.max-request-body-bytes} is not set. Comfortably larger than any
     * legitimate CardDemo request DTO while bounding the memory a single request can force
     * the server to buffer.
     */
    public static final long DEFAULT_MAX_BODY_BYTES = 1_048_576L;

    /** SLF4J logger; a {@code private static final} logger is not dependency injection. */
    private static final Logger log = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

    /**
     * MDC key under which the observability {@code CorrelationIdFilter} stores the
     * per-request correlation ID. Kept as a local literal (rather than importing the
     * observability package) so the config layer carries no compile-time dependency on it;
     * the value must stay in sync with {@code CorrelationIdFilter.CORRELATION_ID_MDC_KEY}
     * and the {@code GlobalExceptionHandler} constant of the same value.
     */
    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /** Fixed, non-revealing detail returned to the client for an oversized body. */
    private static final String DETAIL = "The request body exceeds the maximum permitted size.";

    /** Short, human-readable problem title, consistent with the other 4xx handlers. */
    private static final String TITLE = "Payload Too Large";

    /** Boot-configured mapper, reused to serialize the RFC 7807 problem detail. */
    private final ObjectMapper objectMapper;

    /** Configured maximum request-body size, in bytes. */
    private final long maxRequestBodyBytes;

    /**
     * Creates the filter with the shared object mapper and the configured body-size limit.
     *
     * @param objectMapper        the Boot-configured Jackson mapper used to write the 413 body
     * @param maxRequestBodyBytes the maximum accepted request-body size in bytes; defaults to
     *                            {@link #DEFAULT_MAX_BODY_BYTES} when the property is unset
     */
    public RequestBodySizeLimitFilter(
            ObjectMapper objectMapper,
            @Value("${carddemo.web.max-request-body-bytes:" + DEFAULT_MAX_BODY_BYTES + "}") long maxRequestBodyBytes) {
        this.objectMapper = objectMapper;
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    /**
     * Rejects a request whose declared {@code Content-Length} already exceeds the limit, and
     * otherwise forwards a size-bounding wrapper down the chain so an understated or unknown
     * length is still enforced while the body is read.
     *
     * @param request     the current HTTP request
     * @param response    the current HTTP response
     * @param filterChain the remainder of the filter chain
     * @throws ServletException if the downstream chain raises a servlet error
     * @throws IOException      if writing the rejection or the downstream chain raises an I/O error
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxRequestBodyBytes) {
            writePayloadTooLarge(request, response, declaredLength);
            return;
        }
        filterChain.doFilter(new BoundedBodyRequestWrapper(request, maxRequestBodyBytes), response);
    }

    /**
     * Writes an RFC&nbsp;7807 {@code 413 Payload Too Large} problem detail directly onto the
     * response (fast path), attaching the request URI as {@code instance} and, when present, the
     * correlation ID from the {@link MDC}. The offending length is logged server-side only.
     *
     * @param request        the current request (its URI becomes the problem {@code instance})
     * @param response       the response to write the problem detail onto
     * @param declaredLength the rejected declared {@code Content-Length}, for the server-side log
     * @throws IOException if writing to the response fails
     */
    private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response,
                                      long declaredLength) throws IOException {
        log.warn("Rejected oversized request body: declared Content-Length {} exceeds limit {} bytes for {}",
                declaredLength, maxRequestBodyBytes, request.getRequestURI());
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, DETAIL);
        problemDetail.setTitle(TITLE);
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CORRELATION_ID_MDC_KEY, correlationId);
        }
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }

    /**
     * Request wrapper that returns a size-bounding view of the body so that a chunked or
     * length-understated request is enforced as it is read. Both {@link #getInputStream()} and
     * {@link #getReader()} route through the same counting stream so either consumption style is
     * bounded; the servlet contract allows only one of the two to be used per request, and each is
     * cached so repeated calls return the same instance.
     */
    private static final class BoundedBodyRequestWrapper extends HttpServletRequestWrapper {

        private final long limitBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private BoundedBodyRequestWrapper(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (this.inputStream == null) {
                this.inputStream = new BoundedServletInputStream(super.getInputStream(), this.limitBytes);
            }
            return this.inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (this.reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
                this.reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return this.reader;
        }
    }

    /**
     * {@link ServletInputStream} decorator that counts the bytes read from the delegate and throws
     * {@link RequestBodyTooLargeException} as soon as the cumulative total exceeds the configured
     * limit. Every read path ({@link #read()} and {@link #read(byte[], int, int)}) is guarded, and
     * the async-I/O and lifecycle methods delegate unchanged so streaming clients behave normally
     * up to the limit.
     */
    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limitBytes;
        private long bytesRead;

        private BoundedServletInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                bytesRead++;
                enforceLimit();
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                bytesRead += count;
                enforceLimit();
            }
            return count;
        }

        private void enforceLimit() throws RequestBodyTooLargeException {
            if (bytesRead > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
