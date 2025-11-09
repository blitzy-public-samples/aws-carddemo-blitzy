package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroup.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository interface for DisclosureGroup entity.
 * 
 * Provides CRUD operations and custom query methods for disclosure group reference data.
 * Replaces COBOL EXEC CICS READ/WRITE/REWRITE/DELETE operations on VSAM disclosure
 * group reference data file defined in CVTRA02Y.cpy (DIS-GROUP-RECORD).
 * 
 * Disclosure groups define interest rates for different combinations of account groups,
 * transaction types, and transaction categories. This reference data is used by the
 * interest calculation batch job (InterestCalculationJob.java, transformed from CBACT04C.cbl)
 * to determine applicable interest rates for account transactions.
 * 
 * <h2>VSAM to PostgreSQL Transformation</h2>
 * <pre>
 * COBOL VSAM Operation          Spring Data JPA Equivalent
 * ---------------------          --------------------------
 * EXEC CICS READ                 findById(disclosureGroupId)
 * EXEC CICS WRITE                save(disclosureGroup) - insert
 * EXEC CICS REWRITE              save(disclosureGroup) - update
 * EXEC CICS DELETE               deleteById(disclosureGroupId) or delete(disclosureGroup)
 * EXEC CICS STARTBR/READNEXT     findAll() or custom query methods with pagination
 * </pre>
 * 
 * <h2>Original COBOL Structure</h2>
 * <pre>
 * From CVTRA02Y.cpy:
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID       PIC X(10).     (Account Group ID)
 *        10 DIS-TRAN-TYPE-CD        PIC X(02).     (Transaction Type Code)
 *        10 DIS-TRAN-CAT-CD         PIC 9(04).     (Transaction Category Code)
 *     05  DIS-INT-RATE              PIC S9(04)V99. (Interest Rate)
 * </pre>
 * 
 * <h2>Composite Primary Key</h2>
 * This repository uses a composite primary key (DisclosureGroupId) consisting of three fields:
 * <ul>
 *   <li>Account Group ID - 10-character string identifying account group type</li>
 *   <li>Transaction Type Code - 2-character code for transaction type</li>
 *   <li>Transaction Category Code - 4-character numeric code for transaction category</li>
 * </ul>
 * 
 * <h2>Transaction Management</h2>
 * All repository operations integrate with Spring's transaction management:
 * <ul>
 *   <li>Read operations (find*) execute within transaction boundaries but don't require write locks</li>
 *   <li>Write operations (save, delete) must be called within @Transactional service methods</li>
 *   <li>Transaction commits occur automatically at @Transactional method completion</li>
 *   <li>Exceptions trigger automatic rollback, preserving CICS SYNCPOINT/ROLLBACK behavior</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * {@literal @}Service
 * {@literal @}Transactional
 * public class InterestCalculationService {
 *     
 *     {@literal @}Autowired
 *     private DisclosureGroupRepository disclosureGroupRepository;
 *     
 *     public BigDecimal getInterestRate(String accountGroupId, 
 *                                       String transactionTypeCode,
 *                                       String transactionCategoryCode) {
 *         DisclosureGroupId id = DisclosureGroupId.builder()
 *             .accountGroupId(accountGroupId)
 *             .transactionTypeCode(transactionTypeCode)
 *             .transactionCategoryCode(transactionCategoryCode)
 *             .build();
 *         
 *         return disclosureGroupRepository.findById(id)
 *             .map(DisclosureGroup::getInterestRate)
 *             .orElseThrow(() -> new ResourceNotFoundException(
 *                 "Disclosure group not found for: " + id));
 *     }
 * }
 * </pre>
 * 
 * <h2>Custom Query Methods</h2>
 * This repository provides additional query methods beyond basic CRUD:
 * <ul>
 *   <li>findByIdAccountGroupId - Find all disclosure groups for a specific account group</li>
 *   <li>findByIdTransactionTypeCode - Find all disclosure groups for a transaction type</li>
 *   <li>findByIdTransactionCategoryCode - Find all disclosure groups for a category</li>
 * </ul>
 * 
 * These methods enable flexible querying when only partial key information is available,
 * supporting business logic that needs to retrieve all interest rates for a specific
 * dimension of the composite key.
 * 
 * <h2>Indexing Strategy</h2>
 * PostgreSQL indexes are created to match VSAM KSDS key access patterns:
 * <ul>
 *   <li>Primary key index on (account_group_id, transaction_type_code, transaction_category_code)</li>
 *   <li>Individual indexes on each key component for partial key lookups</li>
 * </ul>
 * 
 * Database migration script: V6__create_reference_tables.sql
 * 
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see com.carddemo.batch.job.InterestCalculationJob
 * @see com.carddemo.service.transaction.TransactionAddService
 * 
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Find all disclosure groups for a specific account group ID.
     * 
     * This method queries disclosure groups by the first component of the composite key,
     * retrieving all interest rate configurations applicable to a particular account group
     * (e.g., "STANDARD", "PREMIUM", "PLATINUM").
     * 
     * Useful for:
     * <ul>
     *   <li>Loading all applicable interest rates for an account group</li>
     *   <li>Validating interest rate configurations during batch processing</li>
     *   <li>Administrative functions that manage disclosure group data</li>
     * </ul>
     * 
     * Spring Data JPA automatically implements this method using the query:
     * <pre>
     * SELECT dg FROM DisclosureGroup dg 
     * WHERE dg.id.accountGroupId = :accountGroupId
     * </pre>
     * 
     * @param accountGroupId the account group identifier (10 characters max, from DIS-ACCT-GROUP-ID)
     * @return list of disclosure groups matching the account group ID; empty list if none found
     * @throws IllegalArgumentException if accountGroupId is null
     */
    List<DisclosureGroup> findByIdAccountGroupId(String accountGroupId);

    /**
     * Find all disclosure groups for a specific transaction type code.
     * 
     * This method queries disclosure groups by the second component of the composite key,
     * retrieving all interest rate configurations for a particular transaction type
     * (e.g., "PU" for Purchase, "CA" for Cash Advance, "BT" for Balance Transfer).
     * 
     * Useful for:
     * <ul>
     *   <li>Analyzing interest rate configurations by transaction type</li>
     *   <li>Validating transaction type interest rate coverage</li>
     *   <li>Administrative reporting on interest rate structures</li>
     * </ul>
     * 
     * Spring Data JPA automatically implements this method using the query:
     * <pre>
     * SELECT dg FROM DisclosureGroup dg 
     * WHERE dg.id.transactionTypeCode = :transactionTypeCode
     * </pre>
     * 
     * @param transactionTypeCode the transaction type code (2 characters max, from DIS-TRAN-TYPE-CD)
     * @return list of disclosure groups matching the transaction type code; empty list if none found
     * @throws IllegalArgumentException if transactionTypeCode is null
     */
    List<DisclosureGroup> findByIdTransactionTypeCode(String transactionTypeCode);

    /**
     * Find all disclosure groups for a specific transaction category code.
     * 
     * This method queries disclosure groups by the third component of the composite key,
     * retrieving all interest rate configurations for a particular transaction category
     * (e.g., "0001" for Retail, "0002" for Dining, "0003" for Travel).
     * 
     * Useful for:
     * <ul>
     *   <li>Analyzing interest rate configurations by transaction category</li>
     *   <li>Validating category interest rate coverage across account groups and transaction types</li>
     *   <li>Category-based interest rate reporting</li>
     * </ul>
     * 
     * Spring Data JPA automatically implements this method using the query:
     * <pre>
     * SELECT dg FROM DisclosureGroup dg 
     * WHERE dg.id.transactionCategoryCode = :transactionCategoryCode
     * </pre>
     * 
     * Note: The transaction category code is stored as a 4-character string to preserve
     * leading zeros from the COBOL PIC 9(04) definition. For example, category code 1
     * is stored as "0001", not "1".
     * 
     * @param transactionCategoryCode the transaction category code (4-digit numeric string, from DIS-TRAN-CAT-CD)
     * @return list of disclosure groups matching the transaction category code; empty list if none found
     * @throws IllegalArgumentException if transactionCategoryCode is null
     */
    List<DisclosureGroup> findByIdTransactionCategoryCode(String transactionCategoryCode);

    // Note: The following methods are inherited from JpaRepository<DisclosureGroup, DisclosureGroupId>
    // and do not need explicit declaration. They are documented here for completeness:
    //
    // - Optional<DisclosureGroup> findById(DisclosureGroupId id)
    //   Retrieves a disclosure group by its composite primary key.
    //   Returns Optional.empty() if not found (equivalent to COBOL NOTFND condition).
    //
    // - <S extends DisclosureGroup> S save(S entity)
    //   Inserts a new disclosure group or updates an existing one.
    //   Replaces both EXEC CICS WRITE (insert) and EXEC CICS REWRITE (update).
    //   Version field automatically increments on update for optimistic locking.
    //
    // - <S extends DisclosureGroup> List<S> saveAll(Iterable<S> entities)
    //   Batch insert or update multiple disclosure groups in a single transaction.
    //   More efficient than multiple individual save() calls.
    //
    // - void deleteById(DisclosureGroupId id)
    //   Deletes a disclosure group by its composite primary key.
    //   Equivalent to EXEC CICS DELETE.
    //   Throws EmptyResultDataAccessException if entity doesn't exist.
    //
    // - void delete(DisclosureGroup entity)
    //   Deletes the given disclosure group entity.
    //   Uses the entity's id for deletion. Version checking occurs if optimistic locking is enabled.
    //
    // - List<DisclosureGroup> findAll()
    //   Retrieves all disclosure groups from the database.
    //   Equivalent to EXEC CICS STARTBR followed by READNEXT until EOF.
    //   Use with caution if table contains large number of records; prefer paginated queries.
    //
    // - boolean existsById(DisclosureGroupId id)
    //   Checks if a disclosure group exists with the given composite primary key.
    //   More efficient than findById() when only existence check is needed.
    //
    // - long count()
    //   Returns the total number of disclosure group records.
    //   Useful for administrative reporting and data validation.
    //
    // Additional inherited methods from JpaRepository that may be useful:
    //
    // - Page<DisclosureGroup> findAll(Pageable pageable)
    //   Retrieves disclosure groups with pagination support.
    //   Recommended for large result sets to avoid memory issues.
    //
    // - List<DisclosureGroup> findAllById(Iterable<DisclosureGroupId> ids)
    //   Retrieves multiple disclosure groups by their composite keys in a single query.
    //
    // - void flush()
    //   Flushes pending changes to the database immediately.
    //   Useful for forcing synchronization with database within a transaction.
}
