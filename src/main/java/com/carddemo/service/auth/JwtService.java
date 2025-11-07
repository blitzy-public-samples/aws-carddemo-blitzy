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

package com.carddemo.service.auth;

import com.carddemo.entity.User.UserType;
import com.carddemo.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token Lifecycle Management Service for CardDemo Application
 * 
 * <p>This service provides complete JWT token operations for stateless API authentication,
 * replacing CICS COMMAREA-based session management with modern token-based security.</p>
 * 
 * <h2>COBOL Origin - Session State Transformation</h2>
 * <p>Transforms CICS pseudo-conversational session management to stateless JWT authentication:</p>
 * <ul>
 *   <li><b>COMMAREA CDEMO-USER-ID</b> (COCOM01Y.cpy line 25): Mapped to JWT "userId" claim</li>
 *   <li><b>COMMAREA CDEMO-USER-TYPE</b> (COCOM01Y.cpy line 26-28): Mapped to JWT "userType" claim</li>
 *   <li><b>CICS RETURN TRANSID with COMMAREA</b> (COSGN00C.cbl lines 98-102): Replaced by JWT token 
 *       in HTTP Authorization header carrying user context across requests</li>
 *   <li><b>Session timeout behavior</b>: Replicated via JWT expiration time matching CICS timeout</li>
 * </ul>
 * 
 * <h2>JWT Token Structure</h2>
 * <p>Generated tokens contain the following claims:</p>
 * <ul>
 *   <li><b>sub (subject)</b>: User ID (8 characters max, matching COBOL PIC X(08))</li>
 *   <li><b>userType</b>: User role as enum name (ADMIN or USER)</li>
 *   <li><b>role</b>: Spring Security role (ROLE_ADMIN or ROLE_USER)</li>
 *   <li><b>iat (issued at)</b>: Token creation timestamp</li>
 *   <li><b>exp (expiration)</b>: Token expiration timestamp (configurable duration)</li>
 * </ul>
 * 
 * <h2>Security Features</h2>
 * <ul>
 *   <li><b>Algorithm</b>: HS512 (HMAC-SHA512) for symmetric key signing</li>
 *   <li><b>Secret Key</b>: Externalized to application.properties (jwt.secret)</li>
 *   <li><b>Expiration</b>: Configurable duration in milliseconds (jwt.expiration)</li>
 *   <li><b>Signature Validation</b>: Cryptographic verification prevents token tampering</li>
 *   <li><b>Expiration Check</b>: Automatic validation of token validity period</li>
 * </ul>
 * 
 * <h2>Integration Points</h2>
 * <ul>
 *   <li><b>AuthenticationService</b>: Calls generateToken() after successful credential validation</li>
 *   <li><b>JwtAuthenticationFilter</b>: Calls validateToken() and extract methods for every request</li>
 *   <li><b>Spring Security</b>: Token claims populate SecurityContext for authorization checks</li>
 * </ul>
 * 
 * <h2>Thread Safety</h2>
 * <p>This service is thread-safe as a Spring singleton. JWT operations are stateless,
 * using immutable configuration values injected at startup.</p>
 * 
 * @see com.carddemo.service.auth.AuthenticationService
 * @see com.carddemo.security.JwtAuthenticationFilter
 */
@Service
@Slf4j
public class JwtService {

    /**
     * JWT secret key for token signing and validation.
     * Injected from application.properties (jwt.secret property).
     * Must be at least 512 bits (64 characters) for HS512 algorithm security.
     * 
     * Example configuration:
     * jwt.secret=your-secret-key-must-be-at-least-512-bits-long-for-hs512-algorithm
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * JWT token expiration duration in milliseconds.
     * Injected from application.properties (jwt.expiration property).
     * Default: 86400000 (24 hours, matching typical CICS session timeout).
     * 
     * Example configuration:
     * jwt.expiration=86400000
     */
    @Value("${jwt.expiration}")
    private long jwtExpirationMs;

    /**
     * Claim key for user type in JWT payload.
     * Stores UserType enum name (ADMIN or USER).
     */
    private static final String CLAIM_USER_TYPE = "userType";

    /**
     * Claim key for Spring Security role in JWT payload.
     * Stores role with ROLE_ prefix (ROLE_ADMIN or ROLE_USER).
     */
    private static final String CLAIM_ROLE = "role";

    /**
     * Generate JWT token for authenticated user.
     * 
     * <p>Creates a signed JWT token containing user identity and role claims,
     * replacing CICS COMMAREA session state with stateless token-based authentication.</p>
     * 
     * <p><b>COBOL Equivalent:</b> After successful authentication in COSGN00C.cbl (lines 222-240),
     * user ID and type are stored in COMMAREA. This method creates equivalent token payload.</p>
     * 
     * <h3>Token Claims:</h3>
     * <ul>
     *   <li><b>subject</b>: User ID from parameter (matching CDEMO-USER-ID)</li>
     *   <li><b>userType</b>: User type string (matching CDEMO-USER-TYPE: 'A' or 'U')</li>
     *   <li><b>role</b>: Spring Security role (ROLE_ADMIN or ROLE_USER)</li>
     *   <li><b>issuedAt</b>: Current timestamp</li>
     *   <li><b>expiration</b>: Current time + jwtExpirationMs</li>
     * </ul>
     * 
     * @param userId User ID (8 characters max, matching COBOL PIC X(08))
     * @param userTypeCode User type code ('A' for admin, 'U' for user, matching SEC-USR-TYPE)
     * @return Signed JWT token string for use in Authorization header
     * @throws AuthenticationException if token generation fails due to invalid input or system error
     */
    public String generateToken(String userId, String userTypeCode) {
        try {
            log.debug("Generating JWT token for user: {}", userId);
            
            // Validate inputs
            if (userId == null || userId.trim().isEmpty()) {
                log.error("Cannot generate token: userId is null or empty");
                throw new AuthenticationException("User ID is required for token generation");
            }
            
            if (userTypeCode == null || userTypeCode.trim().isEmpty()) {
                log.error("Cannot generate token: userTypeCode is null or empty");
                throw new AuthenticationException("User type code is required for token generation");
            }
            
            // Convert user type code to enum
            UserType userType = parseUserType(userTypeCode);
            
            // Build claims map
            Map<String, Object> claims = new HashMap<>();
            claims.put(CLAIM_USER_TYPE, userType.name());
            claims.put(CLAIM_ROLE, "ROLE_" + userType.name());
            
            // Calculate expiration date
            Date now = new Date();
            Date expirationDate = new Date(now.getTime() + jwtExpirationMs);
            
            // Generate secret key
            SecretKey key = getSigningKey();
            
            // Build and sign JWT token
            String token = Jwts.builder()
                    .claims(claims)
                    .subject(userId)
                    .issuedAt(now)
                    .expiration(expirationDate)
                    .signWith(key)
                    .compact();
            
            log.debug("JWT token generated successfully for user: {}, expires at: {}", 
                    userId, expirationDate);
            
            return token;
            
        } catch (AuthenticationException e) {
            // Re-throw authentication exceptions
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error generating JWT token for user: {}", userId, e);
            throw new AuthenticationException("Unable to generate authentication token: " + e.getMessage());
        }
    }

    /**
     * Validate JWT token signature and expiration.
     * 
     * <p>Verifies token integrity and validity, equivalent to CICS security context validation.</p>
     * 
     * <p><b>COBOL Equivalent:</b> CICS automatically validates session context (COMMAREA) on each
     * transaction. This method provides equivalent validation for stateless JWT tokens.</p>
     * 
     * <h3>Validation Steps:</h3>
     * <ol>
     *   <li>Verify token signature using secret key</li>
     *   <li>Check token expiration timestamp</li>
     *   <li>Ensure token structure is valid</li>
     * </ol>
     * 
     * @param token JWT token string to validate
     * @return true if token is valid (signature correct and not expired), false otherwise
     */
    public boolean validateToken(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                log.warn("Token validation failed: token is null or empty");
                return false;
            }
            
            log.debug("Validating JWT token");
            
            SecretKey key = getSigningKey();
            
            // Parse and validate token (throws exception if invalid or expired)
            Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token);
            
            log.debug("JWT token validation successful");
            return true;
            
        } catch (ExpiredJwtException e) {
            log.warn("JWT token validation failed: token has expired", e);
            return false;
        } catch (JwtException e) {
            log.warn("JWT token validation failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error validating JWT token", e);
            return false;
        }
    }

    /**
     * Extract user ID from JWT token.
     * 
     * <p>Retrieves the subject claim containing user ID, equivalent to accessing
     * CDEMO-USER-ID from CICS COMMAREA.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Access CDEMO-USER-ID field from CARDDEMO-COMMAREA
     * (COCOM01Y.cpy line 25) in CICS programs.</p>
     * 
     * @param token JWT token string
     * @return User ID (8 characters max, matching COBOL PIC X(08))
     * @throws AuthenticationException if token is invalid or user ID cannot be extracted
     */
    public String extractUserId(String token) {
        try {
            log.debug("Extracting user ID from JWT token");
            
            Claims claims = extractAllClaims(token);
            String userId = claims.getSubject();
            
            if (userId == null || userId.trim().isEmpty()) {
                log.error("User ID claim is missing or empty in token");
                throw new AuthenticationException("Token does not contain valid user ID");
            }
            
            log.debug("Extracted user ID: {}", userId);
            return userId;
            
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error extracting user ID from token", e);
            throw new AuthenticationException("Unable to extract user ID from token: " + e.getMessage());
        }
    }

    /**
     * Extract user type from JWT token.
     * 
     * <p>Retrieves the userType claim containing user role, equivalent to accessing
     * CDEMO-USER-TYPE from CICS COMMAREA.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Access CDEMO-USER-TYPE field from CARDDEMO-COMMAREA
     * (COCOM01Y.cpy line 26) with 88-level conditions CDEMO-USRTYP-ADMIN (value 'A')
     * and CDEMO-USRTYP-USER (value 'U').</p>
     * 
     * @param token JWT token string
     * @return User type code ('A' for ADMIN, 'U' for USER)
     * @throws AuthenticationException if token is invalid or user type cannot be extracted
     */
    public String extractUserType(String token) {
        try {
            log.debug("Extracting user type from JWT token");
            
            Claims claims = extractAllClaims(token);
            String userTypeStr = claims.get(CLAIM_USER_TYPE, String.class);
            
            if (userTypeStr == null || userTypeStr.trim().isEmpty()) {
                log.error("User type claim is missing or empty in token");
                throw new AuthenticationException("Token does not contain valid user type");
            }
            
            // Convert enum name back to code ('A' or 'U')
            UserType userType = UserType.valueOf(userTypeStr);
            String userTypeCode = userType.getCode();
            
            log.debug("Extracted user type: {} (code: {})", userTypeStr, userTypeCode);
            return userTypeCode;
            
        } catch (IllegalArgumentException e) {
            log.error("Invalid user type value in token", e);
            throw new AuthenticationException("Token contains invalid user type: " + e.getMessage());
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error extracting user type from token", e);
            throw new AuthenticationException("Unable to extract user type from token: " + e.getMessage());
        }
    }

    /**
     * Get token expiration duration in milliseconds.
     * 
     * <p>Returns the configured token validity period, matching CICS session timeout behavior.</p>
     * 
     * @return Token expiration duration in milliseconds (default 86400000 = 24 hours)
     */
    public long getTokenExpiration() {
        return jwtExpirationMs;
    }

    /**
     * Check if JWT token has expired.
     * 
     * <p>Determines token validity by comparing expiration timestamp with current time,
     * equivalent to CICS session timeout detection.</p>
     * 
     * @param token JWT token string
     * @return true if token has expired, false if still valid
     * @throws AuthenticationException if token cannot be parsed
     */
    public boolean isTokenExpired(String token) {
        try {
            log.debug("Checking if JWT token is expired");
            
            Claims claims = extractAllClaims(token);
            Date expiration = claims.getExpiration();
            
            if (expiration == null) {
                log.error("Token expiration claim is missing");
                throw new AuthenticationException("Token does not contain expiration information");
            }
            
            boolean expired = expiration.before(new Date());
            log.debug("Token expiration check: expired={}, expiration={}", expired, expiration);
            
            return expired;
            
        } catch (ExpiredJwtException e) {
            log.debug("Token is expired (caught ExpiredJwtException)");
            return true;
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error checking token expiration", e);
            throw new AuthenticationException("Unable to check token expiration: " + e.getMessage());
        }
    }

    /**
     * Extract all claims from JWT token.
     * 
     * <p>Parses and validates token, returning all payload claims for inspection.</p>
     * 
     * <p><b>COBOL Equivalent:</b> Access entire CARDDEMO-COMMAREA structure containing
     * all session context fields (COCOM01Y.cpy lines 19-47).</p>
     * 
     * @param token JWT token string
     * @return Claims object containing all token payload data
     * @throws AuthenticationException if token is invalid, expired, or cannot be parsed
     */
    public Claims extractAllClaims(String token) {
        try {
            if (token == null || token.trim().isEmpty()) {
                log.error("Cannot extract claims: token is null or empty");
                throw new AuthenticationException("Token is required for claim extraction");
            }
            
            log.debug("Extracting all claims from JWT token");
            
            SecretKey key = getSigningKey();
            
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            
            log.debug("Successfully extracted claims from token");
            return claims;
            
        } catch (ExpiredJwtException e) {
            log.warn("Cannot extract claims: token has expired", e);
            throw new AuthenticationException("Token has expired");
        } catch (JwtException e) {
            log.error("JWT parsing error: {}", e.getMessage());
            throw new AuthenticationException("Invalid or malformed token: " + e.getMessage());
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error extracting claims from token", e);
            throw new AuthenticationException("Unable to extract token claims: " + e.getMessage());
        }
    }

    /**
     * Generate signing key from JWT secret.
     * 
     * <p>Creates a secure SecretKey for HS512 algorithm from the configured secret string.</p>
     * 
     * @return SecretKey for JWT signing and validation
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Parse user type code to UserType enum.
     * 
     * <p>Converts COBOL user type code ('A' or 'U') to Java enum.</p>
     * 
     * <p><b>COBOL Mapping:</b></p>
     * <ul>
     *   <li>'A' (CDEMO-USRTYP-ADMIN) → UserType.ADMIN</li>
     *   <li>'U' (CDEMO-USRTYP-USER) → UserType.USER</li>
     * </ul>
     * 
     * @param userTypeCode User type code from COBOL ('A' or 'U')
     * @return UserType enum value
     * @throws AuthenticationException if user type code is invalid
     */
    private UserType parseUserType(String userTypeCode) {
        try {
            // Match code to enum
            for (UserType type : UserType.values()) {
                if (type.getCode().equalsIgnoreCase(userTypeCode.trim())) {
                    return type;
                }
            }
            
            // No match found
            log.error("Invalid user type code: {}", userTypeCode);
            throw new AuthenticationException("Invalid user type code: " + userTypeCode);
            
        } catch (Exception e) {
            log.error("Error parsing user type code: {}", userTypeCode, e);
            throw new AuthenticationException("Unable to parse user type: " + e.getMessage());
        }
    }
}
