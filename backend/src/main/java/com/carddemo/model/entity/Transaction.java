package com.carddemo.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * JPA entity representing credit card transaction data.
 * 
 * Converted from COBOL copybook: CVTRA05Y.cpy (TRAN-RECORD)
 * Original record length: 350 bytes
 * 
 * This entity stores transaction details including transaction type, category, amount,
 * merchant information, timestamps, and card association. Core transactional entity
 * requiring exact numeric precision and timestamp handling for financial accuracy.
 * 
 * Conversion notes:
 * - COBOL PIC X(16) TRAN-ID converted to String primary key with length 16
 * - COBOL PIC X(16) TRAN-CARD-NUM converted to String foreign key with @ManyToOne relationship to Card entity
 * - COBOL PIC X(02) TRAN-TYPE-CD converted to String with length 2 for transaction type code
 * - COBOL PIC 9(04) TRAN-CAT-CD converted to Integer for transaction category code
 * - COBOL PIC X(10) TRAN-SOURCE converted to String with length 10 for transaction source
 * - COBOL PIC X(100) TRAN-DESC converted to String with length 100 for transaction description
 * - COBOL PIC S9(09)V99 COMP-3 TRAN-AMT converted to BigDecimal with precision 11, scale 2
 *   to preserve COBOL packed decimal precision per Section 0.7.2 for bit-identical financial calculations
 * - COBOL PIC 9(09) TRAN-MERCHANT-ID converted to String with length 9 for leading zero preservation
 * - COBOL PIC X(50) merchant name/city fields converted to String columns with length 50
 * - COBOL PIC X(10) TRAN-MERCHANT-ZIP converted to String with length 10
 * - COBOL PIC X(26) TRAN-ORIG-TS converted to Timestamp for transaction origination timestamp
 * - COBOL PIC X(26) TRAN-PROC-TS converted to Timestamp with default CURRENT_TIMESTAMP for processing timestamp
 * - COBOL FILLER field (20 bytes) removed as not used in business logic
 * - Added audit field createdAt for tracking record lifecycle
 * - Added version field for JPA optimistic locking (replicates VSAM RBA locking semantics)
 * 
 * Referenced by:
 * - TransactionController (COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl) for transaction listing, detail view, and posting
 * - TransactionService for business logic processing
 * - TransactionRepository for data access operations
 * - Batch programs CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl for daily transaction processing
 * 
 * Database indexes (per Section 0.3.4):
 * - Primary key index on trans_id (automatic)
 * - Index on trans_card_num (idx_transaction_card) for card-based queries
 * - Index on trans_orig_ts (idx_transaction_date) for date range filtering
 * - Index on trans_type_cd (idx_transaction_type) for transaction type filtering
 * 
 * @see Card
 * @see TransactionController
 * @see TransactionService
 * @see TransactionRepository
 */
@Entity
@Table(name = "transaction", indexes = {
    @Index(name = "idx_transaction_card", columnList = "trans_card_num"),
    @Index(name = "idx_transaction_date", columnList = "trans_orig_ts"),
    @Index(name = "idx_transaction_type", columnList = "trans_type_cd")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    /**
     * Transaction identifier (primary key).
     * 
     * Converted from: COBOL PIC X(16) TRAN-ID
     * Maximum length: 16 characters
     * Format: Typically alphanumeric transaction reference number
     * 
     * This is the primary key for transaction records, equivalent to the VSAM KSDS
     * primary key in the mainframe TRANSACT dataset.
     * 
     * Uniquely identifies each transaction in the system across all cards and accounts.
     * Used for transaction lookup, dispute resolution, and reconciliation with payment networks.
     */
    @Id
    @Column(name = "trans_id", nullable = false, length = 16)
    private String transId;

    /**
     * Card number associated with this transaction (foreign key column value).
     * 
     * Converted from: COBOL PIC X(16) TRAN-CARD-NUM
     * Maximum length: 16 characters
     * 
     * This field stores the actual card number value used as the foreign key to link
     * the transaction to a specific card. The @ManyToOne relationship field 'card'
     * provides object navigation capabilities.
     * 
     * SECURITY NOTE: This field contains sensitive PII (Payment Card Number).
     * - Must be masked in logs and API responses (show only last 4 digits)
     * - Must be encrypted at rest per PCI-DSS requirements
     * - Access must be restricted to authorized personnel only
     */
    @Column(name = "trans_card_num", nullable = false, length = 16)
    private String transCardNum;

    /**
     * Card entity relationship.
     * 
     * Many-to-One relationship: Multiple transactions belong to one card.
     * 
     * Enables JPA to automatically fetch Card details when accessing Transaction.card
     * and maintains referential integrity through foreign key constraint.
     * 
     * Join column trans_card_num references card.card_num primary key per
     * database schema defined in Section 0.3.4.
     * 
     * Used by transaction processing logic to validate card status, check credit limits,
     * and access account information for authorization decisions.
     */
    @ManyToOne
    @JoinColumn(name = "trans_card_num", referencedColumnName = "card_num", insertable = false, updatable = false)
    private Card card;

    /**
     * Transaction type code.
     * 
     * Converted from: COBOL PIC X(02) TRAN-TYPE-CD
     * Maximum length: 2 characters
     * 
     * Identifies the type of transaction. Valid values referenced from TRANTYPE table
     * (CVTRA03Y.cpy copybook):
     * - '01' = Purchase
     * - '02' = Cash Advance
     * - '03' = Balance Transfer
     * - '04' = Payment
     * - '05' = Fee
     * - '06' = Interest Charge
     * - '07' = Credit Adjustment
     * - '08' = Debit Adjustment
     * 
     * Used in transaction processing logic to determine posting rules, fee calculations,
     * and interest application per business requirements from COBOL programs
     * COTRN02C.cbl (transaction posting) and CBTRN02C.cbl (daily batch posting).
     */
    @Column(name = "trans_type_cd", nullable = false, length = 2)
    private String transTypeCd;

    /**
     * Transaction category code.
     * 
     * Converted from: COBOL PIC 9(04) TRAN-CAT-CD
     * Maximum value: 9999 (4 digits)
     * 
     * Identifies the merchant category or transaction category. Valid values referenced
     * from TRANCATG table (CVTRA04Y.cpy copybook).
     * 
     * Examples:
     * - 5411 = Grocery Stores
     * - 5812 = Restaurants
     * - 5541 = Gas Stations
     * - 6011 = ATM Cash Withdrawal
     * 
     * Used for transaction categorization in statements, spending analysis reports,
     * and category-based balance tracking (TCATBAL table updated by CBTRN03C.cbl).
     */
    @Column(name = "trans_cat_cd", nullable = false)
    private Integer transCatCd;

    /**
     * Transaction source indicator.
     * 
     * Converted from: COBOL PIC X(10) TRAN-SOURCE
     * Maximum length: 10 characters
     * 
     * Identifies the source or channel of the transaction:
     * - 'POS' = Point of Sale terminal
     * - 'ATM' = ATM withdrawal
     * - 'ONLINE' = Online purchase
     * - 'PHONE' = Phone order
     * - 'MAIL' = Mail order
     * - 'MOBILE' = Mobile app
     * 
     * Used for fraud detection, channel-specific reporting, and transaction routing logic.
     */
    @Column(name = "trans_source", length = 10)
    private String transSource;

    /**
     * Transaction description.
     * 
     * Converted from: COBOL PIC X(100) TRAN-DESC
     * Maximum length: 100 characters
     * 
     * Free-form text description of the transaction, typically provided by the merchant
     * or generated by the transaction processing system. Displayed on customer statements
     * and online transaction history.
     * 
     * Examples:
     * - "WALMART SUPERCENTER #1234"
     * - "STARBUCKS COFFEE #5678"
     * - "SHELL OIL #9012"
     * - "ATM WITHDRAWAL"
     * 
     * May be truncated or formatted for statement presentation by CBSTM03A.cbl
     * (statement generation program).
     */
    @Column(name = "trans_desc", length = 100)
    private String transDesc;

    /**
     * Transaction amount.
     * 
     * Converted from: COBOL PIC S9(09)V99 COMP-3 TRAN-AMT
     * Precision: 11 total digits, 2 decimal places
     * Range: -999,999,999.99 to +999,999,999.99
     * 
     * Monetary amount of the transaction in account currency (typically USD).
     * Positive values represent charges/debits to the account (purchases, fees, interest).
     * Negative values represent credits to the account (payments, refunds, adjustments).
     * 
     * CRITICAL: This field uses BigDecimal with scale 2 and precision 11 to preserve
     * exact COBOL COMP-3 packed decimal precision per Section 0.7.2 requirement for
     * bit-identical financial calculations. Any rounding or precision loss would violate
     * financial accuracy requirements and cause reconciliation failures with payment networks.
     * 
     * Used in balance calculations, credit limit checks, and minimum payment computations.
     * Updated by transaction posting logic in COTRN02C.cbl and CBTRN02C.cbl.
     */
    @Column(name = "trans_amt", nullable = false, precision = 11, scale = 2)
    private BigDecimal transAmt;

    /**
     * Merchant identifier.
     * 
     * Converted from: COBOL PIC 9(09) TRAN-MERCHANT-ID
     * Maximum length: 9 characters (stored as String to preserve leading zeros)
     * Format: 9-digit numeric merchant ID assigned by payment network
     * 
     * Uniquely identifies the merchant accepting the transaction. Assigned by
     * acquiring bank or payment network (e.g., Visa, Mastercard).
     * 
     * Stored as String rather than Integer to preserve leading zeros which are
     * significant in merchant identification and routing logic.
     * 
     * Examples:
     * - "000123456" (leading zeros preserved)
     * - "999888777"
     * 
     * Used for merchant-specific reporting, chargeback processing, and fraud detection.
     */
    @Column(name = "trans_merchant_id", length = 9)
    private String transMerchantId;

    /**
     * Merchant name.
     * 
     * Converted from: COBOL PIC X(50) TRAN-MERCHANT-NAME
     * Maximum length: 50 characters
     * 
     * Name of the merchant where the transaction occurred. Typically provided by
     * the merchant's acquiring bank and may include location identifier.
     * 
     * Examples:
     * - "WALMART SUPERCENTER"
     * - "STARBUCKS COFFEE"
     * - "SHELL OIL"
     * 
     * Displayed on customer statements and online transaction history for
     * transaction identification.
     */
    @Column(name = "trans_merchant_name", length = 50)
    private String transMerchantName;

    /**
     * Merchant city.
     * 
     * Converted from: COBOL PIC X(50) TRAN-MERCHANT-CITY
     * Maximum length: 50 characters
     * 
     * City where the merchant is located. Used for transaction display on statements
     * and online banking interfaces to help customers identify transactions.
     * 
     * Examples:
     * - "NEW YORK"
     * - "LOS ANGELES"
     * - "CHICAGO"
     * 
     * Combined with merchant name provides full location context for transaction.
     */
    @Column(name = "trans_merchant_city", length = 50)
    private String transMerchantCity;

    /**
     * Merchant ZIP code.
     * 
     * Converted from: COBOL PIC X(10) TRAN-MERCHANT-ZIP
     * Maximum length: 10 characters
     * Format: 5-digit ZIP (e.g., "12345") or ZIP+4 (e.g., "12345-6789")
     * 
     * Postal code of the merchant location. Used for geographic analysis,
     * fraud detection (comparing to cardholder's ZIP), and reporting.
     * 
     * Stored as String to accommodate different postal code formats
     * (5-digit, ZIP+4, international postal codes).
     */
    @Column(name = "trans_merchant_zip", length = 10)
    private String transMerchantZip;

    /**
     * Transaction origination timestamp.
     * 
     * Converted from: COBOL PIC X(26) TRAN-ORIG-TS
     * Format: TIMESTAMP in database (YYYY-MM-DD HH:MM:SS.ssssss)
     * 
     * Date and time when the transaction was initiated by the cardholder at the
     * merchant location or online. This is the actual transaction date/time from
     * the merchant's perspective, which may differ from processing timestamp due to
     * batch settlement delays.
     * 
     * Used for:
     * - Statement date range filtering (COTRN00C.cbl transaction list by date range)
     * - Grace period calculations for interest charges
     * - Dispute resolution timeframes
     * - Billing cycle cutoff determination
     * 
     * This field is required and must always be populated with the original
     * transaction date/time per database schema Section 0.3.4.
     */
    @Column(name = "trans_orig_ts", nullable = false)
    private Timestamp transOrigTs;

    /**
     * Transaction processing timestamp.
     * 
     * Converted from: COBOL PIC X(26) TRAN-PROC-TS
     * Format: TIMESTAMP in database (YYYY-MM-DD HH:MM:SS.ssssss)
     * Default: CURRENT_TIMESTAMP (automatically set on insert)
     * 
     * Date and time when the transaction was processed and posted to the account by
     * the card issuer's system. This timestamp reflects when the transaction was
     * received from the payment network and posted to account balances.
     * 
     * May differ from trans_orig_ts due to:
     * - Batch settlement processing delays (up to 2-3 days for some merchants)
     * - Time zone differences between merchant and processing system
     * - Weekend/holiday processing delays
     * 
     * Defaults to current timestamp when record is inserted, representing the
     * moment the transaction is posted to the PostgreSQL database by batch
     * program CBTRN02C.cbl (daily transaction posting job).
     */
    @Column(name = "trans_proc_ts", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp transProcTs;

    /**
     * Record creation timestamp.
     * 
     * Added field (not in original COBOL copybook CVTRA05Y.cpy).
     * 
     * Automatically set to current timestamp when record is first inserted into
     * the database. Used for audit tracking, transaction reporting, and data lineage.
     * 
     * Tracks when transaction record was created in PostgreSQL, which typically
     * corresponds to trans_proc_ts but provides independent audit trail.
     */
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private Timestamp createdAt;

    /**
     * Version number for optimistic locking.
     * 
     * Added field (not in original COBOL copybook CVTRA05Y.cpy).
     * 
     * JPA version field automatically incremented on each update to prevent
     * concurrent update conflicts. Replicates COBOL VSAM RBA (Relative Byte Address)
     * optimistic locking semantics from mainframe CICS transaction processing.
     * 
     * Transactions are typically immutable after posting (no updates allowed per
     * financial audit requirements), but this field supports exceptional cases like:
     * - Transaction dispute status updates
     * - Error correction adjustments
     * - Fraud investigation flag updates
     * 
     * Critical for maintaining data integrity in multi-user transaction management
     * operations where multiple channels (online banking, call center, mobile app)
     * may attempt to update the same transaction record simultaneously.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    private Integer version;

    /**
     * JPA lifecycle callback: Automatically set timestamps before persisting new entity.
     * 
     * This method is invoked automatically by JPA before INSERT operations.
     * Sets createdAt to current timestamp and initializes version to 0 for new records.
     * 
     * If transProcTs is not explicitly set by the application code (e.g., when posting
     * a transaction from COTRN02C.cbl logic), it will be set by the database default
     * CURRENT_TIMESTAMP constraint.
     * 
     * Replaces manual timestamp setting that would be required in COBOL programs
     * (e.g., COTRN02C.cbl) where developers explicitly set CURRENT-DATE or
     * CURRENT-TIMESTAMP fields using ACCEPT statements.
     * 
     * Example COBOL equivalent:
     *   ACCEPT WS-CURRENT-TIMESTAMP FROM TIME
     *   MOVE WS-CURRENT-TIMESTAMP TO TRAN-CREATE-TS
     *   EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD) RIDFLD(TRAN-ID) END-EXEC
     */
    @PrePersist
    protected void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        this.createdAt = now;
        if (this.transProcTs == null) {
            this.transProcTs = now;
        }
        if (this.version == null) {
            this.version = 0;
        }
    }
}
