/**
 * Card Service Module
 * 
 * Purpose: Provides card management operations communicating with Spring Boot backend REST API.
 * This module replaces COBOL programs COCRDLIC.cbl (card list), COCRDSLC.cbl (card detail),
 * and COCRDUPC.cbl (card update) including VSAM CARDDAT file operations.
 * 
 * Source COBOL Programs:
 * - COCRDLIC.cbl: Card list with pagination (CCLI transaction, 7-row screen layout)
 * - COCRDSLC.cbl: Card detail view (CCDL transaction, single card display)
 * - COCRDUPC.cbl: Card update (CCUP transaction, card modification)
 * 
 * Source Data Structures:
 * - CVACT02Y.cpy: Card master record layout (CARD-RECORD, 150 bytes)
 * - CVACT03Y.cpy: Card-Account-Customer cross-reference relationships
 * 
 * API Endpoints:
 * - GET /api/cards: List cards with pagination (7 per page)
 * - GET /api/cards/{cardNumber}: Retrieve single card details
 * - PUT /api/cards/{cardNumber}: Update card information
 * - GET /api/cards?accountId={id}: List cards for specific account
 * 
 * Key Features:
 * - Pagination matching COBOL WS-MAX-SCREEN-LINES (7 cards per page)
 * - Card number masking for security (display last 4 digits only)
 * - Expiration date validation (MM/YYYY format)
 * - Card status management (active, blocked, expired)
 * - Cross-reference data handling for card-account relationships
 * 
 * @module cardService
 */

import apiClient from '../utils/apiClient.js';
import { CARDS_PER_PAGE } from '../utils/constants.js';

/**
 * Masks a card number for secure display
 * Displays only the last 4 digits, replaces the rest with asterisks
 * 
 * COBOL Equivalent: Card number masking logic for screen display
 * Pattern: ****-****-****-1234
 * 
 * @param {string} cardNumber - Full 16-digit card number
 * @returns {string} Masked card number (e.g., "****-****-****-1234")
 * @throws {Error} If cardNumber is invalid or not 16 digits
 * 
 * @example
 * maskCardNumber('4111111111111234') // Returns "****-****-****-1234"
 */
export const maskCardNumber = (cardNumber) => {
  // Validate input
  if (!cardNumber) {
    throw new Error('Card number is required for masking');
  }

  // Remove any spaces or hyphens from input
  const cleanedNumber = cardNumber.replace(/[\s-]/g, '');

  // Validate 16-digit card number
  if (cleanedNumber.length !== 16) {
    throw new Error('Card number must be exactly 16 digits');
  }

  // Validate numeric
  if (!/^\d{16}$/.test(cleanedNumber)) {
    throw new Error('Card number must contain only digits');
  }

  // Extract last 4 digits
  const lastFour = cleanedNumber.slice(-4);

  // Return masked format: ****-****-****-1234
  return `****-****-****-${lastFour}`;
};

/**
 * Validates expiration date format and ensures it's in the future
 * 
 * @param {string} expirationDate - Date in MM/YYYY or YYYY-MM-DD format
 * @returns {boolean} True if valid and in future, false otherwise
 * 
 * @private
 */
const validateExpirationDate = (expirationDate) => {
  if (!expirationDate) {
    return false;
  }

  let month, year;

  // Handle YYYY-MM-DD format from backend
  if (expirationDate.includes('-') && expirationDate.length === 10) {
    const parts = expirationDate.split('-');
    year = parseInt(parts[0], 10);
    month = parseInt(parts[1], 10);
  } 
  // Handle MM/YYYY format
  else if (expirationDate.includes('/')) {
    const parts = expirationDate.split('/');
    month = parseInt(parts[0], 10);
    year = parseInt(parts[1], 10);
  } 
  else {
    return false;
  }

  // Validate month range
  if (month < 1 || month > 12) {
    return false;
  }

  // Validate year is reasonable (2000-2099)
  if (year < 2000 || year > 2099) {
    return false;
  }

  // Check if expiration is in the future
  const currentDate = new Date();
  const currentYear = currentDate.getFullYear();
  const currentMonth = currentDate.getMonth() + 1; // getMonth() is 0-indexed

  // Card expires at end of expiration month, so equal month/year is still valid
  if (year < currentYear || (year === currentYear && month < currentMonth)) {
    return false;
  }

  return true;
};

/**
 * Formats expiration date to MM/YYYY format for display
 * 
 * @param {string} expirationDate - Date in YYYY-MM-DD format from backend
 * @returns {string} Formatted date in MM/YYYY format
 * 
 * @private
 */
const formatExpirationDate = (expirationDate) => {
  if (!expirationDate) {
    return '';
  }

  // If already in MM/YYYY format, return as is
  if (expirationDate.match(/^\d{2}\/\d{4}$/)) {
    return expirationDate;
  }

  // Convert from YYYY-MM-DD to MM/YYYY
  if (expirationDate.includes('-') && expirationDate.length === 10) {
    const parts = expirationDate.split('-');
    const year = parts[0];
    const month = parts[1];
    return `${month}/${year}`;
  }

  return expirationDate;
};

/**
 * Retrieves a paginated list of credit cards
 * 
 * COBOL Equivalent: COCRDLIC.cbl PROCEDURE DIVISION
 * - Replaces EXEC CICS STARTBR DATASET(CARDAIX) browse operations
 * - Replaces EXEC CICS READNEXT DATASET(CARDAIX) sequential access
 * - Implements WS-MAX-SCREEN-LINES (7 cards per page) pagination
 * 
 * REST API: GET /api/cards?page={page}&size={pageSize}
 * 
 * @param {number} page - Page number (1-indexed, default: 1)
 * @param {number} pageSize - Number of cards per page (default: 7)
 * @returns {Promise<Object>} Object containing:
 *   - cards: Array of card objects with masked card numbers
 *   - pagination: Object with totalPages, currentPage, totalCards, hasNext, hasPrevious
 * 
 * @throws {Error} If API request fails or returns invalid data
 * 
 * @example
 * const result = await getCards(1, 7);
 * // Returns:
 * // {
 * //   cards: [
 * //     {
 * //       cardNumber: "****-****-****-1234",
 * //       accountId: 12345678901,
 * //       cardType: "VISA",
 * //       expirationDate: "12/2025",
 * //       status: "Active",
 * //       cardholderName: "JOHN DOE"
 * //     },
 * //     ...
 * //   ],
 * //   pagination: {
 * //     totalPages: 5,
 * //     currentPage: 1,
 * //     totalCards: 35,
 * //     hasNext: true,
 * //     hasPrevious: false
 * //   }
 * // }
 */
export const getCards = async (page = 1, pageSize = CARDS_PER_PAGE) => {
  try {
    // Validate pagination parameters
    if (page < 1) {
      throw new Error('Page number must be 1 or greater');
    }

    if (pageSize < 1 || pageSize > 100) {
      throw new Error('Page size must be between 1 and 100');
    }

    // Spring Boot pagination is 0-indexed, so subtract 1 from page
    const springBootPage = page - 1;

    // Make API request
    const response = await apiClient.get('/cards', {
      params: {
        page: springBootPage,
        size: pageSize
      }
    });

    // Extract pagination data from Spring Boot PagedModel response
    const { content, totalPages, totalElements, last, first } = response.data;

    // Transform card data: mask card numbers in list view for security
    const cards = content.map(card => ({
      cardNumber: maskCardNumber(card.cardNumber), // Masked for list display
      fullCardNumber: card.cardNumber, // Keep full number for internal use
      accountId: card.accountId,
      customerId: card.customerId,
      cardType: card.cardType,
      expirationDate: formatExpirationDate(card.expirationDate),
      status: card.status,
      cardholderName: card.cardholderName || card.embossedName,
      cvv: card.cvv, // Note: CVV should typically not be returned from backend
      activeStatus: card.activeStatus
    }));

    // Build pagination metadata matching COBOL pagination flags
    const pagination = {
      totalPages: totalPages,
      currentPage: page, // Return 1-indexed page to caller
      totalCards: totalElements,
      hasNext: !last, // CA-NEXT-PAGE-EXISTS equivalent
      hasPrevious: !first, // Previous page available flag
      pageSize: pageSize
    };

    return {
      cards,
      pagination
    };

  } catch (error) {
    // Enhanced error handling with specific messages
    if (error.response) {
      // Server responded with error status
      const status = error.response.status;
      const message = error.response.data?.message || 'Failed to retrieve cards';

      if (status === 404) {
        // No cards found - return empty result instead of throwing
        return {
          cards: [],
          pagination: {
            totalPages: 0,
            currentPage: page,
            totalCards: 0,
            hasNext: false,
            hasPrevious: false,
            pageSize: pageSize
          }
        };
      } else if (status === 401) {
        throw new Error('Authentication required. Please log in.');
      } else if (status === 403) {
        throw new Error('You do not have permission to view cards.');
      } else {
        throw new Error(`Failed to retrieve cards: ${message}`);
      }
    } else if (error.request) {
      // Request made but no response received
      throw new Error('No response from server. Please check your connection.');
    } else {
      // Error in request setup or validation error
      throw new Error(error.message || 'Failed to retrieve cards');
    }
  }
};

/**
 * Retrieves detailed information for a specific credit card
 * 
 * COBOL Equivalent: COCRDSLC.cbl PROCEDURE DIVISION
 * - Replaces EXEC CICS READ DATASET(CARDDAT) INTO(CARD-RECORD) RIDFLD(WS-CARD-RID)
 * - Returns full card details including complete unmasked card number for detail view
 * 
 * REST API: GET /api/cards/{cardNumber}
 * 
 * @param {string} cardNumber - 16-digit card number
 * @returns {Promise<Object>} Card object with complete details:
 *   - cardNumber: Full unmasked 16-digit card number
 *   - accountId: Associated account ID
 *   - customerId: Associated customer ID
 *   - cardType: Card type (VISA, MASTERCARD, etc.)
 *   - expirationDate: Expiration date (MM/YYYY)
 *   - status: Card status (Active, Blocked, Expired)
 *   - cardholderName: Name embossed on card
 *   - cvv: Card verification value
 *   - activeStatus: Active status flag
 * 
 * @throws {Error} If card not found or API request fails
 * 
 * @example
 * const card = await getCardByNumber('4111111111111234');
 * // Returns:
 * // {
 * //   cardNumber: "4111111111111234",
 * //   accountId: 12345678901,
 * //   cardType: "VISA",
 * //   expirationDate: "12/2025",
 * //   status: "Active",
 * //   cardholderName: "JOHN DOE"
 * // }
 */
export const getCardByNumber = async (cardNumber) => {
  try {
    // Validate card number
    if (!cardNumber) {
      throw new Error('Card number is required');
    }

    // Clean card number (remove spaces and hyphens)
    const cleanedNumber = cardNumber.replace(/[\s-*]/g, '');

    // Validate 16-digit format
    if (cleanedNumber.length !== 16) {
      throw new Error('Card number must be exactly 16 digits');
    }

    if (!/^\d{16}$/.test(cleanedNumber)) {
      throw new Error('Card number must contain only digits');
    }

    // Make API request
    const response = await apiClient.get(`/cards/${cleanedNumber}`);

    const card = response.data;

    // Return complete card details with unmasked card number for detail view
    return {
      cardNumber: card.cardNumber, // Full unmasked number for detail view
      accountId: card.accountId,
      customerId: card.customerId,
      cardType: card.cardType,
      expirationDate: formatExpirationDate(card.expirationDate),
      status: card.status,
      cardholderName: card.cardholderName || card.embossedName,
      cvv: card.cvv,
      activeStatus: card.activeStatus
    };

  } catch (error) {
    // Enhanced error handling
    if (error.response) {
      const status = error.response.status;
      const message = error.response.data?.message || 'Failed to retrieve card';

      if (status === 404) {
        throw new Error('Card not found. Please verify the card number.');
      } else if (status === 401) {
        throw new Error('Authentication required. Please log in.');
      } else if (status === 403) {
        throw new Error('You do not have permission to view this card.');
      } else {
        throw new Error(`Failed to retrieve card: ${message}`);
      }
    } else if (error.request) {
      throw new Error('No response from server. Please check your connection.');
    } else {
      throw new Error(error.message || 'Failed to retrieve card');
    }
  }
};

/**
 * Updates credit card information
 * 
 * COBOL Equivalent: COCRDUPC.cbl PROCEDURE DIVISION
 * - Replaces EXEC CICS REWRITE DATASET(CARDDAT) FROM(CARD-RECORD)
 * - Validates expiration date before update
 * - Supports status updates (active, blocked, expired)
 * 
 * REST API: PUT /api/cards/{cardNumber}
 * 
 * @param {string} cardNumber - 16-digit card number to update
 * @param {Object} cardData - Card data to update:
 *   - expirationDate: Expiration date (MM/YYYY format)
 *   - status: Card status
 *   - cardholderName: Name embossed on card
 *   - activeStatus: Active status flag
 * @returns {Promise<Object>} Updated card object
 * 
 * @throws {Error} If validation fails or API request fails
 * 
 * @example
 * const updatedCard = await updateCard('4111111111111234', {
 *   expirationDate: '12/2026',
 *   status: 'Active',
 *   cardholderName: 'JOHN DOE'
 * });
 */
export const updateCard = async (cardNumber, cardData) => {
  try {
    // Validate card number
    if (!cardNumber) {
      throw new Error('Card number is required');
    }

    // Clean card number
    const cleanedNumber = cardNumber.replace(/[\s-*]/g, '');

    // Validate 16-digit format
    if (cleanedNumber.length !== 16) {
      throw new Error('Card number must be exactly 16 digits');
    }

    if (!/^\d{16}$/.test(cleanedNumber)) {
      throw new Error('Card number must contain only digits');
    }

    // Validate card data
    if (!cardData || typeof cardData !== 'object') {
      throw new Error('Card data is required and must be an object');
    }

    // Validate expiration date if provided
    if (cardData.expirationDate) {
      if (!validateExpirationDate(cardData.expirationDate)) {
        throw new Error('Invalid expiration date. Must be in MM/YYYY format and in the future.');
      }
    }

    // Validate status if provided
    if (cardData.status) {
      const validStatuses = ['Active', 'Blocked', 'Expired', 'Pending'];
      if (!validStatuses.includes(cardData.status)) {
        throw new Error(`Invalid card status. Must be one of: ${validStatuses.join(', ')}`);
      }
    }

    // Validate cardholder name if provided
    if (cardData.cardholderName) {
      if (cardData.cardholderName.length > 50) {
        throw new Error('Cardholder name cannot exceed 50 characters');
      }
      // Validate alphanumeric and spaces only
      if (!/^[A-Za-z\s]+$/.test(cardData.cardholderName)) {
        throw new Error('Cardholder name must contain only letters and spaces');
      }
    }

    // Build update payload
    const updatePayload = {
      ...cardData
    };

    // Convert MM/YYYY to YYYY-MM-DD for backend if provided
    if (updatePayload.expirationDate && updatePayload.expirationDate.includes('/')) {
      const parts = updatePayload.expirationDate.split('/');
      const month = parts[0];
      const year = parts[1];
      // Set to last day of month
      const lastDay = new Date(parseInt(year), parseInt(month), 0).getDate();
      updatePayload.expirationDate = `${year}-${month}-${lastDay.toString().padStart(2, '0')}`;
    }

    // Make API request
    const response = await apiClient.put(`/cards/${cleanedNumber}`, updatePayload);

    const updatedCard = response.data;

    // Return updated card object
    return {
      cardNumber: updatedCard.cardNumber,
      accountId: updatedCard.accountId,
      customerId: updatedCard.customerId,
      cardType: updatedCard.cardType,
      expirationDate: formatExpirationDate(updatedCard.expirationDate),
      status: updatedCard.status,
      cardholderName: updatedCard.cardholderName || updatedCard.embossedName,
      cvv: updatedCard.cvv,
      activeStatus: updatedCard.activeStatus
    };

  } catch (error) {
    // Enhanced error handling
    if (error.response) {
      const status = error.response.status;
      const message = error.response.data?.message || 'Failed to update card';

      if (status === 404) {
        throw new Error('Card not found. Cannot update non-existent card.');
      } else if (status === 400) {
        throw new Error(`Validation error: ${message}`);
      } else if (status === 401) {
        throw new Error('Authentication required. Please log in.');
      } else if (status === 403) {
        throw new Error('You do not have permission to update this card.');
      } else if (status === 409) {
        throw new Error('Conflict: Card data has been modified by another user. Please refresh and try again.');
      } else {
        throw new Error(`Failed to update card: ${message}`);
      }
    } else if (error.request) {
      throw new Error('No response from server. Please check your connection.');
    } else {
      throw new Error(error.message || 'Failed to update card');
    }
  }
};

/**
 * Retrieves all credit cards associated with a specific account
 * 
 * COBOL Equivalent: COCRDLIC.cbl with COMMAREA account context
 * - Replaces account-filtered card browsing using CARDAIX alternate index
 * - Returns cards for specific account ID
 * 
 * REST API: GET /api/cards?accountId={accountId}
 * 
 * @param {number} accountId - 11-digit account ID
 * @returns {Promise<Object>} Object containing:
 *   - cards: Array of card objects for the account
 *   - accountId: The requested account ID
 * 
 * @throws {Error} If API request fails
 * 
 * @example
 * const result = await getCardsByAccount(12345678901);
 * // Returns:
 * // {
 * //   cards: [...],
 * //   accountId: 12345678901
 * // }
 */
export const getCardsByAccount = async (accountId) => {
  try {
    // Validate account ID
    if (!accountId) {
      throw new Error('Account ID is required');
    }

    // Validate 11-digit account ID
    const accountIdStr = accountId.toString();
    if (accountIdStr.length !== 11) {
      throw new Error('Account ID must be exactly 11 digits');
    }

    if (!/^\d{11}$/.test(accountIdStr)) {
      throw new Error('Account ID must contain only digits');
    }

    // Make API request with accountId filter
    const response = await apiClient.get('/cards', {
      params: {
        accountId: accountId
      }
    });

    // Handle both paginated and non-paginated responses
    let cards = [];
    if (Array.isArray(response.data)) {
      // Direct array response
      cards = response.data;
    } else if (response.data.content) {
      // Paginated response
      cards = response.data.content;
    }

    // Transform card data with masked card numbers
    const transformedCards = cards.map(card => ({
      cardNumber: maskCardNumber(card.cardNumber),
      fullCardNumber: card.cardNumber,
      accountId: card.accountId,
      customerId: card.customerId,
      cardType: card.cardType,
      expirationDate: formatExpirationDate(card.expirationDate),
      status: card.status,
      cardholderName: card.cardholderName || card.embossedName,
      cvv: card.cvv,
      activeStatus: card.activeStatus
    }));

    return {
      cards: transformedCards,
      accountId: accountId
    };

  } catch (error) {
    // Enhanced error handling
    if (error.response) {
      const status = error.response.status;
      const message = error.response.data?.message || 'Failed to retrieve cards';

      if (status === 404) {
        // No cards found for account - return empty result
        return {
          cards: [],
          accountId: accountId
        };
      } else if (status === 401) {
        throw new Error('Authentication required. Please log in.');
      } else if (status === 403) {
        throw new Error('You do not have permission to view cards for this account.');
      } else {
        throw new Error(`Failed to retrieve cards for account: ${message}`);
      }
    } else if (error.request) {
      throw new Error('No response from server. Please check your connection.');
    } else {
      throw new Error(error.message || 'Failed to retrieve cards for account');
    }
  }
};

/**
 * Searches for credit cards based on flexible criteria
 * 
 * COBOL Equivalent: COCRDLIC.cbl with multiple filter conditions
 * - Supports partial card number matching
 * - Filters by account ID, status, card type
 * - Combines multiple search criteria
 * 
 * REST API: GET /api/cards?{criteria}
 * 
 * @param {Object} criteria - Search criteria object:
 *   - cardNumber: Partial or full card number (last 4 digits typical)
 *   - accountId: Account ID filter
 *   - status: Card status filter (Active, Blocked, Expired)
 *   - cardType: Card type filter (VISA, MASTERCARD, etc.)
 *   - customerId: Customer ID filter
 * @returns {Promise<Object>} Object containing:
 *   - cards: Array of matching card objects
 *   - criteria: The search criteria used
 *   - count: Number of cards found
 * 
 * @throws {Error} If API request fails
 * 
 * @example
 * const result = await searchCards({
 *   status: 'Active',
 *   cardType: 'VISA'
 * });
 */
export const searchCards = async (criteria = {}) => {
  try {
    // Validate criteria object
    if (!criteria || typeof criteria !== 'object') {
      throw new Error('Search criteria must be an object');
    }

    // Build query parameters from criteria
    const params = {};

    if (criteria.cardNumber) {
      // Clean card number
      const cleanedNumber = criteria.cardNumber.replace(/[\s-*]/g, '');
      params.cardNumber = cleanedNumber;
    }

    if (criteria.accountId) {
      params.accountId = criteria.accountId;
    }

    if (criteria.status) {
      params.status = criteria.status;
    }

    if (criteria.cardType) {
      params.cardType = criteria.cardType;
    }

    if (criteria.customerId) {
      params.customerId = criteria.customerId;
    }

    // Add pagination parameters from criteria, with defaults
    // Spring Boot pagination is 0-indexed
    const page = criteria.page !== undefined ? criteria.page : 1;
    const size = criteria.size || CARDS_PER_PAGE;
    
    // Convert to Spring Boot 0-indexed page if needed
    const springBootPage = page >= 1 ? page - 1 : page;
    
    params.page = springBootPage;
    params.size = size;

    // Make API request
    const response = await apiClient.get('/cards', { params });

    // Extract pagination data from Spring Boot PagedModel response
    const { content, totalPages, totalElements, last, first, number } = response.data;

    // Transform card data with masked card numbers
    const transformedCards = content.map(card => ({
      cardNumber: maskCardNumber(card.cardNumber),
      fullCardNumber: card.cardNumber,
      accountId: card.accountId,
      customerId: card.customerId,
      cardType: card.cardType,
      expirationDate: formatExpirationDate(card.expirationDate),
      status: card.status,
      cardholderName: card.cardholderName || card.embossedName,
      cvv: card.cvv,
      activeStatus: card.activeStatus
    }));

    // Build pagination metadata matching getCards pattern
    const pagination = {
      totalPages: totalPages || 0,
      currentPage: page, // Return 1-indexed page to caller
      totalCards: totalElements || 0,
      hasNext: !last,
      hasPrevious: !first,
      pageSize: size
    };

    return {
      cards: transformedCards,
      pagination,
      criteria: criteria
    };

  } catch (error) {
    // Enhanced error handling
    if (error.response) {
      const status = error.response.status;
      const message = error.response.data?.message || 'Failed to search cards';
      
      // Get page and size for error responses
      const page = criteria.page !== undefined ? criteria.page : 1;
      const size = criteria.size || CARDS_PER_PAGE;

      if (status === 404 || status === 204) {
        // No cards found - return empty result with pagination
        return {
          cards: [],
          pagination: {
            totalPages: 0,
            currentPage: page,
            totalCards: 0,
            hasNext: false,
            hasPrevious: false,
            pageSize: size
          },
          criteria: criteria
        };
      } else if (status === 400) {
        throw new Error(`Invalid search criteria: ${message}`);
      } else if (status === 401) {
        throw new Error('Authentication required. Please log in.');
      } else if (status === 403) {
        throw new Error('You do not have permission to search cards.');
      } else {
        throw new Error(`Failed to search cards: ${message}`);
      }
    } else if (error.request) {
      throw new Error('No response from server. Please check your connection.');
    } else {
      throw new Error(error.message || 'Failed to search cards');
    }
  }
};

/**
 * Card Service Object
 * 
 * Exported as default for convenient import:
 * import cardService from './services/cardService.js';
 * 
 * Also provides named exports for individual functions:
 * import { getCards, getCardByNumber } from './services/cardService.js';
 */
const cardService = {
  getCards,
  getCardByNumber,
  updateCard,
  getCardsByAccount,
  searchCards,
  maskCardNumber
};

export default cardService;
