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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

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
    /**
     * :purpose: A value that is PRESENT but of the wrong type is a field edit failure, not a
     *   malformed document: the JSON parsed and exactly one property could not be converted. The
     *   response must name that property and carry the legacy message its edit owns, because the
     *   generic malformed-body message named no field and silently replaced a frozen literal.
     */
    @Test
    @DisplayName("a type mismatch reports the resolver's legacy message and names the field")
    void typeMismatchReportsTheLegacyFieldMessage() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        advice.setTypeMismatchMessageResolver(
                property -> "acctCreditLimit".equals(property) ? "Credit Limit is not valid" : null);

        ResponseEntity<ErrorResponse> response =
                advice.handleUnreadableRequestBody(mismatchOn("acctCreditLimit"), webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Credit Limit is not valid");
        assertThat(body.getFieldErrors()).containsEntry("acctCreditLimit", "Credit Limit is not valid");
    }

    /**
     * :purpose: A property the resolver does not own keeps the generic message, so contributing a
     *   resolver cannot invent a message for a field it knows nothing about.
     */
    @Test
    @DisplayName("an unowned property falls back to the generic malformed-body message")
    void unownedPropertyFallsBackToGenericMessage() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        advice.setTypeMismatchMessageResolver(property -> null);

        ResponseEntity<ErrorResponse> response =
                advice.handleUnreadableRequestBody(mismatchOn("somethingElse"), webRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
        assertThat(response.getBody().getFieldErrors()).isNull();
    }

    /**
     * :purpose: With no resolver contributed at all the behaviour is unchanged, so a service that
     *   opts out is unaffected.
     */
    @Test
    @DisplayName("with no resolver the generic malformed-body message is unchanged")
    void withoutResolverGenericMessageIsUnchanged() {
        ResponseEntity<ErrorResponse> response =
                new GlobalExceptionHandler()
                        .handleUnreadableRequestBody(mismatchOn("acctCreditLimit"), webRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    /**
     * :purpose: A syntax error carries no property path, so it must keep reporting the generic
     *   message rather than being attributed to some field.
     */
    @Test
    @DisplayName("a body with no property path keeps the generic message")
    void bodyWithoutPropertyPathKeepsGenericMessage() {
        GlobalExceptionHandler advice = new GlobalExceptionHandler();
        advice.setTypeMismatchMessageResolver(property -> "should not be consulted");

        ResponseEntity<ErrorResponse> response = advice.handleUnreadableRequestBody(
                unreadable("truncated", new java.io.IOException("eof")), webRequest());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Malformed request body");
    }

    /**
     * :purpose: Build an unreadable-body exception whose cause is a per-property type mismatch on
     *   the named property, as the JSON converter raises for a wrong-typed value.
     * :param property: the property that failed conversion.
     * :returns: the exception the advice receives.
     */
    private static HttpMessageNotReadableException mismatchOn(String property) {
        MismatchedInputException cause =
                MismatchedInputException.from((tools.jackson.core.JsonParser) null,
                        java.math.BigDecimal.class, "not a number");
        cause.prependPath(new JacksonException.Reference(Object.class, property));
        return unreadable("wrong type", cause);
    }

    /**
     * :purpose: Build an unreadable-body exception with the supplied cause, supplying the input
     *  message the constructor requires.
     * :param message: the converter's own message, which the advice must never echo.
     * :param cause: the underlying conversion failure.
     * :returns: the exception the advice receives.
     */
    private static HttpMessageNotReadableException unreadable(String message, Throwable cause) {
        return new HttpMessageNotReadableException(message, cause,
                new org.springframework.http.server.ServletServerHttpRequest(
                        new MockHttpServletRequest("PUT", "/accounts/90000000001")));
    }

    /**
     * :purpose: Provide a web request for the advice to derive the path from.
     * :returns: a servlet web request for an account update.
     */
    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(
                new MockHttpServletRequest("PUT", "/accounts/90000000001"));
    }
}
