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

import com.carddemo.common.dto.ErrorResponse;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * :purpose: Verify {@link CardDemoErrorController} renders the documented
 *  ``ErrorResponse`` envelope for a container-level error dispatch, replacing
 *  Spring Boot's abbreviated ``{timestamp,status,error,path}`` body so only one
 *  error shape exists in the API contract.
 * :output: Asserts the dispatched status, the ORIGINAL request URI, the reason
 *  phrase in both ``error`` and ``message``, a populated timestamp, and that no
 *  exception detail is disclosed.
 */
class CardDemoErrorControllerTest {

    /**
     * :purpose: Build the controller with an absent error-attributes provider, the
     *  configuration a non-web-error context yields.
     * :returns: the controller under test.
     */
    private CardDemoErrorController newController() {
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.boot.webmvc.error.ErrorAttributes> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return new CardDemoErrorController(provider);
    }

    /**
     * :purpose: A dispatched 404 renders the envelope with the original URI.
     */
    @Test
    @DisplayName("a dispatched 404 renders the envelope with the original URI")
    void dispatched404RendersEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/users/");

        ResponseEntity<ErrorResponse> response = newController().handleError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(404);
        assertThat(body.getError()).isEqualTo("Not Found");
        assertThat(body.getMessage()).isEqualTo("Not Found");
        assertThat(body.getPath()).isEqualTo("/users/");
        assertThat(body.getTimestamp()).isNotNull();
    }

    /**
     * :purpose: A dispatch carrying no status code is reported as 500 rather than
     *  as a success, and the dispatch URI is used when the original is absent.
     */
    @Test
    @DisplayName("a dispatch without a status code renders 500 on the dispatch URI")
    void dispatchWithoutStatusRenders500() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        ResponseEntity<ErrorResponse> response = newController().handleError(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getPath()).isEqualTo("/error");
        assertThat(response.getBody().getMessage()).isEqualTo("Internal Server Error");
    }

    /**
     * :purpose: A recorded exception is never echoed to the caller; only the status
     *  reason phrase is disclosed.
     */
    @Test
    @DisplayName("a recorded exception is not echoed to the caller")
    void recordedExceptionIsNotEchoed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/accounts/1");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("jdbc password=secret"));

        ResponseEntity<ErrorResponse> response = newController().handleError(request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().getMessage()).doesNotContain("secret");
    }
}
