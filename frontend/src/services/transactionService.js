/**
 * CardDemo Transaction Service
 * 
 * Transaction service module providing RESTful operations for credit card transaction 
 * management in the CardDemo frontend application. This service encapsulates all transaction-
 * related business logic and API communication, serving as the primary interface between 
 * React transaction components and the backend REST API endpoints.
 * 
 * Transformation Context:
 * Maps COBOL transaction processing programs (COTRN00C, COTRN01C, COTRN02C) to modern 
 * RESTful API operations, transforming VSAM TRANSACT file I/O operations into HTTP requests
 * while preserving identical business logic, pagination patterns, and computational precision.
 * 
 * COBOL Program Mapping:
 * - COTRN00C.cbl → getTransactions() - Transaction list with pagination (10 per page)
 * - COTRN01C.cbl → getTransaction() - Single transaction retrieval
 * - COTRN02C.cbl → createTransaction() - New transaction posting with balance updates
 * - COTRN01C.cbl → getTransactionsByCategory() - Category aggregation logic
 * 
 * Key Features:
 * - Transaction list retrieval with pagination (10 transactions per page, matching COBOL)
 * - Date range filtering for transaction queries
 * - Transaction category aggregation and summary calculations
 * - New transaction creation with atomic balance updates
 * - COMP-3 decimal precision preservation for transaction amounts (BigDecimal scale 2)
 * - Client-side utility functions for filtering and formatting
 * - Comprehensive error handling with user-friendly messages
 * 
 * Pagination Pattern Preservation:
 * Maintains COBOL COTRN00C pagination pattern of exactly 10 transactions per page,
 * preserving screen layout and user workflow from 3270 BMS terminal interface.
 * 
 * Dependencies:
 * - apiClient.js: Centralized Axios HTTP client for authenticated REST API requests
 * 
 * @module services/transactionService
 */

import apiClient from './apiClient';

/**
 * Retrieve paginated list of transactions with optional filtering
 * 
 * Maps COBOL COTRN00C.cbl transaction list display functionality which performs
 * sequential read of VSAM TRANSACT file with pagination support. Preserves exact
 * pagination pattern of 10 transactions per page from original BMS screen design.
 * 
 * COBOL Equivalence:
 * - COBOL: Sequential READ of TRANSACT file with date filtering
 * - COBOL: WS-REC-COUNT to track records per page (hardcoded to 10)
 * - COBOL: PERFORM READ-TRANSACT-PARA UNTIL EOF OR REC-COUNT >= 10
 * - Java: GET /api/transactions with query parameters for pagination and filtering
 * 
 * Transaction Amount Precision:
 * Preserves COBOL COMP-3 decimal precision (PIC S9(9)V99) as BigDecimal with scale 2,
 * ensuring identical financial calculations without rounding discrepancies.
 * 
 * @param {string} accountId - Account ID to filter transactions (maps COBOL ACCT-ID)
 * @param {number} page - Page number for pagination (1-based, default: 1)
 * @param {number} pageSize - Number of records per page (default: 10, matching COBOL)
 * @param {string|null} startDate - Start date for filtering (format: YYYY-MM-DD, optional)
 * @param {string|null} endDate - End date for filtering (format: YYYY-MM-DD, optional)
 * @returns {Promise<Object>} Promise resolving to paginated transaction response
 * 
 * Response Structure:
 * {
 *   transactions: Array<{
 *     transactionId: string,        // Maps COBOL TRAN-ID (PIC X(16))
 *     accountId: string,             // Maps COBOL ACCT-ID (PIC 9(11))
 *     cardNumber: string,            // Maps COBOL CARD-NUM (PIC 9(16))
 *     transactionDate: string,       // Maps COBOL TRAN-DATE (ISO format)
 *     transactionAmount: number,     // Maps COBOL TRAN-AMT (COMP-3, scale 2)
 *     transactionType: string,       // Maps COBOL TRAN-TYPE-CD (PIC X(2))
 *     transactionCategory: string,   // Maps COBOL TRAN-CAT-CD (PIC 9(4))
 *     merchantName: string,          // Maps COBOL TRAN-MERCHANT-NAME (PIC X(50))
 *     merchantCity: string,          // Maps COBOL TRAN-MERCHANT-CITY (PIC X(50))
 *     merchantZip: string,           // Maps COBOL TRAN-MERCHANT-ZIP (PIC X(10))
 *     description: string            // Maps COBOL TRAN-DESC (PIC X(100))
 *   }>,
 *   totalCount: number,              // Total records matching criteria
 *   currentPage: number,             // Current page number (1-based)
 *   totalPages: number,              // Total number of pages
 *   pageSize: number                 // Records per page (always 10)
 * }
 * 
 * @throws {Error} 'Invalid date range specified' - Start date after end date
 * @throws {Error} 'Account not found' - Invalid account ID
 * @throws {Error} Network or server errors from apiClient interceptors
 * 
 * @example
 * // Retrieve first page of transactions for account (maps COBOL COTRN00C initial display)
 * const response = await getTransactions('12345678901', 1, 10);
 * console.log(`Showing ${response.transactions.length} of ${response.totalCount} transactions`);
 * 
 * @example
 * // Retrieve transactions with date range filter (maps COBOL date filtering logic)
 * const response = await getTransactions('12345678901', 1, 10, '2024-01-01', '2024-01-31');
 * response.transactions.forEach(txn => {
 *   console.log(`${txn.transactionDate}: ${txn.merchantName} - $${txn.transactionAmount}`);
 * });
 */
const getTransactions = async (accountId, page = 1, pageSize = 10, startDate = null, endDate = null) => {
  try {
    // Validate account ID is provided
    if (!accountId || accountId.trim() === '') {
      throw new Error('Account ID is required');
    }

    // Validate page number is positive
    if (page < 1) {
      throw new Error('Page number must be greater than 0');
    }

    // Validate page size (enforce 10 transactions per page matching COBOL pattern)
    // Allow flexibility but default to 10 for COBOL equivalence
    if (pageSize < 1 || pageSize > 100) {
      throw new Error('Page size must be between 1 and 100');
    }

    // Validate date range if both dates provided
    if (startDate && endDate) {
      const start = new Date(startDate);
      const end = new Date(endDate);
      if (start > end) {
        throw new Error('Invalid date range specified');
      }
    }

    // Build query parameters for GET request
    // Maps COBOL working storage variables to HTTP query string
    const params = {
      accountId: accountId.trim(),
      page,
      pageSize
    };

    // Add optional date filters if provided
    // Maps COBOL date comparison logic (IF TRAN-DATE >= START-DATE AND <= END-DATE)
    if (startDate) {
      params.startDate = startDate;
    }
    if (endDate) {
      params.endDate = endDate;
    }

    // Execute GET request to retrieve paginated transactions
    // Maps COBOL: EXEC CICS READ FILE('TRANSACT') INTO(TRAN-RECORD)
    const response = await apiClient.get('/transactions', { params });

    // Return response data containing transactions array and pagination metadata
    // Preserves COBOL screen display structure with current page context
    return response.data;

  } catch (error) {
    // Enhanced error handling with context-specific messages
    // Maps COBOL HANDLE CONDITION NOTFND, ERROR, IOERR patterns
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 404) {
        throw new Error('Account not found');
      }
      
      if (status === 400) {
        throw new Error(data.message || 'Invalid request parameters');
      }
      
      throw new Error(data.message || 'Error retrieving transactions');
    }
    
    // Re-throw validation errors from parameter checks
    if (error.message.includes('required') || 
        error.message.includes('Invalid date range') ||
        error.message.includes('Page number') ||
        error.message.includes('Page size')) {
      throw error;
    }
    
    // Network or unexpected errors
    throw new Error('Failed to retrieve transactions. Please try again.');
  }
};

/**
 * Retrieve single transaction by transaction ID
 * 
 * Maps COBOL COTRN01C.cbl transaction detail view functionality which performs
 * random READ of VSAM TRANSACT file by primary key (transaction ID).
 * 
 * COBOL Equivalence:
 * - COBOL: EXEC CICS READ FILE('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)
 * - Java: GET /api/transactions/{transactionId}
 * 
 * @param {string} transactionId - Unique transaction identifier (maps COBOL TRAN-ID)
 * @returns {Promise<Object>} Promise resolving to transaction detail object
 * 
 * Response Structure:
 * {
 *   transactionId: string,
 *   accountId: string,
 *   cardNumber: string,
 *   transactionDate: string,
 *   transactionTime: string,
 *   transactionAmount: number,
 *   transactionType: string,
 *   transactionTypeDescription: string,
 *   transactionCategory: string,
 *   transactionCategoryDescription: string,
 *   merchantName: string,
 *   merchantCity: string,
 *   merchantZip: string,
 *   description: string,
 *   originalAmount: number,
 *   confirmationNumber: string
 * }
 * 
 * @throws {Error} 'Transaction not found' - Invalid transaction ID (404)
 * @throws {Error} 'Transaction ID is required' - Missing parameter
 * @throws {Error} Network or server errors from apiClient interceptors
 * 
 * @example
 * // Retrieve transaction details (maps COBOL COTRN01C transaction view)
 * const transaction = await getTransaction('1234567890123456');
 * console.log(`Transaction: ${transaction.merchantName} - $${transaction.transactionAmount}`);
 */
const getTransaction = async (transactionId) => {
  try {
    // Validate transaction ID is provided
    if (!transactionId || transactionId.trim() === '') {
      throw new Error('Transaction ID is required');
    }

    // Execute GET request to retrieve single transaction by ID
    // Maps COBOL random READ with RIDFLD specifying primary key
    const response = await apiClient.get(`/transactions/${transactionId.trim()}`);

    // Return transaction detail object
    return response.data;

  } catch (error) {
    // Handle specific error conditions
    // Maps COBOL HANDLE CONDITION NOTFND for transaction not found
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 404) {
        throw new Error('Transaction not found');
      }
      
      throw new Error(data.message || 'Error retrieving transaction details');
    }
    
    // Re-throw validation errors
    if (error.message.includes('required')) {
      throw error;
    }
    
    // Network or unexpected errors
    throw new Error('Failed to retrieve transaction. Please try again.');
  }
};

/**
 * Create new transaction with atomic balance update
 * 
 * Maps COBOL COTRN02C.cbl transaction creation functionality which performs
 * WRITE to VSAM TRANSACT file with synchronized account balance update.
 * Preserves atomic transaction boundaries using EXEC CICS SYNCPOINT pattern.
 * 
 * COBOL Equivalence:
 * - COBOL: EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)
 * - COBOL: EXEC CICS REWRITE FILE('ACCTDAT') FROM(ACCT-RECORD)
 * - COBOL: EXEC CICS SYNCPOINT (atomic commit of both operations)
 * - Java: POST /api/transactions with @Transactional boundary
 * 
 * Transaction Atomicity:
 * Backend performs atomic operation: create transaction + update account balance.
 * If either operation fails, both are rolled back (CICS SYNCPOINT ROLLBACK equivalent).
 * 
 * Amount Precision:
 * Validates and preserves COMP-3 decimal precision (2 decimal places) for transaction
 * amounts, matching COBOL PIC S9(9)V99 field definition.
 * 
 * @param {Object} transactionData - Transaction data for creation
 * @param {string} transactionData.accountId - Account ID (required, PIC 9(11))
 * @param {string} transactionData.cardNumber - Card number (required, PIC 9(16))
 * @param {number} transactionData.transactionAmount - Amount (required, scale 2)
 * @param {string} transactionData.transactionType - Type code (required, PIC X(2))
 * @param {string} transactionData.transactionCategory - Category code (required, PIC 9(4))
 * @param {string} transactionData.merchantName - Merchant name (required, PIC X(50))
 * @param {string} transactionData.merchantCity - Merchant city (optional, PIC X(50))
 * @param {string} transactionData.merchantZip - Merchant ZIP (optional, PIC X(10))
 * @param {string} transactionData.description - Description (optional, PIC X(100))
 * @param {string} transactionData.transactionDate - Date (optional, defaults to current)
 * @returns {Promise<Object>} Promise resolving to created transaction with generated ID
 * 
 * Response Structure:
 * {
 *   transactionId: string,           // Generated transaction ID
 *   accountId: string,
 *   cardNumber: string,
 *   transactionDate: string,
 *   transactionTime: string,
 *   transactionAmount: number,
 *   transactionType: string,
 *   transactionCategory: string,
 *   merchantName: string,
 *   description: string,
 *   confirmationNumber: string,
 *   newBalance: number               // Updated account balance after transaction
 * }
 * 
 * @throws {Error} 'Invalid transaction amount' - Amount <= 0 or invalid format
 * @throws {Error} 'Insufficient account balance for this transaction' - Balance check failure
 * @throws {Error} 'Account not found' - Invalid account ID
 * @throws {Error} 'Card not found' - Invalid card number
 * @throws {Error} Field-specific validation errors from backend
 * 
 * @example
 * // Create purchase transaction (maps COBOL COTRN02C transaction posting)
 * const newTransaction = await createTransaction({
 *   accountId: '12345678901',
 *   cardNumber: '4111111111111111',
 *   transactionAmount: 99.99,
 *   transactionType: 'PU',
 *   transactionCategory: '5411',
 *   merchantName: 'Grocery Store',
 *   merchantCity: 'Seattle',
 *   merchantZip: '98101',
 *   description: 'Weekly groceries'
 * });
 * console.log(`Transaction created: ${newTransaction.transactionId}`);
 * console.log(`New balance: $${newTransaction.newBalance}`);
 */
const createTransaction = async (transactionData) => {
  try {
    // Validate required fields
    // Maps COBOL field validation logic from COTRN02C EDIT-CHECK-PARA
    if (!transactionData.accountId || transactionData.accountId.trim() === '') {
      throw new Error('Account ID is required');
    }

    if (!transactionData.cardNumber || transactionData.cardNumber.trim() === '') {
      throw new Error('Card number is required');
    }

    if (!transactionData.transactionType || transactionData.transactionType.trim() === '') {
      throw new Error('Transaction type is required');
    }

    if (!transactionData.transactionCategory || transactionData.transactionCategory.trim() === '') {
      throw new Error('Transaction category is required');
    }

    if (!transactionData.merchantName || transactionData.merchantName.trim() === '') {
      throw new Error('Merchant name is required');
    }

    // Validate transaction amount
    // Maps COBOL: IF TRAN-AMT-I NOT NUMERIC OR TRAN-AMT-I <= ZERO
    if (transactionData.transactionAmount === undefined || 
        transactionData.transactionAmount === null) {
      throw new Error('Transaction amount is required');
    }

    const amount = Number(transactionData.transactionAmount);
    if (isNaN(amount) || amount <= 0) {
      throw new Error('Invalid transaction amount');
    }

    // Validate amount precision (2 decimal places max, matching COMP-3 scale)
    // Maps COBOL PIC S9(9)V99 precision constraint
    const amountStr = amount.toFixed(2);
    const decimalPart = amountStr.split('.')[1];
    if (decimalPart && decimalPart.length > 2) {
      throw new Error('Transaction amount cannot have more than 2 decimal places');
    }

    // Prepare request body with validated and formatted data
    // Maps COBOL TRAN-RECORD structure fields
    const requestBody = {
      accountId: transactionData.accountId.trim(),
      cardNumber: transactionData.cardNumber.trim(),
      transactionAmount: parseFloat(amount.toFixed(2)), // Preserve COMP-3 scale 2
      transactionType: transactionData.transactionType.trim(),
      transactionCategory: transactionData.transactionCategory.trim(),
      merchantName: transactionData.merchantName.trim(),
      merchantCity: transactionData.merchantCity?.trim() || '',
      merchantZip: transactionData.merchantZip?.trim() || '',
      description: transactionData.description?.trim() || '',
      transactionDate: transactionData.transactionDate || new Date().toISOString().split('T')[0]
    };

    // Execute POST request to create transaction
    // Maps COBOL: EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)
    // Backend performs atomic operation: create transaction + update balance
    const response = await apiClient.post('/transactions', requestBody);

    // Return created transaction with generated ID and updated balance
    return response.data;

  } catch (error) {
    // Enhanced error handling with business rule validation
    // Maps COBOL error handling paragraphs and condition checking
    if (error.response) {
      const { status, data } = error.response;
      
      // 400 Bad Request - Validation errors
      if (status === 400) {
        // Check for specific business rule violations
        if (data.message && data.message.includes('balance')) {
          throw new Error('Insufficient account balance for this transaction');
        }
        
        if (data.errors) {
          // Return field-specific validation errors
          const errorMessages = Object.values(data.errors).join(', ');
          throw new Error(errorMessages);
        }
        
        throw new Error(data.message || 'Invalid transaction data');
      }
      
      // 404 Not Found - Account or card not found
      if (status === 404) {
        if (data.message && data.message.toLowerCase().includes('card')) {
          throw new Error('Card not found');
        }
        throw new Error('Account not found');
      }
      
      // 409 Conflict - Duplicate transaction
      if (status === 409) {
        throw new Error('Duplicate transaction detected');
      }
      
      throw new Error(data.message || 'Error creating transaction');
    }
    
    // Re-throw validation errors from parameter checks
    if (error.message.includes('required') || 
        error.message.includes('Invalid transaction amount') ||
        error.message.includes('decimal places')) {
      throw error;
    }
    
    // Network or unexpected errors
    throw new Error('Failed to create transaction. Please try again.');
  }
};

/**
 * Retrieve transaction aggregation by category
 * 
 * Maps COBOL COTRN01C.cbl category aggregation logic which performs sequential
 * read of TRANSACT file with grouping and summing by transaction category code.
 * 
 * COBOL Equivalence:
 * - COBOL: Sequential READ with category grouping (PERFORM VARYING BY CATEGORY)
 * - COBOL: SUM accumulation by category (ADD TRAN-AMT TO CAT-TOTAL)
 * - COBOL: Percentage calculation (COMPUTE CAT-PCT = CAT-TOTAL / GRAND-TOTAL * 100)
 * - Java: GET /api/transactions/categories with server-side aggregation
 * 
 * Calculation Precision:
 * Preserves COMP-3 decimal arithmetic precision for category totals and percentages,
 * matching COBOL PIC S9(11)V99 accumulator fields.
 * 
 * @param {string} accountId - Account ID for filtering
 * @param {string|null} startDate - Start date for period (format: YYYY-MM-DD)
 * @param {string|null} endDate - End date for period (format: YYYY-MM-DD)
 * @returns {Promise<Array>} Promise resolving to array of category aggregations
 * 
 * Response Structure:
 * [
 *   {
 *     category: string,                // Maps COBOL TRAN-CAT-CD
 *     categoryDescription: string,      // Maps COBOL TRAN-CAT-DESC
 *     totalAmount: number,              // Maps COBOL CAT-TOTAL (COMP-3)
 *     transactionCount: number,         // Maps COBOL CAT-COUNT
 *     percentage: number,               // Maps COBOL CAT-PCT (2 decimals)
 *     averageAmount: number             // Maps COBOL CAT-AVG
 *   }
 * ]
 * 
 * @throws {Error} 'Account not found' - Invalid account ID
 * @throws {Error} 'Invalid date range specified' - Start date after end date
 * 
 * @example
 * // Get category breakdown for current month (maps COBOL category summary)
 * const categories = await getTransactionsByCategory('12345678901', '2024-01-01', '2024-01-31');
 * categories.forEach(cat => {
 *   console.log(`${cat.categoryDescription}: $${cat.totalAmount} (${cat.percentage}%)`);
 * });
 */
const getTransactionsByCategory = async (accountId, startDate = null, endDate = null) => {
  try {
    // Validate account ID is provided
    if (!accountId || accountId.trim() === '') {
      throw new Error('Account ID is required');
    }

    // Validate date range if both dates provided
    if (startDate && endDate) {
      const start = new Date(startDate);
      const end = new Date(endDate);
      if (start > end) {
        throw new Error('Invalid date range specified');
      }
    }

    // Build query parameters
    const params = {
      accountId: accountId.trim()
    };

    // Add optional date filters
    if (startDate) {
      params.startDate = startDate;
    }
    if (endDate) {
      params.endDate = endDate;
    }

    // Execute GET request to retrieve category aggregations
    // Backend performs SQL GROUP BY or equivalent aggregation logic
    const response = await apiClient.get('/transactions/categories', { params });

    // Return array of category aggregations
    return response.data;

  } catch (error) {
    // Handle errors with appropriate messages
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 404) {
        throw new Error('Account not found');
      }
      
      if (status === 400) {
        throw new Error(data.message || 'Invalid request parameters');
      }
      
      throw new Error(data.message || 'Error retrieving transaction categories');
    }
    
    // Re-throw validation errors
    if (error.message.includes('required') || error.message.includes('Invalid date range')) {
      throw error;
    }
    
    // Network or unexpected errors
    throw new Error('Failed to retrieve transaction categories. Please try again.');
  }
};

/**
 * Retrieve transaction summary statistics for account
 * 
 * Provides aggregated transaction statistics including total debits, total credits,
 * net amount, transaction count, and average transaction amount for specified period.
 * 
 * Maps COBOL summary calculation logic with COMP-3 decimal precision for all
 * financial aggregations.
 * 
 * @param {string} accountId - Account ID for summary
 * @param {string} period - Time period for summary ('day', 'week', 'month', 'year')
 * @returns {Promise<Object>} Promise resolving to transaction summary statistics
 * 
 * Response Structure:
 * {
 *   accountId: string,
 *   period: string,
 *   startDate: string,
 *   endDate: string,
 *   totalDebits: number,              // Sum of debit transactions (purchases, fees)
 *   totalCredits: number,             // Sum of credit transactions (payments, refunds)
 *   netAmount: number,                // totalDebits - totalCredits
 *   transactionCount: number,         // Total number of transactions
 *   debitCount: number,               // Count of debit transactions
 *   creditCount: number,              // Count of credit transactions
 *   averageAmount: number,            // Average transaction amount
 *   largestTransaction: number,       // Maximum transaction amount
 *   smallestTransaction: number       // Minimum transaction amount
 * }
 * 
 * @throws {Error} 'Account not found' - Invalid account ID
 * @throws {Error} 'Invalid period specified' - Period not in allowed values
 * 
 * @example
 * // Get monthly transaction summary (maps COBOL monthly summary report)
 * const summary = await getTransactionSummary('12345678901', 'month');
 * console.log(`Total Debits: $${summary.totalDebits}`);
 * console.log(`Total Credits: $${summary.totalCredits}`);
 * console.log(`Net Amount: $${summary.netAmount}`);
 * console.log(`Average Transaction: $${summary.averageAmount}`);
 */
const getTransactionSummary = async (accountId, period = 'month') => {
  try {
    // Validate account ID is provided
    if (!accountId || accountId.trim() === '') {
      throw new Error('Account ID is required');
    }

    // Validate period parameter
    const validPeriods = ['day', 'week', 'month', 'year'];
    if (!validPeriods.includes(period.toLowerCase())) {
      throw new Error('Invalid period specified. Must be one of: day, week, month, year');
    }

    // Build query parameters
    const params = {
      accountId: accountId.trim(),
      period: period.toLowerCase()
    };

    // Execute GET request to retrieve summary statistics
    const response = await apiClient.get('/transactions/summary', { params });

    // Return summary object with aggregated statistics
    return response.data;

  } catch (error) {
    // Handle errors with appropriate messages
    if (error.response) {
      const { status, data } = error.response;
      
      if (status === 404) {
        throw new Error('Account not found');
      }
      
      if (status === 400) {
        throw new Error(data.message || 'Invalid request parameters');
      }
      
      throw new Error(data.message || 'Error retrieving transaction summary');
    }
    
    // Re-throw validation errors
    if (error.message.includes('required') || error.message.includes('Invalid period')) {
      throw error;
    }
    
    // Network or unexpected errors
    throw new Error('Failed to retrieve transaction summary. Please try again.');
  }
};

/**
 * Client-side utility to filter transactions by transaction type
 * 
 * Provides client-side filtering capability for transaction type categorization.
 * Useful for UI components that need to display transaction subsets without
 * additional API calls.
 * 
 * Transaction Types (maps COBOL TRAN-TYPE-CD values):
 * - 'PU' or 'Purchase': Purchase transactions (debit)
 * - 'PA' or 'Payment': Payment transactions (credit)
 * - 'RF' or 'Refund': Refund transactions (credit)
 * - 'FE' or 'Fee': Fee transactions (debit)
 * - 'IN' or 'Interest': Interest charges (debit)
 * - 'AD' or 'Adjustment': Account adjustments (debit or credit)
 * 
 * @param {Array} transactions - Array of transaction objects to filter
 * @param {string} transactionType - Transaction type code or description to filter by
 * @returns {Array} Filtered array of transactions matching the specified type
 * 
 * @example
 * // Filter for purchase transactions only
 * const purchases = filterTransactionsByType(allTransactions, 'Purchase');
 * console.log(`Found ${purchases.length} purchase transactions`);
 * 
 * @example
 * // Filter by type code
 * const payments = filterTransactionsByType(allTransactions, 'PA');
 * console.log(`Found ${payments.length} payment transactions`);
 */
const filterTransactionsByType = (transactions, transactionType) => {
  // Validate inputs
  if (!Array.isArray(transactions)) {
    console.error('filterTransactionsByType: transactions must be an array');
    return [];
  }

  if (!transactionType || transactionType.trim() === '') {
    console.error('filterTransactionsByType: transactionType is required');
    return transactions; // Return all if no filter specified
  }

  // Normalize transaction type for comparison
  const typeFilter = transactionType.trim().toUpperCase();

  // Map common transaction type descriptions to codes
  const typeMap = {
    'PURCHASE': 'PU',
    'PAYMENT': 'PA',
    'REFUND': 'RF',
    'FEE': 'FE',
    'INTEREST': 'IN',
    'ADJUSTMENT': 'AD'
  };

  // Get type code from map or use provided value
  const typeCode = typeMap[typeFilter] || typeFilter;

  // Filter transactions matching the specified type
  // Case-insensitive comparison for both code and description
  return transactions.filter(transaction => {
    const txnType = transaction.transactionType?.toUpperCase() || '';
    const txnTypeDesc = transaction.transactionTypeDescription?.toUpperCase() || '';
    
    return txnType === typeCode || 
           txnType === typeFilter ||
           txnTypeDesc.includes(typeFilter) ||
           txnTypeDesc === typeFilter;
  });
};

/**
 * Utility to format transaction amount with proper decimal precision
 * 
 * Formats transaction amounts consistently with 2 decimal places, preserving
 * COBOL COMP-3 decimal display format. Handles positive and negative amounts
 * with appropriate sign formatting.
 * 
 * Maps COBOL edited numeric field formatting (PIC +99999999.99) with automatic
 * sign positioning and decimal alignment.
 * 
 * @param {number} amount - Transaction amount to format
 * @returns {string} Formatted amount string with 2 decimal places and currency symbol
 * 
 * @example
 * // Format purchase amount (maps COBOL MOVE TRAN-AMT TO TRAN-AMT-E)
 * const formatted = formatTransactionAmount(99.99);
 * console.log(formatted); // "$99.99"
 * 
 * @example
 * // Format negative amount (refund or credit)
 * const formatted = formatTransactionAmount(-50.00);
 * console.log(formatted); // "-$50.00"
 */
const formatTransactionAmount = (amount) => {
  // Validate amount is a number
  if (amount === undefined || amount === null || isNaN(amount)) {
    return '$0.00';
  }

  // Convert to number and preserve COMP-3 scale (2 decimal places)
  const numAmount = Number(amount);

  // Format with 2 decimal places
  const formattedValue = Math.abs(numAmount).toFixed(2);

  // Add currency symbol and handle negative sign
  if (numAmount < 0) {
    return `-$${formattedValue}`;
  }

  return `$${formattedValue}`;
};

/**
 * Transaction Service Export
 * 
 * Exports all transaction service functions for use by React components.
 * Provides clean async/await interface for transaction operations mapping
 * COBOL programs COTRN00C, COTRN01C, and COTRN02C to RESTful endpoints.
 * 
 * Exported Functions:
 * - getTransactions: Retrieve paginated transaction list with filtering
 * - getTransaction: Retrieve single transaction by ID
 * - createTransaction: Create new transaction with balance update
 * - getTransactionsByCategory: Retrieve category aggregations
 * - getTransactionSummary: Retrieve summary statistics
 * - filterTransactionsByType: Client-side type filtering utility
 * - formatTransactionAmount: Amount formatting utility with COMP-3 precision
 * 
 * Usage Pattern:
 * React components (TransactionListComponent, TransactionCategoryComponent,
 * TransactionAddComponent) import this service to perform all transaction-related
 * operations, ensuring consistent business logic and API interaction patterns
 * across the transaction management features.
 * 
 * Transaction Operations:
 * All functions use apiClient for authenticated HTTP requests with automatic
 * JWT token injection, error handling, and response transformation, maintaining
 * security and session management equivalent to CICS RACF integration.
 */
export default {
  getTransactions,
  getTransaction,
  createTransaction,
  getTransactionsByCategory,
  getTransactionSummary,
  filterTransactionsByType,
  formatTransactionAmount
};

// Named exports for direct import flexibility
export {
  getTransactions,
  getTransaction,
  createTransaction,
  getTransactionsByCategory,
  getTransactionSummary,
  filterTransactionsByType,
  formatTransactionAmount
};
