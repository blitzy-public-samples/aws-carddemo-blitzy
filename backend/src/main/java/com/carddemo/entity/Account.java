/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity representing Account master data.
 * 
 * <p>This entity transforms the COBOL ACCOUNT-RECORD structure from CVACT01Y.cpy
 * (300-byte fixed-length record) to a relational database table with proper
 * JPA mappings and validation constraints.</p>
 * 
 * <p><b>COBOL Transformation Details:</b></p>
 * <ul>
 *   <li>ACCT-ID PIC 9(11) → Long (11-digit account identifier)</li>
 *   <li>ACCT-ACTIVE-STATUS PIC X(01) → String (A=Active, C=Closed)</li>
 *   <li>ACCT-CURR-BAL PIC S9(10)V99 → BigDecimal(12,2) with HALF_UP rounding</li>
 *   <li>ACCT-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal(12,2)</li>
 *   <li>ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 → BigDecimal(12,2)</li>
 *   <li>ACCT-OPEN-DATE PIC X(10) → LocalDate (YYYY-MM-DD format)</li>
 *   <li>ACCT-EXPIRAION-DATE PIC X(10) → LocalDate</li>
 *   <li>ACCT-REISSUE-DATE PIC X(10) → LocalDate</li>
 *   <li>ACCT-CURR-CYC-CREDIT PIC S9(10)V99 → BigDecimal(12,2)</li>
 *   <li>ACCT-CURR-CYC-DEBIT PIC S9(10)V99 → BigDecimal(12,2)</li>
 *   <li>ACCT-ADDR-ZIP PIC X(10) → String (trimmed)</li>
 *   <li>ACCT-GROUP-ID PIC X(10) → String (trimmed)</li>
 * </ul>
 * 
 * <p><b>Data Integrity:</b></p>
 * <ul>
 *   <li>Primary key: accountId (ACCT-ID)</li>
 *   <li>Foreign key: customerId (references customer table)</li>
 *   <li>All BigDecimal fields maintain COBOL COMP-3 precision per Section 0.9</li>
 *   <li>Active status validated against enumeration (A, C)</li>
 * </ul>
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024-01-01
 */
@Entity
@Table(name = "account", indexes = {
    @Index(name = "idx_account_status", columnList = "active_status"),
    @Index(name = "idx_account_customer", columnList = "customer_id"),
    @Index(name = "idx_account_group", columnList = "group_id")
})
public class Account {

    /**
     * Account ID - Primary Key
     * COBOL: ACCT-ID PIC 9(11)
     */
    @Id
    @Column(name = "account_id", nullable = false, precision = 11)
    private Long id;

    /**
     * Customer ID - Foreign Key to Customer entity
     * Establishes account-to-customer relationship
     */
    @Column(name = "customer_id", nullable = false, precision = 9)
    private Long customerId;

    /**
     * Account active status
     * COBOL: ACCT-ACTIVE-STATUS PIC X(01)
     * Valid values: 'A' (Active), 'C' (Closed)
     */
    @NotNull
    @Size(min = 1, max = 1)
    @Pattern(regexp = "[AC]", message = "Active status must be 'A' (Active) or 'C' (Closed)")
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * Current account balance
     * COBOL: ACCT-CURR-BAL PIC S9(10)V99
     * Precision 12, Scale 2 to maintain COBOL COMP-3 decimal precision
     */
    @NotNull
    @Column(name = "current_balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentBalance;

    /**
     * Credit limit for purchases
     * COBOL: ACCT-CREDIT-LIMIT PIC S9(10)V99
     */
    @NotNull
    @Column(name = "credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal creditLimit;

    /**
     * Cash advance credit limit
     * COBOL: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99
     */
    @NotNull
    @Column(name = "cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal cashCreditLimit;

    /**
     * Account open date
     * COBOL: ACCT-OPEN-DATE PIC X(10)
     */
    @NotNull
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /**
     * Account expiration date
     * COBOL: ACCT-EXPIRAION-DATE PIC X(10) (typo in original COBOL)
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Account reissue date
     * COBOL: ACCT-REISSUE-DATE PIC X(10)
     */
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    /**
     * Current cycle credit total
     * COBOL: ACCT-CURR-CYC-CREDIT PIC S9(10)V99
     */
    @NotNull
    @Column(name = "current_cycle_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit total
     * COBOL: ACCT-CURR-CYC-DEBIT PIC S9(10)V99
     */
    @NotNull
    @Column(name = "current_cycle_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleDebit;

    /**
     * Account address ZIP code
     * COBOL: ACCT-ADDR-ZIP PIC X(10)
     */
    @Size(max = 10)
    @Column(name = "address_zip", length = 10)
    private String addressZip;

    /**
     * Account group identifier
     * COBOL: ACCT-GROUP-ID PIC X(10)
     */
    @Size(max = 10)
    @Column(name = "group_id", length = 10)
    private String groupId;

    /**
     * Timestamp when record was created
     * Not in COBOL - added for audit trail per Section 0.9 requirements
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp when record was last updated
     * Not in COBOL - added for audit trail per Section 0.9 requirements
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Default constructor required by JPA
     */
    public Account() {
        // Initialize BigDecimal fields with proper scale
        this.currentBalance = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        this.creditLimit = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        this.cashCreditLimit = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        this.currentCycleCredit = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        this.currentCycleDebit = BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
        this.activeStatus = "A"; // Default to Active
    }

    /**
     * Constructor with account ID
     * 
     * @param id Account identifier
     */
    public Account(Long id) {
        this();
        this.id = id;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getActiveStatus() {
        return activeStatus;
    }

    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance != null 
            ? currentBalance.setScale(2, java.math.RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit != null 
            ? creditLimit.setScale(2, java.math.RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit != null 
            ? cashCreditLimit.setScale(2, java.math.RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public LocalDate getOpenDate() {
        return openDate;
    }

    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    public LocalDate getReissueDate() {
        return reissueDate;
    }

    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit != null 
            ? currentCycleCredit.setScale(2, java.math.RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit != null 
            ? currentCycleDebit.setScale(2, java.math.RoundingMode.HALF_UP) 
            : BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public String getAddressZip() {
        return addressZip;
    }

    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip != null ? addressZip.trim() : null;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId != null ? groupId.trim() : null;
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
        Account account = (Account) o;
        return Objects.equals(id, account.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Account{" +
                "id=" + id +
                ", customerId=" + customerId +
                ", activeStatus='" + activeStatus + '\'' +
                ", currentBalance=" + currentBalance +
                ", creditLimit=" + creditLimit +
                ", cashCreditLimit=" + cashCreditLimit +
                ", openDate=" + openDate +
                ", expirationDate=" + expirationDate +
                ", reissueDate=" + reissueDate +
                ", currentCycleCredit=" + currentCycleCredit +
                ", currentCycleDebit=" + currentCycleDebit +
                ", addressZip='" + addressZip + '\'' +
                ", groupId='" + groupId + '\'' +
                '}';
    }
}
