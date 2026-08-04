package com.carddemo.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity;
import com.carddemo.ledger.entity.TransactionCategoryBalanceEntity.TransactionCategoryBalanceId;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Covers {@link CategoryBalanceUpdater}, the category balance upsert at
 * {@code app/cbl/CBTRN02C.cbl:L467-L501}.
 *
 * <p>The keyed read at {@code :L481} accepts two file statuses:</p>
 *
 * <pre>
 *            IF  TCATBALF-STATUS = '00'  OR '23'
 * </pre>
 *
 * <p>Status {@code '23'} reaches Java as an empty {@link Optional}, so a missing row is a create
 * signal and no failure. Each write branch accepts one status alone, at {@code :L512} and at
 * {@code :L530}. The two nested groups below hold the branches the fork at {@code :L495-L499}
 * picks.</p>
 *
 * <p>The key is the COBOL (Common Business Oriented Language) group {@code 05 TRAN-CAT-KEY.} at
 * {@code app/cpy/CVTRA01Y.cpy:L5-L10}, seventeen bytes wide per {@code app/jcl/TCATBALF.jcl:L40}.
 * Its parts are {@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD}. Each part
 * stays a string, so zero padding survives.</p>
 *
 * <p>Record 1 of {@code app/data/ASCII/dailytran.txt} supplies the amounts: its zoned field
 * {@code 0000005047G} decodes to 504.77 at type {@code 01} and category {@code 0001}. Its card
 * resolves through row 21 of {@code app/data/ASCII/cardxref.txt} to account {@code 00000000007}.
 * The seed in {@code app/data/ASCII/tcatbal.txt} holds 50 triples at type {@code 01}, and the feed
 * produces 100, so 50 triples reach the update branch and 50 reach the create branch.</p>
 *
 * <p>Source-to-target mapping sits in {@code card-platform/docs/traceability-matrix.md}.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CategoryBalanceUpdater, the upsert at app/cbl/CBTRN02C.cbl:L467-L501")
class CategoryBalanceUpdaterTest {

    /** Account that row 21 of {@code app/data/ASCII/cardxref.txt} resolves to. */
    private static final String ACCOUNT_ID = "00000000007";

    /** Transaction type each triple in the {@code tcatbal.txt} seed carries. */
    private static final String SEEDED_TYPE_CODE = "01";

    /** Transaction type the seed omits, carried by 50 records of the feed. */
    private static final String UNSEEDED_TYPE_CODE = "03";

    /** The one category code each fixture record carries. */
    private static final String CATEGORY_CODE = "0001";

    /** Amount of {@code dailytran.txt} record 1, decoded from its zoned field. */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("504.77");

    /** Word the create diagnostic at {@code app/cbl/CBTRN02C.cbl:L476-L477} carries. */
    private static final String CREATE_DIAGNOSTIC_WORD = "creating";

    /** Answers each keyed read with {@code row} and each save with the row it received. */
    private static TransactionCategoryBalanceRepository repositoryReturning(
            Optional<TransactionCategoryBalanceEntity> row) {
        TransactionCategoryBalanceRepository categoryBalances =
                mock(TransactionCategoryBalanceRepository.class);
        when(categoryBalances.findById(any())).thenReturn(row);
        when(categoryBalances.save(any())).thenAnswer(call -> call.getArgument(0));
        return categoryBalances;
    }

    /** A collaborator whose keyed read finds no row, the empty result of {@code :L481}. */
    private static TransactionCategoryBalanceRepository noRowStored() {
        return repositoryReturning(Optional.empty());
    }

    /** A collaborator holding one row at {@link #SEEDED_TYPE_CODE} with {@code balance}. */
    private static TransactionCategoryBalanceRepository rowStored(String balance) {
        return repositoryReturning(Optional.of(rowHolding(balance)));
    }

    /** One stored row for {@link #ACCOUNT_ID}, {@link #SEEDED_TYPE_CODE} and the category code. */
    private static TransactionCategoryBalanceEntity rowHolding(String balance) {
        return new TransactionCategoryBalanceEntity(
                new TransactionCategoryBalanceId(ACCOUNT_ID, SEEDED_TYPE_CODE, CATEGORY_CODE),
                new BigDecimal(balance));
    }

    /** Captures the single row the subject stored. */
    private static TransactionCategoryBalanceEntity savedRow(
            TransactionCategoryBalanceRepository categoryBalances) {
        ArgumentCaptor<TransactionCategoryBalanceEntity> stored =
                ArgumentCaptor.forClass(TransactionCategoryBalanceEntity.class);
        verify(categoryBalances, times(1)).save(stored.capture());
        return stored.getValue();
    }

    /** Runs the upsert once for {@link #ACCOUNT_ID} and the fixture category code. */
    private static void upsert(TransactionCategoryBalanceRepository categoryBalances,
            String typeCode, BigDecimal amount) {
        new CategoryBalanceUpdater(categoryBalances)
                .updateCategoryBalance(ACCOUNT_ID, typeCode, CATEGORY_CODE, amount);
    }

    @Test
    @DisplayName("one public method, returning nothing, over three key parts and one amount"
            + " (app/cbl/CBTRN02C.cbl:L467-L501)")
    void theSubjectExposesOneUpsertReturningNothing() throws NoSuchMethodException {
        Method upsert = CategoryBalanceUpdater.class.getMethod("updateCategoryBalance",
                String.class, String.class, String.class, BigDecimal.class);
        List<Method> publicApi = Arrays.stream(CategoryBalanceUpdater.class.getDeclaredMethods())
                .filter(candidate -> Modifier.isPublic(candidate.getModifiers()))
                .toList();

        assertThat(publicApi).containsExactly(upsert);
        assertThat(upsert.getReturnType()).isEqualTo(void.class);
        assertThat(upsert.getParameterTypes())
                .containsExactly(String.class, String.class, String.class, BigDecimal.class);
        assertThat(CategoryBalanceUpdater.class.getDeclaredClasses()).isEmpty();
    }

    @Test
    @DisplayName("the keyed read carries all three key parts, seventeen bytes wide"
            + " (:L469-L471, :L473)")
    void theKeyedReadCarriesAllThreeKeyParts() {
        TransactionCategoryBalanceRepository categoryBalances = noRowStored();
        upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

        ArgumentCaptor<TransactionCategoryBalanceId> read =
                ArgumentCaptor.forClass(TransactionCategoryBalanceId.class);
        verify(categoryBalances, times(1)).findById(read.capture());
        TransactionCategoryBalanceId key = read.getValue();
        assertThat(key.getAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(key.getTypeCode()).isEqualTo(SEEDED_TYPE_CODE);
        assertThat(key.getCategoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(key.getAccountId().length() + key.getTypeCode().length()
                + key.getCategoryCode().length()).isEqualTo(PicClause.TRAN_CAT_KEY_WIDTH);
    }

    /**
     * The branch an empty keyed read picks, reproducing {@code 2700-A-CREATE-TCATBAL-REC}.
     *
     * <p>{@code INITIALIZE} at {@code :L504} zeroes the whole record, the three moves at
     * {@code :L505-L507} set the key again, and {@code :L508} adds the amount to zero. A created
     * balance therefore equals the amount.</p>
     */
    @Nested
    @DisplayName("the create branch, app/cbl/CBTRN02C.cbl:L503-L524")
    class CreateBranch {

        @Test
        @DisplayName("an empty keyed read stores exactly one row and throws nothing"
                + " (:L478, :L481, :L495-L499, :L510, :L512)")
        void anEmptyKeyedReadStoresExactlyOneRow() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();

            assertThatCode(() -> upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT))
                    .doesNotThrowAnyException();

            verify(categoryBalances, times(1)).findById(any());
            verify(categoryBalances, times(1)).save(any(TransactionCategoryBalanceEntity.class));
            verifyNoMoreInteractions(categoryBalances);
        }

        @Test
        @DisplayName("the created balance equals the amount, at the column scale (:L504, :L508)")
        void theCreatedBalanceEqualsTheAmount() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo(FIXTURE_AMOUNT);
            assertThat(stored.scale()).isEqualTo(PicClause.TRAN_CAT_BAL_SCALE);
            assertThat(stored.precision()).isLessThanOrEqualTo(PicClause.TRAN_CAT_BAL_PRECISION);
        }

        @Test
        @DisplayName("the created row keeps the three key parts and their leading zeros"
                + " (:L505-L507)")
        void theCreatedRowKeepsTheThreeKeyParts() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            TransactionCategoryBalanceId key = savedRow(categoryBalances).getId();
            assertThat(key.getAccountId()).isEqualTo(ACCOUNT_ID).startsWith("0")
                    .hasSize(PicClause.TRANCAT_ACCT_ID_WIDTH);
            assertThat(key.getTypeCode()).isEqualTo(UNSEEDED_TYPE_CODE).startsWith("0")
                    .hasSize(PicClause.TRANCAT_TYPE_CD_WIDTH);
            assertThat(key.getCategoryCode()).isEqualTo(CATEGORY_CODE).startsWith("0")
                    .hasSize(PicClause.TRANCAT_CD_WIDTH);
        }

        @Test
        @DisplayName("a negative amount creates a negative balance (:L508)")
        void aNegativeAmountCreatesANegativeBalance() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, new BigDecimal("-42.50"));

            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo("-42.50").isLessThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("an amount of zero creates a balance of 0.00 (:L508)")
        void anAmountOfZeroCreatesAZeroBalance() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, new BigDecimal("0.00"));

            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(stored.toPlainString()).isEqualTo("0.00");
        }

        /**
         * The upsert reads no reference table. {@code app/cpy/CVTRA04Y.cpy:L5-L7} declares a
         * six-byte group of the same name, keyed by {@code app/jcl/TRANCATG.jcl:L40}, and the
         * source consults neither it nor the transaction type table before storing.
         */
        @Test
        @DisplayName("an unseeded transaction type and category still create a row (:L510)")
        void anUnseededTypeAndCategoryStillCreateARow() {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            new CategoryBalanceUpdater(categoryBalances)
                    .updateCategoryBalance(ACCOUNT_ID, "99", "9999", FIXTURE_AMOUNT);

            TransactionCategoryBalanceId key = savedRow(categoryBalances).getId();
            assertThat(key.getTypeCode()).isEqualTo("99");
            assertThat(key.getCategoryCode()).isEqualTo("9999");
            verify(categoryBalances, times(1)).findById(any());
            verifyNoMoreInteractions(categoryBalances);
        }

        @Test
        @DisplayName("the create diagnostic stays below the default log threshold (:L476-L477)")
        void theCreateDiagnosticStaysBelowTheDefaultLogThreshold(CapturedOutput consoleOutput) {
            TransactionCategoryBalanceRepository categoryBalances = noRowStored();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            assertThat(consoleOutput.getAll())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(FIXTURE_AMOUNT.toPlainString())
                    .doesNotContainIgnoringCase(CREATE_DIAGNOSTIC_WORD);
        }
    }

    /**
     * The branch a present keyed read picks, reproducing {@code 2700-B-UPDATE-TCATBAL-REC}.
     *
     * <p>{@code :L527} adds the amount to the balance the read returned, and {@code :L528} stores
     * the result under the same key. The store keeps two decimal digits and discards the rest
     * toward zero, since the {@code ROUNDED} phrase appears nowhere in the source.</p>
     */
    @Nested
    @DisplayName("the update branch, app/cbl/CBTRN02C.cbl:L526-L542")
    class UpdateBranch {

        @Test
        @DisplayName("a present row stores the sum of its balance and the amount, once"
                + " (:L527, :L528, :L530)")
        void aPresentRowStoresTheSumOnce() {
            TransactionCategoryBalanceRepository categoryBalances = rowStored("1000.00");
            upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo("1504.77");
            assertThat(stored.scale()).isEqualTo(PicClause.TRAN_CAT_BAL_SCALE);
            verify(categoryBalances, times(1)).findById(any());
            verifyNoMoreInteractions(categoryBalances);
        }

        @Test
        @DisplayName("the stored row keeps the key the keyed read returned (:L528)")
        void theStoredRowKeepsTheKeyTheKeyedReadReturned() {
            TransactionCategoryBalanceEntity seeded = rowHolding("0.00");
            TransactionCategoryBalanceRepository categoryBalances =
                    repositoryReturning(Optional.of(seeded));
            upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            TransactionCategoryBalanceId stored = savedRow(categoryBalances).getId();
            assertThat(stored).isEqualTo(seeded.getId());
            assertThat(stored.getTypeCode()).isEqualTo(SEEDED_TYPE_CODE);
        }

        @Test
        @DisplayName("a negative amount lowers the stored balance (:L527)")
        void aNegativeAmountLowersTheStoredBalance() {
            TransactionCategoryBalanceRepository categoryBalances = rowStored("1000.00");
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("-250.75"));

            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo("749.25").isLessThan(new BigDecimal("1000.00"));
        }

        /**
         * A sum needing a third decimal digit loses it. Half-up rounding reaches a different value,
         * and the second assertion locks that difference in place.
         */
        @Test
        @DisplayName("a third decimal digit drops toward zero, where half-up rounding differs"
                + " (:L527)")
        void aThirdDecimalDigitDropsTowardZero() {
            TransactionCategoryBalanceRepository categoryBalances = rowStored("100.00");
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("0.005"));

            BigDecimal wholeSum = new BigDecimal("100.00").add(new BigDecimal("0.005"));
            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo("100.00").isNotEqualByComparingTo(
                    wholeSum.setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.HALF_UP));
            assertThat(stored.scale()).isEqualTo(PicClause.TRAN_CAT_BAL_SCALE);
        }

        /**
         * Rounding downward matches a drop toward zero for a positive sum and parts from it for a
         * negative one. A negative third decimal digit proves the store moves toward zero.
         */
        @Test
        @DisplayName("a negative third decimal digit drops toward zero, above the downward result"
                + " (:L527)")
        void aNegativeThirdDecimalDigitDropsTowardZero() {
            TransactionCategoryBalanceRepository categoryBalances = rowStored("-100.00");
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("-0.005"));

            BigDecimal downward = new BigDecimal("-100.00").add(new BigDecimal("-0.005"))
                    .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.FLOOR);
            BigDecimal stored = savedRow(categoryBalances).getCategoryBalance();
            assertThat(stored).isEqualByComparingTo("-100.00").isGreaterThan(downward);
        }

        @Test
        @DisplayName("the update branch writes no diagnostic, which sits at :L476-L477")
        void theUpdateBranchWritesNoDiagnostic(CapturedOutput consoleOutput) {
            TransactionCategoryBalanceRepository categoryBalances = rowStored("0.00");
            upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            assertThat(consoleOutput.getAll())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(FIXTURE_AMOUNT.toPlainString())
                    .doesNotContainIgnoringCase(CREATE_DIAGNOSTIC_WORD);
        }
    }
}
