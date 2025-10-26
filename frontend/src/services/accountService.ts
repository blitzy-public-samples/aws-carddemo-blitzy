/**
 * Account Service Module
 * 
 * Converted from: COBOL programs COACTUPC.cbl (account update) and COACTVWC.cbl (account view)
 * Original function: Account management operations including view, create, and update
 * 
 * Conversion notes:
 * - COBOL EXEC CICS READ FILE('ACCTFILE') converted to REST GET /api/accounts/:id
 * - COBOL EXEC CICS REWRITE FILE('ACCTFILE') converted to REST PUT /api/accounts/:id
 * - COBOL EXEC CICS WRITE FILE('ACCTFILE') converted to REST POST /api/accounts
 * - COBOL file-status codes (00=success, 23=not found) mapped to HTTP status codes
 * - COBOL COMP-3 decimal fields (PIC S9V99) handled with 2 decimal precision
 * - COBOL field validations preserved for phone numbers, dates, status codes
 * - Asynchronous API calls replace synchronous CICS commands
 * 
 * Performance requirements:
 * - Sub-200ms response time for account retrieval operations
 * - Proper error handling with detailed messages for validation failures
 * - Type-safe operations using TypeScript Account interface
 */

import api from './api.ts';
import { Account, AccountStatus } from '../types/account.ts';

/**
 * Account creation request payload
 * Omits system-generated fields (acctId, createdAt, updatedAt)
 */
export type CreateAccountRequest = Omit<Account, 'acctId' | 'createdAt' | 'updatedAt'>;

/**
 * Account update request payload
 * All fields are optional to support partial updates
 */
export type UpdateAccountRequest = Partial<Omit<Account, 'acctId' | 'createdAt' | 'updatedAt'>>;

/**
 * Validation error details
 */
interface ValidationError {
  field: string;
  message: string;
}

/**
 * Account validation result
 */
interface ValidationResult {
  isValid: boolean;
  errors: ValidationError[];
}

/**
 * Validate Account Status
 * 
 * Converts COBOL 88-level condition validation:
 * 88 ACCT-STATUS-ACTIVE VALUE 'Y'
 * 88 ACCT-STATUS-CLOSED VALUE 'N'
 * 88 ACCT-STATUS-SUSPENDED VALUE 'S'
 * 
 * @param status - Account status to validate
 * @returns true if valid status code, false otherwise
 */
const isValidAccountStatus = (status: string): boolean => {
  return Object.values(AccountStatus).includes(status as AccountStatus);
};

/**
 * Validate Credit Limits
 * 
 * Preserves COBOL business rule validation:
 * - Cash credit limit must not exceed total credit limit
 * - Credit limits must be non-negative
 * - Credit limits must not exceed maximum allowed value
 * 
 * From COACTUPC.cbl WORKING-STORAGE validation routines
 * 
 * @param creditLimit - Total credit limit
 * @param cashCreditLimit - Cash advance credit limit
 * @returns Validation result with errors if any
 */
const validateCreditLimits = (
  creditLimit: number,
  cashCreditLimit: number
): ValidationResult => {
  const errors: ValidationError[] = [];
  const MAX_CREDIT_LIMIT = 999999999.99; // COBOL PIC S9(10)V99 max value

  // Credit limit must be non-negative
  if (creditLimit < 0) {
    errors.push({
      field: 'acctCreditLimit',
      message: 'Credit limit must be non-negative',
    });
  }

  // Credit limit must not exceed maximum
  if (creditLimit > MAX_CREDIT_LIMIT) {
    errors.push({
      field: 'acctCreditLimit',
      message: `Credit limit must not exceed ${MAX_CREDIT_LIMIT}`,
    });
  }

  // Cash credit limit must be non-negative
  if (cashCreditLimit < 0) {
    errors.push({
      field: 'acctCashCreditLimit',
      message: 'Cash credit limit must be non-negative',
    });
  }

  // Cash credit limit must not exceed total credit limit
  if (cashCreditLimit > creditLimit) {
    errors.push({
      field: 'acctCashCreditLimit',
      message: 'Cash credit limit cannot exceed total credit limit',
    });
  }

  return {
    isValid: errors.length === 0,
    errors,
  };
};

/**
 * Validate Balance Amount
 * 
 * Ensures balance values are within COBOL COMP-3 field constraints
 * and properly formatted with 2 decimal places
 * 
 * @param balance - Account balance to validate
 * @returns Validation result with errors if any
 */
const validateBalance = (balance: number): ValidationResult => {
  const errors: ValidationError[] = [];
  const MAX_BALANCE = 999999999.99; // COBOL PIC S9(10)V99 max value
  const MIN_BALANCE = -999999999.99; // COBOL PIC S9(10)V99 min value

  // Check balance range
  if (balance > MAX_BALANCE) {
    errors.push({
      field: 'acctCurrBal',
      message: `Balance cannot exceed ${MAX_BALANCE}`,
    });
  }

  if (balance < MIN_BALANCE) {
    errors.push({
      field: 'acctCurrBal',
      message: `Balance cannot be less than ${MIN_BALANCE}`,
    });
  }

  // Validate decimal precision (2 decimal places max)
  const decimalPart = (balance.toString().split('.')[1] || '');
  if (decimalPart.length > 2) {
    errors.push({
      field: 'acctCurrBal',
      message: 'Balance must have at most 2 decimal places',
    });
  }

  return {
    isValid: errors.length === 0,
    errors,
  };
};

/**
 * Validate Date Format
 * 
 * Validates ISO 8601 date format (YYYY-MM-DD) matching COBOL PIC X(10) date fields
 * Ensures dates are valid calendar dates
 * 
 * @param dateStr - Date string to validate
 * @param fieldName - Field name for error reporting
 * @returns Validation result with errors if any
 */
const validateDateFormat = (
  dateStr: string | null,
  fieldName: string
): ValidationResult => {
  const errors: ValidationError[] = [];

  // Null dates are allowed for optional fields
  if (dateStr === null) {
    return { isValid: true, errors: [] };
  }

  // Check ISO 8601 format (YYYY-MM-DD)
  const isoDatePattern = /^\d{4}-\d{2}-\d{2}$/;
  if (!isoDatePattern.test(dateStr)) {
    errors.push({
      field: fieldName,
      message: 'Date must be in YYYY-MM-DD format',
    });
    return { isValid: false, errors };
  }

  // Validate date is a valid calendar date
  const date = new Date(dateStr);
  if (isNaN(date.getTime())) {
    errors.push({
      field: fieldName,
      message: 'Invalid date value',
    });
  }

  return {
    isValid: errors.length === 0,
    errors,
  };
};

/**
 * Validate ZIP Code
 * 
 * Validates US ZIP code format (5 digits or 5+4 format)
 * Preserves COBOL field validation from COACTUPC.cbl
 * 
 * @param zip - ZIP code to validate
 * @returns Validation result with errors if any
 */
const validateZipCode = (zip: string | null): ValidationResult => {
  const errors: ValidationError[] = [];

  // Null ZIP is allowed
  if (zip === null || zip.trim() === '') {
    return { isValid: true, errors: [] };
  }

  // US ZIP code patterns: 12345 or 12345-6789
  const zipPattern = /^\d{5}(-\d{4})?$/;
  if (!zipPattern.test(zip)) {
    errors.push({
      field: 'acctAddrZip',
      message: 'ZIP code must be 5 digits or 5+4 format (e.g., 12345 or 12345-6789)',
    });
  }

  return {
    isValid: errors.length === 0,
    errors,
  };
};

/**
 * Validate Account Creation Data
 * 
 * Comprehensive validation for account creation matching COBOL validation rules
 * from COACTUPC.cbl WORKING-STORAGE edit routines
 * 
 * @param accountData - Account creation request data
 * @returns Validation result with all errors
 */
const validateAccountCreation = (accountData: CreateAccountRequest): ValidationResult => {
  const allErrors: ValidationError[] = [];

  // Validate account status
  if (!isValidAccountStatus(accountData.acctActiveStatus)) {
    allErrors.push({
      field: 'acctActiveStatus',
      message: `Invalid account status. Must be one of: ${Object.values(AccountStatus).join(', ')}`,
    });
  }

  // Validate credit limits
  const creditLimitValidation = validateCreditLimits(
    accountData.acctCreditLimit,
    accountData.acctCashCreditLimit
  );
  allErrors.push(...creditLimitValidation.errors);

  // Validate balance
  const balanceValidation = validateBalance(accountData.acctCurrBal);
  allErrors.push(...balanceValidation.errors);

  // Validate current cycle credit
  const cycCreditValidation = validateBalance(accountData.acctCurrCycCredit);
  cycCreditValidation.errors.forEach(err => {
    allErrors.push({
      field: 'acctCurrCycCredit',
      message: err.message.replace('Balance', 'Current cycle credit'),
    });
  });

  // Validate current cycle debit
  const cycDebitValidation = validateBalance(accountData.acctCurrCycDebit);
  cycDebitValidation.errors.forEach(err => {
    allErrors.push({
      field: 'acctCurrCycDebit',
      message: err.message.replace('Balance', 'Current cycle debit'),
    });
  });

  // Validate open date (mandatory)
  const openDateValidation = validateDateFormat(accountData.acctOpenDate, 'acctOpenDate');
  allErrors.push(...openDateValidation.errors);

  // Validate expiration date (optional)
  const expirationDateValidation = validateDateFormat(
    accountData.acctExpirationDate,
    'acctExpirationDate'
  );
  allErrors.push(...expirationDateValidation.errors);

  // Validate reissue date (optional)
  const reissueDateValidation = validateDateFormat(
    accountData.acctReissueDate,
    'acctReissueDate'
  );
  allErrors.push(...reissueDateValidation.errors);

  // Validate ZIP code
  const zipValidation = validateZipCode(accountData.acctAddrZip);
  allErrors.push(...zipValidation.errors);

  // Validate account group ID length (max 10 characters per COBOL PIC X(10))
  if (accountData.acctGroupId && accountData.acctGroupId.length > 10) {
    allErrors.push({
      field: 'acctGroupId',
      message: 'Account group ID must not exceed 10 characters',
    });
  }

  return {
    isValid: allErrors.length === 0,
    errors: allErrors,
  };
};

/**
 * Validate Account Update Data
 * 
 * Validation for account updates (partial updates allowed)
 * Preserves COBOL validation rules from COACTUPC.cbl
 * 
 * @param accountData - Account update request data
 * @returns Validation result with all errors
 */
const validateAccountUpdate = (accountData: UpdateAccountRequest): ValidationResult => {
  const allErrors: ValidationError[] = [];

  // Validate account status if provided
  if (accountData.acctActiveStatus !== undefined) {
    if (!isValidAccountStatus(accountData.acctActiveStatus)) {
      allErrors.push({
        field: 'acctActiveStatus',
        message: `Invalid account status. Must be one of: ${Object.values(AccountStatus).join(', ')}`,
      });
    }
  }

  // Validate credit limits if both are provided
  if (
    accountData.acctCreditLimit !== undefined &&
    accountData.acctCashCreditLimit !== undefined
  ) {
    const creditLimitValidation = validateCreditLimits(
      accountData.acctCreditLimit,
      accountData.acctCashCreditLimit
    );
    allErrors.push(...creditLimitValidation.errors);
  }

  // Validate balance if provided
  if (accountData.acctCurrBal !== undefined) {
    const balanceValidation = validateBalance(accountData.acctCurrBal);
    allErrors.push(...balanceValidation.errors);
  }

  // Validate current cycle credit if provided
  if (accountData.acctCurrCycCredit !== undefined) {
    const cycCreditValidation = validateBalance(accountData.acctCurrCycCredit);
    cycCreditValidation.errors.forEach(err => {
      allErrors.push({
        field: 'acctCurrCycCredit',
        message: err.message.replace('Balance', 'Current cycle credit'),
      });
    });
  }

  // Validate current cycle debit if provided
  if (accountData.acctCurrCycDebit !== undefined) {
    const cycDebitValidation = validateBalance(accountData.acctCurrCycDebit);
    cycDebitValidation.errors.forEach(err => {
      allErrors.push({
        field: 'acctCurrCycDebit',
        message: err.message.replace('Balance', 'Current cycle debit'),
      });
    });
  }

  // Validate dates if provided
  if (accountData.acctOpenDate !== undefined) {
    const openDateValidation = validateDateFormat(accountData.acctOpenDate, 'acctOpenDate');
    allErrors.push(...openDateValidation.errors);
  }

  if (accountData.acctExpirationDate !== undefined) {
    const expirationDateValidation = validateDateFormat(
      accountData.acctExpirationDate,
      'acctExpirationDate'
    );
    allErrors.push(...expirationDateValidation.errors);
  }

  if (accountData.acctReissueDate !== undefined) {
    const reissueDateValidation = validateDateFormat(
      accountData.acctReissueDate,
      'acctReissueDate'
    );
    allErrors.push(...reissueDateValidation.errors);
  }

  // Validate ZIP code if provided
  if (accountData.acctAddrZip !== undefined) {
    const zipValidation = validateZipCode(accountData.acctAddrZip);
    allErrors.push(...zipValidation.errors);
  }

  // Validate account group ID if provided
  if (accountData.acctGroupId !== undefined && accountData.acctGroupId && accountData.acctGroupId.length > 10) {
    allErrors.push({
      field: 'acctGroupId',
      message: 'Account group ID must not exceed 10 characters',
    });
  }

  return {
    isValid: allErrors.length === 0,
    errors: allErrors,
  };
};

/**
 * Format Validation Errors for User Display
 * 
 * Converts validation errors array into human-readable error message
 * 
 * @param errors - Array of validation errors
 * @returns Formatted error message
 */
const formatValidationErrors = (errors: ValidationError[]): string => {
  if (errors.length === 0) {
    return '';
  }

  if (errors.length === 1) {
    return errors[0]!.message;
  }

  return 'Validation errors:\n' + errors.map(err => `- ${err.field}: ${err.message}`).join('\n');
};

/**
 * Get Account by ID
 * 
 * Retrieves a single account by account ID.
 * Converts COBOL EXEC CICS READ FILE('ACCTFILE') operation to REST API call.
 * 
 * From COACTVWC.cbl:
 * EXEC CICS READ
 *   FILE('ACCTFILE')
 *   RIDFLD(ACCT-ID)
 *   INTO(ACCOUNT-RECORD)
 *   RESP(WS-RESP-CD)
 * END-EXEC
 * 
 * Error handling:
 * - 404 Not Found: Account ID does not exist (COBOL file-status 23)
 * - 400 Bad Request: Invalid account ID format
 * - 500 Internal Server Error: Database or server error
 * 
 * @param acctId - Account identifier (11-digit number)
 * @returns Promise resolving to Account object
 * @throws ApiError with status code and error message
 * 
 * @example
 * const account = await getAccountById(12345678901);
 * console.log(account.acctCurrBal); // 1234.56
 */
export const getAccountById = async (acctId: number): Promise<Account> => {
  // Validate account ID
  if (!acctId || acctId <= 0) {
    throw new Error('Account ID must be a positive number');
  }

  // Validate account ID length (11 digits max per COBOL PIC 9(11))
  if (acctId > 99999999999) {
    throw new Error('Account ID must not exceed 11 digits');
  }

  try {
    // Call REST API: GET /api/accounts/:id
    // Replaces COBOL EXEC CICS READ FILE('ACCTFILE')
    const response = await api.get<Account>(`/accounts/${acctId}`);
    
    return response.data;
  } catch (error: any) {
    // Map HTTP status codes to COBOL file-status equivalents
    // 404 Not Found = COBOL file-status 23 (record not found)
    if (error.status === 404) {
      throw new Error(`Account ${acctId} not found`);
    }

    // Re-throw other errors with context
    throw new Error(error.message || 'Failed to retrieve account');
  }
};

/**
 * Create New Account
 * 
 * Creates a new account with the provided data.
 * Converts COBOL EXEC CICS WRITE FILE('ACCTFILE') operation to REST API call.
 * 
 * From COACTUPC.cbl:
 * EXEC CICS WRITE
 *   FILE('ACCTFILE')
 *   FROM(ACCOUNT-RECORD)
 *   RIDFLD(ACCT-ID)
 *   RESP(WS-RESP-CD)
 * END-EXEC
 * 
 * Validation:
 * - All mandatory fields must be provided
 * - Account status must be valid ('Y', 'N', or 'S')
 * - Credit limits must be non-negative and properly ordered
 * - Date fields must be in YYYY-MM-DD format
 * - ZIP code must be valid US format if provided
 * 
 * Error handling:
 * - 400 Bad Request: Validation errors
 * - 409 Conflict: Account already exists (duplicate ID)
 * - 500 Internal Server Error: Database or server error
 * 
 * @param accountData - Account creation data (without acctId, createdAt, updatedAt)
 * @returns Promise resolving to created Account with assigned ID
 * @throws ApiError with status code and error message
 * 
 * @example
 * const newAccount = await createAccount({
 *   acctActiveStatus: 'Y',
 *   acctCurrBal: 0.00,
 *   acctCreditLimit: 5000.00,
 *   acctCashCreditLimit: 1000.00,
 *   acctOpenDate: '2024-01-15',
 *   acctExpirationDate: null,
 *   acctReissueDate: null,
 *   acctCurrCycCredit: 0.00,
 *   acctCurrCycDebit: 0.00,
 *   acctAddrZip: '12345',
 *   acctGroupId: 'STANDARD'
 * });
 */
export const createAccount = async (
  accountData: CreateAccountRequest
): Promise<Account> => {
  // Perform client-side validation matching COBOL validation routines
  const validation = validateAccountCreation(accountData);
  
  if (!validation.isValid) {
    const errorMessage = formatValidationErrors(validation.errors);
    throw new Error(errorMessage);
  }

  try {
    // Call REST API: POST /api/accounts
    // Replaces COBOL EXEC CICS WRITE FILE('ACCTFILE')
    const response = await api.post<Account>('/accounts', accountData);
    
    return response.data;
  } catch (error: any) {
    // Handle duplicate account error
    // Maps to COBOL file-status 22 (duplicate key)
    if (error.status === 409) {
      throw new Error('Account already exists');
    }

    // Handle validation errors from backend
    if (error.status === 400) {
      throw new Error(error.message || 'Invalid account data');
    }

    // Re-throw other errors with context
    throw new Error(error.message || 'Failed to create account');
  }
};

/**
 * Update Existing Account
 * 
 * Updates an existing account with partial data.
 * Converts COBOL EXEC CICS REWRITE FILE('ACCTFILE') operation to REST API call.
 * 
 * From COACTUPC.cbl:
 * EXEC CICS READ
 *   FILE('ACCTFILE')
 *   RIDFLD(ACCT-ID)
 *   INTO(ACCOUNT-RECORD)
 *   UPDATE
 *   RESP(WS-RESP-CD)
 * END-EXEC
 * ... (modify ACCOUNT-RECORD fields)
 * EXEC CICS REWRITE
 *   FILE('ACCTFILE')
 *   FROM(ACCOUNT-RECORD)
 *   RESP(WS-RESP-CD)
 * END-EXEC
 * 
 * Supports partial updates - only provided fields will be updated.
 * 
 * Validation:
 * - Account ID must exist
 * - Account status must be valid if provided
 * - Credit limits must be properly ordered if both provided
 * - Date fields must be in YYYY-MM-DD format if provided
 * - ZIP code must be valid US format if provided
 * 
 * Error handling:
 * - 404 Not Found: Account ID does not exist
 * - 400 Bad Request: Validation errors
 * - 409 Conflict: Optimistic locking failure (concurrent update)
 * - 500 Internal Server Error: Database or server error
 * 
 * @param acctId - Account identifier to update
 * @param accountData - Partial account data to update
 * @returns Promise resolving to updated Account object
 * @throws ApiError with status code and error message
 * 
 * @example
 * const updatedAccount = await updateAccount(12345678901, {
 *   acctCreditLimit: 10000.00,
 *   acctCashCreditLimit: 2000.00,
 *   acctActiveStatus: 'Y'
 * });
 */
export const updateAccount = async (
  acctId: number,
  accountData: UpdateAccountRequest
): Promise<Account> => {
  // Validate account ID
  if (!acctId || acctId <= 0) {
    throw new Error('Account ID must be a positive number');
  }

  // Validate account ID length (11 digits max per COBOL PIC 9(11))
  if (acctId > 99999999999) {
    throw new Error('Account ID must not exceed 11 digits');
  }

  // Perform client-side validation matching COBOL validation routines
  const validation = validateAccountUpdate(accountData);
  
  if (!validation.isValid) {
    const errorMessage = formatValidationErrors(validation.errors);
    throw new Error(errorMessage);
  }

  try {
    // Call REST API: PUT /api/accounts/:id
    // Replaces COBOL EXEC CICS READ UPDATE + EXEC CICS REWRITE sequence
    const response = await api.put<Account>(`/accounts/${acctId}`, accountData);
    
    return response.data;
  } catch (error: any) {
    // Map HTTP status codes to COBOL file-status equivalents
    // 404 Not Found = COBOL file-status 23 (record not found)
    if (error.status === 404) {
      throw new Error(`Account ${acctId} not found`);
    }

    // 409 Conflict = Optimistic locking failure (concurrent update)
    // Maps to COBOL "record has been modified by another user" scenario
    if (error.status === 409) {
      throw new Error('Account has been modified by another user. Please refresh and try again.');
    }

    // Handle validation errors from backend
    if (error.status === 400) {
      throw new Error(error.message || 'Invalid account data');
    }

    // Re-throw other errors with context
    throw new Error(error.message || 'Failed to update account');
  }
};

/**
 * Account Service Object
 * 
 * Default export providing all account management operations.
 * Used by AccountViewPage and AccountUpdatePage React components.
 * 
 * This object replaces COBOL program linkage:
 * - EXEC CICS LINK PROGRAM('COACTVWC') → accountService.getAccountById()
 * - EXEC CICS LINK PROGRAM('COACTUPC') → accountService.updateAccount()
 * 
 * @example
 * import accountService from './services/accountService';
 * 
 * // View account
 * const account = await accountService.getAccountById(12345678901);
 * 
 * // Create account
 * const newAccount = await accountService.createAccount(accountData);
 * 
 * // Update account
 * const updated = await accountService.updateAccount(12345678901, { acctCreditLimit: 10000 });
 */
const accountService = {
  /**
   * Retrieve account by ID
   * @see getAccountById
   */
  getAccountById,

  /**
   * Create new account
   * @see createAccount
   */
  createAccount,

  /**
   * Update existing account
   * @see updateAccount
   */
  updateAccount,
};

export default accountService;
