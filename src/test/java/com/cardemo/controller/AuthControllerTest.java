/*
 * ============================================================================
 * AuthControllerTest.java — MockMvc Controller Test for AuthController
 * ============================================================================
 * AWS CardDemo Mainframe Application
 * Migrated from COBOL/CICS/VSAM to Java 25 + Spring Boot 3.5.x
 *
 * Source COBOL artifacts covered by this test:
 *   - COSGN00C.cbl  — Sign-on authentication program (CICS transaction CC00)
 *   - COSGN00.bms   — Sign-on BMS map (USERID and PASSWD fields)
 *   - COSGN00.cpy   — BMS data structure copybook (AI/AO two-view pattern)
 *   - COCOM01Y.cpy  — COMMAREA structure with 88-level ADMIN/USER conditions
 *   - CSUSR01Y.cpy  — User security record layout (SEC-USR-ID, SEC-USR-PWD,
 *                      SEC-USR-TYPE)
 *   - CSMSG01Y.cpy  — System message constants (CCDA-MSG-THANK-YOU, etc.)
 *
 * Test Coverage Mapping (COSGN00C.cbl → AuthController):
 *   1. loginSuccess           ← PROCESS-ENTER-KEY → READ-USER-SEC-FILE
 *                               (RESP=0, password match → XCTL to menu)
 *   2. loginWrongPassword     ← READ-USER-SEC-FILE RESP=0 + mismatch
 *                               → "Wrong Password. Try again ..."
 *   3. loginUserNotFound      ← READ-USER-SEC-FILE RESP=13 (NOTFND)
 *                               → "User not found. Try again ..."
 *   4. loginBlankUserId       ← PROCESS-ENTER-KEY: USERIDI = SPACES
 *                               → "User ID Cannot Be Empty"
 *   5. loginBlankPassword     ← PROCESS-ENTER-KEY: PASSWDI = SPACES
 *                               → "Password Cannot Be Empty"
 *   6. neverContainsPassword  ← Security invariant: password NEVER in response
 *   7. logout                 ← MAIN-PARA PF3 → EXEC CICS RETURN
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0.
 * ============================================================================
 */
package com.cardemo.controller;

// Internal imports — controller under test and its dependencies
import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.config.SecurityConfig;
import com.cardemo.service.online.SignonService;

// External imports — JUnit 5 Jupiter (provided by spring-boot-starter-test)
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// External imports — Spring Boot Test and Spring Framework Test
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// External imports — Jackson JSON (provided by spring-boot-starter-web)
import com.fasterxml.jackson.databind.ObjectMapper;

// Static imports — Mockito stubbing and verification
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Static imports — Spring MockMvc request builders and result matchers
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Java standard library
import java.util.Map;

/**
 * Spring {@code @WebMvcTest} slice test for {@link AuthController} — validates
 * the two REST endpoints ({@code POST /api/auth/login} and
 * {@code POST /api/auth/logout}) that translate CICS transaction CC00
 * (COSGN00C.cbl) sign-on functionality into stateless REST.
 *
 * <p>This test uses {@code @WebMvcTest(AuthController.class)} to limit the
 * Spring application context to only the web layer (AuthController), plus the
 * imported {@link SecurityConfig} for the security filter chain. All service
 * dependencies are mocked via {@code @MockitoBean}.</p>
 *
 * <h2>COBOL-to-Java Field Mapping for Auth (CSUSR01Y.cpy):</h2>
 * <table>
 *   <tr><th>COBOL Field</th><th>Java Field</th><th>Constraint</th></tr>
 *   <tr><td>SEC-USR-ID PIC X(08)</td><td>userId (String)</td><td>Max 8 chars</td></tr>
 *   <tr><td>SEC-USR-PWD PIC X(08)</td><td>password (String)</td><td>BCrypt hashed</td></tr>
 *   <tr><td>SEC-USR-TYPE PIC X(01)</td><td>userType (Enum)</td><td>'A' or 'U'</td></tr>
 * </table>
 *
 * <h2>HTTP Status Code Mapping:</h2>
 * <ul>
 *   <li>200 OK — Successful authentication or logout</li>
 *   <li>400 Bad Request — Validation failure (blank userId/password)</li>
 *   <li>401 Unauthorized — Authentication failure (wrong password, user not found)</li>
 * </ul>
 *
 * @see AuthController
 * @see SignonService
 * @see SecurityConfig
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
class AuthControllerTest {

    /**
     * Auto-configured MockMvc instance for performing HTTP requests against
     * the AuthController without starting a real HTTP server. Provided by
     * {@code @WebMvcTest} and {@code @AutoConfigureMockMvc}.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Jackson ObjectMapper for serializing login request bodies (userId and
     * password) into JSON format. Auto-configured by Spring Boot's Jackson
     * support via {@code spring-boot-starter-web}.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Mocked SignonService — replaces the real authentication service that
     * translates COSGN00C.cbl business logic. Test methods stub
     * {@code processEnterKey(String, String)} to simulate success (default void
     * behavior), authentication failures ({@link AuthenticationException}), and
     * validation failures ({@link ValidationException}).
     *
     * <p>Uses {@code @MockitoBean} (Spring Framework 6.2+) instead of the
     * deprecated {@code @MockBean} to avoid compilation errors under
     * {@code -Xlint:all -Werror}.</p>
     */
    @MockitoBean
    private SignonService signonService;

    /**
     * Mocked CardDemoContext — replaces the request-scoped COMMAREA session
     * context bean (← COCOM01Y.cpy). Required because {@link AuthController}
     * reads {@code getUserId()} and {@code getUserType()} after successful
     * authentication, and {@code getUserId()} during logout for audit logging.
     *
     * <p>{@code @WebMvcTest} does not scan {@code @Component} beans like
     * CardDemoContext, so this mock provides the required bean for controller
     * constructor injection.</p>
     */
    @MockitoBean
    private CardDemoContext cardDemoContext;

    /**
     * Test 1: Successful login returns HTTP 200 with user info.
     *
     * <p>Maps to COSGN00C.cbl flow:</p>
     * <ol>
     *   <li>MAIN-PARA (line 73) → WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY</li>
     *   <li>PROCESS-ENTER-KEY (line 108) validates fields, uppercases, calls
     *       READ-USER-SEC-FILE</li>
     *   <li>READ-USER-SEC-FILE (line 209): RESP=0, SEC-USR-PWD matches →
     *       populate COMMAREA, XCTL to COADM01C (admin) or COMEN01C (user)</li>
     * </ol>
     *
     * <p>After {@code processEnterKey()} succeeds (void, no exception),
     * the controller reads the populated CardDemoContext for the response.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — successful login returns 200 with user info (← COSGN00C READ-USER-SEC-FILE RESP=0, password match)")
    void loginSuccess_returnsOkWithUserInfo() throws Exception {
        // Arrange: processEnterKey is void — default mock behavior is "do nothing"
        // (simulates successful authentication with no exception thrown).
        // Configure CardDemoContext to return user info as if COMMAREA was populated
        // by COSGN00C.cbl lines 224-228: CDEMO-USER-ID, CDEMO-USER-TYPE
        when(cardDemoContext.getUserId()).thenReturn("USER0001");
        when(cardDemoContext.getUserType()).thenReturn(UserType.USER);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "USER0001", "password", "password"));

        // Act & Assert: POST login request and verify successful response
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.userId").value("USER0001"))
                .andExpect(jsonPath("$.userType").value("USER"))
                .andExpect(jsonPath("$.message").value("Authentication successful"));

        // Verify: SignonService.processEnterKey() was called exactly once
        // with the correct arguments (userId and password from request body)
        verify(signonService).processEnterKey(eq("USER0001"), eq("password"));
    }

    /**
     * Test 2: Wrong password returns HTTP 401 Unauthorized.
     *
     * <p>Maps to COSGN00C.cbl READ-USER-SEC-FILE:</p>
     * <pre>
     *   EVALUATE WS-RESP-CD
     *     WHEN 0
     *       IF SEC-USR-PWD NOT = WS-USER-PWD
     *         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     * </pre>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — wrong password returns 401 (← COSGN00C SEC-USR-PWD mismatch)")
    void loginWrongPassword_returns401() throws Exception {
        // Arrange: Mock service to throw AuthenticationException for password mismatch
        // Maps to COSGN00C.cbl line 223: IF SEC-USR-PWD NOT = WS-USER-PWD
        // Password "wrongpwd" (8 chars) satisfies BMS PASSWD PIC X(08) @Size(max=8)
        // bean validation so the request reaches service-layer authentication
        willThrow(new AuthenticationException("Wrong Password. Try again ..."))
                .given(signonService).processEnterKey(eq("USER0001"), eq("wrongpwd"));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "USER0001", "password", "wrongpwd"));

        // Act & Assert: Expect 401 Unauthorized with error message
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Wrong Password. Try again ..."));
    }

    /**
     * Test 3: User not found returns HTTP 401 Unauthorized.
     *
     * <p>Maps to COSGN00C.cbl READ-USER-SEC-FILE:</p>
     * <pre>
     *   EVALUATE WS-RESP-CD
     *     WHEN 13
     *       MOVE 'User not found. Try again ...' TO WS-MESSAGE
     * </pre>
     * <p>CICS RESP code 13 = NOTFND condition for VSAM keyed READ.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — user not found returns 401 (← COSGN00C RESP=13 NOTFND)")
    void loginUserNotFound_returns401() throws Exception {
        // Arrange: Mock service to throw AuthenticationException for user not found
        // Maps to COSGN00C.cbl RESP code 13 (VSAM NOTFND → user does not exist)
        willThrow(new AuthenticationException("User not found. Try again ..."))
                .given(signonService).processEnterKey(eq("NOEXIST1"), eq("password"));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "NOEXIST1", "password", "password"));

        // Act & Assert: Expect 401 Unauthorized with error message
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("User not found. Try again ..."));
    }

    /**
     * Test 4: Blank userId returns HTTP 400 Bad Request.
     *
     * <p>Maps to COSGN00C.cbl PROCESS-ENTER-KEY paragraph:</p>
     * <pre>
     *   IF USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'User ID Cannot Be Empty' TO WS-MESSAGE
     * </pre>
     *
     * <p>Tests that the controller correctly maps {@link ValidationException}
     * to HTTP 400 status.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — blank userId returns 400 (← COSGN00C USERIDI = SPACES)")
    void loginBlankUserId_returns400() throws Exception {
        // Arrange: Mock service to throw ValidationException for blank userId
        // Uses any() matchers since the controller passes the blank userId as-is
        willThrow(new ValidationException("User ID Cannot Be Empty"))
                .given(signonService).processEnterKey(any(), any());

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "", "password", "password"));

        // Act & Assert: Expect 400 Bad Request with error message
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User ID Cannot Be Empty"));
    }

    /**
     * Test 5: Blank password returns HTTP 400 Bad Request.
     *
     * <p>Maps to COSGN00C.cbl PROCESS-ENTER-KEY paragraph:</p>
     * <pre>
     *   IF PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
     *     MOVE 'Password Cannot Be Empty' TO WS-MESSAGE
     * </pre>
     *
     * <p>Tests that the controller correctly maps {@link ValidationException}
     * to HTTP 400 status for blank password input.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — blank password returns 400 (← COSGN00C PASSWDI = SPACES)")
    void loginBlankPassword_returns400() throws Exception {
        // Arrange: Mock service to throw ValidationException for blank password
        willThrow(new ValidationException("Password Cannot Be Empty"))
                .given(signonService).processEnterKey(eq("USER0001"), eq(""));

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "USER0001", "password", ""));

        // Act & Assert: Expect 400 Bad Request with error message
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password Cannot Be Empty"));
    }

    /**
     * Test 6: Successful login response NEVER contains a password field.
     *
     * <p><strong>CRITICAL SECURITY INVARIANT</strong>: The password value must
     * NEVER appear in any HTTP response body. This is a zero-tolerance security
     * rule — the COBOL application used BMS DRK (dark) attribute on the PASSWD
     * field to prevent display; the Java migration must ensure the password
     * never leaks through the REST API response.</p>
     *
     * <p>Verifies absence of common password field names: {@code password},
     * {@code pwd}, {@code passwd}.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/login — response NEVER contains password field (security invariant)")
    @WithMockUser(roles = "USER")
    void loginResponse_neverContainsPassword() throws Exception {
        // Arrange: Configure successful authentication with ADMIN user type
        // to verify password absence regardless of user role
        given(cardDemoContext.getUserId()).willReturn("USER0001");
        given(cardDemoContext.getUserType()).willReturn(UserType.ADMIN);

        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "USER0001", "password", "password"));

        // Act & Assert: Verify password is NEVER present in the response
        // Check all common password field name variations
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.pwd").doesNotExist())
                .andExpect(jsonPath("$.passwd").doesNotExist());
    }

    /**
     * Test 7: Logout returns HTTP 200 with success message.
     *
     * <p>Maps to COSGN00C.cbl PF3 key handling in MAIN-PARA:</p>
     * <pre>
     *   EVALUATE EIBAID
     *     WHEN DFHPF3
     *       MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *       PERFORM SEND-PLAIN-TEXT
     *       EXEC CICS RETURN END-EXEC
     * </pre>
     *
     * <p>In the COBOL program, PF3 terminates the pseudo-conversational session
     * via {@code EXEC CICS RETURN} without a TRANSID (no further conversation).
     * The Java equivalent is a stateless POST to the logout endpoint that
     * returns a success confirmation.</p>
     *
     * @throws Exception if MockMvc request execution fails
     */
    @Test
    @DisplayName("POST /api/auth/logout — returns 200 with success message (← COSGN00C PF3 → CICS RETURN)")
    @WithMockUser(roles = "USER")
    void logout_returnsOk() throws Exception {
        // Arrange: Mock cardDemoContext.getUserId() for audit log in controller
        given(cardDemoContext.getUserId()).willReturn("USER0001");

        // Act & Assert: POST logout and verify success response
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logout successful"));
    }
}
