/**
 * Application-wide constants file
 * 
 * Converted from COBOL 88-level condition names, copybook field definitions,
 * and reference data files per Agent Action Plan Section 0.4.21.
 * 
 * All constants preserve exact COBOL values, codes, and message text without
 * modification per MINIMAL CHANGE principle (Section 0.7.2).
 * 
 * Source Files:
 * - CSMSG01Y.cpy: Error messages from CCDA-COMMON-MESSAGES
 * - trantype.txt: Transaction type codes and descriptions
 * - trancatg.txt: Transaction category codes (18 categories)
 * - CVACT01Y.cpy: Account status from ACCT-ACTIVE-STATUS field
 * - CVACT02Y.cpy: Card status from CARD-ACTIVE-STATUS field
 * - CSUSR01Y.cpy: User types from SEC-USR-TYPE field (RACF equivalent)
 * - COACTUP.bms: Account field validation (ACCTSID LENGTH=11)
 * - COCRDUP.bms: Card field validation (CARDSID LENGTH=16)
 * - COTRN02.bms: Transaction field validation (TRNAMT LENGTH=12)
 * - COTRN00.bms: Pagination (10 rows per page from SEL0001-SEL0010)
 */

/**
 * User Types (from CSUSR01Y.cpy SEC-USR-TYPE PIC X(01))
 * Maps RACF security roles to Spring Security equivalents
 * Per Section 0.4.12: Replace RACF user profiles with Spring Security roles
 */
export const USER_TYPES = {
  ADMIN: 'A',      // Administrator - full system access
  USER: 'U',       // Regular user - standard operations
  OPERATOR: 'R',   // Operator - restricted access
} as const;

/**
 * Account Status (from CVACT01Y.cpy ACCT-ACTIVE-STATUS PIC X(01))
 * Used in AccountViewPage and AccountUpdatePage validations
 */
export const ACCOUNT_STATUS = {
  ACTIVE: 'Y',     // Account is active
  CLOSED: 'N',     // Account is closed
  SUSPENDED: 'S',  // Account is suspended
} as const;

/**
 * Card Status (from CVACT02Y.cpy CARD-ACTIVE-STATUS PIC X(01))
 * Used in CardListPage and CardUpdatePage
 */
export const CARD_STATUS = {
  ACTIVE: 'Y',     // Card is active
  CLOSED: 'N',     // Card is closed
  SUSPENDED: 'S',  // Card is suspended
  STOLEN: 'T',     // Card reported stolen
} as const;

/**
 * Transaction Types (from trantype.txt and CVTRA03Y.cpy TRAN-TYPE-RECORD)
 * Converted from COBOL transaction type code definitions
 */
export const TRANSACTION_TYPES = {
  PURCHASE: '01',        // Purchase transaction
  PAYMENT: '02',         // Payment transaction
  CREDIT: '03',          // Credit transaction
  AUTHORIZATION: '04',   // Authorization transaction
  REFUND: '05',          // Refund transaction
  REVERSAL: '06',        // Reversal transaction
  ADJUSTMENT: '07',      // Adjustment transaction
} as const;

/**
 * Transaction Type Descriptions
 * Maps transaction type codes to their descriptions from trantype.txt
 */
export const TRANSACTION_TYPE_DESCRIPTIONS: Record<string, string> = {
  '01': 'Purchase',
  '02': 'Payment',
  '03': 'Credit',
  '04': 'Authorization',
  '05': 'Refund',
  '06': 'Reversal',
  '07': 'Adjustment',
} as const;

/**
 * Transaction Categories (from trancatg.txt and CVTRA04Y.cpy)
 * 18 categories mapped with code, typeCode, and description
 * Format: TTCCCC where TT=type code, CCCC=category code
 */
export const TRANSACTION_CATEGORIES = [
  { code: '010001', typeCode: '01', categoryCode: '0001', description: 'Regular Sales Draft' },
  { code: '010002', typeCode: '01', categoryCode: '0002', description: 'Regular Cash Advance' },
  { code: '010003', typeCode: '01', categoryCode: '0003', description: 'Convenience Check Debit' },
  { code: '010004', typeCode: '01', categoryCode: '0004', description: 'ATM Cash Advance' },
  { code: '010005', typeCode: '01', categoryCode: '0005', description: 'Interest Amount' },
  { code: '020001', typeCode: '02', categoryCode: '0001', description: 'Cash payment' },
  { code: '020002', typeCode: '02', categoryCode: '0002', description: 'Electronic payment' },
  { code: '020003', typeCode: '02', categoryCode: '0003', description: 'Check payment' },
  { code: '030001', typeCode: '03', categoryCode: '0001', description: 'Credit to Account' },
  { code: '030002', typeCode: '03', categoryCode: '0002', description: 'Credit to Purchase balance' },
  { code: '030003', typeCode: '03', categoryCode: '0003', description: 'Credit to Cash balance' },
  { code: '040001', typeCode: '04', categoryCode: '0001', description: 'Zero dollar authorization' },
  { code: '040002', typeCode: '04', categoryCode: '0002', description: 'Online purchase authorization' },
  { code: '040003', typeCode: '04', categoryCode: '0003', description: 'Travel booking authorization' },
  { code: '050001', typeCode: '05', categoryCode: '0001', description: 'Refund credit' },
  { code: '060001', typeCode: '06', categoryCode: '0001', description: 'Fraud reversal' },
  { code: '060002', typeCode: '06', categoryCode: '0002', description: 'Non-fraud reversal' },
  { code: '070001', typeCode: '07', categoryCode: '0001', description: 'Sales draft credit adjustment' },
] as const;

/**
 * API Endpoints
 * Maps BMS transaction IDs to REST endpoints
 * Per Section 0.4.6: COBOL EXEC CICS LINK becomes REST API call
 */
export const API_ENDPOINTS = {
  AUTH: '/api/auth',
  ACCOUNTS: '/api/accounts',
  CARDS: '/api/cards',
  TRANSACTIONS: '/api/transactions',
  USERS: '/api/users',
  REPORTS: '/api/reports',
} as const;

/**
 * Error Messages (from CSMSG01Y.cpy CCDA-COMMON-MESSAGES)
 * Preserved COBOL message text from copybook
 * Per Section 0.7.6: Error message text must remain identical to COBOL
 */
export const ERROR_MESSAGES = {
  THANK_YOU: 'Thank you for using CardDemo application...',
  INVALID_KEY: 'Invalid key pressed. Please see below...',
  // Additional error messages can be added as needed from CSMSG01Y.cpy
} as const;

/**
 * Field Validation Rules
 * Extracted from BMS map attributes (DFHMDF LENGTH and ATTRB)
 * Per Section 0.7.5: Preserve COBOL data type semantics
 * 
 * Sources:
 * - COACTUP.bms: ACCTSID LENGTH=11 (matches ACCT-ID PIC 9(11))
 * - COCRDUP.bms: CARDSID LENGTH=16 (matches CARD-NUM PIC X(16))
 * - COTRN02.bms: TRNAMT LENGTH=12 (matches amount with 2 decimal places)
 */
export const VALIDATION_RULES = {
  ACCOUNT_ID: {
    MIN_LENGTH: 11,
    MAX_LENGTH: 11,
    PATTERN: /^\d{11}$/,  // 11-digit numeric from PIC 9(11)
  },
  CARD_NUMBER: {
    MIN_LENGTH: 16,
    MAX_LENGTH: 16,
    PATTERN: /^\d{16}$/,  // 16-digit numeric from PIC X(16)
  },
  TRANSACTION_AMOUNT: {
    MIN: 0.01,
    MAX: 9999999999.99,  // PIC S9(10)V99 max value
    DECIMAL_PLACES: 2,
  },
} as const;

/**
 * Date Formats (from CSDAT01Y.cpy and BMS date fields)
 * 
 * DISPLAY: Used in UI components (mm/dd/yy format from BMS)
 * API: Used for REST API calls (ISO 8601 format YYYY-MM-DD)
 * TIMESTAMP: Used for full datetime values
 * 
 * Per Section 0.7.5: Preserve COBOL date format (YYYY-MM-DD as PIC X(10))
 * 
 * Note: Uses date-fns format tokens (lowercase: yyyy, dd, MM)
 * - yyyy: 4-digit year (not YYYY which is ISO week-numbering year)
 * - MM: 2-digit month
 * - dd: 2-digit day (not DD which is day of year)
 * - HH: 2-digit hour (24-hour format)
 * - mm: 2-digit minute
 * - ss: 2-digit second
 */
export const DATE_FORMATS = {
  DISPLAY: 'MM/dd/yyyy',           // User-facing display format
  API: 'yyyy-MM-dd',               // API communication format (ISO 8601)
  TIMESTAMP: 'yyyy-MM-dd HH:mm:ss', // Full timestamp format
} as const;

/**
 * Pagination Constants
 * From COTRN00.bms: 10-row display (SEL0001 through SEL0010)
 * Per Section 0.4.21: PAGE_SIZE=10 matching COTRN00.bms 10-row display
 */
export const PAGINATION = {
  PAGE_SIZE: 10,      // Number of items per page (matches BMS screen rows)
  DEFAULT_PAGE: 1,    // Default page number (1-based indexing)
} as const;

// Type exports for TypeScript type safety
export type UserType = typeof USER_TYPES[keyof typeof USER_TYPES];
export type AccountStatus = typeof ACCOUNT_STATUS[keyof typeof ACCOUNT_STATUS];
export type CardStatus = typeof CARD_STATUS[keyof typeof CARD_STATUS];
export type TransactionType = typeof TRANSACTION_TYPES[keyof typeof TRANSACTION_TYPES];
export type TransactionCategory = typeof TRANSACTION_CATEGORIES[number];
export type ApiEndpoint = typeof API_ENDPOINTS[keyof typeof API_ENDPOINTS];
