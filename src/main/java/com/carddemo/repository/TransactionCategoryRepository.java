package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategory.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA Repository interface for TransactionCategory entity.
 * 
 * This repository provides database access operations for transaction category
 * reference data, replacing COBOL VSAM file I/O operations from the mainframe
 * application. It corresponds to the CVTRA04Y.cpy copybook structure
 * (TRAN-CAT-RECORD) from the original CardDemo COBOL application.
 * 
 * <p>Transaction categories are used to classify and group transaction types
 * for reporting, billing, and business intelligence purposes. They are
 * reference/lookup data that define the available transaction categories
 * in the credit card management system.</p>
 * 
 * <h2>Original COBOL/VSAM Context</h2>
 * <pre>
 * COBOL Copybook: CVTRA04Y.cpy
 * Record Structure: TRAN-CAT-RECORD (60 bytes)
 * Key Structure: TRAN-CAT-KEY (composite)
 *   - TRAN-TYPE-CD: PIC X(02) - Transaction type code
 *   - TRAN-CAT-CD: PIC 9(04) - Transaction category code
 * Data Field: TRAN-CAT-TYPE-DESC: PIC X(50) - Category description
 * </pre>
 * 
 * <h2>Transformation Details</h2>
 * <ul>
 *   <li>VSAM KSDS file operations → Spring Data JPA repository methods</li>
 *   <li>EXEC CICS READ → findById(), findByIdTypeCode(), findByIdCategoryCode()</li>
 *   <li>EXEC CICS WRITE → save() (insert)</li>
 *   <li>EXEC CICS REWRITE → save() (update)</li>
 *   <li>EXEC CICS DELETE → deleteById(), delete()</li>
 *   <li>EXEC CICS STARTBR/READNEXT → findAll(), custom query methods</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Find specific category by composite key
 * TransactionCategoryId id = new TransactionCategoryId("01", "0001");
 * Optional&lt;TransactionCategory&gt; category = repository.findById(id);
 * 
 * // Find all categories for a specific type code
 * List&lt;TransactionCategory&gt; purchaseCategories = repository.findByIdTypeCode("01");
 * 
 * // Find all occurrences of a specific category code across types
 * List&lt;TransactionCategory&gt; retailCategories = repository.findByIdCategoryCode("0001");
 * 
 * // Create new category
 * TransactionCategory newCategory = TransactionCategory.builder()
 *     .id(new TransactionCategoryId("02", "0003"))
 *     .categoryDescription("ATM Withdrawals")
 *     .build();
 * repository.save(newCategory);
 * </pre>
 * 
 * <h2>Performance Considerations</h2>
 * <p>The composite primary key (type_code, category_code) is automatically
 * indexed by PostgreSQL, providing performance equivalent to the VSAM KSDS
 * key structure. Custom query methods leverage this index for efficient
 * partial key lookups.</p>
 * 
 * @see TransactionCategory
 * @see TransactionCategoryId
 * @see org.springframework.data.jpa.repository.JpaRepository
 */
@Repository
public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, TransactionCategoryId> {

    /**
     * Find all transaction categories with the specified category code.
     * 
     * This method performs a partial key lookup, finding all categories
     * that have the given category code regardless of their transaction
     * type code. This corresponds to COBOL operations that search by
     * the second part of the composite key (TRAN-CAT-CD).
     * 
     * <p><b>COBOL Equivalent:</b> STARTBR with generic key on TRAN-CAT-CD</p>
     * 
     * <p>Example: Finding all "0001" (retail) categories across different
     * transaction types (purchases, returns, etc.)</p>
     * 
     * <p><b>Query Generated:</b>
     * <pre>
     * SELECT tc FROM TransactionCategory tc 
     * WHERE tc.id.categoryCode = :categoryCode
     * ORDER BY tc.id.typeCode, tc.id.categoryCode
     * </pre>
     * </p>
     * 
     * @param categoryCode the transaction category code (4 characters, 
     *                     e.g., "0001" for retail purchases).
     *                     Maps to TRAN-CAT-CD (PIC 9(04)) from COBOL.
     *                     Should be zero-padded to 4 digits.
     * @return List of TransactionCategory entities matching the category code.
     *         Returns empty list if no matches found.
     *         Results are ordered by type code, then category code.
     * @throws org.springframework.dao.DataAccessException if database access fails
     * 
     * @see TransactionCategory
     * @see TransactionCategoryId#getCategoryCode()
     */
    List<TransactionCategory> findByIdCategoryCode(String categoryCode);

    /**
     * Find all transaction categories with the specified transaction type code.
     * 
     * This method performs a partial key lookup, finding all categories
     * that belong to the given transaction type. This corresponds to COBOL
     * operations that search by the first part of the composite key
     * (TRAN-TYPE-CD).
     * 
     * <p><b>COBOL Equivalent:</b> STARTBR with generic key on TRAN-TYPE-CD</p>
     * 
     * <p>Example: Finding all categories under transaction type "01" (purchases),
     * which might include retail purchases, online purchases, international
     * purchases, etc.</p>
     * 
     * <p><b>Query Generated:</b>
     * <pre>
     * SELECT tc FROM TransactionCategory tc 
     * WHERE tc.id.typeCode = :typeCode
     * ORDER BY tc.id.typeCode, tc.id.categoryCode
     * </pre>
     * </p>
     * 
     * @param typeCode the transaction type code (2 characters, 
     *                 e.g., "01" for purchases, "02" for cash advances).
     *                 Maps to TRAN-TYPE-CD (PIC X(02)) from COBOL.
     * @return List of TransactionCategory entities matching the type code.
     *         Returns empty list if no matches found.
     *         Results are ordered by category code within the type.
     * @throws org.springframework.dao.DataAccessException if database access fails
     * 
     * @see TransactionCategory
     * @see TransactionCategoryId#getTypeCode()
     */
    List<TransactionCategory> findByIdTypeCode(String typeCode);

    // Additional inherited methods from JpaRepository<TransactionCategory, TransactionCategoryId>:
    //
    // - Optional<TransactionCategory> findById(TransactionCategoryId id)
    //   Replaces: EXEC CICS READ FILE('TRNCAT') INTO(TRAN-CAT-RECORD) RIDFLD(TRAN-CAT-KEY)
    //
    // - <S extends TransactionCategory> S save(S entity)
    //   Replaces: EXEC CICS WRITE FILE('TRNCAT') FROM(TRAN-CAT-RECORD) (insert)
    //             EXEC CICS REWRITE FILE('TRNCAT') FROM(TRAN-CAT-RECORD) (update)
    //
    // - <S extends TransactionCategory> List<S> saveAll(Iterable<S> entities)
    //   Batch insert/update operations
    //
    // - void deleteById(TransactionCategoryId id)
    //   Replaces: EXEC CICS DELETE FILE('TRNCAT') RIDFLD(TRAN-CAT-KEY)
    //
    // - void delete(TransactionCategory entity)
    //   Delete by entity reference
    //
    // - List<TransactionCategory> findAll()
    //   Replaces: EXEC CICS STARTBR FILE('TRNCAT') followed by READNEXT loop
    //
    // - boolean existsById(TransactionCategoryId id)
    //   Check existence without retrieving full record
    //
    // - long count()
    //   Get total count of transaction category records
}
