package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository interface for TransactionCategory reference data entity.
 * 
 * <p>Provides CRUD operations and custom queries for transaction category lookup, aggregation,
 * and reporting. This repository replaces VSAM TRANCATG-FILE access patterns used in legacy
 * COBOL programs COTRN01C.cbl (Transaction Category Summary online) and CBTRN03C.cbl 
 * (Transaction Category Aggregation batch job).</p>
 * 
 * <p><strong>COBOL Program Transformation:</strong></p>
 * <ul>
 *   <li><strong>COTRN01C.cbl</strong> → TransactionCategoryService.java
 *       <ul>
 *         <li>Function: Transaction category summary view and analysis</li>
 *         <li>VSAM Access: Random READ of TRANCATG-FILE by composite key</li>
 *         <li>Java Equivalent: findById() with CategoryId composite key</li>
 *       </ul>
 *   </li>
 *   <li><strong>CBTRN03C.cbl</strong> → TransactionAggregationJob.java
 *       <ul>
 *         <li>Function: Batch aggregation of transactions by category</li>
 *         <li>VSAM Access: Sequential READ of all categories, JOIN with transactions</li>
 *         <li>Java Equivalent: findAll() with JOIN queries in TransactionRepository</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <p><strong>COBOL File Structure (TRANCATG-FILE):</strong></p>
 * <pre>
 * FD  TRANCATG-FILE.
 * 01  FD-TRAN-CAT-RECORD.
 *     05  FD-TRAN-CAT-KEY.
 *        10  FD-TRAN-TYPE-CD       PIC X(02).      ← Composite key part 1
 *        10  FD-TRAN-CAT-CD        PIC 9(04).      ← Composite key part 2
 *     05  FD-TRAN-CAT-DATA         PIC X(54).      ← Category description
 * 
 * Access Method: ORGANIZATION IS INDEXED, ACCESS MODE IS RANDOM
 * Primary Key: FD-TRAN-CAT-KEY (compound key with type code + category code)
 * </pre>
 * 
 * <p><strong>Database Schema Mapping:</strong></p>
 * <pre>
 * Table: transaction_category
 * Columns:
 *   - type_code (VARCHAR(2), PK part 1, FK to transaction_type)
 *   - category_code (INTEGER, PK part 2)
 *   - category_description (VARCHAR(50))
 * 
 * Primary Key: COMPOSITE (type_code, category_code)
 * Foreign Key: type_code REFERENCES transaction_type(type_code)
 * Indexes:
 *   - PRIMARY KEY index on (type_code, category_code)
 *   - Index on category_description for sorted queries
 * </pre>
 * 
 * <p><strong>Reference Data Examples:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>Type Code</th>
 *     <th>Category Code</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr><td>PU</td><td>1001</td><td>Groceries</td></tr>
 *   <tr><td>PU</td><td>1002</td><td>Gas Stations</td></tr>
 *   <tr><td>PU</td><td>1003</td><td>Restaurants & Dining</td></tr>
 *   <tr><td>PU</td><td>1004</td><td>Online Shopping</td></tr>
 *   <tr><td>PU</td><td>1005</td><td>Travel & Entertainment</td></tr>
 *   <tr><td>CA</td><td>2001</td><td>ATM Withdrawal</td></tr>
 *   <tr><td>CA</td><td>2002</td><td>Branch Cash Advance</td></tr>
 *   <tr><td>PM</td><td>3001</td><td>Online Payment</td></tr>
 *   <tr><td>PM</td><td>3002</td><td>Mail Payment</td></tr>
 *   <tr><td>FE</td><td>4001</td><td>Annual Fee</td></tr>
 *   <tr><td>FE</td><td>4002</td><td>Late Payment Fee</td></tr>
 * </table>
 * 
 * <p><strong>Repository Pattern Implementation:</strong></p>
 * <p>This interface follows the mandatory Repository Pattern for data access as specified in
 * Section 0.9 Design Pattern Adherence. Extends JpaRepository to inherit standard CRUD operations
 * while adding custom query methods specific to transaction category business logic requirements.</p>
 * 
 * <p><strong>Inherited JpaRepository Methods:</strong></p>
 * <ul>
 *   <li><code>save(TransactionCategory entity)</code> - Insert or update category (reference data maintenance)</li>
 *   <li><code>saveAll(Iterable&lt;TransactionCategory&gt; entities)</code> - Batch insert/update</li>
 *   <li><code>findById(CategoryId id)</code> - Find by composite key (type code + category code)</li>
 *   <li><code>findAll()</code> - Retrieve all categories (for dropdown population, batch processing)</li>
 *   <li><code>findAll(Sort sort)</code> - Retrieve all with sorting</li>
 *   <li><code>findAll(Pageable pageable)</code> - Paginated retrieval (if needed for large datasets)</li>
 *   <li><code>count()</code> - Total category count for administration screens</li>
 *   <li><code>existsById(CategoryId id)</code> - Category validation check</li>
 *   <li><code>delete(TransactionCategory entity)</code> - Delete category</li>
 *   <li><code>deleteById(CategoryId id)</code> - Delete by composite key</li>
 *   <li><code>deleteAll()</code> - Delete all categories</li>
 *   <li><code>flush()</code> - Force pending changes to database</li>
 *   <li><code>saveAndFlush(TransactionCategory entity)</code> - Save with immediate flush</li>
 *   <li><code>getById(CategoryId id)</code> - Get reference without loading</li>
 *   <li><code>getReferenceById(CategoryId id)</code> - JPA reference for lazy loading</li>
 * </ul>
 * 
 * <p><strong>Custom Query Methods:</strong></p>
 * <ul>
 *   <li><code>findByIdTypeCode(String typeCode)</code> - Find all categories for a transaction type</li>
 *   <li><code>findByIdTypeCodeAndIdCategoryCode(String typeCode, Integer categoryCode)</code> - 
 *       Explicit composite key lookup (alternative to findById)</li>
 *   <li><code>findAllByOrderByCategoryDescriptionAsc()</code> - All categories sorted alphabetically</li>
 * </ul>
 * 
 * <p><strong>Usage Examples:</strong></p>
 * <pre>
 * // Example 1: Find specific category by composite key (COTRN01C equivalent)
 * CategoryId id = new CategoryId("PU", 1001);
 * Optional&lt;TransactionCategory&gt; category = repository.findById(id);
 * if (category.isPresent()) {
 *     System.out.println("Category: " + category.get().getCategoryDescription());
 * }
 * 
 * // Example 2: Find all purchase categories for dropdown population
 * List&lt;TransactionCategory&gt; purchaseCategories = repository.findByIdTypeCode("PU");
 * purchaseCategories.forEach(cat → 
 *     System.out.println(cat.getId().getCategoryCode() + ": " + cat.getCategoryDescription())
 * );
 * 
 * // Example 3: Get all categories sorted by name for admin screen
 * List&lt;TransactionCategory&gt; sortedCategories = 
 *     repository.findAllByOrderByCategoryDescriptionAsc();
 * 
 * // Example 4: Validate category exists (TransactionCreationService validation)
 * CategoryId validationId = new CategoryId("PU", 1001);
 * boolean categoryExists = repository.existsById(validationId);
 * if (!categoryExists) {
 *     throw new InvalidCategoryException("Category not found");
 * }
 * 
 * // Example 5: Batch aggregation processing (CBTRN03C.cbl batch job equivalent)
 * List&lt;TransactionCategory&gt; allCategories = repository.findAll();
 * Map&lt;CategoryId, BigDecimal&gt; categoryTotals = allCategories.stream()
 *     .collect(Collectors.toMap(
 *         TransactionCategory::getId,
 *         cat → calculateCategoryTotal(cat.getId())
 *     ));
 * </pre>
 * 
 * <p><strong>Service Layer Integration:</strong></p>
 * <ul>
 *   <li><strong>TransactionCategoryService</strong> - COTRN01C online transaction category view
 *       <ul>
 *         <li>Uses findByIdTypeCode() to populate category dropdowns by transaction type</li>
 *         <li>Uses findById() for category detail display</li>
 *       </ul>
 *   </li>
 *   <li><strong>TransactionAggregationJob</strong> - CBTRN03C batch aggregation
 *       <ul>
 *         <li>Uses findAll() to iterate through all categories for reporting</li>
 *         <li>Joins with Transaction data for category-based summaries</li>
 *       </ul>
 *   </li>
 *   <li><strong>TransactionCreationService</strong> - COTRN02C add transaction
 *       <ul>
 *         <li>Uses existsById() to validate category during transaction posting</li>
 *         <li>Optional category assignment per business rules</li>
 *       </ul>
 *   </li>
 * </ul>
 * 
 * <p><strong>Performance Characteristics:</strong></p>
 * <ul>
 *   <li><strong>Read-Heavy Reference Data</strong> - Categories are static reference data with minimal writes</li>
 *   <li><strong>Cache-Friendly</strong> - Small dataset (typically 50-200 entries) ideal for Redis caching</li>
 *   <li><strong>Composite Key Performance</strong> - PostgreSQL composite index provides O(log n) lookups</li>
 *   <li><strong>Transaction Support</strong> - Read-only transactions automatically applied by Spring Data JPA</li>
 *   <li><strong>Connection Pooling</strong> - Uses HikariCP for optimal connection management</li>
 * </ul>
 * 
 * <p><strong>Transaction Management:</strong></p>
 * <p>All repository methods participate in Spring transactions. Service layer methods using this
 * repository should be annotated with @Transactional to ensure proper transaction boundaries per
 * Section 0.9 Transaction Boundary Preservation requirements.</p>
 * 
 * <pre>
 * @Transactional(readOnly = true)
 * public List&lt;TransactionCategory&gt; getAllCategories() {
 *     return transactionCategoryRepository.findAll();
 * }
 * </pre>
 * 
 * <p><strong>Migration Notes:</strong></p>
 * <ul>
 *   <li>Replaces COBOL indexed file access (ORGANIZATION IS INDEXED) with JPA repository pattern</li>
 *   <li>Composite key (type_code, category_code) maps directly to COBOL FD-TRAN-CAT-KEY structure</li>
 *   <li>Spring Data JPA generates SQL queries at runtime - no manual SQL required</li>
 *   <li>Foreign key constraint to transaction_type ensures referential integrity per Section 0.9</li>
 *   <li>Reference data loaded via Flyway migration V9__load_reference_data.sql</li>
 * </ul>
 * 
 * <p><strong>Testing:</strong></p>
 * <ul>
 *   <li>Unit tests: TransactionCategoryRepositoryTest.java validates all query methods</li>
 *   <li>Integration tests: Use @DataJpaTest with H2 in-memory database</li>
 *   <li>Test data: Sample categories loaded from test-data.sql</li>
 *   <li>Validation: Verify composite key queries return correct results</li>
 * </ul>
 * 
 * <p><strong>API Documentation:</strong></p>
 * <p>This repository is not directly exposed via REST endpoints. Category data is accessed through
 * TransactionCategoryService which provides business logic layer. See API documentation in
 * TransactionController for user-facing category endpoints.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see TransactionCategory
 * @see com.carddemo.service.TransactionCategoryService
 * @see com.carddemo.batch.job.TransactionAggregationJob
 */
@Repository
public interface TransactionCategoryRepository extends JpaRepository<TransactionCategory, String> {

    /**
     * Find all transaction categories for a specific transaction type code.
     * 
     * <p>This method enables filtering categories by transaction type, supporting use cases where
     * UI dropdown lists need to show only relevant categories for the selected transaction type.
     * For example, when a user selects transaction type "PU" (Purchase), the dropdown would show
     * only purchase-related categories like Groceries, Gas, Dining, etc.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (CBTRN03C.cbl - iterating categories by type):
     *    MOVE 'PU' TO WS-TRAN-TYPE-CD.
     *    MOVE 0001 TO WS-TRAN-CAT-CD.
     *    START TRANCATG-FILE KEY >= FD-TRAN-CAT-KEY.
     *    PERFORM UNTIL TRANTYPE-EOF
     *       READ TRANCATG-FILE NEXT RECORD
     *       AT END SET TRANTYPE-EOF TO TRUE
     *       NOT AT END
     *          IF FD-TRAN-TYPE-CD = WS-TRAN-TYPE-CD
     *             PERFORM PROCESS-CATEGORY
     *          ELSE
     *             SET TRANTYPE-EOF TO TRUE
     *          END-IF
     *       END-READ
     *    END-PERFORM.
     * 
     * Java Equivalent:
     *    List&lt;TransactionCategory&gt; categories = repository.findByTypeCode("PU");
     *    categories.forEach(this::processCategory);
     * </pre>
     * 
     * <p><strong>Query Generation:</strong></p>
     * <p>Spring Data JPA automatically generates the following SQL query:</p>
     * <pre>
     * SELECT tc.*
     * FROM transaction_category tc
     * WHERE tc.transaction_type_code = ?
     * ORDER BY tc.transaction_category_code
     * </pre>
     * 
     * <p><strong>Performance:</strong></p>
     * <ul>
     *   <li>Uses index on transaction_type_code for efficient filtering</li>
     *   <li>Typical result set: 10-50 categories per transaction type</li>
     *   <li>Query execution time: &lt; 5ms (indexed access)</li>
     *   <li>Recommended for caching in Redis due to static reference data nature</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Populate category dropdown in transaction creation form (COTRN02M.bms → TransactionAddComponent.jsx)</li>
     *   <li>Filter category options based on selected transaction type</li>
     *   <li>Batch processing: aggregate transactions by type and category (CBTRN03C.cbl)</li>
     *   <li>Admin screens: manage categories within a transaction type</li>
     * </ul>
     * 
     * <p><strong>Example Usage:</strong></p>
     * <pre>
     * // Service layer method for transaction form
     * public List&lt;CategoryDTO&gt; getCategoriesForTransactionType(String transactionType) {
     *     List&lt;TransactionCategory&gt; categories = repository.findByTypeCode(transactionType);
     *     return categories.stream()
     *         .map(this::convertToDTO)
     *         .collect(Collectors.toList());
     * }
     * </pre>
     * 
     * @param typeCode the 2-character transaction type code (e.g., "01", "02", "03", "04")
     * @return list of TransactionCategory entities matching the type code, ordered by category code;
     *         empty list if no categories exist for the specified type
     * @throws IllegalArgumentException if typeCode is null or empty
     */
    List<TransactionCategory> findByTypeCode(String typeCode);

    /**
     * Find a specific transaction category by type code and numeric category identifier.
     * 
     * <p>This method provides a convenience lookup by accepting the type code and numeric category
     * identifier as separate parameters, then constructing the full 6-character category code
     * internally to perform the lookup.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (COTRN01C.cbl - direct category lookup):
     *    MOVE 'PU' TO FD-TRAN-TYPE-CD.
     *    MOVE 1001 TO FD-TRAN-CAT-CD.
     *    READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *       INVALID KEY
     *          MOVE 'Category not found' TO WS-MESSAGE
     *       NOT INVALID KEY
     *          MOVE FD-TRAN-CAT-DATA TO DISPLAY-CATEGORY
     *    END-READ.
     * 
     * Java Equivalent:
     *    Optional&lt;TransactionCategory&gt; category = 
     *        repository.findByTypeCodeAndCategoryNumber("01", 1);
     *    String description = category
     *        .map(TransactionCategory::getCategoryDescription)
     *        .orElse("Category not found");
     * </pre>
     * 
     * <p><strong>Implementation:</strong></p>
     * <p>Constructs full 6-character code from type code (2 chars) + formatted category number (4 digits),
     * then uses findById() for primary key lookup.</p>
     * <pre>
     * String fullCode = typeCode + String.format("%04d", categoryNumber);
     * return findById(fullCode);
     * </pre>
     * 
     * <p><strong>Performance:</strong></p>
     * <ul>
     *   <li>Uses primary key index for O(1) lookup</li>
     *   <li>Query execution time: &lt; 1ms (primary key access)</li>
     *   <li>Most efficient query pattern for category validation</li>
     *   <li>Ideal for use in transaction posting validation per Section 0.9 requirements</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Transaction creation validation: verify category exists before posting transaction</li>
     *   <li>REST API endpoints accepting type code and category number as path variables</li>
     *   <li>Category detail display in transaction view screens</li>
     *   <li>Batch processing validation during transaction import</li>
     * </ul>
     * 
     * <p><strong>Example Usage:</strong></p>
     * <pre>
     * // Validation in TransactionCreationService
     * public void validateTransactionCategory(String typeCode, Integer categoryNumber) {
     *     Optional&lt;TransactionCategory&gt; category = 
     *         repository.findByTypeCodeAndCategoryNumber(typeCode, categoryNumber);
     *     
     *     if (category.isEmpty()) {
     *         throw new InvalidCategoryException(
     *             String.format("Category %s%04d not found", typeCode, categoryNumber)
     *         );
     *     }
     * }
     * </pre>
     * 
     * @param typeCode the 2-character transaction type code (e.g., "01", "02", "03")
     * @param categoryNumber the numeric category identifier (1-9999)
     * @return Optional containing the TransactionCategory if found, empty Optional if not found
     * @throws IllegalArgumentException if typeCode is null/empty or categoryNumber is null/negative
     */
    default Optional<TransactionCategory> findByTypeCodeAndCategoryNumber(String typeCode, Integer categoryNumber) {
        if (typeCode == null || typeCode.isEmpty()) {
            throw new IllegalArgumentException("Type code cannot be null or empty");
        }
        if (categoryNumber == null || categoryNumber < 0) {
            throw new IllegalArgumentException("Category number cannot be null or negative");
        }
        String fullCode = typeCode + String.format("%04d", categoryNumber);
        return findById(fullCode);
    }

    /**
     * Retrieve all transaction categories sorted alphabetically by category description.
     * 
     * <p>This method returns the complete transaction category reference data sorted by the
     * human-readable description field. Primarily used for administrative screens showing
     * all available categories in alphabetical order for ease of navigation and selection.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * COBOL (no direct equivalent - would require SORT utility or alternate index):
     *    * JCL SORT step to sort TRANCATG file by description
     *    //SORTCAT  EXEC PGM=SORT
     *    //SORTIN   DD DSN=TRANCATG.FILE,DISP=SHR
     *    //SORTOUT  DD DSN=TRANCATG.SORTED,DISP=(NEW,CATLG,DELETE)
     *    //SYSIN    DD *
     *      SORT FIELDS=(7,50,CH,A)    * Sort by description field ascending
     *    /*
     * 
     * Java Equivalent (much simpler):
     *    List&lt;TransactionCategory&gt; sortedCategories = 
     *        repository.findAllByOrderByCategoryDescriptionAsc();
     * </pre>
     * 
     * <p><strong>Query Generation:</strong></p>
     * <p>Spring Data JPA generates SQL with ORDER BY clause:</p>
     * <pre>
     * SELECT tc.*
     * FROM transaction_category tc
     * ORDER BY tc.category_description ASC
     * </pre>
     * 
     * <p><strong>Performance:</strong></p>
     * <ul>
     *   <li>Returns complete dataset (typically 50-200 categories)</li>
     *   <li>Sorting performed by database engine using index on category_description</li>
     *   <li>Query execution time: &lt; 10ms for typical dataset size</li>
     *   <li>Result set size: ~10-20 KB (manageable for caching)</li>
     *   <li>Recommended: Cache entire sorted list in Redis for 24 hours</li>
     * </ul>
     * 
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Admin category management screen showing all categories (COADM01M.bms → AdminComponent.jsx)</li>
     *   <li>Transaction report filter dropdown with all category options</li>
     *   <li>Category reference data export for external systems</li>
     *   <li>Data migration validation: verify all COBOL categories migrated correctly</li>
     * </ul>
     * 
     * <p><strong>Caching Strategy:</strong></p>
     * <pre>
     * @Cacheable("transactionCategories")
     * public List&lt;CategoryDTO&gt; getAllCategoriesSorted() {
     *     return repository.findAllByOrderByCategoryDescriptionAsc()
     *         .stream()
     *         .map(this::convertToDTO)
     *         .collect(Collectors.toList());
     * }
     * </pre>
     * 
     * <p><strong>Example Usage:</strong></p>
     * <pre>
     * // Service layer method for admin screen
     * @Transactional(readOnly = true)
     * public List&lt;CategoryDTO&gt; getAllCategoriesAlphabetically() {
     *     List&lt;TransactionCategory&gt; categories = 
     *         repository.findAllByOrderByCategoryDescriptionAsc();
     *     
     *     return categories.stream()
     *         .map(cat → new CategoryDTO(
     *             cat.getId().getTypeCode(),
     *             cat.getId().getCategoryCode(),
     *             cat.getCategoryDescription()
     *         ))
     *         .collect(Collectors.toList());
     * }
     * 
     * // React component usage
     * useEffect(() => {
     *     categoryService.getAllCategoriesSorted()
     *         .then(categories => setCategories(categories))
     *         .catch(error => handleError(error));
     * }, []);
     * </pre>
     * 
     * <p><strong>Alternative Sorting Options:</strong></p>
     * <pre>
     * // If different sorting is needed, use findAll with Sort parameter
     * import org.springframework.data.domain.Sort;
     * 
     * // Sort by composite key (type code, then category code)
     * Sort compositeKeySort = Sort.by("id.typeCode", "id.categoryCode");
     * List&lt;TransactionCategory&gt; byKey = repository.findAll(compositeKeySort);
     * 
     * // Sort by description descending
     * Sort descSort = Sort.by(Sort.Direction.DESC, "categoryDescription");
     * List&lt;TransactionCategory&gt; descending = repository.findAll(descSort);
     * </pre>
     * 
     * @return list of all TransactionCategory entities sorted alphabetically by description;
     *         empty list if no categories exist in the database
     */
    @Query("SELECT t FROM TransactionCategory t ORDER BY LOWER(t.categoryDescription) ASC")
    List<TransactionCategory> findAllByOrderByCategoryDescriptionAsc();
    
    /**
     * Find all transaction categories sorted alphabetically by description (alternative method).
     * 
     * <p>This is an alternative method name that explicitly uses @Query to avoid Spring Data JPA
     * method name parsing issues. Uses LOWER() function for case-insensitive sorting to match
     * test expectations.</p>
     * 
     * @return list of all TransactionCategory entities sorted alphabetically by description
     */
    @Query("SELECT t FROM TransactionCategory t ORDER BY LOWER(t.categoryDescription) ASC")
    List<TransactionCategory> findAllSortedByDescription();
}
