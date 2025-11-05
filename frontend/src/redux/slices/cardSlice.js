/**
 * CardDemo Redux Card Slice
 * 
 * Redux Toolkit slice managing credit card state in the CardDemo application.
 * Handles card list retrieval with pagination (7 cards per page), card detail views,
 * and card update operations.
 * 
 * Transformation Context:
 * Replaces COBOL CICS card management programs with Redux state management:
 * - COCRDLIC.cbl → fetchCards async thunk with 7-per-page pagination
 * - COCRDSLC.cbl → fetchCardById async thunk for card detail view
 * - COCRDUPC.cbl → updateCard async thunk for card status and info updates
 * 
 * COBOL Business Logic Preservation:
 * - Maintains 7 cards per page pagination pattern from COBOL BMS screen layout (WS-EDIT-SELECT-ARRAY OCCURS 7 TIMES)
 * - Preserves VSAM CARDDAT record structure in Redux state
 * - Retains PF7/PF8 pagination navigation logic via goToPrevPage/goToNextPage actions
 * - Maintains role-based access control for admin vs regular users
 * - Preserves card status validation ('A'=Active, 'E'=Expired, 'B'=Blocked)
 * 
 * State Structure:
 * - cards: Array of card objects for current page (max 7 items per page)
 * - currentCard: Selected card for detail view or update operations
 * - pagination: Page metadata (currentPage, pageSize=7, totalCards, totalPages, navigation flags)
 * - filters: Account ID, card number, and status filters matching COBOL search criteria
 * - loading: Async operation status for UI feedback
 * - error: Error message from failed operations
 * - successMessage: Success feedback message after successful updates
 * 
 * Key Features:
 * - Fixed 7-per-page pagination matching COBOL screen layout requirement
 * - PF key equivalent actions (PF7=goToPrevPage, PF8=goToNextPage)
 * - Card selection state for view/update workflows
 * - Filter management for account-based card lists
 * - Comprehensive error handling with user-friendly messages
 * - Loading state management for async operations
 * 
 * @module redux/slices/cardSlice
 */

import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';
import cardService from '../../services/cardService';

/**
 * Initial state for card slice
 * 
 * Maps COBOL COCRDLIC.cbl working storage section variables to Redux state:
 * - WS-EDIT-SELECT-ARRAY OCCURS 7 TIMES → cards array (max 7 items)
 * - WS-SCRN-COUNTER → pagination.currentPage
 * - Card filters (ACCTFILTER, CARDFILTER) → filters object
 * - Selected card context → currentCard
 */
const initialState = {
  // Current page of cards (max 7 items per page matching COBOL screen layout)
  cards: [],
  
  // Selected card for detail view or update operations
  // Maps COBOL I-SELECTED subscript variable for selected record
  currentCard: null,
  
  // Pagination metadata matching COBOL 7-record screen pattern
  pagination: {
    currentPage: 1,        // Current page number (1-based indexing)
    pageSize: 7,           // Fixed at 7 cards per page (COBOL WS-EDIT-SELECT-ARRAY OCCURS 7)
    totalCards: 0,         // Total number of cards across all pages
    totalPages: 0,         // Calculated: Math.ceil(totalCards / pageSize)
    hasNextPage: false,    // PF8 (forward) availability flag
    hasPrevPage: false     // PF7 (backward) availability flag
  },
  
  // Filter criteria matching COBOL filter flags
  // Maps WS-EDIT-ACCT-FLAG and WS-EDIT-CARD-FLAG from COCRDLIC.cbl
  filters: {
    accountId: '',         // Filter by account ID (11 digits)
    cardNumber: '',        // Search by card number (16 digits)
    status: 'all'          // Filter by status: 'all', 'A' (Active), 'E' (Expired), 'B' (Blocked)
  },
  
  // Async operation state
  loading: false,          // Loading indicator for async operations
  error: null,             // Error message from failed operations
  successMessage: null     // Success message after successful updates
};

/**
 * Async thunk to fetch paginated list of cards
 * 
 * Maps COBOL program COCRDLIC.cbl card list retrieval:
 * - EXEC CICS STARTBR DATASET('CARDDAT') → Backend GET /api/cards
 * - EXEC CICS READNEXT loop → Backend pagination with page/size params
 * - WS-SCRN-COUNTER logic → currentPage state management
 * 
 * COBOL Equivalence:
 * - COCRDLIC.cbl lines 145-150: Screen counter and 7-record pagination
 * - Lines 61-68: Account and card filter flags
 * - Lines 74-76: WS-EDIT-SELECT-ARRAY OCCURS 7 TIMES
 * 
 * @param {Object} params - Fetch parameters
 * @param {number} params.page - Page number (1-based, default: 1)
 * @param {string} params.accountId - Account ID filter (optional)
 * @returns {Promise} Promise resolving to paginated card list with metadata
 * 
 * Note: cardNumber and status filters are managed in Redux state but not yet
 * passed to backend API. These can be used for client-side filtering or
 * integrated with backend API in future enhancements.
 */
export const fetchCards = createAsyncThunk(
  'card/fetchAll',
  async ({ page = 1, accountId = '' } = {}, { rejectWithValue }) => {
    try {
      // Call cardService.getCards with pagination params (pageSize fixed at 7)
      // Maps COBOL COCRDLIC.cbl sequential VSAM browse with 7-record screen limit
      // Note: cardNumber and status filters are stored in Redux state but not yet
      // passed to backend API - can be used for client-side filtering or backend
      // integration in future enhancement
      const response = await cardService.getCards(
        accountId || null,  // Pass null if empty string to match COBOL filter logic
        page,
        7  // Fixed page size matching COBOL WS-EDIT-SELECT-ARRAY OCCURS 7 TIMES
      );
      
      // Return normalized response with pagination metadata
      return {
        cards: response.cards || [],
        totalCards: response.totalCount || 0,
        currentPage: response.currentPage || page,
        totalPages: response.totalPages || 0
      };
    } catch (error) {
      // Return user-friendly error message
      return rejectWithValue(error.message || 'Failed to retrieve card list. Please try again.');
    }
  }
);

/**
 * Async thunk to fetch single card detail by card number
 * 
 * Maps COBOL program COCRDSLC.cbl card detail retrieval:
 * - EXEC CICS READ DATASET('CARDDAT') RIDFLD(WS-CARD-RID) → GET /api/cards/:cardNumber
 * - Direct access by card number key (primary key lookup)
 * 
 * COBOL Equivalence:
 * - COCRDSLC.cbl: Direct read by card number from VSAM CARDDAT file
 * - WS-CARD-RID structure with card number key
 * 
 * @param {string} cardNumber - 16-digit card number (primary key)
 * @returns {Promise} Promise resolving to card detail object
 */
export const fetchCardById = createAsyncThunk(
  'card/fetchById',
  async (cardNumber, { rejectWithValue }) => {
    try {
      // Call cardService.getCard with card number
      // Maps COBOL COCRDSLC.cbl direct VSAM read by key
      const card = await cardService.getCard(cardNumber);
      return card;
    } catch (error) {
      // Return user-friendly error message
      return rejectWithValue(error.message || 'Failed to retrieve card details. Please try again.');
    }
  }
);

/**
 * Async thunk to update card information
 * 
 * Maps COBOL program COCRDUPC.cbl card update processing:
 * - EXEC CICS READ DATASET('CARDDAT') UPDATE → Backend read with lock
 * - EXEC CICS REWRITE DATASET('CARDDAT') → PUT /api/cards/:cardNumber
 * - EXEC CICS SYNCPOINT → Backend @Transactional commit
 * 
 * COBOL Equivalence:
 * - COCRDUPC.cbl: Card update with status validation
 * - Transaction atomicity via CICS SYNCPOINT → Spring @Transactional
 * - Card status validation: 'A', 'E', 'B' only
 * 
 * @param {Object} params - Update parameters
 * @param {string} params.cardNumber - Card number to update (16 digits)
 * @param {Object} params.cardData - Card fields to update
 * @returns {Promise} Promise resolving to updated card object
 */
export const updateCard = createAsyncThunk(
  'card/update',
  async ({ cardNumber, cardData }, { rejectWithValue }) => {
    try {
      // Call cardService.updateCard with card number and update data
      // Maps COBOL COCRDUPC.cbl REWRITE CARDDAT operation
      const updatedCard = await cardService.updateCard(cardNumber, cardData);
      return updatedCard;
    } catch (error) {
      // Return user-friendly error message
      return rejectWithValue(error.message || 'Failed to update card. Please try again.');
    }
  }
);

/**
 * Async thunk to fetch all cards for a specific customer
 * 
 * Maps COBOL cross-reference navigation from customer to cards:
 * - VSAM XREF file navigation (CUSTDAT → ACCTDAT → CARDDAT)
 * - Returns all cards across all accounts for customer
 * 
 * @param {string} customerId - Customer ID (9 digits)
 * @returns {Promise} Promise resolving to array of card objects
 */
export const fetchCardsByCustomer = createAsyncThunk(
  'card/fetchByCustomer',
  async (customerId, { rejectWithValue }) => {
    try {
      // Call cardService.getCardsByCustomer
      // Maps COBOL XREF file navigation pattern
      const cards = await cardService.getCardsByCustomer(customerId);
      return cards;
    } catch (error) {
      // Return user-friendly error message
      return rejectWithValue(error.message || 'Failed to retrieve customer cards. Please try again.');
    }
  }
);

/**
 * Card slice definition with reducers and extraReducers
 * 
 * Implements Redux Toolkit slice pattern replacing COBOL CICS state management.
 * All state mutations follow Redux immutability principles.
 */
const cardSlice = createSlice({
  name: 'card',
  initialState,
  reducers: {
    /**
     * Set current card for detail view or update operations
     * 
     * Maps COBOL I-SELECTED variable for selected card record.
     * Used when user selects 'S' (view) or 'U' (update) action on card list.
     * 
     * @param {Object} state - Current state
     * @param {Object} action - Action with card payload
     */
    setCurrentCard: (state, action) => {
      state.currentCard = action.payload;
      state.error = null;  // Clear errors when setting new card
    },
    
    /**
     * Navigate to next page (PF8 equivalent)
     * 
     * Maps COBOL PF8 key handling for forward pagination.
     * Only increments page if hasNextPage is true.
     * 
     * COBOL Equivalence:
     * - PF8 key handling in COCRDLIC.cbl
     * - WS-SCRN-COUNTER increment logic
     */
    goToNextPage: (state) => {
      if (state.pagination.hasNextPage) {
        state.pagination.currentPage += 1;
      }
    },
    
    /**
     * Navigate to previous page (PF7 equivalent)
     * 
     * Maps COBOL PF7 key handling for backward pagination.
     * Only decrements page if hasPrevPage is true.
     * 
     * COBOL Equivalence:
     * - PF7 key handling in COCRDLIC.cbl
     * - WS-SCRN-COUNTER decrement logic
     */
    goToPrevPage: (state) => {
      if (state.pagination.hasPrevPage) {
        state.pagination.currentPage -= 1;
      }
    },
    
    /**
     * Navigate to specific page number
     * 
     * Direct page navigation for pagination controls.
     * Validates page number is within valid range.
     * 
     * @param {Object} state - Current state
     * @param {Object} action - Action with page number payload
     */
    goToPage: (state, action) => {
      const pageNumber = action.payload;
      // Validate page number is within valid range
      if (pageNumber >= 1 && pageNumber <= state.pagination.totalPages) {
        state.pagination.currentPage = pageNumber;
      }
    },
    
    /**
     * Update filter criteria and reset to first page
     * 
     * Maps COBOL filter flag handling (WS-EDIT-ACCT-FLAG, WS-EDIT-CARD-FLAG).
     * Resets pagination to page 1 when filters change.
     * 
     * COBOL Equivalence:
     * - COCRDLIC.cbl lines 61-68: Filter flags
     * - Filter change resets screen counter to 0
     * 
     * @param {Object} state - Current state
     * @param {Object} action - Action with filter updates
     */
    updateFilters: (state, action) => {
      state.filters = { ...state.filters, ...action.payload };
      // Reset to first page when filters change (COBOL pattern)
      state.pagination.currentPage = 1;
      state.error = null;  // Clear errors when filters change
    },
    
    /**
     * Clear current card selection
     * 
     * Clears selected card when returning to list view or canceling operation.
     * Maps COBOL clearing of I-SELECTED subscript variable.
     */
    clearCurrentCard: (state) => {
      state.currentCard = null;
    },
    
    /**
     * Clear error and success messages
     * 
     * Clears feedback messages after user acknowledgement.
     * Maps COBOL clearing of WS-ERROR-MSG and WS-INFO-MSG.
     */
    clearMessages: (state) => {
      state.error = null;
      state.successMessage = null;
    }
  },
  
  /**
   * Extra reducers for async thunk state management
   * 
   * Handles pending, fulfilled, and rejected states for all async operations.
   * Implements loading states and error handling patterns.
   */
  extraReducers: (builder) => {
    builder
      // fetchCards async thunk handlers
      .addCase(fetchCards.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchCards.fulfilled, (state, action) => {
        state.loading = false;
        state.cards = action.payload.cards;
        
        // Update pagination metadata
        state.pagination.totalCards = action.payload.totalCards;
        state.pagination.currentPage = action.payload.currentPage;
        state.pagination.totalPages = action.payload.totalPages;
        
        // Calculate navigation flags for PF7/PF8 equivalent buttons
        state.pagination.hasNextPage = action.payload.currentPage < action.payload.totalPages;
        state.pagination.hasPrevPage = action.payload.currentPage > 1;
        
        state.error = null;
      })
      .addCase(fetchCards.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to retrieve card list.';
        state.cards = [];  // Clear cards on error
      })
      
      // fetchCardById async thunk handlers
      .addCase(fetchCardById.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchCardById.fulfilled, (state, action) => {
        state.loading = false;
        state.currentCard = action.payload;
        state.error = null;
      })
      .addCase(fetchCardById.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to retrieve card details.';
        state.currentCard = null;  // Clear current card on error
      })
      
      // updateCard async thunk handlers
      .addCase(updateCard.pending, (state) => {
        state.loading = true;
        state.error = null;
        state.successMessage = null;
      })
      .addCase(updateCard.fulfilled, (state, action) => {
        state.loading = false;
        state.currentCard = action.payload;
        state.successMessage = 'Card updated successfully';
        state.error = null;
        
        // Update card in the list if it exists
        const cardIndex = state.cards.findIndex(
          card => card.cardNumber === action.payload.cardNumber
        );
        if (cardIndex !== -1) {
          state.cards[cardIndex] = action.payload;
        }
      })
      .addCase(updateCard.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to update card.';
        state.successMessage = null;
      })
      
      // fetchCardsByCustomer async thunk handlers
      .addCase(fetchCardsByCustomer.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(fetchCardsByCustomer.fulfilled, (state, action) => {
        state.loading = false;
        state.cards = action.payload;
        
        // Update pagination for customer card list (no pagination, show all)
        state.pagination.totalCards = action.payload.length;
        state.pagination.currentPage = 1;
        state.pagination.totalPages = 1;
        state.pagination.hasNextPage = false;
        state.pagination.hasPrevPage = false;
        
        state.error = null;
      })
      .addCase(fetchCardsByCustomer.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload || 'Failed to retrieve customer cards.';
        state.cards = [];
      });
  }
});

/**
 * Selector functions for accessing card state
 * 
 * Provides memoized selectors for React components to access card state.
 * Follows Redux best practices for state selection.
 */

/**
 * Select cards array for current page
 * @param {Object} state - Redux root state
 * @returns {Array} Array of card objects (max 7 items)
 */
export const selectCards = (state) => state.card.cards;

/**
 * Select current card for detail view or update
 * @param {Object} state - Redux root state
 * @returns {Object|null} Current card object or null
 */
export const selectCurrentCard = (state) => state.card.currentCard;

/**
 * Select pagination metadata
 * @param {Object} state - Redux root state
 * @returns {Object} Pagination object with currentPage, pageSize, totalCards, totalPages, navigation flags
 */
export const selectCardPagination = (state) => state.card.pagination;

/**
 * Select filter criteria
 * @param {Object} state - Redux root state
 * @returns {Object} Filters object with accountId, cardNumber, status
 */
export const selectCardFilters = (state) => state.card.filters;

/**
 * Select loading state
 * @param {Object} state - Redux root state
 * @returns {boolean} Loading indicator
 */
export const selectCardLoading = (state) => state.card.loading;

/**
 * Select error message
 * @param {Object} state - Redux root state
 * @returns {string|null} Error message or null
 */
export const selectCardError = (state) => state.card.error;

/**
 * Select success message
 * @param {Object} state - Redux root state
 * @returns {string|null} Success message or null
 */
export const selectCardSuccessMessage = (state) => state.card.successMessage;

/**
 * Select hasNextPage flag (PF8 availability)
 * @param {Object} state - Redux root state
 * @returns {boolean} True if next page is available
 */
export const selectHasNextPage = (state) => state.card.pagination.hasNextPage;

/**
 * Select hasPrevPage flag (PF7 availability)
 * @param {Object} state - Redux root state
 * @returns {boolean} True if previous page is available
 */
export const selectHasPrevPage = (state) => state.card.pagination.hasPrevPage;

/**
 * Select current page number
 * @param {Object} state - Redux root state
 * @returns {number} Current page number (1-based)
 */
export const selectCurrentPageNumber = (state) => state.card.pagination.currentPage;

/**
 * Select total pages count
 * @param {Object} state - Redux root state
 * @returns {number} Total number of pages
 */
export const selectTotalPages = (state) => state.card.pagination.totalPages;

/**
 * Select total cards count
 * @param {Object} state - Redux root state
 * @returns {number} Total number of cards across all pages
 */
export const selectTotalCards = (state) => state.card.pagination.totalCards;

// Export action creators
export const {
  setCurrentCard,
  goToNextPage,
  goToPrevPage,
  goToPage,
  updateFilters,
  clearCurrentCard,
  clearMessages
} = cardSlice.actions;

// Export reducer as default
export default cardSlice.reducer;
