/**
 * CardDemo Application Validators
 * 
 * Yup schema validation definitions preserving COBOL PIC clause validation rules.
 * Ensures functional equivalence with mainframe validation logic.
 * 
 * Source mappings:
 * - Customer validation: app/cpy/CVCUS01Y.cpy (Customer record structure)
 * - Account validation: app/cpy/CVACT01Y.cpy (Account record structure)
 * - Card validation: app/cpy/CVCRD01Y.cpy (Card data layout)
 * - Transaction validation: app/cpy/CVTRA01Y.cpy (Transaction record layout)
 * - User validation: app/cpy/CSUSR01Y.cpy (User security record)
 * 
 * Per Agent Action Plan Section 0.9:
 * - Field length validation matching COBOL PIC clause constraints
 * - Numeric constraint validation preserving COBOL numeric field rules
 * - Business rule validation maintaining exact COBOL validation logic
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import * as Yup from 'yup';
import { 
  FIELD_LENGTHS, 
  CARD_STATUS, 
  ACCOUNT_STATUS, 
  USER_ROLES, 
  TRANSACTION_TYPES 
} from './constants';

// =============================================================================
// COMMON FIELD VALIDATORS
// =============================================================================

/**
 * Validate numeric string with exact length
 * Matches COBOL PIC 9(n) validation
 * @param {number} length - Required length
 * @returns {Yup.StringSchema} Validation schema
 */
const numericString = (length) => 
  Yup.string()
    .matches(/^\d+$/, 'Must contain only digits')
    .length(length, `Must be exactly ${length} digits`)
    .required('This field is required');

/**
 * Validate alphanumeric string with max length
 * Matches COBOL PIC X(n) validation
 * @param {number} maxLength - Maximum length
 * @param {boolean} required - Whether field is required
 * @returns {Yup.StringSchema} Validation schema
 */
const alphanumericString = (maxLength, required = false) => {
  const schema = Yup.string()
    .max(maxLength, `Must be at most ${maxLength} characters`);
  
  return required ? schema.required('This field is required') : schema;
};

/**
 * Validate alphabetic-only string
 * Matches COBOL PIC A(n) validation
 * @param {number} maxLength - Maximum length
 * @param {boolean} required - Whether field is required
 * @returns {Yup.StringSchema} Validation schema
 */
const alphabeticString = (maxLength, required = false) => {
  const schema = Yup.string()
    .matches(/^[A-Za-z\s]*$/, 'Must contain only letters')
    .max(maxLength, `Must be at most ${maxLength} characters`);
  
  return required ? schema.required('This field is required') : schema;
};

/**
 * Validate currency amount with 2 decimal places
 * Matches COBOL S9(10)V99 COMP-3 validation
 * Per Agent Action Plan Section 0.9: Preserve exact decimal precision
 * @param {number} max - Maximum amount
 * @returns {Yup.NumberSchema} Validation schema
 */
const currencyAmount = (max = 9999999999.99) =>
  Yup.number()
    .min(0, 'Amount must be positive')
    .max(max, `Amount must not exceed $${max.toFixed(2)}`)
    .test('decimal-places', 'Amount must have at most 2 decimal places', (value) => {
      if (value === undefined || value === null) return true;
      const decimalPart = String(value).split('.')[1];
      return !decimalPart || decimalPart.length <= 2;
    })
    .required('Amount is required');

/**
 * Validate date in YYYY-MM-DD format
 * Matches COBOL PIC X(10) date field validation
 * @returns {Yup.DateSchema} Validation schema
 */
const dateField = () =>
  Yup.date()
    .typeError('Invalid date format')
    .required('Date is required');

// =============================================================================
// CUSTOMER VALIDATION SCHEMA (from CVCUS01Y.cpy)
// =============================================================================

/**
 * Customer validation schema preserving COBOL CUSTOMER-RECORD structure
 * Source: app/cpy/CVCUS01Y.cpy
 * 
 * Fields validated:
 * - CUST-ID: PIC 9(09) - 9-digit customer identifier
 * - CUST-FIRST-NAME: PIC X(25) - First name (alphabetic)
 * - CUST-MIDDLE-NAME: PIC X(25) - Middle name (alphabetic, optional)
 * - CUST-LAST-NAME: PIC X(25) - Last name (alphabetic)
 * - CUST-ADDR-LINE-1/2/3: PIC X(50) - Address lines
 * - CUST-ADDR-STATE-CD: PIC X(02) - 2-char state code
 * - CUST-ADDR-COUNTRY-CD: PIC X(03) - 3-char country code
 * - CUST-ADDR-ZIP: PIC X(10) - ZIP code
 * - CUST-PHONE-NUM-1/2: PIC X(15) - Phone numbers
 * - CUST-SSN: PIC 9(09) - Social Security Number
 * - CUST-GOVT-ISSUED-ID: PIC X(20) - Government ID
 * - CUST-DOB-YYYY-MM-DD: PIC X(10) - Date of birth
 * - CUST-EFT-ACCOUNT-ID: PIC X(10) - EFT account ID (optional)
 * - CUST-PRI-CARD-HOLDER-IND: PIC X(01) - Primary cardholder indicator (Y/N)
 * - CUST-FICO-CREDIT-SCORE: PIC 9(03) - FICO credit score
 */
export const customerValidationSchema = Yup.object().shape({
  // Customer ID: PIC 9(09)
  customerId: numericString(FIELD_LENGTHS.CUSTOMER_ID),
  
  // First Name: PIC X(25)
  firstName: alphabeticString(FIELD_LENGTHS.FIRST_NAME, true),
  
  // Middle Name: PIC X(25) - optional
  middleName: alphabeticString(FIELD_LENGTHS.MIDDLE_NAME, false),
  
  // Last Name: PIC X(25)
  lastName: alphabeticString(FIELD_LENGTHS.LAST_NAME, true),
  
  // Address Lines: PIC X(50)
  addressLine1: alphanumericString(FIELD_LENGTHS.ADDRESS_LINE, true),
  addressLine2: alphanumericString(FIELD_LENGTHS.ADDRESS_LINE, false),
  addressLine3: alphanumericString(FIELD_LENGTHS.ADDRESS_LINE, false),
  
  // State Code: PIC X(02)
  stateCode: Yup.string()
    .matches(/^[A-Z]{2}$/, 'Must be a valid 2-letter state code')
    .length(FIELD_LENGTHS.STATE_CODE, 'Must be 2 characters')
    .required('State code is required'),
  
  // Country Code: PIC X(03)
  countryCode: Yup.string()
    .matches(/^[A-Z]{3}$/, 'Must be a valid 3-letter country code')
    .length(FIELD_LENGTHS.COUNTRY_CODE, 'Must be 3 characters')
    .required('Country code is required'),
  
  // ZIP Code: PIC X(10)
  zipCode: alphanumericString(FIELD_LENGTHS.ZIP_CODE, true),
  
  // Phone Numbers: PIC X(15)
  phoneNumber1: Yup.string()
    .matches(/^\(?(\d{3})\)?[-.\s]?(\d{3})[-.\s]?(\d{4})$/, 'Invalid phone number format')
    .max(FIELD_LENGTHS.PHONE_NUMBER, `Must be at most ${FIELD_LENGTHS.PHONE_NUMBER} characters`)
    .required('Primary phone number is required'),
  
  phoneNumber2: Yup.string()
    .matches(/^\(?(\d{3})\)?[-.\s]?(\d{3})[-.\s]?(\d{4})$/, 'Invalid phone number format')
    .max(FIELD_LENGTHS.PHONE_NUMBER, `Must be at most ${FIELD_LENGTHS.PHONE_NUMBER} characters`)
    .nullable(),
  
  // SSN: PIC 9(09)
  ssn: numericString(FIELD_LENGTHS.SSN),
  
  // Government Issued ID: PIC X(20)
  govtIssuedId: alphanumericString(FIELD_LENGTHS.GOVT_ID, true),
  
  // Date of Birth: PIC X(10) - YYYY-MM-DD
  dateOfBirth: dateField()
    .max(new Date(), 'Date of birth cannot be in the future')
    .test('age-check', 'Must be at least 18 years old', (value) => {
      if (!value) return false;
      const today = new Date();
      const birthDate = new Date(value);
      const age = today.getFullYear() - birthDate.getFullYear();
      const monthDiff = today.getMonth() - birthDate.getMonth();
      
      if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birthDate.getDate())) {
        return age - 1 >= 18;
      }
      return age >= 18;
    }),
  
  // EFT Account ID: PIC X(10)
  eftAccountId: alphanumericString(10, false),
  
  // Primary Cardholder Indicator: PIC X(01)
  primaryCardholderInd: Yup.string()
    .matches(/^[YN]$/, 'Must be Y or N')
    .length(1, 'Must be 1 character')
    .required('Primary cardholder indicator is required'),
  
  // FICO Score: PIC 9(03)
  ficoScore: Yup.number()
    .integer('FICO score must be an integer')
    .min(300, 'FICO score must be at least 300')
    .max(850, 'FICO score must be at most 850')
    .required('FICO score is required')
});

// =============================================================================
// ACCOUNT VALIDATION SCHEMA (from CVACT01Y.cpy)
// =============================================================================

/**
 * Account validation schema preserving COBOL ACCOUNT-RECORD structure
 * Source: app/cpy/CVACT01Y.cpy
 * 
 * Fields validated:
 * - ACCT-ID: PIC 9(11) - 11-digit account identifier
 * - ACCT-ACTIVE-STATUS: PIC X(01) - Active status (Y/N)
 * - ACCT-CURR-BAL: PIC S9(10)V99 - Current balance (COMP-3)
 * - ACCT-CREDIT-LIMIT: PIC S9(10)V99 - Credit limit (COMP-3)
 * - ACCT-CASH-CREDIT-LIMIT: PIC S9(10)V99 - Cash credit limit (COMP-3)
 * - ACCT-OPEN-DATE: PIC X(10) - Account open date
 * - ACCT-EXPIRAION-DATE: PIC X(10) - Expiration date
 * - ACCT-REISSUE-DATE: PIC X(10) - Reissue date (optional)
 * - ACCT-CURR-CYC-CREDIT: PIC S9(10)V99 - Current cycle credit
 * - ACCT-CURR-CYC-DEBIT: PIC S9(10)V99 - Current cycle debit
 * - ACCT-ADDR-ZIP: PIC X(10) - ZIP code (optional)
 * - ACCT-GROUP-ID: PIC X(10) - Group ID (optional)
 */
export const accountValidationSchema = Yup.object().shape({
  // Account ID: PIC 9(11)
  accountId: numericString(FIELD_LENGTHS.ACCOUNT_ID),
  
  // Active Status: PIC X(01)
  activeStatus: Yup.string()
    .oneOf([ACCOUNT_STATUS.ACTIVE, ACCOUNT_STATUS.INACTIVE], 'Invalid account status')
    .length(1, 'Must be 1 character')
    .required('Account status is required'),
  
  // Current Balance: PIC S9(10)V99 COMP-3
  currentBalance: currencyAmount(),
  
  // Credit Limit: PIC S9(10)V99 COMP-3
  creditLimit: currencyAmount()
    .min(0, 'Credit limit must be positive')
    .test('balance-check', 'Credit limit must be greater than or equal to current balance', function(value) {
      const { currentBalance } = this.parent;
      if (currentBalance && value) {
        return value >= currentBalance;
      }
      return true;
    }),
  
  // Cash Credit Limit: PIC S9(10)V99 COMP-3
  cashCreditLimit: currencyAmount()
    .test('credit-limit-check', 'Cash credit limit cannot exceed total credit limit', function(value) {
      const { creditLimit } = this.parent;
      if (creditLimit && value) {
        return value <= creditLimit;
      }
      return true;
    }),
  
  // Open Date: PIC X(10)
  openDate: dateField()
    .max(new Date(), 'Open date cannot be in the future'),
  
  // Expiration Date: PIC X(10)
  expirationDate: dateField()
    .min(Yup.ref('openDate'), 'Expiration date must be after open date')
    .test('future-date', 'Expiration date must be in the future', (value) => {
      if (!value) return false;
      return new Date(value) > new Date();
    }),
  
  // Reissue Date: PIC X(10)
  reissueDate: dateField()
    .min(Yup.ref('openDate'), 'Reissue date must be after open date')
    .nullable(),
  
  // Current Cycle Credit: PIC S9(10)V99 COMP-3
  currentCycleCredit: currencyAmount(),
  
  // Current Cycle Debit: PIC S9(10)V99 COMP-3
  currentCycleDebit: currencyAmount(),
  
  // ZIP Code: PIC X(10)
  zipCode: alphanumericString(FIELD_LENGTHS.ZIP_CODE, false),
  
  // Group ID: PIC X(10)
  groupId: alphanumericString(FIELD_LENGTHS.GROUP_ID, false)
});

// =============================================================================
// CARD VALIDATION SCHEMA (from CVCRD01Y.cpy)
// =============================================================================

/**
 * Card validation schema preserving COBOL card data layout
 * Source: app/cpy/CVCRD01Y.cpy
 * 
 * Fields validated:
 * - CC-CARD-NUM: PIC 9(16) - 16-digit card number with Luhn validation
 * - CC-ACCT-ID: PIC X(11) - Associated account ID
 * - CC-CUST-ID: PIC X(09) - Associated customer ID
 * - Card status: Single character status code
 * - Expiration date: PIC X(10) - Card expiration date
 */
export const cardValidationSchema = Yup.object().shape({
  // Card Number: PIC 9(16)
  cardNumber: Yup.string()
    .matches(/^\d{16}$/, 'Card number must be 16 digits')
    .length(FIELD_LENGTHS.CARD_NUMBER, 'Card number must be 16 digits')
    .test('luhn-check', 'Invalid card number', (value) => {
      if (!value) return false;
      
      // Luhn algorithm validation
      let sum = 0;
      let isEven = false;
      
      for (let i = value.length - 1; i >= 0; i--) {
        let digit = parseInt(value[i], 10);
        
        if (isEven) {
          digit *= 2;
          if (digit > 9) {
            digit -= 9;
          }
        }
        
        sum += digit;
        isEven = !isEven;
      }
      
      return sum % 10 === 0;
    })
    .required('Card number is required'),
  
  // Account ID: PIC X(11)
  accountId: numericString(FIELD_LENGTHS.ACCOUNT_ID),
  
  // Customer ID: PIC X(09)
  customerId: numericString(FIELD_LENGTHS.CUSTOMER_ID),
  
  // Card Status: PIC X(01)
  cardStatus: Yup.string()
    .oneOf(
      [CARD_STATUS.ACTIVE, CARD_STATUS.EXPIRED, CARD_STATUS.BLOCKED, CARD_STATUS.CLOSED],
      'Invalid card status'
    )
    .length(1, 'Must be 1 character')
    .required('Card status is required'),
  
  // Expiration Date: PIC X(10)
  expirationDate: dateField()
    .test('future-date', 'Card expiration date must be in the future', (value) => {
      if (!value) return false;
      return new Date(value) > new Date();
    })
});

// =============================================================================
// TRANSACTION VALIDATION SCHEMA (from CVTRA01Y.cpy)
// =============================================================================

/**
 * Transaction validation schema preserving COBOL TRAN-CAT-BAL-RECORD structure
 * Source: app/cpy/CVTRA01Y.cpy
 * 
 * Fields validated:
 * - TRANCAT-ACCT-ID: PIC 9(11) - Account ID
 * - TRANCAT-TYPE-CD: PIC X(02) - Transaction type code
 * - TRANCAT-CD: PIC 9(04) - Transaction category code
 * - TRAN-CAT-BAL: PIC S9(09)V99 - Transaction balance/amount
 * - Transaction description: Alphanumeric description
 * - Transaction date: Date field
 */
export const transactionValidationSchema = Yup.object().shape({
  // Account ID: PIC 9(11)
  accountId: numericString(FIELD_LENGTHS.ACCOUNT_ID),
  
  // Transaction Type: PIC X(02)
  transactionType: Yup.string()
    .oneOf(Object.values(TRANSACTION_TYPES), 'Invalid transaction type')
    .length(FIELD_LENGTHS.TRANSACTION_TYPE, 'Must be 2 characters')
    .required('Transaction type is required'),
  
  // Transaction Category: PIC 9(04) combined with type (TTCCCC format)
  transactionCategory: Yup.string()
    .matches(/^\d{6}$/, 'Transaction category must be 6 digits')
    .length(FIELD_LENGTHS.TRANSACTION_CATEGORY, 'Must be 6 digits')
    .required('Transaction category is required'),
  
  // Transaction Amount: PIC S9(09)V99
  transactionAmount: currencyAmount()
    .min(0.01, 'Transaction amount must be greater than 0'),
  
  // Transaction Description
  description: alphanumericString(100, false),
  
  // Transaction Date: PIC X(10)
  transactionDate: dateField()
    .max(new Date(), 'Transaction date cannot be in the future')
});

// =============================================================================
// USER AUTHENTICATION VALIDATION SCHEMAS (from CSUSR01Y.cpy)
// =============================================================================

/**
 * Login validation schema preserving COBOL SEC-USER-DATA structure
 * Source: app/cpy/CSUSR01Y.cpy
 * 
 * Fields validated:
 * - SEC-USR-ID: PIC X(08) - User ID
 * - SEC-USR-PWD: PIC X(08) - User password
 */
export const loginValidationSchema = Yup.object().shape({
  // User ID: PIC X(8)
  userId: Yup.string()
    .min(1, 'User ID is required')
    .max(8, 'User ID must be at most 8 characters')
    .required('User ID is required'),
  
  // Password: PIC X(8)
  password: Yup.string()
    .min(1, 'Password is required')
    .max(8, 'Password must be at most 8 characters')
    .required('Password is required')
});

/**
 * User management validation schema for creating/updating users
 * Source: app/cpy/CSUSR01Y.cpy
 * 
 * Fields validated:
 * - SEC-USR-ID: PIC X(08) - User ID (3-8 characters for new users)
 * - SEC-USR-PWD: PIC X(08) - User password (4-8 characters)
 * - SEC-USR-TYPE: PIC X(01) - User type (R=Regular, A=Admin)
 * - SEC-USR-FNAME: PIC X(20) - First name
 * - SEC-USR-LNAME: PIC X(20) - Last name
 */
export const userManagementValidationSchema = Yup.object().shape({
  // User ID: PIC X(8)
  userId: Yup.string()
    .min(3, 'User ID must be at least 3 characters')
    .max(8, 'User ID must be at most 8 characters')
    .matches(/^[A-Za-z0-9]+$/, 'User ID must be alphanumeric')
    .required('User ID is required'),
  
  // Password: PIC X(8)
  password: Yup.string()
    .min(4, 'Password must be at least 4 characters')
    .max(8, 'Password must be at most 8 characters')
    .required('Password is required'),
  
  // User Type: PIC X(1)
  userType: Yup.string()
    .oneOf([USER_ROLES.USER, USER_ROLES.ADMIN], 'Invalid user role')
    .length(1, 'Must be 1 character')
    .required('User role is required'),
  
  // First Name: PIC X(20)
  firstName: alphabeticString(20, true),
  
  // Last Name: PIC X(20)
  lastName: alphabeticString(20, true)
});

// =============================================================================
// BILL PAYMENT VALIDATION SCHEMA
// =============================================================================

/**
 * Bill payment validation schema
 * Validates payment transactions with business rule checks
 * 
 * Fields validated:
 * - Account ID: PIC 9(11)
 * - Payment amount: Must be positive and not exceed account balance
 * - Payee name: Required alphanumeric field
 * - Payment date: Must be today or in the future
 */
export const billPaymentValidationSchema = Yup.object().shape({
  // Account ID: PIC 9(11)
  accountId: numericString(FIELD_LENGTHS.ACCOUNT_ID),
  
  // Payment Amount: PIC S9(10)V99
  paymentAmount: currencyAmount()
    .min(0.01, 'Payment amount must be greater than 0')
    .test('balance-check', 'Payment amount cannot exceed account balance', function(value) {
      const { accountBalance } = this.parent;
      if (accountBalance && value) {
        return value <= accountBalance;
      }
      return true;
    }),
  
  // Payee Name
  payeeName: alphanumericString(50, true),
  
  // Payment Date
  paymentDate: dateField()
    .min(new Date(), 'Payment date must be today or in the future')
});

// =============================================================================
// CUSTOM VALIDATION HELPER FUNCTIONS
// =============================================================================

/**
 * Validate that a field matches COBOL PIC 9(n) format
 * Used for numeric-only string validation
 * @param {string} value - Value to validate
 * @param {number} length - Required length
 * @returns {boolean} Whether value is valid
 */
export const isNumericField = (value, length) => {
  if (!value) return false;
  const regex = new RegExp(`^\\d{${length}}$`);
  return regex.test(value);
};

/**
 * Validate that a field matches COBOL PIC X(n) format
 * Used for alphanumeric string validation
 * @param {string} value - Value to validate
 * @param {number} maxLength - Maximum length
 * @returns {boolean} Whether value is valid
 */
export const isAlphanumericField = (value, maxLength) => {
  if (!value) return false;
  return value.length <= maxLength;
};

/**
 * Validate currency amount with 2 decimal places (COMP-3)
 * Ensures proper decimal precision per Agent Action Plan Section 0.9
 * Matches COBOL S9(10)V99 COMP-3 validation
 * @param {number} value - Value to validate
 * @returns {boolean} Whether value is valid
 */
export const isCurrencyValid = (value) => {
  if (value === null || value === undefined) return false;
  const decimalPart = String(value).split('.')[1];
  return !decimalPart || decimalPart.length <= 2;
};

/**
 * Validate date is in YYYY-MM-DD format
 * Matches COBOL PIC X(10) date field format
 * @param {string} value - Date string to validate
 * @returns {boolean} Whether date is valid
 */
export const isDateValid = (value) => {
  if (!value) return false;
  const regex = /^\d{4}-\d{2}-\d{2}$/;
  if (!regex.test(value)) return false;
  
  const date = new Date(value);
  return date instanceof Date && !isNaN(date.getTime());
};

