/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.account.config;

import com.aws.carddemo.account.exception.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Servlet filter that enforces a hard upper bound on the size of an inbound request body
 * <em>before</em> it is deserialized (SEC-INPUT-1, finding F-10).
 *
 * <h2>Why a filter rather than a Jackson limit alone</h2>
 * <p>The application's {@code ObjectMapper} already sets a {@code maxDocumentLength} stream-read
 * constraint (see {@code AccountServiceApplication#jsonHardeningCustomizer}). That limit, however, is
 * only applied while the parser is actually consuming characters: a large run of <em>skipped</em>
 * content (for example the value of an unknown property that binding ignores, since
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} is intentionally left {@code false} so that legitimate
 * over-posting still succeeds with HTTP&nbsp;200) is not always counted deterministically against the
 * document-length budget, so payloads a little above the intended 64&nbsp;KiB ceiling could slip
 * through and only be refused once they grew much larger. This filter closes that gap by counting the
 * raw request bytes independently of what Jackson chooses to materialize, guaranteeing a deterministic
 * ceiling for every body-carrying request.</p>
 *
 * <h2>Enforcement</h2>
 * <p>The cap is {@value #MAX_BODY_BYTES} bytes (64&nbsp;KiB). A request whose body <em>exceeds</em>
 * the cap is rejected with HTTP&nbsp;400 (Bad Request) carrying the same sanitized {@link ApiError}
 * envelope and generic {@value #MALFORMED_BODY_MESSAGE} summary that
 * {@code GlobalExceptionHandler#handleNotReadable} produces for a malformed body, so the client sees a
 * single, uniform malformed-body contract regardless of whether the byte cap, a Jackson stream
 * constraint, or a parse error triggered the rejection. A body exactly at the cap is accepted (it then
 * flows to Jackson, whose {@code maxDocumentLength} is aligned to the same value).</p>
 *
 * <p>Enforcement is two-layered so the cap holds regardless of how the client frames the body:</p>
 * <ul>
 *   <li><strong>Declared length fast-path</strong> &mdash; when {@code Content-Length} is present and
 *       already exceeds the cap, the request is rejected immediately without reading the body.</li>
 *   <li><strong>Bounded buffering</strong> &mdash; otherwise the body is read into a bounded buffer
 *       (at most cap+1 bytes are ever read); if the stream yields more than the cap the request is
 *       rejected, and if it is within the cap the buffered bytes are re-served to downstream
 *       components via a {@link HttpServletRequestWrapper}. This makes the cap effective even for a
 *       chunked request that omits {@code Content-Length}.</li>
 * </ul>
 *
 * <p>Only body-carrying methods ({@code POST}, {@code PUT}, {@code PATCH}) are inspected; a
 * {@code GET}/{@code HEAD}/{@code OPTIONS} request (which the controller never reads a body from, and
 * which includes the Swagger UI and Actuator endpoints) passes through untouched, so documentation and
 * health probes are unaffected.</p>
 *
 * <h2>Security</h2>
 * <p>The rejection body never echoes any submitted content: only the fixed generic summary and a
 * digit-masked route template ({@code /api/v1/accounts/{accountId}}) are emitted, so the 11-digit
 * account id and any payload fragment are never serialized (AAP &sect;0.6.6, CWE-209/CWE-532). The
 * filter performs no logging.</p>
 *
 * <p>Registered as a {@link Component} so Spring Boot maps it across the servlet path, and ordered
 * early ({@link Ordered#HIGHEST_PRECEDENCE}&nbsp;+&nbsp;10) so the byte cap is applied before any
 * body-reading occurs downstream.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    /**
     * Maximum accepted request-body size in bytes (64&nbsp;KiB). A body of exactly this size is
     * accepted; a body strictly larger is rejected. Deliberately aligned with the Jackson
     * {@code maxDocumentLength} stream-read constraint so the two guards agree on the ceiling.
     */
    static final int MAX_BODY_BYTES = 64 * 1024; // 65536

    /**
     * Generic, sanitized summary returned for an over-sized body. Intentionally identical to
     * {@code GlobalExceptionHandler}'s malformed-body message so the client observes one uniform
     * contract for every unreadable/oversized body.
     */
    static final String MALFORMED_BODY_MESSAGE = "Malformed or unreadable request body";

    /** Placeholder substituted for an all-digit path segment so the account id never leaks. */
    private static final String ACCOUNT_ID_PLACEHOLDER = "{accountId}";

    /** HTTP methods that may carry a request body and therefore warrant a size check. */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    /** Read-chunk size for bounded buffering. */
    private static final int READ_CHUNK = 8192;

    /**
     * Jackson mapper used to serialize the {@link ApiError} rejection body. Injected (rather than
     * constructed) so the response uses the same configured mapper &mdash; including JSR-310 date
     * handling &mdash; as the rest of the API, keeping the error envelope byte-compatible.
     */
    private final ObjectMapper objectMapper;

    /**
     * Creates the filter.
     *
     * @param objectMapper the application's configured Jackson {@link ObjectMapper}
     */
    public RequestBodySizeLimitFilter(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Enforces the body-size cap for body-carrying methods, delegating everything else unchanged.
     *
     * @param request     the inbound request
     * @param response    the outbound response
     * @param filterChain the remaining filter chain
     * @throws ServletException if a downstream component fails
     * @throws IOException      if an I/O error occurs
     */
    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain filterChain)
            throws ServletException, IOException {

        if (!BODY_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // Fast-path: a declared Content-Length that already exceeds the cap needs no body read.
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            writeMalformedBody(request, response);
            return;
        }

        // Bounded read: buffer at most cap+1 bytes; reject if the stream yields more than the cap.
        final byte[] body = readBounded(request.getInputStream());
        if (body == null) {
            writeMalformedBody(request, response);
            return;
        }

        // Within the cap: re-serve the buffered bytes so downstream binding can read the body.
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    /**
     * Reads the stream into a buffer, stopping as soon as more than {@link #MAX_BODY_BYTES} bytes are
     * observed.
     *
     * @param in the request input stream
     * @return the fully buffered body when it is within the cap, or {@code null} when it exceeds the cap
     * @throws IOException if reading the stream fails
     */
    private static byte[] readBounded(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final byte[] chunk = new byte[READ_CHUNK];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (total + read > MAX_BODY_BYTES) {
                return null; // exceeds the cap
            }
            buffer.write(chunk, 0, read);
            total += read;
        }
        return buffer.toByteArray();
    }

    /**
     * Writes a sanitized {@code 400} {@link ApiError} response for an over-sized body.
     *
     * @param request  the current request (used only to derive the masked route template)
     * @param response the response to write
     * @throws IOException if writing the response fails
     */
    private void writeMalformedBody(final HttpServletRequest request,
                                    final HttpServletResponse response) throws IOException {
        final ApiError body = new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                MALFORMED_BODY_MESSAGE,
                sanitizePath(request));
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    /**
     * Derives a sanitized route template from the request URI by masking every all-digit path
     * segment with {@link #ACCOUNT_ID_PLACEHOLDER}. This mirrors the fallback masking in
     * {@code GlobalExceptionHandler#resolvePath} (the request-mapping best-match attribute is not yet
     * set at filter time), so the concrete 11-digit account id is never serialized.
     *
     * @param request the current request
     * @return the digit-masked path, for example {@code /api/v1/accounts/{accountId}}
     */
    private static String sanitizePath(final HttpServletRequest request) {
        final String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return uri;
        }
        final String[] segments = uri.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            final String segment = segments[i];
            if (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit)) {
                segments[i] = ACCOUNT_ID_PLACEHOLDER;
            }
        }
        return String.join("/", segments);
    }

    /**
     * Request wrapper that re-serves a previously buffered body, allowing downstream components
     * (message converters, binding) to read the request stream even though this filter already
     * consumed it during the bounded read.
     */
    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        /** The buffered request body. */
        private final byte[] body;

        /**
         * @param request the original request
         * @param body    the buffered body bytes
         */
        CachedBodyHttpServletRequest(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            final String encoding = getCharacterEncoding();
            final Charset charset = (encoding != null)
                    ? Charset.forName(encoding)
                    : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
        }
    }

    /**
     * {@link ServletInputStream} backed by an in-memory byte array, used to replay a buffered body.
     */
    private static final class CachedBodyServletInputStream extends ServletInputStream {

        /** Delegate stream over the buffered bytes. */
        private final ByteArrayInputStream delegate;

        /**
         * @param body the buffered body bytes
         */
        CachedBodyServletInputStream(final byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public int available() {
            return delegate.available();
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            // Synchronous replay of an in-memory buffer; non-blocking read is not supported.
            throw new UnsupportedOperationException("Non-blocking read is not supported");
        }
    }
}
