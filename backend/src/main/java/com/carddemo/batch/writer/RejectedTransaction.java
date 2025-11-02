/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.batch.writer;

import java.math.BigDecimal;

/**
 * Data Transfer Object representing a rejected transaction.
 * 
 * <p>This class encapsulates all data from a daily transaction that failed validation,
 * including the original transaction fields and validation failure information.
 * 
 * <p><b>COBOL Structure Mapping:</b>
 * <pre>
 * 01  REJECT-RECORD.
 *     05 REJECT-TRAN-DATA          PIC X(350).  -- Original transaction
 *     05 VALIDATION-TRAILER        PIC X(80).   -- Error details
 *        10 WS-VALIDATION-FAIL-REASON      PIC 9(04).
 *        10 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
 * </pre>
 * 
 * <p><b>COBOL Replacement:</b> Represents the structure of REJECT-RECORD
 * from CBTRN02C.cbl (lines 176-178) with validation trailer from lines 180-182.
 * 
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
public class RejectedTransaction {
    
    /**
     * Transaction ID from the rejected daily transaction.
     * COBOL: DALYTRAN-ID PIC X(16)
     */
    private String transactionId;
    
    /**
     * Card number from the rejected transaction.
     * COBOL: DALYTRAN-CARD-NUM PIC X(16)
     */
    private String cardNumber;
    
    /**
     * Transaction amount from the rejected transaction.
     * COBOL: DALYTRAN-AMT PIC S9(09)V99
     */
    private BigDecimal transactionAmount;
    
    /**
     * Transaction type code.
     * COBOL: DALYTRAN-TYPE-CD PIC X(02)
     */
    private String transactionTypeCode;
    
    /**
     * Transaction category code.
     * COBOL: DALYTRAN-CAT-CD PIC 9(04)
     */
    private Integer transactionCategoryCode;
    
    /**
     * Merchant ID from the rejected transaction.
     * COBOL: DALYTRAN-MERCHANT-ID PIC 9(09)
     */
    private String merchantId;
    
    /**
     * Merchant name from the rejected transaction.
     * COBOL: DALYTRAN-MERCHANT-NAME PIC X(50)
     */
    private String merchantName;
    
    /**
     * Original timestamp from the transaction.
     * COBOL: DALYTRAN-ORIG-TS
     */
    private String originalTimestamp;
    
    /**
     * Complete original transaction data (350 bytes).
     * COBOL: REJECT-TRAN-DATA PIC X(350)
     */
    private String originalTransactionData;
    
    /**
     * Validation failure reason code (4 digits).
     * COBOL: WS-VALIDATION-FAIL-REASON PIC 9(04)
     */
    private Integer validationFailureReasonCode;
    
    /**
     * Validation failure description (76 characters).
     * COBOL: WS-VALIDATION-FAIL-REASON-DESC PIC X(76)
     */
    private String validationFailureDescription;

    /**
     * Default constructor.
     */
    public RejectedTransaction() {
    }

    /**
     * Constructor with required fields.
     *
     * @param transactionId the transaction ID
     * @param validationFailureReasonCode the failure reason code
     * @param validationFailureDescription the failure description
     */
    public RejectedTransaction(String transactionId, Integer validationFailureReasonCode, 
                              String validationFailureDescription) {
        this.transactionId = transactionId;
        this.validationFailureReasonCode = validationFailureReasonCode;
        this.validationFailureDescription = validationFailureDescription;
    }

    // Getters
    public String getTransactionId() {
        return transactionId;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    public Integer getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getOriginalTimestamp() {
        return originalTimestamp;
    }

    public String getOriginalTransactionData() {
        return originalTransactionData;
    }

    public Integer getValidationFailureReasonCode() {
        return validationFailureReasonCode;
    }

    public String getValidationFailureDescription() {
        return validationFailureDescription;
    }

    // Setters
    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public void setTransactionAmount(BigDecimal transactionAmount) {
        this.transactionAmount = transactionAmount;
    }

    public void setTransactionTypeCode(String transactionTypeCode) {
        this.transactionTypeCode = transactionTypeCode;
    }

    public void setTransactionCategoryCode(Integer transactionCategoryCode) {
        this.transactionCategoryCode = transactionCategoryCode;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public void setOriginalTimestamp(String originalTimestamp) {
        this.originalTimestamp = originalTimestamp;
    }

    public void setOriginalTransactionData(String originalTransactionData) {
        this.originalTransactionData = originalTransactionData;
    }

    public void setValidationFailureReasonCode(Integer validationFailureReasonCode) {
        this.validationFailureReasonCode = validationFailureReasonCode;
    }

    public void setValidationFailureDescription(String validationFailureDescription) {
        this.validationFailureDescription = validationFailureDescription;
    }

    @Override
    public String toString() {
        return "RejectedTransaction{" +
                "transactionId='" + transactionId + '\'' +
                ", cardNumber='" + cardNumber + '\'' +
                ", transactionAmount=" + transactionAmount +
                ", validationFailureReasonCode=" + validationFailureReasonCode +
                ", validationFailureDescription='" + validationFailureDescription + '\'' +
                '}';
    }
}
