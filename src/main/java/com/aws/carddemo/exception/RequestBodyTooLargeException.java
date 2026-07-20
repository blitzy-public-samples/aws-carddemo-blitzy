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
package com.aws.carddemo.exception;

import java.io.IOException;

/**
 * Signals that an inbound HTTP request body exceeded the configured maximum size
 * (defense-in-depth bound; QA finding F-P7-JSON, decision log D67).
 *
 * <p>It deliberately extends {@link IOException} rather than a runtime exception
 * so it can be thrown from a {@code jakarta.servlet.ServletInputStream#read()}
 * override while a request body is being consumed. When the body is read by a
 * Spring {@code HttpMessageConverter} (for example Jackson deserializing a
 * {@code @RequestBody}), the converter wraps this {@code IOException} in a
 * {@link org.springframework.http.converter.HttpMessageNotReadableException};
 * {@code GlobalExceptionHandler} inspects that exception's cause chain and, when it
 * finds this type, reports HTTP {@code 413 Payload Too Large} rather than a generic
 * {@code 400}. The complementary fast path &mdash; a declared {@code Content-Length}
 * already larger than the limit &mdash; is rejected up front by
 * {@code RequestBodySizeLimitFilter} before any body byte is read.</p>
 */
public class RequestBodyTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The configured maximum request-body size, in bytes, that was exceeded. */
    private final long limitBytes;

    /**
     * Creates the exception for the given configured limit.
     *
     * @param limitBytes the maximum request-body size, in bytes, that was exceeded
     */
    public RequestBodyTooLargeException(long limitBytes) {
        super("Request body exceeds the configured maximum of " + limitBytes + " bytes.");
        this.limitBytes = limitBytes;
    }

    /**
     * Returns the configured maximum request-body size, in bytes, that was exceeded.
     *
     * @return the limit, in bytes
     */
    public long getLimitBytes() {
        return limitBytes;
    }
}
