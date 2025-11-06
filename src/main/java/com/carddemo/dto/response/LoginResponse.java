/*
 * LoginResponse.java
 * 
 * Login authentication response DTO containing JWT token details returned after 
 * successful user authentication.
 * 
 * This class maps the COBOL COMMAREA session context structure (COCOM01Y.cpy) 
 * to a JSON response format for modern RESTful authentication. It replaces the 
 * mainframe RACF authentication mechanism with JWT-based stateless security.
 * 
 * COBOL Source Mapping:
 * - CDEMO-USER-ID (PIC X(08)) → userId field
 * - CDEMO-USER-TYPE (PIC X(01)) → userType field
 *   * 88 CDEMO-USRTYP-ADMIN VALUE 'A' → "ADMIN"
 *   * 88 CDEMO-USRTYP-USER VALUE 'U' → "USER"
 * 
 * Transformation Details:
 * - Replaces CICS pseudo-conversational COMMAREA state management
 * - Eliminates server-side session storage for horizontal scalability
 * - JWT token contains userId and userType claims for stateless authorization
 * - Token expiration replaces CICS session timeout behavior (default 24 hours)
 * 
 * Security Considerations:
 * - JWT token should be stored securely in browser (consider security implications):
 *   * localStorage: Persistent but vulnerable to XSS attacks
 *   * sessionStorage: Cleared on tab close, still vulnerable to XSS
 *   * Memory-only: Most secure but lost on page refresh
 * - Token must be sent in Authorization header as "Bearer {token}"
 * - Client should implement token refresh before expiration
 * - Never expose JWT secret key in client-side code
 * 
 * Usage Pattern:
 * <pre>
 * // AuthenticationService returns this response after successful login
 * LoginResponse response = authService.login(loginRequest);
 * 
 * // React frontend stores token and uses in subsequent requests
 * localStorage.setItem('jwtToken', response.jwtToken);
 * axios.defaults.headers.common['Authorization'] = `Bearer ${response.jwtToken}`;
 * </pre>
 * 
 * Related Components:
 * - AuthenticationService.login() - Generates this response
 * - AuthController.login() - Returns this response to client
 * - JwtService - Creates the JWT token included in this response
 * - SecurityConfig - Validates tokens on subsequent requests
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for authentication response containing JWT token credentials.
 * 
 * This DTO is returned by the POST /api/auth/login endpoint after successful
 * user authentication. It provides all necessary information for the client to
 * establish an authenticated session using JWT bearer tokens.
 * 
 * The response structure enables stateless authentication by including:
 * - JWT token for authorization (replaces RACF security ticket)
 * - User identification for session context
 * - User role for client-side authorization decisions
 * - Expiration timestamp for token refresh logic
 * 
 * JSON Response Example:
 * <pre>
 * {
 *   "jwtToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
 *   "userId": "USER0001",
 *   "userType": "ADMIN",
 *   "expiresAt": "2024-01-15T14:30:00"
 * }
 * </pre>
 * 
 * @see com.carddemo.service.auth.AuthenticationService
 * @see com.carddemo.controller.AuthController
 * @see com.carddemo.service.auth.JwtService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse {

    /**
     * JWT bearer token for stateless API authentication.
     * 
     * This token must be included in the Authorization header of all subsequent
     * API requests using the format: "Bearer {jwtToken}"
     * 
     * Token Generation:
     * - Signed using HMAC SHA-256 algorithm with configurable secret key
     * - Contains claims: userId, userType, issuedAt, expiresAt
     * - Secret key configured in application.properties as jwt.secret
     * - Expiration period configurable via jwt.expiration (default: 86400000ms = 24 hours)
     * 
     * Security Notes:
     * - Token signature prevents tampering - any modification invalidates the token
     * - Token cannot be revoked before expiration (implement token blacklist if needed)
     * - Compromised tokens remain valid until expiration
     * - Use HTTPS to prevent token interception in transit
     * 
     * COBOL Transformation:
     * Replaces RACF authentication ticket and CICS COMMAREA-based session state.
     * Original COBOL programs maintained session via pseudo-conversational COMMAREA
     * passed between transactions. JWT eliminates this by encoding session data in
     * the token itself, enabling stateless horizontal scaling.
     * 
     * @see com.carddemo.service.auth.JwtService#generateToken(String, String)
     */
    @JsonProperty("jwtToken")
    private String jwtToken;

    /**
     * Authenticated user identifier.
     * 
     * Maps from COBOL field CDEMO-USER-ID (PIC X(08)) in COCOM01Y.cpy COMMAREA structure.
     * This is the unique identifier for the user account in the system.
     * 
     * Original COBOL Structure:
     * <pre>
     * 10 CDEMO-USER-ID    PIC X(08).
     * </pre>
     * 
     * Usage:
     * - Displayed in UI to show current logged-in user
     * - Used for audit logging to track user actions
     * - Included as claim in JWT token for authorization checks
     * - Referenced in database queries to retrieve user-specific data
     * 
     * Validation:
     * - Must be 8 characters or less (matching COBOL PIC X(08))
     * - Alphanumeric characters only
     * - Case-sensitive (COBOL uppercase convention typically preserved)
     * 
     * @see com.carddemo.entity.User
     */
    @JsonProperty("userId")
    private String userId;

    /**
     * User role type for authorization and access control.
     * 
     * Maps from COBOL field CDEMO-USER-TYPE (PIC X(01)) in COCOM01Y.cpy with
     * 88-level condition names transformed to enum-style string values.
     * 
     * Original COBOL Structure:
     * <pre>
     * 10 CDEMO-USER-TYPE          PIC X(01).
     *    88 CDEMO-USRTYP-ADMIN    VALUE 'A'.
     *    88 CDEMO-USRTYP-USER     VALUE 'U'.
     * </pre>
     * 
     * Valid Values:
     * - "ADMIN" - Administrative user with full system access
     *   * Can view/update all accounts, cards, transactions
     *   * Can manage user accounts (create, update, delete)
     *   * Access to admin menu and administrative functions
     *   * Mapped from COBOL value 'A'
     * 
     * - "USER" - Regular user with limited access
     *   * Can view own accounts, cards, transactions
     *   * Cannot modify credit limits or user accounts
     *   * Access restricted to standard user functions
     *   * Mapped from COBOL value 'U'
     * 
     * Authorization Pattern:
     * - Used in @PreAuthorize annotations on service methods
     * - Controls menu visibility in React frontend
     * - Determines available API endpoints
     * - Encoded as claim in JWT token for stateless authorization
     * 
     * Example Authorization:
     * <pre>
     * @PreAuthorize("hasRole('ADMIN')")
     * public void updateCreditLimit(...) { }
     * 
     * @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
     * public void viewAccount(...) { }
     * </pre>
     * 
     * @see com.carddemo.security.CustomUserDetailsService
     * @see org.springframework.security.access.prepost.PreAuthorize
     */
    @JsonProperty("userType")
    private String userType;

    /**
     * JWT token expiration timestamp in ISO 8601 format.
     * 
     * Calculated as: current time + jwt.expiration milliseconds from application.properties
     * Default expiration: 24 hours (86400000 milliseconds), matching typical CICS session timeout
     * 
     * Format:
     * - ISO 8601 date-time format: "yyyy-MM-dd'T'HH:mm:ss"
     * - Example: "2024-01-15T14:30:00"
     * - Timezone-neutral (assumes server timezone, typically UTC in cloud deployments)
     * 
     * Client-Side Token Refresh Strategy:
     * 1. Client stores expiresAt timestamp when receiving login response
     * 2. Before making API calls, client checks if token expires soon (e.g., within 5 minutes)
     * 3. If expiring soon, client calls refresh endpoint to obtain new token
     * 4. If expired, client redirects to login page
     * 
     * Example Refresh Logic (JavaScript):
     * <pre>
     * const expiresAt = new Date(loginResponse.expiresAt);
     * const now = new Date();
     * const minutesUntilExpiry = (expiresAt - now) / (1000 * 60);
     * 
     * if (minutesUntilExpiry < 5) {
     *   await refreshToken();
     * }
     * </pre>
     * 
     * COBOL Transformation:
     * Replaces CICS session timeout mechanism. Original COBOL programs relied on
     * CICS to manage session timeout (typically set at system level). JWT approach
     * makes timeout explicit and manageable at application level, enabling per-user
     * or per-role timeout policies if needed.
     * 
     * Configuration:
     * Set in application.properties:
     * <pre>
     * jwt.expiration=86400000  # 24 hours in milliseconds
     * </pre>
     * 
     * @see com.carddemo.service.auth.JwtService#generateToken(String, String)
     * @see java.time.LocalDateTime
     */
    @JsonProperty("expiresAt")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expiresAt;

}
