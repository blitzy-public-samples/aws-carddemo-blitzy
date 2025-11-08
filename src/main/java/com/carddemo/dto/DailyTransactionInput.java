package com.carddemo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Daily Transaction Input DTO
 * Maps to COBOL DALYTRAN-RECORD from CVTRA06Y copybook
 * Used as input for batch transaction processing
 * 
 * Original COBOL structure:
 * - DALYTRAN-ID (PIC X(16))
 * - DALYTRAN-TYPE-CD (PIC X(02))
 * - DALYTRAN-CAT-CD (PIC 9(04))
 * - DALYTRAN-SOURCE (PIC X(10))
 * - DALYTRAN-DESC (PIC X(100))
 * - DALYTRAN-AMT (PIC S9(09)V99)
 * - DALYTRAN-MERCHANT-ID (PIC 9(09))
 * - DALYTRAN-MERCHANT-NAME (PIC X(50))
 * - DALYTRAN-MERCHANT-CITY (PIC X(50))
 * - DALYTRAN-MERCHANT-ZIP (PIC X(10))
 * - DALYTRAN-CARD-NUM (PIC X(16))
 * - DALYTRAN-ORIG-TS (PIC X(26))
 * - DALYTRAN-PROC-TS (PIC X(26))
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyTransactionInput {
    
    /**
     * Transaction ID (16 characters)
     * Maps to DALYTRAN-ID
     */
    private String transactionId;
    
    /**
     * Transaction Type Code (2 characters)
     * Maps to DALYTRAN-TYPE-CD
     */
    private String typeCode;
    
    /**
     * Transaction Category Code (4 digits)
     * Maps to DALYTRAN-CAT-CD
     */
    private String categoryCode;
    
    /**
     * Transaction Source (10 characters)
     * Maps to DALYTRAN-SOURCE
     */
    private String transactionSource;
    
    /**
     * Transaction Description (100 characters)
     * Maps to DALYTRAN-DESC
     */
    private String description;
    
    /**
     * Transaction Amount
     * Maps to DALYTRAN-AMT (PIC S9(09)V99)
     * Uses BigDecimal with scale=2 for COBOL COMP-3 precision
     */
    private BigDecimal amount;
    
    /**
     * Merchant ID (9 digits)
     * Maps to DALYTRAN-MERCHANT-ID
     */
    private String merchantId;
    
    /**
     * Merchant Name (50 characters)
     * Maps to DALYTRAN-MERCHANT-NAME
     */
    private String merchantName;
    
    /**
     * Merchant City (50 characters)
     * Maps to DALYTRAN-MERCHANT-CITY
     */
    private String merchantCity;
    
    /**
     * Merchant ZIP Code (10 characters)
     * Maps to DALYTRAN-MERCHANT-ZIP
     */
    private String merchantZip;
    
    /**
     * Card Number (16 characters)
     * Maps to DALYTRAN-CARD-NUM
     */
    private String cardNumber;
    
    /**
     * Origination Timestamp
     * Maps to DALYTRAN-ORIG-TS (PIC X(26))
     * Converted from COBOL timestamp format to LocalDateTime
     */
    private LocalDateTime originationTimestamp;
}
