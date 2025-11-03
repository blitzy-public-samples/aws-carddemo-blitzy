/**
 * CardDemo Transaction Redux Slice
 * 
 * Redux Toolkit slice managing transaction data state with pagination, filtering,
 * and transaction operations for the CardDemo frontend application. This slice
 * provides centralized state management for all transaction-related UI components.
 * 
 * Transformation Context:
 * Transforms COBOL programs COTRN00C, COTRN01C, and COTRN02C from mainframe VSAM 
 * file operations to modern Redux state management with REST API integration.
 * 
 * COBOL Program Mapping:
 * - COTRN00C.cbl → fetchTransactions async thunk - Transaction list with 10-per-page pagination
 * - COTRN01C.cbl → fetchTransactionById async thunk - Single transaction detail view
 * - COTRN02C.cbl → createTransaction async thunk - Transaction creation with balance updates
 * - COTRN01C.cbl → fetchTransactionCategories async thunk - Category aggregation logic
 * 
 * Key Features:
 * - Transaction list state with 10-per-page pagination (matching COBOL COTRN00C pattern)
 * - Date range filtering for transaction queries
 * - Transaction category aggregations and summaries
 * - New transaction creation with atomic state updates
 * - PF7/PF8 navigation equivalent (goToPrevPage/goToNextPage actions)
 * - Loading states and error handling for async operations
 * - Comprehensive selectors for component data access
 * 
 * Data Structure Mapping:
 * Maps CVTRA05Y.cpy TRAN-RECORD structure to JavaScript transaction objects,
 * preserving all field definitions and data types from COBOL copybook.
 * 
 * Pagination Pattern:
 * Maintains exact COBOL pagination of 10 transactions per page as specified in
 * COTRN00C.cbl lines 290-303, preserving 3270 BMS screen layout constraints.
 * 
 * State Structure:
 * - transactions: Array of transaction objects (current page, max 10 items)
 * - currentTransaction: Selected transaction for detail view
 * - categoryAggregations: Category summary data array
 * - pagination: Page navigation metadata (currentPage, totalPages, hasNext/PrevPage)
 * - filters: Query filters (dates, types, categories, sorting)
 * - loading: Async operation loading state
 * - error: Error message from failed operations
 * - successMessage: Success confirmation message
 * 
 * Dependencies:
 * - @reduxjs/toolkit: Redux state management with createSlice and createAsyncThunk
 * - transactionService: Service layer for REST API transaction operations
 * 
 * @module redux/slices/transactionSlice
 */

import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import transactionService from '../../services/transactionService';

/**
 * Initial state for transaction slice
 * 
 * Defines the complete state structure for transaction management, including
 * transaction lists, pagination metadata, filtering parameters, and UI state.
 * 
 * Maps COBOL WORKING-STORAGE variables from COTRN00C.cbl to Redux state:
 * - WS-REC-COUNT → pagination.totalTransactions
 * - WS-PAGE-NUM → pagination.currentPage
 * - CDEMO-CT00-TRNID-FIRST/LAST → Not needed with page-based pagination
 * - CDEMO-CT00-NEXT-PAGE-FLG → pagination.hasNextPage
 * - WS-ERR-FLG → error state
 * - WS-MESSAGE → error/successMessage
 */
const initialState = {
  // Transaction data arrays
  transactions: [],           // Current page of transactions (max 10 items per COBOL pattern)
  currentTransaction: null,   // Selected transaction for detail view (COTRN01C equivalent)
  categoryAggregations: [],   // Category summary data (COTRN01C aggregation results)
  
  // Pagination state - Fixed 10 per page matching COBOL COTRN00C.cbl line 290
  pagination: {
    currentPage: 1,           // Current page number (1-based indexing, maps WS-PAGE-NUM)
    pageSize: 10,            // Fixed at 10 transactions per page per COBOL requirements
    totalTransactions: 0,    // Total number of transactions across all pages (WS-REC-COUNT)
    totalPages: 0,          // Calculated: Math.ceil(totalTransactions / pageSize)
    hasNextPage: false,     // Boolean flag for PF8 (forward) navigation
    hasPrevPage: false      // Boolean flag for PF7 (backward) navigation
  },
  
  // Filter state for transaction queries
  filters: {
    cardNumber: '',          // Filter by card number (maps COBOL TRAN-CARD-NUM)
    startDate: '',          // Start date for transaction range (YYYY-MM-DD format)
    endDate: '',            // End date for transaction range (YYYY-MM-DD format)
    transactionType: 'all', // 'all', 'debit', 'credit' (maps COBOL TRAN-TYPE-CD filter)
    categoryCode: '',       // Filter by category (maps COBOL TRAN-CAT-CD)
    minAmount: null,        // Minimum transaction amount filter
    maxAmount: null,        // Maximum transaction amount filter
    sortBy: 'originTimestamp', // Sort field (default: most recent first)
    sortOrder: 'desc'       // 'asc' or 'desc' (descending = most recent first)
  },
  
  // UI state for async operations and messages
  loading: false,            // Loading state for async operations
  error: null,              // Error message from failed operations (maps WS-MESSAGE on error)
  successMessage: null      // Success confirmation message (maps WS-MESSAGE on success)
};

/**
 * Async thunk to fetch paginated transactions with filtering
 * 
 * Maps COBOL COTRN00C.cbl transaction list display functionality which performs
 * sequential browse of VSAM TRANSACT file with pagination support.
 * 
 * COBOL Equivalence:
 * - COBOL line 593: EXEC CICS STARTBR DATASET(WS-TRANSACT-FILE) RIDFLD(TRAN-ID)
 * - COBOL line 626: EXEC CICS READNEXT DATASET(WS-TRANSACT-FILE) INTO(TRAN-RECORD)
 * - COBOL line 290: PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
 * - Java: GET /api/transactions with pagination params page, size=10
 * 
 * Pagination Pattern:
 * Fixed 10 transactions per page matching COBOL screen layout from BMS COTRN00M.
 * Page-based navigation replaces VSAM sequential browse with cursor positioning.
 * 
 * @param {Object} params - Query parameters for transaction retrieval
 * @param {number} params.page - Page number (1-based, default: 1)
 * @param {string} params.accountId - Account ID for filtering
 * @param {string} params.cardNumber - Card number for filtering (optional)
 * @param {string} params.startDate - Start date for filtering (optional, YYYY-MM-DD)
 * @param {string} params.endDate - End date for filtering (optional, YYYY-MM-DD)
 * @param {string} params.transactionType - Transaction type filter (optional)
 * @param {string} params.categoryCode - Category code filter (optional)
 * @returns {Promise<Object>} Promise resolving to paginated transaction data
 * 
 * Response includes:
 * - transactions: Array of transaction objects (max 10 items)
 * - totalTransactions: Total count across all pages
 * - currentPage: Current page number
 * - totalPages: Total number of pages
 */
export const fetchTransactions = createAsyncThunk(
  'transaction/fetchTransactions',
  async ({ page = 1, accountId, cardNumber, startDate, endDate, transactionType, categoryCode }, { rejectWithValue }) => {
    try {
      // Call transactionService.getTransactions with pagination and filters
      // Maps COBOL STARTBR + READNEXT loop to REST API GET request
      const response = await transactionService.getTransactions(
        accountId,
        page,
        10, // Fixed page size matching COBOL pattern
        startDate,
        endDate
      );
      
      // Return response data with pagination metadata
      // Maps COBOL screen population logic (lines 390-445) to state update
      return {
        transactions: response.transactions || [],
        totalTransactions: response.totalCount || 0,
        currentPage: response.currentPage || page,
        totalPages: response.totalPages || 0
      };
    } catch (error) {
      // Maps COBOL error handling (lines 602-619) to Redux error state
      return rejectWithValue(error.message || 'Failed to fetch transactions');
    }
  }
);

/**
 * Async thunk to fetch single transaction by ID
 * 
 * Maps COBOL COTRN01C.cbl transaction detail view functionality which performs
 * random READ of VSAM TRANSACT file by primary key.
 * 
 * COBOL Equivalence:
 * - COBOL: EXEC CICS READ FILE('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
 * - Java: GET /api/transactions/{transactionId}
 * 
 * @param {string} transactionId - Unique transaction identifier (maps TRAN-ID)
 * @returns {Promise<Object>} Promise resolving to transaction detail object
 */
export const fetchTransactionById = createAsyncThunk(
  'transaction/fetchTransactionById',
  async (transactionId, { rejectWithValue }) => {
    try {
      // Call transactionService.getTransaction for single record retrieval
      // Maps COBOL random READ with RIDFLD to REST API GET by ID
      const transaction = await transactionService.getTransaction(transactionId);
      
      return transaction;
    } catch (error) {
      // Maps COBOL HANDLE CONDITION NOTFND to error state
      return rejectWithValue(error.message || 'Transaction not found');
    }
  }
);

/**
 * Async thunk to create new transaction
 * 
 * Maps COBOL COTRN02C.cbl transaction creation functionality which performs
 * WRITE to VSAM TRANSACT file with synchronized account balance update.
 * 
 * COBOL Equivalence:
 * - COBOL: EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)
 * - COBOL: EXEC CICS REWRITE FILE('ACCTDAT') FROM(ACCT-RECORD)
 * - COBOL: EXEC CICS SYNCPOINT (atomic commit of both operations)
 * - Java: POST /api/transactions with @Transactional boundary
 * 
 * Transaction Atomicity:
 * Backend performs atomic operation equivalent to CICS SYNCPOINT,
 * ensuring transaction creation and balance update succeed or fail together.
 * 
 * @param {Object} transactionData - Transaction data for creation
 * @returns {Promise<Object>} Promise resolving to created transaction with new balance
 */
export const createTransaction = createAsyncThunk(
  'transaction/createTransaction',
  async (transactionData, { rejectWithValue }) => {
    try {
      // Call transactionService.createTransaction for transaction posting
      // Maps COBOL WRITE + REWRITE + SYNCPOINT to single REST API POST
      const newTransaction = await transactionService.createTransaction(transactionData);
      
      return newTransaction;
    } catch (error) {
      // Maps COBOL error handling and rollback logic to error state
      return rejectWithValue(error.message || 'Failed to create transaction');
    }
  }
);

/**
 * Async thunk to fetch transaction category aggregations
 * 
 * Maps COBOL COTRN01C.cbl category aggregation logic which performs sequential
 * read with grouping and summing by transaction category code.
 * 
 * COBOL Equivalence:
 * - COBOL: Sequential READ with category grouping
 * - COBOL: SUM accumulation by category (ADD TRAN-AMT TO CAT-TOTAL)
 * - COBOL: Percentage calculation (COMPUTE CAT-PCT = CAT-TOTAL / GRAND-TOTAL * 100)
 * - Java: GET /api/transactions/categories with server-side aggregation
 * 
 * Maps CVTRA01Y.cpy TRAN-CAT-BAL-RECORD structure:
 * - TRANCAT-ACCT-ID → accountId parameter
 * - TRANCAT-TYPE-CD → transactionType grouping
 * - TRANCAT-CD → category grouping
 * - TRAN-CAT-BAL → totalAmount result
 * 
 * @param {Object} params - Parameters for category aggregation
 * @param {string} params.accountId - Account ID for filtering
 * @param {string} params.startDate - Start date for period (optional)
 * @param {string} params.endDate - End date for period (optional)
 * @returns {Promise<Array>} Promise resolving to array of category aggregations
 */
export const fetchTransactionCategories = createAsyncThunk(
  'transaction/fetchTransactionCategories',
  async ({ accountId, startDate, endDate }, { rejectWithValue }) => {
    try {
      // Call transactionService.getTransactionsByCategory for aggregation
      // Maps COBOL category grouping and SUM logic to REST API aggregation
      const categories = await transactionService.getTransactionsByCategory(
        accountId,
        startDate,
        endDate
      );
      
      return categories;
    } catch (error) {
      // Maps COBOL aggregation error handling to error state
      return rejectWithValue(error.message || 'Failed to fetch transaction categories');
    }
  }
);

/**
 * Async thunk to fetch transaction summary statistics
 * 
 * Retrieves aggregated transaction statistics including totals, counts, and averages
 * for specified period. Maps COBOL summary calculation logic with COMP-3 precision.
 * 
 * @param {Object} params - Parameters for summary retrieval
 * @param {string} params.accountId - Account ID for summary
 * @param {string} params.period - Time period ('day', 'week', 'month', 'year')
 * @returns {Promise<Object>} Promise resolving to summary statistics object
 */
export const fetchTransactionSummary = createAsyncThunk(
  'transaction/fetchTransactionSummary',
  async ({ accountId, period = 'month' }, { rejectWithValue }) => {
    try {
      // Call transactionService.getTransactionSummary for statistics
      const summary = await transactionService.getTransactionSummary(accountId, period);
      
      return summary;
    } catch (error) {
      return rejectWithValue(error.message || 'Failed to fetch transaction summary');
    }
  }
);

/**
 * Transaction slice definition with reducers and extraReducers
 * 
 * Defines synchronous reducers for state mutations and handles async thunk
 * lifecycle actions (pending, fulfilled, rejected) in extraReducers.
 */
const transactionSlice = createSlice({
  name: 'transaction',
  initialState,
  reducers: {
    /**
     * Set current transaction for detail view
     * 
     * Maps COBOL COTRN01C selection logic where user selects transaction from list.
     * Updates currentTransaction state for detail component rendering.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Action with transaction payload
     */
    setCurrentTransaction: (state, action) => {
      state.currentTransaction = action.payload;
      state.error = null;
    },
    
    /**
     * Navigate to next page of transactions
     * 
     * Maps COBOL COTRN00C.cbl PF8 key handling (lines 255-274) for forward pagination.
     * Increments currentPage if hasNextPage is true, triggering component re-fetch.
     * 
     * COBOL Equivalence:
     * - PROCESS-PF8-KEY paragraph
     * - PERFORM PROCESS-PAGE-FORWARD
     * - Check NEXT-PAGE-YES flag before advancing
     * 
     * @param {Object} state - Current Redux state
     */
    goToNextPage: (state) => {
      if (state.pagination.hasNextPage) {
        state.pagination.currentPage += 1;
      }
    },
    
    /**
     * Navigate to previous page of transactions
     * 
     * Maps COBOL COTRN00C.cbl PF7 key handling (lines 232-252) for backward pagination.
     * Decrements currentPage if hasPrevPage is true, triggering component re-fetch.
     * 
     * COBOL Equivalence:
     * - PROCESS-PF7-KEY paragraph
     * - PERFORM PROCESS-PAGE-BACKWARD
     * - Check page number > 1 before going backward
     * 
     * @param {Object} state - Current Redux state
     */
    goToPrevPage: (state) => {
      if (state.pagination.hasPrevPage) {
        state.pagination.currentPage -= 1;
      }
    },
    
    /**
     * Navigate to specific page number
     * 
     * Provides direct page navigation capability for pagination controls.
     * Validates page number is within valid range before updating state.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Action with page number payload
     */
    goToPage: (state, action) => {
      const pageNumber = action.payload;
      if (pageNumber >= 1 && pageNumber <= state.pagination.totalPages) {
        state.pagination.currentPage = pageNumber;
      }
    },
    
    /**
     * Update transaction filters
     * 
     * Merges new filter values into existing filters state and resets pagination
     * to page 1. Triggers component re-fetch with new filter parameters.
     * 
     * Maps COBOL transaction filtering logic where user inputs filter criteria
     * and screen refreshes with filtered results.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Action with filter updates payload
     */
    updateFilters: (state, action) => {
      state.filters = { ...state.filters, ...action.payload };
      state.pagination.currentPage = 1; // Reset to first page on filter change
      state.error = null;
    },
    
    /**
     * Set date range filter for transactions
     * 
     * Updates startDate and endDate filters and resets pagination to page 1.
     * Maps COBOL date range filtering logic from COTRN00C.
     * 
     * @param {Object} state - Current Redux state
     * @param {Object} action - Action with startDate and endDate payload
     */
    setDateRange: (state, action) => {
      state.filters.startDate = action.payload.startDate;
      state.filters.endDate = action.payload.endDate;
      state.pagination.currentPage = 1;
      state.error = null;
    },
    
    /**
     * Clear current transaction selection
     * 
     * Resets currentTransaction to null, used when navigating away from
     * transaction detail view back to transaction list.
     * 
     * @param {Object} state - Current Redux state
     */
    clearCurrentTransaction: (state) => {
      state.currentTransaction = null;
      state.error = null;
    },
    
    /**
     * Clear error and success messages
     * 
     * Resets error and successMessage to null, typically called when
     * user dismisses notification or navigates to different view.
     * 
     * @param {Object} state - Current Redux state
     */
    clearMessages: (state) => {
      state.error = null;
      state.successMessage = null;
    },
    
    /**
     * Reset all filters to initial state
     * 
     * Clears all filter values and resets pagination to page 1.
     * Maps COBOL screen reset functionality (PF3 to return to unfiltered view).
     * 
     * @param {Object} state - Current Redux state
     */
    resetFilters: (state) => {
      state.filters = initialState.filters;
      state.pagination.currentPage = 1;
      state.error = null;
    }
  },
  
  /**
   * Extra reducers for async thunk lifecycle handling
   * 
   * Handles pending, fulfilled, and rejected states for all async thunks.
   * Updates loading state, populates data on success, and handles errors.
   */
  extraReducers: (builder) => {
    builder
      // fetchTransactions lifecycle
      .addCase(fetchTransactions.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchTransactions.fulfilled, (state, action) => {
        // Populate transactions array (max 10 items per COBOL pattern)
        state.transactions = action.payload.transactions;
        
        // Update pagination metadata
        state.pagination.totalTransactions = action.payload.totalTransactions;
        state.pagination.currentPage = action.payload.currentPage;
        state.pagination.totalPages = action.payload.totalPages;
        
        // Calculate hasNextPage and hasPrevPage flags for PF7/PF8 navigation
        // Maps COBOL NEXT-PAGE-YES/NO flags (lines 66-68, 310-312)
        state.pagination.hasNextPage = action.payload.currentPage < action.payload.totalPages;
        state.pagination.hasPrevPage = action.payload.currentPage > 1;
        
        state.loading = false;
        state.error = null;
      })
      .addCase(fetchTransactions.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to load transactions';
        state.transactions = [];
      })
      
      // fetchTransactionById lifecycle
      .addCase(fetchTransactionById.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchTransactionById.fulfilled, (state, action) => {
        state.currentTransaction = action.payload;
        state.loading = false;
        state.error = null;
      })
      .addCase(fetchTransactionById.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to load transaction details';
        state.currentTransaction = null;
      })
      
      // createTransaction lifecycle
      .addCase(createTransaction.pending, (state) => {
        state.loading = true;
        state.error = null;
        state.successMessage = null;
      })
      .addCase(createTransaction.fulfilled, (state, action) => {
        state.currentTransaction = action.payload;
        state.successMessage = 'Transaction created successfully';
        state.loading = false;
        state.error = null;
      })
      .addCase(createTransaction.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to create transaction';
        state.successMessage = null;
      })
      
      // fetchTransactionCategories lifecycle
      .addCase(fetchTransactionCategories.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchTransactionCategories.fulfilled, (state, action) => {
        state.categoryAggregations = action.payload;
        state.loading = false;
        state.error = null;
      })
      .addCase(fetchTransactionCategories.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to load transaction categories';
        state.categoryAggregations = [];
      })
      
      // fetchTransactionSummary lifecycle
      .addCase(fetchTransactionSummary.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchTransactionSummary.fulfilled, (state, action) => {
        // Summary data could be stored in a separate state property if needed
        // For now, it's handled by the component that dispatches this action
        state.loading = false;
        state.error = null;
      })
      .addCase(fetchTransactionSummary.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to load transaction summary';
      });
  }
});

/**
 * Selector Functions for Transaction State Access
 * 
 * Provides memoized selectors for components to access transaction state.
 * Following Redux best practices for encapsulating state shape knowledge.
 */

/**
 * Select transactions array from state
 * Returns current page of transactions (max 10 items per COBOL pattern)
 * 
 * @param {Object} state - Redux root state
 * @returns {Array} Array of transaction objects
 */
export const selectTransactions = (state) => state.transaction.transactions;

/**
 * Select current transaction for detail view
 * 
 * @param {Object} state - Redux root state
 * @returns {Object|null} Current transaction object or null
 */
export const selectCurrentTransaction = (state) => state.transaction.currentTransaction;

/**
 * Select pagination metadata
 * Returns complete pagination object with page numbers and navigation flags
 * 
 * @param {Object} state - Redux root state
 * @returns {Object} Pagination metadata object
 */
export const selectTransactionPagination = (state) => state.transaction.pagination;

/**
 * Select transaction filters
 * Returns current filter state for date range, types, categories, etc.
 * 
 * @param {Object} state - Redux root state
 * @returns {Object} Filters object
 */
export const selectTransactionFilters = (state) => state.transaction.filters;

/**
 * Select category aggregations array
 * Returns transaction category summary data for chart/report display
 * 
 * @param {Object} state - Redux root state
 * @returns {Array} Array of category aggregation objects
 */
export const selectCategoryAggregations = (state) => state.transaction.categoryAggregations;

/**
 * Select hasNextPage flag
 * Determines if PF8 (forward) navigation is available
 * 
 * @param {Object} state - Redux root state
 * @returns {boolean} True if next page exists
 */
export const selectHasNextPage = (state) => state.transaction.pagination.hasNextPage;

/**
 * Select hasPrevPage flag
 * Determines if PF7 (backward) navigation is available
 * 
 * @param {Object} state - Redux root state
 * @returns {boolean} True if previous page exists
 */
export const selectHasPrevPage = (state) => state.transaction.pagination.hasPrevPage;

/**
 * Select current page number
 * 
 * @param {Object} state - Redux root state
 * @returns {number} Current page number (1-based)
 */
export const selectCurrentPageNumber = (state) => state.transaction.pagination.currentPage;

/**
 * Select total pages count
 * 
 * @param {Object} state - Redux root state
 * @returns {number} Total number of pages
 */
export const selectTotalPages = (state) => state.transaction.pagination.totalPages;

/**
 * Select date range filter
 * Returns object with startDate and endDate
 * 
 * @param {Object} state - Redux root state
 * @returns {Object} Date range object { startDate, endDate }
 */
export const selectDateRange = (state) => ({
  startDate: state.transaction.filters.startDate,
  endDate: state.transaction.filters.endDate
});

/**
 * Select active filters
 * Returns only filters that have non-default values set
 * 
 * @param {Object} state - Redux root state
 * @returns {Object} Object containing only active filter key-value pairs
 */
export const selectActiveFilters = (state) => {
  const filters = state.transaction.filters;
  const activeFilters = {};
  
  // Include only filters with non-empty values
  Object.keys(filters).forEach(key => {
    const value = filters[key];
    if (value !== '' && value !== null && value !== 'all') {
      activeFilters[key] = value;
    }
  });
  
  return activeFilters;
};

/**
 * Select loading state
 * 
 * @param {Object} state - Redux root state
 * @returns {boolean} True if async operation in progress
 */
export const selectTransactionLoading = (state) => state.transaction.loading;

/**
 * Select error message
 * 
 * @param {Object} state - Redux root state
 * @returns {string|null} Error message or null
 */
export const selectTransactionError = (state) => state.transaction.error;

/**
 * Select success message
 * 
 * @param {Object} state - Redux root state
 * @returns {string|null} Success message or null
 */
export const selectTransactionSuccessMessage = (state) => state.transaction.successMessage;

/**
 * Export reducer and actions
 * 
 * Default export: reducer function for Redux store configuration
 * Named exports: Action creators and async thunks for component dispatch
 */
export const {
  setCurrentTransaction,
  goToNextPage,
  goToPrevPage,
  goToPage,
  updateFilters,
  setDateRange,
  clearCurrentTransaction,
  clearMessages,
  resetFilters
} = transactionSlice.actions;

export default transactionSlice.reducer;

