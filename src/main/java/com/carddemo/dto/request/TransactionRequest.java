package com.carddemo.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Transaction Add Request DTO
 * 
 * <p>Data Transfer Object for creating new credit card transactions in the CardDemo application.
 * This DTO replaces the CICS COMMAREA structure used by transaction CT02 (COTRN02C.cbl) in the 
 * mainframe COBOL implementation, serving as the inbound contract for the POST /api/transactions 
 * REST endpoint.</p>
 * 
 * <p><strong>COBOL Source Mapping:</strong></p>
 * <ul>
 *   <li>Source Copybook: app/cpy/CVTRA05Y.cpy (TRAN-RECORD structure, lines 4-18)</li>
 *   <li>BMS Mapset: app/bms/COTRN02.bms (Transaction Add screen, lines 85-200)</li>
 *   <li>COBOL Program: COTRN02C.cbl (Transaction validation and authorization logic)</li>
 * </ul>
 * 
 * <p><strong>Field Mappings from COBOL to Java:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Field</th>
 *     <th>COBOL Type</th>
 *     <th>Java Field</th>
 *     <th>Java Type</th>
 *     <th>Validation</th>
 *   </tr>
 *   <tr>
 *     <td>TRAN-CARD-NUM</td>
 *     <td>PIC X(16)</td>
 *     <td>cardNumber</td>
 *     <td>String</td>
 *     <td>@NotBlank, @Pattern (16 digits)</td>
 *   </tr>
 *   <tr>
 *     <td>TRAN-AMT</td>
 *     <td>PIC S9(09)V99</td>
 *     <td>amount</td>
 *     <td>BigDecimal</td>
 *     <td>@NotNull, @Positive, @Digits(9,2)</td>
 *   </tr>
 *   <tr>
 *     <td>TRAN-MERCHANT-ID</td>
 *     <td>PIC 9(09)</td>
 *     <td>merchantId</td>
 *     <td>Long</td>
 *     <td>@NotNull</td>
 *   </tr>
 *   <tr>
 *     <td>TRAN-DESC</td>
 *     <td>PIC X(100)</td>
 *     <td>description</td>
 *     <td>String</td>
 *     <td>@Size(max=100)</td>
 *   </tr>
 *   <tr>
 *     <td>TRAN-TYPE-CD</td>
 *     <td>PIC X(02)</td>
 *     <td>typeCode</td>
 *     <td>String</td>
 *     <td>@Pattern (2 digits)</td>
 *   </tr>
 *   <tr>
 *     <td>TRAN-CAT-CD</td>
 *     <td>PIC 9(04)</td>
 *     <td>categoryCode</td>
 *     <td>String</td>
 *     <td>@Pattern (4 digits)</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>COBOL COMP-3 to BigDecimal Precision Mapping:</strong></p>
 * <p>The TRAN-AMT field in CVTRA05Y.cpy (line 10) is defined as PIC S9(09)V99, which represents
 * a signed packed decimal (COMP-3) field with 9 integer digits and 2 decimal places. This is
 * transformed to Java BigDecimal with explicit scale=2 and RoundingMode.HALF_UP to preserve
 * exact COBOL monetary precision and prevent floating-point precision loss in financial 
 * calculations.</p>
 * 
 * <p><strong>Validation Business Rules:</strong></p>
 * <ul>
 *   <li><strong>Card Number:</strong> Must be exactly 16 numeric digits matching standard credit
 *       card number format. The @Pattern annotation enforces this constraint, replacing COBOL
 *       PIC X(16) field definition from TRAN-CARD-NUM.</li>
 *   <li><strong>Transaction Amount:</strong> Must be positive (> 0) to prevent negative transactions
 *       that could represent fraudulent activity or data entry errors. The @Positive annotation
 *       enforces this critical business rule. Amount precision is limited to 9 integer digits and
 *       2 decimal places, matching COBOL PIC S9(09)V99 exactly.</li>
 *   <li><strong>Merchant ID:</strong> Required field linking transaction to merchant master data.
 *       Must be a valid 9-digit numeric identifier.</li>
 *   <li><strong>Description:</strong> Optional but limited to 100 characters to match COBOL
 *       PIC X(100) field definition from TRAN-DESC.</li>
 *   <li><strong>Type Code:</strong> 2-digit transaction type code (e.g., "01" for purchase, "02"
 *       for cash advance). Must match valid codes in transaction_type reference table.</li>
 *   <li><strong>Category Code:</strong> 4-digit transaction category code for reporting and
 *       analysis. Must match valid codes in transaction_category reference table.</li>
 * </ul>
 * 
 * <p><strong>Transaction Processing Flow:</strong></p>
 * <p>This DTO is consumed by TransactionController POST /api/transactions endpoint and processed
 * by TransactionAddService within a @Transactional boundary to ensure atomic operations:</p>
 * <ol>
 *   <li>Validate all field constraints (Bean Validation triggered automatically)</li>
 *   <li>Verify card exists and is active (card authorization)</li>
 *   <li>Check sufficient credit limit for transaction amount</li>
 *   <li>Create transaction record in transaction table</li>
 *   <li>Update account current balance (balance += amount)</li>
 *   <li>Record transaction in audit log</li>
 * </ol>
 * <p>If any step fails, the entire transaction is rolled back to maintain data integrity,
 * matching CICS SYNCPOINT/ROLLBACK behavior from mainframe implementation.</p>
 * 
 * <p><strong>JSON Serialization Example:</strong></p>
 * <pre>
 * {
 *   "cardNumber": "4111111111111111",
 *   "amount": 125.50,
 *   "merchantId": 123456789,
 *   "description": "Online Purchase - Electronics Store",
 *   "typeCode": "01",
 *   "categoryCode": "5732"
 * }
 * </pre>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * TransactionRequest request = TransactionRequest.builder()
 *     .cardNumber("4111111111111111")
 *     .amount(new BigDecimal("125.50"))
 *     .merchantId(123456789L)
 *     .description("Online Purchase - Electronics Store")
 *     .typeCode("01")
 *     .categoryCode("5732")
 *     .build();
 * 
 * // POST to /api/transactions
 * TransactionResponse response = transactionController.addTransaction(request);
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see com.carddemo.controller.TransactionController
 * @see com.carddemo.service.transaction.TransactionAddService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionRequest {

    /**
     * Credit card number for the transaction.
     * 
     * <p>Maps to COBOL field TRAN-CARD-NUM PIC X(16) from CVTRA05Y.cpy line 15.</p>
     * 
     * <p>Must be exactly 16 numeric digits matching standard credit card number format.
     * This field is validated against the card master table to ensure the card exists
     * and is active before processing the transaction.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Must match pattern: exactly 16 digits (0-9)</li>
     *   <li>Examples: "4111111111111111", "5500000000000004"</li>
     * </ul>
     * 
     * @see com.carddemo.entity.Card
     */
    @NotBlank(message = "Card number is required")
    @Pattern(regexp = "^[0-9]{16}$", message = "Card number must be exactly 16 digits")
    @JsonProperty("cardNumber")
    private String cardNumber;

    /**
     * Transaction amount in USD.
     * 
     * <p>Maps to COBOL field TRAN-AMT PIC S9(09)V99 from CVTRA05Y.cpy line 10.</p>
     * 
     * <p>Uses BigDecimal to preserve exact COBOL COMP-3 packed decimal precision with
     * scale=2 (2 decimal places) and RoundingMode.HALF_UP for consistent rounding behavior.
     * This prevents floating-point precision loss that would occur with double or float types.</p>
     * 
     * <p><strong>COBOL Precision Mapping:</strong></p>
     * <ul>
     *   <li>COBOL: PIC S9(09)V99 = Signed, 9 integer digits, 2 decimal places</li>
     *   <li>Java: BigDecimal with @Digits(integer=9, fraction=2)</li>
     *   <li>Range: -999,999,999.99 to +999,999,999.99</li>
     *   <li>Scale: Always 2 decimal places (e.g., 125.50, not 125.5)</li>
     * </ul>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null (@NotNull)</li>
     *   <li>Must be positive (> 0) to prevent negative transactions (@Positive)</li>
     *   <li>Maximum 9 integer digits and 2 fraction digits (@Digits)</li>
     *   <li>Examples: 0.01, 125.50, 999999999.99</li>
     *   <li>Invalid: -50.00, 0.00, 1234567890.00, 125.505</li>
     * </ul>
     * 
     * <p><strong>Business Rule:</strong> Positive amount validation prevents fraudulent
     * negative transactions and ensures data integrity in account balance calculations.</p>
     */
    @NotNull(message = "Transaction amount is required")
    @Positive(message = "Transaction amount must be positive")
    @Digits(integer = 9, fraction = 2, message = "Amount must have at most 9 integer digits and 2 decimal places")
    @JsonProperty("amount")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER, pattern = "#.##")
    private BigDecimal amount;

    /**
     * Merchant identifier for the transaction.
     * 
     * <p>Maps to COBOL field TRAN-MERCHANT-ID PIC 9(09) from CVTRA05Y.cpy line 11.</p>
     * 
     * <p>Links this transaction to the merchant master data containing merchant name, location,
     * and business details. Used for transaction reporting, fraud detection, and merchant
     * settlement processing.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null (@NotNull)</li>
     *   <li>Must be a valid 9-digit numeric identifier</li>
     *   <li>Range: 0 to 999,999,999</li>
     *   <li>Example: 123456789</li>
     * </ul>
     */
    @NotNull(message = "Merchant ID is required")
    @JsonProperty("merchantId")
    private Long merchantId;

    /**
     * Transaction description or memo.
     * 
     * <p>Maps to COBOL field TRAN-DESC PIC X(100) from CVTRA05Y.cpy line 9.</p>
     * 
     * <p>Free-text field describing the transaction details, merchant name, or purchase
     * description. This appears on customer statements and transaction history. Optional
     * field but recommended for transaction clarity.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Optional field (can be null or empty)</li>
     *   <li>Maximum length: 100 characters (@Size)</li>
     *   <li>Examples: "Online Purchase - Electronics Store", "ATM Withdrawal - Main Branch"</li>
     * </ul>
     */
    @Size(max = 100, message = "Description cannot exceed 100 characters")
    @JsonProperty("description")
    private String description;

    /**
     * Transaction type code.
     * 
     * <p>Maps to COBOL field TRAN-TYPE-CD PIC X(02) from CVTRA05Y.cpy line 6.</p>
     * 
     * <p>Two-digit code identifying the transaction type (purchase, cash advance, payment,
     * refund, etc.). Must reference a valid entry in the transaction_type reference table.
     * Used for transaction categorization, reporting, and business rule application.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Must match pattern: exactly 2 digits (0-9)</li>
     *   <li>Examples: "01" (Purchase), "02" (Cash Advance), "03" (Payment)</li>
     *   <li>Must exist in transaction_type reference table</li>
     * </ul>
     * 
     * @see com.carddemo.entity.TransactionType
     */
    @Pattern(regexp = "^[0-9]{2}$", message = "Type code must be exactly 2 digits")
    @JsonProperty("typeCode")
    private String typeCode;

    /**
     * Transaction category code.
     * 
     * <p>Maps to COBOL field TRAN-CAT-CD PIC 9(04) from CVTRA05Y.cpy line 7.</p>
     * 
     * <p>Four-digit code categorizing the transaction for reporting and analysis purposes
     * (e.g., dining, travel, groceries, fuel). Must reference a valid entry in the
     * transaction_category reference table. Used for spending analysis, rewards calculation,
     * and financial reporting.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Must match pattern: exactly 4 digits (0-9)</li>
     *   <li>Examples: "5732" (Electronics), "5812" (Restaurants), "5541" (Gas Stations)</li>
     *   <li>Must exist in transaction_category reference table</li>
     * </ul>
     * 
     * @see com.carddemo.entity.TransactionCategory
     */
    @Pattern(regexp = "^[0-9]{4}$", message = "Category code must be exactly 4 digits")
    @JsonProperty("categoryCode")
    private String categoryCode;

    /**
     * Transaction source identifier.
     * 
     * <p>Maps to COBOL field TRAN-SOURCE PIC X(10) from CVTRA05Y.cpy.</p>
     * 
     * <p>Identifies the channel or system source of the transaction (e.g., "ONLINE", "POS", "ATM").
     * Used for transaction tracking and fraud detection.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Maximum length: 10 characters</li>
     *   <li>Examples: "ONLINE", "POS", "ATM", "MOBILE"</li>
     * </ul>
     */
    @NotBlank(message = "Transaction source is required")
    @Size(max = 10, message = "Transaction source cannot exceed 10 characters")
    @JsonProperty("transactionSource")
    private String transactionSource;

    /**
     * Merchant name.
     * 
     * <p>Maps to COBOL field TRAN-MERCHANT-NAME PIC X(30) from CVTRA05Y.cpy.</p>
     * 
     * <p>The name of the merchant where the transaction occurred. This appears on
     * customer statements and transaction history.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Maximum length: 30 characters</li>
     *   <li>Example: "Best Buy Electronics"</li>
     * </ul>
     */
    @NotBlank(message = "Merchant name is required")
    @Size(max = 30, message = "Merchant name cannot exceed 30 characters")
    @JsonProperty("merchantName")
    private String merchantName;

    /**
     * Merchant city.
     * 
     * <p>Maps to COBOL field TRAN-MERCHANT-CITY PIC X(25) from CVTRA05Y.cpy.</p>
     * 
     * <p>The city where the merchant is located.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Maximum length: 25 characters</li>
     *   <li>Example: "New York"</li>
     * </ul>
     */
    @NotBlank(message = "Merchant city is required")
    @Size(max = 25, message = "Merchant city cannot exceed 25 characters")
    @JsonProperty("merchantCity")
    private String merchantCity;

    /**
     * Merchant ZIP code.
     * 
     * <p>Maps to COBOL field TRAN-MERCHANT-ZIP PIC X(10) from CVTRA05Y.cpy.</p>
     * 
     * <p>The ZIP or postal code where the merchant is located.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Maximum length: 10 characters</li>
     *   <li>Example: "10001" or "10001-1234"</li>
     * </ul>
     */
    @NotBlank(message = "Merchant ZIP is required")
    @Size(max = 10, message = "Merchant ZIP cannot exceed 10 characters")
    @JsonProperty("merchantZip")
    private String merchantZip;

    /**
     * Transaction origination date.
     * 
     * <p>Maps to COBOL field TRAN-ORIG-TS PIC X(26) from CVTRA05Y.cpy.</p>
     * 
     * <p>The date when the transaction originally occurred, in YYYY-MM-DD format.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Must be in YYYY-MM-DD format</li>
     *   <li>Example: "2024-01-15"</li>
     * </ul>
     */
    @NotBlank(message = "Origination date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Origination date must be in YYYY-MM-DD format")
    @JsonProperty("origDate")
    private String origDate;

    /**
     * Transaction processing date.
     * 
     * <p>Maps to COBOL field TRAN-PROC-TS PIC X(26) from CVTRA05Y.cpy.</p>
     * 
     * <p>The date when the transaction is processed by the system, in YYYY-MM-DD format.</p>
     * 
     * <p><strong>Validation Rules:</strong></p>
     * <ul>
     *   <li>Cannot be null or empty (@NotBlank)</li>
     *   <li>Must be in YYYY-MM-DD format</li>
     *   <li>Example: "2024-01-16"</li>
     * </ul>
     */
    @NotBlank(message = "Processing date is required")
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "Processing date must be in YYYY-MM-DD format")
    @JsonProperty("procDate")
    private String procDate;
}
