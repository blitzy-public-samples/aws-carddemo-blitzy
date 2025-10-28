package com.carddemo.repository;

import com.carddemo.model.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Customer Repository - Converted from VSAM CUSTFILE I/O operations
 * 
 * Spring Data JPA repository interface replacing COBOL VSAM CUSTFILE access from CBCUS01C.cbl.
 * Provides CRUD operations and custom query methods for customer master data persistence.
 * 
 * Original COBOL File: CUSTFILE (VSAM KSDS - Key-Sequenced Data Set)
 * Original Copybook: CVCUS01Y.cpy (CUSTOMER-RECORD structure with RECLN 500)
 * Target Database Table: customer (PostgreSQL with B-tree indexes)
 * 
 * COBOL-to-Java Conversion Mapping:
 * =====================================
 * 
 * VSAM Operations → JPA Repository Methods:
 * - EXEC CICS READ FILE('CUSTFILE') RIDFLD(CUST-ID) INTO(CUSTOMER-RECORD)
 *   → findById(Long customerId) returns Optional<Customer>
 * 
 * - EXEC CICS WRITE FILE('CUSTFILE') FROM(CUSTOMER-RECORD) RIDFLD(CUST-ID)
 *   → save(Customer customer) returns Customer (insert)
 * 
 * - EXEC CICS REWRITE FILE('CUSTFILE') FROM(CUSTOMER-RECORD)
 *   → save(Customer customer) returns Customer (update)
 * 
 * - EXEC CICS DELETE FILE('CUSTFILE') RIDFLD(CUST-ID)
 *   → deleteById(Long customerId) returns void
 * 
 * - READ CUSTFILE-FILE INTO CUSTOMER-RECORD (sequential batch access)
 *   → findAll() returns List<Customer> (for batch processing)
 * 
 * VSAM Alternate Index Access → Custom Query Methods:
 * - SSN-based lookup (alternate index on CUST-SSN)
 *   → findByCustSsn(String ssn) returns Optional<Customer>
 * 
 * - Name-based search (alternate index on CUST-LAST-NAME + CUST-FIRST-NAME)
 *   → findByCustLastNameAndCustFirstName(String lastName, String firstName) 
 *     returns List<Customer>
 * 
 * COBOL File Status → Spring Exception Handling:
 * - File-status '00' (success) → Successful method return
 * - File-status '10' (end-of-file) → Empty Optional or empty List
 * - File-status '23' (record not found) → Empty Optional
 * - File-status '22' (duplicate key) → DataIntegrityViolationException
 * - Other errors → Spring DataAccessException hierarchy
 * 
 * Performance Considerations:
 * ===========================
 * - Primary key lookup (custId) uses B-tree index for O(log n) access time
 * - SSN lookup uses idx_customer_ssn index for fast retrieval (maintains VSAM alternate key performance)
 * - Name search uses idx_customer_name composite index for efficient two-field queries
 * - Query response times must match or exceed VSAM key access performance (sub-10ms for primary key)
 * 
 * Data Security and Privacy:
 * ==========================
 * Customer records contain PII (Personally Identifiable Information) including:
 * - Social Security Numbers (custSsn) - MUST be masked in logs and protected in transit
 * - Government-issued ID numbers (custGovtIssuedId) - MUST be tokenized per security requirements
 * - Date of birth (custDobYyyyMmDd) - MUST be handled per GDPR/CCPA privacy regulations
 * - Full name and address - MUST be encrypted at rest in production environments
 * 
 * All repository methods inherit Spring Security's method-level authorization.
 * Sensitive customer data must be masked when returned to frontend or logged for debugging.
 * 
 * Transaction Management:
 * =======================
 * All repository operations participate in Spring's transaction management.
 * COBOL EXEC CICS SYNCPOINT boundaries are replicated using @Transactional 
 * annotations in service layer methods that call these repository methods.
 * 
 * Optimistic locking is implemented via Customer entity's @Version field to prevent
 * lost updates in concurrent modification scenarios (replaces COBOL VSAM RBA checking).
 * 
 * Batch Processing:
 * =================
 * The findAll() method supports COBOL batch program CBCUS01C.cbl sequential read pattern.
 * For large datasets, consider using Spring Data JPA's pagination support:
 * - findAll(Pageable pageable) for chunk-based batch processing
 * - Or use Spring Batch ItemReader with JpaPagingItemReader for memory-efficient streaming
 * 
 * Usage Examples:
 * ===============
 * // Primary key lookup (replaces COBOL READ with RIDFLD):
 * Optional<Customer> customer = customerRepository.findById(123456789L);
 * 
 * // Insert new customer (replaces COBOL WRITE):
 * Customer newCustomer = Customer.builder()
 *     .custId(123456789L)
 *     .custFirstName("John")
 *     .custLastName("Doe")
 *     .custSsn("123456789")
 *     .build();
 * customerRepository.save(newCustomer);
 * 
 * // Update existing customer (replaces COBOL REWRITE):
 * customer.ifPresent(c -> {
 *     c.setCustAddrZip("12345");
 *     customerRepository.save(c);
 * });
 * 
 * // Delete customer (replaces COBOL DELETE):
 * customerRepository.deleteById(123456789L);
 * 
 * // Lookup by SSN (replaces COBOL alternate index access):
 * Optional<Customer> customerBySsn = customerRepository.findByCustSsn("123456789");
 * 
 * // Search by name (replaces COBOL name-based browse):
 * List<Customer> customers = customerRepository.findByCustLastNameAndCustFirstName("Doe", "John");
 * 
 * Dependencies:
 * =============
 * - Customer entity (com.carddemo.model.entity.Customer) - JPA entity from CVCUS01Y.cpy
 * - JpaRepository - Spring Data JPA core repository interface
 * - Spring Boot 3.4.5 with Spring Data JPA 3.4.x
 * - PostgreSQL 16.x with JDBC driver 42.7.x
 * 
 * @see Customer JPA entity converted from CVCUS01Y.cpy copybook
 * @see CBCUS01C.cbl COBOL batch program for original sequential read pattern
 * 
 * @author CardDemo Modernization Team
 * @version 1.0
 * @since 2024
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Find customer by Social Security Number (SSN).
     * 
     * Replaces COBOL alternate index access on CUST-SSN field (PIC 9(09)).
     * Uses PostgreSQL index idx_customer_ssn for fast SSN-based lookup.
     * 
     * COBOL Equivalent:
     * <pre>
     * EXEC CICS READ FILE('CUSTFILE')
     *     RIDFLD(WS-SSN)
     *     INTO(CUSTOMER-RECORD)
     *     GENERIC
     * END-EXEC
     * </pre>
     * 
     * Performance: Uses B-tree index scan on idx_customer_ssn for O(log n) lookup.
     * Expected response time: < 10ms for indexed SSN lookup.
     * 
     * Security Note: SSN is PII data. Ensure calling service methods:
     * - Validate user authorization before invoking this method
     * - Mask SSN in API responses (e.g., "***-**-6789")
     * - Log only masked SSN values for audit trail
     * 
     * @param ssn Customer Social Security Number as 9-digit string (e.g., "123456789")
     *            Must preserve leading zeros from COBOL PIC 9(09) format
     *            Expected format: exactly 9 numeric digits without dashes
     * @return Optional containing Customer if found with matching SSN, 
     *         empty Optional if no customer with specified SSN exists
     *         (replaces COBOL file-status '23' for record not found)
     */
    Optional<Customer> findByCustSsn(String ssn);

    /**
     * Find customers by last name and first name.
     * 
     * Replaces COBOL alternate index search on CUST-LAST-NAME + CUST-FIRST-NAME fields.
     * Uses PostgreSQL composite index idx_customer_name for efficient two-field query.
     * 
     * COBOL Equivalent:
     * <pre>
     * EXEC CICS STARTBR FILE('CUSTFILE')
     *     RIDFLD(WS-CUSTOMER-NAME-KEY)
     *     GTEQ
     * END-EXEC
     * 
     * PERFORM UNTIL END-OF-BROWSE
     *     EXEC CICS READNEXT FILE('CUSTFILE')
     *         RIDFLD(WS-CUSTOMER-NAME-KEY)
     *         INTO(CUSTOMER-RECORD)
     *     END-EXEC
     *     
     *     IF CUST-LAST-NAME NOT = WS-SEARCH-LAST-NAME
     *         OR CUST-FIRST-NAME NOT = WS-SEARCH-FIRST-NAME
     *         SET END-OF-BROWSE TO TRUE
     *     END-IF
     * END-PERFORM
     * 
     * EXEC CICS ENDBR FILE('CUSTFILE') END-EXEC
     * </pre>
     * 
     * Performance: Uses composite B-tree index scan on (cust_last_name, cust_first_name).
     * Expected response time: < 50ms for name-based searches returning up to 100 rows.
     * 
     * Use Cases:
     * - Customer service representative searching for customer by name
     * - Duplicate customer detection during new customer enrollment
     * - Customer identity verification during support calls
     * 
     * Note: Returns List (potentially multiple customers) because names are not unique.
     * For exact match requirements, calling service should validate other fields 
     * (DOB, address, phone) to confirm customer identity.
     * 
     * @param lastName Customer last name (CUST-LAST-NAME PIC X(25) from COBOL)
     *                 Case-sensitive exact match required
     *                 Spaces are significant (COBOL trailing space behavior preserved)
     * @param firstName Customer first name (CUST-FIRST-NAME PIC X(25) from COBOL)
     *                  Case-sensitive exact match required
     *                  Spaces are significant (COBOL trailing space behavior preserved)
     * @return List of Customer entities matching specified name criteria.
     *         Empty list if no customers found with matching name combination.
     *         List may contain multiple customers (names are not unique).
     *         (replaces COBOL file-status '10' for end-of-file with empty list)
     */
    List<Customer> findByCustLastNameAndCustFirstName(String lastName, String firstName);

    /*
     * Additional Inherited Methods from JpaRepository<Customer, Long>:
     * =================================================================
     * All methods below are inherited from JpaRepository and its parent interfaces
     * (CrudRepository, PagingAndSortingRepository). They replace standard VSAM I/O operations.
     * 
     * From CrudRepository:
     * -------------------
     * - save(Customer entity) - Insert or update (replaces WRITE/REWRITE)
     * - saveAll(Iterable<Customer> entities) - Batch insert/update
     * - findById(Long id) - Primary key lookup (replaces READ with RIDFLD)
     * - existsById(Long id) - Check if customer exists
     * - findAll() - Sequential read all (replaces batch READ loop)
     * - findAllById(Iterable<Long> ids) - Batch primary key lookup
     * - count() - Count total customers (replaces COBOL counter accumulation)
     * - deleteById(Long id) - Delete by primary key (replaces DELETE with RIDFLD)
     * - delete(Customer entity) - Delete by entity (replaces DELETE with current record)
     * - deleteAll() - Delete all customers (use with extreme caution)
     * - deleteAll(Iterable<Customer> entities) - Batch delete
     * - deleteAllById(Iterable<Long> ids) - Batch delete by primary keys
     * 
     * From PagingAndSortingRepository:
     * --------------------------------
     * - findAll(Sort sort) - Sorted retrieval (replaces COBOL SORT verb)
     * - findAll(Pageable pageable) - Paginated retrieval for batch processing
     * 
     * Transaction Behavior:
     * --------------------
     * All repository methods participate in Spring's transaction management.
     * Use @Transactional annotation in service layer to define transaction boundaries
     * (replaces COBOL EXEC CICS SYNCPOINT for commit, EXEC CICS ROLLBACK for rollback).
     * 
     * Exception Handling:
     * ------------------
     * Repository methods throw Spring's DataAccessException hierarchy:
     * - DataIntegrityViolationException (replaces COBOL file-status '22' duplicate key)
     * - EmptyResultDataAccessException (replaces COBOL file-status '23' not found)
     * - JpaSystemException for other persistence errors
     * 
     * Performance Notes:
     * -----------------
     * - Primary key operations (findById, deleteById) use clustered index for O(1) access
     * - findAll() loads entire table - use pagination for large datasets
     * - Batch operations (saveAll, deleteAll) are more efficient than individual calls
     * - Consider using Spring Batch for large-scale batch processing (10,000+ records)
     */
}
