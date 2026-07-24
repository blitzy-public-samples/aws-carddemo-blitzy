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
package com.carddemo.auth.controller;

import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.repository.SecurityUserRepository;
import com.carddemo.auth.service.AuthenticationService;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionContext;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Web-layer (``@WebMvcTest``) HTTP-contract tests for
 *  {@link AuthenticationController}, the CardDemo sign-on endpoint
 *  ``POST /auth/signon`` (CICS transaction ``CC00``, program ``COSGN00C``).
 *  Loads only the controller slice, imports the shared
 *  {@link GlobalExceptionHandler}, disables the servlet security filter chain,
 *  and mocks {@link AuthenticationService}. Verifies the success response shape
 *  (userId, ``SEC-USR-TYPE`` wire code ``"A"``/``"U"`` from which the client
 *  derives the ``ROLE_ADMIN``/``ROLE_USER`` authority, and the ``CA00``/``CM00``
 *  redirect target), the three verbatim ``401`` failure reasons surfaced by the
 *  controller-local handler, the two verbatim ``400`` field-validation messages
 *  surfaced by the shared handler, and the HTTP method/path guards.
 */
@WebMvcTest(AuthenticationController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthenticationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationService authenticationService;

    /**
     * :purpose: Mock standing in for the security-user repository bean. The
     *  auth-service main class declares an explicit ``@EnableJpaRepositories``,
     *  so the web slice eagerly registers JPA repository infrastructure it does
     *  not auto-configure; overriding the repository as a mock replaces its
     *  factory bean. The controller under test never uses it.
     */
    @MockitoBean
    private SecurityUserRepository securityUserRepository;

    /**
     * :purpose: Mock ``entityManagerFactory`` bean that satisfies the
     *  shared-EntityManager and metamodel mapping-context singletons registered
     *  eagerly by the explicit ``@EnableJpaRepositories``. ``RETURNS_MOCKS`` makes
     *  ``getMetamodel()`` non-null with an empty managed-type set, so the mapping
     *  context initializes without a real persistence unit. Never exercised by
     *  the controller under test.
     */
    @MockitoBean(name = "entityManagerFactory", answers = Answers.RETURNS_MOCKS)
    private EntityManagerFactory entityManagerFactory;

    /**
     * :purpose: Serialize a sign-on request body to JSON for the request content.
     * :param userId: user id to place in the request body (may be blank to
     *  exercise ``@NotBlank`` validation).
     * :param password: password to place in the request body (may be blank to
     *  exercise ``@NotBlank`` validation).
     * :returns: the JSON string for the ``POST /auth/signon`` request body.
     */
    private String toJson(String userId, String password) throws Exception {
        return objectMapper.writeValueAsString(new SignonRequestDto(userId, password));
    }

    /**
     * :purpose: Administrator sign-on returns 200 with the ``SEC-USR-TYPE`` code
     *  ``"A"`` (ROLE_ADMIN) and the ``CA00`` redirect, and the JSON body
     *  deserializes into the request DTO passed to the service.
     */
    @Test
    @DisplayName("POST /auth/signon: valid ADMIN credentials -> 200 with userType 'A' (ROLE_ADMIN) and CA00 redirect")
    void signonAdminReturnsOk() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willReturn(new SignonResponseDto("USER0001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "PASS0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.redirectTarget").value("CA00"));

        ArgumentCaptor<SignonRequestDto> captor = ArgumentCaptor.forClass(SignonRequestDto.class);
        verify(authenticationService).signon(captor.capture(), any());
        assertThat(captor.getValue().getUserId()).isEqualTo("USER0001");
        assertThat(captor.getValue().getPassword()).isEqualTo("PASS0001");
    }

    /**
     * :purpose: Standard-user sign-on returns 200 with the ``SEC-USR-TYPE`` code
     *  ``"U"`` (ROLE_USER) and the ``CM00`` redirect.
     */
    @Test
    @DisplayName("POST /auth/signon: valid USER credentials -> 200 with userType 'U' (ROLE_USER) and CM00 redirect")
    void signonUserReturnsOk() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willReturn(new SignonResponseDto("USER0002", SessionContext.UserType.CDEMO_USRTYP_USER, "CM00"));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0002", "PASS0002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("USER0002"))
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.redirectTarget").value("CM00"));
    }

    /**
     * :purpose: The success body carries no password (nor any other secret):
     *  only ``userId``, ``userType``, and ``redirectTarget`` are serialized.
     */
    @Test
    @DisplayName("POST /auth/signon: success body never echoes the password")
    void signonSuccessDoesNotEchoPassword() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willReturn(new SignonResponseDto("USER0001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "PASS0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").exists())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * :purpose: A service ``401`` with the verbatim reason
     *  ``"Wrong Password. Try again ..."`` is surfaced through the
     *  controller-local handler as a ``401`` error body.
     */
    @Test
    @DisplayName("POST /auth/signon: wrong password -> 401 'Wrong Password. Try again ...'")
    void signonWrongPasswordReturnsUnauthorized() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Wrong Password. Try again ..."));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "PASS0001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Wrong Password. Try again ..."))
                .andExpect(jsonPath("$.path").value("/auth/signon"));
    }

    /**
     * :purpose: A service ``401`` with the verbatim reason
     *  ``"User not found. Try again ..."`` is surfaced as a ``401`` error body.
     */
    @Test
    @DisplayName("POST /auth/signon: unknown user -> 401 'User not found. Try again ...'")
    void signonUserNotFoundReturnsUnauthorized() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found. Try again ..."));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("NOSUCH01", "PASS0001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("User not found. Try again ..."))
                .andExpect(jsonPath("$.path").value("/auth/signon"));
    }

    /**
     * :purpose: A service ``401`` with the verbatim reason
     *  ``"Unable to verify the User ..."`` is surfaced as a ``401`` error body.
     */
    @Test
    @DisplayName("POST /auth/signon: credential-store failure -> 401 'Unable to verify the User ...'")
    void signonUnableToVerifyReturnsUnauthorized() throws Exception {
        given(authenticationService.signon(any(), any()))
                .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unable to verify the User ..."));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "PASS0001")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Unable to verify the User ..."))
                .andExpect(jsonPath("$.path").value("/auth/signon"));
    }

    /**
     * :purpose: A blank ``userId`` fails ``@NotBlank`` and returns ``400`` with
     *  the verbatim ``userId`` field error via the shared handler, without
     *  invoking the service.
     */
    @Test
    @DisplayName("POST /auth/signon: blank userId -> 400 'Please enter User ID ...'")
    void signonBlankUserIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("", "PASS0001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.userId").value("Please enter User ID ..."));

        verify(authenticationService, never()).signon(any(), any());
    }

    /**
     * :purpose: A blank ``password`` fails ``@NotBlank`` and returns ``400`` with
     *  the verbatim ``password`` field error via the shared handler, without
     *  invoking the service.
     */
    @Test
    @DisplayName("POST /auth/signon: blank password -> 400 'Please enter Password ...'")
    void signonBlankPasswordReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.password").value("Please enter Password ..."));

        verify(authenticationService, never()).signon(any(), any());
    }

    /**
     * :purpose: Both fields blank fail ``@NotBlank`` and return ``400`` carrying
     *  both verbatim field errors.
     */
    @Test
    @DisplayName("POST /auth/signon: both fields blank -> 400 with both field errors")
    void signonBothFieldsBlankReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.userId").value("Please enter User ID ..."))
                .andExpect(jsonPath("$.fieldErrors.password").value("Please enter Password ..."));

        verify(authenticationService, never()).signon(any(), any());
    }

    /**
     * :purpose: The endpoint is POST-only; ``GET /auth/signon`` returns ``405``
     *  Method Not Allowed and never reaches the service.
     */
    @Test
    @DisplayName("GET /auth/signon -> 405 Method Not Allowed")
    void signonGetReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/auth/signon")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());

        verify(authenticationService, never()).signon(any(), any());
    }

    /**
     * :purpose: The frozen path is ``/auth/signon``; ``POST /auth/wrong`` returns
     *  ``404`` Not Found and never reaches the service.
     */
    @Test
    @DisplayName("POST /auth/wrong -> 404 Not Found")
    void signonWrongPathReturnsNotFound() throws Exception {
        mockMvc.perform(post("/auth/wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(toJson("USER0001", "PASS0001")))
                .andExpect(status().isNotFound());

        verify(authenticationService, never()).signon(any(), any());
    }
}
