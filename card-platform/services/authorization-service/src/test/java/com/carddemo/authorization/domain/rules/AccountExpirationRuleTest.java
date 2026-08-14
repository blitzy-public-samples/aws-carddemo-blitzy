package com.carddemo.authorization.domain.rules;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.DeclineRule;
import com.carddemo.authorization.entity.AccountCreditSnapshotEntity;
import com.carddemo.events.DeclineReason;
import jakarta.persistence.Column;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.annotation.Order;

/**
 * Unit tests for {@link AccountExpirationRule}, which assigns reject code {@code 0103}.
 *
 * <p>Subject line {@code app/cbl/CBTRN02C.cbl:L414}, inside the {@code NOT INVALID KEY} limb of the
 * account read. The assignment sits at {@code app/cbl/CBTRN02C.cbl:L417-L419}. Both are quoted
 * below.
 *
 * <pre>{@code
 *                IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *                  CONTINUE
 *                ELSE
 *                  MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *                  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *                    TO WS-VALIDATION-FAIL-REASON-DESC
 *                END-IF
 * }</pre>
 *
 * <p>Both operands are text and stay text. {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 * {@code app/cpy/CVACT01Y.cpy:L11} holds ten characters, and {@code (1:10)} takes the leading ten
 * of {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:L16}. Each value runs year,
 * then month, then day, so sorting the characters sorts the dates.
 *
 * <p>The approving condition is tested and {@code CONTINUE} sits on its limb, so the reject reason
 * is assigned on the {@code ELSE}. An expiry date equal to the capture date answers with nothing.
 *
 * <p>Scenarios here are constructed. Reject code {@code 0103} is reached by 0 of the 300 records of
 * {@code app/data/ASCII/dailytran.txt}. All 300 carry the one capture date {@code 2022-06-10},
 * while the fifty accounts of {@code app/data/ASCII/acctdata.txt} expire between
 * {@code 2023-01-06} and {@code 2025-12-28}. Account 7 supplies the approving values, sliced at the
 * copybook offsets, and no fixture is edited to close that gap.
 *
 * <p>Snapshot rows are stubs. Their own column contract is asserted under
 * {@code src/test/java/com/carddemo/authorization/entity}.
 *
 * <p>Five tests a reader may expect are absent from the source, and so from here.
 *
 * <ul>
 *   <li>No card expiry test. {@code CARD-EXPIRAION-DATE PIC X(10)} at
 *       {@code app/cpy/CVACT02Y.cpy:L9} sits on another record, carrying the same transposed word.
 *       {@code app/cbl/CBTRN02C.cbl:L28-L61} declares six files without a card file, and
 *       {@code app/jcl/POSTTRAN.jcl} allocates none.</li>
 *   <li>No account status test. {@code ACCT-ACTIVE-STATUS PIC X(01)} at
 *       {@code app/cpy/CVACT01Y.cpy:L6} holds no column on the row, and no paragraph reads it
 *       before posting. All fifty fixture accounts carry {@code Y}.</li>
 *   <li>No open or reissue date test. {@code ACCT-OPEN-DATE} at {@code app/cpy/CVACT01Y.cpy:L10}
 *       and {@code ACCT-REISSUE-DATE} at {@code app/cpy/CVACT01Y.cpy:L12} hold no column
 *       either.</li>
 *   <li>No monetary test. {@link CreditLimitRule} owns the limit comparison, and this rule
 *       computes nothing.</li>
 *   <li>No card number format test. This rule reads no card number.</li>
 * </ul>
 *
 * <p>The source spells the account field with a transposed word, and the target spells it
 * {@code accountExpirationDate}. That rename is recorded in
 * {@code card-platform/docs/traceability-matrix.md}.
 */
final class AccountExpirationRuleTest {

    /**
     * The card number record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 263-278. Row 21 of {@code app/data/ASCII/cardxref.txt} resolves it to account
     * {@code 00000000007}. This rule reads no card number.
     */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /**
     * The capture timestamp record one of {@code app/data/ASCII/dailytran.txt} carries at positions
     * 279-304, held as the text {@code DALYTRAN-ORIG-TS PIC X(26)} declares at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. All 300 records carry this one value.
     */
    private static final String FIXTURE_ORIGIN_TIMESTAMP = "2022-06-10 19:27:53.000000";

    /**
     * The leading ten characters of {@link #FIXTURE_ORIGIN_TIMESTAMP}, which are the characters
     * {@code (1:10)} at {@code app/cbl/CBTRN02C.cbl:L414} takes.
     */
    private static final String FIXTURE_CAPTURE_DATE = "2022-06-10";

    /**
     * The amount record one of {@code app/data/ASCII/dailytran.txt} carries at positions 133-143.
     * The bytes read {@code 0000005047G}, whose trailing overpunch marks a positive 7, giving
     * {@code +504.77} under {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}. This rule reads no amount.
     */
    private static final BigDecimal FIXTURE_AMOUNT = new BigDecimal("504.77");

    /**
     * The expiry date account 7 of {@code app/data/ASCII/acctdata.txt} carries at bytes 59-68,
     * under {@code ACCT-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT01Y.cpy:L11}.
     */
    private static final String ACCOUNT_7_EXPIRY_DATE = "2024-12-13";

    /**
     * The expiry date one day below {@link #FIXTURE_CAPTURE_DATE}. No fixture account carries it,
     * so the declining scenarios build it.
     */
    private static final String EXPIRY_DAY_BEFORE_CAPTURE = "2022-06-09";

    /**
     * The expiry date differing from {@link #FIXTURE_CAPTURE_DATE} at one position only, which is
     * the day position. Lowering that one character is enough to reach reject code {@code 0103}.
     */
    private static final String EXPIRY_ONE_CHARACTER_BELOW = "2022-06-00";

    /** The earliest expiry date among the fifty accounts of {@code app/data/ASCII/acctdata.txt}. */
    private static final String EARLIEST_FIXTURE_EXPIRY_DATE = "2023-01-06";

    /** The newest expiry date among those same fifty accounts. */
    private static final String NEWEST_FIXTURE_EXPIRY_DATE = "2025-12-28";

    /** Ten characters naming no real day, sorting above {@link #FIXTURE_CAPTURE_DATE}. */
    private static final String HIGH_IMPOSSIBLE_DATE = "9999-99-99";

    /** Ten characters naming no real day, sorting below {@link #FIXTURE_CAPTURE_DATE}. */
    private static final String LOW_IMPOSSIBLE_DATE = "0000-00-00";

    /**
     * An expiry date whose tenth character is a lower-case letter. Against
     * {@link #UPPER_CASE_LETTER_TIMESTAMP} it sorts above, and folding both operands to one case
     * would reverse that ordering.
     */
    private static final String LOWER_CASE_LETTER_DATE = "2022-06-1a";

    /**
     * A capture timestamp of twenty-six characters whose tenth character is an upper-case letter,
     * held as text.
     */
    private static final String UPPER_CASE_LETTER_TIMESTAMP = "2022-06-1Z 19:27:53.000000";

    /**
     * A capture timestamp of twenty-six characters naming no real day. Its leading ten sort
     * above every expiry date the fixture carries.
     */
    private static final String HIGH_IMPOSSIBLE_TIMESTAMP = "9999-99-99 99:99:99.999999";

    /**
     * Characters {@code ACCT-EXPIRAION-DATE PIC X(10)} holds at {@code app/cpy/CVACT01Y.cpy:L11},
     * which are also the characters {@code (1:10)} takes from the capture timestamp.
     */
    private static final int EXPIRY_DATE_WIDTH = 10;

    /** Characters {@code DALYTRAN-ORIG-TS PIC X(26)} holds at {@code app/cpy/CVTRA06Y.cpy:L16}. */
    private static final int ORIGIN_TIMESTAMP_WIDTH = 26;

    /** The one-based position of the separator the comparison leaves out. */
    private static final int SEPARATOR_POSITION = 11;

    /** The column the expiry date sits in, which holds text and not a converted value. */
    private static final String EXPIRY_DATE_COLUMN = "account_expiration_date";

    /**
     * Characters {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L181}.
     */
    private static final int REASON_CODE_WIDTH = 4;

    /** Characters the reject reason text of this rule holds. */
    private static final int REASON_TEXT_LENGTH = 42;

    /**
     * Characters {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} holds at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    private static final int REASON_TEXT_WIDTH = 76;

    /** Words the reject reason text of this rule holds. */
    private static final int REASON_TEXT_WORDS = 5;

    /** The abbreviation the source text carries, which no target copy expands. */
    private static final String SOURCE_ABBREVIATION = "ACCT";

    /** The expansion the source text does not carry. */
    private static final String REJECTED_EXPANSION = "ACCOUNT";

    /** The position this rule declares in the chain. */
    private static final int CHAIN_POSITION = 40;

    /** The step between declared chain positions, which leaves room between any two of them. */
    private static final int CHAIN_POSITION_STRIDE = 10;

    /** Records of {@code app/data/ASCII/dailytran.txt} that reach this reject reason. */
    private static final int RECORDS_REACHING_THIS_REASON = 0;

    /** The rule under test, which takes no collaborator. */
    private AccountExpirationRule rule;

    @BeforeEach
    void prepareRule() {
        rule = new AccountExpirationRule();
    }

    /** The comparison at {@code app/cbl/CBTRN02C.cbl:L414}. */
    @Nested
    @DisplayName("The expiry comparison")
    class ExpiryComparison {

        @Test
        @DisplayName("answers with nothing when the expiry date equals the capture date")
        void answersWithNothingAtTheCaptureDate() {
            Optional<DeclineReason> outcome = outcomeFor(FIXTURE_CAPTURE_DATE);

            assertEquals(Optional.empty(), outcome,
                    "app/cbl/CBTRN02C.cbl:L414 tests ACCT-EXPIRAION-DATE >= the capture date, and"
                            + " app/cbl/CBTRN02C.cbl:L415 puts CONTINUE on that limb");
        }

        @Test
        @DisplayName("answers with reject code 0103 one day below the capture date")
        void answersWithTheRejectReasonOneDayBelow() {
            Optional<DeclineReason> outcome = outcomeFor(EXPIRY_DAY_BEFORE_CAPTURE);

            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome,
                    "app/cbl/CBTRN02C.cbl:L417 assigns 103 on the ELSE of"
                            + " app/cbl/CBTRN02C.cbl:L416");
        }

        @Test
        @DisplayName("answers with nothing for account 7 against record one of the daily feed")
        void answersWithNothingForAccountSeven() {
            Optional<DeclineReason> outcome = outcomeFor(ACCOUNT_7_EXPIRY_DATE);

            assertEquals(Optional.empty(), outcome,
                    "account 7 of app/data/ASCII/acctdata.txt expires at bytes 59-68 well above the"
                            + " capture date every record of app/data/ASCII/dailytran.txt carries");
        }

        @ParameterizedTest
        @ValueSource(strings = {LOW_IMPOSSIBLE_DATE, "1999-12-31", "2022-05-31", "2022-06-08",
                EXPIRY_DAY_BEFORE_CAPTURE})
        @DisplayName("answers with reject code 0103 for every expiry date sorting below")
        void answersWithTheRejectReasonBelowTheCaptureDate(String expiryDate) {
            assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcomeFor(expiryDate),
                    "app/cbl/CBTRN02C.cbl:L414 approves only at or above the capture date");
        }

        @ParameterizedTest
        @ValueSource(strings = {FIXTURE_CAPTURE_DATE, "2022-06-11", EARLIEST_FIXTURE_EXPIRY_DATE,
                ACCOUNT_7_EXPIRY_DATE, NEWEST_FIXTURE_EXPIRY_DATE, HIGH_IMPOSSIBLE_DATE})
        @DisplayName("answers with nothing for every expiry date reaching the capture date")
        void answersWithNothingAtOrAboveTheCaptureDate(String expiryDate) {
            assertEquals(Optional.empty(), outcomeFor(expiryDate),
                    "app/cbl/CBTRN02C.cbl:L414 uses >= and not >, so equality approves");
        }

        @Test
        @DisplayName("turns on the day characters, one position at a time")
        void turnsOnTheDayCharacters() {
            Optional<DeclineReason> atTheCaptureDate = outcomeFor(FIXTURE_CAPTURE_DATE);
            Optional<DeclineReason> oneDayBelow = outcomeFor(EXPIRY_DAY_BEFORE_CAPTURE);
            Optional<DeclineReason> oneCharacterBelow = outcomeFor(EXPIRY_ONE_CHARACTER_BELOW);

            assertAll(
                    () -> assertEquals(Optional.empty(), atTheCaptureDate,
                            "app/cbl/CBTRN02C.cbl:L415 approves the equal pair"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), oneDayBelow,
                            "app/cbl/CBTRN02C.cbl:L417 assigns 103 to the preceding day"),
                    () -> assertEquals(2, differingPositions(FIXTURE_CAPTURE_DATE,
                                    EXPIRY_DAY_BEFORE_CAPTURE),
                            "the preceding day moves both day characters, from 10 to 09"),
                    () -> assertEquals(1, differingPositions(FIXTURE_CAPTURE_DATE,
                                    EXPIRY_ONE_CHARACTER_BELOW),
                            "lowering the tens character alone gives a value one position apart"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                            oneCharacterBelow,
                            "one character is enough to reach reject code 0103, which is the"
                                    + " character comparison of app/cbl/CBTRN02C.cbl:L414 at work"),
                    () -> assertNotEquals(atTheCaptureDate, oneCharacterBelow,
                            "that single character decides the outcome"));
        }

        @Test
        @DisplayName("answers for every expiry date the fixture carries, and declines none of them")
        void declinesNoFixtureExpiryDate() {
            Optional<DeclineReason> earliest = outcomeFor(EARLIEST_FIXTURE_EXPIRY_DATE);
            Optional<DeclineReason> newest = outcomeFor(NEWEST_FIXTURE_EXPIRY_DATE);

            assertAll(
                    () -> assertEquals(Optional.empty(), earliest,
                            "the earliest of the fifty fixture expiry dates still sits above the"
                                    + " capture date"),
                    () -> assertEquals(Optional.empty(), newest,
                            "so does the newest"),
                    () -> assertEquals(RECORDS_REACHING_THIS_REASON,
                            countDeclining(EARLIEST_FIXTURE_EXPIRY_DATE,
                                    NEWEST_FIXTURE_EXPIRY_DATE, ACCOUNT_7_EXPIRY_DATE),
                            "no pairing of a fixture account with a fixture capture date reaches"
                                    + " reject code 0103, which is why every declining scenario"
                                    + " here is built"));
        }
    }

    /**
     * The comparison reads characters. {@code app/cbl/CBTRN02C.cbl:L414} compares two alphanumeric
     * fields, and {@link String#compareTo(String)} reproduces that ordering.
     *
     * <p>A comparison built on a date type would refuse the values below. Each one holds ten
     * characters and names no day of any year, so an answer proves no parsing took place.
     */
    @Nested
    @DisplayName("A text comparison")
    class TextComparison {

        @Test
        @DisplayName("answers for an expiry date naming no real day")
        void answersForAnExpiryDateNamingNoRealDay() {
            Optional<DeclineReason> above =
                    assertDoesNotThrow(() -> outcomeFor(HIGH_IMPOSSIBLE_DATE),
                            "app/cbl/CBTRN02C.cbl:L414 reads characters, so a month of 99 raises"
                                    + " nothing");
            Optional<DeclineReason> below =
                    assertDoesNotThrow(() -> outcomeFor(LOW_IMPOSSIBLE_DATE),
                            "a month of 00 raises nothing either");

            assertAll(
                    () -> assertEquals(Optional.empty(), above,
                            "9999-99-99 sorts above the capture date"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), below,
                            "0000-00-00 sorts below it"));
        }

        @Test
        @DisplayName("answers for a capture timestamp naming no real day")
        void answersForACaptureTimestampNamingNoRealDay() {
            Optional<DeclineReason> outcome = assertDoesNotThrow(
                    () -> outcomeFor(ACCOUNT_7_EXPIRY_DATE, HIGH_IMPOSSIBLE_TIMESTAMP),
                    "the second operand of app/cbl/CBTRN02C.cbl:L414 is read as characters too");

            assertAll(
                    () -> assertEquals(ORIGIN_TIMESTAMP_WIDTH, HIGH_IMPOSSIBLE_TIMESTAMP.length(),
                            "DALYTRAN-ORIG-TS PIC X(26) at app/cpy/CVTRA06Y.cpy:L16 holds"
                                    + " twenty-six characters"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome,
                            "the expiry date of account 7 sorts below 9999-99-99"));
        }

        @Test
        @DisplayName("orders by character and not by month and day")
        void ordersByCharacter() {
            assertAll(
                    () -> assertTrue(HIGH_IMPOSSIBLE_DATE.compareTo(FIXTURE_CAPTURE_DATE) > 0,
                            "the character ordering places 9999-99-99 above the capture date"),
                    () -> assertTrue(LOW_IMPOSSIBLE_DATE.compareTo(FIXTURE_CAPTURE_DATE) < 0,
                            "and places 0000-00-00 below it"),
                    () -> assertEquals(Optional.empty(), outcomeFor(HIGH_IMPOSSIBLE_DATE),
                            "the rule follows the character ordering upward"),
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED),
                            outcomeFor(LOW_IMPOSSIBLE_DATE),
                            "and downward"));
        }

        @Test
        @DisplayName("reads the ten characters as they arrive, with no fold and no padding")
        void readsTheCharactersAsTheyArrive() {
            AccountCreditSnapshotEntity row = snapshotExpiring(ACCOUNT_7_EXPIRY_DATE);

            rule.evaluate(contextCarrying(FIXTURE_ORIGIN_TIMESTAMP, row));

            assertAll(
                    () -> verify(row).getAccountExpirationDate(),
                    () -> verifyNoMoreInteractions(row),
                    () -> assertEquals(EXPIRY_DATE_WIDTH, ACCOUNT_7_EXPIRY_DATE.length(),
                            "the stored value already holds the width PIC X(10) declares, so"
                                    + " nothing is padded"));
        }

        @Test
        @DisplayName("folds neither operand to one case")
        void foldsNeitherOperand() {
            Optional<DeclineReason> outcome =
                    outcomeFor(LOWER_CASE_LETTER_DATE, UPPER_CASE_LETTER_TIMESTAMP);
            String capturedDate =
                    UPPER_CASE_LETTER_TIMESTAMP.substring(0, EXPIRY_DATE_WIDTH);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "the lower-case letter sorts above the upper-case one, so"
                                    + " app/cbl/CBTRN02C.cbl:L414 approves this pair. Folding both"
                                    + " operands to one case reverses that ordering and assigns"
                                    + " reject code 0103 to the same pair"),
                    () -> assertTrue(LOWER_CASE_LETTER_DATE.compareTo(capturedDate) > 0,
                            "the character ordering places the pair that way"));
        }

        @Test
        @DisplayName("names no date or time type on the rule surface")
        void namesNoDateOrTimeType() {
            Set<String> named = typesTheRuleNames();

            assertAll(
                    () -> assertEquals(Set.of("java.util.Optional",
                                    "com.carddemo.authorization.domain.DeclineRule$Context",
                                    "com.carddemo.authorization.domain.DeclineRule$Segment"),
                            named,
                            "app/cbl/CBTRN02C.cbl:L414 compares text, so the rule names the"
                                    + " context, the segment and an optional answer and nothing"
                                    + " else"),
                    () -> assertTrue(named.stream().noneMatch(name -> name.startsWith("java.time")),
                            "no value of the time packages reaches this rule"),
                    () -> assertTrue(named.stream().noneMatch(name -> name.startsWith("java.sql")),
                            "and no value of the database packages either"));
        }

        @Test
        @DisplayName("reads a column of ten characters of text")
        void readsAColumnOfText() throws NoSuchFieldException, NoSuchMethodException {
            Field stored =
                    AccountCreditSnapshotEntity.class.getDeclaredField("accountExpirationDate");
            Column column = stored.getAnnotation(Column.class);
            Method accessor =
                    AccountCreditSnapshotEntity.class.getMethod("getAccountExpirationDate");

            assertAll(
                    () -> assertEquals(String.class, stored.getType(),
                            "a date column would force a conversion, and"
                                    + " app/cbl/CBTRN02C.cbl:L414 performs none"),
                    () -> assertEquals(String.class, accessor.getReturnType(),
                            "the accessor hands the rule the same ten characters"),
                    () -> assertNotNull(column, "the field carries its column mapping"),
                    () -> assertEquals(EXPIRY_DATE_COLUMN, column.name(),
                            "the column name corrects the transposed word of"
                                    + " app/cpy/CVACT01Y.cpy:L11"),
                    () -> assertEquals(EXPIRY_DATE_WIDTH, column.length(),
                            "PIC X(10) at app/cpy/CVACT01Y.cpy:L11 fixes the width"));
        }
    }

    /**
     * The reference modification {@code (1:10)} at {@code app/cbl/CBTRN02C.cbl:L414}, which takes
     * an offset of one and a length of ten.
     */
    @Nested
    @DisplayName("The ten-character slice")
    class TenCharacterSlice {

        @Test
        @DisplayName("leaves the separator at position eleven out of the comparison")
        void leavesTheSeparatorOut() {
            String slice = FIXTURE_ORIGIN_TIMESTAMP.substring(0, EXPIRY_DATE_WIDTH);

            assertAll(
                    () -> assertEquals(FIXTURE_CAPTURE_DATE, slice,
                            "the leading ten characters are the capture date"),
                    () -> assertEquals(' ',
                            FIXTURE_ORIGIN_TIMESTAMP.charAt(SEPARATOR_POSITION - 1),
                            "position eleven holds a space in all 300 records of"
                                    + " app/data/ASCII/dailytran.txt"),
                    () -> assertEquals(EXPIRY_DATE_WIDTH, slice.length(),
                            "the length is ten and the separator sits past it"));
        }

        @Test
        @DisplayName("stops at ten, where an eleventh character would change the equal pair")
        void stopsAtTen() {
            Optional<DeclineReason> outcome = outcomeFor(FIXTURE_CAPTURE_DATE);
            String elevenCharacterSlice =
                    FIXTURE_ORIGIN_TIMESTAMP.substring(0, SEPARATOR_POSITION);

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L414 compares ten characters against ten, and"
                                    + " they are equal here"),
                    () -> assertTrue(FIXTURE_CAPTURE_DATE.compareTo(elevenCharacterSlice) < 0,
                            "a slice of eleven carries the trailing space, which sorts the stored"
                                    + " ten characters below it and would assign reject code"
                                    + " 0103 to this same pair"));
        }

        @Test
        @DisplayName("raises when the capture timestamp is too short to slice")
        void raisesOnAShortCaptureTimestamp() {
            String tooShort = FIXTURE_CAPTURE_DATE.substring(0, EXPIRY_DATE_WIDTH - 1);

            IndexOutOfBoundsException raised = assertThrows(IndexOutOfBoundsException.class,
                    () -> outcomeFor(ACCOUNT_7_EXPIRY_DATE, tooShort),
                    "a capture timestamp narrower than ten characters is a caller defect, and the"
                            + " request contract already refuses it");

            assertNotNull(raised, "the failure reaches the caller unaltered");
        }

        @Test
        @DisplayName("turns a short capture timestamp into no reject reason at all")
        void doesNotConvertAShortCaptureTimestampIntoAReason() {
            String tooShort = FIXTURE_CAPTURE_DATE.substring(0, EXPIRY_DATE_WIDTH - 1);
            AtomicReference<Optional<DeclineReason>> answer = new AtomicReference<>(null);

            assertThrows(IndexOutOfBoundsException.class,
                    () -> answer.set(outcomeFor(ACCOUNT_7_EXPIRY_DATE, tooShort)),
                    "no width-tolerant fallback stands between the slice and the caller");

            assertNull(answer.get(),
                    "the call produced no answer, so a narrow timestamp reaches neither an empty"
                            + " result nor reject code 0103");
        }
    }

    /** The reject reason of {@code app/cbl/CBTRN02C.cbl:L417-L419}. */
    @Nested
    @DisplayName("The reject reason")
    class RejectReason {

        @Test
        @DisplayName("carries the zero-padded four-character code 0103")
        void carriesTheWireCode() {
            DeclineReason answer = rejectReasonBelowTheCaptureDate();

            assertAll(
                    () -> assertEquals("0103", answer.code(),
                            "WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:L181"
                                    + " zero-pads the 103 that app/cbl/CBTRN02C.cbl:L417 moves"),
                    () -> assertEquals(REASON_CODE_WIDTH, answer.code().length(),
                            "the field holds four characters"),
                    () -> assertEquals(103, answer.numericCode(),
                            "app/cbl/CBTRN02C.cbl:L417 moves 103"));
        }

        @Test
        @DisplayName("carries the text of app/cbl/CBTRN02C.cbl:L418 unedited")
        void carriesTheSourceText() {
            String text = rejectReasonBelowTheCaptureDate().description();

            assertAll(
                    () -> assertEquals(REASON_TEXT_LENGTH, text.length(),
                            "the literal at app/cbl/CBTRN02C.cbl:L418 holds forty-two characters"),
                    () -> assertTrue(text.length() <= REASON_TEXT_WIDTH,
                            "WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at"
                                    + " app/cbl/CBTRN02C.cbl:L182 holds seventy-six"),
                    () -> assertEquals(REASON_TEXT_WORDS, text.split(" ").length,
                            "the source literal is five words separated by one space each"),
                    () -> assertTrue(text.contains(SOURCE_ABBREVIATION),
                            "app/cbl/CBTRN02C.cbl:L418 abbreviates the fourth word"),
                    () -> assertEquals(-1, text.indexOf(REJECTED_EXPANSION),
                            "the abbreviation stays abbreviated, and an expansion would fail"
                                    + " schema of the declined event against"
                                    + " transaction-declined-v1.json"),
                    () -> assertEquals(-1, text.indexOf('-'),
                            "the source literal carries no hyphen"));
        }

        @Test
        @DisplayName("reads its text from the shared contract and restates none of it")
        void readsItsTextFromTheSharedContract() {
            DeclineReason answer = rejectReasonBelowTheCaptureDate();

            assertAll(
                    () -> assertEquals(DeclineReason.ACCOUNT_EXPIRED, answer,
                            "the rule answers with the constant carrying code 0103"),
                    () -> assertEquals(DeclineReason.ACCOUNT_EXPIRED.description(),
                            answer.description(),
                            "the text comes from the shared contract, so this package holds no"
                                    + " second copy of it"),
                    () -> assertTrue(answer.resolvesAccount(),
                            "app/cbl/CBTRN02C.cbl:L394 keys the account read before"
                                    + " app/cbl/CBTRN02C.cbl:L414 runs, so an account identifier"
                                    + " is in hand"));
        }
    }

    /** The shape of the rule, which carries no state and no collaborator. */
    @Nested
    @DisplayName("The rule surface")
    class RuleSurface {

        @Test
        @DisplayName("exposes evaluate and segment, and neither answers with a flag")
        void exposesTwoMethods() throws NoSuchMethodException {
            Set<String> exposed = new TreeSet<>();
            for (Method method : AccountExpirationRule.class.getDeclaredMethods()) {
                if (method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                exposed.add(method.getName());
                assertNotEquals(boolean.class, method.getReturnType(),
                        "app/cbl/CBTRN02C.cbl:L414 tests the approving condition and"
                                + " app/cbl/CBTRN02C.cbl:L417 assigns on the ELSE, so the answer"
                                + " must be a reject reason and never a flag");
                assertNotEquals(Boolean.class, method.getReturnType(),
                        "the answer must be a reject reason and never a flag");
            }

            assertEquals(Set.of("evaluate", "segment"), exposed,
                    "the public surface must be the two methods DeclineRule declares, so no"
                            + " differently named entry point can grow beside them");

            Method evaluation =
                    AccountExpirationRule.class.getMethod("evaluate", DeclineRule.Context.class);
            assertEquals("java.util.Optional<com.carddemo.events.DeclineReason>",
                    evaluation.getGenericReturnType().getTypeName(),
                    "an absent answer is an approval and a present answer is the reject reason");
            assertEquals(0, evaluation.getDeclaredAnnotations().length,
                    "the evaluation method declares no runtime annotation, so no transaction"
                            + " boundary sits here");
        }

        @Test
        @DisplayName("takes no collaborator through one unannotated constructor")
        void takesNoCollaborator() {
            Constructor<?>[] constructors = AccountExpirationRule.class.getConstructors();
            Set<String> fields = new TreeSet<>();
            for (Field field : AccountExpirationRule.class.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    fields.add(field.getName());
                }
            }

            assertAll(
                    () -> assertEquals(1, constructors.length,
                            "the rule must offer one way to build it"),
                    () -> assertArrayEquals(new Class<?>[] {}, constructors[0].getParameterTypes(),
                            "app/cbl/CBTRN02C.cbl:L414 opens no dataset and reads no wall clock, so"
                                    + " no repository, collaborator, port or clock reaches this"
                                    + " rule"),
                    () -> assertEquals(0, constructors[0].getAnnotations().length,
                            "a sole constructor needs no injection annotation"),
                    () -> assertEquals(Set.of(), fields,
                            "a rule that reads both operands off the context holds no state"),
                    () -> assertNotNull(assertDoesNotThrow(AccountExpirationRule::new),
                            "building the rule takes no argument"));
        }

        @Test
        @DisplayName("declares only the component and position annotations")
        void declaresTwoAnnotations() {
            Set<String> declared = new TreeSet<>();
            for (Annotation annotation : AccountExpirationRule.class.getDeclaredAnnotations()) {
                declared.add(annotation.annotationType().getSimpleName());
            }

            assertEquals(Set.of("Component", "Order"), declared,
                    "app/cbl/CBTRN02C.cbl:L414-L420 writes nothing, so the rule declares no"
                            + " transaction boundary of its own");
        }

        @Test
        @DisplayName("sits at position 40 with room on either side")
        void sitsAtPositionForty() {
            Order declared = AccountExpirationRule.class.getAnnotation(Order.class);

            assertAll(
                    () -> assertNotNull(declared, "the rule must declare its chain position"),
                    () -> assertEquals(CHAIN_POSITION, declared.value(),
                            "the chain at app/cbl/CBTRN02C.cbl:L370-L378 reaches this test fourth"),
                    () -> assertEquals(0, declared.value() % CHAIN_POSITION_STRIDE,
                            "positions advance in tens, so a further rule slots between any two of"
                                    + " them without renumbering one"));
        }

        @Test
        @DisplayName("declares that a later rule of its segment can overwrite its answer")
        void declaresTheLastDeclineWinsSegment() {
            assertEquals(DeclineRule.Segment.LAST_DECLINE_WINS, rule.segment(),
                    "app/cbl/CBTRN02C.cbl:L413 closes the preceding test and"
                            + " app/cbl/CBTRN02C.cbl:L414 opens this one with no gate between"
                            + " them");
        }
    }

    /** A context reaching the rule before the account read seated its row. */
    @Nested
    @DisplayName("A missing snapshot")
    class MissingSnapshot {

        @Test
        @DisplayName("raises when no snapshot reached the call")
        void raisesWhenNoSnapshotReachedTheCall() {
            DeclineRule.Context context = new DeclineRule.Context(FIXTURE_CARD_NUMBER,
                    FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);

            NullPointerException raised = assertThrows(NullPointerException.class,
                    () -> rule.evaluate(context),
                    "app/cbl/CBTRN02C.cbl:L414 sits inside the NOT INVALID KEY limb of the account"
                            + " read, so no snapshot means the chain ran out of order");

            assertAll(
                    () -> assertNotNull(raised.getMessage(),
                            "the failure names what was missing"),
                    () -> assertTrue(raised.getMessage().contains("account snapshot"),
                            "the failure names the account snapshot"),
                    () -> assertNull(context.getAccountCreditSnapshot(),
                            "the rule seats nothing of its own"));
        }

        @Test
        @DisplayName("answers with neither an approval nor reject code 0103")
        void answersWithNeitherOutcome() {
            DeclineRule.Context context = new DeclineRule.Context(FIXTURE_CARD_NUMBER,
                    FIXTURE_AMOUNT, FIXTURE_ORIGIN_TIMESTAMP);
            AtomicReference<Optional<DeclineReason>> answer = new AtomicReference<>(null);

            assertThrows(NullPointerException.class,
                    () -> answer.set(rule.evaluate(context)),
                    "app/cbl/CBTRN02C.cbl:L417 is the one place this rule assigns a reject reason,"
                            + " and a missing snapshot never reaches it");

            assertNull(answer.get(),
                    "the call produced no answer at all, so an unresolved account never looks like"
                            + " an expired one");
        }
    }

    /** Behaviour a reader may expect that {@code app/cbl/CBTRN02C.cbl} does not have. */
    @Nested
    @DisplayName("Deliberate non-additions")
    class DeliberateNonAdditions {

        @Test
        @DisplayName("the credit limit and both cycle accumulators are never read")
        void leavesTheMonetaryColumnsUnread() {
            AccountCreditSnapshotEntity row = snapshotExpiring(ACCOUNT_7_EXPIRY_DATE);

            Optional<DeclineReason> outcome =
                    rule.evaluate(contextCarrying(FIXTURE_ORIGIN_TIMESTAMP, row));

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "app/cbl/CBTRN02C.cbl:L414 names no monetary field"),
                    () -> verify(row, never()).getCreditLimit(),
                    () -> verify(row, never()).getCurrentCycleCredit(),
                    () -> verify(row, never()).getCurrentCycleDebit());
        }

        @Test
        @DisplayName("the expiry date is the one value read off the row")
        void readsOneValueOffTheRow() {
            AccountCreditSnapshotEntity row = snapshotExpiring(EXPIRY_DAY_BEFORE_CAPTURE);

            Optional<DeclineReason> outcome =
                    rule.evaluate(contextCarrying(FIXTURE_ORIGIN_TIMESTAMP, row));

            assertAll(
                    () -> assertEquals(Optional.of(DeclineReason.ACCOUNT_EXPIRED), outcome,
                            "app/cbl/CBTRN02C.cbl:L417 assigns the reject reason"),
                    () -> verify(row).getAccountExpirationDate(),
                    () -> verifyNoMoreInteractions(row));
        }

        @Test
        @DisplayName("an expiry date above the capture date answers with nothing, whatever the"
                + " status")
        void answersWithNothingWhateverTheStatus() {
            Optional<DeclineReason> outcome = outcomeFor(ACCOUNT_7_EXPIRY_DATE);
            Set<String> exposed = rowAccessorNames();

            assertAll(
                    () -> assertEquals(Optional.empty(), outcome,
                            "ACCT-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT01Y.cpy:L6 is read by no"
                                    + " paragraph before posting, so a closed account still"
                                    + " authorizes"),
                    () -> assertFalse(exposed.contains("getAccountActiveStatus"),
                            "the row carries no status column to read"));
        }

        @Test
        @DisplayName("the card record is out of reach of this rule")
        void leavesTheCardRecordOut() {
            Set<String> exposed = rowAccessorNames();

            assertAll(
                    () -> assertEquals(Optional.empty(), outcomeFor(ACCOUNT_7_EXPIRY_DATE),
                            "app/cbl/CBTRN02C.cbl:L414 names no card field"),
                    () -> assertFalse(exposed.contains("getCardExpirationDate"),
                            "CARD-EXPIRAION-DATE at app/cpy/CVACT02Y.cpy:L9 sits on another"
                                    + " record, and app/cbl/CBTRN02C.cbl:L28-L61 declares six files"
                                    + " without a card file"),
                    () -> assertFalse(exposed.contains("getCardActiveStatus"),
                            "CARD-ACTIVE-STATUS at app/cpy/CVACT02Y.cpy:L10 is out of reach too,"
                                    + " and app/jcl/POSTTRAN.jcl allocates no card dataset"));
        }

        @Test
        @DisplayName("the capture timestamp is compared and the processing timestamp is not")
        void comparesTheCaptureTimestampOnly() {
            Set<String> timestampAccessors = new TreeSet<>();
            for (Method method : DeclineRule.Context.class.getMethods()) {
                if (method.getName().startsWith("get") && method.getName().endsWith("Timestamp")) {
                    timestampAccessors.add(method.getName());
                }
            }

            assertEquals(Set.of("getOriginTimestamp"), timestampAccessors,
                    "app/cbl/CBTRN02C.cbl:L414 reads DALYTRAN-ORIG-TS at"
                            + " app/cpy/CVTRA06Y.cpy:L16, while DALYTRAN-PROC-TS at"
                            + " app/cpy/CVTRA06Y.cpy:L17 holds twenty-six blanks in all 300"
                            + " records of app/data/ASCII/dailytran.txt and carries two"
                            + " significant fractional digits once stamped");
        }

        @Test
        @DisplayName("the open and reissue dates hold no column on the row")
        void leavesTheOtherAccountDatesOut() {
            Set<String> exposed = rowAccessorNames();

            assertAll(
                    () -> assertFalse(exposed.contains("getAccountOpenDate"),
                            "ACCT-OPEN-DATE at app/cpy/CVACT01Y.cpy:L10 is read by no rule"),
                    () -> assertFalse(exposed.contains("getAccountReissueDate"),
                            "ACCT-REISSUE-DATE at app/cpy/CVACT01Y.cpy:L12 is read by no rule"),
                    () -> assertTrue(exposed.contains("getAccountExpirationDate"),
                            "the row exposes the one date app/cbl/CBTRN02C.cbl:L414 compares"));
        }
    }

    /**
     * Runs the rule over one expiry date against the capture timestamp of the daily feed.
     *
     * @param expiryDate {@code ACCT-EXPIRAION-DATE} from {@code app/cpy/CVACT01Y.cpy:L11}
     * @return the answer the rule gives for that date
     */
    private Optional<DeclineReason> outcomeFor(String expiryDate) {
        return outcomeFor(expiryDate, FIXTURE_ORIGIN_TIMESTAMP);
    }

    /**
     * Runs the rule over one expiry date and one capture timestamp.
     *
     * @param expiryDate      {@code ACCT-EXPIRAION-DATE} from {@code app/cpy/CVACT01Y.cpy:L11}
     * @param originTimestamp {@code DALYTRAN-ORIG-TS} from {@code app/cpy/CVTRA06Y.cpy:L16}
     * @return the answer the rule gives for that pair
     */
    private Optional<DeclineReason> outcomeFor(String expiryDate, String originTimestamp) {
        return rule.evaluate(contextCarrying(originTimestamp, snapshotExpiring(expiryDate)));
    }

    /**
     * Runs the rule over several expiry dates and counts the declining answers.
     *
     * @param expiryDates the dates to run
     * @return how many of them reached reject code {@code 0103}
     */
    private int countDeclining(String... expiryDates) {
        int declining = 0;
        for (String expiryDate : expiryDates) {
            if (outcomeFor(expiryDate).isPresent()) {
                declining++;
            }
        }
        return declining;
    }

    /**
     * Builds the values one authorization call carries, with a snapshot row already seated.
     *
     * <p>The preceding rule seats that row, mirroring the working-storage area
     * {@code app/cbl/CBTRN02C.cbl:L395} reads into.
     *
     * @param originTimestamp the capture timestamp, held as text
     * @param row             the row an account read resolved
     * @return the values the rule reads
     */
    private static DeclineRule.Context contextCarrying(String originTimestamp,
            AccountCreditSnapshotEntity row) {
        DeclineRule.Context context =
                new DeclineRule.Context(FIXTURE_CARD_NUMBER, FIXTURE_AMOUNT, originTimestamp);
        context.setAccountCreditSnapshot(row);
        return context;
    }

    /**
     * Builds the snapshot row an account read resolves to, carrying the one value the comparison
     * reads.
     *
     * <p>The row is a stub, and it answers for that accessor alone. A second read therefore shows
     * up as an unverified interaction.
     *
     * @param expirationDate the expiry date, ten characters held as text
     * @return the resolved row
     */
    private static AccountCreditSnapshotEntity snapshotExpiring(String expirationDate) {
        AccountCreditSnapshotEntity row = mock(AccountCreditSnapshotEntity.class);
        when(row.getAccountExpirationDate()).thenReturn(expirationDate);
        return row;
    }

    /**
     * Runs the rule over an expiry date below the capture date and returns what it assigns.
     *
     * @return the reject reason of {@code app/cbl/CBTRN02C.cbl:L417-L419}
     */
    private DeclineReason rejectReasonBelowTheCaptureDate() {
        return outcomeFor(EXPIRY_DAY_BEFORE_CAPTURE).orElseThrow();
    }

    /**
     * Collects the types the rule names in its fields, its constructors and its method signatures.
     *
     * @return the binary names of those types
     */
    private static Set<String> typesTheRuleNames() {
        Set<String> named = new TreeSet<>();
        for (Field field : AccountExpirationRule.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                named.add(field.getType().getName());
            }
        }
        for (Constructor<?> constructor : AccountExpirationRule.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                named.add(parameter.getName());
            }
        }
        for (Method method : AccountExpirationRule.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            named.add(method.getReturnType().getName());
            for (Class<?> parameter : method.getParameterTypes()) {
                named.add(parameter.getName());
            }
        }
        return named;
    }

    /**
     * Collects the name of every no-argument accessor the snapshot row exposes.
     *
     * @return those accessor names
     */
    private static Set<String> rowAccessorNames() {
        Set<String> named = new TreeSet<>();
        for (Method method : AccountCreditSnapshotEntity.class.getMethods()) {
            if (method.getName().startsWith("get") && method.getParameterCount() == 0) {
                named.add(method.getName());
            }
        }
        return named;
    }

    /**
     * Counts the positions at which two values of equal width differ.
     *
     * @param left  the first value
     * @param right the second value
     * @return how many positions differ
     */
    private static int differingPositions(String left, String right) {
        int differing = 0;
        for (int position = 0; position < left.length(); position++) {
            if (left.charAt(position) != right.charAt(position)) {
                differing++;
            }
        }
        return differing;
    }
}
