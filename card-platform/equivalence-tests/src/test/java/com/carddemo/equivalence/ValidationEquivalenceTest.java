package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.domain.validation.CreditScoreRangeValidator;
import com.carddemo.account.domain.validation.EditResult;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.cobol.PicClause;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * <p>Every comparison is exact string equality and no failure text carries a fixture value.
 * {@code card-platform/docs/business-rule-flags.md} registers the three account-number texts.</p>
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
     * ADDITIVE. Constructed score one step below the lower bound. No record of
     * {@code app/data/ASCII/custdata.txt} holds it.
     */
    private static final int SCORE_BELOW_LOWER_BOUND =
            LOWEST_PASSING_SCORE - ONE_STEP_OUTSIDE_THE_RANGE;

    /**
     * ADDITIVE. Constructed score one step above the upper bound. No record of
     * {@code app/data/ASCII/custdata.txt} holds it.
     */
    private static final int SCORE_ABOVE_UPPER_BOUND =
            HIGHEST_PASSING_SCORE + ONE_STEP_OUTSIDE_THE_RANGE;

    /**
     * ADDITIVE. Constructed score whose leading character is a zero. The {@code PIC 9(03)} redefine
     * at {@code app/cbl/COACTUPC.cbl:L846-L847} reads the three characters in place.
     */
    private static final String SCORE_WITH_A_LEADING_ZERO = "030";

    /**
     * ADDITIVE. Constructed score narrower than
     * {@link PicClause#CUST_FICO_CREDIT_SCORE_WIDTH}.
     */
    private static final String SCORE_OF_ANOTHER_WIDTH = "50";

    /** ADDITIVE. Constructed score holding one character outside the digit class. */
    private static final String SCORE_WITH_A_NON_DIGIT = "3x0";

    /** Label {@code 'FICO Score'} moved at {@code app/cbl/COACTUPC.cbl:L1545}. */
    private static final String CREDIT_SCORE_LABEL = "FICO Score";

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

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits below the lower bound. */
    private static final int CUSTDATA_SCORES_BELOW_LOWER_BOUND = 21;

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits inside the range. */
    private static final int CUSTDATA_SCORES_INSIDE_THE_RANGE = 29;

    /** Records of {@code app/data/ASCII/custdata.txt} whose score sits above the upper bound. */
    private static final int CUSTDATA_SCORES_ABOVE_UPPER_BOUND = 0;

    /**
     * Component label {@link CopybookRecordParser.CardRecord} renders ahead of
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    private static final String VERIFICATION_VALUE_LABEL = "cardVerificationValue=";

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
     * Reports whether a text holds at least one digit.
     *
     * @param candidate the text to scan
     * @return true when one character of {@code candidate} belongs to the digit class
     */
    private static boolean holdsADigit(String candidate) {
        for (int position = 0; position < candidate.length(); position++) {
            if (Character.isDigit(candidate.charAt(position))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the characters a card rendering places after
     * {@link #VERIFICATION_VALUE_LABEL}, at the width of the field they stand for.
     *
     * @param rendering one {@link CopybookRecordParser.CardRecord} rendering
     * @return the characters standing in for {@code CARD-CVV-CD}
     */
    private static String renderedVerificationValue(String rendering) {
        int labelPosition = rendering.indexOf(VERIFICATION_VALUE_LABEL);
        assertTrue(labelPosition >= 0, "a CardRecord rendering carried no '"
                + VERIFICATION_VALUE_LABEL + "' label, so CARD-CVV-CD at "
                + "app/cpy/CVACT02Y.cpy:L7 could not be located in it");
        int valuePosition = labelPosition + VERIFICATION_VALUE_LABEL.length();
        return rendering.substring(valuePosition,
                valuePosition + PicClause.CARD_CVV_CD_WIDTH);
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
         * Derives the count of distinct band texts from the seven condition names and the one
         * repeat at L192.
         */
        @Test
        @DisplayName("the COCRDUPC L189-L202 band carries six distinct texts")
        void theBandCarriesSixDistinctTexts() {
            Set<String> distinctTexts = new LinkedHashSet<>(BAND_TEXTS_IN_SOURCE_ORDER);
            assertEquals(BAND_TEXTS_IN_SOURCE_ORDER.size() - BAND_REPEATED_TEXT_COUNT,
                    distinctTexts.size(),
                    "the account-and-card band of app/cbl/COCRDUPC.cbl:L189-L202 stopped carrying "
                            + "the distinct-text count its seven condition names and its one "
                            + "repeat at L192 give");
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

        /**
         * Holds the three texts the two programs carry for one concept mutually distinct. One text
         * sits in the band and two sit in the account programs.
         */
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

        /** ADDITIVE. Holds the score one step below the lower bound outside the range. */
        @Test
        @DisplayName("a score one step below the lower bound fails")
        void aScoreOneStepBelowTheLowerBoundFails() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_BELOW_LOWER_BOUND));
            assertFalse(verdict.valid(), "a score one step below VALUES 300 at "
                    + "app/cbl/COACTUPC.cbl:L848 passed the range test at "
                    + "app/cbl/COACTUPC.cbl:L2515");
        }

        /** Holds the lower bound inside the range. */
        @Test
        @DisplayName("the lower bound of COACTUPC L848 passes")
        void theLowerBoundPasses() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(LOWEST_PASSING_SCORE));
            assertTrue(verdict.valid(), "VALUES 300 at app/cbl/COACTUPC.cbl:L848 failed the range "
                    + "test at app/cbl/COACTUPC.cbl:L2515, so the lower bound stopped being "
                    + "inclusive");
        }

        /** Holds the upper bound inside the range. */
        @Test
        @DisplayName("the upper bound of COACTUPC L849 passes")
        void theUpperBoundPasses() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(HIGHEST_PASSING_SCORE));
            assertTrue(verdict.valid(), "THROUGH 850 at app/cbl/COACTUPC.cbl:L849 failed the range "
                    + "test at app/cbl/COACTUPC.cbl:L2515, so the upper bound stopped being "
                    + "inclusive");
        }

        /** ADDITIVE. Holds the score one step above the upper bound outside the range. */
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
         * ADDITIVE. Holds a leading zero read in place, which the {@code PIC 9(03)} redefine at
         * {@code app/cbl/COACTUPC.cbl:L846-L847} gives.
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
         * ADDITIVE. Holds a score outside the digit class inside the one message slot, with no
         * exception and no parse. {@code app/cbl/COACTUPC.cbl:L1549-L1550} runs the numeric edit
         * and {@code app/cbl/COACTUPC.cbl:L1553} gates the range paragraph on its verdict.
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

        /** ADDITIVE. Holds a score of another width inside the same single message slot. */
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

        /** Holds the failing verdict reporting that its slot holds text. */
        @Test
        @DisplayName("a failing score reports that the message slot holds text")
        void aFailingScoreReportsThatTheSlotHoldsText() {
            EditResult verdict = CreditScoreRangeValidator.validate(CREDIT_SCORE_LABEL,
                    scoreText(SCORE_ABOVE_UPPER_BOUND));
            assertTrue(verdict.hasMessage(), "a failing score left WS-RETURN-MSG at "
                    + "app/cbl/COACTUPC.cbl:L479 in the all-spaces state its condition name at "
                    + "app/cbl/COACTUPC.cbl:L480 names");
        }

        /** Holds a passing verdict free of text, which leaves the slot open for a later edit. */
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

        /** Holds the assembled text inside the width of the slot it is written into. */
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
     * Holds {@code CARD-CVV-CD} at {@code app/cpy/CVACT02Y.cpy:L7} out of every rendering the card
     * fixture produces.
     */
    @Nested
    @DisplayName("carddata.txt renderings at CVACT02Y L4-L11")
    class CardFixtureRendering {

        /** Holds every rendered verification value away from the value the record carries. */
        @Test
        @DisplayName("no carddata.txt rendering carries CARD-CVV-CD at CVACT02Y L7")
        void noRenderingCarriesItsVerificationValue() {
            List<CopybookRecordParser.CardRecord> cards = CardDemoFixtureLoader.loadCards();
            assertEquals(PicClause.CARDDATA_FIXTURE_RECORD_COUNT, cards.size(),
                    "app/data/ASCII/carddata.txt stopped holding the record count PicClause "
                            + "publishes");
            int ordinal = FIRST_RECORD_ORDINAL;
            for (CopybookRecordParser.CardRecord card : cards) {
                String rendered = renderedVerificationValue(card.toString());
                assertFalse(rendered.equals(card.cardVerificationValue()),
                        "CARD-CVV-CD at app/cpy/CVACT02Y.cpy:L7 reached the rendering of "
                                + "app/data/ASCII/carddata.txt record " + ordinal);
                assertFalse(holdsADigit(rendered),
                        "the characters standing in for CARD-CVV-CD at app/cpy/CVACT02Y.cpy:L7 in "
                                + "app/data/ASCII/carddata.txt record " + ordinal
                                + " now hold a digit");
                ordinal++;
            }
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
}
