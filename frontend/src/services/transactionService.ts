/**
 * Transaction Service Module
 * 
 * Converted from COBOL programs:
 * - COTRN00C.cbl: Transaction list with browse/pagination (STARTBR/READNEXT)
 * - COTRN01C.cbl: Transaction detail view (CICS READ TRANSACT file)
 * - COTRN02C.cbl: Transaction entry and posting (CICS WRITE TRANSACT file)
 * 
 * Purpose: Provides API calls for transaction processing and inquiry operations
 * integrating with Spring Boot backend REST endpoints at /api/transactions
 * 
 * Key Features:
 * - List transactions with date range filtering and pagination (10 per page like COBOL)
 * - Retrieve single transaction by ID
 * - Create new transaction with comprehensive validation
 * - Maintains COBOL COMP-3 decimal precision for amounts using BigDecimal equivalents
 * - Preserves COBOL validation rules and error handling patterns
 * 
 * Backend Endpoints:
 * - GET  /api/transactions - List with query parameters
 * - GET  /api/transactions/:id - Detail view
 * - POST /api/transactions - Create new transaction
 */

import api from './api';
import { Transaction, TransactionType } from '../types/transaction';
import { PaginationParams } from '../types/common';

/**
 * Transaction List Query Parameters
 * 
 * Converted from COBOL COTRN00C.cbl pagination and filtering logic:
 * - WS-PAGE-NUM: Page number for browse cursor positioning
 * - CDEMO-CT00-TRNID-FIRST/LAST: Transaction ID range for pagination
 * - TRNIDINI: Starting transaction ID filter
 * 
 * Supports server-side pagination, sorting, and filtering matching
 * COBOL VSAM TRANSACT file STARTBR/READNEXT sequential browse patterns
 */
export interface TransactionQueryParams {
  /**
   * Page number (1-based indexing)
   * COBOL equivalent: WS-PAGE-NUM / CDEMO-CT00-PAGE-NUM
   * Default: 1
   */
  page?: number;

  /**
   * Number of records per page
   * COBOL equivalent: Fixed 10 records per screen (hardcoded in COTRN00C)
   * Default: 10
   */
  pageSize?: number;

  /**
   * Field to sort by
   * COBOL equivalent: VSAM KSDS primary key (TRAN-ID) sort order
   * Examples: 'transOrigTs', 'transAmt', 'transId'
   * Default: 'transOrigTs' (descending)
   */
  sortBy?: string;

  /**
   * Sort direction
   * COBOL equivalent: READNEXT (asc) vs READPREV (desc)
   * Default: 'desc'
   */
  sortDirection?: 'asc' | 'desc';

  /**
   * Filter by start date (inclusive)
   * COBOL equivalent: Date range filtering in TRAN-ORIG-TS field
   * Format: ISO 8601 string (YYYY-MM-DD)
   */
  startDate?: string;

  /**
   * Filter by end date (inclusive)
   * COBOL equivalent: Date range filtering in TRAN-ORIG-TS field
   * Format: ISO 8601 string (YYYY-MM-DD)
   */
  endDate?: string;

  /**
   * Filter by card number
   * COBOL equivalent: TRAN-CARD-NUM filter (PIC X(16))
   * Format: 16-digit numeric string
   */
  cardNum?: string;

  /**
   * Filter by transaction ID
   * COBOL equivalent: TRNIDINI input field (PIC X(16))
   * Format: 16-character string
   */
  transId?: string;
}

/**
 * Transaction List Response
 * 
 * Returned by getTransactions() method
 * Contains array of transactions and pagination metadata
 * 
 * Matches COBOL screen structure:
 * - transactions: 10 rows displayed on COTRN0A screen
 * - pagination: CDEMO-CT00-PAGE-NUM, NEXT-PAGE-FLG
 */
export interface TransactionListResponse {
  /**
   * Array of transaction records
   * COBOL equivalent: 10 rows displayed on screen (TRNID01-TRNID10, etc.)
   */
  transactions: Transaction[];

  /**
   * Pagination metadata
   * COBOL equivalent: WS-PAGE-NUM, CDEMO-CT00-PAGE-NUM, NEXT-PAGE-FLG
   */
  pagination: PaginationParams;
}

/**
 * Transaction Creation Request
 * 
 * Input data for createTransaction() method
 * Omits system-generated fields (transId, createdAt, transProcTs)
 * 
 * COBOL equivalent: COTRN2AI input map fields from COTRN02C.cbl
 * All fields validated in VALIDATE-INPUT-DATA-FIELDS paragraph
 */
export type TransactionCreateRequest = Omit<
  Transaction,
  'transId' | 'createdAt' | 'transProcTs'
>;

/**
 * Get Transactions with Filtering and Pagination
 * 
 * Converted from: COTRN00C.cbl PROCESS-PAGE-FORWARD paragraph
 * COBOL flow:
 * 1. STARTBR-TRANSACT-FILE: Position browse cursor at starting key
 * 2. READNEXT-TRANSACT-FILE: Read 10 records sequentially
 * 3. POPULATE-TRAN-DATA: Format each record for screen display
 * 4. Check TRANSACT-EOF flag for NEXT-PAGE-FLG
 * 
 * @param params - Query parameters for filtering and pagination
 * @returns Promise with transaction list and pagination metadata
 * 
 * @throws ApiError if request fails
 * 
 * @example
 * ```typescript
 * // Get first page of transactions
 * const result = await getTransactions({ page: 1, pageSize: 10 });
 * console.log(result.transactions); // Array of 10 transactions
 * console.log(result.pagination.totalPages); // Total pages available
 * 
 * // Filter by date range
 * const filtered = await getTransactions({
 *   startDate: '2025-01-01',
 *   endDate: '2025-01-31',
 *   page: 1
 * });
 * 
 * // Filter by card number
 * const cardTransactions = await getTransactions({
 *   cardNum: '4000123456789010'
 * });
 * ```
 */
export async function getTransactions(
  params: TransactionQueryParams = {}
): Promise<TransactionListResponse> {
  try {
    // Default pagination values matching COBOL screen layout
    // COBOL: 10 records per screen (fixed), page 1 by default
    const queryParams: Record<string, string | number> = {
      page: params.page || 1,
      size: params.pageSize || 10, // COBOL displays 10 rows (TRNID01-TRNID10)
      sort: params.sortBy || 'transOrigTs', // Default sort by transaction date
      direction: params.sortDirection || 'desc', // COBOL READPREV for recent first
    };

    // Add optional filters if provided
    // COBOL equivalent: TRNIDINI field for starting transaction ID filter
    if (params.transId) {
      queryParams['transId'] = params.transId;
    }

    // COBOL equivalent: Filter by TRAN-CARD-NUM field
    if (params.cardNum) {
      queryParams['cardNum'] = params.cardNum;
    }

    // COBOL equivalent: Date range filtering on TRAN-ORIG-TS field
    // Format must be YYYY-MM-DD to match backend LocalDate parsing
    if (params.startDate) {
      queryParams['startDate'] = params.startDate;
    }

    if (params.endDate) {
      queryParams['endDate'] = params.endDate;
    }

    // Make API request
    // Spring Boot backend: TransactionController.getTransactions()
    // Returns paginated transaction list with metadata
    const response = await api.get<{
      content: Transaction[];
      page: number;
      size: number;
      totalElements: number;
      totalPages: number;
    }>('/transactions', { params: queryParams });

    // Transform Spring Boot Page response to our interface
    // COBOL equivalent: Building screen output structure (COTRN0AO)
    const result: TransactionListResponse = {
      transactions: response.data.content || [],
      pagination: {
        page: response.data.page + 1, // Spring Boot uses 0-based, convert to 1-based
        pageSize: response.data.size,
        totalItems: response.data.totalElements,
        totalPages: response.data.totalPages,
      },
    };

    return result;
  } catch (error) {
    // COBOL error handling equivalent: ERR-FLG-ON, WS-RESP-CD checks
    // Errors re-thrown with ApiError structure from api.ts interceptor
    throw error;
  }
}

/**
 * Get Transaction by ID
 * 
 * Converted from: COTRN01C.cbl READ-TRANSACT-FILE paragraph
 * COBOL flow:
 * 1. MOVE TRNIDINI to TRAN-ID (key field)
 * 2. EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)
 * 3. Handle DFHRESP(NORMAL), DFHRESP(NOTFND), or other errors
 * 4. POPULATE screen fields (TRNIDI, CARDNUMI, TTYPCDI, etc.)
 * 
 * @param transId - Transaction ID (16-character string)
 * @returns Promise with transaction details
 * 
 * @throws ApiError with status 404 if transaction not found
 * @throws ApiError with status 400 if transId is invalid
 * 
 * @example
 * ```typescript
 * try {
 *   const transaction = await getTransactionById('0000000000000123');
 *   console.log(transaction.transDesc); // "Amazon Purchase"
 *   console.log(transaction.transAmt);  // 49.99
 * } catch (error) {
 *   if (error.status === 404) {
 *     console.error('Transaction not found');
 *   }
 * }
 * ```
 */
export async function getTransactionById(transId: string): Promise<Transaction> {
  try {
    // Validation: Transaction ID cannot be empty
    // COBOL equivalent: "WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES"
    if (!transId || transId.trim() === '') {
      throw {
        status: 400,
        message: 'Tran ID can NOT be empty...',
        errors: ['Transaction ID is required'],
      };
    }

    // Validation: Transaction ID should be 16 characters
    // COBOL: TRAN-ID PIC X(16)
    if (transId.length !== 16) {
      throw {
        status: 400,
        message: 'Tran ID must be 16 characters...',
        errors: ['Transaction ID must be exactly 16 characters'],
      };
    }

    // Make API request
    // Spring Boot backend: TransactionController.getTransactionById()
    // COBOL equivalent: EXEC CICS READ DATASET('TRANSACT')
    const response = await api.get<Transaction>(`/transactions/${transId}`);

    // COBOL equivalent: Transaction found (DFHRESP(NORMAL))
    // Fields populated: TRAN-ID, TRAN-CARD-NUM, TRAN-TYPE-CD, etc.
    return response.data;
  } catch (error: any) {
    // COBOL error handling equivalent:
    // - DFHRESP(NOTFND): "Transaction ID NOT found..."
    // - Other errors: "Unable to lookup Transaction..."
    
    // If it's already an ApiError from api.ts interceptor, re-throw as-is
    if (error.status) {
      throw error;
    }

    // Otherwise wrap in generic error
    throw {
      status: 500,
      message: 'Unable to lookup Transaction...',
      errors: [error.message || 'Unknown error occurred'],
    };
  }
}

/**
 * Create New Transaction
 * 
 * Converted from: COTRN02C.cbl ADD-TRANSACTION paragraph
 * COBOL flow:
 * 1. VALIDATE-INPUT-KEY-FIELDS: Account ID or Card Number validation
 * 2. VALIDATE-INPUT-DATA-FIELDS: Type, category, amount, dates, merchant validation
 * 3. Generate new TRAN-ID: READPREV highest ID, ADD 1
 * 4. INITIALIZE TRAN-RECORD and populate all fields
 * 5. WRITE-TRANSACT-FILE: EXEC CICS WRITE FILE('TRANSACT')
 * 
 * Validation Rules (from COBOL):
 * - Card number must be numeric (16 digits)
 * - Transaction type code required and must be numeric
 * - Transaction category code required and must be numeric
 * - Transaction source required
 * - Transaction description required
 * - Amount required and must be in format +/-99999999.99
 * - Original date required in format YYYY-MM-DD
 * - Process date required in format YYYY-MM-DD
 * - Merchant ID required and must be numeric
 * - Merchant name, city, zip required
 * 
 * @param transactionData - Transaction data (excludes transId, createdAt, transProcTs)
 * @returns Promise with created transaction including generated ID
 * 
 * @throws ApiError with status 400 for validation errors
 * @throws ApiError with status 404 if card not found
 * 
 * @example
 * ```typescript
 * const newTransaction: TransactionCreateRequest = {
 *   transCardNum: '4000123456789010',
 *   transTypeCd: '01', // Purchase
 *   transCatCd: 5411, // Groceries
 *   transSource: 'POS',
 *   transDesc: 'Whole Foods Market',
 *   transAmt: 87.53, // Maintains 2 decimal precision
 *   transMerchantId: '123456789',
 *   transMerchantName: 'Whole Foods Market',
 *   transMerchantCity: 'Seattle',
 *   transMerchantZip: '98101',
 *   transOrigTs: '2025-10-26T14:30:00Z',
 * };
 * 
 * try {
 *   const created = await createTransaction(newTransaction);
 *   console.log('Transaction ID:', created.transId); // System-generated ID
 *   console.log('Created at:', created.createdAt);
 * } catch (error) {
 *   console.error('Validation error:', error.message);
 * }
 * ```
 */
export async function createTransaction(
  transactionData: TransactionCreateRequest
): Promise<Transaction> {
  try {
    // Client-side validation matching COBOL VALIDATE-INPUT-KEY-FIELDS
    // and VALIDATE-INPUT-DATA-FIELDS paragraphs

    // Validate card number
    // COBOL: "WHEN CARDNINI OF COTRN2AI NOT = SPACES AND LOW-VALUES"
    if (!transactionData.transCardNum || transactionData.transCardNum.trim() === '') {
      throw {
        status: 400,
        message: 'Card Number must be entered...',
        errors: ['Card number is required'],
      };
    }

    // COBOL: "IF CARDNINI OF COTRN2AI IS NOT NUMERIC"
    if (!/^\d+$/.test(transactionData.transCardNum)) {
      throw {
        status: 400,
        message: 'Card Number must be Numeric...',
        errors: ['Card number must contain only digits'],
      };
    }

    // COBOL: Card number should be 16 digits (PIC X(16))
    if (transactionData.transCardNum.length !== 16) {
      throw {
        status: 400,
        message: 'Card Number must be 16 digits...',
        errors: ['Card number must be exactly 16 digits'],
      };
    }

    // Validate transaction type code
    // COBOL: "WHEN TTYPCDI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (!transactionData.transTypeCd || transactionData.transTypeCd.trim() === '') {
      throw {
        status: 400,
        message: 'Type CD can NOT be empty...',
        errors: ['Transaction type code is required'],
      };
    }

    // COBOL: "WHEN TTYPCDI OF COTRN2AI NOT NUMERIC"
    if (!/^\d+$/.test(transactionData.transTypeCd)) {
      throw {
        status: 400,
        message: 'Type CD must be Numeric...',
        errors: ['Transaction type code must be numeric'],
      };
    }

    // Validate transaction category code
    // COBOL: "WHEN TCATCDI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (
      transactionData.transCatCd === undefined ||
      transactionData.transCatCd === null
    ) {
      throw {
        status: 400,
        message: 'Category CD can NOT be empty...',
        errors: ['Transaction category code is required'],
      };
    }

    // COBOL: "WHEN TCATCDI OF COTRN2AI NOT NUMERIC"
    if (!Number.isInteger(transactionData.transCatCd) || transactionData.transCatCd < 0) {
      throw {
        status: 400,
        message: 'Category CD must be Numeric...',
        errors: ['Transaction category code must be a positive integer'],
      };
    }

    // Validate transaction source
    // COBOL: "WHEN TRNSRCI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (!transactionData.transSource || transactionData.transSource.trim() === '') {
      throw {
        status: 400,
        message: 'Source can NOT be empty...',
        errors: ['Transaction source is required'],
      };
    }

    // Validate transaction description
    // COBOL: "WHEN TDESCI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (!transactionData.transDesc || transactionData.transDesc.trim() === '') {
      throw {
        status: 400,
        message: 'Description can NOT be empty...',
        errors: ['Transaction description is required'],
      };
    }

    // Validate transaction amount
    // COBOL: "WHEN TRNAMTI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (
      transactionData.transAmt === undefined ||
      transactionData.transAmt === null
    ) {
      throw {
        status: 400,
        message: 'Amount can NOT be empty...',
        errors: ['Transaction amount is required'],
      };
    }

    // COBOL: Amount format validation +/-99999999.99
    // Validate numeric range and precision
    if (typeof transactionData.transAmt !== 'number' || isNaN(transactionData.transAmt)) {
      throw {
        status: 400,
        message: 'Amount should be in format -99999999.99',
        errors: ['Transaction amount must be a valid number'],
      };
    }

    // Check COBOL range: PIC S9(09)V99 means -999999999.99 to 999999999.99
    if (transactionData.transAmt < -999999999.99 || transactionData.transAmt > 999999999.99) {
      throw {
        status: 400,
        message: 'Amount exceeds maximum allowed value',
        errors: ['Transaction amount must be between -999999999.99 and 999999999.99'],
      };
    }

    // Validate 2 decimal places (COBOL COMP-3 precision)
    const amountStr = transactionData.transAmt.toFixed(2);
    if (transactionData.transAmt.toString() !== amountStr && 
        Math.abs(transactionData.transAmt - parseFloat(amountStr)) > 0.001) {
      throw {
        status: 400,
        message: 'Amount must have at most 2 decimal places',
        errors: ['Transaction amount must have exactly 2 decimal places'],
      };
    }

    // Validate original timestamp
    // COBOL: "WHEN TORIGDTI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (!transactionData.transOrigTs || transactionData.transOrigTs.trim() === '') {
      throw {
        status: 400,
        message: 'Orig Date can NOT be empty...',
        errors: ['Original transaction timestamp is required'],
      };
    }

    // COBOL: Date format validation YYYY-MM-DD
    // Accept both YYYY-MM-DD and ISO 8601 full timestamp formats
    const datePattern = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}:\d{2}(\.\d{3})?Z?)?$/;
    if (!datePattern.test(transactionData.transOrigTs)) {
      throw {
        status: 400,
        message: 'Orig Date should be in format YYYY-MM-DD or ISO 8601',
        errors: ['Original timestamp must be in YYYY-MM-DD or ISO 8601 format'],
      };
    }

    // Validate date is valid (not just format but actual valid date)
    const origDate = new Date(transactionData.transOrigTs);
    if (isNaN(origDate.getTime())) {
      throw {
        status: 400,
        message: 'Orig Date - Not a valid date...',
        errors: ['Original timestamp is not a valid date'],
      };
    }

    // Validate merchant ID
    // COBOL: "WHEN MIDI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (!transactionData.transMerchantId || transactionData.transMerchantId.trim() === '') {
      throw {
        status: 400,
        message: 'Merchant ID can NOT be empty...',
        errors: ['Merchant ID is required'],
      };
    }

    // COBOL: "IF MIDI OF COTRN2AI IS NOT NUMERIC"
    if (!/^\d+$/.test(transactionData.transMerchantId)) {
      throw {
        status: 400,
        message: 'Merchant ID must be Numeric...',
        errors: ['Merchant ID must contain only digits'],
      };
    }

    // Validate merchant name
    // COBOL: "WHEN MNAMEI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (
      !transactionData.transMerchantName ||
      transactionData.transMerchantName.trim() === ''
    ) {
      throw {
        status: 400,
        message: 'Merchant Name can NOT be empty...',
        errors: ['Merchant name is required'],
      };
    }

    // Validate merchant city
    // COBOL: "WHEN MCITYI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (
      !transactionData.transMerchantCity ||
      transactionData.transMerchantCity.trim() === ''
    ) {
      throw {
        status: 400,
        message: 'Merchant City can NOT be empty...',
        errors: ['Merchant city is required'],
      };
    }

    // Validate merchant ZIP
    // COBOL: "WHEN MZIPI OF COTRN2AI = SPACES OR LOW-VALUES"
    if (
      !transactionData.transMerchantZip ||
      transactionData.transMerchantZip.trim() === ''
    ) {
      throw {
        status: 400,
        message: 'Merchant Zip can NOT be empty...',
        errors: ['Merchant ZIP code is required'],
      };
    }

    // All validations passed, make API request
    // Spring Boot backend: TransactionController.createTransaction()
    // COBOL equivalent: WRITE-TRANSACT-FILE (EXEC CICS WRITE FILE('TRANSACT'))
    const response = await api.post<Transaction>('/transactions', transactionData);

    // COBOL equivalent: Transaction written successfully
    // Backend generates TRAN-ID (sequential ID generation)
    // Backend sets TRAN-PROC-TS (processing timestamp)
    return response.data;
  } catch (error: any) {
    // COBOL error handling equivalent:
    // - Validation errors: Set ERR-FLG-ON, move message to WS-MESSAGE
    // - VSAM errors: Handle file-status codes
    // - CICS errors: Handle RESP/RESP2 codes
    
    // If it's already an ApiError from our validation or api.ts, re-throw as-is
    if (error.status) {
      throw error;
    }

    // Otherwise wrap in generic error
    throw {
      status: 500,
      message: 'Unable to create transaction...',
      errors: [error.message || 'Unknown error occurred'],
    };
  }
}

/**
 * Transaction Service Object
 * 
 * Provides all transaction management operations as a single export
 * Matches the service layer pattern from COBOL program structure:
 * - COTRN00C: List/browse transactions
 * - COTRN01C: View transaction details
 * - COTRN02C: Create new transaction
 * 
 * This is the default export for easy import:
 * import transactionService from './transactionService';
 * 
 * @example
 * ```typescript
 * import transactionService from './transactionService';
 * 
 * // List transactions
 * const list = await transactionService.getTransactions({ page: 1 });
 * 
 * // Get detail
 * const detail = await transactionService.getTransactionById('0000000000000123');
 * 
 * // Create new
 * const created = await transactionService.createTransaction(newTransactionData);
 * ```
 */
const transactionService = {
  /**
   * Get paginated list of transactions with filtering
   * 
   * @param params - Query parameters for filtering and pagination
   * @returns Promise with transaction list and pagination metadata
   */
  getTransactions,

  /**
   * Get single transaction by ID
   * 
   * @param transId - Transaction ID (16-character string)
   * @returns Promise with transaction details
   */
  getTransactionById,

  /**
   * Create new transaction
   * 
   * @param transactionData - Transaction data (excludes transId, createdAt, transProcTs)
   * @returns Promise with created transaction including generated ID
   */
  createTransaction,
};

/**
 * Default export: Transaction service object
 * Named exports also available for tree-shaking optimization
 */
export default transactionService;
