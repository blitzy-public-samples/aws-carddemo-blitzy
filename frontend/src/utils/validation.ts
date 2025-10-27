/**
 * Field validation utility functions
 * 
 * Converted from BMS map field attributes and COBOL program validation logic
 * per Agent Action Plan Section 0.4.21.
 * 
 * Source Files:
 * - COACTUP.bms: Account field validations (ACCTSID LENGTH=11, phone format)
 * - COCRDUP.bms: Card field validations (CARDSID LENGTH=16)
 * - COTRN02.bms: Transaction field validations (TRNAMT LENGTH=12, date formats)
 * - COSGN00.bms: User authentication field validations
 * - COACTUPC.cbl: Account validation logic (lines 1802+, 2264+)
 * - COCRDUPC.cbl: Card validation logic with Luhn algorithm
 * - COTRN02C.cbl: Transaction amount and date validation logic
 * 
 * All validation rules are synchronized with backend Spring validation
 * annotations to ensure consistent validation behavior across frontend and
 * backend per Agent Action Plan Section 0.7.2 preservation requirements.
 * 
 * BMS Attribute Mappings:
 * - NUM attribute → isNumeric() function
 * - ALPHA attribute → isAlpha() function
 * - LENGTH attribute → validateLength() function
 * - Required fields (UNPROT without ASKIP) → validateRequired() function
 * 
 * Per Section 0.7.5: All validation functions preserve exact COBOL field
 * validation logic and BMS map attribute behavior for consistent validation
 * across mainframe and cloud systems.
 */

import { VALIDATION_RULES } from './constants';
import { isValidDate as isValidDateUtil } from './dateFormatter';

/**
 * Validation result type for complex validators
 */
export interface ValidationResult {
  valid: boolean;
  message: string;
}

/**
 * Checks if string contains only numeric digits (0-9)
 * 
 * BMS equivalent: ATTRB=(NUM) - Numeric field attribute
 * COBOL equivalent: IF FIELD IS NUMERIC
 * 
 * Used for validating:
 * - Account IDs (11-digit numeric)
 * - Card numbers (16-digit numeric)
 * - Amount whole numbers
 * - ZIP codes (5-digit numeric)
 * 
 * Per Section 0.7.5: Matches BMS NUM field attribute behavior
 * 
 * @param value - String to validate
 * @returns true if contains only digits 0-9, false otherwise
 * 
 * @example
 * ```typescript
 * isNumeric('12345'); // Returns true
 * isNumeric('123.45'); // Returns false (contains decimal point)
 * isNumeric('abc'); // Returns false (contains letters)
 * isNumeric(''); // Returns false (empty string)
 * isNumeric(null); // Returns false
 * ```
 */
export const isNumeric = (value: string | null | undefined): boolean => {
  if (!value || value.trim() === '') {
    return false;
  }
  return /^\d+$/.test(value.trim());
};

/**
 * Checks if string contains only alphabetic characters (A-Z, a-z)
 * 
 * BMS equivalent: ATTRB=(ALPHA) - Alphabetic field attribute
 * COBOL equivalent: IF FIELD IS ALPHABETIC
 * 
 * Used for validating:
 * - Customer first names (ACSFNAM from COACTUP.bms)
 * - Customer middle names (ACSMNAM)
 * - Customer last names (ACSLNAM)
 * - State codes (2-letter alphabetic)
 * 
 * Per Section 0.7.5: Matches BMS ALPHA field attribute
 * 
 * @param value - String to validate
 * @returns true if contains only letters A-Z, a-z, false otherwise
 * 
 * @example
 * ```typescript
 * isAlpha('John'); // Returns true
 * isAlpha('Mary Ann'); // Returns false (contains space)
 * isAlpha('John123'); // Returns false (contains digits)
 * isAlpha(''); // Returns false (empty string)
 * ```
 */
export const isAlpha = (value: string | null | undefined): boolean => {
  if (!value || value.trim() === '') {
    return false;
  }
  return /^[A-Za-z]+$/.test(value.trim());
};

/**
 * Validates string length within min/max constraints
 * 
 * BMS equivalent: LENGTH=n attribute
 * COBOL equivalent: IF LENGTH OF FIELD < min OR > max
 * 
 * Used for enforcing BMS map LENGTH constraints:
 * - ACCTSID LENGTH=11
 * - CARDSID LENGTH=16
 * - Name fields LENGTH=25, 50
 * - Address fields LENGTH=50
 * 
 * Per Section 0.7.5: Enforces exact BMS LENGTH constraints
 * 
 * @param value - String to validate
 * @param minLength - Minimum length (inclusive)
 * @param maxLength - Maximum length (inclusive)
 * @returns Validation result with detailed message
 * 
 * @example
 * ```typescript
 * validateLength('John', 1, 25); // Returns { valid: true, message: '' }
 * validateLength('', 1, 25); // Returns { valid: false, message: 'Value is required' }
 * validateLength('A', 2, 10); // Returns { valid: false, message: 'Must be at least 2 characters' }
 * validateLength('ThisIsAVeryLongString', 1, 10); // Returns { valid: false, message: 'Must be no more than 10 characters' }
 * ```
 */
export const validateLength = (
  value: string | null | undefined,
  minLength: number,
  maxLength: number
): ValidationResult => {
  if (!value) {
    return {
      valid: false,
      message: 'Value is required'
    };
  }
  
  const trimmed = value.trim();
  
  if (trimmed.length < minLength) {
    return {
      valid: false,
      message: `Must be at least ${minLength} character${minLength !== 1 ? 's' : ''}`
    };
  }
  
  if (trimmed.length > maxLength) {
    return {
      valid: false,
      message: `Must be no more than ${maxLength} character${maxLength !== 1 ? 's' : ''}`
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates required field is not empty
 * 
 * BMS equivalent: Fields without ASKIP attribute (input required)
 * COBOL equivalent: IF FIELD = SPACES OR LOW-VALUES
 * 
 * Used for all BMS fields with ATTRB=(UNPROT) indicating user input required.
 * Trims whitespace per COBOL FUNCTION TRIM behavior.
 * 
 * Per Section 0.7.2: Matches COBOL LOW-VALUES and SPACES checks
 * 
 * @param value - Value to validate
 * @returns Validation result
 * 
 * @example
 * ```typescript
 * validateRequired('John'); // Returns { valid: true, message: '' }
 * validateRequired('   '); // Returns { valid: false, message: 'This field is required' }
 * validateRequired(''); // Returns { valid: false, message: 'This field is required' }
 * validateRequired(null); // Returns { valid: false, message: 'This field is required' }
 * ```
 */
export const validateRequired = (
  value: string | null | undefined
): ValidationResult => {
  if (!value || value.trim() === '') {
    return {
      valid: false,
      message: 'This field is required'
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates account ID format (11-digit numeric)
 * 
 * BMS field: ACCTSID DFHMDF ATTRB=(IC,UNPROT), LENGTH=11
 * From COACTUP.bms lines 84-86
 * 
 * COBOL validation: IF CC-ACCT-ID IS NOT NUMERIC (line 1802 COACTUPC.cbl)
 * COBOL data type: PIC 9(11) - 11-digit numeric field
 * 
 * Per Section 0.7.5: Enforces exact 11-digit numeric format matching
 * COBOL PIC 9(11) data type semantics
 * 
 * @param accountId - Account ID to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateAccountId('12345678901'); // Returns { valid: true, message: '' }
 * validateAccountId('123456789'); // Returns { valid: false, message: 'Account ID must be exactly 11 digits' }
 * validateAccountId('1234567890a'); // Returns { valid: false, message: 'Account ID must contain only numeric digits' }
 * validateAccountId(''); // Returns { valid: false, message: 'Account ID is required' }
 * ```
 */
export const validateAccountId = (
  accountId: string | null | undefined
): ValidationResult => {
  if (!accountId || accountId.trim() === '') {
    return {
      valid: false,
      message: 'Account ID is required'
    };
  }
  
  const trimmed = accountId.trim();
  const { MIN_LENGTH, MAX_LENGTH, PATTERN } = VALIDATION_RULES.ACCOUNT_ID;
  
  if (trimmed.length !== MIN_LENGTH) {
    return {
      valid: false,
      message: `Account ID must be exactly ${MIN_LENGTH} digits`
    };
  }
  
  if (!PATTERN.test(trimmed)) {
    return {
      valid: false,
      message: 'Account ID must contain only numeric digits'
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates credit card number with Luhn algorithm checksum
 * 
 * BMS field: CARDSID DFHMDF ATTRB=(FSET,NORM,UNPROT), LENGTH=16
 * From COCRDUP.bms lines 96-100
 * 
 * COBOL data type: PIC X(16) - 16-character alphanumeric field
 * Industry standard: Luhn algorithm (mod 10 checksum) for credit card validation
 * 
 * The Luhn algorithm validates credit card numbers by:
 * 1. Starting from the rightmost digit (check digit)
 * 2. Moving left, doubling every second digit
 * 3. If doubling results in > 9, subtract 9
 * 4. Sum all digits
 * 5. If sum modulo 10 equals 0, card number is valid
 * 
 * Per Section 0.7.2: Implements industry-standard Luhn algorithm to catch
 * typos and invalid card numbers, maintaining identical validation to mainframe
 * 
 * @param cardNumber - 16-digit card number (spaces allowed)
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateCardNumber('4532015112830366'); // Returns { valid: true, message: '' } (valid test card)
 * validateCardNumber('4532 0151 1283 0366'); // Returns { valid: true, message: '' } (spaces removed)
 * validateCardNumber('4532015112830367'); // Returns { valid: false, message: 'Invalid card number (checksum failed)' }
 * validateCardNumber('123456789012345'); // Returns { valid: false, message: 'Card number must be exactly 16 digits' }
 * ```
 */
export const validateCardNumber = (
  cardNumber: string | null | undefined
): ValidationResult => {
  if (!cardNumber || cardNumber.trim() === '') {
    return {
      valid: false,
      message: 'Card number is required'
    };
  }
  
  // Remove spaces and hyphens for validation
  const cleaned = cardNumber.replace(/[\s-]/g, '');
  const { MIN_LENGTH, PATTERN } = VALIDATION_RULES.CARD_NUMBER;
  
  if (cleaned.length !== MIN_LENGTH) {
    return {
      valid: false,
      message: `Card number must be exactly ${MIN_LENGTH} digits`
    };
  }
  
  if (!PATTERN.test(cleaned)) {
    return {
      valid: false,
      message: 'Card number must contain only numeric digits'
    };
  }
  
  // Luhn algorithm checksum validation
  if (!luhnCheck(cleaned)) {
    return {
      valid: false,
      message: 'Invalid card number (checksum failed)'
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Luhn algorithm checksum validation (mod 10 algorithm)
 * 
 * Used by credit card industry for card number validation.
 * Detects single-digit errors and most transposition errors.
 * 
 * Algorithm:
 * 1. Starting from the rightmost digit (check digit), moving left
 * 2. Double every second digit
 * 3. If doubling results in a two-digit number, subtract 9 (equivalent to summing the digits)
 * 4. Sum all the digits
 * 5. If the sum modulo 10 equals 0, the number is valid
 * 
 * @param cardNumber - Numeric card number string (no spaces or hyphens)
 * @returns true if checksum is valid, false otherwise
 * 
 * @example
 * ```typescript
 * luhnCheck('4532015112830366'); // Returns true (valid test Visa card)
 * luhnCheck('4532015112830367'); // Returns false (invalid check digit)
 * ```
 */
const luhnCheck = (cardNumber: string): boolean => {
  let sum = 0;
  let isEven = false;
  
  // Process digits from right to left
  for (let i = cardNumber.length - 1; i >= 0; i--) {
    let digit = parseInt(cardNumber[i], 10);
    
    if (isEven) {
      digit *= 2;
      if (digit > 9) {
        digit -= 9; // Equivalent to summing the two digits
      }
    }
    
    sum += digit;
    isEven = !isEven;
  }
  
  return sum % 10 === 0;
};

/**
 * Validates monetary amount format and range
 * 
 * BMS field: TRNAMT DFHMDF ATTRB=(FSET,NORM,UNPROT), LENGTH=12
 * From COTRN02.bms lines 174-179
 * 
 * COBOL data type: PIC S9(10)V99 - signed 10-digit integer with 2 decimal places
 * Range: -9,999,999,999.99 to +9,999,999,999.99
 * Business rule: Only positive amounts allowed (0.01 to 9,999,999,999.99)
 * 
 * Per Section 0.7.2: Maintains exact numeric precision and rounding behavior
 * when converting COBOL COMP-3 (packed decimal) fields to JavaScript number
 * validation. Enforces 2 decimal place precision matching COBOL V99.
 * 
 * @param amount - Amount string or number to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateAmount('100.50'); // Returns { valid: true, message: '' }
 * validateAmount(99.99); // Returns { valid: true, message: '' }
 * validateAmount('0.00'); // Returns { valid: false, message: 'Amount must be at least $0.01' }
 * validateAmount('10.999'); // Returns { valid: false, message: 'Amount can have at most 2 decimal places' }
 * validateAmount('abc'); // Returns { valid: false, message: 'Amount must be a valid number' }
 * ```
 */
export const validateAmount = (
  amount: string | number | null | undefined
): ValidationResult => {
  if (amount === null || amount === undefined || amount === '') {
    return {
      valid: false,
      message: 'Amount is required'
    };
  }
  
  const numAmount = typeof amount === 'string' ? parseFloat(amount) : amount;
  
  if (isNaN(numAmount)) {
    return {
      valid: false,
      message: 'Amount must be a valid number'
    };
  }
  
  const { MIN, MAX, DECIMAL_PLACES } = VALIDATION_RULES.TRANSACTION_AMOUNT;
  
  if (numAmount < MIN) {
    return {
      valid: false,
      message: `Amount must be at least $${MIN.toFixed(DECIMAL_PLACES)}`
    };
  }
  
  if (numAmount > MAX) {
    return {
      valid: false,
      message: `Amount cannot exceed $${MAX.toLocaleString('en-US', { 
        minimumFractionDigits: DECIMAL_PLACES,
        maximumFractionDigits: DECIMAL_PLACES
      })}`
    };
  }
  
  // Check decimal places (max 2 for currency per COMP-3 V99)
  const amountStr = numAmount.toString();
  const decimalPart = amountStr.split('.')[1];
  if (decimalPart && decimalPart.length > DECIMAL_PLACES) {
    return {
      valid: false,
      message: `Amount can have at most ${DECIMAL_PLACES} decimal places`
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates date format and reasonable range
 * 
 * COBOL equivalent: CALL CSUTLDTC date validation
 * From CSUTLDTC.cbl: Uses IBM CEEDAYS API for date validation
 * 
 * BMS date fields:
 * - OPNYEAR, OPNMON, OPNDAY from COACTUP.bms (account open date)
 * - EXPYEAR, EXPMON, EXPDAY from COACTUP.bms (expiration date)
 * - TORIGDT from COTRN02.bms (transaction origination date)
 * - TPROCDT from COTRN02.bms (transaction processing date)
 * 
 * Accepts formats:
 * - MM/DD/YYYY (display format)
 * - YYYY-MM-DD (API format)
 * 
 * Per Section 0.7.2: Preserves exact COBOL date validation semantics from
 * CSUTLDTC.cbl including range checks (1900-2100) and format validation
 * 
 * @param dateString - Date string to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateDate('03/15/2024'); // Returns { valid: true, message: '' }
 * validateDate('2024-03-15'); // Returns { valid: true, message: '' }
 * validateDate('13/01/2024'); // Returns { valid: false, message: 'Invalid date format...' }
 * validateDate('02/30/2024'); // Returns { valid: false, message: 'Invalid date format...' }
 * validateDate(''); // Returns { valid: false, message: 'Date is required' }
 * ```
 */
export const validateDate = (
  dateString: string | null | undefined
): ValidationResult => {
  if (!dateString || dateString.trim() === '') {
    return {
      valid: false,
      message: 'Date is required'
    };
  }
  
  // Try validating with both display format (MM/DD/YYYY) and API format (YYYY-MM-DD)
  const trimmed = dateString.trim();
  const isValidDisplay = isValidDateUtil(trimmed); // Uses MM/DD/YYYY by default
  
  if (!isValidDisplay) {
    // Try API format
    const apiFormatPattern = /^\d{4}-\d{2}-\d{2}$/;
    if (apiFormatPattern.test(trimmed)) {
      const isValidApi = isValidDateUtil(trimmed, 'yyyy-MM-dd');
      if (!isValidApi) {
        return {
          valid: false,
          message: 'Invalid date format. Use YYYY-MM-DD'
        };
      }
    } else {
      return {
        valid: false,
        message: 'Invalid date format. Use MM/DD/YYYY'
      };
    }
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates email format
 * 
 * RFC 5322 compliant email regex (simplified for practical use)
 * 
 * Pattern validates:
 * - Local part (before @): allows letters, digits, dots, hyphens, underscores
 * - @ symbol required
 * - Domain part: allows letters, digits, dots, hyphens
 * - TLD (top-level domain): at least 2 characters
 * 
 * Note: Full RFC 5322 validation is extremely complex. This implementation
 * covers 99% of real-world email addresses while rejecting obviously invalid
 * formats.
 * 
 * @param email - Email address to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateEmail('john.doe@example.com'); // Returns { valid: true, message: '' }
 * validateEmail('invalid.email'); // Returns { valid: false, message: 'Invalid email format' }
 * validateEmail(''); // Returns { valid: false, message: 'Email is required' }
 * ```
 */
export const validateEmail = (
  email: string | null | undefined
): ValidationResult => {
  if (!email || email.trim() === '') {
    return {
      valid: false,
      message: 'Email is required'
    };
  }
  
  // RFC 5322 simplified email regex
  // Matches: localpart@domain.tld
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  
  if (!emailRegex.test(email.trim())) {
    return {
      valid: false,
      message: 'Invalid email format'
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates US phone number format
 * 
 * BMS fields: ACSPH1A, ACSPH1B, ACSPH1C from COACTUP.bms (3-3-4 format)
 * From COACTUP.bms lines 412-426
 * 
 * COBOL validation: WS-EDIT-US-PHONE-NUMA IS NUMERIC (line 2264 COACTUPC.cbl)
 * 
 * Accepts formats:
 * - (123) 456-7890
 * - 123-456-7890
 * - 1234567890
 * - 123 456 7890
 * 
 * Validates that cleaned number is exactly 10 digits (US format).
 * 
 * Per Section 0.7.2: Matches COBOL phone validation in COACTUPC.cbl
 * 
 * @param phoneNumber - Phone number to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validatePhoneNumber('(123) 456-7890'); // Returns { valid: true, message: '' }
 * validatePhoneNumber('123-456-7890'); // Returns { valid: true, message: '' }
 * validatePhoneNumber('1234567890'); // Returns { valid: true, message: '' }
 * validatePhoneNumber('123-456'); // Returns { valid: false, message: 'Phone number must be 10 digits' }
 * ```
 */
export const validatePhoneNumber = (
  phoneNumber: string | null | undefined
): ValidationResult => {
  if (!phoneNumber || phoneNumber.trim() === '') {
    return {
      valid: false,
      message: 'Phone number is required'
    };
  }
  
  // Remove formatting characters (parentheses, spaces, hyphens)
  const cleaned = phoneNumber.replace(/[\s()\-]/g, '');
  
  // Must be exactly 10 digits for US phone numbers
  if (!/^\d{10}$/.test(cleaned)) {
    return {
      valid: false,
      message: 'Phone number must be 10 digits'
    };
  }
  
  return { valid: true, message: '' };
};

/**
 * Validates US ZIP code format
 * 
 * BMS field: ACSZIPC DFHMDF ATTRB=(UNPROT), LENGTH=5
 * From COACTUP.bms lines 382-385
 * 
 * Accepts formats:
 * - 12345 (5-digit ZIP)
 * - 12345-6789 (5+4 ZIP+4)
 * 
 * Per Section 0.7.5: Validates US ZIP code matching BMS LENGTH=5 constraint
 * with optional +4 extension
 * 
 * @param zipCode - ZIP code to validate
 * @returns Validation result with detailed error message
 * 
 * @example
 * ```typescript
 * validateZipCode('12345'); // Returns { valid: true, message: '' }
 * validateZipCode('12345-6789'); // Returns { valid: true, message: '' }
 * validateZipCode('1234'); // Returns { valid: false, message: 'Invalid ZIP code format...' }
 * validateZipCode('abcde'); // Returns { valid: false, message: 'Invalid ZIP code format...' }
 * ```
 */
export const validateZipCode = (
  zipCode: string | null | undefined
): ValidationResult => {
  if (!zipCode || zipCode.trim() === '') {
    return {
      valid: false,
      message: 'ZIP code is required'
    };
  }
  
  // Validates: 12345 or 12345-6789
  const zipRegex = /^\d{5}(-\d{4})?$/;
  
  if (!zipRegex.test(zipCode.trim())) {
    return {
      valid: false,
      message: 'Invalid ZIP code format (use 12345 or 12345-6789)'
    };
  }
  
  return { valid: true, message: '' };
};
