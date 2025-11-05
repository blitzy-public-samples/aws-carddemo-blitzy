/**
 * CardDemo Authentication Redux Slice
 * 
 * Redux Toolkit slice managing authentication state with JWT token storage, user credentials,
 * and login/logout operations for the CardDemo frontend application. This slice transforms
 * COBOL COSGN00C.cbl CICS transaction (CC00) authentication state management from mainframe
 * pseudo-conversational COMMAREA patterns to modern Redux state management with JWT tokens.
 * 
 * COBOL Source Mapping:
 * - COBOL Program: COSGN00C.cbl (Sign-on Screen for CardDemo Application)
 * - CICS Transaction: CC00
 * - VSAM File: USRSEC (User Security file) → REST API POST /api/auth/login
 * - COMMAREA: CARDDEMO-COMMAREA → Redux state + localStorage persistence
 * - Security: File-based authentication → JWT token-based authentication
 * 
 * Transformation Details:
 * - COBOL SEC-USER-DATA structure → Redux state.user object
 *   * SEC-USR-ID (PIC X(8)) → state.user.userId
 *   * SEC-USR-FNAME (PIC X(20)) → state.user.firstName
 *   * SEC-USR-LNAME (PIC X(20)) → state.user.lastName
 *   * SEC-USR-TYPE (PIC X(1)) → state.user.userType ('A' Admin / 'R' User)
 * 
 * - COBOL COMMAREA session state → Redux persistent state
 *   * CDEMO-USER-ID → state.user.userId
 *   * CDEMO-USER-TYPE → state.user.userType
 *   * 88-level CDEMO-USRTYP-ADMIN → state.user.userType === 'A'
 *   * 88-level CDEMO-USRTYP-USER → state.user.userType === 'R'
 * 
 * - COBOL authentication logic → Redux async thunk + reducers
 *   * READ-USER-SEC-FILE paragraph → loginUser async thunk
 *   * Password validation → Backend JWT verification
 *   * CICS XCTL program transfer → React Router navigation in components
 *   * Error handling → Redux rejected actions with error messages
 * 
 * State Structure:
 * {
 *   user: {
 *     userId: string,        // User ID from COBOL SEC-USR-ID
 *     firstName: string,     // First name from COBOL SEC-USR-FNAME
 *     lastName: string,      // Last name from COBOL SEC-USR-LNAME
 *     userType: string,      // User type 'A' (Admin) or 'R' (Regular) from COBOL SEC-USR-TYPE
 *     role: string           // Spring Security role: ROLE_USER or ROLE_ADMIN
 *   },
 *   token: string,           // JWT token for authenticated requests
 *   isAuthenticated: boolean,// Authentication status flag
 *   loading: boolean,        // Async operation loading state
 *   error: string            // Error message from failed authentication
 * }
 * 
 * Key Features:
 * - Async login operation with JWT token management
 * - User profile storage from COBOL SEC-USER-DATA structure
 * - Token persistence to localStorage for session continuity
 * - Role-based access control support (Admin vs Regular user)
 * - Error handling matching COBOL COSGN00C error messages
 * - Logout action clearing authentication state
 * - Selectors for accessing auth state in components
 * 
 * Integration Points:
 * - LoginComponent.jsx: Dispatches loginUser thunk, handles navigation based on userType
 * - App routing: Uses selectIsAuthenticated for protected route guards
 * - Header/Navigation: Uses selectUser for display, dispatches logout action
 * - API calls: Token automatically injected by apiClient interceptor
 * 
 * @module redux/slices/authSlice
 */

import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import authService from '../../services/authService';

/**
 * Initial Authentication State
 * 
 * Defines default authentication state before user login. All fields are null/false
 * until successful authentication populates user profile and token data.
 * 
 * Maps COBOL COMMAREA initialization where CDEMO-USER-ID and CDEMO-USER-TYPE
 * are spaces/null until user authenticates via COSGN00C program.
 */
const initialState = {
  user: null,              // User profile object (userId, firstName, lastName, userType, role)
  token: null,             // JWT authentication token
  isAuthenticated: false,  // Authentication status flag
  loading: false,          // Loading state for async login operation
  error: null              // Error message from failed authentication attempt
};

/**
 * Async Thunk: Login User
 * 
 * Performs asynchronous user authentication by calling authService.login() with user
 * credentials, transforming COBOL COSGN00C.cbl READ-USER-SEC-FILE paragraph to modern
 * REST API authentication with JWT token response.
 * 
 * COBOL Mapping (COSGN00C.cbl lines 209-257):
 * ```cobol
 * READ-USER-SEC-FILE.
 *     EXEC CICS READ
 *          DATASET   (WS-USRSEC-FILE)
 *          INTO      (SEC-USER-DATA)
 *          RIDFLD    (WS-USER-ID)
 *          KEYLENGTH (LENGTH OF WS-USER-ID)
 *          RESP      (WS-RESP-CD)
 *     END-EXEC.
 * 
 *     EVALUATE WS-RESP-CD
 *         WHEN 0
 *             IF SEC-USR-PWD = WS-USER-PWD
 *                 MOVE WS-USER-ID   TO CDEMO-USER-ID
 *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
 *                 IF CDEMO-USRTYP-ADMIN
 *                      EXEC CICS XCTL PROGRAM ('COADM01C') END-EXEC
 *                 ELSE
 *                      EXEC CICS XCTL PROGRAM ('COMEN01C') END-EXEC
 *                 END-IF
 *             ELSE
 *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
 *             END-IF
 *         WHEN 13
 *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
 *         WHEN OTHER
 *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
 *     END-EVALUATE.
 * ```
 * 
 * Transformation:
 * - EXEC CICS READ DATASET('USRSEC') → authService.login(userId, password)
 * - VSAM file lookup → POST /api/auth/login REST endpoint
 * - Password comparison → Backend JWT verification (Spring Security)
 * - Success: Returns user profile + JWT token
 * - Error: Throws error with COBOL-equivalent error message
 * 
 * Request Parameters:
 * @param {object} credentials - User login credentials
 * @param {string} credentials.userId - User ID (8 characters, matching COBOL PIC X(8))
 * @param {string} credentials.password - User password (8 characters, matching COBOL PIC X(8))
 * 
 * Return Value (fulfilled):
 * {
 *   userId: string,      // User ID from SEC-USR-ID
 *   userName: string,    // Full name from SEC-USR-FNAME + SEC-USR-LNAME
 *   firstName: string,   // First name from SEC-USR-FNAME
 *   lastName: string,    // Last name from SEC-USR-LNAME
 *   userType: string,    // User type from SEC-USR-TYPE ('A' Admin or 'R' Regular)
 *   role: string,        // Spring Security role (ROLE_USER or ROLE_ADMIN)
 *   token: string        // JWT authentication token
 * }
 * 
 * Error Messages (matching COBOL COSGN00C.cbl):
 * - "Wrong Password. Try again ..." (line 242) - Password mismatch
 * - "User not found. Try again ..." (line 249) - User ID not found (RESP 13)
 * - "Unable to verify the User ..." (line 254) - System error (RESP OTHER)
 * - "User ID and password are required." - Missing credentials
 * 
 * Async Thunk Lifecycle:
 * - pending: Sets loading=true, clears error
 * - fulfilled: Stores user profile, token, sets isAuthenticated=true, persists to localStorage
 * - rejected: Sets error message, clears authentication state
 * 
 * @example
 * // Dispatch login from LoginComponent
 * const handleLogin = async () => {
 *   try {
 *     const result = await dispatch(loginUser({ userId, password })).unwrap();
 *     // Navigate based on user type (maps COBOL XCTL logic)
 *     if (result.userType === 'A') {
 *       navigate('/admin');  // COBOL: XCTL PROGRAM('COADM01C')
 *     } else {
 *       navigate('/menu');   // COBOL: XCTL PROGRAM('COMEN01C')
 *     }
 *   } catch (error) {
 *     // Error message displayed to user (matches COBOL WS-MESSAGE)
 *     console.error(error);
 *   }
 * };
 */
export const loginUser = createAsyncThunk(
  'auth/login',
  async (credentials, { rejectWithValue }) => {
    try {
      // Validate required credentials
      if (!credentials || !credentials.userId || !credentials.password) {
        return rejectWithValue('User ID and password are required.');
      }

      // Call authService to authenticate user
      // Maps COBOL: EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA) RIDFLD(WS-USER-ID)
      const response = await authService.login(credentials.userId, credentials.password);

      // authService.login returns user profile with token
      // Extract user data matching COBOL SEC-USER-DATA structure
      const { userId, userName, userType, role, token } = response;

      // Parse full name into first and last names (if provided as single string)
      // Maps COBOL SEC-USR-FNAME and SEC-USR-LNAME fields
      let firstName = '';
      let lastName = '';
      if (userName) {
        const nameParts = userName.trim().split(' ');
        firstName = nameParts[0] || '';
        lastName = nameParts.slice(1).join(' ') || '';
      }

      // Return user profile for Redux state
      // Maps to COBOL COMMAREA fields: CDEMO-USER-ID, CDEMO-USER-TYPE
      return {
        userId: userId,              // Maps COBOL SEC-USR-ID → CDEMO-USER-ID
        userName: userName || userId,// Full name for display
        firstName: firstName,        // Maps COBOL SEC-USR-FNAME
        lastName: lastName,          // Maps COBOL SEC-USR-LNAME
        userType: userType || 'R',   // Maps COBOL SEC-USR-TYPE → CDEMO-USER-TYPE
        role: role || 'ROLE_USER',   // Spring Security role
        token: token                 // JWT token for authenticated requests
      };

    } catch (error) {
      // Handle authentication errors with COBOL-equivalent error messages
      // Maps COBOL WS-RESP-CD evaluation and error message assignment
      
      // Extract error message from error object
      const errorMessage = error.message || 'Authentication failed. Please try again.';
      
      // Return error message to be stored in state.error
      // Matches COBOL error messages from lines 242, 249, 254
      return rejectWithValue(errorMessage);
    }
  }
);

/**
 * Authentication Slice
 * 
 * Redux Toolkit slice defining authentication state structure, reducers for synchronous
 * actions, and extra reducers for async thunk lifecycle management.
 * 
 * Slice Name: 'auth'
 * 
 * Reducers (synchronous actions):
 * - logout: Clears authentication state and removes token from localStorage
 * - setToken: Sets JWT token and authentication status (for token restoration)
 * - clearError: Clears error message from authentication state
 * 
 * Extra Reducers (async thunk lifecycle):
 * - loginUser.pending: Sets loading=true, clears error
 * - loginUser.fulfilled: Stores user profile, token, sets isAuthenticated=true
 * - loginUser.rejected: Sets error message, clears authentication
 * 
 * State Management:
 * - All state changes are immutable via Redux Toolkit's Immer integration
 * - Token persisted to localStorage on successful login
 * - Token removed from localStorage on logout
 * - User profile stored separately in localStorage by authService
 */
const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    /**
     * Logout Action
     * 
     * Synchronous action to clear authentication state and terminate user session.
     * Removes JWT token from localStorage and resets all authentication state to initial values.
     * 
     * COBOL Mapping (COSGN00C.cbl lines 88-90):
     * Maps PF3 key handling which terminates user session:
     * ```cobol
     * WHEN DFHPF3
     *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
     *     PERFORM SEND-PLAIN-TEXT
     *     EXEC CICS RETURN END-EXEC
     * ```
     * 
     * Post-Logout State:
     * - user: null
     * - token: null
     * - isAuthenticated: false
     * - loading: false
     * - error: null
     * - localStorage: JWT token removed
     * 
     * Note: This action only handles Redux state cleanup. The async backend logout call
     * should be dispatched separately if needed (via authService.logout()).
     * 
     * @param {object} state - Current Redux auth state
     */
    logout: (state) => {
      // Clear all authentication state
      state.user = null;
      state.token = null;
      state.isAuthenticated = false;
      state.loading = false;
      state.error = null;

      // Remove JWT token from localStorage
      // Maps COBOL CICS session termination
      localStorage.removeItem('carddemo_jwt_token');
      localStorage.removeItem('carddemo_user');
    },

    /**
     * Set Token Action
     * 
     * Synchronous action to set JWT authentication token and mark user as authenticated.
     * Used for token restoration from localStorage on application initialization.
     * 
     * This action handles scenarios where the application is reloaded and needs to
     * restore authentication state from persisted localStorage token, avoiding
     * unnecessary re-authentication when user already has valid session.
     * 
     * Maps COBOL COMMAREA persistence across pseudo-conversational transaction boundaries
     * where user context (CDEMO-USER-ID, CDEMO-USER-TYPE) is maintained throughout session.
     * 
     * @param {object} state - Current Redux auth state
     * @param {object} action - Redux action
     * @param {string} action.payload - JWT token string to store
     */
    setToken: (state, action) => {
      if (action.payload) {
        state.token = action.payload;
        state.isAuthenticated = true;
        
        // Persist token to localStorage for session continuity
        localStorage.setItem('carddemo_jwt_token', action.payload);
      }
    },

    /**
     * Clear Error Action
     * 
     * Synchronous action to clear error message from authentication state.
     * Used to dismiss error messages after user has acknowledged them,
     * preventing stale error messages from persisting in UI.
     * 
     * Maps COBOL error flag clearing (WS-ERR-FLG) after error message display.
     * 
     * @param {object} state - Current Redux auth state
     */
    clearError: (state) => {
      state.error = null;
    }
  },

  /**
   * Extra Reducers for Async Thunk Lifecycle
   * 
   * Handles state transitions for loginUser async thunk throughout its lifecycle:
   * pending, fulfilled, and rejected states. Provides comprehensive state management
   * for authentication operation including loading indicators and error handling.
   */
  extraReducers: (builder) => {
    builder
      /**
       * Login User - Pending State
       * 
       * Handles state when login operation is in progress (API call in flight).
       * Sets loading flag to true for UI loading indicators and clears any
       * previous error messages to provide clean slate for new login attempt.
       * 
       * Maps COBOL authentication processing state where user waits for
       * VSAM USRSEC file read and password validation to complete.
       */
      .addCase(loginUser.pending, (state) => {
        state.loading = true;
        state.error = null;
      })

      /**
       * Login User - Fulfilled State
       * 
       * Handles successful authentication with user profile and JWT token.
       * Stores user data in Redux state, persists token to localStorage,
       * and sets authentication status to true.
       * 
       * COBOL Mapping (COSGN00C.cbl lines 223-240):
       * Maps successful VSAM read with password match:
       * ```cobol
       * WHEN 0
       *     IF SEC-USR-PWD = WS-USER-PWD
       *         MOVE WS-USER-ID   TO CDEMO-USER-ID
       *         MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
       *         [Navigation to COADM01C or COMEN01C based on user type]
       * ```
       * 
       * Post-Login State:
       * - state.user populated with userId, firstName, lastName, userType, role
       * - state.token contains JWT token
       * - state.isAuthenticated = true
       * - state.loading = false
       * - localStorage contains token and user profile
       * 
       * Component Responsibility:
       * After this action completes, LoginComponent should navigate user to
       * appropriate screen based on userType:
       * - userType 'A' (Admin) → navigate('/admin') - maps XCTL COADM01C
       * - userType 'R' (Regular) → navigate('/menu') - maps XCTL COMEN01C
       */
      .addCase(loginUser.fulfilled, (state, action) => {
        state.loading = false;
        
        // Store user profile in Redux state
        // Maps COBOL SEC-USER-DATA fields to state
        state.user = {
          userId: action.payload.userId,          // SEC-USR-ID → CDEMO-USER-ID
          firstName: action.payload.firstName,    // SEC-USR-FNAME
          lastName: action.payload.lastName,      // SEC-USR-LNAME
          userName: action.payload.userName,      // Full name for display
          userType: action.payload.userType,      // SEC-USR-TYPE → CDEMO-USER-TYPE
          role: action.payload.role               // Spring Security role
        };
        
        // Store JWT token
        state.token = action.payload.token;
        
        // Set authentication status
        state.isAuthenticated = true;
        
        // Clear any previous errors
        state.error = null;

        // Persist JWT token to localStorage for session continuity
        // Maps COBOL COMMAREA persistence across transaction boundaries
        localStorage.setItem('carddemo_jwt_token', action.payload.token);
        
        // Persist user profile to localStorage (handled by authService, but ensure consistency)
        localStorage.setItem('carddemo_user', JSON.stringify(state.user));
      })

      /**
       * Login User - Rejected State
       * 
       * Handles failed authentication with error message for display to user.
       * Clears any partial authentication state and stores error message matching
       * COBOL error handling patterns.
       * 
       * COBOL Mapping (COSGN00C.cbl lines 241-257):
       * Maps VSAM read error conditions and password mismatch:
       * ```cobol
       * ELSE
       *     MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
       *     [Display error and re-prompt]
       * WHEN 13
       *     MOVE 'User not found. Try again ...' TO WS-MESSAGE
       * WHEN OTHER
       *     MOVE 'Unable to verify the User ...' TO WS-MESSAGE
       * ```
       * 
       * Error Messages:
       * - "Wrong Password. Try again ..." - Password mismatch (line 242)
       * - "User not found. Try again ..." - User ID not found (line 249)
       * - "Unable to verify the User ..." - System error (line 254)
       * - Other validation errors from authService
       * 
       * Post-Error State:
       * - state.loading = false
       * - state.error contains user-friendly error message
       * - state.isAuthenticated = false
       * - state.user = null
       * - state.token = null
       * 
       * Component Responsibility:
       * LoginComponent should display error message to user and keep focus on
       * appropriate input field (userId or password) based on error type.
       */
      .addCase(loginUser.rejected, (state, action) => {
        state.loading = false;
        
        // Store error message for display
        // Maps COBOL WS-MESSAGE error text
        state.error = action.payload || 'Authentication failed. Please try again.';
        
        // Ensure authentication state is cleared
        state.isAuthenticated = false;
        state.user = null;
        state.token = null;
      });
  }
});

/**
 * Action Creators Export
 * 
 * Exports synchronous action creators generated by createSlice for use in components.
 * These actions can be dispatched directly without async thunk wrapper.
 */
export const { logout, setToken, clearError } = authSlice.actions;

/**
 * Selector: Select Auth
 * 
 * Selector function to retrieve entire authentication state object from Redux store.
 * Useful for components that need access to multiple authentication state properties.
 * 
 * @param {object} state - Redux store root state
 * @returns {object} Complete authentication state object
 * 
 * @example
 * const authState = useSelector(selectAuth);
 * console.log(authState); // { user, token, isAuthenticated, loading, error }
 */
export const selectAuth = (state) => state.auth;

/**
 * Selector: Select User
 * 
 * Selector function to retrieve authenticated user profile from Redux store.
 * Returns user object containing userId, firstName, lastName, userType, and role.
 * Returns null if user is not authenticated.
 * 
 * Maps COBOL COMMAREA access pattern where programs check CDEMO-USER-ID and
 * CDEMO-USER-TYPE to retrieve current user context for authorization and display.
 * 
 * @param {object} state - Redux store root state
 * @returns {object|null} User profile object or null if not authenticated
 * 
 * @example
 * const user = useSelector(selectUser);
 * if (user) {
 *   console.log(`Welcome ${user.firstName} ${user.lastName}`);
 *   // Check role for conditional rendering (maps COBOL CDEMO-USRTYP-ADMIN)
 *   if (user.userType === 'A') {
 *     // Show admin options
 *   }
 * }
 */
export const selectUser = (state) => state.auth.user;

/**
 * Selector: Select Is Authenticated
 * 
 * Selector function to check if user is currently authenticated.
 * Returns boolean flag indicating authentication status.
 * 
 * Primary selector for protected route guards and conditional rendering
 * based on authentication state throughout the application.
 * 
 * Maps COBOL CICS session validation checking if CDEMO-USER-ID is populated
 * to determine if user has authenticated session context.
 * 
 * @param {object} state - Redux store root state
 * @returns {boolean} True if user is authenticated, false otherwise
 * 
 * @example
 * const isAuthenticated = useSelector(selectIsAuthenticated);
 * if (!isAuthenticated) {
 *   navigate('/login');
 * }
 * 
 * @example
 * // Protected route guard
 * const ProtectedRoute = ({ children }) => {
 *   const isAuthenticated = useSelector(selectIsAuthenticated);
 *   return isAuthenticated ? children : <Navigate to="/login" />;
 * };
 */
export const selectIsAuthenticated = (state) => state.auth.isAuthenticated;

/**
 * Selector: Select Auth Loading
 * 
 * Selector function to retrieve loading state for authentication operations.
 * Returns boolean flag indicating if login operation is in progress.
 * 
 * Used by LoginComponent to display loading indicators and disable form
 * submission during authentication API call.
 * 
 * @param {object} state - Redux store root state
 * @returns {boolean} True if authentication operation in progress, false otherwise
 * 
 * @example
 * const loading = useSelector(selectAuthLoading);
 * <button type="submit" disabled={loading}>
 *   {loading ? 'Signing in...' : 'Sign In'}
 * </button>
 */
export const selectAuthLoading = (state) => state.auth.loading;

/**
 * Selector: Select Auth Error
 * 
 * Selector function to retrieve authentication error message from Redux store.
 * Returns error message string for display to user, or null if no error.
 * 
 * Error messages match COBOL COSGN00C.cbl error handling patterns for
 * consistent user experience and error communication.
 * 
 * @param {object} state - Redux store root state
 * @returns {string|null} Error message or null if no error
 * 
 * @example
 * const error = useSelector(selectAuthError);
 * {error && (
 *   <div className="error-message">
 *     {error}
 *   </div>
 * )}
 */
export const selectAuthError = (state) => state.auth.error;

/**
 * Selector: Select Auth Token
 * 
 * Selector function to retrieve JWT authentication token from Redux store.
 * Returns token string for manual use if needed, or null if not authenticated.
 * 
 * Note: Token is automatically injected into API requests by apiClient interceptor,
 * so direct token access is rarely needed. This selector primarily supports
 * token validation and debugging scenarios.
 * 
 * @param {object} state - Redux store root state
 * @returns {string|null} JWT token or null if not authenticated
 * 
 * @example
 * const token = useSelector(selectAuthToken);
 * if (token) {
 *   // Token exists, can make authenticated API calls
 *   console.log('Authenticated with token');
 * }
 */
export const selectAuthToken = (state) => state.auth.token;

/**
 * Reducer Export (Default)
 * 
 * Exports authentication slice reducer as default export for Redux store configuration.
 * This reducer handles all authentication state transitions for the CardDemo application.
 * 
 * Usage in store configuration:
 * ```javascript
 * import { configureStore } from '@reduxjs/toolkit';
 * import authReducer from './slices/authSlice';
 * 
 * export const store = configureStore({
 *   reducer: {
 *     auth: authReducer,
 *     // other reducers...
 *   }
 * });
 * ```
 */
export default authSlice.reducer;
