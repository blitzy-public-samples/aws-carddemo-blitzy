/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing rejected transaction log entries.
 * 
 * <p>This entity stores transactions that failed validation during daily transaction
 * processing, preserving the original transaction data along with detailed validation
 * failure information.
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
 * <p><b>COBOL Replacement:</b> Replaces DALYREJS-FILE sequential file
 * from CBTRN02C.cbl lines 446-465 (2500-WRITE-REJECT-REC paragraph).
 * 
 * <p><b>Rejection Reason Codes:</b>
 * <ul>
 *   <li>100 - INVALID CARD NUMBER FOUND (card not found in XREF file)</li>
 *   <li>101 - ACCOUNT RECORD NOT FOUND</li>
 *   <li>102 - OVERLIMIT TRANSACTION (credit limit exceeded)</li>
 *   <li>103 - TRANSACTION RECEIVED AFTER ACCT EXPIRATION</li>
 *   <li>104 - INVALID TRANSACTION AMOUNT</li>
 *   <li>105 - FUTURE-DATED TRANSACTION</li>
 *   <li>106 - DUPLICATE TRANSACTION ID</li>
 *   <li>107 - INVALID MERCHANT</li>
 *   <li>109 - ACCOUNT RECORD NOT FOUND (during update phase)</li>
 * </ul>
 * 
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@Entity
@Table(name = "rejection_log", indexes = {
    @Index(name = "idx_rejection_transaction_id", columnList = "transaction_id"),
    @Index(name = "idx_rejection_processing_timestamp", columnList = "processing_timestamp"),
    @Index(name = "idx_rejection_reason_code", columnList = "validation_failure_reason_code")
})
public class RejectionLogEntry {

    /**
     * Auto-generated surrogate key for rejection log entries.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Transaction ID from the rejected daily transaction.
     * COBOL: DALYTRAN-ID PIC X(16)
     */
    @NotNull
    @Size(max = 16)
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Timestamp when the rejection was processed.
     * Used for audit trail and rejection analysis.
     */
    @NotNull
    @Column(name = "processing_timestamp", nullable = false)
    private LocalDateTime processingTimestamp;

    /**
     * Validation failure reason code (4 digits).
     * COBOL: WS-VALIDATION-FAIL-REASON PIC 9(04)
     */
    @NotNull
    @Column(name = "validation_failure_reason_code", nullable = false)
    private Integer validationFailureReasonCode;

    /**
     * Validation failure description (76 characters).
     * COBOL: WS-VALIDATION-FAIL-REASON-DESC PIC X(76)
     */
    @NotNull
    @Size(max = 76)
    @Column(name = "validation_failure_description", length = 76, nullable = false)
    private String validationFailureDescription;

    /**
     * Complete original transaction data (350 bytes).
     * COBOL: REJECT-TRAN-DATA PIC X(350)
     * Preserved for audit trail and potential reprocessing.
     */
    @Column(name = "original_transaction_data", length = 350)
    private String originalTransactionData;

    /**
     * Card number from the rejected transaction.
     * COBOL: DALYTRAN-CARD-NUM PIC X(16)
     * Extracted for queryability.
     */
    @Size(max = 16)
    @Column(name = "card_number", length = 16)
    private String cardNumber;

    /**
     * Transaction amount from the rejected transaction.
     * COBOL: DALYTRAN-AMT PIC S9(09)V99
     * BigDecimal with scale 2 for COMP-3 equivalence.
     */
    @Column(name = "transaction_amount", precision = 11, scale = 2)
    private BigDecimal transactionAmount;

    /**
     * Transaction type code.
     * COBOL: DALYTRAN-TYPE-CD PIC X(02)
     */
    @Size(max = 2)
    @Column(name = "transaction_type_code", length = 2)
    private String transactionTypeCode;

    /**
     * Transaction category code.
     * COBOL: DALYTRAN-CAT-CD PIC 9(04)
     */
    @Column(name = "transaction_category_code")
    private Integer transactionCategoryCode;

    /**
     * Merchant ID from the rejected transaction.
     * COBOL: DALYTRAN-MERCHANT-ID PIC 9(09)
     */
    @Size(max = 50)
    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    /**
     * Merchant name from the rejected transaction.
     * COBOL: DALYTRAN-MERCHANT-NAME PIC X(50)
     */
    @Size(max = 50)
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Original timestamp from the transaction.
     * COBOL: DALYTRAN-ORIG-TS
     * Stored as string to preserve original format.
     */
    @Size(max = 26)
    @Column(name = "original_timestamp", length = 26)
    private String originalTimestamp;

    /**
     * Default constructor for JPA.
     */
    public RejectionLogEntry() {
    }

    /**
     * Constructor with required fields.
     *
     * @param transactionId the transaction ID
     * @param processingTimestamp the processing timestamp
     * @param validationFailureReasonCode the failure reason code
     * @param validationFailureDescription the failure description
     */
    public RejectionLogEntry(String transactionId, LocalDateTime processingTimestamp,
                            Integer validationFailureReasonCode, String validationFailureDescription) {
        this.transactionId = transactionId;
        this.processingTimestamp = processingTimestamp;
        this.validationFailureReasonCode = validationFailureReasonCode;
        this.validationFailureDescription = validationFailureDescription;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public LocalDateTime getProcessingTimestamp() {
        return processingTimestamp;
    }

    public void setProcessingTimestamp(LocalDateTime processingTimestamp) {
        this.processingTimestamp = processingTimestamp;
    }

    public Integer getValidationFailureReasonCode() {
        return validationFailureReasonCode;
    }

    public void setValidationFailureReasonCode(Integer validationFailureReasonCode) {
        this.validationFailureReasonCode = validationFailureReasonCode;
    }

    public String getValidationFailureDescription() {
        return validationFailureDescription;
    }

    public void setValidationFailureDescription(String validationFailureDescription) {
        this.validationFailureDescription = validationFailureDescription;
    }

    public String getOriginalTransactionData() {
        return originalTransactionData;
    }

    public void setOriginalTransactionData(String originalTransactionData) {
        this.originalTransactionData = originalTransactionData;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    public void setTransactionAmount(BigDecimal transactionAmount) {
        this.transactionAmount = transactionAmount;
    }

    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    public void setTransactionTypeCode(String transactionTypeCode) {
        this.transactionTypeCode = transactionTypeCode;
    }

    public Integer getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    public void setTransactionCategoryCode(Integer transactionCategoryCode) {
        this.transactionCategoryCode = transactionCategoryCode;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getOriginalTimestamp() {
        return originalTimestamp;
    }

    public void setOriginalTimestamp(String originalTimestamp) {
        this.originalTimestamp = originalTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RejectionLogEntry that = (RejectionLogEntry) o;
        return Objects.equals(id, that.id) &&
               Objects.equals(transactionId, that.transactionId) &&
               Objects.equals(processingTimestamp, that.processingTimestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, transactionId, processingTimestamp);
    }

    @Override
    public String toString() {
        return "RejectionLogEntry{" +
                "id=" + id +
                ", transactionId='" + transactionId + '\'' +
                ", processingTimestamp=" + processingTimestamp +
                ", validationFailureReasonCode=" + validationFailureReasonCode +
                ", validationFailureDescription='" + validationFailureDescription + '\'' +
                ", cardNumber='" + cardNumber + '\'' +
                ", transactionAmount=" + transactionAmount +
                '}';
    }
}
