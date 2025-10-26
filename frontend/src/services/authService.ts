/**
 * Authentication Service Module
 * 
 * Converted from: COBOL program COSGN00C.cbl (Signon Screen)
 * Original function: User authentication via CICS signon screen and RACF validation
 * 
 * Purpose: Provides JWT token-based authentication API calls for user login and logout,
 *          replacing COBOL EXEC CICS authentication with modern REST API pattern.
 * 
 * Key Transformations:
 * - COBOL EXEC CICS READ FILE('USRSEC') → REST API POST /api/auth/login
 * - COBOL WS-USER-ID, WS-USER-PWD variables → TypeScript AuthRequest interface
 * - COBOL COMMAREA session management → JWT token storage in localStorage
 * - COBOL EXEC CICS SEND MAP → JSON response with AuthResponse structure
 * - RACF security validation → Spring Security BCrypt password validation
 * - COBOL file-status codes → HTTP status codes with comprehensive error handling
 * 
 * Authentication Flow:
 * 1. User submits credentials via SignonPage form
 * 2. login() calls POST /api/auth/login with {userId, password}
 * 3. Backend validates credentials against user_security table (BCrypt hash)
 * 4. Backend generates JWT token with user claims (userId, userType, expiration)
 * 5. Frontend stores token in localStorage for subsequent API requests
 * 6. getCurrentUser() fetches full user profile using stored JWT token
 * 7. logout() clears token from localStorage and calls POST /api/auth/logout
 * 
 * Security Notes:
 * - Passwords transmitted over HTTPS only (TLS encryption)
 * - JWT tokens stored in localStorage (alternative: sessionStorage)
 * - Token automatically attached to requests via api.ts interceptors
 * - Token expires after 1 hour (3600 seconds) requiring re-authentication
 * - Failed login attempts rate-limited by backend Spring Security
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/cbl/COSGN00C.cbl - Original COBOL signon program
 * @see backend/src/main/java/com/carddemo/controller/AuthController.java
 * @see backend/src/main/java/com/carddemo/security/JwtTokenProvider.java
 */

import api from './api';
import { User, AuthRequest, AuthResponse } from '../types/user';

/**
 * LocalStorage key constants for authentication token management
 * Matches constants defined in api.ts for consistent token handling
 */
const AUTH_TOKEN_KEY = 'authToken';
const REFRESH_TOKEN_KEY = 'refreshToken';

/**
 * User Login Function
 * 
 * Authenticates user credentials and obtains JWT authentication token.
 * Replaces COBOL EXEC CICS READ FILE('USRSEC') user validation flow.
 * 
 * COBOL Equivalent Flow (COSGN00C.cbl lines 209-257):
 * ```cobol
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *          RESP      (WS-RESP-CD)
 *     END-EXEC.
 *     IF SEC-USR-PWD = WS-USER-PWD
 *         MOVE WS-USER-ID TO CDEMO-USER-ID
 *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *         EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC
 *     ELSE
 *         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *     END-IF.
 * ```
 * 
 * Java/TypeScript Equivalent:
 * - POST /api/auth/login with {userId, password} JSON payload
 * - Backend validates password using BCrypt hash comparison
 * - Returns JWT token with embedded user claims (userId, userType)
 * - Token stored in localStorage for Authorization header injection
 * 
 * Error Handling:
 * - 401 Unauthorized: Invalid credentials (wrong userId or password)
 * - 403 Forbidden: Account locked or disabled
 * - 500 Internal Server Error: Database or system error
 * - Network errors: Connection timeout or server unavailable
 * 
 * @param authRequest - Login credentials containing userId and password
 * @returns Promise<AuthResponse> - JWT token, expiration time, and user type
 * @throws ApiError with status code and error messages on authentication failure
 * 
 * @example
 * ```typescript
 * try {
 *   const response = await login({ userId: 'USER0001', password: 'Pass1234' });
 *   console.log('Login successful, token:', response.token);
 *   console.log('Token expires in:', response.expiresIn, 'seconds');
 *   console.log('User type:', response.userType);
 * } catch (error) {
 *   console.error('Login failed:', error.message);
 * }
 * ```
 */
export const login = async (authRequest: AuthRequest): Promise<AuthResponse> => {
  try {
    // Validate input parameters (client-side validation)
    // Replaces COBOL field validation (lines 118-130)
    if (!authRequest.userId || authRequest.userId.trim() === '') {
      throw {
        status: 400,
        message: 'Please enter User ID ...',
        errors: ['User ID is required'],
      };
    }

    if (!authRequest.password || authRequest.password.trim() === '') {
      throw {
        status: 400,
        message: 'Please enter Password ...',
        errors: ['Password is required'],
      };
    }

    // Convert userId to uppercase (matches COBOL FUNCTION UPPER-CASE, line 132)
    const loginRequest: AuthRequest = {
      userId: authRequest.userId.trim().toUpperCase(),
      password: authRequest.password.trim(),
    };

    // Call backend authentication endpoint
    // Replaces COBOL EXEC CICS READ FILE('USRSEC') (lines 211-219)
    const response = await api.post<AuthResponse>('/auth/login', loginRequest);

    // Extract authentication response data
    const authResponse: AuthResponse = response.data;

    // Store JWT token in localStorage for subsequent API requests
    // Replaces COBOL COMMAREA session state management
    // Token will be automatically attached to requests via api.ts interceptor
    localStorage.setItem(AUTH_TOKEN_KEY, authResponse.token);

    // Store refresh token if provided by backend (future enhancement)
    // Enables token refresh without re-authentication
    if (response.data.refreshToken) {
      localStorage.setItem(REFRESH_TOKEN_KEY, response.data.refreshToken);
    }

    // Store user type for immediate role-based UI rendering
    // Avoids additional API call to fetch user details
    localStorage.setItem('userType', authResponse.userType);

    // Log successful authentication in development mode
    if (import.meta.env.DEV) {
      console.log('[authService] Login successful:', {
        userId: loginRequest.userId,
        userType: authResponse.userType,
        expiresIn: authResponse.expiresIn,
      });
    }

    // Return authentication response with token and user type
    return authResponse;
  } catch (error: any) {
    // Enhanced error handling with user-friendly messages
    // Maps COBOL error responses (lines 241-256) to HTTP error codes

    // Development logging
    if (import.meta.env.DEV) {
      console.error('[authService] Login failed:', error);
    }

    // Handle validation errors
    if (error.status === 400) {
      throw error;
    }

    // Handle authentication errors (401 Unauthorized)
    // COBOL equivalent: WHEN 13 "User not found" or password mismatch
    if (error.status === 401) {
      throw {
        status: 401,
        message: error.message || 'Invalid User ID or Password. Please try again.',
        errors: error.errors || ['Authentication failed'],
      };
    }

    // Handle forbidden access (403 Forbidden)
    // Account may be locked or disabled
    if (error.status === 403) {
      throw {
        status: 403,
        message: error.message || 'Account access denied. Please contact administrator.',
        errors: error.errors || ['Access forbidden'],
      };
    }

    // Handle server errors (500 Internal Server Error)
    // COBOL equivalent: WHEN OTHER "Unable to verify the User"
    if (error.status === 500) {
      throw {
        status: 500,
        message: error.message || 'Unable to verify credentials. Please try again later.',
        errors: error.errors || ['Server error'],
      };
    }

    // Handle network errors
    if (!error.status || error.status === 0) {
      throw {
        status: 0,
        message: 'Network error. Please check your connection and try again.',
        errors: ['Unable to connect to authentication server'],
      };
    }

    // Re-throw original error if not handled above
    throw error;
  }
};

/**
 * Get Current User Function
 * 
 * Fetches authenticated user's profile details using stored JWT token.
 * Provides full user information beyond basic authentication claims.
 * 
 * COBOL Equivalent:
 * In COBOL, user data is stored in COMMAREA after authentication:
 * ```cobol
 * MOVE WS-USER-ID   TO CDEMO-USER-ID
 * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 * ```
 * 
 * Java/TypeScript Equivalent:
 * - GET /api/auth/user with Authorization header containing JWT token
 * - Backend validates token and returns full user profile from user_security table
 * - Includes firstName, lastName, timestamps, and role information
 * 
 * Token Handling:
 * - Uses stored token from localStorage if no token parameter provided
 * - Token automatically attached to request via api.ts Authorization interceptor
 * - Backend validates token signature and expiration before returning user data
 * 
 * Error Handling:
 * - 401 Unauthorized: Token expired or invalid
 * - 404 Not Found: User record not found (rare, indicates data inconsistency)
 * - 500 Internal Server Error: Database or system error
 * 
 * @param token - Optional JWT token (uses localStorage token if not provided)
 * @returns Promise<User> - Complete user profile with role and timestamps
 * @throws ApiError with status code and error messages on failure
 * 
 * @example
 * ```typescript
 * try {
 *   const user = await getCurrentUser();
 *   console.log('Current user:', user.userId, user.userFirstName, user.userLastName);
 *   console.log('User role:', user.userType);
 *   console.log('Last login:', user.lastLoginTs);
 * } catch (error) {
 *   console.error('Failed to fetch user:', error.message);
 *   // Redirect to login if token expired
 * }
 * ```
 */
export const getCurrentUser = async (token?: string): Promise<User> => {
  try {
    // Use provided token or retrieve from localStorage
    // If neither exists, request will be made without Authorization header
    // and backend will return 401 Unauthorized
    const authToken = token || localStorage.getItem(AUTH_TOKEN_KEY);

    if (!authToken) {
      throw {
        status: 401,
        message: 'No authentication token found. Please log in.',
        errors: ['Authentication required'],
      };
    }

    // Call backend endpoint to fetch current user details
    // Token is automatically attached to request via api.ts interceptor
    const response = await api.get<User>('/auth/user');

    // Extract user data from response
    const user: User = response.data;

    // Log successful user fetch in development mode
    if (import.meta.env.DEV) {
      console.log('[authService] Current user fetched:', {
        userId: user.userId,
        userType: user.userType,
        lastLogin: user.lastLoginTs,
      });
    }

    // Return complete user profile
    return user;
  } catch (error: any) {
    // Enhanced error handling

    // Development logging
    if (import.meta.env.DEV) {
      console.error('[authService] Get current user failed:', error);
    }

    // Handle authentication errors (401 Unauthorized)
    // Token may be expired or invalid
    if (error.status === 401) {
      // Clear invalid token from localStorage
      localStorage.removeItem(AUTH_TOKEN_KEY);
      localStorage.removeItem(REFRESH_TOKEN_KEY);
      localStorage.removeItem('userType');

      throw {
        status: 401,
        message: error.message || 'Session expired. Please log in again.',
        errors: error.errors || ['Authentication token invalid or expired'],
      };
    }

    // Handle user not found (404 Not Found)
    // Rare case indicating data inconsistency
    if (error.status === 404) {
      throw {
        status: 404,
        message: error.message || 'User profile not found.',
        errors: error.errors || ['User record missing'],
      };
    }

    // Handle server errors (500 Internal Server Error)
    if (error.status === 500) {
      throw {
        status: 500,
        message: error.message || 'Unable to fetch user profile. Please try again later.',
        errors: error.errors || ['Server error'],
      };
    }

    // Handle network errors
    if (!error.status || error.status === 0) {
      throw {
        status: 0,
        message: 'Network error. Please check your connection and try again.',
        errors: ['Unable to connect to server'],
      };
    }

    // Re-throw original error if not handled above
    throw error;
  }
};

/**
 * User Logout Function
 * 
 * Terminates user session by clearing authentication token and notifying backend.
 * Replaces COBOL EXEC CICS RETURN with session cleanup.
 * 
 * COBOL Equivalent Flow:
 * ```cobol
 * WHEN DFHPF3
 *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
 *     PERFORM SEND-PLAIN-TEXT
 *     EXEC CICS RETURN END-EXEC.
 * ```
 * 
 * Java/TypeScript Equivalent:
 * - POST /api/auth/logout to notify backend of session termination
 * - Backend invalidates any server-side session data or refresh tokens
 * - Frontend removes JWT token from localStorage
 * - User redirected to login page by calling component
 * 
 * Session Cleanup:
 * - Removes authToken from localStorage (JWT authentication token)
 * - Removes refreshToken from localStorage (if present)
 * - Removes userType from localStorage (cached role information)
 * - Clears any other cached user data
 * 
 * Error Handling:
 * - Best effort logout: Clears local storage even if backend call fails
 * - Network errors don't prevent local session cleanup
 * - User is considered logged out regardless of backend response
 * 
 * @returns Promise<void> - Resolves when logout complete (local and backend)
 * 
 * @example
 * ```typescript
 * try {
 *   await logout();
 *   console.log('Logout successful');
 *   // Redirect to login page
 *   navigate('/login');
 * } catch (error) {
 *   console.error('Logout warning:', error.message);
 *   // User is still logged out locally even if backend call failed
 *   navigate('/login');
 * }
 * ```
 */
export const logout = async (): Promise<void> => {
  try {
    // Log logout attempt in development mode
    if (import.meta.env.DEV) {
      console.log('[authService] Logout initiated');
    }

    // Call backend logout endpoint to invalidate server-side session
    // Backend may:
    // - Invalidate refresh tokens
    // - Log logout event for audit trail
    // - Clean up server-side session data
    // - Update last_login_ts timestamp
    try {
      await api.post('/auth/logout');
      
      if (import.meta.env.DEV) {
        console.log('[authService] Backend logout successful');
      }
    } catch (backendError: any) {
      // Log backend error but continue with local cleanup
      // Best effort logout: User is logged out locally even if backend fails
      if (import.meta.env.DEV) {
        console.warn('[authService] Backend logout failed (continuing with local cleanup):', backendError);
      }
    }

    // Clear all authentication data from localStorage
    // This is the critical step that logs user out on client side
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem('userType');

    // Clear any other cached user data (future enhancement)
    // localStorage.removeItem('currentUser');
    // localStorage.removeItem('userPreferences');

    // Log successful logout in development mode
    if (import.meta.env.DEV) {
      console.log('[authService] Logout complete - all tokens cleared');
    }

    // Logout complete - calling component should redirect to login page
  } catch (error: any) {
    // Even if an error occurs, ensure local storage is cleared
    // User should always be logged out locally
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem('userType');

    // Development logging
    if (import.meta.env.DEV) {
      console.error('[authService] Logout error (local cleanup completed):', error);
    }

    // Don't throw error - logout should always succeed from user perspective
    // Calling component should redirect to login page regardless
  }
};

/**
 * Authentication Service Object
 * 
 * Provides organized access to all authentication functions.
 * Replaces COBOL COSGN00C program with modern service object pattern.
 * 
 * Usage Pattern:
 * ```typescript
 * import { authService } from './services/authService';
 * 
 * // Login
 * const authResponse = await authService.login({ userId, password });
 * 
 * // Get current user
 * const user = await authService.getCurrentUser();
 * 
 * // Logout
 * await authService.logout();
 * ```
 * 
 * Alternative Direct Import:
 * ```typescript
 * import { login, getCurrentUser, logout } from './services/authService';
 * 
 * await login({ userId, password });
 * const user = await getCurrentUser();
 * await logout();
 * ```
 * 
 * @constant authService
 */
export const authService = {
  login,
  getCurrentUser,
  logout,
};

/**
 * Default export for convenient importing
 * 
 * @example
 * ```typescript
 * import authService from './services/authService';
 * await authService.login({ userId, password });
 * ```
 */
export default authService;
