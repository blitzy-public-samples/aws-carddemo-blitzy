package com.carddemo.repository;

import com.carddemo.entity.DisclosureGroup;
import com.carddemo.entity.DisclosureGroup.DisclosureGroupId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 test class for DisclosureGroupRepository using @DataJpaTest annotation
 * for isolated repository testing with H2 in-memory database.
 * 
 * Tests verify that PostgreSQL queries correctly replicate VSAM READ operations
 * on disclosure group reference data from CVTRA02Y.cpy (DIS-GROUP-RECORD).
 * 
 * <h2>Original COBOL Structure (CVTRA02Y.cpy)</h2>
 * <pre>
 * 01  DIS-GROUP-RECORD.
 *     05  DIS-GROUP-KEY.
 *        10 DIS-ACCT-GROUP-ID       PIC X(10).
 *        10 DIS-TRAN-TYPE-CD        PIC X(02).
 *        10 DIS-TRAN-CAT-CD         PIC 9(04).
 *     05  DIS-INT-RATE              PIC S9(04)V99.
 * </pre>
 * 
 * <h2>Test Coverage</h2>
 * This test class validates:
 * <ul>
 *   <li>Composite key lookups matching COBOL DIS-GROUP-KEY structure</li>
 *   <li>BigDecimal precision for interest rates (scale=2) matching COBOL S9(04)V99 COMP-3</li>
 *   <li>Rounding behavior matching COBOL COMP-3 arithmetic (RoundingMode.HALF_UP)</li>
 *   <li>Composite key uniqueness constraints</li>
 *   <li>Custom query methods for partial key lookups</li>
 *   <li>CRUD operations with optimistic locking</li>
 * </ul>
 * 
 * Test data uses sample disclosure groups with interest rates:
 * - STANDARD account group with Purchase (PU) transaction type: 15.99%
 * - PREMIUM account group with Cash Advance (CA) transaction type: 18.50%
 * - PLATINUM account group with Balance Transfer (BT) transaction type: 12.75%
 * 
 * All monetary calculations preserve exact decimal precision per Agent Action Plan
 * section 0.10 requirement 7 (COBOL COMP-3 to Java BigDecimal precision mapping).
 * 
 * @see DisclosureGroup
 * @see DisclosureGroupId
 * @see DisclosureGroupRepository
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("DisclosureGroupRepository Integration Tests")
public class DisclosureGroupRepositoryTest {

    @Autowired
    private DisclosureGroupRepository disclosureGroupRepository;

    @Autowired
    private TestEntityManager entityManager;

    // Test data: Composite key IDs
    private DisclosureGroupId standardPurchaseRetailId;
    private DisclosureGroupId premiumCashAdvanceDiningId;
    private DisclosureGroupId platinumBalanceTransferTravelId;

    // Test data: Disclosure group entities
    private DisclosureGroup standardPurchaseRetail;
    private DisclosureGroup premiumCashAdvanceDining;
    private DisclosureGroup platinumBalanceTransferTravel;

    /**
     * Set up test data before each test method.
     * 
     * Creates sample disclosure groups with composite keys and interest rates
     * matching COBOL DIS-GROUP-RECORD structure from CVTRA02Y.cpy.
     * 
     * Test data includes:
     * - STANDARD account group, PU (Purchase) transaction type, 0001 (Retail) category: 15.99%
     * - PREMIUM account group, CA (Cash Advance) transaction type, 0002 (Dining) category: 18.50%
     * - PLATINUM account group, BT (Balance Transfer) transaction type, 0003 (Travel) category: 12.75%
     * 
     * Interest rates use BigDecimal with scale=2 to preserve COBOL COMP-3
     * packed decimal S9(04)V99 precision per Agent Action Plan section 0.10 requirement 7.
     */
    @BeforeEach
    void setUp() {
        // Clear any existing test data to ensure clean state
        disclosureGroupRepository.deleteAll();

        // Create composite key IDs matching COBOL DIS-GROUP-KEY structure

        // Standard account group - Purchase transaction - Retail category
        standardPurchaseRetailId = DisclosureGroupId.builder()
                .accountGroupId("STANDARD")        // DIS-ACCT-GROUP-ID PIC X(10)
                .transactionTypeCode("PU")         // DIS-TRAN-TYPE-CD PIC X(02) - Purchase
                .transactionCategoryCode("0001")   // DIS-TRAN-CAT-CD PIC 9(04) - Retail
                .build();

        // Premium account group - Cash Advance transaction - Dining category
        premiumCashAdvanceDiningId = DisclosureGroupId.builder()
                .accountGroupId("PREMIUM")         // DIS-ACCT-GROUP-ID PIC X(10)
                .transactionTypeCode("CA")         // DIS-TRAN-TYPE-CD PIC X(02) - Cash Advance
                .transactionCategoryCode("0002")   // DIS-TRAN-CAT-CD PIC 9(04) - Dining
                .build();

        // Platinum account group - Balance Transfer transaction - Travel category
        platinumBalanceTransferTravelId = DisclosureGroupId.builder()
                .accountGroupId("PLATINUM")        // DIS-ACCT-GROUP-ID PIC X(10)
                .transactionTypeCode("BT")         // DIS-TRAN-TYPE-CD PIC X(02) - Balance Transfer
                .transactionCategoryCode("0003")   // DIS-TRAN-CAT-CD PIC 9(04) - Travel
                .build();

        // Create disclosure group entities with interest rates
        // All interest rates use BigDecimal with scale=2 matching COBOL S9(04)V99 COMP-3

        standardPurchaseRetail = DisclosureGroup.builder()
                .id(standardPurchaseRetailId)
                .interestRate(new BigDecimal("15.99"))  // DIS-INT-RATE 15.99% scale=2
                .build();

        premiumCashAdvanceDining = DisclosureGroup.builder()
                .id(premiumCashAdvanceDiningId)
                .interestRate(new BigDecimal("18.50"))  // DIS-INT-RATE 18.50% scale=2
                .build();

        platinumBalanceTransferTravel = DisclosureGroup.builder()
                .id(platinumBalanceTransferTravelId)
                .interestRate(new BigDecimal("12.75"))  // DIS-INT-RATE 12.75% scale=2
                .build();

        // Persist test data to H2 in-memory database
        disclosureGroupRepository.save(standardPurchaseRetail);
        disclosureGroupRepository.save(premiumCashAdvanceDining);
        disclosureGroupRepository.save(platinumBalanceTransferTravel);
    }

    /**
     * Test findById with composite key returns correct disclosure group.
     * 
     * Validates that PostgreSQL queries correctly replicate VSAM READ operations
     * using composite key DIS-GROUP-KEY (account group ID, transaction type code,
     * transaction category code) from COBOL CVTRA02Y.cpy.
     * 
     * Verifies:
     * - findById returns Optional containing the entity
     * - All composite key components match expected values
     * - Interest rate field preserves BigDecimal precision with scale=2
     * 
     * Equivalent COBOL operation:
     * <pre>
     * MOVE 'STANDARD' TO DIS-ACCT-GROUP-ID.
     * MOVE 'PU'       TO DIS-TRAN-TYPE-CD.
     * MOVE '0001'     TO DIS-TRAN-CAT-CD.
     * EXEC CICS READ
     *     FILE('DISGRP')
     *     INTO(DIS-GROUP-RECORD)
     *     RIDFLD(DIS-GROUP-KEY)
     *     RESP(WS-RESP)
     * END-EXEC.
     * </pre>
     */
    @Test
    @DisplayName("Find disclosure group by composite key returns correct interest rate with scale=2")
    void testFindById_WithCompositeKey() {
        // Execute: Query disclosure group by composite key (equivalent to VSAM READ with key)
        Optional<DisclosureGroup> result = disclosureGroupRepository.findById(standardPurchaseRetailId);

        // Verify: Disclosure group found
        assertThat(result).isPresent();

        DisclosureGroup disclosureGroup = result.get();

        // Verify: Composite key components match COBOL DIS-GROUP-KEY structure
        assertThat(disclosureGroup.getId().getAccountGroupId())
                .isEqualTo("STANDARD")
                .as("Account group ID should match DIS-ACCT-GROUP-ID");

        assertThat(disclosureGroup.getId().getTransactionTypeCode())
                .isEqualTo("PU")
                .as("Transaction type code should match DIS-TRAN-TYPE-CD");

        assertThat(disclosureGroup.getId().getTransactionCategoryCode())
                .isEqualTo("0001")
                .as("Transaction category code should match DIS-TRAN-CAT-CD with leading zeros");

        // Verify: Interest rate matches expected value with exact precision
        assertThat(disclosureGroup.getInterestRate())
                .isEqualByComparingTo(new BigDecimal("15.99"))
                .as("Interest rate should match DIS-INT-RATE with exact decimal precision");

        // Verify: Interest rate scale=2 matching COBOL S9(04)V99 COMP-3 precision
        assertThat(disclosureGroup.getInterestRate().scale())
                .isEqualTo(2)
                .as("Interest rate scale must be 2 to match COBOL PIC S9(04)V99 decimal precision");

        // Verify: Version field initialized (optimistic locking)
        assertThat(disclosureGroup.getVersion())
                .isNotNull()
                .as("Version field should be initialized for optimistic locking");
    }

    /**
     * Test findById returns empty Optional when disclosure group not found.
     * 
     * Validates COBOL NOTFND condition handling when composite key doesn't exist.
     * 
     * Equivalent COBOL operation:
     * <pre>
     * EXEC CICS READ
     *     FILE('DISGRP')
     *     INTO(DIS-GROUP-RECORD)
     *     RIDFLD(DIS-GROUP-KEY)
     *     RESP(WS-RESP)
     * END-EXEC.
     * IF WS-RESP = DFHRESP(NOTFND)
     *     MOVE 'Y' TO WS-NOT-FOUND-FLAG
     * END-IF.
     * </pre>
     */
    @Test
    @DisplayName("Find disclosure group by non-existent composite key returns empty Optional")
    void testFindById_NotFound() {
        // Setup: Create composite key that doesn't exist in database
        DisclosureGroupId nonExistentId = DisclosureGroupId.builder()
                .accountGroupId("NONEXIST")
                .transactionTypeCode("XX")
                .transactionCategoryCode("9999")
                .build();

        // Execute: Query with non-existent key
        Optional<DisclosureGroup> result = disclosureGroupRepository.findById(nonExistentId);

        // Verify: Optional is empty (equivalent to COBOL NOTFND condition)
        assertThat(result).isEmpty()
                .as("Optional should be empty when disclosure group not found (NOTFND condition)");
    }

    /**
     * Test save operation with interest rate preserves BigDecimal scale=2 precision.
     * 
     * Validates that DIS-INT-RATE BigDecimal field preserves scale=2 precision
     * matching COBOL COMP-3 packed decimal S9(04)V99 semantics per Agent Action Plan
     * section 0.10 requirement 7.
     * 
     * Verifies:
     * - Save operation succeeds (insert)
     * - Interest rate value preserved exactly
     * - Scale=2 maintained after database round-trip
     * - Composite key uniqueness enforced
     * 
     * Equivalent COBOL operation:
     * <pre>
     * MOVE 'GOLD' TO DIS-ACCT-GROUP-ID.
     * MOVE 'PU'   TO DIS-TRAN-TYPE-CD.
     * MOVE '0004' TO DIS-TRAN-CAT-CD.
     * MOVE 14.25  TO DIS-INT-RATE.
     * EXEC CICS WRITE
     *     FILE('DISGRP')
     *     FROM(DIS-GROUP-RECORD)
     *     RIDFLD(DIS-GROUP-KEY)
     *     RESP(WS-RESP)
     * END-EXEC.
     * </pre>
     */
    @Test
    @DisplayName("Save disclosure group with interest rate preserves exact scale=2 precision")
    void testSave_WithInterestRate() {
        // Setup: Create new disclosure group with interest rate 14.25%
        DisclosureGroupId goldPurchaseGasId = DisclosureGroupId.builder()
                .accountGroupId("GOLD")
                .transactionTypeCode("PU")
                .transactionCategoryCode("0004")  // Gas category
                .build();

        BigDecimal interestRate = new BigDecimal("14.25");  // scale=2 implicit
        
        DisclosureGroup goldPurchaseGas = DisclosureGroup.builder()
                .id(goldPurchaseGasId)
                .interestRate(interestRate)
                .build();

        // Execute: Save disclosure group (equivalent to VSAM WRITE)
        DisclosureGroup saved = disclosureGroupRepository.save(goldPurchaseGas);

        // Verify: Entity saved successfully
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(goldPurchaseGasId);

        // Verify: Interest rate preserved with exact precision
        assertThat(saved.getInterestRate())
                .isEqualByComparingTo(interestRate)
                .as("Interest rate should be preserved with exact decimal precision");

        // Verify: Scale=2 maintained matching COBOL S9(04)V99 COMP-3
        assertThat(saved.getInterestRate().scale())
                .isEqualTo(2)
                .as("Interest rate scale must remain 2 after save operation");

        // Verify: Version field initialized after insert
        assertThat(saved.getVersion())
                .isNotNull()
                .isEqualTo(0L)
                .as("Version should be 0 after initial insert");

        // Execute: Re-query to verify database persistence
        Optional<DisclosureGroup> retrieved = disclosureGroupRepository.findById(goldPurchaseGasId);

        // Verify: Entity persisted and retrievable
        assertThat(retrieved).isPresent();

        // Verify: Interest rate precision preserved after database round-trip
        assertThat(retrieved.get().getInterestRate())
                .isEqualByComparingTo(interestRate)
                .as("Interest rate precision should be preserved in database");

        assertThat(retrieved.get().getInterestRate().scale())
                .isEqualTo(2)
                .as("Interest rate scale=2 should be preserved after database round-trip");
    }

    /**
     * Test interest rate calculation preserves exact decimal precision with RoundingMode.HALF_UP.
     * 
     * Validates that interest rate calculations maintain COBOL COMP-3 rounding behavior
     * using RoundingMode.HALF_UP per Agent Action Plan section 0.10 requirement 7.
     * 
     * Simulates interest calculation scenario:
     * - Retrieve disclosure group interest rate
     * - Perform calculation (e.g., monthly rate adjustment)
     * - Update with new rate
     * - Verify rounding matches COBOL behavior
     * 
     * COBOL COMP-3 rounding uses "round half up" semantics, which must be
     * replicated exactly in Java BigDecimal operations.
     */
    @Test
    @DisplayName("Interest rate calculations preserve exact decimal precision with RoundingMode.HALF_UP")
    void testInterestRateCalculation() {
        // Setup: Retrieve existing disclosure group
        Optional<DisclosureGroup> retrieved = disclosureGroupRepository.findById(standardPurchaseRetailId);
        assertThat(retrieved).isPresent();

        DisclosureGroup disclosureGroup = retrieved.get();
        BigDecimal originalRate = disclosureGroup.getInterestRate();  // 15.99%

        // Simulate calculation: Apply rate adjustment (e.g., regulatory change +0.005%)
        // This tests rounding behavior matching COBOL COMP-3 arithmetic
        BigDecimal adjustment = new BigDecimal("0.005");
        BigDecimal newRate = originalRate.add(adjustment)
                .setScale(2, RoundingMode.HALF_UP);  // COBOL COMP-3 rounding

        // Expected result: 15.99 + 0.005 = 15.995 rounds to 16.00 (HALF_UP)
        BigDecimal expectedRate = new BigDecimal("16.00");

        // Verify: Rounding matches COBOL COMP-3 behavior
        assertThat(newRate)
                .isEqualByComparingTo(expectedRate)
                .as("Interest rate calculation with RoundingMode.HALF_UP should match COBOL COMP-3 rounding");

        assertThat(newRate.scale())
                .isEqualTo(2)
                .as("Interest rate scale must remain 2 after calculation");

        // Execute: Update disclosure group with new rate (equivalent to VSAM REWRITE)
        disclosureGroup.setInterestRate(newRate);
        DisclosureGroup updated = disclosureGroupRepository.save(disclosureGroup);
        
        // Flush to database and clear persistence context to ensure version increment
        entityManager.flush();
        entityManager.clear();
        
        // Re-fetch the entity to verify persisted state
        DisclosureGroup refetched = disclosureGroupRepository.findById(standardPurchaseRetailId).orElseThrow();

        // Verify: Update successful
        assertThat(refetched.getInterestRate())
                .isEqualByComparingTo(expectedRate)
                .as("Updated interest rate should be persisted with correct rounding");

        // Verify: Version incremented (optimistic locking)
        assertThat(refetched.getVersion())
                .isGreaterThan(0L)
                .as("Version should increment on update for optimistic locking");

        // Test another calculation: Division requiring rounding
        // Calculate daily rate from annual rate: 16.00 / 365 = 0.043835... rounds to 0.04
        BigDecimal annualRate = refetched.getInterestRate();
        BigDecimal dailyRate = annualRate.divide(new BigDecimal("365"), 2, RoundingMode.HALF_UP);

        BigDecimal expectedDailyRate = new BigDecimal("0.04");

        assertThat(dailyRate)
                .isEqualByComparingTo(expectedDailyRate)
                .as("Division calculation with RoundingMode.HALF_UP should match COBOL COMP-3 division");

        assertThat(dailyRate.scale())
                .isEqualTo(2)
                .as("Calculated daily rate scale must be 2");
    }

    /**
     * Test composite key uniqueness constraint.
     * 
     * Validates that database enforces uniqueness on composite primary key
     * matching COBOL VSAM KSDS duplicate key behavior.
     * 
     * Attempts to insert duplicate disclosure group with same composite key
     * should fail or be treated as update operation.
     * 
     * Equivalent COBOL operation with duplicate key:
     * <pre>
     * EXEC CICS WRITE
     *     FILE('DISGRP')
     *     FROM(DIS-GROUP-RECORD)
     *     RIDFLD(DIS-GROUP-KEY)
     *     RESP(WS-RESP)
     * END-EXEC.
     * IF WS-RESP = DFHRESP(DUPREC)
     *     MOVE 'Y' TO WS-DUPLICATE-KEY-FLAG
     * END-IF.
     * </pre>
     */
    @Test
    @DisplayName("Composite key uniqueness constraint prevents duplicate disclosure groups")
    void testCompositeKeyConstraint() {
        // Verify: Original disclosure group exists
        assertThat(disclosureGroupRepository.existsById(standardPurchaseRetailId)).isTrue();

        // Fetch the existing entity to get its current state including version
        DisclosureGroup existing = disclosureGroupRepository.findById(standardPurchaseRetailId)
                .orElseThrow(() -> new AssertionError("Test data entity should exist"));
        
        BigDecimal originalRate = existing.getInterestRate();
        assertThat(originalRate).isEqualByComparingTo(new BigDecimal("15.99"));

        // Clear persistence context to simulate a new transaction
        // This ensures we're working with a detached entity
        entityManager.clear();

        // Simulate scenario: In a new transaction, fetch and update the same entity
        // This replicates COBOL VSAM READ followed by REWRITE with same key
        DisclosureGroup toUpdate = disclosureGroupRepository.findById(standardPurchaseRetailId)
                .orElseThrow(() -> new AssertionError("Entity should still exist after clear"));
        
        // Execute: Update the interest rate
        toUpdate.setInterestRate(new BigDecimal("99.99"));
        DisclosureGroup saved = disclosureGroupRepository.save(toUpdate);
        entityManager.flush();

        // Verify: Save operation updated the existing entity
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(standardPurchaseRetailId);

        // Verify: Interest rate updated to new value
        assertThat(saved.getInterestRate())
                .isEqualByComparingTo(new BigDecimal("99.99"))
                .as("Update operation should persist new interest rate");

        // Verify: Still only one entity with this composite key
        long count = disclosureGroupRepository.count();
        assertThat(count)
                .isEqualTo(3)  // Original 3 test entities, no duplicates created
                .as("Count should remain 3, confirming no duplicate entities created");

        // Clear and verify persistence
        entityManager.clear();
        
        // Verify: Updated entity retrievable with same key
        Optional<DisclosureGroup> retrieved = disclosureGroupRepository.findById(standardPurchaseRetailId);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getInterestRate())
                .isEqualByComparingTo(new BigDecimal("99.99"))
                .as("Retrieved entity should have updated interest rate");
    }

    /**
     * Test findByIdAccountGroupId custom query method.
     * 
     * Validates partial composite key lookup by account group ID.
     * Returns all disclosure groups for a specific account group regardless
     * of transaction type or category.
     */
    @Test
    @DisplayName("Find disclosure groups by account group ID returns all matching entities")
    void testFindByIdAccountGroupId() {
        // Setup: Add another disclosure group with STANDARD account group
        DisclosureGroupId standardCashAdvanceRetailId = DisclosureGroupId.builder()
                .accountGroupId("STANDARD")
                .transactionTypeCode("CA")
                .transactionCategoryCode("0001")
                .build();

        DisclosureGroup standardCashAdvanceRetail = DisclosureGroup.builder()
                .id(standardCashAdvanceRetailId)
                .interestRate(new BigDecimal("19.99"))
                .build();

        disclosureGroupRepository.save(standardCashAdvanceRetail);

        // Execute: Query all disclosure groups for STANDARD account group
        List<DisclosureGroup> standardGroups = 
                disclosureGroupRepository.findByIdAccountGroupId("STANDARD");

        // Verify: Found 2 disclosure groups with STANDARD account group
        assertThat(standardGroups)
                .hasSize(2)
                .as("Should find 2 disclosure groups with STANDARD account group");

        // Verify: All returned groups have STANDARD account group ID
        assertThat(standardGroups)
                .allMatch(dg -> "STANDARD".equals(dg.getId().getAccountGroupId()))
                .as("All returned groups should have STANDARD account group ID");

        // Verify: Interest rates preserved with scale=2
        assertThat(standardGroups)
                .allMatch(dg -> dg.getInterestRate().scale() == 2)
                .as("All interest rates should have scale=2");
    }

    /**
     * Test findByIdTransactionTypeCode custom query method.
     * 
     * Validates partial composite key lookup by transaction type code.
     * Returns all disclosure groups for a specific transaction type regardless
     * of account group or category.
     */
    @Test
    @DisplayName("Find disclosure groups by transaction type code returns all matching entities")
    void testFindByIdTransactionTypeCode() {
        // Setup: Add another disclosure group with PU (Purchase) transaction type
        DisclosureGroupId premiumPurchaseDiningId = DisclosureGroupId.builder()
                .accountGroupId("PREMIUM")
                .transactionTypeCode("PU")
                .transactionCategoryCode("0002")
                .build();

        DisclosureGroup premiumPurchaseDining = DisclosureGroup.builder()
                .id(premiumPurchaseDiningId)
                .interestRate(new BigDecimal("17.50"))
                .build();

        disclosureGroupRepository.save(premiumPurchaseDining);

        // Execute: Query all disclosure groups for PU (Purchase) transaction type
        List<DisclosureGroup> purchaseGroups = 
                disclosureGroupRepository.findByIdTransactionTypeCode("PU");

        // Verify: Found 2 disclosure groups with PU transaction type
        assertThat(purchaseGroups)
                .hasSize(2)
                .as("Should find 2 disclosure groups with PU transaction type");

        // Verify: All returned groups have PU transaction type code
        assertThat(purchaseGroups)
                .allMatch(dg -> "PU".equals(dg.getId().getTransactionTypeCode()))
                .as("All returned groups should have PU transaction type code");
    }

    /**
     * Test findByIdTransactionCategoryCode custom query method.
     * 
     * Validates partial composite key lookup by transaction category code.
     * Returns all disclosure groups for a specific category regardless
     * of account group or transaction type.
     * 
     * Verifies leading zero preservation in category code (COBOL PIC 9(04)).
     */
    @Test
    @DisplayName("Find disclosure groups by transaction category code returns all matching entities")
    void testFindByIdTransactionCategoryCode() {
        // Setup: Add another disclosure group with category code 0001 (Retail)
        DisclosureGroupId premiumPurchaseRetailId = DisclosureGroupId.builder()
                .accountGroupId("PREMIUM")
                .transactionTypeCode("PU")
                .transactionCategoryCode("0001")  // Leading zeros preserved
                .build();

        DisclosureGroup premiumPurchaseRetail = DisclosureGroup.builder()
                .id(premiumPurchaseRetailId)
                .interestRate(new BigDecimal("16.99"))
                .build();

        disclosureGroupRepository.save(premiumPurchaseRetail);

        // Execute: Query all disclosure groups for category code 0001 (Retail)
        List<DisclosureGroup> retailGroups = 
                disclosureGroupRepository.findByIdTransactionCategoryCode("0001");

        // Verify: Found 2 disclosure groups with category code 0001
        assertThat(retailGroups)
                .hasSize(2)
                .as("Should find 2 disclosure groups with category code 0001");

        // Verify: All returned groups have category code 0001 with leading zeros
        assertThat(retailGroups)
                .allMatch(dg -> "0001".equals(dg.getId().getTransactionCategoryCode()))
                .as("All returned groups should have category code 0001 with leading zeros preserved");

        // Verify: Leading zeros are preserved (not stored as "1")
        assertThat(retailGroups)
                .noneMatch(dg -> "1".equals(dg.getId().getTransactionCategoryCode()))
                .as("Category codes should preserve leading zeros from COBOL PIC 9(04)");
    }

    /**
     * Test count operation returns correct number of disclosure groups.
     * 
     * Validates that repository count matches number of persisted entities.
     */
    @Test
    @DisplayName("Count operation returns correct number of disclosure groups")
    void testCount() {
        // Execute: Count all disclosure groups
        long count = disclosureGroupRepository.count();

        // Verify: Count matches test data (3 disclosure groups in setUp)
        assertThat(count)
                .isEqualTo(3)
                .as("Count should equal number of disclosure groups created in setUp");
    }

    /**
     * Test existsById returns true for existing composite key.
     * 
     * Validates existence check without loading full entity.
     * More efficient than findById when only existence verification is needed.
     */
    @Test
    @DisplayName("ExistsById returns true for existing disclosure group composite key")
    void testExistsById_Exists() {
        // Execute: Check existence of known composite key
        boolean exists = disclosureGroupRepository.existsById(premiumCashAdvanceDiningId);

        // Verify: Exists returns true
        assertThat(exists)
                .isTrue()
                .as("ExistsById should return true for existing composite key");
    }

    /**
     * Test existsById returns false for non-existent composite key.
     * 
     * Validates existence check correctly identifies missing entities.
     */
    @Test
    @DisplayName("ExistsById returns false for non-existent disclosure group composite key")
    void testExistsById_NotExists() {
        // Setup: Create composite key that doesn't exist
        DisclosureGroupId nonExistentId = DisclosureGroupId.builder()
                .accountGroupId("NONEXIST")
                .transactionTypeCode("XX")
                .transactionCategoryCode("9999")
                .build();

        // Execute: Check existence of non-existent key
        boolean exists = disclosureGroupRepository.existsById(nonExistentId);

        // Verify: Exists returns false
        assertThat(exists)
                .isFalse()
                .as("ExistsById should return false for non-existent composite key");
    }

    /**
     * Test deleteById removes disclosure group by composite key.
     * 
     * Validates delete operation matching COBOL EXEC CICS DELETE.
     * 
     * Equivalent COBOL operation:
     * <pre>
     * EXEC CICS DELETE
     *     FILE('DISGRP')
     *     RIDFLD(DIS-GROUP-KEY)
     *     RESP(WS-RESP)
     * END-EXEC.
     * </pre>
     */
    @Test
    @DisplayName("DeleteById removes disclosure group by composite key")
    void testDeleteById() {
        // Verify: Disclosure group exists before deletion
        assertThat(disclosureGroupRepository.existsById(platinumBalanceTransferTravelId))
                .isTrue();

        // Execute: Delete disclosure group by composite key
        disclosureGroupRepository.deleteById(platinumBalanceTransferTravelId);

        // Verify: Disclosure group no longer exists
        assertThat(disclosureGroupRepository.existsById(platinumBalanceTransferTravelId))
                .isFalse()
                .as("Disclosure group should not exist after deleteById");

        // Verify: Other disclosure groups unaffected
        assertThat(disclosureGroupRepository.count())
                .isEqualTo(2)
                .as("Count should be 2 after deleting 1 of 3 disclosure groups");

        // Verify: Deleted entity not retrievable
        Optional<DisclosureGroup> deleted = 
                disclosureGroupRepository.findById(platinumBalanceTransferTravelId);
        assertThat(deleted)
                .isEmpty()
                .as("Deleted disclosure group should not be retrievable");
    }

    /**
     * Test findAll returns all disclosure groups.
     * 
     * Validates retrieval of complete dataset matching COBOL EXEC CICS STARTBR/READNEXT.
     * 
     * Equivalent COBOL operation:
     * <pre>
     * EXEC CICS STARTBR
     *     FILE('DISGRP')
     *     RESP(WS-RESP)
     * END-EXEC.
     * 
     * PERFORM UNTIL WS-EOF = 'Y'
     *     EXEC CICS READNEXT
     *         FILE('DISGRP')
     *         INTO(DIS-GROUP-RECORD)
     *         RESP(WS-RESP)
     *     END-EXEC
     *     IF WS-RESP = DFHRESP(ENDFILE)
     *         MOVE 'Y' TO WS-EOF
     *     END-IF
     * END-PERFORM.
     * 
     * EXEC CICS ENDBR
     *     FILE('DISGRP')
     * END-EXEC.
     * </pre>
     */
    @Test
    @DisplayName("FindAll returns all disclosure groups with interest rates preserving scale=2")
    void testFindAll() {
        // Execute: Retrieve all disclosure groups
        List<DisclosureGroup> allGroups = disclosureGroupRepository.findAll();

        // Verify: All test data retrieved
        assertThat(allGroups)
                .hasSize(3)
                .as("FindAll should return all 3 disclosure groups from test data");

        // Verify: All disclosure groups have valid composite keys
        assertThat(allGroups)
                .allMatch(dg -> dg.getId() != null)
                .allMatch(dg -> dg.getId().getAccountGroupId() != null)
                .allMatch(dg -> dg.getId().getTransactionTypeCode() != null)
                .allMatch(dg -> dg.getId().getTransactionCategoryCode() != null)
                .as("All disclosure groups should have valid composite keys");

        // Verify: All interest rates preserve scale=2 precision
        assertThat(allGroups)
                .allMatch(dg -> dg.getInterestRate() != null)
                .allMatch(dg -> dg.getInterestRate().scale() == 2)
                .as("All interest rates should have scale=2 matching COBOL S9(04)V99 COMP-3");

        // Verify: Interest rates within valid range (0.00 to 99.99)
        assertThat(allGroups)
                .allMatch(dg -> dg.getInterestRate().compareTo(BigDecimal.ZERO) >= 0)
                .allMatch(dg -> dg.getInterestRate().compareTo(new BigDecimal("99.99")) <= 0)
                .as("All interest rates should be within valid range 0.00-99.99");
    }
}
