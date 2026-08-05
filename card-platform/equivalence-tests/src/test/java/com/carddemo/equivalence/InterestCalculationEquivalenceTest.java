package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.account.config.ObservabilityConfig;
import com.carddemo.account.domain.BillingCycleService;
import com.carddemo.account.entity.AccountEntity;
import com.carddemo.account.entity.DisclosureGroupEntity;
import com.carddemo.account.entity.DisclosureGroupEntity.DisclosureGroupId;
import com.carddemo.account.outbox.OutboxWriter;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.equivalence.CopybookRecordParser.CardCrossReferenceRecord;
import com.carddemo.equivalence.CopybookRecordParser.DailyTransactionRecord;
import com.carddemo.equivalence.CopybookRecordParser.DisclosureGroupRecord;
import com.carddemo.equivalence.CopybookRecordParser.TransactionCategoryBalanceRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the rate rules and fallback of {@code CBACT04C L415-L470}.
 * Interest remains scheduled batch work and no interest service is created.
 * The target reproduces only the cycle-counter reset at {@code CBACT04C L353-L354}.
 * The class runs only under {@code mvn verify}.
 */
@DisplayName("Interest rule parity from CBACT04C L350-L470")
class InterestCalculationEquivalenceTest {

    private static final String DEFAULT_GROUP = fixedGroup("DEFAULT");
    private static final String MATCHED_GROUP = fixedGroup("A000000000");
    private static final String BLANK_GROUP = " ".repeat(PicClause.DIS_ACCT_GROUP_ID_WIDTH);
    private static final String ZERO_RATE_GROUP = fixedGroup("ZEROAPR");
    private static final String CRITICAL_TYPE = "03";
    private static final String CRITICAL_CATEGORY = "0001";
    private static final String MATCHED_TYPE = "07";
    private static final String MATCHED_CATEGORY = "0001";
    private static final String ABSENT_TYPE = "99";
    private static final String ABSENT_CATEGORY = "9999";
    private static final BigDecimal ZERO_RATE = new BigDecimal("0.00");
    private static final BigDecimal STANDARD_RATE = new BigDecimal("15.00");
    private static final BigDecimal HIGH_RATE = new BigDecimal("25.00");
    private static final BigDecimal SAMPLE_CATEGORY_BALANCE = new BigDecimal("1287.09");
    private static final BigDecimal SAMPLE_MONTHLY_INTEREST = new BigDecimal("16.08");
    private static final BigDecimal ADDITIVE_CYCLE_CREDIT = new BigDecimal("123.45");
    private static final BigDecimal ADDITIVE_CYCLE_DEBIT = new BigDecimal("-67.89");

    private static final List<AccountRecord> ACCOUNTS = CardDemoFixtureLoader.loadAccounts();
    private static final List<DisclosureGroupRecord> DISCLOSURE_GROUPS =
            CardDemoFixtureLoader.loadDisclosureGroups();
    private static final Map<RateKey, BigDecimal> RATES = rateTable();

    @Nested
    @DisplayName("1200-GET-INTEREST-RATE at CBACT04C L415-L440")
    class RateResolution {

        @Test
        @DisplayName("ADDITIVE: a matched fixture group returns its own rate without fallback")
        void aMatchedGroupReturnsItsOwnRate() {
            ResolvedRate result = resolveRate(MATCHED_GROUP, MATCHED_TYPE, MATCHED_CATEGORY);

            assertEquals(STANDARD_RATE, result.rate(),
                    "A000000000 07/0001 carries 15.00 in discgrp.txt");
            assertFalse(result.fallbackUsed(),
                    "a successful primary read reaches no DEFAULT substitution");
            assertEquals(1, result.lookupCount(),
                    "CBACT04C L416 performs one successful read");
        }

        @Test
        void aMissSubstitutesDefaultAndKeepsTheTypeAndCategoryKeyParts() {
            for (DisclosureGroupRecord defaultRow : rowsOf(DEFAULT_GROUP)) {
                ResolvedRate result = resolveRate(BLANK_GROUP,
                        defaultRow.transactionTypeCode(),
                        defaultRow.transactionCategoryCode());

                assertEquals(defaultRow.interestRate(), result.rate(),
                        "DEFAULT must retain type " + defaultRow.transactionTypeCode()
                                + " and category " + defaultRow.transactionCategoryCode());
                assertTrue(result.fallbackUsed(),
                        "CBACT04C L436-L438 performs the DEFAULT retry");
                assertEquals(2, result.lookupCount(),
                        "one missed primary read is followed by one retry");
            }
        }

        @Test
        void aSecondMissFailsAfterTheSingleRetry() {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> resolveRate(BLANK_GROUP, ABSENT_TYPE, ABSENT_CATEGORY));

            assertTrue(failure.getMessage().contains("DEFAULT"),
                    "CBACT04C L446-L458 reports the failed default read");
        }

        @Test
        void everyFixtureAccountCarriesTheBlankPrimaryGroup() {
            assertTrue(ACCOUNTS.stream().allMatch(account -> account.groupId().equals(BLANK_GROUP)),
                    "ACCT-GROUP-ID is ten spaces on every acctdata.txt row");
            assertTrue(ACCOUNTS.stream().allMatch(account -> RATES.keySet().stream()
                            .anyMatch(key -> key.groupId().equals(account.addressZip()))),
                    "the adjacent ACCT-ADDR-ZIP names a real disclosure group");
        }

        @Test
        void everyCategoryRowProducedByTheFixtureRunUsesTheFallback() {
            Set<CategoryKey> categoryKeys = categoryKeysAfterPosting();

            for (CategoryKey key : categoryKeys) {
                ResolvedRate result =
                        resolveRate(BLANK_GROUP, key.typeCode(), key.categoryCode());
                assertTrue(result.fallbackUsed(),
                        "the blank fixture group must use DEFAULT for "
                                + key.typeCode() + "/" + key.categoryCode());
            }

            assertEquals(categoryKeys.size(),
                    categoryKeys.stream()
                            .filter(key -> resolveRate(BLANK_GROUP, key.typeCode(),
                                    key.categoryCode()).fallbackUsed())
                            .count(),
                    "the fallback count is derived from every category row reached");
        }
    }

    @Nested
    @DisplayName("The disclosure-group fixture and target row shape")
    class RateTable {

        @Test
        void everyGroupCarriesTheSameTypeAndCategoryKeys() {
            Map<String, Set<RateCode>> keysByGroup = new LinkedHashMap<>();
            for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
                keysByGroup.computeIfAbsent(row.accountGroupId(), ignored -> new LinkedHashSet<>())
                        .add(new RateCode(row.transactionTypeCode(),
                                row.transactionCategoryCode()));
            }

            Set<RateCode> defaultKeys = keysByGroup.get(DEFAULT_GROUP);
            assertEquals(defaultKeys, keysByGroup.get(MATCHED_GROUP),
                    "A000000000 carries the same key set as DEFAULT");
            assertEquals(defaultKeys, keysByGroup.get(ZERO_RATE_GROUP),
                    "ZEROAPR carries the same key set as DEFAULT");

            int rowsPerGroup = DISCLOSURE_GROUPS.size() / keysByGroup.size();
            assertTrue(keysByGroup.values().stream()
                            .allMatch(keys -> keys.size() == rowsPerGroup),
                    "each measured group carries the same derived row count");
        }

        @Test
        void theDefaultTableCarriesTheThreeMeasuredRateValues() {
            Set<BigDecimal> rates = new TreeSet<>();
            for (DisclosureGroupRecord row : rowsOf(DEFAULT_GROUP)) {
                rates.add(row.interestRate());
            }

            assertEquals(Set.of(ZERO_RATE, STANDARD_RATE, HIGH_RATE), rates,
                    "DEFAULT carries only 0.00, 15.00 and 25.00");
        }

        @Test
        void type03Category0001CarriesTheZeroRate() {
            ResolvedRate result = resolveRate(BLANK_GROUP, CRITICAL_TYPE, CRITICAL_CATEGORY);

            assertEquals(0, result.rate().compareTo(BigDecimal.ZERO),
                    "DEFAULT 03/0001 must be numeric zero");
            assertEquals(PicClause.DIS_INT_RATE_SCALE, result.rate().scale(),
                    "the zero rate keeps DIS-INT-RATE scale");
        }

        @Test
        void everyFixtureRowMapsToTheTargetCompositeKeyAndRate() {
            for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
                DisclosureGroupEntity entity = new DisclosureGroupEntity(
                        new DisclosureGroupId(row.accountGroupId(),
                                row.transactionTypeCode(), row.transactionCategoryCode()),
                        row.interestRate());

                assertEquals(row.accountGroupId(), entity.getId().getAccountGroupId(),
                        "the first key part matches CVTRA02Y L6");
                assertEquals(row.transactionTypeCode(),
                        entity.getId().getTransactionTypeCode(),
                        "the second key part matches CVTRA02Y L7");
                assertEquals(row.transactionCategoryCode(),
                        entity.getId().getTransactionCategoryCode(),
                        "the third key part matches CVTRA02Y L8");
                assertEquals(row.interestRate(), entity.getInterestRate(),
                        "the mapped rate matches CVTRA02Y L9");
            }
        }
    }

    @Nested
    @DisplayName("1300-COMPUTE-INTEREST is verified and not migrated")
    class VerifiedNotMigrated {

        @Test
        void aResolvedNonZeroRateUsesTheSourceDivideAndTruncation() {
            BigDecimal rate =
                    resolveRate(BLANK_GROUP, "01", "0001").rate();
            BigDecimal monthlyInterest = CobolDecimal.multiplyThenDivide(
                    SAMPLE_CATEGORY_BALANCE, rate, CobolDecimal.INTEREST_DIVISOR,
                    PicClause.WS_MONTHLY_INT_SCALE);

            assertEquals(SAMPLE_MONTHLY_INTEREST, monthlyInterest,
                    "CBACT04C L464-L465 truncates the monthly interest toward zero");
        }

        @Test
        void theCriticalZeroRateProducesNumericZero() {
            BigDecimal rate =
                    resolveRate(BLANK_GROUP, CRITICAL_TYPE, CRITICAL_CATEGORY).rate();
            BigDecimal monthlyInterest = CobolDecimal.multiplyThenDivide(
                    SAMPLE_CATEGORY_BALANCE, rate, CobolDecimal.INTEREST_DIVISOR,
                    PicClause.WS_MONTHLY_INT_SCALE);

            assertEquals(0, monthlyInterest.compareTo(BigDecimal.ZERO),
                    "DEFAULT 03/0001 leaves the interest divide at zero");
        }

        @Test
        void noServiceSourceDeclaresAnInterestService() {
            Path services = repositoryRoot().resolve("card-platform/services");
            List<Path> interestServices = new ArrayList<>();
            try (Stream<Path> files = Files.walk(services)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Service.java"))
                        .filter(path -> path.getFileName().toString().contains("Interest"))
                        .forEach(interestServices::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("cannot inspect " + services, unreadable);
            }

            assertEquals(List.of(), interestServices,
                    "the C8 carve-out verifies interest without creating a service");
        }
    }

    @Nested
    @DisplayName("1050-UPDATE-ACCOUNT at CBACT04C L350-L358")
    class CycleCloseCarveOut {

        @Test
        void theTargetZeroesBothAccumulatorsAndLeavesTheBalanceUnchanged() {
            AccountRecord fixture = ACCOUNTS.get(0);
            AccountEntity account = accountEntity(fixture);
            account.setCurrentCycleCredit(ADDITIVE_CYCLE_CREDIT);
            account.setCurrentCycleDebit(ADDITIVE_CYCLE_DEBIT);
            AccountRepository repository = mock(AccountRepository.class);
            when(repository.findForUpdateByAccountId(account.getAccountId()))
                    .thenReturn(Optional.of(account));
            when(repository.save(account)).thenReturn(account);
            OutboxWriter outbox = mock(OutboxWriter.class);
            BillingCycleService service = new BillingCycleService(repository, outbox,
                    immediateTransactions(),
                    new ObservabilityConfig().accountMeters(new SimpleMeterRegistry()));
            BigDecimal openingBalance = account.getCurrentBalance();

            AccountEntity closed = service.closeBillingCycle(account.getAccountId()).orElseThrow();

            assertEquals(openingBalance, closed.getCurrentBalance(),
                    "BillingCycleService omits the interest add at CBACT04C L352");
            assertEquals(0, closed.getCurrentCycleCredit().compareTo(BigDecimal.ZERO),
                    "CBACT04C L353 zeroes cycle credit");
            assertEquals(0, closed.getCurrentCycleDebit().compareTo(BigDecimal.ZERO),
                    "CBACT04C L354 zeroes cycle debit");
            assertEquals(PicClause.ACCT_CURR_CYC_CREDIT_SCALE,
                    closed.getCurrentCycleCredit().scale(),
                    "cycle credit keeps its Picture-clause scale");
            assertEquals(PicClause.ACCT_CURR_CYC_DEBIT_SCALE,
                    closed.getCurrentCycleDebit().scale(),
                    "cycle debit keeps its Picture-clause scale");
            verify(outbox).write(any());
        }
    }

    /** Runs the cycle-close callback on the calling thread without an external transaction manager. */
    private static TransactionTemplate immediateTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static ResolvedRate resolveRate(String groupId, String typeCode, String categoryCode) {
        BigDecimal primary = RATES.get(new RateKey(groupId, typeCode, categoryCode));
        if (primary != null) {
            return new ResolvedRate(primary, false, 1);
        }
        BigDecimal fallback = RATES.get(new RateKey(DEFAULT_GROUP, typeCode, categoryCode));
        if (fallback == null) {
            throw new IllegalStateException("DEFAULT disclosure group has no row for "
                    + typeCode + "/" + categoryCode);
        }
        return new ResolvedRate(fallback, true, 2);
    }

    private static Map<RateKey, BigDecimal> rateTable() {
        Map<RateKey, BigDecimal> rates = new LinkedHashMap<>();
        for (DisclosureGroupRecord row : DISCLOSURE_GROUPS) {
            RateKey key = new RateKey(row.accountGroupId(), row.transactionTypeCode(),
                    row.transactionCategoryCode());
            BigDecimal displaced = rates.put(key, row.interestRate());
            if (displaced != null) {
                throw new IllegalStateException("discgrp.txt repeats "
                        + row.transactionTypeCode() + "/" + row.transactionCategoryCode());
            }
        }
        return Map.copyOf(rates);
    }

    private static List<DisclosureGroupRecord> rowsOf(String groupId) {
        return DISCLOSURE_GROUPS.stream()
                .filter(row -> row.accountGroupId().equals(groupId))
                .toList();
    }

    private static Set<CategoryKey> categoryKeysAfterPosting() {
        Map<String, MutableAccount> accounts = mutableAccounts();
        Map<String, CardCrossReferenceRecord> xrefs =
                CardDemoFixtureLoader.cardCrossReferencesByCardNumber();
        Set<CategoryKey> keys = new LinkedHashSet<>();
        for (TransactionCategoryBalanceRecord row
                : CardDemoFixtureLoader.loadTransactionCategoryBalances()) {
            keys.add(new CategoryKey(row.accountId(), row.typeCode(), row.categoryCode()));
        }

        for (DailyTransactionRecord transaction : CardDemoFixtureLoader.loadDailyTransactions()) {
            CardCrossReferenceRecord xref = xrefs.get(transaction.cardNumber());
            MutableAccount account = xref == null ? null : accounts.get(xref.accountId());
            if (account == null || declines(account, transaction)) {
                continue;
            }
            keys.add(new CategoryKey(xref.accountId(), transaction.typeCode(),
                    transaction.categoryCode()));
            account.apply(transaction.amount());
        }
        return Set.copyOf(keys);
    }

    private static Map<String, MutableAccount> mutableAccounts() {
        Map<String, MutableAccount> accounts = new LinkedHashMap<>();
        for (AccountRecord account : ACCOUNTS) {
            accounts.put(account.accountId(), new MutableAccount(account.creditLimit(),
                    account.expirationDate(), account.currentCycleCredit(),
                    account.currentCycleDebit()));
        }
        return accounts;
    }

    private static boolean declines(MutableAccount account, DailyTransactionRecord transaction) {
        BigDecimal difference = CobolDecimal.subtract(account.cycleCredit, account.cycleDebit,
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal computed = CobolDecimal.add(difference, transaction.amount(),
                PicClause.WS_TEMP_BAL_SCALE);
        BigDecimal working = CobolDecimal.truncateToPictureField(computed,
                PicClause.WS_TEMP_BAL_PRECISION, PicClause.WS_TEMP_BAL_SCALE);
        boolean overLimit = account.creditLimit.compareTo(working) < 0;
        boolean expired = account.expirationDate.compareTo(
                CopybookRecordParser.timestampDatePart(transaction.originTimestamp())) < 0;
        return overLimit || expired;
    }

    private static AccountEntity accountEntity(AccountRecord source) {
        AccountEntity account = new AccountEntity();
        account.setAccountId(source.accountId());
        account.setActiveStatus(source.activeStatus());
        account.setCurrentBalance(source.currentBalance());
        account.setCreditLimit(source.creditLimit());
        account.setCashCreditLimit(source.cashCreditLimit());
        account.setOpenDate(source.openDate());
        account.setExpirationDate(source.expirationDate());
        account.setReissueDate(source.reissueDate());
        account.setCurrentCycleCredit(source.currentCycleCredit());
        account.setCurrentCycleDebit(source.currentCycleDebit());
        account.setAddressZip(source.addressZip());
        account.setGroupId(source.groupId());
        return account;
    }

    private static String fixedGroup(String value) {
        if (value.length() > PicClause.DIS_ACCT_GROUP_ID_WIDTH) {
            throw new IllegalArgumentException("group identifier exceeds its Picture-clause width");
        }
        return value + " ".repeat(PicClause.DIS_ACCT_GROUP_ID_WIDTH - value.length());
    }

    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory()
                .getParent()
                .getParent()
                .getParent();
    }

    private record RateKey(String groupId, String typeCode, String categoryCode) {
    }

    private record RateCode(String typeCode, String categoryCode) {
    }

    private record CategoryKey(String accountId, String typeCode, String categoryCode) {
    }

    private record ResolvedRate(BigDecimal rate, boolean fallbackUsed, int lookupCount) {
    }

    private static final class MutableAccount {
        private final BigDecimal creditLimit;
        private final String expirationDate;
        private BigDecimal cycleCredit;
        private BigDecimal cycleDebit;

        private MutableAccount(BigDecimal creditLimit, String expirationDate,
                BigDecimal cycleCredit, BigDecimal cycleDebit) {
            this.creditLimit = creditLimit;
            this.expirationDate = expirationDate;
            this.cycleCredit = cycleCredit;
            this.cycleDebit = cycleDebit;
        }

        private void apply(BigDecimal amount) {
            if (amount.signum() >= 0) {
                cycleCredit = CobolDecimal.add(cycleCredit, amount,
                        PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
            } else {
                cycleDebit = CobolDecimal.add(cycleDebit, amount,
                        PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
            }
        }
    }
}