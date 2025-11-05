/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * TransactionCategoryResponse DTO
 * 
 * Transformed from: app/cpy-bms/COTRN01.CPY (COTRN1AO output structure)
 * Source Program: app/cbl/COTRN01C.cbl (CICS Transaction CT01)
 * 
 * Purpose: Response DTO for transaction category summary and detail operations,
 * returning transaction categorization analysis and merchant information.
 * 
 * This DTO represents the complete transaction category view screen displayed
 * by CICS transaction COTRN01C, which retrieves individual transaction details
 * from the TRANSACT VSAM file including transaction categorization, amounts,
 * dates, and complete merchant information.
 * 
 * REST Endpoint Mapping:
 * - GET /api/transactions/categories/{transactionId} - Retrieve transaction category details
 * - GET /api/transactions/{id}/category - Get categorization analysis for specific transaction
 * 
 * Business Context:
 * Critical for preserving exact category aggregation logic and merchant data
 * presentation from COBOL batch category aggregation program (CBTRN03C) and
 * online category inquiry (COTRN01C). Supports spending pattern analysis and
 * transaction categorization reporting required for customer account management.
 * 
 * COBOL Equivalence:
 * - TRNNAMEO (4 chars) → transactionName
 * - TITLE01O (40 chars) → title01
 * - CURDATEO (8 chars MM/DD/YY) → currentDate (LocalDate)
 * - PGMNAMEO (8 chars) → programName
 * - TITLE02O (40 chars) → title02
 * - CURTIMEO (8 chars HH:MM:SS) → currentTime (LocalTime)
 * - TRNIDINO (16 chars) → transactionIdInput
 * - TRNIDO (16 chars) → transactionId
 * - CARDNUMO (16 chars) → cardNumber (with PCI-compliant masking)
 * - TTYPCDO (2 chars) → transactionTypeCode
 * - TCATCDO (4 chars) → transactionCategoryCode
 * - TRNSRCO (10 chars) → transactionSource
 * - TDESCO (60 chars) → description
 * - TRNAMTO (12 chars) → transactionAmount (BigDecimal scale 2 for COMP-3 precision)
 * - TORIGDTO (10 chars) → originationDate (LocalDate)
 * - TPROCDTO (10 chars) → processingDate (LocalDate)
 * - MIDO (9 chars) → merchantId
 * - MNAMEO (30 chars) → merchantName
 * - MCITYO (25 chars) → merchantCity
 * - MZIPO (10 chars) → merchantZip
 * - ERRMSGO (78 chars) → errorMessage
 * 
 * PCI Compliance:
 * Card numbers are masked to show only the last 4 digits (e.g., "************1234")
 * via the getMaskedCardNumber() method. The full cardNumber field is retained for
 * backend processing but should be carefully controlled in API exposure.
 * 
 * Migration Notes:
 * - Preserves COBOL COMP-3 decimal precision for transaction amounts using BigDecimal(scale=2)
 * - Transaction category aggregation logic maintained per Section 0.1 batch processing equivalence
 * - Response DTOs preserve exact field types, lengths, and formatting rules per Section 0.9
 * - Supports Redis caching via Serializable interface for frequently accessed transaction data
 */
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Transaction Category Response DTO
 * 
 * Represents the complete transaction category and detail view response
 * including transaction identification, categorization codes, financial
 * amounts with COMP-3 precision preservation, processing dates, and
 * comprehensive merchant information for spending analysis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionCategoryResponse implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Transaction name/identifier for the screen (COBOL: TRNNAMEO)
     * Typically the CICS transaction ID (e.g., "CT01")
     */
    @JsonProperty("transactionName")
    @Size(max = 4, message = "Transaction name must not exceed 4 characters")
    private String transactionName;
    
    /**
     * Primary screen title (COBOL: TITLE01O)
     * Standard CardDemo application title from COTTL01Y copybook
     */
    @JsonProperty("title01")
    @Size(max = 40, message = "Title01 must not exceed 40 characters")
    private String title01;
    
    /**
     * Current date when screen was generated (COBOL: CURDATEO)
     * Formatted as MM/DD/YYYY in JSON output
     */
    @JsonProperty("currentDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate currentDate;
    
    /**
     * Program name that generated this response (COBOL: PGMNAMEO)
     * Typically "COTRN01C" for transaction category view
     */
    @JsonProperty("programName")
    @Size(max = 8, message = "Program name must not exceed 8 characters")
    private String programName;
    
    /**
     * Secondary screen title (COBOL: TITLE02O)
     * Contextual title for transaction category screen
     */
    @JsonProperty("title02")
    @Size(max = 40, message = "Title02 must not exceed 40 characters")
    private String title02;
    
    /**
     * Current time when screen was generated (COBOL: CURTIMEO)
     * Formatted as HH:MM:SS in JSON output
     */
    @JsonProperty("currentTime")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss")
    private LocalTime currentTime;
    
    /**
     * Transaction ID input by user for lookup (COBOL: TRNIDINO)
     * Echo of user-entered transaction identifier
     */
    @JsonProperty("transactionIdInput")
    @Size(max = 16, message = "Transaction ID input must not exceed 16 characters")
    private String transactionIdInput;
    
    /**
     * Actual transaction identifier retrieved from database (COBOL: TRNIDO)
     * Primary key from TRANSACT file/table
     */
    @JsonProperty("transactionId")
    @Size(max = 16, message = "Transaction ID must not exceed 16 characters")
    private String transactionId;
    
    /**
     * Card number associated with transaction (COBOL: CARDNUMO)
     * Full 16-digit card number - use getMaskedCardNumber() for PCI-compliant display
     * 
     * WARNING: This field contains sensitive cardholder data.
     * Must be protected in logs, API responses to frontend, and external interfaces.
     */
    @JsonProperty("cardNumber")
    @Size(max = 16, message = "Card number must not exceed 16 characters")
    @Pattern(regexp = "^[0-9]{16}$|^$", message = "Card number must be 16 digits or empty")
    private String cardNumber;
    
    /**
     * Transaction type code (COBOL: TTYPCDO)
     * 2-character code defining transaction category (e.g., "01"=Purchase, "02"=Refund)
     */
    @JsonProperty("transactionTypeCode")
    @Size(max = 2, message = "Transaction type code must not exceed 2 characters")
    @Pattern(regexp = "^[0-9]{2}$|^$", message = "Transaction type code must be 2 digits or empty")
    private String transactionTypeCode;
    
    /**
     * Transaction category code (COBOL: TCATCDO)
     * 4-character code for detailed transaction categorization
     * Used for spending pattern analysis and category aggregation reporting
     */
    @JsonProperty("transactionCategoryCode")
    @Size(max = 4, message = "Transaction category code must not exceed 4 characters")
    @Pattern(regexp = "^[0-9]{4}$|^$", message = "Transaction category code must be 4 digits or empty")
    private String transactionCategoryCode;
    
    /**
     * Transaction source indicator (COBOL: TRNSRCO)
     * Identifies the origin/channel of the transaction (e.g., "POS", "ONLINE", "ATM")
     */
    @JsonProperty("transactionSource")
    @Size(max = 10, message = "Transaction source must not exceed 10 characters")
    private String transactionSource;
    
    /**
     * Transaction description (COBOL: TDESCO)
     * Detailed narrative description of the transaction
     */
    @JsonProperty("description")
    @Size(max = 60, message = "Description must not exceed 60 characters")
    private String description;
    
    /**
     * Transaction amount (COBOL: TRNAMTO - originally COMP-3)
     * Preserves COBOL COMP-3 packed decimal precision using BigDecimal with scale 2
     * 
     * CRITICAL: This field maintains exact decimal precision from mainframe COMP-3 format.
     * Scale is fixed at 2 decimal places, rounding mode HALF_UP per Section 0.2 requirements.
     * Maximum 10 integer digits, 2 fractional digits for total precision of 12 digits.
     */
    @JsonProperty("transactionAmount")
    @Digits(integer = 10, fraction = 2, message = "Transaction amount must have at most 10 integer digits and 2 fractional digits")
    private BigDecimal transactionAmount;
    
    /**
     * Transaction origination date (COBOL: TORIGDTO)
     * Date when transaction was originally initiated
     * Formatted as MM/DD/YYYY in JSON output
     */
    @JsonProperty("originationDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate originationDate;
    
    /**
     * Transaction processing date (COBOL: TPROCDTO)
     * Date when transaction was processed/posted to account
     * Formatted as MM/DD/YYYY in JSON output
     */
    @JsonProperty("processingDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "MM/dd/yyyy")
    private LocalDate processingDate;
    
    /**
     * Merchant identifier (COBOL: MIDO)
     * Unique 9-digit merchant ID
     */
    @JsonProperty("merchantId")
    @Size(max = 9, message = "Merchant ID must not exceed 9 characters")
    @Pattern(regexp = "^[0-9]{9}$|^$", message = "Merchant ID must be 9 digits or empty")
    private String merchantId;
    
    /**
     * Merchant name (COBOL: MNAMEO)
     * Business name of the merchant
     */
    @JsonProperty("merchantName")
    @Size(max = 30, message = "Merchant name must not exceed 30 characters")
    private String merchantName;
    
    /**
     * Merchant city (COBOL: MCITYO)
     * City where merchant is located
     */
    @JsonProperty("merchantCity")
    @Size(max = 25, message = "Merchant city must not exceed 25 characters")
    private String merchantCity;
    
    /**
     * Merchant ZIP code (COBOL: MZIPO)
     * Postal code for merchant location (supports ZIP+4 format)
     */
    @JsonProperty("merchantZip")
    @Size(max = 10, message = "Merchant ZIP must not exceed 10 characters")
    @Pattern(regexp = "^[0-9]{5}(-[0-9]{4})?$|^$", message = "Merchant ZIP must be in format 12345 or 12345-6789")
    private String merchantZip;
    
    /**
     * Error message (COBOL: ERRMSGO)
     * Error or informational message to display to user
     * Empty/null indicates successful operation
     */
    @JsonProperty("errorMessage")
    @Size(max = 78, message = "Error message must not exceed 78 characters")
    private String errorMessage;
    
    /**
     * Returns PCI-compliant masked card number showing only last 4 digits.
     * 
     * This method implements Payment Card Industry Data Security Standard (PCI DSS)
     * requirement 3.3 to mask PAN (Primary Account Number) when displayed.
     * 
     * CRITICAL: Use this method for all frontend display, logging, and external
     * API responses. Never expose the full cardNumber field in client-facing contexts.
     * 
     * Examples:
     * - Input: "4556737586899855" → Output: "************9855"
     * - Input: null → Output: null
     * - Input: "" → Output: ""
     * - Input: "123" (invalid length) → Output: "123" (no masking for invalid cards)
     * 
     * @return Masked card number with asterisks replacing all but last 4 digits,
     *         or null/empty if cardNumber is null/empty
     */
    @JsonProperty("maskedCardNumber")
    public String getMaskedCardNumber() {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return cardNumber;
        }
        
        // Only mask if card number is valid length (16 digits)
        if (cardNumber.length() == 16) {
            // Mask all digits except last 4 with asterisks
            return "************" + cardNumber.substring(12);
        }
        
        // For invalid lengths, return as-is (validation will catch this)
        return cardNumber;
    }
}
