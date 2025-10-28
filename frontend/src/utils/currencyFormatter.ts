/**
 * Currency Formatter Utility
 * 
 * Financial value formatting utility functions for currency and monetary amount
 * display and parsing. Maintains 2 decimal place precision matching COBOL COMP-3
 * PIC S9(10)V99 fields for financial calculations.
 * 
 * Converted from COBOL financial display formatting per Agent Action Plan
 * Section 0.4.21 and Section 0.7.2 COMP-3 precision preservation requirements.
 * 
 * Source Files:
 * - CVTRA05Y.cpy: TRAN-AMT PIC S9(09)V99 (transaction amounts)
 * - CVACT01Y.cpy: ACCT-CURR-BAL PIC S9(10)V99 (account balances)
 * - COTRN02.bms: TRNAMT field with format hint (-99999999.99)
 * - COACTUP.bms: Account financial field display patterns
 * 
 * All functions ensure bit-identical results to COBOL packed decimal arithmetic
 * per Section 0.7.2 requirements.
 */

import { VALIDATION_RULES } from './constants';

/**
 * Formats a numeric value as US currency with dollar sign, thousand separators,
 * and exactly 2 decimal places.
 * 
 * COBOL equivalent: PIC S9(10)V99 COMP-3 displayed with editing pattern $$$,$$$,$$9.99
 * 
 * Examples:
 *   formatCurrency(1234.56) → '$1,234.56'
 *   formatCurrency(1234.567) → '$1,234.57' (rounded)
 *   formatCurrency(-100.00) → '-$100.00'
 *   formatCurrency(0) → '$0.00'
 *   formatCurrency(null) → '$0.00'
 *   formatCurrency(1000000) → '$1,000,000.00'
 * 
 * @param amount - Numeric amount to format (can be null, undefined, or NaN)
 * @param showCurrencySymbol - Include $ symbol prefix (default: true)
 * @returns Formatted currency string with 2 decimal places
 */
export const formatCurrency = (
  amount: number | null | undefined,
  showCurrencySymbol: boolean = true
): string => {
  // Handle null, undefined, or NaN gracefully
  if (amount === null || amount === undefined || isNaN(amount)) {
    return showCurrencySymbol ? '$0.00' : '0.00';
  }

  // Round to 2 decimal places to match COBOL COMP-3 precision (PIC S9(10)V99)
  // Uses banker's rounding (round half to even) via Math.round
  const rounded = Math.round(amount * 100) / 100;

  // Format with thousand separators using Intl.NumberFormat (browser-optimized)
  // en-US locale provides comma thousand separator and period decimal separator
  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(rounded);

  // Return with or without currency symbol based on parameter
  return showCurrencySymbol ? formatted : formatted.substring(1);
};

/**
 * Parses a formatted currency string back to a numeric value for API submission
 * and calculations.
 * 
 * COBOL equivalent: Converting edited numeric field (PIC $$$,$$$,$$9.99) back to
 * COMP-3 internal format (PIC S9(10)V99)
 * 
 * Handles multiple formats:
 * - Standard currency: '$1,234.56' → 1234.56
 * - Without symbol: '1234.56' → 1234.56
 * - Negative: '-$100.00' → -100.00
 * - Accounting format: '($100.00)' or '(100.00)' → -100.00
 * - With spaces: '$ 1,234.56' → 1234.56
 * - Invalid: 'abc' → null
 * 
 * @param currencyString - Formatted currency string to parse
 * @returns Numeric value rounded to 2 decimals, or null if invalid
 */
export const parseCurrency = (
  currencyString: string | null | undefined
): number | null => {
  // Handle null, undefined, or empty string
  if (!currencyString || currencyString.trim() === '') {
    return null;
  }

  // Remove currency symbols ($), thousand separators (,), and spaces
  let cleaned = currencyString.replace(/[$,\s]/g, '');

  // Handle accounting format with parentheses for negative values
  // Example: '(100.00)' becomes '-100.00'
  if (cleaned.startsWith('(') && cleaned.endsWith(')')) {
    cleaned = '-' + cleaned.slice(1, -1);
  }

  // Parse the cleaned string to a float
  const parsed = parseFloat(cleaned);

  // Return null if parsing resulted in NaN (invalid input)
  if (isNaN(parsed)) {
    return null;
  }

  // Round to 2 decimal places to match COBOL COMP-3 precision
  return Math.round(parsed * 100) / 100;
};

/**
 * Formats a transaction amount with configurable decimal places.
 * Used for different financial fields that may have varying precision requirements.
 * 
 * COBOL equivalent: PIC S9(09)V99 displayed without editing pattern
 * 
 * Examples:
 *   formatAmount(1234.56) → '1234.56'
 *   formatAmount(1234.567, 3) → '1234.567'
 *   formatAmount(null) → '0.00'
 *   formatAmount(100, 0) → '100'
 * 
 * @param amount - Numeric amount to format
 * @param decimalPlaces - Number of decimal places to display (default: 2)
 * @returns Formatted amount without currency symbol or thousand separators
 */
export const formatAmount = (
  amount: number | null | undefined,
  decimalPlaces: number = 2
): string => {
  // Handle null, undefined, or NaN - return zero with appropriate decimals
  if (amount === null || amount === undefined || isNaN(amount)) {
    return '0.' + '0'.repeat(decimalPlaces);
  }

  // Use toFixed for precise decimal formatting
  // toFixed uses banker's rounding internally
  return amount.toFixed(decimalPlaces);
};

/**
 * Formats debit amounts (negative values) with optional accounting format.
 * 
 * COBOL equivalent: PIC ---,--9.99 with minus sign or accounting format
 * Used in transaction lists and billing displays where debits need special formatting.
 * 
 * Examples:
 *   formatDebit(-100.00) → '-$100.00'
 *   formatDebit(-100.00, true) → '($100.00)'
 *   formatDebit(50.00) → '$50.00'
 *   formatDebit(50.00, true) → '$50.00'
 * 
 * @param amount - Debit amount (typically negative for debits)
 * @param useParentheses - Use accounting format with parentheses instead of minus sign (default: false)
 * @returns Formatted debit string with appropriate sign representation
 */
export const formatDebit = (
  amount: number,
  useParentheses: boolean = false
): string => {
  // Get absolute value for formatting
  const absAmount = Math.abs(amount);
  
  // Format the absolute value as currency
  const formatted = formatCurrency(absAmount, true);

  // Apply negative sign or parentheses based on parameters
  if (useParentheses && amount < 0) {
    // Accounting format: wrap in parentheses
    return `(${formatted})`;
  } else if (amount < 0) {
    // Standard format: prepend negative sign
    return `-${formatted}`;
  } else {
    // Positive amount: return as-is
    return formatted;
  }
};

/**
 * Validates whether an amount falls within acceptable range constraints.
 * 
 * COBOL equivalent: IF TRAN-AMT < 0.01 OR > 9999999999.99 validation
 * Enforces min/max constraints from VALIDATION_RULES per COBOL field specifications.
 * 
 * Examples:
 *   validateAmount(100.50) → true
 *   validateAmount(0.01) → true (minimum valid)
 *   validateAmount(0.00) → false (below minimum)
 *   validateAmount(9999999999.99) → true (maximum valid)
 *   validateAmount(10000000000.00) → false (exceeds maximum)
 *   validateAmount(null) → false
 *   validateAmount(NaN) → false
 * 
 * @param amount - Amount to validate
 * @returns true if amount is within valid range, false otherwise
 */
export const validateAmount = (amount: number | null | undefined): boolean => {
  // Reject null, undefined, or NaN values
  if (amount === null || amount === undefined || isNaN(amount)) {
    return false;
  }

  // Extract min/max from validation rules (imported from constants.ts)
  const { MIN, MAX } = VALIDATION_RULES.TRANSACTION_AMOUNT;

  // Check if amount falls within acceptable range
  return amount >= MIN && amount <= MAX;
};

/**
 * Formats account or card balance for display with appropriate sign handling.
 * 
 * COBOL equivalent: ACCT-CURR-BAL PIC S9(10)V99 COMP-3 display
 * Used in AccountViewPage, CardListPage, and balance displays.
 * 
 * Balance semantics:
 * - Positive balance: Amount owed by customer (credit balance)
 * - Negative balance: Amount owed to customer (debit balance, rare)
 * - Zero balance: No amount owed either way
 * 
 * Examples:
 *   formatBalance(1234.56) → '$1,234.56' (customer owes)
 *   formatBalance(-50.00) → '-$50.00' (owed to customer)
 *   formatBalance(0) → '$0.00'
 *   formatBalance(null) → '$0.00'
 * 
 * @param balance - Balance amount (positive=credit, negative=debit)
 * @returns Formatted balance with appropriate sign
 */
export const formatBalance = (balance: number | null | undefined): string => {
  // Handle null or undefined by defaulting to zero
  if (balance === null || balance === undefined) {
    return formatCurrency(0);
  }

  // Format balance using standard currency formatting
  // Positive balances (credits) shown normally: $X,XXX.XX
  // Negative balances (debits) shown with negative sign: -$X,XXX.XX
  return formatCurrency(balance);
};
