package com.carddemo.repository;

import com.carddemo.entity.Card;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for Card entity providing comprehensive CRUD operations,
 * pagination support, and custom queries for card data access, replacing VSAM CARDDAT file operations.
 * 
 * <p>This repository is the cornerstone of the data access layer for credit card management,
 * transforming mainframe VSAM KSDS file operations from COBOL programs COCRDLIC.cbl, COCRDSLC.cbl,
 * COCRDUPC.cbl, and CBCRD01C.cbl into modern Spring Data JPA repository patterns. It provides
 * type-safe, transactional database operations with automatic query generation and optimization.</p>
 * 
 * <p><strong>COBOL-to-Java Transformation Context (Section 0.3 and 0.6):</strong></p>
 * <ul>
 *   <li><strong>VSAM File Replaced:</strong> CARDDAT KSDS (Key-Sequenced Dataset)</li>
 *   <li><strong>Primary Key:</strong> CARD-NUM PIC X(16) → String cardNumber (16 characters)</li>
 *   <li><strong>COBOL Programs Migrated:</strong></li>
 *   <ul>
 *     <li>COCRDLIC.cbl → CardListService (card list display with pagination)</li>
 *     <li>COCRDSLC.cbl → CardDetailService (card detail view by card number)</li>
 *     <li>COCRDUPC.cbl → CardUpdateService (card status and information updates)</li>
 *     <li>CBCRD01C.cbl → CardDataLoadJob (batch card data import with validation)</li>
 *   </ul>
 *   <li><strong>CICS Operations Replaced:</strong></li>
 *   <ul>
 *     <li>EXEC CICS READ DATASET(CARDFILE) → findByCardNumber()</li>
 *     <li>EXEC CICS WRITE DATASET(CARDFILE) → save()</li>
 *     <li>EXEC CICS REWRITE DATASET(CARDFILE) → save() with existing entity</li>
 *     <li>EXEC CICS DELETE DATASET(CARDFILE) → delete()</li>
 *     <li>EXEC CICS STARTBR/READNEXT browsing → findByAccountId() with pagination</li>
 *   </ul>
 * </ul>
 * 
 * <p><strong>Pagination Requirements (Section 0.1 Core Objectives):</strong></p>
 * <ul>
 *   <li>COBOL COCRDLIC.cbl displays 7 cards per screen page</li>
 *   <li>This pattern is preserved using Spring Data Pageable support</li>
 *   <li>Method: findByAccountId(Long accountId, Pageable pageable) returns Page&lt;Card&gt;</li>
 *   <li>Usage: PageRequest.of(0, 7, Sort.by("cardNumber").ascending())</li>
 *   <li>Benefits: Efficient memory usage, reduced network overhead, better UX</li>
 * </ul>
 * 
 * <p><strong>Performance Requirements (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>Card lookup by primary key (cardNumber): &lt; 10ms (indexed primary key)</li>
 *   <li>Cards by account query: &lt; 50ms (indexed foreign key on account_id)</li>
 *   <li>Paginated card lists: &lt; 100ms for page retrieval with 7 rows</li>
 *   <li>Batch operations: Support bulk inserts/updates for CardDataLoadJob</li>
 *   <li>Concurrent access: Optimistic locking via @Version in Card entity</li>
 * </ul>
 * 
 * <p><strong>Database Indexes for Performance (Section 0.6 Transformation Rules):</strong></p>
 * <ul>
 *   <li>Primary Key Index: B-tree on card_number (unique, non-null)</li>
 *   <li>Foreign Key Index: B-tree on account_id (non-unique, supports JOIN operations)</li>
 *   <li>Status Filter Index: B-tree on active_status (cardinality optimization)</li>
 *   <li>Expiration Index: B-tree on expiration_date (batch job performance)</li>
 * </ul>
 * 
 * <p><strong>PCI-DSS Security Compliance (Section 0.4 and 0.9):</strong></p>
 * <ul>
 *   <li>Card numbers (PAN) are Level 1 PII - encrypted at rest in PostgreSQL</li>
 *   <li>CVV codes never logged or exposed via repository methods</li>
 *   <li>All card data access must be audited per compliance requirements</li>
 *   <li>Repository methods return full Card entities; masking applied at service layer</li>
 *   <li>Spring Security integration ensures only authenticated users access card data</li>
 * </ul>
 * 
 * <p><strong>Transaction Boundary Preservation (Section 0.2 and 0.9):</strong></p>
 * <ul>
 *   <li>All repository operations are transactional via @Transactional at service layer</li>
 *   <li>CICS SYNCPOINT equivalence: Spring @Transactional commit/rollback</li>
 *   <li>Isolation level: READ_COMMITTED (matches CICS default)</li>
 *   <li>Propagation: REQUIRED (participates in existing transaction)</li>
 *   <li>Rollback strategy: Automatic rollback on unchecked exceptions</li>
 * </ul>
 * 
 * <p><strong>Card Status Filtering (COBOL 88-Level Preservation per Section 0.9):</strong></p>
 * <ul>
 *   <li>findByCardStatus() replaces COBOL IF CARD-ACTIVE conditions</li>
 *   <li>Status codes: 'Y'=ACTIVE, 'N'=INACTIVE, 'B'=BLOCKED, 'E'=EXPIRED, 'C'=CLOSED, 'P'=PENDING</li>
 *   <li>Enables filtering card lists by operational status for display and reporting</li>
 * </ul>
 * 
 * <p><strong>Batch Processing Support (Section 0.6 JCL Transformation):</strong></p>
 * <ul>
 *   <li>CardDataLoadJob uses saveAll() for efficient bulk card data import</li>
 *   <li>findByExpirationDateBefore() identifies expired cards for status updates</li>
 *   <li>Chunk-oriented processing: Read 1000 cards, process, write in batches</li>
 *   <li>Error handling: Spring Batch skip/retry mechanisms for data quality issues</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Card lookup by card number (COCRDSLC.cbl detail view)
 * Optional&lt;Card&gt; card = cardRepository.findByCardNumber("4532123456789012");
 * 
 * // Paginated card list (COCRDLIC.cbl list display with 7 cards per page)
 * Pageable pageable = PageRequest.of(0, 7, Sort.by("cardNumber").ascending());
 * Page&lt;Card&gt; cardPage = cardRepository.findByAccountId(12345678901L, pageable);
 * 
 * // Filter active cards for an account
 * List&lt;Card&gt; activeCards = cardRepository.findByCardStatus("Y");
 * 
 * // Identify expired cards for batch processing (CBCRD01C.cbl)
 * LocalDate today = LocalDate.now();
 * List&lt;Card&gt; expiredCards = cardRepository.findByExpirationDateBefore(today);
 * 
 * // Sorted card list for consistent display
 * List&lt;Card&gt; sortedCards = cardRepository.findByAccountIdOrderByCardNumberAsc(12345678901L);
 * </pre>
 * 
 * <p><strong>Inherited Methods from JpaRepository:</strong></p>
 * <ul>
 *   <li>save(Card card) - Insert new card or update existing card</li>
 *   <li>saveAll(Iterable&lt;Card&gt; cards) - Bulk insert/update for batch jobs</li>
 *   <li>findById(String cardNumber) - Primary key lookup</li>
 *   <li>findAll() - Retrieve all cards (use with caution, prefer pagination)</li>
 *   <li>findAll(Pageable pageable) - Paginated retrieval of all cards</li>
 *   <li>delete(Card card) - Delete card by entity</li>
 *   <li>deleteById(String cardNumber) - Delete card by primary key</li>
 *   <li>count() - Total count of cards in database</li>
 *   <li>existsById(String cardNumber) - Check if card exists without loading entity</li>
 * </ul>
 * 
 * <p><strong>Design Pattern:</strong> Repository Pattern (Spring Data JPA)</p>
 * <p><strong>Layer:</strong> Data Access Layer (Repository)</p>
 * <p><strong>Thread Safety:</strong> Thread-safe (Spring-managed singleton bean)</p>
 * 
 * @see Card
 * @see CardListService
 * @see CardDetailService
 * @see CardUpdateService
 * @see CardDataLoadJob
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.9">Security and Compliance Requirements</a>
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {
    
    /**
     * Find card by card number (primary key).
     * 
     * <p>Primary card lookup operation replacing VSAM KSDS READ by primary key from COBOL
     * programs COCRDSLC.cbl (card detail view) and COCRDUPC.cbl (card update operations).</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: EXEC CICS READ DATASET('CARDFILE')
     *                      INTO(CARD-RECORD)
     *                      RIDFLD(WS-CARD-NUM)
     *                      RESP(WS-RESP-CD)
     *        END-EXEC
     * 
     * Java:  Optional&lt;Card&gt; card = cardRepository.findByCardNumber(cardNumber);
     * </pre>
     * 
     * <p><strong>Performance:</strong> O(1) lookup via primary key B-tree index. Expected
     * response time &lt; 10ms per Section 0.9 performance requirements.</p>
     * 
     * <p><strong>PCI-DSS Note:</strong> Returns full Card entity including encrypted PAN and CVV.
     * Service layer must apply masking before exposing to API responses or logs.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardDetailService.getCardDetails() - Detail view (COCRDSLC replacement)</li>
     *   <li>CardUpdateService.updateCard() - Pre-update validation (COCRDUPC replacement)</li>
     *   <li>TransactionCreationService - Card validation before posting transaction</li>
     *   <li>BillPaymentService - Payment method validation</li>
     * </ul>
     * 
     * @param cardNumber 16-character card number (PAN) serving as primary key
     * @return Optional containing Card if found, empty Optional if card does not exist
     */
    Optional<Card> findByCardNumber(String cardNumber);
    
    /**
     * Find all cards associated with a given account ID.
     * 
     * <p>Retrieves complete list of cards linked to an account, supporting card list display
     * from COBOL program COCRDLIC.cbl. This method returns ALL cards without pagination;
     * for paginated results use findByAccountId(Long accountId, Pageable pageable) instead.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: EXEC CICS STARTBR DATASET('CARDFILE')
     *                          RIDFLD(WS-ACCT-ID)
     *                          KEYLENGTH(11)
     *        END-EXEC
     *        PERFORM UNTIL END-OF-BROWSE
     *            EXEC CICS READNEXT DATASET('CARDFILE')
     *                              INTO(CARD-RECORD)
     *            END-EXEC
     *            [Process card...]
     *        END-PERFORM
     * 
     * Java:  List&lt;Card&gt; cards = cardRepository.findByAccountId(accountId);
     * </pre>
     * 
     * <p><strong>Performance:</strong> Indexed query via foreign key on account_id column.
     * Response time &lt; 50ms for typical account with 1-10 cards. For accounts with many
     * cards, prefer paginated variant to avoid memory overhead.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardListService.getAllCardsForAccount() - Complete card list retrieval</li>
     *   <li>AccountViewService - Display all cards associated with account</li>
     *   <li>CardDataLoadJob - Validate account-card relationships during batch import</li>
     * </ul>
     * 
     * @param accountId 11-digit account identifier (foreign key to Account entity)
     * @return List of all cards associated with the account (may be empty, never null)
     */
    List<Card> findByAccountId(Long accountId);
    
    /**
     * Find cards for an account with pagination support (7 cards per page).
     * 
     * <p>Implements COBOL COCRDLIC.cbl pagination pattern which displays 7 cards per screen.
     * This is the PREFERRED method for card list retrieval in user-facing operations to
     * maintain sub-200ms response times and efficient memory usage per Section 0.9 requirements.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: 01 WS-CARD-COUNT PIC 9(2) VALUE 0.
     *        EXEC CICS STARTBR DATASET('CARDFILE')...
     *        PERFORM UNTIL WS-CARD-COUNT = 7 OR END-OF-FILE
     *            EXEC CICS READNEXT DATASET('CARDFILE')...
     *            ADD 1 TO WS-CARD-COUNT
     *        END-PERFORM
     * 
     * Java:  Pageable pageable = PageRequest.of(pageNumber, 7, Sort.by("cardNumber").ascending());
     *        Page&lt;Card&gt; cardPage = cardRepository.findByAccountId(accountId, pageable);
     * </pre>
     * 
     * <p><strong>Pagination Configuration:</strong></p>
     * <ul>
     *   <li>Page size: 7 cards (matches COBOL screen capacity)</li>
     *   <li>Default sort: cardNumber ascending (consistent ordering)</li>
     *   <li>Zero-based page numbering: page 0 is first page</li>
     *   <li>Total pages and elements available via Page interface methods</li>
     * </ul>
     * 
     * <p><strong>Performance Benefits:</strong></p>
     * <ul>
     *   <li>Reduced memory footprint - loads only requested page</li>
     *   <li>Faster query execution - LIMIT and OFFSET clauses in SQL</li>
     *   <li>Improved UX - immediate display of first 7 cards</li>
     *   <li>Maintains &lt; 100ms response time even with hundreds of cards</li>
     * </ul>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>
     * // Retrieve first page (cards 1-7) sorted by card number
     * Pageable firstPage = PageRequest.of(0, 7, Sort.by("cardNumber").ascending());
     * Page&lt;Card&gt; page = cardRepository.findByAccountId(12345678901L, firstPage);
     * 
     * List&lt;Card&gt; cards = page.getContent();         // Cards on this page
     * int totalPages = page.getTotalPages();          // Total number of pages
     * long totalCards = page.getTotalElements();      // Total cards for account
     * boolean hasNext = page.hasNext();               // True if more pages available
     * </pre>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardListService.getCardPage() - Primary card list display (COCRDLIC.cbl)</li>
     *   <li>AccountViewController REST endpoint - /api/accounts/{id}/cards?page=0&size=7</li>
     *   <li>CardListComponent React UI - Paginated card table with next/previous buttons</li>
     * </ul>
     * 
     * @param accountId 11-digit account identifier
     * @param pageable Pagination and sorting parameters (page number, size, sort order)
     * @return Page object containing requested cards, pagination metadata, and navigation info
     */
    Page<Card> findByAccountId(Long accountId, Pageable pageable);
    
    /**
     * Find all cards with a specific status code.
     * 
     * <p>Filters cards by active status, replacing COBOL conditional logic from COCRDLIC.cbl
     * that checks CARD-ACTIVE-STATUS field using 88-level condition names (Section 0.9
     * COBOL construct preservation requirement).</p>
     * 
     * <p><strong>COBOL Equivalent (88-Level Condition):</strong></p>
     * <pre>
     * COBOL: 01 CARD-ACTIVE-STATUS PIC X(1).
     *            88 CARD-IS-ACTIVE VALUE 'Y'.
     *            88 CARD-IS-BLOCKED VALUE 'B'.
     *        
     *        IF CARD-IS-ACTIVE THEN
     *            [Process active card...]
     *        END-IF
     * 
     * Java:  List&lt;Card&gt; activeCards = cardRepository.findByCardStatus("Y");
     * </pre>
     * 
     * <p><strong>Valid Status Codes (per CardStatus enum):</strong></p>
     * <ul>
     *   <li>'Y' - ACTIVE: Card is active and can be used for transactions</li>
     *   <li>'N' - INACTIVE: Card temporarily inactive (can be reactivated)</li>
     *   <li>'B' - BLOCKED: Card blocked due to security concerns or fraud</li>
     *   <li>'E' - EXPIRED: Card has passed expiration date</li>
     *   <li>'C' - CLOSED: Card permanently closed (cannot be reactivated)</li>
     *   <li>'P' - PENDING: Card activation pending customer confirmation</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Indexed query on active_status column. Response time
     * proportional to number of matching cards. Consider adding pagination for large result sets.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardListService.getActiveCards() - Filter display to active cards only</li>
     *   <li>AdminService.listBlockedCards() - Security monitoring and review</li>
     *   <li>ReportService - Card status distribution reports</li>
     *   <li>CardDataLoadJob - Validate status codes during batch import</li>
     * </ul>
     * 
     * @param status Single-character card status code ('Y', 'N', 'B', 'E', 'C', 'P')
     * @return List of cards matching the specified status (may be empty, never null)
     */
    List<Card> findByCardStatus(String status);
    
    /**
     * Find all cards expiring before a given date.
     * 
     * <p>Identifies cards approaching or past expiration date, supporting batch processing
     * jobs from COBOL program CBCRD01C.cbl that generate replacement cards and update
     * card statuses to EXPIRED.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: PERFORM VARYING I FROM 1 BY 1 UNTIL I > CARD-COUNT
     *            IF CARD-EXP-DATE(I) < CURRENT-DATE
     *                PERFORM UPDATE-CARD-STATUS-EXPIRED
     *            END-IF
     *        END-PERFORM
     * 
     * Java:  LocalDate today = LocalDate.now();
     *        List&lt;Card&gt; expiredCards = cardRepository.findByExpirationDateBefore(today);
     *        expiredCards.forEach(card -&gt; card.setCardStatus(CardStatus.EXPIRED));
     * </pre>
     * 
     * <p><strong>Business Rules:</strong></p>
     * <ul>
     *   <li>Cards expire at end of expiration month (e.g., 12/2024 valid through 12/31/2024)</li>
     *   <li>Replacement cards generated 60 days before expiration (use today.plusDays(60))</li>
     *   <li>Daily batch job updates EXPIRED status for cards past expiration date</li>
     *   <li>Expired cards automatically rejected for transaction authorization</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Indexed query on expiration_date column. Efficient
     * for batch processing with typical result set of hundreds of cards per day.</p>
     * 
     * <p><strong>Usage Examples:</strong></p>
     * <pre>
     * // Find all expired cards (daily batch job CBCRD01C)
     * LocalDate today = LocalDate.now();
     * List&lt;Card&gt; expiredCards = cardRepository.findByExpirationDateBefore(today);
     * 
     * // Find cards expiring within next 60 days (replacement card generation)
     * LocalDate sixtyDaysFromNow = LocalDate.now().plusDays(60);
     * List&lt;Card&gt; expiringSoon = cardRepository.findByExpirationDateBefore(sixtyDaysFromNow);
     * </pre>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardDataLoadJob - Daily expired card status update batch job</li>
     *   <li>ReplacementCardJob - Generate replacement cards 60 days before expiration</li>
     *   <li>ReportService - Expiring cards report for customer service</li>
     *   <li>TransactionCreationService - Pre-transaction expiration validation</li>
     * </ul>
     * 
     * @param date Date threshold for expiration comparison (typically LocalDate.now())
     * @return List of cards expiring before the specified date (may be empty, never null)
     */
    List<Card> findByExpirationDateBefore(LocalDate date);
    
    /**
     * Find all cards for an account, sorted by card number in ascending order.
     * 
     * <p>Provides deterministic ordering of card lists for consistent display across
     * multiple views and API calls. Maintains the predictable sort order users expect
     * from the mainframe VSAM KSDS sequential access pattern.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL: EXEC CICS STARTBR DATASET('CARDFILE')
     *                          RIDFLD(WS-ACCT-ID)
     *                          KEYLENGTH(11)
     *        END-EXEC
     *        [VSAM automatically returns records in key sequence]
     *        PERFORM UNTIL END-OF-BROWSE
     *            EXEC CICS READNEXT DATASET('CARDFILE')
     *                              INTO(CARD-RECORD)
     *            END-EXEC
     *        END-PERFORM
     * 
     * Java:  List&lt;Card&gt; sortedCards = cardRepository.findByAccountIdOrderByCardNumberAsc(accountId);
     * </pre>
     * 
     * <p><strong>Sort Order:</strong> Card number ascending (0000000000000001, 0000000000000002, ...)</p>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Consistent display order across multiple UI views</li>
     *   <li>Deterministic ordering for automated testing and validation</li>
     *   <li>Predictable card selection in dropdown menus</li>
     *   <li>Report generation requiring stable sort order</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Combined index on (account_id, card_number) provides
     * optimized query execution. Response time &lt; 50ms for typical accounts.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <ul>
     *   <li>CardListService.getSortedCardList() - Sorted list display</li>
     *   <li>CardUpdateService - Validation that card belongs to account in order</li>
     *   <li>AccountViewService - Display cards in predictable sequence</li>
     *   <li>ReportService - Generate consistent card listing reports</li>
     * </ul>
     * 
     * @param accountId 11-digit account identifier
     * @return List of cards sorted by card number in ascending order (may be empty, never null)
     */
    List<Card> findByAccountIdOrderByCardNumberAsc(Long accountId);
}
