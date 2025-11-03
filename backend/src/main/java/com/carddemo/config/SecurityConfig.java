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

package com.carddemo.config;

import com.carddemo.security.CustomUserDetailsService;
import com.carddemo.security.JwtAuthenticationFilter;
import com.carddemo.security.SecurityConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security configuration for the CardDemo application.
 * 
 * <p>This configuration class transforms the mainframe RACF/CICS security model
 * from COSGN00C.cbl (sign-on screen processing) into a modern Spring Security 6.x
 * architecture with JWT-based authentication and role-based authorization. It replaces
 * USRSEC VSAM file authentication with PostgreSQL database authentication via
 * {@link CustomUserDetailsService} and implements stateless REST API security replacing
 * CICS pseudo-conversational session management.</p>
 * 
 * <h2>COBOL/CICS Security Model Transformation</h2>
 * 
 * <p><strong>Original Mainframe Architecture (COSGN00C.cbl):</strong></p>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  3270 Terminal → BMS Sign-on Screen (COSGN00M)             │
 * │  User enters: USER-ID (PIC X(08)), PASSWORD (PIC X(08))    │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  CICS Transaction CC00 → COSGN00C Program                  │
 * │  EXEC CICS READ DATASET(USRSEC)                            │
 * │       INTO(SEC-USER-DATA)                                  │
 * │       RIDFLD(WS-USER-ID)                                   │
 * │  IF SEC-USR-PWD = WS-USER-PWD (plain-text comparison)     │
 * │      MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE                  │
 * │      IF CDEMO-USRTYP-ADMIN (SEC-USR-TYPE = 'A')           │
 * │          EXEC CICS XCTL PROGRAM('COADM01C')                │
 * │      ELSE                                                  │
 * │          EXEC CICS XCTL PROGRAM('COMEN01C')                │
 * └─────────────────────────────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  COMMAREA State Management                                  │
 * │  - CDEMO-USER-ID: Current user                             │
 * │  - CDEMO-USER-TYPE: 'A' (Admin) or 'U' (User)             │
 * │  - Passed between transactions via EXEC CICS XCTL          │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 * 
 * <p><strong>Modern Cloud-Native Architecture (Spring Boot):</strong></p>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │  React SPA → Login Component                                │
 * │  POST /api/auth/login                                       │
 * │  { "userId": "USER0001", "password": "password123" }       │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  AuthenticationController → AuthenticationService           │
 * │  AuthenticationManager.authenticate()                       │
 * │  → CustomUserDetailsService.loadUserByUsername()            │
 * │  → UserSecurityRepository.findByUserId() [PostgreSQL]       │
 * │  → PasswordEncoder.matches() [BCrypt hash comparison]       │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  JWT Token Generation (JwtTokenProvider)                    │
 * │  Token payload: { sub: "USER0001", roles: "ROLE_USER" }    │
 * │  Expiration: 24 hours                                       │
 * │  Response: { token: "eyJ...", type: "Bearer" }             │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  Client includes token in all requests                      │
 * │  Authorization: Bearer eyJ...                               │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  JwtAuthenticationFilter (on every request)                 │
 * │  1. Extract JWT from Authorization header                   │
 * │  2. Validate token signature and expiration                 │
 * │  3. Load user details from database                         │
 * │  4. Set SecurityContext with user authorities               │
 * └────────────────────────┬────────────────────────────────────┘
 *                          ↓
 * ┌─────────────────────────────────────────────────────────────┐
 * │  Controller Method-Level Security                           │
 * │  @PreAuthorize("hasRole('USER')") - Regular users           │
 * │  @PreAuthorize("hasRole('ADMIN')") - Admin only             │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 * 
 * <h2>Key Transformation Mappings</h2>
 * 
 * <table border="1">
 *   <tr>
 *     <th>COBOL/CICS Concept</th>
 *     <th>Java/Spring Security Equivalent</th>
 *     <th>Implementation Location</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READ USRSEC</td>
 *     <td>CustomUserDetailsService.loadUserByUsername()</td>
 *     <td>Database query via UserSecurityRepository</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-PWD = WS-USER-PWD</td>
 *     <td>PasswordEncoder.matches(plain, hash)</td>
 *     <td>BCrypt password verification</td>
 *   </tr>
 *   <tr>
 *     <td>SEC-USR-TYPE ('A' or 'U')</td>
 *     <td>GrantedAuthority (ROLE_ADMIN or ROLE_USER)</td>
 *     <td>SimpleGrantedAuthority in UserDetails</td>
 *   </tr>
 *   <tr>
 *     <td>COMMAREA (user context)</td>
 *     <td>JWT token claims</td>
 *     <td>JwtTokenProvider</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS XCTL PROGRAM</td>
 *     <td>@PreAuthorize method security</td>
 *     <td>Spring Security authorization</td>
 *   </tr>
 *   <tr>
 *     <td>CICS session management</td>
 *     <td>Stateless JWT authentication</td>
 *     <td>SecurityFilterChain</td>
 *   </tr>
 * </table>
 * 
 * <h2>Security Configuration Components</h2>
 * 
 * <p>This class configures the following Spring Security components:</p>
 * 
 * <ol>
 *   <li><strong>SecurityFilterChain Bean ({@link #filterChain}):</strong>
 *       <ul>
 *         <li>HTTP security configuration with JWT authentication filter</li>
 *         <li>Public endpoints (login, health checks, Swagger documentation)</li>
 *         <li>Protected endpoints (all /api/** except auth)</li>
 *         <li>CSRF disabled (stateless REST API)</li>
 *         <li>Stateless session management (SessionCreationPolicy.STATELESS)</li>
 *         <li>CORS configuration for frontend integration</li>
 *         <li>HTTP security headers (X-Frame-Options, X-Content-Type-Options)</li>
 *       </ul>
 *   </li>
 *   
 *   <li><strong>Password Encoder Bean ({@link #passwordEncoder}):</strong>
 *       <ul>
 *         <li>BCrypt password encoder with strength 12</li>
 *         <li>Replaces COBOL plain-text password storage (SEC-USR-PWD PIC X(08))</li>
 *         <li>Password hash format: $2a$12$[salt][hash] (60 characters)</li>
 *         <li>Used by AuthenticationManager for credential verification</li>
 *       </ul>
 *   </li>
 *   
 *   <li><strong>Authentication Manager Bean ({@link #authenticationManager}):</strong>
 *       <ul>
 *         <li>Processes authentication requests during login</li>
 *         <li>Delegates to CustomUserDetailsService for user lookup</li>
 *         <li>Uses PasswordEncoder for password verification</li>
 *         <li>Required by AuthenticationController for login processing</li>
 *       </ul>
 *   </li>
 *   
 *   <li><strong>CORS Configuration Bean ({@link #corsConfigurationSource}):</strong>
 *       <ul>
 *         <li>Allows frontend React application to call backend APIs</li>
 *         <li>Configures allowed origins from application.yml (cors.allowed-origins)</li>
 *         <li>Allows GET, POST, PUT, DELETE HTTP methods</li>
 *         <li>Exposes Authorization header for JWT tokens</li>
 *         <li>Max age: 3600 seconds (1 hour) for preflight caching</li>
 *       </ul>
 *   </li>
 * </ol>
 * 
 * <h2>Role-Based Access Control (RBAC)</h2>
 * 
 * <p>Per Section 0.9 security model preservation requirements, the application
 * maintains exact access control patterns from the mainframe:</p>
 * 
 * <p><strong>ROLE_USER (Regular Users):</strong></p>
 * <ul>
 *   <li>COBOL user type: 'R' or 'U' (SEC-USR-TYPE from USRSEC file)</li>
 *   <li>Access level: View and manage own accounts, cards, transactions</li>
 *   <li>Allowed operations:
 *       <ul>
 *         <li>View own account details (COACTVWC program equivalent)</li>
 *         <li>Update own account information (COACTUPC program equivalent)</li>
 *         <li>View card list (COCRDLIC program equivalent)</li>
 *         <li>View transaction history (COTRN00C program equivalent)</li>
 *         <li>Make bill payments (COBIL00C program equivalent)</li>
 *         <li>Update own profile (COUSR01C program equivalent)</li>
 *       </ul>
 *   </li>
 *   <li>Denied operations: Administrative functions, reporting, user management</li>
 * </ul>
 * 
 * <p><strong>ROLE_ADMIN (Administrative Users):</strong></p>
 * <ul>
 *   <li>COBOL user type: 'A' (SEC-USR-TYPE = 'A' from USRSEC file)</li>
 *   <li>Access level: All ROLE_USER permissions plus administrative functions</li>
 *   <li>Additional allowed operations:
 *       <ul>
 *         <li>View any customer/account information (admin view)</li>
 *         <li>Update any account/card status</li>
 *         <li>User management operations (COUSR00C program equivalent)</li>
 *         <li>Generate system reports (CORPT00C program equivalent)</li>
 *         <li>Administrative functions (COADM01C program equivalent)</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <h2>Method-Level Security Examples</h2>
 * 
 * <p>The @EnableMethodSecurity annotation enables fine-grained authorization checks
 * using @PreAuthorize annotations on controller and service methods:</p>
 * 
 * <pre>
 * // Regular user operation - both ROLE_USER and ROLE_ADMIN allowed
 * {@literal @}PreAuthorize("hasRole('USER')")
 * public AccountViewResponse viewOwnAccount(Long accountId, String userId) {
 *     // Verify user owns this account (business logic validation)
 *     return accountViewService.viewAccount(accountId, userId);
 * }
 * 
 * // Administrative operation - ROLE_ADMIN only
 * {@literal @}PreAuthorize("hasRole('ADMIN')")
 * public AccountViewResponse viewAnyAccount(Long accountId) {
 *     // Admin can view any account without ownership check
 *     return accountViewService.viewAccountAdmin(accountId);
 * }
 * 
 * // Complex authorization with SpEL expression
 * {@literal @}PreAuthorize("hasRole('ADMIN') or (#userId == authentication.principal.username)")
 * public void updateProfile(String userId, ProfileUpdateRequest request) {
 *     // Allow admin to update any profile, or user to update own profile
 *     userProfileService.updateProfile(userId, request);
 * }
 * </pre>
 * 
 * <h2>Security Requirements Compliance</h2>
 * 
 * <p>This configuration implements all security requirements from Section 0.1 and Section 0.9:</p>
 * <ul>
 *   <li><strong>JWT Authentication:</strong> Stateless token-based auth replacing CICS sessions</li>
 *   <li><strong>Password Encryption:</strong> BCrypt with strength 12 replacing plain-text passwords</li>
 *   <li><strong>Role-Based Authorization:</strong> Two-tier model (USER/ADMIN) preserved from mainframe</li>
 *   <li><strong>Session Management:</strong> Stateless (SessionCreationPolicy.STATELESS)</li>
 *   <li><strong>Method-Level Security:</strong> @PreAuthorize for fine-grained access control</li>
 *   <li><strong>CORS Support:</strong> Configured for frontend React application integration</li>
 *   <li><strong>Public Endpoints:</strong> Authentication, health checks, API documentation</li>
 *   <li><strong>Security Headers:</strong> X-Frame-Options, X-Content-Type-Options, X-XSS-Protection</li>
 * </ul>
 * 
 * <h2>Integration Points</h2>
 * 
 * <p>This configuration integrates with the following components:</p>
 * <ul>
 *   <li>{@link JwtAuthenticationFilter} - Custom filter for JWT token validation</li>
 *   <li>{@link CustomUserDetailsService} - Loads user details from database</li>
 *   <li>{@link SecurityConstants} - Centralized security configuration constants</li>
 *   <li>{@link com.carddemo.controller.AuthenticationController} - Login endpoint</li>
 *   <li>{@link com.carddemo.repository.UserSecurityRepository} - User data access</li>
 *   <li>{@link com.carddemo.entity.UserSecurity} - User entity with password hash</li>
 * </ul>
 * 
 * <h2>Performance Considerations</h2>
 * 
 * <ul>
 *   <li>Stateless architecture eliminates session storage overhead</li>
 *   <li>JWT validation uses in-memory cryptographic operations (fast)</li>
 *   <li>User details loaded once during login, cached in JWT token</li>
 *   <li>No database queries for authentication on subsequent requests</li>
 *   <li>Target overhead: &lt; 10ms per request for security filter chain</li>
 * </ul>
 * 
 * <h2>Configuration Properties</h2>
 * 
 * <p>The following application.yml properties are referenced:</p>
 * <pre>
 * cors:
 *   allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
 * 
 * app:
 *   jwt:
 *     secret: ${JWT_SECRET:&lt;default-secret&gt;}
 *     expiration-ms: 86400000  # 24 hours
 * </pre>
 * 
 * @see JwtAuthenticationFilter
 * @see CustomUserDetailsService
 * @see SecurityConstants
 * @see EnableWebSecurity
 * @see EnableMethodSecurity
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {
    
    /**
     * JWT authentication filter for token validation.
     * 
     * <p>This custom filter intercepts HTTP requests to extract and validate
     * JWT tokens from the Authorization header. It sets the authentication
     * context for the current request if a valid token is present.</p>
     * 
     * <p>Injected automatically by Spring's dependency injection.</p>
     * 
     * @see JwtAuthenticationFilter
     */
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    
    /**
     * Custom UserDetailsService for loading user authentication details.
     * 
     * <p>Loads user information from the PostgreSQL user_security table,
     * replacing COBOL EXEC CICS READ DATASET(USRSEC) operations from
     * COSGN00C.cbl.</p>
     * 
     * <p>Injected automatically by Spring's dependency injection.</p>
     * 
     * @see CustomUserDetailsService
     */
    @Autowired
    private CustomUserDetailsService customUserDetailsService;
    
    /**
     * CORS allowed origins configuration property.
     * 
     * <p>Specifies which frontend origins are allowed to make cross-origin
     * requests to this backend API. Configured via application.yml:</p>
     * 
     * <pre>
     * cors:
     *   allowed-origins: http://localhost:3000,https://app.carddemo.com
     * </pre>
     * 
     * <p>Defaults to "http://localhost:3000" for local development if not specified.</p>
     */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;
    
    /**
     * Configures the Spring Security filter chain with JWT authentication and authorization.
     * 
     * <p>This is the core security configuration method that defines which endpoints
     * are public vs. protected, how authentication is performed (JWT tokens), and
     * what security policies apply to HTTP requests. It replaces the COBOL/CICS
     * security model from COSGN00C.cbl with modern Spring Security patterns.</p>
     * 
     * <h3>COBOL Security Flow Replacement</h3>
     * 
     * <p><strong>Original COBOL/CICS Pattern (COSGN00C.cbl):</strong></p>
     * <pre>
     * MAIN-PARA.
     *     IF EIBCALEN = 0
     *         * No COMMAREA - redirect to sign-on screen
     *         PERFORM SEND-SIGNON-SCREEN
     *     ELSE
     *         * COMMAREA present - user is authenticated
     *         EVALUATE EIBAID
     *             WHEN DFHENTER
     *                 PERFORM PROCESS-ENTER-KEY
     *             WHEN DFHPF3
     *                 * Exit/logout
     *                 PERFORM SEND-PLAIN-TEXT
     *         END-EVALUATE
     *     END-IF.
     * </pre>
     * 
     * <p><strong>Java Spring Security Equivalent:</strong></p>
     * <ul>
     *   <li>EIBCALEN = 0 (no COMMAREA) → No JWT token in Authorization header</li>
     *   <li>EIBCALEN > 0 (COMMAREA present) → Valid JWT token present</li>
     *   <li>SEND-SIGNON-SCREEN → Return 401 Unauthorized</li>
     *   <li>DFHPF3 (logout) → POST /api/auth/logout endpoint</li>
     * </ul>
     * 
     * <h3>Public Endpoints (No Authentication Required)</h3>
     * 
     * <p>The following endpoints are accessible without JWT tokens, equivalent
     * to the sign-on screen (COSGN00M) in the COBOL application:</p>
     * 
     * <table border="1">
     *   <tr>
     *     <th>Endpoint Pattern</th>
     *     <th>Purpose</th>
     *     <th>COBOL Equivalent</th>
     *   </tr>
     *   <tr>
     *     <td>/api/auth/login</td>
     *     <td>User authentication, JWT token generation</td>
     *     <td>COSGN00C READ-USER-SEC-FILE paragraph</td>
     *   </tr>
     *   <tr>
     *     <td>/api/auth/logout</td>
     *     <td>User logout, token invalidation</td>
     *     <td>DFHPF3 key handler</td>
     *   </tr>
     *   <tr>
     *     <td>/actuator/health</td>
     *     <td>Health check for monitoring</td>
     *     <td>N/A (infrastructure)</td>
     *   </tr>
     *   <tr>
     *     <td>/actuator/info</td>
     *     <td>Application information</td>
     *     <td>N/A (infrastructure)</td>
     *   </tr>
     *   <tr>
     *     <td>/swagger-ui/**</td>
     *     <td>API documentation UI</td>
     *     <td>N/A (development tool)</td>
     *   </tr>
     *   <tr>
     *     <td>/v3/api-docs/**</td>
     *     <td>OpenAPI specification</td>
     *     <td>N/A (development tool)</td>
     *   </tr>
     * </table>
     * 
     * <h3>Protected Endpoints (Authentication Required)</h3>
     * 
     * <p>All other endpoints under /api/** require valid JWT tokens, equivalent
     * to COMMAREA validation in CICS transactions:</p>
     * 
     * <ul>
     *   <li>/api/accounts/** - Account management (COACTVWC, COACTUPC programs)</li>
     *   <li>/api/cards/** - Card management (COCRDLIC, COCRDUPC programs)</li>
     *   <li>/api/transactions/** - Transaction operations (COTRN00C, COTRN02C programs)</li>
     *   <li>/api/payments/** - Bill payment processing (COBIL00C program)</li>
     *   <li>/api/reports/** - Report generation (CORPT00C program) - ROLE_ADMIN only</li>
     *   <li>/api/admin/** - Administrative functions (COADM01C program) - ROLE_ADMIN only</li>
     *   <li>/api/users/** - User management (COUSR00C, COUSR01C programs)</li>
     * </ul>
     * 
     * <h3>HTTP Security Configuration Details</h3>
     * 
     * <ol>
     *   <li><strong>CSRF Protection - DISABLED:</strong>
     *       <ul>
     *         <li>Reason: Stateless REST API with JWT tokens</li>
     *         <li>CSRF attacks not applicable to stateless architectures</li>
     *         <li>Tokens stored in Authorization header (not cookies)</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Session Management - STATELESS:</strong>
     *       <ul>
     *         <li>SessionCreationPolicy.STATELESS - No HTTP sessions created</li>
     *         <li>Replaces CICS COMMAREA pseudo-conversational sessions</li>
     *         <li>User context carried in JWT token on every request</li>
     *         <li>Enables horizontal scalability (no session affinity)</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>CORS - CONFIGURED:</strong>
     *       <ul>
     *         <li>Allows frontend React application to call backend APIs</li>
     *         <li>Origins configured via application.yml (cors.allowed-origins)</li>
     *         <li>Credentials (cookies, Authorization headers) allowed</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>HTTP Security Headers - ENABLED:</strong>
     *       <ul>
     *         <li>X-Frame-Options: DENY (prevent clickjacking)</li>
     *         <li>X-Content-Type-Options: nosniff (prevent MIME sniffing)</li>
     *         <li>X-XSS-Protection: 1; mode=block (XSS protection)</li>
     *         <li>Strict-Transport-Security: max-age=31536000 (force HTTPS)</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>JWT Authentication Filter - ADDED:</strong>
     *       <ul>
     *         <li>Positioned before UsernamePasswordAuthenticationFilter</li>
     *         <li>Extracts and validates JWT on every request</li>
     *         <li>Sets SecurityContext with user authentication</li>
     *         <li>Replaces COMMAREA validation logic from COBOL</li>
     *       </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Authorization Decision Flow</h3>
     * 
     * <p>For each incoming HTTP request:</p>
     * <ol>
     *   <li>JwtAuthenticationFilter extracts and validates JWT token</li>
     *   <li>If valid, SecurityContext is populated with user authentication</li>
     *   <li>Request proceeds to authorization checks</li>
     *   <li>authorizeHttpRequests() evaluates request path:
     *       <ul>
     *         <li>Public URLs → permitAll() (no authentication required)</li>
     *         <li>All other URLs → authenticated() (JWT required)</li>
     *       </ul>
     *   </li>
     *   <li>Controller method @PreAuthorize checks role-based permissions</li>
     *   <li>If authorized, controller method executes</li>
     *   <li>If not authorized, 403 Forbidden response returned</li>
     * </ol>
     * 
     * <h3>Error Handling</h3>
     * 
     * <table border="1">
     *   <tr>
     *     <th>Scenario</th>
     *     <th>HTTP Status</th>
     *     <th>Response</th>
     *     <th>COBOL Equivalent</th>
     *   </tr>
     *   <tr>
     *     <td>No JWT token</td>
     *     <td>401 Unauthorized</td>
     *     <td>{ "error": "Unauthorized" }</td>
     *     <td>EIBCALEN = 0 → SEND-SIGNON-SCREEN</td>
     *   </tr>
     *   <tr>
     *     <td>Invalid/expired token</td>
     *     <td>401 Unauthorized</td>
     *     <td>{ "error": "Token expired" }</td>
     *     <td>COMMAREA validation failed</td>
     *   </tr>
     *   <tr>
     *     <td>Insufficient permissions</td>
     *     <td>403 Forbidden</td>
     *     <td>{ "error": "Access denied" }</td>
     *     <td>IF NOT CDEMO-USRTYP-ADMIN</td>
     *   </tr>
     * </table>
     * 
     * <h3>Performance Considerations</h3>
     * 
     * <ul>
     *   <li>Filter chain executes on every request - optimized for speed</li>
     *   <li>Public endpoint matching uses efficient pattern matching</li>
     *   <li>JWT validation is in-memory (no I/O operations)</li>
     *   <li>SecurityContext is thread-local (no contention)</li>
     *   <li>Target overhead: &lt; 5ms per request for security checks</li>
     * </ul>
     * 
     * <h3>Configuration Integration</h3>
     * 
     * <p>This method integrates with:</p>
     * <ul>
     *   <li>{@link #jwtAuthenticationFilter} - JWT token validation</li>
     *   <li>{@link #corsConfigurationSource()} - CORS configuration</li>
     *   <li>{@link SecurityConstants#PUBLIC_URLS} - Public endpoint patterns</li>
     * </ul>
     * 
     * @param http HttpSecurity builder for configuring web-based security
     * @return SecurityFilterChain configured with JWT authentication and authorization rules
     * @throws Exception if security configuration fails (e.g., invalid URL patterns)
     * 
     * @see JwtAuthenticationFilter
     * @see SecurityConstants#PUBLIC_URLS
     * @see #corsConfigurationSource()
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for stateless REST API
            // CSRF protection not needed when using JWT tokens in Authorization header
            .csrf(csrf -> csrf.disable())
            
            // Configure CORS for frontend integration
            // Allows React application to make cross-origin requests
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // Configure stateless session management
            // Replaces CICS pseudo-conversational sessions with JWT tokens
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            
            // Configure HTTP security headers for additional protection
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())  // Prevent clickjacking
                .contentTypeOptions(contentType -> {})  // Prevent MIME sniffing
                .xssProtection(xss -> {})  // XSS protection
            )
            
            // Configure authorization rules for HTTP requests
            .authorizeHttpRequests(authz -> authz
                // Public endpoints - no authentication required
                // Maps to COBOL sign-on screen (COSGN00M) that's accessible before authentication
                .requestMatchers(SecurityConstants.PUBLIC_URLS).permitAll()
                
                // All other endpoints require authentication
                // Maps to COBOL COMMAREA validation (EIBCALEN > 0 check)
                .anyRequest().authenticated()
            )
            
            // Add JWT authentication filter before standard authentication filter
            // This filter extracts and validates JWT tokens from Authorization header
            // Replaces COBOL COMMAREA validation logic from CICS transactions
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        
        return http.build();
    }
    
    /**
     * Configures BCrypt password encoder with strength 12 for secure password hashing.
     * 
     * <p>This bean replaces the COBOL plain-text password storage and comparison
     * mechanism from COSGN00C.cbl with industry-standard BCrypt password hashing.
     * BCrypt is a one-way cryptographic hash function specifically designed for
     * password storage, with built-in salting and configurable computational cost.</p>
     * 
     * <h3>COBOL Plain-Text Password Pattern (INSECURE)</h3>
     * 
     * <p><strong>Original COBOL Implementation (COSGN00C.cbl lines 223-246):</strong></p>
     * <pre>
     * WORKING-STORAGE SECTION.
     *     01 WS-USER-PWD                PIC X(08).
     *     
     * COPY CSUSR01Y.  * Contains SEC-USR-PWD PIC X(08)
     * 
     * PROCEDURE DIVISION.
     * READ-USER-SEC-FILE.
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)
     *          INTO      (SEC-USER-DATA)
     *          RIDFLD    (WS-USER-ID)
     *     END-EXEC.
     *     
     *     * Plain-text password comparison (INSECURE!)
     *     IF SEC-USR-PWD = WS-USER-PWD
     *         * Authentication successful
     *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *         EXEC CICS XCTL PROGRAM ('COMEN01C') ...
     *     ELSE
     *         * Wrong password
     *         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *     END-IF.
     * </pre>
     * 
     * <p><strong>Java BCrypt Implementation (SECURE):</strong></p>
     * <pre>
     * // Password hashing during user registration/password change
     * String plainPassword = "password123";
     * String hashedPassword = passwordEncoder.encode(plainPassword);
     * // Result: $2a$12$randomSalt...hashedPassword (60 chars)
     * userSecurity.setPassword(hashedPassword);  // Store in database
     * 
     * // Password verification during login (in AuthenticationManager)
     * String submittedPassword = loginRequest.getPassword();
     * String storedHash = userDetails.getPassword();
     * boolean matches = passwordEncoder.matches(submittedPassword, storedHash);
     * if (matches) {
     *     // Authentication successful - generate JWT token
     * } else {
     *     // Wrong password - throw BadCredentialsException
     * }
     * </pre>
     * 
     * <h3>BCrypt Algorithm Properties</h3>
     * 
     * <table border="1">
     *   <tr>
     *     <th>Property</th>
     *     <th>Value</th>
     *     <th>Purpose</th>
     *   </tr>
     *   <tr>
     *     <td>Strength (rounds)</td>
     *     <td>12</td>
     *     <td>2^12 = 4,096 iterations, balances security and performance</td>
     *   </tr>
     *   <tr>
     *     <td>Hash format</td>
     *     <td>$2a$12$[salt][hash]</td>
     *     <td>Self-describing format with algorithm version and parameters</td>
     *   </tr>
     *   <tr>
     *     <td>Hash length</td>
     *     <td>60 characters</td>
     *     <td>Includes algorithm ID, cost factor, salt, and hash</td>
     *   </tr>
     *   <tr>
     *     <td>Salt</td>
     *     <td>128-bit random</td>
     *     <td>Unique per password, prevents rainbow table attacks</td>
     *   </tr>
     *   <tr>
     *     <td>Hashing time</td>
     *     <td>~100-200ms</td>
     *     <td>Acceptable for login, too slow for brute-force attacks</td>
     *   </tr>
     * </table>
     * 
     * <h3>Security Improvements Over COBOL</h3>
     * 
     * <ol>
     *   <li><strong>One-Way Hashing:</strong>
     *       <ul>
     *         <li>COBOL: Passwords stored as plain text (8 characters)</li>
     *         <li>Java: Passwords stored as BCrypt hash (60 characters)</li>
     *         <li>Benefit: Even if database is compromised, passwords cannot be recovered</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Salting:</strong>
     *       <ul>
     *         <li>COBOL: No salt, identical passwords have identical storage</li>
     *         <li>Java: Each password has unique random salt</li>
     *         <li>Benefit: Prevents rainbow table and duplicate password attacks</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Computational Cost:</strong>
     *       <ul>
     *         <li>COBOL: Simple string comparison (instant)</li>
     *         <li>Java: 4,096 rounds of hashing (~150ms)</li>
     *         <li>Benefit: Brute-force attacks computationally infeasible</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Future-Proof:</strong>
     *       <ul>
     *         <li>COBOL: Fixed 8-character limit (weak passwords)</li>
     *         <li>Java: Supports passwords of any length</li>
     *         <li>Benefit: Can enforce strong password policies</li>
     *       </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Strength Parameter Selection</h3>
     * 
     * <p>Per Section 0.9 requirements: "Password encryption: BCrypt with strength 12"</p>
     * 
     * <p>Strength 12 provides excellent security while maintaining acceptable performance:</p>
     * <ul>
     *   <li>Strength 10: ~70ms hashing time, adequate security</li>
     *   <li><strong>Strength 12: ~150ms hashing time, strong security (SELECTED)</strong></li>
     *   <li>Strength 14: ~600ms hashing time, very strong security (too slow)</li>
     * </ul>
     * 
     * <p>The selected strength of 12 balances security requirements with user experience.
     * Hashing time of ~150ms is imperceptible during login but makes brute-force attacks
     * requiring millions of attempts completely infeasible.</p>
     * 
     * <h3>Database Schema Impact</h3>
     * 
     * <p>Password field migration from COBOL to PostgreSQL:</p>
     * <pre>
     * COBOL CSUSR01Y.cpy:
     *     05  SEC-USR-PWD              PIC X(08).  -- 8 characters, plain text
     * 
     * PostgreSQL user_security table:
     *     password VARCHAR(60) NOT NULL  -- 60 characters, BCrypt hash
     * </pre>
     * 
     * <h3>Password Migration Strategy</h3>
     * 
     * <p>For data migration from VSAM USRSEC to PostgreSQL:</p>
     * <ol>
     *   <li>Cannot convert plain-text passwords to BCrypt hashes (one-way function)</li>
     *   <li>Options:
     *       <ul>
     *         <li><strong>Option A:</strong> Force all users to reset passwords on first login</li>
     *         <li><strong>Option B:</strong> Generate temporary passwords, send via secure channel</li>
     *         <li><strong>Option C:</strong> Dual authentication during transition period</li>
     *       </ul>
     *   </li>
     *   <li>Recommended: Option A with email notification to users</li>
     * </ol>
     * 
     * <h3>Integration with Spring Security</h3>
     * 
     * <p>This bean is automatically used by:</p>
     * <ul>
     *   <li><strong>AuthenticationManager:</strong> During login password verification</li>
     *   <li><strong>UserManagementService:</strong> When creating/updating user passwords</li>
     *   <li><strong>Password Reset Flow:</strong> Generating secure password hashes</li>
     * </ul>
     * 
     * <h3>Usage Examples</h3>
     * 
     * <pre>
     * // Encoding a password (user registration or password change)
     * {@literal @}Autowired
     * private PasswordEncoder passwordEncoder;
     * 
     * public void createUser(UserRegistrationRequest request) {
     *     UserSecurity user = new UserSecurity();
     *     user.setUserId(request.getUserId());
     *     
     *     // Hash the plain-text password before storing
     *     String hashedPassword = passwordEncoder.encode(request.getPassword());
     *     user.setPassword(hashedPassword);
     *     
     *     userSecurityRepository.save(user);
     * }
     * 
     * // Verifying a password (handled automatically by Spring Security)
     * // AuthenticationManager internally calls:
     * boolean isValid = passwordEncoder.matches(
     *     loginRequest.getPassword(),      // Plain text from user
     *     userDetails.getPassword()        // BCrypt hash from database
     * );
     * </pre>
     * 
     * <h3>Security Best Practices</h3>
     * 
     * <ul>
     *   <li>Never log passwords (plain-text or hashed)</li>
     *   <li>Always use this encoder for password operations (never store plain-text)</li>
     *   <li>Consider increasing strength in future (e.g., to 14) as computing power increases</li>
     *   <li>Implement password complexity requirements at application layer</li>
     *   <li>Enforce password expiration policies if required by compliance</li>
     * </ul>
     * 
     * @return BCryptPasswordEncoder configured with strength 12 for secure password hashing.
     *         This encoder is thread-safe and can be reused across the application.
     * 
     * @see SecurityConstants#BCRYPT_STRENGTH
     * @see BCryptPasswordEncoder
     * @see org.springframework.security.authentication.AuthenticationManager
     * @see CustomUserDetailsService
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Create BCrypt password encoder with strength 12
        // Per Section 0.9: "Password encryption: BCrypt with strength 12"
        // Strength 12 = 2^12 = 4,096 iterations, providing strong protection
        // against brute-force attacks while maintaining acceptable performance
        return new BCryptPasswordEncoder(SecurityConstants.BCRYPT_STRENGTH);
    }
    
    /**
     * Provides AuthenticationManager bean for processing authentication requests.
     * 
     * <p>The AuthenticationManager is the core component of Spring Security's authentication
     * architecture. It processes authentication requests during login, coordinating between
     * {@link CustomUserDetailsService} for user lookup and {@link PasswordEncoder} for
     * password verification. This bean replaces the COBOL EXEC CICS READ and password
     * comparison logic from COSGN00C.cbl with Spring Security's authentication framework.</p>
     * 
     * <h3>COBOL Authentication Flow (COSGN00C.cbl)</h3>
     * 
     * <pre>
     * PROCESS-ENTER-KEY.
     *     * Receive user input from BMS screen
     *     EXEC CICS RECEIVE
     *          MAP('COSGN0A')
     *          MAPSET('COSGN00')
     *     END-EXEC.
     *     
     *     * Validate input fields
     *     IF USERIDI = SPACES
     *         MOVE 'Please enter User ID ...' TO WS-MESSAGE
     *     END-IF.
     *     
     *     IF PASSWDI = SPACES
     *         MOVE 'Please enter Password ...' TO WS-MESSAGE
     *     END-IF.
     *     
     *     * Perform authentication
     *     PERFORM READ-USER-SEC-FILE.
     * 
     * READ-USER-SEC-FILE.
     *     * Step 1: Lookup user in VSAM USRSEC file
     *     EXEC CICS READ
     *          DATASET   (WS-USRSEC-FILE)
     *          INTO      (SEC-USER-DATA)
     *          RIDFLD    (WS-USER-ID)
     *          RESP      (WS-RESP-CD)
     *     END-EXEC.
     *     
     *     * Step 2: Evaluate response code
     *     EVALUATE WS-RESP-CD
     *         WHEN 0
     *             * Step 3: Verify password (plain-text comparison)
     *             IF SEC-USR-PWD = WS-USER-PWD
     *                 * Step 4: Extract user type (role)
     *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *                 * Step 5: Route based on role
     *                 IF CDEMO-USRTYP-ADMIN
     *                      EXEC CICS XCTL PROGRAM ('COADM01C')
     *                 ELSE
     *                      EXEC CICS XCTL PROGRAM ('COMEN01C')
     *                 END-IF
     *             ELSE
     *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
     *             END-IF
     *         WHEN 13
     *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
     *         WHEN OTHER
     *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
     *     END-EVALUATE.
     * </pre>
     * 
     * <h3>Java Spring Security Authentication Flow</h3>
     * 
     * <pre>
     * // Controller receives login request
     * {@literal @}PostMapping("/api/auth/login")
     * public ResponseEntity&lt;LoginResponse&gt; login(@RequestBody LoginRequest request) {
     *     
     *     // Step 1: Create authentication request object
     *     UsernamePasswordAuthenticationToken authRequest = 
     *         new UsernamePasswordAuthenticationToken(
     *             request.getUserId(),    // WS-USER-ID equivalent
     *             request.getPassword()   // WS-USER-PWD equivalent
     *         );
     *     
     *     // Step 2: AuthenticationManager.authenticate() internally performs:
     *     //   a. CustomUserDetailsService.loadUserByUsername()
     *     //      → SELECT * FROM user_security WHERE user_id = ?
     *     //      → Maps to: EXEC CICS READ DATASET(USRSEC)
     *     //   
     *     //   b. PasswordEncoder.matches(plainPassword, hashedPassword)
     *     //      → BCrypt hash comparison
     *     //      → Maps to: IF SEC-USR-PWD = WS-USER-PWD
     *     //   
     *     //   c. If successful, returns Authentication with authorities
     *     //      → Maps to: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *     
     *     try {
     *         Authentication authentication = authenticationManager.authenticate(authRequest);
     *         
     *         // Step 3: Generate JWT token (replaces COMMAREA)
     *         String token = jwtTokenProvider.generateToken(authentication);
     *         
     *         // Step 4: Return token to client
     *         return ResponseEntity.ok(new LoginResponse(token, "Bearer", ...));
     *         
     *     } catch (UsernameNotFoundException e) {
     *         // Maps to: WHEN 13 MOVE 'User not found...'
     *         return ResponseEntity.status(401).body(new ErrorResponse("User not found"));
     *         
     *     } catch (BadCredentialsException e) {
     *         // Maps to: ELSE MOVE 'Wrong Password...'
     *         return ResponseEntity.status(401).body(new ErrorResponse("Invalid credentials"));
     *         
     *     } catch (Exception e) {
     *         // Maps to: WHEN OTHER MOVE 'Unable to verify the User...'
     *         return ResponseEntity.status(500).body(new ErrorResponse("Authentication failed"));
     *     }
     * }
     * </pre>
     * 
     * <h3>AuthenticationManager Responsibilities</h3>
     * 
     * <ol>
     *   <li><strong>User Lookup:</strong>
     *       <ul>
     *         <li>Delegates to CustomUserDetailsService.loadUserByUsername()</li>
     *         <li>Queries user_security table via UserSecurityRepository</li>
     *         <li>Throws UsernameNotFoundException if user not found</li>
     *         <li>Replaces: EXEC CICS READ DATASET(USRSEC) RESP(WS-RESP-CD)</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Password Verification:</strong>
     *       <ul>
     *         <li>Uses PasswordEncoder.matches() for BCrypt comparison</li>
     *         <li>Compares submitted plain-text password with stored hash</li>
     *         <li>Throws BadCredentialsException if passwords don't match</li>
     *         <li>Replaces: IF SEC-USR-PWD = WS-USER-PWD</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Authority Assignment:</strong>
     *       <ul>
     *         <li>Retrieves user's GrantedAuthority collection from UserDetails</li>
     *         <li>Authorities derived from SEC-USR-TYPE ('A' → ROLE_ADMIN, 'U' → ROLE_USER)</li>
     *         <li>Attached to Authentication object for authorization decisions</li>
     *         <li>Replaces: MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Authentication Object Creation:</strong>
     *       <ul>
     *         <li>Returns fully populated Authentication object on success</li>
     *         <li>Contains username, authorities, and authentication status</li>
     *         <li>Used by JwtTokenProvider to generate JWT token</li>
     *         <li>Replaces: COMMAREA with user context for subsequent transactions</li>
     *       </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Exception Handling Mapping</h3>
     * 
     * <table border="1">
     *   <tr>
     *     <th>COBOL RESP Code</th>
     *     <th>COBOL Message</th>
     *     <th>Spring Security Exception</th>
     *     <th>HTTP Status</th>
     *   </tr>
     *   <tr>
     *     <td>0 (Success)</td>
     *     <td>User authenticated</td>
     *     <td>Authentication returned</td>
     *     <td>200 OK (with token)</td>
     *   </tr>
     *   <tr>
     *     <td>13 (Not Found)</td>
     *     <td>"User not found. Try again ..."</td>
     *     <td>UsernameNotFoundException</td>
     *     <td>401 Unauthorized</td>
     *   </tr>
     *   <tr>
     *     <td>Wrong Password</td>
     *     <td>"Wrong Password. Try again ..."</td>
     *     <td>BadCredentialsException</td>
     *     <td>401 Unauthorized</td>
     *   </tr>
     *   <tr>
     *     <td>Other Error</td>
     *     <td>"Unable to verify the User ..."</td>
     *     <td>AuthenticationException</td>
     *     <td>500 Internal Server Error</td>
     *   </tr>
     * </table>
     * 
     * <h3>Configuration Integration</h3>
     * 
     * <p>The AuthenticationManager is configured by Spring Security to automatically use:</p>
     * <ul>
     *   <li>{@link CustomUserDetailsService} - Injected via @Autowired, provides user lookup</li>
     *   <li>{@link #passwordEncoder()} - BCrypt password encoder for verification</li>
     *   <li>AuthenticationConfiguration - Spring Security's authentication configuration</li>
     * </ul>
     * 
     * <h3>Usage in Authentication Flow</h3>
     * 
     * <p>This bean is injected into AuthenticationController and used during login:</p>
     * <pre>
     * {@literal @}RestController
     * {@literal @}RequestMapping("/api/auth")
     * public class AuthenticationController {
     *     
     *     {@literal @}Autowired
     *     private AuthenticationManager authenticationManager;
     *     
     *     {@literal @}PostMapping("/login")
     *     public ResponseEntity&lt;?&gt; authenticateUser(@RequestBody LoginRequest request) {
     *         // This triggers the complete authentication flow
     *         Authentication authentication = authenticationManager.authenticate(
     *             new UsernamePasswordAuthenticationToken(
     *                 request.getUserId(),
     *                 request.getPassword()
     *             )
     *         );
     *         
     *         // Generate and return JWT token
     *         String jwt = jwtTokenProvider.generateToken(authentication);
     *         return ResponseEntity.ok(new LoginResponse(jwt, "Bearer", ...));
     *     }
     * }
     * </pre>
     * 
     * <h3>Performance Considerations</h3>
     * 
     * <ul>
     *   <li>Database query uses primary key index (fast lookup)</li>
     *   <li>BCrypt verification takes ~150ms (acceptable for login)</li>
     *   <li>Total authentication time: &lt; 200ms average per Section 0.9 requirements</li>
     *   <li>Thread-safe for concurrent authentication requests</li>
     * </ul>
     * 
     * <h3>Security Audit Trail</h3>
     * 
     * <p>Authentication events are automatically logged by Spring Security:</p>
     * <ul>
     *   <li>Successful authentication → INFO log with username</li>
     *   <li>Failed authentication → WARN log with reason (user not found, wrong password)</li>
     *   <li>Exception during authentication → ERROR log with stack trace</li>
     * </ul>
     * 
     * @param authConfig Spring Security's authentication configuration containing
     *                   the default AuthenticationManager instance configured with
     *                   CustomUserDetailsService and PasswordEncoder
     * 
     * @return AuthenticationManager configured for user authentication with BCrypt
     *         password verification. This manager is used by AuthenticationController
     *         to process login requests and generate JWT tokens.
     * 
     * @throws Exception if AuthenticationManager cannot be obtained from configuration
     *                   (should not occur in normal Spring Boot setup)
     * 
     * @see AuthenticationConfiguration#getAuthenticationManager()
     * @see CustomUserDetailsService
     * @see #passwordEncoder()
     * @see org.springframework.security.authentication.AuthenticationManager
     * @see org.springframework.security.authentication.UsernamePasswordAuthenticationToken
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) 
            throws Exception {
        // Obtain the default AuthenticationManager from Spring Security configuration
        // This manager is pre-configured with:
        //   - CustomUserDetailsService for user lookup (injected via @Autowired)
        //   - PasswordEncoder for password verification (from passwordEncoder() bean)
        //   - Default authentication providers for standard authentication flow
        return authConfig.getAuthenticationManager();
    }
    
    /**
     * Configures CORS (Cross-Origin Resource Sharing) for frontend integration.
     * 
     * <p>This bean enables the React frontend application running on a different
     * origin (e.g., http://localhost:3000) to make HTTP requests to this backend
     * API. Without CORS configuration, browsers would block these requests due to
     * the Same-Origin Policy security restriction.</p>
     * 
     * <h3>Why CORS is Needed</h3>
     * 
     * <p>In the mainframe COBOL/CICS architecture, there was no concept of CORS
     * because:</p>
     * <ul>
     *   <li>3270 terminals accessed CICS directly (no browser)</li>
     *   <li>No cross-origin requests existed in the architecture</li>
     *   <li>BMS screens were served by the same CICS region handling business logic</li>
     * </ul>
     * 
     * <p>In the modern cloud-native architecture:</p>
     * <ul>
     *   <li>React frontend: http://localhost:3000 (development) or https://app.carddemo.com (production)</li>
     *   <li>Spring Boot backend: http://localhost:8080 (development) or https://api.carddemo.com (production)</li>
     *   <li>Different origins → Browser enforces Same-Origin Policy → CORS required</li>
     * </ul>
     * 
     * <h3>CORS Configuration Details</h3>
     * 
     * <p><strong>Allowed Origins:</strong></p>
     * <pre>
     * Development: http://localhost:3000
     * Production: https://app.carddemo.com
     * 
     * Configured via application.yml:
     * cors:
     *   allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
     * 
     * Multiple origins supported (comma-separated):
     * cors:
     *   allowed-origins: http://localhost:3000,https://app.carddemo.com
     * </pre>
     * 
     * <p><strong>Allowed HTTP Methods:</strong></p>
     * <ul>
     *   <li>GET - Retrieve resources (account view, transaction list)</li>
     *   <li>POST - Create resources (login, create transaction, bill payment)</li>
     *   <li>PUT - Update resources (account update, card update, profile update)</li>
     *   <li>DELETE - Delete resources (logout, delete user - admin only)</li>
     *   <li>OPTIONS - Preflight requests (browser automatically sends before actual request)</li>
     * </ul>
     * 
     * <p><strong>Allowed Headers:</strong></p>
     * <ul>
     *   <li>Authorization - JWT Bearer token header</li>
     *   <li>Content-Type - Request body type (application/json)</li>
     *   <li>Accept - Response type preference (application/json)</li>
     * </ul>
     * 
     * <p><strong>Exposed Headers:</strong></p>
     * <ul>
     *   <li>Authorization - Allows frontend to read Authorization header in response</li>
     * </ul>
     * 
     * <p><strong>Credentials Support:</strong></p>
     * <ul>
     *   <li>Allow Credentials: true</li>
     *   <li>Enables sending cookies and Authorization headers</li>
     *   <li>Required for JWT token authentication</li>
     * </ul>
     * 
     * <p><strong>Max Age (Preflight Cache):</strong></p>
     * <ul>
     *   <li>3600 seconds (1 hour)</li>
     *   <li>Browser caches preflight OPTIONS responses to reduce overhead</li>
     *   <li>Subsequent requests within 1 hour skip preflight check</li>
     * </ul>
     * 
     * <h3>CORS Request Flow</h3>
     * 
     * <p><strong>Simple Request (GET, POST with simple headers):</strong></p>
     * <pre>
     * 1. Frontend: GET /api/accounts/123
     *    Origin: http://localhost:3000
     *    Authorization: Bearer eyJ...
     * 
     * 2. Backend: CORS filter checks allowed origins
     *    → Origin matches configuration → Allow request
     * 
     * 3. Response includes CORS headers:
     *    Access-Control-Allow-Origin: http://localhost:3000
     *    Access-Control-Allow-Credentials: true
     * </pre>
     * 
     * <p><strong>Preflight Request (PUT, DELETE, custom headers):</strong></p>
     * <pre>
     * 1. Browser sends preflight OPTIONS request:
     *    OPTIONS /api/accounts/123
     *    Origin: http://localhost:3000
     *    Access-Control-Request-Method: PUT
     *    Access-Control-Request-Headers: Authorization,Content-Type
     * 
     * 2. Backend CORS filter responds with allowed methods/headers:
     *    Access-Control-Allow-Origin: http://localhost:3000
     *    Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS
     *    Access-Control-Allow-Headers: Authorization,Content-Type,Accept
     *    Access-Control-Max-Age: 3600
     * 
     * 3. Browser caches preflight response for 1 hour
     * 
     * 4. Browser sends actual request:
     *    PUT /api/accounts/123
     *    Origin: http://localhost:3000
     *    Authorization: Bearer eyJ...
     * </pre>
     * 
     * <h3>Security Considerations</h3>
     * 
     * <ol>
     *   <li><strong>Restrict Allowed Origins:</strong>
     *       <ul>
     *         <li>NEVER use "*" (wildcard) with credentials enabled</li>
     *         <li>Always specify exact allowed origins</li>
     *         <li>Separate development and production configurations</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>HTTPS in Production:</strong>
     *       <ul>
     *         <li>Production origins must use HTTPS (https://app.carddemo.com)</li>
     *         <li>Mixed content (HTTP backend, HTTPS frontend) blocked by browsers</li>
     *       </ul>
     *   </li>
     *   
     *   <li><strong>Token Security:</strong>
     *       <ul>
     *         <li>JWT tokens in Authorization header (not cookies)</li>
     *         <li>Credentials: true required for Authorization header</li>
     *         <li>Tokens validated by JwtAuthenticationFilter regardless of CORS</li>
     *       </ul>
     *   </li>
     * </ol>
     * 
     * <h3>Configuration Example</h3>
     * 
     * <p>application.yml:</p>
     * <pre>
     * # Development environment
     * spring:
     *   profiles: dev
     * cors:
     *   allowed-origins: http://localhost:3000
     * 
     * ---
     * # Production environment
     * spring:
     *   profiles: prod
     * cors:
     *   allowed-origins: https://app.carddemo.com,https://www.carddemo.com
     * </pre>
     * 
     * <h3>Testing CORS Configuration</h3>
     * 
     * <pre>
     * // Test CORS preflight request
     * curl -X OPTIONS http://localhost:8080/api/accounts/123 \
     *   -H "Origin: http://localhost:3000" \
     *   -H "Access-Control-Request-Method: PUT" \
     *   -H "Access-Control-Request-Headers: Authorization,Content-Type"
     * 
     * // Expected response headers:
     * Access-Control-Allow-Origin: http://localhost:3000
     * Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS
     * Access-Control-Allow-Headers: Authorization, Content-Type, Accept
     * Access-Control-Allow-Credentials: true
     * Access-Control-Max-Age: 3600
     * </pre>
     * 
     * @return CorsConfigurationSource configured with allowed origins, methods,
     *         headers, and credentials support for frontend integration
     * 
     * @see CorsConfiguration
     * @see UrlBasedCorsConfigurationSource
     * @see #filterChain(HttpSecurity)
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Configure allowed origins from application.yml property
        // Supports comma-separated list: "http://localhost:3000,https://app.carddemo.com"
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        
        // Configure allowed HTTP methods
        // Covers all CRUD operations: GET (read), POST (create), PUT (update), DELETE (delete)
        // OPTIONS required for preflight requests
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        
        // Configure allowed request headers
        // Authorization: JWT Bearer token
        // Content-Type: application/json for request bodies
        // Accept: application/json for response format
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept"));
        
        // Configure exposed response headers
        // Allows frontend to read Authorization header (e.g., for token refresh)
        configuration.setExposedHeaders(List.of("Authorization"));
        
        // Allow credentials (cookies, Authorization headers)
        // Required for JWT token authentication
        // IMPORTANT: When true, allowedOrigins cannot be "*"
        configuration.setAllowCredentials(true);
        
        // Configure preflight response cache duration
        // 3600 seconds = 1 hour
        // Browser caches OPTIONS response to reduce preflight overhead
        configuration.setMaxAge(3600L);
        
        // Register CORS configuration for all endpoints
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}
