/**
 * Redux Store Configuration
 * 
 * Centralized Redux store configuration for the CardDemo application using Redux Toolkit.
 * Combines all slice reducers (auth, account, card, transaction) into a single root reducer,
 * configures custom middleware chain including apiMiddleware for request/response handling,
 * enables Redux DevTools for development debugging, and sets up store persistence.
 * 
 * This store replaces CICS COMMAREA pseudo-conversational state preservation patterns with
 * stateless Redux architecture, providing single source of truth for all application state.
 * 
 * COBOL Source Mapping:
 * - COBOL Copybook: COCOM01Y.cpy (CARDDEMO-COMMAREA structure)
 * - CICS Pattern: Pseudo-conversational state via COMMAREA
 * - Redux Pattern: Centralized immutable state store
 * 
 * COMMAREA Structure Transformation:
 * ```cobol
 * 01 CARDDEMO-COMMAREA.
 *    05 CDEMO-GENERAL-INFO.      → state.auth (authentication context)
 *       10 CDEMO-USER-ID         → state.auth.user.userId
 *       10 CDEMO-USER-TYPE       → state.auth.user.userType
 *    05 CDEMO-CUSTOMER-INFO.     → state.account (customer/account context)
 *       10 CDEMO-CUST-ID         → state.account.currentAccount.customerId
 *    05 CDEMO-ACCOUNT-INFO.      → state.account (account details)
 *       10 CDEMO-ACCT-ID         → state.account.currentAccount.accountId
 *       10 CDEMO-ACCT-STATUS     → state.account.currentAccount.activeStatus
 *    05 CDEMO-CARD-INFO.         → state.card (card context)
 *       10 CDEMO-CARD-NUM        → state.card.currentCard.cardNumber
 * ```
 * 
 * Store Structure:
 * {
 *   auth: {
 *     user: { userId, firstName, lastName, userType, role },
 *     token: string,
 *     isAuthenticated: boolean,
 *     loading: boolean,
 *     error: string|null
 *   },
 *   account: {
 *     accounts: array,
 *     currentAccount: object|null,
 *     filters: object,
 *     loading: boolean,
 *     error: string|null,
 *     successMessage: string|null
 *   },
 *   card: {
 *     cards: array,
 *     currentCard: object|null,
 *     currentPage: number,
 *     pageSize: number (7 cards per page),
 *     totalCards: number,
 *     totalPages: number,
 *     filters: object,
 *     loading: boolean,
 *     error: string|null
 *   },
 *   transaction: {
 *     transactions: array,
 *     currentTransaction: object|null,
 *     currentPage: number,
 *     pageSize: number (10 transactions per page),
 *     totalTransactions: number,
 *     totalPages: number,
 *     filters: { startDate, endDate, categoryCode, transactionType },
 *     categoryAggregations: array,
 *     loading: boolean,
 *     error: string|null
 *   }
 * }
 * 
 * Key Features:
 * - Redux Toolkit configureStore API for simplified store setup
 * - Combined reducers from all application slices
 * - Custom middleware chain with apiMiddleware for centralized error handling
 * - Redux DevTools integration for time-travel debugging in development
 * - Store subscription for auth token persistence to localStorage
 * - Typed hooks (useAppDispatch, useAppSelector) for TypeScript-like safety
 * - Immutable state updates via Redux Toolkit's Immer integration
 * - Serializable state checking in development mode
 * 
 * Middleware Chain:
 * 1. Redux Thunk (default) - for async action creators
 * 2. Serializable Check (dev only) - detects non-serializable values in state/actions
 * 3. Immutability Check (dev only) - detects state mutations
 * 4. API Middleware (custom) - JWT token injection, error handling, retry logic
 * 
 * State Persistence Strategy:
 * - Auth token persisted to localStorage with key 'carddemo_jwt_token'
 * - User profile persisted to localStorage with key 'carddemo_user'
 * - Store subscription monitors auth.token changes and syncs to localStorage
 * - Token rehydration on store initialization from localStorage
 * 
 * Integration:
 * - Provider wrapper in index.js wraps entire app with store context
 * - Components use useAppSelector to read state
 * - Components use useAppDispatch to dispatch actions
 * - Middleware intercepts all actions for side effects
 * 
 * Agent Action Plan References:
 * - Section 0.3: State Management Strategy (COMMAREA → Redux transformation)
 * - Section 0.5: Frontend Architecture (Redux store configuration)
 * - Section 0.7: Dependencies (Redux Toolkit 2.0.1)
 * 
 * @module redux/store
 */

import { configureStore, combineReducers } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';

// Import slice reducers
import authReducer from './slices/authSlice';
import accountReducer from './slices/accountSlice';
import cardReducer from './slices/cardSlice';
import transactionReducer from './slices/transactionSlice';

// Import custom middleware
import apiMiddleware from './middleware/apiMiddleware';

/**
 * Root Reducer
 * 
 * Combines all slice reducers into a single root reducer for the Redux store.
 * Each slice manages a specific domain of the application state, replacing different
 * sections of the COBOL CARDDEMO-COMMAREA structure.
 * 
 * Reducer Mapping to COBOL COMMAREA Sections:
 * - auth: CDEMO-GENERAL-INFO (user authentication and session context)
 * - account: CDEMO-ACCOUNT-INFO + CDEMO-CUSTOMER-INFO (account/customer data)
 * - card: CDEMO-CARD-INFO (credit card information)
 * - transaction: (new domain not in COMMAREA, managed in transaction processing programs)
 * 
 * Redux Toolkit's combineReducers creates a root reducer that delegates state updates
 * to individual slice reducers based on the action type, ensuring modular state management.
 */
const rootReducer = combineReducers({
  auth: authReducer,               // Authentication and user session state
  account: accountReducer,         // Account and customer data state
  card: cardReducer,               // Credit card data state
  transaction: transactionReducer  // Transaction data state
});

/**
 * Preloaded State Initialization
 * 
 * Attempts to rehydrate auth token from localStorage on store initialization,
 * maintaining user session across page reloads. This replaces CICS COMMAREA
 * persistence across pseudo-conversational transaction boundaries.
 * 
 * COBOL Pattern:
 * CICS maintains COMMAREA data between transaction invocations via:
 * EXEC CICS RETURN TRANSID(...) COMMAREA(...) END-EXEC
 * 
 * Redux Pattern:
 * Token and user data persisted to localStorage, rehydrated on app initialization:
 * - carddemo_jwt_token: JWT authentication token
 * - carddemo_user: User profile JSON object
 * 
 * Note: Full auth state is not restored here to avoid stale data. Only token
 * is checked for existence. Auth slice will validate token with backend on first use.
 */
const getPreloadedState = () => {
  try {
    // Check if JWT token exists in localStorage
    const token = localStorage.getItem('carddemo_jwt_token');
    const userJson = localStorage.getItem('carddemo_user');
    
    if (token && userJson) {
      // Parse user profile from localStorage
      const user = JSON.parse(userJson);
      
      // Return preloaded auth state with token and user profile
      // This maintains authentication across page reloads
      return {
        auth: {
          user: user,
          token: token,
          isAuthenticated: true,
          loading: false,
          error: null
        }
      };
    }
  } catch (error) {
    // Handle localStorage access errors or JSON parse errors
    console.error('Error rehydrating auth state from localStorage:', error);
    
    // Clear potentially corrupted localStorage data
    try {
      localStorage.removeItem('carddemo_jwt_token');
      localStorage.removeItem('carddemo_user');
    } catch (clearError) {
      console.error('Error clearing localStorage:', clearError);
    }
  }
  
  // Return undefined to use default initial state from slices
  return undefined;
};

/**
 * Redux Store Configuration
 * 
 * Creates and configures the Redux store using Redux Toolkit's configureStore API.
 * Provides comprehensive store setup with reducer combination, middleware configuration,
 * DevTools integration, and preloaded state rehydration.
 * 
 * Configuration Options:
 * - reducer: Root reducer combining all slice reducers
 * - middleware: Custom middleware chain with apiMiddleware
 * - devTools: Redux DevTools extension enabled in non-production
 * - preloadedState: Rehydrated auth state from localStorage
 * 
 * Middleware Configuration:
 * Uses getDefaultMiddleware() to include Redux Toolkit's default middleware:
 * - Thunk middleware: Enabled for async action creators (loginUser, fetchAccounts, etc.)
 * - Serializable check: Enabled in development to detect non-serializable values
 * - Immutability check: Enabled in development to detect state mutations
 * 
 * Custom middleware (apiMiddleware) is concatenated to default middleware chain for:
 * - JWT token attachment to Authorization headers
 * - HTTP status code processing and error transformation
 * - Exponential backoff retry logic for failed idempotent requests
 * - Request cancellation capabilities
 * - Centralized error message handling (maps COBOL HANDLE CONDITION)
 * 
 * Redux DevTools:
 * Enabled in development mode for time-travel debugging, action inspection,
 * and state diff visualization. Disabled in production for security and performance.
 * 
 * State Persistence:
 * Store subscription monitors auth.token changes and persists to localStorage
 * for session continuity across page reloads, mapping CICS COMMAREA persistence.
 */
export const store = configureStore({
  // Root reducer combining all slice reducers
  reducer: rootReducer,
  
  // Middleware configuration
  // Includes default middleware (thunk, serializable check, immutability check)
  // Adds custom apiMiddleware for centralized API error handling
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware({
      // Serializable check options (development only)
      serializableCheck: {
        // Ignore specific action types that may contain non-serializable values
        ignoredActions: ['persist/PERSIST', 'persist/REHYDRATE'],
        // Ignore specific paths in state that may contain non-serializable values
        ignoredActionPaths: ['payload.timestamp', 'meta.arg'],
        ignoredPaths: ['items.dates']
      },
      // Immutability check options (development only)
      immutableCheck: {
        // Ignore specific paths that are expected to be mutable (none in this app)
        ignoredPaths: []
      }
    }).concat(apiMiddleware), // Add custom API middleware to chain
  
  // Redux DevTools configuration
  // Enable in development for debugging, disable in production
  devTools: process.env.NODE_ENV !== 'production' && {
    // DevTools options
    name: 'CardDemo Redux Store',
    trace: true,           // Enable action stack traces
    traceLimit: 25,        // Limit stack trace depth
    // Feature flags
    features: {
      pause: true,         // Allow pausing action dispatching
      lock: true,          // Allow locking state changes
      persist: true,       // Allow state persistence
      export: true,        // Allow state export
      import: 'custom',    // Allow custom state import
      jump: true,          // Allow jumping to specific state
      skip: true,          // Allow skipping actions
      reorder: true,       // Allow reordering actions
      dispatch: true,      // Allow custom action dispatch
      test: true           // Enable test templates
    }
  },
  
  // Preloaded state for token rehydration
  preloadedState: getPreloadedState()
});

/**
 * Store Subscription for Auth Token Persistence
 * 
 * Subscribes to Redux store state changes and persists auth token to localStorage
 * whenever authentication state is updated. This ensures session continuity across
 * page reloads and browser restarts.
 * 
 * COBOL Pattern Mapping:
 * Maps CICS COMMAREA persistence pattern where CDEMO-USER-ID and CDEMO-USER-TYPE
 * are preserved across pseudo-conversational transaction boundaries via:
 * EXEC CICS RETURN TRANSID(...) COMMAREA(CARDDEMO-COMMAREA) END-EXEC
 * 
 * Redux Pattern:
 * Store subscription listens for state changes and syncs auth.token and auth.user
 * to localStorage for persistence across page reloads.
 * 
 * Persistence Strategy:
 * - On login: Token and user profile saved to localStorage
 * - On logout: Token and user profile removed from localStorage
 * - On page reload: Token and user profile rehydrated from localStorage
 * 
 * Error Handling:
 * LocalStorage access errors are caught and logged but don't throw exceptions
 * to prevent Redux store subscription failures. If localStorage is unavailable,
 * session will not persist across page reloads (graceful degradation).
 * 
 * Performance:
 * Subscription callback runs after every action dispatch. To optimize, we only
 * perform localStorage writes when auth.token actually changes by tracking
 * previous token value.
 */
let previousAuthToken = null;

store.subscribe(() => {
  try {
    // Get current auth state from store
    const state = store.getState();
    const currentAuthToken = state.auth?.token;
    const currentUser = state.auth?.user;
    const isAuthenticated = state.auth?.isAuthenticated;
    
    // Only update localStorage if token has changed (performance optimization)
    if (currentAuthToken !== previousAuthToken) {
      if (currentAuthToken && isAuthenticated) {
        // User authenticated: Persist token and user profile
        localStorage.setItem('carddemo_jwt_token', currentAuthToken);
        
        if (currentUser) {
          // Persist user profile as JSON string
          localStorage.setItem('carddemo_user', JSON.stringify(currentUser));
        }
        
        // Update tracking variable
        previousAuthToken = currentAuthToken;
        
      } else if (!currentAuthToken && previousAuthToken !== null) {
        // User logged out: Remove token and user profile from localStorage
        localStorage.removeItem('carddemo_jwt_token');
        localStorage.removeItem('carddemo_user');
        
        // Update tracking variable
        previousAuthToken = null;
      }
    }
    
  } catch (error) {
    // Handle localStorage errors gracefully
    // Possible errors: QuotaExceededError, SecurityError (private browsing)
    console.error('Error persisting auth state to localStorage:', error);
    
    // Log error details for debugging
    if (error.name === 'QuotaExceededError') {
      console.error('localStorage quota exceeded. Session will not persist.');
    } else if (error.name === 'SecurityError') {
      console.error('localStorage access denied. Session will not persist.');
    }
  }
});

/**
 * Type Definitions for Store
 * 
 * Export TypeScript-like type definitions for RootState and AppDispatch
 * to enable type-safe hooks usage throughout the application.
 * 
 * RootState: Complete Redux store state type (inferred from store.getState)
 * AppDispatch: Store dispatch function type (inferred from store.dispatch)
 * 
 * These types enable TypeScript-like type checking in JavaScript via JSDoc
 * comments and provide IDE autocomplete support for state access.
 */

/**
 * Root State Type
 * 
 * Represents the complete Redux store state structure with all slice states.
 * Inferred from store.getState() to automatically reflect reducer composition.
 * 
 * Usage:
 * @type {ReturnType<typeof store.getState>}
 */
export const selectRootState = store.getState;

/**
 * App Dispatch Type
 * 
 * Represents the Redux store dispatch function type including thunk support.
 * Inferred from store.dispatch to include all middleware-enhanced capabilities.
 * 
 * Usage:
 * @type {typeof store.dispatch}
 */
export const getAppDispatch = () => store.dispatch;

/**
 * Typed useAppDispatch Hook
 * 
 * Custom hook providing type-safe dispatch function for components.
 * Returns dispatch function with correct typing for thunk actions and standard actions.
 * 
 * Replaces react-redux useDispatch with typed version for better developer experience
 * with IDE autocomplete and type checking.
 * 
 * COBOL Pattern:
 * Maps COBOL CALL statements to dispatch async thunks:
 * CALL 'COSGN00C' → dispatch(loginUser(credentials))
 * CALL 'COACTVWC' → dispatch(fetchAccountById(accountId))
 * 
 * @returns {typeof store.dispatch} Typed dispatch function
 * 
 * @example
 * import { useAppDispatch } from './redux/store';
 * 
 * const MyComponent = () => {
 *   const dispatch = useAppDispatch();
 *   
 *   const handleLogin = async () => {
 *     // Dispatch async thunk with type safety
 *     await dispatch(loginUser({ userId, password })).unwrap();
 *   };
 *   
 *   return <button onClick={handleLogin}>Login</button>;
 * };
 */
export const useAppDispatch = () => useDispatch();

/**
 * Typed useAppSelector Hook
 * 
 * Custom hook providing type-safe state selector function for components.
 * Returns selected state slice with correct typing from RootState.
 * 
 * Replaces react-redux useSelector with typed version for better developer experience
 * with IDE autocomplete and type checking.
 * 
 * COBOL Pattern:
 * Maps COBOL COMMAREA field access to Redux state selection:
 * CDEMO-USER-ID → useAppSelector(state => state.auth.user?.userId)
 * CDEMO-ACCT-ID → useAppSelector(state => state.account.currentAccount?.accountId)
 * CDEMO-CARD-NUM → useAppSelector(state => state.card.currentCard?.cardNumber)
 * 
 * @template TSelected
 * @param {(state: ReturnType<typeof store.getState>) => TSelected} selector - State selector function
 * @returns {TSelected} Selected state value
 * 
 * @example
 * import { useAppSelector } from './redux/store';
 * import { selectUser, selectIsAuthenticated } from './slices/authSlice';
 * 
 * const MyComponent = () => {
 *   // Select specific state slices with type safety
 *   const user = useAppSelector(selectUser);
 *   const isAuthenticated = useAppSelector(selectIsAuthenticated);
 *   
 *   return (
 *     <div>
 *       {isAuthenticated ? `Welcome ${user.firstName}` : 'Please login'}
 *     </div>
 *   );
 * };
 * 
 * @example
 * // Inline selector with complex logic
 * const filteredAccounts = useAppSelector(state => 
 *   state.account.accounts.filter(acc => acc.activeStatus === 'Y')
 * );
 */
export const useAppSelector = useSelector;

/**
 * Store Methods Export
 * 
 * While the store is exported as default, individual store methods are also
 * documented here for reference and potential direct usage in non-component code.
 * 
 * Store Methods:
 * - getState(): Returns current Redux store state (all slices)
 * - dispatch(action): Dispatches action to reducers and middleware
 * - subscribe(listener): Subscribes callback to state changes
 * - replaceReducer(nextReducer): Replaces root reducer (for code splitting)
 * 
 * COBOL Pattern Mapping:
 * - getState() → Reading COMMAREA fields (CDEMO-USER-ID, CDEMO-ACCT-ID, etc.)
 * - dispatch() → Program calls (CALL 'PROGRAM-ID') triggering state changes
 * - subscribe() → CICS event handlers monitoring state changes
 * 
 * Note: Most components should use useAppSelector and useAppDispatch hooks
 * instead of accessing store methods directly. Direct store access is primarily
 * for non-React code like middleware, utilities, or integration tests.
 * 
 * @example
 * // Direct store access in non-component code
 * import { store } from './redux/store';
 * 
 * // Get current state
 * const currentState = store.getState();
 * console.log('Current user:', currentState.auth.user);
 * 
 * // Dispatch action
 * store.dispatch(logout());
 * 
 * // Subscribe to state changes
 * const unsubscribe = store.subscribe(() => {
 *   console.log('State updated:', store.getState());
 * });
 * 
 * // Later: Unsubscribe
 * unsubscribe();
 */

/**
 * Default Export: Redux Store Instance
 * 
 * Exports configured Redux store instance for Provider wrapper in index.js.
 * This is the primary export used to provide store context to entire React application.
 * 
 * Usage in index.js:
 * ```javascript
 * import React from 'react';
 * import ReactDOM from 'react-dom/client';
 * import { Provider } from 'react-redux';
 * import { store } from './redux/store';
 * import App from './App';
 * 
 * const root = ReactDOM.createRoot(document.getElementById('root'));
 * root.render(
 *   <Provider store={store}>
 *     <App />
 *   </Provider>
 * );
 * ```
 * 
 * COBOL Pattern:
 * Maps CICS region with COMMAREA to React Provider with Redux store:
 * - CICS region: Runtime environment maintaining COMMAREA state
 * - Redux Provider: React context providing store to all components
 * - COMMAREA: Session state structure
 * - Redux store: Application state tree
 * 
 * Integration Points:
 * - index.js: Wraps App component with Provider
 * - Components: Access store via useAppSelector and useAppDispatch hooks
 * - Middleware: Intercepts actions via store.dispatch
 * - Tests: Mock store for component testing
 * 
 * Store Lifecycle:
 * 1. Store created on application initialization
 * 2. Preloaded state rehydrated from localStorage (auth token)
 * 3. Store provided to React component tree via Provider
 * 4. Components dispatch actions and select state
 * 5. Middleware processes actions
 * 6. Reducers update state immutably
 * 7. Subscribed components re-render with new state
 * 8. Store subscription persists auth changes to localStorage
 * 
 * Performance Considerations:
 * - Single store instance shared across entire application
 * - Reducers combined for efficient state updates
 * - Middleware chain optimized for minimal overhead
 * - Component subscriptions optimized via React-Redux connect/hooks
 * - DevTools disabled in production for performance
 * 
 * Security Considerations:
 * - JWT token stored in localStorage (XSS risk - use HttpOnly cookies in production)
 * - Sensitive data not logged to console in production
 * - Redux DevTools disabled in production to prevent state inspection
 * - Token automatically removed on logout
 * 
 * Maintenance Notes:
 * - Add new slices by importing reducer and adding to rootReducer combineReducers
 * - Add custom middleware by appending to getDefaultMiddleware().concat() chain
 * - Modify preloaded state logic in getPreloadedState() for new persistence needs
 * - Update store subscription for additional persistence requirements
 */
export default store;

