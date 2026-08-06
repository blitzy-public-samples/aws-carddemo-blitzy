package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.TransactionIdentifierSource;
import com.carddemo.cobol.CobolDecimal;
import com.carddemo.cobol.PicClause;
import com.carddemo.ledger.domain.AccountBalanceUpdater;
import com.carddemo.ledger.entity.AccountBalanceProjectionEntity;
import com.carddemo.ledger.repository.AccountBalanceProjectionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds online bill payment at {@code app/cbl/COBIL00C.cbl:L203-L250} against batch posting at
 * {@code app/cbl/CBTRN02C.cbl:L545-L560}.
 *
 * <p>{@code L224} moves the whole of {@code ACCT-CURR-BAL} into {@code TRAN-AMT} and {@code L234}
 * subtracts that same amount, closing the balance at zero for every value {@code TRAN-AMT} holds.
 * A search of all 572 lines of {@code app/cbl/COBIL00C.cbl} finds no reference to either cycle
 * accumulator, while {@code L548-L551} of the batch paragraph moves one of them on every call.
 * Both halves are asserted below, on one shared input.
 *
 * <p>No target service migrates the online payment path, and the reference rule below is held in
 * this class. Rationale and flagged findings: {@code card-platform/docs/decision-log.md}.
 */
@DisplayName("Online bill payment at COBIL00C L203-L250 against batch posting at CBTRN02C L545-L560")
class BillPaymentEquivalenceTest {

    /** {@code MOVE '02' TO TRAN-TYPE-CD} at {@code app/cbl/COBIL00C.cbl:L220}, a quoted literal. */
    private static final String ONLINE_TYPE_CODE = "02";

    /**
     * {@code MOVE 2 TO TRAN-CAT-CD} at {@code app/cbl/COBIL00C.cbl:L221}. The target field is
     * {@code TRAN-CAT-CD PIC 9(04)} at {@code app/cpy/CVTRA05Y.cpy:L7}, and an unquoted numeric
     * literal reaches it zero-padded to four characters.
     */
    private static final String ONLINE_CATEGORY_CODE = "0002";

    /**
     * The category code every one of the {@value PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} records
     * of {@code app/data/ASCII/dailytran.txt} carries, held here as the value
     * {@link #ONLINE_CATEGORY_CODE} is checked against.
     */
    private static final String FEED_CATEGORY_CODE = "0001";

    /** {@code MOVE 'POS TERM' TO TRAN-SOURCE} at {@code app/cbl/COBIL00C.cbl:L222}. */
    private static final String ONLINE_SOURCE = "POS TERM";

    /** {@code MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC} at {@code app/cbl/COBIL00C.cbl:L223}. */
    private static final String ONLINE_DESCRIPTION = "BILL PAYMENT - ONLINE";

    /** {@code MOVE 999999999 TO TRAN-MERCHANT-ID} at {@code app/cbl/COBIL00C.cbl:L226}. */
    private static final String ONLINE_MERCHANT_IDENTIFIER = "999999999";

    /** {@code MOVE 'BILL PAYMENT' TO TRAN-MERCHANT-NAME} at {@code app/cbl/COBIL00C.cbl:L227}. */
    private static final String ONLINE_MERCHANT_NAME = "BILL PAYMENT";

    /**
     * {@code 'N/A'}, moved to {@code TRAN-MERCHANT-CITY} at {@code app/cbl/COBIL00C.cbl:L228} and
     * to {@code TRAN-MERCHANT-ZIP} at {@code app/cbl/COBIL00C.cbl:L229}.
     */
    private static final String ONLINE_MERCHANT_PLACEHOLDER = "N/A";

    /**
     * The layout of {@code WS-TIMESTAMP} at {@code app/cpy/CSDAT01Y.cpy:L42-L55}: dashes between
     * the date parts, one space at position 11, colons between the time parts.
     * {@code GET-CURRENT-TIMESTAMP} at {@code app/cbl/COBIL00C.cbl:L249-L267} fills it through
     * {@code DATESEP('-')} and {@code TIMESEP(':')}.
     */
    private static final DateTimeFormatter ONLINE_TIMESTAMP_LAYOUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    /** The separator ahead of {@code WS-TIMESTAMP-TM-MS6} at {@code app/cpy/CSDAT01Y.cpy:L54}. */
    private static final char ONLINE_TIMESTAMP_FRACTION_SEPARATOR = '.';

    /**
     * The single space the {@code FILLER} at {@code app/cpy/CSDAT01Y.cpy:L48} holds between the
     * date and the time. Every one of the
     * {@value PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} origin timestamps in
     * {@code app/data/ASCII/dailytran.txt} carries it.
     */
    private static final char ONLINE_TIMESTAMP_DATE_TIME_SEPARATOR = ' ';

    /**
     * The digit {@code MOVE ZEROS TO WS-TIMESTAMP-TM-MS6} at {@code app/cbl/COBIL00C.cbl:L266}
     * leaves in every fractional position.
     */
    private static final char ONLINE_TIMESTAMP_FRACTION_DIGIT = '0';

    /**
     * The index carrying the date and time separator in both timestamp forms. Position 11 of
     * {@code WS-TIMESTAMP} at {@code app/cpy/CSDAT01Y.cpy:L48} holds a space, and the same index of
     * {@code DB2-FORMAT-TS} holds {@code DB2-STREEP-3} at {@code app/cbl/CBTRN02C.cbl:L166}.
     */
    private static final int DATE_TIME_SEPARATOR_INDEX =
            PicClause.PROCESSING_TIMESTAMP_HOUR_OFFSET
                    - PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;

    /**
     * The index carrying the first time separator in both timestamp forms. {@code WS-TIMESTAMP}
     * holds a colon there at {@code app/cpy/CSDAT01Y.cpy:L50}, and {@code DB2-FORMAT-TS} holds
     * {@code DB2-DOT-1} at {@code app/cbl/CBTRN02C.cbl:L168}.
     */
    private static final int TIME_SEPARATOR_INDEX =
            PicClause.PROCESSING_TIMESTAMP_MINUTE_OFFSET
                    - PicClause.PROCESSING_TIMESTAMP_SEPARATOR_WIDTH;

    /**
     * ADDITIVE. A non-zero opening value for {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L13}. Every account of {@code app/data/ASCII/acctdata.txt} opens
     * both accumulators at zero, which
     * {@link CycleAccumulatorDivergence#everyFixtureAccountOpensWithBothAccumulatorsAtZero()}
     * asserts.
     */
    private static final BigDecimal SEEDED_CYCLE_CREDIT = new BigDecimal("4321.99");

    /**
     * ADDITIVE. A non-zero, negative opening value for {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    private static final BigDecimal SEEDED_CYCLE_DEBIT = new BigDecimal("-876.54");

    /**
     * The smallest amount either accumulator can hold, one unit at
     * {@value PicClause#ACCT_CURR_CYC_DEBIT_SCALE} decimal places. The sign test at
     * {@code app/cbl/CBTRN02C.cbl:L548} falls between the negation of this value and zero.
     */
    private static final BigDecimal SMALLEST_AMOUNT_STEP =
            BigDecimal.ONE.movePointLeft(PicClause.ACCT_CURR_CYC_DEBIT_SCALE);

    /** Zero at the scale {@code ACCT-CURR-BAL} holds, at {@code app/cpy/CVACT01Y.cpy:L7}. */
    private static final BigDecimal ZERO_AMOUNT =
            BigDecimal.ZERO.setScale(PicClause.ACCT_CURR_BAL_SCALE);

    /**
     * The smallest balance {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}
     * cannot hold: ten raised to the count of integer digits that field carries.
     * {@code ACCT-CURR-BAL PIC S9(10)V99} holds one integer digit more.
     */
    private static final BigDecimal FIRST_BALANCE_TRAN_AMT_CANNOT_HOLD = BigDecimal.TEN
            .pow(PicClause.TRAN_AMT_PRECISION - PicClause.TRAN_AMT_SCALE)
            .setScale(PicClause.ACCT_CURR_BAL_SCALE);

    /** The function a sequence allocation calls, upper case for the comparisons below. */
    private static final String SEQUENCE_FUNCTION = "NEXTVAL";

    /**
     * Statement fragments a read from the high end of a stored key range carries, and a sequence
     * allocation does not. {@code app/cbl/COBIL00C.cbl:L212-L217} performs that read with
     * {@code MOVE HIGH-VALUES}, {@code STARTBR}, {@code READPREV} and {@code ENDBR}.
     */
    private static final List<String> BACKWARDS_BROWSE_FRAGMENTS =
            List.of("MAX(", "ORDER BY", "DESC");

    /** The first value a fresh sequence yields. */
    private static final long FIRST_ALLOCATION = 1L;

    /** The last allocation needing one digit, where the zero padding is at its widest. */
    private static final long LARGEST_SINGLE_DIGIT_ALLOCATION = 9L;

    /** The next allocation after {@link #LARGEST_SINGLE_DIGIT_ALLOCATION}, needing two digits. */
    private static final long SMALLEST_TWO_DIGIT_ALLOCATION = LARGEST_SINGLE_DIGIT_ALLOCATION + 1L;

    /** Three rising allocations, spanning the point where an allocation gains a digit. */
    private static final List<Long> RISING_ALLOCATIONS = List.of(FIRST_ALLOCATION,
            LARGEST_SINGLE_DIGIT_ALLOCATION, SMALLEST_TWO_DIGIT_ALLOCATION);

    /** The schema the sequence lives in, matching the authorization service configuration. */
    private static final String MAPPED_SCHEMA = "authorization_service";

    /** All {@value PicClause#ACCTDATA_FIXTURE_RECORD_COUNT} account records, loaded once. */
    private static final List<CopybookRecordParser.AccountRecord> ACCOUNTS =
            CardDemoFixtureLoader.loadAccounts();

    /** All {@value PicClause#DAILYTRAN_FIXTURE_RECORD_COUNT} feed records, loaded once. */
    private static final List<CopybookRecordParser.DailyTransactionRecord> FEED =
            CardDemoFixtureLoader.loadDailyTransactions();

    /** The first feed record, whose origin timestamp fixes both timestamp comparisons below. */
    private static final CopybookRecordParser.DailyTransactionRecord FEED_RECORD = FEED.get(0);

    /** The moment {@link #FEED_RECORD} carries, read back through the online layout. */
    private static final LocalDateTime FEED_MOMENT = LocalDateTime.parse(
            FEED_RECORD.originTimestamp().substring(0,
                    PicClause.PROCESSING_TIMESTAMP_SECOND_OFFSET
                            + PicClause.PROCESSING_TIMESTAMP_COMPONENT_WIDTH),
            ONLINE_TIMESTAMP_LAYOUT);

    /**
     * {@link #FEED_MOMENT} carrying the largest fraction of a second {@link ChronoField} holds,
     * which is finer than the two digits the processing timestamp keeps.
     */
    private static final LocalDateTime SUB_HUNDREDTH_MOMENT = FEED_MOMENT.with(
            ChronoField.NANO_OF_SECOND, ChronoField.NANO_OF_SECOND.range().getMaximum());

    @Nested
    @DisplayName("The balance rule at COBIL00C L224 and L234")
    class OnlineBalanceRule {

        @Test
        @DisplayName("every acctdata.txt balance closes at zero")
        void everyFixtureBalanceClosesAtZero() {
            assertEquals(PicClause.ACCTDATA_FIXTURE_RECORD_COUNT, ACCOUNTS.size(),
                    "acctdata.txt must supply every account this rule runs against");

            for (CopybookRecordParser.AccountRecord account : ACCOUNTS) {
                OnlineBillPayment payment = applyOnlineBillPayment(account.currentBalance());

                assertEquals(0, payment.closingBalance().compareTo(BigDecimal.ZERO),
                        "COBIL00C L234 must leave ACCT-CURR-BAL at zero, compared with compareTo"
                                + " so a scale of two still counts as zero");
                assertEquals(PicClause.ACCT_CURR_BAL_SCALE, payment.closingBalance().scale(),
                        "the closing balance must keep the ACCT-CURR-BAL scale at CVACT01Y L7");
            }
        }

        @Test
        @DisplayName("the lowest and the highest acctdata.txt balance follow the same rule")
        void theFixtureBalanceBoundsCloseAtZero() {
            BigDecimal lowest = ACCOUNTS.stream()
                    .map(CopybookRecordParser.AccountRecord::currentBalance)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            BigDecimal highest = ACCOUNTS.stream()
                    .map(CopybookRecordParser.AccountRecord::currentBalance)
                    .max(Comparator.naturalOrder())
                    .orElseThrow();

            assertTrue(lowest.compareTo(highest) < 0,
                    "acctdata.txt must span a range of balances for the two bounds to differ");
            for (BigDecimal bound : List.of(lowest, highest)) {
                assertEquals(0,
                        applyOnlineBillPayment(bound).closingBalance().compareTo(BigDecimal.ZERO),
                        "a fixture balance bound must close at zero, compared with compareTo");
            }
        }

        @Test
        @DisplayName("the written amount is the opening balance, so L233 precedes L234")
        void theWrittenAmountIsTheOpeningBalance() {
            BigDecimal opening = ACCOUNTS.get(0).currentBalance();

            OnlineBillPayment payment = applyOnlineBillPayment(opening);

            assertEquals(0, payment.transactionAmount().compareTo(opening),
                    "COBIL00C L224 copies ACCT-CURR-BAL into TRAN-AMT, and the WRITE at L233 runs"
                            + " ahead of the COMPUTE at L234");
            assertEquals(0, payment.closingBalance().compareTo(BigDecimal.ZERO),
                    "COBIL00C L234 must subtract that written amount, compared with compareTo");
            assertNotEquals(0, payment.transactionAmount().compareTo(payment.closingBalance()),
                    "a written amount equal to the closing balance would put the COMPUTE at L234"
                            + " ahead of the WRITE at L233");
        }

        @Test
        @DisplayName("a balance wider than TRAN-AMT does not close at zero")
        void aBalanceWiderThanTheAmountFieldLeavesTheBalanceStanding() {
            OnlineBillPayment payment = applyOnlineBillPayment(FIRST_BALANCE_TRAN_AMT_CANNOT_HOLD);

            assertEquals(0, payment.transactionAmount().compareTo(BigDecimal.ZERO),
                    "the MOVE at COBIL00C L224 drops the integer digit ACCT-CURR-BAL holds and"
                            + " TRAN-AMT at CVTRA05Y L10 does not");
            assertEquals(0,
                    payment.closingBalance().compareTo(FIRST_BALANCE_TRAN_AMT_CANNOT_HOLD),
                    "COBIL00C L234 subtracts the narrowed amount, and the balance stands");
        }

        @Test
        @DisplayName("batch posting adds to the balance where the online rule zeroes it")
        void batchPostingAddsToTheBalanceTheOnlineRuleZeroes() {
            CopybookRecordParser.AccountRecord account = ACCOUNTS.get(0);
            BigDecimal amount = account.currentBalance();

            BigDecimal onlineClosing = applyOnlineBillPayment(amount).closingBalance();
            BigDecimal batchClosing = applyBatchPosting(account, amount).getCurrentBalance();

            assertEquals(0, onlineClosing.compareTo(BigDecimal.ZERO),
                    "COBIL00C L234 subtracts the whole balance, compared with compareTo");
            assertEquals(CobolDecimal.add(amount, amount, PicClause.ACCT_CURR_BAL_SCALE),
                    batchClosing,
                    "CBTRN02C L547 adds the amount to ACCT-CURR-BAL, in value and scale");
            assertNotEquals(0, batchClosing.compareTo(onlineClosing),
                    "COBIL00C L234 and CBTRN02C L547 must reach different balances from one input");
        }
    }

    @Nested
    @DisplayName("Cycle accumulators: untouched by COBIL00C, moved by CBTRN02C L548-L551")
    class CycleAccumulatorDivergence {

        @Test
        @DisplayName("every acctdata.txt account opens with both accumulators at zero")
        void everyFixtureAccountOpensWithBothAccumulatorsAtZero() {
            for (CopybookRecordParser.AccountRecord account : ACCOUNTS) {
                assertEquals(0, account.currentCycleCredit().compareTo(BigDecimal.ZERO),
                        "acctdata.txt carries zero in ACCT-CURR-CYC-CREDIT at CVACT01Y L13 for"
                                + " every account, compared with compareTo");
                assertEquals(0, account.currentCycleDebit().compareTo(BigDecimal.ZERO),
                        "acctdata.txt carries zero in ACCT-CURR-CYC-DEBIT at CVACT01Y L14 for"
                                + " every account, compared with compareTo");
            }
        }

        @Test
        @DisplayName("ADDITIVE seeded input: the online rule leaves both accumulators untouched")
        void theOnlineRuleLeavesBothSeededAccumulatorsUntouched() {
            OnlineBillPayment payment = applyOnlineBillPayment(ACCOUNTS.get(0).currentBalance(),
                    SEEDED_CYCLE_CREDIT, SEEDED_CYCLE_DEBIT, FEED_MOMENT);

            assertEquals(SEEDED_CYCLE_CREDIT, payment.cycleCredit(),
                    "online bill payment must leave ACCT-CURR-CYC-CREDIT at its opening value and"
                            + " scale; COBIL00C names that field in none of its 572 lines");
            assertEquals(SEEDED_CYCLE_DEBIT, payment.cycleDebit(),
                    "online bill payment must leave ACCT-CURR-CYC-DEBIT at its opening value and"
                            + " scale; COBIL00C names that field in none of its 572 lines");
        }

        @Test
        @DisplayName("batch posting sends a positive amount to cycle credit at CBTRN02C L549")
        void batchPostingMovesCycleCreditForAPositiveAmount() {
            CopybookRecordParser.AccountRecord account = ACCOUNTS.get(0);
            BigDecimal amount = account.currentBalance();

            AccountBalanceProjectionEntity posted = applyBatchPosting(account, amount);

            assertEquals(CobolDecimal.add(SEEDED_CYCLE_CREDIT, amount,
                            PicClause.ACCT_CURR_CYC_CREDIT_SCALE), posted.getCycleCredit(),
                    "CBTRN02C L549 must add the positive amount to ACCT-CURR-CYC-CREDIT, in value"
                            + " and scale");
            assertEquals(SEEDED_CYCLE_DEBIT, posted.getCycleDebit(),
                    "the branch at CBTRN02C L549 must leave ACCT-CURR-CYC-DEBIT at its opening"
                            + " value");
            assertNotEquals(SEEDED_CYCLE_CREDIT, posted.getCycleCredit(),
                    "CBTRN02C L549 must move an accumulator on the input COBIL00C leaves alone");
        }

        @Test
        @DisplayName("batch posting sends a negative amount to cycle debit at CBTRN02C L551")
        void batchPostingMovesCycleDebitForANegativeAmount() {
            CopybookRecordParser.AccountRecord account = ACCOUNTS.get(0);
            BigDecimal amount = SMALLEST_AMOUNT_STEP.negate();

            AccountBalanceProjectionEntity posted = applyBatchPosting(account, amount);

            assertEquals(CobolDecimal.add(SEEDED_CYCLE_DEBIT, amount,
                            PicClause.ACCT_CURR_CYC_DEBIT_SCALE), posted.getCycleDebit(),
                    "CBTRN02C L551 must add the negative amount to ACCT-CURR-CYC-DEBIT, in value"
                            + " and scale");
            assertEquals(SEEDED_CYCLE_CREDIT, posted.getCycleCredit(),
                    "the branch at CBTRN02C L551 must leave ACCT-CURR-CYC-CREDIT at its opening"
                            + " value");
        }

        @Test
        @DisplayName("the sign test at CBTRN02C L548 changes branch within one step of zero")
        void theSignTestChangesBranchWithinOneStepOfZero() {
            CopybookRecordParser.AccountRecord account = ACCOUNTS.get(0);

            AccountBalanceProjectionEntity belowZero =
                    applyBatchPosting(account, SMALLEST_AMOUNT_STEP.negate());
            AccountBalanceProjectionEntity atZero = applyBatchPosting(account, ZERO_AMOUNT);
            AccountBalanceProjectionEntity aboveZero =
                    applyBatchPosting(account, SMALLEST_AMOUNT_STEP);

            assertNotEquals(SEEDED_CYCLE_DEBIT, belowZero.getCycleDebit(),
                    "one step below zero must reach ACCT-CURR-CYC-DEBIT at CBTRN02C L551");
            assertNotEquals(SEEDED_CYCLE_CREDIT, aboveZero.getCycleCredit(),
                    "one step above zero must reach ACCT-CURR-CYC-CREDIT at CBTRN02C L549");
            assertEquals(SEEDED_CYCLE_DEBIT, aboveZero.getCycleDebit(),
                    "one step above zero must leave ACCT-CURR-CYC-DEBIT at its opening value");
            assertEquals(SEEDED_CYCLE_DEBIT, atZero.getCycleDebit(),
                    "CBTRN02C L548 tests DALYTRAN-AMT >= 0, and a zero amount must leave"
                            + " ACCT-CURR-CYC-DEBIT at its opening value");
            assertEquals(CobolDecimal.add(SEEDED_CYCLE_CREDIT, ZERO_AMOUNT,
                            PicClause.ACCT_CURR_CYC_CREDIT_SCALE), atZero.getCycleCredit(),
                    "the credit branch at CBTRN02C L549 must hold the opening value with zero"
                            + " added to it");
        }
    }

    @Nested
    @DisplayName("Timestamps: one value online at COBIL00C L231-L232, two forms in batch posting")
    class TimestampDivergence {

        @Test
        @DisplayName("the online path leaves TRAN-ORIG-TS and TRAN-PROC-TS equal")
        void theOnlinePathLeavesBothTimestampFieldsEqual() {
            OnlineBillPayment payment = applyOnlineBillPayment(ACCOUNTS.get(0).currentBalance());

            assertEquals(payment.originTimestamp(), payment.processingTimestamp(),
                    "COBIL00C L231-L232 moves one WS-TIMESTAMP into both fields");
            assertEquals(PicClause.TRAN_ORIG_TS_WIDTH, payment.originTimestamp().length(),
                    "TRAN-ORIG-TS at CVTRA05Y L16 holds a fixed width");
            assertEquals(PicClause.TRAN_PROC_TS_WIDTH, payment.processingTimestamp().length(),
                    "TRAN-PROC-TS at CVTRA05Y L17 holds a fixed width");
        }

        @Test
        @DisplayName("the online layout renders the shape dailytran.txt already carries")
        void theOnlineLayoutRendersTheFeedOriginShape() {
            assertEquals(FEED_RECORD.originTimestamp(), renderOnlineTimestamp(FEED_MOMENT),
                    "the WS-TIMESTAMP layout at CSDAT01Y L42-L55 must render the origin timestamp"
                            + " dailytran.txt holds for the same moment");
        }

        @Test
        @DisplayName("batch posting leaves the two fields different at CBTRN02C L436-L438")
        void batchPostingLeavesTheTwoTimestampFieldsDifferent() {
            String origin = FEED_RECORD.originTimestamp();
            String processing = CobolDecimal.formatProcessingTimestamp(FEED_MOMENT);

            assertNotEquals(origin, processing,
                    "CBTRN02C L436 takes TRAN-ORIG-TS from the feed and L437-L438 generates"
                            + " TRAN-PROC-TS, so one moment reaches the two fields in two shapes");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_DASH,
                    processing.charAt(DATE_TIME_SEPARATOR_INDEX),
                    "DB2-STREEP-3 at CBTRN02C L166 puts a dash between the day and the hour");
            assertEquals(ONLINE_TIMESTAMP_DATE_TIME_SEPARATOR,
                    origin.charAt(DATE_TIME_SEPARATOR_INDEX),
                    "the FILLER at CSDAT01Y L48 puts a space at the same index of the feed shape");
        }

        @Test
        @DisplayName("the two forms use different time separators")
        void theTwoFormsUseDifferentTimeSeparators() {
            String origin = FEED_RECORD.originTimestamp();
            String processing = CobolDecimal.formatProcessingTimestamp(FEED_MOMENT);

            assertEquals(PicClause.PROCESSING_TIMESTAMP_DOT,
                    processing.charAt(TIME_SEPARATOR_INDEX),
                    "DB2-DOT-1 at CBTRN02C L168 separates the hour from the minute with a dot");
            assertNotEquals(processing.charAt(TIME_SEPARATOR_INDEX),
                    origin.charAt(TIME_SEPARATOR_INDEX),
                    "TIMESEP(':') at COBIL00C L260 separates them with a colon in the feed shape");
        }

        @Test
        @DisplayName("the generated form keeps two fractional digits and four literal zeros")
        void theGeneratedFormKeepsTwoFractionalDigitsAndFourZeros() {
            String processing = CobolDecimal.formatProcessingTimestamp(SUB_HUNDREDTH_MOMENT);

            assertEquals(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS,
                    processing.substring(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                    "MOVE '0000' TO DB2-REST at CBTRN02C L701 fills the last four characters");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS,
                    PicClause.PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET
                            - PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET,
                    "DB2-MIL at CBTRN02C L173 holds the only significant fractional digits");
            assertNotEquals(ONLINE_TIMESTAMP_FRACTION_DIGIT,
                    processing.charAt(PicClause.PROCESSING_TIMESTAMP_FRACTION_OFFSET),
                    "a moment carrying hundredths must reach DB2-MIL, and a fraction finer than"
                            + " that is dropped");
            assertEquals(PicClause.PROCESSING_TIMESTAMP_WIDTH, processing.length(),
                    "DB2-FORMAT-TS at CBTRN02C L159 holds a fixed width");
        }
    }

    @Nested
    @DisplayName("The values COBIL00C L220-L229 stamps on the written row")
    class StampedTransactionFields {

        @Test
        @DisplayName("the row carries the four reproduced text literals")
        void theRowCarriesTheReproducedTextLiterals() {
            OnlineBillPayment payment = applyOnlineBillPayment(ACCOUNTS.get(0).currentBalance());

            assertEquals(ONLINE_SOURCE, payment.source(),
                    "COBIL00C L222 stamps TRAN-SOURCE");
            assertEquals(ONLINE_DESCRIPTION, payment.description(),
                    "COBIL00C L223 stamps TRAN-DESC, hyphen and surrounding spaces included");
            assertEquals(ONLINE_MERCHANT_NAME, payment.merchantName(),
                    "COBIL00C L227 stamps TRAN-MERCHANT-NAME");
            assertEquals(ONLINE_MERCHANT_PLACEHOLDER, payment.merchantCity(),
                    "COBIL00C L228 stamps TRAN-MERCHANT-CITY");
            assertEquals(ONLINE_MERCHANT_PLACEHOLDER, payment.merchantZip(),
                    "COBIL00C L229 stamps TRAN-MERCHANT-ZIP");
        }

        @Test
        @DisplayName("the row carries type 02 and merchant identifier 999999999 at their field widths")
        void theRowCarriesTheCodedValuesAtTheirFieldWidths() {
            OnlineBillPayment payment = applyOnlineBillPayment(ACCOUNTS.get(0).currentBalance());

            assertEquals(ONLINE_TYPE_CODE, payment.typeCode(),
                    "COBIL00C L220 stamps TRAN-TYPE-CD");
            assertEquals(PicClause.TRAN_TYPE_CD_WIDTH, payment.typeCode().length(),
                    "TRAN-TYPE-CD at CVTRA05Y L6 holds a fixed width");
            assertEquals(ONLINE_MERCHANT_IDENTIFIER, payment.merchantIdentifier(),
                    "COBIL00C L226 stamps TRAN-MERCHANT-ID");
            assertEquals(PicClause.TRAN_MERCHANT_ID_WIDTH, payment.merchantIdentifier().length(),
                    "TRAN-MERCHANT-ID at CVTRA05Y L11 holds a fixed width");
            assertTrue(payment.source().length() <= PicClause.TRAN_SOURCE_WIDTH,
                    "TRAN-SOURCE at CVTRA05Y L8 must hold the stamped literal");
            assertTrue(payment.description().length() <= PicClause.TRAN_DESC_WIDTH,
                    "TRAN-DESC at CVTRA05Y L9 must hold the stamped literal");
            assertTrue(payment.merchantName().length() <= PicClause.TRAN_MERCHANT_NAME_WIDTH,
                    "TRAN-MERCHANT-NAME at CVTRA05Y L12 must hold the stamped literal");
            assertTrue(payment.merchantCity().length() <= PicClause.TRAN_MERCHANT_CITY_WIDTH,
                    "TRAN-MERCHANT-CITY at CVTRA05Y L13 must hold the stamped literal");
            assertTrue(payment.merchantZip().length() <= PicClause.TRAN_MERCHANT_ZIP_WIDTH,
                    "TRAN-MERCHANT-ZIP at CVTRA05Y L14 must hold the stamped literal");
        }

        @Test
        @DisplayName("the category code is zero-padded to four characters at COBIL00C L221")
        void theCategoryCodeIsZeroPaddedToFourCharacters() {
            OnlineBillPayment payment = applyOnlineBillPayment(ACCOUNTS.get(0).currentBalance());

            assertEquals(ONLINE_CATEGORY_CODE, payment.categoryCode(),
                    "an unquoted numeric literal moved to TRAN-CAT-CD PIC 9(04) at CVTRA05Y L7"
                            + " reaches the field zero-padded");
            assertEquals(PicClause.TRAN_CAT_CD_WIDTH, payment.categoryCode().length(),
                    "TRAN-CAT-CD at CVTRA05Y L7 holds a fixed width");
        }

        @Test
        @DisplayName("the online category code separates a payment row from every dailytran.txt row")
        void theOnlineCategoryCodeDiffersFromEveryFeedRecord() {
            assertEquals(PicClause.DAILYTRAN_FIXTURE_RECORD_COUNT, FEED.size(),
                    "dailytran.txt must supply every record this comparison runs against");
            assertNotEquals(FEED_CATEGORY_CODE, ONLINE_CATEGORY_CODE,
                    "COBIL00C L221 and the feed must carry different category codes");

            for (CopybookRecordParser.DailyTransactionRecord record : FEED) {
                assertEquals(FEED_CATEGORY_CODE, record.categoryCode(),
                        "every dailytran.txt record must carry the feed category code, which is"
                                + " what makes the online code a discriminator in stored data");
            }
        }
    }

    @Nested
    @DisplayName("ADDITIVE: a database sequence replaces the browse at COBIL00C L212-L217")
    class TransactionIdentifierAllocation {

        @Test
        @DisplayName("allocation is monotonic across a change in digit count")
        void allocationIsMonotonicAcrossAChangeInDigitCount() {
            TransactionIdentifierSource source =
                    sequenceSource(new ArrayList<>(), RISING_ALLOCATIONS);

            List<String> allocated = new ArrayList<>();
            for (int allocation = 0; allocation < RISING_ALLOCATIONS.size(); allocation++) {
                allocated.add(source.nextIdentifier());
            }

            assertEquals(PicClause.TRAN_ID_WIDTH, TransactionIdentifierSource.IDENTIFIER_WIDTH,
                    "the allocated width must match TRAN-ID PIC X(16) at CVTRA05Y L5");
            for (String identifier : allocated) {
                assertEquals(PicClause.TRAN_ID_WIDTH, identifier.length(),
                        "every allocated identifier must fill TRAN-ID");
            }
            for (int later = 1; later < allocated.size(); later++) {
                assertTrue(allocated.get(later - 1).compareTo(allocated.get(later)) < 0,
                        "zero padding must keep allocated TRAN-ID values rising in text order as"
                                + " the digit count grows, and got " + allocated.get(later - 1)
                                + " ahead of " + allocated.get(later));
            }
        }

        @Test
        @DisplayName("allocation reads a sequence and performs no browse from the high end")
        void allocationReadsASequenceAndPerformsNoBackwardsBrowse() {
            List<String> statements = new ArrayList<>();
            TransactionIdentifierSource source = sequenceSource(statements, RISING_ALLOCATIONS);

            for (int allocation = 0; allocation < RISING_ALLOCATIONS.size(); allocation++) {
                source.nextIdentifier();
            }

            assertEquals(RISING_ALLOCATIONS.size(), statements.size(),
                    "each allocation must run one sequence statement in place of the browse at"
                            + " COBIL00C L212-L217");
            for (String statement : statements) {
                String upperCase = statement.toUpperCase(Locale.ROOT);
                assertTrue(upperCase.contains(SEQUENCE_FUNCTION),
                        "allocation must call the sequence, and ran: " + statement);
                for (String fragment : BACKWARDS_BROWSE_FRAGMENTS) {
                    assertFalse(upperCase.contains(fragment),
                            "allocation must carry no " + fragment + " read of the stored"
                                    + " TRAN-ID range, and ran: " + statement);
                }
            }
        }
    }

    /**
     * Applies the online rule to one balance, opening from the seeded accumulators at the moment
     * {@code app/data/ASCII/dailytran.txt} carries.
     *
     * @param openingBalance {@code ACCT-CURR-BAL} as the row holds it
     * @return the fields {@code app/cbl/COBIL00C.cbl:L218-L234} leaves behind
     */
    private static OnlineBillPayment applyOnlineBillPayment(BigDecimal openingBalance) {
        return applyOnlineBillPayment(openingBalance, SEEDED_CYCLE_CREDIT, SEEDED_CYCLE_DEBIT,
                FEED_MOMENT);
    }

    /**
     * Applies the documented {@code COBIL00C} rule in the statement order the source runs it, under
     * the {@code ERR-FLG} guard at {@code L208} and the {@code CONF-PAY-YES} guard at {@code L210}.
     * {@code L224} carries the whole balance into the narrower {@code TRAN-AMT}, {@code L231-L232}
     * moves one timestamp into both fields, and {@code L234} subtracts the written amount.
     *
     * @param openingBalance {@code ACCT-CURR-BAL} as the row holds it
     * @param cycleCredit    {@code ACCT-CURR-CYC-CREDIT} as the row holds it
     * @param cycleDebit     {@code ACCT-CURR-CYC-DEBIT} as the row holds it
     * @param moment         the moment {@code GET-CURRENT-TIMESTAMP} at
     *                       {@code app/cbl/COBIL00C.cbl:L249} reads
     * @return the fields {@code app/cbl/COBIL00C.cbl:L218-L234} leaves behind
     */
    private static OnlineBillPayment applyOnlineBillPayment(BigDecimal openingBalance,
            BigDecimal cycleCredit, BigDecimal cycleDebit, LocalDateTime moment) {
        BigDecimal transactionAmount = CobolDecimal.truncateToPictureField(openingBalance,
                PicClause.TRAN_AMT_PRECISION, PicClause.TRAN_AMT_SCALE);
        String timestamp = renderOnlineTimestamp(moment);
        BigDecimal closingBalance = CobolDecimal.subtract(openingBalance, transactionAmount,
                PicClause.ACCT_CURR_BAL_SCALE);

        return new OnlineBillPayment(transactionAmount, closingBalance, cycleCredit, cycleDebit,
                ONLINE_TYPE_CODE, ONLINE_CATEGORY_CODE, ONLINE_SOURCE, ONLINE_DESCRIPTION,
                ONLINE_MERCHANT_IDENTIFIER, ONLINE_MERCHANT_NAME, ONLINE_MERCHANT_PLACEHOLDER,
                ONLINE_MERCHANT_PLACEHOLDER, timestamp, timestamp);
    }

    /**
     * Renders one moment as {@code GET-CURRENT-TIMESTAMP} at
     * {@code app/cbl/COBIL00C.cbl:L249-L267} builds {@code WS-TIMESTAMP}.
     *
     * <p>The date and time reach positions 1 to 19 and {@code L266} zeroes every remaining
     * fractional position, filling the field to the width {@code TRAN-ORIG-TS} holds.
     *
     * @param moment the moment to render
     * @return {@value PicClause#TRAN_ORIG_TS_WIDTH} characters
     */
    private static String renderOnlineTimestamp(LocalDateTime moment) {
        StringBuilder rendered = new StringBuilder(PicClause.TRAN_ORIG_TS_WIDTH)
                .append(ONLINE_TIMESTAMP_LAYOUT.format(moment))
                .append(ONLINE_TIMESTAMP_FRACTION_SEPARATOR);
        while (rendered.length() < PicClause.TRAN_ORIG_TS_WIDTH) {
            rendered.append(ONLINE_TIMESTAMP_FRACTION_DIGIT);
        }
        return rendered.toString();
    }

    /**
     * Runs {@code 2800-UPDATE-ACCOUNT-REC} through the production updater over one seeded row.
     *
     * <p>The row opens with the account balance the fixture carries and the two seeded
     * accumulators. No Spring context and no database take part.
     *
     * @param account the fixture account supplying the identifier and the opening balance
     * @param amount  the posted amount, whose sign selects the branch at
     *                {@code app/cbl/CBTRN02C.cbl:L548}
     * @return the row the updater stored
     */
    private static AccountBalanceProjectionEntity applyBatchPosting(
            CopybookRecordParser.AccountRecord account, BigDecimal amount) {
        AccountBalanceProjectionEntity seeded = new AccountBalanceProjectionEntity(
                account.accountId(), account.currentBalance(), SEEDED_CYCLE_CREDIT,
                SEEDED_CYCLE_DEBIT);
        AtomicReference<AccountBalanceProjectionEntity> stored = new AtomicReference<>(seeded);
        AccountBalanceProjectionRepository balances =
                mock(AccountBalanceProjectionRepository.class);

        when(balances.findForUpdateById(account.accountId())).thenReturn(Optional.of(seeded));
        when(balances.save(any(AccountBalanceProjectionEntity.class))).thenAnswer(posted -> {
            stored.set(posted.getArgument(0));
            return stored.get();
        });

        new AccountBalanceUpdater(balances).updateBalances(account.accountId(), amount);

        return stored.get();
    }

    /**
     * Builds an identifier source over a persistence context that yields the given allocations.
     *
     * @param statements  collects the statement each allocation runs
     * @param allocations the values the sequence yields, in order
     * @return the source under test
     */
    private static TransactionIdentifierSource sequenceSource(List<String> statements,
            List<Long> allocations) {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        Iterator<Long> remaining = allocations.iterator();

        when(entityManager.createNativeQuery(anyString())).thenAnswer(allocation -> {
            statements.add(allocation.getArgument(0));
            return query;
        });
        when(query.getSingleResult()).thenAnswer(allocation -> remaining.next());

        return new TransactionIdentifierSource(entityManager, MAPPED_SCHEMA);
    }

    /**
     * The fields {@code app/cbl/COBIL00C.cbl:L218-L234} leaves behind on one payment.
     *
     * @param transactionAmount   {@code TRAN-AMT} after {@code L224}
     * @param closingBalance      {@code ACCT-CURR-BAL} after {@code L234}
     * @param cycleCredit         {@code ACCT-CURR-CYC-CREDIT}, which the program never names
     * @param cycleDebit          {@code ACCT-CURR-CYC-DEBIT}, which the program never names
     * @param typeCode            {@code TRAN-TYPE-CD} after {@code L220}
     * @param categoryCode        {@code TRAN-CAT-CD} after {@code L221}
     * @param source              {@code TRAN-SOURCE} after {@code L222}
     * @param description         {@code TRAN-DESC} after {@code L223}
     * @param merchantIdentifier  {@code TRAN-MERCHANT-ID} after {@code L226}
     * @param merchantName        {@code TRAN-MERCHANT-NAME} after {@code L227}
     * @param merchantCity        {@code TRAN-MERCHANT-CITY} after {@code L228}
     * @param merchantZip         {@code TRAN-MERCHANT-ZIP} after {@code L229}
     * @param originTimestamp     {@code TRAN-ORIG-TS} after {@code L231}
     * @param processingTimestamp {@code TRAN-PROC-TS} after {@code L232}
     */
    private record OnlineBillPayment(
            BigDecimal transactionAmount,
            BigDecimal closingBalance,
            BigDecimal cycleCredit,
            BigDecimal cycleDebit,
            String typeCode,
            String categoryCode,
            String source,
            String description,
            String merchantIdentifier,
            String merchantName,
            String merchantCity,
            String merchantZip,
            String originTimestamp,
            String processingTimestamp) {
    }
}
