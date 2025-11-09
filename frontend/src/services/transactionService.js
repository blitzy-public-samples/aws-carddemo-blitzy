/**
 * Transaction Service Module
 * 
 * Purpose: Provides transaction management operations communicating with Spring Boot backend REST API.
 * Replaces COBOL programs COTRN00C.cbl (CT00 transaction list), COTRN01C.cbl (CT01 transaction detail),
 * and COTRN02C.cbl (CT02 transaction add) with stateless REST API client methods.
 * 
 * Source COBOL Programs:
 * - COTRN00C.cbl: Transaction list with pagination (10 transactions per page) via VSAM browse
 * - COTRN01C.cbl: Transaction detail view via VSAM READ DATASET(TRANSACT)
 * - COTRN02C.cbl: Transaction add with validation via VSAM WRITE DATASET(TRANSACT)
 * 
 * Source BMS Mapsets:
 * - COTRN00.bms: Transaction list screen (10-row pagination: SEL0001-SEL0010)
 * - COTRN01.bms: Transaction detail screen
 * - COTRN02.bms: Transaction add screen
 * 
 * Source Copybooks:
 * - CVTRA05Y.cpy: Transaction record structure (TRAN-RECORD, 350 bytes)
 * - CVTRA06Y.cpy: Daily transaction input structure
 * 
 * REST API Endpoints:
 * - GET /api/transactions: List transactions with pagination and filtering
 * - GET /api/transactions/{id}: Retrieve single transaction detail
 * - POST /api/transactions: Create new transaction with validation
 * 
 * Business Logic Preservation:
 * - Transaction amount validation with BigDecimal precision (2 decimal places)
 * - Card authorization validation ensuring valid card number and sufficient credit
 * - Date range filtering for transaction history queries
 * - Merchant information handling and transaction description support
 * - Transaction status tracking (pending, posted, declined)
 * 
 * Critical Requirements from Agent Action Plan (Section 0.10):
 * - Maintain pagination pattern: 10 transactions per page matching BMS screen layout
 * - Preserve COBOL COMP-3 decimal precision using 2 decimal places for amounts
 * - Handle date format conversion from COBOL YYYY-MM-DD to ISO 8601
 * - Implement comprehensive error handling with try-catch blocks
 * - Format amounts with proper thousands separators and 2 decimal places
 * 
 * @module transactionService
 */

import apiClient from '../utils/apiClient.js';

/**
 * Retrieve paginated list of transactions with optional filtering
 * 
 * Replaces COBOL: EXEC CICS STARTBR/READNEXT DATASET(TRANSACT)
 * Maps to: GET /api/transactions
 * 
 * Business Logic from COTRN00C.cbl:
 * - Pagination with 10 transactions per page (WS-REC-COUNT, lines 52)
 * - Transaction browsing with first/last transaction ID tracking (CDEMO-CT00-TRNID-FIRST/LAST)
 * - Page number management (CDEMO-CT00-PAGE-NUM)
 * - Next page flag handling (CDEMO-CT00-NEXT-PAGE-FLG)
 * 
 * @param {Object} filters - Optional filter criteria
 * @param {string} filters.cardNumber - Filter by 16-digit card number
 * @param {string} filters.startDate - Filter by start date (YYYY-MM-DD format)
 * @param {string} filters.endDate - Filter by end date (YYYY-MM-DD format)
 * @param {string} filters.transactionType - Filter by transaction type code (2 characters)
 * @param {string} filters.status - Filter by transaction status (pending, posted, declined)
 * @param {string} filters.merchantName - Filter by merchant name (partial match)
 * @param {number} page - Page number (1-based index, default 1)
 * @param {number} pageSize - Number of transactions per page (default 10 matching BMS screen)
 * 
 * @returns {Promise<Object>} Transaction list response with pagination metadata
 * @returns {Array} response.transactions - Array of transaction objects
 * @returns {string} response.transactions[].transactionId - 16-character transaction ID (TRAN-ID PIC X(16))
 * @returns {string} response.transactions[].cardNumber - 16-digit card number (TRAN-CARD-NUM PIC X(16))
 * @returns {string} response.transactions[].transactionDate - Transaction date in display format
 * @returns {string} response.transactions[].amount - Formatted amount with 2 decimals (TRAN-AMT PIC S9(9)V99)
 * @returns {string} response.transactions[].merchantName - Merchant name (TRAN-MERCHANT-NAME PIC X(50))
 * @returns {string} response.transactions[].merchantCity - Merchant city (TRAN-MERCHANT-CITY PIC X(50))
 * @returns {string} response.transactions[].merchantCategory - Merchant category code (TRAN-CAT-CD PIC 9(04))
 * @returns {string} response.transactions[].transactionType - Transaction type code (TRAN-TYPE-CD PIC X(02))
 * @returns {string} response.transactions[].status - Transaction status
 * @returns {string} response.transactions[].description - Transaction description (TRAN-DESC PIC X(100))
 * @returns {Object} response.pagination - Pagination metadata
 * @returns {number} response.pagination.totalPages - Total number of pages
 * @returns {number} response.pagination.currentPage - Current page number
 * @returns {number} response.pagination.totalTransactions - Total count of transactions
 * @returns {number} response.pagination.pageSize - Transactions per page
 * 
 * @throws {Error} If API request fails or network error occurs
 * 
 * @example
 * const result = await getTransactions({ cardNumber: '4000123456789010' }, 1, 10);
 * console.log(result.transactions); // Array of 10 transaction objects
 * console.log(result.pagination.totalPages); // Total pages available
 */
const getTransactions = async (filters = {}, page = 1, pageSize = 10) => {
  try {
    // Validate page number (must be positive integer)
    if (page < 1) {
      throw new Error('Page number must be greater than or equal to 1');
    }

    // Validate page size (must be positive integer, max 100)
    if (pageSize < 1 || pageSize > 100) {
      throw new Error('Page size must be between 1 and 100');
    }

    // Build query parameters for API call
    // Spring Boot uses 0-based page index, convert from 1-based
    const params = {
      page: page - 1, // Convert to 0-based index for Spring Boot
      size: pageSize,
    };

    // Add filter parameters if provided
    if (filters.cardNumber) {
      // Validate card number format: 16 digits
      if (!/^\d{16}$/.test(filters.cardNumber)) {
        throw new Error('Card number must be exactly 16 digits');
      }
      params.cardNumber = filters.cardNumber;
    }

    if (filters.startDate) {
      // Validate date format: YYYY-MM-DD
      if (!/^\d{4}-\d{2}-\d{2}$/.test(filters.startDate)) {
        throw new Error('Start date must be in YYYY-MM-DD format');
      }
      params.startDate = filters.startDate;
    }

    if (filters.endDate) {
      // Validate date format: YYYY-MM-DD
      if (!/^\d{4}-\d{2}-\d{2}$/.test(filters.endDate)) {
        throw new Error('End date must be in YYYY-MM-DD format');
      }
      params.endDate = filters.endDate;
    }

    if (filters.transactionType) {
      // Transaction type code: 2 characters (TRAN-TYPE-CD PIC X(02))
      params.transactionType = filters.transactionType;
    }

    if (filters.status) {
      // Transaction status: pending, posted, declined
      params.status = filters.status;
    }

    if (filters.merchantName) {
      // Merchant name for partial match searching
      params.merchantName = filters.merchantName;
    }

    // Execute GET request to /api/transactions
    const response = await apiClient.get('/transactions', { params });

    // Extract pagination metadata from Spring Boot response
    const { content, totalPages, totalElements, number, size } = response.data;

    // Transform response to match expected format
    // Spring Boot returns 'content' array and pagination metadata
    return {
      transactions: content.map((transaction) => ({
        transactionId: transaction.transactionId,
        cardNumber: transaction.cardNumber,
        transactionDate: formatDate(transaction.transactionDate),
        amount: formatAmount(transaction.amount),
        merchantName: transaction.merchantName || '',
        merchantCity: transaction.merchantCity || '',
        merchantCategory: transaction.merchantCategory || '',
        transactionType: transaction.transactionType || '',
        status: transaction.status || 'posted',
        description: transaction.description || '',
      })),
      pagination: {
        totalPages: totalPages,
        currentPage: number + 1, // Convert back to 1-based index
        totalTransactions: totalElements,
        pageSize: size,
      },
    };
  } catch (error) {
    // Enhanced error handling with context
    console.error('[Transaction Service] Error fetching transactions:', error);

    // Re-throw error with additional context if it's an API error
    if (error.status) {
      throw new Error(
        `Failed to retrieve transactions: ${error.message || 'Server error'}`
      );
    }

    // For validation errors or other errors, re-throw as-is
    throw error;
  }
};

/**
 * Retrieve detailed information for a specific transaction
 * 
 * Replaces COBOL: EXEC CICS READ DATASET(TRANSACT) INTO(TRAN-RECORD)
 * Maps to: GET /api/transactions/{transactionId}
 * 
 * Business Logic from COTRN01C.cbl:
 * - Single transaction READ with KEY IS TRAN-ID
 * - Full transaction record retrieval (all fields from CVTRA05Y.cpy)
 * - Timestamp handling (TRAN-ORIG-TS, TRAN-PROC-TS)
 * - Amount formatting (WS-TRAN-AMT PIC +99999999.99)
 * 
 * @param {string} transactionId - 16-character transaction identifier (TRAN-ID PIC X(16))
 * 
 * @returns {Promise<Object>} Complete transaction object with all details
 * @returns {string} response.transactionId - 16-character transaction ID
 * @returns {string} response.transactionTypeCode - 2-character transaction type (TRAN-TYPE-CD PIC X(02))
 * @returns {string} response.transactionCategoryCode - 4-digit category code (TRAN-CAT-CD PIC 9(04))
 * @returns {string} response.transactionSource - 10-character source (TRAN-SOURCE PIC X(10))
 * @returns {string} response.description - 100-character description (TRAN-DESC PIC X(100))
 * @returns {string} response.amount - Formatted amount (TRAN-AMT PIC S9(9)V99)
 * @returns {string} response.merchantId - 9-digit merchant ID (TRAN-MERCHANT-ID PIC 9(09))
 * @returns {string} response.merchantName - 50-character merchant name (TRAN-MERCHANT-NAME PIC X(50))
 * @returns {string} response.merchantCity - 50-character merchant city (TRAN-MERCHANT-CITY PIC X(50))
 * @returns {string} response.merchantZip - 10-character merchant ZIP (TRAN-MERCHANT-ZIP PIC X(10))
 * @returns {string} response.cardNumber - 16-character card number (TRAN-CARD-NUM PIC X(16))
 * @returns {string} response.originationTimestamp - Origination timestamp (TRAN-ORIG-TS PIC X(26))
 * @returns {string} response.processingTimestamp - Processing timestamp (TRAN-PROC-TS PIC X(26))
 * @returns {string} response.status - Transaction status (pending, posted, declined)
 * @returns {string} response.authorizationCode - Authorization code if approved
 * 
 * @throws {Error} If transaction ID is invalid format
 * @throws {Error} If transaction not found (404)
 * @throws {Error} If API request fails
 * 
 * @example
 * const transaction = await getTransactionById('0001000000000001');
 * console.log(transaction.amount); // "$1,234.56"
 * console.log(transaction.merchantName); // "Amazon Web Services"
 */
const getTransactionById = async (transactionId) => {
  try {
    // Validate transaction ID format: 16 characters
    // COBOL: TRAN-ID PIC X(16)
    if (!transactionId || typeof transactionId !== 'string') {
      throw new Error('Transaction ID is required and must be a string');
    }

    if (transactionId.length !== 16) {
      throw new Error('Transaction ID must be exactly 16 characters');
    }

    // Execute GET request to /api/transactions/{transactionId}
    const response = await apiClient.get(`/transactions/${transactionId}`);

    // Transform response to include formatted fields
    const transaction = response.data;

    return {
      transactionId: transaction.transactionId,
      transactionTypeCode: transaction.transactionTypeCode || '',
      transactionCategoryCode: transaction.transactionCategoryCode || '',
      transactionSource: transaction.transactionSource || '',
      description: transaction.description || '',
      amount: formatAmount(transaction.amount),
      merchantId: transaction.merchantId || '',
      merchantName: transaction.merchantName || '',
      merchantCity: transaction.merchantCity || '',
      merchantZip: transaction.merchantZip || '',
      cardNumber: transaction.cardNumber,
      originationTimestamp: transaction.originationTimestamp || '',
      processingTimestamp: transaction.processingTimestamp || '',
      status: transaction.status || 'posted',
      authorizationCode: transaction.authorizationCode || '',
    };
  } catch (error) {
    console.error(
      `[Transaction Service] Error fetching transaction ${transactionId}:`,
      error
    );

    // Enhanced error messages based on error type
    if (error.status === 404) {
      throw new Error(
        `Transaction not found: ${transactionId}. Please verify the transaction ID.`
      );
    }

    if (error.status) {
      throw new Error(
        `Failed to retrieve transaction: ${error.message || 'Server error'}`
      );
    }

    throw error;
  }
};

/**
 * Create a new transaction with comprehensive validation
 * 
 * Replaces COBOL: EXEC CICS WRITE DATASET(TRANSACT) FROM(TRAN-RECORD)
 * Maps to: POST /api/transactions
 * 
 * Business Logic from COTRN02C.cbl:
 * - Transaction amount validation (WS-TRAN-AMT-N PIC S9(9)V99, lines 58)
 * - Card number validation via CCXREF file (EXEC CICS READ DATASET(CCXREF))
 * - Account balance validation via ACCTDAT file
 * - Credit limit enforcement
 * - Date format validation (WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD', line 60)
 * - Transaction ID generation
 * - Merchant information validation
 * 
 * Validation Rules:
 * - Amount must be positive decimal with 2 decimal places
 * - Amount range: -99999999.99 to +99999999.99 (COBOL PIC S9(9)V99)
 * - Card number must be exactly 16 digits
 * - Transaction date must be in YYYY-MM-DD format
 * - Merchant ID must be valid
 * 
 * Server-Side Validations (handled by Spring Boot backend):
 * - Card authorization check (card exists and is active)
 * - Card expiration date validation
 * - Available credit validation (current balance + transaction amount <= credit limit)
 * - Transaction decline conditions
 * 
 * @param {Object} transactionData - Transaction creation request data
 * @param {string} transactionData.cardNumber - 16-digit card number (required)
 * @param {number} transactionData.amount - Transaction amount as number (required)
 * @param {string} transactionData.merchantId - 9-digit merchant ID (required)
 * @param {string} transactionData.merchantName - Merchant name up to 50 characters (required)
 * @param {string} transactionData.merchantCity - Merchant city up to 50 characters (optional)
 * @param {string} transactionData.merchantCategory - 4-digit category code (optional)
 * @param {string} transactionData.transactionTypeCode - 2-character type code (required)
 * @param {string} transactionData.description - Transaction description up to 100 characters (optional)
 * @param {string} transactionData.transactionDate - Transaction date YYYY-MM-DD (optional, defaults to current date)
 * 
 * @returns {Promise<Object>} Created transaction object
 * @returns {string} response.transactionId - Generated 16-character transaction ID
 * @returns {string} response.status - Transaction status (pending, posted, declined)
 * @returns {string} response.message - Success or decline message
 * @returns {Object} response.transaction - Full transaction details
 * 
 * @throws {Error} If required fields are missing
 * @throws {Error} If amount validation fails
 * @throws {Error} If card number format is invalid
 * @throws {Error} If date format is invalid
 * @throws {Error} If server-side validation fails (insufficient credit, card expired, etc.)
 * 
 * @example
 * const newTransaction = await createTransaction({
 *   cardNumber: '4000123456789010',
 *   amount: 125.50,
 *   merchantId: '123456789',
 *   merchantName: 'Amazon Web Services',
 *   merchantCity: 'Seattle',
 *   merchantCategory: '5734',
 *   transactionTypeCode: '00',
 *   description: 'AWS Cloud Services',
 *   transactionDate: '2024-01-15'
 * });
 */
const createTransaction = async (transactionData) => {
  try {
    // Validate required fields
    if (!transactionData || typeof transactionData !== 'object') {
      throw new Error('Transaction data is required');
    }

    // Validate card number: 16 digits (TRAN-CARD-NUM PIC X(16))
    if (!transactionData.cardNumber) {
      throw new Error('Card number is required');
    }

    if (!/^\d{16}$/.test(transactionData.cardNumber)) {
      throw new Error('Card number must be exactly 16 digits');
    }

    // Validate amount: positive decimal with 2 decimal places
    // COBOL: WS-TRAN-AMT-N PIC S9(9)V99
    // Range: -99999999.99 to +99999999.99
    if (transactionData.amount === undefined || transactionData.amount === null) {
      throw new Error('Transaction amount is required');
    }

    const amount = Number(transactionData.amount);

    if (isNaN(amount)) {
      throw new Error('Transaction amount must be a valid number');
    }

    if (amount <= 0) {
      throw new Error('Transaction amount must be greater than zero');
    }

    if (Math.abs(amount) > 99999999.99) {
      throw new Error('Transaction amount exceeds maximum allowed value of $99,999,999.99');
    }

    // Validate decimal precision: maximum 2 decimal places
    const amountStr = amount.toFixed(2);
    const decimalPart = amountStr.split('.')[1];
    if (decimalPart && decimalPart.length > 2) {
      throw new Error('Transaction amount must have at most 2 decimal places');
    }

    // Validate merchant ID: 9 digits (TRAN-MERCHANT-ID PIC 9(09))
    if (!transactionData.merchantId) {
      throw new Error('Merchant ID is required');
    }

    if (!/^\d{1,9}$/.test(transactionData.merchantId)) {
      throw new Error('Merchant ID must be numeric and up to 9 digits');
    }

    // Validate merchant name: required, max 50 characters (TRAN-MERCHANT-NAME PIC X(50))
    if (!transactionData.merchantName) {
      throw new Error('Merchant name is required');
    }

    if (transactionData.merchantName.length > 50) {
      throw new Error('Merchant name must not exceed 50 characters');
    }

    // Validate merchant city: optional, max 50 characters (TRAN-MERCHANT-CITY PIC X(50))
    if (transactionData.merchantCity && transactionData.merchantCity.length > 50) {
      throw new Error('Merchant city must not exceed 50 characters');
    }

    // Validate transaction type code: 2 characters (TRAN-TYPE-CD PIC X(02))
    if (!transactionData.transactionTypeCode) {
      throw new Error('Transaction type code is required');
    }

    if (transactionData.transactionTypeCode.length !== 2) {
      throw new Error('Transaction type code must be exactly 2 characters');
    }

    // Validate description: optional, max 100 characters (TRAN-DESC PIC X(100))
    if (transactionData.description && transactionData.description.length > 100) {
      throw new Error('Transaction description must not exceed 100 characters');
    }

    // Validate transaction date: YYYY-MM-DD format (WS-DATE-FORMAT)
    if (transactionData.transactionDate) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(transactionData.transactionDate)) {
        throw new Error('Transaction date must be in YYYY-MM-DD format');
      }

      // Validate date is a real date
      const dateObj = new Date(transactionData.transactionDate);
      if (isNaN(dateObj.getTime())) {
        throw new Error('Transaction date is not a valid date');
      }
    }

    // Build request payload for API
    const requestPayload = {
      cardNumber: transactionData.cardNumber,
      amount: Number(amount.toFixed(2)), // Ensure 2 decimal places
      merchantId: transactionData.merchantId,
      merchantName: transactionData.merchantName,
      merchantCity: transactionData.merchantCity || '',
      merchantCategory: transactionData.merchantCategory || '',
      transactionTypeCode: transactionData.transactionTypeCode,
      description: transactionData.description || '',
      transactionDate: transactionData.transactionDate || new Date().toISOString().split('T')[0],
    };

    // Execute POST request to /api/transactions
    const response = await apiClient.post('/transactions', requestPayload);

    // Return created transaction with formatted fields
    const createdTransaction = response.data;

    return {
      transactionId: createdTransaction.transactionId,
      status: createdTransaction.status || 'posted',
      message: createdTransaction.message || 'Transaction created successfully',
      transaction: {
        ...createdTransaction,
        amount: formatAmount(createdTransaction.amount),
      },
    };
  } catch (error) {
    console.error('[Transaction Service] Error creating transaction:', error);

    // Enhanced error messages for specific validation failures
    if (error.status === 400) {
      // Bad request - validation error from server
      throw new Error(
        error.message || 'Transaction validation failed. Please check your input.'
      );
    }

    if (error.status === 402) {
      // Payment required - insufficient credit
      throw new Error(
        'Transaction declined: Insufficient credit available on card'
      );
    }

    if (error.status === 403) {
      // Forbidden - card expired or inactive
      throw new Error('Transaction declined: Card is expired or inactive');
    }

    if (error.status === 404) {
      // Not found - invalid card number
      throw new Error('Transaction declined: Invalid card number');
    }

    if (error.status === 409) {
      // Conflict - duplicate transaction
      throw new Error('Transaction declined: Duplicate transaction detected');
    }

    if (error.status === 422) {
      // Unprocessable entity - business logic error
      throw new Error(
        error.message || 'Transaction declined due to business rule violation'
      );
    }

    if (error.status) {
      throw new Error(
        `Failed to create transaction: ${error.message || 'Server error'}`
      );
    }

    throw error;
  }
};

/**
 * Retrieve transactions for a specific card with pagination
 * 
 * Convenience method wrapping getTransactions with cardNumber filter
 * 
 * Business Logic: Card-specific transaction history retrieval
 * Useful for displaying transaction history on card detail screen
 * 
 * @param {string} cardNumber - 16-digit card number
 * @param {number} page - Page number (1-based index, default 1)
 * @param {number} pageSize - Transactions per page (default 10)
 * 
 * @returns {Promise<Object>} Transaction list response with pagination metadata
 * 
 * @throws {Error} If card number format is invalid
 * @throws {Error} If API request fails
 * 
 * @example
 * const result = await getTransactionsByCard('4000123456789010', 1, 10);
 * console.log(result.transactions); // Array of transactions for this card
 */
const getTransactionsByCard = async (cardNumber, page = 1, pageSize = 10) => {
  try {
    // Validate card number format
    if (!cardNumber || !/^\d{16}$/.test(cardNumber)) {
      throw new Error('Card number must be exactly 16 digits');
    }

    // Call getTransactions with cardNumber filter
    return await getTransactions({ cardNumber }, page, pageSize);
  } catch (error) {
    console.error(
      `[Transaction Service] Error fetching transactions for card ${cardNumber}:`,
      error
    );
    throw error;
  }
};

/**
 * Retrieve transactions within a specific date range with pagination
 * 
 * Convenience method wrapping getTransactions with date range filters
 * 
 * Business Logic: Date-filtered transaction history retrieval
 * Useful for generating transaction reports and statement views
 * 
 * @param {string} startDate - Start date in YYYY-MM-DD format (inclusive)
 * @param {string} endDate - End date in YYYY-MM-DD format (inclusive)
 * @param {number} page - Page number (1-based index, default 1)
 * @param {number} pageSize - Transactions per page (default 10)
 * 
 * @returns {Promise<Object>} Transaction list response with pagination metadata
 * 
 * @throws {Error} If date format is invalid
 * @throws {Error} If end date is before start date
 * @throws {Error} If API request fails
 * 
 * @example
 * const result = await getTransactionsByDateRange('2024-01-01', '2024-01-31', 1, 10);
 * console.log(result.transactions); // Array of transactions in January 2024
 */
const getTransactionsByDateRange = async (
  startDate,
  endDate,
  page = 1,
  pageSize = 10
) => {
  try {
    // Validate date formats
    if (!startDate || !/^\d{4}-\d{2}-\d{2}$/.test(startDate)) {
      throw new Error('Start date must be in YYYY-MM-DD format');
    }

    if (!endDate || !/^\d{4}-\d{2}-\d{2}$/.test(endDate)) {
      throw new Error('End date must be in YYYY-MM-DD format');
    }

    // Validate date objects are valid
    const startDateObj = new Date(startDate);
    const endDateObj = new Date(endDate);

    if (isNaN(startDateObj.getTime())) {
      throw new Error('Start date is not a valid date');
    }

    if (isNaN(endDateObj.getTime())) {
      throw new Error('End date is not a valid date');
    }

    // Validate end date is not before start date
    if (endDateObj < startDateObj) {
      throw new Error('End date must be greater than or equal to start date');
    }

    // Call getTransactions with date range filters
    return await getTransactions({ startDate, endDate }, page, pageSize);
  } catch (error) {
    console.error(
      `[Transaction Service] Error fetching transactions for date range ${startDate} to ${endDate}:`,
      error
    );
    throw error;
  }
};

/**
 * Format transaction amount to currency string with proper formatting
 * 
 * Business Logic from COBOL:
 * - WS-TRAN-AMT PIC +99999999.99 (lines 56 in COTRN00C.cbl)
 * - Always displays 2 decimal places
 * - Includes thousands separators
 * - Includes currency symbol
 * 
 * Preserves COBOL COMP-3 decimal precision (2 decimal places)
 * 
 * @param {number|string} amount - Amount to format (can be number or string)
 * 
 * @returns {string} Formatted amount string (e.g., "$1,234.56")
 * 
 * @example
 * formatAmount(1234.5);  // Returns "$1,234.50"
 * formatAmount('1234.56'); // Returns "$1,234.56"
 * formatAmount(0);       // Returns "$0.00"
 */
const formatAmount = (amount) => {
  try {
    // Convert to number if string
    const numericAmount = typeof amount === 'string' ? parseFloat(amount) : amount;

    // Handle invalid amounts
    if (isNaN(numericAmount)) {
      return '$0.00';
    }

    // Format with currency symbol, thousands separators, and 2 decimal places
    // Matches COBOL PIC +99999999.99 format
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(numericAmount);
  } catch (error) {
    console.error('[Transaction Service] Error formatting amount:', error);
    return '$0.00';
  }
};

/**
 * Format date string to user-friendly display format
 * 
 * Business Logic from COBOL:
 * - WS-TRAN-DATE PIC X(08) VALUE '00/00/00' (lines 57 in COTRN00C.cbl)
 * - Converts from ISO 8601 format (YYYY-MM-DD) to display format (MM/DD/YYYY)
 * 
 * Handles date format conversion from API (YYYY-MM-DD) to display format
 * 
 * @param {string} dateString - Date string in YYYY-MM-DD format (ISO 8601)
 * 
 * @returns {string} Formatted date string (MM/DD/YYYY)
 * 
 * @example
 * formatDate('2024-01-15'); // Returns "01/15/2024"
 * formatDate('2024-12-31'); // Returns "12/31/2024"
 */
const formatDate = (dateString) => {
  try {
    // Handle null or undefined
    if (!dateString) {
      return '';
    }

    // Parse ISO 8601 date string (YYYY-MM-DD)
    const dateObj = new Date(dateString);

    // Validate date object
    if (isNaN(dateObj.getTime())) {
      return dateString; // Return original string if parsing fails
    }

    // Format as MM/DD/YYYY
    const month = String(dateObj.getMonth() + 1).padStart(2, '0');
    const day = String(dateObj.getDate()).padStart(2, '0');
    const year = dateObj.getFullYear();

    return `${month}/${day}/${year}`;
  } catch (error) {
    console.error('[Transaction Service] Error formatting date:', error);
    return dateString; // Return original string on error
  }
};

/**
 * Transaction Service Default Export
 * 
 * Exports all transaction management methods as a single service object
 * 
 * Available Methods:
 * - getTransactions: Retrieve paginated transaction list with filtering
 * - getTransactionById: Retrieve single transaction detail
 * - createTransaction: Create new transaction with validation
 * - getTransactionsByCard: Retrieve transactions for specific card
 * - getTransactionsByDateRange: Retrieve transactions within date range
 * - formatAmount: Format transaction amount to currency string
 * - formatDate: Format date to display format
 * 
 * Usage Example:
 * import transactionService from './services/transactionService';
 * 
 * const transactions = await transactionService.getTransactions({}, 1, 10);
 * const transaction = await transactionService.getTransactionById('0001000000000001');
 * const newTransaction = await transactionService.createTransaction({ ... });
 */
const transactionService = {
  getTransactions,
  getTransactionById,
  createTransaction,
  getTransactionsByCard,
  getTransactionsByDateRange,
  formatAmount,
  formatDate,
};

export default transactionService;
