package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.TransactionIdentifierSource;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.equivalence.CopybookRecordParser.AccountRecord;
import com.carddemo.ledger.domain.AccountBalanceUpdater;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Compares online bill payment at {@code COBIL00C L203-L250} with batch posting.
 * The online reference rule is held in this test; no target service migrates bill payment.
 * The transaction amount is the opening balance, and subtracting it leaves exact zero.
 * Both cycle accumulators remain unchanged. The class runs only under {@code mvn verify}.
 */
@DisplayName("Online bill-payment parity from COBIL00C L203-L250")
class BillPaymentEquivalenceTest {

    private static final String ONLINE_TYPE_CODE = "02";
    private static final String ONLINE_CATEGORY_CODE = "0002";
    private static final String ONLINE_SOURCE = "POS TERM";
    private static final String ONLINE_DESCRIPTION = "BILL PAYMENT - ONLINE";
    private static final String ONLINE_MERCHANT_ID = "999999999";
    private static final String ONLINE_MERCHANT_NAME = "BILL PAYMENT";
    private static final String NOT_APPLICABLE = "N/A";
    private static final String ONLINE_TIMESTAMP = "2022-06-10 19:27:53.000000";
    private static final BigDecimal ADDITIVE_CYCLE_CREDIT = new BigDecimal("12.34");
    private static final BigDecimal ADDITIVE_CYCLE_DEBIT = new BigDecimal("-5.67");
    private static final long FIRST_SEQUENCE_VALUE = 1L;
    private static final long SECOND_SEQUENCE_VALUE = FIRST_SEQUENCE_VALUE + 1L;

    private static final List<AccountRecord> ACCOUNTS = CardDemoFixtureLoader.loadAccounts();

    @Nested
    @DisplayName("The online balance rule at COBIL00C L224 and L234")
    class OnlineBalanceRule {

        @Test
        void everyFixtureBalanceBecomesNumericZeroAtTheAccountScale() {
            for (AccountRecord account : ACCOUNTS) {
                OnlinePayment result = onlinePayment(account, account.currentCycleCredit(),
                        account.currentCycleDebit(), ONLINE_TIMESTAMP);

                assertEquals(0, result.closingBalance().compareTo(BigDecimal.ZERO),
                        "online bill payment must leave numeric zero per COBIL00C L234");
                assertEquals(PicClause.ACCT_CURR_BAL_SCALE, result.closingBalance().scale(),
                        "the stored zero keeps the ACCT-CURR-BAL scale");
            }
        }

        @Test
        void theMinimumAndMaximumFixtureBalancesFollowTheSameRule() {
            AccountRecord minimum = ACCOUNTS.stream()
                    .min(Comparator.comparing(AccountRecord::currentBalance))
                    .orElseThrow();
            AccountRecord maximum = ACCOUNTS.stream()
                    .max(Comparator.comparing(AccountRecord::currentBalance))
                    .orElseThrow();

            for (AccountRecord account : List.of(minimum, maximum)) {
                OnlinePayment result = onlinePayment(account, ADDITIVE_CYCLE_CREDIT,
                        ADDITIVE_CYCLE_DEBIT, ONLINE_TIMESTAMP);
                assertEquals(0, result.closingBalance().compareTo(BigDecimal.ZERO),
                        "the fixture balance bound must also close at zero");
            }
        }

        @Test
        void theWrittenAmountIsTheOpeningBalanceBeforeTheBalanceChanges() {
            AccountRecord account = ACCOUNTS.get(0);

            OnlinePayment result = onlinePayment(account, ADDITIVE_CYCLE_CREDIT,
                    ADDITIVE_CYCLE_DEBIT, ONLINE_TIMESTAMP);

            assertEquals(0, result.transactionAmount().compareTo(account.currentBalance()),
                    "COBIL00C L224 copies the opening balance before L233 writes the row");
            assertEquals(0, result.closingBalance().compareTo(BigDecimal.ZERO),
                    "COBIL00C L234 subtracts that same amount after the write");
        }
    }

    @Nested
    @DisplayName("Online and batch accumulator divergence")
    class AccumulatorDivergence {

        @Test
        @DisplayName("ADDITIVE: non-zero accumulators prove the online path leaves them unchanged")
        void onlinePaymentLeavesBothNonZeroAccumulatorsUnchanged() {
            AccountRecord account = ACCOUNTS.get(0);

            OnlinePayment result = onlinePayment(account, ADDITIVE_CYCLE_CREDIT,
                    ADDITIVE_CYCLE_DEBIT, ONLINE_TIMESTAMP);

            assertEquals(ADDITIVE_CYCLE_CREDIT, result.cycleCredit(),
                    "COBIL00C contains no cycle-credit reference");
            assertEquals(ADDITIVE_CYCLE_DEBIT, result.cycleDebit(),
                    "COBIL00C contains no cycle-debit reference");
        }

        @Test
        void batchPostingMovesTheCreditAccumulatorForTheSamePositiveAmount() {
            AccountRecord account = ACCOUNTS.get(0);
            AccountBalanceProjectionRepository repository =
                    mock(AccountBalanceProjectionRepository.class);
            AccountBalanceProjectionEntity stored = new AccountBalanceProjectionEntity(
                    account.accountId(), account.currentBalance(), ADDITIVE_CYCLE_CREDIT,
                    ADDITIVE_CYCLE_DEBIT);
            when(repository.findForUpdateById(account.accountId())).thenReturn(Optional.of(stored));
            AccountBalanceUpdater updater = new AccountBalanceUpdater(repository);

            updater.updateBalances(account.accountId(), account.currentBalance());

            ArgumentCaptor<AccountBalanceProjectionEntity> saved =
                    ArgumentCaptor.forClass(AccountBalanceProjectionEntity.class);
            verify(repository).save(saved.capture());
            BigDecimal expectedCredit = CobolDecimal.add(ADDITIVE_CYCLE_CREDIT,
                    account.currentBalance(), PicClause.ACCT_CURR_CYC_CREDIT_SCALE);

            assertEquals(expectedCredit, saved.getValue().getCycleCredit(),
                    "CBTRN02C L548-L549 routes the positive amount to cycle credit");
            assertEquals(ADDITIVE_CYCLE_DEBIT, saved.getValue().getCycleDebit(),
                    "the batch positive branch leaves cycle debit unchanged");
            assertFalse(saved.getValue().getCycleCredit().equals(ADDITIVE_CYCLE_CREDIT),
                    "the batch path must differ from the online non-effect");
        }
    }

    @Nested
    @DisplayName("Timestamp and stamped transaction fields")
    class StampedFields {

        @Test
        void onlineOriginAndProcessingTimestampsUseOneValue() {
            OnlinePayment result = onlinePayment(ACCOUNTS.get(0), ADDITIVE_CYCLE_CREDIT,
                    ADDITIVE_CYCLE_DEBIT, ONLINE_TIMESTAMP);

            assertEquals(result.originTimestamp(), result.processingTimestamp(),
                    "COBIL00C L231-L232 moves one timestamp into both fields");
            assertEquals(PicClause.TRAN_ORIG_TS_WIDTH, result.originTimestamp().length(),
                    "the online origin timestamp keeps the transaction-record width");
        }

        @Test
        void batchOriginAndProcessingTimestampLayoutsRemainDifferent() {
            String batchProcessing = CobolDecimal.formatProcessingTimestamp(
                    LocalDateTime.of(2022, 6, 10, 19, 27, 53, 990_000_000));

            assertFalse(ONLINE_TIMESTAMP.equals(batchProcessing),
                    "CBTRN02C processing separators differ from the feed origin separators");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                    batchProcessing.charAt(PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET - 1),
                    "the batch form carries the third dash before the hour");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS,
                    batchProcessing.substring(
                            PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                    "the batch form keeps two significant fractional digits");
        }

        @Test
        void theOnlineRowCarriesTheSourceLiteralsAtL220ThroughL229() {
            OnlinePayment result = onlinePayment(ACCOUNTS.get(0), ADDITIVE_CYCLE_CREDIT,
                    ADDITIVE_CYCLE_DEBIT, ONLINE_TIMESTAMP);

            assertEquals(ONLINE_TYPE_CODE, result.typeCode(),
                    "COBIL00C L220 stamps transaction type 02");
            assertEquals(ONLINE_CATEGORY_CODE, result.categoryCode(),
                    "COBIL00C L221 moves numeric 2 into PIC 9(04)");
            assertEquals(ONLINE_SOURCE, result.source(), "COBIL00C L222 stamps the source");
            assertEquals(ONLINE_DESCRIPTION, result.description(),
                    "COBIL00C L223 stamps the description");
            assertEquals(ONLINE_MERCHANT_ID, result.merchantId(),
                    "COBIL00C L226 stamps the merchant identifier");
            assertEquals(ONLINE_MERCHANT_NAME, result.merchantName(),
                    "COBIL00C L227 stamps the merchant name");
            assertEquals(NOT_APPLICABLE, result.merchantCity(),
                    "COBIL00C L228 stamps the merchant city");
            assertEquals(NOT_APPLICABLE, result.merchantZip(),
                    "COBIL00C L229 stamps the merchant postal code");
        }
    }

    @Nested
    @DisplayName("ADDITIVE transaction identifier sequence")
    class IdentifierAllocation {

        @Test
        void theDatabaseSequenceAllocatesMonotonicFixedWidthIdentifiers() {
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.getSingleResult())
                    .thenReturn(FIRST_SEQUENCE_VALUE, SECOND_SEQUENCE_VALUE);
            TransactionIdentifierSource source =
                    new TransactionIdentifierSource(entityManager, "authorization_service");

            String first = source.nextIdentifier();
            String second = source.nextIdentifier();

            assertEquals(TransactionIdentifierSource.IDENTIFIER_WIDTH, first.length(),
                    "the first sequence value fills TRAN-ID");
            assertEquals(TransactionIdentifierSource.IDENTIFIER_WIDTH, second.length(),
                    "the second sequence value fills TRAN-ID");
            assertTrue(first.compareTo(second) < 0,
                    "the padded identifiers preserve sequence order");

            ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
            verify(entityManager, org.mockito.Mockito.times(2))
                    .createNativeQuery(statements.capture());
            assertTrue(statements.getAllValues().stream()
                            .allMatch(statement -> statement.contains("nextval")),
                    "allocation uses the database sequence");
            assertTrue(statements.getAllValues().stream()
                            .noneMatch(statement -> statement.contains("READPREV")
                                    || statement.contains("STARTBR")),
                    "the target does not reproduce the backwards browse");
        }
    }

    private static OnlinePayment onlinePayment(AccountRecord account, BigDecimal cycleCredit,
            BigDecimal cycleDebit, String timestamp) {
        BigDecimal transactionAmount = CobolDecimal.truncateToPictureField(
                account.currentBalance(), PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);
        BigDecimal closingBalance = CobolDecimal.subtract(account.currentBalance(),
                transactionAmount, PicClause.ACCT_CURR_BAL_SCALE);
        return new OnlinePayment(transactionAmount, closingBalance, cycleCredit, cycleDebit,
                ONLINE_TYPE_CODE, ONLINE_CATEGORY_CODE, ONLINE_SOURCE, ONLINE_DESCRIPTION,
                ONLINE_MERCHANT_ID, ONLINE_MERCHANT_NAME, NOT_APPLICABLE, NOT_APPLICABLE,
                timestamp, timestamp);
    }

    private record OnlinePayment(
            BigDecimal transactionAmount,
            BigDecimal closingBalance,
            BigDecimal cycleCredit,
            BigDecimal cycleDebit,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            String merchantId,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String originTimestamp,
            String processingTimestamp) {
    }
}