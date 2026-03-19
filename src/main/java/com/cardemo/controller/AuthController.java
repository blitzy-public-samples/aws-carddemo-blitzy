/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cardemo.controller;

import com.cardemo.common.context.CardDemoContext;
import com.cardemo.common.enums.UserType;
import com.cardemo.common.exception.AuthenticationException;
import com.cardemo.common.exception.ValidationException;
import com.cardemo.service.online.SignonService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for user authentication — translates CICS transaction CC00
 * (COSGN00C.cbl) into stateless REST endpoints for sign-on and sign-off.
 *
 * <p>This controller is the HTTP entry point for the CardDemo authentication
 * subsystem. It exposes two endpoints that map directly to the COBOL sign-on
 * program's user interactions:</p>
 *
 * <h2>Endpoint Mapping (← COSGN00C.cbl)</h2>
 * <table>
 *   <tr><th>Endpoint</th><th>COBOL Source</th><th>Description</th></tr>
 *   <tr>
 *     <td>{@code POST /api/auth/login}</td>
 *     <td>MAIN-PARA (line 73) → PROCESS-ENTER-KEY (line 108) →
 *         READ-USER-SEC-FILE (line 209)</td>
 *     <td>User authentication — validates credentials, returns user info</td>
 *   </tr>
 *   <tr>
 *     <td>{@code POST /api/auth/logout}</td>
 *     <td>PF3 key handling in MAIN-PARA → EXEC CICS RETURN</td>
 *     <td>Session termination — clears context, confirms logout</td>
 *   </tr>
 * </table>
 *
 * <h2>BMS Map Reference (COSGN00.bms)</h2>
 * <p>The login request body maps to the BMS sign-on screen fields:</p>
 * <ul>
 *   <li>{@code userId} → USERID field: {@code PIC X(08)}, max 8 characters</li>
 *   <li>{@code password} → PASSWD field: {@code PIC X(08)} with DRK attribute
 *       (dark/masked input)</li>
 * </ul>
 *
 * <h2>Security Principles</h2>
 * <ul>
 *   <li><strong>Delegation only</strong> — All authentication logic resides in
 *       {@link SignonService}. This controller contains zero business logic.</li>
 *   <li><strong>Password never exposed</strong> — The password value is NEVER
 *       included in response bodies, log messages, or error details.</li>
 *   <li><strong>Stateless REST</strong> — The CICS pseudo-conversational model
 *       is mapped to stateless HTTP. {@link CardDemoContext} is request-scoped
 *       and exists only for the duration of a single request.</li>
 *   <li><strong>No feature expansion</strong> — Only login and logout are
 *       provided, matching the COBOL program's Enter key and PF3 key handlers.
 *       No password reset, MFA, or session timeout endpoints.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <ul>
 *   <li>{@link ValidationException} → HTTP 400 Bad Request (blank fields)</li>
 *   <li>{@link AuthenticationException} → HTTP 401 Unauthorized (bad credentials)</li>
 *   <li>Unexpected exceptions → HTTP 500 Internal Server Error</li>
 * </ul>
 *
 * @see SignonService
 * @see CardDemoContext
 * @see AuthenticationException
 * @see ValidationException
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * SLF4J logger for structured logging with correlation IDs.
     * Logs authentication attempts (userId only, NEVER password), results,
     * logout events, and unexpected errors per AAP observability requirements.
     */
    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    /**
     * Sign-on service encapsulating all authentication business logic
     * translated from COSGN00C.cbl. Injected via constructor — no field injection.
     */
    private final SignonService signonService;

    /**
     * Request-scoped session context bean mirroring the 1024-byte COMMAREA
     * (COCOM01Y.cpy). Used to retrieve authenticated user info after
     * {@link SignonService} populates it, and to reference the current user
     * during logout.
     */
    private final CardDemoContext cardDemoContext;

    /**
     * Constructs the {@code AuthController} with all required dependencies.
     *
     * <p>Uses constructor injection (not {@code @Autowired} field injection)
     * for testability and immutability. Both dependencies are final and set
     * once at construction time.</p>
     *
     * @param signonService   the authentication service (← COSGN00C.cbl)
     * @param cardDemoContext  the request-scoped session context (← COCOM01Y.cpy COMMAREA)
     */
    public AuthController(SignonService signonService, CardDemoContext cardDemoContext) {
        this.signonService = signonService;
        this.cardDemoContext = cardDemoContext;
    }

    /**
     * Authenticates a user — maps to COSGN00C.cbl PROCESS-ENTER-KEY paragraph.
     *
     * <p>This endpoint translates the CICS Enter key handler from the sign-on
     * screen. The COBOL flow is:</p>
     * <ol>
     *   <li>MAIN-PARA (line 73) receives control</li>
     *   <li>EVALUATE EIBAID → WHEN DFHENTER (line 86)</li>
     *   <li>PERFORM PROCESS-ENTER-KEY (line 87)</li>
     *   <li>Validate USERIDI and PASSWDI not blank (lines 117–130)</li>
     *   <li>FUNCTION UPPER-CASE both fields (lines 132–136)</li>
     *   <li>PERFORM READ-USER-SEC-FILE (line 139)</li>
     *   <li>Verify SEC-USR-PWD = WS-USER-PWD (line 223, now BCrypt)</li>
     *   <li>Populate COMMAREA (lines 224–228)</li>
     *   <li>Route to COADM01C (admin) or COMEN01C (user) via XCTL (lines 230–239)</li>
     * </ol>
     *
     * <p><strong>CRITICAL SECURITY</strong>: The password parameter is passed
     * to {@link SignonService#processEnterKey(String, String)} but is NEVER
     * logged, stored in memory beyond the call, or included in any response.</p>
     *
     * @param request typed {@link LoginRequest} DTO with Jakarta Bean Validation
     *                constraints enforcing non-blank, max-8-char fields matching
     *                BMS USERID PIC X(08) and PASSWD PIC X(08) DRK
     * @return HTTP 200 with {@code {userId, userType, message}} on success;
     *         HTTP 400 with {@code {error}} for validation failures;
     *         HTTP 401 with {@code {error}} for authentication failures;
     *         HTTP 500 with {@code {error}} for unexpected errors
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @Valid @RequestBody LoginRequest request) {

        String userId = request.getUserId();
        String password = request.getPassword();

        // Log the authentication attempt — userId only, NEVER password
        logger.info("Login attempt initiated for userId='{}'", userId);

        try {
            // Delegate to SignonService.processEnterKey — maps to COBOL
            // PERFORM PROCESS-ENTER-KEY (line 87) which performs:
            //   1. Input validation (blank checks)
            //   2. FUNCTION UPPER-CASE on both fields
            //   3. PERFORM READ-USER-SEC-FILE (USRSEC dataset lookup)
            //   4. Password verification (BCrypt replacing plaintext)
            //   5. COMMAREA population on success
            signonService.processEnterKey(userId, password);

            // Authentication successful — read populated context
            // Maps to post-authentication state where COMMAREA contains:
            //   CDEMO-USER-ID (line 226) and CDEMO-USER-TYPE (line 227)
            String authenticatedUserId = cardDemoContext.getUserId();
            UserType userType = cardDemoContext.getUserType();
            String userTypeStr = userType != null ? userType.name() : "UNKNOWN";

            logger.info("Authentication successful for userId='{}', userType='{}'",
                    authenticatedUserId, userTypeStr);

            // Return success response — matches COBOL's successful XCTL transfer
            // where user identity is carried in COMMAREA to the next program
            return ResponseEntity.ok(Map.of(
                    "userId", authenticatedUserId != null ? authenticatedUserId : "",
                    "userType", userTypeStr,
                    "message", "Authentication successful"
            ));

        } catch (ValidationException e) {
            // Maps to COBOL input validation failures:
            //   WHEN USERIDI = SPACES → 'User ID Cannot Be Empty'
            //   WHEN PASSWDI = SPACES → 'Password Cannot Be Empty'
            logger.warn("Validation failed for userId='{}': {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));

        } catch (AuthenticationException e) {
            // Maps to COBOL READ-USER-SEC-FILE error paths:
            //   WHEN 0 + password mismatch → 'Wrong Password. Try again ...'
            //   WHEN 13 (NOTFND) → 'User not found. Try again ...'
            //   WHEN OTHER → 'Unable to verify the User ...'
            // Also catches blank-field AuthenticationExceptions from SignonService
            logger.warn("Authentication failed for userId='{}': {}", userId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            // Catch-all for unexpected errors not covered by business exceptions.
            // Maps to unhandled conditions in the COBOL EVALUATE block.
            logger.error("Unexpected error during login for userId='{}'", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error"));
        }
    }

    /**
     * Terminates the user session — maps to COSGN00C.cbl PF3 key handler.
     *
     * <p>This endpoint translates the CICS PF3 key handling in MAIN-PARA:</p>
     * <ol>
     *   <li>EVALUATE EIBAID → WHEN DFHPF3 (line 88)</li>
     *   <li>MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE (line 89)</li>
     *   <li>PERFORM SEND-PLAIN-TEXT (line 90)</li>
     *   <li>EXEC CICS RETURN (line 171) — terminates pseudo-conversational
     *       session without TRANSID (no further conversation)</li>
     * </ol>
     *
     * <p>In the stateless REST model, {@link CardDemoContext} is request-scoped
     * and automatically destroyed at request end. This endpoint serves as a
     * semantic signal that the client is ending its session. No server-side
     * session state persists beyond the request lifecycle.</p>
     *
     * @return HTTP 200 with {@code {message: "Logout successful"}}
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        // Log the logout event with userId for audit trail
        String userId = cardDemoContext.getUserId();
        logger.info("Logout request received for userId='{}'", userId);

        // In COBOL, EXEC CICS RETURN without TRANSID ends the session.
        // In Spring, CardDemoContext is @RequestScope — it is created fresh
        // per request and destroyed automatically. No explicit cleanup needed.
        // The logout endpoint provides a semantic contract for clients.

        logger.info("Logout successful for userId='{}'", userId);
        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }

    // =========================================================================
    // Request DTOs
    // =========================================================================

    /**
     * Typed login request DTO with Jakarta Bean Validation constraints,
     * replacing the untyped {@code Map<String, String>} parameter.
     *
     * <p>Maps directly to the BMS sign-on screen (COSGN00.bms) input fields:</p>
     * <ul>
     *   <li>{@code userId} → USERID field: {@code PIC X(08)}, required, max 8 chars</li>
     *   <li>{@code password} → PASSWD field: {@code PIC X(08)}, required, max 8 chars,
     *       DRK attribute (masked)</li>
     * </ul>
     *
     * <p>Validation constraints enforce input requirements at the REST boundary
     * before business logic (defense-in-depth), matching the COBOL field-level
     * checks in COSGN00C.cbl lines 117–130 that test for SPACES.</p>
     */
    public static class LoginRequest {

        /**
         * User ID — maps to BMS USERID PIC X(08). Required, max 8 characters.
         * Validation message matches COBOL COSGN00C.cbl line 118:
         * {@code IF USERIDI = SPACES → MOVE 'User ID Cannot Be Empty' TO WS-MESSAGE}
         */
        @NotBlank(message = "User ID Cannot Be Empty")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        private String userId;

        /**
         * Password — maps to BMS PASSWD PIC X(08) DRK. Required, max 8 characters.
         * Validation message matches COBOL COSGN00C.cbl line 126:
         * {@code IF PASSWDI = SPACES → MOVE 'Password Cannot Be Empty' TO WS-MESSAGE}
         */
        @NotBlank(message = "Password Cannot Be Empty")
        @Size(max = 8, message = "Password must be at most 8 characters")
        private String password;

        /** Default constructor required by Jackson deserialization. */
        public LoginRequest() {
        }

        /**
         * Constructs a LoginRequest with the specified credentials.
         *
         * @param userId   the user identifier (max 8 chars)
         * @param password the user password (max 8 chars)
         */
        public LoginRequest(String userId, String password) {
            this.userId = userId;
            this.password = password;
        }

        /**
         * Returns the user ID.
         * @return user identifier string
         */
        public String getUserId() {
            return userId;
        }

        /**
         * Sets the user ID.
         * @param userId the user identifier
         */
        public void setUserId(String userId) {
            this.userId = userId;
        }

        /**
         * Returns the password.
         * @return password string
         */
        public String getPassword() {
            return password;
        }

        /**
         * Sets the password.
         * @param password the user password
         */
        public void setPassword(String password) {
            this.password = password;
        }
    }
}
