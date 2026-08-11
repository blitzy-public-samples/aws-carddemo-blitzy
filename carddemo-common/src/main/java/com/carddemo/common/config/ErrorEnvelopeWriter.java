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
import com.carddemo.common.security.SensitiveDataMasker;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * :purpose: Render the documented {@link ErrorResponse} envelope for the failures that are
 *     answered OUTSIDE Spring MVC's exception handling and therefore cannot go through {@link
 *     GlobalExceptionHandler}: a security filter-chain denial, a request refused by the shared
 *     size cap, and a rejection the servlet container answers on its own. Without one writer
 *     those paths answered with an empty body or with the container's own markup, so a client
 *     faced several error shapes for one API.
 * :output: Static helpers that serialize the envelope -- status, reason phrase as both
 *     ``error`` and ``message``, PAN-masked path, correlation id, trace id -- and write it to
 *     a servlet response.
 * :note: The envelope is written by hand rather than through an ``ObjectMapper`` because
 *     these callers are filters, valves, and security handlers that are constructed outside
 *     the application context and hold no serializer. Only the status reason phrase is ever
 *     used as the message: no exception detail, resource name, or framework internal is
 *     disclosed.
 */
public final class ErrorEnvelopeWriter {

    /** :purpose: Logger for envelope-write failures on an already-gone response. */
    private static final Logger log = LoggerFactory.getLogger(ErrorEnvelopeWriter.class);

    /** :purpose: Utility class; never instantiated. */
    private ErrorEnvelopeWriter() {
    }

    /**
     * :purpose: Build the envelope JSON for a container- or filter-level failure.
     * :param status: the HTTP status being reported.
     * :param reason: the caller-facing message, which is the status reason phrase.
     * :param path: the request path; PAN-masked by this method.
     * :param correlationId: the business correlation id, or ``null`` when none was resolved.
     * :param traceId: the distributed-trace id, or ``null`` when tracing is inactive.
     * :returns: the JSON document carrying the documented envelope members.
     */
    public static String json(int status, String reason, String path,
                              String correlationId, String traceId) {
        String maskedPath = path == null ? null : SensitiveDataMasker.maskPan(path);
        // The nine members are written in the ``ErrorResponse`` DECLARATION order, which is
        // the order every other component that can answer a failure writes them in. Four
        // components can produce this envelope, so a reader finds the same document
        // whichever one answered only if they all agree on the order as well as the set.
        return "{\"timestamp\":\"" + Instant.now() + "\""
                + ",\"status\":" + status
                + ",\"error\":" + quoteOrNull(reason)
                + ",\"errorCode\":null"
                + ",\"message\":" + quoteOrNull(reason)
                + ",\"path\":" + quoteOrNull(maskedPath)
                + ",\"traceId\":" + quoteOrNull(traceId)
                + ",\"correlationId\":" + quoteOrNull(correlationId)
                + ",\"fieldErrors\":null"
                + "}";
    }

    /**
     * :purpose: Complete a servlet response with the envelope for the supplied status,
     *  taking the correlation id from the current request scope and the trace id from the
     *  tracing MDC.
     * :param response: the response to complete; left untouched when already committed.
     * :param status: the status to report.
     * :param path: the request path to echo.
     */
    public static void write(HttpServletResponse response, HttpStatus status, String path) {
        if (response == null || response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = json(status.value(), status.getReasonPhrase(), path,
                CorrelationIdContext.getCorrelationId(), ErrorResponseFactory.traceId());
        try {
            PrintWriter writer = response.getWriter();
            writer.write(body);
            writer.flush();
        } catch (IOException | IllegalStateException ex) {
            // The response stream is already gone or was claimed as a binary stream; the
            // status has been set, which is the part the caller depends on.
            log.debug("Unable to write the error envelope for status {}", status.value(), ex);
        }
    }

    /**
     * :purpose: Render a value as a JSON string literal, or as ``null``.
     * :param value: the value to render; may be ``null``.
     * :returns: the quoted, escaped literal, or the bare token ``null``.
     */
    private static String quoteOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    /**
     * :purpose: Escape the characters that would otherwise break the JSON document or
     *  allow content injection into it.
     * :param value: the raw value.
     * :returns: the escaped value.
     */
    public static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
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
