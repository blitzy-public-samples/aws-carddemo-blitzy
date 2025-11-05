/**
 * CardDemo Application Constants
 * 
 * Application-wide constant definitions migrated from COBOL source files.
 * Maintains functional equivalence with mainframe CardDemo application.
 * 
 * Source mappings:
 * - Transaction types: app/data/ASCII/trantype.txt
 * - Transaction categories: app/data/ASCII/trancatg.txt
 * - Field lengths: app/cpy/CVCUS01Y.cpy, app/cpy/CVACT01Y.cpy, app/cpy/CVCRD01Y.cpy
 * - Messages: app/cpy/CSMSG01Y.cpy, app/cpy/CSMSG02Y.cpy
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

// =============================================================================
// TRANSACTION TYPE CONSTANTS (from app/data/ASCII/trantype.txt)
// =============================================================================

/**
 * Transaction type codes matching COBOL reference data
 * Maps to transaction type file records
 */
export const TRANSACTION_TYPES = {
  PURCHASE: '01',
  PAYMENT: '02',
  CREDIT: '03',
  AUTHORIZATION: '04',
  REFUND: '05',
  REVERSAL: '06',
  ADJUSTMENT: '07'
};

/**
 * Human-readable labels for transaction types
 * Used for UI display purposes
 */
export const TRANSACTION_TYPE_LABELS = {
  '01': 'Purchase',
  '02': 'Payment',
  '03': 'Credit',
  '04': 'Authorization',
  '05': 'Refund',
  '06': 'Reversal',
  '07': 'Adjustment'
};

// =============================================================================
// TRANSACTION CATEGORY CONSTANTS (from app/data/ASCII/trancatg.txt)
// =============================================================================

/**
 * Transaction category codes with full granularity
 * Format: TTCCCC where TT = transaction type, CCCC = category code
 * Maps to transaction category file records
 */
export const TRANSACTION_CATEGORIES = {
  // Purchase categories (01xxxx)
  PURCHASE_REGULAR_SALES: '010001',
  PURCHASE_CASH_ADVANCE: '010002',
  PURCHASE_CONVENIENCE_CHECK: '010003',
  PURCHASE_ATM_CASH: '010004',
  PURCHASE_INTEREST: '010005',
  
  // Payment categories (02xxxx)
  PAYMENT_CASH: '020001',
  PAYMENT_ELECTRONIC: '020002',
  PAYMENT_CHECK: '020003',
  
  // Credit categories (03xxxx)
  CREDIT_TO_ACCOUNT: '030001',
  CREDIT_TO_PURCHASE: '030002',
  CREDIT_TO_CASH: '030003',
  
  // Authorization categories (04xxxx)
  AUTH_ZERO_DOLLAR: '040001',
  AUTH_ONLINE_PURCHASE: '040002',
  AUTH_TRAVEL_BOOKING: '040003',
  
  // Refund categories (05xxxx)
  REFUND_CREDIT: '050001',
  
  // Reversal categories (06xxxx)
  REVERSAL_FRAUD: '060001',
  REVERSAL_NON_FRAUD: '060002',
  
  // Adjustment categories (07xxxx)
  ADJUSTMENT_SALES_DRAFT: '070001'
};

// =============================================================================
// CARD STATUS CONSTANTS
// =============================================================================

/**
 * Card status codes from COBOL card record structures
 * Single character codes per mainframe convention
 */
export const CARD_STATUS = {
  ACTIVE: 'A',
  EXPIRED: 'E',
  BLOCKED: 'B',
  CLOSED: 'C'
};

/**
 * Human-readable labels for card status codes
 */
export const CARD_STATUS_LABELS = {
  A: 'Active',
  E: 'Expired',
  B: 'Blocked',
  C: 'Closed'
};

// =============================================================================
// ACCOUNT STATUS CONSTANTS (from app/cpy/CVACT01Y.cpy)
// =============================================================================

/**
 * Account active status codes
 * Maps to ACCT-ACTIVE-STATUS field (PIC X(01))
 */
export const ACCOUNT_STATUS = {
  ACTIVE: 'Y',
  INACTIVE: 'N'
};

// =============================================================================
// USER ROLE CONSTANTS
// =============================================================================

/**
 * User role codes from USRSEC file structure
 * Two-tier role model: Regular User and Administrative User
 */
export const USER_ROLES = {
  USER: 'R',      // Regular User
  ADMIN: 'A'      // Administrative User
};

/**
 * Human-readable labels for user roles
 */
export const USER_ROLE_LABELS = {
  R: 'Regular User',
  A: 'Administrator'
};

// =============================================================================
// ERROR CODE CONSTANTS
// =============================================================================

/**
 * Application error codes
 * Maps to CICS response codes and business validation errors
 */
export const ERROR_CODES = {
  INVALID_KEY: 'ERR_001',
  AUTHENTICATION_FAILED: 'ERR_002',
  ACCOUNT_NOT_FOUND: 'ERR_003',
  CARD_NOT_FOUND: 'ERR_004',
  TRANSACTION_FAILED: 'ERR_005',
  INSUFFICIENT_BALANCE: 'ERR_006',
  INVALID_INPUT: 'ERR_007',
  UNAUTHORIZED: 'ERR_008',
  SERVER_ERROR: 'ERR_009',
  CARD_EXPIRED: 'ERR_010',
  CARD_BLOCKED: 'ERR_011'
};

/**
 * Error messages mapped to error codes
 * From app/cpy/CSMSG01Y.cpy and app/cpy/CSMSG02Y.cpy
 */
export const ERROR_MESSAGES = {
  [ERROR_CODES.INVALID_KEY]: 'Invalid key pressed. Please see below...',
  [ERROR_CODES.AUTHENTICATION_FAILED]: 'Invalid username or password',
  [ERROR_CODES.ACCOUNT_NOT_FOUND]: 'Account not found',
  [ERROR_CODES.CARD_NOT_FOUND]: 'Card not found',
  [ERROR_CODES.TRANSACTION_FAILED]: 'Transaction processing failed',
  [ERROR_CODES.INSUFFICIENT_BALANCE]: 'Insufficient account balance',
  [ERROR_CODES.INVALID_INPUT]: 'Invalid input data',
  [ERROR_CODES.UNAUTHORIZED]: 'Unauthorized access',
  [ERROR_CODES.SERVER_ERROR]: 'Server error occurred',
  [ERROR_CODES.CARD_EXPIRED]: 'Card has expired',
  [ERROR_CODES.CARD_BLOCKED]: 'Card is blocked'
};

// =============================================================================
// API ENDPOINT CONSTANTS
// =============================================================================

/**
 * Backend REST API endpoint URLs
 * Maps CICS transaction IDs to REST endpoints per Agent Action Plan Section 0.6
 * 
 * COBOL Transaction → REST Endpoint mappings:
 * - CC00 (Sign-on) → /api/auth/*
 * - CM00 (Menu) → /api/menu
 * - CA00 (Admin) → /api/admin/*
 */
const API_BASE_URL = process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080/api';

export const API_ENDPOINTS = {
  // Authentication endpoints (COSGN00C → AuthenticationController)
  AUTH: {
    LOGIN: `${API_BASE_URL}/auth/login`,
    LOGOUT: `${API_BASE_URL}/auth/logout`,
    REFRESH: `${API_BASE_URL}/auth/refresh`
  },
  
  // Menu endpoints (COMEN01C → MenuController)
  MENU: {
    GET: `${API_BASE_URL}/menu`
  },
  
  // Account endpoints (COACTVWC/COACTUPC/COACTADD → AccountController)
  ACCOUNTS: {
    BASE: `${API_BASE_URL}/accounts`,
    GET_BY_ID: (id) => `${API_BASE_URL}/accounts/${id}`,
    CREATE: `${API_BASE_URL}/accounts`,
    UPDATE: (id) => `${API_BASE_URL}/accounts/${id}`,
    DELETE: (id) => `${API_BASE_URL}/accounts/${id}`
  },
  
  // Card endpoints (COCRDLIC/COCRDSLC/COCRDUPC → CardController)
  CARDS: {
    BASE: `${API_BASE_URL}/cards`,
    GET_BY_ID: (id) => `${API_BASE_URL}/cards/${id}`,
    GET_BY_ACCOUNT: (accountId) => `${API_BASE_URL}/accounts/${accountId}/cards`,
    UPDATE: (id) => `${API_BASE_URL}/cards/${id}`
  },
  
  // Transaction endpoints (COTRN00C/COTRN01C/COTRN02C → TransactionController)
  TRANSACTIONS: {
    BASE: `${API_BASE_URL}/transactions`,
    GET_BY_ID: (id) => `${API_BASE_URL}/transactions/${id}`,
    GET_BY_ACCOUNT: (accountId) => `${API_BASE_URL}/accounts/${accountId}/transactions`,
    CATEGORIES: `${API_BASE_URL}/transactions/categories`,
    CREATE: `${API_BASE_URL}/transactions`
  },
  
  // Bill payment endpoints (COBIL00C → BillPaymentController)
  PAYMENTS: {
    CREATE: `${API_BASE_URL}/payments/bill`
  },
  
  // Report endpoints (CORPT00C → ReportController)
  REPORTS: {
    BASE: `${API_BASE_URL}/reports`,
    ACCOUNT: `${API_BASE_URL}/reports/account`,
    TRANSACTION: `${API_BASE_URL}/reports/transaction`,
    CARD: `${API_BASE_URL}/reports/card`
  },
  
  // Admin endpoints (COADM01C → AdminController)
  ADMIN: {
    BASE: `${API_BASE_URL}/admin`
  },
  
  // User management endpoints (COUSR00C/COUSR01C → UserController)
  USERS: {
    BASE: `${API_BASE_URL}/users`,
    GET_BY_ID: (id) => `${API_BASE_URL}/users/${id}`,
    PROFILE: `${API_BASE_URL}/users/profile`
  }
};

// =============================================================================
// PAGINATION CONSTANTS (Per Agent Action Plan Section 0.9)
// =============================================================================

/**
 * Pagination configuration matching mainframe screen display limits
 * COCRDLIC displays 7 cards per page
 * COTRN00C displays 10 transactions per page
 */
export const PAGINATION = {
  CARDS_PER_PAGE: 7,           // Per BMS screen COCRDLIM capacity
  TRANSACTIONS_PER_PAGE: 10,   // Per BMS screen COTRN00M capacity
  DEFAULT_PAGE: 1
};

// =============================================================================
// FIELD LENGTH VALIDATION CONSTANTS (from COBOL PIC clauses)
// =============================================================================

/**
 * Maximum field lengths from COBOL copybook definitions
 * Used for client-side validation to match backend constraints
 * 
 * Source: app/cpy/CVCUS01Y.cpy, app/cpy/CVACT01Y.cpy, app/cpy/CVCRD01Y.cpy
 */
export const FIELD_LENGTHS = {
  // Customer fields (from CVCUS01Y.cpy)
  CUSTOMER_ID: 9,              // PIC 9(09) - CUST-ID
  FIRST_NAME: 25,              // PIC X(25) - CUST-FIRST-NAME
  MIDDLE_NAME: 25,             // PIC X(25) - CUST-MIDDLE-NAME
  LAST_NAME: 25,               // PIC X(25) - CUST-LAST-NAME
  ADDRESS_LINE: 50,            // PIC X(50) - CUST-ADDR-LINE-1/2/3
  STATE_CODE: 2,               // PIC X(02) - CUST-ADDR-STATE-CD
  COUNTRY_CODE: 3,             // PIC X(03) - CUST-ADDR-COUNTRY-CD
  ZIP_CODE: 10,                // PIC X(10) - CUST-ADDR-ZIP
  PHONE_NUMBER: 15,            // PIC X(15) - CUST-PHONE-NUM-1/2
  SSN: 9,                      // PIC 9(09) - CUST-SSN
  GOVT_ID: 20,                 // PIC X(20) - CUST-GOVT-ISSUED-ID
  FICO_SCORE: 3,               // PIC 9(03) - CUST-FICO-CREDIT-SCORE
  
  // Account fields (from CVACT01Y.cpy)
  ACCOUNT_ID: 11,              // PIC 9(11) - ACCT-ID
  ACCOUNT_STATUS: 1,           // PIC X(01) - ACCT-ACTIVE-STATUS
  GROUP_ID: 10,                // PIC X(10) - ACCT-GROUP-ID
  
  // Card fields (from CVCRD01Y.cpy)
  CARD_NUMBER: 16,             // PIC X(16) or PIC 9(16) - CC-CARD-NUM
  
  // Transaction fields
  TRANSACTION_TYPE: 2,         // PIC X(02) - Two-digit transaction type code
  TRANSACTION_CATEGORY: 6,     // Six-digit category code (TTCCCC format)
  
  // Common fields (from CVCRD01Y.cpy)
  DATE_FIELD: 10,              // PIC X(10) - YYYY-MM-DD format
  MESSAGE_TEXT: 75,            // PIC X(75) - CCARD-ERROR-MSG, CCARD-RETURN-MSG
  ERROR_MESSAGE: 80            // PIC X(80) - Standard error message length
};

// =============================================================================
// DATE FORMAT CONSTANTS
// =============================================================================

/**
 * Date format patterns for display and API communication
 * Maintains compatibility with COBOL date handling (CEEDAYS conversion)
 * Using date-fns format tokens: yyyy = year, MM = month, dd = day, HH = hour, mm = minute, ss = second
 */
export const DATE_FORMATS = {
  DISPLAY: 'MM/dd/yyyy',       // For UI display (e.g., 11/03/2024)
  API: 'yyyy-MM-dd',           // For API requests (ISO 8601, e.g., 2024-11-03)
  COBOL: 'yyyyMMdd',           // COBOL date format (no delimiters, e.g., 20241103)
  TIMESTAMP: 'yyyy-MM-dd HH:mm:ss'  // Full timestamp (e.g., 2024-11-03 14:30:45)
};

// =============================================================================
// DECIMAL PRECISION CONSTANTS (matching COBOL COMP-3)
// =============================================================================

/**
 * Decimal precision and scale for financial calculations
 * Matches COBOL COMP-3 packed decimal field definitions
 * Per Agent Action Plan Section 0.9 - preserve exact precision
 */
export const DECIMAL_PRECISION = {
  CURRENCY: 2,                  // S9(10)V99 - dollars and cents
  INTEREST_RATE: 5,             // S9(3)V9(5) - interest rates
  PERCENTAGE: 2                 // General percentage values
};

// =============================================================================
// SUCCESS MESSAGE CONSTANTS (from app/cpy/CSMSG01Y.cpy)
// =============================================================================

/**
 * Success messages for user operations
 * Maintains consistency with mainframe messaging patterns
 */
export const SUCCESS_MESSAGES = {
  LOGIN_SUCCESS: 'Login successful',
  LOGOUT_SUCCESS: 'Thank you for using CardDemo application...',
  ACCOUNT_CREATED: 'Account created successfully',
  ACCOUNT_UPDATED: 'Account updated successfully',
  CARD_UPDATED: 'Card updated successfully',
  TRANSACTION_CREATED: 'Transaction created successfully',
  PAYMENT_SUCCESS: 'Payment processed successfully'
};

// =============================================================================
// APPLICATION CONFIGURATION CONSTANTS
// =============================================================================

/**
 * Application-wide configuration settings
 */
export const APP_CONFIG = {
  APP_NAME: 'CardDemo',
  VERSION: '1.0.0',
  SESSION_TIMEOUT: 1800000,     // 30 minutes in milliseconds (JWT token expiration)
  TOKEN_REFRESH_INTERVAL: 300000, // 5 minutes in milliseconds
  MAX_LOGIN_ATTEMPTS: 3
};
