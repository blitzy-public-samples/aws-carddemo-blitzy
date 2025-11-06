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
import java.time.LocalDate;

/**
 * Account Response DTO
 * 
 * <p>Data Transfer Object representing complete account information returned by account management
 * REST API endpoints. This DTO transforms the COBOL ACCOUNT-RECORD structure from CVACT01Y.cpy
 * copybook (300-byte VSAM KSDS record) to modern JSON representation for web services.</p>
 * 
 * <h2>COBOL Source Mapping</h2>
 * <p>This response DTO maps directly to the mainframe COBOL copybook structure:</p>
 * <pre>
 * Source: app/cpy/CVACT01Y.cpy - ACCOUNT-RECORD
 * VSAM File: ACCTDAT (Account Master File)
 * Record Length: 300 bytes
 * Key Field: ACCT-ID (PIC 9(11)) - 11-digit account identifier
 * </pre>
 * 
 * <h2>COBOL Program Transformations</h2>
 * <ul>
 *   <li><strong>COACTVWC.cbl</strong> (CAVW transaction) - Account View Program
 *       <br>READ operation replaced by AccountRepository.findById()
 *       <br>Returns account details with transaction history summary
 *   </li>
 *   <li><strong>COACTUPC.cbl</strong> (CAUP transaction) - Account Update Program
 *       <br>REWRITE operation replaced by AccountRepository.save()
 *       <br>Returns updated account after credit limit changes with @Transactional ACID properties
 *   </li>
 * </ul>
 * 
 * <h2>BMS Screen Correspondence</h2>
 * <ul>
 *   <li><strong>COACTVWM.bms</strong> - Account View Screen (3270 terminal display)
 *       <br>Transformed to React AccountViewComponent.jsx
 *   </li>
 *   <li><strong>COACTUPM.bms</strong> - Account Update Screen (3270 terminal form)
 *       <br>Transformed to React AccountUpdateComponent.jsx
 *   </li>
 * </ul>
 * 
 * <h2>Monetary Field Precision Requirements</h2>
 * <p><strong>CRITICAL:</strong> All monetary fields use BigDecimal with scale=2 and RoundingMode.HALF_UP
 * to maintain exact COBOL COMP-3 packed decimal arithmetic precision. This prevents floating-point
 * rounding errors in financial calculations and ensures mainframe arithmetic equivalence.</p>
 * 
 * <p>COBOL COMP-3 fields (PIC S9(10)V99) represent signed decimal numbers with 2 implied decimal places.
 * The V indicates the decimal position, not an actual decimal point character. Java BigDecimal with
 * scale=2 provides equivalent precision:</p>
 * <pre>
 * COBOL: PIC S9(10)V99 COMP-3  →  Java: BigDecimal with scale=2
 * Range: -9,999,999,999.99 to +9,999,999,999.99 (max $999,999,999.99 for limits)
 * </pre>
 * 
 * <h2>JSON Serialization</h2>
 * <p>All BigDecimal monetary fields use @JsonFormat(shape = JsonFormat.Shape.STRING) to serialize
 * as JSON strings rather than numbers. This critical annotation prevents precision loss in JavaScript
 * clients where the Number type is limited to 53-bit integer precision (IEEE 754 double).</p>
 * 
 * <p>Example JSON output:</p>
 * <pre>
 * {
 *   "accountId": 11111111111,
 *   "activeStatus": "Y",
 *   "currentBalance": "25000.50",      // STRING prevents precision loss
 *   "creditLimit": "50000.00",          // STRING prevents precision loss
 *   "cashCreditLimit": "10000.00",      // STRING prevents precision loss
 *   "openDate": "2020-01-15",
 *   "expirationDate": "2025-01-31",
 *   "reissueDate": "2024-11-01",
 *   "currentCycleCredit": "1500.75",    // STRING prevents precision loss
 *   "currentCycleDebit": "2300.25",     // STRING prevents precision loss
 *   "addressZip": "10001",
 *   "groupId": "GROUP001"
 * }
 * </pre>
 * 
 * <h2>Date Field Transformations</h2>
 * <p>COBOL alphanumeric date fields (PIC X(10)) formatted as YYYY-MM-DD transform to Java LocalDate
 * with ISO 8601 serialization. This provides proper date type support and consistent representation:</p>
 * <pre>
 * COBOL: ACCT-OPEN-DATE PIC X(10) "2020-01-15"  →  Java: LocalDate.of(2020, 1, 15)
 * JSON:  "openDate": "2020-01-15"  (ISO 8601 format)
 * </pre>
 * 
 * <h2>Validation Rules</h2>
 * <ul>
 *   <li><strong>accountId:</strong> 11-digit positive integer, primary key</li>
 *   <li><strong>activeStatus:</strong> "Y" (active) or "N" (inactive)</li>
 *   <li><strong>currentBalance:</strong> Signed decimal, can be negative (overdrawn account)</li>
 *   <li><strong>creditLimit:</strong> Range $1,000.00 to $999,999,999.99</li>
 *   <li><strong>cashCreditLimit:</strong> Range $0.00 to creditLimit</li>
 *   <li><strong>currentCycleCredit:</strong> Sum of credits (payments, refunds) in billing cycle</li>
 *   <li><strong>currentCycleDebit:</strong> Sum of debits (purchases, fees) in billing cycle</li>
 * </ul>
 * 
 * <h2>Billing Cycle Semantics</h2>
 * <p>The current cycle fields track financial activity within the monthly statement period:</p>
 * <ul>
 *   <li><strong>currentCycleCredit:</strong> Payments and refunds that reduce balance</li>
 *   <li><strong>currentCycleDebit:</strong> Purchases, cash advances, and fees that increase balance</li>
 *   <li>These fields reset to zero at the start of each new billing cycle</li>
 *   <li>Used for statement generation and cycle-to-date reporting</li>
 * </ul>
 * 
 * <h2>REST API Usage</h2>
 * <ul>
 *   <li><strong>GET /api/accounts/{id}</strong> - Retrieve account details
 *       <br>Service: AccountViewService.getAccountDetails()
 *       <br>Returns: AccountResponse with complete account information
 *   </li>
 *   <li><strong>PUT /api/accounts/{id}</strong> - Update account information
 *       <br>Service: AccountUpdateService.updateAccount()
 *       <br>Returns: AccountResponse with updated account details
 *       <br>Transaction: @Transactional ensures ACID properties matching CICS SYNCPOINT
 *   </li>
 * </ul>
 * 
 * <h2>Concurrency Control</h2>
 * <p>The Account entity includes @Version field for optimistic locking, preventing concurrent update
 * conflicts. If two users attempt to update the same account simultaneously, the second update will
 * fail with OptimisticLockException, requiring the user to refresh and retry.</p>
 * 
 * <h2>Referential Integrity</h2>
 * <p>Account records maintain foreign key relationships with:</p>
 * <ul>
 *   <li><strong>Customer:</strong> Each account belongs to one customer (one-to-many)</li>
 *   <li><strong>Card:</strong> Each account can have multiple cards (one-to-many)</li>
 *   <li><strong>Transaction:</strong> Each account has multiple transactions (one-to-many)</li>
 * </ul>
 * 
 * <h2>Migration Architecture</h2>
 * <p>This DTO is part of the comprehensive mainframe-to-cloud migration transforming:</p>
 * <ul>
 *   <li>VSAM KSDS file access → PostgreSQL table with JPA Repository</li>
 *   <li>CICS pseudo-conversational processing → Stateless REST API with JWT authentication</li>
 *   <li>BMS 3270 terminal screens → React web components with Material-UI</li>
 *   <li>COBOL COMP-3 decimal arithmetic → Java BigDecimal with explicit precision</li>
 *   <li>COMMAREA state management → Spring Session with Redis backing</li>
 * </ul>
 * 
 * @see com.carddemo.entity.Account JPA entity with database mapping
 * @see com.carddemo.service.account.AccountViewService Account retrieval service
 * @see com.carddemo.service.account.AccountUpdateService Account update service
 * @see com.carddemo.controller.AccountController REST API controller
 * @see com.carddemo.repository.AccountRepository Data access repository
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "accountId",
    "activeStatus",
    "currentBalance",
    "creditLimit",
    "cashCreditLimit",
    "openDate",
    "expirationDate",
    "reissueDate",
    "currentCycleCredit",
    "currentCycleDebit",
    "addressZip",
    "groupId"
})
public class AccountResponse {

    /**
     * Account Identifier (Primary Key)
     * 
     * <p>Maps from COBOL field: ACCT-ID PIC 9(11)</p>
     * <p>11-digit unique account identifier used as primary key in VSAM KSDS file
     * and PostgreSQL account table. This field serves as the account number visible
     * to customers and used for all account operations.</p>
     * 
     * <p>Valid Range: 00000000001 to 99999999999</p>
     * <p>Example: 11111111111</p>
     */
    @JsonProperty("accountId")
    private Long accountId;

    /**
     * Account Active Status Flag
     * 
     * <p>Maps from COBOL field: ACCT-ACTIVE-STATUS PIC X(01)</p>
     * <p>Single character indicating whether the account is currently active
     * and available for transactions.</p>
     * 
     * <p>Valid Values:</p>
     * <ul>
     *   <li>"Y" - Active account, can process transactions</li>
     *   <li>"N" - Inactive account, transactions blocked</li>
     * </ul>
     * 
     * <p>Inactive accounts cannot process purchases, cash advances, or payments
     * until reactivated by administrative action.</p>
     */
    @JsonProperty("activeStatus")
    private String activeStatus;

    /**
     * Current Account Balance
     * 
     * <p>Maps from COBOL field: ACCT-CURR-BAL PIC S9(10)V99 COMP-3</p>
     * <p>Current outstanding balance on the account, representing total amount owed.
     * This field uses BigDecimal with scale=2 to maintain exact COBOL COMP-3 packed
     * decimal precision. The signed field can be negative if account is overdrawn
     * or has credit balance from overpayment.</p>
     * 
     * <p>Calculation: Previous Balance + Purchases + Fees - Payments - Credits</p>
     * <p>Valid Range: -9,999,999,999.99 to +9,999,999,999.99</p>
     * <p>Example: "25000.50" (serialized as string to prevent JavaScript precision loss)</p>
     * 
     * <p><strong>CRITICAL:</strong> Serialized as JSON string using @JsonFormat(shape = STRING)
     * to prevent precision loss in JavaScript Number type (limited to 53-bit integer precision).</p>
     */
    @JsonProperty("currentBalance")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal currentBalance;

    /**
     * Credit Limit
     * 
     * <p>Maps from COBOL field: ACCT-CREDIT-LIMIT PIC S9(10)V99 COMP-3</p>
     * <p>Maximum credit line available on this account. Customer cannot exceed this
     * limit with purchases and cash advances. Uses BigDecimal with scale=2 for exact
     * monetary precision matching COBOL COMP-3 arithmetic.</p>
     * 
     * <p>Validation Rules:</p>
     * <ul>
     *   <li>Minimum: $1,000.00 (regulatory requirement)</li>
     *   <li>Maximum: $999,999,999.99</li>
     *   <li>Must be greater than or equal to current balance</li>
     * </ul>
     * 
     * <p>Available Credit = Credit Limit - Current Balance</p>
     * <p>Example: "50000.00"</p>
     */
    @JsonProperty("creditLimit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal creditLimit;

    /**
     * Cash Credit Limit
     * 
     * <p>Maps from COBOL field: ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3</p>
     * <p>Maximum amount available for cash advances. Typically a percentage of the
     * overall credit limit (commonly 20-30%). Uses BigDecimal with scale=2 for
     * exact COBOL COMP-3 precision.</p>
     * 
     * <p>Validation Rules:</p>
     * <ul>
     *   <li>Must be less than or equal to creditLimit</li>
     *   <li>Can be $0.00 (no cash advance privilege)</li>
     *   <li>Cannot exceed available credit</li>
     * </ul>
     * 
     * <p>Example: "10000.00"</p>
     */
    @JsonProperty("cashCreditLimit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal cashCreditLimit;

    /**
     * Account Open Date
     * 
     * <p>Maps from COBOL field: ACCT-OPEN-DATE PIC X(10)</p>
     * <p>Date when the account was originally opened. COBOL alphanumeric field
     * (PIC X(10)) formatted as YYYY-MM-DD transforms to Java LocalDate with
     * ISO 8601 serialization.</p>
     * 
     * <p>Format: YYYY-MM-DD (ISO 8601)</p>
     * <p>Example: "2020-01-15"</p>
     * 
     * <p>This date is used for:</p>
     * <ul>
     *   <li>Account age calculations</li>
     *   <li>Credit line increase eligibility (typically after 6 months)</li>
     *   <li>Historical reporting and analytics</li>
     * </ul>
     */
    @JsonProperty("openDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate openDate;

    /**
     * Account Expiration Date
     * 
     * <p>Maps from COBOL field: ACCT-EXPIRAION-DATE PIC X(10)</p>
     * <p>Date when the account expires and must be renewed or closed. Note: The
     * COBOL field name contains a typo ("EXPIRAION" instead of "EXPIRATION") which
     * is preserved in the source mapping but corrected in Java naming.</p>
     * 
     * <p>Format: YYYY-MM-DD (ISO 8601)</p>
     * <p>Example: "2025-01-31"</p>
     * 
     * <p>Account behavior at expiration:</p>
     * <ul>
     *   <li>New transactions blocked 30 days before expiration</li>
     *   <li>Account automatically closed if not renewed</li>
     *   <li>Outstanding balance remains payable after closure</li>
     * </ul>
     */
    @JsonProperty("expirationDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Card Reissue Date
     * 
     * <p>Maps from COBOL field: ACCT-REISSUE-DATE PIC X(10)</p>
     * <p>Date when replacement cards were last issued for this account. Updated
     * when cards are reissued due to expiration, loss, theft, or fraud.</p>
     * 
     * <p>Format: YYYY-MM-DD (ISO 8601)</p>
     * <p>Example: "2024-11-01"</p>
     * 
     * <p>Reissue triggers:</p>
     * <ul>
     *   <li>Regular card expiration (typically 3-5 year card validity)</li>
     *   <li>Lost or stolen card replacement</li>
     *   <li>Suspected fraud requiring new card number</li>
     *   <li>Damaged card replacement</li>
     * </ul>
     */
    @JsonProperty("reissueDate")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate reissueDate;

    /**
     * Current Cycle Credit Amount
     * 
     * <p>Maps from COBOL field: ACCT-CURR-CYC-CREDIT PIC S9(10)V99 COMP-3</p>
     * <p>Sum of all credit transactions (payments, refunds, credits) posted to the
     * account during the current billing cycle. Uses BigDecimal with scale=2 for
     * exact COBOL COMP-3 precision. This field resets to $0.00 at the start of
     * each new billing cycle.</p>
     * 
     * <p>Includes:</p>
     * <ul>
     *   <li>Customer payments (online, mail, phone)</li>
     *   <li>Merchant refunds for returned purchases</li>
     *   <li>Billing adjustments and credits</li>
     *   <li>Promotional credits and rewards</li>
     * </ul>
     * 
     * <p>Example: "1500.75"</p>
     * <p>Cycle Period: Typically monthly, aligned with statement generation</p>
     */
    @JsonProperty("currentCycleCredit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal currentCycleCredit;

    /**
     * Current Cycle Debit Amount
     * 
     * <p>Maps from COBOL field: ACCT-CURR-CYC-DEBIT PIC S9(10)V99 COMP-3</p>
     * <p>Sum of all debit transactions (purchases, cash advances, fees, interest)
     * posted to the account during the current billing cycle. Uses BigDecimal with
     * scale=2 for exact COBOL COMP-3 precision. This field resets to $0.00 at the
     * start of each new billing cycle.</p>
     * 
     * <p>Includes:</p>
     * <ul>
     *   <li>Purchase transactions at merchants</li>
     *   <li>Cash advances from ATMs</li>
     *   <li>Annual fees, late fees, over-limit fees</li>
     *   <li>Interest charges on carried balances</li>
     * </ul>
     * 
     * <p>Example: "2300.25"</p>
     * <p>Cycle Period: Typically monthly, aligned with statement generation</p>
     * <p>Net Cycle Activity: currentCycleCredit - currentCycleDebit</p>
     */
    @JsonProperty("currentCycleDebit")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private BigDecimal currentCycleDebit;

    /**
     * Address ZIP Code
     * 
     * <p>Maps from COBOL field: ACCT-ADDR-ZIP PIC X(10)</p>
     * <p>ZIP or postal code from the primary billing address associated with this
     * account. Used for address verification and fraud detection.</p>
     * 
     * <p>Format: Flexible to support both US ZIP codes and international postal codes</p>
     * <ul>
     *   <li>US ZIP: 5 digits (e.g., "10001") or ZIP+4 (e.g., "10001-1234")</li>
     *   <li>Canadian: A1A 1A1 format</li>
     *   <li>Other international postal codes as appropriate</li>
     * </ul>
     * 
     * <p>Example: "10001"</p>
     */
    @JsonProperty("addressZip")
    private String addressZip;

    /**
     * Account Group Identifier
     * 
     * <p>Maps from COBOL field: ACCT-GROUP-ID PIC X(10)</p>
     * <p>Business grouping or portfolio identifier used for account categorization,
     * reporting, and special processing. Accounts within the same group may share
     * common characteristics such as promotional rates, fee structures, or rewards
     * programs.</p>
     * 
     * <p>Usage Examples:</p>
     * <ul>
     *   <li>Product line grouping (Premium, Standard, Student)</li>
     *   <li>Promotional campaign identifier</li>
     *   <li>Partner or co-branded card program</li>
     *   <li>Special rate or fee schedule grouping</li>
     * </ul>
     * 
     * <p>Example: "GROUP001"</p>
     */
    @JsonProperty("groupId")
    private String groupId;
}
