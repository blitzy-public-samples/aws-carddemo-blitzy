/**
 * CardDemo Authentication Service
 * 
 * JWT token-based authentication service providing user sign-on, sign-off, and session 
 * management operations for the CardDemo frontend application. This service transforms 
 * COBOL COSGN00C.cbl CICS transaction (CC00) authentication logic into modern REST API 
 * operations while preserving identical business logic and security semantics.
 * 
 * COBOL Source Mapping:
 * - COBOL Program: COSGN00C.cbl (Sign-on Screen for CardDemo Application)
 * - CICS Transaction: CC00
 * - VSAM File: USRSEC (User Security file)
 * - Security Model: Two-tier role system (Regular User 'R', Administrative User 'A')
 * 
 * Transformation Details:
 * - EXEC CICS READ DATASET('USRSEC') → POST /api/auth/login REST endpoint
 * - USRSEC file-based authentication → JWT token-based authentication
 * - CICS COMMAREA session state → localStorage JWT token + user profile
 * - CICS XCTL program transfer → React Router navigation based on user role
 * - RACF security → Spring Security with JWT tokens
 * 
 * Key Features:
 * - User credential validation with backend authentication endpoint
 * - JWT token storage and retrieval from localStorage
 * - User profile management (userId, userName, userType/role)
 * - Token expiration detection and session state verification
 * - Token refresh capability for extended sessions
 * - Role-based access control support (ROLE_USER, ROLE_ADMIN)
 * - Error handling matching COBOL error messages
 * 
 * Security Implementation:
 * - Passwords transmitted over HTTPS (TLS 1.3)
 * - JWT tokens stored in localStorage (carddemo_jwt_token)
 * - User profile stored in localStorage (carddemo_user)
 * - Automatic token injection via apiClient interceptors
 * - Token expiration handled by apiClient 401 response interceptor
 * 
 * @module services/authService
 */

import apiClient from './apiClient';

/**
 * LocalStorage Keys
 * 
 * Defines localStorage key constants for JWT token and user profile storage.
 * These keys match the apiClient interceptor expectations for automatic
 * token injection into authenticated requests.
 */
const TOKEN_KEY = 'carddemo_jwt_token';
const USER_KEY = 'carddemo_user';

/**
 * Token Management - Set Token
 * 
 * Stores JWT authentication token in localStorage for persistent session management.
 * The token is automatically retrieved by apiClient request interceptor and injected
 * into Authorization header for all subsequent authenticated API requests.
 * 
 * Maps COBOL CICS COMMAREA session state persistence across pseudo-conversational
 * transaction boundaries to JWT token-based stateless authentication.
 * 
 * @private
 * @param {string} token - JWT token string received from backend authentication endpoint
 */
const setToken = (token) => {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  }
};

/**
 * Token Management - Get Token
 * 
 * Retrieves JWT authentication token from localStorage for session validation
 * and manual token access when needed by application components.
 * 
 * @private
 * @returns {string|null} JWT token string or null if not authenticated
 */
const getToken = () => {
  return localStorage.getItem(TOKEN_KEY);
};

/**
 * Token Management - Remove Token
 * 
 * Removes JWT authentication token from localStorage during logout or session expiration.
 * This invalidates the client-side session state and requires re-authentication.
 * 
 * Maps COBOL CICS session termination to JWT token removal for sign-off functionality.
 * 
 * @private
 */
const removeToken = () => {
  localStorage.removeItem(TOKEN_KEY);
};

/**
 * User Profile Management - Set User
 * 
 * Stores authenticated user profile information in localStorage for application-wide
 * access to current user context, role information, and display data.
 * 
 * User Profile Structure:
 * {
 *   userId: string,      // User ID from COBOL SEC-USR-ID (PIC X(8))
 *   userName: string,    // User name from COBOL SEC-USR-FNAME + SEC-USR-LNAME
 *   userType: string,    // User type 'R' (Regular) or 'A' (Admin) from COBOL SEC-USR-TYPE
 *   role: string         // Spring Security role: ROLE_USER or ROLE_ADMIN
 * }
 * 
 * @private
 * @param {object} user - User profile object containing userId, userName, userType, role
 */
const setUser = (user) => {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }
};

/**
 * User Profile Management - Get User
 * 
 * Retrieves authenticated user profile from localStorage for display and authorization
 * checks throughout the application.
 * 
 * @private
 * @returns {object|null} User profile object or null if not authenticated
 */
const getUser = () => {
  const userJson = localStorage.getItem(USER_KEY);
  return userJson ? JSON.parse(userJson) : null;
};

/**
 * User Profile Management - Remove User
 * 
 * Removes user profile from localStorage during logout or session expiration.
 * 
 * @private
 */
const removeUser = () => {
  localStorage.removeItem(USER_KEY);
};

/**
 * User Authentication - Login
 * 
 * Authenticates user credentials with backend authentication endpoint and establishes
 * authenticated session by storing JWT token and user profile in localStorage.
 * 
 * COBOL Mapping:
 * Maps COBOL COSGN00C.cbl READ-USER-SEC-FILE paragraph logic:
 * 
 * ```cobol
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *          KEYLENGTH (LENGTH OF WS-USER-ID)
 *     END-EXEC.
 * 
 *     IF SEC-USR-PWD = WS-USER-PWD
 *         MOVE WS-USER-ID   TO CDEMO-USER-ID
 *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *         IF CDEMO-USRTYP-ADMIN
 *              EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC
 *         ELSE
 *              EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC
 *         END-IF
 *     ELSE
 *         MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *     END-IF
 * ```
 * 
 * Backend Endpoint: POST /api/auth/login
 * 
 * Request Body:
 * {
 *   "userId": "string(8)",    // User ID matching COBOL WS-USER-ID (PIC X(8))
 *   "password": "string(8)"   // Password matching COBOL WS-USER-PWD (PIC X(8))
 * }
 * 
 * Response Body (Success - 200 OK):
 * {
 *   "token": "string",           // JWT token for authenticated requests
 *   "userId": "string",          // Authenticated user ID
 *   "userName": "string",        // User full name for display
 *   "userType": "string",        // User type: 'R' (Regular) or 'A' (Admin)
 *   "role": "string",            // Spring Security role: ROLE_USER or ROLE_ADMIN
 *   "responseCode": "string"     // Response code (00 = success)
 * }
 * 
 * Error Responses:
 * - 401 Unauthorized: Invalid credentials (wrong password or user not found)
 * - 400 Bad Request: Invalid request format or missing required fields
 * - 500 Internal Server Error: Backend authentication service failure
 * 
 * Error Messages (matching COBOL COSGN00C.cbl):
 * - "Wrong Password. Try again ..." (line 242) - Password mismatch
 * - "User not found. Try again ..." (line 249) - User ID not found in USRSEC
 * - "Unable to verify the User ..." (line 254) - System error during authentication
 * 
 * @param {string} userId - User ID (8 characters, matching COBOL PIC X(8))
 * @param {string} password - User password (8 characters, matching COBOL PIC X(8))
 * @returns {Promise<object>} Promise resolving to user profile with token
 * @throws {Error} Authentication error with message property containing user-friendly error message
 * 
 * @example
 * // Successful authentication
 * try {
 *   const user = await authService.login('USER001', 'password');
 *   console.log(`Welcome ${user.userName}! Role: ${user.role}`);
 *   // Navigate to appropriate menu based on role
 *   if (user.userType === 'A') {
 *     navigate('/admin'); // Maps to COBOL XCTL COADM01C
 *   } else {
 *     navigate('/menu');  // Maps to COBOL XCTL COMEN01C
 *   }
 * } catch (error) {
 *   console.error(error.message); // Display error to user
 * }
 * 
 * @example
 * // Authentication failure
 * try {
 *   await authService.login('USER001', 'wrongpassword');
 * } catch (error) {
 *   // error.message === "Wrong Password. Try again ..."
 *   // Display error message to user, keep focus on password field
 * }
 */
const login = async (userId, password) => {
  try {
    // Validate input parameters
    if (!userId || !password) {
      throw new Error('User ID and password are required.');
    }

    // Trim inputs to match COBOL fixed-length field expectations
    // COBOL: WS-USER-ID PIC X(8), WS-USER-PWD PIC X(8)
    const trimmedUserId = userId.trim();
    const trimmedPassword = password.trim();

    // Validate input lengths (COBOL enforces 8-character max via PIC clause)
    if (trimmedUserId.length === 0 || trimmedUserId.length > 8) {
      throw new Error('User ID must be between 1 and 8 characters.');
    }

    if (trimmedPassword.length === 0 || trimmedPassword.length > 8) {
      throw new Error('Password must be between 1 and 8 characters.');
    }

    // Call backend authentication endpoint
    // Maps COBOL: EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
    const response = await apiClient.post('/auth/login', {
      userId: trimmedUserId,
      password: trimmedPassword
    });

    // Extract authentication response data
    const { token, userId: authenticatedUserId, userName, userType, role, responseCode } = response.data;

    // Validate response contains required fields
    if (!token || !authenticatedUserId) {
      throw new Error('Invalid authentication response from server.');
    }

    // Store JWT token in localStorage
    // Maps COBOL COMMAREA session state persistence
    setToken(token);

    // Build user profile object for application-wide access
    // Maps COBOL CDEMO-USER-ID, CDEMO-USER-TYPE from COMMAREA
    const userProfile = {
      userId: authenticatedUserId,
      userName: userName || authenticatedUserId, // Fallback to userId if name not provided
      userType: userType || 'R',                 // Default to Regular user if not specified
      role: role || 'ROLE_USER',                 // Default to ROLE_USER if not specified
      responseCode: responseCode || '00'         // Success response code
    };

    // Store user profile in localStorage
    setUser(userProfile);

    // Return user profile for caller to handle navigation
    // Caller should check userType to determine navigation target:
    // - userType 'A' (Admin) → navigate to /admin (maps COBOL XCTL COADM01C)
    // - userType 'R' (Regular) → navigate to /menu (maps COBOL XCTL COMEN01C)
    return userProfile;

  } catch (error) {
    // Handle authentication errors with COBOL-equivalent error messages
    
    // Check if error response contains specific error message from backend
    if (error.response) {
      const { status, data } = error.response;

      // 401 Unauthorized - Invalid credentials
      // Maps COBOL RESP-CD evaluation with password mismatch or user not found
      if (status === 401) {
        // Check for specific error messages from backend matching COBOL errors
        const errorMessage = data.message || '';
        
        // Password mismatch error (COBOL line 242: 'Wrong Password. Try again ...')
        if (errorMessage.includes('password') || errorMessage.includes('Password')) {
          throw new Error('Wrong Password. Try again ...');
        }
        
        // User not found error (COBOL line 249: 'User not found. Try again ...')
        if (errorMessage.includes('not found') || errorMessage.includes('Not found')) {
          throw new Error('User not found. Try again ...');
        }

        // Generic authentication failure
        throw new Error('Wrong Password. Try again ...');
      }

      // 400 Bad Request - Validation errors
      if (status === 400) {
        throw new Error(data.message || 'Invalid user ID or password format.');
      }

      // 500 Internal Server Error
      // Maps COBOL RESP-CD OTHER condition (line 252-256: 'Unable to verify the User ...')
      if (status >= 500) {
        throw new Error('Unable to verify the User ...');
      }

      // Other HTTP errors
      throw new Error(data.message || 'Authentication failed. Please try again.');
    }

    // Network error - no response received
    // Maps COBOL CICS communication failure conditions
    if (error.networkError) {
      throw new Error('Network error. Please check your connection and try again.');
    }

    // Re-throw error with message property for consistent error handling
    throw new Error(error.message || 'Authentication failed. Please try again.');
  }
};

/**
 * User Authentication - Logout
 * 
 * Terminates authenticated user session by removing JWT token and user profile from
 * localStorage and notifying backend authentication service to invalidate server-side
 * session (if applicable with Redis session storage).
 * 
 * COBOL Mapping:
 * Maps COBOL CICS RETURN with PF3 key handling (lines 88-90):
 * ```cobol
 * WHEN DFHPF3
 *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
 *     PERFORM SEND-PLAIN-TEXT
 * ```
 * 
 * Backend Endpoint: POST /api/auth/logout
 * 
 * The backend endpoint invalidates server-side session state (Redis session) and
 * adds JWT token to blacklist (if implemented) to prevent reuse of expired tokens.
 * 
 * Post-Logout State:
 * - JWT token removed from localStorage (client-side session termination)
 * - User profile removed from localStorage
 * - apiClient interceptor will not inject Authorization header on subsequent requests
 * - User must call login() to re-authenticate and establish new session
 * 
 * @returns {Promise<void>} Promise resolving when logout is complete
 * 
 * @example
 * // Logout user and navigate to login page
 * try {
 *   await authService.logout();
 *   navigate('/login');
 * } catch (error) {
 *   // Logout failed on backend, but clear local state anyway
 *   console.error('Logout error:', error.message);
 *   navigate('/login'); // Still navigate to login page
 * }
 */
const logout = async () => {
  try {
    // Notify backend to invalidate server-side session
    // Maps COBOL CICS RETURN with session termination
    // Backend will invalidate Redis session and add token to blacklist
    await apiClient.post('/auth/logout');

  } catch (error) {
    // Log error but continue with local cleanup
    // Even if backend logout fails, we clear local authentication state
    console.error('Backend logout failed:', error.message);
  } finally {
    // Always remove local authentication state regardless of backend response
    // This ensures user is logged out locally even if backend call fails
    removeToken();
    removeUser();
  }
};

/**
 * Session Management - Get Current User
 * 
 * Retrieves authenticated user profile from localStorage for display and authorization
 * checks throughout the application. Returns null if user is not authenticated.
 * 
 * User Profile Structure:
 * {
 *   userId: string,      // User ID (e.g., 'USER001')
 *   userName: string,    // User full name (e.g., 'John Smith')
 *   userType: string,    // User type: 'R' (Regular) or 'A' (Admin)
 *   role: string         // Spring Security role: ROLE_USER or ROLE_ADMIN
 * }
 * 
 * Maps COBOL COMMAREA CDEMO-USER-ID and CDEMO-USER-TYPE fields accessed throughout
 * CardDemo programs to retrieve current user context for authorization checks.
 * 
 * @returns {object|null} User profile object or null if not authenticated
 * 
 * @example
 * // Check current user and display in header
 * const user = authService.getCurrentUser();
 * if (user) {
 *   console.log(`Logged in as: ${user.userName} (${user.userType})`);
 * } else {
 *   console.log('Not logged in');
 * }
 * 
 * @example
 * // Role-based rendering
 * const user = authService.getCurrentUser();
 * if (user && user.userType === 'A') {
 *   // Show admin menu options (maps COBOL CDEMO-USRTYP-ADMIN check)
 *   return <AdminMenu />;
 * } else {
 *   // Show regular user menu options
 *   return <UserMenu />;
 * }
 */
const getCurrentUser = () => {
  return getUser();
};

/**
 * Session Management - Is Authenticated
 * 
 * Checks if user has valid authentication session by verifying presence of JWT token
 * in localStorage. This provides quick authentication state check for routing guards
 * and conditional rendering.
 * 
 * Note: This method only checks for token presence, not token validity or expiration.
 * Token expiration is handled by apiClient response interceptor which automatically
 * clears authentication state on 401 Unauthorized responses.
 * 
 * Maps COBOL CICS session validation by checking COMMAREA CDEMO-USER-ID presence
 * to determine if user has authenticated session context.
 * 
 * @returns {boolean} True if JWT token exists (user authenticated), false otherwise
 * 
 * @example
 * // Protected route guard
 * const ProtectedRoute = ({ children }) => {
 *   const isAuthenticated = authService.isAuthenticated();
 *   
 *   if (!isAuthenticated) {
 *     return <Navigate to="/login" replace />;
 *   }
 *   
 *   return children;
 * };
 * 
 * @example
 * // Conditional rendering based on authentication state
 * const Header = () => {
 *   const isAuthenticated = authService.isAuthenticated();
 *   
 *   return (
 *     <header>
 *       {isAuthenticated ? (
 *         <button onClick={handleLogout}>Logout</button>
 *       ) : (
 *         <button onClick={() => navigate('/login')}>Login</button>
 *       )}
 *     </header>
 *   );
 * };
 */
const isAuthenticated = () => {
  const token = getToken();
  return token !== null && token !== undefined && token !== '';
};

/**
 * Session Management - Refresh Token
 * 
 * Refreshes JWT authentication token by requesting new token from backend using
 * current valid token. This extends user session without requiring re-authentication
 * with credentials, providing seamless user experience for long-running sessions.
 * 
 * Backend Endpoint: POST /api/auth/refresh
 * 
 * Request Headers:
 * - Authorization: Bearer {current-jwt-token}
 * 
 * Response Body (Success - 200 OK):
 * {
 *   "token": "string",           // New JWT token with extended expiration
 *   "userId": "string",          // User ID (unchanged)
 *   "userName": "string",        // User name (unchanged)
 *   "userType": "string",        // User type (unchanged)
 *   "role": "string"             // User role (unchanged)
 * }
 * 
 * Token Refresh Strategy:
 * - Typically called before token expiration (e.g., 5 minutes before expiry)
 * - Can be triggered by user activity or periodic background refresh
 * - On success, replaces existing token in localStorage with new token
 * - On failure (401), user session expired and must re-authenticate
 * 
 * Maps COBOL CICS session extension patterns where pseudo-conversational
 * transactions maintain session context across multiple user interactions
 * without requiring re-authentication.
 * 
 * @returns {Promise<object>} Promise resolving to updated user profile with new token
 * @throws {Error} Token refresh error (typically 401 requiring re-authentication)
 * 
 * @example
 * // Refresh token before expiration
 * try {
 *   const updatedUser = await authService.refreshToken();
 *   console.log('Token refreshed successfully');
 * } catch (error) {
 *   console.error('Token refresh failed, re-authentication required');
 *   navigate('/login');
 * }
 * 
 * @example
 * // Periodic token refresh (every 15 minutes)
 * useEffect(() => {
 *   const interval = setInterval(async () => {
 *     if (authService.isAuthenticated()) {
 *       try {
 *         await authService.refreshToken();
 *       } catch (error) {
 *         // Token refresh failed, session expired
 *         clearInterval(interval);
 *         navigate('/login');
 *       }
 *     }
 *   }, 15 * 60 * 1000); // 15 minutes
 *   
 *   return () => clearInterval(interval);
 * }, []);
 */
const refreshToken = async () => {
  try {
    // Check if user is currently authenticated
    if (!isAuthenticated()) {
      throw new Error('No active session to refresh.');
    }

    // Call backend token refresh endpoint
    // Current token automatically injected by apiClient request interceptor
    const response = await apiClient.post('/auth/refresh');

    // Extract refreshed token and user data
    const { token, userId, userName, userType, role } = response.data;

    // Validate response contains new token
    if (!token) {
      throw new Error('Invalid token refresh response from server.');
    }

    // Update stored JWT token with new token
    setToken(token);

    // Update user profile with refreshed data (in case of role changes)
    const userProfile = {
      userId: userId || getCurrentUser().userId,
      userName: userName || getCurrentUser().userName,
      userType: userType || getCurrentUser().userType,
      role: role || getCurrentUser().role
    };

    // Update user profile in localStorage
    setUser(userProfile);

    // Return updated user profile
    return userProfile;

  } catch (error) {
    // Handle token refresh errors
    
    // 401 Unauthorized - Token expired or invalid, user must re-authenticate
    if (error.status === 401) {
      // Clear authentication state (handled by apiClient interceptor)
      removeToken();
      removeUser();
      throw new Error('Session expired. Please login again.');
    }

    // Network error
    if (error.networkError) {
      throw new Error('Network error. Unable to refresh session.');
    }

    // Other errors
    throw new Error(error.message || 'Unable to refresh session. Please login again.');
  }
};

/**
 * Authentication Service Export
 * 
 * Exports authentication service object with public methods for user authentication,
 * session management, and token operations. This service is consumed by React components
 * (particularly LoginComponent.jsx) and routing guards to manage authentication state
 * throughout the CardDemo frontend application.
 * 
 * Exported Methods:
 * - login(userId, password): Authenticate user and establish session
 * - logout(): Terminate user session and clear authentication state
 * - getCurrentUser(): Retrieve current authenticated user profile
 * - isAuthenticated(): Check if user has valid authentication session
 * - refreshToken(): Refresh JWT token to extend session
 * - getToken(): Get current JWT token (for manual use if needed)
 * 
 * Usage Pattern:
 * All authentication-related operations throughout the frontend application should
 * use this service to ensure consistent authentication behavior, error handling,
 * and state management matching COBOL COSGN00C program authentication patterns.
 * 
 * Integration Points:
 * - LoginComponent.jsx: Calls login() and handles navigation based on user role
 * - App routing: Uses isAuthenticated() for protected route guards
 * - Header/Navigation: Uses getCurrentUser() for user display and logout button
 * - API calls: Token automatically injected by apiClient, no direct service interaction
 * - Session management: Uses refreshToken() for extended session support
 */
export default {
  login,
  logout,
  getCurrentUser,
  isAuthenticated,
  refreshToken,
  getToken
};
