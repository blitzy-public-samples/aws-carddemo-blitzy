/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing Transaction master data.
 * 
 * <p>This entity transforms the COBOL TRAN-RECORD structure from CVTRA05Y.cpy
 * (350-byte fixed-length record) to a relational database table.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>TRAN-ID PIC X(16) → String (16-character transaction ID)</li>
 *   <li>TRAN-TYPE-CD PIC X(02) → String (transaction type code)</li>
 *   <li>TRAN-CAT-CD PIC 9(04) → Integer (transaction category code)</li>
 *   <li>TRAN-SOURCE PIC X(10) → String (transaction source)</li>
 *   <li>TRAN-DESC PIC X(100) → String (transaction description)</li>
 *   <li>TRAN-AMT PIC S9(09)V99 → BigDecimal(11,2) with HALF_UP rounding</li>
 *   <li>TRAN-MERCHANT-ID PIC 9(09) → Long (merchant identifier)</li>
 *   <li>TRAN-MERCHANT-NAME PIC X(50) → String (merchant name)</li>
 *   <li>TRAN-MERCHANT-CITY PIC X(50) → String (merchant city)</li>
 *   <li>TRAN-MERCHANT-ZIP PIC X(10) → String (merchant ZIP)</li>
 *   <li>TRAN-CARD-NUM PIC X(16) → String (card number)</li>
 *   <li>TRAN-ORIG-TS PIC X(26) → LocalDateTime (origination timestamp)</li>
 *   <li>TRAN-PROC-TS PIC X(26) → LocalDateTime (processing timestamp)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "transaction", indexes = {
    @Index(name = "idx_transaction_account", columnList = "account_id"),
    @Index(name = "idx_transaction_date", columnList = "transaction_date"),
    @Index(name = "idx_transaction_card", columnList = "card_number"),
    @Index(name = "idx_transaction_type", columnList = "transaction_type_code")
})
public class Transaction {

    /**
     * Transaction ID - Primary Key
     * COBOL: TRAN-ID PIC X(16)
     */
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Account ID - Foreign Key to Account entity
     */
    @NotNull
    @Column(name = "account_id", nullable = false, precision = 11)
    private Long accountId;

    /**
     * Transaction type code
     * COBOL: TRAN-TYPE-CD PIC X(02)
     */
    @NotNull
    @Size(max = 2)
    @Column(name = "transaction_type_code", length = 2, nullable = false)
    private String transactionTypeCode;

    /**
     * Transaction category code
     * COBOL: TRAN-CAT-CD PIC 9(04)
     */
    @NotNull
    @Column(name = "transaction_category_code", nullable = false)
    private Integer transactionCategoryCode;

    /**
     * Transaction source
     * COBOL: TRAN-SOURCE PIC X(10)
     */
    @Size(max = 10)
    @Column(name = "transaction_source", length = 10)
    private String transactionSource;

    /**
     * Transaction description
     * COBOL: TRAN-DESC PIC X(100)
     */
    @Size(max = 100)
    @Column(name = "transaction_description", length = 100)
    private String transactionDescription;

    /**
     * Transaction amount
     * COBOL: TRAN-AMT PIC S9(09)V99
     * Precision 11, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "transaction_amount", precision = 11, scale = 2, nullable = false)
    private BigDecimal transactionAmount;

    /**
     * Merchant ID
     * COBOL: TRAN-MERCHANT-ID PIC 9(09)
     */
    @Column(name = "merchant_id", precision = 9)
    private Long merchantId;

    /**
     * Merchant name
     * COBOL: TRAN-MERCHANT-NAME PIC X(50)
     */
    @Size(max = 50)
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city
     * COBOL: TRAN-MERCHANT-CITY PIC X(50)
     */
    @Size(max = 50)
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP code
     * COBOL: TRAN-MERCHANT-ZIP PIC X(10)
     */
    @Size(max = 10)
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number
     * COBOL: TRAN-CARD-NUM PIC X(16)
     */
    @NotNull
    @Size(max = 16)
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNumber;

    /**
     * Transaction date (derived from origination timestamp)
     * Used for date range queries in statement generation
     */
    @NotNull
    @Column(name = "transaction_date", nullable = false)
    private LocalDate transactionDate;

    /**
     * Origination timestamp
     * COBOL: TRAN-ORIG-TS PIC X(26)
     */
    @NotNull
    @Column(name = "origination_timestamp", nullable = false)
    private LocalDateTime originationTimestamp;

    /**
     * Processing timestamp
     * COBOL: TRAN-PROC-TS PIC X(26)
     */
    @Column(name = "processing_timestamp")
    private LocalDateTime processingTimestamp;

    /**
     * Default constructor required by JPA
     */
    public Transaction() {
        this.transactionAmount = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Constructor with transaction ID
     * 
     * @param transactionId Transaction identifier
     */
    public Transaction(String transactionId) {
        this();
        this.transactionId = transactionId;
    }

    // Getters and Setters

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
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

    public String getTransactionSource() {
        return transactionSource;
    }

    public void setTransactionSource(String transactionSource) {
        this.transactionSource = transactionSource;
    }

    public String getTransactionDescription() {
        return transactionDescription;
    }

    public void setTransactionDescription(String transactionDescription) {
        this.transactionDescription = transactionDescription;
    }

    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    public void setTransactionAmount(BigDecimal transactionAmount) {
        this.transactionAmount = transactionAmount != null
            ? transactionAmount.setScale(2, java.math.RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
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

    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(LocalDate transactionDate) {
        this.transactionDate = transactionDate;
    }

    public LocalDateTime getOriginationTimestamp() {
        return originationTimestamp;
    }

    public void setOriginationTimestamp(LocalDateTime originationTimestamp) {
        this.originationTimestamp = originationTimestamp;
        // Auto-set transaction date from timestamp
        if (originationTimestamp != null) {
            this.transactionDate = originationTimestamp.toLocalDate();
        }
    }

    public LocalDateTime getProcessingTimestamp() {
        return processingTimestamp;
    }

    public void setProcessingTimestamp(LocalDateTime processingTimestamp) {
        this.processingTimestamp = processingTimestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return Objects.equals(transactionId, that.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "transactionId='" + transactionId + '\'' +
                ", accountId=" + accountId +
                ", transactionTypeCode='" + transactionTypeCode + '\'' +
                ", transactionAmount=" + transactionAmount +
                ", transactionDate=" + transactionDate +
                ", cardNumber='" + cardNumber + '\'' +
                '}';
    }
}
