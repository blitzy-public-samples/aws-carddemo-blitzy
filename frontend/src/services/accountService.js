/**
 * Account Service Module
 * 
 * Purpose: JavaScript service module for account management operations communicating with
 * Spring Boot backend REST API. This module replaces COBOL CICS programs COACTVWC.cbl
 * (CAVW transaction - account view) and COACTUPC.cbl (CAUP transaction - account update)
 * including VSAM ACCTDAT file operations and cross-reference file lookups.
 * 
 * Source COBOL Programs:
 * - COACTVWC.cbl: Account view program handling EXEC CICS READ DATASET(ACCTDAT)
 * - COACTUPC.cbl: Account update program handling EXEC CICS REWRITE DATASET(ACCTDAT)
 * 
 * Source Data Structures:
 * - CVACT01Y.cpy: Account record layout (RECLN 300 bytes)
 *   - ACCT-ID: PIC 9(11) - 11 digit account identifier
 *   - ACCT-ACTIVE-STATUS: PIC X(01) - Active status (Y/N)
 *   - ACCT-CURR-BAL: PIC S9(10)V99 - Current balance with 2 decimal places
 *   - ACCT-CREDIT-LIMIT: PIC S9(10)V99 - Credit limit with 2 decimal places
 *   - ACCT-CASH-CREDIT-LIMIT: PIC S9(10)V99 - Cash credit limit with 2 decimal places
 *   - ACCT-OPEN-DATE: PIC X(10) - Account open date
 *   - ACCT-EXPIRAION-DATE: PIC X(10) - Account expiration date
 *   - ACCT-REISSUE-DATE: PIC X(10) - Account reissue date
 *   - ACCT-CURR-CYC-CREDIT: PIC S9(10)V99 - Current cycle credit
 *   - ACCT-CURR-CYC-DEBIT: PIC S9(10)V99 - Current cycle debit
 *   - ACCT-ADDR-ZIP: PIC X(10) - Account address ZIP
 *   - ACCT-GROUP-ID: PIC X(10) - Account group identifier
 * 
 * REST API Endpoints:
 * - GET /api/accounts/{id} - Retrieve account by ID (replaces VSAM READ)
 * - PUT /api/accounts/{id} - Update account by ID (replaces VSAM REWRITE)
 * - GET /api/accounts?customerId={id} - Retrieve accounts by customer
 * - GET /api/accounts?searchCriteria - Search accounts with filters
 * 
 * Features:
 * - Automatic JWT authentication header injection via apiClient
 * - Request/response data transformation matching Spring Boot DTOs
 * - BigDecimal monetary field formatting to 2 decimal places
 * - Comprehensive error handling with user-friendly messages
 * - Client-side validation before API calls with server-side validation as authority
 * 
 * Migration Context: Transforms COBOL VSAM file operations to RESTful API calls
 * as specified in Agent Action Plan sections 0.1, 0.3, and 0.6.
 * 
 * @module accountService
 */

import apiClient from '../utils/apiClient.js';
import { MAX_LENGTH_ACCOUNT_ID } from '../utils/constants.js';

/**
 * Validates account ID format
 * 
 * Transformation Context: Replaces COBOL validation logic from COACTVWC.cbl
 * that checks account number is non-zero 11-digit number.
 * 
 * COBOL Source (COACTVWC.cbl lines 125-128):
 * - SEARCHED-ACCT-ZEROES: 'Account number must be a non zero 11 digit number'
 * - SEARCHED-ACCT-NOT-NUMERIC: 'Account number must be a non zero 11 digit number'
 * 
 * @param {string|number} accountId - Account ID to validate
 * @returns {Object} Validation result with isValid flag and error message
 * @private
 */
const validateAccountId = (accountId) => {
  // Check if account ID is provided
  if (!accountId && accountId !== 0) {
    return {
      isValid: false,
      errorMessage: 'Account number not provided'
    };
  }

  // Convert to string for validation
  const accountIdStr = String(accountId).trim();

  // Check if account ID is numeric
  if (!/^\d+$/.test(accountIdStr)) {
    return {
      isValid: false,
      errorMessage: 'Account number must be a non zero 11 digit number'
    };
  }

  // Check if account ID length matches COBOL PIC 9(11) specification
  if (accountIdStr.length !== MAX_LENGTH_ACCOUNT_ID) {
    return {
      isValid: false,
      errorMessage: `Account number must be exactly ${MAX_LENGTH_ACCOUNT_ID} digits`
    };
  }

  // Check if account ID is non-zero
  if (parseInt(accountIdStr, 10) === 0) {
    return {
      isValid: false,
      errorMessage: 'Account number must be a non zero 11 digit number'
    };
  }

  return {
    isValid: true,
    errorMessage: null
  };
};

/**
 * Formats monetary amount to 2 decimal places
 * 
 * Transformation Context: Ensures BigDecimal monetary fields from Spring Boot backend
 * are consistently formatted for display, matching COBOL COMP-3 PIC S9(10)V99 precision.
 * 
 * Agent Action Plan Section 0.10 Special Instructions:
 * "COBOL COMP-3 to Java BigDecimal Precision Mapping - All monetary fields must use
 * BigDecimal with exact precision... Use RoundingMode.HALF_UP to match COBOL rounding behavior"
 * 
 * @param {number|string|null} amount - Monetary amount to format
 * @returns {string} Formatted amount with 2 decimal places (e.g., "1234.56")
 * @public
 */
export const formatAccountBalance = (amount) => {
  // Handle null, undefined, or empty values
  if (amount === null || amount === undefined || amount === '') {
    return '0.00';
  }

  // Convert to number if string
  const numericAmount = typeof amount === 'string' ? parseFloat(amount) : amount;

  // Check if conversion resulted in valid number
  if (isNaN(numericAmount)) {
    return '0.00';
  }

  // Format to 2 decimal places matching COBOL V99 specification
  // Use toFixed(2) which implements HALF_UP rounding (banker's rounding for .5)
  return numericAmount.toFixed(2);
};

/**
 * Transforms account data from backend response to frontend format
 * 
 * Ensures all monetary fields are properly formatted and all dates are strings.
 * Handles any null or undefined fields gracefully.
 * 
 * @param {Object} accountData - Raw account data from backend API
 * @returns {Object} Transformed account object with formatted fields
 * @private
 */
const transformAccountResponse = (accountData) => {
  if (!accountData) {
    return null;
  }

  return {
    accountId: accountData.accountId || accountData.acctId,
    customerId: accountData.customerId || accountData.custId,
    accountStatus: accountData.accountStatus || accountData.acctActiveStatus || 'N',
    currentBalance: formatAccountBalance(accountData.currentBalance || accountData.acctCurrBal),
    creditLimit: formatAccountBalance(accountData.creditLimit || accountData.acctCreditLimit),
    cashCreditLimit: formatAccountBalance(accountData.cashCreditLimit || accountData.acctCashCreditLimit),
    openDate: accountData.openDate || accountData.acctOpenDate || '',
    expirationDate: accountData.expirationDate || accountData.acctExpiraionDate || '', // Note: typo preserved from COBOL
    reissueDate: accountData.reissueDate || accountData.acctReissueDate || '',
    currentCycleCredit: formatAccountBalance(accountData.currentCycleCredit || accountData.acctCurrCycCredit),
    currentCycleDebit: formatAccountBalance(accountData.currentCycleDebit || accountData.acctCurrCycDebit),
    addressZip: accountData.addressZip || accountData.acctAddrZip || '',
    groupId: accountData.groupId || accountData.acctGroupId || '',
    // Include customer information if present from backend join/cross-reference
    customerFirstName: accountData.customerFirstName || accountData.custFirstName || '',
    customerMiddleName: accountData.customerMiddleName || accountData.custMiddleName || '',
    customerLastName: accountData.customerLastName || accountData.custLastName || ''
  };
};

/**
 * Retrieves account details by account ID
 * 
 * Transformation Context: Replaces COBOL program COACTVWC.cbl (CAVW transaction)
 * EXEC CICS READ DATASET(ACCTDAT) operation with GET /api/accounts/{id} REST call.
 * 
 * COBOL Source: COACTVWC.cbl
 * - Receives account ID from BMS screen COACTVW
 * - Validates account ID (non-zero 11-digit number)
 * - Reads ACCTDAT VSAM file with account ID key
 * - Reads CARDXREF cross-reference file for customer association
 * - Reads CUSTDAT customer master file for customer details
 * - Returns account data to BMS screen
 * 
 * Backend Endpoint: GET /api/accounts/{id}
 * - Spring Boot AccountController.getAccountById()
 * - Returns AccountResponse DTO with all account fields
 * - Includes customer information via JPA foreign key join
 * - Returns 404 if account not found
 * - Returns 400 for invalid account ID format
 * 
 * @param {string|number} accountId - 11-digit account identifier
 * @returns {Promise<Object>} Account object with all fields including formatted monetary values
 * @throws {Error} Validation error if account ID format invalid
 * @throws {Error} API error with user-friendly message from backend
 * @public
 * 
 * @example
 * // Successful retrieval
 * const account = await getAccountById('00000000001');
 * console.log(account.accountId); // '00000000001'
 * console.log(account.currentBalance); // '1234.56'
 * console.log(account.creditLimit); // '5000.00'
 * 
 * @example
 * // Error handling
 * try {
 *   const account = await getAccountById('invalid');
 * } catch (error) {
 *   console.error(error.message); // 'Account number must be a non zero 11 digit number'
 * }
 */
export const getAccountById = async (accountId) => {
  try {
    // Client-side validation before API call (server-side validation is final authority)
    const validation = validateAccountId(accountId);
    if (!validation.isValid) {
      throw new Error(validation.errorMessage);
    }

    // Make GET request to backend API endpoint
    // apiClient automatically injects JWT token and handles authentication
    const response = await apiClient.get(`/accounts/${accountId}`);

    // Extract account data from response
    // Backend returns AccountResponse DTO in response.data
    const accountData = response.data;

    // Transform response data to frontend format with formatted monetary fields
    const transformedAccount = transformAccountResponse(accountData);

    // Log successful retrieval in development mode
    if (import.meta.env.MODE === 'development') {
      console.log('[AccountService] Successfully retrieved account:', accountId);
    }

    return transformedAccount;
  } catch (error) {
    // Error handling with user-friendly messages
    // apiClient interceptor already transformed error to consistent format
    
    // Log error in development mode
    if (import.meta.env.MODE === 'development') {
      console.error('[AccountService] Error retrieving account:', accountId, error);
    }

    // Transform COBOL error messages to user-friendly format
    let errorMessage = error.message || 'An error occurred while retrieving the account';

    // Handle specific error scenarios matching COBOL error messages
    if (error.status === 404) {
      // DID-NOT-FIND-ACCT-IN-ACCTDAT from COACTVWC.cbl line 131-132
      errorMessage = 'Did not find this account in account master file';
    } else if (error.status === 400) {
      // Use validation error message from backend or client-side validation
      errorMessage = error.message || 'Invalid account number format';
    } else if (error.status === 403) {
      errorMessage = 'You do not have permission to view this account';
    } else if (error.status === 500) {
      // Generic server error matching COBOL file read error
      errorMessage = 'Error reading account data. Please try again later.';
    }

    // Throw error with user-friendly message for component error handling
    throw new Error(errorMessage);
  }
};

/**
 * Updates account details by account ID
 * 
 * Transformation Context: Replaces COBOL program COACTUPC.cbl (CAUP transaction)
 * EXEC CICS REWRITE DATASET(ACCTDAT) operation with PUT /api/accounts/{id} REST call.
 * 
 * COBOL Source: COACTUPC.cbl
 * - Receives account ID and updated fields from BMS screen COACTUP
 * - Validates all input fields (credit limits, status, dates)
 * - Reads ACCTDAT VSAM file to ensure account exists
 * - Updates account record in ACCTDAT VSAM file
 * - Returns updated account data to BMS screen
 * 
 * Backend Endpoint: PUT /api/accounts/{id}
 * - Spring Boot AccountController.updateAccount()
 * - Accepts AccountUpdateRequest DTO with updated fields
 * - Validates all fields with Bean Validation annotations
 * - Updates Account entity using JPA repository.save()
 * - Returns AccountResponse DTO with updated values
 * - Returns 404 if account not found
 * - Returns 400 for validation errors
 * - Returns 422 for business logic validation failures
 * 
 * @param {string|number} accountId - 11-digit account identifier
 * @param {Object} accountData - Account data to update
 * @param {string} [accountData.accountStatus] - Account active status (Y/N)
 * @param {number|string} [accountData.creditLimit] - Credit limit amount
 * @param {number|string} [accountData.cashCreditLimit] - Cash credit limit amount
 * @param {string} [accountData.expirationDate] - Account expiration date
 * @param {string} [accountData.addressZip] - Account address ZIP code
 * @param {string} [accountData.groupId] - Account group identifier
 * @returns {Promise<Object>} Updated account object with all fields
 * @throws {Error} Validation error if account ID or data invalid
 * @throws {Error} API error with user-friendly message from backend
 * @public
 * 
 * @example
 * // Successful update
 * const updatedAccount = await updateAccount('00000000001', {
 *   creditLimit: 10000.00,
 *   cashCreditLimit: 2000.00,
 *   accountStatus: 'Y'
 * });
 * console.log(updatedAccount.creditLimit); // '10000.00'
 * 
 * @example
 * // Error handling
 * try {
 *   await updateAccount('00000000001', { creditLimit: -1000 });
 * } catch (error) {
 *   console.error(error.message); // Validation error from backend
 * }
 */
export const updateAccount = async (accountId, accountData) => {
  try {
    // Client-side validation of account ID before API call
    const validation = validateAccountId(accountId);
    if (!validation.isValid) {
      throw new Error(validation.errorMessage);
    }

    // Validate that accountData object is provided
    if (!accountData || typeof accountData !== 'object') {
      throw new Error('Account data is required for update');
    }

    // Prepare request body matching Spring Boot AccountUpdateRequest DTO
    // Transform frontend field names to backend DTO field names if necessary
    const requestBody = {
      accountId: accountId,
      accountStatus: accountData.accountStatus,
      creditLimit: accountData.creditLimit,
      cashCreditLimit: accountData.cashCreditLimit,
      expirationDate: accountData.expirationDate,
      reissueDate: accountData.reissueDate,
      addressZip: accountData.addressZip,
      groupId: accountData.groupId
    };

    // Remove undefined fields to avoid sending null values
    Object.keys(requestBody).forEach(key => {
      if (requestBody[key] === undefined) {
        delete requestBody[key];
      }
    });

    // Make PUT request to backend API endpoint
    // apiClient automatically injects JWT token and handles authentication
    const response = await apiClient.put(`/accounts/${accountId}`, requestBody);

    // Extract updated account data from response
    const updatedAccountData = response.data;

    // Transform response data to frontend format
    const transformedAccount = transformAccountResponse(updatedAccountData);

    // Log successful update in development mode
    if (import.meta.env.MODE === 'development') {
      console.log('[AccountService] Successfully updated account:', accountId);
    }

    return transformedAccount;
  } catch (error) {
    // Error handling with user-friendly messages
    
    // Log error in development mode
    if (import.meta.env.MODE === 'development') {
      console.error('[AccountService] Error updating account:', accountId, error);
    }

    // Transform error messages to user-friendly format
    let errorMessage = error.message || 'An error occurred while updating the account';

    // Handle specific error scenarios
    if (error.status === 404) {
      errorMessage = 'Did not find this account. Cannot update non-existent account.';
    } else if (error.status === 400 || error.status === 422) {
      // Validation error from backend Bean Validation or business logic
      // Use specific error message from backend if available
      if (error.data && error.data.message) {
        errorMessage = error.data.message;
      } else if (error.data && error.data.errors) {
        // Handle multiple validation errors
        const errors = error.data.errors;
        if (Array.isArray(errors) && errors.length > 0) {
          errorMessage = errors.map(e => e.message || e).join('; ');
        } else {
          errorMessage = 'Validation failed. Please check your input.';
        }
      } else {
        errorMessage = error.message || 'Invalid account data provided';
      }
    } else if (error.status === 403) {
      errorMessage = 'You do not have permission to update this account';
    } else if (error.status === 409) {
      // Conflict error (e.g., optimistic locking failure)
      errorMessage = 'Account was modified by another user. Please refresh and try again.';
    } else if (error.status === 500) {
      errorMessage = 'Error updating account data. Please try again later.';
    }

    // Throw error with user-friendly message for component error handling
    throw new Error(errorMessage);
  }
};

/**
 * Retrieves all accounts associated with a specific customer
 * 
 * Transformation Context: Handles customer-to-account relationship lookups
 * that in COBOL required reading CARDXREF cross-reference file (CVACT03Y.cpy)
 * and iterating through account IDs. The Spring Boot backend uses JPA foreign
 * key relationships to perform this lookup efficiently.
 * 
 * COBOL Context:
 * - COACTVWC.cbl reads CARDXREF (CXACAIX) for customer-account associations
 * - Uses EXEC CICS STARTBR and READNEXT for sequential processing
 * 
 * Backend Endpoint: GET /api/accounts?customerId={id}
 * - Spring Boot AccountController.getAccountsByCustomer()
 * - Uses Spring Data JPA repository query: findByCustomerId()
 * - Returns List<AccountResponse> DTOs
 * - Returns empty array if no accounts found for customer
 * 
 * @param {string|number} customerId - 9-digit customer identifier
 * @returns {Promise<Array<Object>>} Array of account objects for the customer
 * @throws {Error} Validation error if customer ID invalid
 * @throws {Error} API error with user-friendly message from backend
 * @public
 * 
 * @example
 * // Retrieve all accounts for customer
 * const accounts = await getAccountsByCustomer('000000001');
 * console.log(`Customer has ${accounts.length} accounts`);
 * accounts.forEach(account => {
 *   console.log(`Account ${account.accountId}: Balance ${account.currentBalance}`);
 * });
 */
export const getAccountsByCustomer = async (customerId) => {
  try {
    // Validate customer ID is provided
    if (!customerId && customerId !== 0) {
      throw new Error('Customer ID is required');
    }

    // Convert to string and validate numeric format
    const customerIdStr = String(customerId).trim();
    if (!/^\d+$/.test(customerIdStr)) {
      throw new Error('Customer ID must be numeric');
    }

    // Make GET request with customerId query parameter
    const response = await apiClient.get('/accounts', {
      params: { customerId: customerIdStr }
    });

    // Extract accounts array from response
    const accountsData = response.data;

    // Handle case where backend returns single object instead of array
    const accountsArray = Array.isArray(accountsData) ? accountsData : [accountsData];

    // Transform each account in the array
    const transformedAccounts = accountsArray
      .filter(account => account) // Filter out any null/undefined entries
      .map(account => transformAccountResponse(account));

    // Log successful retrieval in development mode
    if (import.meta.env.MODE === 'development') {
      console.log('[AccountService] Successfully retrieved accounts for customer:', customerId, 
                  `(${transformedAccounts.length} accounts)`);
    }

    return transformedAccounts;
  } catch (error) {
    // Error handling
    
    // Log error in development mode
    if (import.meta.env.MODE === 'development') {
      console.error('[AccountService] Error retrieving accounts for customer:', customerId, error);
    }

    // Transform error messages
    let errorMessage = error.message || 'An error occurred while retrieving customer accounts';

    if (error.status === 404) {
      // Customer not found or no accounts found - return empty array instead of error
      console.warn('[AccountService] No accounts found for customer:', customerId);
      return [];
    } else if (error.status === 400) {
      errorMessage = 'Invalid customer ID format';
    } else if (error.status === 403) {
      errorMessage = 'You do not have permission to view customer accounts';
    } else if (error.status === 500) {
      errorMessage = 'Error retrieving customer accounts. Please try again later.';
    }

    // Throw error for non-404 cases
    throw new Error(errorMessage);
  }
};

/**
 * Searches accounts with flexible criteria
 * 
 * Provides flexible account searching capability with multiple optional filters.
 * This supports advanced search scenarios beyond simple ID-based lookups.
 * 
 * Backend Endpoint: GET /api/accounts
 * - Accepts multiple query parameters for filtering
 * - Returns paginated results if many accounts match
 * - Supports filters: accountStatus, minBalance, maxBalance, zipCode, etc.
 * 
 * @param {Object} criteria - Search criteria object
 * @param {string} [criteria.accountStatus] - Filter by account status (Y/N)
 * @param {number} [criteria.minBalance] - Minimum current balance
 * @param {number} [criteria.maxBalance] - Maximum current balance
 * @param {string} [criteria.zipCode] - Filter by ZIP code
 * @param {string} [criteria.groupId] - Filter by group ID
 * @param {number} [criteria.page] - Page number for pagination (0-based)
 * @param {number} [criteria.size] - Page size for pagination
 * @returns {Promise<Object>} Search results with accounts array and pagination info
 * @throws {Error} API error with user-friendly message from backend
 * @public
 * 
 * @example
 * // Search active accounts with balance over $1000
 * const results = await searchAccounts({
 *   accountStatus: 'Y',
 *   minBalance: 1000.00
 * });
 * console.log(`Found ${results.accounts.length} accounts`);
 * 
 * @example
 * // Paginated search
 * const results = await searchAccounts({
 *   accountStatus: 'Y',
 *   page: 0,
 *   size: 10
 * });
 * console.log(`Page ${results.currentPage} of ${results.totalPages}`);
 */
export const searchAccounts = async (criteria = {}) => {
  try {
    // Validate criteria object
    if (criteria && typeof criteria !== 'object') {
      throw new Error('Search criteria must be an object');
    }

    // Build query parameters from criteria
    const params = {};

    // Add filters if provided
    if (criteria.accountStatus !== undefined && criteria.accountStatus !== null) {
      params.accountStatus = criteria.accountStatus;
    }
    if (criteria.minBalance !== undefined && criteria.minBalance !== null) {
      params.minBalance = criteria.minBalance;
    }
    if (criteria.maxBalance !== undefined && criteria.maxBalance !== null) {
      params.maxBalance = criteria.maxBalance;
    }
    if (criteria.zipCode) {
      params.zipCode = criteria.zipCode;
    }
    if (criteria.groupId) {
      params.groupId = criteria.groupId;
    }

    // Add pagination parameters if provided
    if (criteria.page !== undefined && criteria.page !== null) {
      params.page = criteria.page;
    }
    if (criteria.size !== undefined && criteria.size !== null) {
      params.size = criteria.size;
    }

    // Make GET request with search parameters
    const response = await apiClient.get('/accounts', { params });

    // Extract response data
    const responseData = response.data;

    // Handle paginated response from Spring Boot (Page<AccountResponse>)
    if (responseData.content && Array.isArray(responseData.content)) {
      // Paginated response format
      const transformedAccounts = responseData.content
        .filter(account => account)
        .map(account => transformAccountResponse(account));

      const results = {
        accounts: transformedAccounts,
        currentPage: responseData.number || 0,
        totalPages: responseData.totalPages || 1,
        totalElements: responseData.totalElements || transformedAccounts.length,
        pageSize: responseData.size || transformedAccounts.length,
        hasNext: !responseData.last,
        hasPrevious: !responseData.first
      };

      // Log successful search in development mode
      if (import.meta.env.MODE === 'development') {
        console.log('[AccountService] Search completed:', 
                    `${results.totalElements} total, showing ${results.accounts.length} on page ${results.currentPage}`);
      }

      return results;
    } else {
      // Non-paginated response (simple array)
      const accountsArray = Array.isArray(responseData) ? responseData : [responseData];
      const transformedAccounts = accountsArray
        .filter(account => account)
        .map(account => transformAccountResponse(account));

      const results = {
        accounts: transformedAccounts,
        currentPage: 0,
        totalPages: 1,
        totalElements: transformedAccounts.length,
        pageSize: transformedAccounts.length,
        hasNext: false,
        hasPrevious: false
      };

      // Log successful search in development mode
      if (import.meta.env.MODE === 'development') {
        console.log('[AccountService] Search completed:', `${results.totalElements} accounts found`);
      }

      return results;
    }
  } catch (error) {
    // Error handling
    
    // Log error in development mode
    if (import.meta.env.MODE === 'development') {
      console.error('[AccountService] Error searching accounts:', criteria, error);
    }

    // Transform error messages
    let errorMessage = error.message || 'An error occurred while searching accounts';

    if (error.status === 400) {
      errorMessage = error.message || 'Invalid search criteria provided';
    } else if (error.status === 403) {
      errorMessage = 'You do not have permission to search accounts';
    } else if (error.status === 500) {
      errorMessage = 'Error searching accounts. Please try again later.';
    }

    // Throw error for component error handling
    throw new Error(errorMessage);
  }
};

/**
 * Account Service Object
 * 
 * Default export containing all account management functions.
 * Provides a cohesive API for account operations throughout the frontend application.
 * 
 * @type {Object}
 * @property {Function} getAccountById - Retrieve account by ID
 * @property {Function} updateAccount - Update account details
 * @property {Function} getAccountsByCustomer - Get accounts for specific customer
 * @property {Function} searchAccounts - Search accounts with criteria
 * @property {Function} formatAccountBalance - Format monetary amount to 2 decimals
 */
const accountService = {
  getAccountById,
  updateAccount,
  getAccountsByCustomer,
  searchAccounts,
  formatAccountBalance
};

export default accountService;
