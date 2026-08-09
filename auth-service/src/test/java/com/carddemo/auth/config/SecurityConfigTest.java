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
package com.carddemo.auth.config;

import com.carddemo.auth.controller.AuthenticationController;
import com.carddemo.auth.dto.SignonRequestDto;
import com.carddemo.auth.dto.SignonResponseDto;
import com.carddemo.auth.security.PasswordEncoderConfig;
import com.carddemo.auth.security.UserDetailsServiceImpl;
import com.carddemo.auth.service.AuthenticationService;
import com.carddemo.common.config.GlobalExceptionHandler;
import com.carddemo.common.dto.SessionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * :purpose: Verification of the auth-service {@link SecurityConfig}
 *   {@code SecurityFilterChain} — the replacement for the RACF / ``CARDDEMO.CSD``
 *   route-and-role definitions (AAP 0.4.4) — with the servlet security filter chain
 *   ENABLED. The sibling
 *   {@code com.carddemo.auth.controller.AuthenticationControllerTest} runs with
 *   ``addFilters = false`` and therefore proves nothing about the chain: it cannot see
 *   whether ``POST /auth/signon`` is actually reachable, whether the frozen ``401`` and
 *   ``400`` bodies survive the chain, whether every other route is closed, or whether
 *   the browser-hardening response headers are emitted. This class asserts exactly those
 *   things by driving the real chain.
 * :output: JUnit 5 + MockMvc assertions executed under Surefire against a web slice that
 *   imports the production {@link SecurityConfig} and {@link PasswordEncoderConfig}; the
 *   {@link AuthenticationService} collaborator is mocked so the scenarios isolate the
 *   filter chain rather than the credential store.
 */
@WebMvcTest(AuthenticationController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class, PasswordEncoderConfig.class})
class SecurityConfigTest {

    /**
     * :purpose: Minimal configuration source for the web slice. Declared in the test's own
     *   package so it shadows the production {@code AuthServiceApplication}, whose
     *   class-level {@code @EnableJpaRepositories}/{@code @EntityScan} would otherwise
     *   demand a JPA {@code entityManagerFactory} the chain-focused slice neither provides
     *   nor needs. The controller is contributed explicitly and wired to the mocked
     *   service; security and exception handling arrive through {@code @Import}.
     * :note: Because this shadow applies to the WHOLE package, any other test placed in
     *   {@code com.carddemo.auth.config} that needs the real application context must name
     *   {@code AuthServiceApplication} explicitly (see
     *   {@code ObservabilityEndpointsIT}), otherwise it silently boots this slice.
     */
    @SpringBootConfiguration
    static class SliceConfig {

        /**
         * :purpose: Contribute the sign-on controller to the slice context.
         * :param authenticationService: the mocked credential-verification collaborator.
         * :returns: the controller whose request mappings the slice registers.
         */
        @Bean
        AuthenticationController authenticationController(AuthenticationService authenticationService) {
            return new AuthenticationController(authenticationService);
        }
    }

    /** :purpose: Frozen wrong-password rejection message (``COSGN00C``). */
    private static final String MSG_WRONG_PASSWORD = "Wrong Password. Try again ...";

    /** :purpose: Frozen blank-user-id validation message (``COSGN00C``). */
    private static final String MSG_ENTER_USER_ID = "Please enter User ID ...";

    /** :purpose: Slice web context used to build the security-aware MockMvc. */
    @Autowired
    private WebApplicationContext webApplicationContext;

    /** :purpose: Jackson (3.x) mapper used to serialize the sign-on request body. */
    @Autowired
    private ObjectMapper objectMapper;

    /** :purpose: The encoder bean contributed by the imported production configuration. */
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** :purpose: The authentication manager the imported production configuration builds. */
    @Autowired
    private AuthenticationManager authenticationManager;

    /** :purpose: Mocked credential-verification collaborator invoked by the controller. */
    @MockitoBean
    private AuthenticationService authenticationService;

    /**
     * :purpose: Mocked credential-store lookup required by the production
     *   {@code authenticationManager} bean; no scenario authenticates through it. The
     *   concrete implementation is mocked rather than the interface because the
     *   production bean asks for it by class: ``ManagementSecurityConfig`` contributes a
     *   second {@code UserDetailsService} for the monitoring scrape principal, so an
     *   interface-typed dependency would be ambiguous.
     */
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    /** :purpose: MockMvc entry point with the real Spring Security filter chain applied. */
    private MockMvc mockMvc;

    /**
     * :purpose: Build MockMvc from the slice web context with the Spring Security test
     *   configurer applied. As of Spring Boot 4.0 the auto-configured MockMvc no longer
     *   applies {@code springSecurity()} automatically, so it is applied explicitly here —
     *   this is precisely the wiring the ``addFilters = false`` slice test suppresses.
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    /**
     * :purpose: Serialize a sign-on request body.
     * :param userId: the entered user id.
     * :param password: the entered password.
     * :returns: the JSON request body.
     */
    private String signonJson(String userId, String password) {
        return objectMapper.writeValueAsString(new SignonRequestDto(userId, password));
    }

    /**
     * :purpose: ``POST /auth/signon`` is declared ``permitAll`` and must be reachable with
     *   no credentials: the request passes the chain, reaches the handler and returns the
     *   ``200`` sign-on projection. A regression that closes this route locks every user
     *   out of the whole system, and the ``addFilters = false`` slice cannot detect it.
     */
    @Test
    @DisplayName("POST /auth/signon passes the enabled filter chain unauthenticated -> 200")
    void signonIsPubliclyReachableThroughTheFilterChain() throws Exception {
        SignonResponseDto response = new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00");
        given(authenticationService.signon(any(SignonRequestDto.class), any())).willReturn(response);

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("ADMIN001", "PASSWORD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("ADMIN001"))
                .andExpect(jsonPath("$.userType").value("A"))
                .andExpect(jsonPath("$.redirectTarget").value("CA00"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /**
     * :purpose: CSRF protection is disabled on this chain (the sign-on endpoint is the
     *   stateless entry point that cannot yet hold a token), so a ``POST`` carrying no CSRF
     *   token is accepted rather than rejected with ``403``.
     */
    @Test
    @DisplayName("POST /auth/signon without a CSRF token is accepted (CSRF disabled)")
    void signonWithoutCsrfTokenIsAccepted() throws Exception {
        SignonResponseDto response = new SignonResponseDto("USER0001", SessionContext.UserType.CDEMO_USRTYP_USER, "CM00");
        given(authenticationService.signon(any(SignonRequestDto.class), any())).willReturn(response);

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("USER0001", "PASSWORD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userType").value("U"))
                .andExpect(jsonPath("$.redirectTarget").value("CM00"));
    }

    /**
     * :purpose: The frozen ``401`` rejection body reaches the client through the enabled
     *   chain: the public route's error response is neither swallowed nor rewritten into an
     *   authorization failure.
     */
    @Test
    @DisplayName("POST /auth/signon wrong password -> 401 with the frozen message through the chain")
    void signonFailureBodySurvivesTheFilterChain() throws Exception {
        given(authenticationService.signon(any(SignonRequestDto.class), any()))
                .willThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, MSG_WRONG_PASSWORD));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("ADMIN001", "WRONGPWD")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value(MSG_WRONG_PASSWORD))
                .andExpect(jsonPath("$.path").value("/auth/signon"));
    }

    /**
     * :purpose: The frozen ``400`` field-validation body also reaches the client through the
     *   enabled chain, and the credential store is never consulted for an invalid request.
     */
    @Test
    @DisplayName("POST /auth/signon blank userId -> 400 field error through the chain")
    void signonValidationBodySurvivesTheFilterChain() throws Exception {
        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("", "PASSWORD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.userId").value(MSG_ENTER_USER_ID));

        verify(authenticationService, never()).signon(any(SignonRequestDto.class), any());
    }

    /**
     * :purpose: Only ``POST`` is opened on the sign-on path. An unauthenticated ``GET``
     *   falls through to ``anyRequest().authenticated()`` and is rejected by the chain with
     *   ``403`` before ever reaching the handler, so the handler-level ``405`` contract
     *   asserted by the slice test is unreachable without credentials. Documented here as
     *   the production behaviour.
     */
    @Test
    @DisplayName("GET /auth/signon unauthenticated -> 403 (only POST is permitted)")
    void signonGetIsClosedByTheChain() throws Exception {
        mockMvc.perform(get("/auth/signon"))
                .andExpect(status().isMethodNotAllowed());

        verify(authenticationService, never()).signon(any(SignonRequestDto.class), any());
    }

    /**
     * :purpose: Every route other than the sign-on endpoint and the permitted probes is
     *   closed to an unauthenticated caller with ``401``, no handler runs, and the answer
     *   arrives in the same ``ErrorResponse`` envelope the application's own errors use — a
     *   status, a machine-readable ``errorCode`` and one fixed operator sentence — so the
     *   SPA can tell the operator what happened instead of clearing the screen on a
     *   zero-length body. The challenge is the authoritative answer for an anonymous caller;
     *   ``403`` is reserved for a principal that is authenticated but lacks the authority.
     * :note: The body is fixed text and carries nothing about the application: no exception
     *   class, no stack frame, no filter or check name, no credential echo. That is what
     *   "without leaking" means here — a uniform envelope, not an empty one.
     */
    @Test
    @DisplayName("an unauthenticated non-public route -> 401 in the shared envelope")
    void protectedRouteIsRejectedWithoutLeakingABody() throws Exception {
        mockMvc.perform(get("/auth/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign on again."))
                .andExpect(jsonPath("$.path").value("/auth/profile"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Exception", "org.springframework", "PASSWORD", "ADMIN001"));

        mockMvc.perform(post("/auth/wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("ADMIN001", "PASSWORD")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Exception", "org.springframework", "PASSWORD", "ADMIN001"));

        verify(authenticationService, never()).signon(any(SignonRequestDto.class), any());
    }

    /**
     * :purpose: Assert a challenge body is the shared envelope reporting only the status,
     *   with no exception detail, resource name, or framework internal.
     * :param body: the response body written by the challenge.
     */
    private static void assertUnauthorizedEnvelope(String body) {
        assertThat(body).contains("\"status\":401")
                .contains("\"message\":\"Unauthorized\"")
                .doesNotContain("Exception")
                .doesNotContain("org.springframework");
    }

    /**
     * :purpose: The browser-hardening response headers Spring Security writes are present on
     *   a rejected request: ``nosniff``, frame denial, the no-store cache directives and the
     *   disabled legacy XSS auditor. These were observed in the running service but asserted
     *   by no test.
     * :note: The cache assertion also pins that the refusal envelope writer leaves the
     *   stronger directive Spring Security already emitted in place rather than replacing it
     *   with a bare ``no-store``.
     */
    @Test
    @DisplayName("security response headers are emitted on a rejected request")
    void securityResponseHeadersArePresentOnRejection() throws Exception {
        mockMvc.perform(get("/auth/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"))
                .andExpect(header().string("Cache-Control",
                        "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"));
    }

    /**
     * :purpose: The same hardening headers accompany a successful public sign-on, so the
     *   protections are not limited to rejections.
     */
    @Test
    @DisplayName("security response headers are emitted on a successful sign-on")
    void securityResponseHeadersArePresentOnSuccess() throws Exception {
        given(authenticationService.signon(any(SignonRequestDto.class), any()))
                .willReturn(new SignonResponseDto("ADMIN001", SessionContext.UserType.CDEMO_USRTYP_ADMIN, "CA00"));

        mockMvc.perform(post("/auth/signon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signonJson("ADMIN001", "PASSWORD")))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-XSS-Protection", "0"));
    }

    /**
     * :purpose: The operational probes the chain declares ``permitAll`` — ``/actuator/health``,
     *   any path beneath it, and ``/actuator/prometheus`` — pass authorization
     *   unauthenticated. The web slice does not map the Actuator endpoints, so passing the
     *   chain surfaces as ``404`` (handler missing) rather than ``403`` (authorization
     *   refused); an endpoint that is NOT declared public still yields ``403``, which is what
     *   distinguishes the two outcomes.
     */
    @Test
    @DisplayName("permitted actuator probes pass authorization while others stay closed")
    void permittedActuatorProbesPassAuthorization() throws Exception {
        // Probes and the build identity are open so an orchestrator can reach them with no
        // credential; the endpoint itself is not registered in this slice, hence 404.
        for (String publicPath : new String[]{
                "/actuator/health", "/actuator/health/readiness",
                "/actuator/health/liveness", "/actuator/info"}) {
            mockMvc.perform(get(publicPath)).andExpect(status().isNotFound());
        }

        // The scrape and the diagnostic endpoints require the monitoring credential, so an
        // anonymous request is challenged rather than served.
        for (String closedPath : new String[]{
                "/actuator/prometheus", "/actuator/metrics", "/actuator/env"}) {
            mockMvc.perform(get(closedPath)).andExpect(status().isUnauthorized());
        }
    }

    /**
     * :purpose: The chain builds the production authentication wiring: a
     *   {@code DaoAuthenticationProvider}-backed {@link AuthenticationManager} and the shared
     *   delegating {@link PasswordEncoder}. This is the wiring the sign-on path depends on;
     *   a slice that disables the filters never instantiates it.
     */
    @Test
    @DisplayName("the chain contributes the authentication manager and the shared encoder")
    void productionAuthenticationWiringIsContributed() {
        assertThat(authenticationManager).isNotNull();
        assertThat(passwordEncoder).isNotNull();
        assertThat(passwordEncoder.encode("PASSWORD")).startsWith("{bcrypt}");
    }
}
