package com.carddemo.repository;

import com.carddemo.entity.TransactionCategoryBalance;
import com.carddemo.entity.TransactionCategoryBalance.TransactionCategoryBalanceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 test class for TransactionCategoryBalanceRepository using @DataJpaTest annotation
 * for isolated repository testing with H2 in-memory database.
 * 
 * Tests verify that PostgreSQL queries correctly replicate VSAM READ/WRITE operations on 
 * transaction category balance data from CVTRA01Y.cpy (TRAN-CAT-BAL-RECORD).
 * 
 * Key Test Objectives:
 * 1. Validate composite key query (findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode)
 *    matching TRAN-CAT-KEY structure (TRANCAT-ACCT-ID PIC 9(11), TRANCAT-TYPE-CD PIC X(02), 
 *    TRANCAT-CD PIC 9(04))
 * 2. Verify BigDecimal precision for TRAN-CAT-BAL PIC S9(09)V99 balance field with scale=2 
 *    and RoundingMode.HALF_UP matching COBOL COMP-3 packed decimal behavior
 * 3. Ensure balance aggregation and updates work correctly with exact decimal precision
 * 
 * Per Agent Action Plan section 0.10 requirement 7: All monetary fields must use BigDecimal
 * with explicit scale matching COBOL V decimal positions and RoundingMode.HALF_UP.
 * 
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceRepository
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("TransactionCategoryBalanceRepository Tests")
class TransactionCategoryBalanceRepositoryTest {

    @Autowired
    private TransactionCategoryBalanceRepository repository;

    private TransactionCategoryBalance testBalance1;
    private TransactionCategoryBalance testBalance2;
    private TransactionCategoryBalance testBalance3;

    /**
     * Set up test data before each test method.
     * 
     * Creates sample transaction category balance records matching COBOL TRAN-CAT-BAL-RECORD
     * structure with:
     * - Composite keys combining account ID, transaction type code, and category code
     * - BigDecimal balance values with scale=2 precision
     * 
     * Test data represents typical scenarios:
     * - Balance 1: Account 123456789, Type 'DB' (Debit), Category '5000', Balance $1234.56
     * - Balance 2: Account 123456789, Type 'CR' (Credit), Category '5001', Balance $2500.00
     * - Balance 3: Account 987654321, Type 'DB' (Debit), Category '5000', Balance $999.99
     */
    @BeforeEach
    void setUp() {
        // Clear any existing data
        repository.deleteAll();

        // Create test balance 1: Account 123456789, Type DB, Category 5000, Balance $1234.56
        TransactionCategoryBalanceId id1 = TransactionCategoryBalanceId.builder()
                .accountId(12345678901L)
                .transactionTypeCode("DB")
                .categoryCode("5000")
                .build();

        testBalance1 = TransactionCategoryBalance.builder()
                .id(id1)
                .balance(new BigDecimal("1234.56").setScale(2, RoundingMode.HALF_UP))
                .build();

        // Create test balance 2: Account 123456789, Type CR, Category 5001, Balance $2500.00
        TransactionCategoryBalanceId id2 = TransactionCategoryBalanceId.builder()
                .accountId(12345678901L)
                .transactionTypeCode("CR")
                .categoryCode("5001")
                .build();

        testBalance2 = TransactionCategoryBalance.builder()
                .id(id2)
                .balance(new BigDecimal("2500.00").setScale(2, RoundingMode.HALF_UP))
                .build();

        // Create test balance 3: Account 987654321, Type DB, Category 5000, Balance $999.99
        TransactionCategoryBalanceId id3 = TransactionCategoryBalanceId.builder()
                .accountId(98765432109L)
                .transactionTypeCode("DB")
                .categoryCode("5000")
                .build();

        testBalance3 = TransactionCategoryBalance.builder()
                .id(id3)
                .balance(new BigDecimal("999.99").setScale(2, RoundingMode.HALF_UP))
                .build();

        // Persist test data
        repository.save(testBalance1);
        repository.save(testBalance2);
        repository.save(testBalance3);
        repository.flush();
    }

    /**
     * Test composite key query matching TRAN-CAT-KEY structure from COBOL copybook.
     * 
     * Validates that findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode correctly
     * retrieves balance records using all three key components:
     * - TRANCAT-ACCT-ID PIC 9(11) → accountId parameter
     * - TRANCAT-TYPE-CD PIC X(02) → transactionTypeCode parameter
     * - TRANCAT-CD PIC 9(04) → categoryCode parameter
     * 
     * Maps to COBOL VSAM READ operation:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * MOVE type-code TO TRANCAT-TYPE-CD
     * MOVE category-code TO TRANCAT-CD
     * EXEC CICS READ FILE(TCATBAL) INTO(balance-record) RIDFLD(TRAN-CAT-KEY)
     * 
     * Verifies returned balance has correct BigDecimal scale=2 matching COBOL S9(09)V99.
     */
    @Test
    @DisplayName("Find balance by composite key returns correct balance with scale=2 precision")
    void testFindByAccountIdAndTypeCodeAndCategoryCode_Success() {
        // When: Query by composite key components (account ID, transaction type code, category code)
        Optional<TransactionCategoryBalance> result = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        12345678901L, "DB", "5000");

        // Then: Balance record is found
        assertThat(result).isPresent();

        TransactionCategoryBalance balance = result.get();

        // Verify composite key components match TRAN-CAT-KEY structure
        assertThat(balance.getId().getAccountId()).isEqualTo(12345678901L);
        assertThat(balance.getId().getTransactionTypeCode()).isEqualTo("DB");
        assertThat(balance.getId().getCategoryCode()).isEqualTo("5000");

        // Verify balance value and precision match COBOL COMP-3 S9(09)V99 semantics
        assertThat(balance.getBalance()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(balance.getBalance().scale()).isEqualTo(2); // Must be scale=2 per requirement 7
    }

    /**
     * Test composite key query with non-existent record.
     * 
     * Validates that query returns empty Optional when no record matches the composite key,
     * replicating COBOL VSAM READ with NOTFND condition handling:
     * EXEC CICS READ FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY) RESP(ws-resp)
     * IF ws-resp = DFHRESP(NOTFND) THEN...
     */
    @Test
    @DisplayName("Find balance by composite key returns empty when record not found")
    void testFindByAccountIdAndTypeCodeAndCategoryCode_NotFound() {
        // When: Query by non-existent composite key
        Optional<TransactionCategoryBalance> result = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        99999999999L, "XX", "9999");

        // Then: No balance record is found
        assertThat(result).isEmpty();
    }

    /**
     * Test save operation persists balance with correct BigDecimal precision.
     * 
     * Validates that repository.save() correctly persists TRAN-CAT-BAL field with scale=2,
     * replicating COBOL VSAM WRITE operation:
     * MOVE balance-value TO TRAN-CAT-BAL
     * EXEC CICS WRITE FILE(TCATBAL) FROM(balance-record) RIDFLD(TRAN-CAT-KEY)
     * 
     * Ensures BigDecimal scale=2 is maintained in database matching COBOL S9(09)V99 precision.
     */
    @Test
    @DisplayName("Save operation persists balance with scale=2 precision matching COBOL COMP-3")
    void testSave_PersistsBalance() {
        // Given: New balance record with precise BigDecimal value
        TransactionCategoryBalanceId newId = TransactionCategoryBalanceId.builder()
                .accountId(55555555555L)
                .transactionTypeCode("PY")
                .categoryCode("6000")
                .build();

        BigDecimal balanceValue = new BigDecimal("3456.78").setScale(2, RoundingMode.HALF_UP);

        TransactionCategoryBalance newBalance = TransactionCategoryBalance.builder()
                .id(newId)
                .balance(balanceValue)
                .build();

        // When: Save the new balance record
        TransactionCategoryBalance savedBalance = repository.save(newBalance);
        repository.flush();

        // Then: Balance is persisted with correct precision
        assertThat(savedBalance).isNotNull();
        assertThat(savedBalance.getId()).isEqualTo(newId);
        assertThat(savedBalance.getBalance()).isEqualByComparingTo(balanceValue);
        assertThat(savedBalance.getBalance().scale()).isEqualTo(2);

        // Verify retrieval maintains precision
        Optional<TransactionCategoryBalance> retrieved = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        55555555555L, "PY", "6000");

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getBalance()).isEqualByComparingTo(new BigDecimal("3456.78"));
        assertThat(retrieved.get().getBalance().scale()).isEqualTo(2);
    }

    /**
     * Test balance update preserves exact decimal precision.
     * 
     * Validates that TRAN-CAT-BAL updates maintain scale=2 precision throughout update operations,
     * replicating COBOL VSAM REWRITE:
     * ADD update-amount TO TRAN-CAT-BAL
     * EXEC CICS REWRITE FILE(TCATBAL) FROM(balance-record)
     * 
     * Ensures updates preserve COBOL S9(09)V99 COMP-3 decimal precision per section 0.10
     * requirement 7: "Use BigDecimal with explicit scale matching COBOL V decimal positions."
     */
    @Test
    @DisplayName("Balance updates preserve exact decimal precision with scale=2")
    void testBalanceUpdate_PreservesScale() {
        // Given: Existing balance record
        Optional<TransactionCategoryBalance> existing = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        12345678901L, "DB", "5000");

        assertThat(existing).isPresent();
        TransactionCategoryBalance balance = existing.get();
        BigDecimal originalBalance = balance.getBalance();

        // When: Update balance with precise decimal value
        BigDecimal updateAmount = new BigDecimal("500.25").setScale(2, RoundingMode.HALF_UP);
        BigDecimal newBalance = originalBalance.add(updateAmount);
        balance.setBalance(newBalance);

        TransactionCategoryBalance updatedBalance = repository.save(balance);
        repository.flush();

        // Then: Updated balance maintains scale=2 precision
        assertThat(updatedBalance.getBalance()).isEqualByComparingTo(new BigDecimal("1734.81"));
        assertThat(updatedBalance.getBalance().scale()).isEqualTo(2);

        // Verify retrieval after update maintains precision
        Optional<TransactionCategoryBalance> retrieved = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        12345678901L, "DB", "5000");

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getBalance()).isEqualByComparingTo(new BigDecimal("1734.81"));
        assertThat(retrieved.get().getBalance().scale()).isEqualTo(2);
    }

    /**
     * Test balance arithmetic uses correct rounding mode.
     * 
     * Validates that balance calculations use RoundingMode.HALF_UP to match COBOL COMP-3 
     * packed decimal rounding behavior per Agent Action Plan section 0.10 requirement 7:
     * "Use RoundingMode.HALF_UP to match COBOL rounding behavior."
     * 
     * Tests rounding scenarios:
     * - Values ending in .5 or higher round up
     * - Values ending below .5 round down
     * - Result scale always remains 2 for monetary precision
     * 
     * Ensures division operations maintain COBOL-equivalent precision.
     */
    @Test
    @DisplayName("Balance arithmetic uses RoundingMode.HALF_UP matching COBOL COMP-3 rounding")
    void testBalanceArithmetic_UsesCorrectRounding() {
        // Given: Balance that will require rounding in division
        TransactionCategoryBalanceId testId = TransactionCategoryBalanceId.builder()
                .accountId(11111111111L)
                .transactionTypeCode("FE")
                .categoryCode("7000")
                .build();

        BigDecimal initialBalance = new BigDecimal("1000.00").setScale(2, RoundingMode.HALF_UP);

        TransactionCategoryBalance balance = TransactionCategoryBalance.builder()
                .id(testId)
                .balance(initialBalance)
                .build();

        repository.save(balance);
        repository.flush();

        // When: Perform division that requires rounding (divide by 3)
        // 1000.00 / 3 = 333.333... should round to 333.33 with HALF_UP
        BigDecimal divisor = new BigDecimal("3");
        BigDecimal dividedBalance = initialBalance.divide(divisor, 2, RoundingMode.HALF_UP);

        balance.setBalance(dividedBalance);
        repository.save(balance);
        repository.flush();

        // Then: Result is correctly rounded with HALF_UP mode
        Optional<TransactionCategoryBalance> retrieved = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        11111111111L, "FE", "7000");

        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getBalance()).isEqualByComparingTo(new BigDecimal("333.33"));
        assertThat(retrieved.get().getBalance().scale()).isEqualTo(2);

        // Test HALF_UP rounding behavior: 333.335 rounds to 333.34 (up)
        BigDecimal valueRequiringRoundUp = new BigDecimal("333.335");
        BigDecimal roundedUp = valueRequiringRoundUp.setScale(2, RoundingMode.HALF_UP);
        assertThat(roundedUp).isEqualByComparingTo(new BigDecimal("333.34"));

        // Test HALF_UP rounding behavior: 333.334 rounds to 333.33 (down)
        BigDecimal valueRequiringRoundDown = new BigDecimal("333.334");
        BigDecimal roundedDown = valueRequiringRoundDown.setScale(2, RoundingMode.HALF_UP);
        assertThat(roundedDown).isEqualByComparingTo(new BigDecimal("333.33"));
    }

    /**
     * Test finding all balances by account ID.
     * 
     * Validates partial key search replicating COBOL VSAM STARTBR/READNEXT with generic key:
     * MOVE account-id TO TRANCAT-ACCT-ID
     * EXEC CICS STARTBR FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY) GENERIC KEYLENGTH(11)
     * Loop: EXEC CICS READNEXT FILE(TCATBAL) INTO(balance-record)
     * 
     * Used for balance aggregation and reporting by account.
     */
    @Test
    @DisplayName("Find by account ID returns all category balances for account")
    void testFindByAccountId_ReturnsMultipleBalances() {
        // When: Query by account ID
        List<TransactionCategoryBalance> balances = repository.findByIdAccountId(12345678901L);

        // Then: All balances for account are returned
        assertThat(balances).hasSize(2); // testBalance1 and testBalance2
        assertThat(balances).extracting(b -> b.getId().getAccountId())
                .containsOnly(12345678901L);
        assertThat(balances).extracting(b -> b.getBalance().scale())
                .containsOnly(2); // All have scale=2
    }

    /**
     * Test finding balances by transaction type code.
     * 
     * Validates aggregation across accounts by transaction type, used in batch processing
     * and reporting to calculate totals by transaction type.
     */
    @Test
    @DisplayName("Find by transaction type code returns balances across accounts")
    void testFindByTransactionTypeCode_ReturnsMultipleBalances() {
        // When: Query by transaction type code
        List<TransactionCategoryBalance> balances = repository.findByIdTransactionTypeCode("DB");

        // Then: All balances with type DB are returned (from multiple accounts)
        assertThat(balances).hasSize(2); // testBalance1 and testBalance3
        assertThat(balances).extracting(b -> b.getId().getTransactionTypeCode())
                .containsOnly("DB");
        assertThat(balances).allMatch(b -> b.getBalance().scale() == 2);
    }

    /**
     * Test finding balances by category code.
     * 
     * Validates category-specific balance aggregation across all accounts and transaction types.
     */
    @Test
    @DisplayName("Find by category code returns balances across accounts and types")
    void testFindByCategoryCode_ReturnsMultipleBalances() {
        // When: Query by category code
        List<TransactionCategoryBalance> balances = repository.findByIdCategoryCode("5000");

        // Then: All balances with category 5000 are returned
        assertThat(balances).hasSize(2); // testBalance1 and testBalance3
        assertThat(balances).extracting(b -> b.getId().getCategoryCode())
                .containsOnly("5000");
        assertThat(balances).allMatch(b -> b.getBalance().scale() == 2);
    }

    /**
     * Test finding balances by account ID and transaction type code.
     * 
     * Validates partial key search with account and type, used for detailed balance breakdowns.
     */
    @Test
    @DisplayName("Find by account ID and type code returns filtered balances")
    void testFindByAccountIdAndTransactionTypeCode() {
        // When: Query by account ID and transaction type code
        List<TransactionCategoryBalance> balances = repository
                .findByIdAccountIdAndIdTransactionTypeCode(12345678901L, "DB");

        // Then: Only matching balances are returned
        assertThat(balances).hasSize(1); // Only testBalance1
        assertThat(balances.get(0).getId().getAccountId()).isEqualTo(12345678901L);
        assertThat(balances.get(0).getId().getTransactionTypeCode()).isEqualTo("DB");
        assertThat(balances.get(0).getBalance().scale()).isEqualTo(2);
    }

    /**
     * Test existence check by composite key.
     * 
     * Validates existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode method,
     * more efficient than findById() when only existence needs verification.
     * 
     * Maps to COBOL existence check with RESP code handling.
     */
    @Test
    @DisplayName("Exists by composite key returns true for existing record")
    void testExistsByCompositeKey_ReturnsTrue() {
        // When: Check existence by composite key
        boolean exists = repository
                .existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        12345678901L, "DB", "5000");

        // Then: Record exists
        assertThat(exists).isTrue();
    }

    /**
     * Test existence check returns false for non-existent record.
     */
    @Test
    @DisplayName("Exists by composite key returns false for non-existent record")
    void testExistsByCompositeKey_ReturnsFalse() {
        // When: Check existence by non-existent composite key
        boolean exists = repository
                .existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        99999999999L, "XX", "9999");

        // Then: Record does not exist
        assertThat(exists).isFalse();
    }

    /**
     * Test delete by composite key.
     * 
     * Validates deleteByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode method,
     * replicating COBOL VSAM DELETE operation:
     * EXEC CICS DELETE FILE(TCATBAL) RIDFLD(TRAN-CAT-KEY)
     */
    @Test
    @DisplayName("Delete by composite key removes balance record")
    void testDeleteByCompositeKey() {
        // Given: Record exists
        assertThat(repository.existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                12345678901L, "DB", "5000")).isTrue();

        // When: Delete by composite key
        repository.deleteByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                12345678901L, "DB", "5000");
        repository.flush();

        // Then: Record is deleted
        assertThat(repository.existsByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                12345678901L, "DB", "5000")).isFalse();
    }

    /**
     * Test delete all balances by account ID.
     * 
     * Validates account closure processing, deleting all category balances for an account.
     */
    @Test
    @DisplayName("Delete by account ID removes all balances for account")
    void testDeleteByAccountId() {
        // Given: Account has multiple balance records
        assertThat(repository.findByIdAccountId(12345678901L)).hasSize(2);

        // When: Delete all balances for account
        long deletedCount = repository.deleteByIdAccountId(12345678901L);
        repository.flush();

        // Then: All balances for account are deleted
        assertThat(deletedCount).isEqualTo(2);
        assertThat(repository.findByIdAccountId(12345678901L)).isEmpty();
    }

    /**
     * Test optimistic locking with version field.
     * 
     * Validates that @Version field enables optimistic concurrency control,
     * preventing lost updates in concurrent access scenarios.
     */
    @Test
    @DisplayName("Version field enables optimistic locking for concurrent updates")
    void testOptimisticLocking_VersionField() {
        // Given: Existing balance record
        Optional<TransactionCategoryBalance> existing = repository
                .findByIdAccountIdAndIdTransactionTypeCodeAndIdCategoryCode(
                        12345678901L, "DB", "5000");

        assertThat(existing).isPresent();
        TransactionCategoryBalance balance = existing.get();

        // Then: Version field is initialized
        assertThat(balance.getVersion()).isNotNull();
        Long initialVersion = balance.getVersion();

        // When: Update balance
        balance.setBalance(new BigDecimal("2000.00").setScale(2, RoundingMode.HALF_UP));
        TransactionCategoryBalance updated = repository.save(balance);
        repository.flush();

        // Then: Version is incremented
        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
    }
}
