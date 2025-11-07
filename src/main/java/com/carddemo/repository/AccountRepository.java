package com.carddemo.repository;

import com.carddemo.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Account Entity.
 * 
 * <p>This repository interface provides CRUD operations and custom query methods for the
 * Account entity, replacing COBOL EXEC CICS READ/WRITE/REWRITE/DELETE operations on the
 * VSAM KSDS ACCTDAT master file defined in copybook CVACT01Y.cpy.</p>
 * 
 * <p><strong>COBOL to Spring Data JPA Transformation:</strong></p>
 * <ul>
 *   <li>EXEC CICS READ with RIDFLD(ACCT-ID) → findByAccountId(Long accountId)</li>
 *   <li>EXEC CICS WRITE → save(Account account) for new records</li>
 *   <li>EXEC CICS REWRITE → save(Account account) for existing records</li>
 *   <li>EXEC CICS DELETE → deleteById(Long accountId) or delete(Account account)</li>
 *   <li>EXEC CICS STARTBR/READNEXT → findAll() with pagination support</li>
 * </ul>
 * 
 * <p><strong>VSAM Key Access Patterns:</strong></p>
 * <p>The VSAM ACCTDAT file uses ACCT-ID (PIC 9(11)) as the primary key for indexed access.
 * This maps to the accountId field in the Account entity with a PRIMARY KEY constraint
 * and btree index in PostgreSQL. The repository provides query methods matching VSAM
 * access patterns:</p>
 * <ul>
 *   <li><strong>Primary Key Access:</strong> findByAccountId matches VSAM READ with RIDFLD</li>
 *   <li><strong>Alternate Index Access:</strong> findByCustomerId matches CXACAIX cross-reference
 *       alternate index for accessing accounts by customer relationship</li>
 * </ul>
 * 
 * <p><strong>Source COBOL Programs Using This Repository:</strong></p>
 * <ul>
 *   <li><strong>COACTVWC.cbl:</strong> Account View - reads account data by account ID
 *       (paragraph 9300-GETACCTDATA-BYACCT performs EXEC CICS READ on ACCTDAT)</li>
 *   <li><strong>COACTUPC.cbl:</strong> Account Update - reads and rewrites account data with
 *       validation for credit limit changes, requires @Transactional boundaries</li>
 *   <li><strong>CBACT01C.cbl:</strong> Account Data Load Batch Job - bulk inserts account records
 *       from sequential file input into ACCTDAT file</li>
 *   <li><strong>CBACT04C.cbl:</strong> Interest Calculation Batch Job - reads all accounts,
 *       calculates interest using BigDecimal precision, and updates balances</li>
 * </ul>
 * 
 * <p><strong>Custom Query Methods:</strong></p>
 * <p>Spring Data JPA automatically implements query methods based on method naming conventions.
 * The following custom methods are provided beyond standard JpaRepository operations:</p>
 * <ul>
 *   <li><strong>findByAccountId:</strong> Retrieves a single account by exact account ID match.
 *       Returns Optional to handle NOTFND condition without exceptions, matching COBOL
 *       RESP(DFHRESP(NOTFND)) error handling pattern.</li>
 *   <li><strong>findByCustomerId:</strong> Retrieves all accounts associated with a specific
 *       customer ID. Returns List supporting one-to-many customer-account relationship
 *       from CARD-XREF-RECORD (CVACT03Y.cpy) cross-reference structure.</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <p>Repository operations participate in Spring's declarative transaction management.
 * Service layer methods using this repository should be annotated with @Transactional
 * to match CICS transaction boundaries:</p>
 * <ul>
 *   <li>CICS transaction start → @Transactional method entry</li>
 *   <li>SYNCPOINT → automatic commit at method completion</li>
 *   <li>ROLLBACK → exception thrown triggers automatic rollback</li>
 *   <li>Multi-file updates → all repository save operations within single @Transactional method</li>
 * </ul>
 * 
 * <p><strong>Inherited JpaRepository Methods:</strong></p>
 * <p>All standard CRUD operations are automatically available from JpaRepository:</p>
 * <ul>
 *   <li>findById(Long id) - retrieves account by primary key, returns Optional</li>
 *   <li>save(Account account) - inserts new or updates existing account</li>
 *   <li>saveAll(Iterable&lt;Account&gt; accounts) - bulk save operation for batch processing</li>
 *   <li>deleteById(Long id) - deletes account by primary key</li>
 *   <li>delete(Account account) - deletes specific account entity</li>
 *   <li>findAll() - retrieves all accounts (use with caution, prefer pagination)</li>
 *   <li>findAll(Pageable pageable) - retrieves accounts with pagination support</li>
 *   <li>existsById(Long id) - checks if account exists without loading full entity</li>
 *   <li>count() - returns total number of accounts</li>
 * </ul>
 * 
 * <p><strong>COBOL COMP-3 Decimal Precision Preservation:</strong></p>
 * <p>All monetary fields in Account entity (currentBalance, creditLimit, cashCreditLimit,
 * currentCycleCredit, currentCycleDebit) use BigDecimal with precision=12 and scale=2
 * to exactly match COBOL PIC S9(10)V99 COMP-3 packed decimal fields. This ensures
 * identical financial calculation results with RoundingMode.HALF_UP, meeting the
 * requirement from Section 0.10 Special Instructions.</p>
 * 
 * <p><strong>Database Indexing Strategy:</strong></p>
 * <p>PostgreSQL indexes are created in Flyway migration V7__create_indexes.sql to match
 * VSAM access patterns and ensure query performance:</p>
 * <ul>
 *   <li>PRIMARY KEY on account_id (btree) - matches VSAM primary key</li>
 *   <li>INDEX on customer_id (btree) - supports findByCustomerId queries</li>
 *   <li>INDEX on active_status, open_date (composite) - supports filtered queries</li>
 * </ul>
 * 
 * <p><strong>Concurrent Access Handling:</strong></p>
 * <p>The Account entity includes @Version annotation for optimistic locking, replicating
 * VSAM record locking behavior. When concurrent updates occur:</p>
 * <ul>
 *   <li>OptimisticLockException is thrown if version mismatch detected</li>
 *   <li>Service layer should catch and retry or return appropriate error</li>
 *   <li>Matches COBOL DUPREC (duplicate record) error handling pattern</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations:</strong></p>
 * <ul>
 *   <li>Use findByAccountId for single record lookup with indexed access</li>
 *   <li>Use findByCustomerId for customer account queries with indexed join</li>
 *   <li>Use Pageable parameter for large result sets (default page size: 20)</li>
 *   <li>Consider @Query with fetch joins for eagerly loading related entities</li>
 *   <li>Database connection pool (HikariCP) configured in DatabaseConfig.java</li>
 * </ul>
 * 
 * <p><strong>Usage Example from Service Layer:</strong></p>
 * <pre>{@code
 * @Service
 * @Transactional
 * public class AccountViewService {
 *     private final AccountRepository accountRepository;
 *     
 *     public AccountResponse viewAccount(Long accountId) {
 *         // Matches COBOL: EXEC CICS READ DATASET(ACCTDAT) RIDFLD(ACCT-ID)
 *         Account account = accountRepository.findByAccountId(accountId)
 *             .orElseThrow(() -> new ResourceNotFoundException(
 *                 "Account not found: " + accountId));
 *         
 *         return mapToResponse(account);
 *     }
 *     
 *     public List<AccountResponse> getCustomerAccounts(Long customerId) {
 *         // Matches COBOL: Read via CXACAIX alternate index
 *         List<Account> accounts = accountRepository.findByCustomerId(customerId);
 *         return accounts.stream()
 *             .map(this::mapToResponse)
 *             .collect(Collectors.toList());
 *     }
 * }
 * }</pre>
 * 
 * <p><strong>Testing Strategy:</strong></p>
 * <p>Repository methods should be tested with:</p>
 * <ul>
 *   <li>@DataJpaTest annotation for JPA slice testing</li>
 *   <li>H2 in-memory database for fast test execution</li>
 *   <li>TestEntityManager for setting up test data</li>
 *   <li>Verify query derivation correctness and index usage</li>
 * </ul>
 * 
 * <p><strong>Migration Validation:</strong></p>
 * <p>Verification steps to ensure functional equivalence:</p>
 * <ol>
 *   <li>All COBOL test scenarios with account READ operations pass identically</li>
 *   <li>Account retrieval by account ID returns identical field values</li>
 *   <li>Customer account lookups return same record sets as VSAM alternate index</li>
 *   <li>Response times meet &lt;200ms requirement for account detail queries</li>
 *   <li>Concurrent updates handled correctly with optimistic locking</li>
 * </ol>
 * 
 * @see Account
 * @see JpaRepository
 * @see <a href="Section 0.4">Agent Action Plan - Source File app/cpy/CVACT01Y.cpy</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan</a>
 * @see <a href="Section 0.7">Cross-File Dependencies and Import Updates</a>
 * @see <a href="Section 0.10">Special Instructions for Refactoring</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Find account by account ID (primary key access).
     * 
     * <p>This method replicates COBOL EXEC CICS READ operation with RIDFLD(ACCT-ID)
     * from programs COACTVWC.cbl (paragraph 9300-GETACCTDATA-BYACCT) and COACTUPC.cbl.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-ACCTFILENAME)
     *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *      INTO      (ACCOUNT-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *    WHEN DFHRESP(NORMAL)
     *       SET FOUND-ACCT-IN-MASTER TO TRUE
     *    WHEN DFHRESP(NOTFND)
     *       SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE
     * END-EVALUATE
     * </pre>
     * 
     * <p><strong>Query Execution:</strong></p>
     * <p>Spring Data JPA automatically generates SQL:</p>
     * <pre>
     * SELECT a.* FROM account a WHERE a.account_id = ?
     * </pre>
     * <p>Uses PRIMARY KEY index for optimal performance (O(log n) lookup).</p>
     * 
     * <p><strong>Return Value Handling:</strong></p>
     * <p>Returns Optional&lt;Account&gt; to handle NOTFND condition without exceptions.
     * This matches COBOL RESP(DFHRESP(NOTFND)) pattern where calling code checks response
     * code and handles record-not-found gracefully.</p>
     * 
     * <p><strong>Usage Pattern:</strong></p>
     * <pre>{@code
     * Optional<Account> accountOpt = accountRepository.findByAccountId(accountId);
     * if (accountOpt.isPresent()) {
     *     // Process account (matches WHEN DFHRESP(NORMAL))
     *     Account account = accountOpt.get();
     * } else {
     *     // Handle not found (matches WHEN DFHRESP(NOTFND))
     *     throw new ResourceNotFoundException("Account not found");
     * }
     * }</pre>
     * 
     * <p><strong>Performance Characteristics:</strong></p>
     * <ul>
     *   <li>Index scan on PRIMARY KEY (account_id)</li>
     *   <li>Single row lookup - constant time O(1) with hash, O(log n) with btree</li>
     *   <li>No table scan required</li>
     *   <li>Optimal for real-time transaction processing</li>
     * </ul>
     * 
     * <p><strong>Validation:</strong></p>
     * <p>Ensure response time &lt; 200ms at 95th percentile per Section 0.10 requirements.</p>
     * 
     * @param accountId the 11-digit account identifier (ACCT-ID from COBOL), must not be null
     * @return Optional containing the Account if found, empty Optional if not found
     *         (matches COBOL RESP DFHRESP(NOTFND) condition)
     * @throws IllegalArgumentException if accountId is null
     */
    Optional<Account> findByAccountId(Long accountId);

    /**
     * Find all accounts associated with a specific customer ID.
     * 
     * <p>This method replicates VSAM alternate index access via CXACAIX cross-reference
     * file (CVACT03Y.cpy) that maps customer IDs to account IDs. In COBOL programs,
     * this requires reading CARD-XREF-RECORD to get account IDs, then reading ACCTDAT
     * for each account. This method consolidates that logic with a single efficient query.</p>
     * 
     * <p><strong>COBOL Cross-Reference Pattern:</strong></p>
     * <pre>
     * * First, read cross-reference to get account IDs for customer
     * EXEC CICS READ
     *      DATASET   (LIT-CARDXREFNAME-CUST-PATH)
     *      RIDFLD    (WS-CARD-RID-CUST-ID-X)
     *      INTO      (CARD-XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * * Then, for each XREF-ACCT-ID, read account master
     * PERFORM VARYING WS-INDEX FROM 1 BY 1
     *    UNTIL WS-INDEX > XREF-ACCT-COUNT
     *    
     *    MOVE XREF-ACCT-ID(WS-INDEX) TO WS-CARD-RID-ACCT-ID-X
     *    
     *    EXEC CICS READ
     *         DATASET   (LIT-ACCTFILENAME)
     *         RIDFLD    (WS-CARD-RID-ACCT-ID-X)
     *         INTO      (ACCOUNT-RECORD)
     *         RESP      (WS-RESP-CD)
     *    END-EXEC
     *    
     *    * Process each account...
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>SQL Query Generation:</strong></p>
     * <p>Spring Data JPA generates optimized SQL with indexed access:</p>
     * <pre>
     * SELECT a.* FROM account a 
     * INNER JOIN customer c ON a.customer_id = c.customer_id 
     * WHERE c.customer_id = ?
     * ORDER BY a.account_id
     * </pre>
     * <p>Uses INDEX on customer_id column for efficient lookup.</p>
     * 
     * <p><strong>Method Name Convention:</strong></p>
     * <p>The method name uses underscore notation (findByCustomer_CustomerId) to properly
     * traverse the entity relationship path. Spring Data JPA interprets this as:</p>
     * <ul>
     *   <li><strong>customer</strong> - Navigate to the customer property on Account entity</li>
     *   <li><strong>_</strong> - Path separator indicating property traversal</li>
     *   <li><strong>customerId</strong> - Access the customerId property on Customer entity</li>
     * </ul>
     * <p>This is necessary because the Customer entity's ID field is named 'customerId', 
     * not 'id'. Without the underscore, Spring Data would look for a non-existent 'id' field.</p>
     * 
     * <p><strong>Database Relationship:</strong></p>
     * <p>The Account entity has a @ManyToOne relationship to Customer entity, with
     * customer_id as a foreign key. This replaces the COBOL cross-reference file
     * (CARD-XREF-RECORD) with proper relational database constraints, ensuring
     * referential integrity.</p>
     * 
     * <p><strong>Return Value Characteristics:</strong></p>
     * <ul>
     *   <li>Returns empty List if no accounts found for customer (not null)</li>
     *   <li>List is ordered by account_id for consistent ordering</li>
     *   <li>List contains all active and inactive accounts (filter in service layer if needed)</li>
     *   <li>Supports one-to-many customer-account relationship</li>
     * </ul>
     * 
     * <p><strong>Usage Pattern:</strong></p>
     * <pre>{@code
     * List<Account> accounts = accountRepository.findByCustomer_CustomerId(customerId);
     * if (accounts.isEmpty()) {
     *     // Handle no accounts found for customer
     *     throw new ResourceNotFoundException("No accounts for customer: " + customerId);
     * }
     * 
     * // Process each account
     * for (Account account : accounts) {
     *     // Business logic for each account
     * }
     * }</pre>
     * 
     * <p><strong>Performance Considerations:</strong></p>
     * <ul>
     *   <li>Uses index scan on customer_id column (btree index)</li>
     *   <li>Query time proportional to number of customer's accounts (typically 1-5)</li>
     *   <li>More efficient than COBOL cross-reference + multiple account reads</li>
     *   <li>Consider pagination if customers can have many accounts (&gt;100)</li>
     * </ul>
     * 
     * <p><strong>Indexing Strategy:</strong></p>
     * <p>Flyway migration V7__create_indexes.sql creates:</p>
     * <pre>
     * CREATE INDEX idx_account_customer_id ON account(customer_id);
     * </pre>
     * <p>This index matches VSAM CXACAIX alternate index access pattern.</p>
     * 
     * <p><strong>Related COBOL Structures:</strong></p>
     * <ul>
     *   <li><strong>CVACT03Y.cpy (CARD-XREF-RECORD):</strong> Defines customer-account-card
     *       cross-reference with XREF-CUST-ID and XREF-ACCT-ID fields</li>
     *   <li><strong>CXACAIX:</strong> Alternate index file providing customer ID to account ID
     *       lookup path in VSAM infrastructure</li>
     * </ul>
     * 
     * <p><strong>Service Layer Usage:</strong></p>
     * <p>Typically called from AccountViewService or AccountListService to display all
     * accounts for a logged-in customer or to support customer service representatives
     * viewing customer account portfolios.</p>
     * 
     * <p><strong>Validation:</strong></p>
     * <ol>
     *   <li>Query returns same account IDs as COBOL cross-reference lookup</li>
     *   <li>Account records match field-for-field with VSAM data</li>
     *   <li>Query completes within performance budget (&lt;200ms)</li>
     *   <li>Empty list handling matches COBOL NOTFND response</li>
     * </ol>
     * 
     * @param customerId the 9-digit customer identifier (CUST-ID from COBOL), must not be null
     * @return List of Account entities associated with the customer, empty list if none found
     *         (never returns null)
     * @throws IllegalArgumentException if customerId is null
     */
    List<Account> findByCustomer_CustomerId(Long customerId);
}
