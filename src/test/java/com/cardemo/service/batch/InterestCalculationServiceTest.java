package com.cardemo.service.batch;

import com.cardemo.common.util.DateConversionUtil;
import com.cardemo.entity.Account;
import com.cardemo.entity.CardXref;
import com.cardemo.entity.CategoryBalance;
import com.cardemo.entity.DiscountGroup;
import com.cardemo.entity.Transaction;
import com.cardemo.repository.AccountRepository;
import com.cardemo.repository.CardXrefRepository;
import com.cardemo.repository.CategoryBalanceRepository;
import com.cardemo.repository.DiscountGroupRepository;
import com.cardemo.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InterestCalculationService} — the batch interest calculation
 * service migrated from CBACT04C.cbl.
 *
 * <p>Tests cover the COBOL interest formula
 * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200},
 * discount group lookup with DEFAULT fallback (paragraphs 1200, 1200-A),
 * per-account interest accumulation across category balances, account balance
 * update with cycle reset (paragraph 1050-UPDATE-ACCOUNT), interest transaction
 * generation (paragraph 1300-B-WRITE-TX), transaction ID generation from
 * parmDate + suffix, fees computation stub (paragraph 1400-COMPUTE-FEES),
 * and multi-account independent processing.</p>
 *
 * <p>Uses Mockito mocks (no Spring context) for all 5 repository dependencies.
 * {@link DateConversionUtil} static methods are controlled via
 * {@link MockedStatic} where needed for deterministic timestamp assertions.</p>
 */
@ExtendWith(MockitoExtension.class)
class InterestCalculationServiceTest {

    @Mock
    private CategoryBalanceRepository categoryBalanceRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private DiscountGroupRepository discountGroupRepository;

    @Mock
    private DateConversionUtil dateConversionUtil;

    @InjectMocks
    private InterestCalculationService interestCalculationService;

    /** Fixed DB2 26-character timestamp used across tests for deterministic assertions. */
    private static final String FIXED_TIMESTAMP = "2024-01-15-10.30.45.123456";

    /** Divisor constant matching COBOL formula: catBal * rate / 1200. */
    private static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200);

    @BeforeEach
    void setUp() {
        // MockitoExtension initializes all @Mock and @InjectMocks fields before each test.
        // Individual tests configure their own mock behaviour to avoid unused-stubbing warnings.
        assertThat(interestCalculationService).isNotNull();
    }

    // ========================================================================
    // Test: Interest Computation Formula (← paragraph 1300-COMPUTE-INTEREST)
    // COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
    // ========================================================================

    @Test
    @DisplayName("Interest formula: monthlyInterest = catBalance * interestRate / 1200 (← 1300-COMPUTE-INTEREST)")
    void shouldComputeInterestWithExactFormula() {
        // Given: CategoryBalance with balance 5000.00 and rate 18.00% APR
        BigDecimal catBalance = new BigDecimal("5000.00");
        BigDecimal interestRate = new BigDecimal("18.00");

        // When: computeInterest replicates the COBOL formula
        BigDecimal result = interestCalculationService.computeInterest(catBalance, interestRate);

        // Then: 5000.00 * 18.00 / 1200 = 90000.00 / 1200 = 75.00
        BigDecimal expected = catBalance
                .multiply(interestRate)
                .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_UP);
        assertThat(result).isEqualTo(expected);
        assertThat(result.compareTo(new BigDecimal("75.00"))).isZero();
    }

    @Test
    @DisplayName("Interest calculation rounds with HALF_UP matching COBOL default rounding")
    void shouldRoundInterestWithHalfUp() {
        // Given: values that produce a non-terminating decimal division
        BigDecimal catBalance = new BigDecimal("1234.56");
        BigDecimal interestRate = new BigDecimal("15.75");

        // When
        BigDecimal result = interestCalculationService.computeInterest(catBalance, interestRate);

        // Then: 1234.56 * 15.75 = 19444.32; 19444.32 / 1200 = 16.2036 → 16.20 (HALF_UP, scale 2)
        BigDecimal expected = catBalance
                .multiply(interestRate)
                .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_UP);
        assertThat(result).isEqualTo(expected);
        assertThat(result).isEqualTo(new BigDecimal("16.20"));
    }

    // ========================================================================
    // Test: Discount Group Lookup — Specific Group (← paragraph 1200-GET-INTEREST-RATE)
    // ========================================================================

    @Test
    @DisplayName("Should lookup interest rate from specific discount group (← 1200-GET-INTEREST-RATE)")
    void shouldLookupInterestRateFromSpecificGroup() {
        // Given: Account group "A" with type "01" and category code "0001" (Integer 1)
        DiscountGroup group = createTestDiscountGroup("A", "01", "0001", new BigDecimal("18.00"));
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("A", "01", 1))
                .thenReturn(Optional.of(group));

        // When: getInterestRate looks up the specific group "A"
        BigDecimal rate = interestCalculationService.getInterestRate("A", "01", "0001");

        // Then: Rate 18.00 is returned from group "A"
        assertThat(rate).isEqualTo(new BigDecimal("18.00"));
        verify(discountGroupRepository).findByGroupIdAndTranTypeCodeAndTranCatCode("A", "01", 1);
    }

    // ========================================================================
    // Test: DEFAULT Group Fallback (← paragraph 1200-A-GET-DEFAULT-INT-RATE)
    // COBOL: MOVE 'DEFAULT   ' TO FD-DIS-ACCT-GROUP-ID; READ DISCGRP-FILE
    // ========================================================================

    @Test
    @DisplayName("Should fallback to DEFAULT group when specific group not found — VSAM STATUS '23' (← 1200-A-GET-DEFAULT-INT-RATE)")
    void shouldFallbackToDefaultGroupWhenSpecificNotFound() {
        // Given: Group "B" does not exist in DISCGRP (simulates VSAM STATUS '23')
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("B", "01", 1))
                .thenReturn(Optional.empty());
        // And: DEFAULT group exists with rate 15.00 (from discgrp.txt DEFAULT block)
        DiscountGroup defaultGroup = createTestDiscountGroup("DEFAULT", "01", "0001", new BigDecimal("15.00"));
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("DEFAULT", "01", 1))
                .thenReturn(Optional.of(defaultGroup));

        // When: getInterestRate falls back to DEFAULT
        BigDecimal rate = interestCalculationService.getInterestRate("B", "01", "0001");

        // Then: DEFAULT rate 15.00 is used
        assertThat(rate).isEqualTo(new BigDecimal("15.00"));
        // Verify both lookups were attempted (specific first, then DEFAULT)
        verify(discountGroupRepository).findByGroupIdAndTranTypeCodeAndTranCatCode("B", "01", 1);
        verify(discountGroupRepository).findByGroupIdAndTranTypeCodeAndTranCatCode("DEFAULT", "01", 1);
    }

    // ========================================================================
    // Test: Per-Account Interest Accumulation (← grouping logic in calculateInterest)
    // ========================================================================

    @Test
    @DisplayName("Should accumulate interest across multiple category balances for same account")
    void shouldAccumulateInterestAcrossCategories() {
        // Given: 3 category balances for account "00000000001" with different type/cat combos
        List<CategoryBalance> balances = new ArrayList<>();
        balances.add(createTestCategoryBalance("00000000001", "01", "0001", new BigDecimal("1000.00")));
        balances.add(createTestCategoryBalance("00000000001", "01", "0002", new BigDecimal("2000.00")));
        balances.add(createTestCategoryBalance("00000000001", "02", "0001", new BigDecimal("500.00")));
        when(categoryBalanceRepository.findAll(any(Sort.class))).thenReturn(balances);

        // Account with initial balance 5000.00, group "A"
        Account testAcct = createTestAccount("00000000001", new BigDecimal("5000.00"));
        when(accountRepository.findById("00000000001")).thenReturn(Optional.of(testAcct));
        when(cardXrefRepository.findByAccountId("00000000001"))
                .thenReturn(List.of(createTestXref("00000000001")));

        // Rate lookups for each (group, type, cat) combination
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("A", "01", 1))
                .thenReturn(Optional.of(createTestDiscountGroup("A", "01", "0001", new BigDecimal("18.00"))));
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("A", "01", 2))
                .thenReturn(Optional.of(createTestDiscountGroup("A", "01", "0002", new BigDecimal("12.00"))));
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("A", "02", 1))
                .thenReturn(Optional.of(createTestDiscountGroup("A", "02", "0001", new BigDecimal("24.00"))));

        // When: calculateInterest processes all category balances
        try (MockedStatic<DateConversionUtil> mockedDateUtil = mockStatic(DateConversionUtil.class)) {
            mockedDateUtil.when(DateConversionUtil::getCurrentTimestamp).thenReturn(FIXED_TIMESTAMP);
            interestCalculationService.calculateInterest("20240115");
        }

        // Then: Interest = 1000*18/1200 + 2000*12/1200 + 500*24/1200
        //                 = 15.00 + 20.00 + 10.00 = 45.00
        BigDecimal expectedTotal = new BigDecimal("15.00")
                .add(new BigDecimal("20.00"))
                .add(new BigDecimal("10.00"));
        assertThat(expectedTotal).isEqualByComparingTo(new BigDecimal("45.00"));

        // Updated balance = 5000.00 + 45.00 = 5045.00
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getCurrBal()).isEqualByComparingTo(new BigDecimal("5045.00"));

        // Verify 3 interest transactions were written (one per category balance)
        verify(transactionRepository, times(3)).save(any(Transaction.class));
    }

    // ========================================================================
    // Test: Account Balance Update (← paragraph 1050-UPDATE-ACCOUNT)
    // COBOL: ADD WS-TOTAL-INT TO ACCT-CURR-BAL
    //        MOVE 0 TO ACCT-CURR-CYC-CREDIT
    //        MOVE 0 TO ACCT-CURR-CYC-DEBIT
    //        REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD
    // ========================================================================

    @Test
    @DisplayName("Should update account balance and reset cycle credits/debits (← 1050-UPDATE-ACCOUNT)")
    void shouldUpdateAccountBalanceWithTotalInterest() {
        // Given: Account with initial balance and non-zero cycle amounts
        Account account = createTestAccount("00000000001", new BigDecimal("5000.00"));
        account.setCurrCycCredit(new BigDecimal("250.00"));
        account.setCurrCycDebit(new BigDecimal("100.00"));
        BigDecimal totalInterest = new BigDecimal("45.00");

        // When: updateAccount is called (COBOL: ADD WS-TOTAL-INT TO ACCT-CURR-BAL)
        interestCalculationService.updateAccount(account, totalInterest);

        // Then: Balance updated, cycle amounts reset to zero per COBOL paragraph
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(captor.capture());
        Account saved = captor.getValue();

        // COBOL: ADD WS-TOTAL-INT TO ACCT-CURR-BAL → 5000.00 + 45.00 = 5045.00
        assertThat(saved.getCurrBal()).isEqualByComparingTo(new BigDecimal("5045.00"));
        // COBOL: MOVE 0 TO ACCT-CURR-CYC-CREDIT
        assertThat(saved.getCurrCycCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        // COBOL: MOVE 0 TO ACCT-CURR-CYC-DEBIT
        assertThat(saved.getCurrCycDebit()).isEqualByComparingTo(BigDecimal.ZERO);
        // Verify groupId is preserved
        assertThat(saved.getGroupId()).isEqualTo("A");
    }

    // ========================================================================
    // Test: Interest Transaction Generation (← paragraph 1300-B-WRITE-TX)
    // COBOL: MOVE '01' TO TRAN-TYPE-CD
    //        MOVE '0005' TO TRAN-CAT-CD
    //        MOVE 'System' TO TRAN-SOURCE
    //        STRING 'Int. for a/c ' ACCT-ID INTO TRAN-DESC
    //        MOVE WS-MONTHLY-INT TO TRAN-AMT
    //        MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
    // ========================================================================

    @Test
    @DisplayName("Should generate interest transaction with correct fields (← 1300-B-WRITE-TX)")
    void shouldGenerateInterestTransaction() {
        try (MockedStatic<DateConversionUtil> mockedDateUtil = mockStatic(DateConversionUtil.class)) {
            mockedDateUtil.when(DateConversionUtil::getCurrentTimestamp).thenReturn(FIXED_TIMESTAMP);

            // When: writeInterestTransaction creates the transaction record
            interestCalculationService.writeInterestTransaction(
                    "20240115", 1, new BigDecimal("75.00"),
                    "00000000001", "4111111111111111");

            // Then: Transaction saved with COBOL-equivalent field values
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            Transaction saved = captor.getValue();

            // TRAN-ID = STRING PARM-DATE WS-TRANID-SUFFIX → "2024011500000001"
            assertThat(saved.getTranId()).isEqualTo("2024011500000001");
            // COBOL: MOVE '01' TO TRAN-TYPE-CD
            assertThat(saved.getTypeCode()).isEqualTo("01");
            // COBOL: MOVE '0005' TO TRAN-CAT-CD → Integer 5
            assertThat(saved.getCategoryCode()).isEqualTo(Integer.valueOf(5));
            // COBOL: MOVE 'System' TO TRAN-SOURCE
            assertThat(saved.getSource()).isEqualTo("System");
            // COBOL: STRING 'Int. for a/c ' ACCT-ID INTO TRAN-DESC
            assertThat(saved.getDescription()).isEqualTo("Int. for a/c 00000000001");
            // COBOL: MOVE WS-MONTHLY-INT TO TRAN-AMT
            assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("75.00"));
            // COBOL: MOVE XREF-CARD-NUM TO TRAN-CARD-NUM
            assertThat(saved.getCardNum()).isEqualTo("4111111111111111");
            // DB2 format timestamps (26 chars: YYYY-MM-DD-HH.MM.SS.mmmmmm)
            assertThat(saved.getOrigTimestamp()).isEqualTo(FIXED_TIMESTAMP);
            assertThat(saved.getProcTimestamp()).isEqualTo(FIXED_TIMESTAMP);
        }
    }

    // ========================================================================
    // Test: Transaction ID Generation (← STRING PARM-DATE WS-TRANID-SUFFIX INTO TRAN-ID)
    // ========================================================================

    @Test
    @DisplayName("Transaction ID = parmDate + zero-padded suffix (← STRING concatenation)")
    void shouldGenerateTranIdFromDateAndSuffix() {
        try (MockedStatic<DateConversionUtil> mockedDateUtil = mockStatic(DateConversionUtil.class)) {
            mockedDateUtil.when(DateConversionUtil::getCurrentTimestamp).thenReturn(FIXED_TIMESTAMP);

            // When: suffix = 42
            interestCalculationService.writeInterestTransaction(
                    "20240115", 42, new BigDecimal("50.00"),
                    "00000000002", "5222222222222222");

            // Then: tranId = "20240115" + "00000042" (8-digit zero-padded suffix)
            ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
            verify(transactionRepository).save(captor.capture());
            String generatedTranId = captor.getValue().getTranId();
            assertThat(generatedTranId).isEqualTo("2024011500000042");
            assertThat(generatedTranId).hasSize(16);
        }
    }

    // ========================================================================
    // Test: Fees Computation Stub (← paragraph 1400-COMPUTE-FEES)
    // COBOL: 1400-COMPUTE-FEES. * To be implemented
    // ========================================================================

    @Test
    @DisplayName("computeFees is a no-op stub matching COBOL 'To be implemented' (← 1400-COMPUTE-FEES)")
    void shouldHaveComputeFeesAsStub() {
        // When / Then: Method exists and does not throw any exception
        // COBOL paragraph 1400-COMPUTE-FEES is a placeholder in the original source
        interestCalculationService.computeFees();
        // No exception thrown — stub method is correctly empty, matching COBOL original
    }

    // ========================================================================
    // Test: Multiple Accounts Processing (← main loop with account change detection)
    // ========================================================================

    @Test
    @DisplayName("Should process multiple accounts independently and update each")
    void shouldProcessMultipleAccountsIndependently() {
        // Given: Category balances for two different accounts (sorted by accountId)
        List<CategoryBalance> balances = new ArrayList<>();
        balances.add(createTestCategoryBalance("00000000001", "01", "0001", new BigDecimal("1000.00")));
        balances.add(createTestCategoryBalance("00000000002", "01", "0001", new BigDecimal("3000.00")));
        when(categoryBalanceRepository.findAll(any(Sort.class))).thenReturn(balances);

        // Account 1 (group "A", balance 5000.00)
        Account acct1 = createTestAccount("00000000001", new BigDecimal("5000.00"));
        when(accountRepository.findById("00000000001")).thenReturn(Optional.of(acct1));
        when(cardXrefRepository.findByAccountId("00000000001"))
                .thenReturn(List.of(createTestXref("00000000001")));

        // Account 2 (group "B", balance 8000.00) — triggers DEFAULT rate fallback
        Account acct2 = new Account("00000000002", "Y", new BigDecimal("8000.00"),
                new BigDecimal("15000.00"), new BigDecimal("5000.00"),
                "2019-06-15", "2026-06-30", "2022-06-15",
                BigDecimal.ZERO, BigDecimal.ZERO, "54321", "B");
        when(accountRepository.findById("00000000002")).thenReturn(Optional.of(acct2));
        when(cardXrefRepository.findByAccountId("00000000002"))
                .thenReturn(List.of(new CardXref("5222222222222222", "000000002", "00000000002")));

        // Rates: group "A" → direct lookup succeeds; group "B" → fallback to DEFAULT
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("A", "01", 1))
                .thenReturn(Optional.of(createTestDiscountGroup("A", "01", "0001", new BigDecimal("18.00"))));
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("B", "01", 1))
                .thenReturn(Optional.empty());
        when(discountGroupRepository.findByGroupIdAndTranTypeCodeAndTranCatCode("DEFAULT", "01", 1))
                .thenReturn(Optional.of(createTestDiscountGroup("DEFAULT", "01", "0001", new BigDecimal("15.00"))));

        // When: calculateInterest processes all balances grouped by account
        try (MockedStatic<DateConversionUtil> mockedDateUtil = mockStatic(DateConversionUtil.class)) {
            mockedDateUtil.when(DateConversionUtil::getCurrentTimestamp).thenReturn(FIXED_TIMESTAMP);
            interestCalculationService.calculateInterest("20240115");
        }

        // Then: accountRepository.save() called twice (once per account)
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());
        List<Account> savedAccounts = accountCaptor.getAllValues();

        // Acct1: 1000 * 18 / 1200 = 15.00 → balance = 5000 + 15 = 5015.00
        assertThat(savedAccounts.get(0).getCurrBal()).isEqualByComparingTo(new BigDecimal("5015.00"));
        // Acct2: 3000 * 15 / 1200 = 37.50 → balance = 8000 + 37.50 = 8037.50
        assertThat(savedAccounts.get(1).getCurrBal()).isEqualByComparingTo(new BigDecimal("8037.50"));

        // 2 interest transactions written (one per category balance record)
        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    // ========================================================================
    // Helper Methods for Test Data Construction
    // ========================================================================

    /**
     * Creates a {@link CategoryBalance} entity for testing.
     *
     * @param accountId    the 11-character account identifier (ACCT-ID)
     * @param typeCode     the transaction type code (e.g. "01")
     * @param categoryCode the category code as COBOL PIC 9(04) string (e.g. "0001")
     * @param balance      the category balance amount as BigDecimal
     * @return a fully populated CategoryBalance entity
     */
    private CategoryBalance createTestCategoryBalance(String accountId, String typeCode,
                                                      String categoryCode, BigDecimal balance) {
        return new CategoryBalance(accountId, typeCode, Integer.valueOf(categoryCode), balance);
    }

    /**
     * Creates a test {@link Account} entity with default field values matching
     * the CVACT01Y.cpy record layout. Uses the provided acctId and currBal
     * with sensible defaults for all other fields.
     *
     * @param acctId  the 11-character account identifier
     * @param currBal the current account balance
     * @return a fully populated Account entity with group "A" and active status
     */
    private Account createTestAccount(String acctId, BigDecimal currBal) {
        return new Account(acctId, "Y", currBal,
                new BigDecimal("10000.00"), new BigDecimal("3000.00"),
                "2020-01-01", "2025-12-31", "2023-01-01",
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "A");
    }

    /**
     * Creates a test {@link CardXref} entity representing the CARDXREF VSAM
     * junction table (CVACT03Y.cpy). The card number "4111111111111111" is
     * a standard test Visa number.
     *
     * @param acctId the account identifier to link the cross-reference to
     * @return a CardXref entity with a test card number and customer ID
     */
    private CardXref createTestXref(String acctId) {
        return new CardXref("4111111111111111", "000000001", acctId);
    }

    /**
     * Creates a test {@link DiscountGroup} entity from the DISCGRP reference
     * dataset. Converts the category code string (COBOL PIC 9(04)) to Integer
     * to match the entity field type.
     *
     * @param groupId  the discount group identifier (e.g. "A", "DEFAULT", "ZEROAPR")
     * @param typeCode the transaction type code (e.g. "01")
     * @param catCode  the category code as COBOL PIC 9(04) string (e.g. "0001")
     * @param rate     the annual interest rate percentage (e.g. 18.00 for 18% APR)
     * @return a fully populated DiscountGroup entity
     */
    private DiscountGroup createTestDiscountGroup(String groupId, String typeCode,
                                                  String catCode, BigDecimal rate) {
        return new DiscountGroup(groupId, typeCode, Integer.valueOf(catCode), rate);
    }
}
