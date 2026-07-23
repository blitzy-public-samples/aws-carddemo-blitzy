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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

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

    @Override
    public String toString() {
        return "ErrorResponse{timestamp=" + timestamp
                + ", status=" + status
                + ", error='" + error + '\''
                + ", message='" + message + '\''
                + ", path='" + path + '\''
                + ", traceId='" + traceId + '\''
                + ", fieldErrors=" + fieldErrors
                + '}';
    }
}
