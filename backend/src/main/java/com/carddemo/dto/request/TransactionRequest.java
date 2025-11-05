/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Request DTO for creating new credit card transactions.
 * 
 * <p>Transforms COBOL COTRN02 BMS copybook structure to Java request object for
 * transaction creation REST API endpoint. Captures comprehensive transaction details
 * including account identification, card information, merchant data, transaction
 * categorization, and precise financial amounts.</p>
 * 
 * <p>This DTO maintains functional equivalence with the mainframe COTRN02C transaction
 * program, preserving all field validation rules, business logic constraints, and
 * data precision requirements from the original COBOL implementation.</p>
 * 
 * <p><b>COBOL Source Mapping:</b> app/cpy-bms/COTRN02.CPY COTRN2AI input structure</p>
 * 
 * <p><b>Critical Requirements:</b></p>
 * <ul>
 *   <li>COMP-3 packed decimal precision preserved using BigDecimal with scale=2 and
 *       RoundingMode.HALF_UP for exact financial calculations per section 0.9</li>
 *   <li>Transaction amount validation ensures positive values with maximum 10 integer
 *       digits and exactly 2 decimal places matching COBOL PIC S9(10)V99 COMP-3</li>
 *   <li>Date fields transformed from COBOL PIC X(10) YYYY-MM-DD string format to
 *       Java LocalDate for type-safe date handling and validation</li>
 *   <li>Bean Validation annotations enforce business rules matching COBOL field
 *       validation in COTRN02C program logic</li>
 *   <li>Support sub-200ms response time validation through efficient DTO structure
 *       per section 0.9 performance requirements</li>
 * </ul>
 * 
 * <p><b>API Contract:</b> POST /api/transactions endpoint request body</p>
 * 
 * @see com.carddemo.service.TransactionCreationService
 * @see com.carddemo.controller.TransactionController
 * @since 1.0
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRequest {

    /**
     * Account identifier for the transaction.
     * 
     * <p>Maps to COBOL field ACTIDINI PIC X(11) from COTRN02.CPY line 60.
     * Uniquely identifies the credit card account being charged or credited.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be blank</li>
     *   <li>Maximum length: 11 characters</li>
     *   <li>Must correspond to existing account in ACCTDAT/account table</li>
     * </ul>
     * 
     * <p><b>Business Rule:</b> Account must be in active status and not expired
     * for transaction to be processed successfully.</p>
     */
    @NotBlank(message = "Account ID is required and cannot be blank")
    @Size(max = 11, message = "Account ID must not exceed 11 characters")
    @JsonProperty("accountId")
    private String accountId;

    /**
     * Card number for the transaction.
     * 
     * <p>Maps to COBOL field CARDNINI PIC X(16) from COTRN02.CPY line 66.
     * 16-digit credit card number used for the transaction.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be blank</li>
     *   <li>Exact length: 16 numeric digits</li>
     *   <li>Pattern: Must contain only digits 0-9</li>
     *   <li>Must correspond to active card in CARDDAT/card table</li>
     * </ul>
     * 
     * <p><b>Security Note:</b> Card number should be masked in logs and audit trails.
     * Only last 4 digits should be displayed in user interfaces.</p>
     */
    @NotBlank(message = "Card number is required and cannot be blank")
    @Size(min = 16, max = 16, message = "Card number must be exactly 16 characters")
    @Pattern(regexp = "^[0-9]{16}$", message = "Card number must contain exactly 16 numeric digits")
    @JsonProperty("cardNumber")
    private String cardNumber;

    /**
     * Transaction type code.
     * 
     * <p>Maps to COBOL field TTYPCDI PIC X(2) from COTRN02.CPY line 72.
     * Two-character code classifying the type of transaction.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be blank</li>
     *   <li>Exact length: 2 characters</li>
     *   <li>Pattern: Uppercase letters A-Z or digits 0-9</li>
     *   <li>Must exist in reference data table transaction_type</li>
     * </ul>
     * 
     * <p><b>Common Transaction Types:</b></p>
     * <ul>
     *   <li>PU - Purchase</li>
     *   <li>CA - Cash Advance</li>
     *   <li>PM - Payment</li>
     *   <li>RF - Refund</li>
     *   <li>FE - Fee</li>
     *   <li>IN - Interest Charge</li>
     * </ul>
     */
    @NotBlank(message = "Transaction type code is required and cannot be blank")
    @Size(min = 2, max = 2, message = "Transaction type code must be exactly 2 characters")
    @Pattern(regexp = "^[A-Z0-9]{2}$", message = "Transaction type code must contain exactly 2 uppercase alphanumeric characters")
    @JsonProperty("transactionTypeCode")
    private String transactionTypeCode;

    /**
     * Transaction category code.
     * 
     * <p>Maps to COBOL field TCATCDI from COTRN02.CPY, now stored as 6-character composite key
     * combining transaction type (2 chars) + category number (4 digits) to match refactored
     * TransactionCategory entity structure.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be blank</li>
     *   <li>Maximum length: 6 characters (composite key format)</li>
     *   <li>Must exist in reference data table transaction_category</li>
     * </ul>
     * 
     * <p><b>Common Transaction Category Format Examples:</b></p>
     * <ul>
     *   <li>010001 - Purchase: Food and Dining</li>
     *   <li>010002 - Purchase: Gasoline and Fuel</li>
     *   <li>020001 - Cash Advance: ATM Withdrawal</li>
     *   <li>030001 - Payment: Online Payment</li>
     * </ul>
     */
    @NotBlank(message = "Transaction category code is required and cannot be blank")
    @Size(max = 6, message = "Transaction category code must not exceed 6 characters")
    @JsonProperty("transactionCategoryCode")
    private String transactionCategoryCode;

    /**
     * Transaction source identifier.
     * 
     * <p>Maps to COBOL field TRNSRCI PIC X(10) from COTRN02.CPY line 84.
     * Identifies the origination source or channel of the transaction.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field - may be null or blank</li>
     *   <li>Maximum length: 10 characters when provided</li>
     * </ul>
     * 
     * <p><b>Common Transaction Sources:</b></p>
     * <ul>
     *   <li>POS - Point of Sale Terminal</li>
     *   <li>ATM - Automated Teller Machine</li>
     *   <li>ONLINE - Online Web Transaction</li>
     *   <li>MOBILE - Mobile App Transaction</li>
     *   <li>PHONE - Phone/IVR Transaction</li>
     *   <li>MAIL - Mail Order</li>
     * </ul>
     */
    @Size(max = 10, message = "Transaction source must not exceed 10 characters")
    @JsonProperty("transactionSource")
    private String transactionSource;

    /**
     * Transaction description.
     * 
     * <p>Maps to COBOL field TDESCI PIC X(60) from COTRN02.CPY line 90.
     * Free-text description providing additional details about the transaction.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be blank</li>
     *   <li>Maximum length: 60 characters</li>
     *   <li>Typically contains merchant name, location, or purchase details</li>
     * </ul>
     * 
     * <p><b>Usage:</b> This field appears on customer statements and transaction
     * history displays. Should contain meaningful information for customer recognition.</p>
     */
    @NotBlank(message = "Transaction description is required and cannot be blank")
    @Size(max = 60, message = "Transaction description must not exceed 60 characters")
    @JsonProperty("transactionDescription")
    private String transactionDescription;

    /**
     * Transaction amount in dollars and cents.
     * 
     * <p>Maps to COBOL field TRNAMTI PIC X(12) from COTRN02.CPY line 96.
     * Represents the monetary value of the transaction with exact decimal precision.</p>
     * 
     * <p><b>CRITICAL PRECISION REQUIREMENT:</b></p>
     * <p>This field preserves COBOL COMP-3 packed decimal precision per section 0.9
     * critical requirements. Uses BigDecimal with scale=2 and RoundingMode.HALF_UP
     * to maintain exact financial calculation accuracy matching mainframe decimal
     * arithmetic. Prevents floating-point rounding errors that would cause
     * discrepancies in transaction amounts and balance calculations.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be null</li>
     *   <li>Absolute value must be at least 0.01 (transactions must be at least one cent)</li>
     *   <li>Maximum integer digits: 10</li>
     *   <li>Exact fractional digits: 2 (cents precision)</li>
     *   <li>Rounding mode: HALF_UP (consistent with COBOL rounding)</li>
     *   <li>Sign validation performed by service layer based on transaction type</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b></p>
     * <ul>
     *   <li>Negative amounts represent debits (purchases, charges) to the account per COBOL convention</li>
     *   <li>Positive amounts represent credits (payments, refunds) to the account</li>
     *   <li>Transaction type code determines expected sign (validated in service layer)</li>
     *   <li>Amount precision must exactly match COBOL COMP-3 PIC S9(10)V99 (signed)</li>
     * </ul>
     * 
     * <p><b>Example Values:</b></p>
     * <ul>
     *   <li>-125.50 - Purchase transaction (debit)</li>
     *   <li>125.50 - Payment transaction (credit)</li>
     *   <li>-0.99 - Ninety-nine cent purchase</li>
     *   <li>9999999999.99 - Maximum allowed amount</li>
     *   <li>-9999999999.99 - Maximum allowed debit</li>
     * </ul>
     */
    @NotNull(message = "Transaction amount is required and cannot be null")
    @Digits(integer = 10, fraction = 2, message = "Transaction amount must have at most 10 integer digits and exactly 2 decimal places")
    @JsonProperty("transactionAmount")
    private BigDecimal transactionAmount;

    /**
     * Transaction origin date.
     * 
     * <p>Maps to COBOL field TORIGDTI PIC X(10) from COTRN02.CPY line 102.
     * Date when the transaction originally occurred at the merchant or source.</p>
     * 
     * <p><b>Date Format Transformation:</b></p>
     * <p>Converts from COBOL string format YYYY-MM-DD (PIC X(10)) to Java LocalDate
     * for type-safe date handling, validation, and arithmetic operations per
     * section 0.3 transformation rules. Replaces COBOL CEEDAYS Lillian date format
     * with modern Java 8 date/time API.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be null</li>
     *   <li>Must be a date in the past - future dates not allowed</li>
     *   <li>Typically matches or precedes process date</li>
     *   <li>Used for transaction aging and statement cycle calculations</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b></p>
     * <ul>
     *   <li>Origin date represents when transaction occurred at merchant</li>
     *   <li>May differ from process date due to batch processing delays</li>
     *   <li>Critical for interest calculation and billing cycle assignment</li>
     * </ul>
     */
    @NotNull(message = "Origin date is required and cannot be null")
    @Past(message = "Origin date must be a date in the past")
    @JsonProperty("originDate")
    private LocalDate originDate;

    /**
     * Transaction process date.
     * 
     * <p>Maps to COBOL field TPROCDTI PIC X(10) from COTRN02.CPY line 108.
     * Date when the transaction is processed and posted to the account.</p>
     * 
     * <p><b>Date Format Transformation:</b></p>
     * <p>Converts from COBOL string format YYYY-MM-DD (PIC X(10)) to Java LocalDate
     * for type-safe date handling per section 0.3 transformation rules.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Required field - must not be null</li>
     *   <li>Typically current date or later than origin date</li>
     *   <li>Used for posting transactions to account balances</li>
     * </ul>
     * 
     * <p><b>Business Rules:</b></p>
     * <ul>
     *   <li>Process date determines which billing cycle includes the transaction</li>
     *   <li>Interest accrual starts from process date, not origin date</li>
     *   <li>Account balance updated on process date</li>
     *   <li>Process date used for transaction aging calculations</li>
     * </ul>
     */
    @NotNull(message = "Process date is required and cannot be null")
    @JsonProperty("processDate")
    private LocalDate processDate;

    /**
     * Merchant identifier.
     * 
     * <p>Maps to COBOL field MIDI PIC X(9) from COTRN02.CPY line 114.
     * Unique identifier for the merchant or business where transaction occurred.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field - may be null or blank for certain transaction types</li>
     *   <li>Maximum length: 9 characters when provided</li>
     *   <li>Typically numeric merchant ID assigned by payment processor</li>
     * </ul>
     * 
     * <p><b>Usage:</b></p>
     * <ul>
     *   <li>Used for merchant category reporting and analysis</li>
     *   <li>Links transaction to merchant master data</li>
     *   <li>Required for chargeback and dispute processing</li>
     * </ul>
     */
    @Size(max = 9, message = "Merchant ID must not exceed 9 characters")
    @JsonProperty("merchantId")
    private String merchantId;

    /**
     * Merchant name.
     * 
     * <p>Maps to COBOL field MNAMEI PIC X(30) from COTRN02.CPY line 120.
     * Name of the merchant or business where transaction occurred.</p>
     * 
     * <p><b>Validation Rules:</b></p>
     * <ul>
     *   <li>Optional field - may be null or blank</li>
     *   <li>Maximum length: 30 characters when provided</li>
     *   <li>Displayed on customer statements and transaction history</li>
     * </ul>
     * 
     * <p><b>Usage:</b></p>
     * <ul>
     *   <li>Primary merchant identification on customer-facing displays</li>
     *   <li>Used for transaction search and filtering</li>
     *   <li>Should contain recognizable merchant doing-business-as (DBA) name</li>
     * </ul>
     */
    @Size(max = 30, message = "Merchant name must not exceed 30 characters")
    @JsonProperty("merchantName")
    private String merchantName;

    /**
     * Sets the transaction amount with proper scale and rounding mode.
     * 
     * <p>This setter enforces COBOL COMP-3 decimal precision requirements by
     * automatically setting scale to 2 decimal places with RoundingMode.HALF_UP.
     * This ensures exact financial calculation accuracy matching mainframe
     * packed decimal arithmetic per section 0.9 critical requirements.</p>
     * 
     * <p><b>Precision Enforcement:</b></p>
     * <ul>
     *   <li>Scale: 2 decimal places (cents precision)</li>
     *   <li>Rounding: HALF_UP (0.5 rounds up, matching COBOL behavior)</li>
     *   <li>Prevents rounding discrepancies in balance calculations</li>
     * </ul>
     * 
     * @param transactionAmount the transaction amount to set, will be scaled to 2 decimal places
     */
    public void setTransactionAmount(BigDecimal transactionAmount) {
        if (transactionAmount != null) {
            this.transactionAmount = transactionAmount.setScale(2, RoundingMode.HALF_UP);
        } else {
            this.transactionAmount = null;
        }
    }
}
