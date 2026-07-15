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
package com.aws.carddemo.account.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, structured error body returned by {@code GlobalExceptionHandler} for every
 * REST error response produced by the Account Management service (Feature F-003).
 *
 * <p>This is the single, consistent JSON shape emitted for {@code 400 Bad Request},
 * {@code 404 Not Found}, {@code 409 Conflict}, and any optional {@code 5xx} responses,
 * replacing the ad-hoc 3270 screen error messages of the legacy {@code COACTVWC}/{@code COACTUPC}
 * COBOL programs with a machine-readable contract.</p>
 *
 * <p>A serialized instance looks like:</p>
 * <pre>{@code
 * {
 *   "timestamp": "2026-07-15T20:09:30.123456Z",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "activeStatus must be 'Y' or 'N'",
 *   "path": "/api/v1/accounts/{accountId}",
 *   "fieldErrors": {
 *     "activeStatus": "must be 'Y' or 'N'"
 *   }
 * }
 * }</pre>
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Serialize-only.</b> Instances are written to HTTP responses and are never
 *       deserialized from incoming requests, so only getters are provided (no setters and
 *       no no-arg constructor are required for Jackson output).</li>
 *   <li><b>Immutable.</b> All fields are {@code final}; the optional {@code fieldErrors}
 *       map is stored as an unmodifiable, insertion-ordered copy to prevent post-construction
 *       mutation and to keep field ordering deterministic in the JSON output.</li>
 *   <li><b>Null-omitting.</b> The class is annotated with
 *       {@link JsonInclude}({@link JsonInclude.Include#NON_NULL}) so that {@code fieldErrors}
 *       (and any other absent field) is omitted from the JSON for simple, non-validation
 *       errors, keeping the payload clean.</li>
 *   <li><b>ISO-8601 timestamp.</b> No {@code @JsonFormat} is declared on {@link #timestamp};
 *       Spring Boot auto-registers Jackson's {@code JavaTimeModule} (via
 *       {@code jackson-datatype-jsr310}, present transitively through
 *       {@code spring-boot-starter-web}) and disables {@code WRITE_DATES_AS_TIMESTAMPS} by
 *       default, so {@link OffsetDateTime} serializes as an ISO-8601 string automatically.</li>
 *   <li><b>Passive container.</b> This class performs no formatting of monetary values or
 *       account/card numbers. Callers are responsible for passing already-sanitized strings
 *       so that sensitive data (full account numbers, balances, credit limits) never leaks
 *       into an error payload or the logs.</li>
 *   <li><b>Sanitized path (AAP &sect;0.6.6).</b> The {@link #path} is expected to be the
 *       sanitized request-mapping route template (for example {@code /api/v1/accounts/{accountId}}),
 *       <em>not</em> the raw request URI. The 11-digit account id is classified sensitive
 *       "full account number" data; supplying the template rather than the concrete URI keeps
 *       the id out of the serialized error body and out of any log that captures it
 *       (CWE-209 / CWE-532). The producing {@code GlobalExceptionHandler} is responsible for
 *       passing the template; this container neither derives nor rewrites the value.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {

    /** Instant at which the error body was produced, serialized as an ISO-8601 string. */
    private final OffsetDateTime timestamp;

    /** Numeric HTTP status code (for example {@code 400}, {@code 404}, {@code 409}). */
    private final int status;

    /** HTTP reason phrase (for example {@code "Bad Request"}, {@code "Not Found"}, {@code "Conflict"}). */
    private final String error;

    /** Human-readable, already-sanitized description of the error condition. */
    private final String message;

    /**
     * Sanitized request path that produced the error — the request-mapping route template
     * (for example {@code /api/v1/accounts/{accountId}}), never the raw URI, so the sensitive
     * 11-digit account id is not stored or serialized here (AAP &sect;0.6.6).
     */
    private final String path;

    /**
     * Optional map of field name to validation message, populated for {@code 400} validation
     * failures and {@code null} otherwise. Stored as an unmodifiable, insertion-ordered copy.
     */
    private final Map<String, String> fieldErrors;

    /**
     * Convenience constructor for simple errors that carry no per-field detail.
     * Delegates to {@link #ApiError(int, String, String, String, Map)} with a {@code null}
     * {@code fieldErrors} map, which is subsequently omitted from the JSON output.
     *
     * @param status  numeric HTTP status code
     * @param error   HTTP reason phrase
     * @param message human-readable, already-sanitized error message
     * @param path    sanitized request path (route template, e.g. {@code /api/v1/accounts/{accountId}}),
     *                never the raw URI containing the account id
     */
    public ApiError(int status, String error, String message, String path) {
        this(status, error, message, path, null);
    }

    /**
     * Full constructor. Captures the current instant as the {@link #timestamp} and assigns the
     * remaining fields. When {@code fieldErrors} is non-null and non-empty it is copied into an
     * unmodifiable {@link LinkedHashMap} to preserve insertion order and guarantee immutability;
     * when it is {@code null} or empty the stored value is {@code null} so that the
     * {@link JsonInclude}({@link JsonInclude.Include#NON_NULL}) annotation omits it from the JSON.
     *
     * @param status      numeric HTTP status code
     * @param error       HTTP reason phrase
     * @param message     human-readable, already-sanitized error message
     * @param path        sanitized request path (route template, e.g.
     *                    {@code /api/v1/accounts/{accountId}}), never the raw URI containing the account id
     * @param fieldErrors optional field-to-message map for validation failures; may be {@code null}
     */
    public ApiError(int status, String error, String message, String path,
                    Map<String, String> fieldErrors) {
        this.timestamp = OffsetDateTime.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.fieldErrors = (fieldErrors == null || fieldErrors.isEmpty())
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    /**
     * @return the instant at which this error body was produced
     */
    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    /**
     * @return the numeric HTTP status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * @return the HTTP reason phrase
     */
    public String getError() {
        return error;
    }

    /**
     * @return the human-readable, already-sanitized error message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return the sanitized request path (route template) that produced the error;
     *         does not contain the raw account id
     */
    public String getPath() {
        return path;
    }

    /**
     * @return an unmodifiable, insertion-ordered map of field-to-message validation details,
     *         or {@code null} when this error carries no per-field information
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
