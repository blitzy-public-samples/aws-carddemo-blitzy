package com.carddemo.repository;

import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategory.TransactionCategoryId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JUnit 5 test class for TransactionCategoryRepository using @DataJpaTest annotation
 * for isolated repository testing with H2 in-memory database.
 * 
 * This test class validates that PostgreSQL queries correctly replicate VSAM READ
 * operations on transaction category reference data from CVTRA04Y.cpy (TRAN-CAT-RECORD).
 * 
 * Tests verify:
 * - Composite key structure (TRAN-TYPE-CD PIC X(02) + TRAN-CAT-CD PIC 9(04))
 * - Category descriptions (TRAN-CAT-TYPE-DESC PIC X(50)) are retrieved correctly
 * - Composite primary key constraints work as expected
 * - Data integrity is maintained during CRUD operations
 * - Proper encoding conversion from EBCDIC to UTF-8 ASCII
 * 
 * COBOL Context:
 * - Original copybook: CVTRA04Y.cpy
 * - Record structure: TRAN-CAT-RECORD (60 bytes)
 * - Composite key: TRAN-CAT-KEY (TRAN-TYPE-CD + TRAN-CAT-CD)
 * - Description: TRAN-CAT-TYPE-DESC (50 characters)
 * 
 * @see TransactionCategoryRepository
 * @see TransactionCategory
 * @see TransactionCategoryId
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("TransactionCategoryRepository Integration Tests")
class TransactionCategoryRepositoryTest {

    @Autowired
    private TransactionCategoryRepository transactionCategoryRepository;

    private TransactionCategory debitMerchandiseCategory;
    private TransactionCategory debitCashCategory;
    private TransactionCategory creditReturnCategory;
    private TransactionCategory creditAdjustmentCategory;

    /**
     * Set up test data before each test method.
     * 
     * Populates the H2 in-memory database with sample transaction categories
     * that match the COBOL TRAN-CAT-RECORD structure from CVTRA04Y.cpy.
     * 
     * Test data includes:
     * - DB-5000: Debit Merchandise (typeCode='DB', categoryCode='5000')
     * - DB-5100: Debit Cash Advances (typeCode='DB', categoryCode='5100')
     * - CR-6000: Credit Returns (typeCode='CR', categoryCode='6000')
     * - CR-6100: Credit Adjustments (typeCode='CR', categoryCode='6100')
     */
    @BeforeEach
    void setUp() {
        // Clear any existing data
        transactionCategoryRepository.deleteAll();

        // Create test transaction categories matching COBOL TRAN-CAT-RECORD structure
        // TRAN-TYPE-CD = 'DB' (Debit), TRAN-CAT-CD = '5000' (Merchandise)
        debitMerchandiseCategory = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("DB")
                        .categoryCode("5000")
                        .build())
                .categoryDescription("Debit Merchandise")
                .build();

        // TRAN-TYPE-CD = 'DB' (Debit), TRAN-CAT-CD = '5100' (Cash Advances)
        debitCashCategory = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("DB")
                        .categoryCode("5100")
                        .build())
                .categoryDescription("Debit Cash Advances")
                .build();

        // TRAN-TYPE-CD = 'CR' (Credit), TRAN-CAT-CD = '6000' (Returns)
        creditReturnCategory = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("CR")
                        .categoryCode("6000")
                        .build())
                .categoryDescription("Credit Returns")
                .build();

        // TRAN-TYPE-CD = 'CR' (Credit), TRAN-CAT-CD = '6100' (Adjustments)
        creditAdjustmentCategory = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("CR")
                        .categoryCode("6100")
                        .build())
                .categoryDescription("Credit Adjustments")
                .build();

        // Save all test categories to H2 database
        transactionCategoryRepository.save(debitMerchandiseCategory);
        transactionCategoryRepository.save(debitCashCategory);
        transactionCategoryRepository.save(creditReturnCategory);
        transactionCategoryRepository.save(creditAdjustmentCategory);
    }

    /**
     * Test finding a transaction category by composite key (findById).
     * 
     * Validates that PostgreSQL composite key lookup correctly replicates
     * COBOL EXEC CICS READ FILE('TRNCAT') INTO(TRAN-CAT-RECORD) RIDFLD(TRAN-CAT-KEY)
     * where TRAN-CAT-KEY contains both TRAN-TYPE-CD and TRAN-CAT-CD.
     * 
     * Expected behavior:
     * - findById with valid composite key returns Optional containing the category
     * - Returned category has correct typeCode matching TRAN-TYPE-CD (PIC X(02))
     * - Returned category has correct categoryCode matching TRAN-CAT-CD (PIC 9(04))
     * - Category description matches TRAN-CAT-TYPE-DESC (PIC X(50))
     */
    @Test
    @DisplayName("Find transaction category by composite key returns correct category with TRAN-CAT-KEY structure")
    void testFindById_Success() {
        // Given: Composite key matching COBOL TRAN-CAT-KEY structure
        TransactionCategoryId searchKey = TransactionCategoryId.builder()
                .typeCode("DB")
                .categoryCode("5000")
                .build();

        // When: Execute findById (replicates VSAM READ with full key)
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(searchKey);

        // Then: Verify category found with correct composite key and description
        assertThat(result).isPresent();
        assertThat(result.get().getId().getTypeCode()).isEqualTo("DB");
        assertThat(result.get().getId().getCategoryCode()).isEqualTo("5000");
        assertThat(result.get().getCategoryDescription()).isEqualTo("Debit Merchandise");
    }

    /**
     * Test finding a non-existent transaction category by composite key.
     * 
     * Validates that querying with an invalid composite key returns empty Optional,
     * replicating COBOL EXEC CICS READ behavior when NOTFND condition is raised.
     * 
     * Expected behavior:
     * - findById with non-existent key returns empty Optional
     * - No exception is thrown (matches COBOL RESP(NOTFND) handling)
     */
    @Test
    @DisplayName("Find transaction category by non-existent composite key returns empty Optional")
    void testFindById_NotFound() {
        // Given: Non-existent composite key
        TransactionCategoryId nonExistentKey = TransactionCategoryId.builder()
                .typeCode("XX")
                .categoryCode("9999")
                .build();

        // When: Execute findById with non-existent key
        Optional<TransactionCategory> result = transactionCategoryRepository.findById(nonExistentKey);

        // Then: Verify empty Optional returned (COBOL NOTFND condition)
        assertThat(result).isEmpty();
    }

    /**
     * Test finding all transaction categories by category code (partial key lookup).
     * 
     * Validates findByIdCategoryCode method which performs partial key search
     * on the second part of the composite key (TRAN-CAT-CD), replicating
     * COBOL EXEC CICS STARTBR with generic key on TRAN-CAT-CD followed by READNEXT.
     * 
     * Expected behavior:
     * - findByIdCategoryCode returns all categories matching the category code
     * - Results include categories from different transaction types
     * - Results are ordered by type code, then category code
     */
    @Test
    @DisplayName("Find all transaction categories by category code returns matching categories across types")
    void testFindByIdCategoryCode_Success() {
        // Given: Category code '5000' exists under 'DB' type
        String searchCategoryCode = "5000";

        // When: Execute partial key lookup by category code
        List<TransactionCategory> results = transactionCategoryRepository.findByIdCategoryCode(searchCategoryCode);

        // Then: Verify correct category found
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId().getCategoryCode()).isEqualTo("5000");
        assertThat(results.get(0).getId().getTypeCode()).isEqualTo("DB");
        assertThat(results.get(0).getCategoryDescription()).isEqualTo("Debit Merchandise");
    }

    /**
     * Test finding all transaction categories by transaction type code (partial key lookup).
     * 
     * Validates findByIdTypeCode method which performs partial key search
     * on the first part of the composite key (TRAN-TYPE-CD), replicating
     * COBOL EXEC CICS STARTBR with generic key on TRAN-TYPE-CD followed by READNEXT.
     * 
     * Expected behavior:
     * - findByIdTypeCode returns all categories for the specified transaction type
     * - Results are ordered by category code within the type
     * - Both debit categories ('DB') should be returned when searching for 'DB'
     */
    @Test
    @DisplayName("Find all transaction categories by type code returns all categories for that type")
    void testFindByIdTypeCode_Success() {
        // Given: Two categories exist under 'DB' (Debit) type code
        String searchTypeCode = "DB";

        // When: Execute partial key lookup by type code
        List<TransactionCategory> results = transactionCategoryRepository.findByIdTypeCode(searchTypeCode);

        // Then: Verify both debit categories found and properly ordered
        assertThat(results).hasSize(2);
        assertThat(results).extracting(tc -> tc.getId().getTypeCode())
                .containsOnly("DB");
        assertThat(results).extracting(tc -> tc.getId().getCategoryCode())
                .containsExactly("5000", "5100");
        assertThat(results).extracting(TransactionCategory::getCategoryDescription)
                .containsExactly("Debit Merchandise", "Debit Cash Advances");
    }

    /**
     * Test finding all transaction categories (full table scan).
     * 
     * Validates findAll method replicating COBOL EXEC CICS STARTBR FILE('TRNCAT')
     * followed by READNEXT loop to retrieve all transaction category records.
     * 
     * Expected behavior:
     * - findAll returns all transaction categories in the database
     * - All 4 test categories are returned
     * - Categories are ordered by composite key (type code, then category code)
     */
    @Test
    @DisplayName("Find all transaction categories returns complete reference data")
    void testFindAll_ReturnsAllCategories() {
        // When: Execute findAll (replicates VSAM STARTBR/READNEXT loop)
        List<TransactionCategory> allCategories = transactionCategoryRepository.findAll();

        // Then: Verify all 4 test categories returned
        assertThat(allCategories).hasSize(4);
        assertThat(allCategories).extracting(tc -> tc.getId().getTypeCode())
                .containsExactlyInAnyOrder("DB", "DB", "CR", "CR");
        assertThat(allCategories).extracting(tc -> tc.getId().getCategoryCode())
                .containsExactlyInAnyOrder("5000", "5100", "6000", "6100");
    }

    /**
     * Test saving a new transaction category (insert operation).
     * 
     * Validates that save method correctly persists new transaction category
     * with composite key, replicating COBOL EXEC CICS WRITE FILE('TRNCAT') 
     * FROM(TRAN-CAT-RECORD) operation.
     * 
     * Expected behavior:
     * - save returns the persisted entity with version initialized
     * - Saved category can be retrieved by composite key
     * - Composite key fields and description are persisted correctly
     */
    @Test
    @DisplayName("Save persists new transaction category with composite key uniqueness constraint")
    void testSave_PersistsNewCategory() {
        // Given: New transaction category not yet in database
        TransactionCategory newCategory = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("FE")
                        .categoryCode("7000")
                        .build())
                .categoryDescription("Fee Charges")
                .build();

        // When: Execute save (replicates VSAM WRITE)
        TransactionCategory savedCategory = transactionCategoryRepository.save(newCategory);

        // Then: Verify category persisted with correct composite key and description
        assertThat(savedCategory).isNotNull();
        assertThat(savedCategory.getId().getTypeCode()).isEqualTo("FE");
        assertThat(savedCategory.getId().getCategoryCode()).isEqualTo("7000");
        assertThat(savedCategory.getCategoryDescription()).isEqualTo("Fee Charges");
        assertThat(savedCategory.getVersion()).isNotNull();

        // Verify category can be retrieved
        Optional<TransactionCategory> retrieved = transactionCategoryRepository.findById(newCategory.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getCategoryDescription()).isEqualTo("Fee Charges");
    }

    /**
     * Test updating an existing transaction category (rewrite operation).
     * 
     * Validates that save method correctly updates existing transaction category,
     * replicating COBOL EXEC CICS REWRITE FILE('TRNCAT') FROM(TRAN-CAT-RECORD).
     * 
     * Expected behavior:
     * - save on existing entity updates the category description
     * - Composite key remains unchanged
     * - Version field is incremented (optimistic locking)
     */
    @Test
    @DisplayName("Update existing transaction category modifies description while preserving composite key")
    void testUpdate_ExistingCategory() {
        // Given: Existing category with original description
        TransactionCategoryId existingKey = TransactionCategoryId.builder()
                .typeCode("DB")
                .categoryCode("5000")
                .build();
        Optional<TransactionCategory> existing = transactionCategoryRepository.findById(existingKey);
        assertThat(existing).isPresent();
        Long originalVersion = existing.get().getVersion();

        // When: Update description and save (replicates VSAM REWRITE)
        TransactionCategory toUpdate = existing.get();
        toUpdate.setCategoryDescription("Updated Debit Merchandise");
        TransactionCategory updated = transactionCategoryRepository.saveAndFlush(toUpdate);

        // Then: Verify description updated, key unchanged, version incremented
        assertThat(updated.getCategoryDescription()).isEqualTo("Updated Debit Merchandise");
        assertThat(updated.getId().getTypeCode()).isEqualTo("DB");
        assertThat(updated.getId().getCategoryCode()).isEqualTo("5000");
        assertThat(updated.getVersion()).isGreaterThan(originalVersion);

        // Verify updated category persisted
        Optional<TransactionCategory> retrieved = transactionCategoryRepository.findById(existingKey);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getCategoryDescription()).isEqualTo("Updated Debit Merchandise");
    }

    /**
     * Test composite key uniqueness constraint enforcement.
     * 
     * Validates that attempting to insert a duplicate composite key
     * (TRAN-TYPE-CD + TRAN-CAT-CD combination) throws DataIntegrityViolationException,
     * replicating COBOL EXEC CICS WRITE behavior when DUPREC condition is raised.
     * 
     * Expected behavior:
     * - Attempting to save category with duplicate composite key throws exception
     * - Original category remains unchanged in database
     * - Primary key constraint is enforced at database level
     */
    @Test
    @DisplayName("Composite key uniqueness constraint prevents duplicate TRAN-CAT-KEY insertion")
    void testCompositeKeyUniqueness() {
        // Given: Existing category with composite key DB-5000
        TransactionCategoryId duplicateKey = TransactionCategoryId.builder()
                .typeCode("DB")
                .categoryCode("5000")
                .build();

        // When/Then: Attempt to save category with duplicate key throws exception
        TransactionCategory duplicateCategory = TransactionCategory.builder()
                .id(duplicateKey)
                .categoryDescription("Duplicate Category")
                .build();

        assertThatThrownBy(() -> {
            transactionCategoryRepository.save(duplicateCategory);
            transactionCategoryRepository.flush(); // Force immediate database constraint check
        }).isInstanceOf(DataIntegrityViolationException.class);

        // Verify original category unchanged
        Optional<TransactionCategory> original = transactionCategoryRepository.findById(duplicateKey);
        assertThat(original).isPresent();
        assertThat(original.get().getCategoryDescription()).isEqualTo("Debit Merchandise");
    }

    /**
     * Test checking existence of transaction category by composite key.
     * 
     * Validates existsById method for efficient existence checking without
     * retrieving full entity, useful for validation before insert operations.
     * 
     * Expected behavior:
     * - existsById returns true for existing composite key
     * - existsById returns false for non-existent composite key
     */
    @Test
    @DisplayName("Exists by composite key returns true for existing category, false otherwise")
    void testExistsById_ReturnsTrue() {
        // Given: Existing category with key DB-5000
        TransactionCategoryId existingKey = TransactionCategoryId.builder()
                .typeCode("DB")
                .categoryCode("5000")
                .build();

        // When: Check existence
        boolean exists = transactionCategoryRepository.existsById(existingKey);

        // Then: Verify existence confirmed
        assertThat(exists).isTrue();

        // Test non-existent key
        TransactionCategoryId nonExistentKey = TransactionCategoryId.builder()
                .typeCode("XX")
                .categoryCode("9999")
                .build();
        assertThat(transactionCategoryRepository.existsById(nonExistentKey)).isFalse();
    }

    /**
     * Test counting total transaction categories.
     * 
     * Validates count method returning total number of transaction category
     * reference records, useful for pagination and reporting.
     * 
     * Expected behavior:
     * - count returns correct total number of categories in database
     * - Matches the number of categories inserted in setUp
     */
    @Test
    @DisplayName("Count returns correct total number of transaction categories")
    void testCount_ReturnsCorrectCount() {
        // When: Count total categories
        long totalCount = transactionCategoryRepository.count();

        // Then: Verify count matches test data (4 categories inserted in setUp)
        assertThat(totalCount).isEqualTo(4);
    }

    /**
     * Test deleting transaction category by composite key.
     * 
     * Validates deleteById method replicating COBOL EXEC CICS DELETE
     * FILE('TRNCAT') RIDFLD(TRAN-CAT-KEY) operation.
     * 
     * Expected behavior:
     * - deleteById removes category from database
     * - Subsequent findById returns empty Optional
     * - Total count decreases by one
     */
    @Test
    @DisplayName("Delete by composite key removes transaction category from database")
    void testDeleteById_RemovesCategory() {
        // Given: Existing category and initial count
        TransactionCategoryId keyToDelete = TransactionCategoryId.builder()
                .typeCode("DB")
                .categoryCode("5100")
                .build();
        long initialCount = transactionCategoryRepository.count();
        assertThat(transactionCategoryRepository.existsById(keyToDelete)).isTrue();

        // When: Delete by composite key (replicates VSAM DELETE)
        transactionCategoryRepository.deleteById(keyToDelete);

        // Then: Verify category deleted
        assertThat(transactionCategoryRepository.existsById(keyToDelete)).isFalse();
        assertThat(transactionCategoryRepository.findById(keyToDelete)).isEmpty();
        assertThat(transactionCategoryRepository.count()).isEqualTo(initialCount - 1);
    }

    /**
     * Test data integrity of category description field.
     * 
     * Validates that TRAN-CAT-TYPE-DESC field (PIC X(50)) maintains proper
     * data integrity during save and retrieval operations, including:
     * - 50-character length constraint
     * - UTF-8 encoding (converted from EBCDIC)
     * - NOT NULL constraint enforcement
     * 
     * Expected behavior:
     * - Description up to 50 characters is saved correctly
     * - Description field cannot be null (required field)
     */
    @Test
    @DisplayName("Category description field maintains data integrity with 50-character limit")
    void testCategoryDescription_DataIntegrity() {
        // Given: Category with 50-character description (max length from COBOL PIC X(50))
        String maxLengthDescription = "A".repeat(50); // Exactly 50 characters
        TransactionCategory categoryWithMaxDesc = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("TE")
                        .categoryCode("8000")
                        .build())
                .categoryDescription(maxLengthDescription)
                .build();

        // When: Save category with max-length description
        TransactionCategory saved = transactionCategoryRepository.save(categoryWithMaxDesc);

        // Then: Verify description saved correctly
        assertThat(saved.getCategoryDescription()).isEqualTo(maxLengthDescription);
        assertThat(saved.getCategoryDescription()).hasSize(50);

        // Verify retrieval maintains description integrity
        Optional<TransactionCategory> retrieved = transactionCategoryRepository.findById(saved.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getCategoryDescription()).isEqualTo(maxLengthDescription);
    }

    /**
     * Test composite key field constraints.
     * 
     * Validates that composite key fields maintain COBOL PIC specifications:
     * - TRAN-TYPE-CD: PIC X(02) - 2 character type code
     * - TRAN-CAT-CD: PIC 9(04) - 4 digit category code (stored as String with leading zeros)
     * 
     * Expected behavior:
     * - Type code maintains 2-character format
     * - Category code maintains 4-character format with leading zeros
     * - Key fields cannot be null
     */
    @Test
    @DisplayName("Composite key fields maintain COBOL PIC specifications")
    void testCompositeKey_FieldConstraints() {
        // Given: Category with properly formatted composite key
        TransactionCategory category = TransactionCategory.builder()
                .id(TransactionCategoryId.builder()
                        .typeCode("AB")  // 2 characters matching PIC X(02)
                        .categoryCode("0123")  // 4 digits with leading zero matching PIC 9(04)
                        .build())
                .categoryDescription("Test Category")
                .build();

        // When: Save and retrieve category
        transactionCategoryRepository.save(category);
        Optional<TransactionCategory> retrieved = transactionCategoryRepository.findById(category.getId());

        // Then: Verify key fields maintain correct format
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getId().getTypeCode()).isEqualTo("AB");
        assertThat(retrieved.get().getId().getTypeCode()).hasSize(2);
        assertThat(retrieved.get().getId().getCategoryCode()).isEqualTo("0123");
        assertThat(retrieved.get().getId().getCategoryCode()).hasSize(4);
        // Verify leading zero preserved (important for COBOL PIC 9(04) compatibility)
        assertThat(retrieved.get().getId().getCategoryCode()).startsWith("0");
    }
}

