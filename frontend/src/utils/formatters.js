/**
 * CardDemo Application - Data Formatting Utilities
 * 
 * Data formatting utility functions for transforming data between display formats 
 * and API formats. Preserves COBOL field format compatibility and precision.
 * 
 * Source mappings:
 * - Date formats: app/cpy/CSDAT01Y.cpy (WS-CURDATE, WS-TIMESTAMP structures)
 * - Customer fields: app/cpy/CVCUS01Y.cpy (phone, SSN, customer ID)
 * - Card fields: app/cpy/CVCRD01Y.cpy (card number formatting)
 * - Account fields: app/cpy/CVACT01Y.cpy (account ID, currency amounts)
 * 
 * Per Agent Action Plan Section 0.9: Maintains COBOL COMP-3 precision (2 decimal places)
 * for all currency formatting and preserves exact date format conversions.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { format, parse, parseISO, isValid } from 'date-fns';
import { DATE_FORMATS, DECIMAL_PRECISION } from './constants';

// =============================================================================
// DATE FORMATTING FUNCTIONS
// Preserving COBOL date formats from CSDAT01Y.cpy
// =============================================================================

/**
 * Format date for display in MM/DD/YYYY format
 * Matches COBOL WS-CURDATE-MM-DD-YY format
 * 
 * @param {Date|string} date - Date object or ISO string
 * @returns {string} Formatted date string in MM/DD/YYYY format
 */
export const formatDateDisplay = (date) => {
  if (!date) return '';
  
  try {
    const dateObj = typeof date === 'string' ? parseISO(date) : date;
    return isValid(dateObj) ? format(dateObj, DATE_FORMATS.DISPLAY) : '';
  } catch (error) {
    console.error('Date formatting error:', error);
    return '';
  }
};

/**
 * Format date for API requests in YYYY-MM-DD format
 * Matches COBOL date storage format and ISO 8601 standard
 * 
 * @param {Date|string} date - Date object or display string
 * @returns {string} ISO date string in YYYY-MM-DD format
 */
export const formatDateForAPI = (date) => {
  if (!date) return '';
  
  try {
    let dateObj;
    if (typeof date === 'string') {
      // Try parsing MM/DD/YYYY format first
      dateObj = parse(date, DATE_FORMATS.DISPLAY, new Date());
      if (!isValid(dateObj)) {
        dateObj = parseISO(date);
      }
    } else {
      dateObj = date;
    }
    
    return isValid(dateObj) ? format(dateObj, DATE_FORMATS.API) : '';
  } catch (error) {
    console.error('Date API formatting error:', error);
    return '';
  }
};

/**
 * Format date to COBOL YYYYMMDD format
 * Matches COBOL WS-CURDATE-N PIC 9(08) format
 * 
 * @param {Date|string} date - Date object or ISO string
 * @returns {string} YYYYMMDD formatted string (8 digits, no delimiters)
 */
export const formatDateCobol = (date) => {
  if (!date) return '';
  
  try {
    const dateObj = typeof date === 'string' ? parseISO(date) : date;
    return isValid(dateObj) ? format(dateObj, DATE_FORMATS.COBOL) : '';
  } catch (error) {
    console.error('Date COBOL formatting error:', error);
    return '';
  }
};

/**
 * Format timestamp for display
 * Matches COBOL WS-TIMESTAMP format (YYYY-MM-DD HH:MM:SS)
 * 
 * @param {Date|string} date - Date object or ISO string
 * @returns {string} Formatted timestamp string
 */
export const formatTimestampDisplay = (date) => {
  if (!date) return '';
  
  try {
    const dateObj = typeof date === 'string' ? parseISO(date) : date;
    return isValid(dateObj) ? format(dateObj, DATE_FORMATS.TIMESTAMP) : '';
  } catch (error) {
    console.error('Timestamp formatting error:', error);
    return '';
  }
};

/**
 * Parse COBOL date format YYYYMMDD to JavaScript Date
 * Converts 8-digit COBOL date format to Date object
 * 
 * @param {string} cobolDate - Date in YYYYMMDD format (8 digits)
 * @returns {Date|null} Parsed date or null if invalid
 */
export const parseCobolDate = (cobolDate) => {
  if (!cobolDate || cobolDate.length !== 8) return null;
  
  try {
    const year = parseInt(cobolDate.substring(0, 4), 10);
    const month = parseInt(cobolDate.substring(4, 6), 10) - 1; // 0-indexed
    const day = parseInt(cobolDate.substring(6, 8), 10);
    
    const date = new Date(year, month, day);
    return isValid(date) ? date : null;
  } catch (error) {
    console.error('COBOL date parsing error:', error);
    return null;
  }
};

// =============================================================================
// CURRENCY FORMATTING FUNCTIONS
// Matching COBOL COMP-3 precision S9(10)V99 (2 decimal places)
// Per Agent Action Plan Section 0.9
// =============================================================================

/**
 * Format currency for display with $ symbol and 2 decimal places
 * Matches COBOL S9(10)V99 COMP-3 precision (per Section 0.9)
 * Used for ACCT-CURR-BAL, ACCT-CREDIT-LIMIT, and all monetary amounts
 * 
 * @param {number|string} amount - Amount to format
 * @returns {string} Formatted currency string (e.g., "$1,234.56")
 */
export const formatCurrency = (amount) => {
  if (amount === null || amount === undefined || amount === '') return '$0.00';
  
  try {
    const numAmount = typeof amount === 'string' ? parseFloat(amount) : amount;
    
    if (isNaN(numAmount)) return '$0.00';
    
    // Ensure 2 decimal precision matching COBOL COMP-3
    const formatted = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: DECIMAL_PRECISION.CURRENCY,
      maximumFractionDigits: DECIMAL_PRECISION.CURRENCY
    }).format(numAmount);
    
    return formatted;
  } catch (error) {
    console.error('Currency formatting error:', error);
    return '$0.00';
  }
};

/**
 * Format currency without $ symbol for input fields
 * Returns plain numeric string with 2 decimal places
 * 
 * @param {number|string} amount - Amount to format
 * @returns {string} Formatted amount string (e.g., "1234.56")
 */
export const formatCurrencyInput = (amount) => {
  if (amount === null || amount === undefined || amount === '') return '0.00';
  
  try {
    const numAmount = typeof amount === 'string' ? parseFloat(amount) : amount;
    
    if (isNaN(numAmount)) return '0.00';
    
    return numAmount.toFixed(DECIMAL_PRECISION.CURRENCY);
  } catch (error) {
    console.error('Currency input formatting error:', error);
    return '0.00';
  }
};

/**
 * Parse currency string to number for API
 * Removes currency symbols and commas, returns numeric value
 * 
 * @param {string} currencyStr - Currency string (e.g., "$1,234.56")
 * @returns {number} Parsed amount as number
 */
export const parseCurrency = (currencyStr) => {
  if (!currencyStr) return 0;
  
  try {
    // Remove currency symbols and commas
    const cleanStr = currencyStr.replace(/[$,]/g, '');
    const amount = parseFloat(cleanStr);
    
    return isNaN(amount) ? 0 : amount;
  } catch (error) {
    console.error('Currency parsing error:', error);
    return 0;
  }
};

// =============================================================================
// PHONE NUMBER FORMATTING FUNCTIONS
// Matching CUST-PHONE-NUM PIC X(15) from CVCUS01Y.cpy
// =============================================================================

/**
 * Format phone number as (XXX) XXX-XXXX
 * Matches CUST-PHONE-NUM PIC X(15) from CVCUS01Y.cpy
 * 
 * @param {string} phone - Phone number string
 * @returns {string} Formatted phone number (e.g., "(555) 123-4567")
 */
export const formatPhoneNumber = (phone) => {
  if (!phone) return '';
  
  try {
    // Remove all non-numeric characters
    const cleaned = phone.replace(/\D/g, '');
    
    // Format based on length
    if (cleaned.length === 10) {
      return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6)}`;
    } else if (cleaned.length === 11 && cleaned[0] === '1') {
      return `+1 (${cleaned.slice(1, 4)}) ${cleaned.slice(4, 7)}-${cleaned.slice(7)}`;
    } else if (cleaned.length > 0) {
      // Return as-is if doesn't match expected format
      return phone;
    }
    
    return '';
  } catch (error) {
    console.error('Phone formatting error:', error);
    return phone || '';
  }
};

/**
 * Remove phone number formatting for API submission
 * Strips all non-numeric characters
 * 
 * @param {string} phone - Formatted phone number
 * @returns {string} Unformatted phone number (numeric only)
 */
export const unformatPhoneNumber = (phone) => {
  if (!phone) return '';
  return phone.replace(/\D/g, '');
};

// =============================================================================
// CREDIT CARD NUMBER FORMATTING FUNCTIONS
// Matching CC-CARD-NUM PIC 9(16) from CVCRD01Y.cpy
// =============================================================================

/**
 * Format credit card number with masking (XXXX-XXXX-XXXX-1234)
 * Matches CC-CARD-NUM PIC 9(16) from CVCRD01Y.cpy
 * 
 * @param {string} cardNumber - Card number string
 * @param {boolean} masked - Whether to mask the number (default: false)
 * @returns {string} Formatted card number
 */
export const formatCardNumber = (cardNumber, masked = false) => {
  if (!cardNumber) return '';
  
  try {
    // Remove all non-numeric characters
    const cleaned = cardNumber.replace(/\D/g, '');
    
    if (cleaned.length !== 16) {
      return cardNumber; // Return as-is if not 16 digits
    }
    
    if (masked) {
      // Show only last 4 digits
      const lastFour = cleaned.slice(-4);
      return `XXXX-XXXX-XXXX-${lastFour}`;
    } else {
      // Format as XXXX-XXXX-XXXX-XXXX
      return cleaned.match(/.{1,4}/g).join('-');
    }
  } catch (error) {
    console.error('Card number formatting error:', error);
    return cardNumber || '';
  }
};

/**
 * Remove card number formatting for API submission
 * Strips all non-numeric characters
 * 
 * @param {string} cardNumber - Formatted card number
 * @returns {string} Unformatted card number (16 digits)
 */
export const unformatCardNumber = (cardNumber) => {
  if (!cardNumber) return '';
  return cardNumber.replace(/\D/g, '');
};

// =============================================================================
// ACCOUNT AND CUSTOMER ID FORMATTING FUNCTIONS
// =============================================================================

/**
 * Format account ID with leading zeros (11 digits)
 * Matches ACCT-ID PIC 9(11) from CVACT01Y.cpy
 * 
 * @param {string|number} accountId - Account ID
 * @returns {string} Formatted account ID (11 digits with leading zeros)
 */
export const formatAccountId = (accountId) => {
  if (!accountId) return '';
  
  try {
    const cleaned = String(accountId).replace(/\D/g, '');
    return cleaned.padStart(11, '0');
  } catch (error) {
    console.error('Account ID formatting error:', error);
    return String(accountId);
  }
};

/**
 * Format customer ID with leading zeros (9 digits)
 * Matches CUST-ID PIC 9(09) from CVCUS01Y.cpy
 * 
 * @param {string|number} customerId - Customer ID
 * @returns {string} Formatted customer ID (9 digits with leading zeros)
 */
export const formatCustomerId = (customerId) => {
  if (!customerId) return '';
  
  try {
    const cleaned = String(customerId).replace(/\D/g, '');
    return cleaned.padStart(9, '0');
  } catch (error) {
    console.error('Customer ID formatting error:', error);
    return String(customerId);
  }
};

// =============================================================================
// SSN FORMATTING FUNCTIONS
// Matching CUST-SSN PIC 9(09) from CVCUS01Y.cpy
// =============================================================================

/**
 * Format SSN as XXX-XX-XXXX
 * Matches CUST-SSN PIC 9(09) from CVCUS01Y.cpy
 * 
 * @param {string} ssn - SSN string
 * @param {boolean} masked - Whether to mask the SSN (default: true for security)
 * @returns {string} Formatted SSN
 */
export const formatSSN = (ssn, masked = true) => {
  if (!ssn) return '';
  
  try {
    const cleaned = ssn.replace(/\D/g, '');
    
    if (cleaned.length !== 9) {
      return ssn; // Return as-is if not 9 digits
    }
    
    if (masked) {
      // Only show last 4 digits for security
      return `XXX-XX-${cleaned.slice(-4)}`;
    } else {
      // Full SSN formatting (use with caution)
      return `${cleaned.slice(0, 3)}-${cleaned.slice(3, 5)}-${cleaned.slice(5)}`;
    }
  } catch (error) {
    console.error('SSN formatting error:', error);
    return ssn || '';
  }
};

// =============================================================================
// GENERAL DISPLAY UTILITY FUNCTIONS
// =============================================================================

/**
 * Truncate text with ellipsis
 * Used for displaying long text fields in constrained UI spaces
 * 
 * @param {string} text - Text to truncate
 * @param {number} maxLength - Maximum length before truncation
 * @returns {string} Truncated text with ellipsis if needed
 */
export const truncateText = (text, maxLength) => {
  if (!text) return '';
  if (text.length <= maxLength) return text;
  return `${text.substring(0, maxLength)}...`;
};

/**
 * Format null or undefined values for display
 * Provides consistent handling of empty values
 * 
 * @param {any} value - Value to format
 * @param {string} defaultValue - Default value if null/undefined (default: 'N/A')
 * @returns {string} Formatted value or default
 */
export const formatNullable = (value, defaultValue = 'N/A') => {
  if (value === null || value === undefined || value === '') {
    return defaultValue;
  }
  return String(value);
};

/**
 * Capitalize first letter of each word
 * Used for proper name formatting (customer names, etc.)
 * 
 * @param {string} text - Text to capitalize
 * @returns {string} Capitalized text
 */
export const capitalizeWords = (text) => {
  if (!text) return '';
  return text
    .toLowerCase()
    .split(' ')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
};

/**
 * Format percentage value
 * Converts decimal to percentage with specified decimal places
 * 
 * @param {number} value - Decimal value (e.g., 0.15 for 15%)
 * @param {number} decimals - Number of decimal places (default: 2)
 * @returns {string} Formatted percentage (e.g., "15.00%")
 */
export const formatPercentage = (value, decimals = 2) => {
  if (value === null || value === undefined) return '0%';
  
  try {
    const percentage = (value * 100).toFixed(decimals);
    return `${percentage}%`;
  } catch (error) {
    console.error('Percentage formatting error:', error);
    return '0%';
  }
};

