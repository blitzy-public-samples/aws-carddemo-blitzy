/*
 * CardDetailResponse.java
 *
 * Card detail view response Data Transfer Object (DTO) containing comprehensive 
 * card information with associated account details and transaction summary for 
 * single card display.
 *
 * This class extends CardResponse to provide enriched card details by combining 
 * card data with related account financial information and transaction aggregates,
 * replicating the COBOL COCRDSLC.cbl card detail screen logic that performed 
 * multiple VSAM file reads across CARDDAT, ACCTDAT, and TRANSACT files using 
 * cross-reference relationships.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Card Detail Response DTO
 * 
 * Comprehensive card information response extending CardResponse with additional
 * account financial details and transaction summary data for enriched single card
 * display views.
 * 
 * <h2>COBOL Source Mapping</h2>
 * This DTO consolidates data from multiple COBOL programs and copybooks that were
 * accessed through VSAM cross-reference file relationships:
 * <pre>
 * COBOL Program              Copybook              Java Transformation
 * ────────────────────────────────────────────────────────────────────────────
 * COCRDSLC.cbl              CVACT02Y.cpy          CardResponse (parent class)
 *   - Card detail display   CARD-RECORD           Base card fields inherited
 *   
 * COCRDSLC.cbl              CVACT01Y.cpy          AccountInfo nested object
 *   - Account data fetch    ACCOUNT-RECORD        JPA eager fetch join
 *   - ACCT-CURR-BAL         PIC S9(10)V99         BigDecimal currentBalance
 *   - ACCT-CREDIT-LIMIT     PIC S9(10)V99         BigDecimal creditLimit
 *   - ACCT-ACTIVE-STATUS    PIC X(01)             String activeStatus
 *   
 * COCRDSLC.cbl              CVTRA05Y.cpy          TransactionSummary object
 *   - Transaction aggregate TRAN-RECORD           JPA aggregate query
 *   - COUNT(*)              -                     Long transactionCount
 *   - SUM(TRAN-AMT)         PIC S9(09)V99         BigDecimal totalAmount
 * </pre>
 * 
 * <h2>VSAM Cross-Reference File Replacement</h2>
 * The COBOL program COCRDSLC.cbl performed multiple sequential file reads:
 * <ol>
 *   <li><b>Step 1:</b> READ CARDDAT file by card number (CARD-NUM key)</li>
 *   <li><b>Step 2:</b> Extract CARD-ACCT-ID from card record</li>
 *   <li><b>Step 3:</b> READ ACCTDAT file using CARD-ACCT-ID (cross-reference)</li>
 *   <li><b>Step 4:</b> STARTBR/READNEXT loop through TRANSACT file filtering 
 *       by TRAN-CARD-NUM matching card number</li>
 *   <li><b>Step 5:</b> Aggregate transaction count and total amount in COBOL 
 *       working storage variables</li>
 * </ol>
 * 
 * <p>This multi-step process is replaced by a single JPA query with eager fetch 
 * joins in CardDetailService.getCardDetail():
 * <pre>
 * SELECT c, a, COUNT(t), SUM(t.amount)
 * FROM Card c
 * LEFT JOIN FETCH c.account a
 * LEFT JOIN c.transactions t
 * WHERE c.cardNumber = :cardNumber
 * GROUP BY c.cardNumber, a.accountId
 * </pre>
 * 
 * <h2>BMS Mapset Correspondence</h2>
 * Maps to COCRDSLM.bms (CCDL transaction) card detail view screen layout:
 * <ul>
 *   <li><b>Screen Title:</b> "Credit Card Detail View"</li>
 *   <li><b>Card Section:</b> Displays all CardResponse fields (inherited)</li>
 *   <li><b>Account Section:</b> Shows accountInfo nested object fields</li>
 *   <li><b>Transaction Summary Section:</b> Displays transactionSummary data</li>
 *   <li><b>Navigation:</b> PF3=Back to card list, PF5=Update card</li>
 * </ul>
 * 
 * <h2>Data Precision Requirements</h2>
 * All monetary fields must maintain exact COBOL COMP-3 packed decimal precision:
 * <ul>
 *   <li><b>ACCT-CURR-BAL (PIC S9(10)V99):</b> Maps to BigDecimal with scale=2, 
 *       using RoundingMode.HALF_UP to match COBOL rounding behavior</li>
 *   <li><b>ACCT-CREDIT-LIMIT (PIC S9(10)V99):</b> Maps to BigDecimal with scale=2, 
 *       enforcing identical precision for credit limit calculations</li>
 *   <li><b>TRAN-AMT (PIC S9(09)V99):</b> Aggregated using BigDecimal.add() with 
 *       scale=2 to match COBOL ADD statement precision</li>
 * </ul>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li><b>CVV Display:</b> Full CVV returned only for admin users 
 *       (@PreAuthorize("hasRole('ADMIN')") in service layer)</li>
 *   <li><b>Account Balance:</b> Sensitive financial data requiring authentication</li>
 *   <li><b>Credit Limit:</b> Displayed only for account holder and admins</li>
 *   <li><b>Transaction Summary:</b> Available to authenticated card owner</li>
 * </ul>
 * 
 * <h2>API Usage</h2>
 * <ul>
 *   <li><b>Endpoint:</b> GET /api/cards/{cardNumber}</li>
 *   <li><b>Service:</b> CardDetailService.getCardDetail(String cardNumber)</li>
 *   <li><b>Controller:</b> CardController.getCardDetails(@PathVariable cardNumber)</li>
 *   <li><b>React Component:</b> CardDetailComponent.jsx</li>
 *   <li><b>Authentication:</b> Requires valid JWT token with USER or ADMIN role</li>
 * </ul>
 * 
 * <h2>React Component Integration</h2>
 * CardDetailComponent.jsx displays this DTO in structured sections:
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │ Card Information                                │
 * │ ─────────────────────────────────────────────── │
 * │ Card Number:     ************1234               │
 * │ Cardholder:      John Doe                       │
 * │ Expiration:      12/2025                        │
 * │ Status:          Active ✓                       │
 * │                                                 │
 * │ Account Information                             │
 * │ ─────────────────────────────────────────────── │
 * │ Account ID:      12345678901                    │
 * │ Current Balance: $5,234.56                      │
 * │ Credit Limit:    $10,000.00                     │
 * │ Account Status:  Active                         │
 * │                                                 │
 * │ Transaction Summary                             │
 * │ ─────────────────────────────────────────────── │
 * │ Transaction Count: 42                           │
 * │ Total Amount:      $8,765.43                    │
 * └─────────────────────────────────────────────────┘
 * </pre>
 * 
 * <h2>COBOL-to-Java Transformation Patterns</h2>
 * <ul>
 *   <li><b>VSAM READ operations:</b> → JPA repository.findByCardNumber()</li>
 *   <li><b>Cross-reference file access:</b> → JPA @ManyToOne relationship with 
 *       eager fetching</li>
 *   <li><b>COBOL COMPUTE aggregations:</b> → JPA aggregate query with SUM and COUNT</li>
 *   <li><b>WORKING-STORAGE data structures:</b> → Static nested classes 
 *       (AccountInfo, TransactionSummary)</li>
 *   <li><b>COBOL 88-level conditions:</b> → Java String values ('Y'/'N') with 
 *       enum-like constants</li>
 * </ul>
 * 
 * @see com.carddemo.dto.response.CardResponse Parent class with base card fields
 * @see com.carddemo.entity.Card JPA entity for card table
 * @see com.carddemo.entity.Account JPA entity for account table
 * @see com.carddemo.entity.Transaction JPA entity for transaction table
 * @see com.carddemo.service.card.CardDetailService Service providing enriched card details
 * @see com.carddemo.controller.CardController REST controller for card endpoints
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardDetailResponse extends CardResponse {

    /**
     * Account Information Nested Object
     * 
     * <p>Contains financial details from the parent account linked to this card,
     * providing context for credit limits, current balances, and account status.
     * This data was retrieved in COBOL through VSAM cross-reference file reads
     * using CARD-ACCT-ID as the lookup key.
     * 
     * <p><b>COBOL Source:</b> CVACT01Y.cpy ACCOUNT-RECORD accessed via ACCTDAT 
     * file READ operation after extracting CARD-ACCT-ID from CARD-RECORD
     * 
     * <p><b>JPA Transformation:</b> Populated through eager fetch join on 
     * Card.account relationship, eliminating separate database round trip:
     * <pre>
     * @ManyToOne(fetch = FetchType.EAGER)
     * @JoinColumn(name = "account_id")
     * private Account account;
     * </pre>
     * 
     * <p><b>Null Handling:</b> This field may be null if:
     * <ul>
     *   <li>Account has been closed but card record still exists</li>
     *   <li>Data integrity issue (orphaned card record)</li>
     *   <li>JPA lazy loading not triggered (should not occur with EAGER fetch)</li>
     * </ul>
     * When null, @JsonInclude(NON_NULL) excludes this field from JSON response.
     * 
     * <p><b>Security Note:</b> Balance and limit information is sensitive financial
     * data requiring proper authentication and authorization checks in service layer.
     */
    @JsonProperty("accountInfo")
    private AccountInfo accountInfo;

    /**
     * Transaction Summary Nested Object
     * 
     * <p>Contains aggregated transaction statistics for this specific card, computed
     * from all transactions in the TRANSACT file where TRAN-CARD-NUM matches the
     * card number. In COBOL, this was calculated using STARTBR/READNEXT loop with
     * working storage counters and accumulators.
     * 
     * <p><b>COBOL Source:</b> CVTRA05Y.cpy TRAN-RECORD aggregated through VSAM
     * sequential read loop in COCRDSLC.cbl PROCEDURE DIVISION:
     * <pre>
     * WORKING-STORAGE SECTION.
     * 01 WS-TRAN-COUNT         PIC 9(05) VALUE ZEROS.
     * 01 WS-TRAN-TOTAL-AMT     PIC S9(09)V99 VALUE ZEROS.
     * 
     * PROCEDURE DIVISION.
     *     PERFORM VARYING WS-TRAN-COUNT FROM 0 BY 1
     *         UNTIL END-OF-FILE
     *         READ TRANSACT FILE
     *         IF TRAN-CARD-NUM = CARD-NUM
     *             ADD 1 TO WS-TRAN-COUNT
     *             ADD TRAN-AMT TO WS-TRAN-TOTAL-AMT
     *         END-IF
     *     END-PERFORM.
     * </pre>
     * 
     * <p><b>JPA Transformation:</b> Computed using single aggregate query in
     * TransactionRepository:
     * <pre>
     * @Query("SELECT NEW com.carddemo.dto.response.TransactionSummary(" +
     *        "COUNT(t), SUM(t.amount)) " +
     *        "FROM Transaction t WHERE t.cardNumber = :cardNumber")
     * TransactionSummary getTransactionSummary(@Param("cardNumber") String cardNumber);
     * </pre>
     * 
     * <p><b>Null Handling:</b> This field may be null if:
     * <ul>
     *   <li>Card has no transactions (new card)</li>
     *   <li>All transactions have been purged (archived)</li>
     * </ul>
     * When null, @JsonInclude(NON_NULL) excludes this field from JSON response.
     * 
     * <p><b>Business Rules:</b>
     * <ul>
     *   <li>Only POSTED transactions included in count and total</li>
     *   <li>Pending transactions excluded from aggregation</li>
     *   <li>Voided transactions subtracted from total amount</li>
     *   <li>Credits (returns) have negative amounts reducing total</li>
     * </ul>
     */
    @JsonProperty("transactionSummary")
    private TransactionSummary transactionSummary;

    /**
     * Account Information Static Nested Class
     * 
     * <p>Encapsulates account financial details linked to the card, providing context
     * for credit availability, current debt, and account operational status.
     * 
     * <p><b>COBOL Record Mapping:</b>
     * <pre>
     * COBOL Field (CVACT01Y.cpy)     Java Field           Transformation
     * ─────────────────────────────────────────────────────────────────────
     * ACCT-ID (PIC 9(11))            accountId            Long
     * ACCT-CURR-BAL (PIC S9(10)V99)  currentBalance       BigDecimal (scale=2)
     * ACCT-CREDIT-LIMIT (PIC S9(10)V99) creditLimit       BigDecimal (scale=2)
     * ACCT-ACTIVE-STATUS (PIC X(01)) activeStatus         String ('Y' or 'N')
     * </pre>
     * 
     * <p><b>Design Pattern:</b> Static nested class pattern used to group related
     * account fields into cohesive unit, matching COBOL copybook structure and
     * improving JSON response organization with clear hierarchical nesting.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AccountInfo {

        /**
         * Account Identifier
         * 
         * <p>11-digit unique account identifier linking card to parent account.
         * Same value as CardResponse.accountId but duplicated here for completeness
         * in nested object structure.
         * 
         * <p><b>COBOL Source:</b> ACCT-ID (PIC 9(11)) from CVACT01Y.cpy
         * 
         * <p><b>Database Column:</b> account.account_id (PRIMARY KEY)
         * 
         * <p><b>Example Value:</b> 12345678901L
         */
        @JsonProperty("accountId")
        private Long accountId;

        /**
         * Current Account Balance
         * 
         * <p>Current outstanding balance on the account representing total debt.
         * Positive values indicate money owed by cardholder to issuer. Negative
         * values indicate credit balance (overpayment).
         * 
         * <p><b>COBOL Source:</b> ACCT-CURR-BAL (PIC S9(10)V99 COMP-3) from 
         * CVACT01Y.cpy, packed decimal format with 2 implied decimal places
         * 
         * <p><b>Precision Mapping:</b>
         * <ul>
         *   <li><b>COBOL COMP-3:</b> Signed packed decimal, 10 integer digits, 
         *       2 fractional digits</li>
         *   <li><b>Java BigDecimal:</b> scale=2, RoundingMode.HALF_UP matching 
         *       COBOL rounding</li>
         *   <li><b>PostgreSQL:</b> NUMERIC(12,2) column type</li>
         *   <li><b>JSON:</b> String representation to preserve exact precision</li>
         * </ul>
         * 
         * <p><b>Business Rules:</b>
         * <ul>
         *   <li>Updated by transaction posting (debits increase, credits decrease)</li>
         *   <li>Must not exceed creditLimit for purchase transactions</li>
         *   <li>Payments reduce balance (credit transactions)</li>
         *   <li>Interest charges increase balance (computed by CBACT04C batch job)</li>
         * </ul>
         * 
         * <p><b>Display Format:</b> React component formats as currency with 
         * thousand separators: "$5,234.56" or "$-100.00" for credit balance
         * 
         * <p><b>Example Values:</b>
         * <ul>
         *   <li>5234.56 - Cardholder owes $5,234.56</li>
         *   <li>-100.00 - Account has $100.00 credit balance</li>
         *   <li>0.00 - Account has zero balance</li>
         * </ul>
         */
        @JsonProperty("currentBalance")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal currentBalance;

        /**
         * Credit Limit
         * 
         * <p>Maximum amount that can be borrowed on this account. Transactions that
         * would cause currentBalance to exceed creditLimit are declined unless
         * over-limit protection is enabled.
         * 
         * <p><b>COBOL Source:</b> ACCT-CREDIT-LIMIT (PIC S9(10)V99 COMP-3) from
         * CVACT01Y.cpy, packed decimal format with 2 implied decimal places
         * 
         * <p><b>Precision Mapping:</b>
         * <ul>
         *   <li><b>COBOL COMP-3:</b> Signed packed decimal, 10 integer digits, 
         *       2 fractional digits</li>
         *   <li><b>Java BigDecimal:</b> scale=2, RoundingMode.HALF_UP</li>
         *   <li><b>PostgreSQL:</b> NUMERIC(12,2) column type</li>
         *   <li><b>JSON:</b> String representation for precision</li>
         * </ul>
         * 
         * <p><b>Business Rules:</b>
         * <ul>
         *   <li>Set during account opening based on credit score</li>
         *   <li>Reviewed periodically for increase/decrease (COACTUPC.cbl)</li>
         *   <li>Cannot be reduced below current balance</li>
         *   <li>Transaction authorization validates: 
         *       (currentBalance + transactionAmount) &lt;= creditLimit</li>
         * </ul>
         * 
         * <p><b>Available Credit Calculation:</b>
         * availableCredit = creditLimit - currentBalance
         * 
         * <p><b>Display Format:</b> React component shows as currency: "$10,000.00"
         * along with available credit as derived value
         * 
         * <p><b>Example Values:</b>
         * <ul>
         *   <li>10000.00 - Standard credit limit of $10,000</li>
         *   <li>25000.00 - Premium account with $25,000 limit</li>
         *   <li>5000.00 - Entry-level account with $5,000 limit</li>
         * </ul>
         */
        @JsonProperty("creditLimit")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal creditLimit;

        /**
         * Account Active Status
         * 
         * <p>Indicates whether the account is active and operational. Inactive
         * accounts cannot process new transactions, though existing balance must
         * still be paid.
         * 
         * <p><b>COBOL Source:</b> ACCT-ACTIVE-STATUS (PIC X(01)) from CVACT01Y.cpy
         * 
         * <p><b>Valid Values:</b>
         * <ul>
         *   <li><b>"Y"</b> - Active: Account operational, transactions allowed</li>
         *   <li><b>"N"</b> - Inactive: Account closed, no new transactions</li>
         * </ul>
         * 
         * <p><b>Status Change Triggers:</b>
         * <ul>
         *   <li>Account closure request → Admin sets to 'N'</li>
         *   <li>Extended delinquency → System sets to 'N'</li>
         *   <li>Fraud detection → Risk team sets to 'N'</li>
         *   <li>Death of account holder → Admin sets to 'N'</li>
         * </ul>
         * 
         * <p><b>Business Rules:</b>
         * <ul>
         *   <li>Inactive accounts cannot authorize new transactions</li>
         *   <li>Existing balance must be paid even if inactive</li>
         *   <li>Cards linked to inactive accounts auto-set to inactive</li>
         *   <li>Reactivation requires credit review and approval</li>
         * </ul>
         * 
         * <p><b>Example Values:</b>
         * <ul>
         *   <li>"Y" - Account is active and operational</li>
         *   <li>"N" - Account is closed or suspended</li>
         * </ul>
         */
        @JsonProperty("activeStatus")
        private String activeStatus;
    }

    /**
     * Transaction Summary Static Nested Class
     * 
     * <p>Encapsulates aggregated transaction statistics computed from all transactions
     * associated with this card, providing quick overview of card usage without
     * requiring full transaction list retrieval.
     * 
     * <p><b>COBOL Aggregation Logic:</b>
     * Original COBOL program COCRDSLC.cbl performed manual aggregation:
     * <pre>
     * 01 WS-TRANSACTION-COUNTERS.
     *    05 WS-TRAN-COUNT          PIC 9(05) VALUE ZEROS.
     *    05 WS-TRAN-TOTAL-AMT      PIC S9(09)V99 VALUE ZEROS.
     * 
     * AGGREGATE-TRANSACTIONS.
     *     MOVE ZERO TO WS-TRAN-COUNT
     *     MOVE ZERO TO WS-TRAN-TOTAL-AMT
     *     PERFORM UNTIL END-OF-TRANS-FILE
     *         READ TRANSACT FILE NEXT
     *         AT END SET END-OF-TRANS-FILE TO TRUE
     *         NOT AT END
     *             IF TRAN-CARD-NUM = WS-CARD-NUM
     *                 ADD 1 TO WS-TRAN-COUNT
     *                 ADD TRAN-AMT TO WS-TRAN-TOTAL-AMT
     *             END-IF
     *         END-READ
     *     END-PERFORM.
     * </pre>
     * 
     * <p><b>JPA Query Replacement:</b> Single aggregate query replaces sequential
     * file processing:
     * <pre>
     * SELECT COUNT(t), COALESCE(SUM(t.amount), 0)
     * FROM Transaction t
     * WHERE t.cardNumber = :cardNumber
     * AND t.transactionStatus = 'POSTED'
     * </pre>
     * 
     * <p><b>Design Pattern:</b> Static nested class groups related aggregate fields,
     * improving JSON response structure with clear semantic grouping.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransactionSummary {

        /**
         * Total Transaction Count
         * 
         * <p>Total number of posted transactions associated with this card across
         * all time periods. Includes both debits (purchases) and credits (returns/payments).
         * 
         * <p><b>COBOL Source:</b> Calculated in COCRDSLC.cbl using counter:
         * <pre>
         * 05 WS-TRAN-COUNT PIC 9(05) VALUE ZEROS.
         * ...
         * ADD 1 TO WS-TRAN-COUNT.
         * </pre>
         * 
         * <p><b>JPA Transformation:</b> Computed using COUNT aggregate function:
         * <pre>
         * SELECT COUNT(t) FROM Transaction t WHERE t.cardNumber = :cardNumber
         * </pre>
         * 
         * <p><b>Business Rules:</b>
         * <ul>
         *   <li>Only includes transactions with status = 'POSTED'</li>
         *   <li>Excludes pending/authorized transactions not yet settled</li>
         *   <li>Excludes declined/failed transaction attempts</li>
         *   <li>Includes both debit and credit transactions</li>
         * </ul>
         * 
         * <p><b>Display Format:</b> React component displays with thousands
         * separator: "1,234 transactions"
         * 
         * <p><b>Example Values:</b>
         * <ul>
         *   <li>0L - New card with no transactions</li>
         *   <li>42L - Card with 42 posted transactions</li>
         *   <li>1523L - Heavily used card</li>
         * </ul>
         */
        @JsonProperty("transactionCount")
        private Long transactionCount;

        /**
         * Total Transaction Amount
         * 
         * <p>Sum of all posted transaction amounts for this card. Debits (purchases)
         * are positive, credits (returns/payments) are negative, resulting in net
         * total spend.
         * 
         * <p><b>COBOL Source:</b> Calculated in COCRDSLC.cbl using accumulator:
         * <pre>
         * 05 WS-TRAN-TOTAL-AMT PIC S9(09)V99 VALUE ZEROS.
         * ...
         * ADD TRAN-AMT TO WS-TRAN-TOTAL-AMT.
         * </pre>
         * 
         * <p><b>JPA Transformation:</b> Computed using SUM aggregate function with
         * BigDecimal arithmetic preserving exact precision:
         * <pre>
         * SELECT COALESCE(SUM(t.amount), 0) 
         * FROM Transaction t 
         * WHERE t.cardNumber = :cardNumber
         * </pre>
         * 
         * <p><b>Precision Requirements:</b>
         * <ul>
         *   <li><b>COBOL TRAN-AMT:</b> PIC S9(09)V99 COMP-3 (signed, 2 decimals)</li>
         *   <li><b>Java BigDecimal:</b> scale=2, RoundingMode.HALF_UP</li>
         *   <li><b>Aggregation:</b> BigDecimal.add() maintains scale throughout</li>
         *   <li><b>JSON:</b> String representation preserves exact precision</li>
         * </ul>
         * 
         * <p><b>Business Rules:</b>
         * <ul>
         *   <li>Purchase transactions: Positive amounts (debit)</li>
         *   <li>Return transactions: Negative amounts (credit)</li>
         *   <li>Payment transactions: Negative amounts (credit)</li>
         *   <li>Net total = Sum of all signed amounts</li>
         * </ul>
         * 
         * <p><b>Display Format:</b> React component formats as currency:
         * <ul>
         *   <li>Positive: "$8,765.43" (net spending)</li>
         *   <li>Negative: "$-500.00" (net credits exceed debits)</li>
         * </ul>
         * 
         * <p><b>Example Values:</b>
         * <ul>
         *   <li>8765.43 - Total purchases of $8,765.43</li>
         *   <li>-500.00 - Returns exceeded purchases by $500</li>
         *   <li>0.00 - No transactions or debits equal credits</li>
         * </ul>
         */
        @JsonProperty("totalAmount")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        private BigDecimal totalAmount;
    }

    /**
     * Builder Method
     * 
     * <p>Returns a builder instance for fluent construction of CardDetailResponse
     * objects. Provided by Lombok @SuperBuilder annotation, enabling construction
     * of both parent (CardResponse) and child (CardDetailResponse) fields through
     * single builder chain.
     * 
     * <p><b>Usage Example:</b>
     * <pre>
     * CardDetailResponse response = CardDetailResponse.builder()
     *     .cardNumber("************1234")
     *     .accountId(12345678901L)
     *     .cvv("123")
     *     .embossedName("JOHN DOE")
     *     .expirationDate(LocalDate.of(2025, 12, 31))
     *     .activeStatus("Y")
     *     .accountInfo(new AccountInfo(
     *         12345678901L,
     *         new BigDecimal("5234.56"),
     *         new BigDecimal("10000.00"),
     *         "Y"
     *     ))
     *     .transactionSummary(new TransactionSummary(
     *         42L,
     *         new BigDecimal("8765.43")
     *     ))
     *     .build();
     * </pre>
     * 
     * @return CardDetailResponseBuilder instance for fluent object construction
     */
    // Provided by Lombok @SuperBuilder annotation

    /**
     * Get Account Information
     * 
     * <p>Retrieves the nested AccountInfo object containing financial details
     * of the parent account linked to this card.
     * 
     * @return AccountInfo object with balance and limit data, or null if account
     *         information not populated (orphaned card or lazy loading issue)
     */
    // Provided by Lombok @Data annotation
    // public AccountInfo getAccountInfo() { return accountInfo; }

    /**
     * Set Account Information
     * 
     * <p>Sets the nested AccountInfo object with account financial details.
     * Called by CardDetailService after fetching account data through JPA
     * eager join or manual DTO mapping.
     * 
     * @param accountInfo AccountInfo object populated from account entity,
     *                    or null if account not found/not accessible
     */
    // Provided by Lombok @Data annotation
    // public void setAccountInfo(AccountInfo accountInfo) { this.accountInfo = accountInfo; }

    /**
     * Get Transaction Summary
     * 
     * <p>Retrieves the nested TransactionSummary object containing aggregated
     * transaction statistics for this card.
     * 
     * @return TransactionSummary object with count and total amount, or null
     *         if no transactions exist or aggregation not performed
     */
    // Provided by Lombok @Data annotation
    // public TransactionSummary getTransactionSummary() { return transactionSummary; }

    /**
     * Set Transaction Summary
     * 
     * <p>Sets the nested TransactionSummary object with aggregated transaction data.
     * Called by CardDetailService after executing aggregate query on transaction
     * repository.
     * 
     * @param transactionSummary TransactionSummary object populated from aggregate
     *                           query results, or null if no transactions found
     */
    // Provided by Lombok @Data annotation
    // public void setTransactionSummary(TransactionSummary transactionSummary) { this.transactionSummary = transactionSummary; }
}
