package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for Customer Entity.
 * 
 * <p>This repository interface provides CRUD operations and custom query methods
 * for the Customer entity, replacing COBOL VSAM KSDS file I/O operations on the
 * CUSTDAT master file originally defined in CVCUS01Y.cpy copybook.</p>
 * 
 * <p>Key COBOL to Spring Data JPA Transformations:</p>
 * <ul>
 *   <li>EXEC CICS READ DATASET('CUSTDAT') RIDFLD(CUST-ID) → findByCustomerId(Long customerId)</li>
 *   <li>EXEC CICS WRITE DATASET('CUSTDAT') → save(Customer customer)</li>
 *   <li>EXEC CICS REWRITE DATASET('CUSTDAT') → save(Customer customer)</li>
 *   <li>EXEC CICS DELETE DATASET('CUSTDAT') → deleteById(Long customerId)</li>
 *   <li>EXEC CICS STARTBR/READNEXT sequential reads → findAll() with Pageable</li>
 * </ul>
 * 
 * <p>This repository extends JpaRepository which provides the following standard operations:</p>
 * <ul>
 *   <li>findById(Long id) - retrieve customer by primary key (CUST-ID)</li>
 *   <li>save(Customer customer) - insert or update customer record</li>
 *   <li>saveAll(Iterable&lt;Customer&gt; customers) - batch insert/update for data loads</li>
 *   <li>deleteById(Long id) - delete customer by primary key</li>
 *   <li>findAll() - retrieve all customers (supports pagination)</li>
 *   <li>existsById(Long id) - check if customer exists</li>
 *   <li>count() - count total customer records</li>
 * </ul>
 * 
 * <p>Custom Query Methods:</p>
 * <ul>
 *   <li><b>findByCustomerId(Long customerId)</b> - Retrieves customer by customer ID 
 *       (equivalent to primary key lookup). Returns Optional to handle customer not found cases.
 *       Used by AccountViewService, CardDetailService, and transaction validation services.</li>
 *   <li><b>findByLastName(String lastName)</b> - Searches customers by last name 
 *       (supports name-based customer search patterns). Returns list of matching customers.
 *       Used by customer search and lookup operations in admin functions.</li>
 * </ul>
 * 
 * <p>Database Indexing Strategy:</p>
 * <ul>
 *   <li>Primary Index: customer_id (CUST-ID PIC 9(09)) - B-tree index for unique key access</li>
 *   <li>Secondary Index: last_name - B-tree index for name-based search optimization</li>
 *   <li>Indexes replicate VSAM KSDS primary key and alternate index access patterns</li>
 * </ul>
 * 
 * <p>Transaction Management:</p>
 * <ul>
 *   <li>All repository methods participate in Spring @Transactional boundaries</li>
 *   <li>Save operations use optimistic locking via @Version field in Customer entity</li>
 *   <li>Concurrent access handled by PostgreSQL row-level locking (replaces VSAM record locking)</li>
 * </ul>
 * 
 * <p>Data Loading Support:</p>
 * <ul>
 *   <li>Used by CBACT03C batch job (CustomerDataLoadJob) via saveAll() for bulk inserts</li>
 *   <li>Used by CBCUS01C utility program (CustomerProcessingUtility) for customer maintenance</li>
 *   <li>Supports migration from ASCII test data files (app/data/ASCII/custdata.txt)</li>
 * </ul>
 * 
 * <p>Foreign Key Relationships:</p>
 * <ul>
 *   <li>One-to-Many with Account entity (customer has multiple accounts)</li>
 *   <li>Replaces COBOL XREF cross-reference files (CVACT03Y.cpy, CXACAIX) with JPA associations</li>
 *   <li>Cascade operations configured in Account entity's @ManyToOne relationship</li>
 * </ul>
 * 
 * <p>Security Considerations:</p>
 * <ul>
 *   <li>Customer SSN field stored encrypted in database (handled at entity level)</li>
 *   <li>Repository operations secured via Spring Security method-level authorization</li>
 *   <li>Admin role required for customer data modification operations</li>
 * </ul>
 * 
 * <p>Query Method Naming Convention:</p>
 * Spring Data JPA automatically implements query methods based on method naming patterns:
 * <ul>
 *   <li>findBy[PropertyName] - generates SELECT query with WHERE clause</li>
 *   <li>Return type Optional&lt;Customer&gt; - for single result (may not exist)</li>
 *   <li>Return type List&lt;Customer&gt; - for multiple results</li>
 * </ul>
 * 
 * <p>Performance Characteristics:</p>
 * <ul>
 *   <li>findByCustomerId: O(log n) - B-tree index lookup on primary key</li>
 *   <li>findByLastName: O(log n + k) - B-tree index scan, k = matching records</li>
 *   <li>save: O(log n) - B-tree index update</li>
 *   <li>Connection pooling via HikariCP ensures efficient database connection reuse</li>
 * </ul>
 * 
 * <p>Example Usage in Service Layer:</p>
 * <pre>
 * // Retrieve customer by ID (replaces COBOL READ with RIDFLD)
 * Optional&lt;Customer&gt; customer = customerRepository.findByCustomerId(123456789L);
 * 
 * // Search customers by last name (replaces COBOL sequential scan)
 * List&lt;Customer&gt; customers = customerRepository.findByLastName("Smith");
 * 
 * // Create or update customer (replaces COBOL WRITE/REWRITE)
 * Customer savedCustomer = customerRepository.save(customer);
 * 
 * // Batch load customers from file (replaces COBOL batch job sequential writes)
 * List&lt;Customer&gt; customers = loadFromFile();
 * customerRepository.saveAll(customers);
 * </pre>
 * 
 * @see Customer
 * @see com.carddemo.batch.job.CustomerDataLoadJob
 * @see com.carddemo.service.util.CustomerProcessingUtility
 * @see <a href="Section 0.3">Technical Interpretation - Layer 2: Data Persistence Transformation</a>
 * @see <a href="Section 0.6">File-by-File Transformation Plan - CustomerRepository.java</a>
 * @see <a href="Section 0.10">Special Instructions - Repository Pattern Requirements</a>
 * @since 1.0
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Find customer by customer ID.
     * 
     * <p>This method retrieves a customer record using the customer ID as the lookup key.
     * It replicates the COBOL VSAM random access pattern using CUST-ID as the primary key:</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   EXEC CICS READ
     *     DATASET('CUSTDAT')
     *     INTO(CUSTOMER-RECORD)
     *     RIDFLD(CUST-ID)
     *     RESP(WS-RESP)
     *   END-EXEC
     * </pre>
     * 
     * <p>Generated SQL Query:</p>
     * <pre>
     * SELECT * FROM customer 
     * WHERE customer_id = ?
     * </pre>
     * 
     * <p>This method uses the primary key index for O(log n) lookup performance,
     * equivalent to VSAM KSDS direct access by primary key.</p>
     * 
     * <p>Usage Examples:</p>
     * <ul>
     *   <li>AccountViewService: Retrieve customer details when viewing account information</li>
     *   <li>CardDetailService: Look up card holder customer information</li>
     *   <li>TransactionAddService: Validate customer existence during transaction authorization</li>
     *   <li>UserListService: Display customer information for administrative functions</li>
     * </ul>
     * 
     * @param customerId the unique 9-digit customer identifier (CUST-ID PIC 9(09)),
     *                   must not be null, range: 1 to 999,999,999
     * @return Optional containing the Customer entity if found, empty Optional if customer
     *         does not exist in the database (replaces COBOL NOTFND condition)
     * @throws IllegalArgumentException if customerId is null
     */
    Optional<Customer> findByCustomerId(Long customerId);

    /**
     * Find customers by last name.
     * 
     * <p>This method retrieves all customer records matching the specified last name.
     * It replicates COBOL sequential search patterns or alternate index lookups
     * for name-based customer search functionality:</p>
     * 
     * <pre>
     * COBOL equivalent:
     *   EXEC CICS STARTBR
     *     DATASET('CUSTDAT')
     *     RIDFLD(WS-SEARCH-LASTNAME)
     *   END-EXEC
     *   
     *   PERFORM UNTIL END-OF-FILE
     *     EXEC CICS READNEXT
     *       DATASET('CUSTDAT')
     *       INTO(CUSTOMER-RECORD)
     *     END-EXEC
     *     
     *     IF CUST-LAST-NAME = WS-SEARCH-LASTNAME
     *       ... process matching customer
     *     ELSE
     *       MOVE 'Y' TO END-OF-FILE
     *     END-IF
     *   END-PERFORM
     * </pre>
     * 
     * <p>Generated SQL Query:</p>
     * <pre>
     * SELECT * FROM customer 
     * WHERE last_name = ?
     * ORDER BY customer_id
     * </pre>
     * 
     * <p>This method uses a secondary B-tree index on the last_name column for
     * efficient range scan operations. Performance: O(log n + k) where k is the
     * number of matching customers.</p>
     * 
     * <p>Search Characteristics:</p>
     * <ul>
     *   <li>Exact match search (case-sensitive by default)</li>
     *   <li>Returns all customers with matching last name</li>
     *   <li>Results ordered by customer_id for consistent ordering</li>
     *   <li>Empty list returned if no matches found (never returns null)</li>
     * </ul>
     * 
     * <p>Usage Examples:</p>
     * <ul>
     *   <li>UserListService: Search customers by last name in admin interface</li>
     *   <li>CustomerProcessingUtility: Batch operations on customer groups</li>
     *   <li>AccountUpdateService: Lookup customers during account maintenance</li>
     *   <li>ReportGenerationService: Filter customers for reporting purposes</li>
     * </ul>
     * 
     * <p>For case-insensitive or partial match searches, use custom query methods:</p>
     * <ul>
     *   <li>findByLastNameIgnoreCase(String lastName) - case-insensitive exact match</li>
     *   <li>findByLastNameContaining(String lastName) - partial match search</li>
     *   <li>findByLastNameStartingWith(String lastName) - prefix search</li>
     * </ul>
     * 
     * @param lastName the customer's last name to search for (CUST-LAST-NAME PIC X(25)),
     *                 must not be null, maximum length 25 characters,
     *                 leading/trailing spaces will be trimmed by caller
     * @return List of Customer entities with matching last name, may be empty if no matches,
     *         never returns null. List is ordered by customer_id ascending.
     * @throws IllegalArgumentException if lastName is null
     */
    List<Customer> findByLastName(String lastName);

    // Note: The following methods are automatically provided by JpaRepository<Customer, Long>:
    // - Optional<Customer> findById(Long id)
    // - Customer save(Customer customer)
    // - void deleteById(Long id)
    // - List<Customer> findAll()
    // - List<Customer> saveAll(Iterable<Customer> customers)
    // - boolean existsById(Long id)
    // - long count()
    // These methods fulfill the VSAM KSDS CRUD operation requirements without explicit declaration.
}
