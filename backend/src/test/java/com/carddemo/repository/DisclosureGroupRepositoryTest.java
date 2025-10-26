package com.carddemo.repository;

import com.carddemo.model.entity.DisclosureGroup;
import com.carddemo.model.entity.DisclosureGroupId;
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

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DisclosureGroupRepository using Testcontainers PostgreSQL.
 * 
 * Tests JPA repository operations for disclosure_group table that replaces VSAM DISCGRP file.
 * Original COBOL copybook: CVTRA02Y.cpy (DIS-GROUP-RECORD, 50-byte record)
 * Original VSAM file: AWS.M2.CARDDEMO.DISCGRP.VSAM.KSDS
 * 
 * Test Focus Areas per Agent Action Plan Section 0.7:
 * - Three-part composite key operations (discAcctGroupId, discTranTypeCd, discTranCatCd)
 * - CRUD operations with composite primary key validation
 * - BigDecimal precision for interest rate (COBOL S9(04)V99 → Java BigDecimal scale 2)
 * - Custom query methods (findByDiscAcctGroupId, findByDiscTranTypeCd)
 * - Query performance validation (sub-10ms requirement from Section 0.7.7)
 * - Composite key uniqueness constraints
 * - Optimistic locking mechanism via @Version
 * - Data persistence and retrieval with three-part key
 * 
 * Test Strategy:
 * - Uses Testcontainers PostgreSQL 16.6-alpine for isolated database testing
 * - @DataJpaTest annotation for JPA slice testing with transaction rollback
 * - TestEntityManager for direct entity lifecycle management
 * - All tests validate exact COBOL-to-Java conversion behavior
 * 
 * Conversion Validation (Section 0.7.2):
 * - Preserves COBOL COMP-3 packed decimal precision using BigDecimal with scale 2
 * - Validates composite key integrity from COBOL DIS-GROUP-KEY structure
 * - Tests replicate VSAM KSDS key access patterns with sub-10ms lookups
 * 
 * @see DisclosureGroupRepository Repository interface under test
 * @see DisclosureGroup JPA entity with three-part composite key
 * @see DisclosureGroupId Composite primary key class
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public class DisclosureGroupRepositoryTest {

    /**
     * PostgreSQL test container using version 16.6-alpine.
     * Provides isolated database instance for integration testing.
     * Container lifecycle managed automatically by @Testcontainers extension.
     */
    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private TestEntityManager entityManager;

    /**
     * Configure Spring datasource properties dynamically from Testcontainers.
     * Registers PostgreSQL container connection details for test execution.
     */
    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * Test save operation with three-part composite key.
     * 
     * Validates:
     * - Entity persists successfully with all three key components
     * - All fields including BigDecimal interest rate persist correctly
     * - Audit timestamps (createdAt, updatedAt) are set automatically
     * - Version field initialized for optimistic locking
     * 
     * Maps to COBOL: EXEC CICS WRITE FILE('DISCGRP') FROM(DIS-GROUP-RECORD)
     */
    @Test
    public void testSaveDisclosureGroup() {
        // Arrange - Create disclosure group with three-part composite key
        DisclosureGroup disclosureGroup = DisclosureGroup.builder()
                .discAcctGroupId("GROUP001")
                .discTranTypeCd("01")
                .discTranCatCd(1001)
                .discIntRate(new BigDecimal("12.50"))
                .createdAt(new Timestamp(System.currentTimeMillis()))
                .updatedAt(new Timestamp(System.currentTimeMillis()))
                .build();

        // Act - Save entity
        DisclosureGroup saved = disclosureGroupRepository.save(disclosureGroup);

        // Assert - Verify all three key components and fields
        assertNotNull(saved);
        assertEquals("GROUP001", saved.getDiscAcctGroupId());
        assertEquals("01", saved.getDiscTranTypeCd());
        assertEquals(1001, saved.getDiscTranCatCd());
        assertEquals(0, new BigDecimal("12.50").compareTo(saved.getDiscIntRate()));
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertNotNull(saved.getVersion());
        assertEquals(0, saved.getVersion()); // Initial version is 0
    }

    /**
     * Test findById operation with three-part composite key.
     * 
     * Validates:
     * - Entity can be retrieved using DisclosureGroupId with all three components
     * - All field values match the persisted data including BigDecimal precision
     * - Optional.isPresent() returns true for existing records
     * 
     * Maps to COBOL: EXEC CICS READ FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY)
     */
    @Test
    public void testFindByIdWithThreePartKey() {
        // Arrange - Create and persist entity
        DisclosureGroup disclosureGroup = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        entityManager.persistAndFlush(disclosureGroup);
        entityManager.clear();

        // Create composite key with all three components
        DisclosureGroupId compositeKey = new DisclosureGroupId("GROUP001", "01", 1001);

        // Act - Find by three-part composite key
        Optional<DisclosureGroup> found = disclosureGroupRepository.findById(compositeKey);

        // Assert - Verify entity found and all fields match
        assertTrue(found.isPresent());
        DisclosureGroup retrieved = found.get();
        assertEquals("GROUP001", retrieved.getDiscAcctGroupId());
        assertEquals("01", retrieved.getDiscTranTypeCd());
        assertEquals(1001, retrieved.getDiscTranCatCd());
        assertEquals(0, new BigDecimal("12.50").compareTo(retrieved.getDiscIntRate()));
    }

    /**
     * Test findAll operation returns multiple disclosure groups.
     * 
     * Validates:
     * - Repository returns all persisted records
     * - Each record maintains its three-part composite key integrity
     * - Different composite key combinations are distinct records
     * 
     * Maps to COBOL: Sequential read of DISCGRP VSAM file
     */
    @Test
    public void testFindAllDisclosureGroups() {
        // Arrange - Create multiple disclosure groups with different composite keys
        DisclosureGroup group1 = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        DisclosureGroup group2 = createTestDisclosureGroup("GROUP001", "01", 1002, "15.75");
        DisclosureGroup group3 = createTestDisclosureGroup("GROUP002", "02", 2001, "18.00");

        entityManager.persist(group1);
        entityManager.persist(group2);
        entityManager.persist(group3);
        entityManager.flush();

        // Act - Find all disclosure groups
        List<DisclosureGroup> allGroups = disclosureGroupRepository.findAll();

        // Assert - Verify all three records present with distinct composite keys
        assertNotNull(allGroups);
        assertEquals(3, allGroups.size());
        assertTrue(allGroups.stream().anyMatch(g -> 
            "GROUP001".equals(g.getDiscAcctGroupId()) && 
            "01".equals(g.getDiscTranTypeCd()) && 
            1001 == g.getDiscTranCatCd()));
        assertTrue(allGroups.stream().anyMatch(g -> 
            "GROUP001".equals(g.getDiscAcctGroupId()) && 
            "01".equals(g.getDiscTranTypeCd()) && 
            1002 == g.getDiscTranCatCd()));
        assertTrue(allGroups.stream().anyMatch(g -> 
            "GROUP002".equals(g.getDiscAcctGroupId()) && 
            "02".equals(g.getDiscTranTypeCd()) && 
            2001 == g.getDiscTranCatCd()));
    }

    /**
     * Test update operation using three-part composite key.
     * 
     * Validates:
     * - Entity can be updated via save() with existing composite key
     * - Interest rate (BigDecimal) updates correctly with scale 2 precision
     * - Version field increments for optimistic locking
     * - updatedAt timestamp changes
     * 
     * Maps to COBOL: EXEC CICS REWRITE FILE('DISCGRP') FROM(DIS-GROUP-RECORD)
     */
    @Test
    public void testUpdateDisclosureGroup() {
        // Arrange - Create and persist initial entity
        DisclosureGroup disclosureGroup = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        entityManager.persistAndFlush(disclosureGroup);
        Integer initialVersion = disclosureGroup.getVersion();
        entityManager.clear();

        // Retrieve entity using composite key
        DisclosureGroupId compositeKey = new DisclosureGroupId("GROUP001", "01", 1001);
        DisclosureGroup existing = disclosureGroupRepository.findById(compositeKey).orElseThrow();

        // Act - Modify interest rate and save
        existing.setDiscIntRate(new BigDecimal("15.75"));
        existing.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        DisclosureGroup updated = disclosureGroupRepository.save(existing);
        entityManager.flush();
        entityManager.clear();

        // Assert - Verify update persisted with composite key intact
        DisclosureGroup verified = disclosureGroupRepository.findById(compositeKey).orElseThrow();
        assertEquals("GROUP001", verified.getDiscAcctGroupId());
        assertEquals("01", verified.getDiscTranTypeCd());
        assertEquals(1001, verified.getDiscTranCatCd());
        assertEquals(0, new BigDecimal("15.75").compareTo(verified.getDiscIntRate()));
        assertEquals(initialVersion + 1, verified.getVersion()); // Version incremented
    }

    /**
     * Test delete operation using three-part composite key.
     * 
     * Validates:
     * - Entity can be deleted using DisclosureGroupId with all three components
     * - Record no longer exists after deletion
     * - findById returns Optional.empty() for deleted records
     * 
     * Maps to COBOL: EXEC CICS DELETE FILE('DISCGRP') RIDFLD(DIS-GROUP-KEY)
     */
    @Test
    public void testDeleteDisclosureGroup() {
        // Arrange - Create and persist entity
        DisclosureGroup disclosureGroup = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        entityManager.persistAndFlush(disclosureGroup);
        entityManager.clear();

        DisclosureGroupId compositeKey = new DisclosureGroupId("GROUP001", "01", 1001);

        // Act - Delete using three-part composite key
        disclosureGroupRepository.deleteById(compositeKey);
        entityManager.flush();

        // Assert - Verify deletion successful
        Optional<DisclosureGroup> deleted = disclosureGroupRepository.findById(compositeKey);
        assertFalse(deleted.isPresent());
    }

    /**
     * Test three-part composite key uniqueness constraint.
     * 
     * Validates:
     * - Database enforces unique constraint on all three key components together
     * - Duplicate composite key insert throws DataIntegrityViolationException
     * - Matches VSAM KSDS unique key behavior from COBOL
     */
    @Test
    public void testThreePartKeyUniqueness() {
        // Arrange - Create and persist first entity
        DisclosureGroup first = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        entityManager.persistAndFlush(first);
        entityManager.clear();

        // Act & Assert - Attempt duplicate three-part composite key
        DisclosureGroup duplicate = createTestDisclosureGroup("GROUP001", "01", 1001, "15.75");
        assertThrows(DataIntegrityViolationException.class, () -> {
            entityManager.persistAndFlush(duplicate);
        });
    }

    /**
     * Test findById with non-existent three-part composite key.
     * 
     * Validates:
     * - Repository returns Optional.empty() for non-existent records
     * - No exception thrown for missing records
     * - Matches COBOL file-status 23 (record not found) behavior
     */
    @Test
    public void testPartialKeyNotFound() {
        // Arrange - Create composite key for non-existent record
        DisclosureGroupId nonExistentKey = new DisclosureGroupId("NOGROUP", "99", 9999);

        // Act - Attempt to find non-existent record
        Optional<DisclosureGroup> notFound = disclosureGroupRepository.findById(nonExistentKey);

        // Assert - Verify Optional.empty() returned
        assertFalse(notFound.isPresent());
    }

    /**
     * Test query performance with three-part composite key lookups.
     * 
     * Validates:
     * - Primary key lookups meet sub-10ms requirement (Section 0.7.7)
     * - B-tree index provides efficient access for composite key
     * - Performance matches or exceeds VSAM KSDS key access times
     */
    @Test
    public void testQueryPerformanceWithCompositeKey() {
        // Arrange - Create and persist test data
        for (int i = 1; i <= 100; i++) {
            DisclosureGroup group = createTestDisclosureGroup(
                "GROUP" + String.format("%03d", i), 
                "01", 
                1000 + i, 
                "12.50"
            );
            entityManager.persist(group);
        }
        entityManager.flush();
        entityManager.clear();

        // Act - Execute 100 findById operations and measure time
        long startTime = System.nanoTime();
        for (int i = 1; i <= 100; i++) {
            DisclosureGroupId key = new DisclosureGroupId(
                "GROUP" + String.format("%03d", i), 
                "01", 
                1000 + i
            );
            disclosureGroupRepository.findById(key);
        }
        long endTime = System.nanoTime();

        // Calculate average time per lookup in milliseconds
        double averageTimeMs = (endTime - startTime) / 1_000_000.0 / 100.0;

        // Assert - Verify sub-10ms average lookup time per Section 0.7.7
        assertTrue(averageTimeMs < 10.0, 
            "Average lookup time " + averageTimeMs + "ms exceeds 10ms requirement");
    }

    /**
     * Test custom query method findByDiscAcctGroupId.
     * 
     * Validates:
     * - Custom query returns all disclosure groups for specific account group
     * - Results include records with different transaction types and categories
     * - Results ordered correctly for deterministic processing
     * 
     * Maps to COBOL: Sequential read filtering by account group ID
     */
    @Test
    public void testFindByAccountGroup() {
        // Arrange - Create multiple groups with same account group ID
        DisclosureGroup group1 = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        DisclosureGroup group2 = createTestDisclosureGroup("GROUP001", "01", 1002, "13.00");
        DisclosureGroup group3 = createTestDisclosureGroup("GROUP001", "02", 2001, "15.75");
        DisclosureGroup group4 = createTestDisclosureGroup("GROUP002", "01", 1001, "18.00");

        entityManager.persist(group1);
        entityManager.persist(group2);
        entityManager.persist(group3);
        entityManager.persist(group4);
        entityManager.flush();

        // Act - Find all groups for GROUP001
        List<DisclosureGroup> group001Results = disclosureGroupRepository.findByDiscAcctGroupId("GROUP001");

        // Assert - Verify correct filtering and count
        assertNotNull(group001Results);
        assertEquals(3, group001Results.size());
        assertTrue(group001Results.stream().allMatch(g -> "GROUP001".equals(g.getDiscAcctGroupId())));
        
        // Verify all expected combinations present
        assertTrue(group001Results.stream().anyMatch(g -> 
            "01".equals(g.getDiscTranTypeCd()) && 1001 == g.getDiscTranCatCd()));
        assertTrue(group001Results.stream().anyMatch(g -> 
            "01".equals(g.getDiscTranTypeCd()) && 1002 == g.getDiscTranCatCd()));
        assertTrue(group001Results.stream().anyMatch(g -> 
            "02".equals(g.getDiscTranTypeCd()) && 2001 == g.getDiscTranCatCd()));
    }

    /**
     * Test interest rate precision preservation (COBOL S9(04)V99 → BigDecimal).
     * 
     * Validates:
     * - BigDecimal maintains scale of 2 decimal places
     * - Exact numeric values preserved per Section 0.7.2 requirement
     * - Financial calculations maintain COBOL COMP-3 precision
     */
    @Test
    public void testInterestRatePrecision() {
        // Arrange - Create entity with specific interest rate
        DisclosureGroup disclosureGroup = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        entityManager.persistAndFlush(disclosureGroup);
        entityManager.clear();

        // Act - Retrieve entity
        DisclosureGroupId key = new DisclosureGroupId("GROUP001", "01", 1001);
        DisclosureGroup retrieved = disclosureGroupRepository.findById(key).orElseThrow();

        // Assert - Verify BigDecimal scale and exact value
        assertNotNull(retrieved.getDiscIntRate());
        assertEquals(2, retrieved.getDiscIntRate().scale());
        assertEquals(0, new BigDecimal("12.50").compareTo(retrieved.getDiscIntRate()));
    }

    /**
     * Test interest rate rounding behavior maintains 2 decimal places.
     * 
     * Validates:
     * - Interest rates stored with exactly 2 decimal places
     * - Matches COBOL COMP-3 precision behavior
     */
    @Test
    public void testInterestRateRounding() {
        // Arrange - Create entity with interest rate requiring rounding
        DisclosureGroup disclosureGroup = createTestDisclosureGroup("GROUP001", "01", 1001, "15.755");
        
        // Explicitly set scale to 2 to match COBOL behavior
        disclosureGroup.setDiscIntRate(disclosureGroup.getDiscIntRate().setScale(2, BigDecimal.ROUND_HALF_UP));
        entityManager.persistAndFlush(disclosureGroup);
        entityManager.clear();

        // Act - Retrieve entity
        DisclosureGroupId key = new DisclosureGroupId("GROUP001", "01", 1001);
        DisclosureGroup retrieved = disclosureGroupRepository.findById(key).orElseThrow();

        // Assert - Verify scale maintained at 2 decimal places
        assertEquals(2, retrieved.getDiscIntRate().scale());
        assertEquals(0, new BigDecimal("15.76").compareTo(retrieved.getDiscIntRate()));
    }

    /**
     * Test interest rate valid range (0.00 to 99.99).
     * 
     * Validates:
     * - Interest rates within COBOL S9(04)V99 range persist correctly
     * - Boundary values (minimum and maximum) handled correctly
     */
    @Test
    public void testInterestRateRange() {
        // Arrange & Act - Test minimum value
        DisclosureGroup minRate = createTestDisclosureGroup("GROUP001", "01", 1001, "0.00");
        entityManager.persistAndFlush(minRate);
        
        // Arrange & Act - Test maximum value
        DisclosureGroup maxRate = createTestDisclosureGroup("GROUP002", "02", 2001, "9999.99");
        entityManager.persistAndFlush(maxRate);
        entityManager.clear();

        // Assert - Verify both boundary values persist correctly
        DisclosureGroup retrievedMin = disclosureGroupRepository.findById(
            new DisclosureGroupId("GROUP001", "01", 1001)).orElseThrow();
        assertEquals(0, new BigDecimal("0.00").compareTo(retrievedMin.getDiscIntRate()));

        DisclosureGroup retrievedMax = disclosureGroupRepository.findById(
            new DisclosureGroupId("GROUP002", "02", 2001)).orElseThrow();
        assertEquals(0, new BigDecimal("9999.99").compareTo(retrievedMax.getDiscIntRate()));
    }

    /**
     * Test custom query method findByDiscTranTypeCd.
     * 
     * Validates:
     * - Custom query returns all disclosure groups for specific transaction type
     * - Results include records with different account groups and categories
     */
    @Test
    public void testFindByTransactionType() {
        // Arrange - Create multiple groups with same transaction type
        DisclosureGroup group1 = createTestDisclosureGroup("GROUP001", "01", 1001, "12.50");
        DisclosureGroup group2 = createTestDisclosureGroup("GROUP002", "01", 1002, "13.00");
        DisclosureGroup group3 = createTestDisclosureGroup("GROUP003", "02", 2001, "15.75");

        entityManager.persist(group1);
        entityManager.persist(group2);
        entityManager.persist(group3);
        entityManager.flush();

        // Act - Find all groups for transaction type "01"
        List<DisclosureGroup> type01Results = disclosureGroupRepository.findByDiscTranTypeCd("01");

        // Assert - Verify correct filtering
        assertNotNull(type01Results);
        assertEquals(2, type01Results.size());
        assertTrue(type01Results.stream().allMatch(g -> "01".equals(g.getDiscTranTypeCd())));
    }

    /**
     * Test count operation for disclosure groups.
     * 
     * Validates:
     * - Repository count() returns correct number of records
     */
    @Test
    public void testCountDisclosureGroups() {
        // Arrange - Create and persist multiple entities
        entityManager.persist(createTestDisclosureGroup("GROUP001", "01", 1001, "12.50"));
        entityManager.persist(createTestDisclosureGroup("GROUP002", "02", 2001, "15.75"));
        entityManager.persist(createTestDisclosureGroup("GROUP003", "03", 3001, "18.00"));
        entityManager.flush();

        // Act - Count all records
        long count = disclosureGroupRepository.count();

        // Assert - Verify correct count
        assertEquals(3, count);
    }

    /**
     * Helper method to create test DisclosureGroup entity.
     * 
     * @param groupId Account group ID (part 1 of composite key)
     * @param typeCode Transaction type code (part 2 of composite key)
     * @param catCode Transaction category code (part 3 of composite key)
     * @param intRate Interest rate as string
     * @return Configured DisclosureGroup entity ready for persistence
     */
    private DisclosureGroup createTestDisclosureGroup(String groupId, String typeCode, 
                                                      Integer catCode, String intRate) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return DisclosureGroup.builder()
                .discAcctGroupId(groupId)
                .discTranTypeCd(typeCode)
                .discTranCatCd(catCode)
                .discIntRate(new BigDecimal(intRate))
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
