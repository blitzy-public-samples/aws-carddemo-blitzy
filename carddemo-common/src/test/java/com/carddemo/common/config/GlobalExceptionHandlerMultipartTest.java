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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * :purpose: Verify the multipart classification. A request declaring a multipart content type
 *     on an endpoint that consumes JSON is refused with ``415`` and the shared error body,
 *     whether or not its boundary is well formed. Before this handler existed the malformed
 *     variant reached the catch-all and reported ``500`` with an ERROR log and a stack trace on
 *     six of seven write routes, so the same refusal had two statuses and an unauthenticated
 *     client could flood the operator's alerting with a header alone.
 * :output: Assertions over the status, the message, and the ``413`` carve-out for the size
 *     refusal.
 */
@DisplayName("GlobalExceptionHandler — multipart request classification")
class GlobalExceptionHandlerMultipartTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users");
        request.setRequestURI("/users");
        return new ServletWebRequest(request);
    }

    @Test
    @DisplayName("a malformed multipart request reports 415, not 500")
    void malformedMultipartReports415() {
        MultipartException ex = new MultipartException(
                "Failed to parse multipart servlet request: no multipart boundary was found");

        ResponseEntity<ErrorResponse> response = handler.handleMultipart(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(415);
        assertThat(response.getBody().getError()).isEqualTo("Unsupported Media Type");
        assertThat(response.getBody().getPath()).isEqualTo("/users");
    }

    @Test
    @DisplayName("the message names the media type and never echoes the submitted header")
    void messageNamesTheMediaTypeOnly() {
        // The parse failure quotes the request back; the boundary is caller-controlled and
        // must not be reflected, so the published message is fixed.
        MultipartException ex = new MultipartException(
                "Failed to parse multipart servlet request; boundary=----<script>alert(1)</script>");

        ResponseEntity<ErrorResponse> response = handler.handleMultipart(ex, request());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Content-Type 'multipart/form-data' is not supported.");
        assertThat(response.getBody().getMessage())
                .doesNotContain("script")
                .doesNotContain("boundary");
    }

    @Test
    @DisplayName("every error-body key of the shared envelope is populated")
    void sharedEnvelopeIsPopulated() {
        ResponseEntity<ErrorResponse> response =
                handler.handleMultipart(new MultipartException("no boundary"), request());

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTimestamp()).isNotNull();
        assertThat(body.getStatus()).isEqualTo(415);
        assertThat(body.getError()).isNotBlank();
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getPath()).isEqualTo("/users");
    }

    @Test
    @DisplayName("the size refusal still reports 413, not 415")
    void sizeRefusalStillReports413() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(1_048_576L);

        ResponseEntity<ErrorResponse> response = handler.handleMultipart(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(413);
    }
}
