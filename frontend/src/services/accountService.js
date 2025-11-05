/**
 * Account Service
 * 
 * Account service providing RESTful CRUD operations for credit card account management
 * in CardDemo frontend application. This module encapsulates all account-related API
 * interactions, transforming COBOL CICS transactions into modern REST API calls.
 * 
 * Transformation Context:
 * Maps COBOL programs COACTVWC.cbl (Account View) and COACTUPC.cbl (Account Update)
 * to equivalent JavaScript async/await methods, preserving business logic while
 * replacing VSAM ACCTDAT file operations with PostgreSQL-backed REST endpoints.
 * 
 * COBOL Source Programs:
 * - COACTVWC.cbl: Account view transaction (EXEC CICS READ DATASET('ACCTDAT'))
 * - COACTUPC.cbl: Account update transaction (EXEC CICS REWRITE DATASET('ACCTDAT'))
 * 
 * REST API Endpoints:
 * - GET    /api/accounts/{accountId}              - Retrieve account by ID
 * - GET    /api/accounts?customerId={customerId}  - Retrieve accounts by customer
 * - POST   /api/accounts                          - Create new account
 * - PUT    /api/accounts/{accountId}              - Update existing account
 * - GET    /api/accounts/{accountId}/balance      - Retrieve account balance
 * 
 * Key Features:
 * - Account retrieval with comprehensive field mapping
 * - Account creation with validation
 * - Account updates with field-level validation
 * - Balance calculation and display
 * - Cross-reference navigation (customer to accounts)
 * - Error handling with user-friendly messages
 * - COMP-3 decimal precision preservation for monetary fields
 * 
 * Integration:
 * Used by AccountViewComponent, AccountUpdateComponent, and AccountAddComponent
 * for all account-related operations in the CardDemo React frontend.
 * 
 * @module services/accountService
 */

import apiClient from './apiClient';

/**
 * Retrieve account details by account ID
 * 
 * Maps COBOL COACTVWC.cbl account view logic:
 * - COBOL: EXEC CICS READ DATASET('ACCTDAT') RIDFLD(accountId)
 * - JavaScript: GET /api/accounts/{accountId}
 * 
 * This function retrieves comprehensive account information including:
 * - Account identification (accountId, customerId)
 * - Account status and limits (accountStatus, creditLimit, cashCreditLimit)
 * - Balance information (currentBalance, availableBalance)
 * - Cycle information (currentCycleCredit, currentCycleDebit)
 * - Date information (openDate, expiryDate, reissueDate)
 * - Account group membership (accountGroupId)
 * 
 * COBOL Field Mappings:
 * - ACCT-ID → accountId
 * - ACCT-ACTIVE-STATUS → accountStatus
 * - ACCT-CURR-BAL → currentBalance (COMP-3 precision preserved)
 * - ACCT-CREDIT-LIMIT → creditLimit (COMP-3 precision preserved)
 * - ACCT-CASH-CREDIT-LIMIT → cashCreditLimit
 * - ACCT-CURR-CYC-CREDIT → currentCycleCredit
 * - ACCT-CURR-CYC-DEBIT → currentCycleDebit
 * - ACCT-OPEN-DATE → openDate
 * - ACCT-EXPIRAION-DATE → expiryDate
 * - ACCT-REISSUE-DATE → reissueDate
 * - ACCT-GROUP-ID → accountGroupId
 * 
 * Error Handling:
 * - 404: Account not found (maps COBOL NOTFND condition)
 * - 400: Invalid account ID format
 * - 401: Unauthorized access (session expired)
 * - 500: Server error (maps COBOL file I/O errors)
 * 
 * @param {string|number} accountId - 11-digit account identifier (must be non-zero)
 * @returns {Promise<Object>} Promise resolving to account object with fields:
 *   {
 *     accountId: string,           // 11-digit account number
 *     customerId: string,           // 9-digit customer number
 *     accountStatus: string,        // 'Y' active, 'N' inactive
 *     currentBalance: number,       // Current balance (2 decimal precision)
 *     availableBalance: number,     // Available credit balance
 *     creditLimit: number,          // Credit limit (2 decimal precision)
 *     cashCreditLimit: number,      // Cash credit limit
 *     currentCycleCredit: number,   // Current cycle credit total
 *     currentCycleDebit: number,    // Current cycle debit total
 *     openDate: string,             // Account open date (YYYY-MM-DD)
 *     expiryDate: string,           // Account expiry date (YYYY-MM-DD)
 *     reissueDate: string,          // Account reissue date (YYYY-MM-DD)
 *     accountGroupId: string        // Account group identifier
 *   }
 * 
 * @throws {Error} Error with message property containing user-friendly error description
 * 
 * @example
 * // Retrieve account for display (maps COACTVWC.cbl 9300-GETACCTDATA-BYACCT)
 * try {
 *   const account = await getAccount('12345678901');
 *   console.log(`Account status: ${account.accountStatus}`);
 *   console.log(`Current balance: $${account.currentBalance.toFixed(2)}`);
 *   console.log(`Credit limit: $${account.creditLimit.toFixed(2)}`);
 * } catch (error) {
 *   if (error.status === 404) {
 *     console.error('Account not found');
 *   } else {
 *     console.error(`Error: ${error.message}`);
 *   }
 * }
 */
export const getAccount = async (accountId) => {
  try {
    // Validate account ID format before making API call
    // Maps COBOL 2210-EDIT-ACCOUNT validation logic
    if (!accountId || accountId === '0' || accountId === 0) {
      throw {
        message: 'Invalid account number',
        status: 400
      };
    }

    // Convert to string and validate numeric format
    const accountIdStr = String(accountId);
    if (!/^\d{11}$/.test(accountIdStr)) {
      throw {
        message: 'Account number must be a non-zero 11 digit number',
        status: 400
      };
    }

    // Execute GET request to retrieve account by ID
    // Maps COBOL: EXEC CICS READ DATASET('ACCTDAT') RIDFLD(accountId)
    const response = await apiClient.get(`/accounts/${accountIdStr}`);
    
    // Return account data from response
    // Preserves COMP-3 decimal precision for monetary fields (currentBalance, creditLimit)
    return response.data;
    
  } catch (error) {
    // Handle specific error scenarios with user-friendly messages
    // Maps COBOL error handling in 9300-GETACCTDATA-BYACCT-EXIT
    
    if (error.status === 404) {
      // Account not found in database
      // Maps COBOL: WHEN DFHRESP(NOTFND) - DID-NOT-FIND-ACCT-IN-ACCTDAT
      throw {
        message: 'Account not found',
        status: 404
      };
    }
    
    if (error.status === 400) {
      // Validation error - invalid account ID format
      // Maps COBOL: FLG-ACCTFILTER-NOT-OK validation failures
      throw {
        message: error.message || 'Invalid account number',
        status: 400
      };
    }
    
    // Propagate all other errors (401 Unauthorized, 500 Server Error, etc.)
    throw error;
  }
};

/**
 * Retrieve all accounts for a specific customer
 * 
 * Maps COBOL cross-reference file navigation pattern:
 * - COBOL: Sequential read of XREF file filtered by customer ID
 * - JavaScript: GET /api/accounts?customerId={customerId}
 * 
 * This function retrieves all accounts associated with a customer, enabling
 * customer account portfolio views and account selection interfaces.
 * 
 * COBOL Navigation Pattern:
 * The COBOL programs navigate from customer to accounts using cross-reference
 * files (XREF, CXACAIX). This REST API endpoint performs equivalent filtering
 * using PostgreSQL foreign key relationships and indexed queries.
 * 
 * Use Cases:
 * - Customer account portfolio display
 * - Account selection for card operations
 * - Account summary views in customer dashboard
 * - Multi-account customer management
 * 
 * @param {string|number} customerId - 9-digit customer identifier
 * @returns {Promise<Array>} Promise resolving to array of account objects
 *   Each account object contains same fields as getAccount() return value
 * 
 * @throws {Error} Error with message property containing user-friendly error description
 * 
 * @example
 * // Retrieve all accounts for a customer (maps VSAM XREF navigation)
 * try {
 *   const accounts = await getAccountsByCustomer('123456789');
 *   console.log(`Customer has ${accounts.length} account(s)`);
 *   accounts.forEach(account => {
 *     console.log(`Account ${account.accountId}: Balance $${account.currentBalance.toFixed(2)}`);
 *   });
 * } catch (error) {
 *   console.error(`Error retrieving accounts: ${error.message}`);
 * }
 */
export const getAccountsByCustomer = async (customerId) => {
  try {
    // Validate customer ID format
    // Maps COBOL customer ID validation logic
    if (!customerId || customerId === '0' || customerId === 0) {
      throw {
        message: 'Invalid customer number',
        status: 400
      };
    }

    // Convert to string and validate numeric format (9 digits)
    const customerIdStr = String(customerId);
    if (!/^\d{9}$/.test(customerIdStr)) {
      throw {
        message: 'Customer number must be a 9 digit number',
        status: 400
      };
    }

    // Execute GET request with query parameter
    // Maps COBOL: XREF file sequential read with customer ID filter
    const response = await apiClient.get('/accounts', {
      params: { customerId: customerIdStr }
    });
    
    // Return array of accounts
    // Empty array if customer has no accounts
    return response.data || [];
    
  } catch (error) {
    // Handle specific error scenarios
    
    if (error.status === 404) {
      // Customer not found or has no accounts
      // Return empty array for consistency with COBOL behavior
      return [];
    }
    
    if (error.status === 400) {
      // Validation error
      throw {
        message: error.message || 'Invalid customer number',
        status: 400
      };
    }
    
    // Propagate all other errors
    throw error;
  }
};

/**
 * Update existing account information
 * 
 * Maps COBOL COACTUPC.cbl account update logic:
 * - COBOL: EXEC CICS REWRITE DATASET('ACCTDAT') FROM(account-record)
 * - JavaScript: PUT /api/accounts/{accountId}
 * 
 * This function updates account fields with comprehensive validation matching
 * COBOL field validation rules from COACTUPC program. Updates are transactional,
 * ensuring all changes are committed atomically or rolled back on error.
 * 
 * Updateable Fields:
 * - accountStatus: 'Y' (active) or 'N' (inactive)
 * - creditLimit: Credit limit amount (2 decimal precision)
 * - cashCreditLimit: Cash advance limit
 * - currentBalance: Current balance (typically read-only, admin only)
 * - currentCycleCredit: Current billing cycle credits
 * - currentCycleDebit: Current billing cycle debits
 * - expiryDate: Account expiration date
 * - reissueDate: Card reissue date
 * 
 * COBOL Field Validation Mappings:
 * - accountStatus: WS-EDIT-ACCT-STATUS (88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N')
 * - creditLimit: WS-EDIT-CREDIT-LIMIT (signed number validation)
 * - cashCreditLimit: WS-EDIT-CASH-CREDIT-LIMIT (signed number validation)
 * - Date fields: WS-EDIT-*-DATE-FLGS (CCYYMMDD format validation)
 * 
 * Transaction Semantics:
 * Updates are performed within a database transaction (@Transactional in backend),
 * preserving CICS SYNCPOINT semantics. All field updates succeed or all fail,
 * maintaining data integrity equivalent to COBOL file locking.
 * 
 * @param {string|number} accountId - 11-digit account identifier
 * @param {Object} accountData - Account fields to update
 * @param {string} [accountData.accountStatus] - Account status ('Y' or 'N')
 * @param {number} [accountData.creditLimit] - Credit limit amount
 * @param {number} [accountData.cashCreditLimit] - Cash credit limit
 * @param {number} [accountData.currentBalance] - Current balance (admin only)
 * @param {number} [accountData.currentCycleCredit] - Cycle credit total
 * @param {number} [accountData.currentCycleDebit] - Cycle debit total
 * @param {string} [accountData.expiryDate] - Expiry date (YYYY-MM-DD)
 * @param {string} [accountData.reissueDate] - Reissue date (YYYY-MM-DD)
 * @returns {Promise<Object>} Promise resolving to updated account object
 * 
 * @throws {Error} Error with message and optional errors object for field-specific validation errors
 * 
 * @example
 * // Update account credit limit (maps COACTUPC.cbl update logic)
 * try {
 *   const updatedAccount = await updateAccount('12345678901', {
 *     accountStatus: 'Y',
 *     creditLimit: 15000.00
 *   });
 *   console.log('Account updated successfully');
 *   console.log(`New credit limit: $${updatedAccount.creditLimit.toFixed(2)}`);
 * } catch (error) {
 *   if (error.status === 400 && error.errors) {
 *     // Field-specific validation errors
 *     Object.keys(error.errors).forEach(field => {
 *       console.error(`${field}: ${error.errors[field]}`);
 *     });
 *   } else {
 *     console.error(`Error: ${error.message}`);
 *   }
 * }
 */
export const updateAccount = async (accountId, accountData) => {
  try {
    // Validate account ID format
    // Maps COBOL 2210-EDIT-ACCOUNT validation
    if (!accountId || accountId === '0' || accountId === 0) {
      throw {
        message: 'Invalid account number',
        status: 400
      };
    }

    const accountIdStr = String(accountId);
    if (!/^\d{11}$/.test(accountIdStr)) {
      throw {
        message: 'Account number must be a non-zero 11 digit number',
        status: 400
      };
    }

    // Validate required fields in accountData
    // Maps COBOL WS-NON-KEY-FLAGS validation logic
    if (!accountData || typeof accountData !== 'object') {
      throw {
        message: 'Account data is required',
        status: 400
      };
    }

    // Validate accountStatus if provided
    // Maps COBOL: 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'
    if (accountData.accountStatus !== undefined) {
      if (!['Y', 'N'].includes(accountData.accountStatus)) {
        throw {
          message: 'Account status must be Y (active) or N (inactive)',
          status: 400,
          errors: {
            accountStatus: 'Must be Y or N'
          }
        };
      }
    }

    // Validate creditLimit if provided
    // Maps COBOL: WS-EDIT-CREDIT-LIMIT signed number validation
    if (accountData.creditLimit !== undefined) {
      const creditLimit = Number(accountData.creditLimit);
      if (isNaN(creditLimit) || creditLimit < 0) {
        throw {
          message: 'Credit limit must be a positive number',
          status: 400,
          errors: {
            creditLimit: 'Must be a positive number'
          }
        };
      }
    }

    // Validate cashCreditLimit if provided
    // Maps COBOL: WS-EDIT-CASH-CREDIT-LIMIT validation
    if (accountData.cashCreditLimit !== undefined) {
      const cashLimit = Number(accountData.cashCreditLimit);
      if (isNaN(cashLimit) || cashLimit < 0) {
        throw {
          message: 'Cash credit limit must be a positive number',
          status: 400,
          errors: {
            cashCreditLimit: 'Must be a positive number'
          }
        };
      }
    }

    // Execute PUT request to update account
    // Maps COBOL: EXEC CICS REWRITE DATASET('ACCTDAT')
    const response = await apiClient.put(`/accounts/${accountIdStr}`, accountData);
    
    // Return updated account data
    // Backend applies @Transactional for atomicity (COBOL SYNCPOINT equivalent)
    return response.data;
    
  } catch (error) {
    // Handle specific error scenarios with detailed error information
    // Maps COBOL error handling in update procedures
    
    if (error.status === 404) {
      // Account not found for update
      // Maps COBOL: NOTFND condition in REWRITE operation
      throw {
        message: 'Account not found',
        status: 404
      };
    }
    
    if (error.status === 400) {
      // Validation errors from backend or frontend validation
      // Maps COBOL: FLG-*-NOT-OK validation failures
      throw {
        message: error.message || 'Invalid account data',
        status: 400,
        errors: error.errors || {}
      };
    }
    
    if (error.status === 409) {
      // Conflict - concurrent update detected
      // Maps COBOL: File locking conflict
      throw {
        message: 'Account was modified by another user. Please refresh and try again.',
        status: 409
      };
    }
    
    // Propagate all other errors (401, 500, etc.)
    throw error;
  }
};

/**
 * Create new account
 * 
 * Maps COBOL account creation logic:
 * - COBOL: EXEC CICS WRITE DATASET('ACCTDAT') FROM(new-account-record)
 * - JavaScript: POST /api/accounts
 * 
 * This function creates a new account with validation and automatic cross-reference
 * creation. The backend generates the account ID and initializes default values
 * for fields not provided in the request.
 * 
 * Required Fields:
 * - customerId: Customer who owns the account (must exist in CUSTDAT)
 * - accountStatus: Initial account status ('Y' active, 'N' inactive)
 * - creditLimit: Initial credit limit
 * - openDate: Account open date (YYYY-MM-DD format)
 * 
 * Optional Fields:
 * - cashCreditLimit: Cash advance limit (defaults to 0)
 * - accountGroupId: Account group membership
 * - expiryDate: Account expiration date
 * 
 * Auto-Generated Fields:
 * - accountId: System-generated 11-digit unique identifier
 * - currentBalance: Initialized to 0.00
 * - currentCycleCredit: Initialized to 0.00
 * - currentCycleDebit: Initialized to 0.00
 * 
 * Cross-Reference Creation:
 * Backend automatically creates entries in account cross-reference tables
 * (equivalent to COBOL XREF file writes) to maintain referential integrity
 * and enable customer-to-account and account-to-card navigation.
 * 
 * @param {Object} accountData - New account data
 * @param {string|number} accountData.customerId - Customer ID (required, 9 digits)
 * @param {string} accountData.accountStatus - Account status (required, 'Y' or 'N')
 * @param {number} accountData.creditLimit - Credit limit (required, positive number)
 * @param {string} accountData.openDate - Open date (required, YYYY-MM-DD format)
 * @param {number} [accountData.cashCreditLimit] - Cash credit limit (optional)
 * @param {string} [accountData.accountGroupId] - Account group (optional)
 * @param {string} [accountData.expiryDate] - Expiry date (optional, YYYY-MM-DD)
 * @returns {Promise<Object>} Promise resolving to newly created account object with generated accountId
 * 
 * @throws {Error} Error with message and optional errors object for field-specific validation errors
 * 
 * @example
 * // Create new account (maps COBOL account creation logic)
 * try {
 *   const newAccount = await createAccount({
 *     customerId: '123456789',
 *     accountStatus: 'Y',
 *     creditLimit: 10000.00,
 *     cashCreditLimit: 1000.00,
 *     openDate: '2024-01-15',
 *     expiryDate: '2029-01-15'
 *   });
 *   console.log(`New account created with ID: ${newAccount.accountId}`);
 *   console.log(`Credit limit: $${newAccount.creditLimit.toFixed(2)}`);
 * } catch (error) {
 *   if (error.status === 400 && error.errors) {
 *     console.error('Validation errors:', error.errors);
 *   } else {
 *     console.error(`Error: ${error.message}`);
 *   }
 * }
 */
export const createAccount = async (accountData) => {
  try {
    // Validate required fields
    // Maps COBOL mandatory field validation
    if (!accountData || typeof accountData !== 'object') {
      throw {
        message: 'Account data is required',
        status: 400
      };
    }

    // Validate customerId (required)
    // Maps COBOL: FLG-CUSTFILTER validation
    if (!accountData.customerId) {
      throw {
        message: 'Customer ID is required',
        status: 400,
        errors: {
          customerId: 'Required field'
        }
      };
    }

    const customerIdStr = String(accountData.customerId);
    if (!/^\d{9}$/.test(customerIdStr)) {
      throw {
        message: 'Customer ID must be a 9 digit number',
        status: 400,
        errors: {
          customerId: 'Must be 9 digits'
        }
      };
    }

    // Validate accountStatus (required)
    // Maps COBOL: 88 FLG-ACCT-STATUS-ISVALID
    if (!accountData.accountStatus) {
      throw {
        message: 'Account status is required',
        status: 400,
        errors: {
          accountStatus: 'Required field'
        }
      };
    }

    if (!['Y', 'N'].includes(accountData.accountStatus)) {
      throw {
        message: 'Account status must be Y (active) or N (inactive)',
        status: 400,
        errors: {
          accountStatus: 'Must be Y or N'
        }
      };
    }

    // Validate creditLimit (required)
    // Maps COBOL: WS-EDIT-CREDIT-LIMIT validation
    if (accountData.creditLimit === undefined || accountData.creditLimit === null) {
      throw {
        message: 'Credit limit is required',
        status: 400,
        errors: {
          creditLimit: 'Required field'
        }
      };
    }

    const creditLimit = Number(accountData.creditLimit);
    if (isNaN(creditLimit) || creditLimit < 0) {
      throw {
        message: 'Credit limit must be a positive number',
        status: 400,
        errors: {
          creditLimit: 'Must be a positive number'
        }
      };
    }

    // Validate openDate (required)
    // Maps COBOL: WS-EDIT-OPEN-DATE-FLGS validation
    if (!accountData.openDate) {
      throw {
        message: 'Open date is required',
        status: 400,
        errors: {
          openDate: 'Required field'
        }
      };
    }

    // Validate date format (YYYY-MM-DD)
    if (!/^\d{4}-\d{2}-\d{2}$/.test(accountData.openDate)) {
      throw {
        message: 'Open date must be in YYYY-MM-DD format',
        status: 400,
        errors: {
          openDate: 'Must be in YYYY-MM-DD format'
        }
      };
    }

    // Validate cashCreditLimit if provided
    // Maps COBOL: WS-EDIT-CASH-CREDIT-LIMIT validation
    if (accountData.cashCreditLimit !== undefined) {
      const cashLimit = Number(accountData.cashCreditLimit);
      if (isNaN(cashLimit) || cashLimit < 0) {
        throw {
          message: 'Cash credit limit must be a positive number',
          status: 400,
          errors: {
            cashCreditLimit: 'Must be a positive number'
          }
        };
      }
    }

    // Validate expiryDate format if provided
    // Maps COBOL: WS-EXPIRY-DATE-FLGS validation
    if (accountData.expiryDate) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(accountData.expiryDate)) {
        throw {
          message: 'Expiry date must be in YYYY-MM-DD format',
          status: 400,
          errors: {
            expiryDate: 'Must be in YYYY-MM-DD format'
          }
        };
      }
    }

    // Execute POST request to create account
    // Maps COBOL: EXEC CICS WRITE DATASET('ACCTDAT')
    // Backend generates accountId and creates cross-reference entries
    const response = await apiClient.post('/accounts', accountData);
    
    // Return newly created account with generated accountId
    return response.data;
    
  } catch (error) {
    // Handle specific error scenarios
    
    if (error.status === 400) {
      // Validation errors from backend or frontend
      // Maps COBOL: Field validation failures
      throw {
        message: error.message || 'Invalid account data',
        status: 400,
        errors: error.errors || {}
      };
    }
    
    if (error.status === 404) {
      // Customer not found - cannot create account for non-existent customer
      // Maps COBOL: CUSTDAT READ NOTFND condition
      throw {
        message: 'Customer not found',
        status: 404
      };
    }
    
    if (error.status === 409) {
      // Conflict - duplicate account (should not occur with auto-generated IDs)
      throw {
        message: 'Account already exists',
        status: 409
      };
    }
    
    // Propagate all other errors (401, 500, etc.)
    throw error;
  }
};

/**
 * Retrieve account balance information
 * 
 * Maps COBOL balance calculation logic:
 * - COBOL: Read ACCTDAT and calculate available balance
 * - JavaScript: GET /api/accounts/{accountId}/balance
 * 
 * This function retrieves current balance information including:
 * - Current balance (currentBalance)
 * - Available credit balance (creditLimit - currentBalance)
 * - Pending transactions count and total
 * - Credit utilization percentage
 * 
 * Balance Calculation:
 * Available Balance = Credit Limit - Current Balance
 * This preserves COBOL COMP-3 decimal arithmetic precision (2 decimal places)
 * 
 * COBOL Field Mappings:
 * - ACCT-CURR-BAL → currentBalance
 * - ACCT-CREDIT-LIMIT → creditLimit
 * - Calculated: availableBalance = creditLimit - currentBalance
 * 
 * Use Cases:
 * - Display available credit on account views
 * - Pre-transaction authorization checks
 * - Credit utilization reporting
 * - Account balance monitoring
 * 
 * @param {string|number} accountId - 11-digit account identifier
 * @returns {Promise<Object>} Promise resolving to balance information object:
 *   {
 *     accountId: string,           // Account identifier
 *     currentBalance: number,      // Current balance (2 decimal precision)
 *     creditLimit: number,         // Credit limit
 *     availableBalance: number,    // Available credit (creditLimit - currentBalance)
 *     cashCreditLimit: number,     // Cash advance limit
 *     pendingTransactions: number, // Count of pending transactions
 *     pendingAmount: number        // Total amount of pending transactions
 *   }
 * 
 * @throws {Error} Error with message property containing user-friendly error description
 * 
 * @example
 * // Retrieve account balance (maps COBOL balance inquiry logic)
 * try {
 *   const balance = await getAccountBalance('12345678901');
 *   console.log(`Current balance: $${balance.currentBalance.toFixed(2)}`);
 *   console.log(`Available credit: $${balance.availableBalance.toFixed(2)}`);
 *   console.log(`Credit limit: $${balance.creditLimit.toFixed(2)}`);
 *   
 *   // Calculate utilization percentage
 *   const utilization = (balance.currentBalance / balance.creditLimit * 100).toFixed(1);
 *   console.log(`Credit utilization: ${utilization}%`);
 * } catch (error) {
 *   console.error(`Error retrieving balance: ${error.message}`);
 * }
 */
export const getAccountBalance = async (accountId) => {
  try {
    // Validate account ID format
    // Maps COBOL account ID validation
    if (!accountId || accountId === '0' || accountId === 0) {
      throw {
        message: 'Invalid account number',
        status: 400
      };
    }

    const accountIdStr = String(accountId);
    if (!/^\d{11}$/.test(accountIdStr)) {
      throw {
        message: 'Account number must be a non-zero 11 digit number',
        status: 400
      };
    }

    // Execute GET request to retrieve balance information
    // Maps COBOL: Read ACCTDAT and calculate available balance
    const response = await apiClient.get(`/accounts/${accountIdStr}/balance`);
    
    // Return balance information
    // Backend calculates availableBalance with COMP-3 precision preservation
    return response.data;
    
  } catch (error) {
    // Handle specific error scenarios
    
    if (error.status === 404) {
      // Account not found
      // Maps COBOL: NOTFND condition
      throw {
        message: 'Account not found',
        status: 404
      };
    }
    
    if (error.status === 400) {
      // Validation error
      throw {
        message: error.message || 'Invalid account number',
        status: 400
      };
    }
    
    // Propagate all other errors (401, 500, etc.)
    throw error;
  }
};

/**
 * Account Service Default Export
 * 
 * Exports all account service methods as a single object for convenient
 * import and usage in React components.
 * 
 * Usage Patterns:
 * 
 * Default Import:
 * import accountService from './services/accountService';
 * const account = await accountService.getAccount('12345678901');
 * 
 * Named Imports:
 * import { getAccount, updateAccount } from './services/accountService';
 * const account = await getAccount('12345678901');
 * 
 * Component Integration:
 * Used by AccountViewComponent, AccountUpdateComponent, AccountAddComponent
 * for all account-related operations including view, create, update, and
 * balance inquiries.
 * 
 * Error Handling:
 * All methods throw errors with consistent structure:
 * - message: User-friendly error description
 * - status: HTTP status code
 * - errors: Field-specific validation errors (for 400 Bad Request)
 * 
 * Components should wrap calls in try-catch blocks and display error messages
 * to users, matching COBOL error message display patterns from BMS screens.
 */
export default {
  getAccount,
  getAccountsByCustomer,
  updateAccount,
  createAccount,
  getAccountBalance
};
