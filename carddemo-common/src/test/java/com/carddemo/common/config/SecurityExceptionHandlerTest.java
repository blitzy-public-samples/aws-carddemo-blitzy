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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.ServletWebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Verify {@link SecurityExceptionHandler} translates the Spring Security
 *  failures raised inside the application into the documented ``ErrorResponse``
 *  envelope, so a denied or unauthenticated request is never answered with an
 *  empty body, and that no exception detail is echoed back to an attacker.
 * :output: Asserts 403 for an authorization denial and 401 for an authentication
 *  failure, both carrying the status reason phrase and the request path.
 */
class SecurityExceptionHandlerTest {

    /** :purpose: System under test. */
    private final SecurityExceptionHandler handler = new SecurityExceptionHandler();

    /**
     * :purpose: An in-application authorization denial answers 403 with the envelope.
     */
    @Test
    @DisplayName("an access denial answers 403 with the envelope and no detail")
    void accessDenialAnswers403() {
        ServletWebRequest request =
                new ServletWebRequest(new MockHttpServletRequest("POST", "/users"));

        ResponseEntity<ErrorResponse> response = handler.handleAccessDenied(
                new AccessDeniedException("Access Denied for ROLE_USER on /users"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(403);
        assertThat(response.getBody().getError()).isEqualTo("Forbidden");
        assertThat(response.getBody().getMessage()).isEqualTo("Forbidden");
        assertThat(response.getBody().getPath()).isEqualTo("/users");
        assertThat(response.getBody().getMessage()).doesNotContain("ROLE_USER");
    }

    /**
     * :purpose: An authentication failure answers 401 with the envelope.
     */
    @Test
    @DisplayName("an authentication failure answers 401 with the envelope")
    void authenticationFailureAnswers401() {
        ServletWebRequest request =
                new ServletWebRequest(new MockHttpServletRequest("POST", "/auth/signon"));

        ResponseEntity<ErrorResponse> response = handler.handleAuthenticationFailure(
                new BadCredentialsException("Bad credentials for ADMIN001"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(401);
        assertThat(response.getBody().getMessage()).isEqualTo("Unauthorized");
        assertThat(response.getBody().getPath()).isEqualTo("/auth/signon");
        assertThat(response.getBody().getMessage()).doesNotContain("ADMIN001");
    }
}
