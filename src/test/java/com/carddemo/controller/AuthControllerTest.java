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
package com.carddemo.controller;

import com.carddemo.controller.advice.GlobalExceptionHandler;
import com.carddemo.dto.auth.LoginRequest;
import com.carddemo.dto.auth.LoginResponse;
import com.carddemo.security.SecurityConfig;
import com.carddemo.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc web-slice tests for {@link AuthController} &mdash; the REST replacement for the
 * legacy CICS sign-on program {@code app/cbl/COSGN00C.cbl} (TRANID {@code CC00}).
 *
 * <p>This slice exercises the two authentication endpoints in isolation from the persistence
 * tier and the JWT/Spring-Security machinery:</p>
 * <ul>
 *   <li>{@code POST /api/auth/login} &mdash; the sign-on path that replaces the COBOL VSAM
 *       {@code USRSEC} keyed {@code READ} followed by the plaintext comparison
 *       {@code IF SEC-USR-PWD = WS-USER-PWD} ({@code app/cbl/COSGN00C.cbl:L221-L257});</li>
 *   <li>{@code POST /api/auth/logout} &mdash; the stateless sign-off that clears the
 *       security context and acknowledges with {@code 204 No Content} for an
 *       authenticated caller, or {@code 401 Unauthorized} for an anonymous caller
 *       (CP4: logout is not a public route).</li>
 * </ul>
 *
 * <h2>COBOL outcome &rarr; HTTP status parity ({@code READ-USER-SEC-FILE})</h2>
 * <table border="1">
 *   <caption>COSGN00C terminal outcome to asserted HTTP result</caption>
 *   <tr><th>COBOL behaviour</th><th>Asserted HTTP result</th></tr>
 *   <tr><td>{@code WHEN 0} + password match &rarr; XCTL {@code COADM01C} (admin)
 *       ({@code COSGN00C.cbl:L222-L234})</td><td>{@code 200 OK}, {@code userType == "A"}</td></tr>
 *   <tr><td>{@code WHEN 0} + password match &rarr; XCTL {@code COMEN01C} (user)
 *       ({@code COSGN00C.cbl:L235-L240})</td><td>{@code 200 OK}, {@code userType == "U"}</td></tr>
 *   <tr><td>{@code WHEN 0} + mismatch &rarr; "Wrong Password. Try again ..."
 *       ({@code COSGN00C.cbl:L241-L246})</td><td>{@code 401 Unauthorized}</td></tr>
 *   <tr><td>{@code WHEN 13} &rarr; "User not found. Try again ..."
 *       ({@code COSGN00C.cbl:L247-L251})</td><td>{@code 401 Unauthorized}</td></tr>
 *   <tr><td>blank {@code USERIDI} / {@code PASSWDI} ({@code COSGN00C.cbl:L118-L127})</td>
 *       <td>{@code 400 Bad Request} (Bean Validation)</td></tr>
 * </table>
 *
 * <h2>Slice configuration (matches the project's controller-test convention)</h2>
 * <ul>
 *   <li>{@link WebMvcTest @WebMvcTest(controllers = AuthController.class)} loads only the
 *       {@code AuthController} web layer.</li>
 *   <li>{@code excludeFilters} drops the entire {@code com.carddemo.security} package from
 *       the slice's component scan. A {@code @WebMvcTest} slice always registers application
 *       {@code jakarta.servlet.Filter} beans, and the production
 *       {@code com.carddemo.security.JwtAuthenticationFilter} is a {@code @Component}
 *       extending {@code OncePerRequestFilter}; left untouched it would be instantiated here
 *       and fail the context with an {@code UnsatisfiedDependencyException} because its
 *       {@code CustomAuthorityMapper} collaborator is not loaded by the slice.
 *       {@code @AutoConfigureMockMvc(addFilters = false)} only removes filters from the
 *       MockMvc chain &mdash; it does NOT prevent the bean from being created &mdash; so the
 *       exclusion is required.</li>
 *   <li>{@link Import @Import(GlobalExceptionHandler.class)} wires the
 *       {@code @RestControllerAdvice} so that status-code assertions reflect production
 *       error handling: {@code AuthenticationException} &rarr; {@code 401},
 *       {@code MethodArgumentNotValidException} &rarr; {@code 400}, and
 *       {@code HttpMessageNotReadableException} &rarr; {@code 400}.</li>
 *   <li>{@link AutoConfigureMockMvc @AutoConfigureMockMvc(addFilters = false)} disables the
 *       security filter chain so the controller's HTTP behaviour is asserted in isolation.
 *       In production ({@link SecurityConfig}) only {@code POST /api/auth/login} is
 *       {@code permitAll()} &mdash; it <em>is</em> the authentication step; {@code logout}
 *       is NOT public (CP4) and requires an authenticated principal. The controller reads
 *       {@code SecurityContextHolder} directly, so logout's authenticated path is exercised
 *       with {@code @WithMockUser} (which populates the context via the test execution
 *       listener, independent of the disabled filter chain) and the anonymous path with no
 *       annotation (empty context &rarr; {@code 401}).</li>
 * </ul>
 *
 * <p>Because {@code /api/auth/login} delegates the whole credential check to
 * {@link AuthService} (which drives the Spring Security {@code AuthenticationManager} and
 * {@code BCryptPasswordEncoder.matches(...)}), the credential-failure outcomes are simulated
 * by stubbing the {@code @MockBean AuthService} to throw {@link BadCredentialsException}
 * (wrong password) or {@link UsernameNotFoundException} (unknown user) &mdash; both extend
 * {@code org.springframework.security.core.AuthenticationException} and are therefore mapped
 * to {@code 401} by {@link GlobalExceptionHandler}. No real BCrypt hash, JWT secret, or
 * credential is ever materialised in this test (the returned token is an opaque placeholder).</p>
 *
 * <h2>Refactoring rules exercised</h2>
 * <ul>
 *   <li><strong>PR-17</strong> &mdash; the controller never compares passwords; it forwards
 *       the RAW, case-preserved password to {@link AuthService} for BCrypt matching (asserted
 *       by {@link Login#controllerForwardsRawPasswordToServiceForBcrypt()}).</li>
 *   <li><strong>PR-19</strong> &mdash; {@code userType} is the single character {@code 'A'}
 *       (ADMIN) or {@code 'U'} (USER), echoed in the {@link LoginResponse}.</li>
 *   <li><strong>PR-28</strong> &mdash; Jakarta EE 10 baseline (no {@code javax.*}).</li>
 *   <li><strong>PR-29</strong> &mdash; {@code AuthController} uses constructor injection of a
 *       single {@code final AuthService}; the slice supplies it via {@code @MockBean}.</li>
 * </ul>
 *
 * @see AuthController
 * @see AuthService
 * @see LoginRequest
 * @see LoginResponse
 * @see GlobalExceptionHandler
 * @see SecurityConfig
 */
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.carddemo\\.security\\..*"))
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AuthController web-slice tests (replaces COBOL COSGN00C sign-on)")
class AuthControllerTest {

    /**
     * Opaque, obviously-fake JWT placeholder returned by the mocked {@link AuthService}.
     * This is NOT a real token and carries no secret &mdash; JWT signing/verification is the
     * concern of {@code JwtAuthenticationFilter} and is covered by integration tests, not by
     * this controller slice. Used to assert the token is echoed verbatim in the JSON body.
     */
    private static final String TEST_TOKEN = "test-jwt-token";

    /** Auto-configured MockMvc for the {@code AuthController} web slice (security filters disabled). */
    @Autowired
    private MockMvc mockMvc;

    /** Auto-configured Jackson mapper used to serialize {@link LoginRequest} bodies to JSON. */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The sole controller collaborator, replaced by a Mockito mock in the slice context. The
     * real {@code AuthService} owns the BCrypt credential check and JWT issuance; here it is
     * stubbed so the controller's HTTP behaviour can be asserted in isolation (PR-17).
     */
    @MockBean
    private AuthService authService;

    /**
     * Builds an opaque, fully-populated {@link LoginResponse} for stubbing the mocked service
     * on the success paths. The {@code firstName}/{@code lastName} are arbitrary display values
     * and the {@code expiresAt} is one hour out; none of these are asserted except {@code token},
     * {@code userId} and {@code userType}, which carry the COBOL-parity semantics.
     *
     * @param userId   the echoed (uppercase) user id
     * @param userType the single-character role flag ({@code "A"} admin / {@code "U"} user, PR-19)
     * @return a populated response record mirroring what the real service would return
     */
    private LoginResponse loginResponse(String userId, String userType) {
        return LoginResponse.builder()
                .token(TEST_TOKEN)
                .userId(userId)
                .userType(userType)
                .firstName("Test")
                .lastName("User")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }

    /**
     * Tests for {@code POST /api/auth/login} &mdash; the sign-on endpoint replacing the
     * {@code COSGN00C} {@code READ-USER-SEC-FILE} {@code EVALUATE WS-RESP-CD} dispatch
     * ({@code app/cbl/COSGN00C.cbl:L209-L257}).
     */
    @Nested
    @DisplayName("POST /api/auth/login")
    class Login {

        @Test
        @DisplayName("Valid ADMIN credentials -> 200 OK with JWT and userType 'A' (COSGN00C WHEN 0, admin XCTL COADM01C)")
        void adminValidCredentialsReturns200WithTypeA() throws Exception {
            LoginRequest request = new LoginRequest("ADMIN001", "PASSWORD");
            when(authService.authenticate(any(LoginRequest.class)))
                    .thenReturn(loginResponse("ADMIN001", "A"));

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value(TEST_TOKEN))
                    .andExpect(jsonPath("$.userId").value("ADMIN001"))
                    // PR-19: ROLE_ADMIN is encoded as the single-character userType 'A'.
                    .andExpect(jsonPath("$.userType").value("A"));

            // The controller delegates the credential check to the service (PR-17).
            verify(authService).authenticate(any(LoginRequest.class));
        }

        @Test
        @DisplayName("Valid USER credentials -> 200 OK with JWT and userType 'U' (COSGN00C WHEN 0, user XCTL COMEN01C)")
        void userValidCredentialsReturns200WithTypeU() throws Exception {
            LoginRequest request = new LoginRequest("USER0001", "PASSWORD");
            when(authService.authenticate(any(LoginRequest.class)))
                    .thenReturn(loginResponse("USER0001", "U"));

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value("USER0001"))
                    // PR-19: ROLE_USER is encoded as the single-character userType 'U'.
                    .andExpect(jsonPath("$.userType").value("U"));

            verify(authService).authenticate(any(LoginRequest.class));
        }

        @Test
        @DisplayName("Wrong password -> 401 Unauthorized (COSGN00C 'Wrong Password. Try again ...'; BCrypt mismatch)")
        void wrongPasswordReturns401() throws Exception {
            LoginRequest request = new LoginRequest("ADMIN001", "WRONGPASS");
            // The service surfaces a BCrypt mismatch as a BadCredentialsException (an
            // AuthenticationException), which GlobalExceptionHandler maps to 401.
            when(authService.authenticate(any(LoginRequest.class)))
                    .thenThrow(new BadCredentialsException("Wrong Password. Try again ..."));

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());

            verify(authService).authenticate(any(LoginRequest.class));
        }

        @Test
        @DisplayName("Unknown user -> 401 Unauthorized (COSGN00C WHEN 13 'User not found. Try again ...')")
        void unknownUserReturns401() throws Exception {
            LoginRequest request = new LoginRequest("NOSUCHID", "PASSWORD");
            // UsernameNotFoundException also extends AuthenticationException -> 401. The REST
            // surface intentionally returns the SAME 401 for wrong-password and unknown-user
            // (user-enumeration defence) even though COSGN00C distinguished the two messages.
            when(authService.authenticate(any(LoginRequest.class)))
                    .thenThrow(new UsernameNotFoundException("User not found. Try again ..."));

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());

            verify(authService).authenticate(any(LoginRequest.class));
        }

        @Test
        @DisplayName("Blank userId -> 400 Bad Request (Bean Validation; COSGN00C 'Please enter User ID ...'); service never invoked")
        void blankUserIdReturns400() throws Exception {
            // @NotBlank/@Size/@Pattern on LoginRequest.userId fail before the handler body runs,
            // so @Valid short-circuits with 400 and AuthService is never reached.
            LoginRequest request = new LoginRequest("", "PASSWORD");

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("Blank password -> 400 Bad Request (Bean Validation; COSGN00C 'Please enter Password ...'); service never invoked")
        void blankPasswordReturns400() throws Exception {
            // @NotBlank on LoginRequest.password fails validation -> 400 before delegation.
            LoginRequest request = new LoginRequest("ADMIN001", "");

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("Malformed JSON body -> 400 Bad Request (HttpMessageNotReadableException); service never invoked")
        void malformedJsonReturns400() throws Exception {
            // The body cannot be deserialized into a LoginRequest, so the request fails at the
            // message converter (before validation and before the handler) -> 400.
            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not valid json"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("PR-17: controller forwards the RAW, case-preserved password to AuthService (no plaintext compare in controller)")
        void controllerForwardsRawPasswordToServiceForBcrypt() throws Exception {
            // A mixed-case password proves the controller preserves case end-to-end. Unlike
            // COBOL COSGN00C.cbl:L135-L136 (which uppercased PASSWDI), the migration keeps the
            // password verbatim so BCrypt (case-sensitive) can match it inside AuthService.
            LoginRequest request = new LoginRequest("ADMIN001", "PaSsWoRd");
            when(authService.authenticate(any(LoginRequest.class)))
                    .thenReturn(loginResponse("ADMIN001", "A"));

            mockMvc.perform(post("/api/auth/login").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            // The controller passes the EXACT request (userId + case-preserved password) to the
            // service and performs NO password comparison itself (PR-17). LoginRequest's
            // @Data-generated equals() compares userId + password.
            verify(authService).authenticate(eq(new LoginRequest("ADMIN001", "PaSsWoRd")));
        }
    }

    /**
     * Tests for {@code POST /api/auth/logout} &mdash; the stateless sign-off. In a JWT design
     * the server cannot truly invalidate a token (no blacklist, per AAP &sect;0.7.2); the
     * endpoint clears the request-scoped security context and returns {@code 204 No Content}
     * to hint the client to discard its token.
     *
     * <p><strong>CP4 security contract.</strong> Logout requires an authenticated principal:
     * the production {@link SecurityConfig} no longer lists it under {@code permitAll}, and the
     * controller guards against an absent/anonymous {@code Authentication} by returning
     * {@code 401 Unauthorized}. Both branches are asserted below &mdash; an authenticated caller
     * ({@code @WithMockUser}) receives {@code 204}, while an anonymous caller (no security context)
     * receives {@code 401}. In every case the {@code AuthService} is untouched (sign-off performs
     * no credential check).</p>
     */
    @Nested
    @DisplayName("POST /api/auth/logout")
    class Logout {

        @Test
        @WithMockUser(username = "ADMIN001")
        @DisplayName("Authenticated logout -> 204 No Content with an empty body (stateless JWT; client discards token)")
        void authenticatedLogoutReturns204WithEmptyBody() throws Exception {
            // @WithMockUser populates the SecurityContextHolder via the test execution listener,
            // independent of the disabled MockMvc filter chain. The controller reads the context
            // directly and, finding an authenticated non-anonymous principal, returns 204.
            MvcResult result = mockMvc.perform(post("/api/auth/logout").with(csrf()))
                    .andExpect(status().isNoContent())
                    .andReturn();

            // 204 responses must not carry a body.
            Assertions.assertTrue(
                    result.getResponse().getContentAsString().isEmpty(),
                    "Stateless logout must return an empty 204 body");

            // Sign-off performs no credential check, so the authentication service is untouched.
            verifyNoInteractions(authService);
        }

        @Test
        @DisplayName("Anonymous logout -> 401 Unauthorized (CP4: logout is not a public route)")
        void anonymousLogoutReturns401() throws Exception {
            // No @WithMockUser: the SecurityContextHolder carries no Authentication, so the
            // controller's defence-in-depth guard rejects the call with 401 — matching the
            // documented OpenAPI 401 contract and the SecurityConfig rule that removed logout
            // from the permitAll set (CP4).
            mockMvc.perform(post("/api/auth/logout").with(csrf()))
                    .andExpect(status().isUnauthorized());

            // No credential check is attempted on the rejected path either.
            verifyNoInteractions(authService);
        }
    }
}
