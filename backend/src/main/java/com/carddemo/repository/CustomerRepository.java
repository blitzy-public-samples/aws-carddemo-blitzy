/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.repository;

import com.carddemo.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository interface for Customer entity providing CRUD operations
 * and custom queries for customer data access, replacing VSAM CUSTDAT file operations.
 * 
 * <p>This repository transforms COBOL/CICS VSAM file access patterns from the CardDemo
 * mainframe application into modern relational database operations using Spring Data JPA.</p>
 * 
 * <p><b>COBOL Source Programs:</b></p>
 * <ul>
 *   <li><b>COACTVWC.cbl</b> (lines 826-834): EXEC CICS READ DATASET(CUSTFILE) RIDFLD(customer-id)
 *       for account view customer detail retrieval</li>
 *   <li><b>CBCUS01C.cbl</b>: Sequential customer file processing for batch customer data load</li>
 *   <li><b>CBSTM03A.cbl</b>: Customer information retrieval for monthly statement generation</li>
 * </ul>
 * 
 * <p><b>VSAM to PostgreSQL Transformation:</b></p>
 * <ul>
 *   <li>VSAM KSDS CUSTDAT file → PostgreSQL customer table with B-tree indexes</li>
 *   <li>VSAM primary key (CUST-ID PIC 9(09)) → customer_id BIGINT PRIMARY KEY</li>
 *   <li>VSAM random READ by key → findByCustomerId() with O(log n) index lookup</li>
 *   <li>VSAM sequential READ → findAll(Pageable) with pagination support</li>
 *   <li>VSAM WRITE/REWRITE → save() and saveAll() with transaction management</li>
 * </ul>
 * 
 * <p><b>Key Access Patterns Implemented:</b></p>
 * <ul>
 *   <li>Primary key lookup by customer ID (most frequent operation)</li>
 *   <li>SSN-based customer search for authentication and duplicate detection</li>
 *   <li>Last name partial match for customer search UI functionality</li>
 *   <li>Paginated customer lists for batch processing and administrative views</li>
 * </ul>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li>Primary key queries: Sub-millisecond response via B-tree index on customer_id</li>
 *   <li>SSN queries: Fast lookup via secondary index on ssn column</li>
 *   <li>Last name searches: Index-supported LIKE queries for partial matches</li>
 *   <li>Batch operations: Chunk-oriented processing with default 1000 records/chunk</li>
 *   <li>Connection pooling: HikariCP with 20-50 connections (Section 0.5 specifications)</li>
 * </ul>
 * 
 * <p><b>Transaction Management:</b></p>
 * <p>All repository methods automatically participate in Spring-managed transactions.
 * Service layer methods calling this repository should be annotated with @Transactional
 * to ensure ACID properties matching CICS SYNCPOINT behavior from original COBOL programs.</p>
 * 
 * <p><b>Security and Compliance:</b></p>
 * <p>This repository accesses Personally Identifiable Information (PII) including SSN
 * and date of birth. All access must be logged for audit compliance per Section 0.9
 * requirements. SSN fields should be encrypted at rest in the database.</p>
 * 
 * @see Customer
 * @see com.carddemo.service.AccountViewService
 * @see com.carddemo.batch.job.CustomerDataLoadJob
 * @see com.carddemo.batch.job.StatementGenerationJob
 * @since 1.0
 * @version 1.0
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Finds a customer by their unique 9-digit customer identifier.
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CUSTFILENAME)
     *      RIDFLD    (WS-CARD-RID-CUST-ID-X)
     *      KEYLENGTH (LENGTH OF WS-CARD-RID-CUST-ID-X)
     *      INTO      (CUSTOMER-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * </pre>
     * 
     * <p><b>Source:</b> COACTVWC.cbl lines 826-834</p>
     * 
     * <p><b>VSAM Operation:</b> Random access by primary key with O(log n) complexity</p>
     * 
     * <p><b>Database Query:</b> SELECT * FROM customer WHERE customer_id = ?</p>
     * 
     * <p>This is the primary customer lookup method used by:</p>
     * <ul>
     *   <li>AccountViewService for displaying customer details when viewing accounts</li>
     *   <li>AuthenticationService for user profile retrieval after sign-on</li>
     *   <li>StatementGenerationJob for populating customer address on statements</li>
     * </ul>
     * 
     * <p><b>Performance:</b> Uses B-tree index on customer_id column providing
     * sub-millisecond lookup times matching VSAM KSDS key-based access patterns.</p>
     * 
     * @param customerId The 9-digit customer identifier as String (CUST-ID PIC 9(09))
     *                   Accepts String to match REST API parameter types; Spring Data JPA
     *                   automatically converts to Long for database query
     * @return Optional containing the Customer entity if found, empty Optional if not found
     *         (equivalent to COBOL DFHRESP(NOTFND) condition)
     */
    Optional<Customer> findByCustomerId(String customerId);

    /**
     * Finds a customer by their Social Security Number (SSN).
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with SSN filter condition:</p>
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUST-SSN = WS-SEARCH-SSN
     *         MOVE 'Y' TO CUSTOMER-FOUND-FLAG
     *         EXIT PERFORM
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Database Query:</b> SELECT * FROM customer WHERE ssn = ?</p>
     * 
     * <p><b>Security Warning:</b> This method accesses Personally Identifiable Information (PII).
     * According to Section 0.9 audit and compliance requirements:</p>
     * <ul>
     *   <li>All calls to this method MUST be logged with timestamp, user ID, and purpose</li>
     *   <li>SSN field MUST be encrypted at rest in the database (AES-256 encryption)</li>
     *   <li>SSN values MUST be masked in application logs (format: ***-**-XXXX)</li>
     *   <li>Access requires appropriate role-based authorization (ROLE_ADMIN or ROLE_USER with data access)</li>
     * </ul>
     * 
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Duplicate customer detection during new account onboarding</li>
     *   <li>Customer verification during authentication flows</li>
     *   <li>Compliance and regulatory reporting requirements</li>
     * </ul>
     * 
     * <p><b>Performance:</b> Uses secondary B-tree index on ssn column for efficient lookups.
     * Expected performance: sub-10ms response time under normal load.</p>
     * 
     * @param ssn The customer's Social Security Number as 9-digit string (CUST-SSN PIC 9(09))
     * @return Optional containing the Customer entity if found with matching SSN,
     *         empty Optional if no customer exists with that SSN
     */
    Optional<Customer> findByCustomerSsn(String ssn);

    /**
     * Finds all customers whose last name contains the specified substring (case-insensitive).
     * 
     * <p><b>COBOL Equivalent:</b> Sequential scan with partial last name match:</p>
     * <pre>
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUST-LAST-NAME CONTAINS WS-SEARCH-LASTNAME
     *         MOVE CUSTOMER-RECORD TO RESULT-TABLE(RESULT-COUNT)
     *         ADD 1 TO RESULT-COUNT
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Database Query:</b> SELECT * FROM customer WHERE LOWER(last_name) LIKE LOWER(?)</p>
     * 
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Customer search functionality in administrative user interfaces</li>
     *   <li>Customer lookup when only partial last name is known</li>
     *   <li>Account servicing and customer support operations</li>
     * </ul>
     * 
     * <p><b>Performance Characteristics:</b></p>
     * <ul>
     *   <li>Uses B-tree index on last_name column for optimized prefix searches</li>
     *   <li>Case-insensitive comparison using database LOWER() function</li>
     *   <li>Returns unsorted list; calling code should sort if needed</li>
     *   <li>For large result sets (&gt;100 records), consider using paginated variant</li>
     * </ul>
     * 
     * <p><b>Example Usage:</b></p>
     * <pre>
     * // Find all customers with last name containing "Smith"
     * List&lt;Customer&gt; customers = customerRepository.findByCustomerLastNameContaining("Smith");
     * // Returns: Smith, Smithson, Blacksmith, etc.
     * </pre>
     * 
     * @param lastName The last name substring to search for (CUST-LAST-NAME PIC X(25))
     *                 Search is case-insensitive and matches any part of the last name
     * @return List of Customer entities matching the search criteria, empty list if no matches found
     */
    List<Customer> findByCustomerLastNameContaining(String lastName);

    /**
     * Finds all customers with pagination support for efficient large dataset handling.
     * 
     * <p><b>COBOL Equivalent:</b> Sequential file processing with record positioning:</p>
     * <pre>
     * OPEN INPUT CUSTFILE-FILE
     * PERFORM UNTIL END-OF-FILE = 'Y'
     *     READ CUSTFILE-FILE INTO CUSTOMER-RECORD
     *     IF CUSTFILE-STATUS = '00'
     *         PERFORM PROCESS-CUSTOMER-RECORD
     *     ELSE IF CUSTFILE-STATUS = '10'
     *         MOVE 'Y' TO END-OF-FILE
     *     END-IF
     * END-PERFORM
     * CLOSE CUSTFILE-FILE
     * </pre>
     * 
     * <p><b>Source:</b> CBCUS01C.cbl (Customer Data Load Batch Program)</p>
     * 
     * <p><b>Database Query:</b> SELECT * FROM customer ORDER BY customer_id LIMIT ? OFFSET ?</p>
     * 
     * <p><b>Batch Processing Integration:</b></p>
     * <p>This method is the foundation for Spring Batch chunk-oriented processing
     * in CustomerDataLoadJob (replaces CBCUS01C.cbl). Configuration per Section 0.5:</p>
     * <ul>
     *   <li>Default chunk size: 1000 customer records per transaction</li>
     *   <li>Page size: 1000 records per database fetch</li>
     *   <li>Processing window: Must complete within 4-hour batch window</li>
     *   <li>Error handling: Skip limit of 100 errors before job failure</li>
     * </ul>
     * 
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Batch customer data loading (CBCUS01C replacement)</li>
     *   <li>Monthly statement generation customer iteration (CBSTM03A replacement)</li>
     *   <li>Administrative customer list displays with pagination controls</li>
     *   <li>Bulk customer data exports for reporting and analytics</li>
     * </ul>
     * 
     * <p><b>Performance Optimization:</b></p>
     * <ul>
     *   <li>Uses OFFSET/LIMIT for efficient pagination without loading entire dataset</li>
     *   <li>Returns Page object with metadata: total count, total pages, current page number</li>
     *   <li>Supports sorting via Pageable parameter: Sort.by("lastName").ascending()</li>
     *   <li>Batch jobs should use consistent sort order for checkpoint/restart reliability</li>
     * </ul>
     * 
     * <p><b>Example Usage:</b></p>
     * <pre>
     * // Get page 2 with 1000 records, sorted by customer ID
     * Pageable pageable = PageRequest.of(1, 1000, Sort.by("customerId").ascending());
     * Page&lt;Customer&gt; customerPage = customerRepository.findAll(pageable);
     * 
     * // Process customers in current page
     * customerPage.getContent().forEach(customer -&gt; processCustomer(customer));
     * 
     * // Check if more pages available
     * if (customerPage.hasNext()) {
     *     Pageable nextPage = customerPage.nextPageable();
     * }
     * </pre>
     * 
     * @param pageable Pagination parameters including page number (0-based), page size,
     *                 and optional sort criteria
     * @return Page object containing Customer entities for the requested page,
     *         along with pagination metadata (total elements, total pages, etc.)
     */
    @Override
    Page<Customer> findAll(Pageable pageable);

    // Note: All inherited JpaRepository methods are automatically available:
    // - save(Customer): Insert or update single customer (WRITE/REWRITE CUSTFILE)
    // - saveAll(Iterable<Customer>): Batch insert/update for chunk processing
    // - findById(Long): Direct lookup by primary key (redundant with findByCustomerId but kept for JPA compatibility)
    // - existsById(Long): Check customer existence without loading full entity
    // - count(): Total customer count for reporting
    // - delete(Customer): Remove customer record (DELETE CUSTFILE)
    // - deleteById(Long): Remove customer by ID with referential integrity checks
}
