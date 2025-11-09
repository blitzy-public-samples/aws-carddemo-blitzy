/*
 * SecurityConstants.java
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * CardDemo - Spring Boot Microservices Migration
 * 
 * JWT and security configuration constants class defining token management 
 * parameters for stateless authentication replacing mainframe RACF security.
 * 
 * This class provides centralized constant definitions used by:
 * - JwtAuthenticationFilter for token extraction and validation
 * - JwtService for token generation and parsing
 * - SecurityConfig for security filter chain configuration
 * - CustomUserDetailsService for role mapping
 *
 * Replaces: RACF security infrastructure and COBOL COSGN00C sign-on program
 * Source References: 
 *   - app/cbl/COSGN00C.cbl (CICS sign-on transaction processing)
 *   - app/cpy/CSUSR01Y.cpy (user security data structure)
 *   - app/cpy/COCOM01Y.cpy (COMMAREA with user type definitions)
 *
 * Role Mapping from COBOL to Spring Security:
 *   COBOL: CDEMO-USRTYP-ADMIN VALUE 'A' → Spring: ROLE_ADMIN
 *   COBOL: CDEMO-USRTYP-USER VALUE 'U'  → Spring: ROLE_USER
 */
package com.carddemo.security;

/**
 * SecurityConstants class contains all JWT and security-related constant definitions
 * for the CardDemo application's stateless authentication mechanism.
 * 
 * <p>This class replaces the mainframe RACF security infrastructure with Spring Security
 * JWT token-based authentication, maintaining the two-tier role model from the original
 * COBOL application while enabling modern REST API authentication patterns.</p>
 * 
 * <p>All constants are declared as public static final to ensure immutability,
 * compile-time optimization, and centralized security configuration accessible
 * throughout the application.</p>
 *
 * @version 1.0
 * @since 2024
 */
public final class SecurityConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     * This class should only be used for its static constant members.
     */
    private SecurityConstants() {
        throw new UnsupportedOperationException("SecurityConstants is a utility class and cannot be instantiated");
    }

    /**
     * JWT_SECRET_KEY - Placeholder for externalized secret key configuration.
     * 
     * <p>The actual secret key value is retrieved from application.properties using
     * the property key "jwt.secret". This approach enables secure key management and
     * rotation without code changes, following Spring Boot externalized configuration
     * best practices.</p>
     * 
     * <p>In production environments, this value should be:</p>
     * <ul>
     *   <li>At least 256 bits (32 characters) for HS256 algorithm</li>
     *   <li>Stored in environment variables or secure vault systems</li>
     *   <li>Never committed to version control</li>
     *   <li>Rotated periodically per security policy</li>
     * </ul>
     * 
     * <p>Configuration example in application.properties:</p>
     * <pre>
     * jwt.secret=${JWT_SECRET:defaultSecretKeyForDevelopmentOnly}
     * </pre>
     * 
     * @see com.carddemo.service.auth.JwtService#generateToken(org.springframework.security.core.userdetails.UserDetails)
     */
    public static final String JWT_SECRET_KEY = "${jwt.secret}";

    /**
     * JWT_EXPIRATION_MS - JWT token expiration time in milliseconds.
     * 
     * <p>Set to 86400000 milliseconds (24 hours), providing reasonable token lifetime
     * that balances security concerns (automatic session expiration) with user experience
     * (avoiding frequent re-authentication).</p>
     * 
     * <p>This duration matches typical mainframe CICS session patterns where users would
     * remain authenticated throughout a business day. After 24 hours, users must
     * re-authenticate by providing credentials again.</p>
     * 
     * <p>Token expiration enforces:</p>
     * <ul>
     *   <li>Automatic logout after 24 hours of inactivity</li>
     *   <li>Reduced window for token theft exploitation</li>
     *   <li>Regular credential validation</li>
     *   <li>Compliance with security policies requiring session timeouts</li>
     * </ul>
     * 
     * <p>Calculation: 24 hours × 60 minutes × 60 seconds × 1000 milliseconds = 86400000</p>
     * 
     * @see com.carddemo.service.auth.JwtService#generateToken(org.springframework.security.core.userdetails.UserDetails)
     */
    public static final long JWT_EXPIRATION_MS = 86400000L; // 24 hours

    /**
     * TOKEN_PREFIX - Bearer token prefix for HTTP Authorization header.
     * 
     * <p>Set to "Bearer " (with trailing space) conforming to RFC 6750 OAuth 2.0 Bearer
     * Token Usage specification. This prefix identifies the authentication scheme used
     * in the HTTP Authorization header.</p>
     * 
     * <p>Standard format: {@code Authorization: Bearer <token>}</p>
     * 
     * <p>Example usage:</p>
     * <pre>
     * Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
     * </pre>
     * 
     * <p>The Bearer authentication scheme is widely adopted for REST APIs and indicates
     * that the bearer of the token is authorized to access protected resources.</p>
     * 
     * @see <a href="https://tools.ietf.org/html/rfc6750">RFC 6750 - OAuth 2.0 Bearer Token Usage</a>
     * @see com.carddemo.security.JwtAuthenticationFilter#doFilterInternal
     */
    public static final String TOKEN_PREFIX = "Bearer ";

    /**
     * HEADER_STRING - HTTP header name for JWT token transmission.
     * 
     * <p>Set to "Authorization", the standard HTTP header name for transmitting
     * authentication credentials from client to server. This header is used in
     * all REST API requests requiring authentication.</p>
     * 
     * <p>Client applications (React frontend, mobile apps, third-party integrations)
     * must include this header with the JWT token:</p>
     * <pre>
     * fetch('/api/accounts', {
     *   headers: {
     *     'Authorization': 'Bearer ' + jwtToken
     *   }
     * });
     * </pre>
     * 
     * <p>The JwtAuthenticationFilter extracts the token from this header, validates it,
     * and establishes the security context for the request.</p>
     * 
     * @see com.carddemo.security.JwtAuthenticationFilter#doFilterInternal
     */
    public static final String HEADER_STRING = "Authorization";

    /**
     * AUTHORITIES_KEY - JWT claim name for storing user authorities/roles.
     * 
     * <p>Set to "roles", this constant defines the claim name within the JWT payload
     * where user authorities are stored. This enables role-based access control (RBAC)
     * by embedding user roles directly in the token.</p>
     * 
     * <p>Role mapping from COBOL user types to Spring Security roles:</p>
     * <ul>
     *   <li>COBOL 'A' (CDEMO-USRTYP-ADMIN) → Spring ROLE_ADMIN</li>
     *   <li>COBOL 'U' (CDEMO-USRTYP-USER) → Spring ROLE_USER</li>
     * </ul>
     * 
     * <p>JWT payload example:</p>
     * <pre>
     * {
     *   "sub": "user123",
     *   "roles": ["ROLE_ADMIN"],
     *   "iat": 1609459200,
     *   "exp": 1609545600
     * }
     * </pre>
     * 
     * <p>This preserves the two-tier role-based access control model from the mainframe
     * RACF security system, where administrative users have elevated privileges for
     * user management operations.</p>
     * 
     * @see com.carddemo.service.auth.JwtService#generateToken(org.springframework.security.core.userdetails.UserDetails)
     * @see com.carddemo.security.CustomUserDetailsService#loadUserByUsername(String)
     */
    public static final String AUTHORITIES_KEY = "roles";

    /**
     * TOKEN_TYPE - Token type identifier for authentication responses.
     * 
     * <p>Set to "JWT", this constant identifies the token type in authentication
     * response payloads. It provides explicit indication that the returned token
     * is a JSON Web Token, as opposed to other token formats (e.g., opaque tokens,
     * SAML tokens).</p>
     * 
     * <p>Used in login response DTOs:</p>
     * <pre>
     * {
     *   "tokenType": "JWT",
     *   "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
     *   "expiresIn": 86400000
     * }
     * </pre>
     * 
     * <p>This helps client applications understand how to handle and store the token,
     * and which authentication scheme to use when making subsequent API requests.</p>
     * 
     * @see com.carddemo.dto.response.LoginResponse
     * @see com.carddemo.controller.AuthController#login
     */
    public static final String TOKEN_TYPE = "JWT";
}
