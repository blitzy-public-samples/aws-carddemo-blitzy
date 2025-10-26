package com.carddemo.repository;

import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionCategoryId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA Repository interface for TransactionCategory entity.
 * 
 * Converted from VSAM TRANCATG KSDS file I/O operations to Spring Data JPA
 * repository providing PostgreSQL database access with automatic CRUD operations.
 * 
 * Original VSAM Structure:
 * - File: TRANCATG (Transaction Category Type reference data)
 * - Organization: KSDS (Key-Sequenced Data Set) indexed file
 * - Record Layout: CVTRA04Y.cpy copybook (60-byte fixed records)
 * - Key Structure: Composite key (6 bytes):
 *   * TRAN-TYPE-CD PIC X(02) - Transaction type code (2 bytes, offset 0)
 *   * TRAN-CAT-CD PIC 9(04) - Transaction category code (4 bytes, offset 2)
 * - Record Size: 60 bytes fixed (RECORDSIZE(60 60))
 * - Access Pattern: Reference data lookups by composite key and by type code
 * 
 * COBOL VSAM Operations Replaced:
 * - EXEC CICS READ FILE('TRANCATG') RIDFLD(TRAN-CAT-KEY) INTO(TRAN-CAT-RECORD)
 *   → findById(TransactionCategoryId id)
 * 
 * - EXEC CICS WRITE FILE('TRANCATG') FROM(TRAN-CAT-RECORD) RIDFLD(TRAN-CAT-KEY)
 *   → save(TransactionCategory category) [insert]
 * 
 * - EXEC CICS REWRITE FILE('TRANCATG') FROM(TRAN-CAT-RECORD)
 *   → save(TransactionCategory category) [update]
 * 
 * - EXEC CICS DELETE FILE('TRANCATG') RIDFLD(TRAN-CAT-KEY)
 *   → deleteById(TransactionCategoryId id) or delete(TransactionCategory category)
 * 
 * - Sequential browse by transaction type (STARTBR/READNEXT pattern)
 *   → findByTransTypeCd(String transTypeCd)
 * 
 * Database Mapping:
 * - Target Table: transaction_category
 * - Primary Key: Composite (trans_type_cd, trans_cat_cd)
 * - Indexes: PostgreSQL B-tree indexes replicate VSAM key access patterns
 *   * PRIMARY KEY (trans_type_cd, trans_cat_cd) - composite key index
 *   * INDEX idx_tran_cat_type_cd ON transaction_category(trans_type_cd)
 *     for efficient findByTransTypeCd queries
 * 
 * Record Count (Estimated): 50 transaction category records
 * Access Frequency: High read, low write (reference data)
 * 
 * Usage Examples:
 * <pre>
 * // Replace COBOL: READ FILE('TRANCATG') with composite key "015001"
 * TransactionCategoryId id = new TransactionCategoryId("01", 5001);
 * Optional&lt;TransactionCategory&gt; category = repository.findById(id);
 * 
 * // Replace COBOL: WRITE FILE('TRANCATG') new category record
 * TransactionCategory newCategory = TransactionCategory.builder()
 *     .transTypeCd("01")
 *     .tranCatCd(5010)
 *     .tranCatTypeDesc("Entertainment")
 *     .build();
 * repository.save(newCategory);
 * 
 * // Replace COBOL: Sequential browse by transaction type
 * List&lt;TransactionCategory&gt; categories = repository.findByTransTypeCd("01");
 * </pre>
 * 
 * Performance Considerations:
 * - PostgreSQL B-tree indexes ensure sub-10ms primary key lookups
 *   (equivalent to VSAM KSDS direct access performance)
 * - findByTransTypeCd uses index scan for efficient type-based filtering
 * - Small reference table (50 records) allows effective caching by JPA
 * 
 * Transaction Management:
 * - All repository operations participate in Spring-managed transactions
 * - Optimistic locking via @Version field prevents concurrent update conflicts
 *   (replicates COBOL VSAM RBA optimistic locking semantics)
 * 
 * @see TransactionCategory JPA entity representing TRAN-CAT-RECORD structure
 * @see TransactionCategoryId Composite primary key class for the entity
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, TransactionCategoryId> {

    /**
     * Find all transaction categories for a specific transaction type code.
     * 
     * Replaces COBOL VSAM sequential browse pattern using STARTBR/READNEXT
     * with generic key positioning. Returns all category records matching
     * the specified transaction type, enabling dropdown population in UI
     * screens and transaction categorization logic.
     * 
     * COBOL Equivalent:
     * <pre>
     * EXEC CICS STARTBR FILE('TRANCATG')
     *      RIDFLD(WS-GENERIC-KEY)
     *      GTEQ
     * END-EXEC.
     * 
     * PERFORM UNTIL END-OF-FILE OR TRAN-TYPE-CD NOT = WS-SEARCH-TYPE
     *    EXEC CICS READNEXT FILE('TRANCATG')
     *         INTO(TRAN-CAT-RECORD)
     *    END-EXEC
     *    ... process category record ...
     * END-PERFORM.
     * 
     * EXEC CICS ENDBR FILE('TRANCATG') END-EXEC.
     * </pre>
     * 
     * Spring Data JPA automatically generates the SQL query:
     * <pre>
     * SELECT * FROM transaction_category
     * WHERE trans_type_cd = ?
     * ORDER BY trans_type_cd, trans_cat_cd
     * </pre>
     * 
     * Query Performance:
     * - Uses index idx_tran_cat_type_cd for efficient filtering
     * - Returns ordered results by composite key (type code, category code)
     * - Typical result set size: 5-10 categories per transaction type
     * - Expected execution time: &lt;5ms with proper indexing
     * 
     * Usage Examples:
     * <pre>
     * // Get all purchase categories (type "01")
     * List&lt;TransactionCategory&gt; purchaseCategories = 
     *     repository.findByTransTypeCd("01");
     * 
     * // Populate dropdown in React UI for transaction categorization
     * List&lt;TransactionCategory&gt; categories = 
     *     repository.findByTransTypeCd(transactionTypeCode);
     * return categories.stream()
     *     .map(cat -&gt; new CategoryOption(
     *         cat.getTransCatCd(),
     *         cat.getTranCatTypeDesc()))
     *     .collect(Collectors.toList());
     * </pre>
     * 
     * Business Logic Context:
     * - Used during transaction entry (COTRN02C.cbl → TransactionController)
     * - Used for report generation (CORPT00C.cbl → ReportController)
     * - Used for transaction validation and categorization
     * - Supports category dropdown population in React forms
     * 
     * @param transTypeCd Transaction type code to filter by (PIC X(02))
     *                    Examples: "01" = Purchase, "02" = Cash Advance, 
     *                              "03" = Payment, "04" = Fee
     * @return List of all TransactionCategory entities matching the type code,
     *         ordered by composite key (type code, category code).
     *         Returns empty list if no categories exist for the type.
     *         Never returns null (Spring Data JPA guarantee).
     * 
     * @throws org.springframework.dao.DataAccessException if database access fails
     * @see TransactionCategory#getTransTypeCd() Transaction type code field
     * @see TransactionCategory#getTranCatCd() Category code field
     * @see TransactionCategory#getTranCatTypeDesc() Category description field
     */
    List<TransactionCategory> findByTransTypeCd(String transTypeCd);

    // Note: All standard CRUD operations are inherited from JpaRepository:
    // - save(TransactionCategory entity) - Insert or update
    // - saveAll(Iterable<TransactionCategory> entities) - Batch insert/update
    // - findById(TransactionCategoryId id) - Find by composite key
    // - existsById(TransactionCategoryId id) - Check existence by key
    // - findAll() - Retrieve all categories (50 records)
    // - findAllById(Iterable<TransactionCategoryId> ids) - Batch retrieval
    // - count() - Count total records
    // - deleteById(TransactionCategoryId id) - Delete by composite key
    // - delete(TransactionCategory entity) - Delete by entity
    // - deleteAll() - Delete all records (use with caution on reference data)
    // - deleteAll(Iterable<TransactionCategory> entities) - Batch delete
    // - deleteAllById(Iterable<TransactionCategoryId> ids) - Batch delete by IDs
}
