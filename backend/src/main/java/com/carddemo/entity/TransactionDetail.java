package com.carddemo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA Entity representing transaction detail data table for storing additional transactional
 * metadata and audit information.
 * 
 * <p>This entity extends the base Transaction entity with supplementary metadata, authorization
 * details, payment processor information, settlement data, and audit trail information. It enables
 * separation of core transaction data (stored in Transaction entity) from supplementary details,
 * supporting detailed transaction history, reconciliation, and regulatory compliance requirements
 * per Section 0.9.</p>
 * 
 * <p><strong>Database Design Rationale:</strong></p>
 * <ul>
 *   <li>Normalized relational database design - separates core vs supplementary transaction data</li>
 *   <li>One-to-one relationship with Transaction entity via transactionId foreign key</li>
 *   <li>Not all transactions require detail records (optional supplementary data)</li>
 *   <li>Enables efficient queries when only core transaction data needed</li>
 *   <li>Supports extension with additional detail fields without impacting base Transaction table</li>
 * </ul>
 * 
 * <p><strong>Entity Relationships:</strong></p>
 * <ul>
 *   <li>Parent entity: Transaction (one-to-one via transaction_id foreign key)</li>
 *   <li>Relationship chain: TransactionDetail → Transaction → Card → Account → Customer</li>
 *   <li>Foreign key constraint: transaction_id REFERENCES transaction(transaction_id) ON DELETE CASCADE</li>
 *   <li>Unique constraint on transaction_id ensures true one-to-one relationship</li>
 * </ul>
 * 
 * <p><strong>Key Fields and Purpose:</strong></p>
 * <ul>
 *   <li><strong>Authorization Data:</strong> Authorization code and response code from payment processor</li>
 *   <li><strong>Processor Info:</strong> Processor name and terminal ID for transaction routing</li>
 *   <li><strong>Extended Description:</strong> Additional transaction description beyond base 100 chars</li>
 *   <li><strong>Merchant Classification:</strong> Merchant category code (MCC) for industry categorization</li>
 *   <li><strong>Multi-Currency:</strong> Currency code (ISO 4217) and exchange rate for foreign transactions</li>
 *   <li><strong>Settlement:</strong> Settlement date tracking (may differ from processing date)</li>
 *   <li><strong>Audit Trail:</strong> Created/updated timestamps for regulatory compliance</li>
 * </ul>
 * 
 * <p><strong>Usage in COBOL Programs (Transformation Context):</strong></p>
 * <ul>
 *   <li>COTRN00C.cbl → TransactionListService (transaction inquiry with extended details)</li>
 *   <li>COTRN01C.cbl → TransactionCategoryService (category summary with MCC data)</li>
 *   <li>COTRN02C.cbl → TransactionCreationService (create transaction with authorization data)</li>
 *   <li>CBTRN02C.cbl → DailyTransactionProcessingJob (batch processing with settlement tracking)</li>
 *   <li>CBSTM03A.cbl → StatementGenerationJob (statement with full transaction detail)</li>
 * </ul>
 * 
 * <p><strong>CRITICAL Numeric Precision Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>Exchange rate: BigDecimal with precision=10, scale=6 for accurate currency conversion</li>
 *   <li>ALL arithmetic operations MUST explicitly call setScale(6, RoundingMode.HALF_UP)</li>
 *   <li>Maintains precision for multi-currency transaction calculations</li>
 *   <li>Example: convertedAmount = transactionAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP)</li>
 * </ul>
 * 
 * <p><strong>Audit Trail Requirements (Section 0.9):</strong></p>
 * <ul>
 *   <li>createdDate timestamp: Record creation time for compliance tracking</li>
 *   <li>updatedDate timestamp: Record modification audit trail</li>
 *   <li>Response codes stored for transaction approval/decline history</li>
 *   <li>Authorization codes retained for payment processor reconciliation</li>
 *   <li>Complete audit trail for regulatory reporting requirements</li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li>One-to-one with Transaction: Detail loaded only when explicitly needed (LAZY fetch)</li>
 *   <li>Prevents unnecessary data retrieval when only core transaction info required</li>
 *   <li>Supports sub-200ms transaction query response times per Section 0.2</li>
 *   <li>Indexed on transaction_id (unique) for efficient detail lookup</li>
 *   <li>Additional indexes on authorization_code and settlement_date for specialized queries</li>
 * </ul>
 * 
 * @see Transaction
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Audit and Compliance Requirements</a>
 */
@Entity
@Table(name = "transaction_detail")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDetail implements Serializable {

    /**
     * Serial version UID for Serializable interface.
     * Supports distributed caching with Redis-backed Spring Session for
     * clustered deployment environments per Section 0.5 caching strategy.
     * Required for entity serialization across JVM boundaries in distributed systems.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Transaction detail identifier - Primary key (auto-generated).
     * 
     * <p><strong>PRIMARY KEY for TransactionDetail entity.</strong></p>
     * 
     * <p>Auto-generated surrogate key using database sequence (IDENTITY strategy).
     * Provides unique identifier for each transaction detail record independent
     * of the natural key (transactionId).</p>
     * 
     * <p><strong>Generation Strategy:</strong></p>
     * <ul>
     *   <li>GenerationType.IDENTITY: Database auto-increment column</li>
     *   <li>PostgreSQL SERIAL or BIGSERIAL type (8-byte integer)</li>
     *   <li>Automatically assigned on INSERT, never null after persistence</li>
     *   <li>Supports high-volume transaction detail creation without key conflicts</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Internal entity management (JPA entity identity)</li>
     *   <li>Direct detail record lookup when ID known</li>
     *   <li>Foreign key references from potential child entities (future extensions)</li>
     *   <li>Audit logging and debugging (unique record identifier)</li>
     * </ul>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_detail_id", nullable = false)
    private Long transactionDetailId;

    /**
     * Transaction identifier - Foreign key to Transaction entity (16 characters).
     * 
     * <p>Establishes one-to-one relationship from TransactionDetail to Transaction entity.
     * Each transaction detail record belongs to exactly one parent transaction. This is
     * the foreign key component of the relationship, working in conjunction with the
     * @OneToOne transaction field navigation property.</p>
     * 
     * <p><strong>Relationship Configuration:</strong></p>
     * <ul>
     *   <li>Foreign key: References transaction(transaction_id) table</li>
     *   <li>Unique constraint: Enforces one-to-one relationship (one detail per transaction)</li>
     *   <li>ON DELETE CASCADE: Detail deleted automatically when parent transaction deleted</li>
     *   <li>nullable = false: Every detail must belong to a valid transaction</li>
     * </ul>
     * 
     * <p><strong>Data Format:</strong></p>
     * <ul>
     *   <li>16-character alphanumeric string matching Transaction.transactionId format</li>
     *   <li>Example: "2024121501234567" (date + sequence)</li>
     *   <li>Stored as String to preserve leading zeros and alphanumeric capability</li>
     *   <li>Must exist in transaction table (referential integrity enforced)</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Join key for transaction-to-detail queries</li>
     *   <li>Lookup detail by parent transaction ID</li>
     *   <li>Foreign key constraint ensures orphan detail records cannot exist</li>
     *   <li>Used by service layer to create/retrieve transaction with details</li>
     * </ul>
     */
    @Column(name = "transaction_id", length = 16, nullable = false)
    private String transactionId;

    /**
     * Authorization code - Payment processor authorization code (6 characters).
     * 
     * <p>Unique authorization code assigned by the payment processor (Visa, Mastercard, etc.)
     * when the transaction is approved. Used for transaction reconciliation, dispute resolution,
     * and chargeback processing. Critical for matching authorization records with settlement
     * records in payment processor reporting.</p>
     * 
     * <p><strong>Authorization Code Format:</strong></p>
     * <ul>
     *   <li>Typically 6 alphanumeric characters (e.g., "AB12CD", "123456")</li>
     *   <li>Assigned by issuing bank or payment network authorization system</li>
     *   <li>Unique within merchant/processor combination for reconciliation</li>
     *   <li>May be numeric only or alphanumeric depending on processor</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Proof of authorization for approved transactions</li>
     *   <li>Required for chargeback defense (merchant provides auth code)</li>
     *   <li>Reconciliation key matching authorization to settlement records</li>
     *   <li>Customer service reference for transaction verification</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Transaction detail display screens (COTRN00C → TransactionListService)</li>
     *   <li>Chargeback processing and dispute resolution</li>
     *   <li>Payment processor reconciliation reports</li>
     *   <li>Authorization lookup for customer service inquiries</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> Null for declined transactions (no auth code issued),
     * non-authorized transactions (payments, fees, interest), or legacy transactions missing data.</p>
     */
    @Column(name = "authorization_code", length = 6)
    private String authorizationCode;

    /**
     * Response code - Payment processor response code (2 characters).
     * 
     * <p>Standardized response code returned by the payment processor indicating the result
     * of the transaction authorization request. Critical for understanding transaction
     * approval/decline reasons, fraud detection, and customer service issue resolution.</p>
     * 
     * <p><strong>Common Response Codes (ISO 8583 Standard):</strong></p>
     * <ul>
     *   <li>"00" - Approved/Completed Successfully</li>
     *   <li>"05" - Do Not Honor (generic decline)</li>
     *   <li>"14" - Invalid Card Number</li>
     *   <li>"41" - Lost Card, Pick Up</li>
     *   <li>"43" - Stolen Card, Pick Up</li>
     *   <li>"51" - Insufficient Funds</li>
     *   <li>"54" - Expired Card</li>
     *   <li>"61" - Exceeds Withdrawal Limit</li>
     *   <li>"62" - Restricted Card</li>
     *   <li>"65" - Exceeds Withdrawal Frequency</li>
     *   <li>"91" - Issuer or Switch Inoperative</li>
     *   <li>"96" - System Malfunction</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Transaction approval/decline determination</li>
     *   <li>Fraud detection patterns (multiple decline codes indicate fraud attempts)</li>
     *   <li>Customer service issue diagnosis ("Why was my card declined?")</li>
     *   <li>Authorization rules tuning (analyze decline reasons to adjust limits)</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Transaction detail display with decline reason (COTRN00C program)</li>
     *   <li>Fraud detection analysis (multiple "05" declines = potential fraud)</li>
     *   <li>Customer service scripts (interpret response code for cardholder)</li>
     *   <li>Authorization performance reporting (approval rate analysis)</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> May be null for internal transactions (payments, fees,
     * interest) that do not go through external payment processor authorization.</p>
     */
    @Column(name = "response_code", length = 2)
    private String responseCode;

    /**
     * Processor name - Payment processor handling transaction (50 characters).
     * 
     * <p>Name of the payment processor or acquiring bank that handled the transaction
     * authorization and settlement. Used for processor-specific reporting, reconciliation,
     * and routing analysis. Identifies which payment network processed the transaction.</p>
     * 
     * <p><strong>Common Processor Names:</strong></p>
     * <ul>
     *   <li>"VISA" - Visa payment network</li>
     *   <li>"MASTERCARD" - Mastercard payment network</li>
     *   <li>"AMERICAN EXPRESS" - American Express network</li>
     *   <li>"DISCOVER" - Discover network</li>
     *   <li>"FIRST DATA" - First Data processor (now Fiserv)</li>
     *   <li>"TSYS" - Total System Services processor</li>
     *   <li>"WORLDPAY" - Worldpay processor</li>
     *   <li>"INTERNAL" - Internal system processing (payments, fees, interest)</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Processor-specific reconciliation (match transactions to processor settlements)</li>
     *   <li>Network fee calculation (different networks charge different interchange fees)</li>
     *   <li>Routing optimization (route transactions to most cost-effective processor)</li>
     *   <li>Performance analysis (processor authorization approval rates)</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Transaction detail display for customer service reference</li>
     *   <li>Processor reconciliation reports (CBTRN02C batch processing)</li>
     *   <li>Network fee calculation and allocation</li>
     *   <li>Transaction routing rules and optimization</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> May be null for legacy transactions or internal
     * adjustments not processed through external payment networks.</p>
     */
    @Column(name = "processor_name", length = 50)
    private String processorName;

    /**
     * Terminal identifier - POS terminal or ATM device ID (20 characters).
     * 
     * <p>Unique identifier for the point-of-sale terminal, ATM machine, or virtual terminal
     * where the transaction was initiated. Used for terminal-level reporting, device tracking,
     * fraud detection (compromised terminal patterns), and merchant reconciliation.</p>
     * 
     * <p><strong>Terminal ID Format:</strong></p>
     * <ul>
     *   <li>POS terminals: Merchant-assigned terminal ID (e.g., "STORE1234-TERM01")</li>
     *   <li>ATM machines: ATM ID including bank and location (e.g., "ATM-BANK123-LOC456")</li>
     *   <li>E-commerce: Virtual terminal ID or gateway ID (e.g., "ECOM-GATEWAY-01")</li>
     *   <li>Mobile: Mobile device fingerprint or app instance ID</li>
     *   <li>Maximum 20 characters accommodates various terminal ID schemes</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Fraud detection: Compromised terminal identification (multiple fraud from same terminal)</li>
     *   <li>Merchant reconciliation: Terminal-level transaction reporting for multi-terminal merchants</li>
     *   <li>Device management: Track transaction volume and performance by terminal</li>
     *   <li>Customer service: Identify exact terminal for transaction dispute resolution</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Fraud detection analysis (terminal-level fraud patterns)</li>
     *   <li>Merchant reporting (terminal performance and volume analysis)</li>
     *   <li>Device tracking and management (terminal health monitoring)</li>
     *   <li>Transaction detail display for customer service inquiries</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> May be null for phone banking, mail payments,
     * or internal system transactions where terminal concept does not apply.</p>
     */
    @Column(name = "terminal_id", length = 20)
    private String terminalId;

    /**
     * Extended description - Additional transaction description (500 characters).
     * 
     * <p>Extended free-form text description providing additional transaction details beyond
     * the base 100-character description in the Transaction entity. Used for detailed
     * transaction notes, supplementary merchant information, promotion details, or
     * customer service comments added post-transaction.</p>
     * 
     * <p><strong>Extended Description Content:</strong></p>
     * <ul>
     *   <li>Full merchant name and address (when base description truncated)</li>
     *   <li>Itemized purchase details (list of items purchased)</li>
     *   <li>Promotion or discount information applied to transaction</li>
     *   <li>Customer service notes added during dispute resolution</li>
     *   <li>Supplementary transaction context for audit trail</li>
     * </ul>
     * 
     * <p><strong>Usage Context:</strong></p>
     * <ul>
     *   <li>Transaction detail screens requiring full description (COTRN00C program)</li>
     *   <li>Customer service inquiry screens (full transaction context)</li>
     *   <li>Statement generation with detailed line item descriptions</li>
     *   <li>Regulatory compliance reporting requiring full transaction details</li>
     * </ul>
     * 
     * <p><strong>Data Source:</strong></p>
     * <ul>
     *   <li>May be populated from ISO 8583 additional data fields</li>
     *   <li>Customer service adds notes during transaction inquiry or dispute</li>
     *   <li>Batch processing appends supplementary information from merchant files</li>
     *   <li>Cardholder may add notes via mobile banking app (future enhancement)</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> Null if no extended description needed or available.
     * Most transactions use only the base 100-character description in Transaction entity.</p>
     */
    @Column(name = "extended_description", length = 500)
    private String extendedDescription;

    /**
     * Merchant category code (MCC) - Industry classification (4 characters).
     * 
     * <p>ISO 18245 Merchant Category Code (MCC) classifying the merchant's type of business.
     * Used for spending analysis, budgeting categories, reward program rules, and interchange
     * fee determination. Provides standardized industry categorization across all merchants.</p>
     * 
     * <p><strong>Common MCC Examples:</strong></p>
     * <ul>
     *   <li>"5411" - Grocery Stores, Supermarkets</li>
     *   <li>"5541" - Service Stations (Gasoline)</li>
     *   <li>"5812" - Eating Places, Restaurants</li>
     *   <li>"5921" - Package Stores - Beer, Wine, Liquor</li>
     *   <li>"5999" - Miscellaneous and Specialty Retail Stores</li>
     *   <li>"4111" - Local/Suburban Commuter Transportation</li>
     *   <li>"4511" - Airlines, Air Carriers</li>
     *   <li>"7011" - Hotels, Motels, Resorts</li>
     *   <li>"8011" - Doctors, Physicians</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Spending analysis: Categorize transactions by merchant type (COTRN01C category summary)</li>
     *   <li>Budgeting: Track spending limits by MCC category (e.g., dining, travel)</li>
     *   <li>Rewards programs: Different reward rates by MCC (e.g., 3x points on dining)</li>
     *   <li>Interchange fees: MCC determines network interchange fee rates</li>
     *   <li>Fraud detection: Unusual MCC patterns indicate potential fraud</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Transaction categorization (COTRN01C → TransactionCategoryService)</li>
     *   <li>Spending analysis dashboards (category breakdown charts)</li>
     *   <li>Rewards calculation (MCC-based reward multipliers)</li>
     *   <li>Budget tracking (spending vs budget by MCC category)</li>
     * </ul>
     * 
     * <p><strong>Data Source:</strong> Sourced from payment network authorization message
     * (ISO 8583 field 18 - Merchant Type). Provided by acquiring bank merchant setup.</p>
     * 
     * <p><strong>Optional Field:</strong> May be null for non-merchant transactions (payments,
     * fees, interest) or legacy transactions with incomplete merchant data.</p>
     */
    @Column(name = "merchant_category_code", length = 4)
    private String merchantCategoryCode;

    /**
     * Currency code - ISO 4217 currency code (3 characters).
     * 
     * <p>Three-letter ISO 4217 currency code identifying the currency in which the transaction
     * was conducted. Required for multi-currency transaction support, foreign exchange
     * conversion, and international transaction reporting. Default is "USD" for domestic
     * US dollar transactions.</p>
     * 
     * <p><strong>Common Currency Codes:</strong></p>
     * <ul>
     *   <li>"USD" - United States Dollar (domestic default)</li>
     *   <li>"EUR" - Euro (European Union)</li>
     *   <li>"GBP" - British Pound Sterling (United Kingdom)</li>
     *   <li>"JPY" - Japanese Yen (Japan)</li>
     *   <li>"CAD" - Canadian Dollar (Canada)</li>
     *   <li>"AUD" - Australian Dollar (Australia)</li>
     *   <li>"CHF" - Swiss Franc (Switzerland)</li>
     *   <li>"CNY" - Chinese Yuan Renminbi (China)</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Foreign currency conversion: Determine exchange rate application</li>
     *   <li>International transaction fees: Identify transactions subject to foreign transaction fees</li>
     *   <li>Multi-currency reporting: Separate domestic vs international transaction volumes</li>
     *   <li>Settlement currency: Determine settlement currency for processor reconciliation</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Currency conversion calculation (amount * exchangeRate)</li>
     *   <li>Foreign transaction fee determination (apply fee if currencyCode != "USD")</li>
     *   <li>International transaction reporting and analysis</li>
     *   <li>Transaction detail display with currency symbol (COTRN00C program)</li>
     * </ul>
     * 
     * <p><strong>Required Field:</strong> Must be populated for all transactions. Default to
     * "USD" for domestic transactions if not explicitly specified by payment processor.</p>
     */
    @Column(name = "currency_code", length = 3, nullable = false)
    private String currencyCode;

    /**
     * Exchange rate - Currency conversion rate (precision 10, scale 6).
     * 
     * <p><strong>CRITICAL NUMERIC PRECISION REQUIREMENT:</strong> This field uses BigDecimal
     * with precision=10, scale=6 to maintain accurate currency conversion rates. The 6 decimal
     * places provide sufficient precision for exchange rate fluctuations while preventing
     * rounding errors in currency conversion calculations per Section 0.2 and 0.9 requirements.</p>
     * 
     * <p><strong>Precision Specification:</strong></p>
     * <ul>
     *   <li>Precision: 10 total digits (4 integer + 6 decimal)</li>
     *   <li>Scale: 6 decimal places for exchange rate accuracy</li>
     *   <li>Rounding: MUST use RoundingMode.HALF_UP for all arithmetic operations</li>
     *   <li>Range: 0.000001 to 9999.999999 (covers all realistic exchange rates)</li>
     *   <li>Example rates: 1.185432 (USD to EUR), 110.234567 (USD to JPY)</li>
     * </ul>
     * 
     * <p><strong>Exchange Rate Interpretation:</strong></p>
     * <ul>
     *   <li>Rate represents: 1 unit of transaction currency = X units of settlement currency</li>
     *   <li>Example: USD to EUR rate 0.850000 means 1 USD = 0.85 EUR</li>
     *   <li>Conversion formula: settlementAmount = transactionAmount * exchangeRate</li>
     *   <li>Result rounded to 2 decimal places: settlementAmount.setScale(2, RoundingMode.HALF_UP)</li>
     * </ul>
     * 
     * <p><strong>Precision Preservation Example:</strong></p>
     * <pre>
     * // CORRECT - Maintains exchange rate precision
     * BigDecimal usdAmount = new BigDecimal("100.00");
     * BigDecimal exchangeRate = new BigDecimal("1.185432");
     * BigDecimal eurAmount = usdAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
     * // Result: 118.54 EUR
     * 
     * // INCORRECT - Loses precision, violates Section 0.9 requirements
     * double eurAmount = 100.00 * 1.185432;  // NEVER use double for currency!
     * </pre>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Foreign currency transaction conversion to cardholder billing currency</li>
     *   <li>Accurate multi-currency settlement calculations</li>
     *   <li>Foreign exchange gain/loss reporting</li>
     *   <li>Regulatory compliance: accurate currency conversion disclosure</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Convert foreign transaction amount to USD settlement amount</li>
     *   <li>Display converted amounts on transaction detail screens</li>
     *   <li>Statement generation with exchange rate disclosure (CBSTM03A batch job)</li>
     *   <li>Foreign exchange reporting and analysis</li>
     * </ul>
     * 
     * <p><strong>Data Source:</strong> Exchange rate provided by payment processor at time of
     * transaction authorization, typically sourced from daily exchange rate tables published
     * by payment networks (Visa, Mastercard) or card issuer's foreign exchange provider.</p>
     * 
     * <p><strong>Optional Field:</strong> Null for domestic transactions in base currency (USD).
     * Required for foreign currency transactions where currencyCode != "USD".</p>
     */
    @Column(name = "exchange_rate", precision = 10, scale = 6)
    private BigDecimal exchangeRate;

    /**
     * Settlement date - Date transaction was settled (may differ from processing date).
     * 
     * <p>Date when the transaction was settled between the acquiring bank and issuing bank
     * through the payment network settlement process. This date may differ from the transaction
     * processing date due to batch settlement cycles, weekend/holiday delays, or merchant batch
     * submission timing. Used for settlement reconciliation and cash flow analysis.</p>
     * 
     * <p><strong>Settlement Date vs Processing Date:</strong></p>
     * <ul>
     *   <li>Processing date: When transaction posted to cardholder account (Transaction.processingTimestamp)</li>
     *   <li>Settlement date: When funds transferred between banks via payment network</li>
     *   <li>Typical lag: 1-3 business days between processing and settlement</li>
     *   <li>Weekend/holiday: Settlement delayed to next business day</li>
     * </ul>
     * 
     * <p><strong>Business Significance:</strong></p>
     * <ul>
     *   <li>Cash flow management: Predict when funds will be debited from settlement account</li>
     *   <li>Settlement reconciliation: Match transaction amounts to network settlement reports</li>
     *   <li>Float calculation: Determine interest on funds between processing and settlement</li>
     *   <li>Working capital analysis: Understand timing of cash movements</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Settlement reconciliation reports (match to payment network settlement files)</li>
     *   <li>Cash flow forecasting (predict settlement account debits)</li>
     *   <li>Financial reporting (accrue for unsettled transactions)</li>
     *   <li>Transaction detail display for accounting inquiries</li>
     * </ul>
     * 
     * <p><strong>Data Source:</strong> Settlement date provided by payment processor in
     * settlement file (typically received 1-2 days after settlement). Updated via batch
     * settlement reconciliation job (CBTRN02C → DailyTransactionProcessingJob).</p>
     * 
     * <p><strong>Optional Field:</strong> Null for transactions not yet settled (pending settlement)
     * or internal transactions (payments, fees, interest) that do not go through network settlement.</p>
     */
    @Column(name = "settlement_date")
    private LocalDate settlementDate;

    /**
     * Created date - Record creation timestamp (audit trail).
     * 
     * <p>Audit timestamp indicating when the transaction detail record was created in the database.
     * Required for regulatory compliance audit trail per Section 0.9. Provides complete record
     * lifecycle tracking for compliance reporting, dispute resolution, and system debugging.</p>
     * 
     * <p><strong>Audit Trail Purpose:</strong></p>
     * <ul>
     *   <li>Compliance: Regulatory requirement to track record creation time</li>
     *   <li>Dispute resolution: Establish timeline of transaction processing</li>
     *   <li>Data quality: Identify data load batches by creation timestamp</li>
     *   <li>Debugging: Trace when records were created for issue investigation</li>
     * </ul>
     * 
     * <p><strong>Timestamp Format:</strong></p>
     * <ul>
     *   <li>LocalDateTime with microsecond precision (6 decimal places)</li>
     *   <li>Example: 2024-12-15 14:23:45.123456</li>
     *   <li>Automatically set on record creation (INSERT operation)</li>
     *   <li>Immutable: Never updated after initial record creation</li>
     * </ul>
     * 
     * <p><strong>Population Strategy:</strong></p>
     * <ul>
     *   <li>Set by application code: LocalDateTime.now() during entity creation</li>
     *   <li>Alternative: Database DEFAULT CURRENT_TIMESTAMP trigger</li>
     *   <li>JPA @PrePersist: Automatically populate before INSERT</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Audit trail reporting for regulatory compliance</li>
     *   <li>Data lineage tracking (when was this record created?)</li>
     *   <li>Batch job monitoring (track data load completion time)</li>
     *   <li>System debugging (investigate data creation issues)</li>
     * </ul>
     * 
     * <p><strong>Required Field:</strong> Must be populated for all transaction detail records.
     * Critical for Section 0.9 audit trail and compliance requirements.</p>
     */
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    /**
     * Updated date - Record last modification timestamp (audit trail).
     * 
     * <p>Audit timestamp indicating when the transaction detail record was last modified.
     * Tracks updates to authorization codes, response codes, settlement dates, or other
     * detail fields added post-transaction. Provides complete modification audit trail
     * for regulatory compliance per Section 0.9.</p>
     * 
     * <p><strong>Audit Trail Purpose:</strong></p>
     * <ul>
     *   <li>Compliance: Regulatory requirement to track record modification history</li>
     *   <li>Change tracking: Identify when transaction details were updated</li>
     *   <li>Data quality: Audit trail for correction or enrichment processes</li>
     *   <li>Debugging: Investigate data modification issues and timing</li>
     * </ul>
     * 
     * <p><strong>Timestamp Format:</strong></p>
     * <ul>
     *   <li>LocalDateTime with microsecond precision (6 decimal places)</li>
     *   <li>Example: 2024-12-15 16:45:12.987654</li>
     *   <li>Updated on every record modification (UPDATE operation)</li>
     *   <li>Null if record never updated after initial creation</li>
     * </ul>
     * 
     * <p><strong>Update Scenarios:</strong></p>
     * <ul>
     *   <li>Settlement date added via batch reconciliation job</li>
     *   <li>Authorization code updated during transaction re-authorization</li>
     *   <li>Extended description added by customer service during inquiry</li>
     *   <li>Currency exchange rate adjusted for foreign transactions</li>
     * </ul>
     * 
     * <p><strong>Population Strategy:</strong></p>
     * <ul>
     *   <li>Set by application code: LocalDateTime.now() during entity update</li>
     *   <li>Alternative: Database ON UPDATE CURRENT_TIMESTAMP trigger</li>
     *   <li>JPA @PreUpdate: Automatically populate before UPDATE</li>
     * </ul>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Audit trail reporting (when was this record modified?)</li>
     *   <li>Change history tracking for compliance</li>
     *   <li>Data quality monitoring (identify stale or updated records)</li>
     *   <li>System debugging (investigate modification timing issues)</li>
     * </ul>
     * 
     * <p><strong>Optional Field:</strong> Null if record never updated after initial creation.
     * Populated only on first UPDATE operation and refreshed on subsequent updates.</p>
     */
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    /**
     * Parent transaction relationship (one-to-one).
     * 
     * <p>Establishes foreign key relationship from TransactionDetail to Transaction entity via
     * transactionId. Each transaction detail record belongs to exactly one parent transaction.
     * The transaction provides access to core transaction data (amount, date, card, merchant)
     * while this detail entity provides supplementary metadata.</p>
     * 
     * <p><strong>Fetch Strategy:</strong> LAZY loading defers parent transaction retrieval until
     * explicitly accessed, optimizing query performance and reducing memory footprint. When
     * querying transaction details, the parent transaction is only loaded if explicitly needed,
     * preventing unnecessary joins and maintaining sub-200ms response times per Section 0.2
     * performance requirements under 10,000 TPS load.</p>
     * 
     * <p><strong>JoinColumn Configuration:</strong></p>
     * <ul>
     *   <li>insertable = false, updatable = false: Prevents JPA from managing this side of the
     *       bidirectional relationship (transactionId field is the owning side)</li>
     *   <li>Avoids duplicate column mapping conflicts with transactionId field</li>
     *   <li>transactionId field remains the single source of truth for foreign key value</li>
     *   <li>JPA uses this relationship for navigation only, not persistence</li>
     * </ul>
     * 
     * <p><strong>JSON Serialization:</strong> @JsonIgnore prevents circular reference issues
     * when TransactionDetail entities are serialized in REST API responses. Breaks bidirectional
     * relationship serialization loop between TransactionDetail and Transaction entities (if
     * Transaction has @OneToOne(mappedBy="transaction") back-reference), avoiding infinite
     * recursion and stack overflow errors during JSON conversion for transaction inquiry and
     * reporting endpoints.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>Access core transaction data via transaction.getTransactionAmount()</li>
     *   <li>Navigate to card via transaction.getCard() for cardholder information</li>
     *   <li>Retrieve transaction date via transaction.getOriginationTimestamp()</li>
     *   <li>Service layer joins detail with transaction for complete view</li>
     * </ul>
     * 
     * <p><strong>Relationship Pattern:</strong> TransactionDetail → Transaction → Card → Account → Customer</p>
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", insertable = false, updatable = false)
    @JsonIgnore
    private Transaction transaction;

    /**
     * Custom setter for exchange rate with automatic precision enforcement.
     * 
     * <p><strong>CRITICAL PRECISION REQUIREMENT:</strong> This setter ALWAYS enforces scale=6
     * with RoundingMode.HALF_UP to maintain accurate currency exchange rate precision per
     * Section 0.2 and Section 0.9 requirements. ALL exchange rate assignments go through this
     * setter to guarantee consistent precision across multi-currency transaction processing.</p>
     * 
     * <p><strong>Automatic Rounding Behavior:</strong></p>
     * <pre>
     * // Input values automatically rounded to 6 decimal places:
     * detail.setExchangeRate(new BigDecimal("1.1854321234"));
     * // Result: 1.185432 (rounded to 6 decimal places)
     * 
     * detail.setExchangeRate(new BigDecimal("110.2345678"));
     * // Result: 110.234568 (HALF_UP: .0000078 rounds up to .000568)
     * 
     * detail.setExchangeRate(new BigDecimal("0.8500005"));
     * // Result: 0.850001 (HALF_UP: .0000005 rounds up to .000001)
     * </pre>
     * 
     * <p><strong>Null Handling:</strong> If null value provided, the field is set to null
     * (not defaulted to zero). Null exchange rate indicates domestic transaction in base
     * currency with no currency conversion needed.</p>
     * 
     * <p><strong>Currency Conversion Precision:</strong> The 6 decimal places provide
     * sufficient precision for accurate currency conversion while preventing accumulation
     * of rounding errors in multi-step calculations. This maintains financial accuracy
     * requirements per Section 0.9 compliance standards.</p>
     * 
     * <p><strong>Usage in Calculations:</strong></p>
     * <pre>
     * // CORRECT - Currency conversion with proper precision
     * BigDecimal transactionAmount = new BigDecimal("100.00");
     * BigDecimal exchangeRate = detail.getExchangeRate();  // Already scale 6
     * BigDecimal convertedAmount = transactionAmount
     *     .multiply(exchangeRate)
     *     .setScale(2, RoundingMode.HALF_UP);  // Final result to 2 decimal places
     * </pre>
     * 
     * @param exchangeRate BigDecimal exchange rate to set (will be rounded to scale 6)
     */
    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate != null
            ? exchangeRate.setScale(6, RoundingMode.HALF_UP)
            : null;
    }
}
