/**
 * Transaction Type Definitions
 * 
 * TypeScript interfaces and enums for transaction data structures.
 * Converted from COBOL copybooks:
 * - CVTRA05Y.cpy: Transaction record structure (TRAN-RECORD)
 * - CVTRA03Y.cpy: Transaction type codes (TRAN-TYPE-RECORD)
 * - CVTRA04Y.cpy: Transaction category codes (TRAN-CAT-RECORD)
 * 
 * Maintains data precision and field mappings for COBOL-to-Java-to-TypeScript conversion.
 * Backend counterpart: com.carddemo.model.dto.TransactionDto.java
 */

/**
 * Transaction data structure
 * 
 * Represents a credit card transaction with all associated details.
 * Converted from COBOL TRAN-RECORD (CVTRA05Y.cpy, RECLN = 350 bytes)
 * 
 * @interface Transaction
 */
export interface Transaction {
  /**
   * Transaction unique identifier
   * COBOL: TRAN-ID PIC X(16)
   * 
   * 16-character unique transaction ID assigned at transaction creation
   */
  transId: string;

  /**
   * Card number associated with this transaction
   * COBOL: TRAN-CARD-NUM PIC X(16)
   * 
   * 16-digit card number that was used for the transaction
   */
  transCardNum: string;

  /**
   * Transaction type code
   * COBOL: TRAN-TYPE-CD PIC X(02)
   * 
   * Two-character code identifying the transaction type:
   * - '01': Purchase (debit from account)
   * - '02': Payment (credit to account)
   * - '03': Cash advance
   * - '04': Refund (credit to account)
   * - '05': Balance transfer
   * - '06': Fee charge
   * - '07': Interest charge
   * 
   * @see TransactionType enum for valid values
   */
  transTypeCd: string;

  /**
   * Transaction category code
   * COBOL: TRAN-CAT-CD PIC 9(04)
   * 
   * Four-digit numeric code identifying the merchant category:
   * - 5411: Groceries
   * - 5541: Gas stations
   * - 5812: Restaurants/Dining
   * - 4511: Travel
   * - 5999: Online shopping
   * - 5912: Healthcare/Pharmacy
   * - 4900: Utilities
   * - 5311: General merchandise
   * - 9999: Other/Miscellaneous
   * 
   * @see TransactionCategory enum for valid values
   */
  transCatCd: number;

  /**
   * Transaction source system identifier
   * COBOL: TRAN-SOURCE PIC X(10)
   * 
   * Identifies the originating system or channel (e.g., 'POS', 'ATM', 'ONLINE', 'MOBILE')
   */
  transSource: string;

  /**
   * Transaction description
   * COBOL: TRAN-DESC PIC X(100)
   * 
   * Free-text description of the transaction, typically includes merchant name and location
   */
  transDesc: string;

  /**
   * Transaction amount
   * COBOL: TRAN-AMT PIC S9(09)V99 (signed, 9 digits + 2 decimal places)
   * Backend: BigDecimal with scale 2
   * 
   * Transaction amount in dollars with 2 decimal precision.
   * Positive values for debits (purchases, fees, interest)
   * Negative values for credits (payments, refunds)
   * 
   * Precision preservation: JavaScript number maintains 2 decimal places
   * matching COBOL COMP-3 and Java BigDecimal requirements
   */
  transAmt: number;

  /**
   * Merchant identifier
   * COBOL: TRAN-MERCHANT-ID PIC 9(09)
   * 
   * Nine-digit numeric merchant ID assigned by the payment network
   */
  transMerchantId: string;

  /**
   * Merchant name
   * COBOL: TRAN-MERCHANT-NAME PIC X(50)
   * 
   * Business name of the merchant where transaction occurred
   */
  transMerchantName: string;

  /**
   * Merchant city
   * COBOL: TRAN-MERCHANT-CITY PIC X(50)
   * 
   * City where the merchant is located
   */
  transMerchantCity: string;

  /**
   * Merchant ZIP code
   * COBOL: TRAN-MERCHANT-ZIP PIC X(10)
   * 
   * ZIP/postal code of merchant location
   */
  transMerchantZip: string;

  /**
   * Original transaction timestamp
   * COBOL: TRAN-ORIG-TS PIC X(26)
   * Format: ISO 8601 string (YYYY-MM-DDTHH:mm:ss.sssZ)
   * 
   * Timestamp when transaction originally occurred at merchant location
   */
  transOrigTs: string;

  /**
   * Processing timestamp
   * COBOL: TRAN-PROC-TS PIC X(26)
   * Format: ISO 8601 string (YYYY-MM-DDTHH:mm:ss.sssZ)
   * 
   * Timestamp when transaction was processed by the system
   */
  transProcTs: string;

  /**
   * Record creation timestamp
   * Format: ISO 8601 string (YYYY-MM-DDTHH:mm:ss.sssZ)
   * 
   * Timestamp when this transaction record was created in the database
   */
  createdAt: string;
}

/**
 * Transaction type enumeration
 * 
 * Maps COBOL transaction type codes from CVTRA03Y.cpy (TRAN-TYPE PIC X(02))
 * These codes identify the fundamental nature of the transaction.
 * 
 * @enum {string}
 */
export enum TransactionType {
  /**
   * Purchase transaction
   * Code: '01'
   * Effect: Debit from cardholder account
   * Description: Standard point-of-sale or online purchase
   */
  PURCHASE = '01',

  /**
   * Payment transaction
   * Code: '02'
   * Effect: Credit to cardholder account
   * Description: Payment received from cardholder to reduce balance
   */
  PAYMENT = '02',

  /**
   * Cash advance transaction
   * Code: '03'
   * Effect: Debit from cardholder account
   * Description: Cash withdrawal using credit card at ATM or bank
   */
  CASH_ADVANCE = '03',

  /**
   * Refund transaction
   * Code: '04'
   * Effect: Credit to cardholder account
   * Description: Merchant refund for returned goods or cancelled services
   */
  REFUND = '04',

  /**
   * Balance transfer transaction
   * Code: '05'
   * Effect: Debit from cardholder account
   * Description: Transfer of balance from another credit account
   */
  BALANCE_TRANSFER = '05',

  /**
   * Fee charge transaction
   * Code: '06'
   * Effect: Debit from cardholder account
   * Description: Various fees (annual fee, late payment fee, over-limit fee, etc.)
   */
  FEE = '06',

  /**
   * Interest charge transaction
   * Code: '07'
   * Effect: Debit from cardholder account
   * Description: Interest charges on outstanding balance
   */
  INTEREST = '07'
}

/**
 * Transaction category enumeration
 * 
 * Maps COBOL transaction category codes from CVTRA04Y.cpy (TRAN-CAT-CD PIC 9(04))
 * These are industry-standard Merchant Category Codes (MCC) that classify merchants
 * by business type. Used for spending analysis, rewards programs, and reporting.
 * 
 * @enum {number}
 */
export enum TransactionCategory {
  /**
   * Grocery stores and supermarkets
   * Code: 5411
   * Examples: Supermarkets, grocery stores, food markets
   */
  GROCERIES = 5411,

  /**
   * Gas stations and fuel merchants
   * Code: 5541
   * Examples: Service stations, fuel dispensers, automated fuel dispensers
   */
  GAS = 5541,

  /**
   * Restaurants and dining establishments
   * Code: 5812
   * Examples: Restaurants, cafes, bars, fast food, food delivery
   */
  DINING = 5812,

  /**
   * Travel services
   * Code: 4511
   * Examples: Airlines, car rentals, hotels, travel agencies, cruises
   */
  TRAVEL = 4511,

  /**
   * Online shopping and e-commerce
   * Code: 5999
   * Examples: Online retailers, e-commerce platforms, digital marketplaces
   */
  ONLINE = 5999,

  /**
   * Healthcare and pharmacy services
   * Code: 5912
   * Examples: Pharmacies, drug stores, medical services, healthcare providers
   */
  HEALTHCARE = 5912,

  /**
   * Utilities and services
   * Code: 4900
   * Examples: Electric, gas, water, cable, internet, phone services
   */
  UTILITIES = 4900,

  /**
   * General merchandise and department stores
   * Code: 5311
   * Examples: Department stores, discount stores, variety stores
   */
  MERCHANDISE = 5311,

  /**
   * Other or miscellaneous categories
   * Code: 9999
   * Examples: Transactions that don't fit standard categories
   */
  OTHER = 9999
}
