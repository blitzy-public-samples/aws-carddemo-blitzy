package com.carddemo.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA Entity for transaction_detail_view table.
 * 
 * <p>This entity represents a denormalized transaction reporting table populated by 
 * the TransactionCombineJob batch process. It combines transaction data with enriched 
 * customer, card, account, type, and category information for optimized reporting queries.</p>
 * 
 * <p><strong>Source Transformation:</strong></p>
 * <ul>
 *   <li>COBOL Program: CBTRN03C.cbl - Print the transaction detail report</li>
 *   <li>Replaces COBOL SORT utility with database-driven consolidation</li>
 *   <li>Materializes joined data from transaction, card, account, customer, 
 *       transaction_type, and transaction_category tables</li>
 * </ul>
 * 
 * <p><strong>Design Decisions:</strong></p>
 * <ul>
 *   <li>Physical table (not a database VIEW) to support TRUNCATE and INSERT operations</li>
 *   <li>No foreign key constraints to allow flexible batch data loading</li>
 *   <li>Indexed on commonly queried columns for reporting performance</li>
 *   <li>Refreshed periodically by TransactionCombineJob batch process</li>
 * </ul>
 * 
 * <p><strong>COBOL Data Precision Mapping:</strong></p>
 * <ul>
 *   <li>amount: NUMERIC(11,2) preserves COBOL S9(09)V99 COMP-3 decimal precision</li>
 *   <li>Timestamps: TIMESTAMP(6) provides microsecond precision</li>
 * </ul>
 * 
 * @see com.carddemo.batch.job.TransactionCombineJob
 * @see Transaction
 */
@Entity
@Table(name = "transaction_detail_view", indexes = {
    @Index(name = "idx_transaction_detail_view_proc_ts", columnList = "processing_timestamp DESC"),
    @Index(name = "idx_transaction_detail_view_customer", columnList = "customer_id"),
    @Index(name = "idx_transaction_detail_view_card", columnList = "card_number"),
    @Index(name = "idx_transaction_detail_view_account", columnList = "account_id"),
    @Index(name = "idx_transaction_detail_view_type_cat", columnList = "type_code, category_code")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDetailView {

    /**
     * Unique transaction identifier from transaction table.
     * <p>Primary key: VARCHAR(16)</p>
     * <p>Source: transaction.transaction_id</p>
     */
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Card number associated with the transaction.
     * <p>Source: transaction.card_number</p>
     */
    @Column(name = "card_number", length = 16)
    private String cardNumber;

    /**
     * Account ID enriched from card table.
     * <p>Source: card.account_id</p>
     */
    @Column(name = "account_id")
    private Long accountId;

    /**
     * Customer ID enriched from account table.
     * <p>Source: account.customer_id</p>
     */
    @Column(name = "customer_id")
    private Long customerId;

    /**
     * Customer first name enriched from customer table via card->account->customer joins.
     * <p>Source: customer.first_name</p>
     */
    @Column(name = "customer_first_name", length = 25)
    private String customerFirstName;

    /**
     * Customer last name enriched from customer table via card->account->customer joins.
     * <p>Source: customer.last_name</p>
     */
    @Column(name = "customer_last_name", length = 25)
    private String customerLastName;

    /**
     * Transaction type code.
     * <p>Source: transaction.transaction_type_code</p>
     */
    @Column(name = "type_code", length = 2)
    private String typeCode;

    /**
     * Human-readable transaction type description from transaction_type reference table.
     * <p>Source: transaction_type.description</p>
     */
    @Column(name = "type_description", length = 50)
    private String typeDescription;

    /**
     * Transaction category code.
     * <p>Source: transaction.transaction_category_code</p>
     */
    @Column(name = "category_code")
    private Short categoryCode;

    /**
     * Human-readable transaction category description from transaction_category reference table.
     * <p>Source: transaction_category.description</p>
     */
    @Column(name = "category_description", length = 50)
    private String categoryDescription;

    /**
     * Transaction source (e.g., "POS", "ATM", "ONLINE").
     * <p>Source: transaction.source</p>
     */
    @Column(name = "transaction_source", length = 10)
    private String transactionSource;

    /**
     * Transaction amount with exact decimal precision.
     * <p>NUMERIC(11,2) ensures exact precision matching COBOL S9(09)V99 COMP-3</p>
     * <p>Source: transaction.amount</p>
     */
    @Column(name = "amount", precision = 11, scale = 2)
    private BigDecimal amount;

    /**
     * Original timestamp when transaction was initiated.
     * <p>Source: transaction.original_timestamp</p>
     */
    @Column(name = "origination_timestamp")
    private LocalDateTime originationTimestamp;

    /**
     * Timestamp when transaction was processed.
     * <p>Source: transaction.processed_timestamp</p>
     */
    @Column(name = "processing_timestamp")
    private LocalDateTime processingTimestamp;

    /**
     * Merchant name where transaction occurred.
     * <p>Source: transaction.merchant_name</p>
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city where transaction occurred.
     * <p>Source: transaction.merchant_city</p>
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant postal code where transaction occurred.
     * <p>Source: transaction.merchant_postal_code</p>
     */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Transaction description or memo.
     * <p>Source: transaction.description</p>
     */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Timestamp when this denormalized record was created by batch job.
     * <p>Audit column tracking when batch process materialized this record</p>
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Automatically set creation timestamp before persisting.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
