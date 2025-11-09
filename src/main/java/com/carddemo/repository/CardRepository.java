package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository Interface for Card Entity
 * 
 * <p>This repository interface provides CRUD operations and custom query methods
 * for the Card entity, replacing COBOL EXEC CICS READ/WRITE/REWRITE/DELETE file
 * I/O operations on the VSAM KSDS CARDDAT master file (defined in CVACT02Y.cpy).</p>
 * 
 * <p><strong>COBOL Program Transformations:</strong></p>
 * <ul>
 *   <li><strong>COCRDLIC.cbl</strong> (Card List Display) - Uses findByAccountId with
 *       pagination to replace VSAM STARTBR/READNEXT sequential browsing operations
 *       (lines 1129-1200), supporting 7 cards per page display pattern</li>
 *   <li><strong>COCRDSLC.cbl</strong> (Card Detail View) - Uses findByCardNumber for
 *       direct card retrieval replacing EXEC CICS READ with RIDFLD(CARD-NUM)</li>
 *   <li><strong>COCRDUPC.cbl</strong> (Card Update) - Uses save() method for card
 *       updates replacing EXEC CICS REWRITE operations on CARDDAT file</li>
 *   <li><strong>CBACT02C.cbl</strong> (Card Batch Load) - Uses saveAll() for bulk
 *       card data loading operations replacing batch file processing</li>
 * </ul>
 * 
 * <p><strong>Primary Key Access Pattern:</strong></p>
 * <p>The VSAM CARDDAT file uses CARD-NUM (PIC X(16)) as the primary key for direct
 * access. This maps to the cardNumber field in the Card entity, enabling:</p>
 * <ul>
 *   <li>Direct card lookup by card number via inherited findById(String) method</li>
 *   <li>Unique constraint enforcement at database level</li>
 *   <li>Indexed access matching VSAM KSDS key structure performance</li>
 * </ul>
 * 
 * <p><strong>Alternate Index Access Pattern:</strong></p>
 * <p>The VSAM CARDAIX alternate index file (line 217 in COCRDLIC.cbl) provides
 * access by CARD-ACCT-ID (PIC 9(11)). This is replaced with:</p>
 * <ul>
 *   <li>PostgreSQL foreign key index on account_id column</li>
 *   <li>findByAccountId() custom query method for account-based card retrieval</li>
 *   <li>Composite index on (account_id, card_number) for efficient pagination</li>
 * </ul>
 * 
 * <p><strong>Pagination Support:</strong></p>
 * <p>COCRDLIC.cbl implements pagination with 7 cards per page (WS-SCREEN-ROWS
 * OCCURS 7 TIMES in line 255, WS-MAX-SCREEN-LINES = 7 in line 1191). This is
 * replicated using Spring Data Pageable parameter in findByAccountId method,
 * enabling:</p>
 * <ul>
 *   <li>Page number tracking (first page, subsequent pages)</li>
 *   <li>Page size enforcement (7 cards per page)</li>
 *   <li>Forward/backward navigation matching PF7/PF8 keys</li>
 *   <li>Last page detection (CA-LAST-PAGE-SHOWN flag equivalent)</li>
 * </ul>
 * 
 * <p><strong>Cross-Reference Relationship:</strong></p>
 * <p>The CARD-XREF-RECORD (CVACT03Y.cpy) establishes relationships between cards,
 * accounts, and customers through XREF-CARD-NUM to XREF-ACCT-ID mapping. This VSAM
 * cross-reference file is replaced with JPA @ManyToOne relationship in Card entity,
 * providing:</p>
 * <ul>
 *   <li>Automatic JOIN operations for account information retrieval</li>
 *   <li>Referential integrity enforced by PostgreSQL foreign key constraint</li>
 *   <li>Cascading operations based on relationship configuration</li>
 *   <li>Efficient navigation from card to account and customer entities</li>
 * </ul>
 * 
 * <p><strong>Query Method Naming Convention:</strong></p>
 * <p>Spring Data JPA derives query implementation from method names following
 * convention: findBy[PropertyName]. The account relationship is accessed through
 * the nested property path: account.accountId (Long type matching Account entity
 * primary key). However, Spring Data allows simplified method naming when the
 * property type is unambiguous.</p>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <p>All repository operations are automatically wrapped in transactions by Spring
 * Data JPA. CICS transaction boundaries (SYNCPOINT, ROLLBACK) are replicated through
 * Spring @Transactional annotations in service layer classes, ensuring:</p>
 * <ul>
 *   <li>Atomic operations matching CICS SYNCPOINT commit behavior</li>
 *   <li>Automatic rollback on exceptions matching CICS ROLLBACK command</li>
 *   <li>Isolation level configuration for concurrent access patterns</li>
 *   <li>Read consistency matching VSAM record locking behavior</li>
 * </ul>
 * 
 * <p><strong>Inherited CRUD Operations from JpaRepository:</strong></p>
 * <ul>
 *   <li><strong>findById(String cardNumber)</strong> - Direct card lookup by primary key,
 *       replaces EXEC CICS READ DATASET(CARDDAT) RIDFLD(CARD-NUM)</li>
 *   <li><strong>save(Card card)</strong> - Insert or update card record, replaces
 *       EXEC CICS WRITE (new card) or REWRITE (update existing)</li>
 *   <li><strong>saveAll(Iterable&lt;Card&gt;)</strong> - Bulk insert/update for batch
 *       operations, replaces sequential WRITE operations in batch programs</li>
 *   <li><strong>deleteById(String cardNumber)</strong> - Delete card by primary key,
 *       replaces EXEC CICS DELETE DATASET(CARDDAT) RIDFLD(CARD-NUM)</li>
 *   <li><strong>delete(Card card)</strong> - Delete card entity, alternative deletion
 *       method with entity reference</li>
 *   <li><strong>findAll()</strong> - Retrieve all cards (use with caution on large
 *       datasets), replaces VSAM sequential scan</li>
 *   <li><strong>findAll(Pageable)</strong> - Retrieve all cards with pagination support</li>
 *   <li><strong>existsById(String cardNumber)</strong> - Check card existence without
 *       loading full entity, optimized for validation checks</li>
 *   <li><strong>count()</strong> - Return total card count, useful for reporting and
 *       pagination calculations</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <p>Database indexes are created in Flyway migration script V7__create_indexes.sql
 * to match VSAM key structures and optimize query performance:</p>
 * <ul>
 *   <li>PRIMARY KEY index on card_number (matches VSAM CARDDAT primary key)</li>
 *   <li>FOREIGN KEY index on account_id (matches VSAM CARDAIX alternate index)</li>
 *   <li>Composite index on (account_id, card_number) for efficient pagination</li>
 *   <li>Index on active_status for active/inactive card filtering</li>
 *   <li>Index on expiration_date for card renewal batch processing</li>
 * </ul>
 * 
 * <p><strong>Security and Data Masking:</strong></p>
 * <p>The Card entity contains sensitive data (card numbers, CVV codes) requiring
 * security measures in service layer:</p>
 * <ul>
 *   <li>Card numbers should be masked in logs and UI (show last 4 digits only)</li>
 *   <li>CVV codes should never be displayed or logged after initial entry</li>
 *   <li>Access to full card numbers restricted by role-based security</li>
 *   <li>Audit logging for all card data access and modifications</li>
 * </ul>
 * 
 * <p><strong>Concurrency Control:</strong></p>
 * <p>The Card entity includes optimistic locking via @Version annotation, replicating
 * VSAM record locking behavior. Repository operations automatically handle:</p>
 * <ul>
 *   <li>Version checking on updates (throws OptimisticLockException on conflicts)</li>
 *   <li>Automatic version increment on successful updates</li>
 *   <li>Retry logic in service layer for transient lock conflicts</li>
 * </ul>
 * 
 * <p><strong>Functional Equivalence Validation:</strong></p>
 * <p>This repository maintains complete functional equivalence with COBOL VSAM file
 * operations as required by Section 0.10 Special Instructions:</p>
 * <ul>
 *   <li>All CARD-RECORD fields (CVACT02Y.cpy) mapped to Card entity properties</li>
 *   <li>Primary key access pattern preserved (CARD-NUM → cardNumber)</li>
 *   <li>Alternate key access pattern preserved (CARD-ACCT-ID → account.accountId)</li>
 *   <li>Pagination pattern preserved (7 cards per page)</li>
 *   <li>Sequential browsing pattern replicated with Spring Data Pageable</li>
 *   <li>Transaction boundaries match CICS commit/rollback behavior</li>
 * </ul>
 * 
 * <p><strong>Data Migration Notes:</strong></p>
 * <p>During migration from VSAM CARDDAT to PostgreSQL card table:</p>
 * <ul>
 *   <li>EBCDIC to ASCII character encoding conversion required</li>
 *   <li>COBOL PIC X(10) date format conversion to ISO format (YYYY-MM-DD)</li>
 *   <li>Zero-padding preservation for numeric fields stored as strings</li>
 *   <li>Cross-reference relationships established through account_id foreign key</li>
 * </ul>
 * 
 * <p><strong>Repository Design Patterns:</strong></p>
 * <p>This interface follows Spring Data JPA repository pattern conventions:</p>
 * <ul>
 *   <li>Interface-based design with no implementation code required</li>
 *   <li>Automatic query derivation from method names</li>
 *   <li>Transaction management handled by Spring framework</li>
 *   <li>Exception translation to Spring DataAccessException hierarchy</li>
 *   <li>Integration with Spring dependency injection container</li>
 * </ul>
 * 
 * <p><strong>Testing Strategy:</strong></p>
 * <p>Repository testing uses @DataJpaTest annotation for isolated JPA slice testing:</p>
 * <ul>
 *   <li>In-memory H2 database for fast test execution</li>
 *   <li>Test data loaded via @Sql scripts or test data builders</li>
 *   <li>Verify query methods return correct results</li>
 *   <li>Validate pagination behavior matches 7 cards per page requirement</li>
 *   <li>Test concurrent access patterns with optimistic locking</li>
 * </ul>
 * 
 * @see Card
 * @see com.carddemo.entity.Account
 * @see <a href="Section 0.4">Agent Action Plan - Source Files CVACT02Y.cpy, COCRDLIC.cbl, COCRDSLC.cbl, COCRDUPC.cbl, CBACT02C.cbl</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CardRepository.java</a>
 * @see <a href="Section 0.10">Special Instructions - Rule 4: Follow Repository Pattern</a>
 * @see <a href="Section 0.10">Special Instructions - Rule 9: Preserve Transaction Boundaries</a>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Find a card by card number (Primary Key Lookup)
     * 
     * <p>Replaces COBOL EXEC CICS READ operation with RIDFLD(CARD-NUM) for direct
     * card retrieval by primary key. This is the primary access method used in
     * COCRDSLC.cbl (Card Detail View) and COCRDUPC.cbl (Card Update) programs.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET(LIT-CARD-FILE)    *> 'CARDDAT'
     *      INTO(CARD-RECORD)
     *      RIDFLD(WS-CARD-RID-CARDNUM)
     *      RESP(WS-RESP-CD)
     *      RESP2(WS-REAS-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><strong>Usage Pattern:</strong></p>
     * <pre>
     * Optional&lt;Card&gt; card = cardRepository.findByCardNumber("4000123456789010");
     * if (card.isPresent()) {
     *     // Process card details
     *     Card cardEntity = card.get();
     *     // ... business logic ...
     * } else {
     *     // Handle card not found (RESP = NOTFND)
     *     throw new ResourceNotFoundException("Card not found: " + cardNumber);
     * }
     * </pre>
     * 
     * <p><strong>Query Performance:</strong></p>
     * <ul>
     *   <li>Uses primary key index for O(log n) lookup time</li>
     *   <li>Matches VSAM KSDS direct access performance</li>
     *   <li>No table scan required</li>
     * </ul>
     * 
     * <p><strong>Return Value:</strong></p>
     * <p>Returns Optional&lt;Card&gt; to handle card not found scenarios gracefully.
     * Empty Optional replaces COBOL RESP code DFHRESP(NOTFND), allowing service layer
     * to decide error handling strategy (throw exception, return null object, etc.)</p>
     * 
     * @param cardNumber the 16-character card number (CARD-NUM PIC X(16))
     * @return Optional containing the Card entity if found, empty Optional if not found
     * @throws org.springframework.dao.DataAccessException if database access error occurs
     */
    Optional<Card> findByCardNumber(String cardNumber);

    /**
     * Find all cards by account ID (Foreign Key Lookup)
     * 
     * <p>Retrieves all cards associated with a specific account, replacing COBOL
     * VSAM alternate index access through CARDAIX file. This is the primary access
     * method used in COCRDLIC.cbl (Card List Display) for displaying all cards
     * belonging to an account.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS STARTBR
     *      DATASET(LIT-CARD-FILE-ACCT-PATH)    *> 'CARDAIX' alternate index
     *      RIDFLD(WS-CARD-RID-ACCTID)
     *      KEYLENGTH(LENGTH OF WS-CARD-RID-ACCTID)
     *      GTEQ
     *      RESP(WS-RESP-CD)
     * END-EXEC
     * 
     * PERFORM UNTIL READ-LOOP-EXIT
     *    EXEC CICS READNEXT
     *         DATASET(LIT-CARD-FILE-ACCT-PATH)
     *         INTO(CARD-RECORD)
     *         LENGTH(LENGTH OF CARD-RECORD)
     *         RIDFLD(WS-CARD-RID-ACCTID)
     *         RESP(WS-RESP-CD)
     *    END-EXEC
     *    
     *    IF WS-RESP-CD = DFHRESP(NORMAL)
     *       *> Process card record
     *       MOVE CARD-NUM TO WS-ROW-CARD-NUM(WS-SCRN-COUNTER)
     *       MOVE CARD-ACCT-ID TO WS-ROW-ACCTNO(WS-SCRN-COUNTER)
     *       ADD 1 TO WS-SCRN-COUNTER
     *    END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>Usage Pattern (Without Pagination):</strong></p>
     * <pre>
     * List&lt;Card&gt; cards = cardRepository.findByAccount_AccountId(accountId);
     * if (cards.isEmpty()) {
     *     // No cards found for account (RESP = ENDFILE)
     *     // ... handle no cards scenario ...
     * } else {
     *     // Process card list
     *     for (Card card : cards) {
     *         // ... display card information ...
     *     }
     * }
     * </pre>
     * 
     * <p><strong>Cross-Reference Mapping:</strong></p>
     * <p>This method replaces the CARD-XREF-RECORD (CVACT03Y.cpy) cross-reference
     * file access pattern. The VSAM CARDAIX alternate index provided access by
     * CARD-ACCT-ID field, which is now handled by PostgreSQL foreign key index on
     * account_id column.</p>
     * 
     * <p><strong>Query Performance:</strong></p>
     * <ul>
     *   <li>Uses foreign key index on account_id for efficient lookup</li>
     *   <li>Returns all matching records in single query (no cursor needed)</li>
     *   <li>Composite index (account_id, card_number) optimizes ordering</li>
     *   <li>For large result sets, consider using paginated version below</li>
     * </ul>
     * 
     * <p><strong>Account Relationship Navigation:</strong></p>
     * <p>The method parameter uses account.accountId property path. Spring Data JPA
     * automatically translates this to JOIN condition on account_id foreign key,
     * matching the VSAM cross-reference file behavior.</p>
     * 
     * <p><strong>Result Ordering:</strong></p>
     * <p>Results are returned in natural order by card_number (primary key). For
     * custom ordering, use @Query annotation with ORDER BY clause or method name
     * convention with OrderBy suffix.</p>
     * 
     * @param accountId the account identifier (CARD-ACCT-ID PIC 9(11) / Account.accountId)
     * @return List of Card entities associated with the account, empty list if no cards found
     * @throws org.springframework.dao.DataAccessException if database access error occurs
     */
    List<Card> findByAccount_AccountId(Long accountId);

    /**
     * Find all cards by account ID with pagination support (Paginated Foreign Key Lookup)
     * 
     * <p>Retrieves cards associated with a specific account with pagination support,
     * replicating COCRDLIC.cbl screen pagination pattern of 7 cards per page. This is
     * the recommended method for card listing to avoid loading excessive data in memory.</p>
     * 
     * <p><strong>Pagination Pattern from COCRDLIC.cbl:</strong></p>
     * <ul>
     *   <li><strong>Line 255:</strong> WS-SCREEN-ROWS OCCURS 7 TIMES - defines 7-row display</li>
     *   <li><strong>Line 1191:</strong> WS-MAX-SCREEN-LINES = 7 - enforces 7 cards per page</li>
     *   <li><strong>Line 237-241:</strong> WS-CA-SCREEN-NUM for page tracking</li>
     *   <li><strong>Line 242-244:</strong> WS-CA-NEXT-PAGE-IND for next page detection</li>
     *   <li><strong>PF7/PF8 Keys:</strong> Backward/Forward page navigation</li>
     * </ul>
     * 
     * <p><strong>Usage Pattern (7 Cards Per Page):</strong></p>
     * <pre>
     * // First page (page 0, size 7)
     * Pageable pageable = PageRequest.of(0, 7, Sort.by("cardNumber"));
     * Page&lt;Card&gt; firstPage = cardRepository.findByAccount_AccountId(accountId, pageable);
     * 
     * // Next page (page 1, size 7) - simulates PF8 key press
     * Pageable nextPageable = PageRequest.of(1, 7, Sort.by("cardNumber"));
     * Page&lt;Card&gt; secondPage = cardRepository.findByAccount_AccountId(accountId, nextPageable);
     * 
     * // Check if more pages exist (CA-NEXT-PAGE-EXISTS flag)
     * boolean hasMorePages = secondPage.hasNext();
     * </pre>
     * 
     * <p><strong>Service Layer Implementation Example:</strong></p>
     * <pre>
     * public Page&lt;CardResponse&gt; getCardsByAccount(Long accountId, int pageNumber) {
     *     // Create Pageable with 7 cards per page matching COBOL screen size
     *     Pageable pageable = PageRequest.of(pageNumber, 7, Sort.by("cardNumber"));
     *     Page&lt;Card&gt; cardPage = cardRepository.findByAccount_AccountId(accountId, pageable);
     *     
     *     // Page object provides all pagination metadata
     *     // hasNext() replaces WS-CA-NEXT-PAGE-IND flag
     *     // getTotalPages() provides total page count
     *     // getTotalElements() provides total card count
     *     
     *     // Map to response DTO preserving pagination metadata
     *     return cardPage.map(this::mapToCardResponse);
     * }
     * </pre>
     * 
     * <p><strong>COBOL Pagination Logic Equivalent:</strong></p>
     * <pre>
     * *> COBOL Pagination Check (lines 1191-1196)
     * IF WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES    *> Counter reached 7
     *    SET READ-LOOP-EXIT TO TRUE               *> Stop reading
     *    MOVE CARD-ACCT-ID TO WS-CA-LAST-CARD-ACCT-ID
     *    MOVE CARD-NUM TO WS-CA-LAST-CARD-NUM
     *    
     *    *> Peek ahead to check for next page
     *    EXEC CICS READNEXT
     *         DATASET(LIT-CARD-FILE)
     *         INTO(CARD-RECORD)
     *         ...
     *    END-EXEC
     *    
     *    IF WS-RESP-CD = DFHRESP(NORMAL)
     *       SET CA-NEXT-PAGE-EXISTS TO TRUE        *> More records available
     *    ELSE
     *       SET CA-NEXT-PAGE-NOT-EXISTS TO TRUE    *> Last page reached
     *    END-IF
     * END-IF
     * </pre>
     * 
     * <p><strong>Pageable Configuration Options:</strong></p>
     * <ul>
     *   <li><strong>Page Number:</strong> Zero-based index (0 = first page, 1 = second page)</li>
     *   <li><strong>Page Size:</strong> Number of records per page (fixed at 7 for card listing)</li>
     *   <li><strong>Sort Order:</strong> Sorting criteria (default: cardNumber ascending)</li>
     *   <li><strong>Alternative Sorting:</strong> Sort by activeStatus desc, cardNumber asc to show
     *       active cards first</li>
     * </ul>
     * 
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduces memory consumption by loading only required page of data</li>
     *   <li>Uses LIMIT and OFFSET SQL clauses for efficient pagination</li>
     *   <li>Composite index (account_id, card_number) optimizes pagination queries</li>
     *   <li>Matches VSAM sequential browsing performance characteristics</li>
     * </ul>
     * 
     * <p><strong>Navigation State Management:</strong></p>
     * <p>COBOL maintains pagination state in COMMAREA (WS-THIS-PROGCOMMAREA structure,
     * lines 229-248). In Spring Boot, this state is managed through:</p>
     * <ul>
     *   <li>HTTP request parameters (pageNumber, pageSize)</li>
     *   <li>Session storage (Spring Session with Redis) for user-specific state</li>
     *   <li>Response metadata (currentPage, hasNextPage, hasPreviousPage)</li>
     * </ul>
     * 
     * <p><strong>Edge Cases:</strong></p>
     * <ul>
     *   <li><strong>No Cards:</strong> Returns empty Page (hasContent() = false)</li>
     *   <li><strong>Partial Page:</strong> Last page may have fewer than 7 cards</li>
     *   <li><strong>Single Card:</strong> Page with single element</li>
     *   <li><strong>Invalid Page:</strong> Returns empty Page for pages beyond last page</li>
     * </ul>
     * 
     * @param accountId the account identifier (CARD-ACCT-ID PIC 9(11) / Account.accountId)
     * @param pageable pagination parameters (page number, page size of 7, sort order)
     * @return Page of Card entities for the requested page with pagination metadata,
     *         empty Page if no cards found or page exceeds available data
     * @throws org.springframework.dao.DataAccessException if database access error occurs
     */
    Page<Card> findByAccount_AccountId(Long accountId, Pageable pageable);
}
