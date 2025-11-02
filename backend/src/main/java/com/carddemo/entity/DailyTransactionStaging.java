/*
 * DailyTransactionStaging.java
 *
 * JPA Entity for daily transaction staging table used in batch processing.
 * This entity represents the temporary staging area where daily transactions
 * are loaded before validation and posting to the permanent transaction table.
 *
 * Original COBOL:
 *   - Copybook: CVTRA06Y.cpy (DALYTRAN-RECORD structure)
 *   - File: DALYTRAN-FILE (sequential file in CBTRN02C.cbl)
 *   - Purpose: Staging area for daily batch transaction processing
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 */
package com.carddemo.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity representing a daily transaction staging record.
 * 
 * This table serves as a staging area for daily transaction batch processing.
 * Records are loaded with status 'PENDING', validated, and then either:
 * - Posted to permanent TRANSACTION table (status → 'POSTED')
 * - Rejected with validation errors (status → 'REJECTED')
 * 
 * <p>COBOL Structure Mapping (CVTRA06Y.cpy):</p>
 * <pre>
 * COBOL Field               → Java Field            → DB Column
 * ---------------           -----------------       ----------------
 * DALYTRAN-ID               → transactionId        → transaction_id
 * DALYTRAN-TYPE-CD          → typeCode             → type_code
 * DALYTRAN-CAT-CD           → categoryCode         → category_code
 * DALYTRAN-SOURCE           → source               → source
 * DALYTRAN-DESC             → description          → description
 * DALYTRAN-AMT              → amount               → amount
 * DALYTRAN-MERCHANT-ID      → merchantId           → merchant_id
 * DALYTRAN-MERCHANT-NAME    → merchantName         → merchant_name
 * DALYTRAN-MERCHANT-CITY    → merchantCity         → merchant_city
 * DALYTRAN-MERCHANT-ZIP     → merchantZip          → merchant_zip
 * DALYTRAN-CARD-NUM         → cardNumber           → card_number
 * DALYTRAN-ORIG-TS          → originalTimestamp    → original_timestamp
 * DALYTRAN-PROC-TS          → processedTimestamp   → processed_timestamp
 * </pre>
 */
@Entity
@Table(name = "daily_transaction_staging",
       indexes = {
           @Index(name = "idx_staging_status_txn_id", columnList = "status, transaction_id"),
           @Index(name = "idx_staging_card_num", columnList = "card_number"),
           @Index(name = "idx_staging_orig_ts", columnList = "original_timestamp")
       })
public class DailyTransactionStaging {

    /**
     * Transaction unique identifier (primary key).
     * COBOL: DALYTRAN-ID PIC X(16)
     */
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Transaction type code.
     * COBOL: DALYTRAN-TYPE-CD PIC X(02)
     * Values: 'DR' = Debit (charge), 'CR' = Credit (payment/refund)
     */
    @Column(name = "type_code", length = 2, nullable = false)
    private String typeCode;

    /**
     * Transaction category code.
     * COBOL: DALYTRAN-CAT-CD PIC 9(04)
     * Valid range: 1-9999
     */
    @Column(name = "category_code", nullable = false)
    private Integer categoryCode;

    /**
     * Transaction source/channel.
     * COBOL: DALYTRAN-SOURCE PIC X(10)
     * Values: 'POS', 'ATM', 'ONLINE', 'PHONE', 'MAIL', etc.
     */
    @Column(name = "source", length = 10, nullable = false)
    private String source;

    /**
     * Transaction description.
     * COBOL: DALYTRAN-DESC PIC X(100)
     */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Transaction amount with COMP-3 precision preservation.
     * COBOL: DALYTRAN-AMT PIC S9(09)V99
     * Precision: 11 total digits, 2 decimal places
     * Positive = charge to customer
     * Negative = credit to customer (payment/refund)
     */
    @Column(name = "amount", precision = 11, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Merchant identifier.
     * COBOL: DALYTRAN-MERCHANT-ID PIC 9(09)
     * Stored as String to preserve leading zeros
     */
    @Column(name = "merchant_id", length = 15)
    private String merchantId;

    /**
     * Merchant name.
     * COBOL: DALYTRAN-MERCHANT-NAME PIC X(50)
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city.
     * COBOL: DALYTRAN-MERCHANT-CITY PIC X(50)
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP code.
     * COBOL: DALYTRAN-MERCHANT-ZIP PIC X(10)
     */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number used for the transaction.
     * COBOL: DALYTRAN-CARD-NUM PIC X(16)
     */
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNumber;

    /**
     * Original transaction timestamp from source system.
     * COBOL: DALYTRAN-ORIG-TS PIC X(26)
     * Format: YYYY-MM-DD-HH.MM.SS.nnnnnn
     */
    @Column(name = "original_timestamp", nullable = false)
    private LocalDateTime originalTimestamp;

    /**
     * Processing timestamp when record was validated/posted.
     * COBOL: DALYTRAN-PROC-TS PIC X(26)
     */
    @Column(name = "processed_timestamp")
    private LocalDateTime processedTimestamp;

    /**
     * Processing status indicator.
     * Values:
     * - 'PENDING' : Loaded, awaiting validation and posting
     * - 'POSTED'  : Successfully validated and posted to TRANSACTION table
     * - 'REJECTED': Failed validation, written to reject file
     */
    @Column(name = "status", length = 10, nullable = false)
    private String status = "PENDING";

    /**
     * Validation failure reason code (if rejected).
     * Corresponds to WS-VALIDATION-FAIL-REASON in COBOL
     */
    @Column(name = "reject_reason_code")
    private Integer rejectReasonCode;

    /**
     * Validation failure description (if rejected).
     * Corresponds to WS-VALIDATION-FAIL-REASON-DESC in COBOL
     */
    @Column(name = "reject_reason_desc", length = 100)
    private String rejectReasonDesc;

    /**
     * Audit field: Record creation timestamp.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit field: Record last update timestamp.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // JPA Lifecycle Callbacks

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Constructors

    public DailyTransactionStaging() {
    }

    public DailyTransactionStaging(String transactionId) {
        this.transactionId = transactionId;
    }

    // Getters and Setters

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public Integer getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(Integer categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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

    public String getMerchantCity() {
        return merchantCity;
    }

    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public LocalDateTime getOriginalTimestamp() {
        return originalTimestamp;
    }

    public void setOriginalTimestamp(LocalDateTime originalTimestamp) {
        this.originalTimestamp = originalTimestamp;
    }

    public LocalDateTime getProcessedTimestamp() {
        return processedTimestamp;
    }

    public void setProcessedTimestamp(LocalDateTime processedTimestamp) {
        this.processedTimestamp = processedTimestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRejectReasonCode() {
        return rejectReasonCode;
    }

    public void setRejectReasonCode(Integer rejectReasonCode) {
        this.rejectReasonCode = rejectReasonCode;
    }

    public String getRejectReasonDesc() {
        return rejectReasonDesc;
    }

    public void setRejectReasonDesc(String rejectReasonDesc) {
        this.rejectReasonDesc = rejectReasonDesc;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Object Methods

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DailyTransactionStaging)) return false;
        DailyTransactionStaging that = (DailyTransactionStaging) o;
        return transactionId != null && transactionId.equals(that.transactionId);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "DailyTransactionStaging{" +
                "transactionId='" + transactionId + '\'' +
                ", typeCode='" + typeCode + '\'' +
                ", categoryCode=" + categoryCode +
                ", amount=" + amount +
                ", cardNumber='" + cardNumber + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
