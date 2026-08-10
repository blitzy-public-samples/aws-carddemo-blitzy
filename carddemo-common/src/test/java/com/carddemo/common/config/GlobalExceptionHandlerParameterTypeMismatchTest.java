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
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * :purpose: Verifies that a request PARAMETER whose value cannot be converted to the type the
 *     endpoint declares answers with a CardDemo message and a populated error code, instead of
 *     the framework's own ``Failed to convert 'page' with value: 'abc'`` wording. The submitted
 *     value must never appear in the envelope, and a service that registers a legacy message for
 *     the parameter must have that message reported instead of the generic one.
 */
@DisplayName("GlobalExceptionHandler — request-parameter type mismatch (400)")
class GlobalExceptionHandlerParameterTypeMismatchTest {

    /** :purpose: Generic message used when no service resolver owns the parameter. */
    private static final String GENERIC = "Malformed request parameter";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * :purpose: Endpoint signature the mismatched parameter is bound against; a real
     *  ``MethodParameter`` is required because the exception carries one.
     * :param page: the zero-based page index of the browse.
     */
    @SuppressWarnings("unused")
    private void listUsers(int page) {
        // Signature-only target for MethodParameter resolution.
    }

    private static ServletWebRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users");
        request.setRequestURI("/users");
        request.setQueryString("page=abc");
        return new ServletWebRequest(request);
    }

    /**
     * :purpose: Build the exception exactly as Spring raises it when a query parameter fails
     *  conversion.
     * :param submitted: the value the caller supplied.
     * :param name: the parameter name.
     * :returns: the populated mismatch exception.
     */
    private MethodArgumentTypeMismatchException mismatch(Object submitted, String name) {
        Method method;
        try {
            method = GlobalExceptionHandlerParameterTypeMismatchTest.class
                    .getDeclaredMethod("listUsers", int.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("the signature-only target must exist", ex);
        }
        return new MethodArgumentTypeMismatchException(submitted, int.class, name,
                new MethodParameter(method, 0),
                new IllegalArgumentException("For input string: \"" + submitted + "\""));
    }

    /**
     * :purpose: The reported message must be the CardDemo generic, must carry the validation
     *  error code, and must not quote the caller's value anywhere in the envelope.
     * :param submitted: values the QA reproduction used, both of which fail ``int`` conversion.
     */
    @ParameterizedTest(name = "[{index}] page={0} reports the CardDemo message without echoing it")
    @ValueSource(strings = {"abc", "1e9", "9999999999999999999", "0x1F"})
    void unconvertibleParameterReportsTheCardDemoMessage(String submitted) {
        ResponseEntity<ErrorResponse> response =
                handler.handleParameterTypeMismatch(mismatch(submitted, "page"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(GENERIC);
        assertThat(body.getErrorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.getPath()).isEqualTo("/users");
        assertThat(body.getFieldErrors()).containsEntry("page", GENERIC);
        // The framework's wording quotes the submitted value; the envelope must not.
        assertThat(body.getMessage()).doesNotContain(submitted);
        assertThat(body.getMessage()).doesNotContain("Failed to convert");
    }

    /**
     * :purpose: A service that registers a resolver for the parameter must have ITS legacy
     *  message reported, so a wrong-typed parameter reads the same as failing the screen edit
     *  that owns the field.
     */
    @Test
    @DisplayName("a registered legacy message outranks the generic one")
    void registeredLegacyMessageIsPreferred() {
        String legacy = "Tran ID must be Numeric ...";
        handler.setTypeMismatchMessageResolver(
                property -> "page".equals(property) ? legacy : null);

        ResponseEntity<ErrorResponse> response =
                handler.handleParameterTypeMismatch(mismatch("abc", "page"), request());

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(legacy);
        assertThat(body.getFieldErrors()).containsEntry("page", legacy);
        assertThat(body.getErrorCode()).isEqualTo("VALIDATION_FAILED");
    }

    /**
     * :purpose: A resolver that does not own the parameter must leave the generic message in
     *  place rather than suppress the response.
     */
    @Test
    @DisplayName("a resolver that owns a different parameter falls back to the generic message")
    void unrelatedResolverFallsBackToTheGenericMessage() {
        handler.setTypeMismatchMessageResolver(
                property -> "acctCreditLimit".equals(property) ? "Credit Limit is not valid" : null);

        ResponseEntity<ErrorResponse> response =
                handler.handleParameterTypeMismatch(mismatch("abc", "page"), request());

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(GENERIC);
    }

    /**
     * :purpose: A blank resolved message is treated as no message at all, so a resolver that
     *  answers with whitespace cannot blank the line-23 region.
     */
    @Test
    @DisplayName("a blank resolved message falls back to the generic message")
    void blankResolvedMessageFallsBackToTheGenericMessage() {
        handler.setTypeMismatchMessageResolver(property -> "   ");

        ResponseEntity<ErrorResponse> response =
                handler.handleParameterTypeMismatch(mismatch("abc", "page"), request());

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(GENERIC);
    }

    /**
     * :purpose: A parameter bound into a command OBJECT (``@ModelAttribute``) fails through the
     *  binding result rather than through its own exception, and Spring's message for that failure
     *  names the internal Java types as well as quoting the value. It must be substituted exactly
     *  as the ``@RequestParam`` case is, or the same leak reappears on every list endpoint that
     *  binds its query string into a request DTO.
     */
    @Test
    @DisplayName("a type mismatch inside a bound command object reports the CardDemo message")
    void typeMismatchInsideACommandObjectIsSubstituted() {
        PagingCommand target = new PagingCommand();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(new FieldError("request", "pageNumber", "abc", true,
                new String[] {"typeMismatch"}, null,
                "Failed to convert property value of type 'java.lang.String' to required type 'int'"
                        + " for property 'pageNumber'; For input string: \"abc\""));
        Method method;
        try {
            method = GlobalExceptionHandlerParameterTypeMismatchTest.class
                    .getDeclaredMethod("listUsers", int.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("the signature-only target must exist", ex);
        }
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

        ResponseEntity<Object> response =
                handler.handleMethodArgumentNotValid(ex, new HttpHeaders(),
                        HttpStatus.BAD_REQUEST, request());

        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(GENERIC);
        assertThat(body.getErrorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.getFieldErrors()).containsEntry("pageNumber", GENERIC);
        assertThat(body.getMessage()).doesNotContain("java.lang.String");
        assertThat(body.getMessage()).doesNotContain("abc");
    }

    /**
     * :purpose: A genuine CONSTRAINT violation must still report its own message verbatim; the
     *  substitution above applies only to type-conversion failures.
     */
    @Test
    @DisplayName("a constraint violation inside a bound object keeps its own message")
    void constraintViolationKeepsItsOwnMessage() {
        PagingCommand target = new PagingCommand();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(new FieldError("request", "pageNumber", null, false,
                new String[] {"Min"}, null, "Page number must not be negative"));
        Method method;
        try {
            method = GlobalExceptionHandlerParameterTypeMismatchTest.class
                    .getDeclaredMethod("listUsers", int.class);
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("the signature-only target must exist", ex);
        }
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

        ResponseEntity<Object> response =
                handler.handleMethodArgumentNotValid(ex, new HttpHeaders(),
                        HttpStatus.BAD_REQUEST, request());

        ErrorResponse body = (ErrorResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Page number must not be negative");
    }

    /**
     * :purpose: Command object the binding-result cases bind against.
     */
    static class PagingCommand {

        /** :purpose: The paging index a caller supplies in the query string. */
        private int pageNumber;

        /**
         * :purpose: Read the paging index.
         * :output: the ``pageNumber`` value.
         */
        public int getPageNumber() {
            return pageNumber;
        }

        /**
         * :purpose: Set the paging index.
         * :param pageNumber: the ``pageNumber`` value.
         */
        public void setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
        }
    }

    /**
     * :purpose: The envelope must carry the correlation id of the current request, so a caller's
     *  400 can be tied back to the log line that recorded it.
     */
    @Test
    @DisplayName("the envelope carries the request's correlation id")
    void envelopeCarriesTheCorrelationId() {
        CorrelationIdContext.setCorrelationId("11111111-2222-3333-4444-555555555555");
        try {
            ResponseEntity<ErrorResponse> response =
                    handler.handleParameterTypeMismatch(mismatch("abc", "page"), request());

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getCorrelationId())
                    .isEqualTo("11111111-2222-3333-4444-555555555555");
        } finally {
            CorrelationIdContext.clear();
        }
    }
}
