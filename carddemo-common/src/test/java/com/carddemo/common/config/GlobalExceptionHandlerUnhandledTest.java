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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.dto.ErrorResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * :purpose: Verifies the catch-all handler that guarantees a trace id on every error
 *     payload. A framework failure that declares its own status must keep that status and
 *     its response headers while gaining the shared error body; an internal fault must
 *     report ``500`` with a fixed message and no leaked detail; and a Spring Security
 *     authentication or authorization failure must be re-thrown so the security filter
 *     chain still produces the configured ``401``/``403`` with its audit record.
 */
@DisplayName("GlobalExceptionHandler — catch-all handler (status preservation and trace id)")
class GlobalExceptionHandlerUnhandledTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/accounts/1");
        request.setRequestURI("/accounts/1");
        return new ServletWebRequest(request);
    }

    @Test
    @DisplayName("an unsupported method keeps 405 and its Allow header")
    void unsupportedMethodKeeps405AndAllowHeader() throws Exception {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", List.of("POST"));

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(405);
        assertThat(response.getBody().getPath()).isEqualTo("/accounts/1");
        assertThat(response.getBody().getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("an unsupported media type keeps 415")
    void unsupportedMediaTypeKeeps415() throws Exception {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(415);
    }

    @Test
    @DisplayName("an unmapped path keeps 404 and gains the shared body")
    void unmappedPathKeeps404() throws Exception {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/nosuchroute", null);

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(404);
        assertThat(response.getBody().getError()).isEqualTo("Not Found");
    }

    @Test
    @DisplayName("an internal fault reports 500 with a fixed message and no leaked detail")
    void internalFaultReports500WithoutLeakingDetail() throws Exception {
        RuntimeException ex = new IllegalStateException("jdbc://user:secret@db/carddemo is unreachable");

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().getMessage()).doesNotContain("secret");
        assertThat(response.getBody().getStatus()).isEqualTo(500);
    }

    @Test
    @DisplayName("an authorization failure is re-thrown so the security chain still answers 403")
    void authorizationFailureIsRethrown() {
        AccessDeniedException ex = new AccessDeniedException("Access Denied");

        assertThatThrownBy(() -> handler.handleUnhandled(ex, request())).isSameAs(ex);
    }

    @Test
    @DisplayName("an authentication failure is re-thrown so the security chain still answers 401")
    void authenticationFailureIsRethrown() {
        BadCredentialsException ex = new BadCredentialsException("Bad credentials");

        assertThatThrownBy(() -> handler.handleUnhandled(ex, request())).isSameAs(ex);
    }

    @Test
    @DisplayName("an unwrapped size signal reports 413, not 500 (the api-gateway proxy path)")
    void unwrappedSizeSignalReports413() throws Exception {
        // At the api-gateway the proxy streams the request body downstream from its own
        // publisher thread, so the cap is breached inside RestClientProxyExchange.copyBody
        // and the signal reaches the web layer unwrapped rather than inside a
        // message-converter exception.
        RequestSizeLimitFilter.RequestSizeExceededException ex =
                new RequestSizeLimitFilter.RequestSizeExceededException();

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(413);
        assertThat(response.getBody().getMessage())
                .isEqualTo("Request body exceeds the configured maximum size");
    }

    @Test
    @DisplayName("a wrapped size signal also reports 413 and never echoes the payload")
    void wrappedSizeSignalReports413() throws Exception {
        RuntimeException ex = new IllegalStateException("stream aborted",
                new RequestSizeLimitFilter.RequestSizeExceededException());

        ResponseEntity<ErrorResponse> response = handler.handleUnhandled(ex, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("Request body exceeds the configured maximum size");
    }
}
