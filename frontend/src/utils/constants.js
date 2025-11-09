/**
 * Application-wide Constants Module
 * 
 * This module provides comprehensive constants for the CardDemo React frontend application.
 * All constants are derived from COBOL copybook definitions to ensure functional equivalence
 * during the mainframe-to-cloud migration.
 * 
 * Source COBOL Copybooks:
 * - COTTL01Y.cpy: Screen title constants
 * - CSMSG01Y.cpy: Common message constants
 * - CSMSG02Y.cpy: Error handling messages
 * - CSDAT01Y.cpy: Date and time format constants
 * - CVCUS01Y.cpy: Customer field length definitions
 * - CVACT01Y.cpy: Account field length definitions
 * - CVACT02Y.cpy: Card field length definitions
 * - CVTRA05Y.cpy: Transaction field length definitions
 * - CSLKPCDY.cpy: Lookup codes including state codes
 * 
 * @module constants
 */

// =============================================================================
// API CONFIGURATION CONSTANTS
// =============================================================================

/**
 * Base URL for API endpoints
 * Retrieved from environment variable or defaults to localhost development server
 * Uses Vite's import.meta.env instead of process.env
 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api';

/**
 * API request timeout in milliseconds (30 seconds)
 */
export const API_TIMEOUT = 30000;

/**
 * LocalStorage key for JWT authentication token
 */
export const AUTH_TOKEN_KEY = 'cardemo_jwt_token';

// =============================================================================
// PAGINATION CONSTANTS
// Matches COBOL pagination patterns from Agent Action Plan section 0.1
// =============================================================================

/**
 * Number of cards to display per page
 * Matches COBOL pagination pattern: 7 cards per page
 */
export const CARDS_PER_PAGE = 7;

/**
 * Number of transactions to display per page
 * Matches COBOL pagination pattern: 10 transactions per page
 */
export const TRANSACTIONS_PER_PAGE = 10;

/**
 * Maximum page size for any paginated request
 */
export const MAX_PAGE_SIZE = 100;

// =============================================================================
// SCREEN TITLE CONSTANTS
// Source: COTTL01Y.cpy lines 18-24
// =============================================================================

/**
 * Main application title
 * Source: COTTL01Y.cpy line 18-19: CCDA-TITLE01
 */
export const SCREEN_TITLE_MAIN = 'AWS Mainframe Modernization';

/**
 * Application name
 * Source: COTTL01Y.cpy line 20-22: CCDA-TITLE02
 */
export const SCREEN_TITLE_APP = 'CardDemo';

/**
 * Thank you screen title
 * Source: COTTL01Y.cpy line 23-24: CCDA-THANK-YOU
 */
export const SCREEN_TITLE_THANK_YOU = 'Thank you for using CCDA application...';

// =============================================================================
// MESSAGE CONSTANTS
// Source: CSMSG01Y.cpy lines 18-21
// =============================================================================

/**
 * Thank you message displayed to users
 * Source: CSMSG01Y.cpy line 18-19: CCDA-MSG-THANK-YOU
 */
export const MSG_THANK_YOU = 'Thank you for using CardDemo application...';

/**
 * Invalid key press message
 * Source: CSMSG01Y.cpy line 20-21: CCDA-MSG-INVALID-KEY
 */
export const MSG_INVALID_KEY = 'Invalid key pressed. Please see below...';

/**
 * Session expiration message
 */
export const MSG_SESSION_EXPIRED = 'Your session has expired. Please login again.';

/**
 * Unauthorized access message
 */
export const MSG_UNAUTHORIZED = 'You are not authorized to access this resource.';

/**
 * Generic server error message
 */
export const MSG_SERVER_ERROR = 'An error occurred. Please try again later.';

/**
 * Successful operation message
 */
export const MSG_SUCCESS = 'Operation completed successfully.';

/**
 * Data not found message
 */
export const MSG_NOT_FOUND = 'The requested resource was not found.';

// =============================================================================
// DATE AND TIME FORMAT CONSTANTS
// Source: CSDAT01Y.cpy lines 20-55
// =============================================================================

/**
 * Date format for user display (MM/DD/YYYY)
 * Source: CSDAT01Y.cpy lines 30-35: WS-CURDATE-MM-DD-YY pattern
 */
export const DATE_FORMAT_DISPLAY = 'MM/DD/YYYY';

/**
 * Date format for API communication (YYYY-MM-DD)
 * Source: CSDAT01Y.cpy lines 20-22: WS-CURDATE pattern (YYYY-MM-DD)
 */
export const DATE_FORMAT_API = 'YYYY-MM-DD';

/**
 * Timestamp format for display (YYYY-MM-DD HH:mm:ss)
 * Source: CSDAT01Y.cpy lines 42-55: WS-TIMESTAMP pattern
 */
export const TIMESTAMP_FORMAT = 'YYYY-MM-DD HH:mm:ss';

/**
 * Time format (HH:mm:ss)
 * Source: CSDAT01Y.cpy lines 36-41: WS-CURTIME-HH-MM-SS pattern
 */
export const TIME_FORMAT = 'HH:mm:ss';

/**
 * Short date format (MM/DD/YY)
 * Source: CSDAT01Y.cpy lines 30-35: WS-CURDATE-MM-DD-YY
 */
export const DATE_FORMAT_SHORT = 'MM/DD/YY';

// =============================================================================
// USER TYPE CONSTANTS
// Replaces COBOL 88-level conditions for user types
// =============================================================================

/**
 * User type enumeration
 * Maps to COBOL user type field values
 * ADMIN: 'A' - Administrative user with full system access
 * USER: 'U' - Regular user with standard access
 */
export const USER_TYPE = {
  ADMIN: 'A',
  USER: 'U'
};

// =============================================================================
// CARD STATUS CONSTANTS
// Source: CVACT02Y.cpy line 10: CARD-ACTIVE-STATUS
// =============================================================================

/**
 * Card status enumeration
 * ACTIVE: 'Y' - Card is active and can be used
 * INACTIVE: 'N' - Card is inactive or blocked
 */
export const CARD_STATUS = {
  ACTIVE: 'Y',
  INACTIVE: 'N'
};

// =============================================================================
// ACCOUNT STATUS CONSTANTS
// Source: CVACT01Y.cpy line 6: ACCT-ACTIVE-STATUS
// =============================================================================

/**
 * Account status enumeration
 * ACTIVE: 'Y' - Account is active
 * INACTIVE: 'N' - Account is inactive or closed
 */
export const ACCOUNT_STATUS = {
  ACTIVE: 'Y',
  INACTIVE: 'N'
};

// =============================================================================
// FIELD LENGTH CONSTANTS
// Source: Various COBOL copybook PIC clauses
// =============================================================================

/**
 * Maximum length for customer ID
 * Source: CVCUS01Y.cpy line 5: CUST-ID PIC 9(09)
 */
export const MAX_LENGTH_CUSTOMER_ID = 9;

/**
 * Maximum length for account ID
 * Source: CVACT01Y.cpy line 5: ACCT-ID PIC 9(11)
 */
export const MAX_LENGTH_ACCOUNT_ID = 11;

/**
 * Maximum length for card number
 * Source: CVACT02Y.cpy line 5: CARD-NUM PIC X(16)
 */
export const MAX_LENGTH_CARD_NUMBER = 16;

/**
 * Maximum length for card CVV code
 * Source: CVACT02Y.cpy line 7: CARD-CVV-CD PIC 9(03)
 */
export const MAX_LENGTH_CVV = 3;

/**
 * Maximum length for person name fields (first, middle, last)
 * Source: CVCUS01Y.cpy lines 6-8: CUST-FIRST-NAME, CUST-MIDDLE-NAME, CUST-LAST-NAME PIC X(25)
 */
export const MAX_LENGTH_NAME = 25;

/**
 * Maximum length for embossed name on card
 * Source: CVACT02Y.cpy line 8: CARD-EMBOSSED-NAME PIC X(50)
 */
export const MAX_LENGTH_EMBOSSED_NAME = 50;

/**
 * Maximum length for address lines
 * Source: CVCUS01Y.cpy lines 9-11: CUST-ADDR-LINE-1/2/3 PIC X(50)
 */
export const MAX_LENGTH_ADDRESS = 50;

/**
 * Maximum length for phone number
 * Source: CVCUS01Y.cpy lines 15-16: CUST-PHONE-NUM-1/2 PIC X(15)
 */
export const MAX_LENGTH_PHONE = 15;

/**
 * Maximum length for SSN
 * Source: CVCUS01Y.cpy line 17: CUST-SSN PIC 9(09)
 */
export const MAX_LENGTH_SSN = 9;

/**
 * Maximum length for ZIP code
 * Source: CVCUS01Y.cpy line 14: CUST-ADDR-ZIP PIC X(10)
 */
export const MAX_LENGTH_ZIP = 10;

/**
 * Maximum length for state code
 * Source: CVCUS01Y.cpy line 12: CUST-ADDR-STATE-CD PIC X(02)
 */
export const MAX_LENGTH_STATE = 2;

/**
 * Maximum length for country code
 * Source: CVCUS01Y.cpy line 13: CUST-ADDR-COUNTRY-CD PIC X(03)
 */
export const MAX_LENGTH_COUNTRY = 3;

/**
 * Maximum length for transaction ID
 * Source: CVTRA05Y.cpy line 5: TRAN-ID PIC X(16)
 */
export const MAX_LENGTH_TRANSACTION_ID = 16;

/**
 * Maximum length for transaction type code
 * Source: CVTRA05Y.cpy line 6: TRAN-TYPE-CD PIC X(02)
 */
export const MAX_LENGTH_TRANSACTION_TYPE = 2;

/**
 * Maximum length for transaction description
 * Source: CVTRA05Y.cpy line 9: TRAN-DESC PIC X(100)
 */
export const MAX_LENGTH_DESCRIPTION = 100;

/**
 * Maximum length for merchant name
 * Source: CVTRA05Y.cpy line 12: TRAN-MERCHANT-NAME PIC X(50)
 */
export const MAX_LENGTH_MERCHANT = 50;

/**
 * Maximum length for merchant ID
 * Source: CVTRA05Y.cpy line 11: TRAN-MERCHANT-ID PIC 9(09)
 */
export const MAX_LENGTH_MERCHANT_ID = 9;

/**
 * FICO credit score length
 * Source: CVCUS01Y.cpy line 22: CUST-FICO-CREDIT-SCORE PIC 9(03)
 */
export const MAX_LENGTH_FICO_SCORE = 3;

/**
 * Government issued ID length
 * Source: CVCUS01Y.cpy line 18: CUST-GOVT-ISSUED-ID PIC X(20)
 */
export const MAX_LENGTH_GOVT_ID = 20;

// =============================================================================
// VALIDATION ERROR MESSAGES
// =============================================================================

/**
 * Required field validation error
 */
export const ERROR_REQUIRED = 'This field is required';

/**
 * Invalid format validation error
 */
export const ERROR_INVALID_FORMAT = 'Invalid format';

/**
 * Invalid length validation error
 */
export const ERROR_INVALID_LENGTH = 'Invalid length';

/**
 * Invalid card number error
 */
export const ERROR_INVALID_CARD_NUMBER = 'Invalid card number';

/**
 * Invalid date format error
 */
export const ERROR_INVALID_DATE = 'Invalid date format';

/**
 * Invalid amount error
 */
export const ERROR_INVALID_AMOUNT = 'Invalid amount';

/**
 * Invalid email error
 */
export const ERROR_INVALID_EMAIL = 'Invalid email address';

/**
 * Invalid phone number error
 */
export const ERROR_INVALID_PHONE = 'Invalid phone number';

/**
 * Invalid SSN error
 */
export const ERROR_INVALID_SSN = 'Invalid SSN format';

/**
 * Invalid ZIP code error
 */
export const ERROR_INVALID_ZIP = 'Invalid ZIP code';

/**
 * Minimum length error
 */
export const ERROR_MIN_LENGTH = 'Minimum length not met';

/**
 * Maximum length error
 */
export const ERROR_MAX_LENGTH = 'Maximum length exceeded';

// =============================================================================
// HTTP STATUS CODES
// =============================================================================

/**
 * HTTP 200 - OK
 */
export const STATUS_OK = 200;

/**
 * HTTP 201 - Created
 */
export const STATUS_CREATED = 201;

/**
 * HTTP 204 - No Content
 */
export const STATUS_NO_CONTENT = 204;

/**
 * HTTP 400 - Bad Request
 */
export const STATUS_BAD_REQUEST = 400;

/**
 * HTTP 401 - Unauthorized
 */
export const STATUS_UNAUTHORIZED = 401;

/**
 * HTTP 403 - Forbidden
 */
export const STATUS_FORBIDDEN = 403;

/**
 * HTTP 404 - Not Found
 */
export const STATUS_NOT_FOUND = 404;

/**
 * HTTP 409 - Conflict
 */
export const STATUS_CONFLICT = 409;

/**
 * HTTP 500 - Internal Server Error
 */
export const STATUS_SERVER_ERROR = 500;

/**
 * HTTP 503 - Service Unavailable
 */
export const STATUS_SERVICE_UNAVAILABLE = 503;

// =============================================================================
// VALID STATE CODES
// Source: CSLKPCDY.cpy lines 1014-1069
// =============================================================================

/**
 * Valid US state and territory codes
 * Source: CSLKPCDY.cpy VALID-US-STATE-CODE enumeration
 * Includes all 50 states plus DC and territories (AS, GU, MP, PR, VI)
 */
export const VALID_STATE_CODES = [
  'AL', // Alabama
  'AK', // Alaska
  'AZ', // Arizona
  'AR', // Arkansas
  'CA', // California
  'CO', // Colorado
  'CT', // Connecticut
  'DE', // Delaware
  'FL', // Florida
  'GA', // Georgia
  'HI', // Hawaii
  'ID', // Idaho
  'IL', // Illinois
  'IN', // Indiana
  'IA', // Iowa
  'KS', // Kansas
  'KY', // Kentucky
  'LA', // Louisiana
  'ME', // Maine
  'MD', // Maryland
  'MA', // Massachusetts
  'MI', // Michigan
  'MN', // Minnesota
  'MS', // Mississippi
  'MO', // Missouri
  'MT', // Montana
  'NE', // Nebraska
  'NV', // Nevada
  'NH', // New Hampshire
  'NJ', // New Jersey
  'NM', // New Mexico
  'NY', // New York
  'NC', // North Carolina
  'ND', // North Dakota
  'OH', // Ohio
  'OK', // Oklahoma
  'OR', // Oregon
  'PA', // Pennsylvania
  'RI', // Rhode Island
  'SC', // South Carolina
  'SD', // South Dakota
  'TN', // Tennessee
  'TX', // Texas
  'UT', // Utah
  'VT', // Vermont
  'VA', // Virginia
  'WA', // Washington
  'WV', // West Virginia
  'WI', // Wisconsin
  'WY', // Wyoming
  'DC', // District of Columbia
  'AS', // American Samoa
  'GU', // Guam
  'MP', // Northern Mariana Islands
  'PR', // Puerto Rico
  'VI'  // US Virgin Islands
];

// =============================================================================
// TRANSACTION TYPE CODES
// Common transaction types in the CardDemo system
// =============================================================================

/**
 * Transaction type codes
 */
export const TRANSACTION_TYPES = {
  PURCHASE: '01',
  PAYMENT: '02',
  REFUND: '03',
  CASH_ADVANCE: '04',
  INTEREST_CHARGE: '05',
  LATE_FEE: '06',
  ANNUAL_FEE: '07',
  BALANCE_TRANSFER: '08'
};

// =============================================================================
// CARD TYPES
// =============================================================================

/**
 * Card type enumeration
 */
export const CARD_TYPES = {
  VISA: 'VISA',
  MASTERCARD: 'MASTERCARD',
  AMEX: 'AMEX',
  DISCOVER: 'DISCOVER'
};

// =============================================================================
// AMOUNT CONSTRAINTS
// Based on COBOL PIC S9(10)V99 field definitions
// =============================================================================

/**
 * Minimum transaction amount (0.01)
 */
export const MIN_AMOUNT = 0.01;

/**
 * Maximum transaction amount (9,999,999,999.99)
 * Source: COBOL PIC S9(10)V99 maximum value
 */
export const MAX_AMOUNT = 9999999999.99;

/**
 * Minimum credit limit (1,000.00)
 */
export const MIN_CREDIT_LIMIT = 1000.00;

/**
 * Maximum credit limit (999,999,999.99)
 */
export const MAX_CREDIT_LIMIT = 999999999.99;

// =============================================================================
// TIMEOUT VALUES
// =============================================================================

/**
 * Session timeout in milliseconds (30 minutes)
 */
export const SESSION_TIMEOUT = 1800000;

/**
 * Toast notification display duration in milliseconds (5 seconds)
 */
export const TOAST_DURATION = 5000;

/**
 * Debounce delay for search inputs in milliseconds (300ms)
 */
export const SEARCH_DEBOUNCE_DELAY = 300;

// =============================================================================
// FICO SCORE RANGES
// =============================================================================

/**
 * FICO credit score ranges
 */
export const FICO_RANGES = {
  POOR: { min: 300, max: 579 },
  FAIR: { min: 580, max: 669 },
  GOOD: { min: 670, max: 739 },
  VERY_GOOD: { min: 740, max: 799 },
  EXCEPTIONAL: { min: 800, max: 850 }
};

// =============================================================================
// REGEX PATTERNS
// =============================================================================

/**
 * Email validation regex pattern
 */
export const REGEX_EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Phone number validation regex (digits, spaces, dashes, parentheses)
 */
export const REGEX_PHONE = /^[\d\s\-()]+$/;

/**
 * SSN validation regex (9 digits)
 */
export const REGEX_SSN = /^\d{9}$/;

/**
 * ZIP code validation regex (5 or 9 digits)
 */
export const REGEX_ZIP = /^\d{5}(-\d{4})?$/;

/**
 * Card number validation regex (13-19 digits)
 */
export const REGEX_CARD_NUMBER = /^\d{13,19}$/;

/**
 * CVV validation regex (3 or 4 digits)
 */
export const REGEX_CVV = /^\d{3,4}$/;

/**
 * Numeric only regex
 */
export const REGEX_NUMERIC = /^\d+$/;

/**
 * Alphanumeric regex
 */
export const REGEX_ALPHANUMERIC = /^[a-zA-Z0-9]+$/;

// =============================================================================
// TABLE SORT ORDERS
// =============================================================================

/**
 * Sort order enumeration
 */
export const SORT_ORDER = {
  ASC: 'asc',
  DESC: 'desc'
};

// =============================================================================
// DEFAULT VALUES
// =============================================================================

/**
 * Default country code
 */
export const DEFAULT_COUNTRY = 'USA';

/**
 * Default currency symbol
 */
export const DEFAULT_CURRENCY = '$';

/**
 * Default page number for pagination
 */
export const DEFAULT_PAGE = 0;

/**
 * Application version
 */
export const APP_VERSION = '1.0.0';

// =============================================================================
// EXPORT ALL CONSTANTS AS DEFAULT OBJECT
// =============================================================================

/**
 * Default export containing all constants
 * Provides convenient access to all application constants
 */
export default {
  // API Configuration
  API_BASE_URL,
  API_TIMEOUT,
  AUTH_TOKEN_KEY,
  
  // Pagination
  CARDS_PER_PAGE,
  TRANSACTIONS_PER_PAGE,
  MAX_PAGE_SIZE,
  
  // Screen Titles
  SCREEN_TITLE_MAIN,
  SCREEN_TITLE_APP,
  SCREEN_TITLE_THANK_YOU,
  
  // Messages
  MSG_THANK_YOU,
  MSG_INVALID_KEY,
  MSG_SESSION_EXPIRED,
  MSG_UNAUTHORIZED,
  MSG_SERVER_ERROR,
  MSG_SUCCESS,
  MSG_NOT_FOUND,
  
  // Date/Time Formats
  DATE_FORMAT_DISPLAY,
  DATE_FORMAT_API,
  TIMESTAMP_FORMAT,
  TIME_FORMAT,
  DATE_FORMAT_SHORT,
  
  // User Types
  USER_TYPE,
  
  // Status Constants
  CARD_STATUS,
  ACCOUNT_STATUS,
  
  // Field Lengths
  MAX_LENGTH_CUSTOMER_ID,
  MAX_LENGTH_ACCOUNT_ID,
  MAX_LENGTH_CARD_NUMBER,
  MAX_LENGTH_CVV,
  MAX_LENGTH_NAME,
  MAX_LENGTH_EMBOSSED_NAME,
  MAX_LENGTH_ADDRESS,
  MAX_LENGTH_PHONE,
  MAX_LENGTH_SSN,
  MAX_LENGTH_ZIP,
  MAX_LENGTH_STATE,
  MAX_LENGTH_COUNTRY,
  MAX_LENGTH_TRANSACTION_ID,
  MAX_LENGTH_TRANSACTION_TYPE,
  MAX_LENGTH_DESCRIPTION,
  MAX_LENGTH_MERCHANT,
  MAX_LENGTH_MERCHANT_ID,
  MAX_LENGTH_FICO_SCORE,
  MAX_LENGTH_GOVT_ID,
  
  // Validation Errors
  ERROR_REQUIRED,
  ERROR_INVALID_FORMAT,
  ERROR_INVALID_LENGTH,
  ERROR_INVALID_CARD_NUMBER,
  ERROR_INVALID_DATE,
  ERROR_INVALID_AMOUNT,
  ERROR_INVALID_EMAIL,
  ERROR_INVALID_PHONE,
  ERROR_INVALID_SSN,
  ERROR_INVALID_ZIP,
  ERROR_MIN_LENGTH,
  ERROR_MAX_LENGTH,
  
  // HTTP Status Codes
  STATUS_OK,
  STATUS_CREATED,
  STATUS_NO_CONTENT,
  STATUS_BAD_REQUEST,
  STATUS_UNAUTHORIZED,
  STATUS_FORBIDDEN,
  STATUS_NOT_FOUND,
  STATUS_CONFLICT,
  STATUS_SERVER_ERROR,
  STATUS_SERVICE_UNAVAILABLE,
  
  // State Codes
  VALID_STATE_CODES,
  
  // Transaction Types
  TRANSACTION_TYPES,
  
  // Card Types
  CARD_TYPES,
  
  // Amount Constraints
  MIN_AMOUNT,
  MAX_AMOUNT,
  MIN_CREDIT_LIMIT,
  MAX_CREDIT_LIMIT,
  
  // Timeouts
  SESSION_TIMEOUT,
  TOAST_DURATION,
  SEARCH_DEBOUNCE_DELAY,
  
  // FICO Ranges
  FICO_RANGES,
  
  // Regex Patterns
  REGEX_EMAIL,
  REGEX_PHONE,
  REGEX_SSN,
  REGEX_ZIP,
  REGEX_CARD_NUMBER,
  REGEX_CVV,
  REGEX_NUMERIC,
  REGEX_ALPHANUMERIC,
  
  // Sort Order
  SORT_ORDER,
  
  // Defaults
  DEFAULT_COUNTRY,
  DEFAULT_CURRENCY,
  DEFAULT_PAGE,
  APP_VERSION
};
