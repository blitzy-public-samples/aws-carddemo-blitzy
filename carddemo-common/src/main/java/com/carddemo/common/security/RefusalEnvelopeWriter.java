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

import com.carddemo.common.config.CorrelationIdContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * :purpose: Write the shared error envelope for a request refused BEFORE it reached a
 *     controller — an unauthenticated call, a denied call, or a call the request firewall
 *     rejected. Those three refusals are produced by servlet-layer components that the
 *     ``@ControllerAdvice`` never sees, so each one used to answer in a shape of its own: a
 *     zero-byte body for the two security refusals and the container's four-field error page
 *     for the firewall. A caller could therefore be told nothing at all about why it was
 *     refused, and an operator had no id to correlate the refusal with.
 * :output: The named ``write`` method, and the two error codes the refusals carry.
 * :note: The envelope is deliberately minimal in CONTENT while identical in SHAPE: a fixed
 *     message per status, the request path with any PAN redacted, and the two ids. It names no
 *     resource, no principal and no reason beyond the status, so a refused caller still learns
 *     nothing it did not already know.
 * :note: The JSON is written directly rather than through an ``ObjectMapper`` because
 *     these components are plain collaborators of the security chain, constructed before any
 *     bean is available; the envelope has seven scalar members and one of them is a fixed
 *     literal, so there is nothing here a mapper would get more right.
 */
public final class RefusalEnvelopeWriter {

    /** :purpose: Machine-readable code of an unauthenticated refusal. */
    public static final String CODE_AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";

    /** :purpose: Machine-readable code of a denied refusal for an authenticated principal. */
    public static final String CODE_AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED";

    /** :purpose: Machine-readable code of a request the request firewall rejected. */
    public static final String CODE_REQUEST_REJECTED = "REQUEST_REJECTED";

    /**
     * :purpose: Line-23 text for a refusal that ends the session's usefulness. It matches
     *     the SPA's own fallback literal for a zero-byte ``401`` character for character,
     *     so the screen shows the same sentence whichever side supplied it.
     */
    public static final String MSG_AUTHENTICATION_REQUIRED =
            "Your session has ended. Please sign on again.";

    /**
     * :purpose: Line-23 text for a refusal the caller can recover from in place — a stale
     *     double-submit token, or a principal without the authority for that call. Matches
     *     the SPA's own fallback literal for a zero-byte ``403``.
     */
    public static final String MSG_AUTHORIZATION_DENIED =
            "Request could not be authorized. Please try again.";

    /** :purpose: Fixed text for a request the firewall refused to let into the chain. */
    public static final String MSG_REQUEST_REJECTED = "The request could not be processed";

    /** :purpose: Machine-readable code of a request refused by a source-address budget. */
    public static final String CODE_RATE_LIMITED = "RATE_LIMITED";

    /**
     * :purpose: Line-23 text for a refusal the caller can recover from by waiting. The
     *     accompanying ``Retry-After`` header says how long; the sentence says that waiting
     *     is the remedy, which a zero-byte ``429`` did not.
     */
    public static final String MSG_RATE_LIMITED =
            "Too many requests. Please try again shortly.";

    /** :purpose: Response header name of the MIME-sniffing guard. */
    private static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    /** :purpose: MDC key holding the Micrometer Tracing trace id. */
    private static final String MDC_TRACE_ID = "traceId";

    /**
     * :purpose: Non-instantiable helper holder.
     */
    private RefusalEnvelopeWriter() {
    }

    /**
     * :purpose: Complete the response with the refusal envelope for a refusal decided OUTSIDE
     *     Spring Security's filter chain, supplying the two response headers that chain would
     *     otherwise have written.
     * :param request: the refused request, used for its path only.
     * :param response: the response to complete; left untouched when already committed.
     * :param status: the refusal status.
     * :param errorCode: the machine-readable code for the refusal.
     * :param message: the fixed operator-facing text.
     * :raises IOException: if the body cannot be written.
     * :note: For a refusal decided before or around ``FilterChainProxy`` — the request
     *     firewall and the source-address budget — ``HeaderWriterFilter`` never runs, so nothing
     *     supplies a cache directive or the sniffing guard. Observed on both paths: the response
     *     carried only the correlation id. Setting them here therefore cannot suppress a stronger
     *     directive, which is exactly why {@link #write} does not set them for the in-chain
     *     callers, where the security writer owns them and returns early if anything is present.
     */
    public static void writeOutsideSecurityChain(HttpServletRequest request,
                                                 HttpServletResponse response,
                                                 HttpStatus status,
                                                 String errorCode,
                                                 String message) throws IOException {
        if (!response.isCommitted()) {
            if (!response.containsHeader(HttpHeaders.CACHE_CONTROL)) {
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            }
            if (!response.containsHeader(CONTENT_TYPE_OPTIONS)) {
                response.setHeader(CONTENT_TYPE_OPTIONS, "nosniff");
            }
        }
        write(request, response, status, errorCode, message);
    }

    /**
     * :purpose: Complete the response with the refusal envelope.
     * :param request: the refused request, used for its path only.
     * :param response: the response to complete; left untouched when already committed.
     * :param status: the refusal status.
     * :param errorCode: the machine-readable code for the refusal.
     * :param message: the fixed operator-facing text.
     * :raises IOException: if the body cannot be written.
     */
    public static void write(HttpServletRequest request,
                             HttpServletResponse response,
                             HttpStatus status,
                             String errorCode,
                             String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        byte[] body = envelope(request, status, errorCode, message).getBytes(StandardCharsets.UTF_8);
        // The status is set directly and never through sendError: sendError starts the
        // container ERROR dispatch, which re-enters the security chain and is what turned
        // a genuine failure into an empty 403 before this envelope existed.
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(body.length);
        // Deliberately NOT setting Cache-Control here. Spring Security's HeaderWriterFilter
        // writes its headers lazily, when the response commits, and
        // CacheControlHeadersWriter.writeHeaders returns immediately if ANY of Cache-Control,
        // Expires or Pragma is already present. Setting a bare "no-store" before flushing
        // would therefore suppress the whole hardened triple
        // ("no-cache, no-store, max-age=0, must-revalidate" + Pragma: no-cache + Expires: 0)
        // instead of adding to it. Inside the security chain that writer is the right author.
        // The one path with no header writer is the firewall rejection, and
        // RequestRejectedEnvelopeHandler supplies the directive itself there.
        response.getOutputStream().write(body);
        response.flushBuffer();
    }

    /**
     * :purpose: Render the envelope, in the member order the ``ErrorResponse`` contract
     *     declares so a reader sees one shape whichever component answered.
     * :param request: the refused request; may be ``null``.
     * :param status: the refusal status.
     * :param errorCode: the machine-readable code.
     * :param message: the fixed operator-facing text.
     * :returns: the JSON document.
     */
    private static String envelope(HttpServletRequest request,
                                  HttpStatus status,
                                  String errorCode,
                                  String message) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        member(json, "timestamp", Instant.now().toString(), true);
        json.append(",\"status\":").append(status.value());
        json.append(',');
        member(json, "error", status.getReasonPhrase(), true);
        json.append(',');
        member(json, "errorCode", errorCode, true);
        json.append(',');
        member(json, "message", message, true);
        json.append(',');
        member(json, "path", path(request), true);
        json.append(',');
        member(json, "traceId", MDC.get(MDC_TRACE_ID), true);
        json.append(',');
        member(json, "correlationId", CorrelationIdContext.getCorrelationId(), true);
        // A refusal decided before any controller ran has no per-field validation results,
        // but the member is still written as null rather than omitted: the whole point of the
        // envelope is that one reader can parse every failure, and a key that appears only
        // sometimes is what made four producers look like four contracts.
        json.append(",\"fieldErrors\":null");
        json.append('}');
        return json.toString();
    }

    /**
     * :purpose: Append one member, writing a JSON ``null`` for an absent value rather than
     *     omitting the key, so the shape does not vary with what happened to be available.
     * :param json: the document under construction.
     * :param name: the member name.
     * :param value: the member value; may be ``null`` or blank.
     * :param quoted: whether the value is a JSON string.
     */
    private static void member(StringBuilder json, String name, String value, boolean quoted) {
        json.append('"').append(name).append("\":");
        if (value == null || value.isBlank()) {
            json.append("null");
            return;
        }
        if (quoted) {
            json.append('"').append(escape(value)).append('"');
        } else {
            json.append(value);
        }
    }

    /**
     * :purpose: Resolve the refused request's path with any PAN reduced to its last four
     *     digits, so the envelope can never echo a card number back to a caller, a proxy
     *     log or a browser history entry.
     * :param request: the refused request; may be ``null``.
     * :returns: the redacted path, or ``null`` when there is no request.
     */
    private static String path(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String uri = request.getRequestURI();
        return uri == null ? null : SensitiveDataMasker.maskPan(uri);
    }

    /**
     * :purpose: Escape a value for a JSON string literal. Only the characters JSON forbids
     *     unescaped are touched; everything else, including non-ASCII text, is written
     *     through because the response declares UTF-8.
     * :param value: the raw value.
     * :returns: the escaped value.
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
