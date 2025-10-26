package com.carddemo.repository;

import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.entity.DisclosureGroupId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Converted from VSAM DISCGRP file I/O operations in COBOL programs.
 * Original function: Repository for disclosure group reference data with interest rates.
 * Original VSAM file: AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
 * Record layout copybook: CVTRA02Y.cpy (DIS-GROUP-RECORD)
 * Record length: 50 bytes
 * 
 * Spring Data JPA repository interface providing database access for DisclosureGroup entities.
 * Replaces all COBOL EXEC CICS FILE('DISCGRP') operations with JPA repository methods.
 * 
 * The disclosure group table stores interest rate configuration data per account group,
 * transaction type, and transaction category combination. This is a reference table used
 * by billing and interest calculation processes.
 * 
 * VSAM to PostgreSQL Transformation:
 * - VSAM KSDS (Key-Sequenced Data Set) → PostgreSQL table 'disclosure_group'
 * - VSAM composite key (16 bytes: 10 + 2 + 4) → PostgreSQL composite primary key
 * - VSAM KEYS(16 0) definition → B-tree indexes automatically created by PostgreSQL
 * - VSAM sequential/random access → JPA findById/findAll methods with query derivation
 * 
 * Conversion notes from Section 0.4.23:
 * - COBOL: EXEC CICS READ FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY) INTO(DIS-GROUP-RECORD) END-EXEC
 *   Java:  Optional&lt;DisclosureGroup&gt; group = disclosureGroupRepository.findById(disclosureGroupId);
 * 
 * - COBOL: EXEC CICS WRITE FILE('DISCGRP') FROM(DIS-GROUP-RECORD) RIDFLD(DIS-GROUP-KEY) END-EXEC
 *   Java:  disclosureGroupRepository.save(disclosureGroup);
 * 
 * - COBOL: EXEC CICS REWRITE FILE('DISCGRP') FROM(DIS-GROUP-RECORD) END-EXEC
 *   Java:  disclosureGroupRepository.save(disclosureGroup);  // Same method for update
 * 
 * - COBOL: EXEC CICS DELETE FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY) END-EXEC
 *   Java:  disclosureGroupRepository.deleteById(disclosureGroupId);
 * 
 * Primary Key Structure (Composite):
 * - discAcctGroupId: Account group identifier (PIC X(10), 10 characters)
 * - discTranTypeCd: Transaction type code (PIC X(02), 2 characters)
 * - discTranCatCd: Transaction category code (PIC 9(04), 0-9999 integer)
 * 
 * Database table: disclosure_group
 * Primary key: (disc_acct_group_id, disc_tran_type_cd, disc_tran_cat_cd)
 * Indexes: Primary key B-tree index replicating VSAM KSDS key access pattern
 * 
 * Performance requirements (Section 0.7.7):
 * - Database queries MUST meet or exceed VSAM key access response times (sub-10ms for primary key lookups)
 * - PostgreSQL B-tree indexes provide equivalent or better performance than VSAM KSDS
 * 
 * Transaction management (Section 0.7.2):
 * - All repository operations participate in Spring @Transactional boundaries
 * - Optimistic locking via @Version field prevents concurrent update conflicts
 * - Replicates COBOL EXEC CICS SYNCPOINT transaction boundary semantics
 * 
 * @see DisclosureGroup JPA entity with composite key and interest rate data
 * @see com.carddemo.model.entity.DisclosureGroupId Composite primary key class
 */
@Repository
public interface DisclosureGroupRepository extends JpaRepository<DisclosureGroup, DisclosureGroupId> {

    /**
     * Find all disclosure group records for a specific account group.
     * 
     * Replaces COBOL sequential file browsing pattern where programs read DISCGRP file
     * filtering by account group ID. Enables retrieval of all interest rate configurations
     * applicable to a specific account group across all transaction types and categories.
     * 
     * Use case: Billing and interest calculation processes need to retrieve all applicable
     * interest rates for an account group to apply correct rates based on transaction
     * type and category.
     * 
     * Query derivation: Spring Data JPA automatically generates query:
     * SELECT * FROM disclosure_group WHERE disc_acct_group_id = ?1
     * ORDER BY disc_tran_type_cd, disc_tran_cat_cd
     * 
     * Performance: Uses B-tree index on disc_acct_group_id for efficient lookup.
     * Expected response time: sub-10ms per Section 0.7.7 requirements.
     * 
     * @param groupId Account group identifier (PIC X(10), max 10 characters)
     *                Maps to COBOL: DIS-ACCT-GROUP-ID
     * @return List of all disclosure group records matching the account group ID.
     *         Returns empty list if no records found for the specified group.
     *         List is ordered by transaction type code and category code for
     *         deterministic processing matching COBOL sequential read behavior.
     */
    List<DisclosureGroup> findByDiscAcctGroupId(String groupId);

    /**
     * Find all disclosure group records for a specific transaction type.
     * 
     * Replaces COBOL sequential file browsing pattern where programs read DISCGRP file
     * filtering by transaction type code. Enables retrieval of all interest rate
     * configurations applicable to a specific transaction type across all account
     * groups and categories.
     * 
     * Use case: Transaction processing and reporting systems need to retrieve all
     * interest rate settings for a specific transaction type to analyze rate structures
     * or apply type-specific billing rules.
     * 
     * Query derivation: Spring Data JPA automatically generates query:
     * SELECT * FROM disclosure_group WHERE disc_tran_type_cd = ?1
     * ORDER BY disc_acct_group_id, disc_tran_cat_cd
     * 
     * Performance: Uses B-tree index on disc_tran_type_cd for efficient lookup.
     * Expected response time: sub-10ms per Section 0.7.7 requirements.
     * 
     * @param typeCode Transaction type code (PIC X(02), max 2 characters)
     *                 Maps to COBOL: DIS-TRAN-TYPE-CD
     * @return List of all disclosure group records matching the transaction type code.
     *         Returns empty list if no records found for the specified type.
     *         List is ordered by account group ID and category code for
     *         deterministic processing matching COBOL sequential read behavior.
     */
    List<DisclosureGroup> findByDiscTranTypeCd(String typeCode);

    // Inherited methods from JpaRepository<DisclosureGroup, DisclosureGroupId>:
    // All methods below are automatically implemented by Spring Data JPA and replace
    // COBOL EXEC CICS FILE('DISCGRP') operations per Section 0.4.23 transformation rules.

    /**
     * Save a disclosure group record (insert or update).
     * 
     * Replaces COBOL EXEC CICS WRITE and EXEC CICS REWRITE operations.
     * If the entity with the composite key doesn't exist, performs INSERT.
     * If the entity exists, performs UPDATE with optimistic locking check.
     * 
     * @param entity DisclosureGroup entity to save
     * @return Saved DisclosureGroup entity with updated audit timestamps
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict detected
     */
    // <S extends DisclosureGroup> S save(S entity);

    /**
     * Save multiple disclosure group records (batch insert or update).
     * 
     * Replaces COBOL batch file write operations. More efficient than multiple
     * individual save() calls as it uses JDBC batch processing.
     * 
     * @param entities Iterable of DisclosureGroup entities to save
     * @return List of saved DisclosureGroup entities
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict detected
     */
    // <S extends DisclosureGroup> List<S> saveAll(Iterable<S> entities);

    /**
     * Find a disclosure group record by its composite primary key.
     * 
     * Replaces COBOL EXEC CICS READ FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY) operation.
     * Uses PostgreSQL B-tree index for sub-10ms lookup per Section 0.7.7 requirements.
     * 
     * @param id DisclosureGroupId containing discAcctGroupId, discTranTypeCd, discTranCatCd
     * @return Optional containing the DisclosureGroup if found, empty Optional otherwise
     */
    // Optional<DisclosureGroup> findById(DisclosureGroupId id);

    /**
     * Check if a disclosure group record exists by its composite primary key.
     * 
     * Efficient existence check without loading the full entity.
     * Uses COUNT query on indexed composite key.
     * 
     * @param id DisclosureGroupId containing discAcctGroupId, discTranTypeCd, discTranCatCd
     * @return true if disclosure group record exists, false otherwise
     */
    // boolean existsById(DisclosureGroupId id);

    /**
     * Find all disclosure group records in the table.
     * 
     * Replaces COBOL sequential file read of entire DISCGRP file.
     * Use with caution on large datasets; prefer query methods with filters.
     * 
     * @return List of all DisclosureGroup entities
     */
    // List<DisclosureGroup> findAll();

    /**
     * Find multiple disclosure group records by their composite primary keys.
     * 
     * Replaces COBOL multiple random file reads with single batch query.
     * More efficient than multiple findById() calls.
     * 
     * @param ids Iterable of DisclosureGroupId keys to retrieve
     * @return List of DisclosureGroup entities matching the provided keys
     */
    // List<DisclosureGroup> findAllById(Iterable<DisclosureGroupId> ids);

    /**
     * Count total number of disclosure group records in the table.
     * 
     * Replaces COBOL file record count operations.
     * Returns count from PostgreSQL internal table statistics for efficiency.
     * 
     * @return Total count of disclosure group records
     */
    // long count();

    /**
     * Delete a disclosure group record by its composite primary key.
     * 
     * Replaces COBOL EXEC CICS DELETE FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY) operation.
     * Throws exception if record doesn't exist.
     * 
     * @param id DisclosureGroupId containing discAcctGroupId, discTranTypeCd, discTranCatCd
     * @throws org.springframework.dao.EmptyResultDataAccessException if record not found
     */
    // void deleteById(DisclosureGroupId id);

    /**
     * Delete a disclosure group record by entity instance.
     * 
     * Replaces COBOL EXEC CICS DELETE operation using loaded entity.
     * Includes optimistic locking check via version field.
     * 
     * @param entity DisclosureGroup entity to delete
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict detected
     */
    // void delete(DisclosureGroup entity);

    /**
     * Delete all disclosure group records in the table.
     * 
     * Replaces COBOL file purge operations.
     * Use with caution - permanently removes all disclosure group data.
     * 
     * WARNING: This operation cannot be rolled back once committed.
     */
    // void deleteAll();

    /**
     * Delete multiple disclosure group records by entity instances.
     * 
     * Replaces COBOL batch delete operations.
     * Includes optimistic locking checks for each entity.
     * 
     * @param entities Iterable of DisclosureGroup entities to delete
     * @throws org.springframework.dao.OptimisticLockingFailureException if version conflict detected
     */
    // void deleteAll(Iterable<? extends DisclosureGroup> entities);

    /**
     * Delete multiple disclosure group records by their composite primary keys.
     * 
     * Replaces COBOL batch delete operations by key.
     * More efficient than deleteAll(Iterable) as it doesn't load entities first.
     * 
     * @param ids Iterable of DisclosureGroupId keys to delete
     */
    // void deleteAllById(Iterable<? extends DisclosureGroupId> ids);
}
