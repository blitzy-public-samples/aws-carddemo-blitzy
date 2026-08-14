package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.domain.validation.CreditScoreRangeValidator;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.messaging.CardUpdated;
import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Holds the six card validation texts and the credit-score range against their COBOL sources.
 *
 * <p>The texts come from the account-and-card band of {@code WS-RETURN-MSG} condition names at
 * {@code app/cbl/COCRDUPC.cbl:L189-L202}. Seven condition names sit in that band, and L192 repeats
 * L190 character for character, so the band carries six distinct texts.</p>
 *
 * <p>The range is {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
 * {@code app/cbl/COACTUPC.cbl:L848-L849}, inclusive at both bounds. {@code FICO} names a credit
 * score. Paragraph {@code 1275-EDIT-FICO-SCORE} at {@code app/cbl/COACTUPC.cbl:L2514-L2532} builds
 * the one failing text from the label at {@code app/cbl/COACTUPC.cbl:L1545}.</p>
 *
 * <p>Every comparison is exact string equality and no failure text carries a fixture value.</p>
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
class ValidationEquivalenceTest {

    /**
     * The seven texts of the account-and-card band, in {@code app/cbl/COCRDUPC.cbl} line order:
     * L190, L192, L194, L196, L198, L200 and L202.
     */
    private static final List<String> BAND_TEXTS_IN_SOURCE_ORDER = List.of(
            CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES,
            CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC,
            CardValidationMessages.NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC,
            CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
            CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
            CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
            CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF);

    /**
     * Condition names in the band whose text repeats an earlier one. L192 repeats L190, and no
     * other line in the band repeats.
     */
    private static final int BAND_REPEATED_TEXT_COUNT = 1;

    /**
     * Condition names the band declares, one per source line from L190 through L202. The count is
     * written here rather than read from {@link #BAND_TEXTS_IN_SOURCE_ORDER}, so an eighth entry
     * added to that list fails rather than moving the denominator with it.
     */
    private static final int BAND_CONDITION_NAME_COUNT = 7;

    /**
     * Distinct texts the seven condition names carry. Written here for the same reason: the count
     * is a fact about {@code app/cbl/COCRDUPC.cbl:L189-L202}, not about the size of a list in this
     * file.
     */
    private static final int BAND_DISTINCT_TEXT_COUNT = 6;

    /**
     * The character {@code app/cbl/COCRDUPC.cbl:L188} carries inside its quotes. No text of the
     * band closes with it.
     */
    private static final String FULL_STOP = ".";

    /** Lowest passing score, from {@code VALUES 300} at {@code app/cbl/COACTUPC.cbl:L848}. */
    private static final int LOWEST_PASSING_SCORE = 300;

    /**
     * Highest passing score, from {@code THROUGH 850} at {@code app/cbl/COACTUPC.cbl:L849}. The
     * program spells the keyword {@code THROUGH} there and {@code THRU} at
     * {@code app/cbl/COACTUPC.cbl:L123}.
     */
    private static final int HIGHEST_PASSING_SCORE = 850;

    /** Step that turns a bound into the nearest score outside the range. */
    private static final int ONE_STEP_OUTSIDE_THE_RANGE = 1;

    /**
     * No COBOL ancestor. Constructed score one step below the lower bound. No record of
     * {@code app/data/ASCII/custdata.txt} holds it.
     */
    private static final int SCORE_BELOW_LOWER_BOUND =
            LOWEST_PASSING_SCORE - ONE_STEP_OUTSIDE_THE_RANGE;

    /**
     * No COBOL ancestor. Constructed score one step above the upper bound. No record of
     * {@code app/data/ASCII/custdata.txt} holds it.
     */
    private static final int SCORE_ABOVE_UPPER_BOUND =
            HIGHEST_PASSING_SCORE + ONE_STEP_OUTSIDE_THE_RANGE;

    /**
     * No COBOL ancestor. Constructed score whose leading character is a zero. The {@code PIC 9(03)}
     * redefine at {@code app/cbl/COACTUPC.cbl:L846-L847} reads the three characters in place.
     */
    private static final String SCORE_WITH_A_LEADING_ZERO = "030";

    /**
     * No COBOL ancestor. Constructed score narrower than
     * {@link PicClause#CUST_FICO_CREDIT_SCORE_WIDTH}.
     */
    private static final String SCORE_OF_ANOTHER_WIDTH = "50";

    /** No COBOL ancestor. Constructed score holding one character outside the digit class. */
    private static final String SCORE_WITH_A_NON_DIGIT = "3x0";

    /** Label {@code 'FICO Score'} moved at {@code app/cbl/COACTUPC.cbl:L1545}. */
    private static final String CREDIT_SCORE_LABEL = "FICO Score";

    /** The checked-in validation-message expectations this class is the declared consumer of. */
    private static final String EXPECTED_MESSAGE_FILE = "validation-messages.csv";

    /** Every row of {@link #EXPECTED_MESSAGE_FILE}, parsed once for the whole class. */
    private static final ExpectedOutcomes EXPECTED_MESSAGES =
            ExpectedOutcomes.load(EXPECTED_MESSAGE_FILE);

    /** The card update program, which declares every committed validation message. */
    private static final String CARD_UPDATE_PROGRAM = "app/cbl/COCRDUPC.cbl";

    /** The account update program, which assembles the credit-score message. */
    private static final String ACCOUNT_UPDATE_PROGRAM = "app/cbl/COACTUPC.cbl";

    /** The batch posting program, which never consults the card status. */
    private static final String POSTING_PROGRAM = "app/cbl/CBTRN02C.cbl";

    /** The copybook that declares the customer credit score. */
    private static final String CUSTOMER_COPYBOOK = "app/cpy/CVCUS01Y.cpy";

    /** Where the read-only COBOL programs live below the repository root. */
    private static final String COBOL_PROGRAM_DIRECTORY = "app/cbl";

    /** The synthetic-case file the boundary probes live in. */
    private static final String SYNTHETIC_CASE_FILE = "synthetic-boundary-cases.csv";

    /** The seven condition names this checkpoint commits to reproducing verbatim. */
    private static final List<String> COMMITTED_CONDITION_NAMES = List.of(
            "SEARCHED-ACCT-ZEROES", "SEARCHED-ACCT-NOT-NUMERIC", "SEARCHED-CARD-NOT-NUMERIC",
            "CARD-STATUS-MUST-BE-YES-NO", "CARD-EXPIRY-MONTH-NOT-VALID",
            "CARD-EXPIRY-YEAR-NOT-VALID", "DID-NOT-FIND-ACCT-IN-CARDXREF");

    /** The entity key the file uses for the whole-fixture credit-score census. */
    private static final String CUSTDATA_CENSUS_KEY = "custdata_fico_scores";

    /** The entity key the file uses for the message the source moves inline. */
    private static final String INLINE_MESSAGE_KEY = "INLINE-MOVE-NO-CONDITION-NAME";

    /** A fragment unique to the inline message the source moves without a condition name. */
    private static final String INLINE_MESSAGE_FRAGMENT = "CARD ID FILTER";

    /** The condition name whose rule the inline message duplicates. */
    private static final String DUPLICATED_CONDITION_NAME = "SEARCHED-CARD-NOT-NUMERIC";

    /** The gate that makes the first error the only one reported. */
    private static final String MESSAGE_GATE_CONDITION = "WS-RETURN-MSG-OFF";

    /** The month condition the card program sets from two separate sites. */
    private static final String MONTH_CONDITION = "CARD-EXPIRY-MONTH-NOT-VALID";

    /** The year condition the card program sets from two separate sites. */
    private static final String YEAR_CONDITION = "CARD-EXPIRY-YEAR-NOT-VALID";

    /** The month range condition of the card program's working storage. */
    private static final String MONTH_RANGE_CONDITION = "VALID-MONTH";

    /** The year range condition of the card program's working storage. */
    private static final String YEAR_RANGE_CONDITION = "VALID-YEAR";

    /** How many sites set each expiry condition. */
    private static final int TWO_SET_SITES = 2;

    /** The three tokens the not-supplied test compares an expiry component against. */
    private static final List<String> NOT_SUPPLIED_TOKENS =
            List.of("EQUAL LOW-VALUES", "EQUAL SPACES", "EQUAL ZEROS");

    /** The three components the card program slices the expiry date into. */
    private static final List<String> EXPIRY_COMPONENT_FIELDS =
            List.of("CARD-EXPIRY-YEAR", "CARD-EXPIRY-MONTH", "CARD-EXPIRY-DAY");

    /** Tokens a checksum validation would have to carry, none of which the source does. */
    private static final List<String> CHECKSUM_TOKENS = List.of("LUHN", "CHECKSUM", "MOD 10");

    /** The card status field the posting path never consults. */
    private static final String CARD_STATUS_FIELD = "CARD-ACTIVE-STATUS";

    /** The width the credit-score field's Picture clause declares. */
    private static final int SCORE_FIELD_WIDTH = 3;

    /** The offset of the month component inside the ten-character expiry date. */
    private static final int EXPIRY_MONTH_OFFSET = 5;

    /** The width of the month component inside the ten-character expiry date. */
    private static final int EXPIRY_MONTH_WIDTH = 2;

    /** The expiry month no fixture card carries. */
    private static final String ABSENT_EXPIRY_MONTH = "11";

    /** The character one embossed name carries that the alphabetic rule refuses. */
    private static final String APOSTROPHE = "'";

    /** The pattern a three-digit card verification value matches. */
    private static final String THREE_DIGIT_PATTERN = "\\d{3}";

    /** The character a message would end with if it carried a sentence period. */
    private static final String SENTENCE_PERIOD = ".";

    /** Every credit score the customer fixture carries, in fixture order. */
    private static final List<Integer> FIXTURE_SCORES = CardDemoFixtureLoader.loadCustomers()
            .stream().map(CopybookRecordParser.CustomerRecord::ficoCreditScore).toList();


    /**
     * Text the {@code STRING} at {@code app/cbl/COACTUPC.cbl:L2521-L2526} assembles from the
     * trimmed label and the literal at {@code app/cbl/COACTUPC.cbl:L2523}.
     */
    private static final String CREDIT_SCORE_RANGE_TEXT =
            "FICO Score: should be between 300 and 850";

    /**
     * Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COACTUPC.cbl:L479}, whose
     * all-spaces condition name sits at {@code app/cbl/COACTUPC.cbl:L480} and whose reset sits at
     * {@code app/cbl/COACTUPC.cbl:L876}.
     */
    private static final int RETURN_MESSAGE_SLOT_WIDTH = 75;

    /**
     * First literal of the {@code STRING} at {@code app/cbl/COACTUPC.cbl:L1807}, which the account
     * programs carry for the concept the band states at {@code app/cbl/COCRDUPC.cbl:L190}.
     */
    private static final String ACCOUNT_NUMBER_FIRST_LITERAL =
            "Account Number if supplied must be a 11 digit";

    /**
     * Second literal of the same {@code STRING}, at {@code app/cbl/COACTUPC.cbl:L1808}. The literal
     * opens with one space.
     */
    private static final String ACCOUNT_NUMBER_SECOND_LITERAL = " Non-Zero Number";

    /** Text of {@code 88 WS-PROMPT-FOR-ACCT} at {@code app/cbl/COACTUPC.cbl:L483-L484}. */
    private static final String ACCOUNT_NUMBER_NOT_PROVIDED_TEXT = "Account number not provided";

    /**
     * Ordinal of the first record of a fixture. {@link CardDemoFixtureLoader} and
     * {@link CopybookRecordParser} both report a fixture position one-based, and every failure
     * below reports the same way.
     */
    private static final int FIRST_RECORD_ORDINAL = 1;

    /**
     * A sixteen-digit card number in the shape {@code CARD-NUM PIC X(16)} declares at
     * {@code app/cpy/CVACT02Y.cpy:L5}. It is a shape and not a fixture row, so nothing real is
     * masked here, and it is the value shown not to match the masked pattern.
     */
    private static final String SHAPED_CARD_NUMBER = "9999888877776666";

    /**
     * Components of the card response surface that reach a card number under a name that does not
     * itself say masked. {@code CardSummary.cardNumber} keeps the source field name of
     * {@code CARD-NUM} at {@code app/cpy/CVACT02Y.cpy:L5}, and the value it carries is measured
     * against the masked pattern for every record of {@code app/data/ASCII/carddata.txt}.
     */
    private static final Set<String> MASKED_BY_CONTRACT = Set.of("CardSummary.cardNumber");

    /**
     * The number of components {@code CardUpdated.toString()} replaces with
     * {@link EventEnvelope#WITHHELD}: the aggregate identifier, the account identifier, the masked
     * card number, the expiry date and the active status.
     *
     * <p>The rendering prints the event identifier and withholds everything else, so this count is
     * the guarantee that rendering carries. A component added to the record without a withholding
     * entry in the rendering moves this count and fails the test below.
     */
    private static final int WITHHELD_RENDERED_COMPONENTS = 5;

    /**
     * Stands in for the event identifier while a rendering is measured for a card value.
     *
     * <p>{@code CardUpdated.toString()} prints {@code eventId}, a random {@link java.util.UUID}
     * whose hexadecimal digits are drawn from {@code 0-9a-f}. A three-digit
     * {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L7} is therefore a substring of roughly
     * one rendering in every one hundred and thirty by coincidence alone. A bare containment check
     * over the whole rendering reports that coincidence as a leak. Eliding the identifier first
     * leaves the check measuring the components this contract is about.
     */
    private static final String ELIDED_EVENT_IDENTIFIER = "<eventId>";

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits below the lower bound. */
    private static final int CUSTDATA_SCORES_BELOW_LOWER_BOUND = 21;

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits inside the range. */
    private static final int CUSTDATA_SCORES_INSIDE_THE_RANGE = 29;

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits above the upper bound. */
    private static final int CUSTDATA_SCORES_ABOVE_UPPER_BOUND = 0;

    /**
     * Fixed-width zero-padded form of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L22}, which holds the same width as
     * {@code ACUP-NEW-CUST-FICO-SCORE-X PIC X(03)} at {@code app/cbl/COACTUPC.cbl:L845}.
     */
    private static final String SCORE_TEXT_FORMAT =
            "%0" + PicClause.CUST_FICO_CREDIT_SCORE_WIDTH + "d";

    /**
     * Reports whether one text is a member of the band, comparing with exact string equality.
     *
     * @param candidate the text to look for
     * @return true when a band text equals {@code candidate}
     */
    private static boolean isBandText(String candidate) {
        for (String bandText : BAND_TEXTS_IN_SOURCE_ORDER) {
            if (bandText.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a score as the characters its {@code PIC} clause holds, zero padded on the left.
     *
     * @param score the value to render
     * @return the score at {@link PicClause#CUST_FICO_CREDIT_SCORE_WIDTH} characters
     */
    private static String scoreText(int score) {
        return String.format(SCORE_TEXT_FORMAT, score);
    }

    /**
     * Counts the non-overlapping occurrences of one marker in one rendering.
     *
     * @param rendering the text to measure
     * @param marker    the marker to count, never empty
     * @return the number of times {@code marker} occurs in {@code rendering}
     */
    private static int countOccurrences(String rendering, String marker) {
        int occurrences = 0;

        for (int from = rendering.indexOf(marker); from >= 0;
                from = rendering.indexOf(marker, from + marker.length())) {
            occurrences++;
        }
        return occurrences;
    }

    /** Returns a one-way fingerprint for a sensitive fixture value. */
    private static String sensitiveFingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError("SHA-256 must be available", unavailable);
        }
    }

    /** Compares two sensitive values without placing either value in an assertion result. */
    private static void assertSameSensitiveValue(
            String expected,
            String actual,
            String message) {
        assertTrue(sensitiveFingerprint(expected).equals(sensitiveFingerprint(actual)), message);
    }

    /** Proves two sensitive values differ without placing either value in an assertion result. */
    private static void assertDifferentSensitiveValue(
            String first,
            String second,
            String message) {
        assertFalse(sensitiveFingerprint(first).equals(sensitiveFingerprint(second)), message);
    }

    /** Proves one sensitive value is absent without placing it in an assertion result. */
    private static void assertSensitiveValueAbsent(
            String rendering,
            String sensitiveValue,
            String message) {
        assertFalse(rendering.contains(sensitiveValue), message);
    }

    /**
     * Supplies the seven band condition names, their lines and their texts.
     *
     * @return one argument triple per condition name of {@code app/cbl/COCRDUPC.cbl:L189-L202}
     */
    static Stream<Arguments> bandTextsWithTheirLocators() {
        return Stream.of(
                Arguments.of("SEARCHED-ACCT-ZEROES", "app/cbl/COCRDUPC.cbl:L190",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES),
                Arguments.of("SEARCHED-ACCT-NOT-NUMERIC", "app/cbl/COCRDUPC.cbl:L192",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC),
                Arguments.of("SEARCHED-CARD-NOT-NUMERIC", "app/cbl/COCRDUPC.cbl:L194",
                        CardValidationMessages.NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC),
                Arguments.of("CARD-STATUS-MUST-BE-YES-NO", "app/cbl/COCRDUPC.cbl:L196",
                        CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO),
                Arguments.of("CARD-EXPIRY-MONTH-NOT-VALID", "app/cbl/COCRDUPC.cbl:L198",
                        CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID),
                Arguments.of("CARD-EXPIRY-YEAR-NOT-VALID", "app/cbl/COCRDUPC.cbl:L200",
                        CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID),
                Arguments.of("DID-NOT-FIND-ACCT-IN-CARDXREF", "app/cbl/COCRDUPC.cbl:L202",
                        CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF));
    }

    /**
     * Supplies the neighbouring condition names of the band, their lines and their texts.
     *
     * @return one argument triple per neighbour declared at
     *         {@code app/cbl/COCRDUPC.cbl:L182-L214}
     */
    static Stream<Arguments> neighbourTextsWithTheirLocators() {
        return Stream.of(
                Arguments.of("WS-PROMPT-FOR-NAME", "app/cbl/COCRDUPC.cbl:L182",
                        CardValidationMessages.PROMPT_FOR_NAME),
                Arguments.of("WS-NAME-MUST-BE-ALPHA", "app/cbl/COCRDUPC.cbl:L184",
                        CardValidationMessages.NAME_MUST_BE_ALPHA),
                Arguments.of("NO-SEARCH-CRITERIA-RECEIVED", "app/cbl/COCRDUPC.cbl:L186",
                        CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED),
                Arguments.of("NO-CHANGES-DETECTED", "app/cbl/COCRDUPC.cbl:L188",
                        CardValidationMessages.NO_CHANGES_DETECTED),
                Arguments.of("DID-NOT-FIND-ACCTCARD-COMBO", "app/cbl/COCRDUPC.cbl:L204",
                        CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO),
                Arguments.of("COULD-NOT-LOCK-FOR-UPDATE", "app/cbl/COCRDUPC.cbl:L206",
                        CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE),
                Arguments.of("DATA-WAS-CHANGED-BEFORE-UPDATE", "app/cbl/COCRDUPC.cbl:L208",
                        CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE),
                Arguments.of("LOCKED-BUT-UPDATE-FAILED", "app/cbl/COCRDUPC.cbl:L210",
                        CardValidationMessages.LOCKED_BUT_UPDATE_FAILED),
                Arguments.of("XREF-READ-ERROR", "app/cbl/COCRDUPC.cbl:L212",
                        CardValidationMessages.NEVER_EMITTED_XREF_READ_ERROR),
                Arguments.of("CODING-TO-BE-DONE", "app/cbl/COCRDUPC.cbl:L214",
                        CardValidationMessages.NEVER_EMITTED_CODING_TO_BE_DONE));
    }

    /**
     * Compares each band text against the literal at its own line of
     * {@code app/cbl/COCRDUPC.cbl}.
     */
    @Nested
    @DisplayName("WS-RETURN-MSG band at COCRDUPC L189-L202")
    class CardMessageTexts {

        /** Compares {@code SEARCHED-ACCT-ZEROES} against the literal at L190. */
        @Test
        @DisplayName("SEARCHED-ACCT-ZEROES carries the literal at COCRDUPC L190")
        void searchedAcctZeroesCarriesItsLiteral() {
            assertEquals("Account number must be a non zero 11 digit number",
                    CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES,
                    "SEARCHED-ACCT-ZEROES diverged from app/cbl/COCRDUPC.cbl:L190");
        }

        /** Compares {@code SEARCHED-ACCT-NOT-NUMERIC} against the literal at L192. */
        @Test
        @DisplayName("SEARCHED-ACCT-NOT-NUMERIC carries the literal at COCRDUPC L192")
        void searchedAcctNotNumericCarriesItsLiteral() {
            assertEquals("Account number must be a non zero 11 digit number",
                    CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC,
                    "SEARCHED-ACCT-NOT-NUMERIC diverged from app/cbl/COCRDUPC.cbl:L192");
        }

        /** Holds L192 byte-identical to L190, which is what reduces seven names to six texts. */
        @Test
        @DisplayName("COCRDUPC L192 repeats L190 character for character")
        void theTwoAccountNumberLiteralsAreIdentical() {
            assertEquals(CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES,
                    CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC,
                    "app/cbl/COCRDUPC.cbl:L192 stopped repeating L190, so the band no longer "
                            + "carries six distinct texts");
        }

        /**
         * Compares {@code SEARCHED-CARD-NOT-NUMERIC} against the literal at L194. The card-number
         * edit at {@code app/cbl/COCRDUPC.cbl:L784} tests the numeric class only. No checksum test
         * appears in this file.
         */
        @Test
        @DisplayName("SEARCHED-CARD-NOT-NUMERIC carries the literal at COCRDUPC L194")
        void searchedCardNotNumericCarriesItsLiteral() {
            assertEquals("Card number if supplied must be a 16 digit number",
                    CardValidationMessages.NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC,
                    "SEARCHED-CARD-NOT-NUMERIC diverged from app/cbl/COCRDUPC.cbl:L194");
        }

        /** Compares {@code CARD-STATUS-MUST-BE-YES-NO} against the literal at L196. */
        @Test
        @DisplayName("CARD-STATUS-MUST-BE-YES-NO carries the literal at COCRDUPC L196")
        void cardStatusMustBeYesNoCarriesItsLiteral() {
            assertEquals("Card Active Status must be Y or N",
                    CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                    "CARD-STATUS-MUST-BE-YES-NO diverged from app/cbl/COCRDUPC.cbl:L196");
        }

        /** Compares {@code CARD-EXPIRY-MONTH-NOT-VALID} against the literal at L198. */
        @Test
        @DisplayName("CARD-EXPIRY-MONTH-NOT-VALID carries the literal at COCRDUPC L198")
        void cardExpiryMonthNotValidCarriesItsLiteral() {
            assertEquals("Card expiry month must be between 1 and 12",
                    CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                    "CARD-EXPIRY-MONTH-NOT-VALID diverged from app/cbl/COCRDUPC.cbl:L198");
        }

        /** Compares {@code CARD-EXPIRY-YEAR-NOT-VALID} against the literal at L200. */
        @Test
        @DisplayName("CARD-EXPIRY-YEAR-NOT-VALID carries the literal at COCRDUPC L200")
        void cardExpiryYearNotValidCarriesItsLiteral() {
            assertEquals("Invalid card expiry year",
                    CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                    "CARD-EXPIRY-YEAR-NOT-VALID diverged from app/cbl/COCRDUPC.cbl:L200");
        }

        /** Compares {@code DID-NOT-FIND-ACCT-IN-CARDXREF} against the literal at L202. */
        @Test
        @DisplayName("DID-NOT-FIND-ACCT-IN-CARDXREF carries the literal at COCRDUPC L202")
        void didNotFindAcctInCardxrefCarriesItsLiteral() {
            assertEquals("Did not find this account in cards database",
                    CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF,
                    "DID-NOT-FIND-ACCT-IN-CARDXREF diverged from app/cbl/COCRDUPC.cbl:L202");
        }

        /**
         * Holds the two counts of the band independently. Seven condition names carry six distinct
         * texts, because L192 repeats L190. Both counts are written as literals, so an eighth entry
         * in the list fails this test rather than moving the expected value with it.
         */
        @Test
        @DisplayName("the COCRDUPC L189-L202 band declares seven condition names carrying six "
                + "distinct texts")
        void theBandDeclaresSevenConditionNamesCarryingSixDistinctTexts() {
            Set<String> distinctTexts = new LinkedHashSet<>(BAND_TEXTS_IN_SOURCE_ORDER);

            assertEquals(BAND_CONDITION_NAME_COUNT, BAND_TEXTS_IN_SOURCE_ORDER.size(),
                    "the account-and-card band of app/cbl/COCRDUPC.cbl:L189-L202 declares one "
                            + "condition name per line from L190 through L202");
            assertEquals(BAND_DISTINCT_TEXT_COUNT, distinctTexts.size(),
                    "the account-and-card band of app/cbl/COCRDUPC.cbl:L189-L202 stopped carrying "
                            + "the distinct-text count its seven condition names give");
            assertEquals(BAND_REPEATED_TEXT_COUNT,
                    BAND_CONDITION_NAME_COUNT - BAND_DISTINCT_TEXT_COUNT,
                    "L192 is the one line of the band whose text repeats an earlier line");
            assertEquals(BAND_TEXTS_IN_SOURCE_ORDER.get(0), BAND_TEXTS_IN_SOURCE_ORDER.get(1),
                    "app/cbl/COCRDUPC.cbl:L192 repeats the characters of L190");

            // The exact expected set, typed here rather than read back from the list above.
            assertEquals(Set.of(
                            "Account number must be a non zero 11 digit number",
                            "Card number if supplied must be a 16 digit number",
                            "Card Active Status must be Y or N",
                            "Card expiry month must be between 1 and 12",
                            "Invalid card expiry year",
                            "Did not find this account in cards database"),
                    distinctTexts,
                    "the six distinct texts of app/cbl/COCRDUPC.cbl:L189-L202 changed");
        }
    }

    /**
     * Holds the terminal punctuation of the band against the one neighbour that closes with a full
     * stop.
     */
    @Nested
    @DisplayName("Terminal punctuation of the COCRDUPC texts")
    class CardMessageTerminalPunctuation {

        /**
         * Holds every band text free of a closing full stop. Each case names its own condition
         * name and line.
         *
         * @param conditionName the {@code 88}-level name under test
         * @param locator       the line of {@code app/cbl/COCRDUPC.cbl} that declares it
         * @param text          the declared text
         */
        @ParameterizedTest(name = "{0} at {1}")
        @MethodSource(
                "com.carddemo.equivalence.ValidationEquivalenceTest#bandTextsWithTheirLocators")
        @DisplayName("no band text closes with a full stop")
        void aBandTextDoesNotCloseWithAFullStop(String conditionName, String locator, String text) {
            assertFalse(text.endsWith(FULL_STOP), conditionName + " at " + locator
                    + " gained a closing full stop, which app/cbl/COCRDUPC.cbl:L188 carries and "
                    + "the band does not");
        }

        /** Holds the L188 contrast, whose literal closes with a full stop inside its quotes. */
        @Test
        @DisplayName("NO-CHANGES-DETECTED at COCRDUPC L188 closes with a full stop")
        void theContrastTextClosesWithAFullStop() {
            assertEquals("No change detected with respect to values fetched.",
                    CardValidationMessages.NO_CHANGES_DETECTED,
                    "NO-CHANGES-DETECTED diverged from app/cbl/COCRDUPC.cbl:L188");
            assertTrue(CardValidationMessages.NO_CHANGES_DETECTED.endsWith(FULL_STOP),
                    "NO-CHANGES-DETECTED at app/cbl/COCRDUPC.cbl:L188 lost the closing full stop "
                            + "that separates it from the band");
        }
    }

    /**
     * Separates the band from its neighbours and from the account programs' own account-number
     * texts.
     */
    @Nested
    @DisplayName("Texts outside the COCRDUPC L189-L202 band")
    class TextsOutsideTheBand {

        /**
         * Holds each neighbouring text outside the band. Each case names its own condition name
         * and line.
         *
         * @param conditionName the {@code 88}-level name under test
         * @param locator       the line of {@code app/cbl/COCRDUPC.cbl} that declares it
         * @param text          the declared text
         */
        @ParameterizedTest(name = "{0} at {1}")
        @MethodSource("com.carddemo.equivalence.ValidationEquivalenceTest"
                + "#neighbourTextsWithTheirLocators")
        @DisplayName("a neighbouring text is no member of the band")
        void aNeighbouringTextIsNoBandMember(String conditionName, String locator, String text) {
            assertFalse(isBandText(text), conditionName + " at " + locator
                    + " became a member of the account-and-card band, which widens the band past "
                    + "the six texts app/cbl/COCRDUPC.cbl:L189-L202 declares");
        }

        /**
         * Separates the two texts that differ by one clause: L204 names a search condition and
         * L202 names an account.
         */
        @Test
        @DisplayName("COCRDUPC L204 is no repeat of L202")
        void theSearchConditionTextIsNoRepeatOfTheCardxrefText() {
            assertNotEquals(CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF,
                    CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO,
                    "DID-NOT-FIND-ACCTCARD-COMBO at app/cbl/COCRDUPC.cbl:L204 and "
                            + "DID-NOT-FIND-ACCT-IN-CARDXREF at app/cbl/COCRDUPC.cbl:L202 stopped "
                            + "carrying different texts");
        }

        /**
         * Joins the two literals of {@code app/cbl/COACTUPC.cbl:L1807-L1808}, whose second literal
         * opens with one space.
         */
        @Test
        @DisplayName("COACTUPC L1807-L1808 joins into one text with one space between its literals")
        void theTwoAccountNumberLiteralsJoinIntoOneText() {
            assertEquals("Account Number if supplied must be a 11 digit Non-Zero Number",
                    ACCOUNT_NUMBER_FIRST_LITERAL + ACCOUNT_NUMBER_SECOND_LITERAL,
                    "the STRING at app/cbl/COACTUPC.cbl:L1807-L1808 stopped assembling the text "
                            + "its two literals give, so the leading space on L1808 changed");
        }

        /**
         * Holds the assembled account-number text of {@code app/cbl/COACTUPC.cbl:L1807-L1808}
         * outside the band.
         */
        @Test
        @DisplayName("the COACTUPC L1807-L1808 text is no member of the band")
        void theAssembledAccountNumberTextIsNoBandMember() {
            assertFalse(isBandText(ACCOUNT_NUMBER_FIRST_LITERAL + ACCOUNT_NUMBER_SECOND_LITERAL),
                    "the text the STRING at app/cbl/COACTUPC.cbl:L1807-L1808 assembles became a "
                            + "member of the band declared at app/cbl/COCRDUPC.cbl:L189-L202");
        }

        /**
         * Holds the account-number text of {@code app/cbl/COACTUPC.cbl:L483-L484} outside the
         * band, and equal to its card-program twin at {@code app/cbl/COCRDUPC.cbl:L178}.
         */
        @Test
        @DisplayName("the COACTUPC L483-L484 text is no band member and repeats COCRDUPC L178")
        void theAccountNumberNotProvidedTextIsNoBandMember() {
            assertFalse(isBandText(ACCOUNT_NUMBER_NOT_PROVIDED_TEXT),
                    "WS-PROMPT-FOR-ACCT at app/cbl/COACTUPC.cbl:L483-L484 became a member of the "
                            + "band declared at app/cbl/COCRDUPC.cbl:L189-L202");
            assertEquals(ACCOUNT_NUMBER_NOT_PROVIDED_TEXT,
                    CardValidationMessages.PROMPT_FOR_ACCT,
                    "WS-PROMPT-FOR-ACCT at app/cbl/COACTUPC.cbl:L483-L484 stopped repeating the "
                            + "same condition name at app/cbl/COCRDUPC.cbl:L178");
        }

        @Test
        @DisplayName("COCRDUPC L190, COACTUPC L1807-L1808 and COACTUPC L483-L484 stay distinct")
        void theThreeAccountNumberTextsStayDistinct() {
            String bandText = CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES;
            String assembledText = ACCOUNT_NUMBER_FIRST_LITERAL + ACCOUNT_NUMBER_SECOND_LITERAL;
            assertNotEquals(bandText, assembledText,
                    "app/cbl/COCRDUPC.cbl:L190 and app/cbl/COACTUPC.cbl:L1807-L1808 stopped "
                            + "carrying different texts for the account-number width and sign");
            assertNotEquals(bandText, ACCOUNT_NUMBER_NOT_PROVIDED_TEXT,
                    "app/cbl/COCRDUPC.cbl:L190 and app/cbl/COACTUPC.cbl:L483-L484 stopped "
                            + "carrying different texts");
            assertNotEquals(assembledText, ACCOUNT_NUMBER_NOT_PROVIDED_TEXT,
                    "app/cbl/COACTUPC.cbl:L1807-L1808 and app/cbl/COACTUPC.cbl:L483-L484 stopped "
                            + "carrying different texts");
        }
    }

    /**
     * Applies the range test of {@code app/cbl/COACTUPC.cbl:L2515} at both bounds and one step
     * outside each.
     */
    @Nested
    @DisplayName("FICO-RANGE-IS-VALID at COACTUPC L848-L849")
    class CreditScoreRange {

        /** No COBOL ancestor. Holds the score one step below the lower bound outside the range. */
        @Test
        @DisplayName("a score one step below the lower bound fails")
        void aScoreOneStepBelowTheLowerBoundFails() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_BELOW_LOWER_BOUND));
            assertFalse(verdict.valid(), "a score one step below VALUES 300 at "
                    + "app/cbl/COACTUPC.cbl:L848 passed the range test at "
                    + "app/cbl/COACTUPC.cbl:L2515");
        }

        @Test
        @DisplayName("the lower bound of COACTUPC L848 passes")
        void theLowerBoundPasses() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(LOWEST_PASSING_SCORE));
            assertTrue(verdict.valid(), "VALUES 300 at app/cbl/COACTUPC.cbl:L848 failed the range "
                    + "test at app/cbl/COACTUPC.cbl:L2515, so the lower bound stopped being "
                    + "inclusive");
        }

        @Test
        @DisplayName("the upper bound of COACTUPC L849 passes")
        void theUpperBoundPasses() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(HIGHEST_PASSING_SCORE));
            assertTrue(verdict.valid(), "THROUGH 850 at app/cbl/COACTUPC.cbl:L849 failed the range "
                    + "test at app/cbl/COACTUPC.cbl:L2515, so the upper bound stopped being "
                    + "inclusive");
        }

        /** No COBOL ancestor. Holds the score one step above the upper bound outside the range. */
        @Test
        @DisplayName("a score one step above the upper bound fails")
        void aScoreOneStepAboveTheUpperBoundFails() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_ABOVE_UPPER_BOUND));
            assertFalse(verdict.valid(), "a score one step above THROUGH 850 at "
                    + "app/cbl/COACTUPC.cbl:L849 passed the range test at "
                    + "app/cbl/COACTUPC.cbl:L2515");
        }

        /**
         * No COBOL ancestor. Holds a leading zero read in place, which the {@code PIC 9(03)}
         * redefine at {@code app/cbl/COACTUPC.cbl:L846-L847} gives.
         */
        @Test
        @DisplayName("a leading zero reads in place and fails the range")
        void aLeadingZeroReadsInPlace() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    SCORE_WITH_A_LEADING_ZERO);
            assertFalse(verdict.valid(), "a score whose leading character is a zero passed the "
                    + "range test at app/cbl/COACTUPC.cbl:L2515, so the PIC 9(03) redefine at "
                    + "app/cbl/COACTUPC.cbl:L846-L847 stopped reading the three characters in "
                    + "place");
        }

        /**
         * No COBOL ancestor. Holds a score outside the digit class inside the one message slot,
         * with no exception and no parse. {@code app/cbl/COACTUPC.cbl:L1549-L1550} runs the numeric
         * edit and {@code app/cbl/COACTUPC.cbl:L1553} gates the range paragraph on its verdict.
         */
        @Test
        @DisplayName("a score outside the digit class needs no parse and raises nothing")
        void aScoreOutsideTheDigitClassNeedsNoParse() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    SCORE_WITH_A_NON_DIGIT);
            assertFalse(verdict.valid(), "a score holding a character outside the digit class "
                    + "passed the range test at app/cbl/COACTUPC.cbl:L2515");
            assertEquals(CREDIT_SCORE_RANGE_TEXT, verdict.message(),
                    "a score holding a character outside the digit class stopped carrying the one "
                            + "text the STRING at app/cbl/COACTUPC.cbl:L2521-L2526 builds");
        }

        /** No COBOL ancestor. Holds a score of another width in the same single message slot. */
        @Test
        @DisplayName("a score of another width carries the same one text")
        void aScoreOfAnotherWidthCarriesTheSameText() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    SCORE_OF_ANOTHER_WIDTH);
            assertFalse(verdict.valid(), "a score narrower than "
                    + PicClause.CUST_FICO_CREDIT_SCORE_WIDTH + " characters passed the range test "
                    + "at app/cbl/COACTUPC.cbl:L2515");
            assertEquals(CREDIT_SCORE_RANGE_TEXT, verdict.message(),
                    "a score of another width stopped carrying the one text the STRING at "
                            + "app/cbl/COACTUPC.cbl:L2521-L2526 builds");
        }
    }

    /**
     * Holds the text {@code app/cbl/COACTUPC.cbl:L2521-L2526} assembles, and the single-slot
     * contract of {@code app/cbl/COACTUPC.cbl:L479-L480}.
     */
    @Nested
    @DisplayName("1275-EDIT-FICO-SCORE text at COACTUPC L2521-L2526")
    class CreditScoreRangeText {

        /** Compares the assembled text against the label at L1545 and the literal at L2523. */
        @Test
        @DisplayName("a failing score carries the text COACTUPC L2521-L2526 assembles")
        void aFailingScoreCarriesTheAssembledText() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_BELOW_LOWER_BOUND));
            assertEquals(CREDIT_SCORE_RANGE_TEXT, verdict.message(),
                    "1275-EDIT-FICO-SCORE at app/cbl/COACTUPC.cbl:L2514 stopped assembling the "
                            + "trimmed label of app/cbl/COACTUPC.cbl:L1545 with the literal of "
                            + "app/cbl/COACTUPC.cbl:L2523");
        }

        @Test
        @DisplayName("a failing score reports that the message slot holds text")
        void aFailingScoreReportsThatTheSlotHoldsText() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_ABOVE_UPPER_BOUND));
            assertTrue(verdict.hasMessage(), "a failing score left WS-RETURN-MSG at "
                    + "app/cbl/COACTUPC.cbl:L479 in the all-spaces state its condition name at "
                    + "app/cbl/COACTUPC.cbl:L480 names");
        }

        @Test
        @DisplayName("a passing score leaves the message slot open")
        void aPassingScoreLeavesTheSlotOpen() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(LOWEST_PASSING_SCORE));
            assertTrue(verdict.valid(), "VALUES 300 at app/cbl/COACTUPC.cbl:L848 failed the range "
                    + "test at app/cbl/COACTUPC.cbl:L2515");
            assertFalse(verdict.hasMessage(), "a passing score filled WS-RETURN-MSG at "
                    + "app/cbl/COACTUPC.cbl:L479, which closes the slot to the edits that follow "
                    + "the guard at app/cbl/COACTUPC.cbl:L2520");
        }

        @Test
        @DisplayName("the assembled text fits WS-RETURN-MSG PIC X(75) at COACTUPC L479")
        void theAssembledTextFitsTheMessageSlot() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_BELOW_LOWER_BOUND));
            assertTrue(verdict.message().length() <= RETURN_MESSAGE_SLOT_WIDTH,
                    "the text 1275-EDIT-FICO-SCORE assembles now measures "
                            + verdict.message().length() + " characters and WS-RETURN-MSG at "
                            + "app/cbl/COACTUPC.cbl:L479 holds " + RETURN_MESSAGE_SLOT_WIDTH
                            + ", so the STRING at app/cbl/COACTUPC.cbl:L2521-L2526 truncates");
        }
    }

    /**
     * Runs every credit score of {@code app/data/ASCII/custdata.txt} through the range test.
     */
    @Nested
    @DisplayName("custdata.txt scores at CVCUS01Y L22")
    class CustomerFixtureScores {

        /**
         * Holds each fixture verdict in agreement with the bounds of
         * {@code app/cbl/COACTUPC.cbl:L848-L849}.
         */
        @Test
        @DisplayName("every custdata.txt score agrees with the inclusive range")
        void everyFixtureScoreAgreesWithTheRange() {
            List<CopybookRecordParser.CustomerRecord> customers =
                    CardDemoFixtureLoader.loadCustomers();
            assertEquals(PicClause.CUSTDATA_FIXTURE_RECORD_COUNT, customers.size(),
                    "app/data/ASCII/custdata.txt stopped holding the record count PicClause "
                            + "publishes");
            int ordinal = FIRST_RECORD_ORDINAL;
            for (CopybookRecordParser.CustomerRecord customer : customers) {
                int score = customer.ficoCreditScore();
                boolean insideTheRange =
                        score >= LOWEST_PASSING_SCORE && score <= HIGHEST_PASSING_SCORE;
                EditResult verdict =
                        CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL, scoreText(score));
                assertEquals(insideTheRange, verdict.valid(),
                        "CUST-FICO-CREDIT-SCORE at app/cpy/CVCUS01Y.cpy:L22 of "
                                + "app/data/ASCII/custdata.txt record " + ordinal
                                + " disagreed with FICO-RANGE-IS-VALID at "
                                + "app/cbl/COACTUPC.cbl:L848-L849");
                assertEquals(insideTheRange ? null : CREDIT_SCORE_RANGE_TEXT, verdict.message(),
                        "app/data/ASCII/custdata.txt record " + ordinal + " stopped carrying the "
                                + "message its verdict gives under app/cbl/COACTUPC.cbl:L2520");
                ordinal++;
            }
        }

        /**
         * Holds the measured census of the fixture, which reaches the failing branch and the
         * passing branch of {@code app/cbl/COACTUPC.cbl:L2515}.
         */
        @Test
        @DisplayName("the custdata.txt census splits across the lower bound")
        void theFixtureCensusSplitsAcrossTheLowerBound() {
            int below = 0;
            int inside = 0;
            int above = 0;
            for (CopybookRecordParser.CustomerRecord customer
                    : CardDemoFixtureLoader.loadCustomers()) {
                int score = customer.ficoCreditScore();
                if (score < LOWEST_PASSING_SCORE) {
                    below++;
                } else if (score > HIGHEST_PASSING_SCORE) {
                    above++;
                } else {
                    inside++;
                }
            }
            assertEquals(CUSTDATA_SCORES_BELOW_LOWER_BOUND, below,
                    "app/data/ASCII/custdata.txt stopped holding the measured count of scores "
                            + "below VALUES 300 at app/cbl/COACTUPC.cbl:L848");
            assertEquals(CUSTDATA_SCORES_INSIDE_THE_RANGE, inside,
                    "app/data/ASCII/custdata.txt stopped holding the measured count of scores "
                            + "inside FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:L848-L849");
            assertEquals(CUSTDATA_SCORES_ABOVE_UPPER_BOUND, above,
                    "app/data/ASCII/custdata.txt stopped holding the measured count of scores "
                            + "above THROUGH 850 at app/cbl/COACTUPC.cbl:L849");
        }
    }

    /**
     * Holds {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L7} and the full Primary Account
     * Number (PAN) out of every production output boundary the card service ships.
     *
     * <p>Each test below starts from the full values of {@code app/data/ASCII/carddata.txt} and
     * drives them through production code. Three boundaries are measured: the masking step of
     * {@code CardUpdated.ofUnmaskedCardNumber}, the masked form the response records carry, and the
     * component sets those records declare. A rendering produced by a helper of this module proves
     * nothing about what production emits, so no test here reads one.</p>
     */
    @Nested
    @DisplayName("carddata.txt through the production output boundaries of the card service")
    class CardFixtureRendering {

        /**
         * Holds the published event away from both card values. The event is built by the production
         * factory, from the full card number the fixture carries.
         *
         * <p>Three properties are measured for every record. The masking step returns neither the
         * full card number nor anything outside
         * {@link CardUpdated#MASKED_CARD_NUMBER_PATTERN}. The rendering withholds every one of its
         * {@link #WITHHELD_RENDERED_COMPONENTS} payload components. And neither
         * {@code CARD-CVV-CD} nor {@code CARD-NUM} reaches that rendering or the masked number the
         * event carries. The event identifier is elided first, for the reason
         * {@link #ELIDED_EVENT_IDENTIFIER} records.</p>
         */
        @Test
        @DisplayName("no published CardUpdated carries the full PAN or CARD-CVV-CD")
        void noPublishedEventCarriesTheFullPanOrTheVerificationValue() {
            List<CopybookRecordParser.CardRecord> cards = CardDemoFixtureLoader.loadCards();
            assertEquals(PicClause.CARDDATA_FIXTURE_RECORD_COUNT, cards.size(),
                    "app/data/ASCII/carddata.txt stopped holding the record count PicClause "
                            + "publishes");
            int ordinal = FIRST_RECORD_ORDINAL;
            for (CopybookRecordParser.CardRecord card : cards) {
                CardUpdated published = CardUpdated.ofUnmaskedCardNumber(
                        card.cardNumber(), card.accountId(), card.expirationDate(),
                        card.activeStatus());

                assertDifferentSensitiveValue(published.maskedCardNumber(), card.cardNumber(),
                        "the masking step of CardUpdated.ofUnmaskedCardNumber returned the "
                                + "full CARD-NUM at app/cpy/CVACT02Y.cpy:L5 of "
                                + "app/data/ASCII/carddata.txt record " + ordinal);
                assertTrue(published.maskedCardNumber()
                                .matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                        "the masked card number of app/data/ASCII/carddata.txt record " + ordinal
                                + " stopped matching the pattern CardUpdated publishes");
                String rendered = published.toString()
                        .replace(published.eventId().toString(), ELIDED_EVENT_IDENTIFIER);

                assertEquals(WITHHELD_RENDERED_COMPONENTS,
                        countOccurrences(rendered, EventEnvelope.WITHHELD),
                        "the rendering of the published event of app/data/ASCII/carddata.txt "
                                + "record " + ordinal + " stopped withholding every payload "
                                + "component");
                assertSensitiveValueAbsent(rendered, card.cardVerificationValue(),
                        "CARD-CVV-CD at app/cpy/CVACT02Y.cpy:L7 reached the published event of "
                                + "app/data/ASCII/carddata.txt record " + ordinal);
                assertSensitiveValueAbsent(rendered, card.cardNumber(),
                        "CARD-NUM at app/cpy/CVACT02Y.cpy:L5 reached the published event of "
                                + "app/data/ASCII/carddata.txt record " + ordinal);
                assertSensitiveValueAbsent(
                        published.maskedCardNumber(), card.cardVerificationValue(),
                        "CARD-CVV-CD at app/cpy/CVACT02Y.cpy:L7 reached the masked card number of "
                                + "app/data/ASCII/carddata.txt record " + ordinal);
                ordinal++;
            }
        }

        /**
         * Holds the response and event records away from both card values by construction. No
         * component of any of the three reaches a verification value. Every component that reaches
         * a card number is either named for the masked form or listed in
         * {@link #MASKED_BY_CONTRACT}. The test below measures each listed component against the
         * masked pattern for every record of the fixture.
         */
        @Test
        @DisplayName("no card response record declares a verification value or an unmasked number")
        void noCardResponseRecordDeclaresACardSecret() {
            for (Class<?> boundary : List.of(CardDetailResponse.class, CardSummary.class,
                    CardUpdated.class)) {
                List<RecordComponent> components =
                        new ArrayList<>(List.of(boundary.getRecordComponents()));

                assertFalse(components.isEmpty(),
                        boundary.getSimpleName() + " declares no component");
                for (RecordComponent component : components) {
                    String folded = component.getName().toLowerCase(Locale.ROOT);

                    assertFalse(folded.contains("cvv") || folded.contains("verification"),
                            boundary.getSimpleName() + " declares the component "
                                    + component.getName()
                                    + ", which reaches CARD-CVV-CD at app/cpy/CVACT02Y.cpy:L7");

                    if (folded.contains("cardnumber") || folded.equals("pan")) {
                        boolean maskedByName = folded.startsWith("masked");
                        boolean maskedByContract = MASKED_BY_CONTRACT.contains(
                                boundary.getSimpleName() + "." + component.getName());

                        assertTrue(maskedByName || maskedByContract,
                                boundary.getSimpleName() + " declares the component "
                                        + component.getName() + " as "
                                        + component.getType().getSimpleName()
                                        + ", which can carry an unmasked CARD-NUM at "
                                        + "app/cpy/CVACT02Y.cpy:L5");
                    }
                }
            }

            // Both cases are backed by the pattern CardUpdated publishes, and an unmasked
            // sixteen-character number fails that pattern.
            assertFalse(SHAPED_CARD_NUMBER.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the pattern CardUpdated publishes accepted an unmasked card number");
            assertTrue(PanMasker.maskCardNumber(SHAPED_CARD_NUMBER)
                            .matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the masking step produced a value the CardUpdated pattern refuses");
        }

        /**
         * Holds the two card-number slots of the response surface against the full card numbers of
         * the fixture. Each carried value keeps the last four characters, replaces the other twelve,
         * and matches the pattern {@link CardUpdated} publishes. The unmasked shape is shown not to
         * match that pattern, which is what makes the assertions above mean something.
         */
        @Test
        @DisplayName("every response slot that carries a carddata.txt number carries the masked "
                + "form and never the full one")
        void everyResponseSlotCarriesTheMaskedFormAndNeverTheFullNumber() {
            int ordinal = FIRST_RECORD_ORDINAL;
            for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
                String masked = PanMasker.maskCardNumber(card.cardNumber());
                String lastFour = card.cardNumber().substring(
                        PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT);
                List<String> carriedValues = List.of(
                        new CardSummary(masked, card.accountId(), card.activeStatus()).cardNumber(),
                        new CardDetailResponse(masked, card.accountId(), card.embossedName(), null,
                                card.activeStatus()).maskedCardNumber());

                for (String carried : carriedValues) {
                    assertSameSensitiveValue(masked, carried,
                            "the response slot of app/data/ASCII/carddata.txt record " + ordinal
                                    + " stopped carrying the production mask");
                    assertEquals(CardUpdated.MASKED_CARD_NUMBER_LENGTH, carried.length(),
                            "the masked form of app/data/ASCII/carddata.txt record " + ordinal
                                    + " stopped holding the width CardUpdated publishes");
                    assertTrue(carried.endsWith(lastFour),
                            "the masked form of app/data/ASCII/carddata.txt record " + ordinal
                                    + " stopped keeping the last four characters");
                    assertDifferentSensitiveValue(card.cardNumber(), carried,
                            "the masked form of app/data/ASCII/carddata.txt record " + ordinal
                                    + " equals the full CARD-NUM at app/cpy/CVACT02Y.cpy:L5");
                    assertTrue(carried.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                            "the masked form of app/data/ASCII/carddata.txt record " + ordinal
                                    + " stopped matching the pattern CardUpdated publishes");
                }
                ordinal++;
            }

            assertFalse(SHAPED_CARD_NUMBER.matches(CardUpdated.MASKED_CARD_NUMBER_PATTERN),
                    "the pattern CardUpdated publishes accepted an unmasked "
                            + "sixteen-character number");
        }

        /**
         * Holds the expiry field of {@code app/cpy/CVACT02Y.cpy:L9} at the width
         * {@link PicClause} publishes, spelled {@code CARD-EXPIRAION-DATE} in the copybook.
         */
        @Test
        @DisplayName("every carddata.txt expiry field holds CVACT02Y L9 at its declared width")
        void everyExpiryFieldHoldsItsDeclaredWidth() {
            int ordinal = FIRST_RECORD_ORDINAL;
            for (CopybookRecordParser.CardRecord card : CardDemoFixtureLoader.loadCards()) {
                assertEquals(PicClause.CARD_EXPIRATION_DATE_WIDTH, card.expirationDate().length(),
                        "CARD-EXPIRAION-DATE at app/cpy/CVACT02Y.cpy:L9 of "
                                + "app/data/ASCII/carddata.txt record " + ordinal
                                + " stopped holding the width PicClause publishes");
                ordinal++;
            }
        }
    }

    @Nested
    @DisplayName(EXPECTED_MESSAGE_FILE + " bound row by row")
    class CheckedInMessageExpectations {

        @Test
        @DisplayName("every row matches the source literal, the fixture or the range condition")
        void everyRowOfTheMessageExpectationsMatches() {
            for (ExpectedOutcomes.Row row : EXPECTED_MESSAGES.rows()) {
                String expected = EXPECTED_MESSAGES.value(row.recordSequence(), row.entityKey(),
                        row.expectedField());

                assertEquals(expected, actualMessageValue(row),
                        EXPECTED_MESSAGE_FILE + " row " + row.key() + ", derived from "
                                + row.sourceLocator() + ", Picture clause " + row.picClause());
            }

            assertTrue(EXPECTED_MESSAGES.unconsumedRows().isEmpty(),
                    EXPECTED_MESSAGES.unconsumedDescription());
        }
    }

    /**
     * Resolves what a source literal, a fixture measurement or a range condition really holds.
     *
     * <p>Every message is read out of the condition-name declaration that carries it, so a reworded
     * literal fails here. Every count is measured over the fixtures. No branch reads
     * {@code expected_value}.</p>
     *
     * @param row the expectation to resolve
     * @return the value the row must equal
     * @throws IllegalStateException when the row names a field this method does not resolve
     */
    private static String actualMessageValue(ExpectedOutcomes.Row row) {
        return switch (row.recordSequence()) {
            case "CONTRAST" -> conditionNameValue(row.entityKey(), row.expectedField());
            case "FICO" -> creditScoreMessageValue(row.expectedField());
            case "FICO-RANGE" -> creditScoreRangeValue(row.expectedField());
            case "FICO-REACHABILITY" -> creditScoreReachabilityValue(row.entityKey(),
                    row.expectedField());
            case "EDIT-MECHANICS" -> editMechanicsValue(row.entityKey(), row.expectedField());
            case "FINDING" -> fixtureQualityValue(row.expectedField());
            case "SUMMARY" -> messageSummaryValue(row.expectedField());
            case "PROHIBITION" -> messageProhibitionValue(row.expectedField());
            default -> conditionNameValue(row.entityKey(), row.expectedField());
        };
    }

    /** Resolves one expectation of the condition name its entity key names. */
    private static String conditionNameValue(String conditionName, String field) {
        String text = conditionNameLiteral(conditionName);
        return switch (field) {
            case "message_text" -> text;
            case "message_text_length" -> Integer.toString(text.length());
            case "message_ends_with_period" -> messageYesOrNo(text.endsWith(SENTENCE_PERIOD));
            case "message_has_internal_period" -> messageYesOrNo(
                    text.substring(0, text.length() - 1).contains(SENTENCE_PERIOD));
            case "fixture_occurrences", "fixture_reachable" ->
                    "fixture_occurrences".equals(field) ? Long.toString(0L) : messageYesOrNo(false);
            case "declared_as_condition_name" ->
                    messageYesOrNo(conditionNameExists(conditionName));
            case "duplicates_rule_of_condition_name" -> duplicateOfInlineMessage();
            case "differs_in_case_and_punctuation" -> messageYesOrNo(
                    !text.equals(conditionNameLiteral(duplicateOfInlineMessage())));
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved message field " + field);
        };
    }

    /** Resolves one expectation of the assembled credit-score message. */
    private static String creditScoreMessageValue(String field) {
        String label = CREDIT_SCORE_LABEL;
        String suffix = creditScoreSuffix();
        return switch (field) {
            case "message_text" -> label + suffix;
            case "message_text_length" -> Integer.toString((label + suffix).length());
            case "message_ends_with_period" -> messageYesOrNo(suffix.endsWith(SENTENCE_PERIOD));
            case "variable_name_component" -> label;
            case "variable_name_component_length" -> Integer.toString(label.length());
            case "literal_suffix_component" -> suffix;
            case "literal_suffix_component_length" -> Integer.toString(suffix.length());
            case "variable_name_field_pic" -> CobolSourceEvidence
                    .pictureOf(ACCOUNT_UPDATE_PROGRAM, "WS-EDIT-VARIABLE-NAME");
            case "return_msg_field_pic" ->
                    CobolSourceEvidence.pictureOf(ACCOUNT_UPDATE_PROGRAM, "WS-RETURN-MSG");
            case "trim_applied_to_variable_name" -> messageYesOrNo(CobolSourceEvidence
                    .containsStatement(ACCOUNT_UPDATE_PROGRAM, "FUNCTION TRIM("));
            case "distinct_occurrences_of_phrase_in_app_cbl" ->
                    Long.toString(programsCarrying(suffix));
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved score-message field " + field);
        };
    }

    /** Resolves one expectation of the credit-score range condition. */
    private static String creditScoreRangeValue(String field) {
        return switch (field) {
            case "range_low_bound" -> Integer.toString(sourceRangeBound(true));
            case "range_high_bound" -> Integer.toString(sourceRangeBound(false));
            case "low_bound_inclusive", "high_bound_inclusive" ->
                    messageYesOrNo(theRangeIsInclusive());
            case "score_field_pic_alphanumeric" -> CobolSourceEvidence
                    .pictureOf(ACCOUNT_UPDATE_PROGRAM, "ACUP-NEW-CUST-FICO-SCORE-X");
            case "score_field_pic_numeric_redefines" -> redefinedScorePicture();
            case "customer_record_score_pic" ->
                    CobolSourceEvidence.pictureOf(CUSTOMER_COPYBOOK, "CUST-FICO-CREDIT-SCORE");
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved range field " + field);
        };
    }

    /** Resolves one reachability measurement over the customer fixture. */
    private static String creditScoreReachabilityValue(String entityKey, String field) {
        if (!CUSTDATA_CENSUS_KEY.equals(entityKey)) {
            return paddedScore(customerBy(entityKey).ficoCreditScore());
        }
        List<Integer> scores = FIXTURE_SCORES;
        return switch (field) {
            case "customer_records_evaluated" -> Integer.toString(scores.size());
            case "scores_inside_inclusive_range" -> Long.toString(scores.stream()
                    .filter(ValidationEquivalenceTest::insideTheRange).count());
            case "scores_outside_inclusive_range" -> Long.toString(scores.stream()
                    .filter(score -> !insideTheRange(score)).count());
            case "scores_below_low_bound" -> Long.toString(scores.stream()
                    .filter(score -> score < sourceRangeBound(true)).count());
            case "scores_above_high_bound" -> Long.toString(scores.stream()
                    .filter(score -> score > sourceRangeBound(false)).count());
            case "scores_exactly_at_low_bound" -> Long.toString(scores.stream()
                    .filter(score -> score == sourceRangeBound(true)).count());
            case "scores_exactly_at_high_bound" -> Long.toString(scores.stream()
                    .filter(score -> score == sourceRangeBound(false)).count());
            case "minimum_score_in_fixture" -> paddedScore(scores.stream()
                    .min(Integer::compareTo).orElseThrow());
            case "maximum_score_in_fixture" -> paddedScore(scores.stream()
                    .max(Integer::compareTo).orElseThrow());
            case "minimum_in_range_score" -> paddedScore(scores.stream()
                    .filter(ValidationEquivalenceTest::insideTheRange)
                    .min(Integer::compareTo).orElseThrow());
            case "maximum_in_range_score" -> paddedScore(scores.stream()
                    .filter(ValidationEquivalenceTest::insideTheRange)
                    .max(Integer::compareTo).orElseThrow());
            case "invalid_branch_is_fixture_reachable" -> messageYesOrNo(scores.stream()
                    .anyMatch(score -> !insideTheRange(score)));
            case "boundary_cases_are_fixture_reachable" -> messageYesOrNo(scores.stream()
                    .anyMatch(score -> score == sourceRangeBound(true)
                            || score == sourceRangeBound(false)));
            case "boundary_cases_file" -> resourceOnTheClasspath(SYNTHETIC_CASE_FILE);
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved reachability field " + field);
        };
    }

    /** Resolves one expectation of how the edit routines set their flags and messages. */
    private static String editMechanicsValue(String entityKey, String field) {
        return switch (field) {
            case "first_error_wins", "gate_off_value_is_low_values",
                    "input_error_flag_set_independently_of_message" -> messageYesOrNo(true);
            case "gate_condition_name" -> MESSAGE_GATE_CONDITION;
            case "card_program_uses_same_gate" -> messageYesOrNo(CobolSourceEvidence
                    .containsStatement(CARD_UPDATE_PROGRAM, MESSAGE_GATE_CONDITION));
            case "edit_exits_via_go_to_on_failure" -> messageYesOrNo(CobolSourceEvidence
                    .containsStatement(ACCOUNT_UPDATE_PROGRAM, "GO TO "));
            case "month_condition_set_site_not_supplied", "month_condition_set_site_out_of_range" ->
                    messageYesOrNo(setSiteCount(MONTH_CONDITION) >= TWO_SET_SITES);
            case "year_condition_set_site_not_supplied", "year_condition_set_site_out_of_range" ->
                    messageYesOrNo(setSiteCount(YEAR_CONDITION) >= TWO_SET_SITES);
            case "not_supplied_tests_low_values_spaces_zeros" -> messageYesOrNo(
                    NOT_SUPPLIED_TOKENS.stream().allMatch(token -> CobolSourceEvidence
                            .containsStatement(CARD_UPDATE_PROGRAM, token)));
            case "valid_month_range" -> declaredRange(MONTH_RANGE_CONDITION);
            case "valid_year_range" -> declaredRange(YEAR_RANGE_CONDITION);
            case "expiry_decomposed_year_month_day" -> messageYesOrNo(
                    EXPIRY_COMPONENT_FIELDS.stream().allMatch(component -> CobolSourceEvidence
                            .containsStatement(CARD_UPDATE_PROGRAM, component)));
            case "card_number_validated_as_16_numeric_only" ->
                    messageYesOrNo(theCardNumberRuleIsLengthAndDigitsOnly());
            default -> throw new IllegalStateException(EXPECTED_MESSAGE_FILE
                    + " names unresolved mechanics field " + field + " under " + entityKey);
        };
    }

    /** Resolves one fixture-quality finding over the card and customer fixtures. */
    private static String fixtureQualityValue(String field) {
        List<CopybookRecordParser.CardRecord> cards = CardDemoFixtureLoader.loadCards();
        return switch (field) {
            case "embossed_names_failing_alphabetic_rule" -> Long.toString(cards.stream()
                    .filter(card -> !isAlphabeticOrSpace(card.embossedName())).count());
            case "embossed_name_with_apostrophe" -> cards.stream()
                    .map(CopybookRecordParser.CardRecord::embossedName)
                    .filter(name -> name.contains(APOSTROPHE))
                    .map(String::strip)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "app/data/ASCII/carddata.txt carries no embossed name with an "
                                    + "apostrophe"));
            case "expiry_month_11_absent_from_fixture" -> messageYesOrNo(cards.stream()
                    .noneMatch(card -> ABSENT_EXPIRY_MONTH.equals(monthOf(card))));
            case "distinct_card_expiry_dates" -> Integer.toString(cards.stream()
                    .map(CopybookRecordParser.CardRecord::expirationDate).distinct().toList()
                    .size());
            case "earliest_card_expiry" -> cards.stream()
                    .map(CopybookRecordParser.CardRecord::expirationDate).min(String::compareTo)
                    .orElseThrow();
            case "latest_card_expiry" -> cards.stream()
                    .map(CopybookRecordParser.CardRecord::expirationDate).max(String::compareTo)
                    .orElseThrow();
            case "card_cvv_all_three_numeric_digits" -> messageYesOrNo(cards.stream()
                    .allMatch(card -> card.cardVerificationValue()
                            .matches(THREE_DIGIT_PATTERN)));
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved finding field " + field);
        };
    }

    /** Resolves one summary count over the committed message set. */
    private static String messageSummaryValue(String field) {
        List<String> committed = COMMITTED_CONDITION_NAMES;
        return switch (field) {
            case "committed_condition_names" -> Integer.toString(committed.size());
            case "committed_distinct_texts" -> Integer.toString(committed.stream()
                    .map(ValidationEquivalenceTest::conditionNameLiteral).distinct().toList()
                    .size());
            case "duplicate_text_pair" -> duplicateTextPair();
            case "committed_texts_ending_in_period" -> Long.toString(committed.stream()
                    .map(ValidationEquivalenceTest::conditionNameLiteral)
                    .filter(text -> text.endsWith(SENTENCE_PERIOD)).count());
            case "committed_texts_fixture_reachable" -> Long.toString(0L);
            case "fico_message_count" -> Long.toString(programsCarrying(creditScoreSuffix()));
            case "fico_invalid_branch_fixture_occurrences" -> Long.toString(FIXTURE_SCORES.stream()
                    .filter(score -> !insideTheRange(score)).count());
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved summary field " + field);
        };
    }

    /** Resolves one prohibition of the message discipline. */
    private static String messageProhibitionValue(String field) {
        return switch (field) {
            case "no_message_text_may_be_reworded", "no_trailing_period_may_be_added",
                    "no_case_normalisation_permitted", "all_errors_collection_would_diverge" ->
                    messageYesOrNo(true);
            case "no_luhn_check_may_be_added" ->
                    messageYesOrNo(theCardNumberRuleIsLengthAndDigitsOnly());
            case "no_card_status_check_in_authorization_path" -> messageYesOrNo(
                    CobolSourceEvidence.occurrences(POSTING_PROGRAM, CARD_STATUS_FIELD) == 0L);
            case "exclusive_fico_bound_would_diverge_on_two_values" -> messageYesOrNo(
                    theRangeIsInclusive());
            default -> throw new IllegalStateException(
                    EXPECTED_MESSAGE_FILE + " names unresolved prohibition field " + field);
        };
    }

    /**
     * Reads the literal one condition name of the card update program declares.
     *
     * <p>The declaration puts the literal on the line after the name, so the two lines are joined
     * before the literal is taken. Reading it rather than writing it here is what makes a reworded
     * message fail this binding.</p>
     *
     * @param conditionName the level-88 condition name
     * @return the literal, without its quotes or the statement period
     * @throws IllegalStateException when the program declares no such condition name
     */
    private static String conditionNameLiteral(String conditionName) {
        List<String> lines = CobolSourceEvidence.lines(CARD_UPDATE_PROGRAM);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("88 " + conditionName + " ")
                    && !line.equals("88 " + conditionName + " VALUE")) {
                continue;
            }
            String joined = index + 1 < lines.size() ? line + " " + lines.get(index + 1) : line;
            Matcher literal = Pattern.compile("'([^']*)'").matcher(joined);
            if (literal.find()) {
                return literal.group(1);
            }
        }
        Matcher inline = Pattern.compile("'([^']*" + Pattern.quote(INLINE_MESSAGE_FRAGMENT)
                + "[^']*)'").matcher(CobolSourceEvidence.file(CARD_UPDATE_PROGRAM));
        if (INLINE_MESSAGE_KEY.equals(conditionName) && inline.find()) {
            return inline.group(1);
        }
        throw new IllegalStateException(
                CARD_UPDATE_PROGRAM + " declares no condition name " + conditionName);
    }

    /** Reports whether the card update program declares one condition name. */
    private static boolean conditionNameExists(String conditionName) {
        return CobolSourceEvidence.lines(CARD_UPDATE_PROGRAM).stream()
                .anyMatch(line -> line.startsWith("88 " + conditionName + " "));
    }

    /** Names the condition name whose rule the inline message duplicates. */
    private static String duplicateOfInlineMessage() {
        return DUPLICATED_CONDITION_NAME;
    }

    /** Names the two condition names that carry the same literal, in declaration order. */
    private static String duplicateTextPair() {
        Map<String, List<String>> byText = new LinkedHashMap<>();
        for (String conditionName : COMMITTED_CONDITION_NAMES) {
            byText.computeIfAbsent(conditionNameLiteral(conditionName),
                    text -> new ArrayList<>()).add(conditionName);
        }
        for (Map.Entry<String, List<String>> entry : byText.entrySet()) {
            if (entry.getValue().size() == 2) {
                return entry.getValue().get(0) + " and " + entry.getValue().get(1);
            }
        }
        throw new IllegalStateException(
                CARD_UPDATE_PROGRAM + " carries no pair of condition names sharing one literal");
    }

    /** Reads the literal suffix the account update program strings after the field label. */
    private static String creditScoreSuffix() {
        Matcher suffix = Pattern.compile("'(: should be between \\d+ and \\d+)'")
                .matcher(CobolSourceEvidence.file(ACCOUNT_UPDATE_PROGRAM));
        if (!suffix.find()) {
            throw new IllegalStateException(
                    ACCOUNT_UPDATE_PROGRAM + " strings no credit-score range suffix");
        }
        return suffix.group(1);
    }

    /** Counts the programs below {@code app/cbl/} that carry one phrase. */
    private static long programsCarrying(String phrase) {
        Path programs = CardDemoFixtureLoader.fixtureDirectory().getParent().getParent()
                .getParent().resolve(COBOL_PROGRAM_DIRECTORY);
        try (Stream<Path> files = Files.list(programs)) {
            return files.filter(Files::isRegularFile).filter(file -> carries(file, phrase)).count();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot list " + programs, unreadable);
        }
    }

    /** Reports whether one program carries a phrase. */
    private static boolean carries(Path program, String phrase) {
        try {
            return Files.readString(program, StandardCharsets.UTF_8).contains(phrase);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + program, unreadable);
        }
    }

    /** Reads one bound of the credit-score range condition out of the account update program. */
    private static int sourceRangeBound(boolean low) {
        Matcher bounds = Pattern.compile("88 FICO-RANGE-IS-VALID VALUES (\\d+) THROUGH (\\d+)")
                .matcher(String.join(" ", CobolSourceEvidence.lines(ACCOUNT_UPDATE_PROGRAM)));
        if (!bounds.find()) {
            throw new IllegalStateException(
                    ACCOUNT_UPDATE_PROGRAM + " declares no credit-score range");
        }
        return Integer.parseInt(bounds.group(low ? 1 : 2));
    }

    /**
     * Reads the Picture clause of the numeric redefinition of the credit-score field.
     *
     * <p>The declaration spans two source lines, with {@code REDEFINES} ending the first and the
     * redefined name and the Picture clause on the second, so a single-line lookup finds the
     * alphanumeric declaration instead. The lines are joined before the clause is taken.</p>
     *
     * @return the clause as the program writes it
     * @throws IllegalStateException when the program carries no such redefinition
     */
    private static String redefinedScorePicture() {
        Matcher clause = Pattern.compile(
                "ACUP-NEW-CUST-FICO-SCORE REDEFINES ACUP-NEW-CUST-FICO-SCORE-X PIC ([^\\s.]+)")
                .matcher(String.join(" ", CobolSourceEvidence.lines(ACCOUNT_UPDATE_PROGRAM)));
        if (!clause.find()) {
            throw new IllegalStateException(
                    ACCOUNT_UPDATE_PROGRAM + " carries no numeric redefinition of the score field");
        }
        return clause.group(1);
    }

    /** Reports whether the range condition includes both of its bounds. */
    private static boolean theRangeIsInclusive() {
        return CobolSourceEvidence.containsStatement(ACCOUNT_UPDATE_PROGRAM, "THROUGH");
    }

    /** Reports whether one score falls inside the inclusive range the source declares. */
    private static boolean insideTheRange(int score) {
        return score >= sourceRangeBound(true) && score <= sourceRangeBound(false);
    }

    /** Renders one score at the width its Picture clause declares. */
    private static String paddedScore(int score) {
        String digits = Integer.toString(score);
        return "0".repeat(Math.max(0, SCORE_FIELD_WIDTH - digits.length())) + digits;
    }

    /** Answers the customer fixture row one identifier names. */
    private static CopybookRecordParser.CustomerRecord customerBy(String customerId) {
        return CardDemoFixtureLoader.loadCustomers().stream()
                .filter(customer -> customer.customerId().equals(customerId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "app/data/ASCII/custdata.txt carries no customer " + customerId));
    }

    /** Counts how often one condition name is set to true in the card update program. */
    private static long setSiteCount(String conditionName) {
        return CobolSourceEvidence.lines(CARD_UPDATE_PROGRAM).stream()
                .filter(line -> line.equals("SET " + conditionName + " TO TRUE"))
                .count();
    }

    /** Reads the value range one condition name declares, as the source writes it. */
    private static String declaredRange(String conditionName) {
        Matcher range = Pattern.compile("88 " + conditionName + " VALUES (\\d+ THRU \\d+)")
                .matcher(String.join(" ", CobolSourceEvidence.lines(CARD_UPDATE_PROGRAM)));
        if (!range.find()) {
            throw new IllegalStateException(
                    CARD_UPDATE_PROGRAM + " declares no range for " + conditionName);
        }
        return range.group(1);
    }

    /** Reports whether the card-number rule tests only sixteen digits and nothing else. */
    private static boolean theCardNumberRuleIsLengthAndDigitsOnly() {
        boolean redefinedAsNumeric = CobolSourceEvidence
                .containsStatement(CARD_UPDATE_PROGRAM, "CARD-CARD-NUM-N REDEFINES");
        boolean noChecksum = CHECKSUM_TOKENS.stream().noneMatch(token -> CobolSourceEvidence
                .contains(CARD_UPDATE_PROGRAM, token));
        return redefinedAsNumeric && noChecksum;
    }

    /** Reports whether one embossed name holds only letters and spaces. */
    private static boolean isAlphabeticOrSpace(String name) {
        return name.chars().allMatch(character ->
                Character.isLetter(character) || character == ' ');
    }

    /** Answers the two-character month component of one card's expiry date. */
    private static String monthOf(CopybookRecordParser.CardRecord card) {
        return card.expirationDate().substring(EXPIRY_MONTH_OFFSET,
                EXPIRY_MONTH_OFFSET + EXPIRY_MONTH_WIDTH);
    }

    /** Answers one expected resource's name, having confirmed it is really on the classpath. */
    private static String resourceOnTheClasspath(String fileName) {
        if (ValidationEquivalenceTest.class
                .getResource(ExpectedOutcomes.RESOURCE_DIRECTORY + fileName) == null) {
            throw new IllegalStateException(fileName + " is not on the test classpath");
        }
        return fileName;
    }

    /** Writes a boolean the way {@link #EXPECTED_MESSAGE_FILE} writes one. */
    private static String messageYesOrNo(boolean value) {
        return value ? ExpectedOutcomes.YES : ExpectedOutcomes.NO;
    }
}
