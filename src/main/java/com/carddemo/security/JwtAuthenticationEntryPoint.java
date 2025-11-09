/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Security AuthenticationEntryPoint implementation that handles authentication
 * failures and unauthorized access attempts by returning HTTP 401 Unauthorized responses
 * with proper JSON error messages.
 * 
 * <p>This class replaces mainframe CICS authentication error handling from COSGN00C.cbl
 * (lines 241-256) which handled three error scenarios:
 * <ul>
 *   <li>Wrong password (line 242: "Wrong Password. Try again...")</li>
 *   <li>User not found (line 249: "User not found. Try again...")</li>
 *   <li>General authentication failure (line 254: "Unable to verify the User...")</li>
 * </ul>
 * 
 * <p>The entry point is invoked automatically by Spring Security's ExceptionTranslationFilter
 * when authentication fails during request processing, including scenarios such as:
 * <ul>
 *   <li>Invalid JWT token signature</li>
 *   <li>Expired JWT token</li>
 *   <li>Missing JWT token</li>
 *   <li>Malformed JWT token</li>
 *   <li>Attempting to access protected resources without authentication</li>
 * </ul>
 * 
 * <p>This implementation converts COBOL BMS error screens to standardized HTTP 401 responses
 * suitable for REST API clients, including React frontend components that consume the
 * authentication endpoints.
 * 
 * @see org.springframework.security.web.AuthenticationEntryPoint
 * @see com.carddemo.config.SecurityConfig
 */
@Slf4j
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * Constructs a new JwtAuthenticationEntryPoint with the provided ObjectMapper
     * for JSON serialization.
     * 
     * @param objectMapper Jackson ObjectMapper for converting error objects to JSON
     */
    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Handles authentication failures by sending HTTP 401 Unauthorized response with
     * JSON error payload containing detailed error information.
     * 
     * <p>This method is invoked by Spring Security when an AuthenticationException is
     * thrown during request processing. It constructs a standardized JSON error response
     * that includes:
     * <ul>
     *   <li>timestamp: Current date-time in ISO-8601 format</li>
     *   <li>status: HTTP 401 status code</li>
     *   <li>error: "Unauthorized" error type</li>
     *   <li>message: Specific failure reason from the exception</li>
     *   <li>path: The requested endpoint that was accessed</li>
     * </ul>
     * 
     * <p>Security audit logging is performed at WARN level, recording:
     * <ul>
     *   <li>Client IP address</li>
     *   <li>Requested path</li>
     *   <li>HTTP method</li>
     *   <li>Exception type and message</li>
     * </ul>
     * 
     * <p>Error message mapping from Spring Security AuthenticationException types:
     * <ul>
     *   <li>BadCredentialsException → "Invalid username or password"
     *       (matches COSGN00C.cbl line 242: "Wrong Password. Try again...")</li>
     *   <li>UsernameNotFoundException → "User not found"
     *       (matches COSGN00C.cbl line 249: "User not found. Try again...")</li>
     *   <li>AccountExpiredException/CredentialsExpiredException/DisabledException →
     *       appropriate account status messages</li>
     *   <li>Generic AuthenticationException → "Authentication failed"
     *       (matches COSGN00C.cbl line 254: "Unable to verify the User...")</li>
     * </ul>
     * 
     * @param request the HttpServletRequest that resulted in an AuthenticationException
     * @param response the HttpServletResponse to send the error information
     * @param authException the exception that was thrown, containing authentication failure details
     * @throws IOException if an input or output exception occurs during JSON writing
     */
    @Override
    public void commence(HttpServletRequest request,
                        HttpServletResponse response,
                        AuthenticationException authException) throws IOException {
        
        // Log authentication failure for security audit trail
        // This replaces mainframe RACF security logging patterns
        log.warn("Authentication failure - IP: {}, Path: {}, Method: {}, Exception: {} - {}",
                request.getRemoteAddr(),
                request.getServletPath(),
                request.getMethod(),
                authException.getClass().getSimpleName(),
                authException.getMessage());

        // Set HTTP response status to 401 Unauthorized
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        
        // Configure Content-Type for JSON response
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // Construct error response object with detailed information
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        errorResponse.put("error", "Unauthorized");
        errorResponse.put("message", determineErrorMessage(authException));
        errorResponse.put("path", request.getServletPath());

        // Write JSON error payload to response output stream
        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
        response.getWriter().flush();
    }

    /**
     * Determines the appropriate user-friendly error message based on the
     * AuthenticationException type.
     * 
     * <p>This method maps Spring Security exception types to messages that match
     * the original COBOL error handling patterns from COSGN00C.cbl while providing
     * modern, clear error descriptions suitable for REST API responses.
     * 
     * @param authException the authentication exception
     * @return a user-friendly error message
     */
    private String determineErrorMessage(AuthenticationException authException) {
        String exceptionClassName = authException.getClass().getSimpleName();
        String message = authException.getMessage();

        // Map exception types to user-friendly messages matching COBOL patterns
        switch (exceptionClassName) {
            case "BadCredentialsException":
                // Matches COSGN00C.cbl line 242: "Wrong Password. Try again..."
                return "Invalid username or password. Please try again.";
            
            case "UsernameNotFoundException":
                // Matches COSGN00C.cbl line 249: "User not found. Try again..."
                return "User not found. Please check your credentials and try again.";
            
            case "AccountExpiredException":
                return "Account has expired. Please contact your administrator.";
            
            case "CredentialsExpiredException":
                return "Credentials have expired. Please update your password.";
            
            case "DisabledException":
                return "Account is disabled. Please contact your administrator.";
            
            case "LockedException":
                return "Account is locked. Please contact your administrator.";
            
            case "InsufficientAuthenticationException":
                return "Full authentication is required to access this resource.";
            
            default:
                // Generic fallback matching COSGN00C.cbl line 254: "Unable to verify the User..."
                if (message != null && !message.isEmpty()) {
                    return message;
                }
                return "Authentication failed. Unable to verify your credentials.";
        }
    }
}
