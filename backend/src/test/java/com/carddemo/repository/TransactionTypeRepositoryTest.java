package com.carddemo.repository;

import com.carddemo.model.entity.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.OptimisticLockException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for TransactionTypeRepository using Testcontainers PostgreSQL.
 * 
 * Tests JPA repository operations for transaction_type table (replaces TRANTYPE VSAM 
 * from CVTRA03Y.cpy COBOL copybook). This test validates the complete data access 
 * layer for transaction type reference data.
 * 
 * Original COBOL/VSAM context:
 * - COBOL copybook: CVTRA03Y.cpy (TRAN-TYPE-RECORD, 60-byte fixed record)
 * - VSAM dataset: TRANTYPE (Key-Sequenced Data Set)
 * - Primary key: TRAN-TYPE PIC X(02) (2-byte transaction type code)
 * - Record layout: 
 *   * TRAN-TYPE (2 bytes) - Transaction type code
 *   * TRAN-TYPE-DESC (50 bytes) - Description
 *   * FILLER (8 bytes) - Unused padding
 * 
 * PostgreSQL replacement:
 * - Table: transaction_type
 * - Primary key: trans_type_cd VARCHAR(2) PRIMARY KEY
 * - B-tree index on primary key (automatic) replicates VSAM KSDS access
 * 
 * Test coverage:
 * 1. CRUD operations (save, findById, findAll, delete) - validates repository methods
 * 2. Primary key constraints and uniqueness - validates database schema
 * 3. Query performance validation - ensures sub-10ms lookups per Section 0.7.7
 * 4. Optimistic locking mechanism - validates @Version field behavior
 * 5. Data persistence and retrieval accuracy - ensures COBOL-to-Java fidelity
 * 6. Entity field mapping from CVTRA03Y.cpy - validates conversion correctness
 * 
 * Uses Testcontainers PostgreSQL 16.6-alpine for isolated database testing,
 * ensuring test independence and reproducibility without external database dependencies.
 * 
 * Performance requirement (Section 0.7.7):
 * - Primary key lookups MUST complete in under 10ms to meet or exceed VSAM performance
 * - testQueryPerformance() validates this requirement with 100 iterations
 * 
 * @see com.carddemo.repository.TransactionTypeRepository
 * @see com.carddemo.model.entity.TransactionType
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class TransactionTypeRepositoryTest {

    /**
     * PostgreSQL container using Testcontainers.
     * 
     * Provides isolated PostgreSQL 16.6-alpine database instance for testing.
     * Container lifecycle is managed automatically by @Testcontainers annotation.
     * Database is created fresh for each test class execution, ensuring clean state.
     */
    @Container
    private static final PostgreSQLContainer<?> postgresContainer = 
        new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("test_carddemo")
            .withUsername("test")
            .withPassword("test");

    /**
     * Configures Spring Boot data source properties dynamically from Testcontainers.
     * 
     * Injects PostgreSQL container connection details into Spring application context,
     * replacing any default datasource configuration with test container properties.
     * 
     * @param registry Spring dynamic property registry
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgresContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresContainer::getUsername);
        registry.add("spring.datasource.password", postgresContainer::getPassword);
    }

    /**
     * TransactionTypeRepository instance under test.
     * 
     * Autowired by Spring Test framework, providing the repository implementation
     * configured against the Testcontainers PostgreSQL database.
     */
    @Autowired
    private TransactionTypeRepository transactionTypeRepository;

    /**
     * TestEntityManager for direct entity lifecycle management.
     * 
     * Provides direct access to JPA persistence operations for advanced testing
     * scenarios like optimistic locking validation and constraint violation testing.
     * Allows bypassing repository layer to manipulate entity state directly.
     */
    @Autowired
    private TestEntityManager testEntityManager;

    /**
     * Cleanup method executed after each test.
     * 
     * Deletes all transaction type records from the database to ensure test isolation.
     * Prevents data pollution between tests and guarantees each test starts with
     * a clean database state.
     */
    @AfterEach
    void tearDown() {
        transactionTypeRepository.deleteAll();
    }

    /**
     * Tests saving a new TransactionType entity to the database.
     * 
     * Validates:
     * - Repository save() method creates new record
     * - Primary key (transTypeCd) is persisted correctly
     * - Description field is persisted correctly
     * - Audit fields (createdAt, updatedAt) are auto-populated
     * - Version field is initialized to 0
     * 
     * COBOL equivalent:
     * EXEC CICS WRITE FILE('TRANTYPE') FROM(TRAN-TYPE-RECORD) RIDFLD(TRAN-TYPE) END-EXEC
     */
    @Test
    void testSaveTransactionType() {
        // Arrange: Create new TransactionType entity matching CVTRA03Y.cpy structure
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("01")  // TRAN-TYPE PIC X(02)
                .transTypeDesc("Purchase Transaction")  // TRAN-TYPE-DESC PIC X(50)
                .build();

        // Act: Save entity to database
        TransactionType savedTransactionType = transactionTypeRepository.save(transactionType);

        // Assert: Verify entity was persisted correctly
        assertNotNull(savedTransactionType, "Saved transaction type should not be null");
        assertEquals("01", savedTransactionType.getTransTypeCd(), 
                "Transaction type code should match");
        assertEquals("Purchase Transaction", savedTransactionType.getTransTypeDesc(), 
                "Transaction type description should match");
        assertNotNull(savedTransactionType.getCreatedAt(), 
                "Created timestamp should be auto-populated");
        assertNotNull(savedTransactionType.getUpdatedAt(), 
                "Updated timestamp should be auto-populated");
        assertNotNull(savedTransactionType.getVersion(), 
                "Version should be initialized");
        assertEquals(0, savedTransactionType.getVersion(), 
                "Initial version should be 0");
    }

    /**
     * Tests finding a TransactionType by ID (primary key lookup).
     * 
     * Validates:
     * - Repository findById() retrieves existing record
     * - All fields match original values from CVTRA03Y.cpy structure
     * - Optional container properly wraps result
     * 
     * COBOL equivalent:
     * EXEC CICS READ FILE('TRANTYPE') RIDFLD(TRAN-TYPE) INTO(TRAN-TYPE-RECORD) END-EXEC
     */
    @Test
    void testFindByIdTransactionType() {
        // Arrange: Save transaction type to database
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("02")
                .transTypeDesc("Credit/Payment")
                .build();
        transactionTypeRepository.save(transactionType);
        testEntityManager.flush();
        testEntityManager.clear();

        // Act: Find by primary key
        Optional<TransactionType> foundTransactionType = 
                transactionTypeRepository.findById("02");

        // Assert: Verify record was retrieved correctly
        assertTrue(foundTransactionType.isPresent(), 
                "Transaction type should be found");
        assertEquals("02", foundTransactionType.get().getTransTypeCd(), 
                "Transaction type code should match");
        assertEquals("Credit/Payment", foundTransactionType.get().getTransTypeDesc(), 
                "Transaction type description should match");
    }

    /**
     * Tests retrieving all TransactionType records.
     * 
     * Validates:
     * - Repository findAll() returns all persisted records
     * - Record count matches number of saved entities
     * - All entities are properly hydrated
     * 
     * COBOL equivalent:
     * EXEC CICS STARTBR FILE('TRANTYPE') ... loop: EXEC CICS READNEXT ... EXEC CICS ENDBR END-EXEC
     */
    @Test
    void testFindAllTransactionTypes() {
        // Arrange: Save multiple transaction types
        TransactionType type1 = TransactionType.builder()
                .transTypeCd("01")
                .transTypeDesc("Purchase Transaction")
                .build();
        TransactionType type2 = TransactionType.builder()
                .transTypeCd("02")
                .transTypeDesc("Credit/Payment")
                .build();
        TransactionType type3 = TransactionType.builder()
                .transTypeCd("03")
                .transTypeDesc("Cash Advance")
                .build();
        transactionTypeRepository.saveAll(List.of(type1, type2, type3));
        testEntityManager.flush();

        // Act: Retrieve all records
        List<TransactionType> allTypes = transactionTypeRepository.findAll();

        // Assert: Verify all records retrieved
        assertNotNull(allTypes, "Result list should not be null");
        assertEquals(3, allTypes.size(), "Should retrieve exactly 3 transaction types");
        assertTrue(allTypes.stream().anyMatch(t -> "01".equals(t.getTransTypeCd())), 
                "Should contain type 01");
        assertTrue(allTypes.stream().anyMatch(t -> "02".equals(t.getTransTypeCd())), 
                "Should contain type 02");
        assertTrue(allTypes.stream().anyMatch(t -> "03".equals(t.getTransTypeCd())), 
                "Should contain type 03");
    }

    /**
     * Tests updating an existing TransactionType entity.
     * 
     * Validates:
     * - Repository save() method updates existing record
     * - Description field modification is persisted
     * - updatedAt timestamp is refreshed
     * - Version field is incremented for optimistic locking
     * 
     * COBOL equivalent:
     * EXEC CICS REWRITE FILE('TRANTYPE') FROM(TRAN-TYPE-RECORD) END-EXEC
     */
    @Test
    void testUpdateTransactionType() {
        // Arrange: Save initial transaction type
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("04")
                .transTypeDesc("Balance Transfer")
                .build();
        TransactionType savedType = transactionTypeRepository.save(transactionType);
        testEntityManager.flush();
        testEntityManager.clear();
        
        Integer originalVersion = savedType.getVersion();

        // Act: Modify and update description
        savedType.setTransTypeDesc("Balance Transfer - Updated");
        TransactionType updatedType = transactionTypeRepository.save(savedType);
        testEntityManager.flush();

        // Assert: Verify update was persisted
        assertNotNull(updatedType, "Updated transaction type should not be null");
        assertEquals("04", updatedType.getTransTypeCd(), 
                "Transaction type code should remain unchanged");
        assertEquals("Balance Transfer - Updated", updatedType.getTransTypeDesc(), 
                "Transaction type description should be updated");
        assertNotNull(updatedType.getVersion(), 
                "Version should not be null");
        assertEquals(originalVersion + 1, updatedType.getVersion(), 
                "Version should be incremented after update");
    }

    /**
     * Tests deleting a TransactionType by ID.
     * 
     * Validates:
     * - Repository deleteById() removes record from database
     * - Subsequent findById() returns empty Optional
     * - Record is permanently removed
     * 
     * COBOL equivalent:
     * EXEC CICS DELETE FILE('TRANTYPE') RIDFLD(TRAN-TYPE) END-EXEC
     */
    @Test
    void testDeleteTransactionType() {
        // Arrange: Save transaction type
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("05")
                .transTypeDesc("Fee Transaction")
                .build();
        transactionTypeRepository.save(transactionType);
        testEntityManager.flush();

        // Act: Delete by primary key
        transactionTypeRepository.deleteById("05");
        testEntityManager.flush();
        testEntityManager.clear();

        // Assert: Verify record was deleted
        Optional<TransactionType> deletedType = transactionTypeRepository.findById("05");
        assertFalse(deletedType.isPresent(), 
                "Transaction type should not be found after deletion");
    }

    /**
     * Tests finding a non-existent TransactionType.
     * 
     * Validates:
     * - Repository findById() returns empty Optional for invalid ID
     * - No exception is thrown for non-existent record
     * - Behavior matches COBOL file-status 23 (record not found)
     * 
     * COBOL equivalent:
     * EXEC CICS READ FILE('TRANTYPE') RIDFLD(TRAN-TYPE) RESP(WS-RESP) END-EXEC
     * IF WS-RESP = DFHRESP(NOTFND)
     */
    @Test
    void testTransactionTypeNotFound() {
        // Act: Attempt to find non-existent transaction type
        Optional<TransactionType> notFoundType = transactionTypeRepository.findById("99");

        // Assert: Verify empty Optional is returned
        assertFalse(notFoundType.isPresent(), 
                "Non-existent transaction type should return empty Optional");
        assertTrue(notFoundType.isEmpty(), 
                "Optional should be empty for non-existent record");
    }

    /**
     * Tests primary key uniqueness constraint.
     * 
     * Validates:
     * - Duplicate transaction type code throws DataIntegrityViolationException
     * - Database primary key constraint is enforced
     * - Replicates COBOL file-status 22 (duplicate key) behavior
     * 
     * COBOL equivalent:
     * EXEC CICS WRITE FILE('TRANTYPE') FROM(record) RIDFLD(key) RESP(WS-RESP) END-EXEC
     * IF WS-RESP = DFHRESP(DUPREC)
     */
    @Test
    void testPrimaryKeyUniqueness() {
        // Arrange: Save first transaction type
        TransactionType transactionType1 = TransactionType.builder()
                .transTypeCd("06")
                .transTypeDesc("Interest Charge")
                .build();
        transactionTypeRepository.save(transactionType1);
        testEntityManager.flush();
        testEntityManager.clear();

        // Act & Assert: Attempt to save duplicate primary key
        TransactionType transactionType2 = TransactionType.builder()
                .transTypeCd("06")  // Duplicate key
                .transTypeDesc("Interest Charge - Duplicate")
                .build();
        
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionTypeRepository.save(transactionType2);
            testEntityManager.flush();
        }, "Duplicate primary key should throw DataIntegrityViolationException");
    }

    /**
     * Tests query performance to ensure sub-10ms primary key lookups.
     * 
     * Validates Section 0.7.7 requirement:
     * - Primary key lookups MUST complete in under 10ms average
     * - PostgreSQL B-tree index provides performance equivalent to VSAM KSDS
     * - Database query optimization meets or exceeds mainframe performance
     * 
     * Executes 100 findById() operations and measures average execution time.
     * Ensures Java/PostgreSQL solution maintains performance parity with COBOL/VSAM.
     */
    @Test
    void testQueryPerformance() {
        // Arrange: Save transaction types for performance testing
        for (int i = 1; i <= 10; i++) {
            String code = String.format("%02d", i);
            TransactionType type = TransactionType.builder()
                    .transTypeCd(code)
                    .transTypeDesc("Type " + code)
                    .build();
            transactionTypeRepository.save(type);
        }
        testEntityManager.flush();
        testEntityManager.clear();

        // Act: Execute 100 primary key lookups and measure time
        int iterations = 100;
        long totalTime = 0;
        
        for (int i = 0; i < iterations; i++) {
            String searchCode = String.format("%02d", (i % 10) + 1);
            long startTime = System.nanoTime();
            transactionTypeRepository.findById(searchCode);
            long endTime = System.nanoTime();
            totalTime += (endTime - startTime);
        }

        // Calculate average time in milliseconds
        long averageTimeNanos = totalTime / iterations;
        double averageTimeMillis = averageTimeNanos / 1_000_000.0;

        // Assert: Verify average query time is under 10ms per Section 0.7.7
        assertTrue(averageTimeMillis < 10.0, 
                String.format("Average query time (%.2f ms) should be under 10ms. " +
                        "Current performance: %.2f ms", averageTimeMillis, averageTimeMillis));
        
        // Additional validation: log performance for monitoring
        System.out.printf("Query performance test: Average time per lookup = %.4f ms " +
                "(based on %d iterations)%n", averageTimeMillis, iterations);
    }

    /**
     * Tests optimistic locking mechanism using @Version field.
     * 
     * Validates:
     * - JPA @Version field prevents concurrent modification conflicts
     * - OptimisticLockException is thrown when version mismatch detected
     * - Replicates COBOL VSAM RBA (Relative Byte Address) optimistic locking
     * - Ensures data integrity in concurrent update scenarios
     * 
     * Simulates scenario where two transactions attempt to update the same record:
     * - Transaction A reads record (version = 0)
     * - Transaction B reads same record (version = 0)
     * - Transaction A updates and commits (version = 1)
     * - Transaction B attempts update with stale version (version = 0)
     * - Transaction B should fail with OptimisticLockException
     * 
     * COBOL equivalent:
     * VSAM RBA check preventing concurrent updates to the same record
     */
    @Test
    void testOptimisticLocking() {
        // Arrange: Save initial transaction type
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("07")
                .transTypeDesc("Adjustment Transaction")
                .build();
        TransactionType savedType = transactionTypeRepository.save(transactionType);
        testEntityManager.flush();
        testEntityManager.clear();

        // Act: Simulate concurrent modification scenario
        // Transaction A: Read entity
        TransactionType typeA = transactionTypeRepository.findById("07").orElseThrow();
        
        // Transaction B: Read same entity
        TransactionType typeB = transactionTypeRepository.findById("07").orElseThrow();
        
        // Transaction A: Modify and save (increments version to 1)
        typeA.setTransTypeDesc("Adjustment Transaction - Version A");
        transactionTypeRepository.save(typeA);
        testEntityManager.flush();
        
        // Transaction B: Attempt to save with stale version (should fail)
        typeB.setTransTypeDesc("Adjustment Transaction - Version B");
        
        // Assert: Verify OptimisticLockException is thrown
        assertThrows(OptimisticLockException.class, () -> {
            transactionTypeRepository.save(typeB);
            testEntityManager.flush();
        }, "Concurrent modification should throw OptimisticLockException");
    }

    /**
     * Tests count() method for record counting.
     * 
     * Validates:
     * - Repository count() returns accurate record count
     * - Useful for reporting and validation scenarios
     */
    @Test
    void testCountTransactionTypes() {
        // Arrange: Save multiple transaction types
        TransactionType type1 = TransactionType.builder()
                .transTypeCd("08")
                .transTypeDesc("Refund Transaction")
                .build();
        TransactionType type2 = TransactionType.builder()
                .transTypeCd("09")
                .transTypeDesc("Reversal Transaction")
                .build();
        transactionTypeRepository.saveAll(List.of(type1, type2));
        testEntityManager.flush();

        // Act: Count records
        long count = transactionTypeRepository.count();

        // Assert: Verify count is correct
        assertEquals(2, count, "Should count exactly 2 transaction types");
    }

    /**
     * Tests existsById() method for existence checking.
     * 
     * Validates:
     * - Repository existsById() checks record existence without loading full entity
     * - More efficient than findById() for validation scenarios
     * - Returns true for existing records, false for non-existent
     */
    @Test
    void testExistsById() {
        // Arrange: Save transaction type
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("10")
                .transTypeDesc("Authorization Transaction")
                .build();
        transactionTypeRepository.save(transactionType);
        testEntityManager.flush();

        // Act & Assert: Verify existence checks
        assertTrue(transactionTypeRepository.existsById("10"), 
                "Should return true for existing transaction type");
        assertFalse(transactionTypeRepository.existsById("99"), 
                "Should return false for non-existent transaction type");
    }

    /**
     * Tests entity field mapping from CVTRA03Y.cpy COBOL copybook.
     * 
     * Validates:
     * - All COBOL PIC X fields map correctly to Java String fields
     * - Field lengths are preserved (2 chars for code, 50 chars for description)
     * - Data integrity is maintained through persistence cycle
     * - Exact COBOL-to-Java conversion fidelity
     */
    @Test
    void testEntityFieldMapping() {
        // Arrange: Create entity with exact field lengths from CVTRA03Y.cpy
        String typeCode = "11";  // TRAN-TYPE PIC X(02)
        String typeDesc = "Test Description with Exactly Fifty Characters!!";  // TRAN-TYPE-DESC PIC X(50)
        
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd(typeCode)
                .transTypeDesc(typeDesc)
                .build();

        // Act: Save and retrieve
        transactionTypeRepository.save(transactionType);
        testEntityManager.flush();
        testEntityManager.clear();
        
        TransactionType retrievedType = transactionTypeRepository.findById(typeCode).orElseThrow();

        // Assert: Verify exact field mapping
        assertEquals(typeCode, retrievedType.getTransTypeCd(), 
                "Type code should match exactly");
        assertEquals(typeDesc, retrievedType.getTransTypeDesc(), 
                "Type description should match exactly");
        assertEquals(2, retrievedType.getTransTypeCd().length(), 
                "Type code length should be 2 characters");
        assertTrue(retrievedType.getTransTypeDesc().length() <= 50, 
                "Type description should not exceed 50 characters");
    }

    /**
     * Tests delete() method with entity object.
     * 
     * Validates:
     * - Repository delete() removes record using entity object
     * - Alternative to deleteById() when entity is already loaded
     */
    @Test
    void testDeleteByEntity() {
        // Arrange: Save transaction type
        TransactionType transactionType = TransactionType.builder()
                .transTypeCd("12")
                .transTypeDesc("Chargeback Transaction")
                .build();
        TransactionType savedType = transactionTypeRepository.save(transactionType);
        testEntityManager.flush();

        // Act: Delete using entity
        transactionTypeRepository.delete(savedType);
        testEntityManager.flush();
        testEntityManager.clear();

        // Assert: Verify deletion
        assertFalse(transactionTypeRepository.existsById("12"), 
                "Transaction type should not exist after deletion");
    }

    /**
     * Tests saveAll() for batch operations.
     * 
     * Validates:
     * - Repository saveAll() efficiently persists multiple entities
     * - Used during initial data load from TRANTYPE.jcl
     * - More efficient than individual save() calls
     */
    @Test
    void testSaveAllBatchOperation() {
        // Arrange: Create multiple transaction types
        List<TransactionType> types = List.of(
            TransactionType.builder().transTypeCd("13").transTypeDesc("Type 13").build(),
            TransactionType.builder().transTypeCd("14").transTypeDesc("Type 14").build(),
            TransactionType.builder().transTypeCd("15").transTypeDesc("Type 15").build()
        );

        // Act: Batch save
        List<TransactionType> savedTypes = transactionTypeRepository.saveAll(types);
        testEntityManager.flush();

        // Assert: Verify all saved
        assertEquals(3, savedTypes.size(), "Should save all 3 transaction types");
        assertEquals(3, transactionTypeRepository.count(), "Database should contain 3 records");
    }
}

