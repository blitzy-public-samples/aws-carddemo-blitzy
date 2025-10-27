/**
 * Authentication Context Module
 * 
 * Purpose: React Context provider for global authentication state management.
 *          Replaces COBOL COCOM01Y.cpy COMMAREA session management and RACF
 *          user authentication with JWT-based stateless authentication.
 * 
 * COBOL to React Context Transformation:
 * - COBOL COMMAREA session state → React Context + localStorage
 * - COBOL EXEC CICS RETRIEVE → useEffect + localStorage.getItem()
 * - COBOL CDEMO-USER-ID → user.userId in context state
 * - COBOL CDEMO-USER-TYPE → user.userType in context state
 * - RACF security token → JWT token in localStorage
 * - EXEC CICS SIGNOFF → logout() function clearing state
 * 
 * COBOL Source Mappings:
 * - COCOM01Y.cpy CARDDEMO-COMMAREA → AuthContextType interface
 * - CSUSR01Y.cpy SEC-USER-DATA → User interface from types/user.ts
 * - COSGN00C.cbl signon logic → login() function + authService integration
 * 
 * Authentication Flow:
 * 1. User submits credentials via SignonPage component
 * 2. login() calls authService.login() with userId and password
 * 3. Backend validates credentials and returns JWT token with claims
 * 4. Token stored in localStorage (survives page refresh)
 * 5. getCurrentUser() fetches full user profile using token
 * 6. User object and token stored in context state
 * 7. isAuthenticated flag set to true
 * 8. Protected routes check isAuthenticated before rendering
 * 9. logout() clears token and resets authentication state
 * 
 * Session Persistence:
 * - JWT token stored in localStorage persists across page refreshes
 * - useEffect hook on mount restores auth state from localStorage
 * - Token validated by backend on each API request
 * - Expired tokens trigger automatic logout and redirect to login
 * 
 * Role-Based Access Control:
 * - user.userType field contains role code ('A', 'U', 'O')
 * - Maps to COBOL SEC-USR-TYPE from CSUSR01Y.cpy
 * - Used for conditional UI rendering and route protection
 * - Replaces RACF role-based authorization
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/cpy/COCOM01Y.cpy - Original COBOL COMMAREA structure
 * @see app/cpy/CSUSR01Y.cpy - Original COBOL user data structure
 * @see app/cbl/COSGN00C.cbl - Original COBOL signon program
 * @see frontend/src/services/authService.ts - Authentication API service
 * @see backend/src/main/java/com/carddemo/security/JwtTokenProvider.java
 */

import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { User, AuthRequest } from '../types/user';
import { authService } from '../services/authService';

/**
 * LocalStorage key constants for token management
 * Must match constants in authService.ts for consistency
 */
const AUTH_TOKEN_KEY = 'authToken';
const AUTH_USER_KEY = 'authUser';

/**
 * Authentication Context Type Definition
 * 
 * Defines the shape of authentication context state and operations.
 * Replaces COBOL CARDDEMO-COMMAREA structure (COCOM01Y.cpy) with modern
 * React Context pattern for global state management.
 * 
 * COBOL COMMAREA Fields → React Context Mapping:
 * - CDEMO-USER-ID (PIC X(08)) → user.userId: string
 * - CDEMO-USER-TYPE (PIC X(01)) → user.userType: string
 * - CDEMO-FROM-PROGRAM → Navigation handled by React Router
 * - CDEMO-TO-PROGRAM → Navigation handled by React Router
 * - CDEMO-PGM-CONTEXT → Component state (isLoading, etc.)
 * 
 * @interface AuthContextType
 */
interface AuthContextType {
  /**
   * Authentication status flag
   * 
   * Indicates whether user is currently authenticated with valid JWT token.
   * Replaces COBOL session existence check (CICS RETRIEVE success).
   * 
   * Usage:
   * - Protected routes check this flag before rendering
   * - Navigation components show/hide based on auth status
   * - Header displays login/logout button based on this flag
   * 
   * Default: false (user not authenticated)
   */
  isAuthenticated: boolean;

  /**
   * Current authenticated user profile
   * 
   * Contains full user details from user_security table.
   * Replaces COBOL SEC-USER-DATA structure (CSUSR01Y.cpy) stored in COMMAREA.
   * 
   * Structure:
   * - userId: User ID (SEC-USR-ID, max 8 chars)
   * - userFirstName: First name (SEC-USR-FNAME, max 20 chars)
   * - userLastName: Last name (SEC-USR-LNAME, max 20 chars)
   * - userType: Role code (SEC-USR-TYPE, 1 char: 'A'/'U'/'O')
   * - createdAt: Account creation timestamp
   * - updatedAt: Last update timestamp
   * - lastLoginTs: Last successful login timestamp
   * 
   * Null when user is not authenticated.
   */
  user: User | null;

  /**
   * JWT authentication token
   * 
   * JSON Web Token containing user identity and claims.
   * Replaces COBOL session token and RACF security context.
   * 
   * Token Structure:
   * - header.payload.signature (Base64Url encoded)
   * - Claims: userId, userType, issued-at, expiration
   * - Expiration: 1 hour (3600 seconds)
   * - Storage: localStorage for persistence
   * - Transmission: Authorization header "Bearer <token>"
   * 
   * Null when user is not authenticated.
   */
  token: string | null;

  /**
   * Login function
   * 
   * Authenticates user credentials and establishes authenticated session.
   * Replaces COBOL COSGN00C.cbl signon logic and RACF authentication.
   * 
   * COBOL Equivalent:
   * ```cobol
   * READ-USER-SEC-FILE.
   *     EXEC CICS READ FILE('USRSEC') INTO(SEC-USER-DATA)
   *          RIDFLD(WS-USER-ID) END-EXEC.
   *     IF SEC-USR-PWD = WS-USER-PWD
   *         MOVE WS-USER-ID TO CDEMO-USER-ID
   *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
   *         EXEC CICS XCTL PROGRAM('COMEN01C') END-EXEC
   *     END-IF.
   * ```
   * 
   * Authentication Flow:
   * 1. Calls authService.login() with userId and password
   * 2. Backend validates credentials against BCrypt hash
   * 3. Backend generates JWT token with user claims
   * 4. Stores token in localStorage
   * 5. Calls authService.getCurrentUser() to fetch full profile
   * 6. Stores user object in localStorage and context state
   * 7. Sets isAuthenticated to true
   * 
   * Error Handling:
   * - Throws error with status and message on authentication failure
   * - 401: Invalid credentials (wrong userId or password)
   * - 403: Account locked or disabled
   * - 500: Server error
   * - Network errors handled with user-friendly messages
   * 
   * @param userId - User ID (max 8 characters, from SEC-USR-ID)
   * @param password - User password (max 8 characters, from SEC-USR-PWD)
   * @returns Promise<void> - Resolves on successful authentication
   * @throws Error with status code and message on failure
   */
  login: (userId: string, password: string) => Promise<void>;

  /**
   * Logout function
   * 
   * Terminates authenticated session and clears all authentication state.
   * Replaces COBOL EXEC CICS SIGNOFF and session cleanup.
   * 
   * COBOL Equivalent:
   * ```cobol
   * WHEN DFHPF3
   *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
   *     PERFORM SEND-PLAIN-TEXT
   *     EXEC CICS RETURN END-EXEC.
   * ```
   * 
   * Logout Flow:
   * 1. Clears JWT token from localStorage
   * 2. Clears user object from localStorage
   * 3. Resets context state (isAuthenticated=false, user=null, token=null)
   * 4. Calling component should redirect to login page
   * 
   * Note: Backend token invalidation is handled by token expiration.
   * Stateless JWT tokens are not explicitly invalidated on server side.
   * 
   * @returns void - Logout always succeeds
   */
  logout: () => void;

  /**
   * Loading state flag
   * 
   * Indicates whether authentication state is being initialized or updated.
   * Used to show loading indicators during async operations.
   * 
   * True during:
   * - Initial mount (restoring state from localStorage)
   * - Login operation (calling backend API)
   * - Fetching user profile
   * 
   * False when:
   * - Authentication state is stable
   * - User is logged out
   * - Authentication operations complete
   * 
   * Usage:
   * - Show loading spinner during authentication
   * - Prevent route rendering until auth state initialized
   * - Disable form inputs during login operation
   */
  isLoading: boolean;
}

/**
 * Authentication Context
 * 
 * React Context object for authentication state distribution.
 * Created with default values matching AuthContextType interface.
 * 
 * Default values provide type safety and prevent undefined errors
 * when context is accessed before provider initialization.
 * 
 * @constant AuthContext
 */
const AuthContext = createContext<AuthContextType>({
  isAuthenticated: false,
  user: null,
  token: null,
  login: async () => {
    throw new Error('AuthContext not initialized. Wrap component tree with AuthProvider.');
  },
  logout: () => {
    throw new Error('AuthContext not initialized. Wrap component tree with AuthProvider.');
  },
  isLoading: true,
});

/**
 * Authentication Provider Props Interface
 * 
 * Defines props accepted by AuthProvider component.
 * Standard pattern for React Context providers.
 * 
 * @interface AuthProviderProps
 */
interface AuthProviderProps {
  /**
   * Child components to wrap with authentication context
   * 
   * All children components and their descendants will have access
   * to authentication state and functions via useAuth() hook.
   * 
   * Type: ReactNode accepts any valid React children (elements,
   * fragments, strings, numbers, arrays, portals, etc.)
   */
  children: ReactNode;
}

/**
 * Authentication Provider Component
 * 
 * Manages authentication state and provides context to entire application.
 * Must wrap App component to provide authentication to all routes and components.
 * 
 * Replaces COBOL COMMAREA session management with React Context pattern:
 * - COBOL COMMAREA storage → React Context state
 * - EXEC CICS RETRIEVE → useEffect + localStorage
 * - EXEC CICS STORE → localStorage.setItem()
 * - CICS session persistence → localStorage persistence
 * 
 * State Management:
 * - isAuthenticated: Boolean flag for authentication status
 * - user: User object with profile data (from SEC-USER-DATA)
 * - token: JWT token string for API authorization
 * - isLoading: Boolean flag for async operation status
 * 
 * Initialization Flow (useEffect on mount):
 * 1. Check localStorage for existing authToken
 * 2. Check localStorage for existing authUser
 * 3. If both exist, parse user JSON and restore authentication state
 * 4. If parsing fails, clear invalid data from localStorage
 * 5. Set isLoading to false
 * 
 * Usage:
 * ```typescript
 * // App.tsx
 * import { AuthProvider } from './context/AuthContext';
 * 
 * function App() {
 *   return (
 *     <AuthProvider>
 *       <Router>
 *         <Routes>
 *           <Route path="/login" element={<SignonPage />} />
 *           <Route path="/" element={<ProtectedRoute><MainMenu /></ProtectedRoute>} />
 *         </Routes>
 *       </Router>
 *     </AuthProvider>
 *   );
 * }
 * ```
 * 
 * @param props - Component props containing children
 * @returns JSX.Element - Provider component wrapping children
 */
export const AuthProvider: React.FC<AuthProviderProps> = ({ children }) => {
  // Authentication state
  // Replaces COBOL CARDDEMO-COMMAREA fields
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  /**
   * Initialize authentication state from localStorage on component mount
   * 
   * Replaces COBOL EXEC CICS RETRIEVE restoring session state from CICS storage.
   * Runs once when AuthProvider mounts (application startup or page refresh).
   * 
   * COBOL Equivalent:
   * ```cobol
   * EXEC CICS RETRIEVE INTO(CARDDEMO-COMMAREA) END-EXEC.
   * IF EIBRESP = DFHRESP(NORMAL)
   *     MOVE CDEMO-USER-ID TO WS-USER-ID
   *     MOVE CDEMO-USER-TYPE TO WS-USER-TYPE
   * END-IF.
   * ```
   * 
   * Restoration Flow:
   * 1. Retrieve authToken from localStorage
   * 2. Retrieve authUser JSON string from localStorage
   * 3. If both exist:
   *    a. Parse authUser JSON to User object
   *    b. Set token state
   *    c. Set user state
   *    d. Set isAuthenticated to true
   * 4. If parsing fails (corrupted data):
   *    a. Log error to console
   *    b. Clear invalid data from localStorage
   *    c. Leave user logged out
   * 5. Set isLoading to false (initialization complete)
   * 
   * Error Handling:
   * - JSON.parse() errors caught and logged
   * - Invalid data cleared from localStorage
   * - User remains logged out on any error
   * - isLoading always set to false to unblock UI
   */
  useEffect(() => {
    const initializeAuth = () => {
      try {
        // Retrieve stored authentication data
        // Replaces COBOL EXEC CICS RETRIEVE
        const storedToken = localStorage.getItem(AUTH_TOKEN_KEY);
        const storedUserJson = localStorage.getItem(AUTH_USER_KEY);

        // Check if both token and user data exist
        if (storedToken && storedUserJson) {
          try {
            // Parse user JSON from localStorage
            const parsedUser: User = JSON.parse(storedUserJson);

            // Restore authentication state
            // Replaces COBOL COMMAREA restoration
            setToken(storedToken);
            setUser(parsedUser);
            setIsAuthenticated(true);

            // Log successful restoration in development mode
            if (import.meta.env.DEV) {
              console.log('[AuthContext] Session restored from localStorage:', {
                userId: parsedUser.userId,
                userType: parsedUser.userType,
              });
            }
          } catch (parseError) {
            // Handle JSON parsing errors
            console.error('[AuthContext] Failed to parse stored user data:', parseError);

            // Clear corrupted data from localStorage
            localStorage.removeItem(AUTH_TOKEN_KEY);
            localStorage.removeItem(AUTH_USER_KEY);

            // User remains logged out (default state)
            if (import.meta.env.DEV) {
              console.warn('[AuthContext] Cleared corrupted authentication data');
            }
          }
        } else {
          // No stored authentication data - user is logged out
          if (import.meta.env.DEV) {
            console.log('[AuthContext] No stored authentication found - user logged out');
          }
        }
      } catch (error) {
        // Handle any unexpected errors during initialization
        console.error('[AuthContext] Unexpected error during auth initialization:', error);

        // Ensure clean state on error
        localStorage.removeItem(AUTH_TOKEN_KEY);
        localStorage.removeItem(AUTH_USER_KEY);
      } finally {
        // Always set loading to false to unblock UI
        // Even if errors occurred, UI should render
        setIsLoading(false);
      }
    };

    // Execute initialization
    initializeAuth();
  }, []); // Empty dependency array - run once on mount

  /**
   * Login Function Implementation
   * 
   * Authenticates user credentials and establishes authenticated session.
   * Replaces COBOL COSGN00C.cbl signon program logic and RACF authentication.
   * 
   * COBOL Signon Logic (COSGN00C.cbl lines 209-257):
   * ```cobol
   * READ-USER-SEC-FILE.
   *     EXEC CICS READ DATASET(WS-USRSEC-FILE)
   *          INTO(SEC-USER-DATA)
   *          RIDFLD(WS-USER-ID)
   *          RESP(WS-RESP-CD)
   *     END-EXEC.
   *     
   *     EVALUATE WS-RESP-CD
   *       WHEN DFHRESP(NORMAL)
   *         IF SEC-USR-PWD = WS-USER-PWD
   *           MOVE WS-USER-ID TO CDEMO-USER-ID
   *           MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
   *           EXEC CICS XCTL PROGRAM('COMEN01C')
   *                COMMAREA(CARDDEMO-COMMAREA)
   *           END-EXEC
   *         ELSE
   *           MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
   *         END-IF
   *       WHEN 13
   *         MOVE 'User not found ...' TO WS-MESSAGE
   *       WHEN OTHER
   *         MOVE 'Unable to verify the User ...' TO WS-MESSAGE
   *     END-EVALUATE.
   * ```
   * 
   * Java/React Login Flow:
   * 1. Set isLoading to true
   * 2. Call authService.login(userId, password)
   * 3. Backend validates credentials with BCrypt
   * 4. Backend returns JWT token with user claims
   * 5. Store token in localStorage
   * 6. Call authService.getCurrentUser(token) for full profile
   * 7. Store user object in localStorage
   * 8. Update context state (token, user, isAuthenticated)
   * 9. Set isLoading to false
   * 
   * @param userId - User ID (max 8 chars, uppercase)
   * @param password - User password (max 8 chars)
   * @throws Error with status and message on authentication failure
   */
  const login = async (userId: string, password: string): Promise<void> => {
    try {
      setIsLoading(true);

      // Log login attempt in development mode
      if (import.meta.env.DEV) {
        console.log('[AuthContext] Login attempt:', { userId });
      }

      // Authenticate with backend
      // Replaces COBOL EXEC CICS READ FILE('USRSEC') and password check
      const authRequest: AuthRequest = { userId, password };
      const authResponse = await authService.login(authRequest);

      // authService.login() has already stored token in localStorage
      // Extract token for context state
      const jwtToken = authResponse.token;

      // Fetch full user profile using authenticated token
      // Provides complete user data beyond basic authentication claims
      const userDetails: User = await authService.getCurrentUser(jwtToken);

      // Store user object in localStorage for session persistence
      // Replaces COBOL EXEC CICS STORE COMMAREA
      localStorage.setItem(AUTH_USER_KEY, JSON.stringify(userDetails));

      // Update context state
      // Replaces COBOL COMMAREA updates
      setToken(jwtToken);
      setUser(userDetails);
      setIsAuthenticated(true);

      // Log successful login in development mode
      if (import.meta.env.DEV) {
        console.log('[AuthContext] Login successful:', {
          userId: userDetails.userId,
          userType: userDetails.userType,
          userFirstName: userDetails.userFirstName,
          userLastName: userDetails.userLastName,
        });
      }
    } catch (error: any) {
      // Log login failure in development mode
      if (import.meta.env.DEV) {
        console.error('[AuthContext] Login failed:', error);
      }

      // Re-throw error for calling component to handle
      // SignonPage will display error message to user
      throw error;
    } finally {
      // Always set loading to false when operation completes
      setIsLoading(false);
    }
  };

  /**
   * Logout Function Implementation
   * 
   * Terminates authenticated session and clears all authentication state.
   * Replaces COBOL EXEC CICS SIGNOFF and session cleanup.
   * 
   * COBOL Logout Logic:
   * ```cobol
   * WHEN DFHPF3
   *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
   *     PERFORM SEND-PLAIN-TEXT
   *     EXEC CICS RETURN END-EXEC.
   * ```
   * 
   * Session Cleanup Flow:
   * 1. Clear JWT token from localStorage
   * 2. Clear user object from localStorage
   * 3. Reset context state:
   *    - setToken(null)
   *    - setUser(null)
   *    - setIsAuthenticated(false)
   * 4. Calling component redirects to login page
   * 
   * Note: This is a local logout only. Backend token invalidation is
   * handled by token expiration (stateless JWT). For explicit backend
   * logout, authService.logout() could be called, but it's not required
   * for stateless authentication.
   * 
   * Best Practice: For production systems with refresh tokens or server-side
   * sessions, call authService.logout() to notify backend before clearing
   * local state.
   */
  const logout = (): void => {
    // Log logout in development mode
    if (import.meta.env.DEV) {
      console.log('[AuthContext] Logout initiated');
    }

    // Clear authentication data from localStorage
    // Replaces COBOL session cleanup
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(AUTH_USER_KEY);

    // Reset context state
    // Replaces COBOL COMMAREA cleanup
    setToken(null);
    setUser(null);
    setIsAuthenticated(false);

    // Log successful logout in development mode
    if (import.meta.env.DEV) {
      console.log('[AuthContext] Logout complete - authentication state cleared');
    }

    // Note: Calling component should redirect to login page
    // Example: navigate('/login')
  };

  // Build context value object
  // Contains all state and functions exposed to consumers
  const contextValue: AuthContextType = {
    isAuthenticated,
    user,
    token,
    login,
    logout,
    isLoading,
  };

  // Render provider with context value
  return (
    <AuthContext.Provider value={contextValue}>
      {children}
    </AuthContext.Provider>
  );
};

/**
 * useAuth Custom Hook
 * 
 * Provides convenient access to authentication context throughout application.
 * Simplifies context consumption and ensures proper provider usage.
 * 
 * Replaces COBOL COPY COCOM01Y statement with hook-based access to session data.
 * 
 * Usage Pattern:
 * ```typescript
 * // Any component within AuthProvider
 * import { useAuth } from '@/context/AuthContext';
 * 
 * function MyComponent() {
 *   const { isAuthenticated, user, login, logout } = useAuth();
 *   
 *   // Check authentication status
 *   if (!isAuthenticated) {
 *     return <Navigate to="/login" />;
 *   }
 *   
 *   // Access user data (from COBOL SEC-USER-DATA)
 *   const userName = `${user?.userFirstName} ${user?.userLastName}`;
 *   
 *   // Role-based rendering (from COBOL SEC-USR-TYPE)
 *   if (user?.userType === 'A') {
 *     return <AdminPanel />;
 *   }
 *   
 *   // Handle login
 *   const handleLogin = async () => {
 *     try {
 *       await login(userId, password);
 *       navigate('/');
 *     } catch (error) {
 *       setError(error.message);
 *     }
 *   };
 *   
 *   // Handle logout
 *   const handleLogout = () => {
 *     logout();
 *     navigate('/login');
 *   };
 *   
 *   return <div>Welcome, {userName}!</div>;
 * }
 * ```
 * 
 * Error Handling:
 * Throws error if used outside AuthProvider. This prevents accidental usage
 * before context is initialized and provides clear error message.
 * 
 * @returns AuthContextType - Authentication state and functions
 * @throws Error if used outside AuthProvider component tree
 * 
 * @example
 * // Protected Route Component
 * function ProtectedRoute({ children }) {
 *   const { isAuthenticated, isLoading } = useAuth();
 *   
 *   if (isLoading) {
 *     return <LoadingSpinner />;
 *   }
 *   
 *   if (!isAuthenticated) {
 *     return <Navigate to="/login" replace />;
 *   }
 *   
 *   return <>{children}</>;
 * }
 * 
 * @example
 * // Header Component
 * function Header() {
 *   const { user, logout } = useAuth();
 *   
 *   return (
 *     <header>
 *       <span>Welcome, {user?.userFirstName}!</span>
 *       <button onClick={logout}>Logout</button>
 *     </header>
 *   );
 * }
 * 
 * @example
 * // Admin-Only Feature
 * function AdminFeature() {
 *   const { user } = useAuth();
 *   
 *   if (user?.userType !== 'A') {
 *     return null; // Hide for non-admin users
 *   }
 *   
 *   return <AdminPanel />;
 * }
 */
export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);

  // Validate context is defined (component is within AuthProvider)
  if (context === undefined) {
    throw new Error(
      'useAuth must be used within an AuthProvider. ' +
      'Wrap your component tree with <AuthProvider> in App.tsx.'
    );
  }

  return context;
};

/**
 * Default export for flexible import patterns
 * 
 * Allows importing AuthProvider and useAuth together:
 * ```typescript
 * import AuthContext, { AuthProvider, useAuth } from './context/AuthContext';
 * ```
 * 
 * Note: Named exports (AuthProvider, useAuth) are preferred for clarity.
 */
export default AuthContext;
