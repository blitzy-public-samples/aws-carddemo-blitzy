/**
 * Billing Service API Client
 * 
 * Converted from: COBOL program COBIL00C.cbl
 * Original function: Bill Payment - Pay account balance in full and transaction posting
 * 
 * Purpose: Provides billing information retrieval and payment posting API calls
 * Replaces: EXEC CICS READ FILE('ACCTDAT'), EXEC CICS WRITE FILE('TRANSACT'), 
 *           EXEC CICS REWRITE FILE('ACCTDAT') operations with REST endpoints
 * 
 * Conversion notes:
 * - COBIL00C.cbl READ-ACCTDAT-FILE → GET /api/billing/{accountId}
 * - COBIL00C.cbl WRITE-TRANSACT-FILE + UPDATE-ACCTDAT-FILE → POST /api/billing/payment
 * - ACCT-CURR-BAL (COMP-3 field) → currentBalance (number with 2 decimal precision)
 * - COBOL confirmation logic (Y/N) → payment request validation
 * - Transaction ID generation → handled by backend
 * 
 * Copyright: Migrated from AWS CardDemo mainframe application
 * License: Apache 2.0
 */

import api from './api';

/**
 * Billing Information Interface
 * Represents billing details for an account
 * 
 * COBOL equivalent: ACCOUNT-RECORD from CVACT01Y.cpy with fields:
 * - ACCT-ID → accountId
 * - ACCT-CURR-BAL (PIC S9(10)V99 COMP-3) → currentBalance
 * - ACCT-EXPIRATION-DATE → paymentDueDate
 * 
 * @interface BillingInfo
 */
export interface BillingInfo {
  /**
   * 11-digit account identifier
   * COBOL: ACCT-ID PIC 9(11)
   */
  accountId: string;

  /**
   * Current account balance with 2 decimal precision
   * COBOL: ACCT-CURR-BAL PIC S9(10)V99 COMP-3
   * Maintains COMP-3 precision via BigDecimal on backend, JavaScript number on frontend
   */
  currentBalance: number;

  /**
   * Payment due date in ISO format (YYYY-MM-DD)
   * COBOL: WS-CUR-DATE-X10 PIC X(10)
   */
  paymentDueDate: string;

  /**
   * Minimum payment amount required
   * Calculated based on balance and account terms
   */
  minimumPayment: number;

  /**
   * Statement generation date in ISO format (YYYY-MM-DD)
   * COBOL: Statement date from billing cycle
   */
  statementDate: string;

  /**
   * Last payment date (optional)
   * Only populated if previous payment exists
   */
  lastPaymentDate?: string;

  /**
   * Last payment amount (optional)
   * Only populated if previous payment exists
   * COBOL: Previous TRAN-AMT from TRANSACT file
   */
  lastPaymentAmount?: number;
}

/**
 * Payment Request Interface
 * Request payload for payment posting operation
 * 
 * COBOL equivalent: Screen input from COBIL00.bms:
 * - ACTIDINI → accountId
 * - ACCT-CURR-BAL → paymentAmount (full balance payment)
 * 
 * @interface PaymentRequest
 */
export interface PaymentRequest {
  /**
   * 11-digit account identifier
   * COBOL: ACTIDINI PIC X(11) from COBIL0AI
   */
  accountId: string;

  /**
   * Payment amount with 2 decimal precision
   * COBOL: TRAN-AMT PIC S9(10)V99 COMP-3
   * Must be positive and not exceed current balance
   */
  paymentAmount: number;
}

/**
 * Payment Response Interface
 * Response from payment posting operation
 * 
 * COBOL equivalent: Results from WRITE-TRANSACT-FILE and UPDATE-ACCTDAT-FILE:
 * - Transaction creation success/failure
 * - New balance after payment
 * - Transaction ID from TRAN-ID PIC 9(16)
 * 
 * @interface PaymentResponse
 */
export interface PaymentResponse {
  /**
   * Payment processing success indicator
   * COBOL: Based on DFHRESP(NORMAL) for WRITE and REWRITE operations
   */
  success: boolean;

  /**
   * New account balance after payment posting
   * COBOL: ACCT-CURR-BAL after COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
   */
  newBalance: number;

  /**
   * Generated transaction identifier (16 digits)
   * COBOL: WS-TRAN-ID-NUM PIC 9(16) → TRAN-ID
   */
  transactionId: string;

  /**
   * Success or error message
   * COBOL: WS-MESSAGE concatenation from WRITE-TRANSACT-FILE success message
   * Example: "Payment successful. Your Transaction ID is {TRAN-ID}."
   */
  message: string;

  /**
   * Transaction posting date in ISO format
   * COBOL: WS-TIMESTAMP from GET-CURRENT-TIMESTAMP → TRAN-PROC-TS
   */
  postedDate: string;
}

/**
 * Get Billing Information
 * Retrieves billing details for specified account
 * 
 * COBOL equivalent: COBIL00C.cbl PROCEDURE DIVISION:
 * - PROCESS-ENTER-KEY paragraph
 * - READ-ACCTDAT-FILE paragraph
 * - EXEC CICS READ DATASET(WS-ACCTDAT-FILE) INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)
 * 
 * REST API: GET /api/billing/{accountId}
 * 
 * Error Handling:
 * - 404 Not Found: Account ID doesn't exist (DFHRESP(NOTFND))
 * - 401 Unauthorized: User not authenticated
 * - 403 Forbidden: User doesn't have access to account
 * - 500 Server Error: Backend processing error
 * 
 * @param accountId - 11-digit account identifier
 * @returns Promise resolving to billing information
 * @throws {ApiError} If account not found or unauthorized
 * 
 * @example
 * ```typescript
 * try {
 *   const billingInfo = await getBillingInfo('12345678901');
 *   console.log('Current Balance:', billingInfo.currentBalance);
 *   console.log('Due Date:', billingInfo.paymentDueDate);
 * } catch (error) {
 *   console.error('Failed to retrieve billing info:', error.message);
 * }
 * ```
 */
export const getBillingInfo = async (accountId: string): Promise<BillingInfo> => {
  // Validate accountId parameter
  if (!accountId || accountId.trim().length === 0) {
    throw new Error('Account ID cannot be empty');
  }

  // Validate accountId format (11 digits)
  if (!/^\d{11}$/.test(accountId)) {
    throw new Error('Account ID must be exactly 11 digits');
  }

  // Make GET request to billing endpoint
  // COBOL: EXEC CICS READ DATASET('ACCTDAT') INTO(ACCOUNT-RECORD) RIDFLD(ACCT-ID)
  const response = await api.get<BillingInfo>(`/billing/${accountId}`);

  // Return billing information
  // Backend transforms COMP-3 fields to JSON with proper precision
  return response.data;
};

/**
 * Post Payment
 * Posts a payment transaction for an account
 * 
 * COBOL equivalent: COBIL00C.cbl PROCEDURE DIVISION:
 * - PROCESS-ENTER-KEY paragraph (when CONF-PAY-YES)
 * - READ-CXACAIX-FILE paragraph (get card number)
 * - STARTBR-TRANSACT-FILE, READPREV-TRANSACT-FILE, ENDBR-TRANSACT-FILE (get last transaction ID)
 * - WRITE-TRANSACT-FILE paragraph:
 *   * MOVE WS-TRAN-ID-NUM TO TRAN-ID
 *   * MOVE '02' TO TRAN-TYPE-CD (Payment transaction type)
 *   * MOVE 2 TO TRAN-CAT-CD
 *   * MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC
 *   * MOVE ACCT-CURR-BAL TO TRAN-AMT
 *   * EXEC CICS WRITE DATASET('TRANSACT') FROM(TRAN-RECORD) RIDFLD(TRAN-ID)
 * - UPDATE-ACCTDAT-FILE paragraph:
 *   * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
 *   * EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD)
 * 
 * REST API: POST /api/billing/payment
 * Request Body: { accountId: string, paymentAmount: number }
 * Response: PaymentResponse with transaction details
 * 
 * Business Rules (from COBOL):
 * - Payment amount must be positive (COBOL: checks if ACCT-CURR-BAL <= ZEROS)
 * - Payment amount cannot exceed current balance
 * - Transaction ID is auto-generated (sequential increment)
 * - Transaction type code is '02' for payments
 * - Account balance is reduced by payment amount
 * 
 * Error Handling:
 * - 400 Bad Request: Invalid payment amount (negative, zero, exceeds balance)
 * - 404 Not Found: Account ID doesn't exist
 * - 401 Unauthorized: User not authenticated
 * - 403 Forbidden: User doesn't have access to account
 * - 500 Server Error: Backend processing error
 * 
 * @param paymentData - Payment request with accountId and paymentAmount
 * @returns Promise resolving to payment response with transaction details
 * @throws {ApiError} If payment validation fails or processing error occurs
 * 
 * @example
 * ```typescript
 * try {
 *   const paymentResponse = await postPayment({
 *     accountId: '12345678901',
 *     paymentAmount: 150.50
 *   });
 *   
 *   if (paymentResponse.success) {
 *     console.log('Payment successful!');
 *     console.log('Transaction ID:', paymentResponse.transactionId);
 *     console.log('New Balance:', paymentResponse.newBalance);
 *   }
 * } catch (error) {
 *   console.error('Payment failed:', error.message);
 * }
 * ```
 */
export const postPayment = async (paymentData: PaymentRequest): Promise<PaymentResponse> => {
  // Validate payment request
  if (!paymentData.accountId || paymentData.accountId.trim().length === 0) {
    throw new Error('Account ID cannot be empty');
  }

  // Validate accountId format (11 digits)
  if (!/^\d{11}$/.test(paymentData.accountId)) {
    throw new Error('Account ID must be exactly 11 digits');
  }

  // Validate payment amount
  if (!paymentData.paymentAmount || paymentData.paymentAmount <= 0) {
    throw new Error('Payment amount must be greater than zero');
  }

  // Validate payment amount precision (max 2 decimal places)
  // COBOL COMP-3 PIC S9(10)V99 allows exactly 2 decimal places
  const amountString = paymentData.paymentAmount.toString();
  const decimalPart = amountString.split('.')[1];
  if (decimalPart && decimalPart.length > 2) {
    throw new Error('Payment amount cannot have more than 2 decimal places');
  }

  // Round payment amount to 2 decimal places to maintain COMP-3 precision
  const roundedPaymentAmount = Math.round(paymentData.paymentAmount * 100) / 100;

  // Create payment request with rounded amount
  const paymentRequest: PaymentRequest = {
    accountId: paymentData.accountId,
    paymentAmount: roundedPaymentAmount,
  };

  // Make POST request to payment endpoint
  // COBOL: EXEC CICS WRITE DATASET('TRANSACT') + EXEC CICS REWRITE DATASET('ACCTDAT')
  const response = await api.post<PaymentResponse>('/billing/payment', paymentRequest);

  // Return payment response
  // Backend handles transaction creation, balance update, and transaction ID generation
  return response.data;
};

/**
 * Default Export
 * Exports billing service functions as a single object
 * Allows usage: import billingService from './billingService';
 *               billingService.getBillingInfo(accountId);
 */
const billingService = {
  getBillingInfo,
  postPayment,
};

export default billingService;
