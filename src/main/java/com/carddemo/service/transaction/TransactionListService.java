package com.carddemo.service.transaction;

import com.carddemo.dto.response.TransactionResponse;
import com.carddemo.entity.Transaction;
import com.carddemo.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Transaction listing service implementing pagination with exactly 10 transactions per page.
 * 
 * <p>This service transforms COBOL COTRN00C.cbl transaction list program to modern Spring Data JPA
 * implementation. The COBOL program uses VSAM STARTBR/READNEXT sequential browsing with PERFORM VARYING
 * loop to fetch exactly 10 transactions per page. This service replicates that pagination behavior using
 * Spring Data Pageable with fixed page size of 10.</p>
 * 
 * <h2>COBOL Source Program Transformation</h2>
 * <p>Source: app/cbl/COTRN00C.cbl - Transaction List (CICS Transaction CT00)</p>
 * 
 * <h3>COBOL Pagination Logic (Lines 285-295):</h3>
 * <pre>
 * PROCESS-PAGE-FORWARD.
 *     EXEC CICS STARTBR
 *         FILE('TRANSACT')
 *         RIDFLD(CDEMO-CT00-TRNID-FIRST)
 *         RESP(WS-RESP-CD)
 *     END-EXEC.
 *     
 *     MOVE 0 TO WS-IDX
 *     PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
 *         EXEC CICS READNEXT
 *             FILE('TRANSACT')
 *             INTO(TRAN-RECORD)
 *             RESP(WS-RESP-CD)
 *         END-EXEC
 *         
 *         IF WS-RESP-CD = DFHRESP(NORMAL)
 *             PERFORM POPULATE-TRAN-DATA
 *         ELSE
 *             SET TRANSACT-EOF TO TRUE
 *             EXIT PERFORM
 *         END-IF
 *     END-PERFORM.
 * </pre>
 * 
 * <h3>Spring Data JPA Equivalent:</h3>
 * <pre>
 * // Fixed page size of 10 matching COBOL "UNTIL WS-IDX > 10"
 * Pageable pageable = PageRequest.of(pageNumber, 10);
 * 
 * // Dynamic filtering replaces COBOL sequential read with conditions
 * Specification&lt;Transaction&gt; spec = buildSpecification(cardNumber, startDate, endDate, ...);
 * 
 * // Single query replaces STARTBR/READNEXT loop
 * Page&lt;Transaction&gt; page = transactionRepository.findAll(spec, pageable);
 * </pre>
 * 
 * <h2>Pagination Requirements</h2>
 * <ul>
 *   <li><b>Page Size:</b> Fixed at 10 transactions per page (matching COBOL WS-IDX > 10 limit)</li>
 *   <li><b>Page Number:</b> Zero-based index (page 0, 1, 2, ...) passed as method parameter</li>
 *   <li><b>Navigation:</b> Page.hasNext() replaces COBOL NEXT-PAGE-FLG indicator</li>
 *   <li><b>Total Count:</b> Page.getTotalElements() provides total transaction count across all pages</li>
 *   <li><b>Empty Result:</b> Returns empty Page (not null) when no transactions match filters</li>
 * </ul>
 * 
 * <h2>Filtering Capabilities</h2>
 * <p>This service supports comprehensive transaction filtering replacing COBOL sequential file
 * positioning and conditional logic:</p>
 * 
 * <h3>1. Card Number Filter</h3>
 * <p>COBOL: EXEC CICS STARTBR RIDFLD(TRAN-CARD-NUM)</p>
 * <p>Java: WHERE t.card.cardNumber = :cardNumber</p>
 * 
 * <h3>2. Date Range Filter</h3>
 * <p>COBOL: IF TRAN-ORIG-TS >= WS-START-DATE AND TRAN-ORIG-TS <= WS-END-DATE</p>
 * <p>Java: WHERE CAST(t.originationTimestamp AS date) BETWEEN :startDate AND :endDate</p>
 * 
 * <h3>3. Transaction Type Filter</h3>
 * <p>COBOL: IF TRAN-TYPE-CD = WS-TRAN-TYPE</p>
 * <p>Java: WHERE t.typeCode = :transactionType</p>
 * 
 * <h3>4. Merchant Name Search</h3>
 * <p>COBOL: IF TRAN-MERCHANT-NAME CONTAINS WS-SEARCH-TEXT</p>
 * <p>Java: WHERE LOWER(t.merchantName) LIKE LOWER('%' || :merchantName || '%')</p>
 * 
 * <h3>5. Amount Range Filter</h3>
 * <p>COBOL: IF TRAN-AMT >= WS-MIN-AMT AND TRAN-AMT <= WS-MAX-AMT</p>
 * <p>Java: WHERE t.amount BETWEEN :minAmount AND :maxAmount</p>
 * 
 * <h2>COMMAREA State Replacement</h2>
 * <p>The COBOL program maintains state in COMMAREA (CDEMO-CT00-INFO) for pagination:</p>
 * <ul>
 *   <li>CDEMO-CT00-TRNID-FIRST: First transaction ID on page → Not needed (stateless REST)</li>
 *   <li>CDEMO-CT00-TRNID-LAST: Last transaction ID on page → Not needed (stateless REST)</li>
 *   <li>CDEMO-CT00-PAGE-NUM: Current page number → Passed as method parameter</li>
 *   <li>CDEMO-CT00-NEXT-PAGE-FLG: More pages indicator → Page.hasNext() method</li>
 * </ul>
 * 
 * <h2>BMS Screen Field Mapping</h2>
 * <p>COBOL program populates BMS map COTRN00M with 10 transaction rows (SEL0001I-SEL0010I).
 * This service returns Page&lt;TransactionResponse&gt; with equivalent data in JSON format.</p>
 * 
 * <h3>COBOL Screen Fields → JSON Response:</h3>
 * <pre>
 * SEL0001I-SEL0010I (selection flags)      → Not applicable (HTTP request parameters)
 * TRNID01I-TRNID10I (transaction IDs)      → transactionResponse.transactionId
 * TRNTYP01-TRNTYP10 (transaction types)    → transactionResponse.typeCode
 * TRNCAT01-TRNCAT10 (category codes)       → transactionResponse.categoryCode
 * TRNSRC01-TRNSRC10 (transaction sources)  → transactionResponse.source
 * TRNDESC01-TRNDESC10 (descriptions)       → transactionResponse.description
 * TRNAMT01-TRNAMT10 (amounts)              → transactionResponse.amount
 * TRNDATE01-TRNDATE10 (dates)              → transactionResponse.originationTimestamp
 * </pre>
 * 
 * <h2>Error Handling</h2>
 * <p>COBOL uses WS-ERR-FLG and 88-level conditions (ERR-FLG-ON/ERR-FLG-OFF) for error tracking.
 * This service uses Spring exception handling with custom exceptions for error conditions:</p>
 * <ul>
 *   <li>Invalid date range (endDate before startDate) → IllegalArgumentException</li>
 *   <li>Invalid amount range (maxAmount less than minAmount) → IllegalArgumentException</li>
 *   <li>Database errors → Propagated as DataAccessException</li>
 *   <li>Empty result set → Returns empty Page (not an error)</li>
 * </ul>
 * 
 * <h2>Transaction Boundary</h2>
 * <p>Annotated with @Transactional(readOnly = true) matching COBOL CICS read-only transaction
 * behavior. Read-only transactions optimize database access by:</p>
 * <ul>
 *   <li>Disabling Hibernate dirty checking (performance improvement)</li>
 *   <li>Preventing flush operations (no writes allowed)</li>
 *   <li>Enabling database read-only optimizations</li>
 *   <li>Maintaining ACID properties for consistent reads</li>
 * </ul>
 * 
 * <h2>Performance Considerations</h2>
 * <ul>
 *   <li><b>Database Indexes:</b> Composite index (card_number, origination_timestamp) supports efficient filtering</li>
 *   <li><b>Query Optimization:</b> JPA Specification generates optimized SQL with WHERE clause pushdown</li>
 *   <li><b>Pagination:</b> LIMIT 10 OFFSET prevents loading all transactions into memory</li>
 *   <li><b>Response Time:</b> Target &lt;200ms at 95th percentile matching mainframe baseline</li>
 *   <li><b>Concurrent Users:</b> Supports 150+ concurrent users through connection pooling</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // List transactions for card with date range filter
 * Page&lt;TransactionResponse&gt; page = transactionListService.listTransactions(
 *     PageRequest.of(0, 10),                    // First page, 10 items
 *     "4111111111111111",                        // Card number filter
 *     LocalDate.of(2024, 1, 1),                 // Start date
 *     LocalDate.of(2024, 1, 31),                // End date
 *     "01",                                      // Purchase transactions only
 *     "WALMART",                                 // Merchant name contains "WALMART"
 *     new BigDecimal("10.00"),                   // Minimum amount $10
 *     new BigDecimal("500.00")                   // Maximum amount $500
 * );
 * 
 * // Access results
 * List&lt;TransactionResponse&gt; transactions = page.getContent();  // Up to 10 transactions
 * long totalCount = page.getTotalElements();                     // Total matching count
 * int totalPages = page.getTotalPages();                         // Total pages
 * boolean hasMore = page.hasNext();                              // More pages available
 * </pre>
 * 
 * <h2>REST API Integration</h2>
 * <p>This service is called by TransactionController for the following endpoint:</p>
 * <pre>
 * GET /api/transactions?page=0&size=10&cardNumber=4111111111111111&startDate=2024-01-01&endDate=2024-01-31
 * </pre>
 * 
 * <h2>React Component Integration</h2>
 * <p>This service provides data for TransactionListComponent.jsx which displays:</p>
 * <ul>
 *   <li>Paginated transaction table with 10 rows per page</li>
 *   <li>Filter controls for card number, date range, transaction type, merchant, amount</li>
 *   <li>Previous/Next navigation buttons (replacing COBOL PF7/PF8 keys)</li>
 *   <li>Total transaction count and current page indicator</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * 
 * @see Transaction
 * @see TransactionRepository
 * @see TransactionResponse
 * @see com.carddemo.controller.TransactionController
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionListService {

    /**
     * Transaction repository for database access operations.
     * Injected via constructor using Lombok @RequiredArgsConstructor.
     */
    private final TransactionRepository transactionRepository;

    /**
     * Fixed page size constant matching COBOL PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10.
     * This constant ensures consistent pagination behavior across all transaction list queries.
     */
    private static final int PAGE_SIZE = 10;

    /**
     * Card number mask pattern for PCI DSS compliance.
     * Displays only last 4 digits of card number (e.g., "************1234").
     */
    private static final String CARD_MASK_PATTERN = "************%s";

    /**
     * List transactions with comprehensive filtering and pagination support.
     * 
     * <p>This method transforms COBOL COTRN00C.cbl PROCESS-PAGE-FORWARD paragraph logic
     * to Spring Data JPA dynamic query with Specification. The COBOL program uses VSAM
     * STARTBR/READNEXT sequential browsing with PERFORM loop fetching exactly 10 records.
     * This method achieves identical pagination behavior using PageRequest with fixed
     * page size of 10.</p>
     * 
     * <h3>COBOL Equivalent Logic:</h3>
     * <pre>
     * PROCESS-PAGE-FORWARD.
     *     * Position to start of transactions
     *     EXEC CICS STARTBR
     *         FILE('TRANSACT')
     *         RIDFLD(TRAN-CARD-NUM)
     *         RESP(WS-RESP-CD)
     *     END-EXEC.
     *     
     *     * Fetch exactly 10 transactions
     *     MOVE 0 TO WS-IDX
     *     PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
     *         EXEC CICS READNEXT
     *             FILE('TRANSACT')
     *             INTO(TRAN-RECORD)
     *             RESP(WS-RESP-CD)
     *         END-EXEC
     *         
     *         IF WS-RESP-CD = DFHRESP(NORMAL)
     *             * Apply filters
     *             IF TRAN-CARD-NUM = WS-FILTER-CARD-NUM OR WS-FILTER-CARD-NUM = SPACES
     *                 IF TRAN-ORIG-TS >= WS-START-DATE AND TRAN-ORIG-TS <= WS-END-DATE
     *                     IF TRAN-TYPE-CD = WS-FILTER-TYPE OR WS-FILTER-TYPE = SPACES
     *                         PERFORM POPULATE-TRAN-DATA
     *                     END-IF
     *                 END-IF
     *             END-IF
     *         ELSE
     *             SET TRANSACT-EOF TO TRUE
     *             EXIT PERFORM
     *         END-IF
     *     END-PERFORM.
     * </pre>
     * 
     * <h3>Spring Data JPA Implementation:</h3>
     * <pre>
     * // Build dynamic specification for all filters
     * Specification&lt;Transaction&gt; spec = buildSpecification(cardNumber, startDate, endDate, ...);
     * 
     * // Create pageable with fixed page size 10
     * Pageable pageable = PageRequest.of(pageNumber, 10);
     * 
     * // Execute single optimized query
     * Page&lt;Transaction&gt; page = transactionRepository.findAll(spec, pageable);
     * 
     * // Map entities to response DTOs
     * Page&lt;TransactionResponse&gt; response = page.map(this::mapToResponse);
     * </pre>
     * 
     * <h3>Filter Parameter Handling:</h3>
     * <p>All filter parameters are optional (nullable). When a filter parameter is null,
     * that filter is not applied to the query. This matches COBOL behavior where SPACES
     * or LOW-VALUES in filter fields means "no filter".</p>
     * 
     * <h3>Pagination Behavior:</h3>
     * <ul>
     *   <li>Page size is fixed at 10 (not parameterized) matching COBOL loop limit</li>
     *   <li>Page number is 0-based (page 0 is first page, page 1 is second page, etc.)</li>
     *   <li>Empty page returned if no transactions match filters (never null)</li>
     *   <li>Page.hasNext() indicates if more pages available (replaces NEXT-PAGE-FLG)</li>
     *   <li>Page.getTotalElements() provides total count across all pages</li>
     * </ul>
     * 
     * <h3>Date Range Filter Logic:</h3>
     * <p>Both startDate and endDate are inclusive. Date comparison uses only the date
     * portion of originationTimestamp, ignoring time component. If both are null, no
     * date filtering is applied.</p>
     * 
     * <h3>Amount Range Filter Logic:</h3>
     * <p>Both minAmount and maxAmount are inclusive. BigDecimal comparison ensures exact
     * precision matching COBOL COMP-3 decimal arithmetic. If both are null, no amount
     * filtering is applied.</p>
     * 
     * <h3>Merchant Name Search:</h3>
     * <p>Case-insensitive partial match using SQL LIKE with wildcards. For example,
     * merchantName="walmart" matches "WALMART SUPERCENTER", "Walmart Store #1234", etc.</p>
     * 
     * <h3>Card Number Masking:</h3>
     * <p>The returned TransactionResponse DTOs contain masked card numbers showing only
     * the last 4 digits for PCI DSS compliance. Full card numbers are never exposed in
     * API responses.</p>
     * 
     * <h3>Error Handling:</h3>
     * <ul>
     *   <li><b>Invalid date range:</b> Throws IllegalArgumentException if endDate before startDate</li>
     *   <li><b>Invalid amount range:</b> Throws IllegalArgumentException if maxAmount less than minAmount</li>
     *   <li><b>Database errors:</b> Propagated as Spring DataAccessException</li>
     *   <li><b>Empty result:</b> Returns empty Page (not error condition)</li>
     * </ul>
     * 
     * <h3>Performance Notes:</h3>
     * <ul>
     *   <li>Uses composite database index (card_number, origination_timestamp) for optimal query performance</li>
     *   <li>JPA Specification generates efficient SQL with WHERE clause combining all filters</li>
     *   <li>Pagination with LIMIT 10 OFFSET prevents memory issues with large result sets</li>
     *   <li>Read-only transaction disables Hibernate dirty checking for better performance</li>
     *   <li>Target response time: &lt;200ms at 95th percentile</li>
     * </ul>
     * 
     * @param pageable pagination parameters including page number (0-based). Page size parameter
     *                 is ignored; method always uses fixed page size of 10 matching COBOL behavior.
     *                 Typically created using PageRequest.of(pageNumber, 10).
     * @param cardNumber optional card number filter (16-character string). When provided, returns
     *                   only transactions for this specific card. Pass null for no card filter.
     *                   Example: "4111111111111111"
     * @param startDate optional start date for date range filter (inclusive). When provided with
     *                  endDate, returns transactions with origination timestamp >= startDate.
     *                  Pass null for no start date filter. Example: LocalDate.of(2024, 1, 1)
     * @param endDate optional end date for date range filter (inclusive). When provided with
     *                startDate, returns transactions with origination timestamp <= endDate.
     *                Pass null for no end date filter. Must be >= startDate if both provided.
     *                Example: LocalDate.of(2024, 1, 31)
     * @param transactionType optional transaction type code filter. When provided, returns only
     *                        transactions with matching type code. Pass null for no type filter.
     *                        Valid values: "01" (purchase), "02" (cash advance), "03" (payment),
     *                        "04" (refund), "05" (fee). Example: "01"
     * @param merchantName optional merchant name search string. When provided, performs case-insensitive
     *                     partial match on merchant_name field. Pass null for no merchant filter.
     *                     Example: "WALMART" matches "WALMART SUPERCENTER", "Walmart Store", etc.
     * @param minAmount optional minimum transaction amount filter (inclusive). When provided with
     *                  maxAmount, returns transactions with amount >= minAmount. Pass null for no
     *                  minimum amount filter. Uses BigDecimal for exact precision.
     *                  Example: new BigDecimal("10.00")
     * @param maxAmount optional maximum transaction amount filter (inclusive). When provided with
     *                  minAmount, returns transactions with amount <= maxAmount. Pass null for no
     *                  maximum amount filter. Must be >= minAmount if both provided.
     *                  Example: new BigDecimal("500.00")
     * @return Page containing TransactionResponse DTOs with pagination metadata. The Page includes:
     *         <ul>
     *           <li>Content: List of TransactionResponse objects (maximum 10 per page)</li>
     *           <li>Total elements: Total count of transactions matching all filters across all pages</li>
     *           <li>Total pages: Number of pages required to display all matching transactions</li>
     *           <li>Current page number: 0-based page index</li>
     *           <li>hasNext(): Boolean indicating if more pages available</li>
     *           <li>hasPrevious(): Boolean indicating if previous pages exist</li>
     *           <li>Empty page if no transactions match filters (never null)</li>
     *         </ul>
     * @throws IllegalArgumentException if date range is invalid (endDate before startDate) or
     *                                  amount range is invalid (maxAmount less than minAmount)
     */
    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(
            Pageable pageable,
            String cardNumber,
            LocalDate startDate,
            LocalDate endDate,
            String transactionType,
            String merchantName,
            BigDecimal minAmount,
            BigDecimal maxAmount) {
        
        log.info("Listing transactions with filters - cardNumber: {}, dateRange: {} to {}, type: {}, " +
                 "merchant: {}, amountRange: {} to {}, page: {}",
                 cardNumber != null ? maskCardNumber(cardNumber) : "null",
                 startDate, endDate, transactionType, merchantName, minAmount, maxAmount,
                 pageable.getPageNumber());
        
        // Validate date range if both dates provided
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            log.error("Invalid date range: endDate {} is before startDate {}", endDate, startDate);
            throw new IllegalArgumentException(
                "End date must be equal to or after start date. Provided: startDate=" + startDate +
                ", endDate=" + endDate);
        }
        
        // Validate amount range if both amounts provided
        if (minAmount != null && maxAmount != null && maxAmount.compareTo(minAmount) < 0) {
            log.error("Invalid amount range: maxAmount {} is less than minAmount {}", maxAmount, minAmount);
            throw new IllegalArgumentException(
                "Maximum amount must be greater than or equal to minimum amount. Provided: minAmount=" +
                minAmount + ", maxAmount=" + maxAmount);
        }
        
        // Override page size to fixed value of 10 matching COBOL pagination limit
        // COBOL: PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10
        Pageable adjustedPageable = PageRequest.of(
            pageable.getPageNumber(),
            PAGE_SIZE,
            pageable.getSort()
        );
        
        // Build dynamic specification based on provided filters
        Specification<Transaction> spec = buildTransactionSpecification(
            cardNumber,
            startDate,
            endDate,
            transactionType,
            merchantName,
            minAmount,
            maxAmount
        );
        
        // Execute query with all filters and pagination
        Page<Transaction> transactionPage = transactionRepository.findAll(spec, adjustedPageable);
        
        log.info("Retrieved {} transactions out of {} total on page {}/{}",
                 transactionPage.getNumberOfElements(),
                 transactionPage.getTotalElements(),
                 transactionPage.getNumber() + 1,
                 transactionPage.getTotalPages());
        
        // Map Transaction entities to TransactionResponse DTOs
        Page<TransactionResponse> responsePage = transactionPage.map(this::mapToResponse);
        
        if (transactionPage.isEmpty()) {
            log.warn("No transactions found matching the provided filter criteria");
        } else {
            log.debug("Successfully mapped {} transactions to response DTOs", 
                     transactionPage.getNumberOfElements());
        }
        
        return responsePage;
    }

    /**
     * Build JPA Specification for dynamic transaction filtering.
     * 
     * <p>This method constructs a type-safe JPA Criteria API specification combining all
     * provided filter parameters with AND logic. Each filter parameter is optional; when
     * null, that filter is not applied to the query.</p>
     * 
     * <p>This replaces COBOL sequential file reading with conditional logic:</p>
     * <pre>
     * COBOL:
     * IF TRAN-CARD-NUM = WS-FILTER-CARD-NUM OR WS-FILTER-CARD-NUM = SPACES
     *     IF TRAN-ORIG-TS >= WS-START-DATE AND TRAN-ORIG-TS <= WS-END-DATE
     *         IF TRAN-TYPE-CD = WS-FILTER-TYPE OR WS-FILTER-TYPE = SPACES
     *             PERFORM PROCESS-TRANSACTION
     *         END-IF
     *     END-IF
     * END-IF
     * 
     * Java:
     * WHERE (cardNumber IS NULL OR t.card.cardNumber = :cardNumber)
     *   AND (startDate IS NULL OR CAST(t.originationTimestamp AS date) >= :startDate)
     *   AND (endDate IS NULL OR CAST(t.originationTimestamp AS date) <= :endDate)
     *   AND (transactionType IS NULL OR t.typeCode = :transactionType)
     *   AND (merchantName IS NULL OR LOWER(t.merchantName) LIKE LOWER(:merchantName))
     *   AND (minAmount IS NULL OR t.amount >= :minAmount)
     *   AND (maxAmount IS NULL OR t.amount <= :maxAmount)
     * </pre>
     * 
     * <h3>Specification Building Logic:</h3>
     * <ul>
     *   <li>Start with null specification (no filters)</li>
     *   <li>For each non-null filter parameter, add corresponding predicate with AND logic</li>
     *   <li>Return combined specification for repository.findAll(spec, pageable)</li>
     * </ul>
     * 
     * <h3>Performance Optimization:</h3>
     * <p>The generated SQL uses database indexes effectively:</p>
     * <ul>
     *   <li>card_number filter uses card_number index</li>
     *   <li>date range uses composite (card_number, origination_timestamp) index</li>
     *   <li>Multiple filters combined in single WHERE clause (no multiple queries)</li>
     * </ul>
     * 
     * @param cardNumber optional card number filter (exact match)
     * @param startDate optional start date for range filter (inclusive)
     * @param endDate optional end date for range filter (inclusive)
     * @param transactionType optional transaction type code filter (exact match)
     * @param merchantName optional merchant name filter (case-insensitive partial match)
     * @param minAmount optional minimum amount filter (inclusive)
     * @param maxAmount optional maximum amount filter (inclusive)
     * @return JPA Specification combining all provided filters with AND logic. Returns
     *         specification that matches all transactions if no filters provided.
     */
    private Specification<Transaction> buildTransactionSpecification(
            String cardNumber,
            LocalDate startDate,
            LocalDate endDate,
            String transactionType,
            String merchantName,
            BigDecimal minAmount,
            BigDecimal maxAmount) {
        
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            // Card number filter (exact match)
            // COBOL: IF TRAN-CARD-NUM = WS-FILTER-CARD-NUM OR WS-FILTER-CARD-NUM = SPACES
            if (cardNumber != null && !cardNumber.trim().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("card").get("cardNumber"), cardNumber));
                log.debug("Added card number filter: {}", maskCardNumber(cardNumber));
            }
            
            // Start date filter (inclusive)
            // COBOL: IF TRAN-ORIG-TS >= WS-START-DATE
            if (startDate != null) {
                LocalDateTime startDateTime = startDate.atStartOfDay();
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                    root.get("originationTimestamp"), startDateTime));
                log.debug("Added start date filter: {}", startDate);
            }
            
            // End date filter (inclusive)
            // COBOL: IF TRAN-ORIG-TS <= WS-END-DATE
            if (endDate != null) {
                LocalDateTime endDateTime = endDate.atTime(23, 59, 59);
                predicates.add(criteriaBuilder.lessThanOrEqualTo(
                    root.get("originationTimestamp"), endDateTime));
                log.debug("Added end date filter: {}", endDate);
            }
            
            // Transaction type filter (exact match)
            // COBOL: IF TRAN-TYPE-CD = WS-FILTER-TYPE OR WS-FILTER-TYPE = SPACES
            if (transactionType != null && !transactionType.trim().isEmpty()) {
                predicates.add(criteriaBuilder.equal(root.get("typeCode"), transactionType));
                log.debug("Added transaction type filter: {}", transactionType);
            }
            
            // Merchant name filter (case-insensitive partial match)
            // COBOL: IF TRAN-MERCHANT-NAME CONTAINS WS-SEARCH-TEXT
            if (merchantName != null && !merchantName.trim().isEmpty()) {
                String likePattern = "%" + merchantName.toLowerCase() + "%";
                predicates.add(criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("merchantName")), likePattern));
                log.debug("Added merchant name filter: {}", merchantName);
            }
            
            // Minimum amount filter (inclusive, using BigDecimal comparison)
            // COBOL: IF TRAN-AMT >= WS-MIN-AMT
            if (minAmount != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("amount"), minAmount));
                log.debug("Added minimum amount filter: {}", minAmount);
            }
            
            // Maximum amount filter (inclusive, using BigDecimal comparison)
            // COBOL: IF TRAN-AMT <= WS-MAX-AMT
            if (maxAmount != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("amount"), maxAmount));
                log.debug("Added maximum amount filter: {}", maxAmount);
            }
            
            // Combine all predicates with AND logic
            if (predicates.isEmpty()) {
                log.debug("No filters applied - returning all transactions");
                return criteriaBuilder.conjunction(); // Match all
            } else {
                log.debug("Combined {} filter predicates with AND logic", predicates.size());
                return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
            }
        };
    }

    /**
     * Map Transaction entity to TransactionResponse DTO.
     * 
     * <p>This method transforms JPA Transaction entity to REST API response DTO with:</p>
     * <ul>
     *   <li>Card number masking for PCI DSS compliance (show only last 4 digits)</li>
     *   <li>BigDecimal amount with explicit scale=2 for exact monetary precision</li>
     *   <li>LocalDateTime timestamps in ISO 8601 format</li>
     *   <li>All merchant information and transaction details</li>
     * </ul>
     * 
     * <p>This replaces COBOL POPULATE-TRAN-DATA paragraph that moves transaction record
     * fields to BMS screen map fields:</p>
     * <pre>
     * POPULATE-TRAN-DATA.
     *     MOVE TRAN-ID TO TRNID01I
     *     MOVE TRAN-TYPE-CD TO TRNTYP01
     *     MOVE TRAN-CAT-CD TO TRNCAT01
     *     MOVE TRAN-SOURCE TO TRNSRC01
     *     MOVE TRAN-DESC TO TRNDESC01
     *     MOVE TRAN-AMT TO WS-TRAN-AMT
     *     MOVE WS-TRAN-AMT TO TRNAMT01
     *     MOVE TRAN-ORIG-TS TO WS-TRAN-DATE
     *     MOVE WS-TRAN-DATE TO TRNDATE01.
     * </pre>
     * 
     * <h3>Card Number Masking:</h3>
     * <p>Full 16-digit card PAN is masked to show only last 4 digits for PCI DSS compliance.
     * Format: "************1234" (12 asterisks + last 4 digits)</p>
     * 
     * <h3>Amount Formatting:</h3>
     * <p>BigDecimal amount is preserved with exact scale=2 matching COBOL PIC S9(09)V99
     * COMP-3 decimal precision. The @JsonFormat annotation in TransactionResponse ensures
     * amount is serialized as JSON string to prevent JavaScript precision loss.</p>
     * 
     * <h3>Timestamp Conversion:</h3>
     * <p>COBOL PIC X(26) alphanumeric timestamp fields are stored as LocalDateTime in
     * database and serialized to ISO 8601 format (yyyy-MM-dd'T'HH:mm:ss) in JSON response.</p>
     * 
     * @param transaction the Transaction entity from database
     * @return TransactionResponse DTO with masked card number and formatted fields
     */
    private TransactionResponse mapToResponse(Transaction transaction) {
        return TransactionResponse.builder()
                .transactionId(transaction.getTransactionId())
                .typeCode(transaction.getTypeCode())
                .categoryCode(transaction.getCategoryCode())
                .transactionSource(transaction.getTransactionSource())
                .description(transaction.getDescription())
                .amount(transaction.getAmount())
                .merchantId(transaction.getMerchantId())
                .merchantName(transaction.getMerchantName())
                .merchantCity(transaction.getMerchantCity())
                .merchantZip(transaction.getMerchantZip())
                .cardNumber(maskCardNumber(transaction.getCardNumber()))
                .originationTimestamp(transaction.getOriginationTimestamp())
                .processingTimestamp(transaction.getProcessingTimestamp())
                .build();
    }

    /**
     * Mask card number for PCI DSS compliance.
     * 
     * <p>Returns card number with first 12 digits replaced by asterisks, showing only
     * the last 4 digits. This complies with PCI DSS requirements that prohibit displaying
     * full card PANs in application interfaces, logs, or API responses.</p>
     * 
     * <p>PCI DSS Requirement 3.3: Mask PAN when displayed (the first six and last four
     * digits are the maximum number of digits to be displayed).</p>
     * 
     * <p>This implementation shows only the last 4 digits for maximum security:</p>
     * <ul>
     *   <li>Input: "4111111111111234"</li>
     *   <li>Output: "************1234"</li>
     * </ul>
     * 
     * @param cardNumber the full 16-digit card number
     * @return masked card number showing only last 4 digits, or original value if less than 4 characters
     */
    private String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() <= 4) {
            return cardNumber;
        }
        String lastFour = cardNumber.substring(cardNumber.length() - 4);
        return String.format(CARD_MASK_PATTERN, lastFour);
    }
}
