/**
 * Authentication Context Provider
 * 
 * Purpose: Provides global authentication state management using React Context API,
 * replacing COBOL COMMAREA pseudo-conversational session state from COCOM01Y.cpy.
 * 
 * Original COBOL Structure: app/cpy/COCOM01Y.cpy (CARDDEMO-COMMAREA)
 * Original User Data: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA)
 * 
 * Transformation Details:
 * - CDEMO-USER-ID (PIC X(08)) → userId state variable (string)
 * - CDEMO-USER-TYPE (PIC X(01)) → userType state variable ('A' | 'U')
 *   - 88 CDEMO-USRTYP-ADMIN VALUE 'A' → isAdmin boolean check
 *   - 88 CDEMO-USRTYP-USER VALUE 'U' → isRegularUser boolean check
 * - SEC-USR-FNAME (PIC X(20)) → firstName state variable (string)
 * - SEC-USR-LNAME (PIC X(20)) → lastName state variable (string)
 * - CICS pseudo-conversational COMMAREA state → JWT token in localStorage
 * - CICS session management → React Context with JWT token expiration monitoring
 * 
 * Context Structure:
 * - user: Object containing userId, userType, firstName, lastName, isAdmin, isRegularUser
 * - token: JWT token string for API authentication
 * - loading: Boolean indicating authentication state initialization
 * - isAuthenticated: Boolean derived from token presence and validity
 * - login: Function to authenticate user with credentials
 * - logout: Function to terminate session and clear state
 * - isAdmin: Boolean indicating admin privileges (userType === 'A')
 * - getToken: Function returning current JWT token
 * 
 * Usage Pattern:
 * - Wrap application with AuthProvider at root level
 * - Use useAuth() hook in any component to access authentication state/functions
 * - Eliminates prop drilling throughout component tree
 * 
 * Session Persistence:
 * - JWT token stored in localStorage via authService
 * - Automatic session restoration on browser reload
 * - Token expiration monitoring with automatic logout
 * - Session survives page refreshes and browser restarts
 * 
 * Migration Context:
 * This context provider maintains functional equivalence with COBOL COMMAREA
 * session state while modernizing to JWT-based stateless authentication
 * as specified in Agent Action Plan sections 0.1, 0.3, and 0.10.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { createContext, useState, useEffect, useCallback, useContext } from 'react';
import { jwtDecode } from 'jwt-decode';
import authService from '../services/authService.js';

/**
 * User type constants matching COBOL 88-level conditions
 * From COCOM01Y.cpy lines 27-28:
 * - 88 CDEMO-USRTYP-ADMIN VALUE 'A'
 * - 88 CDEMO-USRTYP-USER VALUE 'U'
 */
const USER_TYPE = {
  ADMIN: 'A',
  USER: 'U',
};

/**
 * Token expiration check interval (milliseconds)
 * Check every 60 seconds for token expiration
 */
const TOKEN_CHECK_INTERVAL = 60000; // 60 seconds

/**
 * Authentication Context
 * 
 * Context object created with createContext providing authentication state
 * and functions to all consuming components.
 * 
 * Context Value Structure:
 * {
 *   user: {
 *     userId: string,
 *     userType: string ('A' | 'U'),
 *     firstName: string,
 *     lastName: string,
 *     isAdmin: boolean,
 *     isRegularUser: boolean
 *   } | null,
 *   token: string | null,
 *   loading: boolean,
 *   isAuthenticated: boolean,
 *   login: (credentials) => Promise<void>,
 *   logout: () => Promise<void>,
 *   isAdmin: boolean,
 *   getToken: () => string | null
 * }
 */
export const AuthContext = createContext(null);

/**
 * Authentication Provider Component
 * 
 * Wraps the application (or protected routes) to provide authentication
 * state and functions to all child components via React Context.
 * 
 * Replaces CICS COMMAREA pseudo-conversational session management with
 * React Context API and JWT token-based stateless authentication.
 * 
 * State Management:
 * - user: Current authenticated user object or null
 * - token: JWT token string or null
 * - loading: Boolean indicating initial authentication check in progress
 * 
 * Effects:
 * - Initialization: Checks for existing valid token on mount
 * - Token Expiration Monitoring: Periodic check for token expiration
 * 
 * @param {Object} props - Component props
 * @param {React.ReactNode} props.children - Child components to wrap with provider
 * @returns {React.Element} AuthContext.Provider wrapping children
 */
export function AuthProvider({ children }) {
  // Authentication state management
  // Replaces COBOL COMMAREA session state fields
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(null);
  const [loading, setLoading] = useState(true);

  /**
   * Initialize Authentication State
   * 
   * Effect runs on component mount to restore session from localStorage.
   * 
   * COBOL Equivalent:
   * - Reading CDEMO-USER-ID, CDEMO-USER-TYPE from COMMAREA on program entry
   * - Checking if CDEMO-USER-ID NOT = SPACES (session exists)
   * 
   * Flow:
   * 1. Check if user is authenticated (token exists and valid)
   * 2. If authenticated, retrieve user information from authService
   * 3. Update state with user object and token
   * 4. Set loading to false to indicate initialization complete
   * 
   * This enables session persistence across browser reloads,
   * maintaining user authentication state without re-login.
   */
  useEffect(() => {
    const initializeAuth = () => {
      try {
        // Check if user is authenticated with valid token
        if (authService.isAuthenticated()) {
          // Retrieve current user information from stored JWT token
          const currentUser = authService.getCurrentUser();
          
          if (currentUser) {
            // Extract token from localStorage (authService stores it)
            const storedToken = localStorage.getItem('jwt_token');
            
            // Update state with authenticated user and token
            setUser(currentUser);
            setToken(storedToken);
            
            if (import.meta.env.MODE === 'development') {
              console.log('[AuthContext] Session restored:', {
                userId: currentUser.userId,
                userType: currentUser.userType,
                isAdmin: currentUser.isAdmin,
              });
            }
          } else {
            // Token exists but user data invalid - clear state
            setUser(null);
            setToken(null);
          }
        } else {
          // No valid authentication - ensure state is clear
          setUser(null);
          setToken(null);
        }
      } catch (error) {
        // Error during initialization - clear state and log error
        console.error('[AuthContext] Initialization error:', error);
        setUser(null);
        setToken(null);
      } finally {
        // Always set loading to false when initialization complete
        setLoading(false);
      }
    };

    // Execute initialization
    initializeAuth();
  }, []); // Empty dependency array - run once on mount

  /**
   * Token Expiration Monitoring
   * 
   * Effect runs periodically to check token expiration and automatically
   * log out user when token expires.
   * 
   * COBOL Equivalent:
   * - CICS automatic session timeout handling
   * - Checking session validity before processing transactions
   * 
   * Flow:
   * 1. Set up interval timer to check token expiration every 60 seconds
   * 2. If token exists, check if it's still valid
   * 3. If token expired, automatically logout user
   * 4. Clean up interval timer on component unmount
   * 
   * This prevents users from making API calls with expired tokens
   * and provides automatic session cleanup.
   */
  useEffect(() => {
    // Only monitor if user is authenticated
    if (!token) {
      return;
    }

    // Set up periodic token expiration check
    const intervalId = setInterval(() => {
      try {
        // Check if token is still valid
        if (!authService.isAuthenticated()) {
          // Token expired - logout user automatically
          console.warn('[AuthContext] Token expired - automatic logout');
          
          // Clear authentication state
          setUser(null);
          setToken(null);
          
          // Clear localStorage via authService
          authService.clearAuthState();
          
          // Optional: Show notification to user
          if (import.meta.env.MODE === 'development') {
            console.log('[AuthContext] User logged out due to token expiration');
          }
        }
      } catch (error) {
        // Error checking token validity - log and continue
        console.error('[AuthContext] Token validation error:', error);
      }
    }, TOKEN_CHECK_INTERVAL);

    // Cleanup interval on unmount or when token changes
    return () => {
      clearInterval(intervalId);
    };
  }, [token]); // Re-run when token changes

  /**
   * Login Function
   * 
   * Authenticates user with userId and password credentials.
   * 
   * COBOL Equivalent:
   * - PROCESS-ENTER-KEY paragraph in COSGN00C.cbl
   * - READ-USER-SEC-FILE paragraph with password validation
   * - MOVE statements to populate COMMAREA fields
   * 
   * Flow:
   * 1. Call authService.login with credentials
   * 2. Receive authenticated user object with token
   * 3. Update React state with user and token
   * 4. authService automatically stores token in localStorage
   * 5. Throw error if authentication fails
   * 
   * Error Handling:
   * - Validation errors (empty userId/password)
   * - Authentication errors (invalid credentials)
   * - Network errors
   * - Server errors
   * 
   * @param {Object} credentials - User credentials
   * @param {string} credentials.userId - User ID (CDEMO-USER-ID)
   * @param {string} credentials.password - User password
   * @returns {Promise<void>} Resolves when login successful, rejects on error
   * @throws {Error} Authentication error with user-friendly message
   */
  const login = useCallback(async (credentials) => {
    try {
      // Validate credentials object
      if (!credentials) {
        throw new Error('Credentials are required');
      }

      // Call authService to perform authentication
      // This calls POST /api/auth/login and stores JWT token
      const authenticatedUser = await authService.login(credentials);

      // Extract user data and token from response
      const { token: jwtToken, ...userData } = authenticatedUser;

      // Update React state with authenticated user
      setUser(userData);
      setToken(jwtToken);

      if (import.meta.env.MODE === 'development') {
        console.log('[AuthContext] Login successful:', {
          userId: userData.userId,
          userType: userData.userType,
          isAdmin: userData.isAdmin,
        });
      }

      // Login successful - state updated, token stored in localStorage
    } catch (error) {
      // Clear any partial state on login failure
      setUser(null);
      setToken(null);

      // Log error for debugging
      console.error('[AuthContext] Login error:', error.message);

      // Re-throw error for caller to handle (e.g., display error message)
      throw error;
    }
  }, []); // No dependencies - stable function reference

  /**
   * Logout Function
   * 
   * Terminates user session and clears all authentication state.
   * 
   * COBOL Equivalent:
   * - PF3 key handler in COSGN00C.cbl (lines 88-90)
   * - EXEC CICS RETURN without COMMAREA (session termination)
   * - Clearing CDEMO-USER-ID and CDEMO-USER-TYPE
   * 
   * Flow:
   * 1. Call authService.logout to notify backend and clear localStorage
   * 2. Clear React state (user and token set to null)
   * 3. User redirected to login by protected route logic
   * 
   * Note: Logout always succeeds locally even if backend call fails,
   * ensuring user can always terminate their session.
   * 
   * @returns {Promise<void>} Resolves when logout complete
   */
  const logout = useCallback(async () => {
    try {
      // Call authService to logout (notifies backend, clears localStorage)
      await authService.logout();

      // Clear React state
      setUser(null);
      setToken(null);

      if (import.meta.env.MODE === 'development') {
        console.log('[AuthContext] Logout successful');
      }
    } catch (error) {
      // Logout error - still clear local state
      console.error('[AuthContext] Logout error:', error.message);

      // Force clear state even on error
      setUser(null);
      setToken(null);
      authService.clearAuthState();
    }
  }, []); // No dependencies - stable function reference

  /**
   * Get Token Function
   * 
   * Returns current JWT token for manual API requests.
   * 
   * Most API calls should use apiClient which automatically
   * injects the token, but this is available for special cases.
   * 
   * @returns {string|null} JWT token or null if not authenticated
   */
  const getToken = useCallback(() => {
    return token;
  }, [token]);

  /**
   * Compute derived authentication properties
   * 
   * These values are derived from state and don't need separate state management.
   */
  
  // Check if user is authenticated (has valid token)
  // Matches COBOL: IF CDEMO-USER-ID NOT = SPACES
  const isAuthenticated = Boolean(token) && Boolean(user);

  // Check if user is admin
  // Matches COBOL: IF CDEMO-USRTYP-ADMIN (88-level condition)
  const isAdmin = user ? user.userType === USER_TYPE.ADMIN : false;

  /**
   * Context Value Object
   * 
   * Object provided to all consuming components via useAuth hook.
   * Contains all authentication state and functions.
   */
  const contextValue = {
    // State
    user,              // Current user object or null
    token,             // JWT token string or null
    loading,           // Boolean: true during initialization, false when ready
    isAuthenticated,   // Boolean: true if user has valid token
    isAdmin,           // Boolean: true if user is admin (userType === 'A')
    
    // Functions
    login,             // Function: async (credentials) => Promise<void>
    logout,            // Function: async () => Promise<void>
    getToken,          // Function: () => string | null
  };

  return (
    <AuthContext.Provider value={contextValue}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * useAuth Custom Hook
 * 
 * Custom React hook to access authentication context in any component.
 * 
 * Usage Pattern:
 * ```javascript
 * import { useAuth } from '../context/AuthContext';
 * 
 * function MyComponent() {
 *   const { user, isAuthenticated, login, logout, isAdmin } = useAuth();
 *   
 *   if (!isAuthenticated) {
 *     return <LoginForm onLogin={login} />;
 *   }
 *   
 *   return (
 *     <div>
 *       <h1>Welcome, {user.firstName}!</h1>
 *       {isAdmin && <AdminPanel />}
 *       <button onClick={logout}>Logout</button>
 *     </div>
 *   );
 * }
 * ```
 * 
 * Error Handling:
 * Throws error if hook used outside of AuthProvider, ensuring proper setup.
 * 
 * @returns {Object} Authentication context value
 * @throws {Error} If used outside AuthProvider
 */
export function useAuth() {
  const context = useContext(AuthContext);

  // Validate hook is used within AuthProvider
  if (!context) {
    throw new Error(
      'useAuth must be used within an AuthProvider. ' +
      'Wrap your component tree with <AuthProvider> at the root level.'
    );
  }

  return context;
}

/**
 * Export Summary:
 * 
 * Named Exports:
 * - AuthContext: Context object for advanced usage (e.g., Consumer pattern)
 * - AuthProvider: Provider component to wrap application
 * - useAuth: Custom hook to access authentication context
 * 
 * Usage in App:
 * ```javascript
 * import { AuthProvider } from './context/AuthContext';
 * 
 * function App() {
 *   return (
 *     <AuthProvider>
 *       <AppRoutes />
 *     </AuthProvider>
 *   );
 * }
 * ```
 * 
 * Usage in Components:
 * ```javascript
 * import { useAuth } from './context/AuthContext';
 * 
 * function Dashboard() {
 *   const { user, isAuthenticated, logout } = useAuth();
 *   // Use authentication state and functions
 * }
 * ```
 */
