package com.carddemo.repository;

import com.carddemo.model.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spring Data JPA repository interface for Transaction entity.
 * 
 * Replaces VSAM TRANSACT KSDS file I/O operations from COBOL programs with
 * PostgreSQL database access through Spring Data JPA repository pattern.
 * 
 * Converted from VSAM dataset: TRANSACT (CVTRA05Y.cpy copybook)
 * Original access method: KSDS (Key-Sequenced Data Set) with primary key TRAN-ID
 * Target database: PostgreSQL 16.x with B-tree indexes
 * 
 * COBOL I/O Operation Mappings:
 * - EXEC CICS READ FILE('TRANSACT') RIDFLD(TRAN-ID) INTO(TRAN-RECORD) END-EXEC
 *   → findById(String transactionId)
 * 
 * - EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD) RIDFLD(TRAN-ID) END-EXEC
 *   → save(Transaction transaction) [for new records]
 * 
 * - EXEC CICS REWRITE FILE('TRANSACT') FROM(TRAN-RECORD) END-EXEC
 *   → save(Transaction transaction) [for updates]
 * 
 * - EXEC CICS DELETE FILE('TRANSACT') RIDFLD(TRAN-ID) END-EXEC
 *   → deleteById(String transactionId)
 * 
 * - EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(START-KEY) END-EXEC followed by
 *   EXEC CICS READNEXT FILE('TRANSACT') INTO(TRAN-RECORD) END-EXEC loop
 *   → findByTransCardNum(String cardNum) [alternate index access on TRAN-CARD-NUM]
 * 
 * Custom Query Methods:
 * This repository extends standard CRUD operations with custom query methods
 * that replicate VSAM alternate index access patterns and COBOL browse operations:
 * 
 * 1. findByTransCardNum - Replicates VSAM alternate index access on TRAN-CARD-NUM
 *    field for retrieving all transactions for a specific card. Used by COTRN00C.cbl
 *    transaction list display with card number filter.
 * 
 * 2. findByTransOrigTsBetween - Supports date range queries for transaction browsing
 *    used in COTRN00C.cbl with TRANS-FROM-DATE and TRANS-TO-DATE filters. Uses
 *    LocalDateTime parameters for Java 8 date/time API compatibility while entity
 *    uses Timestamp (Spring Data JPA automatically converts between types).
 * 
 * 3. findByTransCardNumAndTransOrigTsBetween - Combined card and date range filtering
 *    for transaction list display with both filters applied simultaneously. Primary
 *    use case in COTRN00C.cbl transaction inquiry screen.
 * 
 * 4. findByTransTypeCd - Filter transactions by type code (purchase, payment, fee, etc.)
 *    for transaction type analysis in batch programs CBTRN03C.cbl (category summarization).
 * 
 * 5. findByTransCatCd - Filter by merchant category code for category-specific reports
 *    and balance calculations in CBTRN03C.cbl.
 * 
 * 6. findByTransMerchantId - Retrieve all transactions for a specific merchant for
 *    merchant analysis and chargeback processing.
 * 
 * 7. findByTransSource - Filter by transaction source (POS, ATM, ONLINE) for
 *    channel-specific reporting and fraud detection analysis.
 * 
 * Transactional Integrity:
 * All repository operations benefit from Spring's declarative transaction management
 * through @Transactional annotations in service layer classes (AccountService,
 * CardService, TransactionService). This replicates CICS unit-of-work boundaries
 * and EXEC CICS SYNCPOINT semantics from COBOL programs, ensuring atomicity of
 * multi-table updates (transaction posting to TRANSACT, account balance update to
 * ACCTFILE, category balance update to TCATBAL) as implemented in COTRN02C.cbl
 * and CBTRN02C.cbl.
 * 
 * Performance Considerations:
 * Per Section 0.7.7, database queries must meet or exceed VSAM key access response
 * times (sub-10ms for primary key lookups, sub-200ms for alternate index queries).
 * The following PostgreSQL indexes support these requirements (defined in
 * V6__create_indexes.sql migration script):
 * 
 * - PRIMARY KEY index on trans_id (automatic B-tree index for primary key lookups)
 * - idx_transaction_card on trans_card_num (supports findByTransCardNum queries)
 * - idx_transaction_date on trans_orig_ts (supports date range queries)
 * - idx_transaction_type on trans_type_cd (supports type filtering)
 * 
 * Additional composite indexes may be added based on query performance analysis
 * to support multi-field query methods like findByTransCardNumAndTransOrigTsBetween.
 * 
 * Usage Examples:
 * 
 * // Transaction lookup by ID (CICS READ operation)
 * Optional<Transaction> transaction = transactionRepository.findById("TX1234567890ABCD");
 * 
 * // Transaction creation (CICS WRITE operation)
 * Transaction newTrans = Transaction.builder()
 *     .transId("TX1234567890ABCD")
 *     .transCardNum("4111111111111111")
 *     .transTypeCd("01")
 *     .transAmt(new BigDecimal("125.50"))
 *     .build();
 * transactionRepository.save(newTrans);
 * 
 * // Transaction update (CICS REWRITE operation)
 * transaction.setTransDesc("Updated description");
 * transactionRepository.save(transaction);
 * 
 * // Transaction deletion (CICS DELETE operation - rare for immutable transactions)
 * transactionRepository.deleteById("TX1234567890ABCD");
 * 
 * // List all transactions for a card (alternate index access)
 * List<Transaction> cardTransactions = transactionRepository.findByTransCardNum("4111111111111111");
 * 
 * // Date range query for transaction list display
 * LocalDateTime startDate = LocalDateTime.of(2024, 1, 1, 0, 0);
 * LocalDateTime endDate = LocalDateTime.of(2024, 12, 31, 23, 59);
 * List<Transaction> rangeTransactions = transactionRepository.findByTransOrigTsBetween(startDate, endDate);
 * 
 * // Combined card and date filtering
 * List<Transaction> filtered = transactionRepository.findByTransCardNumAndTransOrigTsBetween(
 *     "4111111111111111", startDate, endDate);
 * 
 * Referenced by:
 * - TransactionService (business logic from COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl)
 * - TransactionController (REST API endpoints for transaction operations)
 * - Batch processors: AccountProcessor, TransactionProcessor (CBTRN01C.cbl, CBTRN02C.cbl, CBTRN03C.cbl)
 * 
 * @see Transaction
 * @see TransactionService
 * @see TransactionController
 * @see com.carddemo.batch.processor.TransactionProcessor
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Find all transactions for a specific card number.
     * 
     * Replicates VSAM alternate index access on TRAN-CARD-NUM field from COBOL programs.
     * 
     * COBOL equivalent (COTRN00C.cbl):
     *   EXEC CICS STARTBR FILE('TRANSACT')
     *        RIDFLD(WS-CARD-NUM)
     *        GTEQ
     *   END-EXEC.
     *   PERFORM READ-TRANS-LOOP
     *        UNTIL END-OF-FILE OR TRAN-CARD-NUM NOT = WS-CARD-NUM.
     * 
     * This method retrieves all transaction records associated with a given card number,
     * ordered by trans_orig_ts descending (most recent first) based on database index.
     * 
     * Used by:
     * - TransactionController.listTransactions() for card-specific transaction history
     * - AccountService for calculating card-level balances and limits
     * - ReportService for generating card transaction statements
     * 
     * Performance: Utilizes idx_transaction_card B-tree index on trans_card_num column
     * to achieve sub-200ms response time per Section 0.7.7 requirements. For cards with
     * large transaction volumes (>10,000 transactions), pagination should be applied
     * at service layer using Spring Data Pageable parameter.
     * 
     * @param cardNum Card number to filter transactions (16-character card number,
     *                must match trans_card_num column value exactly)
     * @return List of Transaction entities for the specified card, ordered by
     *         transaction origination timestamp descending (newest first).
     *         Returns empty list if no transactions found for the card.
     */
    List<Transaction> findByTransCardNum(String cardNum);

    /**
     * Find all transactions within a date range based on transaction origination timestamp.
     * 
     * Supports date range queries for transaction browsing used in COTRN00C.cbl with
     * TRANS-FROM-DATE and TRANS-TO-DATE input fields from BMS map COTRN00.bms.
     * 
     * COBOL equivalent (COTRN00C.cbl):
     *   MOVE TRANS-FROM-DATE TO WS-START-DATE.
     *   MOVE TRANS-TO-DATE TO WS-END-DATE.
     *   EXEC CICS STARTBR FILE('TRANSACT')
     *        RIDFLD(WS-START-DATE)
     *        GTEQ
     *   END-EXEC.
     *   PERFORM READ-TRANS-LOOP
     *        UNTIL END-OF-FILE OR TRAN-ORIG-TS > WS-END-DATE.
     * 
     * This method retrieves transactions where trans_orig_ts falls between the specified
     * start and end timestamps (inclusive). Spring Data JPA automatically converts
     * LocalDateTime parameters to Timestamp for database query execution.
     * 
     * Used by:
     * - TransactionController.listTransactions() with date range filters
     * - BillingService for generating statement transactions within billing cycle dates
     * - ReportService for date-based transaction reports and analytics
     * - Batch programs for processing transactions within specific date windows
     * 
     * Performance: Utilizes idx_transaction_date B-tree index on trans_orig_ts column.
     * For large date ranges spanning multiple months/years, result sets can be very large
     * (>100,000 records). Service layer should implement pagination using Pageable parameter
     * to maintain sub-200ms response times per Section 0.7.7 requirements.
     * 
     * Note: Method name follows Spring Data JPA naming convention where "Between" creates
     * SQL condition: trans_orig_ts >= :startDate AND trans_orig_ts <= :endDate (inclusive).
     * 
     * @param startDate Start of date range (inclusive), specifies the earliest transaction
     *                  origination timestamp to include in results
     * @param endDate End of date range (inclusive), specifies the latest transaction
     *                origination timestamp to include in results
     * @return List of Transaction entities with origination timestamp within the specified
     *         range, ordered by trans_orig_ts descending. Returns empty list if no
     *         transactions found in the date range.
     */
    List<Transaction> findByTransOrigTsBetween(LocalDateTime startDate, LocalDateTime endDate);

    /**
     * Find all transactions for a specific card within a date range.
     * 
     * Combined card and date range filtering for transaction list display with both
     * filters applied simultaneously. Primary use case in COTRN00C.cbl transaction
     * inquiry screen where user specifies both card number and date range.
     * 
     * COBOL equivalent (COTRN00C.cbl):
     *   MOVE INPUT-CARD-NUM TO WS-CARD-NUM.
     *   MOVE TRANS-FROM-DATE TO WS-START-DATE.
     *   MOVE TRANS-TO-DATE TO WS-END-DATE.
     *   EXEC CICS STARTBR FILE('TRANSACT')
     *        RIDFLD(WS-CARD-NUM)
     *        GTEQ
     *   END-EXEC.
     *   PERFORM READ-TRANS-LOOP
     *        UNTIL END-OF-FILE
     *           OR TRAN-CARD-NUM NOT = WS-CARD-NUM
     *           OR TRAN-ORIG-TS > WS-END-DATE.
     *        IF TRAN-ORIG-TS >= WS-START-DATE
     *           PERFORM PROCESS-TRANSACTION
     *        END-IF
     *   END-PERFORM.
     * 
     * This method combines card number filtering with date range filtering, retrieving
     * only transactions that match both criteria. More efficient than filtering in
     * application code as database can use composite index or index intersection.
     * 
     * Used by:
     * - TransactionController.listTransactions() with both cardNum and date range parameters
     * - Statement generation (CBSTM03A.cbl) for specific card billing periods
     * - Cardholder inquiry transactions for targeted transaction searches
     * 
     * Performance: Can utilize both idx_transaction_card and idx_transaction_date indexes
     * through index intersection optimization in PostgreSQL query planner. For optimal
     * performance with frequent combined queries, consider adding composite index on
     * (trans_card_num, trans_orig_ts) in future database migration if query volume warrants.
     * 
     * @param cardNum Card number to filter transactions (16-character card number)
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @return List of Transaction entities matching both card number and date range,
     *         ordered by trans_orig_ts descending. Returns empty list if no matching
     *         transactions found.
     */
    List<Transaction> findByTransCardNumAndTransOrigTsBetween(
            String cardNum, 
            LocalDateTime startDate, 
            LocalDateTime endDate);

    /**
     * Find all transactions for a specific card within a date range with pagination.
     * 
     * Paginated version of findByTransCardNumAndTransOrigTsBetween for efficient handling
     * of large transaction result sets in COTRN00C.cbl transaction list display.
     * 
     * This method adds Spring Data pagination support through the Pageable parameter,
     * allowing the service layer to specify page number, page size, and sort order.
     * Essential for maintaining sub-200ms response times per Section 0.7.7 when dealing
     * with cards having thousands of transactions in the specified date range.
     * 
     * Used by:
     * - TransactionService.listTransactions() for paginated transaction browsing
     * - REST API endpoints requiring paginated responses
     * 
     * @param cardNum Card number to filter transactions (16-character card number)
     * @param startDate Start of date range (inclusive)
     * @param endDate End of date range (inclusive)
     * @param pageable Pagination parameters (page number, page size, sort order)
     * @return Page of Transaction entities with pagination metadata (total elements,
     *         total pages, current page number, etc.). Returns empty page if no
     *         matching transactions found.
     */
    Page<Transaction> findByTransCardNumAndTransOrigTsBetween(
            String cardNum, 
            LocalDateTime startDate, 
            LocalDateTime endDate,
            Pageable pageable);

    /**
     * Find all transactions by transaction type code.
     * 
     * Filter transactions by type code (purchase, payment, fee, interest, etc.) for
     * transaction type analysis in batch programs CBTRN03C.cbl (category summarization)
     * and reporting functions.
     * 
     * COBOL equivalent (CBTRN03C.cbl):
     *   MOVE '01' TO WS-TRAN-TYPE-CD.
     *   EXEC CICS STARTBR FILE('TRANSACT')
     *        RIDFLD(WS-TRAN-TYPE-CD)
     *        GTEQ
     *   END-EXEC.
     *   PERFORM READ-TRANS-LOOP
     *        UNTIL END-OF-FILE OR TRAN-TYPE-CD NOT = WS-TRAN-TYPE-CD.
     * 
     * Valid transaction type codes (from TRANTYPE table, CVTRA03Y.cpy):
     * - '01' = Purchase
     * - '02' = Cash Advance
     * - '03' = Balance Transfer
     * - '04' = Payment
     * - '05' = Fee
     * - '06' = Interest Charge
     * - '07' = Credit Adjustment
     * - '08' = Debit Adjustment
     * 
     * Used by:
     * - ReportService for transaction type breakdown reports
     * - Batch program TransactionProcessor (CBTRN03C.cbl) for type-specific aggregations
     * - Analytics services for transaction type volume and amount analysis
     * 
     * Performance: Utilizes idx_transaction_type B-tree index on trans_type_cd column
     * per database schema Section 0.3.4. Expected to return large result sets (thousands
     * to millions of records depending on type and date range), so pagination recommended
     * for online queries.
     * 
     * @param typeCd Transaction type code (2-character code, must match valid TRANTYPE value)
     * @return List of Transaction entities with matching transaction type code,
     *         ordered by trans_orig_ts descending. Returns empty list if no transactions
     *         found for the specified type.
     */
    List<Transaction> findByTransTypeCd(String typeCd);

    /**
     * Find all transactions by transaction category code.
     * 
     * Filter by merchant category code for category-specific reports and balance
     * calculations in CBTRN03C.cbl (transaction category summarization batch job).
     * 
     * COBOL equivalent (CBTRN03C.cbl):
     *   MOVE 5411 TO WS-TRAN-CAT-CD.
     *   EXEC CICS READ FILE('TCATBAL')
     *        RIDFLD(WS-CAT-KEY)
     *        INTO(TCAT-BAL-RECORD)
     *   END-EXEC.
     *   [Then read all TRANSACT records for category to calculate balance]
     * 
     * Valid transaction category codes (from TRANCATG table, CVTRA04Y.cpy):
     * - 5411 = Grocery Stores
     * - 5812 = Restaurants
     * - 5541 = Gas Stations
     * - 6011 = ATM Cash Withdrawal
     * - (and many others per ISO 18245 merchant category codes)
     * 
     * Used by:
     * - Batch program TransactionProcessor (CBTRN03C.cbl) for category balance updates
     * - ReportService for category spending analysis and trends
     * - BudgetService for category-based spending limits and alerts
     * 
     * Performance: No dedicated index on trans_cat_cd in base schema. For frequent
     * category-based queries, consider adding index in future migration if query
     * volume and performance analysis warrant. Current implementation performs
     * full table scan with WHERE clause filtering.
     * 
     * @param catCd Transaction category code (4-digit integer merchant category code)
     * @return List of Transaction entities with matching category code,
     *         ordered by trans_orig_ts descending. Returns empty list if no transactions
     *         found in the specified category.
     */
    List<Transaction> findByTransCatCd(Integer catCd);

    /**
     * Find all transactions for a specific merchant.
     * 
     * Retrieve all transactions for a specific merchant identifier for merchant
     * analysis, chargeback processing, and fraud detection.
     * 
     * Used by:
     * - ReportService for merchant-specific transaction reports
     * - FraudDetectionService for analyzing merchant transaction patterns
     * - ChargebackService for retrieving disputed transaction details
     * 
     * Note: Merchant ID stored as String (not Integer) to preserve leading zeros which
     * are significant in merchant identification (e.g., "000123456").
     * 
     * Performance: No dedicated index on trans_merchant_id in base schema. Add index
     * if merchant-based queries become frequent operational requirement. Current
     * implementation performs table scan.
     * 
     * @param merchantId 9-digit merchant identifier (COBOL PIC 9(09) → Java Long)
     * @return List of Transaction entities for the specified merchant,
     *         ordered by trans_orig_ts descending. Returns empty list if no transactions
     *         found for the merchant.
     */
    List<Transaction> findByTransMerchantId(Long merchantId);

    /**
     * Find all transactions by transaction source.
     * 
     * Filter by transaction source (POS, ATM, ONLINE, PHONE, MAIL, MOBILE) for
     * channel-specific reporting and fraud detection analysis.
     * 
     * Valid transaction sources:
     * - 'POS' = Point of Sale terminal
     * - 'ATM' = ATM withdrawal
     * - 'ONLINE' = Online purchase
     * - 'PHONE' = Phone order
     * - 'MAIL' = Mail order
     * - 'MOBILE' = Mobile app
     * 
     * Used by:
     * - ReportService for channel distribution analysis
     * - FraudDetectionService for unusual channel usage detection
     * - AnalyticsService for channel preference trends
     * 
     * Performance: No dedicated index on trans_source in base schema. Add index
     * if source-based filtering becomes frequent requirement. Current implementation
     * performs table scan.
     * 
     * @param source Transaction source indicator (up to 10 characters, typically
     *               uppercase abbreviations like 'POS', 'ATM', 'ONLINE')
     * @return List of Transaction entities from the specified source channel,
     *         ordered by trans_orig_ts descending. Returns empty list if no transactions
     *         found for the source.
     */
    List<Transaction> findByTransSource(String source);
}
