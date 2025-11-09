package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA Entity representing Transaction master data.
 * 
 * <p>This entity corresponds to the COBOL TRAN-RECORD copybook structure
 * defined in CVTRA05Y.cpy with a 350-byte record layout. It represents transaction
 * information stored in the VSAM KSDS TRANSACT file, now migrated to PostgreSQL
 * transaction table.</p>
 * 
 * <p>Key Transformations from COBOL:</p>
 * <ul>
 *   <li>TRAN-ID (PIC X(16)) → String transactionId (Primary Key)</li>
 *   <li>TRAN-TYPE-CD (PIC X(02)) → String typeCode</li>
 *   <li>TRAN-CAT-CD (PIC 9(04)) → Integer categoryCode</li>
 *   <li>TRAN-SOURCE (PIC X(10)) → String transactionSource</li>
 *   <li>TRAN-DESC (PIC X(100)) → String description</li>
 *   <li>TRAN-AMT (PIC S9(09)V99) → BigDecimal amount with precision=12, scale=2</li>
 *   <li>TRAN-MERCHANT-ID (PIC 9(09)) → Long merchantId</li>
 *   <li>TRAN-MERCHANT-NAME (PIC X(50)) → String merchantName</li>
 *   <li>TRAN-MERCHANT-CITY (PIC X(50)) → String merchantCity</li>
 *   <li>TRAN-MERCHANT-ZIP (PIC X(10)) → String merchantZip</li>
 *   <li>TRAN-CARD-NUM (PIC X(16)) → @ManyToOne Card relationship</li>
 *   <li>TRAN-ORIG-TS (PIC X(26)) → LocalDateTime originationTimestamp</li>
 *   <li>TRAN-PROC-TS (PIC X(26)) → LocalDateTime processingTimestamp</li>
 * </ul>
 * 
 * <p><strong>Relationship to Card Entity:</strong></p>
 * <p>Each transaction is associated with a card through a @ManyToOne relationship.
 * The TRAN-CARD-NUM field (PIC X(16)) from the COBOL copybook establishes this
 * relationship, creating a foreign key constraint on the card_number field. This
 * enables:</p>
 * <ul>
 *   <li>Transaction authorization checking against card status and limits</li>
 *   <li>Transaction history retrieval by card number</li>
 *   <li>Referential integrity ensuring transactions reference valid cards</li>
 *   <li>Efficient navigation from transaction to card and account information</li>
 *   <li>Support for pagination (10 transactions per page as per UI requirements)</li>
 * </ul>
 * 
 * <p><strong>Primary Key Design:</strong></p>
 * <p>The transaction_id field (16-character alphanumeric) serves as the primary key
 * matching VSAM KSDS key structure for transaction master file (TRANSACT). The
 * transaction ID format typically includes:</p>
 * <ul>
 *   <li>Timestamp component for temporal ordering</li>
 *   <li>Sequence number for uniqueness within the same timestamp</li>
 *   <li>System identifier for distributed transaction tracking</li>
 * </ul>
 * 
 * <p><strong>Decimal Precision Requirements (Critical):</strong></p>
 * <p>The amount field uses BigDecimal with precision=12 and scale=2 to preserve
 * exact COBOL COMP-3 packed decimal behavior from TRAN-AMT (PIC S9(09)V99).
 * This ensures:</p>
 * <ul>
 *   <li>Zero precision loss in monetary calculations (matching COBOL arithmetic)</li>
 *   <li>Exact two decimal place representation for cents</li>
 *   <li>Support for transaction amounts up to $999,999,999.99</li>
 *   <li>Consistent rounding behavior using RoundingMode.HALF_UP</li>
 *   <li>Compliance with financial calculation requirements in Section 0.10</li>
 * </ul>
 * 
 * <p><strong>Example BigDecimal Usage:</strong></p>
 * <pre>
 * // Creating transaction with amount
 * Transaction transaction = Transaction.builder()
 *     .transactionId("TXN20240101123456")
 *     .amount(new BigDecimal("1234.56"))
 *     .build();
 * 
 * // Always use BigDecimal methods for arithmetic
 * BigDecimal newBalance = currentBalance.subtract(transaction.getAmount());
 * newBalance = newBalance.setScale(2, RoundingMode.HALF_UP);
 * </pre>
 * 
 * <p><strong>Transaction Type and Category Codes:</strong></p>
 * <p>The typeCode and categoryCode fields link to reference tables:</p>
 * <ul>
 *   <li>typeCode references TransactionType entity (CVTRA03Y.cpy)</li>
 *   <li>categoryCode references TransactionCategory entity (CVTRA04Y.cpy)</li>
 *   <li>These codes classify transactions for reporting and analysis</li>
 *   <li>Examples: type='01' (purchase), '02' (cash advance), '03' (payment)</li>
 *   <li>Examples: category='1000' (retail), '2000' (travel), '3000' (dining)</li>
 * </ul>
 * 
 * <p><strong>Transaction Source Values:</strong></p>
 * <p>The transactionSource field indicates the origin of the transaction:</p>
 * <ul>
 *   <li>'POS' - Point of Sale terminal transaction</li>
 *   <li>'ATM' - Automated Teller Machine withdrawal</li>
 *   <li>'ONLINE' - E-commerce or online banking transaction</li>
 *   <li>'PHONE' - Telephone order transaction</li>
 *   <li>'MAIL' - Mail order transaction</li>
 *   <li>'BATCH' - Batch-posted transaction from daily processing</li>
 * </ul>
 * 
 * <p><strong>Merchant Information:</strong></p>
 * <p>Four fields capture merchant details for transaction tracking:</p>
 * <ul>
 *   <li>merchantId: Unique 9-digit merchant identifier</li>
 *   <li>merchantName: Business name (up to 50 characters)</li>
 *   <li>merchantCity: City location (up to 50 characters)</li>
 *   <li>merchantZip: Postal code (up to 10 characters for international support)</li>
 * </ul>
 * <p>This information is used for:</p>
 * <ul>
 *   <li>Transaction history display showing where charges occurred</li>
 *   <li>Fraud detection analyzing merchant patterns</li>
 *   <li>Dispute resolution providing transaction context</li>
 *   <li>Reporting and analytics by merchant category</li>
 * </ul>
 * 
 * <p><strong>Timestamp Fields:</strong></p>
 * <p>Two timestamp fields track transaction lifecycle:</p>
 * <ul>
 *   <li>originationTimestamp: When transaction was initiated at merchant terminal or
 *       online system (TRAN-ORIG-TS PIC X(26))</li>
 *   <li>processingTimestamp: When transaction was processed and posted to account
 *       by card system (TRAN-PROC-TS PIC X(26))</li>
 * </ul>
 * <p>Both fields convert from COBOL PIC X(26) format to Java LocalDateTime for
 * proper timestamp handling with microsecond precision. The time difference between
 * these timestamps indicates processing latency and is used for:</p>
 * <ul>
 *   <li>Performance monitoring and SLA compliance</li>
 *   <li>Real-time vs batch transaction identification</li>
 *   <li>Settlement and clearing time calculations</li>
 *   <li>Audit trail and regulatory reporting</li>
 * </ul>
 * 
 * <p><strong>Database Indexes:</strong></p>
 * <p>The transaction_id field is indexed as primary key. Additional indexes are
 * created in Flyway migration V7__create_indexes.sql to support:</p>
 * <ul>
 *   <li>Efficient queries by card_number (foreign key index for transaction history)</li>
 *   <li>Date range queries using origination_timestamp (for reporting)</li>
 *   <li>Merchant lookup queries using merchant_id</li>
 *   <li>Transaction type and category analysis queries</li>
 * </ul>
 * 
 * <p><strong>Transaction Processing Flow:</strong></p>
 * <p>This entity is used throughout the transaction processing workflow:</p>
 * <ol>
 *   <li>Transaction Add (COTRN02C.cbl → TransactionAddService): Creates new transaction
 *       with validation against card authorization and account limits</li>
 *   <li>Transaction List (COTRN00C.cbl → TransactionListService): Retrieves
 *       paginated transaction history (10 transactions per page)</li>
 *   <li>Transaction View (COTRN01C.cbl → TransactionViewService): Displays detailed
 *       transaction information including merchant details</li>
 *   <li>Daily Processing (CBTRN02C.cbl → DailyTransactionProcessingJob): Batch job
 *       posts transactions and updates account balances</li>
 *   <li>Reporting (CORPT00C.cbl → ReportGenerationService): Generates transaction
 *       reports with filtering and aggregation</li>
 * </ol>
 * 
 * <p><strong>Optimistic Locking:</strong></p>
 * <p>The entity includes a @Version field for optimistic locking to handle concurrent
 * access patterns that replicate VSAM record locking behavior. This prevents:</p>
 * <ul>
 *   <li>Lost updates during concurrent transaction modifications</li>
 *   <li>Data corruption from simultaneous batch and online processing</li>
 *   <li>Race conditions in multi-threaded transaction processing</li>
 * </ul>
 * <p>If a concurrent modification is detected, JPA throws OptimisticLockException,
 * allowing the application to handle the conflict with refresh and retry logic.</p>
 * 
 * <p>Lombok annotations are used to reduce boilerplate:</p>
 * <ul>
 *   <li>@Data - generates getters, setters, equals, hashCode, toString</li>
 *   <li>@NoArgsConstructor - generates no-args constructor required by JPA</li>
 *   <li>@AllArgsConstructor - generates constructor with all fields</li>
 *   <li>@Builder - generates builder pattern for fluent object construction</li>
 * </ul>
 * 
 * <p><strong>Functional Equivalence:</strong></p>
 * <p>This entity maintains complete functional equivalence with the original COBOL
 * TRAN-RECORD structure, preserving all field mappings, data types (especially
 * BigDecimal precision for amounts), and business logic constraints. All COBOL
 * programs accessing TRANSACT file will use this entity through JPA repositories.</p>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>Transaction volume requirements:</p>
 * <ul>
 *   <li>Support for 10,000 transactions per second (TPS) peak load</li>
 *   <li>Response time under 200ms for transaction authorization (95th percentile)</li>
 *   <li>Efficient pagination for transaction history (10 per page)</li>
 *   <li>Batch processing of daily transactions within 4-hour window</li>
 * </ul>
 * 
 * @see Card
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVTRA05Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - COBOL COMP-3 to Java BigDecimal</a>
 */
@Entity
@Table(name = "transaction")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction ID - Primary Key
     * 
     * <p>Maps from COBOL TRAN-ID field (PIC X(16)).</p>
     * <p>16-character alphanumeric transaction identifier uniquely identifying
     * each transaction in the system.</p>
     * <p>This field serves as the primary key matching VSAM KSDS key structure
     * for transaction master file (TRANSACT).</p>
     * 
     * <p><strong>Format:</strong> Typically includes timestamp and sequence
     * components for uniqueness and temporal ordering. Example formats:</p>
     * <ul>
     *   <li>TXN{YYYYMMDDHHMMSS}{SEQ} - e.g., "TXN202401011230001"</li>
     *   <li>{TIMESTAMP}{SYSTEM}{SEQ} - Distributed system identifier</li>
     * </ul>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Must be unique across all transactions</li>
     *   <li>Generated by transaction processing system</li>
     *   <li>Immutable once created (never changed after initial insert)</li>
     *   <li>Used for transaction lookup, dispute resolution, and audit trails</li>
     * </ul>
     */
    @Id
    @Column(name = "transaction_id", nullable = false, length = 16)
    private String transactionId;

    /**
     * Transaction Type Code
     * 
     * <p>Maps from COBOL TRAN-TYPE-CD field (PIC X(02)).</p>
     * <p>2-character code classifying the type of transaction. References
     * TransactionType reference table (CVTRA03Y.cpy).</p>
     * 
     * <p><strong>Common Type Codes:</strong></p>
     * <ul>
     *   <li>'01' - Purchase/Sale transaction</li>
     *   <li>'02' - Cash advance at ATM or bank</li>
     *   <li>'03' - Payment received (credit to account)</li>
     *   <li>'04' - Fee charge (annual fee, late fee, etc.)</li>
     *   <li>'05' - Interest charge</li>
     *   <li>'06' - Credit adjustment</li>
     *   <li>'07' - Debit adjustment</li>
     *   <li>'08' - Refund/Return</li>
     * </ul>
     * 
     * <p>Used for transaction categorization, reporting, and business logic
     * (e.g., different authorization rules for purchase vs cash advance).</p>
     */
    @Column(name = "transaction_type_code", length = 2)
    private String typeCode;

    /**
     * Transaction Category Code
     * 
     * <p>Maps from COBOL TRAN-CAT-CD field (PIC 9(04)).</p>
     * <p>4-digit numeric code (stored as String to preserve leading zeros)
     * categorizing the transaction by merchant category. References
     * TransactionCategory reference table (CVTRA04Y.cpy).</p>
     * 
     * <p><strong>Category Code Ranges:</strong></p>
     * <ul>
     *   <li>1000-1999: Retail purchases (clothing, electronics, general merchandise)</li>
     *   <li>2000-2999: Travel and entertainment (airlines, hotels, restaurants)</li>
     *   <li>3000-3999: Dining and food services</li>
     *   <li>4000-4999: Automotive (gas stations, car rental, repairs)</li>
     *   <li>5000-5999: Professional services (medical, legal, education)</li>
     *   <li>6000-6999: Utilities and services</li>
     *   <li>7000-7999: Cash advances and ATM withdrawals</li>
     *   <li>8000-8999: Fees and interest charges</li>
     *   <li>9000-9999: Payments and credits</li>
     * </ul>
     * 
     * <p>Used for spending analysis, rewards program calculation, and merchant
     * category reporting.</p>
     */
    @Column(name = "transaction_category_code")
    private Integer categoryCode;

    /**
     * Transaction Source
     * 
     * <p>Maps from COBOL TRAN-SOURCE field (PIC X(10)).</p>
     * <p>Indicates the origin or channel through which the transaction was
     * initiated, up to 10 characters.</p>
     * 
     * <p><strong>Source Values:</strong></p>
     * <ul>
     *   <li>'POS' - Point of Sale terminal (in-person swipe/chip/contactless)</li>
     *   <li>'ATM' - Automated Teller Machine transaction</li>
     *   <li>'ONLINE' - E-commerce or online banking transaction</li>
     *   <li>'PHONE' - Telephone order or phone banking</li>
     *   <li>'MAIL' - Mail order transaction</li>
     *   <li>'BATCH' - Batch-posted transaction (recurring charges, fees)</li>
     *   <li>'MOBILE' - Mobile app transaction</li>
     * </ul>
     * 
     * <p>Used for channel analysis, fraud detection (e.g., multiple channels
     * used rapidly), and transaction routing decisions.</p>
     */
    @Column(name = "transaction_source", length = 10)
    private String transactionSource;

    /**
     * Transaction Description
     * 
     * <p>Maps from COBOL TRAN-DESC field (PIC X(100)).</p>
     * <p>Free-form text description of the transaction, maximum 100 characters.
     * Typically contains merchant name and transaction details as provided by
     * the payment network.</p>
     * 
     * <p><strong>Example Descriptions:</strong></p>
     * <ul>
     *   <li>"AMAZON.COM PURCHASE 123-4567890-1234567"</li>
     *   <li>"SHELL OIL GAS STATION CHICAGO IL"</li>
     *   <li>"HILTON HOTEL NEW YORK NY"</li>
     *   <li>"PAYMENT RECEIVED - THANK YOU"</li>
     *   <li>"ANNUAL FEE"</li>
     *   <li>"INTEREST CHARGE - PURCHASES"</li>
     * </ul>
     * 
     * <p>Displayed in transaction history, statements, and receipts. Should be
     * descriptive enough for cardholder to identify the transaction.</p>
     */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Transaction Amount - CRITICAL PRECISION FIELD
     * 
     * <p>Maps from COBOL TRAN-AMT field (PIC S9(09)V99 COMP-3 packed decimal).</p>
     * <p>Monetary amount of the transaction using BigDecimal with precision=12
     * and scale=2 to preserve exact COBOL COMP-3 decimal behavior.</p>
     * 
     * <p><strong>Precision Requirements (MANDATORY):</strong></p>
     * <ul>
     *   <li>Precision: 12 total digits (10 integer + 2 decimal places)</li>
     *   <li>Scale: 2 (exactly two decimal places for cents)</li>
     *   <li>Maximum value: 9,999,999,999.99 (nearly 10 billion)</li>
     *   <li>Minimum value: -9,999,999,999.99 (negative for credits/refunds)</li>
     *   <li>Rounding: Use RoundingMode.HALF_UP to match COBOL behavior</li>
     * </ul>
     * 
     * <p><strong>Sign Convention:</strong></p>
     * <ul>
     *   <li>Positive amounts: Debits to account (purchases, fees, interest)</li>
     *   <li>Negative amounts: Credits to account (payments, refunds, adjustments)</li>
     * </ul>
     * 
     * <p><strong>Critical Usage Rules:</strong></p>
     * <pre>
     * // CORRECT - Use BigDecimal for all arithmetic
     * BigDecimal newBalance = currentBalance.add(transaction.getAmount());
     * newBalance = newBalance.setScale(2, RoundingMode.HALF_UP);
     * 
     * // WRONG - Never use double/float for monetary calculations
     * double balance = currentBalance + transaction.getAmount(); // PRECISION LOSS!
     * 
     * // CORRECT - Creating amounts from strings
     * transaction.setAmount(new BigDecimal("123.45"));
     * 
     * // WRONG - Creating from double introduces precision errors
     * transaction.setAmount(new BigDecimal(123.45)); // AVOID!
     * </pre>
     * 
     * <p><strong>Validation:</strong></p>
     * <ul>
     *   <li>Must not be null for completed transactions</li>
     *   <li>Must be non-zero (zero-amount transactions are not valid)</li>
     *   <li>Must fit within precision limits (12 digits total, 2 decimal places)</li>
     * </ul>
     * 
     * <p>This field is critical for financial accuracy and must maintain exact
     * precision matching original COBOL calculations. Any precision loss violates
     * functional equivalence requirements per Section 0.10.</p>
     */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * Merchant ID
     * 
     * <p>Maps from COBOL TRAN-MERCHANT-ID field (PIC 9(09)).</p>
     * <p>9-digit numeric identifier uniquely identifying the merchant where
     * the transaction occurred. Assigned by the payment network or acquiring bank.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Links transaction to merchant record for detailed information</li>
     *   <li>Used in fraud detection to analyze merchant patterns</li>
     *   <li>Supports merchant-level reporting and analytics</li>
     *   <li>Enables merchant category analysis and rewards programs</li>
     * </ul>
     * 
     * <p>May be null for non-merchant transactions (e.g., fees, interest charges,
     * payments received).</p>
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * Merchant Name
     * 
     * <p>Maps from COBOL TRAN-MERCHANT-NAME field (PIC X(50)).</p>
     * <p>Business name of the merchant, maximum 50 characters. Provided by
     * payment network based on merchant registration.</p>
     * 
     * <p><strong>Examples:</strong></p>
     * <ul>
     *   <li>"AMAZON.COM"</li>
     *   <li>"WALMART STORE #1234"</li>
     *   <li>"SHELL OIL"</li>
     *   <li>"STARBUCKS #56789"</li>
     * </ul>
     * 
     * <p>Displayed in transaction history for cardholder recognition. May differ
     * from legal business name due to doing-business-as (DBA) names.</p>
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant City
     * 
     * <p>Maps from COBOL TRAN-MERCHANT-CITY field (PIC X(50)).</p>
     * <p>City where the merchant is located, maximum 50 characters.</p>
     * 
     * <p>Used for:</p>
     * <ul>
     *   <li>Transaction display showing where purchase occurred</li>
     *   <li>Fraud detection analyzing geographic patterns</li>
     *   <li>Travel notification and authorization rules</li>
     *   <li>Merchant location analytics</li>
     * </ul>
     * 
     * <p>May be abbreviated for long city names to fit 50-character limit.</p>
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP/Postal Code
     * 
     * <p>Maps from COBOL TRAN-MERCHANT-ZIP field (PIC X(10)).</p>
     * <p>Postal code of merchant location, maximum 10 characters to support
     * international formats.</p>
     * 
     * <p><strong>Format Support:</strong></p>
     * <ul>
     *   <li>US ZIP: "60601" or "60601-1234" (5-digit or ZIP+4)</li>
     *   <li>Canada: "M5H 2N2" (postal code format)</li>
     *   <li>UK: "SW1A 1AA" (postcode format)</li>
     *   <li>Other international formats up to 10 characters</li>
     * </ul>
     * 
     * <p>Used for geographic analysis, fraud detection, and merchant verification.</p>
     */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card - Foreign Key Relationship
     * 
     * <p>ManyToOne relationship to Card entity based on TRAN-CARD-NUM field
     * (PIC X(16)). Multiple transactions belong to a single card, establishing
     * transaction history for each card.</p>
     * <p>Replaces VSAM key-based relationship with PostgreSQL foreign key
     * constraint, ensuring referential integrity.</p>
     * 
     * <p>This relationship enables:</p>
     * <ul>
     *   <li>Transaction authorization against card status and account limits</li>
     *   <li>Transaction history retrieval by card number</li>
     *   <li>Pagination support (10 transactions per page per UI requirements)</li>
     *   <li>Card-level transaction analysis and reporting</li>
     *   <li>Account balance calculation by aggregating card transactions</li>
     * </ul>
     * 
     * <p><strong>Cardinality:</strong> Many transactions can belong to one card
     * (e.g., all purchases, cash advances, and payments using that card).</p>
     * 
     * <p><strong>Transaction Processing Flow:</strong></p>
     * <ol>
     *   <li>Transaction add (COTRN02C.cbl) validates card exists and is active</li>
     *   <li>Authorization check retrieves card and account for limit verification</li>
     *   <li>Transaction list (COTRN00C.cbl) queries by card number with pagination</li>
     *   <li>Daily processing (CBTRN02C.cbl) updates account balance via card link</li>
     * </ol>
     */
    @ManyToOne
    @JoinColumn(name = "card_number", referencedColumnName = "card_number", nullable = false)
    private Card card;

    /**
     * Transaction Origination Timestamp
     * 
     * <p>Maps from COBOL TRAN-ORIG-TS field (PIC X(26)).</p>
     * <p>Date and time when the transaction was initiated at the merchant terminal,
     * online system, or other transaction source. Stored with microsecond precision
     * using LocalDateTime.</p>
     * 
     * <p><strong>Conversion from COBOL:</strong></p>
     * <p>COBOL PIC X(26) format represents ISO 8601 timestamp with microseconds:
     * "YYYY-MM-DDTHH:MM:SS.ssssss". Java LocalDateTime provides equivalent precision
     * and functionality.</p>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Records exact time of merchant transaction initiation</li>
     *   <li>Used for transaction ordering in history displays</li>
     *   <li>Critical for dispute resolution and chargeback processing</li>
     *   <li>Enables temporal analysis of spending patterns</li>
     *   <li>Compared with processing timestamp for latency analysis</li>
     * </ul>
     * 
     * <p><strong>Date Range Queries:</strong></p>
     * <p>This field is indexed (see Flyway V7__create_indexes.sql) to support
     * efficient date range queries for:</p>
     * <ul>
     *   <li>Transaction history by date range (reporting requirement)</li>
     *   <li>Daily/monthly/yearly transaction summaries</li>
     *   <li>Real-time transaction monitoring dashboards</li>
     *   <li>Batch processing windows (4-hour processing requirement)</li>
     * </ul>
     * 
     * <p><strong>Timezone Considerations:</strong></p>
     * <p>LocalDateTime is timezone-agnostic. Original transaction timezone is
     * typically merchant local time. For cross-timezone analysis, UTC conversion
     * may be applied at application layer.</p>
     */
    @Column(name = "origination_timestamp")
    private LocalDateTime originationTimestamp;

    /**
     * Transaction Processing Timestamp
     * 
     * <p>Maps from COBOL TRAN-PROC-TS field (PIC X(26)).</p>
     * <p>Date and time when the transaction was processed and posted to the
     * account by the card management system. Stored with microsecond precision
     * using LocalDateTime.</p>
     * 
     * <p><strong>Processing Flow:</strong></p>
     * <ol>
     *   <li>Transaction originates at merchant (originationTimestamp set)</li>
     *   <li>Transaction enters card system for authorization</li>
     *   <li>Authorization approved/declined based on rules</li>
     *   <li>Approved transaction queued for batch posting</li>
     *   <li>Daily processing job (CBTRN02C.cbl) posts transaction (processingTimestamp set)</li>
     *   <li>Account balance updated to reflect transaction</li>
     * </ol>
     * 
     * <p><strong>Timestamp Difference Analysis:</strong></p>
     * <p>The difference between processingTimestamp and originationTimestamp
     * indicates:</p>
     * <ul>
     *   <li>Real-time transactions: Difference of seconds/minutes</li>
     *   <li>Batch-posted transactions: Difference of hours (within 4-hour window)</li>
     *   <li>Delayed transactions: Difference exceeding normal processing window
     *       (may indicate system issues or merchant batch submission delays)</li>
     * </ul>
     * 
     * <p><strong>Performance Monitoring:</strong></p>
     * <p>Processing latency (processingTimestamp - originationTimestamp) is tracked
     * for:</p>
     * <ul>
     *   <li>SLA compliance monitoring (200ms response time requirement)</li>
     *   <li>System performance analysis</li>
     *   <li>Bottleneck identification in transaction processing pipeline</li>
     *   <li>Batch job performance tuning (4-hour window requirement)</li>
     * </ul>
     * 
     * <p><strong>Audit and Compliance:</strong></p>
     * <p>Both timestamps are immutable after setting and form part of the
     * complete audit trail for regulatory compliance, dispute resolution,
     * and forensic analysis.</p>
     */
    @Column(name = "processing_timestamp")
    private LocalDateTime processingTimestamp;

    /**
     * Version - Optimistic Locking
     * 
     * <p>JPA version field for optimistic locking support, replicating VSAM
     * record locking behavior for concurrent access patterns in transaction
     * processing.</p>
     * <p>Automatically incremented by JPA on each update, preventing lost
     * updates when multiple processes attempt to modify the same transaction
     * simultaneously.</p>
     * 
     * <p><strong>Concurrent Update Scenarios:</strong></p>
     * <ul>
     *   <li>Simultaneous transaction corrections or adjustments</li>
     *   <li>Batch processing while online updates occur</li>
     *   <li>Multiple reporting systems reading transaction data</li>
     *   <li>Concurrent chargeback and refund processing</li>
     * </ul>
     * 
     * <p>If a concurrent modification is detected, JPA throws
     * OptimisticLockException. The application should:</p>
     * <ol>
     *   <li>Catch the exception</li>
     *   <li>Refresh the entity to get latest version</li>
     *   <li>Re-apply changes if still valid</li>
     *   <li>Notify user if manual intervention required</li>
     * </ol>
     * 
     * <p><strong>Performance Impact:</strong></p>
     * <p>Optimistic locking has minimal performance overhead compared to
     * pessimistic locking:</p>
     * <ul>
     *   <li>No database locks held during transaction processing</li>
     *   <li>Higher throughput for read-heavy workloads (transaction queries)</li>
     *   <li>Suitable for 10,000 TPS requirement with mostly read operations</li>
     *   <li>Version check adds minimal overhead to UPDATE statements</li>
     * </ul>
     */
    @Version
    @Column(name = "version")
    private Long version;
}
