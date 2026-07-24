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
package com.carddemo.common.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * :purpose: Standardized JSON error body returned by the CardDemo REST APIs and
 *  produced by the shared global exception handler. This is a framework-light POJO
 *  that carries only non-sensitive error metadata (status, message, path, timestamp,
 *  trace id, and optional per-field validation messages); it never echoes card
 *  numbers, passwords, SSNs, or other PII/PCI values. Rule-mandated observability
 *  infrastructure with no legacy COBOL source.
 */
public class ErrorResponse {

    /** :purpose: Instant the error was produced; serialized as ISO-8601. */
    private Instant timestamp;

    /** :purpose: HTTP status code (for example 400, 404, 409, 500). */
    private int status;

    /** :purpose: HTTP reason phrase / short error label (for example "Not Found", "Conflict"). */
    private String error;

    /**
     * :purpose: Stable, non-sensitive application/domain error code that is
     *  independent of the HTTP status. Carries the exact legacy reject/validation
     *  codes (for example the batch posting reject codes ``100``-``103`` from
     *  ``CBTRN02C``) so that programmatic callers and the React client can branch
     *  on a precise code rather than parsing the human-readable message. Null when
     *  the error has no domain-specific code.
     */
    private String errorCode;

    /** :purpose: Human-readable, non-sensitive detail message. */
    private String message;

    /** :purpose: Request URI that produced the error. */
    private String path;

    /** :purpose: Correlation / trace id sourced from the MDC; null when tracing is absent. */
    private String traceId;

    /** :purpose: Optional per-field validation messages (field name to message); null or empty otherwise. */
    private Map<String, String> fieldErrors;

    /**
     * :purpose: Create an empty error body and default the timestamp to the current
     *  instant. Required for JSON (Jackson) deserialization; the exception handler
     *  may overwrite any field afterwards.
     */
    public ErrorResponse() {
        this.timestamp = Instant.now();
    }

    /**
     * :purpose: Create an error body from the core HTTP error metadata, defaulting the
     *  timestamp to the current instant and leaving traceId and fieldErrors to setters.
     * :param status: HTTP status code.
     * :param error: HTTP reason phrase / short error label.
     * :param message: human-readable, non-sensitive detail message.
     * :param path: request URI that produced the error.
     */
    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = Instant.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    /**
     * :purpose: Create an error body from the core HTTP error metadata plus a stable
     *  domain error code, defaulting the timestamp to the current instant and leaving
     *  traceId and fieldErrors to setters.
     * :param status: HTTP status code.
     * :param error: HTTP reason phrase / short error label.
     * :param message: human-readable, non-sensitive detail message.
     * :param path: request URI that produced the error.
     * :param errorCode: stable, non-sensitive application/domain error code.
     */
    public ErrorResponse(int status, String error, String message, String path, String errorCode) {
        this.timestamp = Instant.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.errorCode = errorCode;
    }

    /**
     * :purpose: Return the instant the error was produced.
     * :output: the error timestamp, serialized as ISO-8601.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * :purpose: Set the instant the error was produced.
     * :param timestamp: the error timestamp.
     */
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * :purpose: Return the HTTP status code.
     * :output: the numeric HTTP status (for example 400, 404, 409, 500).
     */
    public int getStatus() {
        return status;
    }

    /**
     * :purpose: Set the HTTP status code.
     * :param status: the numeric HTTP status.
     */
    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * :purpose: Return the HTTP reason phrase / short error label.
     * :output: the error label (for example "Not Found", "Conflict").
     */
    public String getError() {
        return error;
    }

    /**
     * :purpose: Set the HTTP reason phrase / short error label.
     * :param error: the error label.
     */
    public void setError(String error) {
        this.error = error;
    }

    /**
     * :purpose: Return the stable application/domain error code.
     * :output: the domain error code (for example a ``100``-``103`` reject code),
     *  or ``null`` when the error has no domain-specific code.
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * :purpose: Set the stable application/domain error code.
     * :param errorCode: the domain error code, independent of the HTTP status.
     */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    /**
     * :purpose: Return the human-readable, non-sensitive detail message.
     * :output: the detail message.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the human-readable, non-sensitive detail message.
     * :param message: the detail message.
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * :purpose: Return the request URI that produced the error.
     * :output: the request path.
     */
    public String getPath() {
        return path;
    }

    /**
     * :purpose: Set the request URI that produced the error.
     * :param path: the request path.
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * :purpose: Return the correlation / trace id sourced from the MDC.
     * :output: the trace id, or ``null`` when tracing is absent.
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * :purpose: Set the correlation / trace id.
     * :param traceId: the trace id sourced from the MDC.
     */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * :purpose: Return the optional per-field validation messages.
     * :output: a field-name-to-message map, or ``null``/empty when there are none.
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * :purpose: Set the per-field validation messages.
     * :param fieldErrors: a field-name-to-message map.
     */
    public void setFieldErrors(Map<String, String> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }

    /**
     * :purpose: Add a single field-level validation message, lazily initializing the
     *  backing map as an insertion-ordered LinkedHashMap on first use.
     * :param field: name of the field that failed validation.
     * :param message: validation message describing the failure.
     */
    public void addFieldError(String field, String message) {
        if (this.fieldErrors == null) {
            this.fieldErrors = new LinkedHashMap<>();
        }
        this.fieldErrors.put(field, message);
    }

    /**
     * :purpose: Render a non-sensitive diagnostic representation for logging.
     * :output: a string containing the status, labels, codes and metadata fields;
     *  no PII/PCI values are included.
     */
    @Override
    public String toString() {
        return "ErrorResponse{timestamp=" + timestamp
                + ", status=" + status
                + ", error='" + error + '\''
                + ", errorCode='" + errorCode + '\''
                + ", message='" + message + '\''
                + ", path='" + path + '\''
                + ", traceId='" + traceId + '\''
                + ", fieldErrors=" + fieldErrors
                + '}';
    }
}
