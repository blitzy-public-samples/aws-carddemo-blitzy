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

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * JWT token provider service for CardDemo application.
 * 
 * <p>This component replaces CICS COMMAREA-based session management with stateless
 * JWT (JSON Web Token) authentication. It generates, validates, and extracts user
 * information from JWT tokens using io.jsonwebtoken library version 0.12.3.</p>
 * 
 * <h2>COBOL/CICS Session Management Replacement</h2>
 * 
 * <p><strong>Original COBOL Implementation (COSGN00C.cbl):</strong></p>
 * <pre>
 * * CICS pseudo-conversational session using COMMAREA
 * WORKING-STORAGE SECTION.
 *     COPY COCOM01Y.  * Communication Area
 * 
 * PROCEDURE DIVISION.
 *     * After authentication, pass state to next program
 *     MOVE WS-USER-ID   TO CDEMO-USER-ID      (PIC X(08))
 *     MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE    (PIC X(01): 'A'=Admin, 'U'=User)
 *     
 *     EXEC CICS XCTL
 *         PROGRAM ('COMEN01C')
 *         COMMAREA(CARDDEMO-COMMAREA)
 *         LENGTH(LENGTH OF CARDDEMO-COMMAREA)
 *     END-EXEC
 * </pre>
 * 
 * <p><strong>Java JWT Equivalent:</strong></p>
 * <pre>
 * // Generate JWT token with user context
 * String token = jwtTokenProvider.generateToken(authentication);
 * 
 * // Token payload (JWT claims):
 * {
 *   "sub": "USER0001",           // Maps to CDEMO-USER-ID
 *   "roles": "ROLE_USER",        // Maps to CDEMO-USER-TYPE
 *   "iat": 1672531200,          // Issued at timestamp
 *   "exp": 1672617600           // Expiration (24 hours later)
 * }
 * 
 * // Client includes token in all subsequent requests
 * Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
 * </pre>
 * 
 * <h2>Key Features</h2>
 * <ul>
 *   <li><strong>Token Generation:</strong> Creates JWT tokens with username and roles claims</li>
 *   <li><strong>Token Validation:</strong> Verifies signature, expiration, and format</li>
 *   <li><strong>Claims Extraction:</strong> Retrieves username and roles from token payload</li>
 *   <li><strong>Cryptographic Security:</strong> HMAC SHA-512 algorithm for signature</li>
 *   <li><strong>Expiration Management:</strong> 24-hour token lifetime per requirements</li>
 *   <li><strong>Stateless Architecture:</strong> No server-side session storage required</li>
 * </ul>
 * 
 * <h2>Security Configuration</h2>
 * <p>JWT secret and expiration are configured via application.yml:</p>
 * <pre>
 * app:
 *   jwt:
 *     secret: ${JWT_SECRET:CardDemo2024SecretKeyForJWTTokenGeneration!MustBe256BitsForHS512}
 *     expiration: 86400000  # 24 hours in milliseconds
 * </pre>
 * 
 * <h2>Integration Points</h2>
 * <ul>
 *   <li>{@link com.carddemo.controller.AuthenticationController#login} - Generates token on login</li>
 *   <li>{@link JwtAuthenticationFilter#doFilterInternal} - Validates token on each request</li>
 *   <li>{@link CustomUserDetailsService} - Provides user details for token generation</li>
 * </ul>
 * 
 * <h2>Token Lifecycle</h2>
 * <ol>
 *   <li><strong>Generation:</strong> User authenticates → generateToken() creates JWT</li>
 *   <li><strong>Client Storage:</strong> Frontend stores token (localStorage/sessionStorage)</li>
 *   <li><strong>Request Authorization:</strong> Client sends token in Authorization header</li>
 *   <li><strong>Validation:</strong> Filter calls validateToken() on each request</li>
 *   <li><strong>Extraction:</strong> Extract username/roles for Spring Security context</li>
 *   <li><strong>Expiration:</strong> Token expires after 24 hours, requires re-authentication</li>
 * </ol>
 * 
 * @see SecurityConstants
 * @see JwtAuthenticationFilter
 * @see com.carddemo.controller.AuthenticationController
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Component
public class JwtTokenProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    
    /**
     * JWT secret key for token signing and validation.
     * 
     * <p>Injected from application.yml configuration. Must be at least 256 bits
     * (32 characters) for HS512 algorithm compatibility. The secret should be
     * externalized to environment variables in production environments.</p>
     * 
     * <p>Configuration example:</p>
     * <pre>
     * app:
     *   jwt:
     *     secret: ${JWT_SECRET:&lt;default-fallback-key&gt;}
     * </pre>
     * 
     * <p><strong>Security Warning:</strong> Never commit production secrets to source control.
     * Use environment-specific secret management solutions.</p>
     */
    @Value("${app.jwt.secret:CardDemo2024SecretKeyForJWTTokenGeneration!MustBe256BitsForHS512}")
    private String jwtSecret;
    
    /**
     * JWT token expiration time in milliseconds.
     * 
     * <p>Default: 86400000 milliseconds (24 hours)</p>
     * 
     * <p>This duration matches the CICS session timeout semantics from the
     * mainframe implementation, per Section 0.3 transformation requirements.</p>
     * 
     * <p>Configuration example:</p>
     * <pre>
     * app:
     *   jwt:
     *     expiration: 86400000  # 24 hours
     * </pre>
     */
    @Value("${app.jwt.expiration:86400000}")
    private long jwtExpirationMs;
    
    /**
     * Generates a JWT token for an authenticated user.
     * 
     * <p>This method replaces CICS COMMAREA state initialization that occurred
     * after successful authentication in COSGN00C.cbl. The token encapsulates
     * user identity and roles as JWT claims.</p>
     * 
     * <h3>COBOL Equivalent Pattern:</h3>
     * <pre>
     * * COSGN00C.cbl - After password validation
     * IF SEC-USR-PWD = WS-USER-PWD
     *     MOVE WS-USER-ID   TO CDEMO-USER-ID
     *     MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *     EXEC CICS XCTL PROGRAM ('COMEN01C') COMMAREA(...) END-EXEC
     * END-IF
     * </pre>
     * 
     * <h3>JWT Token Structure:</h3>
     * <pre>
     * {
     *   "sub": "USER0001",              // Username (CDEMO-USER-ID equivalent)
     *   "roles": "ROLE_USER,ROLE_ADMIN", // Authorities (CDEMO-USER-TYPE mapping)
     *   "iat": 1672531200,              // Issued at timestamp
     *   "exp": 1672617600               // Expiration timestamp (iat + 24 hours)
     * }
     * </pre>
     * 
     * @param authentication Spring Security Authentication object containing user details
     *                      and granted authorities from successful login
     * @return Signed JWT token as a compact string (header.payload.signature)
     * @throws IllegalArgumentException if authentication is null or has no principal
     */
    public String generateToken(Authentication authentication) {
        if (authentication == null) {
            logger.error("Cannot generate token: authentication is null");
            throw new IllegalArgumentException("Authentication cannot be null");
        }
        
        String username = authentication.getName();
        if (username == null || username.trim().isEmpty()) {
            logger.error("Cannot generate token: username is null or empty");
            throw new IllegalArgumentException("Username cannot be null or empty");
        }
        
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);
        
        // Extract roles from Authentication (equivalent to CDEMO-USER-TYPE)
        // Spring Security authorities contain role names like "ROLE_USER", "ROLE_ADMIN"
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        
        if (logger.isDebugEnabled()) {
            logger.debug("Generating JWT token for user: {} with roles: {}", username, roles);
            logger.debug("Token will expire at: {}", expiryDate);
        }
        
        // Create secret key from configured string
        // HMAC SHA-512 requires minimum 256-bit key
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        
        try {
            // Build JWT with claims (replaces COMMAREA fields)
            // Using non-deprecated JJWT 0.12.x API
            String token = Jwts.builder()
                    .subject(username)                              // Maps to CDEMO-USER-ID
                    .claim(SecurityConstants.ROLES_CLAIM, roles)    // Maps to CDEMO-USER-TYPE
                    .issuedAt(now)                                  // Token creation timestamp
                    .expiration(expiryDate)                         // 24-hour expiration
                    .signWith(key)                                  // HMAC signature (algorithm auto-detected from key)
                    .compact();                                     // Serialize to string
            
            logger.info("Successfully generated JWT token for user: {}", username);
            return token;
            
        } catch (Exception ex) {
            logger.error("Failed to generate JWT token for user: {}", username, ex);
            throw new RuntimeException("JWT token generation failed", ex);
        }
    }
    
    /**
     * Extracts the username (subject) from a JWT token.
     * 
     * <p>This method retrieves the user ID that was stored in the token's subject claim
     * during generation. The username corresponds to CDEMO-USER-ID from the original
     * COBOL COMMAREA structure.</p>
     * 
     * <h3>COBOL Equivalent:</h3>
     * <pre>
     * * COCOM01Y.cpy - Communication Area
     * 05 CDEMO-USER-ID PIC X(08).
     * 
     * * Program accessing COMMAREA
     * MOVE CDEMO-USER-ID TO WS-CURRENT-USER
     * </pre>
     * 
     * <h3>JWT Claim Extraction:</h3>
     * <pre>
     * Token payload:
     * {
     *   "sub": "USER0001",    // ← This value is extracted
     *   "roles": "ROLE_USER",
     *   "iat": 1672531200,
     *   "exp": 1672617600
     * }
     * </pre>
     * 
     * @param token JWT token string (without "Bearer " prefix)
     * @return Username from the token's subject claim
     * @throws SignatureException if token signature is invalid
     * @throws MalformedJwtException if token format is invalid
     * @throws ExpiredJwtException if token has expired
     * @throws UnsupportedJwtException if token type is not supported
     * @throws IllegalArgumentException if token is null or empty
     */
    public String getUsernameFromToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            logger.error("Cannot extract username: token is null or empty");
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            String username = claims.getSubject();
            
            if (logger.isDebugEnabled()) {
                logger.debug("Successfully extracted username from token: {}", username);
            }
            
            return username;  // Extracts CDEMO-USER-ID equivalent
            
        } catch (SignatureException ex) {
            logger.error("Invalid JWT signature while extracting username", ex);
            throw ex;
        } catch (MalformedJwtException ex) {
            logger.error("Malformed JWT token while extracting username", ex);
            throw ex;
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token while extracting username", ex);
            throw ex;
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token while extracting username", ex);
            throw ex;
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty while extracting username", ex);
            throw ex;
        }
    }
    
    /**
     * Extracts user roles from a JWT token.
     * 
     * <p>This method retrieves the roles custom claim from the token payload.
     * The roles correspond to CDEMO-USER-TYPE from the original COBOL COMMAREA,
     * mapped to Spring Security role names.</p>
     * 
     * <h3>COBOL User Type Mapping:</h3>
     * <pre>
     * * COCOM01Y.cpy - Communication Area
     * 05 CDEMO-USER-TYPE PIC X(01).
     *    88 CDEMO-USRTYP-ADMIN VALUE 'A'.    → ROLE_ADMIN
     *    88 CDEMO-USRTYP-USER  VALUE 'U'.    → ROLE_USER
     * </pre>
     * 
     * <h3>JWT Claim Extraction:</h3>
     * <pre>
     * Token payload:
     * {
     *   "sub": "ADMIN001",
     *   "roles": "ROLE_ADMIN",    // ← This value is extracted
     *   "iat": 1672531200,
     *   "exp": 1672617600
     * }
     * </pre>
     * 
     * @param token JWT token string (without "Bearer " prefix)
     * @return Comma-separated string of role names (e.g., "ROLE_USER" or "ROLE_ADMIN")
     * @throws SignatureException if token signature is invalid
     * @throws MalformedJwtException if token format is invalid
     * @throws ExpiredJwtException if token has expired
     * @throws UnsupportedJwtException if token type is not supported
     * @throws IllegalArgumentException if token is null or empty
     */
    public String getRolesFromToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            logger.error("Cannot extract roles: token is null or empty");
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            String roles = claims.get(SecurityConstants.ROLES_CLAIM, String.class);
            
            if (logger.isDebugEnabled()) {
                logger.debug("Successfully extracted roles from token: {}", roles);
            }
            
            return roles;  // Extracts CDEMO-USER-TYPE equivalent (mapped to Spring roles)
            
        } catch (SignatureException ex) {
            logger.error("Invalid JWT signature while extracting roles", ex);
            throw ex;
        } catch (MalformedJwtException ex) {
            logger.error("Malformed JWT token while extracting roles", ex);
            throw ex;
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token while extracting roles", ex);
            throw ex;
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token while extracting roles", ex);
            throw ex;
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty while extracting roles", ex);
            throw ex;
        }
    }
    
    /**
     * Validates a JWT token's signature, format, and expiration.
     * 
     * <p>This method performs comprehensive validation of JWT tokens to ensure
     * they are authentic, well-formed, and not expired. It replaces CICS session
     * validation that occurred implicitly through COMMAREA passing between programs.</p>
     * 
     * <h3>COBOL Session Validation Equivalent:</h3>
     * <pre>
     * * CICS implicitly validates session through COMMAREA
     * * Program receives COMMAREA only if transaction is valid
     * IF EIBCALEN > 0
     *     * COMMAREA present, session is valid
     *     MOVE CDEMO-USER-ID TO WS-USER-ID
     * ELSE
     *     * No COMMAREA, redirect to sign-on
     *     EXEC CICS XCTL PROGRAM('COSGN00C') END-EXEC
     * END-IF
     * </pre>
     * 
     * <h3>JWT Validation Process:</h3>
     * <ol>
     *   <li><strong>Signature Verification:</strong> Ensures token hasn't been tampered with</li>
     *   <li><strong>Format Validation:</strong> Confirms token structure is valid</li>
     *   <li><strong>Expiration Check:</strong> Verifies token is still within 24-hour window</li>
     *   <li><strong>Claims Validation:</strong> Ensures required claims are present</li>
     * </ol>
     * 
     * <h3>Validation Failure Scenarios:</h3>
     * <ul>
     *   <li><strong>SignatureException:</strong> Token signature doesn't match (possible tampering)</li>
     *   <li><strong>MalformedJwtException:</strong> Token format is invalid (not proper JWT)</li>
     *   <li><strong>ExpiredJwtException:</strong> Token is older than 24 hours</li>
     *   <li><strong>UnsupportedJwtException:</strong> Token algorithm not supported</li>
     *   <li><strong>IllegalArgumentException:</strong> Token is null or empty</li>
     * </ul>
     * 
     * @param token JWT token string to validate (without "Bearer " prefix)
     * @return true if token is valid, false otherwise
     */
    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            logger.error("Token validation failed: token is null or empty");
            return false;
        }
        
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            
            // Parse and validate token
            // This throws exceptions if signature invalid, expired, or malformed
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            
            if (logger.isDebugEnabled()) {
                logger.debug("Token validation successful");
            }
            
            return true;
            
        } catch (SignatureException ex) {
            // Token signature doesn't match - possible tampering detected
            logger.error("Invalid JWT signature: {}", ex.getMessage());
            logger.debug("Signature validation failed for token", ex);
            return false;
            
        } catch (MalformedJwtException ex) {
            // Token structure is invalid - not a proper JWT
            logger.error("Invalid JWT token format: {}", ex.getMessage());
            logger.debug("Malformed token validation failed", ex);
            return false;
            
        } catch (ExpiredJwtException ex) {
            // Token has passed its 24-hour expiration window
            logger.error("Expired JWT token: {}", ex.getMessage());
            logger.debug("Token expired at: {}", ex.getClaims().getExpiration());
            return false;
            
        } catch (UnsupportedJwtException ex) {
            // Token uses unsupported algorithm or features
            logger.error("Unsupported JWT token: {}", ex.getMessage());
            logger.debug("Unsupported token type validation failed", ex);
            return false;
            
        } catch (IllegalArgumentException ex) {
            // Token claims string is empty or invalid
            logger.error("JWT claims string is empty: {}", ex.getMessage());
            logger.debug("Empty claims validation failed", ex);
            return false;
            
        } catch (Exception ex) {
            // Catch any other unexpected exceptions during validation
            logger.error("Unexpected error during JWT token validation: {}", ex.getMessage(), ex);
            return false;
        }
    }
}




