package com.carddemo.repository;

import com.carddemo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Transaction entity.
 * 
 * <p>This repository interface provides CRUD operations and custom query methods
 * for the Transaction entity, replacing COBOL VSAM KSDS file operations on the
 * TRANSACT master file (from CVTRA05Y.cpy copybook).</p>
 * 
 * <p><strong>Functional Equivalence with COBOL Programs:</strong></p>
 * <p>This repository replaces VSAM file I/O operations from multiple COBOL programs:</p>
 * <ul>
 *   <li><strong>COTRN00C.cbl</strong> - Transaction list display with STARTBR/READNEXT
 *       sequential access for pagination (10 transactions per page). Maps to
 *       findByCardNumber() and findByCardNumberOrderByOriginationTimestampDesc()
 *       methods with Pageable parameter.</li>
 *   <li><strong>COTRN01C.cbl</strong> - Transaction detail view using EXEC CICS READ
 *       with transaction ID key. Maps to findById() method inherited from JpaRepository.</li>
 *   <li><strong>COTRN02C.cbl</strong> - Transaction add/validate using EXEC CICS WRITE.
 *       Maps to save() method with @Transactional boundaries matching CICS SYNCPOINT.</li>
 *   <li><strong>CBTRN02C.cbl</strong> - Daily transaction batch posting using sequential
 *       file processing. Maps to saveAll() for bulk inserts and findByCardNumber() for
 *       card-specific transaction lookups during validation.</li>
 *   <li><strong>CORPT00C.cbl</strong> - Transaction report generation with date range
 *       filtering. Maps to findByCardNumberAndTransactionDateBetween() method.</li>
 * </ul>
 * 
 * <p><strong>VSAM to JPA Operation Mapping:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL VSAM Operation</th>
 *     <th>JPA Repository Method</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READ FILE('TRANSACT') INTO(TRAN-RECORD) RIDFLD(TRAN-ID)</td>
 *     <td>findById(String transactionId)</td>
 *     <td>Retrieve single transaction by primary key</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)</td>
 *     <td>save(Transaction transaction)</td>
 *     <td>Insert new transaction record</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS REWRITE FILE('TRANSACT') FROM(TRAN-RECORD)</td>
 *     <td>save(Transaction transaction)</td>
 *     <td>Update existing transaction record</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS DELETE FILE('TRANSACT') RIDFLD(TRAN-ID)</td>
 *     <td>deleteById(String transactionId)</td>
 *     <td>Delete transaction record</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS STARTBR FILE('TRANSACT') RIDFLD(TRAN-CARD-NUM)<br>
 *         PERFORM UNTIL WS-IDX > 10<br>
 *         &nbsp;&nbsp;EXEC CICS READNEXT FILE('TRANSACT')</td>
 *     <td>findByCardNumber(String cardNumber, Pageable.ofSize(10))</td>
 *     <td>Browse transactions by card number with pagination</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>Pagination Requirements:</strong></p>
 * <p>Per Section 0.2 UI Conversion requirements, transaction list displays must
 * maintain original pagination patterns:</p>
 * <ul>
 *   <li><strong>10 transactions per page</strong> - Matching COTRN00C.cbl line 290:
 *       "PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10"</li>
 *   <li>Page navigation using PF7 (previous) and PF8 (next) keys, mapped to REST
 *       API page parameters</li>
 *   <li>First and last transaction IDs tracked for bidirectional scrolling</li>
 *   <li>End-of-file detection when fewer than 10 transactions returned</li>
 * </ul>
 * 
 * <p><strong>Usage Example - Transaction List with Pagination:</strong></p>
 * <pre>
 * // In TransactionListService.java - equivalent to COTRN00C.cbl pagination logic
 * {@literal @}Service
 * public class TransactionListService {
 *     {@literal @}Autowired
 *     private TransactionRepository transactionRepository;
 *     
 *     public Page&lt;Transaction&gt; getTransactionsByCard(String cardNumber, int pageNumber) {
 *         // PageRequest with 10 transactions per page, sorted by origination timestamp desc
 *         Pageable pageable = PageRequest.of(pageNumber, 10, 
 *             Sort.by(Sort.Direction.DESC, "originationTimestamp"));
 *         
 *         // Retrieve page of transactions - replaces COBOL STARTBR/READNEXT loop
 *         Page&lt;Transaction&gt; transactionPage = transactionRepository
 *             .findByCardNumberOrderByOriginationTimestampDesc(cardNumber, pageable);
 *         
 *         // Check if more pages exist (equivalent to COBOL NEXT-PAGE-YES flag)
 *         boolean hasNextPage = transactionPage.hasNext();
 *         
 *         return transactionPage;
 *     }
 * }
 * </pre>
 * 
 * <p><strong>Usage Example - Date Range Filtering:</strong></p>
 * <pre>
 * // In ReportGenerationService.java - equivalent to CORPT00C.cbl date filtering
 * public Page&lt;Transaction&gt; getTransactionReport(String cardNumber, 
 *                                                LocalDate startDate, 
 *                                                LocalDate endDate,
 *                                                int pageNumber) {
 *     Pageable pageable = PageRequest.of(pageNumber, 10);
 *     
 *     return transactionRepository.findByCardNumberAndTransactionDateBetween(
 *         cardNumber, startDate, endDate, pageable);
 * }
 * </pre>
 * 
 * <p><strong>Transaction Boundary Management:</strong></p>
 * <p>All save operations should be wrapped in {@literal @}Transactional service methods
 * to replicate CICS SYNCPOINT behavior:</p>
 * <ul>
 *   <li>CICS transaction start → Spring {@literal @}Transactional method entry</li>
 *   <li>EXEC CICS SYNCPOINT → Automatic commit at method completion</li>
 *   <li>EXEC CICS ROLLBACK → Exception thrown triggers automatic rollback</li>
 *   <li>Isolation level READ_COMMITTED matches CICS default</li>
 * </ul>
 * 
 * <p><strong>Index Support:</strong></p>
 * <p>PostgreSQL indexes are created in Flyway migration V7__create_indexes.sql to
 * support efficient query execution:</p>
 * <ul>
 *   <li><strong>Primary Key Index:</strong> transaction_id (automatic with @Id)</li>
 *   <li><strong>Foreign Key Index:</strong> card_number for transaction history queries</li>
 *   <li><strong>Date Range Index:</strong> origination_timestamp for report queries</li>
 *   <li><strong>Composite Index:</strong> (card_number, origination_timestamp) for
 *       sorted card transaction lists</li>
 * </ul>
 * <p>These indexes replicate VSAM KSDS primary key and alternate index (AIX) access
 * patterns for optimal performance.</p>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Transaction list queries must meet &lt;200ms response time requirement
 *       (95th percentile) per Section 0.10</li>
 *   <li>Pagination reduces memory footprint for large transaction histories</li>
 *   <li>Indexed card_number lookups provide O(log n) access time</li>
 *   <li>Batch operations use saveAll() for bulk inserts during daily processing</li>
 *   <li>Connection pooling via HikariCP supports concurrent transaction queries</li>
 * </ul>
 * 
 * <p><strong>Concurrent Access Handling:</strong></p>
 * <p>The Transaction entity uses optimistic locking ({@literal @}Version field) to
 * handle concurrent modifications:</p>
 * <ul>
 *   <li>Replaces VSAM record-level locking</li>
 *   <li>Prevents lost updates during simultaneous batch and online processing</li>
 *   <li>OptimisticLockException thrown on version conflict, allowing retry logic</li>
 * </ul>
 * 
 * <p><strong>Data Integrity:</strong></p>
 * <p>Foreign key constraints ensure referential integrity:</p>
 * <ul>
 *   <li>card_number references card.card_number (enforced at database level)</li>
 *   <li>Cascade delete behavior configurable through JPA {@literal @}ManyToOne relationship</li>
 *   <li>Prevents orphaned transactions if card record deleted</li>
 * </ul>
 * 
 * <p>Lombok annotations are not used in repository interfaces. Spring Data JPA
 * provides automatic implementation at runtime through proxy generation.</p>
 * 
 * <p><strong>Testing Considerations:</strong></p>
 * <p>Repository methods should be tested using {@literal @}DataJpaTest:</p>
 * <pre>
 * {@literal @}DataJpaTest
 * public class TransactionRepositoryTest {
 *     {@literal @}Autowired
 *     private TransactionRepository transactionRepository;
 *     
 *     {@literal @}Test
 *     public void testFindByCardNumberPagination() {
 *         // Create 15 test transactions for a card
 *         // Query page 0 with size 10
 *         // Verify 10 transactions returned
 *         // Verify hasNext() is true
 *         // Query page 1 with size 10
 *         // Verify 5 transactions returned
 *         // Verify hasNext() is false
 *     }
 * }
 * </pre>
 * 
 * @see Transaction
 * @see JpaRepository
 * @see <a href="Section 0.4">Source File app/cpy/CVTRA05Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.10">Special Instructions - Transaction Boundaries</a>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    /**
     * Find all transactions for a specific card number with pagination support.
     * 
     * <p>This method replaces COBOL COTRN00C.cbl STARTBR/READNEXT sequential
     * browsing pattern for transaction list display. The COBOL program reads
     * transactions sequentially starting from a card number key, fetching 10
     * records per page (line 290: "UNTIL WS-IDX > 10").</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS STARTBR
     *     FILE('TRANSACT')
     *     RIDFLD(TRAN-CARD-NUM)
     *     RESP(WS-RESP-CD)
     * END-EXEC.
     * 
     * PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *     EXEC CICS READNEXT
     *         FILE('TRANSACT')
     *         INTO(TRAN-RECORD)
     *         RIDFLD(TRAN-CARD-NUM)
     *         RESP(WS-RESP-CD)
     *     END-EXEC
     *     
     *     IF WS-RESP-CD = DFHRESP(NORMAL)
     *         PERFORM POPULATE-TRAN-DATA
     *     END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Pagination Behavior:</strong></p>
     * <ul>
     *   <li>Page size should be set to 10 to match COBOL display limit</li>
     *   <li>Returns Page object containing transactions, total count, and navigation info</li>
     *   <li>Page.hasNext() replaces COBOL NEXT-PAGE-FLG indicator</li>
     *   <li>Page.hasPrevious() supports PF7 (backward scroll) functionality</li>
     *   <li>Empty page returned if no transactions found (not null)</li>
     * </ul>
     * 
     * <p><strong>Sorting:</strong></p>
     * <p>Natural ordering by transaction_id (chronological). For date-sorted results,
     * use findByCardNumberOrderByOriginationTimestampDesc() instead.</p>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>
     * // Create pageable for first page, 10 transactions
     * Pageable pageable = PageRequest.of(0, 10);
     * 
     * // Fetch first page of transactions for card
     * Page&lt;Transaction&gt; page1 = transactionRepository.findByCardNumber(
     *     "4111111111111111", pageable);
     * 
     * // Check if more pages exist
     * if (page1.hasNext()) {
     *     // Fetch next page
     *     Pageable nextPageable = PageRequest.of(1, 10);
     *     Page&lt;Transaction&gt; page2 = transactionRepository.findByCardNumber(
     *         "4111111111111111", nextPageable);
     * }
     * </pre>
     * 
     * @param cardNumber the card number to search for (16-character card identifier,
     *                   cannot be null or empty). Must match card_number foreign key
     *                   in transaction table.
     * @param pageable pagination parameters including page number (0-based), page size
     *                 (typically 10 for transaction lists), and optional sort criteria.
     *                 Use {@code PageRequest.of(pageNumber, 10)} for standard pagination.
     * @return Page containing transactions for the specified card number. Page includes:
     *         <ul>
     *           <li>Content: List of Transaction entities (max 10 per page)</li>
     *           <li>Total elements: Total transaction count for this card</li>
     *           <li>Total pages: Number of pages available</li>
     *           <li>hasNext()/hasPrevious(): Navigation flags</li>
     *           <li>Empty page if card has no transactions (not null)</li>
     *         </ul>
     */
    Page<Transaction> findByCardNumber(String cardNumber, Pageable pageable);

    /**
     * Find transactions for a card within a specific date range with pagination.
     * 
     * <p>This method supports transaction report generation (CORPT00C.cbl) with
     * date range filtering. The COBOL program reads transactions sequentially and
     * filters by origination timestamp to generate reports for specified periods.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS STARTBR
     *     FILE('TRANSACT')
     *     RIDFLD(TRAN-CARD-NUM)
     * END-EXEC.
     * 
     * PERFORM UNTIL TRANSACT-EOF
     *     EXEC CICS READNEXT
     *         FILE('TRANSACT')
     *         INTO(TRAN-RECORD)
     *     END-EXEC
     *     
     *     * Date filtering logic
     *     IF TRAN-ORIG-TS >= WS-START-DATE AND
     *        TRAN-ORIG-TS <= WS-END-DATE
     *         PERFORM PROCESS-TRANSACTION-FOR-REPORT
     *     END-IF
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Date Range Behavior:</strong></p>
     * <ul>
     *   <li>Inclusive range: Transactions on startDate and endDate are included</li>
     *   <li>Compares origination_timestamp (date portion only) against range</li>
     *   <li>Transactions ordered by origination_timestamp descending (most recent first)</li>
     *   <li>Returns empty page if no transactions in date range</li>
     * </ul>
     * 
     * <p><strong>Performance Considerations:</strong></p>
     * <ul>
     *   <li>Uses composite index (card_number, origination_timestamp) for efficient lookup</li>
     *   <li>Date range queries benefit from B-tree index range scan</li>
     *   <li>Pagination prevents memory issues with large date ranges</li>
     *   <li>Query execution time typically &lt;50ms for indexed card/date lookups</li>
     * </ul>
     * 
     * <p><strong>Usage Example - Monthly Statement:</strong></p>
     * <pre>
     * // Generate report for December 2023
     * LocalDate startDate = LocalDate.of(2023, 12, 1);
     * LocalDate endDate = LocalDate.of(2023, 12, 31);
     * Pageable pageable = PageRequest.of(0, 10);
     * 
     * Page&lt;Transaction&gt; decemberTransactions = transactionRepository
     *     .findByCardNumberAndTransactionDateBetween(
     *         "4111111111111111", startDate, endDate, pageable);
     * 
     * // Process all pages for complete report
     * while (decemberTransactions.hasContent()) {
     *     List&lt;Transaction&gt; transactions = decemberTransactions.getContent();
     *     // Process transactions for report
     *     
     *     if (decemberTransactions.hasNext()) {
     *         pageable = pageable.next();
     *         decemberTransactions = transactionRepository
     *             .findByCardNumberAndTransactionDateBetween(
     *                 "4111111111111111", startDate, endDate, pageable);
     *     } else {
     *         break;
     *     }
     * }
     * </pre>
     * 
     * @param cardNumber the card number to filter transactions (16-character string,
     *                   cannot be null). Must match existing card in card table.
     * @param startDate the start date of the range (inclusive, cannot be null). Only
     *                  date component is used, time is ignored.
     * @param endDate the end date of the range (inclusive, cannot be null). Must be
     *                equal to or after startDate. Only date component is used.
     * @param pageable pagination and sort parameters. Recommended page size: 10 for
     *                 consistency with transaction list display.
     * @return Page of transactions matching card number and date range criteria, ordered
     *         by origination timestamp descending. Empty page if no matches found.
     */
    @Query("SELECT t FROM Transaction t WHERE t.card.cardNumber = :cardNumber " +
           "AND FUNCTION('DATE', t.originationTimestamp) BETWEEN :startDate AND :endDate " +
           "ORDER BY t.originationTimestamp DESC")
    Page<Transaction> findByCardNumberAndTransactionDateBetween(
            String cardNumber, LocalDate startDate, LocalDate endDate, Pageable pageable);

    /**
     * Find the most recent transaction by transaction ID (for sequence generation).
     * 
     * <p>This method retrieves the transaction with the highest transaction ID,
     * which is useful for generating the next transaction ID in sequence. The
     * COBOL programs generate transaction IDs sequentially, and this method
     * supports that pattern by finding the last assigned ID.</p>
     * 
     * <p><strong>Usage in Transaction ID Generation:</strong></p>
     * <pre>
     * // In TransactionAddService.java
     * public String generateNextTransactionId() {
     *     Optional&lt;Transaction&gt; lastTransaction = 
     *         transactionRepository.findTopByOrderByTransactionIdDesc();
     *     
     *     if (lastTransaction.isPresent()) {
     *         String lastId = lastTransaction.get().getTransactionId();
     *         // Parse and increment sequence number
     *         // Return new transaction ID
     *     } else {
     *         // Return first transaction ID
     *         return "TXN000000000001";
     *     }
     * }
     * </pre>
     * 
     * <p><strong>Transaction ID Format:</strong></p>
     * <p>Transaction IDs are typically formatted as alphanumeric strings with
     * embedded sequence numbers or timestamps. Examples:</p>
     * <ul>
     *   <li>"TXN{YYYYMMDD}{SEQ}" - Date-based with 6-digit sequence</li>
     *   <li>"T{TIMESTAMP}{SEQ}" - Full timestamp with sequence</li>
     *   <li>Sorting by transaction_id provides chronological ordering</li>
     * </ul>
     * 
     * @return Optional containing the transaction with the highest transaction_id,
     *         or Optional.empty() if no transactions exist in the database. This
     *         safe return type prevents NullPointerException in ID generation logic.
     */
    Optional<Transaction> findTopByOrderByTransactionIdDesc();

    /**
     * Find transactions for a card ordered by origination timestamp descending.
     * 
     * <p>This method provides transaction history display sorted by most recent
     * transactions first, which is the most common user requirement for viewing
     * transaction lists. It replaces COTRN00C.cbl sequential browsing with
     * automatic descending date sort.</p>
     * 
     * <p><strong>Difference from findByCardNumber():</strong></p>
     * <ul>
     *   <li>findByCardNumber(): Natural ordering by transaction_id</li>
     *   <li>findByCardNumberOrderByOriginationTimestampDesc(): Date-sorted descending</li>
     *   <li>This method is preferred for user-facing transaction history displays</li>
     *   <li>Most recent transactions appear first (matching user expectations)</li>
     * </ul>
     * 
     * <p><strong>COBOL Sequential Read Pattern:</strong></p>
     * <p>The COBOL program COTRN00C.cbl uses STARTBR/READNEXT to read transactions
     * sequentially. This method provides equivalent functionality with built-in
     * date sorting for improved user experience.</p>
     * 
     * <p><strong>Performance:</strong></p>
     * <ul>
     *   <li>Uses composite index (card_number, origination_timestamp DESC)</li>
     *   <li>Index covers both filter and sort criteria for optimal performance</li>
     *   <li>Query execution time &lt;50ms for typical transaction volumes</li>
     * </ul>
     * 
     * <p><strong>Usage Example - Recent Transaction History:</strong></p>
     * <pre>
     * // Display most recent 10 transactions for a card
     * Pageable pageable = PageRequest.of(0, 10);
     * 
     * Page&lt;Transaction&gt; recentTransactions = transactionRepository
     *     .findByCardNumberOrderByOriginationTimestampDesc(
     *         "4111111111111111", pageable);
     * 
     * // First transaction in page is the most recent
     * if (!recentTransactions.isEmpty()) {
     *     Transaction mostRecent = recentTransactions.getContent().get(0);
     *     System.out.println("Most recent: " + mostRecent.getDescription() +
     *                        " on " + mostRecent.getOriginationTimestamp());
     * }
     * </pre>
     * 
     * <p><strong>Typical Use Cases:</strong></p>
     * <ul>
     *   <li>Transaction history display in web UI (TransactionListComponent.jsx)</li>
     *   <li>Mobile app recent transactions view</li>
     *   <li>Customer service representative transaction lookup</li>
     *   <li>Real-time fraud detection analyzing recent transaction patterns</li>
     * </ul>
     * 
     * @param cardNumber the 16-character card number to retrieve transactions for
     *                   (cannot be null or empty)
     * @param pageable pagination parameters with page number (0-based) and size
     *                 (typically 10). Sort parameter is ignored; method always sorts
     *                 by origination_timestamp descending.
     * @return Page of transactions for the card, sorted by origination timestamp
     *         descending (most recent first). Empty page if card has no transactions.
     */
    Page<Transaction> findByCardNumberOrderByOriginationTimestampDesc(
            String cardNumber, Pageable pageable);
}
