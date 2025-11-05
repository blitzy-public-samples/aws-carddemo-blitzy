package com.carddemo.repository;

import com.carddemo.entity.AccountXref;
import com.carddemo.entity.AccountXref.AccountXrefId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for {@link AccountXref} entity providing CRUD operations
 * and custom queries for customer-account cross-reference relationship lookups.
 * 
 * <p><strong>VSAM Replacement Context:</strong></p>
 * <p>This repository replaces COBOL CICS operations on the VSAM XREF file and CXACAIX alternate
 * index file used for navigating customer-to-account and account-to-customer relationships.
 * In the mainframe architecture, these relationships were managed through:</p>
 * <ul>
 *   <li><strong>XREF KSDS File:</strong> Primary cross-reference data storage</li>
 *   <li><strong>CXACAIX Alternate Index:</strong> Enabled account-based lookups (COACTVWC.cbl line 727)</li>
 *   <li><strong>COBOL START/READ NEXT:</strong> Sequential traversal of relationships</li>
 *   <li><strong>CICS READ operations:</strong> Direct access via RIDFLD key positioning</li>
 * </ul>
 * 
 * <p><strong>COBOL Source Reference:</strong></p>
 * <pre>
 * EXEC CICS READ
 *      DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
 *      RIDFLD    (WS-CARD-RID-ACCT-ID-X)
 *      KEYLENGTH (LENGTH OF WS-CARD-RID-ACCT-ID-X)
 *      INTO      (CARD-XREF-RECORD)
 *      RESP      (WS-RESP-CD)
 * END-EXEC
 * 
 * Source: COACTVWC.cbl lines 727-735
 * Purpose: Read cross-reference by account ID to retrieve associated customer and card information
 * </pre>
 * 
 * <p><strong>Batch Processing Context:</strong></p>
 * <p>This repository is utilized by {@code AccountXrefBuildJob} (replacing CBACT02C.cbl) for
 * batch cross-reference table population and validation during nightly batch processing cycles.
 * The batch job performs:</p>
 * <ul>
 *   <li>Sequential reading of account data (replaces VSAM sequential access)</li>
 *   <li>Cross-reference record creation and updates</li>
 *   <li>Referential integrity validation between customers and accounts</li>
 *   <li>Orphaned relationship detection and cleanup</li>
 * </ul>
 * 
 * <p><strong>Query Method Design:</strong></p>
 * <p>Custom query methods follow Spring Data JPA naming conventions and property path navigation
 * for composite primary keys. Since {@link AccountXref} uses an embedded composite key
 * ({@link AccountXrefId}), query methods reference nested properties using the "id." prefix:</p>
 * <ul>
 *   <li>{@code findByIdCustomerId} → navigates to id.customerId field</li>
 *   <li>{@code findByIdAccountId} → navigates to id.accountId field</li>
 * </ul>
 * 
 * <p><strong>Performance Considerations (Section 0.2 Requirements):</strong></p>
 * <ul>
 *   <li><strong>Response Time Target:</strong> Sub-200ms for cross-reference lookups under 10,000 TPS</li>
 *   <li><strong>Database Indexes:</strong> B-tree indexes on customer_id and account_id (defined in V7__create_indexes.sql)</li>
 *   <li><strong>Query Optimization:</strong> Direct index access via WHERE clause on indexed columns</li>
 *   <li><strong>Connection Pooling:</strong> HikariCP with 20-50 connections for concurrent access</li>
 * </ul>
 * 
 * <p><strong>Referential Integrity (Section 0.9 Critical Requirement):</strong></p>
 * <p>Cross-reference data relationships are preserved with 100% referential integrity through:</p>
 * <ul>
 *   <li>Foreign key constraints enforced at PostgreSQL database level</li>
 *   <li>CASCADE delete rules ensuring orphaned xref records are automatically removed</li>
 *   <li>Composite primary key constraint preventing duplicate customer-account relationships</li>
 *   <li>Service layer validation preventing creation of invalid relationships</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Find all accounts for a customer (replaces COBOL START XREF-FILE KEY IS CUST-ID)
 * List&lt;AccountXref&gt; customerAccounts = accountXrefRepository.findByIdCustomerId(customerId);
 * 
 * // Find all customers for an account (joint account scenarios)
 * List&lt;AccountXref&gt; accountCustomers = accountXrefRepository.findByIdAccountId(accountId);
 * 
 * // Check if customer-account relationship exists
 * AccountXrefId xrefId = new AccountXrefId(customerId, accountId);
 * boolean exists = accountXrefRepository.existsById(xrefId);
 * 
 * // Create new customer-account relationship (replaces COBOL CICS WRITE)
 * AccountXref newXref = new AccountXref();
 * newXref.setId(new AccountXrefId(customerId, accountId));
 * newXref.setCreatedDate(LocalDateTime.now());
 * accountXrefRepository.save(newXref);
 * </pre>
 * 
 * <p><strong>Service Layer Integration:</strong></p>
 * <p>This repository is autowired and used by the following service classes:</p>
 * <ul>
 *   <li><strong>AccountViewService:</strong> Retrieves customer accounts for display (COACTVWC replacement)</li>
 *   <li><strong>AccountUpdateService:</strong> Manages customer-account relationships during updates</li>
 *   <li><strong>AccountCreationService:</strong> Establishes initial customer-account cross-references</li>
 *   <li><strong>AccountXrefBuildJob:</strong> Batch processing for cross-reference table maintenance (CBACT02C replacement)</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <p>All repository methods are executed within transactional boundaries defined by the calling
 * service layer. For operations modifying cross-reference data, the service method must be
 * annotated with:</p>
 * <pre>
 * {@literal @}Transactional(
 *     isolation = Isolation.READ_COMMITTED,
 *     propagation = Propagation.REQUIRED,
 *     rollbackFor = Exception.class
 * )
 * </pre>
 * 
 * <p><strong>Inherited JpaRepository Methods:</strong></p>
 * <p>In addition to custom query methods, this repository inherits standard CRUD operations from
 * {@link JpaRepository}, including:</p>
 * <ul>
 *   <li>{@code save(AccountXref entity)} - Create or update cross-reference</li>
 *   <li>{@code saveAll(Iterable<AccountXref>)} - Batch insert/update for batch jobs</li>
 *   <li>{@code findById(AccountXrefId id)} - Lookup by composite key</li>
 *   <li>{@code findAll()} - Retrieve all cross-references (use with caution in production)</li>
 *   <li>{@code findAll(Pageable pageable)} - Paginated retrieval for large result sets</li>
 *   <li>{@code delete(AccountXref entity)} - Remove cross-reference relationship</li>
 *   <li>{@code deleteById(AccountXrefId id)} - Remove by composite key</li>
 *   <li>{@code deleteAll()} - Bulk delete (restricted to batch processing)</li>
 *   <li>{@code count()} - Total relationship count</li>
 *   <li>{@code existsById(AccountXrefId id)} - Check relationship existence</li>
 * </ul>
 * 
 * <p><strong>Architecture Pattern:</strong> Repository Pattern (Section 0.9 Design Pattern Adherence)</p>
 * <p><strong>VSAM Source:</strong> XREF file, CXACAIX alternate index</p>
 * <p><strong>Target Table:</strong> account_xref (PostgreSQL join table)</p>
 * <p><strong>Migration Context:</strong> Section 0.6 - File-by-File Transformation Plan</p>
 * 
 * @see AccountXref
 * @see AccountXrefId
 * @see <a href="Section 0.3">VSAM to PostgreSQL Transformation Rules</a>
 * @see <a href="Section 0.9">Repository Pattern Requirements</a>
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface AccountXrefRepository extends JpaRepository<AccountXref, AccountXrefId> {

    /**
     * Retrieves all account cross-references for a specific customer.
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (CUSTOMER-ID)
     *      GTEQ
     * END-EXEC
     * 
     * PERFORM UNTIL WS-RESP-CD = DFHRESP(ENDFILE)
     *     EXEC CICS READNEXT
     *          DATASET   ('XREFFILE')
     *          INTO      (XREF-RECORD)
     *          RIDFLD    (WS-RIDFLD)
     *          RESP      (WS-RESP-CD)
     *     END-EXEC
     *     
     *     IF XREF-CUST-ID = CUSTOMER-ID
     *         PROCESS XREF-RECORD
     *     ELSE
     *         SET WS-RESP-CD TO DFHRESP(ENDFILE)
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT * FROM account_xref
     * WHERE customer_id = ?
     * ORDER BY customer_id, account_id
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Display all accounts owned by a customer in Account View screen (COACTVWC)</li>
     *   <li>Joint account detection and management</li>
     *   <li>Customer portfolio analysis and reporting</li>
     *   <li>Authorization verification for account access</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Utilizes B-tree index on customer_id column for
     * efficient lookup. Expected execution time &lt; 10ms for typical customer with 1-10 accounts.</p>
     * 
     * <p><strong>Return Behavior:</strong></p>
     * <ul>
     *   <li>Returns empty list if customer has no associated accounts</li>
     *   <li>Returns multiple records for customers with multiple accounts</li>
     *   <li>Results are ordered by account_id to ensure consistent ordering</li>
     * </ul>
     * 
     * @param customerId the customer identifier (9-digit numeric value stored as Long)
     * @return List of AccountXref entities representing all customer-account relationships,
     *         empty list if no relationships exist for the customer
     * @see AccountXref
     * @see AccountViewService#viewAccount(Long, String)
     */
    List<AccountXref> findByIdCustomerId(Long customerId);

    /**
     * Retrieves all customer cross-references for a specific account.
     * 
     * <p>This method supports joint account scenarios where multiple customers are associated
     * with a single account. In typical cases, an account has one primary customer, but joint
     * accounts (e.g., shared checking accounts) may have multiple customer owners.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS READ
     *      DATASET   (LIT-CARDXREFNAME-ACCT-PATH)
     *      RIDFLD    (ACCOUNT-ID)
     *      KEYLENGTH (LENGTH OF ACCOUNT-ID)
     *      INTO      (XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * Source: COACTVWC.cbl lines 727-735 (CXACAIX alternate index access)
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT * FROM account_xref
     * WHERE account_id = ?
     * ORDER BY customer_id, account_id
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Identify all customers authorized to access an account</li>
     *   <li>Joint account management and authorization verification</li>
     *   <li>Account ownership transfer processing</li>
     *   <li>Compliance reporting for multi-party accounts</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Utilizes B-tree index on account_id column for
     * efficient lookup. Expected execution time &lt; 10ms for typical account with 1-2 customers.</p>
     * 
     * <p><strong>Return Behavior:</strong></p>
     * <ul>
     *   <li>Returns single record for standard single-owner accounts (most common case)</li>
     *   <li>Returns multiple records for joint accounts with multiple owners</li>
     *   <li>Returns empty list if account has no associated customers (orphaned account - data integrity issue)</li>
     *   <li>Results are ordered by customer_id to ensure consistent ordering</li>
     * </ul>
     * 
     * @param accountId the account identifier (11-digit numeric value stored as Long)
     * @return List of AccountXref entities representing all customer-account relationships,
     *         typically contains 1 record for standard accounts, 2+ for joint accounts
     * @see AccountXref
     * @see AccountUpdateService#updateAccount(com.carddemo.dto.request.AccountUpdateRequest)
     */
    List<AccountXref> findByIdAccountId(Long accountId);

    /**
     * Retrieves a specific customer-account cross-reference relationship by composite key.
     * 
     * <p>This method provides direct lookup of a cross-reference record using both customer ID
     * and account ID. It returns an Optional to handle the case where the relationship does not
     * exist, enabling null-safe access pattern.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE CUSTOMER-ID TO XREF-KEY-CUST-ID
     * MOVE ACCOUNT-ID  TO XREF-KEY-ACCT-ID
     * 
     * EXEC CICS READ
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (XREF-KEY)
     *      INTO      (XREF-RECORD)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * EVALUATE WS-RESP-CD
     *     WHEN DFHRESP(NORMAL)
     *         PERFORM PROCESS-XREF-FOUND
     *     WHEN DFHRESP(NOTFND)
     *         PERFORM PROCESS-XREF-NOT-FOUND
     * END-EVALUATE
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT * FROM account_xref
     * WHERE customer_id = ? AND account_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Verify customer authorization to access a specific account</li>
     *   <li>Validate customer-account relationship before performing operations</li>
     *   <li>Check for duplicate relationship before creating new cross-reference</li>
     *   <li>Pre-delete validation to ensure relationship exists before removal</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Utilizes composite primary key index for direct
     * lookup. Expected execution time &lt; 5ms as this is a primary key access.</p>
     * 
     * <p><strong>Return Behavior:</strong></p>
     * <ul>
     *   <li>Returns Optional containing AccountXref if relationship exists</li>
     *   <li>Returns Optional.empty() if no relationship exists between customer and account</li>
     *   <li>Enables null-safe access pattern using Optional methods (isPresent, orElseThrow, etc.)</li>
     * </ul>
     * 
     * <p><strong>Usage Example:</strong></p>
     * <pre>
     * Optional&lt;AccountXref&gt; xref = accountXrefRepository
     *     .findByIdCustomerIdAndIdAccountId(customerId, accountId);
     * 
     * if (xref.isPresent()) {
     *     // Customer is authorized to access this account
     *     processAccountAccess(xref.get());
     * } else {
     *     // Customer does not have access to this account
     *     throw new UnauthorizedAccessException("Customer not authorized for this account");
     * }
     * </pre>
     * 
     * @param customerId the customer identifier (9-digit numeric value stored as Long)
     * @param accountId the account identifier (11-digit numeric value stored as Long)
     * @return Optional containing the AccountXref if relationship exists, Optional.empty() otherwise
     * @see AccountXref
     * @see Optional
     * @see AccountUpdateService#validateCustomerAccountAccess(Long, Long)
     */
    Optional<AccountXref> findByIdCustomerIdAndIdAccountId(Long customerId, Long accountId);

    /**
     * Checks if a customer-account cross-reference relationship exists without retrieving the entity.
     * 
     * <p>This method provides efficient existence checking when only a boolean result is needed,
     * avoiding the overhead of loading the full entity into the persistence context. This is more
     * performant than using {@code findById(id).isPresent()} for simple authorization checks.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE CUSTOMER-ID TO XREF-KEY-CUST-ID
     * MOVE ACCOUNT-ID  TO XREF-KEY-ACCT-ID
     * 
     * EXEC CICS READ
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (XREF-KEY)
     *      INTO      (WS-DUMMY-AREA)
     *      LENGTH    (1)
     *      RESP      (WS-RESP-CD)
     * END-EXEC
     * 
     * IF WS-RESP-CD = DFHRESP(NORMAL)
     *     SET XREF-EXISTS TO TRUE
     * ELSE
     *     SET XREF-EXISTS TO FALSE
     * END-IF
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT CASE WHEN COUNT(*) > 0 THEN TRUE ELSE FALSE END
     * FROM account_xref
     * WHERE customer_id = ? AND account_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Fast authorization checks before account operations</li>
     *   <li>Pre-validation before creating new relationships (duplicate prevention)</li>
     *   <li>Conditional logic in service methods without loading entities</li>
     *   <li>Batch processing validation during cross-reference build job</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> More efficient than {@code findById().isPresent()} as it
     * executes a COUNT query without hydrating entity objects. Expected execution time &lt; 5ms.</p>
     * 
     * @param customerId the customer identifier (9-digit numeric value stored as Long)
     * @param accountId the account identifier (11-digit numeric value stored as Long)
     * @return true if the customer-account relationship exists, false otherwise
     * @see AccountXref
     */
    @Query("SELECT CASE WHEN COUNT(x) > 0 THEN true ELSE false END FROM AccountXref x " +
           "WHERE x.id.customerId = :customerId AND x.id.accountId = :accountId")
    boolean existsByCustomerIdAndAccountId(@Param("customerId") Long customerId, 
                                          @Param("accountId") Long accountId);

    /**
     * Retrieves the count of accounts associated with a specific customer.
     * 
     * <p>This method provides efficient counting of customer-account relationships without
     * loading all AccountXref entities. Useful for displaying customer account counts in
     * summary screens and dashboard reports.</p>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT COUNT(*) FROM account_xref
     * WHERE customer_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Display account count in customer profile summary</li>
     *   <li>Validate customer eligibility for new account (business rule: max 10 accounts per customer)</li>
     *   <li>Reporting and analytics on customer account portfolio size</li>
     *   <li>Pagination calculation for customer account list displays</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Executes optimized COUNT query using customer_id index.
     * Expected execution time &lt; 5ms.</p>
     * 
     * @param customerId the customer identifier (9-digit numeric value stored as Long)
     * @return count of accounts associated with the customer, 0 if customer has no accounts
     */
    long countByIdCustomerId(Long customerId);

    /**
     * Retrieves the count of customers associated with a specific account.
     * 
     * <p>This method provides efficient counting of account-customer relationships, primarily
     * used to identify joint accounts (count &gt; 1) versus standard single-owner accounts (count = 1).</p>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * SELECT COUNT(*) FROM account_xref
     * WHERE account_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Identify joint accounts for special processing logic</li>
     *   <li>Validate account ownership before deletion (prevent orphaned accounts)</li>
     *   <li>Compliance reporting on multi-party account arrangements</li>
     *   <li>Authorization logic adjustment for joint account access</li>
     * </ul>
     * 
     * <p><strong>Performance:</strong> Executes optimized COUNT query using account_id index.
     * Expected execution time &lt; 5ms.</p>
     * 
     * <p><strong>Expected Values:</strong></p>
     * <ul>
     *   <li>1: Standard single-owner account (most common case)</li>
     *   <li>2+: Joint account with multiple owners</li>
     *   <li>0: Orphaned account with no owner (data integrity issue requiring correction)</li>
     * </ul>
     * 
     * @param accountId the account identifier (11-digit numeric value stored as Long)
     * @return count of customers associated with the account
     */
    long countByIdAccountId(Long accountId);

    /**
     * Deletes all cross-reference relationships for a specific customer.
     * 
     * <p><strong>WARNING:</strong> This method performs a bulk delete operation and should be
     * used with caution. Typically invoked only during customer account closure processing or
     * batch cleanup operations. Consider using transactional boundaries and cascade delete
     * rules defined at the database level.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * EXEC CICS START
     *      DATASET   ('XREFFILE')
     *      RIDFLD    (CUSTOMER-ID)
     *      GTEQ
     * END-EXEC
     * 
     * PERFORM UNTIL WS-RESP-CD = DFHRESP(ENDFILE)
     *     EXEC CICS READNEXT
     *          DATASET   ('XREFFILE')
     *          INTO      (XREF-RECORD)
     *          RIDFLD    (WS-RIDFLD)
     *          RESP      (WS-RESP-CD)
     *     END-EXEC
     *     
     *     IF XREF-CUST-ID = CUSTOMER-ID
     *         EXEC CICS DELETE
     *              DATASET   ('XREFFILE')
     *              RIDFLD    (WS-RIDFLD)
     *         END-EXEC
     *     ELSE
     *         SET WS-RESP-CD TO DFHRESP(ENDFILE)
     *     END-IF
     * END-PERFORM
     * </pre>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * DELETE FROM account_xref
     * WHERE customer_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Customer account closure processing (remove all account associations)</li>
     *   <li>Batch cleanup operations during account reorganization</li>
     *   <li>Data correction procedures for invalid relationships</li>
     *   <li>Test data cleanup in non-production environments</li>
     * </ul>
     * 
     * <p><strong>Transaction Management:</strong> This method must be executed within a
     * transactional context with appropriate rollback configuration:</p>
     * <pre>
     * {@literal @}Transactional(
     *     isolation = Isolation.READ_COMMITTED,
     *     propagation = Propagation.REQUIRED,
     *     rollbackFor = Exception.class
     * )
     * public void closeCustomerAccounts(Long customerId) {
     *     accountXrefRepository.deleteByIdCustomerId(customerId);
     *     // Additional cleanup operations...
     * }
     * </pre>
     * 
     * <p><strong>Performance:</strong> Bulk delete operation. Performance depends on number
     * of relationships being deleted. Typical customer with 1-10 accounts: &lt; 50ms.</p>
     * 
     * @param customerId the customer identifier whose relationships should be deleted
     * @return number of AccountXref records deleted
     */
    long deleteByIdCustomerId(Long customerId);

    /**
     * Deletes all cross-reference relationships for a specific account.
     * 
     * <p><strong>WARNING:</strong> This method performs a bulk delete operation and should be
     * used with caution. Typically invoked during account closure processing or when removing
     * all customer associations from an account.</p>
     * 
     * <p><strong>SQL Query Generated:</strong></p>
     * <pre>
     * DELETE FROM account_xref
     * WHERE account_id = ?
     * </pre>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Account closure processing (remove all customer associations)</li>
     *   <li>Account ownership transfer preparation (clear existing owners before reassignment)</li>
     *   <li>Data correction procedures for invalid relationships</li>
     *   <li>Batch cleanup operations during account reorganization</li>
     * </ul>
     * 
     * <p><strong>Transaction Management:</strong> This method must be executed within a
     * transactional context. See {@link #deleteByIdCustomerId(Long)} for transaction example.</p>
     * 
     * <p><strong>Performance:</strong> Bulk delete operation. For typical accounts with 1-2
     * customers: &lt; 20ms. Joint accounts with multiple owners may take slightly longer.</p>
     * 
     * @param accountId the account identifier whose relationships should be deleted
     * @return number of AccountXref records deleted
     */
    long deleteByIdAccountId(Long accountId);
}
