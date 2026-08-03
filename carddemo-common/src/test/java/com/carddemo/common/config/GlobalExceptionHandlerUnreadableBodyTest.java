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

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * :purpose: Verifies how an unreadable request body is classified. A body refused by the
 *     shared request-size cap must report ``413`` even though the abort signal reaches the
 *     advice wrapped in a message-converter exception, while a genuinely malformed body
 *     must keep reporting ``400``. Neither response nor log may echo the submitted
 *     content, because the converter's own message quotes the offending payload.
 */
@DisplayName("GlobalExceptionHandler — unreadable request body (413 vs 400)")
class GlobalExceptionHandlerUnreadableBodyTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/signon");
        request.setRequestURI("/auth/signon");
        return new ServletWebRequest(request);
    }

    @Test
    @DisplayName("a body refused by the size cap reports 413 with a non-echoing message")
    void sizeCapSignalReports413() {
        // Exactly the shape produced at runtime: the converter wraps the filter's abort
        // signal and quotes the payload in its own message.
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Request body exceeds the configured maximum size",
                new RequestSizeLimitFilter.RequestSizeExceededException(),
                new org.springframework.http.server.ServletServerHttpRequest(
                        (MockHttpServletRequest) request().getNativeRequest()));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableRequestBody(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Request body exceeds the configured maximum size");
        assertThat(response.getBody().getPath()).isEqualTo("/auth/signon");
        assertThat(response.getBody().getStatus()).isEqualTo(413);
    }

    @Test
    @DisplayName("the size signal is detected through a nested cause chain")
    void sizeCapSignalIsDetectedThroughANestedChain() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error",
                new IllegalStateException("converter",
                        new RequestSizeLimitFilter.RequestSizeExceededException()),
                new org.springframework.http.server.ServletServerHttpRequest(
                        (MockHttpServletRequest) request().getNativeRequest()));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableRequestBody(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    @Test
    @DisplayName("a malformed body still reports 400 and never echoes the payload")
    void malformedBodyReports400WithoutEchoingThePayload() {
        String payload = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxSECRETPAYLOAD";
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: Unrecognized token '" + payload + "'",
                new IllegalArgumentException("bad token"),
                new org.springframework.http.server.ServletServerHttpRequest(
                        (MockHttpServletRequest) request().getNativeRequest()));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableRequestBody(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
        assertThat(response.getBody().getMessage()).doesNotContain("SECRETPAYLOAD");
        assertThat(response.getBody().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("an unreadable body with no cause is treated as malformed, not oversized")
    void unreadableBodyWithoutACauseReports400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Required request body is missing",
                new org.springframework.http.server.ServletServerHttpRequest(
                        (MockHttpServletRequest) request().getNativeRequest()));

        ResponseEntity<ErrorResponse> response = handler.handleUnreadableRequestBody(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}
