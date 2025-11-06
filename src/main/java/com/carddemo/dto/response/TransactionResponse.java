package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Transaction detail response DTO containing complete transaction information with formatted monetary amounts.
 * 
 * <p>This DTO maps the VSAM transaction master file record (CVTRA05Y.cpy TRAN-RECORD) to JSON response format
 * for RESTful API operations. It replaces COBOL transaction record display from transaction management programs
 * (COTRN00C, COTRN01C, COTRN02C) with JSON representation preserving exact decimal precision for transaction
 * amounts.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This response DTO transforms the 350-byte COBOL TRAN-RECORD structure defined in CVTRA05Y.cpy:</p>
 * <pre>
 * 01  TRAN-RECORD.                                    (RECLN = 350)
 *     05  TRAN-ID                  PIC X(16).         → transactionId
 *     05  TRAN-TYPE-CD             PIC X(02).         → typeCode
 *     05  TRAN-CAT-CD              PIC 9(04).         → categoryCode
 *     05  TRAN-SOURCE              PIC X(10).         → source
 *     05  TRAN-DESC                PIC X(100).        → description
 *     05  TRAN-AMT                 PIC S9(09)V99.     → amount (BigDecimal scale=2)
 *     05  TRAN-MERCHANT-ID         PIC 9(09).         → merchantId
 *     05  TRAN-MERCHANT-NAME       PIC X(50).         → merchantName
 *     05  TRAN-MERCHANT-CITY       PIC X(50).         → merchantCity
 *     05  TRAN-MERCHANT-ZIP        PIC X(10).         → merchantZip
 *     05  TRAN-CARD-NUM            PIC X(16).         → cardNumber (masked)
 *     05  TRAN-ORIG-TS             PIC X(26).         → originationTimestamp
 *     05  TRAN-PROC-TS             PIC X(26).         → processingTimestamp
 * </pre>
 * 
 * <h2>Critical Implementation Details</h2>
 * 
 * <h3>BigDecimal Amount Field with JSON String Serialization</h3>
 * <p>The {@code amount} field uses {@link BigDecimal} with scale=2 and {@link com.fasterxml.jackson.annotation.JsonFormat}
 * shape=STRING to prevent JSON numeric precision loss. This is critical because:</p>
 * <ul>
 *   <li>COBOL COMP-3 packed decimal PIC S9(09)V99 requires exact 2 decimal place precision</li>
 *   <li>JavaScript Number type cannot safely represent all monetary values without precision loss</li>
 *   <li>Serializing as JSON string "123.45" prevents client-side rounding errors</li>
 *   <li>All arithmetic operations use BigDecimal methods (add, subtract, multiply, divide) with RoundingMode.HALF_UP</li>
 * </ul>
 * 
 * <h3>Card Number Masking for PCI DSS Compliance</h3>
 * <p>The {@code cardNumber} field contains masked card PAN showing only the last 4 digits
 * (e.g., "************1234") to comply with PCI DSS requirements. The full 16-digit PAN
 * from TRAN-CARD-NUM is never exposed in API responses for security purposes.</p>
 * 
 * <h3>Timestamp Field Transformations</h3>
 * <p>Both {@code originationTimestamp} and {@code processingTimestamp} fields transform COBOL alphanumeric
 * PIC X(26) fields to Java {@link LocalDateTime} with ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss):</p>
 * <ul>
 *   <li><b>originationTimestamp</b>: When transaction originated at merchant terminal</li>
 *   <li><b>processingTimestamp</b>: When transaction posted to account with balance update</li>
 * </ul>
 * 
 * <h3>Foreign Key Relationships</h3>
 * <p>The {@code cardNumber} field establishes a foreign key relationship to the Card entity,
 * enabling transaction-to-card-to-account navigation for balance updates and transaction history queries.</p>
 * 
 * <h3>Lookup Table Relationships</h3>
 * <ul>
 *   <li><b>typeCode</b>: References transaction_type table (e.g., "01"=purchase, "02"=cash advance, "03"=payment)</li>
 *   <li><b>categoryCode</b>: References transaction_category table (retail, grocery, gas, dining, etc.)</li>
 * </ul>
 * 
 * <h2>Pagination Requirements</h2>
 * <p>Transaction lists return 10 transactions per page matching BMS COTRN00M.bms screen display.
 * Service layer uses Spring Data JPA {@code Pageable} with page size 10 for consistent pagination behavior.</p>
 * 
 * <h2>Date Range Filtering</h2>
 * <p>Transaction queries support date range filtering by {@code originationTimestamp} for report generation
 * and statement processing, enabling queries like "all transactions between 2024-01-01 and 2024-01-31".</p>
 * 
 * <h2>BMS Screen Correspondence</h2>
 * <p>This DTO replaces data displayed on three BMS mapsets:</p>
 * <ul>
 *   <li><b>COTRN00M.bms</b>: Transaction list screen with PF7/PF8 pagination → replaced by HTTP query parameters ?page=0&size=10</li>
 *   <li><b>COTRN01M.bms</b>: Transaction detail view displaying full transaction information</li>
 *   <li><b>COTRN02M.bms</b>: Transaction add form for manual transaction entry</li>
 * </ul>
 * 
 * <h2>COBOL Program Transformation</h2>
 * <p>This DTO supports transformation of three COBOL transaction programs:</p>
 * <ul>
 *   <li><b>COTRN00C.cbl (CT00)</b>: Transaction list with VSAM STARTBR/READNEXT browse
 *       → TransactionRepository.findByCardNumberOrderByOriginationTimestampDesc(cardNumber, pageable)</li>
 *   <li><b>COTRN01C.cbl (CT01)</b>: Transaction detail READ
 *       → TransactionRepository.findById(transactionId)</li>
 *   <li><b>COTRN02C.cbl (CT02)</b>: Transaction add validation and WRITE with account balance update
 *       → TransactionRepository.save() with @Transactional boundary ensuring atomic operation</li>
 * </ul>
 * 
 * <h2>REST API Usage</h2>
 * <p>This response DTO serves the following endpoints:</p>
 * <ul>
 *   <li><b>GET /api/transactions</b>: Returns paginated list via TransactionListService.getTransactionsByCard()</li>
 *   <li><b>GET /api/transactions/{id}</b>: Returns single transaction via TransactionViewService.getTransactionDetails()</li>
 *   <li><b>POST /api/transactions</b>: Returns newly created transaction via TransactionAddService.addTransaction()</li>
 * </ul>
 * 
 * <h2>Service Layer Integration</h2>
 * <p>Service methods that return this DTO type:</p>
 * <ul>
 *   <li>TransactionListService.getTransactionsByCard() → Page&lt;TransactionResponse&gt;</li>
 *   <li>TransactionViewService.getTransactionDetails() → TransactionResponse</li>
 *   <li>TransactionAddService.addTransaction() → TransactionResponse</li>
 *   <li>ReportGenerationService.getTransactionReport() → List&lt;TransactionResponse&gt;</li>
 * </ul>
 * 
 * <h2>React Component Integration</h2>
 * <p>This DTO provides data for React components:</p>
 * <ul>
 *   <li>TransactionListComponent.jsx: Displays paginated transaction list with 10 per page</li>
 *   <li>TransactionViewComponent.jsx: Displays full transaction details including merchant information</li>
 *   <li>TransactionAddComponent.jsx: Shows newly added transaction confirmation</li>
 * </ul>
 * 
 * <h2>CICS Transaction Processing to RESTful HTTP</h2>
 * <p>This DTO transforms CICS transaction processing to RESTful HTTP operations:</p>
 * <ul>
 *   <li>CICS EXEC READ TRANSACT → GET /api/transactions/{id}</li>
 *   <li>CICS EXEC WRITE TRANSACT → POST /api/transactions</li>
 *   <li>CICS EXEC STARTBR/READNEXT → GET /api/transactions with pagination parameters</li>
 *   <li>CICS SYNCPOINT → @Transactional method completion with automatic commit</li>
 * </ul>
 * 
 * <h2>Data Integrity and Referential Integrity</h2>
 * <p>The {@code cardNumber} field maintains referential integrity through foreign key constraints
 * to the Card entity, ensuring all transactions reference valid cards and preventing orphaned transaction records.</p>
 * 
 * <h2>PostgreSQL Persistence</h2>
 * <p>This DTO represents data from the PostgreSQL transaction table with composite indexes
 * matching VSAM TRANSACT file access patterns, supporting efficient queries by card number,
 * date range, and transaction type.</p>
 *
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * 
 * @see com.carddemo.entity.Transaction
 * @see com.carddemo.service.transaction.TransactionListService
 * @see com.carddemo.service.transaction.TransactionViewService
 * @see com.carddemo.service.transaction.TransactionAddService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "transactionId",
    "typeCode",
    "categoryCode",
    "source",
    "description",
    "amount",
    "merchantId",
    "merchantName",
    "merchantCity",
    "merchantZip",
    "cardNumber",
    "originationTimestamp",
    "processingTimestamp"
})
public class TransactionResponse {

    /**
     * Unique 16-character transaction identifier serving as primary key.
     * Maps from CVTRA05Y.cpy TRAN-ID (PIC X(16)).
     * 
     * <p>This identifier uniquely identifies each transaction in the system and is used
     * for transaction detail retrieval and audit trail tracking.</p>
     * 
     * <p>Example: "0000000000000001", "TXN2024010112345"</p>
     */
    @JsonProperty("transactionId")
    private String transactionId;

    /**
     * Transaction type code referencing the transaction_type lookup table.
     * Maps from CVTRA05Y.cpy TRAN-TYPE-CD (PIC X(02)).
     * 
     * <p>Valid type codes include:</p>
     * <ul>
     *   <li>"01" - Purchase transaction</li>
     *   <li>"02" - Cash advance</li>
     *   <li>"03" - Payment/credit</li>
     *   <li>"04" - Refund</li>
     *   <li>"05" - Fee/charge</li>
     * </ul>
     * 
     * <p>This code determines transaction processing rules and account impact direction
     * (debit vs credit).</p>
     */
    @JsonProperty("typeCode")
    private String typeCode;

    /**
     * Transaction category code referencing the transaction_category lookup table.
     * Maps from CVTRA05Y.cpy TRAN-CAT-CD (PIC 9(04)).
     * 
     * <p>Category codes classify transactions for reporting and spending analysis:</p>
     * <ul>
     *   <li>1000 - Retail purchases</li>
     *   <li>2000 - Grocery stores</li>
     *   <li>3000 - Gas stations</li>
     *   <li>4000 - Dining and restaurants</li>
     *   <li>5000 - Travel and entertainment</li>
     *   <li>6000 - Healthcare</li>
     *   <li>7000 - Utilities</li>
     * </ul>
     * 
     * <p>Used for transaction categorization in monthly statements and spending reports.</p>
     */
    @JsonProperty("categoryCode")
    private Integer categoryCode;

    /**
     * Transaction source indicating origin of the transaction.
     * Maps from CVTRA05Y.cpy TRAN-SOURCE (PIC X(10)).
     * 
     * <p>Valid source values:</p>
     * <ul>
     *   <li>"POS" - Point of sale terminal</li>
     *   <li>"ATM" - Automated teller machine</li>
     *   <li>"ONLINE" - E-commerce transaction</li>
     *   <li>"PHONE" - Phone order</li>
     *   <li>"MAIL" - Mail order</li>
     *   <li>"RECURRING" - Recurring payment</li>
     * </ul>
     * 
     * <p>Source information helps identify transaction channel for fraud detection
     * and customer service inquiries.</p>
     */
    @JsonProperty("source")
    private String source;

    /**
     * Human-readable transaction description containing merchant and purchase details.
     * Maps from CVTRA05Y.cpy TRAN-DESC (PIC X(100)).
     * 
     * <p>This field contains descriptive text displayed to cardholders on statements
     * and transaction history screens, helping them identify specific purchases.</p>
     * 
     * <p>Example: "WALMART SUPERCENTER #1234 GROCERY PURCHASE"</p>
     * <p>Example: "SHELL GAS STATION FUEL PURCHASE"</p>
     */
    @JsonProperty("description")
    private String description;

    /**
     * Transaction amount in US dollars with exact 2 decimal place precision.
     * Maps from CVTRA05Y.cpy TRAN-AMT (PIC S9(09)V99).
     * 
     * <p><b>CRITICAL:</b> This field uses {@link BigDecimal} with scale=2 to preserve exact
     * COBOL COMP-3 packed decimal arithmetic precision. The {@link JsonFormat} annotation
     * with shape=STRING ensures the amount is serialized as a JSON string (e.g., "123.45")
     * instead of a JSON number, preventing precision loss in JavaScript clients.</p>
     * 
     * <p>All monetary calculations MUST use BigDecimal methods:</p>
     * <pre>
     * balance = balance.add(amount);                    // Addition
     * balance = balance.subtract(amount);               // Subtraction
     * balance = balance.multiply(rate);                 // Multiplication
     * balance = balance.divide(divisor, RoundingMode.HALF_UP);  // Division
     * balance = balance.setScale(2, RoundingMode.HALF_UP);      // Explicit rounding
     * </pre>
     * 
     * <p><b>NEVER</b> use float or double arithmetic: {@code balance += amount} ❌</p>
     * <p><b>NEVER</b> convert to double for calculations: {@code amount.doubleValue()} ❌</p>
     * 
     * <p>The amount field supports positive values for purchases/debits and negative values
     * for payments/credits, matching the signed COBOL PIC S9(09)V99 definition.</p>
     * 
     * <p>Maximum value: $999,999,999.99 (nine digits before decimal, two after)</p>
     */
    @JsonProperty("amount")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal amount;

    /**
     * Merchant identifier uniquely identifying the merchant location.
     * Maps from CVTRA05Y.cpy TRAN-MERCHANT-ID (PIC 9(09)).
     * 
     * <p>This numeric identifier links the transaction to merchant master data containing
     * full merchant details including name, address, and merchant category code (MCC).</p>
     * 
     * <p>Used for merchant analysis, fraud detection, and transaction dispute resolution.</p>
     */
    @JsonProperty("merchantId")
    private Long merchantId;

    /**
     * Merchant business name as it appears on cardholder statements.
     * Maps from CVTRA05Y.cpy TRAN-MERCHANT-NAME (PIC X(50)).
     * 
     * <p>This field contains the doing-business-as (DBA) name displayed to cardholders,
     * helping them identify merchants on their transaction history and monthly statements.</p>
     * 
     * <p>Example: "WALMART SUPERCENTER"</p>
     * <p>Example: "AMAZON.COM"</p>
     * <p>Example: "STARBUCKS #12345"</p>
     */
    @JsonProperty("merchantName")
    private String merchantName;

    /**
     * Merchant city location.
     * Maps from CVTRA05Y.cpy TRAN-MERCHANT-CITY (PIC X(50)).
     * 
     * <p>City where the merchant terminal is located, displayed on transaction
     * history for geographic context and travel expense tracking.</p>
     * 
     * <p>Example: "SEATTLE"</p>
     * <p>Example: "NEW YORK"</p>
     */
    @JsonProperty("merchantCity")
    private String merchantCity;

    /**
     * Merchant ZIP/postal code.
     * Maps from CVTRA05Y.cpy TRAN-MERCHANT-ZIP (PIC X(10)).
     * 
     * <p>ZIP code or postal code of the merchant location, used for geographic
     * analysis and fraud detection (detecting transactions from unexpected locations).</p>
     * 
     * <p>Example: "98101"</p>
     * <p>Example: "10001-1234"</p>
     */
    @JsonProperty("merchantZip")
    private String merchantZip;

    /**
     * Masked credit card number showing only the last 4 digits for PCI DSS compliance.
     * Maps from CVTRA05Y.cpy TRAN-CARD-NUM (PIC X(16)).
     * 
     * <p><b>CRITICAL SECURITY REQUIREMENT:</b> This field contains a masked representation
     * of the card PAN (Primary Account Number) showing only the last 4 digits with asterisks
     * masking the first 12 digits. The full 16-digit PAN is NEVER exposed in API responses
     * to comply with PCI DSS requirements.</p>
     * 
     * <p>Masking format: "************1234" (12 asterisks + last 4 digits)</p>
     * 
     * <p>The service layer is responsible for masking the card number before populating
     * this DTO. The full card number is stored in the database and used for transaction
     * processing, but external API responses must always use the masked format.</p>
     * 
     * <p>This field establishes the foreign key relationship to the Card entity, enabling
     * transaction-to-card-to-account navigation for balance updates and transaction history queries.</p>
     */
    @JsonProperty("cardNumber")
    private String cardNumber;

    /**
     * ISO 8601 timestamp when the transaction originated at the merchant terminal.
     * Maps from CVTRA05Y.cpy TRAN-ORIG-TS (PIC X(26)).
     * 
     * <p>This timestamp captures when the cardholder initiated the transaction at the
     * point of sale, ATM, or online checkout. It represents the actual transaction time
     * from the cardholder's perspective and is used for:</p>
     * <ul>
     *   <li>Transaction history chronological display</li>
     *   <li>Date range filtering for reports and statements</li>
     *   <li>Fraud detection (multiple transactions in short time span)</li>
     *   <li>Dispute resolution (matching transaction time to cardholder activity)</li>
     * </ul>
     * 
     * <p>Format: yyyy-MM-dd'T'HH:mm:ss (e.g., "2024-01-15T14:30:00")</p>
     * 
     * <p>The COBOL PIC X(26) alphanumeric field is transformed to Java {@link LocalDateTime}
     * for type-safe date/time operations and ISO 8601 JSON serialization.</p>
     */
    @JsonProperty("originationTimestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime originationTimestamp;

    /**
     * ISO 8601 timestamp when the transaction posted to the account with balance update.
     * Maps from CVTRA05Y.cpy TRAN-PROC-TS (PIC X(26)).
     * 
     * <p>This timestamp captures when the transaction was processed by the card processing
     * system and posted to the cardholder's account with a corresponding balance update.
     * The processing timestamp may differ from the origination timestamp due to:</p>
     * <ul>
     *   <li>Batch processing delays (end-of-day settlement)</li>
     *   <li>Authorization holds converting to posted transactions</li>
     *   <li>Manual review for fraud or high-value transactions</li>
     *   <li>Network communication delays</li>
     * </ul>
     * 
     * <p>This timestamp is used for:</p>
     * <ul>
     *   <li>Account balance reconciliation</li>
     *   <li>Statement closing date calculations</li>
     *   <li>Interest calculation cutoff times</li>
     *   <li>Audit trail for posted transactions</li>
     * </ul>
     * 
     * <p>Format: yyyy-MM-dd'T'HH:mm:ss (e.g., "2024-01-15T23:45:00")</p>
     * 
     * <p>The COBOL PIC X(26) alphanumeric field is transformed to Java {@link LocalDateTime}
     * providing type-safe date/time operations and consistent ISO 8601 JSON serialization.</p>
     * 
     * <p>The processing timestamp is set when the transaction moves from "pending" to "posted"
     * status, which occurs during the COBOL batch job CBTRN02C (Daily Transaction Processing)
     * or the equivalent Spring Batch DailyTransactionProcessingJob.</p>
     */
    @JsonProperty("processingTimestamp")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime processingTimestamp;
}
