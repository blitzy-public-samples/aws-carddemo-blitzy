/**
 * CardDemo Card Service
 * 
 * Card management service providing RESTful operations for credit card management
 * in the CardDemo frontend application. This module encapsulates all card-related
 * operations, transforming COBOL VSAM CARDDAT file operations into modern REST API calls.
 * 
 * Transformation Context:
 * Maps three COBOL programs to JavaScript service methods:
 * - COCRDLIC.cbl → getCards() - Card list display with pagination (7 cards per page)
 * - COCRDSLC.cbl → getCard() - Card detail view by card number
 * - COCRDUPC.cbl → updateCard() - Card information update including status changes
 * 
 * COBOL Business Logic Preservation:
 * - Maintains pagination pattern of 7 cards per page from COBOL screen layout
 * - Preserves role-based access control (admin users see all cards, regular users see own)
 * - Retains card status validation ('A'=Active, 'E'=Expired, 'B'=Blocked)
 * - Maintains VSAM cross-reference navigation patterns for customer-to-cards relationships
 * 
 * Key Features:
 * - Paginated card list retrieval with configurable page size (default 7)
 * - Card detail retrieval by card number
 * - Card status and information updates
 * - Customer-based card retrieval for cross-reference navigation
 * - Client-side sorting utilities for card arrays
 * - Comprehensive error handling with user-friendly messages
 * - Role-based access control enforcement
 * 
 * @module services/cardService
 */

import apiClient from './apiClient';

/**
 * Retrieve paginated list of credit cards
 * 
 * Maps COBOL program COCRDLIC.cbl which handles card list display with pagination.
 * 
 * COBOL Equivalence:
 * - EXEC CICS READ DATASET('CARDDAT') sequential access
 * - Screen layout: 7 cards per page (WS-SCREEN-ROWS OCCURS 7 TIMES)
 * - Role-based filtering:
 *   * Admin users (USER-TYPE='A'): All cards if no accountId specified
 *   * Regular users (USER-TYPE='R'): Only cards for specified accountId
 * 
 * Business Logic:
 * - Default page size of 7 cards matches COBOL BMS screen layout
 * - Pagination metadata includes totalCount, currentPage, totalPages
 * - Admin users can retrieve all cards by omitting accountId parameter
 * - Regular users must provide accountId to filter cards
 * - Supports filtering by account ID for account-specific card lists
 * 
 * @param {string} accountId - Account ID to filter cards (optional for admin users)
 * @param {number} page - Page number (1-based indexing, default: 1)
 * @param {number} pageSize - Number of cards per page (default: 7, matching COBOL pattern)
 * @returns {Promise<Object>} Promise resolving to paginated card list response
 *   Response structure: {
 *     cards: Array<Object>,        // Array of card objects
 *     totalCount: number,           // Total number of cards matching filter
 *     currentPage: number,          // Current page number (1-based)
 *     totalPages: number,           // Total number of pages
 *     pageSize: number              // Number of items per page (7)
 *   }
 * @throws {Error} Network error, authentication error, or server error
 * 
 * @example
 * // Retrieve first page of cards for specific account (regular user)
 * const response = await getCards('00000000001', 1, 7);
 * const { cards, totalCount, currentPage, totalPages } = response;
 * console.log(`Showing ${cards.length} of ${totalCount} cards`);
 * 
 * @example
 * // Retrieve all cards (admin user, no accountId filter)
 * const response = await getCards(null, 1, 7);
 * const { cards } = response;
 * console.log(`Admin view: ${cards.length} cards`);
 * 
 * @example
 * // Navigate to second page
 * const response = await getCards('00000000001', 2, 7);
 * const { cards, currentPage, totalPages } = response;
 * console.log(`Page ${currentPage} of ${totalPages}`);
 */
const getCards = async (accountId = null, page = 1, pageSize = 7) => {
  try {
    // Build query parameters object
    const params = {
      page,
      pageSize
    };
    
    // Add accountId filter if provided (required for regular users)
    // Admin users can omit this to retrieve all cards
    if (accountId) {
      params.accountId = accountId;
    }
    
    // Execute GET request to card list endpoint
    // Maps COBOL COCRDLIC.cbl sequential read of CARDDAT file
    const response = await apiClient.get('/cards', { params });
    
    // Return response data containing paginated card list
    // Structure matches COBOL COMMAREA output structure
    return response.data;
  } catch (error) {
    // Enhanced error handling with context-specific messages
    if (error.status === 403) {
      throw new Error('You do not have permission to view these cards.');
    } else if (error.status === 404) {
      throw new Error('No cards found for the specified account.');
    } else if (error.networkError) {
      throw new Error('Network error. Please check your connection and try again.');
    } else {
      throw new Error(error.message || 'Failed to retrieve card list. Please try again.');
    }
  }
};

/**
 * Retrieve detailed information for a specific credit card
 * 
 * Maps COBOL program COCRDSLC.cbl which handles card detail view requests.
 * 
 * COBOL Equivalence:
 * - EXEC CICS READ DATASET('CARDDAT') RIDFLD(CARD-NUM) direct access by key
 * - Returns complete card record with all fields
 * - Handles NOTFND condition when card number doesn't exist
 * 
 * Business Logic:
 * - Validates card number format before API call
 * - Retrieves complete card details including:
 *   * Card number (primary key)
 *   * Account ID (foreign key to ACCTDAT)
 *   * Card status ('A'=Active, 'E'=Expired, 'B'=Blocked)
 *   * Card type (Credit, Debit, etc.)
 *   * Expiry date
 *   * Embossed name
 *   * Issue date
 * - Enforces role-based access control (user can only view own cards unless admin)
 * 
 * @param {string} cardNumber - Credit card number (16 digits, primary key)
 * @returns {Promise<Object>} Promise resolving to card detail object
 *   Card structure: {
 *     cardNumber: string,          // 16-digit card number
 *     accountId: string,            // Associated account ID (11 digits)
 *     cardStatus: string,           // 'A'=Active, 'E'=Expired, 'B'=Blocked
 *     cardType: string,             // Card type (e.g., 'Credit', 'Debit')
 *     expiryDate: string,           // Expiration date (YYYY-MM-DD)
 *     embossedName: string,         // Name printed on card
 *     issuedDate: string,           // Date card was issued (YYYY-MM-DD)
 *     creditLimit: number,          // Associated account credit limit (optional)
 *     currentBalance: number        // Associated account current balance (optional)
 *   }
 * @throws {Error} Invalid card number, card not found, permission denied, or server error
 * 
 * @example
 * // Retrieve card details by card number
 * const card = await getCard('4111111111111111');
 * console.log(`Card Status: ${card.cardStatus}`);
 * console.log(`Expiry: ${card.expiryDate}`);
 * console.log(`Account: ${card.accountId}`);
 * 
 * @example
 * // Handle card not found scenario
 * try {
 *   const card = await getCard('9999999999999999');
 * } catch (error) {
 *   console.error('Card not found:', error.message);
 * }
 */
const getCard = async (cardNumber) => {
  try {
    // Validate card number format - check type first
    if (typeof cardNumber !== 'string') {
      throw new Error('Invalid card number format. Card number is required.');
    }
    
    // Trim and check for empty string
    const trimmedCardNumber = cardNumber.trim();
    if (trimmedCardNumber.length === 0) {
      throw new Error('Invalid card number format. Card number cannot be empty.');
    }
    
    // Execute GET request to retrieve card detail
    // Maps COBOL COCRDSLC.cbl direct read by card number key
    const response = await apiClient.get(`/cards/${trimmedCardNumber}`);
    
    // Return card detail object
    return response.data;
  } catch (error) {
    // Enhanced error handling with context-specific messages
    if (error.status === 404) {
      throw new Error('Card not found. Please verify the card number and try again.');
    } else if (error.status === 403) {
      throw new Error('You do not have permission to view this card.');
    } else if (error.networkError) {
      throw new Error('Network error. Please check your connection and try again.');
    } else {
      throw new Error(error.message || 'Failed to retrieve card details. Please try again.');
    }
  }
};

/**
 * Update credit card information
 * 
 * Maps COBOL program COCRDUPC.cbl which handles card update processing.
 * 
 * COBOL Equivalence:
 * - EXEC CICS READ DATASET('CARDDAT') for update with exclusive lock
 * - EXEC CICS REWRITE DATASET('CARDDAT') to commit changes
 * - EXEC CICS SYNCPOINT for transaction commit
 * - Validates card status values before update
 * - Maintains COMP-3 precision for balance fields
 * 
 * Business Logic:
 * - Card status validation: Only 'A' (Active), 'E' (Expired), or 'B' (Blocked) allowed
 * - Expiry date validation: Must be future date
 * - Role-based access control: Regular users can only update own cards, admins can update any
 * - Preserves transaction atomicity (all changes commit or rollback together)
 * - Maintains referential integrity with account record
 * 
 * Updateable Fields:
 * - cardStatus: 'A' (Active), 'E' (Expired), 'B' (Blocked)
 * - expiryDate: New expiration date (YYYY-MM-DD format)
 * - embossedName: Name on card (string, max 30 characters)
 * 
 * Non-Updateable Fields (ignored if provided):
 * - cardNumber: Primary key, immutable
 * - accountId: Foreign key, immutable
 * - issuedDate: Historical data, immutable
 * 
 * @param {string} cardNumber - Card number to update (16 digits, primary key)
 * @param {Object} cardData - Card fields to update
 * @param {string} cardData.cardStatus - New card status ('A', 'E', or 'B')
 * @param {string} cardData.expiryDate - New expiry date (YYYY-MM-DD)
 * @param {string} cardData.embossedName - Updated name on card
 * @returns {Promise<Object>} Promise resolving to updated card object
 * @throws {Error} Validation error, permission denied, card not found, or server error
 * 
 * @example
 * // Update card status to blocked
 * const updatedCard = await updateCard('4111111111111111', {
 *   cardStatus: 'B'
 * });
 * console.log(`Card status updated to: ${updatedCard.cardStatus}`);
 * 
 * @example
 * // Update expiry date
 * const updatedCard = await updateCard('4111111111111111', {
 *   expiryDate: '2028-12-31'
 * });
 * console.log(`New expiry date: ${updatedCard.expiryDate}`);
 * 
 * @example
 * // Handle validation error
 * try {
 *   await updateCard('4111111111111111', {
 *     cardStatus: 'X' // Invalid status
 *   });
 * } catch (error) {
 *   console.error('Validation failed:', error.message);
 * }
 */
const updateCard = async (cardNumber, cardData) => {
  try {
    // Validate card number - check type first
    if (typeof cardNumber !== 'string') {
      throw new Error('Invalid card number format. Card number is required.');
    }
    
    // Trim and check for empty string
    const trimmedCardNumber = cardNumber.trim();
    if (trimmedCardNumber.length === 0) {
      throw new Error('Invalid card number format. Card number cannot be empty.');
    }
    
    // Validate card data object
    if (!cardData || typeof cardData !== 'object') {
      throw new Error('Invalid card data. Update data is required.');
    }
    
    // Validate card status if provided
    // COBOL card status values: 'A'=Active, 'E'=Expired, 'B'=Blocked
    if (cardData.cardStatus) {
      const validStatuses = ['A', 'E', 'B'];
      if (!validStatuses.includes(cardData.cardStatus)) {
        throw new Error(
          `Invalid card status value. Must be 'A' (Active), 'E' (Expired), or 'B' (Blocked).`
        );
      }
    }
    
    // Validate expiry date format if provided
    if (cardData.expiryDate) {
      // Basic date format validation (YYYY-MM-DD)
      const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
      if (!dateRegex.test(cardData.expiryDate)) {
        throw new Error('Invalid expiry date format. Must be YYYY-MM-DD.');
      }
      
      // Validate that it's a valid date (catches invalid months/days like 2028-13-01)
      const expiryDate = new Date(cardData.expiryDate);
      if (isNaN(expiryDate.getTime())) {
        throw new Error('Invalid expiry date format. Must be YYYY-MM-DD.');
      }
      
      // Additional validation: Check if date string matches the parsed date
      // This catches cases like '2028-13-01' which Date constructor might interpret differently
      const [year, month, day] = cardData.expiryDate.split('-').map(Number);
      if (expiryDate.getFullYear() !== year || 
          expiryDate.getMonth() + 1 !== month || 
          expiryDate.getDate() !== day) {
        throw new Error('Invalid expiry date format. Must be YYYY-MM-DD.');
      }
      
      // Validate that expiry date is in the future
      const today = new Date();
      today.setHours(0, 0, 0, 0); // Normalize to start of day
      
      if (expiryDate < today) {
        throw new Error('Invalid expiry date. Expiry date must be in the future.');
      }
    }
    
    // Validate embossed name length if provided
    if (cardData.embossedName && cardData.embossedName.length > 30) {
      throw new Error('Invalid embossed name. Maximum length is 30 characters.');
    }
    
    // Execute PUT request to update card
    // Maps COBOL COCRDUPC.cbl REWRITE CARDDAT operation
    const response = await apiClient.put(`/cards/${trimmedCardNumber}`, cardData);
    
    // Return updated card object
    return response.data;
  } catch (error) {
    // Enhanced error handling with context-specific messages
    if (error.status === 404) {
      throw new Error('Card not found. Unable to update non-existent card.');
    } else if (error.status === 403) {
      throw new Error('You do not have permission to update this card.');
    } else if (error.status === 400) {
      // Validation errors from backend
      const validationMessage = error.message || 'Invalid card data provided.';
      const fieldErrors = error.errors ? 
        Object.entries(error.errors)
          .map(([field, msg]) => `${field}: ${msg}`)
          .join(', ') 
        : '';
      throw new Error(
        fieldErrors ? `${validationMessage} ${fieldErrors}` : validationMessage
      );
    } else if (error.networkError) {
      throw new Error('Network error. Please check your connection and try again.');
    } else {
      throw new Error(error.message || 'Failed to update card. Please try again.');
    }
  }
};

/**
 * Retrieve all cards associated with a specific customer
 * 
 * Maps COBOL cross-reference navigation from customer to cards through accounts.
 * 
 * COBOL Equivalence:
 * - VSAM XREF file navigation (CUSTDAT → ACCTDAT → CARDDAT)
 * - Sequential read of CARDDAT with customer filter
 * - Cross-reference relationship traversal
 * 
 * Business Logic:
 * - Retrieves all cards across all accounts owned by the customer
 * - No pagination (returns complete list)
 * - Sorted by account ID and card number for consistent ordering
 * - Used for customer-level card management views
 * 
 * @param {string} customerId - Customer ID to retrieve cards for (11 digits)
 * @returns {Promise<Array>} Promise resolving to array of card objects
 * @throws {Error} Invalid customer ID, customer not found, permission denied, or server error
 * 
 * @example
 * // Retrieve all cards for a customer
 * const customerCards = await getCardsByCustomer('00000000001');
 * console.log(`Customer has ${customerCards.length} cards`);
 * 
 * @example
 * // Display cards grouped by account
 * const cards = await getCardsByCustomer('00000000001');
 * const cardsByAccount = cards.reduce((acc, card) => {
 *   if (!acc[card.accountId]) acc[card.accountId] = [];
 *   acc[card.accountId].push(card);
 *   return acc;
 * }, {});
 */
const getCardsByCustomer = async (customerId) => {
  try {
    // Validate customer ID - check type first
    if (typeof customerId !== 'string') {
      throw new Error('Invalid customer ID format. Customer ID is required.');
    }
    
    // Trim and check for empty string
    const trimmedCustomerId = customerId.trim();
    if (trimmedCustomerId.length === 0) {
      throw new Error('Invalid customer ID format. Customer ID cannot be empty.');
    }
    
    // Execute GET request with customer ID filter
    // Maps COBOL cross-reference navigation via XREF file
    const response = await apiClient.get('/cards', {
      params: { customerId: trimmedCustomerId }
    });
    
    // Return array of card objects
    // Backend handles cross-reference join (CUSTDAT → ACCTDAT → CARDDAT)
    return response.data.cards || [];
  } catch (error) {
    // Enhanced error handling with context-specific messages
    if (error.status === 404) {
      throw new Error('Customer not found or has no associated cards.');
    } else if (error.status === 403) {
      throw new Error('You do not have permission to view this customer\'s cards.');
    } else if (error.networkError) {
      throw new Error('Network error. Please check your connection and try again.');
    } else {
      throw new Error(error.message || 'Failed to retrieve customer cards. Please try again.');
    }
  }
};

/**
 * Sort array of cards by specified field and order
 * 
 * Client-side sorting utility preserving COBOL sequential file access patterns.
 * 
 * COBOL Equivalence:
 * - Maps COBOL SORT verb and sequential file ordering
 * - Preserves VSAM key-sequenced access patterns
 * - Provides sorting equivalent to COBOL ASCENDING/DESCENDING KEY clauses
 * 
 * Business Logic:
 * - Supports sorting by: cardNumber, accountId, cardStatus, expiryDate
 * - Default sort order: ascending
 * - Returns new sorted array (does not mutate input)
 * - Handles null/undefined values gracefully
 * - Maintains stable sort (preserves relative order of equal elements)
 * 
 * Supported Sort Fields:
 * - cardNumber: Alphabetic sort of 16-digit card number
 * - accountId: Numeric sort of 11-digit account ID
 * - cardStatus: Alphabetic sort ('A', 'B', 'E')
 * - expiryDate: Date sort (chronological order)
 * 
 * @param {Array<Object>} cards - Array of card objects to sort
 * @param {string} sortBy - Field name to sort by (default: 'cardNumber')
 * @param {string} sortOrder - Sort direction: 'asc' or 'desc' (default: 'asc')
 * @returns {Array<Object>} New sorted array of card objects
 * 
 * @example
 * // Sort cards by card number (ascending)
 * const sorted = sortCards(cards, 'cardNumber', 'asc');
 * 
 * @example
 * // Sort cards by expiry date (descending, soonest to expire first)
 * const sorted = sortCards(cards, 'expiryDate', 'desc');
 * 
 * @example
 * // Sort cards by account ID
 * const sorted = sortCards(cards, 'accountId', 'asc');
 * 
 * @example
 * // Sort cards by status
 * const sorted = sortCards(cards, 'cardStatus', 'asc');
 */
const sortCards = (cards, sortBy = 'cardNumber', sortOrder = 'asc') => {
  // Validate input array
  if (!Array.isArray(cards)) {
    console.error('sortCards: Invalid input - cards must be an array');
    return [];
  }
  
  // Return empty array if no cards to sort
  if (cards.length === 0) {
    return [];
  }
  
  // Create shallow copy to avoid mutating input array
  const sortedCards = [...cards];
  
  // Determine sort direction multiplier
  const direction = sortOrder === 'desc' ? -1 : 1;
  
  // Sort array based on specified field
  sortedCards.sort((a, b) => {
    let aValue = a[sortBy];
    let bValue = b[sortBy];
    
    // Handle null/undefined values (sort to end)
    if (aValue == null && bValue == null) return 0;
    if (aValue == null) return 1;
    if (bValue == null) return -1;
    
    // Date comparison for expiryDate field
    if (sortBy === 'expiryDate') {
      aValue = new Date(aValue);
      bValue = new Date(bValue);
      return (aValue - bValue) * direction;
    }
    
    // Numeric comparison for accountId
    if (sortBy === 'accountId') {
      // Convert to numbers for proper numeric sorting
      const aNum = parseInt(aValue, 10);
      const bNum = parseInt(bValue, 10);
      return (aNum - bNum) * direction;
    }
    
    // String comparison for cardNumber, cardStatus, and other fields
    // Case-insensitive comparison
    const aStr = String(aValue).toLowerCase();
    const bStr = String(bValue).toLowerCase();
    
    if (aStr < bStr) return -1 * direction;
    if (aStr > bStr) return 1 * direction;
    return 0;
  });
  
  return sortedCards;
};

/**
 * Card Service Export
 * 
 * Exports all card management operations as a unified service interface.
 * 
 * Exported Methods:
 * - getCards: Retrieve paginated card list with filtering
 * - getCard: Retrieve single card detail by card number
 * - updateCard: Update card information and status
 * - getCardsByCustomer: Retrieve all cards for a customer
 * - sortCards: Client-side card array sorting utility
 * 
 * Usage Pattern:
 * Card components (CardListComponent, CardSelectComponent, CardUpdateComponent)
 * import this service and use the exported methods to interact with the backend
 * card management API, ensuring consistent business logic, error handling, and
 * data transformation across the card management workflow.
 * 
 * COBOL Program Mapping:
 * - COCRDLIC.cbl → getCards()
 * - COCRDSLC.cbl → getCard()
 * - COCRDUPC.cbl → updateCard()
 * 
 * Business Rules Preserved:
 * - 7 cards per page pagination pattern from COBOL screen layout
 * - Role-based access control (admin vs regular user card visibility)
 * - Card status validation ('A', 'E', 'B')
 * - VSAM cross-reference navigation patterns
 * - Transaction atomicity for updates
 */
export default {
  getCards,
  getCard,
  updateCard,
  getCardsByCustomer,
  sortCards
};

// Named exports for convenience
export {
  getCards,
  getCard,
  updateCard,
  getCardsByCustomer,
  sortCards
};
