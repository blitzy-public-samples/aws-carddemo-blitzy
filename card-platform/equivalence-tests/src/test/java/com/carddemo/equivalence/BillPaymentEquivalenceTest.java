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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** The checked-in bill-payment expectations this class is the declared consumer of. */
    private static final String EXPECTED_PAYMENT_FILE = "bill-payment-results.csv";

    /** Every row of {@link #EXPECTED_PAYMENT_FILE}, parsed once for the whole class. */
    private static final ExpectedOutcomes EXPECTED_PAYMENTS =
            ExpectedOutcomes.load(EXPECTED_PAYMENT_FILE);

    /** The online bill-payment program, which is the only source of the payment path. */
    private static final String ONLINE_PROGRAM = "app/cbl/COBIL00C.cbl";

    /** The batch posting program, which the online path is compared against. */
    private static final String POSTING_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** The copybook that declares the online timestamp structure. */
    private static final String TIMESTAMP_COPYBOOK = "app/cpy/CSDAT01Y.cpy";

    /** The migrated allocation this platform replaced the browse-backwards race with. */
    private static final String TRANSACTION_IDENTIFIER_SOURCE = "card-platform/services/"
            + "authorization-service/src/main/java/com/carddemo/authorization/domain/"
            + "TransactionIdentifierSource.java";

    /** The sibling file that records where the guard becomes reachable after posting. */
    private static final String ACCOUNT_END_STATE_FILE =
            "acctdata-final-account-state-model-b.csv";

    /** The cycle-credit accumulator the online path never touches. */
    private static final String CYCLE_CREDIT_FIELD = "ACCT-CURR-CYC-CREDIT";

    /** The cycle-debit accumulator the online path never touches. */
    private static final String CYCLE_DEBIT_FIELD = "ACCT-CURR-CYC-DEBIT";

    /** The category balance the online path never writes. */
    private static final String CATEGORY_BALANCE_FIELD = "TRAN-CAT-BAL";

    /** The balance field the online path moves whole into the transaction amount. */
    private static final String ONLINE_BALANCE_FIELD = "ACCT-CURR-BAL";

    /** The active-status value every seeded account carries. */
    private static final String ACTIVE_STATUS = "Y";

    /** The phrase whose absence pins truncation on the subtraction. */
    private static final String ROUNDED_PHRASE = "ROUNDED";

    /** The upper-case confirmation literal the branch accepts. */
    private static final String UPPER_CASE_YES = "Y";

    /** The lower-case confirmation literal the branch accepts. */
    private static final String LOWER_CASE_YES = "y";

    /** A fragment of the invalid-confirmation message, enough to name it uniquely. */
    private static final String INVALID_VALUE_FRAGMENT = "Invalid value";

    /** A fragment of the unconfirmed-payment message. */
    private static final String UNCONFIRMED_FRAGMENT = "Confirm to make";

    /** A fragment of the nothing-to-pay guard message. */
    private static final String NOTHING_TO_PAY_FRAGMENT = "nothing to pay";

    /** The prefix every positional separator expectation carries. */
    private static final String SEPARATOR_FIELD_PREFIX = "separator_at_position_";

    /** The first accumulator the batch program adds a signed amount into. */
    private static final int FIRST_ACCUMULATOR = 1;

    /** The second accumulator the batch program adds a signed amount into. */
    private static final int SECOND_ACCUMULATOR = FIRST_ACCUMULATOR + 1;

    /** The paragraphs the allocation browse is made of, in the order the program performs them. */
    private static final List<String> ALLOCATION_BROWSE_PARAGRAPHS = List.of(
            "STARTBR-TRANSACT-FILE", "READPREV-TRANSACT-FILE", "ENDBR-TRANSACT-FILE");

    /** The update intent a browse would have to carry in order to serialise two callers. */
    private static final String UPDATE_INTENT = "UPDATE";

    /** The enqueue the program would have to issue in order to serialise two callers. */
    private static final String ENQUEUE_COMMAND = "EXEC CICS ENQ";

    /** The rendered shape of the online timestamp of {@code app/cpy/CSDAT01Y.cpy:L42-L55}. */
    private static final String ONLINE_TIMESTAMP_SHAPE = "YYYY-MM-DD HH:MM:SS.000000";


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

    @Nested
    @DisplayName(EXPECTED_PAYMENT_FILE + " bound row by row")
    class CheckedInPaymentExpectations {

        @Test
        @DisplayName("every row matches the fixture, the online program or the batch program")
        void everyRowOfThePaymentExpectationsMatches() {
            for (ExpectedOutcomes.Row row : EXPECTED_PAYMENTS.rows()) {
                String expected = EXPECTED_PAYMENTS.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());

                assertEquals(expected, actualPaymentValue(row),
                        EXPECTED_PAYMENT_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertTrue(EXPECTED_PAYMENTS.unconsumedRows().isEmpty(),
                    EXPECTED_PAYMENTS.unconsumedDescription());
        }
    }

    /**
     * Resolves what the fixture, the online program or the batch program holds for one row.
     *
     * <p>The file carries nine rows for each of the fifty seeded accounts, then eight mechanics
     * sections whose {@code record_seq} names the section. No branch reads
     * {@code expected_value}.</p>
     *
     * @param row the expectation to resolve
     * @return the value the row must equal
     * @throws IllegalStateException when the row names a field this method does not resolve
     */
    private static String actualPaymentValue(ExpectedOutcomes.Row row) {
        return switch (row.recordSequence()) {
            case "TXN-SHAPE" -> paymentTransactionValue(row.expectedField());
            case "DIVERGENCE" -> divergenceValue(row.expectedField());
            case "CONFIRM-GATE" -> confirmationGateValue(row.expectedField());
            case "ZERO-GUARD" -> zeroGuardValue(row.expectedField());
            case "TRAN-ID" -> allocationValue(row.expectedField());
            case "TIMESTAMP" -> timestampValue(row.expectedField());
            case "SUMMARY" -> paymentSummaryValue(row.expectedField());
            case "PROHIBITION" -> paymentProhibitionValue(row.expectedField());
            default -> seededAccountPaymentValue(row);
        };
    }

    /** Resolves one of the nine expectations of one seeded account. */
    private static String seededAccountPaymentValue(ExpectedOutcomes.Row row) {
        int ordinal = Integer.parseInt(row.recordSequence());
        CopybookRecordParser.AccountRecord account = ACCOUNTS.get(ordinal - 1);

        assertEquals(row.entityKey(), account.accountId(), EXPECTED_PAYMENT_FILE + " row "
                + row.key() + " names an account the fixture does not carry at that sequence");

        BigDecimal balance = account.currentBalance();
        return switch (row.expectedField()) {
            case "acct_curr_bal_before_payment" -> balance.toPlainString();
            case "tran_amt_equals_balance_before" -> CobolDecimal
                    .truncateToScale(balance, PicClause.TRAN_AMT_SCALE).toPlainString();
            case "acct_curr_bal_after_payment" -> CobolDecimal
                    .subtract(balance, balance, PicClause.ACCT_CURR_BAL_SCALE).toPlainString();
            case "acct_curr_cyc_credit_before", "acct_curr_cyc_credit_after" ->
                    account.currentCycleCredit().toPlainString();
            case "acct_curr_cyc_debit_before", "acct_curr_cyc_debit_after" ->
                    account.currentCycleDebit().toPlainString();
            case "tran_card_num" -> cardNumberOf(account.accountId());
            case "payable" -> paymentYesOrNo(balance.signum() > 0);
            default -> throw new IllegalStateException(EXPECTED_PAYMENT_FILE
                    + " names unresolved account field " + row.expectedField());
        };
    }

    /** Resolves one {@code TXN-SHAPE} expectation of the online transaction build. */
    private static String paymentTransactionValue(String field) {
        return switch (field) {
            case "record_initialized_before_build" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "INITIALIZE TRAN-RECORD"));
            case "tran_type_cd" -> onlineLiteralInto("TRAN-TYPE-CD");
            case "tran_cat_cd" -> paddedCode(onlineLiteralInto("TRAN-CAT-CD"),
                    PicClause.TRAN_CAT_CD_WIDTH);
            case "tran_source" -> spacePadded(onlineLiteralInto("TRAN-SOURCE"),
                    PicClause.TRAN_SOURCE_WIDTH);
            case "tran_desc_literal" -> onlineLiteralInto("TRAN-DESC");
            case "tran_desc_space_padded_to_declared_width" -> paymentYesOrNo(
                    spacePadded(onlineLiteralInto("TRAN-DESC"), PicClause.TRAN_DESC_WIDTH)
                            .length() == PicClause.TRAN_DESC_WIDTH);
            case "tran_amt_source_field" -> onlineFieldInto("TRAN-AMT");
            case "tran_amt_narrows_from_balance_picture" ->
                    paymentYesOrNo(PicClause.TRAN_AMT_PRECISION < PicClause.ACCT_CURR_BAL_PRECISION);
            case "tran_card_num_source_field" -> onlineFieldInto("TRAN-CARD-NUM");
            case "tran_merchant_id" -> paddedCode(onlineLiteralInto("TRAN-MERCHANT-ID"),
                    PicClause.TRAN_MERCHANT_ID_WIDTH);
            case "tran_merchant_name_literal" -> onlineLiteralInto("TRAN-MERCHANT-NAME");
            case "tran_merchant_city_literal" -> onlineLiteralInto("TRAN-MERCHANT-CITY");
            case "tran_merchant_zip_literal" -> onlineLiteralInto("TRAN-MERCHANT-ZIP");
            case "one_timestamp_into_both_ts_fields" ->
                    paymentYesOrNo(oneTimestampReachesBothFields());
            case "write_precedes_balance_update" -> paymentYesOrNo(writePrecedesTheSubtraction());
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved shape field " + field);
        };
    }

    /** Resolves one {@code DIVERGENCE} expectation between the online and batch paths. */
    private static String divergenceValue(String field) {
        return switch (field) {
            case "online_path_updates_current_balance" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT"));
            case "online_path_updates_cycle_credit" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(ONLINE_PROGRAM, CYCLE_CREDIT_FIELD) > 0L);
            case "online_path_updates_cycle_debit" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(ONLINE_PROGRAM, CYCLE_DEBIT_FIELD) > 0L);
            case "cyc_credit_occurrences_in_program" ->
                    Long.toString(CobolSourceEvidence.occurrences(ONLINE_PROGRAM,
                            CYCLE_CREDIT_FIELD));
            case "cyc_debit_occurrences_in_program" ->
                    Long.toString(CobolSourceEvidence.occurrences(ONLINE_PROGRAM,
                            CYCLE_DEBIT_FIELD));
            case "batch_path_updates_current_balance" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(POSTING_PROGRAM, "ADD DALYTRAN-AMT TO ACCT-CURR-BAL"));
            case "batch_path_updates_one_accumulator_on_sign" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(POSTING_PROGRAM, "IF DALYTRAN-AMT >= 0"));
            case "batch_positive_amount_target" -> batchAccumulatorTarget(FIRST_ACCUMULATOR);
            case "batch_negative_amount_target" -> batchAccumulatorTarget(SECOND_ACCUMULATOR);
            case "online_path_uses_subtraction" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "ACCT-CURR-BAL - TRAN-AMT"));
            case "batch_path_uses_addition" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(POSTING_PROGRAM, "ADD DALYTRAN-AMT TO"));
            case "online_path_writes_no_category_balance" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(ONLINE_PROGRAM, CATEGORY_BALANCE_FIELD) == 0L);
            case "batch_path_writes_category_balance" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(POSTING_PROGRAM, CATEGORY_BALANCE_FIELD) > 0L);
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved divergence field " + field);
        };
    }

    /** Resolves one {@code CONFIRM-GATE} expectation of the confirmation branch. */
    private static String confirmationGateValue(String field) {
        return switch (field) {
            case "payment_requires_confirmation" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "IF CONF-PAY-YES"));
            case "confirm_accepts_upper_y" -> paymentYesOrNo(
                    confirmationBranchAccepts(UPPER_CASE_YES));
            case "confirm_accepts_lower_y" -> paymentYesOrNo(
                    confirmationBranchAccepts(LOWER_CASE_YES));
            case "decline_clears_screen_and_sets_error" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "PERFORM CLEAR-CURRENT-SCREEN"));
            case "blank_reads_account_without_paying" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "WHEN SPACES"));
            case "invalid_value_message" -> onlineMessageContaining(INVALID_VALUE_FRAGMENT);
            case "unconfirmed_message" -> onlineMessageContaining(UNCONFIRMED_FRAGMENT);
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved gate field " + field);
        };
    }

    /** Resolves one {@code ZERO-GUARD} expectation of the nothing-to-pay guard. */
    private static String zeroGuardValue(String field) {
        return switch (field) {
            case "guard_condition" -> guardCondition();
            case "guard_message" -> onlineMessageContaining(NOTHING_TO_PAY_FRAGMENT);
            case "guard_rejects_zero_balance" -> paymentYesOrNo(guardRejects(BigDecimal.ZERO));
            case "guard_rejects_negative_balance" -> paymentYesOrNo(
                    guardRejects(BigDecimal.ONE.negate()));
            case "guard_requires_account_id_supplied" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "ACTIDINI OF COBIL0AI NOT = SPACES AND LOW-VALUES"));
            case "seeded_accounts_with_zero_or_negative_balance" -> Long.toString(
                    ACCOUNTS.stream().filter(a -> a.currentBalance().signum() <= 0).count());
            case "guard_unreachable_on_seeded_balances" -> paymentYesOrNo(
                    ACCOUNTS.stream().noneMatch(a -> guardRejects(a.currentBalance())));
            case "guard_reachability_after_posting_recorded_in" ->
                    expectedResourceOnTheClasspath(ACCOUNT_END_STATE_FILE);
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved guard field " + field);
        };
    }

    /** Resolves one {@code TRAN-ID} expectation of the browse-backwards allocation. */
    private static String allocationValue(String field) {
        return switch (field) {
            case "allocation_seeds_with_high_values" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "MOVE HIGH-VALUES TO TRAN-ID"));
            case "allocation_reads_previous_record" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "PERFORM READPREV-TRANSACT-FILE"));
            case "allocation_adds_one" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "ADD 1 TO WS-TRAN-ID-NUM"));
            case "working_counter_pic" ->
                    CobolSourceEvidence.pictureOf(ONLINE_PROGRAM, "WS-TRAN-ID-NUM");
            case "empty_file_yields_zero_then_one" -> paymentYesOrNo(
                    CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "MOVE ZEROS TO TRAN-ID"));
            case "allocation_is_read_modify_write_race" ->
                    paymentYesOrNo(theAllocationIsARace());
            case "target_replaces_allocation_with_database_sequence" ->
                    paymentYesOrNo(theTargetAllocatesFromASequence());
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved allocation field " + field);
        };
    }

    /** Resolves one {@code TIMESTAMP} expectation of either timestamp shape. */
    private static String timestampValue(String field) {
        if (field.startsWith(SEPARATOR_FIELD_PREFIX)) {
            return Character.toString(onlineSeparatorAt(
                    Integer.parseInt(field.substring(field.lastIndexOf('_') + 1))));
        }
        return switch (field) {
            case "total_length" -> Integer.toString(ONLINE_TIMESTAMP_SHAPE.length());
            case "year_component_pic" ->
                    CobolSourceEvidence.pictureOf(TIMESTAMP_COPYBOOK, "WS-TIMESTAMP-DT-YYYY");
            case "fraction_digit_count" -> Integer.toString(ONLINE_TIMESTAMP_SHAPE.length()
                    - ONLINE_TIMESTAMP_SHAPE.indexOf(ONLINE_TIMESTAMP_FRACTION_SEPARATOR) - 1);
            case "fraction_explicitly_zeroed" -> paymentYesOrNo(CobolSourceEvidence
                    .containsStatement(ONLINE_PROGRAM, "MOVE ZEROS TO WS-TIMESTAMP-TM-MS6"));
            case "rendered_shape" -> ONLINE_TIMESTAMP_SHAPE;
            case "matches_fixture_origin_timestamp_shape" ->
                    paymentYesOrNo(theFeedTimestampCarriesTheOnlineShape());
            case "batch_proc_timestamp_separator_at_position_11" ->
                    Character.toString(PicClause.PROCESSING_TIMESTAMP_SHAPE
                            .charAt(DATE_TIME_SEPARATOR_INDEX));
            case "batch_proc_timestamp_significant_fraction_digits" -> Integer
                    .toString(PicClause.PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS);
            case "batch_proc_timestamp_trailing_literal_zeros" ->
                    Integer.toString(PicClause.PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS);
            case "two_timestamp_shapes_must_not_be_conflated" -> paymentYesOrNo(
                    ONLINE_TIMESTAMP_SHAPE.charAt(DATE_TIME_SEPARATOR_INDEX)
                            != PicClause.PROCESSING_TIMESTAMP_SHAPE
                                    .charAt(DATE_TIME_SEPARATOR_INDEX));
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved timestamp field " + field);
        };
    }

    /** Resolves one {@code SUMMARY} expectation of the seeded account set. */
    private static String paymentSummaryValue(String field) {
        return switch (field) {
            case "accounts_evaluated" -> Integer.toString(ACCOUNTS.size());
            case "accounts_payable" -> Long.toString(
                    ACCOUNTS.stream().filter(a -> a.currentBalance().signum() > 0).count());
            case "accounts_rejected_by_guard" -> Long.toString(
                    ACCOUNTS.stream().filter(a -> guardRejects(a.currentBalance())).count());
            case "minimum_seeded_balance" -> extremeSeededBalance(true).toPlainString();
            case "maximum_seeded_balance" -> extremeSeededBalance(false).toPlainString();
            case "all_seeded_balances_are_whole_dollars" -> paymentYesOrNo(ACCOUNTS.stream()
                    .allMatch(a -> a.currentBalance().remainder(BigDecimal.ONE).signum() == 0));
            case "distinct_seeded_cycle_credit_values" -> Integer.toString(ACCOUNTS.stream()
                    .map(a -> a.currentCycleCredit().toPlainString()).distinct().toList().size());
            case "distinct_seeded_cycle_debit_values" -> Integer.toString(ACCOUNTS.stream()
                    .map(a -> a.currentCycleDebit().toPlainString()).distinct().toList().size());
            case "accounts_with_active_status_y" -> Long.toString(ACCOUNTS.stream()
                    .filter(a -> ACTIVE_STATUS.equals(a.activeStatus())).count());
            case "balance_after_payment_on_every_account" -> everyBalanceAfterPayment();
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved summary field " + field);
        };
    }

    /** Resolves one {@code PROHIBITION} expectation of the online payment discipline. */
    private static String paymentProhibitionValue(String field) {
        return switch (field) {
            case "no_cycle_accumulator_update_on_online_path" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(ONLINE_PROGRAM, CYCLE_CREDIT_FIELD) == 0L
                            && CobolSourceEvidence
                                    .occurrences(ONLINE_PROGRAM, CYCLE_DEBIT_FIELD) == 0L);
            case "no_category_balance_write_on_online_path" -> paymentYesOrNo(
                    CobolSourceEvidence.occurrences(ONLINE_PROGRAM, CATEGORY_BALANCE_FIELD) == 0L);
            case "no_shared_posting_routine_with_batch" -> paymentYesOrNo(CobolSourceEvidence
                    .occurrences(ONLINE_PROGRAM, "2800-UPDATE-ACCOUNT-REC") == 0L);
            case "no_partial_payment_path_exists" -> paymentYesOrNo(
                    ONLINE_BALANCE_FIELD.equals(onlineFieldInto("TRAN-AMT")));
            case "no_account_status_check_before_payment" -> paymentYesOrNo(CobolSourceEvidence
                    .occurrences(ONLINE_PROGRAM, "ACCT-ACTIVE-STATUS") == 0L);
            case "no_rounding_on_the_subtraction" -> paymentYesOrNo(!CobolSourceEvidence
                    .contains(ONLINE_PROGRAM, ROUNDED_PHRASE));
            default -> throw new IllegalStateException(
                    EXPECTED_PAYMENT_FILE + " names unresolved prohibition field " + field);
        };
    }

    /** Answers the literal the online program moves into one transaction field. */
    private static String onlineLiteralInto(String field) {
        Matcher matcher = Pattern
                .compile("MOVE '?([^'\\n]*?)'? +TO " + field + "$", Pattern.MULTILINE)
                .matcher(String.join("\n", CobolSourceEvidence.lines(ONLINE_PROGRAM)));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    ONLINE_PROGRAM + " moves no literal into " + field);
        }
        return matcher.group(1);
    }

    /** Names the field the online program moves into one transaction field. */
    private static String onlineFieldInto(String field) {
        Matcher matcher = Pattern
                .compile("MOVE ([A-Z0-9-]+) +TO " + field + "$", Pattern.MULTILINE)
                .matcher(String.join("\n", CobolSourceEvidence.lines(ONLINE_PROGRAM)));
        if (!matcher.find()) {
            throw new IllegalStateException(ONLINE_PROGRAM + " moves no field into " + field);
        }
        return matcher.group(1);
    }

    /** Left-pads a numeric literal to the width its Picture clause declares. */
    private static String paddedCode(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(value + " exceeds " + width + " digits");
        }
        return "0".repeat(width - value.length()) + value;
    }

    /** Right-pads an alphanumeric literal to the width its Picture clause declares. */
    private static String spacePadded(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(value + " exceeds " + width + " bytes");
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Reports whether one timestamp move reaches both timestamp fields.
     *
     * <p>The statement is written across two source lines, with the second receiving field on a
     * continuation line of its own, so the pairing is checked as adjacent lines rather than as one
     * normalised line.</p>
     *
     * @return {@code true} when the move names the origin field and the next line names the
     *         processing field
     */
    private static boolean oneTimestampReachesBothFields() {
        List<String> lines = CobolSourceEvidence.lines(ONLINE_PROGRAM);
        int move = lines.indexOf("MOVE WS-TIMESTAMP TO TRAN-ORIG-TS");
        return move >= 0 && move + 1 < lines.size() && "TRAN-PROC-TS".equals(lines.get(move + 1));
    }

    /** Reports whether the write really precedes the subtraction in source order. */
    private static boolean writePrecedesTheSubtraction() {
        List<String> lines = CobolSourceEvidence.lines(ONLINE_PROGRAM);
        int write = lines.indexOf("PERFORM WRITE-TRANSACT-FILE");
        int subtract = lines.indexOf("COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT");
        if (write < 0 || subtract < 0) {
            throw new IllegalStateException(
                    ONLINE_PROGRAM + " carries no write-then-subtract pairing");
        }
        return write < subtract;
    }

    /** Names the accumulator the batch program adds a signed amount into, in source order. */
    private static String batchAccumulatorTarget(int ordinal) {
        List<String> targets = new ArrayList<>();
        Pattern add = Pattern.compile("ADD DALYTRAN-AMT TO (ACCT-CURR-CYC-[A-Z]+)");
        for (String line : CobolSourceEvidence.lines(POSTING_PROGRAM)) {
            Matcher matcher = add.matcher(line);
            if (matcher.find()) {
                targets.add(matcher.group(1));
            }
        }
        if (targets.size() < ordinal) {
            throw new IllegalStateException(POSTING_PROGRAM + " adds the amount into only "
                    + targets.size() + " accumulators");
        }
        return targets.get(ordinal - 1);
    }

    /** Reports whether the confirmation branch accepts one literal. */
    private static boolean confirmationBranchAccepts(String literal) {
        return CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, "WHEN '" + literal + "'");
    }

    /** Answers the online program's message literal containing one fragment. */
    private static String onlineMessageContaining(String fragment) {
        for (String line : CobolSourceEvidence.lines(ONLINE_PROGRAM)) {
            Matcher matcher = Pattern.compile("MOVE '([^']*" + Pattern.quote(fragment)
                    + "[^']*)'").matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException(
                ONLINE_PROGRAM + " carries no message containing " + fragment);
    }

    /** Answers the nothing-to-pay guard condition as the program writes it. */
    private static String guardCondition() {
        Matcher matcher = Pattern.compile("IF (ACCT-CURR-BAL <= ZEROS) AND")
                .matcher(String.join("\n", CobolSourceEvidence.lines(ONLINE_PROGRAM)));
        if (!matcher.find()) {
            throw new IllegalStateException(ONLINE_PROGRAM + " carries no nothing-to-pay guard");
        }
        return matcher.group(1);
    }

    /** Applies the nothing-to-pay guard of {@code app/cbl/COBIL00C.cbl:L198} to one balance. */
    private static boolean guardRejects(BigDecimal balance) {
        return balance.signum() <= 0;
    }

    /**
     * Reports whether the allocation is a read-modify-write race.
     *
     * <p>Three separate statements make it one: the browse is seeded with the highest key, the
     * previous record is read, and one is added to what came back. None of the three browse
     * paragraphs carries update intent and the program issues no enqueue, so two terminals reading
     * the same highest identifier both allocate the same successor. The account read elsewhere in
     * the program does carry update intent, which is why the check is confined to the browse.</p>
     *
     * @return {@code true} when all three statements are present and nothing serialises them
     */
    private static boolean theAllocationIsARace() {
        boolean seeds = CobolSourceEvidence
                .containsStatement(ONLINE_PROGRAM, "MOVE HIGH-VALUES TO TRAN-ID");
        boolean readsPrevious = CobolSourceEvidence
                .containsStatement(ONLINE_PROGRAM, "PERFORM READPREV-TRANSACT-FILE");
        boolean increments = CobolSourceEvidence
                .containsStatement(ONLINE_PROGRAM, "ADD 1 TO WS-TRAN-ID-NUM");
        boolean browseHoldsUpdateIntent = ALLOCATION_BROWSE_PARAGRAPHS.stream()
                .anyMatch(label -> CobolSourceEvidence
                        .paragraphContains(ONLINE_PROGRAM, label, UPDATE_INTENT));
        boolean enqueued = CobolSourceEvidence.containsStatement(ONLINE_PROGRAM, ENQUEUE_COMMAND);
        return seeds && readsPrevious && increments && !browseHoldsUpdateIntent && !enqueued;
    }

    /** Reports whether the migrated allocation really draws from a database sequence. */
    private static boolean theTargetAllocatesFromASequence() {
        Path source = repositoryRoot().resolve(TRANSACTION_IDENTIFIER_SOURCE);
        try {
            return Files.readString(source, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .contains(SEQUENCE_FUNCTION.toLowerCase(Locale.ROOT));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + source, unreadable);
        }
    }

    /** Answers the separator the online timestamp shape carries at one one-based position. */
    private static char onlineSeparatorAt(int position) {
        return ONLINE_TIMESTAMP_SHAPE.charAt(position - 1);
    }

    /** Reports whether the feed's origin timestamp carries the online shape's separators. */
    private static boolean theFeedTimestampCarriesTheOnlineShape() {
        String moment = FEED_RECORD.originTimestamp();
        if (moment.length() < ONLINE_TIMESTAMP_SHAPE.length()) {
            return false;
        }
        for (int index = 0; index < ONLINE_TIMESTAMP_SHAPE.length(); index++) {
            char shape = ONLINE_TIMESTAMP_SHAPE.charAt(index);
            if (Character.isLetterOrDigit(shape)) {
                continue;
            }
            if (moment.charAt(index) != shape) {
                return false;
            }
        }
        return true;
    }

    /** Answers the smallest or largest seeded balance. */
    private static BigDecimal extremeSeededBalance(boolean smallest) {
        Comparator<BigDecimal> order = Comparator.naturalOrder();
        return ACCOUNTS.stream()
                .map(CopybookRecordParser.AccountRecord::currentBalance)
                .reduce((left, right) -> (smallest ? order.compare(left, right) <= 0
                        : order.compare(left, right) >= 0) ? left : right)
                .orElseThrow(() -> new IllegalStateException("the fixture carries no account"));
    }

    /** Answers the balance every account holds after its payment, refusing any disagreement. */
    private static String everyBalanceAfterPayment() {
        String only = null;
        for (CopybookRecordParser.AccountRecord account : ACCOUNTS) {
            String after = CobolDecimal.subtract(account.currentBalance(),
                    account.currentBalance(), PicClause.ACCT_CURR_BAL_SCALE).toPlainString();
            if (only == null) {
                only = after;
            } else if (!only.equals(after)) {
                throw new IllegalStateException("account " + account.accountId()
                        + " closes at " + after + " while an earlier one closed at " + only);
            }
        }
        return only;
    }

    /** Answers the card number the cross-reference gives one account. */
    private static String cardNumberOf(String accountId) {
        for (CopybookRecordParser.CardCrossReferenceRecord crossReference
                : CardDemoFixtureLoader.loadCardCrossReferences()) {
            if (crossReference.accountId().equals(accountId)) {
                return crossReference.cardNumber();
            }
        }
        throw new IllegalStateException(
                "app/data/ASCII/cardxref.txt names no card for account " + accountId);
    }

    /** Answers one expected resource's name, having confirmed it is really on the classpath. */
    private static String expectedResourceOnTheClasspath(String fileName) {
        if (BillPaymentEquivalenceTest.class
                .getResource(ExpectedOutcomes.RESOURCE_DIRECTORY + fileName) == null) {
            throw new IllegalStateException(fileName + " is not on the test classpath");
        }
        return fileName;
    }

    /** Writes a boolean the way {@link #EXPECTED_PAYMENT_FILE} writes one. */
    private static String paymentYesOrNo(boolean value) {
        return value ? ExpectedOutcomes.YES : ExpectedOutcomes.NO;
    }

    /** Resolves the repository root from the fixture directory the loader reports. */
    private static Path repositoryRoot() {
        return CardDemoFixtureLoader.fixtureDirectory().getParent().getParent().getParent();
    }
}
