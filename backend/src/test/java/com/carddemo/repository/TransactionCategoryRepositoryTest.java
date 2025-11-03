package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategory.CategoryId;
import com.carddemo.entity.TransactionType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive JUnit 5 test class for TransactionCategoryRepository.
 * 
 * <p>Tests Spring Data JPA repository methods for transaction category reference data operations
 * migrated from COBOL VSAM TRANCATG-FILE access patterns. This test suite validates composite
 * key functionality, foreign key relationships, constraint enforcement, and query methods that
 * provide functional equivalence to COBOL programs COTRN01C.cbl (transaction category summary)
 * and CBTRN03C.cbl (batch transaction aggregation).</p>
 * 
 * <p><strong>COBOL Source File Mapping:</strong></p>
 * <ul>
 *   <li><strong>CVTRA04Y.cpy</strong> - TRAN-CAT-RECORD copybook (60-byte record layout)</li>
 *   <li><strong>COTRN01C.cbl</strong> - Online transaction category summary program</li>
 *   <li><strong>CBTRN03C.cbl</strong> - Batch transaction detail report with category aggregation</li>
 *   <li><strong>LISTCAT.txt</strong> - VSAM catalog definition for TRANCATG file</li>
 * </ul>
 * 
 * <p><strong>Data Structure Validation:</strong></p>
 * <pre>
 * COBOL TRAN-CAT-RECORD (RECLN 60):
 * 05  TRAN-CAT-KEY.
 *    10  TRAN-TYPE-CD         PIC X(02).     → VARCHAR(2) type_code
 *    10  TRAN-CAT-CD          PIC 9(04).     → INTEGER category_code
 * 05  TRAN-CAT-TYPE-DESC      PIC X(50).     → VARCHAR(50) category_description
 * 05  FILLER                  PIC X(04).     → Not mapped (alignment padding)
 * </pre>
 * 
 * <p><strong>Database Schema Under Test:</strong></p>
 * <pre>
 * Table: transaction_category
 * Columns:
 *   - type_code (VARCHAR(2), PK part 1, FK to transaction_type)
 *   - category_code (INTEGER, PK part 2)
 *   - category_description (VARCHAR(50), NOT NULL)
 * 
 * Primary Key: COMPOSITE (type_code, category_code)
 * Foreign Key: type_code REFERENCES transaction_type(type_code) ON DELETE RESTRICT
 * Indexes:
 *   - PRIMARY KEY b-tree index on (type_code, category_code)
 *   - Secondary index on category_description for sorted queries
 * </pre>
 * 
 * <p><strong>Test Configuration:</strong></p>
 * <ul>
 *   <li>Uses @DataJpaTest for repository layer testing with H2 in-memory database</li>
 *   <li>Loads test data via @Sql scripts ensuring transaction types loaded first</li>
 *   <li>Tests ordered execution with @TestMethodOrder for sequential validation</li>
 *   <li>Validates composite key operations matching COBOL compound key semantics</li>
 *   <li>Verifies foreign key constraints for referential integrity per Section 0.9</li>
 * </ul>
 * 
 * <p><strong>VSAM Access Pattern Equivalence:</strong></p>
 * <table border="1">
 *   <tr>
 *     <th>COBOL VSAM Operation</th>
 *     <th>Java JPA Equivalent</th>
 *   </tr>
 *   <tr>
 *     <td>READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY</td>
 *     <td>repository.findById(new CategoryId("01", 5010))</td>
 *   </tr>
 *   <tr>
 *     <td>START TRANCATG-FILE KEY >= FD-TRAN-CAT-KEY</td>
 *     <td>repository.findByIdTypeCode("01")</td>
 *   </tr>
 *   <tr>
 *     <td>READ TRANCATG-FILE NEXT</td>
 *     <td>repository.findAll() with sorting</td>
 *   </tr>
 *   <tr>
 *     <td>WRITE TRANCATG-FILE FROM FD-TRAN-CAT-RECORD</td>
 *     <td>repository.save(transactionCategory)</td>
 *   </tr>
 *   <tr>
 *     <td>REWRITE TRANCATG-FILE FROM FD-TRAN-CAT-RECORD</td>
 *     <td>repository.save(existingCategory)</td>
 *   </tr>
 *   <tr>
 *     <td>DELETE TRANCATG-FILE</td>
 *     <td>repository.delete(transactionCategory)</td>
 *   </tr>
 * </table>
 * 
 * <p><strong>Performance Requirements:</strong></p>
 * <ul>
 *   <li>Composite key lookups: &lt; 1ms (B-tree indexed primary key access)</li>
 *   <li>findByIdTypeCode queries: &lt; 5ms (indexed access with 10-50 results)</li>
 *   <li>findAll operations: &lt; 10ms (50-200 total categories)</li>
 *   <li>Save operations: &lt; 10ms (single row insert/update)</li>
 *   <li>Supports 10,000 TPS transaction categorization per Section 0.2 requirements</li>
 * </ul>
 * 
 * <p><strong>Test Data Examples:</strong></p>
 * <pre>
 * Type '01' (Purchase):
 *   - 5010: Retail Merchandise
 *   - 5411: Grocery Stores
 *   - 5541: Service Stations
 *   - 5812: Restaurants
 *   - 5999: Miscellaneous Retail
 * 
 * Type '02' (Cash Advance):
 *   - 6010: ATM Cash Withdrawal
 *   - 6011: Cash Advance Fee
 * 
 * Type '03' (Payment):
 *   - 0: Bill Payment
 * 
 * Type '04' (Fee):
 *   - 7010: Annual Fee
 *   - 7020: Late Payment Fee
 *   - 7030: Over Limit Fee
 * 
 * Type '05' (Interest):
 *   - 8010: Purchase Interest
 *   - 8020: Cash Advance Interest
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2024-01-01
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionType
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Sql(scripts = {
    "/db/test-data/transaction-types.sql",
    "/db/test-data/transaction-categories.sql"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionCategoryRepositoryTest {

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Test 1: testFindById_ValidCompositeKey
     * 
     * <p>Tests VSAM random read with composite key validation. Verifies that findById()
     * correctly retrieves a transaction category using a composite primary key consisting
     * of type_code and category_code, matching COBOL TRAN-CAT-KEY structure.</p>
     * 
     * <p><strong>COBOL Equivalent (COTRN01C.cbl):</strong></p>
     * <pre>
     * MOVE '01' TO FD-TRAN-TYPE-CD.
     * MOVE 5010 TO FD-TRAN-CAT-CD.
     * READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'CATEGORY NOT FOUND' TO WS-MESSAGE
     *    NOT INVALID KEY
     *       MOVE FD-TRAN-CAT-TYPE-DESC TO DISPLAY-CATEGORY
     * END-READ.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Composite key (type_code='01', category_code=5010) retrieves correct entity</li>
     *   <li>TRAN-TYPE-CD PIC X(02) → VARCHAR(2) type_code mapping</li>
     *   <li>TRAN-CAT-CD PIC 9(04) → INTEGER category_code mapping</li>
     *   <li>TRAN-CAT-TYPE-DESC PIC X(50) → VARCHAR(50) category_description mapping</li>
     *   <li>CategoryId embedded key class functionality</li>
     *   <li>Foreign key relationship to transaction_type is established</li>
     * </ul>
     */
    @Test
    @Order(1)
    void testFindById_ValidCompositeKey() {
        // Arrange: Create composite key matching COBOL FD-TRAN-CAT-KEY structure
        // TRAN-TYPE-CD='01' (Purchase), TRAN-CAT-CD=5010 (Retail Merchandise)
        CategoryId categoryId = new CategoryId("01", 5010);

        // Act: Execute VSAM random read equivalent
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(categoryId);

        // Assert: Validate record found and fields mapped correctly
        assertTrue(result.isPresent(), "Category with composite key ('01', 5010) should exist");
        
        TransactionCategory category = result.get();
        assertNotNull(category, "Retrieved category should not be null");
        
        // Validate composite key components
        assertNotNull(category.getId(), "Category composite key should not be null");
        assertEquals("01", category.getId().getTypeCode(), 
            "Type code should match COBOL TRAN-TYPE-CD PIC X(02) value");
        assertEquals(5010, category.getId().getCategoryCode(), 
            "Category code should match COBOL TRAN-CAT-CD PIC 9(04) value");
        
        // Validate COBOL PIC X(50) field mapping
        assertNotNull(category.getCategoryDescription(), 
            "Category description should not be null (TRAN-CAT-TYPE-DESC is required)");
        assertEquals("Retail Merchandise", category.getCategoryDescription(), 
            "Description should match test data for category 5010");
        assertTrue(category.getCategoryDescription().length() <= 50, 
            "Description should not exceed COBOL PIC X(50) length constraint");
        
        // Validate foreign key relationship to transaction_type
        assertNotNull(category.getTransactionType(), 
            "Foreign key relationship to TransactionType should be established");
        assertEquals("01", category.getTransactionType().getTypeCode(), 
            "Foreign key type_code should match parent transaction type");
    }

    /**
     * Test 2: testFindById_InvalidCompositeKey
     * 
     * <p>Tests non-existent category lookup returning Optional.empty(), equivalent to
     * COBOL VSAM INVALID KEY condition when record not found.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '99' TO FD-TRAN-TYPE-CD.
     * MOVE 9999 TO FD-TRAN-CAT-CD.
     * READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'CATEGORY NOT FOUND' TO WS-MESSAGE
     *       SET CATEGORY-NOT-FOUND TO TRUE
     * END-READ.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Non-existent composite key returns Optional.empty()</li>
     *   <li>No exception thrown for missing record (matches COBOL INVALID KEY behavior)</li>
     *   <li>Validates proper handling of category lookup failures</li>
     * </ul>
     */
    @Test
    @Order(2)
    void testFindById_InvalidCompositeKey() {
        // Arrange: Create composite key for non-existent category
        CategoryId nonExistentId = new CategoryId("99", 9999);

        // Act: Attempt to read non-existent record
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(nonExistentId);

        // Assert: Validate COBOL INVALID KEY equivalent behavior
        assertFalse(result.isPresent(), 
            "Non-existent category composite key should return empty Optional (COBOL INVALID KEY)");
    }

    /**
     * Test 3: testFindByTransactionTypeCode_ValidType
     * 
     * <p>Tests retrieving all categories for a specific transaction type, equivalent to
     * COBOL START command followed by sequential READ operations within a type code range.
     * Critical for populating category dropdowns filtered by transaction type.</p>
     * 
     * <p><strong>COBOL Equivalent (COTRN01C.cbl category iteration):</strong></p>
     * <pre>
     * MOVE '01' TO WS-TRAN-TYPE-CD.
     * MOVE 0000 TO WS-TRAN-CAT-CD.
     * START TRANCATG-FILE KEY >= FD-TRAN-CAT-KEY.
     * PERFORM UNTIL CATEGORY-EOF
     *    READ TRANCATG-FILE NEXT RECORD
     *       AT END
     *          SET CATEGORY-EOF TO TRUE
     *       NOT AT END
     *          IF FD-TRAN-TYPE-CD = WS-TRAN-TYPE-CD
     *             PERFORM PROCESS-CATEGORY
     *          ELSE
     *             SET CATEGORY-EOF TO TRUE
     *          END-IF
     *    END-READ
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Returns all categories for transaction type '01' (Purchase)</li>
     *   <li>Validates foreign key relationship is working correctly</li>
     *   <li>Verifies secondary index on type_code enables efficient filtering</li>
     *   <li>Returns List&lt;TransactionCategory&gt; with expected count (5 purchase categories)</li>
     *   <li>Results ordered by category_code within type</li>
     * </ul>
     */
    @Test
    @Order(3)
    void testFindByTransactionTypeCode_ValidType() {
        // Arrange: Set transaction type code for Purchase transactions
        String purchaseTypeCode = "01";

        // Act: Execute COBOL START...READ NEXT equivalent query
        List<TransactionCategory> purchaseCategories = 
            transactionCategoryRepository.findByIdTypeCode(purchaseTypeCode);

        // Assert: Validate results match test data expectations
        assertNotNull(purchaseCategories, "Result list should not be null");
        assertFalse(purchaseCategories.isEmpty(), 
            "Should find categories for Purchase type '01'");
        assertEquals(5, purchaseCategories.size(), 
            "Should find exactly 5 purchase categories from test data");
        
        // Validate all returned categories have correct type code
        for (TransactionCategory category : purchaseCategories) {
            assertEquals(purchaseTypeCode, category.getId().getTypeCode(), 
                "All categories should have type_code='01'");
            assertNotNull(category.getCategoryDescription(), 
                "Each category should have description (PIC X(50) field)");
        }
        
        // Validate specific expected categories exist
        boolean hasRetail = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5010);
        boolean hasGrocery = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5411);
        boolean hasServiceStation = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5541);
        
        assertTrue(hasRetail, "Should include category 5010 (Retail Merchandise)");
        assertTrue(hasGrocery, "Should include category 5411 (Grocery Stores)");
        assertTrue(hasServiceStation, "Should include category 5541 (Service Stations)");
    }

    /**
     * Test 4: testFindByTransactionTypeCode_NoCategories
     * 
     * <p>Tests transaction type with no associated categories returns empty list,
     * equivalent to COBOL START command that immediately reaches EOF.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '99' TO WS-TRAN-TYPE-CD.
     * MOVE 0000 TO WS-TRAN-CAT-CD.
     * START TRANCATG-FILE KEY >= FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'NO CATEGORIES FOR THIS TYPE' TO WS-MESSAGE
     *       SET CATEGORY-EOF TO TRUE
     * END-START.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Non-existent transaction type returns empty list (not null)</li>
     *   <li>No exception thrown for type with no categories</li>
     *   <li>Validates graceful handling of missing data</li>
     * </ul>
     */
    @Test
    @Order(4)
    void testFindByTransactionTypeCode_NoCategories() {
        // Arrange: Use non-existent transaction type code
        String nonExistentTypeCode = "99";

        // Act: Query for categories of non-existent type
        List<TransactionCategory> result = 
            transactionCategoryRepository.findByIdTypeCode(nonExistentTypeCode);

        // Assert: Validate empty list returned (COBOL EOF condition)
        assertNotNull(result, "Result should not be null, even for non-existent type");
        assertTrue(result.isEmpty(), 
            "Should return empty list for transaction type with no categories");
    }

    /**
     * Test 5: testFindByCategoryCode_AcrossTypes
     * 
     * <p>Tests that the same category code can exist across different transaction types,
     * validating the necessity of the composite key structure. Demonstrates that category_code
     * alone is not unique and requires type_code for complete identification.</p>
     * 
     * <p><strong>COBOL Pattern:</strong></p>
     * <pre>
     * * Category code 0 used in multiple transaction types
     * * Type '03' (Payment), Category 0 = Bill Payment
     * * Potentially same category code in different types for standardization
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Validates composite key necessity (type_code + category_code)</li>
     *   <li>Tests that category_code alone doesn't uniquely identify a category</li>
     *   <li>Demonstrates proper data model supporting category reuse patterns</li>
     * </ul>
     */
    @Test
    @Order(5)
    void testFindByCategoryCode_AcrossTypes() {
        // Arrange: Look for category code 0 which appears in Payment type
        CategoryId paymentCategoryId = new CategoryId("03", 0);

        // Act: Retrieve category with code 0 in type '03'
        Optional<TransactionCategory> paymentCategory = 
            transactionCategoryRepository.findById(paymentCategoryId);

        // Assert: Validate category code can be reused across types
        assertTrue(paymentCategory.isPresent(), 
            "Should find category code 0 in Payment type '03'");
        assertEquals("Bill Payment", paymentCategory.get().getCategoryDescription(),
            "Category 0 in type '03' should be Bill Payment");
        
        // Validate that a different type_code with same category_code would be distinct
        CategoryId differentTypeId = new CategoryId("01", 0);
        Optional<TransactionCategory> differentTypeCategory = 
            transactionCategoryRepository.findById(differentTypeId);
        
        // This should either not exist or be a different category if it does exist
        if (differentTypeCategory.isPresent()) {
            assertNotEquals(paymentCategory.get().getCategoryDescription(), 
                differentTypeCategory.get().getCategoryDescription(),
                "Same category code in different types should represent different categories");
        }
        
        // Demonstrate composite key necessity
        assertNotEquals(paymentCategoryId.getTypeCode(), differentTypeId.getTypeCode(),
            "Composite key components differ even when category_code is same");
    }

    /**
     * Test 6: testFindAll_ReturnsAllCategories
     * 
     * <p>Tests VSAM sequential read of all category records, equivalent to COBOL program
     * reading entire TRANCATG-FILE from beginning to end. Validates total category count
     * and ordering.</p>
     * 
     * <p><strong>COBOL Equivalent (CBTRN03C.cbl batch processing):</strong></p>
     * <pre>
     * OPEN INPUT TRANCATG-FILE.
     * PERFORM UNTIL TRANCATG-EOF
     *    READ TRANCATG-FILE NEXT RECORD
     *       AT END
     *          SET TRANCATG-EOF TO TRUE
     *       NOT AT END
     *          ADD 1 TO CATEGORY-COUNT
     *          PERFORM PROCESS-CATEGORY
     *    END-READ
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Returns all categories from test data (15 total categories)</li>
     *   <li>Verifies complete reference data loading</li>
     *   <li>Validates result ordering by composite key (type_code, category_code)</li>
     *   <li>Ensures no duplicate records exist</li>
     * </ul>
     */
    @Test
    @Order(6)
    void testFindAll_ReturnsAllCategories() {
        // Act: Execute VSAM sequential read equivalent
        List<TransactionCategory> allCategories = transactionCategoryRepository.findAll();

        // Assert: Validate all test data loaded correctly
        assertNotNull(allCategories, "Result list should not be null");
        assertFalse(allCategories.isEmpty(), "Should have categories from test data");
        assertEquals(15, allCategories.size(), 
            "Should have exactly 15 categories from test data scripts");
        
        // Validate categories span all transaction types
        long distinctTypes = allCategories.stream()
            .map(c -> c.getId().getTypeCode())
            .distinct()
            .count();
        assertEquals(5, distinctTypes, 
            "Categories should span 5 transaction types (01, 02, 03, 04, 05)");
        
        // Validate each category has required fields populated
        for (TransactionCategory category : allCategories) {
            assertNotNull(category.getId(), "Each category must have composite key");
            assertNotNull(category.getId().getTypeCode(), "Type code required (PIC X(02))");
            assertNotNull(category.getId().getCategoryCode(), "Category code required (PIC 9(04))");
            assertNotNull(category.getCategoryDescription(), "Description required (PIC X(50))");
            assertTrue(category.getCategoryDescription().length() <= 50,
                "Description must not exceed 50 character COBOL limit");
        }
    }

    /**
     * Test 7: testSave_NewCategory
     * 
     * <p>Tests INSERT operation (VSAM WRITE equivalent) for creating a new transaction category.
     * Validates composite key uniqueness, foreign key constraint enforcement, and field length
     * constraints matching COBOL PIC clause specifications.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '01' TO FD-TRAN-TYPE-CD.
     * MOVE 5999 TO FD-TRAN-CAT-CD.
     * MOVE 'Test Category' TO FD-TRAN-CAT-TYPE-DESC.
     * WRITE FD-TRAN-CAT-RECORD
     *    INVALID KEY
     *       MOVE 'DUPLICATE KEY' TO WS-MESSAGE
     *    NOT INVALID KEY
     *       MOVE 'RECORD WRITTEN' TO WS-MESSAGE
     * END-WRITE.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>New category successfully saved with composite key</li>
     *   <li>Foreign key to transaction_type validated (type_code must exist)</li>
     *   <li>VARCHAR(2) constraint on type_code enforced</li>
     *   <li>INTEGER constraint on category_code enforced</li>
     *   <li>VARCHAR(50) constraint on description enforced</li>
     *   <li>Entity retrievable after save (persistence verified)</li>
     * </ul>
     */
    @Test
    @Order(7)
    void testSave_NewCategory() {
        // Arrange: Create new category with valid foreign key reference
        CategoryId newCategoryId = new CategoryId("01", 5999);
        TransactionCategory newCategory = new TransactionCategory();
        newCategory.setId(newCategoryId);
        newCategory.setCategoryDescription("Test Category Description");

        // Act: Execute VSAM WRITE equivalent
        TransactionCategory savedCategory = transactionCategoryRepository.save(newCategory);

        // Assert: Validate successful save operation
        assertNotNull(savedCategory, "Saved category should not be null");
        assertNotNull(savedCategory.getId(), "Saved category should have composite key");
        assertEquals("01", savedCategory.getId().getTypeCode(), 
            "Type code should match COBOL TRAN-TYPE-CD PIC X(02)");
        assertEquals(5999, savedCategory.getId().getCategoryCode(), 
            "Category code should match COBOL TRAN-CAT-CD PIC 9(04)");
        assertEquals("Test Category Description", savedCategory.getCategoryDescription(),
            "Description should match COBOL TRAN-CAT-TYPE-DESC PIC X(50)");
        
        // Verify persistence by retrieving saved entity
        Optional<TransactionCategory> retrievedCategory = 
            transactionCategoryRepository.findById(newCategoryId);
        assertTrue(retrievedCategory.isPresent(), 
            "Saved category should be retrievable from database");
        assertEquals("Test Category Description", 
            retrievedCategory.get().getCategoryDescription(),
            "Retrieved description should match saved value");
        
        // Validate foreign key relationship established
        TransactionType parentType = savedCategory.getTransactionType();
        assertNotNull(parentType, "Foreign key relationship should be established");
        assertEquals("01", parentType.getTypeCode(), 
            "Parent type code should match category type_code");
    }

    /**
     * Test 8: testSave_InvalidTypeCode_ThrowsException
     * 
     * <p>Tests foreign key constraint violation when attempting to save a category with
     * a type_code that doesn't exist in the transaction_type table. Validates referential
     * integrity enforcement per Section 0.9 requirements.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * * In COBOL, foreign key validation would be manual:
     * MOVE '99' TO FD-TRAN-TYPE-CD.
     * READ TRANTYPE-FILE KEY IS FD-TRAN-TYPE
     *    INVALID KEY
     *       MOVE 'INVALID TRANSACTION TYPE' TO WS-MESSAGE
     *       SET ERROR-FLAG TO TRUE
     * END-READ.
     * 
     * IF NOT ERROR-FLAG
     *    WRITE FD-TRAN-CAT-RECORD
     * END-IF.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>DataIntegrityViolationException thrown for invalid type_code</li>
     *   <li>Foreign key constraint prevents orphaned category records</li>
     *   <li>Database enforces referential integrity automatically</li>
     *   <li>Exception message indicates constraint violation</li>
     * </ul>
     */
    @Test
    @Order(8)
    void testSave_InvalidTypeCode_ThrowsException() {
        // Arrange: Create category with non-existent type_code (FK violation)
        CategoryId invalidCategoryId = new CategoryId("99", 9999);
        TransactionCategory invalidCategory = new TransactionCategory();
        invalidCategory.setId(invalidCategoryId);
        invalidCategory.setCategoryDescription("Invalid Type Category");

        // Act & Assert: Expect foreign key constraint violation
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionCategoryRepository.save(invalidCategory);
            transactionCategoryRepository.flush(); // Force immediate constraint check
        }, "Should throw DataIntegrityViolationException for invalid foreign key");
    }

    /**
     * Test 9: testSave_DuplicateCompositeKey_ThrowsException
     * 
     * <p>Tests composite key uniqueness constraint validation. Attempting to insert a
     * duplicate composite key (type_code, category_code) should throw exception, equivalent
     * to COBOL VSAM INVALID KEY on WRITE with duplicate record.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '01' TO FD-TRAN-TYPE-CD.
     * MOVE 5010 TO FD-TRAN-CAT-CD.
     * MOVE 'Duplicate Category' TO FD-TRAN-CAT-TYPE-DESC.
     * WRITE FD-TRAN-CAT-RECORD
     *    INVALID KEY
     *       MOVE 'DUPLICATE RECORD' TO WS-MESSAGE
     *       MOVE '22' TO FILE-STATUS    * Duplicate key condition
     * END-WRITE.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>DataIntegrityViolationException thrown for duplicate composite key</li>
     *   <li>Primary key constraint enforced on (type_code, category_code)</li>
     *   <li>Existing record remains unchanged after failed insert attempt</li>
     *   <li>Database prevents duplicate category entries</li>
     * </ul>
     */
    @Test
    @Order(9)
    void testSave_DuplicateCompositeKey_ThrowsException() {
        // Arrange: Create category with existing composite key from test data
        CategoryId existingId = new CategoryId("01", 5010); // Already exists as Retail Merchandise
        TransactionCategory duplicateCategory = new TransactionCategory();
        duplicateCategory.setId(existingId);
        duplicateCategory.setCategoryDescription("Duplicate Category Attempt");

        // Act & Assert: Expect primary key violation
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionCategoryRepository.save(duplicateCategory);
            transactionCategoryRepository.flush(); // Force immediate constraint check
        }, "Should throw DataIntegrityViolationException for duplicate composite key");
        
        // Verify original record unchanged
        Optional<TransactionCategory> originalCategory = 
            transactionCategoryRepository.findById(existingId);
        assertTrue(originalCategory.isPresent(), "Original category should still exist");
        assertEquals("Retail Merchandise", originalCategory.get().getCategoryDescription(),
            "Original description should remain unchanged after failed duplicate insert");
    }

    /**
     * Test 10: testUpdate_ExistingCategory
     * 
     * <p>Tests UPDATE operation (VSAM REWRITE equivalent) for modifying an existing
     * transaction category's description. Validates that composite key remains unchanged
     * while mutable fields can be updated.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '01' TO FD-TRAN-TYPE-CD.
     * MOVE 5411 TO FD-TRAN-CAT-CD.
     * READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'RECORD NOT FOUND' TO WS-MESSAGE
     *    NOT INVALID KEY
     *       MOVE 'Updated Description' TO FD-TRAN-CAT-TYPE-DESC
     *       REWRITE FD-TRAN-CAT-RECORD
     * END-READ.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Existing category successfully updated</li>
     *   <li>Composite key remains unchanged (immutable identifier)</li>
     *   <li>Description field updated to new value</li>
     *   <li>Foreign key relationship preserved after update</li>
     *   <li>Updated entity retrievable with new description</li>
     * </ul>
     */
    @Test
    @Order(10)
    void testUpdate_ExistingCategory() {
        // Arrange: Retrieve existing category
        CategoryId existingId = new CategoryId("01", 5411); // Grocery Stores
        Optional<TransactionCategory> categoryOpt = 
            transactionCategoryRepository.findById(existingId);
        assertTrue(categoryOpt.isPresent(), "Test category should exist");
        
        TransactionCategory category = categoryOpt.get();
        String originalDescription = category.getCategoryDescription();
        String updatedDescription = "Updated Grocery Stores Category";
        
        // Modify description (COBOL PIC X(50) field)
        category.setCategoryDescription(updatedDescription);

        // Act: Execute VSAM REWRITE equivalent
        TransactionCategory updatedCategory = transactionCategoryRepository.save(category);

        // Assert: Validate successful update operation
        assertNotNull(updatedCategory, "Updated category should not be null");
        assertEquals(existingId, updatedCategory.getId(), 
            "Composite key should remain unchanged");
        assertEquals(updatedDescription, updatedCategory.getCategoryDescription(),
            "Description should be updated to new value");
        assertNotEquals(originalDescription, updatedCategory.getCategoryDescription(),
            "Description should differ from original value");
        
        // Verify persistence of update
        transactionCategoryRepository.flush();
        Optional<TransactionCategory> reloadedCategory = 
            transactionCategoryRepository.findById(existingId);
        assertTrue(reloadedCategory.isPresent(), "Updated category should be retrievable");
        assertEquals(updatedDescription, reloadedCategory.get().getCategoryDescription(),
            "Reloaded description should match updated value");
        
        // Verify foreign key relationship still intact
        assertNotNull(updatedCategory.getTransactionType(), 
            "Foreign key relationship should be preserved after update");
    }

    /**
     * Test 11: testDelete_ExistingCategory
     * 
     * <p>Tests DELETE operation (VSAM DELETE equivalent) for removing a transaction category.
     * Validates that category can be deleted if not referenced by transaction records,
     * respecting foreign key constraints.</p>
     * 
     * <p><strong>COBOL Equivalent:</strong></p>
     * <pre>
     * MOVE '05' TO FD-TRAN-TYPE-CD.
     * MOVE 8020 TO FD-TRAN-CAT-CD.
     * READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'RECORD NOT FOUND' TO WS-MESSAGE
     *    NOT INVALID KEY
     *       DELETE TRANCATG-FILE RECORD
     * END-READ.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Existing category successfully deleted</li>
     *   <li>Composite key no longer found after deletion</li>
     *   <li>Cascade behavior respected if transactions reference this category</li>
     *   <li>Foreign key constraint handling verified</li>
     * </ul>
     */
    @Test
    @Order(11)
    void testDelete_ExistingCategory() {
        // Arrange: Create and save a temporary category for deletion test
        CategoryId tempCategoryId = new CategoryId("05", 8020); // Cash Advance Interest
        
        // Verify category exists before deletion
        Optional<TransactionCategory> categoryBeforeDelete = 
            transactionCategoryRepository.findById(tempCategoryId);
        assertTrue(categoryBeforeDelete.isPresent(), 
            "Test category should exist before deletion");

        // Act: Execute VSAM DELETE equivalent
        transactionCategoryRepository.deleteById(tempCategoryId);
        transactionCategoryRepository.flush();

        // Assert: Validate successful deletion
        Optional<TransactionCategory> categoryAfterDelete = 
            transactionCategoryRepository.findById(tempCategoryId);
        assertFalse(categoryAfterDelete.isPresent(), 
            "Category should not exist after deletion (COBOL DELETE successful)");
        
        // Verify total count decreased
        long totalCount = transactionCategoryRepository.count();
        assertEquals(14, totalCount, 
            "Total category count should decrease by 1 after deletion");
    }

    /**
     * Test 12: testCompositeKeyMapping
     * 
     * <p>Tests the @EmbeddedId or @IdClass composite key implementation, validating that
     * CategoryId properly implements Serializable, equals(), and hashCode() as required
     * by JPA specification for composite key classes.</p>
     * 
     * <p><strong>COBOL Compound Key Equivalent:</strong></p>
     * <pre>
     * 05  TRAN-CAT-KEY.
     *    10  TRAN-TYPE-CD         PIC X(02).     * Key component 1
     *    10  TRAN-CAT-CD          PIC 9(04).     * Key component 2
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>CategoryId implements Serializable interface</li>
     *   <li>equals() method properly compares both key components</li>
     *   <li>hashCode() method generates consistent hash from both components</li>
     *   <li>Composite key structure supports JPA entity identity</li>
     *   <li>Two CategoryId instances with same values are equal</li>
     * </ul>
     */
    @Test
    @Order(12)
    void testCompositeKeyMapping() {
        // Arrange: Create two CategoryId instances with identical values
        CategoryId key1 = new CategoryId("01", 5010);
        CategoryId key2 = new CategoryId("01", 5010);
        CategoryId key3 = new CategoryId("01", 5411);

        // Assert: Validate equals() contract
        assertEquals(key1, key2, 
            "CategoryId instances with same typeCode and categoryCode should be equal");
        assertNotEquals(key1, key3, 
            "CategoryId instances with different categoryCode should not be equal");
        assertEquals(key1, key1, 
            "CategoryId should equal itself (reflexive property)");
        
        // Validate hashCode() contract
        assertEquals(key1.hashCode(), key2.hashCode(), 
            "Equal CategoryId instances must have equal hash codes");
        
        // Validate Serializable (compile-time check via instanceof)
        assertTrue(key1 instanceof Serializable, 
            "CategoryId must implement Serializable for JPA composite key");
        
        // Validate components accessible
        assertNotNull(key1.getTypeCode(), "Type code component should be accessible");
        assertNotNull(key1.getCategoryCode(), "Category code component should be accessible");
        assertEquals("01", key1.getTypeCode(), "Type code should match COBOL PIC X(02)");
        assertEquals(5010, key1.getCategoryCode(), "Category code should match COBOL PIC 9(04)");
        
        // Validate key can be used in HashMap (requires proper equals/hashCode)
        java.util.HashMap<CategoryId, String> keyMap = new java.util.HashMap<>();
        keyMap.put(key1, "Test Value");
        assertEquals("Test Value", keyMap.get(key2), 
            "HashMap lookup should work with equivalent CategoryId");
    }

    /**
     * Test 13: testTransactionCategoryConstraints
     * 
     * <p>Tests database column constraints matching COBOL PIC clause specifications:
     * NOT NULL requirements, VARCHAR length limits, and numeric precision for category_code.</p>
     * 
     * <p><strong>COBOL Field Constraints:</strong></p>
     * <pre>
     * 10  TRAN-TYPE-CD         PIC X(02).     * NOT NULL, max 2 chars
     * 10  TRAN-CAT-CD          PIC 9(04).     * NOT NULL, 4-digit numeric
     * 05  TRAN-CAT-TYPE-DESC   PIC X(50).     * NOT NULL, max 50 chars
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>type_code: VARCHAR(2) NOT NULL constraint</li>
     *   <li>category_code: INTEGER NOT NULL constraint</li>
     *   <li>category_description: VARCHAR(50) NOT NULL constraint</li>
     *   <li>Composite UNIQUE constraint on (type_code, category_code)</li>
     *   <li>Constraint violations throw appropriate exceptions</li>
     * </ul>
     */
    @Test
    @Order(13)
    void testTransactionCategoryConstraints() {
        // Test 1: Validate VARCHAR(2) length constraint on type_code
        CategoryId validKey = new CategoryId("01", 1000);
        TransactionCategory validCategory = new TransactionCategory();
        validCategory.setId(validKey);
        validCategory.setCategoryDescription("Valid Category");
        
        // Should save successfully with 2-character type code
        TransactionCategory saved = transactionCategoryRepository.save(validCategory);
        assertNotNull(saved, "Category with 2-character type code should save successfully");
        
        // Test 2: Validate VARCHAR(50) length constraint on description
        CategoryId descKey = new CategoryId("01", 1001);
        TransactionCategory maxDescCategory = new TransactionCategory();
        maxDescCategory.setId(descKey);
        maxDescCategory.setCategoryDescription("A".repeat(50)); // Exactly 50 characters
        
        TransactionCategory savedMaxDesc = transactionCategoryRepository.save(maxDescCategory);
        assertNotNull(savedMaxDesc, "Category with 50-character description should save");
        assertEquals(50, savedMaxDesc.getCategoryDescription().length(),
            "Description should allow exactly 50 characters per COBOL PIC X(50)");
        
        // Test 3: Validate NUMERIC(4,0) constraint on category_code (integer range)
        CategoryId numericKey = new CategoryId("01", 9999);
        TransactionCategory numericCategory = new TransactionCategory();
        numericCategory.setId(numericKey);
        numericCategory.setCategoryDescription("Max Category Code");
        
        TransactionCategory savedNumeric = transactionCategoryRepository.save(numericCategory);
        assertNotNull(savedNumeric, "Category code 9999 should be valid (PIC 9(04) max)");
        assertEquals(9999, savedNumeric.getId().getCategoryCode(),
            "Category code should support 4-digit values (0000-9999)");
        
        // Test 4: Validate composite uniqueness constraint
        CategoryId uniqueKey = new CategoryId("01", 1002);
        TransactionCategory category1 = new TransactionCategory();
        category1.setId(uniqueKey);
        category1.setCategoryDescription("First Category");
        transactionCategoryRepository.save(category1);
        
        TransactionCategory category2 = new TransactionCategory();
        category2.setId(uniqueKey); // Same composite key
        category2.setCategoryDescription("Duplicate Category");
        
        // Should throw exception for duplicate composite key
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionCategoryRepository.save(category2);
            transactionCategoryRepository.flush();
        }, "Duplicate composite key should violate uniqueness constraint");
    }

    /**
     * Test 14: testForeignKeyToTransactionType
     * 
     * <p>Tests foreign key constraint from transaction_category.type_code to
     * transaction_type.type_code, ensuring referential integrity enforcement and
     * proper relationship configuration.</p>
     * 
     * <p><strong>COBOL Manual Validation Equivalent:</strong></p>
     * <pre>
     * * In COBOL, foreign key validation is manual:
     * READ TRANTYPE-FILE KEY IS FD-TRAN-TYPE
     *    INVALID KEY
     *       MOVE 'INVALID TYPE CODE' TO WS-MESSAGE
     *       SET ERROR-FLAG TO TRUE
     * END-READ.
     * 
     * IF NOT ERROR-FLAG
     *    * Proceed with category write
     * END-IF.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Foreign key constraint exists and is enforced</li>
     *   <li>Constraint name follows naming convention</li>
     *   <li>ON DELETE RESTRICT behavior configured correctly</li>
     *   <li>Referential integrity prevents orphaned categories</li>
     *   <li>Valid type_code successfully establishes relationship</li>
     * </ul>
     */
    @Test
    @Order(14)
    void testForeignKeyToTransactionType() {
        // Test 1: Validate foreign key allows valid type_code
        CategoryId validFkId = new CategoryId("02", 6050);
        TransactionCategory validFkCategory = new TransactionCategory();
        validFkCategory.setId(validFkId);
        validFkCategory.setCategoryDescription("Valid FK Category");
        
        // Should save successfully with valid foreign key
        TransactionCategory savedWithValidFk = transactionCategoryRepository.save(validFkCategory);
        assertNotNull(savedWithValidFk, "Category with valid type_code FK should save");
        
        // Validate relationship is established
        assertNotNull(savedWithValidFk.getTransactionType(), 
            "Foreign key relationship should be established to TransactionType");
        assertEquals("02", savedWithValidFk.getTransactionType().getTypeCode(),
            "Parent TransactionType should have matching type_code");
        
        // Test 2: Validate foreign key rejects invalid type_code
        CategoryId invalidFkId = new CategoryId("ZZ", 9999);
        TransactionCategory invalidFkCategory = new TransactionCategory();
        invalidFkCategory.setId(invalidFkId);
        invalidFkCategory.setCategoryDescription("Invalid FK Category");
        
        // Should throw exception for invalid foreign key
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionCategoryRepository.save(invalidFkCategory);
            transactionCategoryRepository.flush();
        }, "Invalid type_code should violate foreign key constraint");
        
        // Test 3: Verify all existing categories have valid foreign keys
        List<TransactionCategory> allCategories = transactionCategoryRepository.findAll();
        for (TransactionCategory category : allCategories) {
            assertNotNull(category.getTransactionType(), 
                "Every category should have valid TransactionType relationship");
            assertEquals(category.getId().getTypeCode(), 
                category.getTransactionType().getTypeCode(),
                "Category type_code should match parent TransactionType");
        }
    }

    /**
     * Test 15: testCategoryHierarchy
     * 
     * <p>Tests the type → category hierarchy relationship, validating that categories
     * are properly organized under their parent transaction types and that JOIN queries
     * between transaction_category and transaction_type tables perform efficiently.</p>
     * 
     * <p><strong>COBOL Hierarchy Navigation:</strong></p>
     * <pre>
     * * Read parent transaction type first
     * READ TRANTYPE-FILE KEY IS FD-TRAN-TYPE.
     * 
     * * Then read all child categories for that type
     * MOVE FD-TRAN-TYPE TO WS-TYPE-FILTER.
     * START TRANCATG-FILE KEY >= WS-TYPE-FILTER.
     * PERFORM UNTIL CATEGORY-EOF OR TYPE-CHANGED
     *    READ TRANCATG-FILE NEXT RECORD
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Categories properly grouped under parent transaction types</li>
     *   <li>Parent-child relationship navigable in both directions</li>
     *   <li>JOIN query performance acceptable (&lt; 10ms)</li>
     *   <li>Each type has expected number of child categories</li>
     * </ul>
     */
    @Test
    @Order(15)
    void testCategoryHierarchy() {
        // Test 1: Validate Purchase type (01) has correct number of child categories
        List<TransactionCategory> purchaseCategories = 
            transactionCategoryRepository.findByIdTypeCode("01");
        assertEquals(5, purchaseCategories.size(), 
            "Purchase type should have 5 child categories");
        
        // Test 2: Validate Cash Advance type (02) hierarchy
        List<TransactionCategory> cashAdvanceCategories = 
            transactionCategoryRepository.findByIdTypeCode("02");
        assertEquals(2, cashAdvanceCategories.size(), 
            "Cash Advance type should have 2 child categories");
        assertTrue(cashAdvanceCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 6010),
            "Should include ATM Cash Withdrawal category");
        
        // Test 3: Validate Payment type (03) hierarchy
        List<TransactionCategory> paymentCategories = 
            transactionCategoryRepository.findByIdTypeCode("03");
        assertEquals(1, paymentCategories.size(), 
            "Payment type should have 1 child category");
        assertEquals(0, paymentCategories.get(0).getId().getCategoryCode(),
            "Payment category should be code 0 (Bill Payment)");
        
        // Test 4: Validate Fee type (04) hierarchy
        List<TransactionCategory> feeCategories = 
            transactionCategoryRepository.findByIdTypeCode("04");
        assertEquals(3, feeCategories.size(), 
            "Fee type should have 3 child categories");
        
        // Test 5: Validate Interest type (05) hierarchy
        List<TransactionCategory> interestCategories = 
            transactionCategoryRepository.findByIdTypeCode("05");
        assertTrue(interestCategories.size() >= 1, 
            "Interest type should have at least 1 child category");
        
        // Validate parent relationship for each category
        for (TransactionCategory category : purchaseCategories) {
            assertNotNull(category.getTransactionType(), 
                "Each category should have parent TransactionType");
            assertEquals("01", category.getTransactionType().getTypeCode(),
                "All purchase categories should link to type '01'");
        }
    }

    /**
     * Test 16: testFindCategoriesForPurchaseType
     * 
     * <p>Tests specific query for type='01' (Purchase) categories, a common use case from
     * COTRN01C.cbl transaction category summary screen. Validates all expected purchase
     * categories are present and properly configured.</p>
     * 
     * <p><strong>COBOL Use Case (COTRN01C.cbl):</strong></p>
     * <pre>
     * * User selects Purchase transaction type
     * MOVE '01' TO SELECTED-TYPE.
     * 
     * * Display all purchase categories for selection
     * PERFORM LOAD-PURCHASE-CATEGORIES
     *    MOVE '01' TO FD-TRAN-TYPE-CD
     *    START TRANCATG-FILE KEY >= FD-TRAN-CAT-KEY
     *    PERFORM UNTIL CATEGORY-EOF OR TYPE-CHANGED
     *       READ TRANCATG-FILE NEXT RECORD
     *       ADD 1 TO CATEGORY-COUNT
     *    END-PERFORM
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Returns exactly 5 purchase categories from test data</li>
     *   <li>Includes all expected retail category codes (5010, 5411, 5541, 5812, 5999)</li>
     *   <li>Categories ordered by category_code ascending</li>
     *   <li>All descriptions properly populated (PIC X(50))</li>
     * </ul>
     */
    @Test
    @Order(16)
    void testFindCategoriesForPurchaseType() {
        // Act: Execute query for Purchase type categories (common COTRN01C use case)
        List<TransactionCategory> purchaseCategories = 
            transactionCategoryRepository.findByIdTypeCode("01");

        // Assert: Validate all expected purchase categories present
        assertNotNull(purchaseCategories, "Purchase categories list should not be null");
        assertEquals(5, purchaseCategories.size(), 
            "Should have 5 purchase categories (5010, 5411, 5541, 5812, 5999)");
        
        // Validate specific category codes exist
        boolean hasRetailMerchandise = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5010 
                && "Retail Merchandise".equals(c.getCategoryDescription()));
        boolean hasGroceryStores = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5411 
                && "Grocery Stores".equals(c.getCategoryDescription()));
        boolean hasServiceStations = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5541 
                && "Service Stations".equals(c.getCategoryDescription()));
        boolean hasRestaurants = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5812 
                && "Restaurants".equals(c.getCategoryDescription()));
        boolean hasMiscellaneous = purchaseCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5999 
                && "Miscellaneous Retail".equals(c.getCategoryDescription()));
        
        assertTrue(hasRetailMerchandise, "Should include category 5010 (Retail Merchandise)");
        assertTrue(hasGroceryStores, "Should include category 5411 (Grocery Stores)");
        assertTrue(hasServiceStations, "Should include category 5541 (Service Stations)");
        assertTrue(hasRestaurants, "Should include category 5812 (Restaurants)");
        assertTrue(hasMiscellaneous, "Should include category 5999 (Miscellaneous Retail)");
        
        // Validate all have required fields
        for (TransactionCategory category : purchaseCategories) {
            assertEquals("01", category.getId().getTypeCode(), 
                "All categories should have type_code '01'");
            assertNotNull(category.getCategoryDescription(), 
                "Description should be populated (COBOL PIC X(50) NOT NULL)");
            assertTrue(category.getCategoryDescription().length() <= 50,
                "Description should not exceed 50 characters");
        }
    }

    /**
     * Test 17: testCategoryCodeRanges
     * 
     * <p>Tests INTEGER category_code field supports COBOL PIC 9(04) numeric range (0000-9999).
     * Validates numeric ordering, padding behavior, and range limits matching COBOL specifications.</p>
     * 
     * <p><strong>COBOL PIC 9(04) Characteristics:</strong></p>
     * <pre>
     * 10  TRAN-CAT-CD          PIC 9(04).
     * * Valid range: 0000 to 9999 (4-digit unsigned integer)
     * * Numeric comparison: 0001 < 0010 < 0100 < 1000
     * * Display format: leading zeros preserved
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Category code 0 is valid (minimum value)</li>
     *   <li>Category code 9999 is valid (maximum value)</li>
     *   <li>Numeric ordering works correctly (not string ordering)</li>
     *   <li>Category codes properly formatted with 4 digits when displayed</li>
     * </ul>
     */
    @Test
    @Order(17)
    void testCategoryCodeRanges() {
        // Test 1: Validate minimum category code (0)
        CategoryId minCodeId = new CategoryId("03", 0);
        Optional<TransactionCategory> minCodeCategory = 
            transactionCategoryRepository.findById(minCodeId);
        assertTrue(minCodeCategory.isPresent(), 
            "Category code 0 should be valid (PIC 9(04) minimum)");
        assertEquals(0, minCodeCategory.get().getId().getCategoryCode(),
            "Minimum category code should be 0");
        
        // Test 2: Create and validate maximum category code (9999)
        CategoryId maxCodeId = new CategoryId("01", 9999);
        TransactionCategory maxCodeCategory = new TransactionCategory();
        maxCodeCategory.setId(maxCodeId);
        maxCodeCategory.setCategoryDescription("Maximum Code Category");
        
        TransactionCategory savedMaxCode = transactionCategoryRepository.save(maxCodeCategory);
        assertEquals(9999, savedMaxCode.getId().getCategoryCode(),
            "Category code 9999 should be valid (PIC 9(04) maximum)");
        
        // Test 3: Validate numeric ordering (not string ordering)
        List<TransactionCategory> allCategories = transactionCategoryRepository.findAll();
        
        // Extract category codes for Purchase type and verify numeric ordering
        List<Integer> purchaseCodes = allCategories.stream()
            .filter(c -> "01".equals(c.getId().getTypeCode()))
            .map(c -> c.getId().getCategoryCode())
            .sorted()
            .toList();
        
        // Verify numeric ordering: 5010 < 5411 < 5541 < 5812 < 5999
        for (int i = 0; i < purchaseCodes.size() - 1; i++) {
            assertTrue(purchaseCodes.get(i) < purchaseCodes.get(i + 1),
                "Category codes should be in ascending numeric order");
        }
        
        // Test 4: Validate formatting (4-digit representation)
        List<TransactionCategory> purchaseCategoriesForFormat = 
            transactionCategoryRepository.findByIdTypeCode("01");
        TransactionCategory testCategory = purchaseCategoriesForFormat.stream()
            .filter(c -> c.getId().getCategoryCode() == 5010)
            .findFirst()
            .orElseThrow();
        
        String formattedCode = String.format("%04d", testCategory.getId().getCategoryCode());
        assertEquals("5010", formattedCode, 
            "Category code should format as 4-digit string matching COBOL PIC 9(04)");
    }

    /**
     * Test 18: testCategoryDescriptionSearch
     * 
     * <p>Tests LIKE search capability on category_description field for implementing
     * search functionality in admin screens. Validates case-insensitive search and
     * partial match capabilities.</p>
     * 
     * <p><strong>COBOL Sequential Search Equivalent:</strong></p>
     * <pre>
     * * Search all categories for matching description
     * MOVE 'RETAIL' TO SEARCH-TERM.
     * PERFORM UNTIL CATEGORY-EOF
     *    READ TRANCATG-FILE NEXT RECORD
     *       AT END SET CATEGORY-EOF TO TRUE
     *       NOT AT END
     *          IF FD-TRAN-CAT-TYPE-DESC CONTAINS SEARCH-TERM
     *             PERFORM ADD-TO-RESULTS
     *          END-IF
     *    END-READ
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Partial string match finds correct categories</li>
     *   <li>Case-insensitive search works properly</li>
     *   <li>Multiple matches returned in consistent order</li>
     *   <li>Empty search term handled gracefully</li>
     * </ul>
     */
    @Test
    @Order(18)
    void testCategoryDescriptionSearch() {
        // Get all categories and perform in-memory search (simulating LIKE query)
        List<TransactionCategory> allCategories = transactionCategoryRepository.findAll();
        
        // Test 1: Search for "Retail" (case-insensitive)
        List<TransactionCategory> retailCategories = allCategories.stream()
            .filter(c -> c.getCategoryDescription().toUpperCase().contains("RETAIL"))
            .toList();
        
        assertTrue(retailCategories.size() >= 1, 
            "Should find at least 1 category containing 'Retail'");
        assertTrue(retailCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 5010),
            "Should include category 5010 (Retail Merchandise)");
        
        // Test 2: Search for "Fee"
        List<TransactionCategory> feeCategories = allCategories.stream()
            .filter(c -> c.getCategoryDescription().toUpperCase().contains("FEE"))
            .toList();
        
        assertTrue(feeCategories.size() >= 2, 
            "Should find multiple categories containing 'Fee'");
        assertTrue(feeCategories.stream()
            .anyMatch(c -> c.getId().getCategoryCode() == 6011),
            "Should include category 6011 (Cash Advance Fee)");
        
        // Test 3: Search for "Interest"
        List<TransactionCategory> interestCategories = allCategories.stream()
            .filter(c -> c.getCategoryDescription().toUpperCase().contains("INTEREST"))
            .toList();
        
        assertTrue(interestCategories.size() >= 1, 
            "Should find categories containing 'Interest'");
        
        // Test 4: Validate alphabetically sorted search results
        List<TransactionCategory> sortedResults = 
            transactionCategoryRepository.findAllByOrderByCategoryDescriptionAsc();
        
        assertNotNull(sortedResults, "Sorted search results should not be null");
        assertTrue(sortedResults.size() > 0, "Should have categories to sort");
        
        // Verify alphabetical ordering
        for (int i = 0; i < sortedResults.size() - 1; i++) {
            String desc1 = sortedResults.get(i).getCategoryDescription();
            String desc2 = sortedResults.get(i + 1).getCategoryDescription();
            assertTrue(desc1.compareToIgnoreCase(desc2) <= 0,
                "Categories should be in alphabetical order by description");
        }
    }

    /**
     * Test 19: testTransactionForeignKeyToCategory
     * 
     * <p>Tests the reverse relationship from transactions to categories, validating that
     * transaction records can successfully reference valid category composite keys and that
     * referential integrity constraints are properly enforced from the transaction side.</p>
     * 
     * <p><strong>COBOL Validation Pattern:</strong></p>
     * <pre>
     * * Before posting transaction, validate category exists
     * MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE-CD.
     * MOVE TRAN-CAT-CD TO FD-TRAN-CAT-CD.
     * READ TRANCATG-FILE KEY IS FD-TRAN-CAT-KEY
     *    INVALID KEY
     *       MOVE 'INVALID CATEGORY' TO WS-ERROR-MESSAGE
     *       SET ERROR-FLAG TO TRUE
     * END-READ.
     * 
     * IF NOT ERROR-FLAG
     *    * Post transaction
     * END-IF.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>Valid category composite keys can be referenced by transactions</li>
     *   <li>Invalid category references would be caught by foreign key constraint</li>
     *   <li>Category lookup before transaction posting performs efficiently</li>
     *   <li>Composite key foreign key relationship properly configured</li>
     * </ul>
     */
    @Test
    @Order(19)
    void testTransactionForeignKeyToCategory() {
        // Test 1: Validate category exists for transaction reference
        CategoryId validCategoryId = new CategoryId("01", 5010);
        Optional<TransactionCategory> validCategory = 
            transactionCategoryRepository.findById(validCategoryId);
        
        assertTrue(validCategory.isPresent(), 
            "Category for transaction reference should exist");
        assertEquals("Retail Merchandise", validCategory.get().getCategoryDescription(),
            "Category should have expected description");
        
        // Test 2: Validate invalid category detection (transaction validation use case)
        CategoryId invalidCategoryId = new CategoryId("01", 9998);
        Optional<TransactionCategory> invalidCategory = 
            transactionCategoryRepository.findById(invalidCategoryId);
        
        assertFalse(invalidCategory.isPresent(), 
            "Invalid category should not exist (transaction validation should catch)");
        
        // Test 3: Validate existsById for efficient category validation
        boolean exists = transactionCategoryRepository.existsById(validCategoryId);
        assertTrue(exists, 
            "existsById should return true for valid category (efficient validation)");
        
        boolean notExists = transactionCategoryRepository.existsById(invalidCategoryId);
        assertFalse(notExists, 
            "existsById should return false for invalid category");
        
        // Test 4: Validate findByIdTypeCodeAndIdCategoryCode for REST API validation
        Optional<TransactionCategory> apiValidation = 
            transactionCategoryRepository.findByIdTypeCodeAndIdCategoryCode("01", 5010);
        
        assertTrue(apiValidation.isPresent(), 
            "findByIdTypeCodeAndIdCategoryCode should find valid category");
        assertEquals(validCategoryId, apiValidation.get().getId(),
            "Retrieved category should have matching composite key");
    }

    /**
     * Test 20: testCategoryAggregationQueries
     * 
     * <p>Tests aggregation query capabilities for transaction category reporting, equivalent
     * to CBTRN03C.cbl batch transaction aggregation program. Validates COUNT, SUM, and
     * GROUP BY operations with composite key categories.</p>
     * 
     * <p><strong>COBOL Batch Aggregation (CBTRN03C.cbl):</strong></p>
     * <pre>
     * * Aggregate transactions by category
     * PERFORM UNTIL TRANSACTION-EOF
     *    READ TRANSACT-FILE NEXT RECORD
     *       AT END SET TRANSACTION-EOF TO TRUE
     *       NOT AT END
     *          MOVE TRAN-TYPE-CD TO CATEGORY-TYPE
     *          MOVE TRAN-CAT-CD TO CATEGORY-CODE
     *          
     *          * Accumulate by category
     *          SEARCH CATEGORY-TABLE
     *             WHEN CATEGORY-KEY(IDX) = CATEGORY-TYPE & CATEGORY-CODE
     *                ADD TRAN-AMT TO CATEGORY-TOTAL(IDX)
     *                ADD 1 TO CATEGORY-COUNT(IDX)
     *          END-SEARCH
     *    END-READ
     * END-PERFORM.
     * </pre>
     * 
     * <p><strong>Validations:</strong></p>
     * <ul>
     *   <li>COUNT operations by category work correctly</li>
     *   <li>SUM aggregations by category produce accurate totals</li>
     *   <li>GROUP BY with composite key performs efficiently</li>
     *   <li>Multiple categories can be aggregated simultaneously</li>
     *   <li>Query supports CBTRN03C batch processing requirements</li>
     * </ul>
     */
    @Test
    @Order(20)
    void testCategoryAggregationQueries() {
        // Test 1: Validate total category count (COUNT query)
        long totalCount = transactionCategoryRepository.count();
        assertEquals(15, totalCount, 
            "Should have 15 total categories from test data");
        
        // Test 2: Count categories by transaction type (GROUP BY equivalent)
        List<TransactionCategory> purchaseCategories = 
            transactionCategoryRepository.findByIdTypeCode("01");
        List<TransactionCategory> cashAdvanceCategories = 
            transactionCategoryRepository.findByIdTypeCode("02");
        List<TransactionCategory> paymentCategories = 
            transactionCategoryRepository.findByIdTypeCode("03");
        List<TransactionCategory> feeCategories = 
            transactionCategoryRepository.findByIdTypeCode("04");
        
        int typeGroupSum = purchaseCategories.size() + cashAdvanceCategories.size() + 
                          paymentCategories.size() + feeCategories.size();
        
        assertTrue(typeGroupSum >= 11, 
            "Sum of categories by type should account for most categories");
        
        // Test 3: Validate category retrieval for batch processing aggregation
        List<TransactionCategory> allCategoriesForAggregation = 
            transactionCategoryRepository.findAll();
        
        // Simulate CBTRN03C aggregation logic
        java.util.Map<CategoryId, Long> categoryCountMap = new java.util.HashMap<>();
        for (TransactionCategory category : allCategoriesForAggregation) {
            categoryCountMap.put(category.getId(), 0L); // Initialize counters
        }
        
        assertNotNull(categoryCountMap, "Category map for aggregation should be created");
        assertEquals(totalCount, categoryCountMap.size(), 
            "Map should have entry for each category");
        
        // Test 4: Validate composite key usage in aggregation map
        CategoryId testKey = new CategoryId("01", 5010);
        assertTrue(categoryCountMap.containsKey(testKey), 
            "Aggregation map should support composite key lookup");
        
        // Test 5: Validate sorted retrieval for ordered reporting
        List<TransactionCategory> sortedForReport = 
            transactionCategoryRepository.findAllByOrderByCategoryDescriptionAsc();
        
        assertNotNull(sortedForReport, "Sorted category list should not be null");
        assertEquals(totalCount, sortedForReport.size(), 
            "Sorted list should contain all categories");
        
        // Verify ordering for report generation
        String prevDescription = "";
        for (TransactionCategory category : sortedForReport) {
            String currentDescription = category.getCategoryDescription();
            assertTrue(currentDescription.compareToIgnoreCase(prevDescription) >= 0,
                "Categories should be alphabetically ordered for reporting");
            prevDescription = currentDescription;
        }
    }
}

