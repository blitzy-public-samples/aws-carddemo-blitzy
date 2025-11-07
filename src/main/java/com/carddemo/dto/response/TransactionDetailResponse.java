package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Transaction detail view response DTO containing comprehensive transaction information with joined card and account details
 * for single transaction display.
 * 
 * <p>This extended response DTO builds upon {@link TransactionResponse} by adding enriched relationship data through
 * nested {@link CardInfo} and {@link AccountInfo} objects. It provides complete transaction context including cardholder name,
 * account balance, and credit limit through JPA eager fetch joins, enabling React TransactionViewComponent to display
 * transaction details with related card and account information in a unified view without additional API calls.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This response DTO transforms the COBOL COTRN01C.cbl transaction view program logic which combines data from
 * three separate VSAM file records into a single screen display:</p>
 * 
 * <h3>Transaction Record (CVTRA05Y.cpy TRAN-RECORD - Base fields from parent TransactionResponse)</h3>
 * <pre>
 * 01  TRAN-RECORD.                                    (RECLN = 350)
 *     05  TRAN-ID                  PIC X(16).         → transactionId (inherited)
 *     05  TRAN-TYPE-CD             PIC X(02).         → typeCode (inherited)
 *     05  TRAN-CAT-CD              PIC 9(04).         → categoryCode (inherited)
 *     05  TRAN-SOURCE              PIC X(10).         → source (inherited)
 *     05  TRAN-DESC                PIC X(100).        → description (inherited)
 *     05  TRAN-AMT                 PIC S9(09)V99.     → amount BigDecimal scale=2 (inherited)
 *     05  TRAN-MERCHANT-ID         PIC 9(09).         → merchantId (inherited)
 *     05  TRAN-MERCHANT-NAME       PIC X(50).         → merchantName (inherited)
 *     05  TRAN-MERCHANT-CITY       PIC X(50).         → merchantCity (inherited)
 *     05  TRAN-MERCHANT-ZIP        PIC X(10).         → merchantZip (inherited)
 *     05  TRAN-CARD-NUM            PIC X(16).         → cardNumber masked (inherited)
 *     05  TRAN-ORIG-TS             PIC X(26).         → originationTimestamp (inherited)
 *     05  TRAN-PROC-TS             PIC X(26).         → processingTimestamp (inherited)
 * </pre>
 * 
 * <h3>Card Record (CVACT02Y.cpy CARD-RECORD - Nested CardInfo object)</h3>
 * <pre>
 * 01  CARD-RECORD.                                    (RECLN = 150)
 *     05  CARD-EMBOSSED-NAME       PIC X(50).         → cardInfo.embossedName
 *     05  CARD-EXPIRAION-DATE      PIC X(10).         → cardInfo.expirationDate
 *     05  CARD-ACTIVE-STATUS       PIC X(01).         → cardInfo.activeStatus
 * </pre>
 * 
 * <h3>Account Record (CVACT01Y.cpy ACCOUNT-RECORD - Nested AccountInfo object)</h3>
 * <pre>
 * 01  ACCOUNT-RECORD.                                 (RECLN = 300)
 *     05  ACCT-ID                  PIC 9(11).         → accountInfo.accountId
 *     05  ACCT-CURR-BAL            PIC S9(10)V99.     → accountInfo.currentBalance BigDecimal scale=2
 *     05  ACCT-CREDIT-LIMIT        PIC S9(10)V99.     → accountInfo.creditLimit BigDecimal scale=2
 *     05  ACCT-ACTIVE-STATUS       PIC X(01).         → accountInfo.activeStatus
 * </pre>
 * 
 * <h3>Cross-Reference File Relationships (CVACT03Y.cpy XREF, CXACAIX)</h3>
 * <p>In the COBOL mainframe implementation, COTRN01C.cbl performs sequential file reads across three VSAM KSDS files
 * using cross-reference keys to join transaction → card → account data. This Java implementation replaces VSAM
 * cross-reference files with PostgreSQL foreign key relationships and JPA entity joins:</p>
 * <ul>
 *   <li><b>VSAM XREF Pattern</b>: READ TRANSACT BY TRAN-ID → READ CARDDAT BY TRAN-CARD-NUM → READ ACCTDAT BY CARD-ACCT-ID</li>
 *   <li><b>JPA Join Pattern</b>: SELECT t, c, a FROM Transaction t LEFT JOIN t.card c LEFT JOIN c.account a WHERE t.transactionId = ?</li>
 * </ul>
 * 
 * <h2>Critical Implementation Details</h2>
 * 
 * <h3>Nested Object Structure for Enriched Response</h3>
 * <p>The nested {@link CardInfo} and {@link AccountInfo} objects provide complete context for transaction display without
 * requiring the React frontend to make separate API calls for card and account details. This optimization reduces network
 * round-trips from 3 separate requests to 1 comprehensive response:</p>
 * <ul>
 *   <li><b>Without enrichment</b>: GET /api/transactions/{id} → GET /api/cards/{cardNumber} → GET /api/accounts/{accountId}</li>
 *   <li><b>With enrichment</b>: GET /api/transactions/{id} returns all data in single response</li>
 * </ul>
 * 
 * <h3>BigDecimal Precision for Account Monetary Fields</h3>
 * <p>The {@link AccountInfo} nested object contains monetary fields {@code currentBalance} and {@code creditLimit}
 * which use {@link BigDecimal} with scale=2 and {@link JsonFormat} shape=STRING to preserve exact COBOL COMP-3
 * packed decimal precision from CVACT01Y.cpy:</p>
 * <ul>
 *   <li><b>ACCT-CURR-BAL PIC S9(10)V99</b>: Maps to BigDecimal(12, 2) serialized as JSON string "99999999.99"</li>
 *   <li><b>ACCT-CREDIT-LIMIT PIC S9(10)V99</b>: Maps to BigDecimal(12, 2) serialized as JSON string "50000.00"</li>
 *   <li>String serialization prevents JavaScript Number precision loss for monetary calculations</li>
 * </ul>
 * 
 * <h3>JsonInclude.NON_NULL for Optional Nested Objects</h3>
 * <p>The class-level {@link JsonInclude}(Include.NON_NULL) annotation excludes null {@code cardInfo} or {@code accountInfo}
 * objects from JSON serialization, reducing response payload size when optional joined data is unavailable:</p>
 * <ul>
 *   <li>If transaction exists but card is deleted: Response omits cardInfo field entirely</li>
 *   <li>If card exists but account is closed: Response omits accountInfo field entirely</li>
 *   <li>Normal case: Both nested objects present providing complete transaction context</li>
 * </ul>
 * 
 * <h3>Lookup Table Description Fields</h3>
 * <p>The {@code categoryDescription} and {@code typeDescription} fields provide human-readable display names for
 * transaction category and type codes, eliminating the need for separate reference data API calls:</p>
 * <ul>
 *   <li><b>categoryCode="0001" + categoryDescription="Retail Purchase"</b>: From CVTRA04Y.cpy TRAN-CAT-TYPE-DESC</li>
 *   <li><b>typeCode="01" + typeDescription="Point of Sale Purchase"</b>: From CVTRA03Y.cpy TRAN-TYPE-DESC</li>
 * </ul>
 * 
 * <h2>JPA Eager Fetch Join Strategy</h2>
 * <p>TransactionViewService constructs this response using JPA JPQL query with explicit fetch joins to avoid N+1 query problem:</p>
 * <pre>
 * SELECT t, c, a, tc, tt
 * FROM Transaction t
 * LEFT JOIN FETCH t.card c
 * LEFT JOIN FETCH c.account a
 * LEFT JOIN FETCH t.category tc
 * LEFT JOIN FETCH t.type tt
 * WHERE t.transactionId = :transactionId
 * </pre>
 * <p>This single query replaces the COBOL COTRN01C.cbl pattern of sequential file reads across multiple VSAM datasets,
 * providing equivalent data with superior performance through relational database join optimization.</p>
 * 
 * <h2>BMS Screen Correspondence</h2>
 * <p>This DTO replaces data displayed on COTRN01M.bms transaction view screen showing:</p>
 * <ul>
 *   <li><b>Transaction Section</b>: All base TransactionResponse fields (ID, amount, merchant, timestamps)</li>
 *   <li><b>Card Section</b>: Cardholder name, card expiration date, card status</li>
 *   <li><b>Account Section</b>: Account number, current balance, credit limit, account status</li>
 *   <li><b>Navigation</b>: PF3=Back to list, PF5=Return to transaction list → React Router navigation</li>
 * </ul>
 * 
 * <h2>Usage in TransactionViewService</h2>
 * <p>The service layer method {@code TransactionViewService.getTransactionDetail(String transactionId)} constructs this
 * response by:</p>
 * <ol>
 *   <li>Execute JPA fetch join query retrieving Transaction with joined Card, Account, Category, Type entities</li>
 *   <li>Map Transaction entity to TransactionResponse base fields (inherited)</li>
 *   <li>Construct CardInfo nested object from Card entity if present</li>
 *   <li>Construct AccountInfo nested object from Account entity if present</li>
 *   <li>Set categoryDescription from TransactionCategory.description if lookup found</li>
 *   <li>Set typeDescription from TransactionType.description if lookup found</li>
 *   <li>Return enriched TransactionDetailResponse via GET /api/transactions/{id} endpoint</li>
 * </ol>
 * 
 * <h2>REST API Contract</h2>
 * <p><b>Endpoint</b>: GET /api/transactions/{id}</p>
 * <p><b>HTTP Status Codes</b>:</p>
 * <ul>
 *   <li>200 OK: Transaction found and returned with enriched data</li>
 *   <li>404 Not Found: Transaction ID does not exist in database</li>
 *   <li>401 Unauthorized: JWT token missing or invalid</li>
 *   <li>403 Forbidden: User does not have permission to view transaction</li>
 * </ul>
 * 
 * <h2>SuperBuilder Pattern for Inheritance</h2>
 * <p>This class uses Lombok {@link SuperBuilder} annotation enabling fluent builder pattern across the inheritance hierarchy
 * from TransactionResponse. This allows constructing instances with both parent and child fields in a single builder chain:</p>
 * <pre>
 * TransactionDetailResponse response = TransactionDetailResponse.builder()
 *     .transactionId("1234567890123456")  // Parent field
 *     .amount(new BigDecimal("123.45"))   // Parent field
 *     .cardInfo(cardInfoObject)            // Child field
 *     .accountInfo(accountInfoObject)      // Child field
 *     .categoryDescription("Retail")       // Child field
 *     .build();
 * </pre>
 * 
 * @see TransactionResponse Base transaction response with core transaction fields
 * @see com.carddemo.service.transaction.TransactionViewService Service constructing this enriched response
 * @see com.carddemo.controller.TransactionController REST controller exposing GET /api/transactions/{id}
 * @see com.carddemo.entity.Transaction JPA entity representing transaction table
 * @see com.carddemo.entity.Card JPA entity representing card table
 * @see com.carddemo.entity.Account JPA entity representing account table
 * @see com.carddemo.entity.TransactionCategory JPA entity for transaction category lookup
 * @see com.carddemo.entity.TransactionType JPA entity for transaction type lookup
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionDetailResponse extends TransactionResponse {

    /**
     * Card information nested object containing cardholder details associated with the transaction.
     * 
     * <p>This nested object provides card context for transaction display including cardholder name,
     * card expiration date, and card status. Data sourced from Card entity joined via transaction.cardNumber
     * foreign key relationship.</p>
     * 
     * <p><b>COBOL Source</b>: CVACT02Y.cpy CARD-RECORD fields</p>
     * 
     * <p><b>Null Handling</b>: This field will be null if the card referenced by transaction.cardNumber
     * no longer exists in the card table (e.g., deleted card). The @JsonInclude(NON_NULL) class annotation
     * excludes this field from JSON response when null.</p>
     */
    @JsonProperty("cardInfo")
    private CardInfo cardInfo;

    /**
     * Account information nested object containing financial details for the account linked to the card.
     * 
     * <p>This nested object provides account financial context including current balance and credit limit,
     * enabling the transaction view to display available credit and account status alongside transaction details.
     * Data sourced from Account entity joined via card.accountId foreign key relationship.</p>
     * 
     * <p><b>COBOL Source</b>: CVACT01Y.cpy ACCOUNT-RECORD fields</p>
     * 
     * <p><b>Null Handling</b>: This field will be null if the account referenced by card.accountId
     * no longer exists in the account table (e.g., closed account). The @JsonInclude(NON_NULL) class annotation
     * excludes this field from JSON response when null.</p>
     */
    @JsonProperty("accountInfo")
    private AccountInfo accountInfo;

    /**
     * Human-readable description of the transaction category for display purposes.
     * 
     * <p>This field provides the descriptive name corresponding to the {@code categoryCode} field inherited
     * from TransactionResponse. It eliminates the need for separate reference data API calls by including
     * the category description directly in the transaction detail response.</p>
     * 
     * <p><b>COBOL Source</b>: CVTRA04Y.cpy TRAN-CAT-TYPE-DESC PIC X(50)</p>
     * 
     * <p><b>Example Values</b>:</p>
     * <ul>
     *   <li>categoryCode="0001" → categoryDescription="Retail Purchase"</li>
     *   <li>categoryCode="0002" → categoryDescription="Grocery Purchase"</li>
     *   <li>categoryCode="0003" → categoryDescription="Gas Station Purchase"</li>
     * </ul>
     * 
     * <p><b>Null Handling</b>: This field will be null if the categoryCode references a non-existent
     * category in the transaction_category table.</p>
     */
    @JsonProperty("categoryDescription")
    private String categoryDescription;

    /**
     * Human-readable description of the transaction type for display purposes.
     * 
     * <p>This field provides the descriptive name corresponding to the {@code typeCode} field inherited
     * from TransactionResponse. It eliminates the need for separate reference data API calls by including
     * the type description directly in the transaction detail response.</p>
     * 
     * <p><b>COBOL Source</b>: CVTRA03Y.cpy TRAN-TYPE-DESC PIC X(50)</p>
     * 
     * <p><b>Example Values</b>:</p>
     * <ul>
     *   <li>typeCode="01" → typeDescription="Point of Sale Purchase"</li>
     *   <li>typeCode="02" → typeDescription="ATM Cash Advance"</li>
     *   <li>typeCode="03" → typeDescription="Online Payment"</li>
     * </ul>
     * 
     * <p><b>Null Handling</b>: This field will be null if the typeCode references a non-existent
     * type in the transaction_type table.</p>
     */
    @JsonProperty("typeDescription")
    private String typeDescription;

    /**
     * Nested object containing card-specific information for the transaction.
     * 
     * <p>This static nested class encapsulates card details displayed alongside transaction information,
     * providing cardholder context without requiring separate API calls. All fields sourced from CVACT02Y.cpy
     * CARD-RECORD structure.</p>
     * 
     * <h3>COBOL Field Mapping</h3>
     * <pre>
     * CARD-EMBOSSED-NAME     PIC X(50)  → embossedName
     * CARD-EXPIRAION-DATE    PIC X(10)  → expirationDate
     * CARD-ACTIVE-STATUS     PIC X(01)  → activeStatus
     * </pre>
     * 
     * <h3>Usage Example</h3>
     * <pre>
     * CardInfo cardInfo = CardInfo.builder()
     *     .embossedName("JOHN DOE")
     *     .expirationDate(LocalDate.of(2025, 12, 31))
     *     .activeStatus("Y")
     *     .build();
     * </pre>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @lombok.Builder
    public static class CardInfo {

        /**
         * Cardholder name embossed on the physical card.
         * 
         * <p>This field displays the name printed on the card (e.g., "JOHN A DOE"), typically formatted
         * in uppercase letters matching the embossed card name format.</p>
         * 
         * <p><b>COBOL Source</b>: CVACT02Y.cpy CARD-EMBOSSED-NAME PIC X(50)</p>
         * 
         * <p><b>Validation</b>: Maximum length 50 characters</p>
         * 
         * <p><b>Display Format</b>: Uppercase alphanumeric with spaces</p>
         * 
         * @see com.carddemo.entity.Card#getEmbossedName()
         */
        @JsonProperty("embossedName")
        private String embossedName;

        /**
         * Card expiration date in MM/YYYY format.
         * 
         * <p>This field indicates when the card expires and will no longer be valid for transactions.
         * Cards are typically issued with 3-5 year expiration periods from issuance date.</p>
         * 
         * <p><b>COBOL Source</b>: CVACT02Y.cpy CARD-EXPIRAION-DATE PIC X(10)</p>
         * 
         * <p><b>Format</b>: YYYY-MM-DD in JSON, stored as LocalDate</p>
         * 
         * <p><b>Business Rule</b>: Transactions attempted after expiration date should be declined</p>
         * 
         * @see com.carddemo.entity.Card#getExpirationDate()
         */
        @JsonProperty("expirationDate")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private LocalDate expirationDate;

        /**
         * Card active status indicator.
         * 
         * <p>This single-character flag indicates whether the card is currently active and eligible
         * for transactions. Inactive cards cannot process new transactions but maintain transaction history.</p>
         * 
         * <p><b>COBOL Source</b>: CVACT02Y.cpy CARD-ACTIVE-STATUS PIC X(01)</p>
         * 
         * <p><b>Valid Values</b>:</p>
         * <ul>
         *   <li><b>"Y"</b>: Card is active and can process transactions</li>
         *   <li><b>"N"</b>: Card is inactive/blocked and cannot process transactions</li>
         * </ul>
         * 
         * <p><b>Business Rule</b>: Only active cards ("Y") can authorize new transactions</p>
         * 
         * @see com.carddemo.entity.Card#getActiveStatus()
         */
        @JsonProperty("activeStatus")
        private String activeStatus;
    }

    /**
     * Nested object containing account financial information for the transaction.
     * 
     * <p>This static nested class encapsulates account monetary details displayed alongside transaction information,
     * providing financial context including current balance and credit limit. All fields sourced from CVACT01Y.cpy
     * ACCOUNT-RECORD structure with exact BigDecimal precision preservation.</p>
     * 
     * <h3>COBOL Field Mapping</h3>
     * <pre>
     * ACCT-ID                PIC 9(11)      → accountId
     * ACCT-CURR-BAL          PIC S9(10)V99  → currentBalance BigDecimal scale=2
     * ACCT-CREDIT-LIMIT      PIC S9(10)V99  → creditLimit BigDecimal scale=2
     * ACCT-ACTIVE-STATUS     PIC X(01)      → activeStatus
     * </pre>
     * 
     * <h3>BigDecimal Precision Requirements</h3>
     * <p>The {@code currentBalance} and {@code creditLimit} fields use BigDecimal with scale=2 and JsonFormat
     * shape=STRING to preserve exact COBOL COMP-3 packed decimal precision. This prevents JavaScript Number
     * precision loss when displaying account balances in React components.</p>
     * 
     * <h3>Usage Example</h3>
     * <pre>
     * AccountInfo accountInfo = AccountInfo.builder()
     *     .accountId(12345678901L)
     *     .currentBalance(new BigDecimal("15000.00"))
     *     .creditLimit(new BigDecimal("50000.00"))
     *     .activeStatus("Y")
     *     .build();
     * </pre>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @lombok.Builder
    public static class AccountInfo {

        /**
         * Unique account identifier linking card to account.
         * 
         * <p>This 11-digit account number uniquely identifies the credit account associated with the card
         * used in the transaction. It establishes the foreign key relationship: transaction → card → account.</p>
         * 
         * <p><b>COBOL Source</b>: CVACT01Y.cpy ACCT-ID PIC 9(11)</p>
         * 
         * <p><b>Data Type</b>: Long (Java primitive wrapper for 64-bit integer)</p>
         * 
         * <p><b>Range</b>: 0 to 99,999,999,999 (11 digits)</p>
         * 
         * @see com.carddemo.entity.Account#getAccountId()
         */
        @JsonProperty("accountId")
        private Long accountId;

        /**
         * Current account balance reflecting all posted transactions.
         * 
         * <p>This field displays the account's current balance including all posted credits and debits.
         * It provides financial context for the transaction, showing the account balance at the time
         * of transaction display (not at transaction origination time).</p>
         * 
         * <p><b>COBOL Source</b>: CVACT01Y.cpy ACCT-CURR-BAL PIC S9(10)V99</p>
         * 
         * <p><b>Precision</b>: BigDecimal with scale=2 for exact cents precision</p>
         * 
         * <p><b>JSON Serialization</b>: Serialized as string "15000.00" to prevent JavaScript precision loss</p>
         * 
         * <p><b>Range</b>: -9,999,999,999.99 to 9,999,999,999.99 (signed, 10 digits with 2 decimal places)</p>
         * 
         * <p><b>Business Rule</b>: Negative balances indicate customer owes money; positive balances indicate credit</p>
         * 
         * @see com.carddemo.entity.Account#getCurrentBalance()
         */
        @JsonProperty("currentBalance")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal currentBalance;

        /**
         * Maximum credit limit for the account.
         * 
         * <p>This field displays the maximum amount of credit extended to the cardholder. Transactions
         * that would cause the balance to exceed this limit should be declined (subject to credit policies).</p>
         * 
         * <p><b>COBOL Source</b>: CVACT01Y.cpy ACCT-CREDIT-LIMIT PIC S9(10)V99</p>
         * 
         * <p><b>Precision</b>: BigDecimal with scale=2 for exact cents precision</p>
         * 
         * <p><b>JSON Serialization</b>: Serialized as string "50000.00" to prevent JavaScript precision loss</p>
         * 
         * <p><b>Range</b>: 0.00 to 9,999,999,999.99 (typically positive values only)</p>
         * 
         * <p><b>Business Rule</b>: Available credit = creditLimit - currentBalance (when balance is negative)</p>
         * 
         * @see com.carddemo.entity.Account#getCreditLimit()
         */
        @JsonProperty("creditLimit")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal creditLimit;

        /**
         * Account active status indicator.
         * 
         * <p>This single-character flag indicates whether the account is currently active and eligible
         * for new transactions. Inactive accounts maintain historical data but cannot process new charges.</p>
         * 
         * <p><b>COBOL Source</b>: CVACT01Y.cpy ACCT-ACTIVE-STATUS PIC X(01)</p>
         * 
         * <p><b>Valid Values</b>:</p>
         * <ul>
         *   <li><b>"Y"</b>: Account is active and can process transactions</li>
         *   <li><b>"N"</b>: Account is inactive/closed and cannot process transactions</li>
         * </ul>
         * 
         * <p><b>Business Rule</b>: Only active accounts ("Y") can authorize new transactions on linked cards</p>
         * 
         * @see com.carddemo.entity.Account#getActiveStatus()
         */
        @JsonProperty("activeStatus")
        private String activeStatus;
    }
}
