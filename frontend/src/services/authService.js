/**
 * Authentication Service
 * 
 * Purpose: Handles all authentication operations for the CardDemo application,
 * replacing COBOL COSGN00C.cbl CICS transaction (CC00) authentication logic.
 * 
 * Original COBOL Program: app/cbl/COSGN00C.cbl
 * Original BMS Screen: app/bms/COSGN00.bms
 * 
 * Transformation Details:
 * - EXEC CICS RECEIVE MAP (COSGN0A) → JSON request with userId/password
 * - EXEC CICS READ DATASET(USRSEC) → POST /api/auth/login REST API call
 * - VSAM USRSEC file user validation → Spring Boot backend authentication
 * - Password checking (SEC-USR-PWD = WS-USER-PWD) → Server-side BCrypt validation
 * - COMMAREA session state (CDEMO-USER-ID, CDEMO-USER-TYPE) → JWT token in localStorage
 * - EXEC CICS XCTL to COADM01C/COMEN01C based on user type → Client-side routing after login
 * 
 * Security Model:
 * - JWT token-based stateless authentication (replaces RACF)
 * - Two-tier role model: ADMIN ('A') and USER ('U')
 * - Tokens stored in browser localStorage
 * - Automatic token expiration checking
 * - 401 Unauthorized handling with redirect to login
 * 
 * API Endpoints:
 * - POST /api/auth/login: User authentication with userId/password
 * - POST /api/auth/logout: Session termination and token invalidation
 * 
 * Migration Context:
 * This service maintains functional equivalence with COBOL authentication while
 * modernizing to JWT-based stateless authentication as specified in Agent Action
 * Plan sections 0.1, 0.3, and 0.10.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { jwtDecode } from 'jwt-decode';
import apiClient from '../utils/apiClient.js';

/**
 * Local storage key constants
 * Matching the constants used in apiClient.js for consistency
 */
const TOKEN_STORAGE_KEY = 'jwt_token';
const TOKEN_EXPIRY_STORAGE_KEY = 'jwt_token_expiry';
const USER_INFO_STORAGE_KEY = 'user_info';

/**
 * User type constants
 * Matching COBOL 88-level condition names from COCOM01Y.cpy:
 * - 88 CDEMO-USRTYP-ADMIN VALUE 'A'
 * - 88 CDEMO-USRTYP-USER VALUE 'U'
 */
const USER_TYPE = {
  ADMIN: 'A',
  USER: 'U',
};

/**
 * Authentication Service Object
 * 
 * Provides methods for user authentication, session management,
 * and token handling. Replaces COBOL COSGN00C.cbl program logic.
 */
const authService = {
  
  /**
   * Login Method
   * 
   * Authenticates user with userId and password credentials.
   * 
   * COBOL Equivalent:
   * - PROCESS-ENTER-KEY paragraph
   * - READ-USER-SEC-FILE paragraph
   * - EXEC CICS READ DATASET(USRSEC) with password validation
   * 
   * Flow:
   * 1. Validate input credentials (non-empty userId and password)
   * 2. Send POST request to /api/auth/login endpoint
   * 3. Receive JWT token and user information from backend
   * 4. Store JWT token in localStorage with expiration
   * 5. Store user information for quick access
   * 6. Return user object with userId, userType, and token
   * 
   * Error Handling:
   * - 'Please enter User ID' → Empty userId validation (line 118-122 in COSGN00C.cbl)
   * - 'Please enter Password' → Empty password validation (line 123-127 in COSGN00C.cbl)
   * - 'User not found' → RESP-CD = 13 from VSAM READ (line 247-251 in COSGN00C.cbl)
   * - 'Wrong Password' → Password mismatch (line 242-245 in COSGN00C.cbl)
   * - Network/server errors → Generic error handling
   * 
   * @param {Object} credentials - User credentials object
   * @param {string} credentials.userId - User ID (mapped from USERIDI in COSGN0AI)
   * @param {string} credentials.password - User password (mapped from PASSWDI in COSGN0AI)
   * @returns {Promise<Object>} User object with userId, userType, firstName, lastName, and token
   * @throws {Error} Authentication error with user-friendly message
   */
  async login(credentials) {
    try {
      // Input validation - replaces COBOL field validation (lines 118-130)
      if (!credentials || !credentials.userId || credentials.userId.trim() === '') {
        throw new Error('Please enter User ID');
      }
      
      if (!credentials.password || credentials.password.trim() === '') {
        throw new Error('Please enter Password');
      }
      
      // Convert userId to uppercase to match COBOL FUNCTION UPPER-CASE (line 132-133)
      const loginRequest = {
        userId: credentials.userId.trim().toUpperCase(),
        password: credentials.password, // Password sent as-is for server-side validation
      };
      
      // POST request to authentication endpoint
      // Replaces: EXEC CICS READ DATASET(USRSEC) ... (lines 211-219)
      const response = await apiClient.post('/auth/login', loginRequest);
      
      // Extract response data
      // Response structure from Spring Boot backend:
      // {
      //   jwtToken: "eyJhbGciOiJIUzI1NiIs...",
      //   userId: "USER001",
      //   userType: "A" or "U",
      //   firstName: "John",
      //   lastName: "Doe",
      //   expiresAt: 1234567890 (timestamp in milliseconds)
      // }
      const { jwtToken, userId, userType, firstName, lastName, expiresAt } = response.data;
      
      // Validate response contains required fields
      if (!jwtToken || !userId || !userType) {
        throw new Error('Invalid response from authentication server');
      }
      
      // Store JWT token in localStorage
      // This replaces COMMAREA session state management in CICS
      localStorage.setItem(TOKEN_STORAGE_KEY, jwtToken);
      
      // Store token expiration time if provided
      if (expiresAt) {
        localStorage.setItem(TOKEN_EXPIRY_STORAGE_KEY, expiresAt.toString());
      }
      
      // Create user info object matching COBOL COMMAREA structure:
      // CDEMO-USER-ID (line 25 in COCOM01Y.cpy)
      // CDEMO-USER-TYPE (line 26 in COCOM01Y.cpy)
      const userInfo = {
        userId: userId,
        userType: userType,
        firstName: firstName || '',
        lastName: lastName || '',
        isAdmin: userType === USER_TYPE.ADMIN, // Matches 88 CDEMO-USRTYP-ADMIN
        isRegularUser: userType === USER_TYPE.USER, // Matches 88 CDEMO-USRTYP-USER
      };
      
      // Store user info for quick access without JWT decoding
      localStorage.setItem(USER_INFO_STORAGE_KEY, JSON.stringify(userInfo));
      
      // Log successful authentication (development only)
      if (import.meta.env.MODE === 'development') {
        console.log('[AuthService] Login successful:', {
          userId: userInfo.userId,
          userType: userInfo.userType,
          isAdmin: userInfo.isAdmin,
        });
      }
      
      // Return user object with token
      return {
        ...userInfo,
        token: jwtToken,
      };
      
    } catch (error) {
      // Error handling - transform backend errors to user-friendly messages
      // Matches COBOL error messages from COSGN00C.cbl
      
      let errorMessage = 'Authentication failed. Please try again.';
      
      if (error.message) {
        // Check for validation errors (from our code)
        if (error.message.includes('Please enter')) {
          errorMessage = error.message;
        }
        // Check for network errors
        else if (error.message.includes('Network')) {
          errorMessage = 'Network error. Please check your connection and try again.';
        }
        // Use backend error message if available
        else if (error.status === 401) {
          // Matches COBOL: 'Wrong Password. Try again ...' (line 242)
          // or 'User not found. Try again ...' (line 249)
          errorMessage = error.message || 'Invalid User ID or Password. Try again.';
        } else if (error.status === 404) {
          // User not found - matches RESP-CD = 13 (line 247-251)
          errorMessage = 'User not found. Try again.';
        } else if (error.status === 500) {
          // Server error - matches COBOL: 'Unable to verify the User ...' (line 254)
          errorMessage = 'Unable to verify the user. Please try again later.';
        } else if (error.message !== 'Authentication failed. Please try again.') {
          errorMessage = error.message;
        }
      }
      
      // Log error for debugging
      console.error('[AuthService] Login error:', {
        message: errorMessage,
        status: error.status,
        originalError: error,
      });
      
      // Clear any partial authentication state
      this.clearAuthState();
      
      // Throw error with user-friendly message
      throw new Error(errorMessage);
    }
  },
  
  /**
   * Logout Method
   * 
   * Terminates user session and clears authentication state.
   * 
   * COBOL Equivalent:
   * - PF3 key handler (lines 88-90 in COSGN00C.cbl)
   * - EXEC CICS RETURN without COMMAREA (session termination)
   * 
   * Flow:
   * 1. Send POST request to /api/auth/logout endpoint (optional backend cleanup)
   * 2. Clear JWT token from localStorage
   * 3. Clear user information from localStorage
   * 4. Clear token expiration timestamp
   * 
   * Note: Logout always succeeds locally even if backend call fails,
   * ensuring user can always clear their local session.
   * 
   * @returns {Promise<void>}
   */
  async logout() {
    try {
      // Attempt to notify backend of logout
      // This allows backend to invalidate the token if using token blacklist
      await apiClient.post('/auth/logout');
      
      // Log successful logout
      if (import.meta.env.MODE === 'development') {
        console.log('[AuthService] Logout successful - backend notified');
      }
      
    } catch (error) {
      // Logout failure on backend should not prevent local cleanup
      // User can always clear their local session
      console.warn('[AuthService] Logout backend call failed, proceeding with local cleanup:', error.message);
    } finally {
      // Always clear local authentication state
      // This ensures user can log out even if backend is unavailable
      this.clearAuthState();
    }
  },
  
  /**
   * Get Current User Method
   * 
   * Retrieves currently authenticated user information from stored JWT token.
   * 
   * COBOL Equivalent:
   * - Reading CDEMO-USER-ID and CDEMO-USER-TYPE from COMMAREA
   * - Used throughout application to check user identity and permissions
   * 
   * Flow:
   * 1. Check localStorage for user info (fast path)
   * 2. If not found, decode JWT token to extract user information
   * 3. Validate token is not expired
   * 4. Return user object or null if not authenticated
   * 
   * @returns {Object|null} User object with userId, userType, firstName, lastName, isAdmin, isRegularUser
   */
  getCurrentUser() {
    try {
      // Fast path: Check for stored user info
      const storedUserInfo = localStorage.getItem(USER_INFO_STORAGE_KEY);
      if (storedUserInfo) {
        const userInfo = JSON.parse(storedUserInfo);
        
        // Verify token still exists and is not expired
        if (this.isAuthenticated()) {
          return userInfo;
        } else {
          // Token expired, clear state
          this.clearAuthState();
          return null;
        }
      }
      
      // Fallback: Decode JWT token to extract user information
      const token = localStorage.getItem(TOKEN_STORAGE_KEY);
      if (!token) {
        return null;
      }
      
      // Check token expiration before decoding
      if (!this.isAuthenticated()) {
        this.clearAuthState();
        return null;
      }
      
      // Decode JWT token to extract claims
      // JWT payload structure from Spring Boot backend:
      // {
      //   sub: "USER001" (userId),
      //   userType: "A" or "U",
      //   firstName: "John",
      //   lastName: "Doe",
      //   iat: issued_at_timestamp,
      //   exp: expiration_timestamp
      // }
      const decodedToken = jwtDecode(token);
      
      // Extract user information from JWT claims
      const userInfo = {
        userId: decodedToken.sub || decodedToken.userId,
        userType: decodedToken.userType,
        firstName: decodedToken.firstName || '',
        lastName: decodedToken.lastName || '',
        isAdmin: decodedToken.userType === USER_TYPE.ADMIN,
        isRegularUser: decodedToken.userType === USER_TYPE.USER,
      };
      
      // Store user info for future fast path access
      localStorage.setItem(USER_INFO_STORAGE_KEY, JSON.stringify(userInfo));
      
      return userInfo;
      
    } catch (error) {
      // JWT decode error or invalid token format
      console.error('[AuthService] Error getting current user:', error);
      
      // Clear invalid authentication state
      this.clearAuthState();
      
      return null;
    }
  },
  
  /**
   * Get Authorization Header Method
   * 
   * Returns Authorization header value with Bearer token for authenticated API requests.
   * 
   * Usage: Manual addition of Authorization header when apiClient is not used
   * (apiClient automatically injects this header via request interceptor)
   * 
   * @returns {string|null} Authorization header value ("Bearer <token>") or null if not authenticated
   */
  getAuthHeader() {
    const token = localStorage.getItem(TOKEN_STORAGE_KEY);
    
    if (!token) {
      return null;
    }
    
    // Verify token is not expired
    if (!this.isAuthenticated()) {
      this.clearAuthState();
      return null;
    }
    
    // Return Authorization header value with Bearer scheme
    return `Bearer ${token}`;
  },
  
  /**
   * Is Authenticated Method
   * 
   * Checks if user is currently authenticated with a valid non-expired token.
   * 
   * COBOL Equivalent:
   * - Checking if CDEMO-USER-ID is not SPACES in COMMAREA
   * - Verifying active CICS session
   * 
   * Flow:
   * 1. Check if JWT token exists in localStorage
   * 2. Check if token expiration timestamp exists
   * 3. Compare expiration time with current time
   * 4. Return true if token exists and is not expired, false otherwise
   * 
   * @returns {boolean} True if user is authenticated with valid token, false otherwise
   */
  isAuthenticated() {
    // Check if token exists
    const token = localStorage.getItem(TOKEN_STORAGE_KEY);
    if (!token) {
      return false;
    }
    
    // Check token expiration if expiry timestamp is stored
    const tokenExpiry = localStorage.getItem(TOKEN_EXPIRY_STORAGE_KEY);
    if (tokenExpiry) {
      const expiryTime = parseInt(tokenExpiry, 10);
      const currentTime = Date.now();
      
      // Token is expired
      if (currentTime >= expiryTime) {
        // Clear expired token
        this.clearAuthState();
        return false;
      }
    }
    
    // Token exists and is not expired
    return true;
  },
  
  /**
   * Clear Authentication State (Internal Helper Method)
   * 
   * Removes all authentication-related data from localStorage.
   * 
   * This is an internal helper method used by logout, login error handling,
   * and expiration detection. It ensures complete cleanup of authentication state.
   */
  clearAuthState() {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    localStorage.removeItem(TOKEN_EXPIRY_STORAGE_KEY);
    localStorage.removeItem(USER_INFO_STORAGE_KEY);
    
    if (import.meta.env.MODE === 'development') {
      console.log('[AuthService] Authentication state cleared');
    }
  },
  
  /**
   * Get User Type Method (Convenience Method)
   * 
   * Returns the current user's type (ADMIN or USER).
   * 
   * COBOL Equivalent:
   * - Checking CDEMO-USER-TYPE value ('A' or 'U')
   * - 88 CDEMO-USRTYP-ADMIN VALUE 'A'
   * - 88 CDEMO-USRTYP-USER VALUE 'U'
   * 
   * @returns {string|null} User type ('A' or 'U') or null if not authenticated
   */
  getUserType() {
    const user = this.getCurrentUser();
    return user ? user.userType : null;
  },
  
  /**
   * Is Admin Method (Convenience Method)
   * 
   * Checks if current user has admin privileges.
   * 
   * COBOL Equivalent:
   * - IF CDEMO-USRTYP-ADMIN (line 230 in COSGN00C.cbl)
   * - Used to determine routing to COADM01C vs COMEN01C
   * 
   * @returns {boolean} True if user is admin, false otherwise
   */
  isAdmin() {
    const user = this.getCurrentUser();
    return user ? user.isAdmin : false;
  },
  
  /**
   * Is Regular User Method (Convenience Method)
   * 
   * Checks if current user is a regular (non-admin) user.
   * 
   * COBOL Equivalent:
   * - ELSE clause after IF CDEMO-USRTYP-ADMIN (line 235 in COSGN00C.cbl)
   * 
   * @returns {boolean} True if user is regular user, false otherwise
   */
  isRegularUser() {
    const user = this.getCurrentUser();
    return user ? user.isRegularUser : false;
  },
  
  /**
   * Get User ID Method (Convenience Method)
   * 
   * Returns the current user's ID.
   * 
   * COBOL Equivalent:
   * - CDEMO-USER-ID from COMMAREA
   * 
   * @returns {string|null} User ID or null if not authenticated
   */
  getUserId() {
    const user = this.getCurrentUser();
    return user ? user.userId : null;
  },
  
  /**
   * Get Full Name Method (Convenience Method)
   * 
   * Returns the current user's full name (firstName + lastName).
   * 
   * COBOL Equivalent:
   * - SEC-USR-FNAME and SEC-USR-LNAME from CSUSR01Y.cpy
   * 
   * @returns {string} Full name or empty string if not authenticated
   */
  getFullName() {
    const user = this.getCurrentUser();
    if (!user) {
      return '';
    }
    
    const parts = [];
    if (user.firstName) {
      parts.push(user.firstName.trim());
    }
    if (user.lastName) {
      parts.push(user.lastName.trim());
    }
    
    return parts.join(' ');
  },
};

/**
 * Export authentication service as default export
 * 
 * Usage in React components:
 * 
 * import authService from '../services/authService.js';
 * 
 * // Login
 * const user = await authService.login({ userId: 'USER001', password: 'pass123' });
 * 
 * // Check authentication
 * if (authService.isAuthenticated()) {
 *   const currentUser = authService.getCurrentUser();
 * }
 * 
 * // Logout
 * await authService.logout();
 */
export default authService;
