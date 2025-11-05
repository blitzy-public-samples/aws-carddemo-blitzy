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

package com.carddemo.security;

/**
 * Security-related constants for the CardDemo application.
 * 
 * This class centralizes all security configuration values including:
 * - JWT token configuration (expiration, headers, claims)
 * - Spring Security role definitions
 * - COBOL user type mappings from legacy mainframe system
 * - Authentication endpoint paths
 * - Password encoding configuration
 * 
 * <p>Purpose: Eliminate magic strings throughout security components and provide
 * a single source of truth for security configuration.</p>
 * 
 * <p>COBOL Mappings:</p>
 * <ul>
 *   <li>From COCOM01Y.cpy: CDEMO-USER-TYPE with 88-level conditions</li>
 *   <li>CDEMO-USRTYP-ADMIN VALUE 'A' → ROLE_ADMIN</li>
 *   <li>CDEMO-USRTYP-USER VALUE 'U' → ROLE_USER</li>
 *   <li>From CSUSR01Y.cpy: SEC-USR-TYPE PIC X(01)</li>
 * </ul>
 * 
 * <p>This class cannot be instantiated - all members are static constants.</p>
 * 
 * @see com.carddemo.security.JwtTokenProvider
 * @see com.carddemo.security.JwtAuthenticationFilter
 * @see com.carddemo.security.CustomUserDetailsService
 */
public final class SecurityConstants {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     * 
     * @throws UnsupportedOperationException if instantiation is attempted
     */
    private SecurityConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
    
    // ============================================================================
    // JWT Token Configuration Constants
    // ============================================================================
    
    /**
     * Default JWT secret key for token signing.
     * 
     * <p>This is a fallback value that should be overridden in application.yml
     * for production environments. The key must be at least 256 bits (32 characters)
     * for HS512 algorithm compatibility.</p>
     * 
     * <p><strong>Security Note:</strong> Never use this default in production.
     * Configure via environment variable or external secrets management.</p>
     * 
     * <p>Configuration in application.yml:</p>
     * <pre>
     * app:
     *   jwt:
     *     secret: ${JWT_SECRET:&lt;fallback-to-this-default&gt;}
     * </pre>
     */
    public static final String JWT_SECRET_DEFAULT = 
        "CardDemo2024SecretKeyForJWTTokenGeneration!MustBe256BitsForHS512";
    
    /**
     * JWT token expiration time in milliseconds.
     * 
     * <p>Value: 86400000 milliseconds = 24 hours</p>
     * 
     * <p>Per requirements: JWT token expiration set to 24 hours matching
     * CICS session timeout semantics from mainframe implementation.</p>
     */
    public static final long JWT_EXPIRATION_MS = 86400000L;  // 24 * 60 * 60 * 1000
    
    /**
     * JWT token expiration time in seconds.
     * 
     * <p>Value: 86400 seconds = 24 hours</p>
     * 
     * <p>Used for display purposes, logging, and external API responses
     * where millisecond precision is not required.</p>
     */
    public static final long JWT_EXPIRATION_SECONDS = 86400L;  // 24 hours
    
    // ============================================================================
    // HTTP Header and Token Constants
    // ============================================================================
    
    /**
     * Authorization HTTP header name.
     * 
     * <p>Standard HTTP header used to transmit authentication credentials.
     * JWT tokens are extracted from this header by JwtAuthenticationFilter.</p>
     * 
     * <p>Example usage in HTTP request:</p>
     * <pre>
     * Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
     * </pre>
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    
    /**
     * Bearer token prefix.
     * 
     * <p>JWT tokens must be prefixed with "Bearer " (note the trailing space)
     * according to RFC 6750 - The OAuth 2.0 Authorization Framework: Bearer Token Usage.</p>
     * 
     * <p>The prefix is stripped before token validation:</p>
     * <pre>
     * String header = request.getHeader(AUTHORIZATION_HEADER);
     * if (header != null && header.startsWith(TOKEN_PREFIX)) {
     *     String jwt = header.substring(TOKEN_PREFIX.length());
     * }
     * </pre>
     */
    public static final String TOKEN_PREFIX = "Bearer ";
    
    /**
     * Token type identifier.
     * 
     * <p>Indicates the authentication scheme being used. Always "Bearer"
     * for JWT token-based authentication in this application.</p>
     * 
     * <p>Used in login response to inform clients of the expected token format.</p>
     */
    public static final String TOKEN_TYPE = "Bearer";
    
    // ============================================================================
    // Spring Security Role Constants
    // ============================================================================
    
    /**
     * Spring Security role for regular users.
     * 
     * <p>Maps from COBOL user type codes:</p>
     * <ul>
     *   <li>COCOM01Y.cpy: CDEMO-USRTYP-USER VALUE 'U'</li>
     *   <li>Requirements also specify: 'R' for Regular User</li>
     * </ul>
     * 
     * <p>Used in method-level security annotations:</p>
     * <pre>
     * {@literal @}PreAuthorize("hasRole('USER')")
     * public AccountViewResponse viewAccount(Long accountId) {
     *     // Regular user operations
     * }
     * </pre>
     * 
     * <p>Grants access to:</p>
     * <ul>
     *   <li>View own account information</li>
     *   <li>Update own profile</li>
     *   <li>View card details</li>
     *   <li>View transaction history</li>
     *   <li>Make bill payments</li>
     * </ul>
     */
    public static final String ROLE_USER = "ROLE_USER";
    
    /**
     * Spring Security role for administrative users.
     * 
     * <p>Maps from COBOL user type code:</p>
     * <ul>
     *   <li>COCOM01Y.cpy: CDEMO-USRTYP-ADMIN VALUE 'A'</li>
     * </ul>
     * 
     * <p>Used in method-level security annotations:</p>
     * <pre>
     * {@literal @}PreAuthorize("hasRole('ADMIN')")
     * public void deleteUser(Long userId) {
     *     // Administrative operations only
     * }
     * </pre>
     * 
     * <p>Grants access to all ROLE_USER operations plus:</p>
     * <ul>
     *   <li>User management (create, update, delete users)</li>
     *   <li>View any customer/account information</li>
     *   <li>Update any account/card status</li>
     *   <li>Generate system reports</li>
     *   <li>Access administrative functions</li>
     * </ul>
     */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    
    // ============================================================================
    // COBOL User Type Mapping Constants
    // ============================================================================
    
    /**
     * COBOL user type code for administrative users.
     * 
     * <p>Source mappings:</p>
     * <ul>
     *   <li>COCOM01Y.cpy: 88 CDEMO-USRTYP-ADMIN VALUE 'A'</li>
     *   <li>CSUSR01Y.cpy: SEC-USR-TYPE PIC X(01)</li>
     * </ul>
     * 
     * <p>This character code is stored in the VSAM USRSEC file and used
     * to determine user authorization level during authentication.</p>
     * 
     * <p>Transformation logic in CustomUserDetailsService:</p>
     * <pre>
     * if (USER_TYPE_ADMIN.equals(userType)) {
     *     authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
     * }
     * </pre>
     */
    public static final String USER_TYPE_ADMIN = "A";
    
    /**
     * COBOL user type code for regular users.
     * 
     * <p>Source mappings:</p>
     * <ul>
     *   <li>COCOM01Y.cpy: 88 CDEMO-USRTYP-USER VALUE 'U'</li>
     *   <li>CSUSR01Y.cpy: SEC-USR-TYPE PIC X(01)</li>
     * </ul>
     * 
     * <p>This character code is stored in the VSAM USRSEC file for
     * non-administrative users.</p>
     * 
     * <p>Transformation logic in CustomUserDetailsService:</p>
     * <pre>
     * if (USER_TYPE_USER.equals(userType) || USER_TYPE_REGULAR.equals(userType)) {
     *     authorities.add(new SimpleGrantedAuthority(ROLE_USER));
     * }
     * </pre>
     */
    public static final String USER_TYPE_USER = "U";
    
    /**
     * Alternative COBOL user type code for regular users.
     * 
     * <p>Per Section 0.9 requirements: 'R' for Regular User → ROLE_USER</p>
     * 
     * <p>This provides backward compatibility with mainframe systems that
     * may use 'R' instead of 'U' to denote regular user status.</p>
     * 
     * <p>Both 'U' and 'R' codes are treated identically and mapped to ROLE_USER.</p>
     */
    public static final String USER_TYPE_REGULAR = "R";
    
    // ============================================================================
    // JWT Claims Constants
    // ============================================================================
    
    /**
     * Custom JWT claim name for user roles.
     * 
     * <p>This claim contains a comma-separated string of Spring Security role names.</p>
     * 
     * <p>Example JWT payload:</p>
     * <pre>
     * {
     *   "sub": "USER0001",
     *   "roles": "ROLE_USER",
     *   "iat": 1672531200,
     *   "exp": 1672617600
     * }
     * </pre>
     * 
     * <p>For administrative users:</p>
     * <pre>
     * {
     *   "sub": "ADMIN001",
     *   "roles": "ROLE_ADMIN",
     *   "iat": 1672531200,
     *   "exp": 1672617600
     * }
     * </pre>
     */
    public static final String ROLES_CLAIM = "roles";
    
    /**
     * JWT subject claim (standard claim name).
     * 
     * <p>Contains the username (user ID) from COBOL CDEMO-USER-ID field.
     * This is a standard JWT claim defined in RFC 7519.</p>
     * 
     * <p>Maps from:</p>
     * <ul>
     *   <li>COCOM01Y.cpy: CDEMO-USER-ID PIC X(08)</li>
     *   <li>CSUSR01Y.cpy: SEC-USR-ID PIC X(08)</li>
     * </ul>
     * 
     * <p>The subject uniquely identifies the authenticated user across all requests.</p>
     */
    public static final String USERNAME_CLAIM = "sub";
    
    // ============================================================================
    // Authentication Endpoint Constants
    // ============================================================================
    
    /**
     * Login endpoint path.
     * 
     * <p>POST endpoint that accepts username and password credentials
     * and returns a JWT token upon successful authentication.</p>
     * 
     * <p>Maps from CICS transaction CC00 (Sign-on).</p>
     * 
     * <p>Request format:</p>
     * <pre>
     * POST /api/auth/login
     * {
     *   "userId": "USER0001",
     *   "password": "password"
     * }
     * </pre>
     * 
     * <p>Response format:</p>
     * <pre>
     * {
     *   "token": "eyJhbGciOiJIUzUxMiJ9...",
     *   "type": "Bearer",
     *   "userId": "USER0001",
     *   "userName": "John Doe",
     *   "userType": "U"
     * }
     * </pre>
     */
    public static final String LOGIN_URL = "/api/auth/login";
    
    /**
     * Logout endpoint path.
     * 
     * <p>POST endpoint that invalidates the current user's session.
     * In a stateless JWT architecture, this primarily serves to clear
     * client-side token storage.</p>
     * 
     * <p>For enhanced security with Redis session management, this endpoint
     * can blacklist tokens until expiration.</p>
     */
    public static final String LOGOUT_URL = "/api/auth/logout";
    
    /**
     * Public endpoints that don't require authentication.
     * 
     * <p>These URL patterns are excluded from JWT token validation
     * in Spring Security configuration.</p>
     * 
     * <p>Patterns use Ant-style path matching:</p>
     * <ul>
     *   <li>** matches any number of path segments</li>
     *   <li>* matches a single path segment</li>
     * </ul>
     * 
     * <p>Included endpoints:</p>
     * <ul>
     *   <li>/api/auth/** - Authentication endpoints (login, logout)</li>
     *   <li>/actuator/health - Health check for monitoring systems</li>
     *   <li>/swagger-ui/** - API documentation UI</li>
     *   <li>/api-docs/** - OpenAPI specification endpoints</li>
     *   <li>/v3/api-docs/** - OpenAPI 3.0 specification</li>
     * </ul>
     */
    public static final String[] PUBLIC_URLS = {
        "/api/auth/**",
        "/actuator/health",
        "/swagger-ui/**",
        "/api-docs/**",
        "/v3/api-docs/**"
    };
    
    // ============================================================================
    // Password Encoding Constants
    // ============================================================================
    
    /**
     * BCrypt password encoding strength.
     * 
     * <p>Per requirements: BCrypt with strength 12</p>
     * 
     * <p>The strength parameter (also called "rounds" or "log rounds") determines
     * the computational cost of hashing. Higher values increase security but
     * also increase password hashing time.</p>
     * 
     * <p>Strength 12 represents 2^12 = 4,096 iterations, providing strong
     * protection against brute-force attacks while maintaining acceptable
     * performance for authentication operations.</p>
     * 
     * <p>Used in SecurityConfig:</p>
     * <pre>
     * {@literal @}Bean
     * public PasswordEncoder passwordEncoder() {
     *     return new BCryptPasswordEncoder(SecurityConstants.BCRYPT_STRENGTH);
     * }
     * </pre>
     * 
     * <p>Replaces COBOL plain-text password storage (CSUSR01Y.cpy: SEC-USR-PWD)
     * with secure one-way hashing.</p>
     */
    public static final int BCRYPT_STRENGTH = 12;
    
    // ============================================================================
    // Session Configuration Constants
    // ============================================================================
    
    /**
     * Session creation policy for Spring Security.
     * 
     * <p>Value: "STATELESS" - No HTTP session created, JWT-only authentication</p>
     * 
     * <p>This replaces CICS pseudo-conversational session management with
     * stateless REST API architecture. User context is transmitted via JWT
     * token on every request rather than maintained in server-side sessions.</p>
     * 
     * <p>Benefits of stateless architecture:</p>
     * <ul>
     *   <li>Horizontal scalability - no session affinity required</li>
     *   <li>No session storage overhead</li>
     *   <li>Simplified load balancing</li>
     *   <li>Cloud-native deployment compatibility</li>
     * </ul>
     * 
     * <p>Note: Redis-based session storage is still available for advanced
     * use cases like token blacklisting, but primary authentication mechanism
     * is stateless JWT.</p>
     */
    public static final String SESSION_CREATION_POLICY = "STATELESS";
}
