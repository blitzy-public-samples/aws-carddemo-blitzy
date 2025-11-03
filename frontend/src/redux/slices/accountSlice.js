/**
 * Account Redux Slice
 * 
 * Redux Toolkit slice managing account data state for the CardDemo application.
 * Transforms COBOL programs COACTVWC.cbl (Account View) and COACTUPC.cbl (Account Update)
 * to modern Redux state management with async API operations.
 * 
 * Transformation Context:
 * This slice replaces CICS VSAM ACCTDAT file operations with stateless REST API calls,
 * managing account data in Redux store for components throughout the application.
 * 
 * COBOL Source Programs:
 * - COACTVWC.cbl: Account view transaction with VSAM READ operations
 * - COACTUPC.cbl: Account update transaction with VSAM REWRITE operations
 * - CVACT01Y.cpy: Account record structure (ACCOUNT-RECORD)
 * 
 * State Structure:
 * - accounts: Array of account objects retrieved from API
 * - currentAccount: Currently selected account for detail view/edit
 * - filters: Filter criteria for account queries (accountId, customerId, status)
 * - loading: Loading state indicator for async operations
 * - error: Error message from failed operations
 * - successMessage: Success feedback message for completed operations
 * 
 * Async Operations:
 * - fetchAccounts: Retrieve filtered list of accounts
 * - fetchAccountById: Retrieve single account by ID
 * - updateAccount: Update existing account information
 * - createAccount: Create new account record
 * 
 * Synchronous Actions:
 * - setCurrentAccount: Set currently selected account
 * - updateFilters: Update filter criteria
 * - clearCurrentAccount: Clear current account selection
 * - clearMessages: Clear error and success messages
 * 
 * Integration:
 * Used by AccountViewComponent, AccountUpdateComponent, AccountAddComponent,
 * and other account-related UI components for state management.
 * 
 * @module redux/slices/accountSlice
 */

import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import {
  getAccount,
  getAccountsByCustomer,
  updateAccount as updateAccountService,
  createAccount as createAccountService,
  getAccountBalance
} from '../../services/accountService';

/**
 * Initial State Definition
 * 
 * Defines the initial Redux state structure for account management.
 * Maps COBOL WORKING-STORAGE variables to Redux state properties.
 * 
 * State Properties:
 * - accounts: Array of account objects from fetchAccounts
 * - currentAccount: Single account object from fetchAccountById or after create/update
 * - filters: Query filter criteria object
 * - loading: Boolean indicating async operation in progress
 * - error: String error message or null
 * - successMessage: String success feedback or null
 */
const initialState = {
  // Array of account objects
  // Maps COBOL: Array of ACCOUNT-RECORD structures
  accounts: [],
  
  // Currently selected account for detail view/edit
  // Maps COBOL: Single ACCOUNT-RECORD in WORKING-STORAGE
  currentAccount: null,
  
  // Filter criteria for account queries
  // Maps COBOL: WS-ACCT-FILTER, WS-CUST-FILTER variables
  filters: {
    accountId: '',       // Filter by specific account ID (11 digits)
    customerId: '',      // Filter by customer ID (9 digits)
    status: 'all',       // Filter by status: 'all', 'active', 'inactive'
    sortBy: 'accountId', // Sort field: 'accountId', 'openDate', 'currentBalance'
    sortOrder: 'asc'     // Sort order: 'asc' or 'desc'
  },
  
  // Loading state for async operations
  // Maps COBOL: WS-PROCESSING-FLAG
  loading: false,
  
  // Error message from failed operations
  // Maps COBOL: WS-RETURN-MSG, WS-ERROR-MESSAGE
  error: null,
  
  // Success feedback message
  // Maps COBOL: WS-INFO-MSG
  successMessage: null
};

/**
 * Async Thunk: Fetch Accounts
 * 
 * Retrieves filtered list of accounts from the REST API.
 * Maps COBOL COACTVWC.cbl sequential account browsing operations.
 * 
 * COBOL Equivalent:
 * - EXEC CICS STARTBR DATASET('ACCTDAT')
 * - EXEC CICS READNEXT ... UNTIL end-of-file
 * 
 * REST API:
 * - GET /api/accounts?customerId={customerId}&status={status}
 * 
 * @param {Object} filters - Filter criteria
 * @param {string} [filters.customerId] - Filter by customer ID
 * @param {string} [filters.status] - Filter by status ('all', 'active', 'inactive')
 * @returns {Promise<Array>} Array of account objects
 */
export const fetchAccounts = createAsyncThunk(
  'account/fetchAccounts',
  async (filters = {}, { rejectWithValue }) => {
    try {
      // If customerId filter is provided, use getAccountsByCustomer
      // Maps COBOL: Customer-based account lookup via XREF file
      if (filters.customerId && filters.customerId !== '') {
        const accounts = await getAccountsByCustomer(filters.customerId);
        
        // Apply status filter if specified
        // Maps COBOL: ACCT-ACTIVE-STATUS field filtering
        if (filters.status && filters.status !== 'all') {
          const statusValue = filters.status === 'active' ? 'Y' : 'N';
          return accounts.filter(account => account.accountStatus === statusValue);
        }
        
        return accounts;
      }
      
      // If no customerId, return empty array
      // Full account list retrieval would be handled by a different endpoint
      // Maps COBOL: No browse criteria provided scenario
      return [];
      
    } catch (error) {
      // Handle and format error for Redux state
      // Maps COBOL: Error handling in 9999-GENERAL-ERROR-ROUTINE
      return rejectWithValue(error.message || 'Failed to fetch accounts');
    }
  }
);

/**
 * Async Thunk: Fetch Account By ID
 * 
 * Retrieves single account details by account ID.
 * Maps COBOL COACTVWC.cbl account view operation.
 * 
 * COBOL Equivalent:
 * - EXEC CICS READ DATASET('ACCTDAT') RIDFLD(accountId) INTO(account-record)
 * - Paragraph: 9300-GETACCTDATA-BYACCT
 * 
 * REST API:
 * - GET /api/accounts/{accountId}
 * 
 * @param {string|number} accountId - 11-digit account identifier
 * @returns {Promise<Object>} Account object with all fields from CVACT01Y.cpy
 */
export const fetchAccountById = createAsyncThunk(
  'account/fetchAccountById',
  async (accountId, { rejectWithValue }) => {
    try {
      // Call account service to retrieve account by ID
      // Maps COBOL: EXEC CICS READ DATASET('ACCTDAT')
      const account = await getAccount(accountId);
      
      return account;
      
    } catch (error) {
      // Handle specific error scenarios
      // Maps COBOL: WHEN DFHRESP(NOTFND), WHEN DFHRESP(IOERR)
      
      if (error.status === 404) {
        // Account not found
        // Maps COBOL: DID-NOT-FIND-ACCT-IN-ACCTDAT
        return rejectWithValue('Account not found');
      }
      
      if (error.status === 400) {
        // Invalid account ID format
        // Maps COBOL: FLG-ACCTFILTER-NOT-OK
        return rejectWithValue(error.message || 'Invalid account number');
      }
      
      // General error
      // Maps COBOL: File I/O error handling
      return rejectWithValue(error.message || 'Failed to fetch account');
    }
  }
);

/**
 * Async Thunk: Update Account
 * 
 * Updates existing account information with validation.
 * Maps COBOL COACTUPC.cbl account update operation.
 * 
 * COBOL Equivalent:
 * - EXEC CICS READ DATASET('ACCTDAT') UPDATE
 * - EXEC CICS REWRITE DATASET('ACCTDAT') FROM(account-record)
 * - EXEC CICS SYNCPOINT
 * 
 * REST API:
 * - PUT /api/accounts/{accountId}
 * 
 * Transaction Semantics:
 * Backend applies @Transactional annotation for atomicity,
 * preserving COBOL CICS SYNCPOINT behavior.
 * 
 * @param {Object} payload - Update payload
 * @param {string|number} payload.accountId - Account identifier
 * @param {Object} payload.accountData - Fields to update
 * @returns {Promise<Object>} Updated account object
 */
export const updateAccount = createAsyncThunk(
  'account/updateAccount',
  async ({ accountId, accountData }, { rejectWithValue }) => {
    try {
      // Validate required fields
      // Maps COBOL: WS-NON-KEY-FLAGS validation
      if (!accountId) {
        return rejectWithValue('Account ID is required');
      }
      
      if (!accountData || Object.keys(accountData).length === 0) {
        return rejectWithValue('No update data provided');
      }
      
      // Call account service to update account
      // Maps COBOL: EXEC CICS REWRITE DATASET('ACCTDAT')
      const updatedAccount = await updateAccountService(accountId, accountData);
      
      return updatedAccount;
      
    } catch (error) {
      // Handle specific error scenarios
      // Maps COBOL: Update error handling in COACTUPC
      
      if (error.status === 404) {
        // Account not found for update
        // Maps COBOL: NOTFND condition in REWRITE
        return rejectWithValue('Account not found');
      }
      
      if (error.status === 400) {
        // Validation errors
        // Maps COBOL: Field validation failures
        if (error.errors && Object.keys(error.errors).length > 0) {
          // Format field-specific errors for display
          const errorMessages = Object.entries(error.errors)
            .map(([field, message]) => `${field}: ${message}`)
            .join('; ');
          return rejectWithValue(errorMessages);
        }
        return rejectWithValue(error.message || 'Invalid account data');
      }
      
      if (error.status === 409) {
        // Concurrent update conflict
        // Maps COBOL: File locking conflict
        return rejectWithValue('Account was modified by another user. Please refresh and try again.');
      }
      
      // General error
      return rejectWithValue(error.message || 'Failed to update account');
    }
  }
);

/**
 * Async Thunk: Create Account
 * 
 * Creates new account record with validation.
 * Maps COBOL account creation logic.
 * 
 * COBOL Equivalent:
 * - EXEC CICS WRITE DATASET('ACCTDAT') FROM(new-account-record)
 * - EXEC CICS WRITE DATASET('XREF') ... (cross-reference creation)
 * - EXEC CICS SYNCPOINT
 * 
 * REST API:
 * - POST /api/accounts
 * 
 * Auto-Generated Fields:
 * - accountId: System-generated 11-digit unique identifier
 * - currentBalance: Initialized to 0.00
 * - currentCycleCredit: Initialized to 0.00
 * - currentCycleDebit: Initialized to 0.00
 * 
 * @param {Object} accountData - New account data
 * @param {string|number} accountData.customerId - Customer ID (required)
 * @param {string} accountData.accountStatus - Account status (required, 'Y' or 'N')
 * @param {number} accountData.creditLimit - Credit limit (required)
 * @param {string} accountData.openDate - Open date (required, YYYY-MM-DD)
 * @returns {Promise<Object>} Newly created account object with generated accountId
 */
export const createAccount = createAsyncThunk(
  'account/createAccount',
  async (accountData, { rejectWithValue }) => {
    try {
      // Validate required fields before API call
      // Maps COBOL: Mandatory field validation
      if (!accountData) {
        return rejectWithValue('Account data is required');
      }
      
      // Call account service to create new account
      // Maps COBOL: EXEC CICS WRITE DATASET('ACCTDAT')
      // Backend generates accountId and creates cross-references
      const newAccount = await createAccountService(accountData);
      
      return newAccount;
      
    } catch (error) {
      // Handle specific error scenarios
      // Maps COBOL: Account creation error handling
      
      if (error.status === 400) {
        // Validation errors from backend or frontend
        // Maps COBOL: Field validation failures
        if (error.errors && Object.keys(error.errors).length > 0) {
          // Format field-specific errors
          const errorMessages = Object.entries(error.errors)
            .map(([field, message]) => `${field}: ${message}`)
            .join('; ');
          return rejectWithValue(errorMessages);
        }
        return rejectWithValue(error.message || 'Invalid account data');
      }
      
      if (error.status === 404) {
        // Customer not found
        // Maps COBOL: CUSTDAT READ NOTFND condition
        return rejectWithValue('Customer not found');
      }
      
      if (error.status === 409) {
        // Duplicate account (unlikely with auto-generated IDs)
        return rejectWithValue('Account already exists');
      }
      
      // General error
      return rejectWithValue(error.message || 'Failed to create account');
    }
  }
);

/**
 * Account Slice Definition
 * 
 * Creates the Redux Toolkit slice with reducers and extraReducers.
 * Defines synchronous actions and handles async thunk state updates.
 * 
 * Slice Components:
 * - name: 'account' - Slice name for action type prefixing
 * - initialState: Initial state structure
 * - reducers: Synchronous state update functions
 * - extraReducers: Async thunk state transition handlers
 */
const accountSlice = createSlice({
  name: 'account',
  initialState,
  reducers: {
    /**
     * Set Current Account
     * 
     * Sets the currently selected account for detail view or edit.
     * Maps COBOL: Moving account record to WORKING-STORAGE.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Redux action with account payload
     */
    setCurrentAccount: (state, action) => {
      // Set current account from payload
      // Maps COBOL: MOVE ACCOUNT-RECORD TO WS-CURRENT-ACCOUNT
      state.currentAccount = action.payload;
      
      // Clear any existing error messages
      state.error = null;
    },
    
    /**
     * Update Filters
     * 
     * Updates filter criteria for account queries.
     * Maps COBOL: Setting filter variables in WORKING-STORAGE.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Redux action with filter updates
     */
    updateFilters: (state, action) => {
      // Merge new filter values with existing filters
      // Maps COBOL: MOVE values TO WS-ACCT-FILTER, WS-CUST-FILTER
      state.filters = {
        ...state.filters,
        ...action.payload
      };
      
      // Clear error when filters change
      state.error = null;
    },
    
    /**
     * Clear Current Account
     * 
     * Clears the currently selected account.
     * Maps COBOL: Initializing account record area to spaces/zeros.
     * 
     * @param {Object} state - Current Redux state
     */
    clearCurrentAccount: (state) => {
      // Reset current account to null
      // Maps COBOL: INITIALIZE ACCOUNT-RECORD
      state.currentAccount = null;
      
      // Clear messages
      state.error = null;
      state.successMessage = null;
    },
    
    /**
     * Clear Messages
     * 
     * Clears error and success messages from state.
     * Maps COBOL: Clearing message display areas.
     * 
     * @param {Object} state - Current Redux state
     */
    clearMessages: (state) => {
      // Clear all messages
      // Maps COBOL: MOVE SPACES TO WS-RETURN-MSG, WS-INFO-MSG
      state.error = null;
      state.successMessage = null;
    }
  },
  
  /**
   * Extra Reducers
   * 
   * Handles state transitions for async thunk actions.
   * Maps COBOL: File operation response code handling.
   * 
   * Async Thunk States:
   * - pending: Operation in progress (loading = true)
   * - fulfilled: Operation succeeded (update state with data)
   * - rejected: Operation failed (set error message)
   */
  extraReducers: (builder) => {
    // ===================================================================
    // Fetch Accounts Handlers
    // ===================================================================
    
    builder
      // Fetch Accounts - Pending
      // Maps COBOL: Setting WS-PROCESSING-FLAG to 'Y'
      .addCase(fetchAccounts.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      
      // Fetch Accounts - Fulfilled
      // Maps COBOL: Successful READNEXT loop completion
      .addCase(fetchAccounts.fulfilled, (state, action) => {
        state.loading = false;
        state.accounts = action.payload;
        state.error = null;
      })
      
      // Fetch Accounts - Rejected
      // Maps COBOL: File error handling in browse operation
      .addCase(fetchAccounts.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to fetch accounts';
        state.accounts = [];
      })
    
    // ===================================================================
    // Fetch Account By ID Handlers
    // ===================================================================
    
      // Fetch Account By ID - Pending
      // Maps COBOL: Before READ operation
      .addCase(fetchAccountById.pending, (state) => {
        state.loading = true;
        state.error = null;
        state.successMessage = null;
      })
      
      // Fetch Account By ID - Fulfilled
      // Maps COBOL: Successful EXEC CICS READ completion
      .addCase(fetchAccountById.fulfilled, (state, action) => {
        state.loading = false;
        state.currentAccount = action.payload;
        state.error = null;
        state.successMessage = 'Account loaded successfully';
      })
      
      // Fetch Account By ID - Rejected
      // Maps COBOL: NOTFND or IOERR response handling
      .addCase(fetchAccountById.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to fetch account';
        state.currentAccount = null;
        state.successMessage = null;
      })
    
    // ===================================================================
    // Update Account Handlers
    // ===================================================================
    
      // Update Account - Pending
      // Maps COBOL: Before REWRITE operation
      .addCase(updateAccount.pending, (state) => {
        state.loading = true;
        state.error = null;
        state.successMessage = null;
      })
      
      // Update Account - Fulfilled
      // Maps COBOL: Successful REWRITE and SYNCPOINT
      .addCase(updateAccount.fulfilled, (state, action) => {
        state.loading = false;
        state.currentAccount = action.payload;
        state.error = null;
        state.successMessage = 'Account updated successfully';
        
        // Update account in accounts array if present
        // Maps COBOL: Updating account in memory table
        const index = state.accounts.findIndex(
          account => account.accountId === action.payload.accountId
        );
        if (index !== -1) {
          state.accounts[index] = action.payload;
        }
      })
      
      // Update Account - Rejected
      // Maps COBOL: REWRITE error handling
      .addCase(updateAccount.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to update account';
        state.successMessage = null;
      })
    
    // ===================================================================
    // Create Account Handlers
    // ===================================================================
    
      // Create Account - Pending
      // Maps COBOL: Before WRITE operation
      .addCase(createAccount.pending, (state) => {
        state.loading = true;
        state.error = null;
        state.successMessage = null;
      })
      
      // Create Account - Fulfilled
      // Maps COBOL: Successful WRITE and SYNCPOINT
      .addCase(createAccount.fulfilled, (state, action) => {
        state.loading = false;
        state.currentAccount = action.payload;
        state.error = null;
        state.successMessage = 'Account created successfully';
        
        // Add new account to accounts array
        // Maps COBOL: Adding record to memory table
        state.accounts.unshift(action.payload);
      })
      
      // Create Account - Rejected
      // Maps COBOL: WRITE error handling
      .addCase(createAccount.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to create account';
        state.successMessage = null;
      });
  }
});

// ===================================================================
// Export Actions
// ===================================================================

/**
 * Export synchronous action creators
 * 
 * These actions can be dispatched directly from components to update state.
 * Maps COBOL: PERFORM paragraph to update WORKING-STORAGE variables.
 */
export const {
  setCurrentAccount,
  updateFilters,
  clearCurrentAccount,
  clearMessages
} = accountSlice.actions;

// ===================================================================
// Selectors
// ===================================================================

/**
 * Select Accounts Array
 * 
 * Returns the accounts array from state.
 * Maps COBOL: Accessing account table in WORKING-STORAGE.
 * 
 * @param {Object} state - Redux root state
 * @returns {Array} Array of account objects
 */
export const selectAccounts = (state) => state.account.accounts;

/**
 * Select Current Account
 * 
 * Returns the currently selected account.
 * Maps COBOL: Accessing WS-CURRENT-ACCOUNT.
 * 
 * @param {Object} state - Redux root state
 * @returns {Object|null} Current account object or null
 */
export const selectCurrentAccount = (state) => state.account.currentAccount;

/**
 * Select Account Filters
 * 
 * Returns the current filter criteria.
 * Maps COBOL: Accessing WS-ACCT-FILTER, WS-CUST-FILTER.
 * 
 * @param {Object} state - Redux root state
 * @returns {Object} Filter criteria object
 */
export const selectAccountFilters = (state) => state.account.filters;

/**
 * Select Account Loading State
 * 
 * Returns the loading state indicator.
 * Maps COBOL: Accessing WS-PROCESSING-FLAG.
 * 
 * @param {Object} state - Redux root state
 * @returns {boolean} Loading state
 */
export const selectAccountLoading = (state) => state.account.loading;

/**
 * Select Account Error
 * 
 * Returns the current error message.
 * Maps COBOL: Accessing WS-RETURN-MSG, WS-ERROR-MESSAGE.
 * 
 * @param {Object} state - Redux root state
 * @returns {string|null} Error message or null
 */
export const selectAccountError = (state) => state.account.error;

/**
 * Select Success Message
 * 
 * Returns the current success message.
 * Maps COBOL: Accessing WS-INFO-MSG.
 * 
 * @param {Object} state - Redux root state
 * @returns {string|null} Success message or null
 */
export const selectSuccessMessage = (state) => state.account.successMessage;

// ===================================================================
// Export Reducer
// ===================================================================

/**
 * Account Reducer Export
 * 
 * Default export of the account slice reducer.
 * This reducer is added to the Redux store configuration.
 * 
 * Store Configuration Example:
 * import accountReducer from './slices/accountSlice';
 * 
 * const store = configureStore({
 *   reducer: {
 *     account: accountReducer,
 *     // ... other reducers
 *   }
 * });
 * 
 * Component Usage Example:
 * import { useSelector, useDispatch } from 'react-redux';
 * import {
 *   fetchAccountById,
 *   selectCurrentAccount,
 *   selectAccountLoading
 * } from '../redux/slices/accountSlice';
 * 
 * function AccountViewComponent({ accountId }) {
 *   const dispatch = useDispatch();
 *   const account = useSelector(selectCurrentAccount);
 *   const loading = useSelector(selectAccountLoading);
 *   
 *   useEffect(() => {
 *     dispatch(fetchAccountById(accountId));
 *   }, [accountId, dispatch]);
 *   
 *   if (loading) return <div>Loading...</div>;
 *   if (!account) return <div>Account not found</div>;
 *   
 *   return (
 *     <div>
 *       <h2>Account {account.accountId}</h2>
 *       <p>Status: {account.accountStatus === 'Y' ? 'Active' : 'Inactive'}</p>
 *       <p>Balance: ${account.currentBalance.toFixed(2)}</p>
 *       <p>Credit Limit: ${account.creditLimit.toFixed(2)}</p>
 *       <p>Available: ${(account.creditLimit - account.currentBalance).toFixed(2)}</p>
 *     </div>
 *   );
 * }
 */
export default accountSlice.reducer;
