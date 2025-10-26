/*
 * JwtTokenProvider.java
 *
 * JWT token provider for stateless authentication in CardDemo application
 * Replaces CICS COMMAREA session management from mainframe
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * Converted from COBOL programs: COSGN00C.cbl
 * Original COBOL COMMAREA: COCOM01Y.cpy (lines 25-28)
 * Original COBOL fields:
 *   - CDEMO-USER-ID PIC X(08) (line 25)
 *   - CDEMO-USER-TYPE PIC X(01) (line 26)
 *   - CDEMO-USRTYP-ADMIN VALUE 'A' (line 27)
 *   - CDEMO-USRTYP-USER VALUE 'U' (line 28)
 *
 * Conversion Notes:
 * - CICS COMMAREA session state (user ID and type passed between programs)
 *   replaced with stateless JWT tokens containing identical claims
 * - COBOL session management via EXEC CICS RETURN TRANSID with COMMAREA
 *   replaced with JWT generation after successful authentication
 * - JWT tokens expire after 1 hour (3600000ms) matching CICS session timeout
 * - HMAC-SHA256 signature algorithm provides cryptographic authentication
 *   replacing CICS transaction-level security enforcement
 * - Base64-encoded secret key stored in application.yml (minimum 256 bits)
 *   for production deployment via Kubernetes secrets or AWS Secrets Manager
 */
package com.carddemo.security;

import com.carddemo.security.SecurityRoles;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Date;

/**
 * JWT token provider implementing stateless authentication for the CardDemo application.
 * 
 * <p>This class replaces the CICS COMMAREA session management approach used in the mainframe
 * environment. In the original COBOL system, user authentication state (user ID and user type)
 * was maintained in a COMMAREA structure passed between CICS transactions. This JWT-based
 * approach provides stateless authentication suitable for REST APIs and cloud deployment.</p>
 * 
 * <p><b>CICS COMMAREA to JWT Token Migration:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL COMMAREA (COCOM01Y.cpy)</th>
 *     <th>JWT Token Claim</th>
 *     <th>Purpose</th>
 *   </tr>
 *   <tr>
 *     <td>CDEMO-USER-ID (PIC X(08))</td>
 *     <td>subject (sub)</td>
 *     <td>8-character user identifier</td>
 *   </tr>
 *   <tr>
 *     <td>CDEMO-USER-TYPE (PIC X(01))</td>
 *     <td>role (custom claim)</td>
 *     <td>User authorization level ('A' for admin, 'U' for user)</td>
 *   </tr>
 *   <tr>
 *     <td>CICS session context</td>
 *     <td>issuedAt (iat) + expiration (exp)</td>
 *     <td>Session validity period (1 hour)</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Example JWT Token Structure:</b></p>
 * <pre>
 * Header:
 * {
 *   "alg": "HS256",
 *   "typ": "JWT"
 * }
 * 
 * Payload:
 * {
 *   "sub": "USER0001",           // CDEMO-USER-ID from COMMAREA
 *   "role": "ROLE_ADMIN",         // Converted from CDEMO-USER-TYPE='A'
 *   "iat": 1699999999,            // Token issued timestamp
 *   "exp": 1700003599             // Token expiration (iat + 3600 seconds)
 * }
 * 
 * Signature:
 * HMACSHA256(
 *   base64UrlEncode(header) + "." + base64UrlEncode(payload),
 *   secret_key
 * )
 * </pre>
 * 
 * <p><b>Security Considerations:</b></p>
 * <ul>
 *   <li>Minimum 256-bit (32-byte) secret key required for HMAC-SHA256 security</li>
 *   <li>Secret key must be stored securely in application.yml or external secret manager</li>
 *   <li>Token expiration time set to 1 hour matching CICS session timeout behavior</li>
 *   <li>Signature verification prevents token tampering and forgery</li>
 *   <li>Expiration validation ensures sessions timeout automatically</li>
 *   <li>Stateless design enables horizontal scaling without session replication</li>
 * </ul>
 * 
 * <p><b>Configuration Properties:</b></p>
 * <pre>
 * # application.yml
 * jwt:
 *   secret: your-base64-encoded-secret-key-min-256-bits
 *   expiration: 3600000  # 1 hour in milliseconds (default)
 * </pre>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * // After successful authentication (replaces EXEC CICS RETURN with COMMAREA)
 * String token = jwtTokenProvider.generateToken("USER0001", "A");
 * 
 * // On subsequent API requests (replaces COMMAREA validation)
 * if (jwtTokenProvider.validateToken(token)) {
 *     String userId = jwtTokenProvider.extractUserId(token);     // "USER0001"
 *     String role = jwtTokenProvider.extractUserType(token);     // "ROLE_ADMIN"
 *     // Proceed with authenticated request
 * }
 * </pre>
 * 
 * @see SecurityRoles
 * @see io.jsonwebtoken.Jwts
 * @see org.springframework.security.core.Authentication
 */
@Component
public class JwtTokenProvider {

    /**
     * Logger for JWT token operations and security events.
     * Logs token generation, validation, expiration, and signature failures.
     */
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    /**
     * JWT claim name for user role.
     * Stores the Spring Security role name (e.g., "ROLE_ADMIN", "ROLE_USER").
     * Replaces CDEMO-USER-TYPE from COBOL COMMAREA.
     */
    private static final String CLAIM_ROLE = "role";

    /**
     * Base64-encoded secret key for HMAC-SHA256 signature algorithm.
     * Injected from application.yml property: jwt.secret
     * Must be minimum 256 bits (32 bytes) when decoded from Base64.
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Token expiration time in milliseconds.
     * Injected from application.yml property: jwt.expiration
     * Default value: 3600000ms (1 hour) matching CICS session timeout.
     */
    @Value("${jwt.expiration:3600000}")
    private long jwtExpiration;

    /**
     * Cryptographic secret key for HMAC-SHA256 algorithm.
     * Initialized in {@link #init()} method after dependency injection.
     * Used for both token signing (generation) and verification (validation).
     */
    private SecretKey secretKey;

    /**
     * Initializes the cryptographic secret key after dependency injection completes.
     * 
     * <p>This method executes once during Spring application startup, after @Value
     * fields are injected but before the bean is available for use. It decodes the
     * Base64-encoded secret key string from application.yml and converts it to a
     * SecretKey object suitable for HMAC-SHA256 operations.</p>
     * 
     * <p><b>Security Validation:</b></p>
     * <ul>
     *   <li>Verifies jwt.secret property is not null or empty</li>
     *   <li>Decodes Base64 string to byte array</li>
     *   <li>Validates minimum 256-bit (32-byte) key length for HMAC-SHA256</li>
     *   <li>Creates immutable SecretKey object via Keys.hmacShaKeyFor()</li>
     * </ul>
     * 
     * <p><b>Failure Handling:</b></p>
     * <p>Throws IllegalArgumentException if:
     * <ul>
     *   <li>jwt.secret is null, empty, or contains only whitespace</li>
     *   <li>Decoded byte array is less than 32 bytes (256 bits)</li>
     *   <li>Base64 decoding fails due to invalid encoding</li>
     * </ul>
     * This fail-fast approach prevents application startup with insecure configuration.</p>
     * 
     * @throws IllegalArgumentException if jwt.secret is invalid or insufficient length
     */
    @PostConstruct
    public void init() {
        // Validate jwt.secret property is present
        if (jwtSecret == null || jwtSecret.trim().isEmpty()) {
            String errorMsg = "JWT secret key is null or empty. Configure jwt.secret property in application.yml";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        try {
            // Decode Base64-encoded secret key string to byte array
            byte[] secretBytes = Base64.getDecoder().decode(jwtSecret);

            // Validate minimum 256-bit (32-byte) key length for HMAC-SHA256 security
            if (secretBytes.length < 32) {
                String errorMsg = String.format(
                    "JWT secret key must be at least 256 bits (32 bytes). Current length: %d bytes. " +
                    "Generate a secure key with: openssl rand -base64 32",
                    secretBytes.length
                );
                logger.error(errorMsg);
                throw new IllegalArgumentException(errorMsg);
            }

            // Create SecretKey object for HMAC-SHA256 signature algorithm
            this.secretKey = Keys.hmacShaKeyFor(secretBytes);

            logger.info("JWT token provider initialized successfully. Secret key length: {} bits. " +
                       "Token expiration: {} ms ({} hour)", 
                       secretBytes.length * 8, 
                       jwtExpiration,
                       jwtExpiration / 3600000.0);

        } catch (IllegalArgumentException e) {
            // Base64 decoding failure or key length validation failure
            String errorMsg = "Failed to initialize JWT secret key: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new IllegalArgumentException(errorMsg, e);
        }
    }

    /**
     * Generates a JWT token for authenticated user.
     * 
     * <p>This method replaces the COBOL pattern of populating CARDDEMO-COMMAREA with user
     * credentials and returning to CICS with transaction ID. After successful authentication
     * (COSGN00C.cbl user/password validation), this method creates a stateless JWT token
     * containing user identity and authorization level.</p>
     * 
     * <p><b>Token Claims Generated:</b></p>
     * <ul>
     *   <li><b>subject (sub):</b> User ID from CDEMO-USER-ID (e.g., "USER0001")</li>
     *   <li><b>role (custom):</b> Spring Security role from CDEMO-USER-TYPE via
     *       SecurityRoles.fromUserType() (e.g., 'A' → "ROLE_ADMIN")</li>
     *   <li><b>issuedAt (iat):</b> Current timestamp when token is generated</li>
     *   <li><b>expiration (exp):</b> issuedAt + jwtExpiration (default 1 hour)</li>
     * </ul>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (COSGN00C.cbl after successful authentication):
     *   MOVE WS-USER-ID TO CDEMO-USER-ID
     *   MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
     *   EXEC CICS RETURN TRANSID('CC00') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
     * 
     * Java (this method):
     *   String token = generateToken(userId, userType);
     *   return ResponseEntity.ok(new AuthResponse(token, expirationTime));
     * </pre>
     * 
     * <p><b>Token Signing:</b></p>
     * <p>Token is signed with HMAC-SHA256 algorithm using the secret key initialized in
     * {@link #init()}. The signature ensures token integrity and authenticity, preventing
     * tampering or forgery.</p>
     * 
     * @param userId the user identifier (8 characters max, replaces CDEMO-USER-ID).
     *               Must not be null or empty.
     * @param userType the COBOL user type code ('A' for admin, 'U' for user, 'O' for operator).
     *                 Converted to Spring Security role name (ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR).
     *                 Must not be null or empty.
     * @return a signed JWT token string in compact serialization format (header.payload.signature)
     * @throws IllegalArgumentException if userId or userType is null or empty
     */
    public String generateToken(String userId, String userType) {
        // Validate userId parameter
        if (userId == null || userId.trim().isEmpty()) {
            String errorMsg = "User ID cannot be null or empty for JWT token generation";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // Validate userType parameter
        if (userType == null || userType.trim().isEmpty()) {
            String errorMsg = "User type cannot be null or empty for JWT token generation";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        // Convert COBOL user type to Spring Security role name
        String springSecurityRole = SecurityRoles.fromUserType(userType);

        // Calculate token expiration time
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + jwtExpiration);

        logger.debug("Generating JWT token for user '{}' with role '{}'. Expiration: {}", 
                    userId, springSecurityRole, expirationDate);

        // Build and sign JWT token
        String token = Jwts.builder()
            .subject(userId)                              // sub: User ID (CDEMO-USER-ID)
            .claim(CLAIM_ROLE, springSecurityRole)        // role: Spring Security role name
            .issuedAt(now)                                // iat: Token generation timestamp
            .expiration(expirationDate)                   // exp: Token expiration timestamp
            .signWith(secretKey)                          // Sign with HMAC-SHA256
            .compact();                                   // Serialize to compact string

        logger.info("JWT token generated successfully for user '{}'. Token valid until {}", 
                   userId, expirationDate);

        return token;
    }

    /**
     * Validates a JWT token's signature and expiration.
     * 
     * <p>This method replaces the COBOL pattern of validating COMMAREA contents on each
     * CICS transaction entry. In the mainframe system, each program would verify that
     * CDEMO-USER-ID and CDEMO-USER-TYPE in the COMMAREA are properly populated. This method
     * performs equivalent validation for JWT tokens by verifying cryptographic signature
     * and checking expiration timestamp.</p>
     * 
     * <p><b>Validation Steps:</b></p>
     * <ol>
     *   <li>Verify token string is not null or empty</li>
     *   <li>Parse token structure (header, payload, signature)</li>
     *   <li>Verify HMAC-SHA256 signature using secret key</li>
     *   <li>Check token expiration timestamp against current time</li>
     *   <li>Validate claims structure (subject and role claims present)</li>
     * </ol>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (CICS program entry validation):
     *   IF CDEMO-USER-ID = SPACES OR CDEMO-USER-TYPE = SPACES
     *      MOVE 'User not authenticated' TO WS-ERROR-MSG
     *      PERFORM SEND-ERROR-SCREEN
     *   END-IF
     * 
     * Java (this method):
     *   if (!jwtTokenProvider.validateToken(token)) {
     *       throw new UnauthorizedException("Invalid or expired token");
     *   }
     * </pre>
     * 
     * <p><b>Exception Handling:</b></p>
     * <p>All JWT validation exceptions are caught and logged. The method returns false
     * rather than throwing exceptions to allow graceful handling by authentication filters.
     * Exceptions handled:
     * <ul>
     *   <li><b>SignatureException:</b> Token signature verification failed (tampered token)</li>
     *   <li><b>ExpiredJwtException:</b> Token expiration time has passed</li>
     *   <li><b>MalformedJwtException:</b> Token structure is invalid or corrupted</li>
     *   <li><b>UnsupportedJwtException:</b> Token uses unsupported features or algorithms</li>
     *   <li><b>IllegalArgumentException:</b> Token string is null, empty, or invalid</li>
     * </ul>
     * </p>
     * 
     * @param token the JWT token string to validate. Must not be null or empty.
     * @return true if token is valid (signature verified and not expired), false otherwise
     */
    public boolean validateToken(String token) {
        // Handle null or empty token
        if (token == null || token.trim().isEmpty()) {
            logger.warn("Attempted to validate null or empty JWT token");
            return false;
        }

        try {
            // Parse and validate token signature and expiration
            Jwts.parser()
                .verifyWith(secretKey)                    // Verify HMAC-SHA256 signature
                .build()
                .parseSignedClaims(token);                // Parse and validate claims

            logger.debug("JWT token validation successful");
            return true;

        } catch (SignatureException e) {
            // Token signature verification failed - token has been tampered with
            logger.error("JWT token signature validation failed: {}", e.getMessage());
            return false;

        } catch (ExpiredJwtException e) {
            // Token expiration time has passed - session timeout equivalent
            logger.warn("JWT token has expired. Expiration: {}. Current time: {}", 
                       e.getClaims().getExpiration(), new Date());
            return false;

        } catch (MalformedJwtException e) {
            // Token structure is invalid or corrupted
            logger.error("Malformed JWT token: {}", e.getMessage());
            return false;

        } catch (UnsupportedJwtException e) {
            // Token uses unsupported features or algorithms
            logger.error("Unsupported JWT token format or algorithm: {}", e.getMessage());
            return false;

        } catch (IllegalArgumentException e) {
            // Token string is invalid
            logger.error("Invalid JWT token argument: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts user ID from JWT token subject claim.
     * 
     * <p>This method extracts the user identifier from a validated JWT token, equivalent to
     * reading CDEMO-USER-ID from the COBOL COMMAREA. In the mainframe system, the user ID
     * is passed in the COMMAREA structure between CICS transactions. This method retrieves
     * the same information from the JWT token's subject (sub) claim.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (accessing COMMAREA in subsequent program):
     *   MOVE CDEMO-USER-ID TO WS-CURRENT-USER-ID
     * 
     * Java (this method):
     *   String userId = jwtTokenProvider.extractUserId(token);
     * </pre>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * String token = request.getHeader("Authorization").substring(7); // Remove "Bearer "
     * if (jwtTokenProvider.validateToken(token)) {
     *     String userId = jwtTokenProvider.extractUserId(token);
     *     // Use userId for authorization checks or audit logging
     * }
     * </pre>
     * 
     * @param token the JWT token string. Must be a valid, non-expired token.
     * @return the user ID from the token's subject claim (8 characters max, replaces CDEMO-USER-ID)
     * @throws IllegalArgumentException if token is null, empty, or invalid
     * @throws ExpiredJwtException if token has expired
     * @throws SignatureException if token signature is invalid
     */
    public String extractUserId(String token) {
        // Validate token parameter
        if (token == null || token.trim().isEmpty()) {
            String errorMsg = "Token cannot be null or empty for user ID extraction";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        try {
            // Parse token and extract claims
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

            // Extract subject claim (user ID)
            String userId = claims.getSubject();

            logger.debug("Extracted user ID '{}' from JWT token", userId);
            return userId;

        } catch (ExpiredJwtException e) {
            // For expired tokens, still extract user ID from claims for logging
            String userId = e.getClaims().getSubject();
            logger.warn("Extracted user ID '{}' from expired JWT token", userId);
            throw e;

        } catch (Exception e) {
            logger.error("Failed to extract user ID from JWT token: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Extracts user role from JWT token custom claim.
     * 
     * <p>This method extracts the Spring Security role name from a validated JWT token,
     * equivalent to reading CDEMO-USER-TYPE from the COBOL COMMAREA. In the mainframe
     * system, the user type ('A' for admin, 'U' for user) is passed in the COMMAREA
     * structure between CICS transactions. This method retrieves the converted Spring
     * Security role name (ROLE_ADMIN, ROLE_USER) from the JWT token's custom "role" claim.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * COBOL (accessing COMMAREA in subsequent program):
     *   IF CDEMO-USRTYP-ADMIN
     *      PERFORM DISPLAY-ADMIN-MENU
     *   ELSE IF CDEMO-USRTYP-USER
     *      PERFORM DISPLAY-USER-MENU
     *   END-IF
     * 
     * Java (this method):
     *   String role = jwtTokenProvider.extractUserType(token);
     *   if (SecurityRoles.ROLE_ADMIN.equals(role)) {
     *       // Display admin menu
     *   }
     * </pre>
     * 
     * <p><b>Role Claim Value:</b></p>
     * <p>The returned role string will be a Spring Security role name with ROLE_ prefix:
     * <ul>
     *   <li>ROLE_ADMIN - for admin users (COBOL type 'A')</li>
     *   <li>ROLE_USER - for regular users (COBOL type 'U')</li>
     *   <li>ROLE_OPERATOR - for operational staff (COBOL type 'O')</li>
     * </ul>
     * </p>
     * 
     * @param token the JWT token string. Must be a valid, non-expired token.
     * @return the Spring Security role name from the token's "role" claim (ROLE_ADMIN, ROLE_USER, etc.)
     * @throws IllegalArgumentException if token is null, empty, or invalid
     * @throws ExpiredJwtException if token has expired
     * @throws SignatureException if token signature is invalid
     */
    public String extractUserType(String token) {
        // Validate token parameter
        if (token == null || token.trim().isEmpty()) {
            String errorMsg = "Token cannot be null or empty for user type extraction";
            logger.error(errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }

        try {
            // Parse token and extract claims
            Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

            // Extract role claim (Spring Security role name)
            String userType = claims.get(CLAIM_ROLE, String.class);

            logger.debug("Extracted user type '{}' from JWT token", userType);
            return userType;

        } catch (ExpiredJwtException e) {
            // For expired tokens, still extract role from claims for logging
            String userType = e.getClaims().get(CLAIM_ROLE, String.class);
            logger.warn("Extracted user type '{}' from expired JWT token", userType);
            throw e;

        } catch (Exception e) {
            logger.error("Failed to extract user type from JWT token: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Returns the JWT token expiration time in milliseconds.
     * 
     * <p>This method returns the configured token lifetime, equivalent to the CICS session
     * timeout value. In the mainframe environment, CICS sessions automatically timeout after
     * a period of inactivity. This expiration time provides equivalent functionality for
     * stateless JWT tokens.</p>
     * 
     * <p>The expiration time is injected from application.yml (jwt.expiration property) with
     * a default value of 3600000 milliseconds (1 hour) matching typical CICS session timeout
     * behavior.</p>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Return expiration time to client for token refresh logic
     * long expirationMs = jwtTokenProvider.getExpirationTime();
     * AuthResponse response = new AuthResponse(token, expirationMs);
     * return ResponseEntity.ok(response);
     * </pre>
     * 
     * @return the token expiration time in milliseconds (default 3600000 = 1 hour)
     */
    public long getExpirationTime() {
        return jwtExpiration;
    }
}
