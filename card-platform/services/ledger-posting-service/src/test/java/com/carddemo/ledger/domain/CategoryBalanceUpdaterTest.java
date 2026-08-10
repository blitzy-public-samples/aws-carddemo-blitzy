package com.carddemo.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.repository.TransactionCategoryBalanceRepository;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
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
 * <p>Status {@code '23'} is the absent row, which is a create signal and no failure. Each write
 * branch accepts one status alone, at {@code :L512} and at {@code :L530}. Both branches reach the
 * database as one statement here: the insert arm carries {@code 2700-A-CREATE-TCATBAL-REC} at
 * {@code :L503-L524} and the conflict arm carries {@code 2700-B-UPDATE-TCATBAL-REC} at
 * {@code :L526-L542}. The fork at {@code :L495-L499} therefore has no Java counterpart, and the
 * primary key decides it inside the statement.</p>
 *
 * <p>The key is the COBOL (Common Business Oriented Language) group {@code 05 TRAN-CAT-KEY.} at
 * {@code app/cpy/CVTRA01Y.cpy:L5-L10}, seventeen bytes wide per {@code app/jcl/TCATBALF.jcl:L40}.
 * Its parts are {@code TRANCAT-ACCT-ID}, {@code TRANCAT-TYPE-CD} and {@code TRANCAT-CD}. Each part
 * stays a string, so zero padding survives.</p>
 *
 * <p>The addition runs in the database at the scale the column declares, so the amount is truncated
 * before it reaches the statement and the sum of two values at that scale needs no rounding at all.
 * The group below on the addend asserts that truncation.</p>
 *
 * <p>Record 1 of {@code app/data/ASCII/dailytran.txt} supplies the amounts: its zoned field
 * {@code 0000005047G} decodes to 504.77 at type {@code 01} and category {@code 0001}. Its card
 * resolves through row 21 of {@code app/data/ASCII/cardxref.txt} to account {@code 00000000007}.
 * The seed in {@code app/data/ASCII/tcatbal.txt} holds 50 triples at type {@code 01}, and the feed
 * produces 100, so 50 triples reach the conflict arm and 50 reach the insert arm.</p>
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

    /** Rows the upsert writes on either arm. */
    private static final int STORE_WRAPPED = 1;

    /** Rows a statement that wrote nothing reports. */
    private static final int STORE_INSIDE_FIELD = 0;

    /**
     * Answers the upsert with {@code rowCount}, the count the statement reports.
     *
     * @param rowCount rows the statement claims to have written
     * @return a collaborator whose upsert reports that count
     */
    private static TransactionCategoryBalanceRepository repositoryWriting(int rowCount) {
        TransactionCategoryBalanceRepository categoryBalances =
                mock(TransactionCategoryBalanceRepository.class);
        when(categoryBalances.addToCategoryBalance(anyString(), anyString(), anyString(),
                any(BigDecimal.class))).thenReturn(rowCount);
        return categoryBalances;
    }

    /** A collaborator whose upsert writes the one row either arm writes. */
    private static TransactionCategoryBalanceRepository storeInsideTheField() {
        return repositoryWriting(STORE_INSIDE_FIELD);
    }

    /**
     * Captures the addend the subject passed, failing when the call count differs from one.
     *
     * @param categoryBalances the collaborator the subject called
     * @return the amount the statement received
     */
    private static BigDecimal capturedAddend(
            TransactionCategoryBalanceRepository categoryBalances) {
        ArgumentCaptor<BigDecimal> addend = ArgumentCaptor.forClass(BigDecimal.class);
        verify(categoryBalances, times(1)).addToCategoryBalance(anyString(), anyString(),
                anyString(), addend.capture());
        return addend.getValue();
    }

    /**
     * Captures the three key parts the subject passed, in declared order.
     *
     * @param categoryBalances the collaborator the subject called
     * @return the account identifier, the type code and the category code
     */
    private static List<String> capturedKeyParts(
            TransactionCategoryBalanceRepository categoryBalances) {
        ArgumentCaptor<String> accountId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> typeCode = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> categoryCode = ArgumentCaptor.forClass(String.class);
        verify(categoryBalances, times(1)).addToCategoryBalance(accountId.capture(),
                typeCode.capture(), categoryCode.capture(), any(BigDecimal.class));
        return List.of(accountId.getValue(), typeCode.getValue(), categoryCode.getValue());
    }

    /**
     * Runs the upsert once for {@link #ACCOUNT_ID} and the fixture category code.
     *
     * @param categoryBalances the collaborator to call
     * @param typeCode         the two-character type code
     * @param amount           the signed amount to add
     * @return whether the store wrapped past the field
     */
    private static boolean upsert(TransactionCategoryBalanceRepository categoryBalances,
            String typeCode, BigDecimal amount) {
        return new CategoryBalanceUpdater(categoryBalances)
                .updateCategoryBalance(ACCOUNT_ID, typeCode, CATEGORY_CODE, amount);
    }

    @Test
    @DisplayName("one public method, answering whether the store wrapped, over three key parts and"
            + " one amount (app/cbl/CBTRN02C.cbl:L467-L501)")
    void theSubjectExposesOneUpsertAnsweringTheWrap() throws NoSuchMethodException {
        Method upsert = CategoryBalanceUpdater.class.getMethod("updateCategoryBalance",
                String.class, String.class, String.class, BigDecimal.class);
        List<Method> publicApi = Arrays.stream(CategoryBalanceUpdater.class.getDeclaredMethods())
                .filter(candidate -> Modifier.isPublic(candidate.getModifiers()))
                .toList();

        assertThat(publicApi).containsExactly(upsert);
        assertThat(upsert.getReturnType()).isEqualTo(boolean.class);
        assertThat(upsert.getParameterTypes())
                .containsExactly(String.class, String.class, String.class, BigDecimal.class);
        assertThat(CategoryBalanceUpdater.class.getDeclaredClasses()).isEmpty();
    }

    /**
     * The one statement both source branches reach, and the arguments it carries.
     *
     * <p>A read followed by an insert leaves a window in which a second delivery reads nothing as
     * well and one of the two inserts fails on the primary key. One statement has no such window.
     */
    @Nested
    @DisplayName("the one statement, app/cbl/CBTRN02C.cbl:L503-L542")
    class OneStatement {

        @Test
        @DisplayName("the subject reads nothing and saves nothing, running one upsert"
                + " (:L478, :L481, :L495-L499)")
        void theSubjectRunsOneUpsertAndNothingElse() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();

            assertThatCode(() -> upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT))
                    .doesNotThrowAnyException();

            verify(categoryBalances, times(1)).addToCategoryBalance(anyString(), anyString(),
                    anyString(), any(BigDecimal.class));
            verifyNoMoreInteractions(categoryBalances);
        }

        @Test
        @DisplayName("the statement carries all three key parts, seventeen bytes wide"
                + " (:L469-L471, :L505-L507)")
        void theStatementCarriesAllThreeKeyParts() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            List<String> parts = capturedKeyParts(categoryBalances);
            assertThat(parts.get(0)).isEqualTo(ACCOUNT_ID);
            assertThat(parts.get(1)).isEqualTo(SEEDED_TYPE_CODE);
            assertThat(parts.get(2)).isEqualTo(CATEGORY_CODE);
            assertThat(parts.get(0).length() + parts.get(1).length() + parts.get(2).length())
                    .isEqualTo(PicClause.TRAN_CAT_KEY_WIDTH);
        }

        @Test
        @DisplayName("the three key parts keep their leading zeros and their widths (:L505-L507)")
        void theThreeKeyPartsKeepTheirLeadingZeros() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            List<String> parts = capturedKeyParts(categoryBalances);
            assertThat(parts.get(0)).isEqualTo(ACCOUNT_ID).startsWith("0")
                    .hasSize(PicClause.TRANCAT_ACCT_ID_WIDTH);
            assertThat(parts.get(1)).isEqualTo(UNSEEDED_TYPE_CODE).startsWith("0")
                    .hasSize(PicClause.TRANCAT_TYPE_CD_WIDTH);
            assertThat(parts.get(2)).isEqualTo(CATEGORY_CODE).startsWith("0")
                    .hasSize(PicClause.TRANCAT_CD_WIDTH);
        }

        /**
         * The upsert reads no reference table. {@code app/cpy/CVTRA04Y.cpy:L5-L7} declares a
         * six-byte group of the same name, keyed by {@code app/jcl/TRANCATG.jcl:L40}, and the
         * source consults neither it nor the transaction type table before storing.
         */
        @Test
        @DisplayName("an unseeded transaction type and category still write a row (:L510)")
        void anUnseededTypeAndCategoryStillWriteARow() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            new CategoryBalanceUpdater(categoryBalances)
                    .updateCategoryBalance(ACCOUNT_ID, "99", "9999", FIXTURE_AMOUNT);

            List<String> parts = capturedKeyParts(categoryBalances);
            assertThat(parts.get(1)).isEqualTo("99");
            assertThat(parts.get(2)).isEqualTo("9999");
            verifyNoMoreInteractions(categoryBalances);
        }

        @Test
        @DisplayName("an answer outside the two the statement can give fails the posting"
                + " (:L512, :L530)")
        void anAnswerOutsideTheTwoItCanGiveFailsThePosting() {
            int impossible = 2;
            TransactionCategoryBalanceRepository categoryBalances = repositoryWriting(impossible);

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT))
                    .withMessageContaining(String.valueOf(impossible));
        }

        @Test
        @DisplayName("a store that wrapped is reported to the caller, and one inside the field"
                + " is not")
        void aStoreThatWrappedIsReportedToTheCaller() {
            assertThat(upsert(repositoryWriting(STORE_WRAPPED), SEEDED_TYPE_CODE, FIXTURE_AMOUNT))
                    .as("the caller needs this to report the wrap after its transaction commits")
                    .isTrue();
            assertThat(upsert(storeInsideTheField(), SEEDED_TYPE_CODE, FIXTURE_AMOUNT))
                    .as("an ordinary store reports nothing").isFalse();
        }

        @Test
        @DisplayName("no diagnostic reaches the console, and none carries a value (:L476-L477)")
        void noDiagnosticReachesTheConsole(CapturedOutput consoleOutput) {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            assertThat(consoleOutput.getAll())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(FIXTURE_AMOUNT.toPlainString())
                    .doesNotContainIgnoringCase(CREATE_DIAGNOSTIC_WORD);
        }
    }

    /**
     * The amount the statement receives, at the scale the column declares.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L508} and {@code :L527} both store through an {@code ADD} with
     * no {@code ROUNDED} phrase, so each store truncates toward zero. Truncating the addend before
     * the statement keeps that behaviour and keeps the database out of the rounding decision: two
     * values at scale two sum to a value at scale two.
     */
    @Nested
    @DisplayName("the addend, app/cbl/CBTRN02C.cbl:L508 and :L527")
    class TheAddend {

        @Test
        @DisplayName("the addend is the amount at the column scale (:L508)")
        void theAddendIsTheAmountAtTheColumnScale() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            BigDecimal addend = capturedAddend(categoryBalances);
            assertThat(addend).isEqualByComparingTo(FIXTURE_AMOUNT);
            assertThat(addend.scale()).isEqualTo(PicClause.TRAN_CAT_BAL_SCALE);
            assertThat(addend.precision()).isLessThanOrEqualTo(PicClause.TRAN_CAT_BAL_PRECISION);
        }

        @Test
        @DisplayName("a negative amount travels negative, so it lowers the balance (:L527)")
        void aNegativeAmountTravelsNegative() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("-250.75"));

            assertThat(capturedAddend(categoryBalances))
                    .isEqualByComparingTo("-250.75").isLessThan(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("an amount of zero travels as 0.00 (:L508)")
        void anAmountOfZeroTravelsAsZero() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, UNSEEDED_TYPE_CODE, new BigDecimal("0.00"));

            BigDecimal addend = capturedAddend(categoryBalances);
            assertThat(addend).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(addend.toPlainString()).isEqualTo("0.00");
        }

        /**
         * A third decimal digit drops. Half-up rounding reaches a different value, and the second
         * assertion locks that difference in place.
         */
        @Test
        @DisplayName("a third decimal digit drops toward zero, where half-up rounding differs"
                + " (:L527)")
        void aThirdDecimalDigitDropsTowardZero() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("0.005"));

            BigDecimal addend = capturedAddend(categoryBalances);
            assertThat(addend).isEqualByComparingTo("0.00").isNotEqualByComparingTo(
                    new BigDecimal("0.005")
                            .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.HALF_UP));
            assertThat(addend.scale()).isEqualTo(PicClause.TRAN_CAT_BAL_SCALE);
        }

        /**
         * Rounding downward matches a drop toward zero for a positive value and parts from it for a
         * negative one. A negative third decimal digit proves the truncation moves toward zero.
         */
        @Test
        @DisplayName("a negative third decimal digit drops toward zero, above the downward result"
                + " (:L527)")
        void aNegativeThirdDecimalDigitDropsTowardZero() {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, SEEDED_TYPE_CODE, new BigDecimal("-0.005"));

            BigDecimal downward = new BigDecimal("-0.005")
                    .setScale(PicClause.TRAN_CAT_BAL_SCALE, RoundingMode.FLOOR);
            assertThat(capturedAddend(categoryBalances))
                    .isEqualByComparingTo("0.00").isGreaterThan(downward);
        }

        @Test
        @DisplayName("no console output carries the amount, on either arm (:L476-L477)")
        void noConsoleOutputCarriesTheAmount(CapturedOutput consoleOutput) {
            TransactionCategoryBalanceRepository categoryBalances = storeInsideTheField();
            upsert(categoryBalances, SEEDED_TYPE_CODE, FIXTURE_AMOUNT);

            assertThat(consoleOutput.getAll())
                    .doesNotContain(ACCOUNT_ID)
                    .doesNotContain(FIXTURE_AMOUNT.toPlainString())
                    .doesNotContainIgnoringCase(CREATE_DIAGNOSTIC_WORD);
        }
    }
}
