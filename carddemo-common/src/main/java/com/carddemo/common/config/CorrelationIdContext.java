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

import org.slf4j.MDC;
import java.util.UUID;

/**
 * :purpose: Shared, framework-light helper that gets, sets, generates, and
 *     clears a business correlation id in the SLF4J MDC so that every
 *     structured log line emitted by a service can carry the same id via the
 *     ``%X{correlationId}`` pattern configured in each service's
 *     ``logback-spring.xml``. Usable from web services, batch jobs, and other
 *     non-web modules because it depends only on ``org.slf4j.MDC`` and
 *     ``java.util.UUID``.
 * :note: Micrometer Tracing independently manages ``%X{traceId}`` and
 *     ``%X{spanId}``; this helper does not touch those keys.
 * :note: Every value that reaches the MDC is passed through {@link #sanitize}
 *     first, so caller- or client-supplied ids cannot inject CR/LF control
 *     characters (log forging) or unbounded high-cardinality tokens into the
 *     log stream.
 */
public final class CorrelationIdContext {

    /**
     * :purpose: The MDC key under which the correlation id is stored;
     *     per-service ``logback-spring.xml`` reads it via ``%X{correlationId}``.
     */
    public static final String CORRELATION_ID_KEY = "correlationId";

    /**
     * :purpose: Maximum retained length of a correlation id. Bounds cardinality
     *     and log-line width; a W3C trace-id (32 hex) and a UUID (36 chars) both
     *     fit well within this limit.
     */
    public static final int MAX_CORRELATION_ID_LENGTH = 64;

    /**
     * :purpose: Prevent instantiation of this stateless utility class.
     */
    private CorrelationIdContext() {
    }

    /**
     * :purpose: Reduce an arbitrary, possibly client-supplied value to a bounded,
     *     trace-safe correlation id fit for the MDC and the log stream.
     * :param raw: the candidate value (may be ``null``, blank, overlong, or
     *     contain control characters such as CR/LF).
     * :returns: a value containing only ``[A-Za-z0-9._-]`` and no longer than
     *     {@link #MAX_CORRELATION_ID_LENGTH}, or ``null`` when nothing safe
     *     remains. Any disallowed character (including CR, LF, TAB and other ISO
     *     control characters, spaces and log-delimiter punctuation) is dropped,
     *     which neutralizes log-forging injection and caps cardinality.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(Math.min(raw.length(), MAX_CORRELATION_ID_LENGTH));
        for (int i = 0; i < raw.length() && builder.length() < MAX_CORRELATION_ID_LENGTH; i++) {
            char c = raw.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (safe) {
                builder.append(c);
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    /**
     * :purpose: Read the correlation id currently stored in the MDC.
     * :returns: the current correlation id, or ``null`` if none is set.
     */
    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_KEY);
    }

    /**
     * :purpose: Store a correlation id in the MDC after sanitizing it, clearing
     *     the key when nothing safe remains.
     * :param correlationId: the value to store; it is passed through
     *     {@link #sanitize} first. A ``null``, blank, or fully-stripped value
     *     removes any existing correlation id from the MDC rather than storing an
     *     empty or unsafe token.
     */
    public static void setCorrelationId(String correlationId) {
        String safe = sanitize(correlationId);
        if (safe == null) {
            MDC.remove(CORRELATION_ID_KEY);
        } else {
            MDC.put(CORRELATION_ID_KEY, safe);
        }
    }

    /**
     * :purpose: Generate a new random correlation id without storing it in the
     *     MDC.
     * :returns: a newly generated random correlation id.
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * :purpose: Return the correlation id currently in the MDC, generating and
     *     storing a fresh one when none is present.
     * :returns: the existing correlation id, or a newly generated and stored
     *     correlation id when none was set.
     */
    public static String getOrCreateCorrelationId() {
        String current = getCorrelationId();
        if (current == null || current.isBlank()) {
            current = generateCorrelationId();
            setCorrelationId(current);
        }
        return current;
    }

    /**
     * :purpose: Remove the correlation id from the MDC.
     * :note: Call in a ``finally`` block at the end of a request or batch job
     *     to avoid leaking correlation ids across pooled threads.
     */
    public static void clear() {
        MDC.remove(CORRELATION_ID_KEY);
    }

    /**
     * :purpose: Run an action with the given correlation id in scope, then
     *     restore the previous MDC value afterward so the call is
     *     reentrant-safe on reused threads.
     * :param correlationId: the id to use for the scope; when ``null``, blank, or
     *     reduced to nothing by {@link #sanitize} a fresh correlation id is
     *     generated for the scope.
     * :param action: the work to execute with the correlation id in scope.
     */
    public static void runWithCorrelationId(String correlationId, Runnable action) {
        String previous = MDC.get(CORRELATION_ID_KEY);
        String scoped = sanitize(correlationId);
        setCorrelationId(scoped != null ? scoped : generateCorrelationId());
        try {
            action.run();
        } finally {
            if (previous != null) {
                MDC.put(CORRELATION_ID_KEY, previous);
            } else {
                MDC.remove(CORRELATION_ID_KEY);
            }
        }
    }
}
