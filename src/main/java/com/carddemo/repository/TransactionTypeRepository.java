package com.carddemo.repository;

import com.carddemo.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository interface for TransactionType entity.
 * 
 * <p>This repository provides CRUD operations and custom query methods for accessing
 * transaction type reference data in the PostgreSQL database. It replaces COBOL
 * EXEC CICS READ operations on the VSAM transaction type file defined in the
 * CVTRA03Y.cpy copybook.</p>
 * 
 * <p><b>COBOL Source Mapping:</b></p>
 * <pre>
 * COBOL Copybook: app/cpy/CVTRA03Y.cpy
 * VSAM File: Transaction Type Reference File
 * Record Structure: TRAN-TYPE-RECORD (60 bytes)
 *   - TRAN-TYPE (PIC X(02)) → typeCode (String, PRIMARY KEY)
 *   - TRAN-TYPE-DESC (PIC X(50)) → typeDescription (String)
 * </pre>
 * 
 * <p><b>Migration Context:</b></p>
 * <ul>
 *   <li>Replaces COBOL EXEC CICS READ operations with Spring Data JPA methods</li>
 *   <li>Provides type-safe repository access replacing file I/O operations</li>
 *   <li>Supports VSAM-equivalent indexed access through PostgreSQL indexes</li>
 *   <li>Enables declarative transaction management with @Transactional</li>
 *   <li>Automatic query derivation from method names per Spring Data conventions</li>
 * </ul>
 * 
 * <p><b>COBOL to Spring Data JPA Mapping:</b></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Operation</th>
 *     <th>Spring Data JPA Method</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READ ... INTO(TRAN-TYPE-RECORD) RIDFLD(TRAN-TYPE)</td>
 *     <td>findById(String typeCode)</td>
 *     <td>Retrieve transaction type by primary key</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS READ ... NOTFND</td>
 *     <td>findByTypeCode(String typeCode)</td>
 *     <td>Optional return handles not found condition</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS WRITE</td>
 *     <td>save(TransactionType entity)</td>
 *     <td>Insert new transaction type</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS REWRITE</td>
 *     <td>save(TransactionType entity)</td>
 *     <td>Update existing transaction type</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS DELETE</td>
 *     <td>deleteById(String typeCode)</td>
 *     <td>Delete transaction type by primary key</td>
 *   </tr>
 *   <tr>
 *     <td>EXEC CICS STARTBR / READNEXT</td>
 *     <td>findAll()</td>
 *     <td>Retrieve all transaction types</td>
 *   </tr>
 * </table>
 * 
 * <p><b>Usage Example:</b></p>
 * <pre>
 * &#64;Service
 * public class TransactionService {
 *     &#64;Autowired
 *     private TransactionTypeRepository transactionTypeRepository;
 *     
 *     public TransactionType getTransactionType(String typeCode) {
 *         return transactionTypeRepository.findByTypeCode(typeCode)
 *             .orElseThrow(() -> new ResourceNotFoundException(
 *                 "Transaction type not found: " + typeCode));
 *     }
 *     
 *     public List&lt;TransactionType&gt; getAllTransactionTypes() {
 *         return transactionTypeRepository.findAll();
 *     }
 * }
 * </pre>
 * 
 * <p><b>Data Integrity:</b></p>
 * <ul>
 *   <li>Primary key constraint enforced on typeCode (2-character String)</li>
 *   <li>Optimistic locking with @Version prevents concurrent update conflicts</li>
 *   <li>Foreign key relationships maintained with Transaction entity</li>
 *   <li>NOT NULL constraints enforced on all required fields</li>
 * </ul>
 * 
 * <p><b>Performance Considerations:</b></p>
 * <ul>
 *   <li>Primary key index on typeCode provides O(log n) lookup performance</li>
 *   <li>Reference data suitable for second-level caching (rarely changes)</li>
 *   <li>Small dataset (typically 5-20 transaction types) allows full table scan</li>
 *   <li>Connection pooling managed by HikariCP through Spring Boot</li>
 * </ul>
 * 
 * <p><b>Transaction Management:</b></p>
 * <ul>
 *   <li>All repository methods participate in Spring-managed transactions</li>
 *   <li>Read operations use READ_COMMITTED isolation level by default</li>
 *   <li>Write operations are atomic with automatic rollback on exception</li>
 *   <li>Service layer methods should be annotated with @Transactional</li>
 * </ul>
 * 
 * <p><b>Error Handling:</b></p>
 * <ul>
 *   <li>findById/findByTypeCode: Returns Optional.empty() if not found (no exception)</li>
 *   <li>save: Throws DataIntegrityViolationException on constraint violations</li>
 *   <li>deleteById: No exception if entity doesn't exist (idempotent)</li>
 *   <li>All operations throw DataAccessException on database errors</li>
 * </ul>
 * 
 * <p><b>Thread Safety:</b></p>
 * <p>This repository interface is thread-safe. Spring creates a thread-safe proxy
 * that delegates to the underlying JPA EntityManager, which uses connection pooling
 * to handle concurrent requests safely.</p>
 * 
 * <p><b>Testing Considerations:</b></p>
 * <ul>
 *   <li>Use @DataJpaTest for repository integration tests</li>
 *   <li>H2 in-memory database suitable for testing reference data operations</li>
 *   <li>Test findByTypeCode with valid/invalid type codes</li>
 *   <li>Verify Optional.empty() handling for not found scenarios</li>
 * </ul>
 * 
 * @see com.carddemo.entity.TransactionType
 * @see org.springframework.data.jpa.repository.JpaRepository
 * @see org.springframework.stereotype.Repository
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface TransactionTypeRepository extends JpaRepository<TransactionType, String> {

    /**
     * Find a transaction type by its type code.
     * 
     * <p>This method provides a custom query derived from the method name using
     * Spring Data JPA query derivation. It searches for a transaction type with
     * the exact type code match, providing Optional return type for null-safe
     * handling of not found conditions.</p>
     * 
     * <p><b>COBOL Equivalent:</b></p>
     * <pre>
     * EXEC CICS READ
     *     FILE('TRANTYPE')
     *     INTO(TRAN-TYPE-RECORD)
     *     RIDFLD(TRAN-TYPE)
     *     RESP(WS-CICS-RESP)
     * END-EXEC.
     * 
     * IF WS-CICS-RESP = DFHRESP(NOTFND)
     *     MOVE 'N' TO TRAN-TYPE-FOUND-FLAG
     * END-IF.
     * </pre>
     * 
     * <p><b>SQL Query Generated:</b></p>
     * <pre>
     * SELECT tt.* FROM transaction_type tt WHERE tt.type_code = ?1
     * </pre>
     * 
     * <p><b>Method Name Query Derivation:</b></p>
     * <ul>
     *   <li>findBy: Standard Spring Data query prefix</li>
     *   <li>TypeCode: Property name in TransactionType entity (case-sensitive)</li>
     *   <li>Spring Data JPA automatically generates the WHERE clause</li>
     *   <li>Uses primary key index for optimal performance</li>
     * </ul>
     * 
     * <p><b>Usage Example:</b></p>
     * <pre>
     * // Lookup transaction type with code "01" (Purchase)
     * Optional&lt;TransactionType&gt; transactionType = 
     *     transactionTypeRepository.findByTypeCode("01");
     * 
     * if (transactionType.isPresent()) {
     *     String description = transactionType.get().getTypeDescription();
     *     // Process transaction type
     * } else {
     *     // Handle transaction type not found
     *     throw new ResourceNotFoundException("Invalid transaction type: 01");
     * }
     * 
     * // Using Optional methods for concise handling
     * TransactionType type = transactionTypeRepository.findByTypeCode("02")
     *     .orElseThrow(() -> new ResourceNotFoundException("Transaction type not found: 02"));
     * </pre>
     * 
     * <p><b>Parameter Validation:</b></p>
     * <ul>
     *   <li>typeCode: Must be exactly 2 characters (PIC X(02) from COBOL)</li>
     *   <li>null parameter returns Optional.empty()</li>
     *   <li>Empty string ("") returns Optional.empty()</li>
     *   <li>Case-sensitive comparison (e.g., "01" != "01" with trailing spaces)</li>
     * </ul>
     * 
     * <p><b>Return Value Scenarios:</b></p>
     * <ul>
     *   <li>Optional.of(TransactionType): Transaction type found with matching typeCode</li>
     *   <li>Optional.empty(): No transaction type found with given typeCode</li>
     *   <li>Never returns null (Optional wrapper prevents NullPointerException)</li>
     * </ul>
     * 
     * <p><b>Performance Characteristics:</b></p>
     * <ul>
     *   <li>Executes single SELECT query with primary key index lookup</li>
     *   <li>O(log n) time complexity with B-tree index on typeCode</li>
     *   <li>Typically sub-millisecond response time for reference data</li>
     *   <li>Result suitable for second-level caching (rarely changes)</li>
     * </ul>
     * 
     * <p><b>Transaction Behavior:</b></p>
     * <ul>
     *   <li>Participates in active transaction if present</li>
     *   <li>Uses READ_COMMITTED isolation level by default</li>
     *   <li>No locks acquired for read-only operation</li>
     *   <li>Result reflects committed data at query execution time</li>
     * </ul>
     * 
     * <p><b>Caching Considerations:</b></p>
     * <ul>
     *   <li>Eligible for JPA first-level cache (EntityManager session)</li>
     *   <li>Eligible for second-level cache if enabled (e.g., EhCache, Redis)</li>
     *   <li>Query result caching possible with @QueryHints(cacheable=true)</li>
     *   <li>Reference data suitable for aggressive caching strategies</li>
     * </ul>
     * 
     * <p><b>Error Conditions:</b></p>
     * <ul>
     *   <li>DataAccessException: Database connection or query execution failure</li>
     *   <li>No exception thrown if transaction type not found (returns Optional.empty())</li>
     *   <li>InvalidDataAccessApiUsageException: Invalid parameter type</li>
     * </ul>
     * 
     * <p><b>Thread Safety:</b></p>
     * <p>This method is thread-safe. Multiple concurrent invocations with the same or
     * different type codes will execute independently without race conditions.</p>
     * 
     * <p><b>Best Practices:</b></p>
     * <ul>
     *   <li>Always use Optional methods (isPresent, orElse, orElseThrow) to handle result</li>
     *   <li>Cache transaction type lookups in service layer if accessed frequently</li>
     *   <li>Validate typeCode format (2 characters) before calling this method</li>
     *   <li>Use findById(typeCode) if you need direct exception on not found</li>
     * </ul>
     * 
     * <p><b>Alternative Methods:</b></p>
     * <ul>
     *   <li>findById(String typeCode): Returns Optional, same as this method for primary key</li>
     *   <li>getById(String typeCode): Returns proxy, throws exception on access if not found</li>
     *   <li>getReferenceById(String typeCode): Returns lazy proxy, preferred for FK associations</li>
     * </ul>
     * 
     * <p><b>Related Entity Operations:</b></p>
     * <p>Transaction entities use typeCode as a foreign key. Use this method to validate
     * transaction type codes before creating Transaction records to ensure referential integrity.</p>
     * 
     * @param typeCode the transaction type code to search for (PIC X(02), 2 characters)
     * @return Optional containing the TransactionType if found, or Optional.empty() if not found
     * @throws DataAccessException if database access error occurs
     * @see #findById(String)
     * @see com.carddemo.entity.TransactionType#getTypeCode()
     * @see java.util.Optional
     */
    Optional<TransactionType> findByTypeCode(String typeCode);
}
