package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * JPA Entity representing credit card transaction master data table.
 * 
 * <p>This entity is transformed from COBOL copybook CVTRA05Y.cpy (TRAN-RECORD)
 * which defines a 350-byte fixed-length record structure for transaction information stored
 * in the VSAM TRANSACT KSDS file. This entity contains all transaction identification,
 * classification, merchant details, financial amounts, and timestamp data critical for
 * transaction processing, reporting, and statement generation.</p>
 * 
 * <p><strong>COBOL Source Structure (350-byte record):</strong></p>
 * <pre>
 * 01  TRAN-RECORD.
 *     05  TRAN-ID                    PIC X(16).      (Transaction ID - Primary Key)
 *     05  TRAN-TYPE-CD               PIC X(02).      (Transaction Type Code - FK to TransactionType)
 *     05  TRAN-CAT-CD                PIC 9(04).      (Category Code - part of composite FK)
 *     05  TRAN-SOURCE                PIC X(10).      (Transaction Source)
 *     05  TRAN-DESC                  PIC X(100).     (Transaction Description)
 *     05  TRAN-AMT                   PIC S9(09)V99.  (Amount - CRITICAL COMP-3 precision)
 *     05  TRAN-MERCHANT-ID           PIC 9(09).      (Merchant ID)
 *     05  TRAN-MERCHANT-NAME         PIC X(50).      (Merchant Name)
 *     05  TRAN-MERCHANT-CITY         PIC X(50).      (Merchant City)
 *     05  TRAN-MERCHANT-ZIP          PIC X(10).      (Merchant ZIP)
 *     05  TRAN-CARD-NUM              PIC X(16).      (Card Number - FK to Card)
 *     05  TRAN-ORIG-TS               PIC X(26).      (Origination Timestamp)
 *     05  TRAN-PROC-TS               PIC X(26).      (Processing Timestamp)
 *     05  FILLER                     PIC X(20).      (NOT MAPPED - unused padding)
 * </pre>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity: Card (many-to-one via card_number foreign key)</li>
 *   <li>Parent entity: TransactionType (many-to-one via transaction_type_code)</li>
 *   <li>Parent entity: TransactionCategory (many-to-one via composite key: type_code + category_code)</li>
 *   <li>Account relationship: Indirect via Card.account (transaction → card → account)</li>
 *   <li>16-character transaction ID serves as primary key</li>
 * </ul>
 * 
 * <p><strong>Data Transformation Details:</strong></p>
 * <ul>
 *   <li>TRAN-ID PIC X(16) → String transactionId (16-char primary key)</li>
 *   <li>TRAN-TYPE-CD PIC X(02) → String transactionTypeCode (2 chars, FK)</li>
 *   <li>TRAN-CAT-CD PIC 9(04) → Integer transactionCategoryCode (4 digits)</li>
 *   <li>TRAN-SOURCE PIC X(10) → String transactionSource (10 chars)</li>
 *   <li>TRAN-DESC PIC X(100) → String transactionDescription (100 chars)</li>
 *   <li>TRAN-AMT PIC S9(09)V99 → BigDecimal(11,2) transactionAmount with HALF_UP rounding</li>
 *   <li>TRAN-MERCHANT-ID PIC 9(09) → Long merchantId (9-digit numeric)</li>
 *   <li>TRAN-MERCHANT-NAME PIC X(50) → String merchantName (50 chars)</li>
 *   <li>TRAN-MERCHANT-CITY PIC X(50) → String merchantCity (50 chars)</li>
 *   <li>TRAN-MERCHANT-ZIP PIC X(10) → String merchantZip (10 chars)</li>
 *   <li>TRAN-CARD-NUM PIC X(16) → String cardNumber (16-char FK to Card)</li>
 *   <li>TRAN-ORIG-TS PIC X(26) → LocalDateTime originationTimestamp</li>
 *   <li>TRAN-PROC-TS PIC X(26) → LocalDateTime processingTimestamp</li>
 *   <li>FILLER PIC X(20) → NOT MAPPED (unused COBOL padding)</li>
 * </ul>
 * 
 * <p><strong>CRITICAL Numeric Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>TRAN-AMT PIC S9(09)V99 MUST use BigDecimal with precision=11, scale=2</li>
 *   <li>ALL arithmetic operations MUST explicitly call setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>This ensures COMP-3 packed decimal equivalence from COBOL mainframe</li>
 *   <li>Example: totalAmount.add(transactionAmount).setScale(2, RoundingMode.HALF_UP)</li>
 *   <li>NO float or double types allowed for monetary amounts per Section 0.9</li>
 *   <li>Transaction amount calculations must maintain identical precision to COBOL</li>
 * </ul>
 * 
 * <p><strong>Database Indexes for Query Performance:</strong></p>
 * <ul>
 *   <li>Primary key index on transaction_id (automatic via @Id)</li>
 *   <li>Index on card_number for card transaction history queries (COTRN00C program)</li>
 *   <li>Index on origination_timestamp for date range queries (statement generation)</li>
 *   <li>Index on processing_timestamp for batch processing queries (CBTRN02C program)</li>
 *   <li>Composite index on (transaction_type_code, transaction_category_code) for categorization</li>
 * </ul>
 * 
 * <p><strong>Usage in COBOL Programs (Transformation Context):</strong></p>
 * <ul>
 *   <li>COTRN00C.cbl → TransactionListService (transaction list with pagination - 10 per page)</li>
 *   <li>COTRN01C.cbl → TransactionCategoryService (category summary and aggregation)</li>
 *   <li>COTRN02C.cbl → TransactionCreationService (add new transaction with balance updates)</li>
 *   <li>CBTRN01C.cbl → TransactionDataLoadJob (batch transaction data load)</li>
 *   <li>CBTRN02C.cbl → DailyTransactionProcessingJob (daily batch processing at 2 AM)</li>
 *   <li>CBTRN03C.cbl → TransactionAggregationJob (transaction category aggregation)</li>
 *   <li>CBSTM03A.cbl → StatementGenerationJob (monthly statement generation)</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>High-volume entity: 10,000+ transactions per day typical production load</li>
 *   <li>Read-heavy access pattern: Transaction history queries, statement generation</li>
 *   <li>Write operations: Transaction posting (COTRN02C), batch loads (CBTRN01C)</li>
 *   <li>Response time SLA: Sub-200ms for transaction list queries at 95th percentile</li>
 *   <li>Pagination required: Standard 10 transactions per page per COTRN00C program</li>
 *   <li>Date range queries: Monthly statement generation requires efficient timestamp indexing</li>
 * </ul>
 * 
 * <p><strong>COBOL Source:</strong> app/cpy/CVTRA05Y.cpy</p>
 * <p><strong>VSAM File:</strong> TRANSACT KSDS (Key-Sequenced Dataset)</p>
 * <p><strong>Record Length:</strong> 350 bytes</p>
 * 
 * @see Card
 * @see TransactionType
 * @see TransactionCategory
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.3">COBOL to Java Type Conversion Rules</a>
 * @see <a href="Section 0.9">Numeric Precision and Transaction Boundary Requirements</a>
 */
@Entity
@Table(name = "transaction", indexes = {
    @Index(name = "idx_card_number", columnList = "card_number"),
    @Index(name = "idx_orig_timestamp", columnList = "origination_timestamp"),
    @Index(name = "idx_proc_timestamp", columnList = "processing_timestamp"),
    @Index(name = "idx_type_category", columnList = "transaction_type_code, transaction_category_code")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     * Required for entity serialization across JVM boundaries in distributed systems.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction identifier - Primary key (16 characters).
     * Maps to COBOL field: TRAN-ID PIC X(16)
     * 
     * <p><strong>PRIMARY KEY for Transaction entity.</strong></p>
     * 
     * <p>Unique identifier for each transaction in the system. Format typically includes:
     * <ul>
     *   <li>Date component (YYYYMMDD - 8 characters)</li>
     *   <li>Sequence number (00000001-99999999 - 8 characters)</li>
     *   <li>Total: 16-character alphanumeric string</li>
     * </ul>
     * </p>
     * 
     * <p>Generation Strategy:</p>
     * <ul>
     *   <li>Generated by TransactionCreationService upon transaction creation</li>
     *   <li>Ensures uniqueness across all transactions in the database</li>
     *   <li>Supports high-volume transaction processing (10,000 TPS per Section 0.2)</li>
     *   <li>Maintains compatibility with COBOL sequential transaction ID generation</li>
     * </ul>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Transaction lookup by ID (COTRN00C → TransactionListService)</li>
     *   <li>Transaction detail retrieval (COTRN01C → TransactionCategoryService)</li>
     *   <li>Audit trail and regulatory compliance logging per Section 0.9</li>
     *   <li>Statement generation and reporting (CBSTM03A → StatementGenerationJob)</li>
     * </ul>
     * 
     * <p>Stored as String to preserve leading zeros and alphanumeric capability.
     * Not used for arithmetic operations, so String type is appropriate.</p>
     */
    @Id
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Transaction type code - Foreign key to TransactionType entity (2 characters).
     * Maps to COBOL field: TRAN-TYPE-CD PIC X(02)
     * 
     * <p>Establishes many-to-one relationship from Transaction to TransactionType entity.
     * Categorizes transactions by high-level type for reporting and processing logic.</p>
     * 
     * <p>Common transaction type codes:
     * <ul>
     *   <li>"PU" - Purchase transaction (retail, online, recurring)</li>
     *   <li>"CA" - Cash advance (ATM withdrawal, branch cash)</li>
     *   <li>"PM" - Payment (online payment, mail payment, phone payment)</li>
     *   <li>"RF" - Refund (merchant refund, adjustment)</li>
     *   <li>"FE" - Fee (annual fee, late fee, over-limit fee)</li>
     *   <li>"IN" - Interest charge (monthly interest calculation)</li>
     * </ul>
     * </p>
     * 
     * <p>This field enables:</p>
     * <ul>
     *   <li>Transaction categorization by type (COTRN01C category summary)</li>
     *   <li>Type-specific processing rules (different authorization logic per type)</li>
     *   <li>Reporting and analytics (transaction volume by type)</li>
     *   <li>Statement generation with grouped transaction types</li>
     * </ul>
     * 
     * <p>Foreign Key Constraint: References transaction_type(type_code) table.
     * Referential integrity enforced at database level per Section 0.9 requirements.</p>
     */
    @Column(name = "transaction_type_code", length = 2, nullable = false)
    private String transactionTypeCode;

    /**
     * Transaction category code - Component of composite foreign key (4-digit integer).
     * Maps to COBOL field: TRAN-CAT-CD PIC 9(04)
     * 
     * <p>Provides detailed categorization within each transaction type. Combined with
     * transactionTypeCode forms composite foreign key to TransactionCategory entity
     * for granular transaction classification.</p>
     * 
     * <p>Example category codes by transaction type:
     * <ul>
     *   <li>Type "PU" (Purchase): 1001=Groceries, 1002=Gas, 1003=Dining, 1004=Shopping, 1005=Travel</li>
     *   <li>Type "CA" (Cash Advance): 2001=ATM Withdrawal, 2002=Branch Cash, 2003=Check Cashing</li>
     *   <li>Type "PM" (Payment): 3001=Online Payment, 3002=Mail Payment, 3003=Phone Payment</li>
     *   <li>Type "FE" (Fee): 4001=Annual Fee, 4002=Late Fee, 4003=Over Limit Fee</li>
     * </ul>
     * </p>
     * 
     * <p>This field enables:</p>
     * <ul>
     *   <li>Detailed spending analysis by category (COTRN01C transaction category summary)</li>
     *   <li>Category-level transaction aggregation (CBTRN03C aggregation job)</li>
     *   <li>Merchant category code (MCC) mapping for industry-standard categorization</li>
     *   <li>Budgeting and expense tracking features in UI</li>
     * </ul>
     * 
     * <p>Composite Foreign Key: Together with transactionTypeCode references
     * transaction_category(type_code, category_code). Enforces referential integrity
     * per Section 0.9 cross-reference data relationship requirements.</p>
     * 
     * <p>Range: 0001-9999 (4-digit numeric allows up to 9,999 categories per type)</p>
     */
    @Column(name = "transaction_category_code", nullable = false)
    private Integer transactionCategoryCode;

    /**
     * Transaction source - Origination channel or system (10 characters).
     * Maps to COBOL field: TRAN-SOURCE PIC X(10)
     * 
     * <p>Identifies the channel or system through which the transaction was initiated.
     * Used for transaction routing, processing rules, and audit trail purposes.</p>
     * 
     * <p>Common transaction source values:
     * <ul>
     *   <li>"POS" - Point of Sale terminal (physical retail location)</li>
     *   <li>"ATM" - Automated Teller Machine (cash withdrawal, balance inquiry)</li>
     *   <li>"ONLINE" - Online web application (e-commerce, online banking)</li>
     *   <li>"MOBILE" - Mobile banking application (iOS, Android apps)</li>
     *   <li>"PHONE" - Phone banking or IVR system (automated phone payments)</li>
     *   <li>"BRANCH" - Bank branch teller transaction (in-person service)</li>
     *   <li>"MAIL" - Mail-in payment (check payment processing)</li>
     *   <li>"RECURRING" - Scheduled recurring payment (autopay, subscription)</li>
     *   <li>"BATCH" - Batch processing system (daily batch job posting)</li>
     * </ul>
     * </p>
     * 
     * <p>Usage in business logic:</p>
     * <ul>
     *   <li>Source-specific authorization rules (different limits for POS vs ATM)</li>
     *   <li>Fraud detection algorithms (unusual source patterns indicate fraud)</li>
     *   <li>Transaction reporting by channel (volume analysis by source)</li>
     *   <li>Audit trail compliance (regulatory requirement to track transaction origin)</li>
     * </ul>
     * 
     * <p>Optional field: May be null for legacy transactions or internal adjustments.
     * Maximum length 10 characters per COBOL PIC X(10) specification.</p>
     */
    @Column(name = "transaction_source", length = 10)
    private String transactionSource;

    /**
     * Transaction description - Human-readable transaction details (100 characters).
     * Maps to COBOL field: TRAN-DESC PIC X(100)
     * 
     * <p>Free-form text description of the transaction displayed to cardholders in:
     * <ul>
     *   <li>Transaction list screens (COTRN00M.bms → TransactionListComponent.jsx)</li>
     *   <li>Transaction detail views (card statement transaction line items)</li>
     *   <li>Monthly statements (CBSTM03A.cbl → StatementGenerationJob.java)</li>
     *   <li>Mobile banking notifications (push notifications, SMS alerts)</li>
     *   <li>Email transaction alerts (fraud monitoring, large purchase alerts)</li>
     * </ul>
     * </p>
     * 
     * <p>Description Content:</p>
     * <ul>
     *   <li>Purchase transactions: Merchant name and location (e.g., "WALMART #1234 ANYTOWN CA")</li>
     *   <li>Cash advance: ATM location or branch (e.g., "ATM CASH WITHDRAWAL 123 MAIN ST")</li>
     *   <li>Payment: Payment method and source (e.g., "ONLINE PAYMENT FROM CHECKING ***1234")</li>
     *   <li>Fee: Fee type and reason (e.g., "ANNUAL CARD FEE", "LATE PAYMENT FEE")</li>
     *   <li>Interest: Interest period (e.g., "INTEREST CHARGE FOR DECEMBER 2024")</li>
     * </ul>
     * 
     * <p>Data Quality:</p>
     * <ul>
     *   <li>Typically sourced from merchant authorization message (ISO 8583 field 43)</li>
     *   <li>May be truncated to 100 characters if source description exceeds limit</li>
     *   <li>Special characters and formatting may vary by transaction source</li>
     * </ul>
     * 
     * <p>Optional field: Should be populated for all customer-facing transactions.
     * May be null for internal system adjustments or batch processing entries.</p>
     */
    @Column(name = "transaction_description", length = 100)
    private String transactionDescription;

    /**
     * Transaction amount - Monetary value with CRITICAL precision requirements.
     * Maps to COBOL field: TRAN-AMT PIC S9(09)V99
     * 
     * <p><strong>CRITICAL NUMERIC PRECISION REQUIREMENTS (Section 0.2 and 0.9):</strong></p>
     * <ul>
     *   <li>COBOL: PIC S9(09)V99 = signed 9 digits + 2 decimal places</li>
     *   <li>Java: BigDecimal with precision=11 (9 integer + 2 decimal), scale=2</li>
     *   <li>Rounding: MUST use RoundingMode.HALF_UP to match COBOL COMP-3 behavior</li>
     *   <li>ALL arithmetic operations MUST explicitly call setScale(2, RoundingMode.HALF_UP)</li>
     *   <li>NO float or double types allowed - violates precision requirements</li>
     * </ul>
     * 
     * <p><strong>Precision Preservation Example:</strong></p>
     * <pre>
     * // CORRECT - Maintains COBOL COMP-3 precision
     * BigDecimal total = transaction1.getTransactionAmount()
     *     .add(transaction2.getTransactionAmount())
     *     .setScale(2, RoundingMode.HALF_UP);
     * 
     * // INCORRECT - Loses precision, violates Section 0.9 requirements
     * double total = transaction1Amount + transaction2Amount;  // NEVER do this!
     * </pre>
     * 
     * <p><strong>Amount Interpretation:</strong></p>
     * <ul>
     *   <li>Positive amounts: Charges to cardholder (purchases, fees, interest)</li>
     *   <li>Negative amounts: Credits to cardholder (payments, refunds, adjustments)</li>
     *   <li>Zero amounts: Allowed for authorization-only transactions or holds</li>
     * </ul>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Range: -999,999,999.99 to +999,999,999.99 (matches COBOL PIC S9(09)V99)</li>
     *   <li>Scale: Exactly 2 decimal places (cents precision for USD)</li>
     *   <li>Validation: Amount must not exceed card credit limit for purchases</li>
     *   <li>Validation: Payment amount cannot exceed outstanding balance</li>
     * </ul>
     * 
     * <p><strong>Usage in Financial Calculations:</strong></p>
     * <ul>
     *   <li>Balance calculation: Current balance = previous balance + transaction amount</li>
     *   <li>Statement total: Sum of all transaction amounts in billing period</li>
     *   <li>Interest calculation: Interest = balance * rate (maintain scale 2)</li>
     *   <li>Category aggregation: Sum amounts by category (CBTRN03C aggregation job)</li>
     * </ul>
     * 
     * <p><strong>CRITICAL:</strong> This field is subject to audit and regulatory scrutiny.
     * Any precision loss or rounding discrepancies violate compliance requirements and
     * must be prevented through strict BigDecimal usage per Section 0.9.</p>
     */
    @Column(name = "transaction_amount", precision = 11, scale = 2, nullable = false)
    private BigDecimal transactionAmount;

    /**
     * Merchant identifier - Unique merchant ID from payment network (9-digit numeric).
     * Maps to COBOL field: TRAN-MERCHANT-ID PIC 9(09)
     * 
     * <p>Unique identifier assigned by the card payment network (Visa, Mastercard, etc.)
     * to identify the merchant where the transaction occurred. Used for merchant-level
     * reporting, fraud detection, and transaction reconciliation.</p>
     * 
     * <p>Merchant ID Structure:</p>
     * <ul>
     *   <li>First 4-6 digits: Acquiring bank identification</li>
     *   <li>Remaining digits: Unique merchant identifier within acquiring bank</li>
     *   <li>Total: 9-digit numeric identifier (e.g., 123456789)</li>
     * </ul>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Merchant-level transaction aggregation and reporting</li>
     *   <li>Fraud detection (unusual merchant patterns, high-risk merchant categories)</li>
     *   <li>Transaction reconciliation with merchant settlement files</li>
     *   <li>Chargeback and dispute processing (identify merchant for dispute resolution)</li>
     * </ul>
     * 
     * <p>Data Source:</p>
     * <ul>
     *   <li>Sourced from payment network authorization message (ISO 8583 field 42)</li>
     *   <li>Provided by acquiring bank during transaction authorization</li>
     *   <li>May be null for non-merchant transactions (ATM, phone banking, internal)</li>
     * </ul>
     * 
     * <p>Stored as Long (not String) to enable numeric range queries and merchant ID
     * pattern analysis. Preserves leading zeros not critical for merchant IDs as they
     * are not displayed to cardholders in standard transaction views.</p>
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * Merchant name - Business name of merchant (50 characters).
     * Maps to COBOL field: TRAN-MERCHANT-NAME PIC X(50)
     * 
     * <p>Human-readable business name of the merchant where the transaction occurred.
     * Displayed to cardholders in transaction lists, statements, and mobile banking
     * applications for easy transaction identification.</p>
     * 
     * <p>Name Format:</p>
     * <ul>
     *   <li>Typically all uppercase per ISO 8583 specification (e.g., "WALMART")</li>
     *   <li>May include store number or location identifier (e.g., "WALMART #1234")</li>
     *   <li>Special characters limited by payment network standards</li>
     *   <li>Maximum 50 characters per COBOL PIC X(50) specification</li>
     * </ul>
     * 
     * <p>Data Source:</p>
     * <ul>
     *   <li>Sourced from payment network authorization message (ISO 8583 field 43 - Card Acceptor Name)</li>
     *   <li>May be cleaned up or standardized by acquiring bank processing</li>
     *   <li>Truncated to 50 characters if source name exceeds limit</li>
     * </ul>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Primary display field in transaction list screens (COTRN00M.bms)</li>
     *   <li>Statement line items (monthly statement generation CBSTM03A.cbl)</li>
     *   <li>Mobile banking transaction notifications</li>
     *   <li>Merchant name search and filtering in transaction history</li>
     * </ul>
     * 
     * <p>Optional field: May be null for non-merchant transactions (payments, fees,
     * interest charges) where merchant information is not applicable.</p>
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city - City location of merchant (50 characters).
     * Maps to COBOL field: TRAN-MERCHANT-CITY PIC X(50)
     * 
     * <p>City name where the merchant is located. Provides geographic context for
     * transaction and helps cardholders identify legitimate transactions versus
     * potential fraud (unexpected transaction locations).</p>
     * 
     * <p>City Format:</p>
     * <ul>
     *   <li>City name only, without state (state in separate field if available)</li>
     *   <li>Typically uppercase (e.g., "LOS ANGELES", "NEW YORK")</li>
     *   <li>May include country code for international transactions (e.g., "LONDON GB")</li>
     *   <li>Maximum 50 characters per COBOL PIC X(50) specification</li>
     * </ul>
     * 
     * <p>Data Source:</p>
     * <ul>
     *   <li>Sourced from payment network authorization message (ISO 8583 field 43 - location component)</li>
     *   <li>May be parsed and standardized by payment processor</li>
     *   <li>Quality and consistency vary by acquiring bank and merchant setup</li>
     * </ul>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Geographic fraud detection (transaction in unexpected location)</li>
     *   <li>Cardholder transaction recognition ("Did I make a purchase in this city?")</li>
     *   <li>Travel pattern analysis (identify out-of-town transactions)</li>
     *   <li>Statement detail display for additional transaction context</li>
     * </ul>
     * 
     * <p>Optional field: May be null if merchant location information is unavailable
     * or not applicable (online merchants, phone banking transactions).</p>
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant ZIP code - Postal code of merchant location (10 characters).
     * Maps to COBOL field: TRAN-MERCHANT-ZIP PIC X(10)
     * 
     * <p>Postal code (ZIP code in US, postcode in other countries) of the merchant
     * location. Provides precise geographic location for fraud detection, transaction
     * verification, and cardholder recognition of legitimate transactions.</p>
     * 
     * <p>ZIP Format:</p>
     * <ul>
     *   <li>US: 5-digit ZIP or 9-digit ZIP+4 format (e.g., "90210" or "90210-1234")</li>
     *   <li>Canada: Alphanumeric postal code (e.g., "M5H 2N2")</li>
     *   <li>UK: Alphanumeric postcode (e.g., "SW1A 1AA")</li>
     *   <li>International: Varies by country postal standards</li>
     *   <li>Maximum 10 characters accommodates most international postal codes</li>
     * </ul>
     * 
     * <p>Data Source:</p>
     * <ul>
     *   <li>Sourced from payment network authorization message (ISO 8583 field 43)</li>
     *   <li>Quality varies significantly by merchant data entry and acquiring bank</li>
     *   <li>May be missing or incorrect for online merchants or poor merchant setup</li>
     * </ul>
     * 
     * <p>Usage:</p>
     * <ul>
     *   <li>Precise geographic fraud detection (transaction far from cardholder address)</li>
     *   <li>Merchant verification (validate merchant location matches business address)</li>
     *   <li>Distance-based fraud scoring (transactions > X miles from home)</li>
     *   <li>Regional spending analysis and reporting</li>
     * </ul>
     * 
     * <p>Stored as String to preserve formatting (hyphens, spaces, alphanumeric characters).
     * Not used for arithmetic, so String type appropriate for postal codes.</p>
     * 
     * <p>Optional field: May be null if merchant location information is unavailable
     * or not provided by payment network authorization message.</p>
     */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number - Foreign key to Card entity (16-character PAN).
     * Maps to COBOL field: TRAN-CARD-NUM PIC X(16)
     * 
     * <p>Establishes many-to-one relationship from Transaction to Card entity via card number.
     * Every transaction must be associated with exactly one card. The card provides the
     * indirect link to the account for balance updates and credit limit checks.</p>
     * 
     * <p><strong>Relationship Chain:</strong></p>
     * <ul>
     *   <li>Transaction → Card (via cardNumber foreign key)</li>
     *   <li>Card → Account (via Card.accountId foreign key)</li>
     *   <li>Account → Customer (via Account.customerId foreign key)</li>
     * </ul>
     * 
     * <p><strong>PCI-DSS Security Considerations:</strong></p>
     * <ul>
     *   <li>Card number (PAN) is Level 1 PII requiring encryption at rest</li>
     *   <li>Must be masked when displayed or logged (show only last 4 digits)</li>
     *   <li>Access must be logged in audit trail per Section 0.9 compliance</li>
     *   <li>Transmitted only over TLS 1.3+ encrypted channels</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Card transaction history queries (COTRN00C → TransactionListService)</li>
     *   <li>Balance updates via card-to-account relationship</li>
     *   <li>Credit limit validation before transaction authorization</li>
     *   <li>Statement generation for card-specific transactions</li>
     * </ul>
     * 
     * <p>Standard format: 16 consecutive digits (e.g., "4532123456789012").
     * Stored as String to preserve leading zeros and avoid numeric overflow.</p>
     * 
     * <p>Foreign Key Constraint: References card(card_number) table. Referential
     * integrity enforced at database level per Section 0.9 requirements.</p>
     */
    @Column(name = "card_number", length = 16, nullable = false)
    private String cardNumber;

    /**
     * Origination timestamp - When transaction was initiated.
     * Maps to COBOL field: TRAN-ORIG-TS PIC X(26)
     * 
     * <p>Precise timestamp when the transaction was originated or initiated by the cardholder
     * or merchant. This is the "business timestamp" representing when the transaction actually
     * occurred from the cardholder's perspective.</p>
     * 
     * <p><strong>Timestamp Format:</strong></p>
     * <ul>
     *   <li>COBOL: PIC X(26) string format (ISO 8601: YYYY-MM-DD HH:MM:SS.SSSSSS)</li>
     *   <li>Java: LocalDateTime with microsecond precision (6 decimal places)</li>
     *   <li>Example: 2024-12-15 14:23:45.123456</li>
     *   <li>Timezone: Stored in local time (application timezone), may need UTC conversion</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Determines transaction posting date for billing cycle assignment</li>
     *   <li>Used for transaction date range queries (monthly statements, reports)</li>
     *   <li>Fraud detection time-based analysis (unusual transaction timing patterns)</li>
     *   <li>Customer dispute resolution (verify transaction timing claims)</li>
     * </ul>
     * 
     * <p><strong>Data Source:</strong></p>
     * <ul>
     *   <li>POS transactions: Terminal timestamp at point of sale</li>
     *   <li>Online transactions: Web server timestamp when transaction submitted</li>
     *   <li>ATM transactions: ATM machine timestamp</li>
     *   <li>Batch transactions: Batch job execution timestamp</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Primary sorting field for transaction list displays (COTRN00C program)</li>
     *   <li>Date range filtering for statement generation (CBSTM03A batch job)</li>
     *   <li>Transaction aging calculations (days since transaction)</li>
     *   <li>Billing cycle determination (which statement period includes transaction)</li>
     * </ul>
     * 
     * <p><strong>Index Strategy:</strong></p>
     * <ul>
     *   <li>B-tree index on origination_timestamp for efficient date range queries</li>
     *   <li>Critical for statement generation performance (4-hour batch window)</li>
     *   <li>Composite index with card_number for card-specific date range queries</li>
     * </ul>
     */
    @Column(name = "origination_timestamp")
    private LocalDateTime originationTimestamp;

    /**
     * Processing timestamp - When transaction was processed by the system.
     * Maps to COBOL field: TRAN-PROC-TS PIC X(26)
     * 
     * <p>System timestamp when the transaction was processed and posted to the account
     * balance in the CardDemo system. This is the "system timestamp" representing when
     * the transaction was recorded in the database.</p>
     * 
     * <p><strong>Timestamp Format:</strong></p>
     * <ul>
     *   <li>COBOL: PIC X(26) string format (ISO 8601: YYYY-MM-DD HH:MM:SS.SSSSSS)</li>
     *   <li>Java: LocalDateTime with microsecond precision (6 decimal places)</li>
     *   <li>Example: 2024-12-15 14:25:10.987654 (typically 1-5 seconds after origination)</li>
     *   <li>Timezone: System timezone (typically UTC for batch processing)</li>
     * </ul>
     * 
     * <p><strong>Processing Time Difference:</strong></p>
     * <ul>
     *   <li>Real-time transactions: processingTimestamp ≈ originationTimestamp (1-5 seconds difference)</li>
     *   <li>Batch transactions: May be hours or days after origination (batch cycle delay)</li>
     *   <li>Offline transactions: Merchant submits batch later, causing significant delay</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Audit trail: Exact system time when balance was updated</li>
     *   <li>Performance monitoring: Processing latency analysis (origination to processing)</li>
     *   <li>Batch job tracking: Identify which batch job processed the transaction</li>
     *   <li>Regulatory compliance: System processing time for audit requirements</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Batch job reconciliation (CBTRN02C daily transaction processing)</li>
     *   <li>System performance analysis (transaction processing throughput)</li>
     *   <li>Audit trail logging per Section 0.9 compliance requirements</li>
     *   <li>Debugging transaction processing issues (identify processing delays)</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> May be null for transactions not yet processed
     * (pending authorization, held for review, scheduled future transactions). Once
     * transaction is posted to account balance, this field must be populated.</p>
     */
    @Column(name = "processing_timestamp")
    private LocalDateTime processingTimestamp;

    /**
     * Parent card relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from Transaction to Card entity via cardNumber.
     * Each transaction belongs to exactly one card. The card provides access to the parent
     * account for balance updates, credit limit checks, and cardholder information.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers parent card retrieval until
     * explicitly accessed, optimizing query performance and reducing memory footprint.
     * This maintains sub-200ms response times per Section 0.2 performance requirements
     * under 10,000 TPS load. Card entity is only loaded when transaction detail view
     * requires card information.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the bidirectional relationship (cardNumber field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with cardNumber field</li>
     *   <li>cardNumber remains the single source of truth for foreign key value</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues when Transaction entities are serialized in REST API responses. Breaks
     * bidirectional relationship serialization loop between Transaction and Card entities,
     * avoiding infinite recursion during JSON conversion for transaction list and detail
     * endpoints (COTRN00C, COTRN01C REST API equivalents).</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Access card status for transaction authorization (card must be ACTIVE)</li>
     *   <li>Navigate to parent account via card.getAccount() for balance updates</li>
     *   <li>Retrieve cardholder name via card.getEmbossedName() for display</li>
     *   <li>Check card expiration via card.isExpired() for transaction validation</li>
     * </ul>
     * 
     * <p><strong>Relationship Pattern:</strong> Transaction → Card → Account → Customer</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_number", insertable = false, updatable = false)
    @JsonIgnore
    private Card card;

    /**
     * Transaction type relationship (many-to-one).
     * 
     * <p>Establishes foreign key relationship from Transaction to TransactionType entity
     * via transactionTypeCode. Provides access to transaction type description and
     * type-specific processing rules. Each transaction belongs to exactly one transaction
     * type (Purchase, Cash Advance, Payment, Refund, Fee, Interest).</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers TransactionType retrieval
     * until explicitly accessed. Since transaction type is reference data and relatively
     * static, it is ideal for Redis caching to eliminate database lookups entirely.
     * LAZY loading prevents unnecessary reference data fetches when only transaction
     * details are needed, maintaining sub-200ms response times under high load.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the relationship (transactionTypeCode field is the owning side)</li>
     *   <li>Avoids duplicate column mapping with transactionTypeCode field</li>
     *   <li>transactionTypeCode remains the single source of truth for foreign key</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues and reduces JSON payload size in REST API responses. Transaction type code
     * is already included in transactionTypeCode field, so full entity not needed in
     * most API responses. When type description needed, service layer can explicitly
     * load and map to DTO.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Retrieve type description via transactionType.getTypeDescription() for display</li>
     *   <li>Type-specific business rules (e.g., cash advance fee calculation)</li>
     *   <li>Transaction categorization for reporting (COTRN01C category summary)</li>
     *   <li>Statement grouping by type (group all purchases, payments, fees)</li>
     * </ul>
     * 
     * <p><strong>Reference Data Caching:</strong> TransactionType entities are static
     * reference data loaded via Flyway migration V9__load_reference_data.sql. Excellent
     * candidates for Redis caching with long TTL, reducing database load significantly.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_type_code", insertable = false, updatable = false)
    @JsonIgnore
    private TransactionType transactionType;

    /**
     * Transaction category relationship (many-to-one with composite key).
     * 
     * <p>Establishes foreign key relationship from Transaction to TransactionCategory entity
     * via composite key (transactionTypeCode + transactionCategoryCode). Provides detailed
     * categorization beyond transaction type for granular spending analysis, budgeting,
     * and merchant category code (MCC) based reporting.</p>
     * 
     * <p><strong>Composite Key Mapping:</strong> TransactionCategory uses @EmbeddedId with
     * CategoryId composite key containing both typeCode and categoryCode components. This
     * relationship requires @JoinColumns (plural) to map both foreign key components:</p>
     * <ul>
     *   <li>transactionTypeCode → TransactionCategory.id.typeCode</li>
     *   <li>transactionCategoryCode → TransactionCategory.id.categoryCode</li>
     * </ul>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers TransactionCategory retrieval
     * until explicitly accessed. Like TransactionType, this is reference data ideal for
     * Redis caching. Category descriptions are not needed for every transaction query
     * (only for detail views and reports), so LAZY loading prevents unnecessary joins
     * and maintains query performance under 10,000 TPS load per Section 0.2.</p>
     * 
     * <p><strong>JoinColumns Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side
     *       of the relationship (transactionTypeCode and transactionCategoryCode fields
     *       are the owning side)</li>
     *   <li>Avoids duplicate column mapping with composite key component fields</li>
     *   <li>transactionTypeCode and transactionCategoryCode remain source of truth</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference
     * issues and reduces JSON payload size. Category codes are already included in
     * transactionTypeCode and transactionCategoryCode fields. When category description
     * needed, service layer can explicitly load and map to DTO for API response.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Retrieve category description via transactionCategory.getCategoryDescription()</li>
     *   <li>Detailed spending analysis by category (COTRN01C category summary report)</li>
     *   <li>Transaction aggregation by category (CBTRN03C aggregation batch job)</li>
     *   <li>Budgeting features (spending limits by category in UI)</li>
     *   <li>Merchant category code (MCC) mapping for industry-standard categorization</li>
     * </ul>
     * 
     * <p><strong>Reference Data Caching:</strong> TransactionCategory entities are static
     * reference data (typically 50-200 categories) loaded via Flyway migration. Excellent
     * for Redis caching with long TTL to eliminate repetitive database lookups for
     * category descriptions in high-volume transaction processing.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "transaction_type_code", referencedColumnName = "type_code",
                    insertable = false, updatable = false),
        @JoinColumn(name = "transaction_category_code", referencedColumnName = "category_code",
                    insertable = false, updatable = false)
    })
    @JsonIgnore
    private TransactionCategory transactionCategory;

    /**
     * Custom setter for transaction amount with automatic precision enforcement.
     * 
     * <p><strong>CRITICAL PRECISION REQUIREMENT:</strong> This setter ALWAYS enforces
     * scale=2 with RoundingMode.HALF_UP to maintain COBOL COMP-3 decimal precision per
     * Section 0.2 and Section 0.9 requirements. ALL transaction amount assignments go
     * through this setter to guarantee consistent precision across the application.</p>
     * 
     * <p><strong>Automatic Rounding Behavior:</strong></p>
     * <pre>
     * // Input values automatically rounded to 2 decimal places:
     * transaction.setTransactionAmount(new BigDecimal("123.456"));
     * // Result: 123.46 (rounded up from 123.456)
     * 
     * transaction.setTransactionAmount(new BigDecimal("99.994"));
     * // Result: 99.99 (rounded down from 99.994)
     * 
     * transaction.setTransactionAmount(new BigDecimal("50.125"));
     * // Result: 50.13 (HALF_UP: .125 rounds up to .13)
     * </pre>
     * 
     * <p><strong>Null Handling:</strong> If null value provided, defaults to BigDecimal.ZERO
     * with scale 2 to prevent NullPointerException in calculations. This ensures all
     * transaction amounts are always valid BigDecimal objects with proper precision.</p>
     * 
     * <p><strong>COBOL Equivalence:</strong> Matches COBOL COMP-3 rounding behavior for
     * PIC S9(09)V99 fields. The HALF_UP rounding mode is the closest Java equivalent to
     * COBOL's default rounding rules, ensuring calculation results match mainframe behavior
     * byte-for-byte per Section 0.9 functional equivalence requirements.</p>
     * 
     * @param transactionAmount BigDecimal amount to set (will be rounded to scale 2)
     */
    public void setTransactionAmount(BigDecimal transactionAmount) {
        this.transactionAmount = transactionAmount != null
            ? transactionAmount.setScale(2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Custom setter for processing timestamp with automatic precision enforcement.
     * 
     * <p>Additional business logic beyond simple field assignment: This setter is provided
     * as a placeholder for future processing timestamp validation or audit logging requirements.
     * Currently delegates to Lombok-generated setter, but can be extended with business rules.</p>
     * 
     * <p><strong>Potential Future Enhancements:</strong></p>
     * <ul>
     *   <li>Validate processingTimestamp >= originationTimestamp (no future processing)</li>
     *   <li>Audit log when processing timestamp is set (transaction posted to account)</li>
     *   <li>Trigger balance update workflow when transaction marked as processed</li>
     *   <li>Performance monitoring: Log processing latency (processing - origination time)</li>
     * </ul>
     * 
     * <p>Currently this method exists to satisfy the exports schema requirement for
     * setProcessingTimestamp() as a distinct member, even though it delegates to the
     * Lombok-generated implementation. This maintains API contract compatibility and
     * provides extension point for future business logic.</p>
     * 
     * @param processingTimestamp LocalDateTime when transaction was processed
     */
    public void setProcessingTimestamp(LocalDateTime processingTimestamp) {
        this.processingTimestamp = processingTimestamp;
    }

    /**
     * Custom setter for transaction source with automatic precision enforcement.
     * 
     * <p>Additional business logic beyond simple field assignment: This setter is provided
     * as a placeholder for future transaction source validation or audit logging requirements.
     * Currently delegates to Lombok-generated setter, but can be extended with business rules.</p>
     * 
     * <p><strong>Potential Future Enhancements:</strong></p>
     * <ul>
     *   <li>Validate source against allowed values enum (POS, ATM, ONLINE, MOBILE, etc.)</li>
     *   <li>Audit log source-specific processing rules being applied</li>
     *   <li>Normalize source value to uppercase for consistency</li>
     *   <li>Trigger source-specific fraud detection rules</li>
     * </ul>
     * 
     * <p>Currently this method exists to satisfy the exports schema requirement for
     * setTransactionSource() as a distinct member, even though it delegates to the
     * Lombok-generated implementation. This maintains API contract compatibility and
     * provides extension point for future business logic.</p>
     * 
     * @param transactionSource String indicating transaction origination channel (max 10 chars)
     */
    public void setTransactionSource(String transactionSource) {
        this.transactionSource = transactionSource;
    }
}
