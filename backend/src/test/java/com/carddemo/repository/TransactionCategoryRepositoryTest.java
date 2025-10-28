package com.carddemo.repository;

import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionCategoryId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TransactionCategoryRepository using Testcontainers PostgreSQL.
 * 
 * Tests JPA repository operations for transaction_category table (replaces TRANCATG VSAM
 * from CVTRA04Y.cpy COBOL copybook). Validates CRUD operations, composite key handling,
 * custom query methods, entity field mapping, composite primary key constraints,
 * data persistence and retrieval, optimistic locking, and query performance per Section 0.7.7.
 * 
 * Original COBOL Structure (CVTRA04Y.cpy):
 * <pre>
 * 01  TRAN-CAT-RECORD.
 *     05  TRAN-CAT-KEY.
 *        10  TRAN-TYPE-CD         PIC X(02).
 *        10  TRAN-CAT-CD          PIC 9(04).
 *     05  TRAN-CAT-TYPE-DESC      PIC X(50).
 * </pre>
 * 
 * Test Coverage:
 * - Composite key operations (save, findById, update, delete with composite key)
 * - Composite primary key uniqueness constraints
 * - Custom query method findByTransTypeCdOrderByTransTypeCdAscTranCatCdAsc
 * - Entity field mapping validation (transTypeCd, tranCatCd, tranCatTypeDesc)
 * - Data persistence and retrieval with composite keys
 * - Optimistic locking via @Version field
 * - Query performance for sub-10ms lookups per Section 0.7.7
 * 
 * Uses @DataJpaTest for JPA slice testing with Testcontainers providing
 * isolated PostgreSQL 16.6-alpine database instance for each test execution.
 * 
 * @author CardDemo Conversion Team
 * @version 1.0
 * @since 1.0
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionCategoryRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("carddemo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private TransactionCategoryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Test saving a transaction category with composite key.
     * 
     * Validates:
     * - Entity can be saved with composite key (transTypeCd + tranCatCd)
     * - All fields are persisted correctly (50-char description)
     * - Audit fields (createdAt, updatedAt, version) are populated automatically
     * - Entity can be retrieved after save
     * 
     * COBOL equivalent: WRITE FILE('TRANCATG') with composite key
     */
    @Test
    void testSaveTransactionCategory() {
        // Given: Create transaction category with composite key
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();

        // When: Save the category
        TransactionCategory savedCategory = repository.save(category);
        entityManager.flush();

        // Then: Verify all fields
        assertNotNull(savedCategory);
        assertEquals("01", savedCategory.getTransTypeCd());
        assertEquals(1001, savedCategory.getTranCatCd());
        assertEquals("Retail Purchase", savedCategory.getTranCatTypeDesc());
        assertNotNull(savedCategory.getCreatedAt());
        assertNotNull(savedCategory.getUpdatedAt());
        assertEquals(0, savedCategory.getVersion());
    }

    /**
     * Test findById with composite key.
     * 
     * Validates:
     * - findById() works correctly with TransactionCategoryId composite key object
     * - Both key components (transTypeCd + tranCatCd) are required for lookup
     * - Entity fields match saved data
     * 
     * COBOL equivalent: READ FILE('TRANCATG') RIDFLD(TRAN-CAT-KEY)
     */
    @Test
    void testFindByIdWithCompositeKey() {
        // Given: Save a transaction category
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        entityManager.persistAndFlush(category);
        entityManager.clear();

        // When: Find by composite key
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1001);
        Optional<TransactionCategory> foundCategory = repository.findById(compositeKey);

        // Then: Verify entity found and fields match
        assertTrue(foundCategory.isPresent());
        TransactionCategory result = foundCategory.get();
        assertEquals("01", result.getTransTypeCd());
        assertEquals(1001, result.getTranCatCd());
        assertEquals("Retail Purchase", result.getTranCatTypeDesc());
    }

    /**
     * Test findAll retrieves multiple transaction categories.
     * 
     * Validates:
     * - Multiple categories with different composite keys can be saved
     * - findAll() retrieves all saved categories
     * - Each category maintains its unique composite key
     * 
     * COBOL equivalent: Sequential browse of TRANCATG file
     */
    @Test
    void testFindAllTransactionCategories() {
        // Given: Save multiple transaction categories with different composite keys
        TransactionCategory category1 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        TransactionCategory category2 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1002)
                .tranCatTypeDesc("Online Purchase")
                .build();
        TransactionCategory category3 = TransactionCategory.builder()
                .transTypeCd("02")
                .tranCatCd(2001)
                .tranCatTypeDesc("ATM Withdrawal")
                .build();

        entityManager.persist(category1);
        entityManager.persist(category2);
        entityManager.persist(category3);
        entityManager.flush();
        entityManager.clear();

        // When: Retrieve all categories
        List<TransactionCategory> allCategories = repository.findAll();

        // Then: Verify all three categories are retrieved
        assertNotNull(allCategories);
        assertEquals(3, allCategories.size());
        
        // Verify composite keys are unique
        assertTrue(allCategories.stream()
                .anyMatch(c -> "01".equals(c.getTransTypeCd()) && 1001 == c.getTranCatCd()));
        assertTrue(allCategories.stream()
                .anyMatch(c -> "01".equals(c.getTransTypeCd()) && 1002 == c.getTranCatCd()));
        assertTrue(allCategories.stream()
                .anyMatch(c -> "02".equals(c.getTransTypeCd()) && 2001 == c.getTranCatCd()));
    }

    /**
     * Test updating a transaction category using composite key.
     * 
     * Validates:
     * - Entity can be updated using save() with existing composite key
     * - Description field can be modified
     * - Version field is incremented for optimistic locking
     * - updatedAt timestamp is refreshed
     * 
     * COBOL equivalent: REWRITE FILE('TRANCATG') with composite key
     */
    @Test
    void testUpdateTransactionCategory() {
        // Given: Save initial category
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        TransactionCategory savedCategory = entityManager.persistAndFlush(category);
        entityManager.clear();

        // Capture initial version and timestamp
        Integer initialVersion = savedCategory.getVersion();
        Timestamp initialUpdatedAt = savedCategory.getUpdatedAt();

        // Small delay to ensure timestamp difference
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // When: Update the description using composite key
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1001);
        Optional<TransactionCategory> foundCategory = repository.findById(compositeKey);
        assertTrue(foundCategory.isPresent());
        
        TransactionCategory toUpdate = foundCategory.get();
        toUpdate.setTranCatTypeDesc("Updated Retail Purchase");
        repository.save(toUpdate);
        entityManager.flush();
        entityManager.clear();

        // Then: Verify update
        Optional<TransactionCategory> updatedCategory = repository.findById(compositeKey);
        assertTrue(updatedCategory.isPresent());
        TransactionCategory result = updatedCategory.get();
        assertEquals("Updated Retail Purchase", result.getTranCatTypeDesc());
        assertEquals(initialVersion + 1, result.getVersion());
        assertTrue(result.getUpdatedAt().after(initialUpdatedAt));
    }

    /**
     * Test deleting a transaction category by composite key.
     * 
     * Validates:
     * - deleteById() works with TransactionCategoryId composite key
     * - Entity is removed from database
     * - Subsequent findById() returns empty Optional
     * 
     * COBOL equivalent: DELETE FILE('TRANCATG') RIDFLD(TRAN-CAT-KEY)
     */
    @Test
    void testDeleteTransactionCategory() {
        // Given: Save a transaction category
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        entityManager.persistAndFlush(category);
        entityManager.clear();

        // When: Delete by composite key
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1001);
        repository.deleteById(compositeKey);
        entityManager.flush();

        // Then: Verify entity no longer exists
        Optional<TransactionCategory> deletedCategory = repository.findById(compositeKey);
        assertFalse(deletedCategory.isPresent());
    }

    /**
     * Test composite key uniqueness constraint.
     * 
     * Validates:
     * - Duplicate composite key (same transTypeCd + tranCatCd) is rejected
     * - Constraint violation exception is thrown on duplicate key save
     * - Database PRIMARY KEY constraint is enforced
     * 
     * Note: In @DataJpaTest context, Hibernate may throw ConstraintViolationException
     * directly before Spring translates it to DataIntegrityViolationException.
     * 
     * COBOL equivalent: DUPREC condition on WRITE FILE('TRANCATG')
     */
    @Test
    void testCompositeKeyUniqueness() {
        // Given: Save initial category with composite key
        TransactionCategory category1 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        repository.save(category1);
        entityManager.flush();
        entityManager.clear();

        // When & Then: Attempt to save duplicate composite key
        // Should throw either DataIntegrityViolationException (Spring) or 
        // ConstraintViolationException (Hibernate) depending on context
        TransactionCategory category2 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Different Description")
                .build();

        Exception exception = assertThrows(Exception.class, () -> {
            repository.save(category2);
            entityManager.flush();
        });
        
        // Verify it's a constraint violation (either Spring or Hibernate exception)
        boolean isConstraintViolation = exception instanceof DataIntegrityViolationException ||
                                       exception instanceof ConstraintViolationException ||
                                       (exception.getCause() != null && 
                                        exception.getCause() instanceof ConstraintViolationException);
        
        assertTrue(isConstraintViolation, 
                "Expected constraint violation exception but got: " + exception.getClass().getName());
    }

    /**
     * Test findById with non-existent composite key.
     * 
     * Validates:
     * - findById() returns empty Optional for non-existent key
     * - No exception is thrown for missing record
     * 
     * COBOL equivalent: NOTFND condition on READ FILE('TRANCATG')
     */
    @Test
    void testPartialKeyNotFound() {
        // Given: No category exists with this composite key
        TransactionCategoryId compositeKey = new TransactionCategoryId("99", 9999);

        // When: Attempt to find by non-existent key
        Optional<TransactionCategory> result = repository.findById(compositeKey);

        // Then: Verify empty result
        assertFalse(result.isPresent());
    }

    /**
     * Test query performance with composite key lookups.
     * 
     * Validates:
     * - Composite key lookups complete in sub-10ms average (Section 0.7.7)
     * - PostgreSQL B-tree index provides efficient key access
     * - Performance meets VSAM KSDS direct access equivalence
     * 
     * Performance requirement: Sub-10ms for primary key lookups
     */
    @Test
    void testQueryPerformanceWithCompositeKey() {
        // Given: Save test data
        for (int i = 1; i <= 50; i++) {
            TransactionCategory category = TransactionCategory.builder()
                    .transTypeCd("01")
                    .tranCatCd(1000 + i)
                    .tranCatTypeDesc("Category " + i)
                    .build();
            entityManager.persist(category);
        }
        entityManager.flush();
        entityManager.clear();

        // When: Execute 100 findById operations with composite keys
        long startTime = System.currentTimeMillis();
        for (int i = 1; i <= 100; i++) {
            TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1000 + (i % 50 + 1));
            repository.findById(compositeKey);
        }
        long endTime = System.currentTimeMillis();

        // Then: Verify average query time is under 10ms
        long totalTime = endTime - startTime;
        double averageTime = totalTime / 100.0;
        
        assertTrue(averageTime < 10.0, 
                "Average composite key lookup time " + averageTime + "ms exceeds 10ms requirement");
    }

    /**
     * Test custom query method findByTransTypeCdOrderByTransTypeCdAscTranCatCdAsc.
     * 
     * Validates:
     * - Custom query method filters by transaction type code (first part of composite key)
     * - Returns all categories matching the specified type
     * - Results are ordered by composite key (type code, category code)
     * - Empty list returned when no matches found
     * 
     * COBOL equivalent: STARTBR/READNEXT sequential browse with generic key
     */
    @Test
    void testFindByTransactionType() {
        // Given: Save categories with different transaction types
        TransactionCategory category1 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        TransactionCategory category2 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1002)
                .tranCatTypeDesc("Online Purchase")
                .build();
        TransactionCategory category3 = TransactionCategory.builder()
                .transTypeCd("02")
                .tranCatCd(2001)
                .tranCatTypeDesc("ATM Withdrawal")
                .build();
        TransactionCategory category4 = TransactionCategory.builder()
                .transTypeCd("02")
                .tranCatCd(2002)
                .tranCatTypeDesc("Cash Advance")
                .build();

        entityManager.persist(category1);
        entityManager.persist(category2);
        entityManager.persist(category3);
        entityManager.persist(category4);
        entityManager.flush();
        entityManager.clear();

        // When: Find all categories for transaction type "01"
        List<TransactionCategory> type01Categories = 
                repository.findByTransTypeCdOrderByTransTypeCdAscTranCatCdAsc("01");

        // Then: Verify correct categories returned
        assertNotNull(type01Categories);
        assertEquals(2, type01Categories.size());
        assertEquals("01", type01Categories.get(0).getTransTypeCd());
        assertEquals(1001, type01Categories.get(0).getTranCatCd());
        assertEquals("01", type01Categories.get(1).getTransTypeCd());
        assertEquals(1002, type01Categories.get(1).getTranCatCd());

        // When: Find all categories for transaction type "02"
        List<TransactionCategory> type02Categories = 
                repository.findByTransTypeCdOrderByTransTypeCdAscTranCatCdAsc("02");

        // Then: Verify correct categories returned
        assertNotNull(type02Categories);
        assertEquals(2, type02Categories.size());
        assertEquals("02", type02Categories.get(0).getTransTypeCd());
        assertEquals(2001, type02Categories.get(0).getTranCatCd());

        // When: Find categories for non-existent type
        List<TransactionCategory> nonExistentCategories = 
                repository.findByTransTypeCdOrderByTransTypeCdAscTranCatCdAsc("99");

        // Then: Verify empty list returned
        assertNotNull(nonExistentCategories);
        assertTrue(nonExistentCategories.isEmpty());
    }

    /**
     * Test composite key equals and hashCode methods.
     * 
     * Validates:
     * - TransactionCategoryId equals() compares both key fields correctly
     * - TransactionCategoryId hashCode() is consistent with equals()
     * - Composite keys with same values are considered equal
     * - Composite keys with different values are not equal
     * 
     * Required for proper JPA entity identity management
     */
    @Test
    void testCompositeKeyEqualsAndHashCode() {
        // Given: Create composite keys
        TransactionCategoryId key1 = new TransactionCategoryId("01", 1001);
        TransactionCategoryId key2 = new TransactionCategoryId("01", 1001);
        TransactionCategoryId key3 = new TransactionCategoryId("01", 1002);
        TransactionCategoryId key4 = new TransactionCategoryId("02", 1001);

        // Then: Verify equals() and hashCode() behavior
        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        
        assertNotEquals(key1, key3);
        assertNotEquals(key1, key4);
        assertNotEquals(key3, key4);
    }

    /**
     * Test optimistic locking with composite key.
     * 
     * Validates:
     * - Version field prevents concurrent update conflicts
     * - Attempting to update stale entity throws OptimisticLockException
     * - Replicates COBOL VSAM RBA optimistic locking semantics
     * 
     * COBOL equivalent: INVREQ condition on REWRITE with stale RBA
     */
    @Test
    void testOptimisticLockingWithCompositeKey() {
        // Given: Save initial category
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        repository.save(category);
        entityManager.flush();
        entityManager.clear();

        // When: Load same entity twice in separate transactions (simulating two concurrent sessions)
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1001);
        
        // Session 1: Load entity
        Optional<TransactionCategory> session1Entity = repository.findById(compositeKey);
        assertTrue(session1Entity.isPresent());
        TransactionCategory entity1 = session1Entity.get();
        
        // Session 2: Load same entity (before Session 1 commits)
        Optional<TransactionCategory> session2Entity = repository.findById(compositeKey);
        assertTrue(session2Entity.isPresent());
        TransactionCategory entity2 = session2Entity.get();
        
        // Detach entity2 to simulate holding stale data from a different session
        entityManager.detach(entity2);

        // First update succeeds
        entity1.setTranCatTypeDesc("Updated by Session 1");
        repository.save(entity1);
        entityManager.flush();

        // Then: Second update with stale version should fail
        entity2.setTranCatTypeDesc("Updated by Session 2");
        
        assertThrows(Exception.class, () -> {
            repository.save(entity2);
            entityManager.flush();
        });
    }

    /**
     * Test count operation.
     * 
     * Validates:
     * - count() returns correct number of transaction categories
     * - Count reflects additions and deletions
     */
    @Test
    void testCountTransactionCategories() {
        // Given: No categories initially
        long initialCount = repository.count();

        // When: Add three categories
        TransactionCategory category1 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        TransactionCategory category2 = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1002)
                .tranCatTypeDesc("Online Purchase")
                .build();
        TransactionCategory category3 = TransactionCategory.builder()
                .transTypeCd("02")
                .tranCatCd(2001)
                .tranCatTypeDesc("ATM Withdrawal")
                .build();

        repository.save(category1);
        repository.save(category2);
        repository.save(category3);
        entityManager.flush();

        // Then: Verify count increased by 3
        long afterAddCount = repository.count();
        assertEquals(initialCount + 3, afterAddCount);

        // When: Delete one category
        TransactionCategoryId compositeKey = new TransactionCategoryId("01", 1001);
        repository.deleteById(compositeKey);
        entityManager.flush();

        // Then: Verify count decreased by 1
        long afterDeleteCount = repository.count();
        assertEquals(initialCount + 2, afterDeleteCount);
    }

    /**
     * Test existsById with composite key.
     * 
     * Validates:
     * - existsById() returns true for existing composite key
     * - existsById() returns false for non-existent composite key
     * - Provides efficient existence check without loading full entity
     */
    @Test
    void testExistsByIdWithCompositeKey() {
        // Given: Save a transaction category
        TransactionCategory category = TransactionCategory.builder()
                .transTypeCd("01")
                .tranCatCd(1001)
                .tranCatTypeDesc("Retail Purchase")
                .build();
        entityManager.persistAndFlush(category);
        entityManager.clear();

        // When & Then: Check existence with existing key
        TransactionCategoryId existingKey = new TransactionCategoryId("01", 1001);
        assertTrue(repository.existsById(existingKey));

        // When & Then: Check existence with non-existent key
        TransactionCategoryId nonExistentKey = new TransactionCategoryId("99", 9999);
        assertFalse(repository.existsById(nonExistentKey));
    }
}
