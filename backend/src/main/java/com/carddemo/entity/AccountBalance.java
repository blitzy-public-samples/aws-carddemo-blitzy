/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing Account Balance data.
 * 
 * <p>This entity stores calculated account balance information including
 * opening balance, credits, debits, and closing balance for a specific
 * effective date. Used by the account balance calculation batch job.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>ACCT-ID PIC 9(11) → Long (account identifier)</li>
 *   <li>OPENING-BAL PIC S9(13)V99 COMP-3 → BigDecimal(15,2)</li>
 *   <li>CREDIT-AMT PIC S9(13)V99 COMP-3 → BigDecimal(15,2)</li>
 *   <li>DEBIT-AMT PIC S9(13)V99 COMP-3 → BigDecimal(15,2)</li>
 *   <li>CLOSING-BAL PIC S9(13)V99 COMP-3 → BigDecimal(15,2)</li>
 *   <li>EFFECTIVE-DATE PIC X(10) → LocalDate</li>
 * </ul>
 * 
 * <p><b>Balance Calculation Formula:</b></p>
 * <pre>
 * closingBalance = openingBalance + creditAmount - debitAmount
 * </pre>
 * 
 * <p><b>Data Integrity:</b></p>
 * <ul>
 *   <li>Primary key: id (auto-generated)</li>
 *   <li>Foreign key: accountId (references account table)</li>
 *   <li>All BigDecimal fields maintain COBOL COMP-3 precision (scale 2, HALF_UP)</li>
 *   <li>Unique constraint on (accountId, effectiveDate)</li>
 * </ul>
 * 
 * <p>Migrated from: CBACT03C.cbl (Account Balance Calculation Batch Program)</p>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "account_balance", indexes = {
    @Index(name = "idx_account_balance_account", columnList = "account_id"),
    @Index(name = "idx_account_balance_date", columnList = "effective_date"),
    @Index(name = "idx_account_balance_status", columnList = "balance_status")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_account_balance_account_date", 
                      columnNames = {"account_id", "effective_date"})
})
public class AccountBalance {

    /**
     * Account Balance ID - Primary Key (auto-generated)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * Account ID - Foreign Key to Account entity
     * COBOL: ACCT-ID PIC 9(11)
     */
    @NotNull
    @Column(name = "account_id", nullable = false, precision = 11)
    private Long accountId;

    /**
     * Opening balance (balance at start of period)
     * COBOL: OPENING-BAL PIC S9(13)V99 COMP-3
     * Precision 15, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "opening_balance", precision = 15, scale = 2, nullable = false)
    private BigDecimal openingBalance;

    /**
     * Total credit amount (sum of all credits in period)
     * COBOL: CREDIT-AMT PIC S9(13)V99 COMP-3
     * Precision 15, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "credit_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal creditAmount;

    /**
     * Total debit amount (sum of all debits in period)
     * COBOL: DEBIT-AMT PIC S9(13)V99 COMP-3
     * Precision 15, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "debit_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal debitAmount;

    /**
     * Closing balance (calculated: opening + credits - debits)
     * COBOL: CLOSING-BAL PIC S9(13)V99 COMP-3
     * Precision 15, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "balance_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal balanceAmount;

    /**
     * Effective date for this balance calculation
     * COBOL: EFFECTIVE-DATE PIC X(10)
     */
    @NotNull
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /**
     * Number of transactions processed for this balance calculation
     */
    @NotNull
    @Column(name = "transaction_count", nullable = false)
    private Integer transactionCount;

    /**
     * Balance status indicator
     * Valid values: 'POSITIVE', 'NEGATIVE', 'ZERO'
     */
    @Column(name = "balance_status", length = 20)
    private String balanceStatus;

    /**
     * Timestamp when record was created
     * Added for audit trail per Section 0.9 requirements
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when record was last updated
     * Added for audit trail per Section 0.9 requirements
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Default constructor required by JPA
     */
    public AccountBalance() {
        // Initialize BigDecimal fields with proper scale
        this.openingBalance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.creditAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.debitAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.balanceAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.transactionCount = 0;
        this.balanceStatus = "ZERO";
    }

    /**
     * Constructor with account ID and effective date
     * 
     * @param accountId Account identifier
     * @param effectiveDate Effective date for balance
     */
    public AccountBalance(Long accountId, LocalDate effectiveDate) {
        this();
        this.accountId = accountId;
        this.effectiveDate = effectiveDate;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance != null 
            ? openingBalance.setScale(2, RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(BigDecimal creditAmount) {
        this.creditAmount = creditAmount != null 
            ? creditAmount.setScale(2, RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getDebitAmount() {
        return debitAmount;
    }

    public void setDebitAmount(BigDecimal debitAmount) {
        this.debitAmount = debitAmount != null 
            ? debitAmount.setScale(2, RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getBalanceAmount() {
        return balanceAmount;
    }

    public void setBalanceAmount(BigDecimal balanceAmount) {
        this.balanceAmount = balanceAmount != null 
            ? balanceAmount.setScale(2, RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    public Integer getTransactionCount() {
        return transactionCount;
    }

    public void setTransactionCount(Integer transactionCount) {
        this.transactionCount = transactionCount != null ? transactionCount : 0;
    }

    public String getBalanceStatus() {
        return balanceStatus;
    }

    public void setBalanceStatus(String balanceStatus) {
        this.balanceStatus = balanceStatus;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountBalance that = (AccountBalance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "AccountBalance{" +
                "id=" + id +
                ", accountId=" + accountId +
                ", openingBalance=" + openingBalance +
                ", creditAmount=" + creditAmount +
                ", debitAmount=" + debitAmount +
                ", balanceAmount=" + balanceAmount +
                ", effectiveDate=" + effectiveDate +
                ", transactionCount=" + transactionCount +
                ", balanceStatus='" + balanceStatus + '\'' +
                '}';
    }
}
