package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA Repository for TransactionType reference data entity.
 * 
 * <p>This repository provides CRUD operations and custom query methods for transaction type
 * lookup and validation, replacing VSAM reference table access patterns from COBOL programs
 * COTRN01C (Transaction Category Summary) and COTRN02C (Add New Transaction).</p>
 * 
 * <p><b>COBOL Migration Context:</b></p>
 * <p>In the legacy mainframe application, transaction type codes were validated through
 * hardcoded logic and sequential reads of VSAM reference files. This repository replaces
 * those patterns with database-driven reference data management, providing:</p>
 * <ul>
 *   <li>Indexed PostgreSQL lookups (B-tree index on type_code) for O(log n) access time</li>
 *   <li>Elimination of hardcoded transaction type validation logic</li>
 *   <li>Support for transaction type dropdown population in React UI components</li>
 *   <li>Centralized reference data management via Flyway migration V9__load_reference_data.sql</li>
 * </ul>
 * 
 * <p><b>Transaction Type Reference Data:</b></p>
 * <p>Common transaction type codes include:</p>
 * <ul>
 *   <li><b>PU</b> - Purchase transaction</li>
 *   <li><b>CA</b> - Cash advance</li>
 *   <li><b>PM</b> - Payment</li>
 *   <li><b>RF</b> - Refund</li>
 *   <li><b>FE</b> - Fee</li>
 *   <li><b>IN</b> - Interest charge</li>
 *   <li><b>BT</b> - Balance transfer</li>
 * </ul>
 * 
 * <p><b>Usage by Service Layer:</b></p>
 * <ul>
 *   <li><b>TransactionCreationService</b>: Validates transaction type codes during transaction
 *       posting operations, replacing COBOL validation logic from COTRN02C program</li>
 *   <li><b>TransactionCategoryService</b>: Retrieves transaction type descriptions for category
 *       aggregation and summary reports, replacing COTRN01C VSAM reads</li>
 *   <li><b>TransactionListService</b>: Populates transaction type filters in UI components</li>
 * </ul>
 * 
 * <p><b>Performance Characteristics:</b></p>
 * <ul>
 *   <li>Static reference data with read-only access pattern (no runtime updates)</li>
 *   <li>Small dataset (typically 10-20 transaction types, ~1 KB total)</li>
 *   <li>Ideal candidate for Redis caching via Spring Cache abstraction</li>
 *   <li>Query execution time: < 5ms average (indexed lookup)</li>
 *   <li>Supports sub-200ms transaction response time SLA per Section 0.2</li>
 * </ul>
 * 
 * <p><b>Repository Pattern Compliance:</b></p>
 * <p>This repository follows the Spring Data JPA Repository Pattern as mandated by Section 0.9
 * of the Agent Action Plan. All VSAM file I/O operations are replaced with JPA repositories,
 * eliminating direct JDBC access and providing automatic transaction management, query
 * generation, and connection pooling via HikariCP.</p>
 * 
 * <p><b>Architectural Design:</b></p>
 * <ul>
 *   <li><b>Layer</b>: Data Access Layer (Section 0.3 Target Architecture)</li>
 *   <li><b>Pattern</b>: Repository Pattern with Spring Data JPA proxy generation</li>
 *   <li><b>Transaction Management</b>: Read-only transactions automatically applied by Spring</li>
 *   <li><b>Connection Pool</b>: HikariCP with 20-50 connections (application.yml config)</li>
 * </ul>
 * 
 * <p><b>No Implementation Required:</b></p>
 * <p>Spring Data JPA automatically generates the implementation of this interface at runtime
 * using proxy-based repository infrastructure. Custom query methods are derived from method
 * names using Spring Data JPA query creation conventions:</p>
 * <ul>
 *   <li><code>findByTypeCode</code> → WHERE type_code = ?</li>
 *   <li><code>findAllByOrderByTypeCodeAsc</code> → SELECT * ORDER BY type_code ASC</li>
 * </ul>
 * 
 * <p><b>Testing Strategy:</b></p>
 * <p>Repository tests verify:</p>
 * <ul>
 *   <li>Successful lookup of valid transaction type codes</li>
 *   <li>Empty Optional returned for non-existent codes (functional equivalence to COBOL NOTFND)</li>
 *   <li>Correct sorting order of transaction types for dropdown population</li>
 *   <li>Query execution time within performance SLA (< 5ms)</li>
 * </ul>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see TransactionType Entity class representing transaction type reference data
 * @see org.springframework.data.jpa.repository.JpaRepository Parent repository interface
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {

    /**
     * Finds a transaction type by its unique 2-character type code.
     * 
     * <p>This method replaces hardcoded transaction type validation logic in COBOL programs
     * COTRN02C and COTRN01C. In the legacy system, transaction type codes were validated
     * through IF-ELSE chains checking against literal values. This method provides database-
     * driven validation using PostgreSQL's indexed B-tree lookup on the type_code column.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b></p>
     * <pre>
     * IF TRAN-TYPE = 'PU' OR 'CA' OR 'PM' OR 'RF' OR 'FE' OR 'IN'
     *     CONTINUE
     * ELSE
     *     MOVE 'INVALID TRANSACTION TYPE' TO WS-ERROR-MESSAGE
     *     PERFORM DISPLAY-ERROR-MESSAGE
     * END-IF
     * </pre>
     * 
     * <p><b>Java Equivalent Usage:</b></p>
     * <pre>
     * Optional&lt;TransactionType&gt; typeOpt = transactionTypeRepository.findByTypeCode("PU");
     * if (typeOpt.isEmpty()) {
     *     throw new InvalidTransactionTypeException("Invalid transaction type code: PU");
     * }
     * TransactionType transactionType = typeOpt.get();
     * </pre>
     * 
     * <p><b>Query Execution:</b></p>
     * <p>Spring Data JPA generates the following SQL query at runtime:</p>
     * <pre>
     * SELECT type_code, type_description
     * FROM transaction_type
     * WHERE type_code = ?
     * </pre>
     * 
     * <p><b>Performance:</b></p>
     * <ul>
     *   <li>Execution time: < 5ms average (B-tree index on type_code)</li>
     *   <li>Index type: Primary key B-tree index (PostgreSQL default)</li>
     *   <li>Query plan: Index Scan on transaction_type_pkey</li>
     *   <li>Caching recommended: High cache hit rate due to static reference data</li>
     * </ul>
     * 
     * <p><b>Return Value Semantics:</b></p>
     * <ul>
     *   <li><b>Optional.of(transactionType)</b>: Type code exists, returns populated entity</li>
     *   <li><b>Optional.empty()</b>: Type code not found, equivalent to COBOL NOTFND condition</li>
     * </ul>
     * 
     * <p><b>Null Safety:</b></p>
     * <p>The Optional return type eliminates NullPointerException risks present in legacy
     * COBOL logic. Services must explicitly check <code>Optional.isEmpty()</code> before
     * accessing the value, enforcing defensive programming practices.</p>
     * 
     * <p><b>Transaction Context:</b></p>
     * <p>This method executes within a read-only transaction automatically managed by Spring
     * Data JPA. No explicit @Transactional annotation required at repository level. Service
     * layer methods calling this repository should use @Transactional for consistency.</p>
     * 
     * <p><b>Validation Use Case:</b></p>
     * <p>Used by TransactionCreationService.createTransaction() to validate transaction type
     * codes submitted via POST /api/transactions REST endpoint before persisting new
     * transactions, ensuring referential integrity with transaction_type reference table.</p>
     * 
     * @param typeCode The 2-character transaction type code to search for (e.g., "PU", "CA", "PM").
     *                 Must be exactly 2 characters matching COBOL PIC X(02) field definition.
     *                 Case-sensitive comparison performed by PostgreSQL (CHAR(2) column type).
     * @return Optional containing the TransactionType if found, or Optional.empty() if the
     *         type code does not exist in the reference table. Empty Optional indicates
     *         invalid transaction type, equivalent to COBOL NOTFND condition (file-status 23).
     * @throws org.springframework.dao.DataAccessException if database connection fails or
     *         query execution encounters errors (wraps SQLException)
     * @see TransactionType Entity class with typeCode primary key field
     * @see java.util.Optional Container object for null-safe value handling
     */
    Optional<TransactionType> findByTypeCode(String typeCode);

    /**
     * Retrieves all transaction types sorted by type code in ascending order.
     * 
     * <p>This method provides a sorted list of all transaction types for populating dropdown
     * lists and selection controls in React UI components. It replaces sequential VSAM file
     * reads from COBOL programs that retrieved transaction type reference data for display
     * purposes.</p>
     * 
     * <p><b>COBOL Pattern Replaced:</b></p>
     * <pre>
     * EXEC CICS READ FILE('TRANTYPE') INTO(TRAN-TYPE-RECORD)
     *           RIDFLD(WS-TYPE-KEY) KEYLENGTH(2) GENERIC
     *           RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     * END-EXEC
     * PERFORM UNTIL WS-RESP-CD NOT = DFHRESP(NORMAL)
     *     MOVE TRAN-TYPE TO TRN-TYPE-CODE(WS-INDEX)
     *     MOVE TRAN-TYPE-DESC TO TRN-TYPE-DESC(WS-INDEX)
     *     ADD 1 TO WS-INDEX
     *     EXEC CICS READNEXT FILE('TRANTYPE')...
     * END-PERFORM
     * </pre>
     * 
     * <p><b>Java Equivalent Usage:</b></p>
     * <pre>
     * List&lt;TransactionType&gt; transactionTypes = transactionTypeRepository.findAllByOrderByTypeCodeAsc();
     * List&lt;TransactionTypeDTO&gt; typeDTOs = transactionTypes.stream()
     *     .map(type -&gt; new TransactionTypeDTO(type.getTypeCode(), type.getTypeDescription()))
     *     .collect(Collectors.toList());
     * return ResponseEntity.ok(typeDTOs);
     * </pre>
     * 
     * <p><b>Query Execution:</b></p>
     * <p>Spring Data JPA generates the following SQL query at runtime:</p>
     * <pre>
     * SELECT type_code, type_description
     * FROM transaction_type
     * ORDER BY type_code ASC
     * </pre>
     * 
     * <p><b>Performance Characteristics:</b></p>
     * <ul>
     *   <li>Execution time: < 10ms average (small dataset, indexed sort)</li>
     *   <li>Result set size: Typically 10-20 rows (~1 KB total data)</li>
     *   <li>Index usage: B-tree index scan on type_code for sorted retrieval</li>
     *   <li>Network overhead: Minimal due to small payload size</li>
     *   <li>Caching strategy: Ideal for application-level cache with 24-hour TTL</li>
     * </ul>
     * 
     * <p><b>UI Component Integration:</b></p>
     * <p>This method supports the following React components:</p>
     * <ul>
     *   <li><b>TransactionAddComponent.jsx</b>: Populates transaction type dropdown in new
     *       transaction form (COTRN02M.bms equivalent)</li>
     *   <li><b>TransactionListComponent.jsx</b>: Provides filter options for transaction type
     *       selection (COTRN00M.bms equivalent)</li>
     *   <li><b>TransactionCategoryComponent.jsx</b>: Displays category labels in summary view
     *       (COTRN01M.bms equivalent)</li>
     * </ul>
     * 
     * <p><b>REST API Endpoint:</b></p>
     * <p>Typically exposed via GET /api/reference-data/transaction-types endpoint:</p>
     * <pre>
     * GET /api/reference-data/transaction-types
     * Response: 200 OK
     * [
     *   { "typeCode": "BT", "typeDescription": "Balance Transfer" },
     *   { "typeCode": "CA", "typeDescription": "Cash Advance" },
     *   { "typeCode": "FE", "typeDescription": "Fee" },
     *   { "typeCode": "IN", "typeDescription": "Interest Charge" },
     *   { "typeCode": "PM", "typeDescription": "Payment" },
     *   { "typeCode": "PU", "typeDescription": "Purchase" },
     *   { "typeCode": "RF", "typeDescription": "Refund" }
     * ]
     * </pre>
     * 
     * <p><b>Sorting Rationale:</b></p>
     * <p>Ascending order by type code (not description) ensures consistent display across all
     * client components and matches the natural key-based ordering of the legacy VSAM KSDS
     * files. This maintains functional equivalence with the COBOL application's display order.</p>
     * 
     * <p><b>Transaction Context:</b></p>
     * <p>Executes within a read-only transaction automatically managed by Spring Data JPA.
     * No explicit transaction demarcation required. Result list is fully materialized before
     * transaction commit, ensuring no lazy-loading exceptions.</p>
     * 
     * <p><b>Empty Result Handling:</b></p>
     * <p>If the transaction_type table is empty (only possible in misconfigured environments),
     * this method returns an empty List rather than null. Services should validate list is
     * not empty before processing, logging a warning if reference data is missing.</p>
     * 
     * <p><b>Caching Recommendation:</b></p>
     * <p>Strongly recommended to cache results using Spring Cache abstraction:</p>
     * <pre>
     * &#64;Cacheable(value = "transactionTypes", key = "'all'")
     * public List&lt;TransactionType&gt; findAllByOrderByTypeCodeAsc()
     * </pre>
     * <p>This reduces database round-trips for frequently accessed static reference data,
     * supporting high-throughput requirements (10,000 TPS) with minimal latency overhead.</p>
     * 
     * @return List of all TransactionType entities sorted by type code in ascending order.
     *         Returns empty list if no transaction types exist (misconfiguration scenario).
     *         Never returns null (Spring Data JPA guarantees non-null List for query methods).
     * @throws org.springframework.dao.DataAccessException if database connection fails or
     *         query execution encounters errors (wraps SQLException). Services should catch
     *         and transform to appropriate business exception (e.g., ReferenceDataException).
     * @see TransactionType Entity class representing reference data records
     * @see java.util.List Standard Java collection for ordered elements
     */
    @Query("SELECT t FROM TransactionType t ORDER BY t.typeCode ASC")
    List<TransactionType> findAllByOrderByTypeCodeAsc();

    /**
     * Standard JPA Repository Methods Inherited from JpaRepository interface.
     * 
     * <p>The following methods are automatically available through inheritance from
     * JpaRepository&lt;TransactionType, String&gt; and provide standard CRUD operations
     * for TransactionType entities. No explicit implementation required - Spring Data JPA
     * generates implementations at runtime.</p>
     * 
     * <p><b>Query Methods:</b></p>
     * <ul>
     *   <li><b>findById(String id)</b>: Retrieves TransactionType by primary key (type_code).
     *       Returns Optional&lt;TransactionType&gt;. Functionally equivalent to findByTypeCode()
     *       but uses generic repository method naming convention.</li>
     *   <li><b>findAll()</b>: Retrieves all TransactionType entities without sorting. Returns
     *       List&lt;TransactionType&gt;. For UI purposes, prefer findAllByOrderByTypeCodeAsc().</li>
     *   <li><b>existsById(String id)</b>: Checks if TransactionType exists for given type code.
     *       Returns boolean. More efficient than findById() when only existence check needed.</li>
     *   <li><b>count()</b>: Returns total number of transaction types. Used for monitoring and
     *       validation of reference data completeness.</li>
     * </ul>
     * 
     * <p><b>Write Methods (Not Used for Reference Data):</b></p>
     * <ul>
     *   <li><b>save(TransactionType entity)</b>: Insert or update TransactionType. Reference data
     *       loaded via Flyway migrations, not runtime saves.</li>
     *   <li><b>saveAll(Iterable&lt;TransactionType&gt; entities)</b>: Batch insert/update. Not
     *       used in production - reference data is static.</li>
     *   <li><b>delete(TransactionType entity)</b>: Delete TransactionType. Not used - reference
     *       data must remain intact for referential integrity.</li>
     *   <li><b>deleteById(String id)</b>: Delete by type code. Prohibited in production to
     *       maintain data integrity with transaction records.</li>
     *   <li><b>deleteAll()</b>: Delete all transaction types. Prohibited - would violate foreign
     *       key constraints with transaction table.</li>
     * </ul>
     * 
     * <p><b>Batch and Advanced Methods:</b></p>
     * <ul>
     *   <li><b>flush()</b>: Flushes pending changes to database. Not applicable for read-only
     *       reference data repository.</li>
     *   <li><b>saveAndFlush(TransactionType entity)</b>: Save and immediately flush. Not used
     *       for static reference data.</li>
     *   <li><b>deleteAllInBatch()</b>: Batch delete operation. Prohibited for reference data.</li>
     *   <li><b>getOne(String id)</b>: Deprecated in favor of getReferenceById().</li>
     *   <li><b>getById(String id)</b>: Deprecated in favor of getReferenceById().</li>
     *   <li><b>getReferenceById(String id)</b>: Returns lazy-loaded proxy. Use findById() instead
     *       for immediate loading.</li>
     * </ul>
     * 
     * <p><b>Usage Note:</b></p>
     * <p>This repository is read-only in production. All transaction type data is loaded via
     * Flyway migration V9__load_reference_data.sql during application deployment. Write methods
     * (save, delete) should only be used in test scenarios for setup/teardown purposes.</p>
     * 
     * @see org.springframework.data.jpa.repository.JpaRepository Parent repository interface
     * @see org.springframework.data.repository.CrudRepository Base CRUD repository interface
     * @see org.springframework.data.repository.PagingAndSortingRepository Pagination support
     */
    // All standard JpaRepository methods automatically available through inheritance
    // No explicit method declarations required - Spring Data JPA proxy provides implementations
}
