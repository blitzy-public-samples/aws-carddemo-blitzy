package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardValidationMessages}.
 *
 * <p>Each text test compares one constant against a literal typed in this file. The
 * expected texts come from the working-storage message field
 * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173} and from the two
 * inline moves at lines 745 and 789. A text belongs in the production class when its
 * condition name sits under {@code WS-RETURN-MSG}.</p>
 *
 * <p>The production class declares twenty-one fields and holds twenty distinct texts.
 * Lines 190 and 192 carry the same characters under two condition names, and each line
 * has its own constant. Both counts are asserted below.</p>
 *
 * <p>Three condition names are declared and never set: {@code SEARCHED-ACCT-ZEROES} at
 * line 189, {@code SEARCHED-ACCT-NOT-NUMERIC} at line 191 and
 * {@code SEARCHED-CARD-NOT-NUMERIC} at line 193. No {@code SET} site for them exists in
 * the twenty-eight programs under {@code app/cbl}. Runtime writes four other texts: line
 * 731 and line 774 each set a condition name, and lines 745 and 789 each move an upper
 * case literal.</p>
 *
 * <p>A fourth name, {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, has one set site at
 * {@code app/cbl/COCRDSLC.cbl:L799}, inside paragraph {@code 9150-GETCARD-BYACCT}. That
 * paragraph spans lines 779 to 810 and no {@code PERFORM} names it.</p>
 *
 * <p>Two groups of texts stay out of the production class, and two tests below hold those
 * absences. The six texts under {@code WS-INFO-MSG PIC X(40)} at
 * {@code app/cbl/COCRDUPC.cbl:L157} sit under a different field name. The exit text at
 * lines 175 and 176 is screen navigation, dropped under transformation rule T6 with the
 * navigation fields at {@code app/cpy/CVCRD01Y.cpy:L21}, L23 and L24.</p>
 *
 * <p>No text names the card verification value {@code CARD-CVV-CD PIC 9(03)} at
 * {@code app/cpy/CVACT02Y.cpy:L7}, and one test holds that line.</p>
 *
 * <p>Rationale for the three deviations named above:
 * {@code card-platform/docs/decision-log.md}. The dead condition names and the duplicate
 * text: {@code card-platform/docs/business-rule-flags.md}. Source to target mapping:
 * {@code card-platform/docs/traceability-matrix.md}.</p>
 */
class CardValidationMessagesTest {

    /** Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}. */
    private static final int MESSAGE_FIELD_WIDTH = 75;

    /** Count of {@code public static final String} fields the production class declares. */
    private static final int DECLARED_FIELD_COUNT = 21;

    /** Count of distinct texts those fields hold. */
    private static final int DISTINCT_TEXT_COUNT = 20;

    /** Measured length of the two inline literals at lines 745 and 789. */
    private static final int INLINE_LITERAL_LENGTH = 52;

    // One test per text. Each expected value is typed here and compared for exact
    // equality against the constant.

    /** Asserts the text of {@code WS-PROMPT-FOR-ACCT} at {@code app/cbl/COCRDUPC.cbl:L178}. */
    @Test
    void promptForAcctMatchesLine178() {
        assertEquals("Account number not provided", CardValidationMessages.PROMPT_FOR_ACCT,
                "app/cbl/COCRDUPC.cbl:L178 reads: Account number not provided");
    }

    /** Asserts the text of {@code WS-PROMPT-FOR-CARD} at {@code app/cbl/COCRDUPC.cbl:L180}. */
    @Test
    void promptForCardMatchesLine180() {
        assertEquals("Card number not provided", CardValidationMessages.PROMPT_FOR_CARD,
                "app/cbl/COCRDUPC.cbl:L180 reads: Card number not provided");
    }

    /** Asserts the text of {@code WS-PROMPT-FOR-NAME} at {@code app/cbl/COCRDUPC.cbl:L182}. */
    @Test
    void promptForNameMatchesLine182() {
        assertEquals("Card name not provided", CardValidationMessages.PROMPT_FOR_NAME,
                "app/cbl/COCRDUPC.cbl:L182 reads: Card name not provided");
    }

    /**
     * Asserts the text of {@code WS-NAME-MUST-BE-ALPHA} at
     * {@code app/cbl/COCRDUPC.cbl:L184}. The source says alphabets, not letters.
     */
    @Test
    void nameMustBeAlphaMatchesLine184() {
        assertEquals("Card name can only contain alphabets and spaces",
                CardValidationMessages.NAME_MUST_BE_ALPHA,
                "app/cbl/COCRDUPC.cbl:L184 reads: "
                        + "Card name can only contain alphabets and spaces");
    }

    /**
     * Asserts the text of {@code NO-SEARCH-CRITERIA-RECEIVED} at
     * {@code app/cbl/COCRDUPC.cbl:L186}.
     */
    @Test
    void noSearchCriteriaReceivedMatchesLine186() {
        assertEquals("No input received", CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                "app/cbl/COCRDUPC.cbl:L186 reads: No input received");
    }

    /**
     * Asserts the text of {@code NO-CHANGES-DETECTED} at
     * {@code app/cbl/COCRDUPC.cbl:L188}, including its trailing full stop. The second
     * assertion holds the final character on its own.
     */
    @Test
    void noChangesDetectedKeepsItsTrailingFullStopFromLine188() {
        String actual = CardValidationMessages.NO_CHANGES_DETECTED;
        assertEquals("No change detected with respect to values fetched.", actual,
                "app/cbl/COCRDUPC.cbl:L188 reads: "
                        + "No change detected with respect to values fetched.");
        assertEquals('.', actual.charAt(actual.length() - 1),
                "app/cbl/COCRDUPC.cbl:L188 ends with a full stop");
    }

    /**
     * Asserts the two texts at {@code app/cbl/COCRDUPC.cbl:L190} and line 192. The
     * condition names differ, {@code SEARCHED-ACCT-ZEROES} and
     * {@code SEARCHED-ACCT-NOT-NUMERIC}, and the characters match.
     */
    @Test
    void bothAccountNumberTextsFromLines190And192AreByteIdentical() {
        String expected = "Account number must be a non zero 11 digit number";
        assertEquals(expected, CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES,
                "app/cbl/COCRDUPC.cbl:L190 reads: " + expected);
        assertEquals(expected, CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC,
                "app/cbl/COCRDUPC.cbl:L192 reads: " + expected);
        assertEquals(CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_ZEROES,
                CardValidationMessages.NEVER_EMITTED_SEARCHED_ACCT_NOT_NUMERIC,
                "app/cbl/COCRDUPC.cbl:L190 and line 192 hold the same characters");
    }

    /**
     * Asserts the text of {@code SEARCHED-CARD-NOT-NUMERIC} at
     * {@code app/cbl/COCRDUPC.cbl:L194}, cited at its declaration.
     */
    @Test
    void searchedCardNotNumericMatchesLine194() {
        assertEquals("Card number if supplied must be a 16 digit number",
                CardValidationMessages.NEVER_EMITTED_SEARCHED_CARD_NOT_NUMERIC,
                "app/cbl/COCRDUPC.cbl:L194 reads: "
                        + "Card number if supplied must be a 16 digit number");
    }

    /**
     * Asserts the text of {@code CARD-STATUS-MUST-BE-YES-NO} at
     * {@code app/cbl/COCRDUPC.cbl:L196}. The source capitalises Active Status inside the
     * sentence.
     */
    @Test
    void cardStatusMustBeYesNoKeepsCapitalisedActiveStatusFromLine196() {
        String actual = CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO;
        assertEquals("Card Active Status must be Y or N", actual,
                "app/cbl/COCRDUPC.cbl:L196 reads: Card Active Status must be Y or N");
        assertEquals("Card Active Status", actual.substring(0, 18),
                "app/cbl/COCRDUPC.cbl:L196 capitalises Active Status");
    }

    /**
     * Asserts the text of {@code CARD-EXPIRY-MONTH-NOT-VALID} at
     * {@code app/cbl/COCRDUPC.cbl:L198}.
     */
    @Test
    void cardExpiryMonthNotValidMatchesLine198() {
        assertEquals("Card expiry month must be between 1 and 12",
                CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L198 reads: "
                        + "Card expiry month must be between 1 and 12");
    }

    /**
     * Asserts the text of {@code CARD-EXPIRY-YEAR-NOT-VALID} at
     * {@code app/cbl/COCRDUPC.cbl:L200}.
     */
    @Test
    void cardExpiryYearNotValidMatchesLine200() {
        assertEquals("Invalid card expiry year",
                CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L200 reads: Invalid card expiry year");
    }

    /**
     * Asserts the text of {@code DID-NOT-FIND-ACCT-IN-CARDXREF} at
     * {@code app/cbl/COCRDUPC.cbl:L202}.
     */
    @Test
    void didNotFindAcctInCardxrefMatchesLine202() {
        assertEquals("Did not find this account in cards database",
                CardValidationMessages.NEVER_EMITTED_DID_NOT_FIND_ACCT_IN_CARDXREF,
                "app/cbl/COCRDUPC.cbl:L202 reads: "
                        + "Did not find this account in cards database");
    }

    /**
     * Asserts the text of {@code DID-NOT-FIND-ACCTCARD-COMBO} at
     * {@code app/cbl/COCRDUPC.cbl:L204}.
     */
    @Test
    void didNotFindAcctcardComboMatchesLine204() {
        assertEquals("Did not find cards for this search condition",
                CardValidationMessages.DID_NOT_FIND_ACCTCARD_COMBO,
                "app/cbl/COCRDUPC.cbl:L204 reads: "
                        + "Did not find cards for this search condition");
    }

    /**
     * Asserts the text of {@code COULD-NOT-LOCK-FOR-UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:L206}.
     */
    @Test
    void couldNotLockForUpdateMatchesLine206() {
        assertEquals("Could not lock record for update",
                CardValidationMessages.COULD_NOT_LOCK_FOR_UPDATE,
                "app/cbl/COCRDUPC.cbl:L206 reads: Could not lock record for update");
    }

    /**
     * Asserts the text of {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at
     * {@code app/cbl/COCRDUPC.cbl:L208}. The source spells some one as two words, and the
     * second assertion holds that spelling.
     */
    @Test
    void dataWasChangedBeforeUpdateSpellsSomeOneAsTwoWordsAtLine208() {
        String actual = CardValidationMessages.DATA_WAS_CHANGED_BEFORE_UPDATE;
        assertEquals("Record changed by some one else. Please review", actual,
                "app/cbl/COCRDUPC.cbl:L208 reads: "
                        + "Record changed by some one else. Please review");
        assertEquals("some one", actual.substring(18, 26),
                "app/cbl/COCRDUPC.cbl:L208 spells some one as two words");
    }

    /**
     * Asserts the text of {@code LOCKED-BUT-UPDATE-FAILED} at
     * {@code app/cbl/COCRDUPC.cbl:L210}.
     */
    @Test
    void lockedButUpdateFailedMatchesLine210() {
        assertEquals("Update of record failed",
                CardValidationMessages.LOCKED_BUT_UPDATE_FAILED,
                "app/cbl/COCRDUPC.cbl:L210 reads: Update of record failed");
    }

    /** Asserts the text of {@code XREF-READ-ERROR} at {@code app/cbl/COCRDUPC.cbl:L212}. */
    @Test
    void xrefReadErrorMatchesLine212() {
        assertEquals("Error reading Card Data File",
                CardValidationMessages.NEVER_EMITTED_XREF_READ_ERROR,
                "app/cbl/COCRDUPC.cbl:L212 reads: Error reading Card Data File");
    }

    /**
     * Asserts the text of {@code CODING-TO-BE-DONE} at
     * {@code app/cbl/COCRDUPC.cbl:L214}. Four full stops follow the word Good, and the
     * second assertion holds that run.
     */
    @Test
    void codingToBeDoneKeepsFourFullStopsFromLine214() {
        String actual = CardValidationMessages.NEVER_EMITTED_CODING_TO_BE_DONE;
        assertEquals("Looks Good.... so far", actual,
                "app/cbl/COCRDUPC.cbl:L214 reads: Looks Good.... so far");
        assertEquals("....", actual.substring(10, 14),
                "app/cbl/COCRDUPC.cbl:L214 carries four full stops after Good");
    }

    // The two inline moves. Lines 745 and 789 write an upper case literal and use
    // no condition name.

    /**
     * Asserts the account filter text at {@code app/cbl/COCRDUPC.cbl:L745}. The literal
     * measures fifty-two characters, carries no space after the comma, and reads
     * {@code A 11} before the closing words.
     */
    @Test
    void accountFilterTextFromLine745MeasuresFiftyTwoCharacters() {
        String actual = CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC;
        assertEquals("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", actual,
                "app/cbl/COCRDUPC.cbl:L745 reads: "
                        + "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertEquals(INLINE_LITERAL_LENGTH, actual.length(),
                "app/cbl/COCRDUPC.cbl:L745 measures 52 characters");
        assertEquals(actual.toUpperCase(Locale.ROOT), actual,
                "app/cbl/COCRDUPC.cbl:L745 holds an upper case literal");
        assertFalse(actual.contains(", "),
                "app/cbl/COCRDUPC.cbl:L745 carries no space after the comma");
        assertTrue(actual.endsWith("MUST BE A 11 DIGIT NUMBER"),
                "app/cbl/COCRDUPC.cbl:L745 reads A 11 before DIGIT NUMBER");
    }

    /**
     * Asserts the card filter text at {@code app/cbl/COCRDUPC.cbl:L789}. The literal
     * measures fifty-two characters, carries no space after the comma, and reads
     * {@code A 16} before the closing words.
     */
    @Test
    void cardFilterTextFromLine789MeasuresFiftyTwoCharacters() {
        String actual = CardValidationMessages.CARD_FILTER_NOT_NUMERIC;
        assertEquals("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", actual,
                "app/cbl/COCRDUPC.cbl:L789 reads: "
                        + "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertEquals(INLINE_LITERAL_LENGTH, actual.length(),
                "app/cbl/COCRDUPC.cbl:L789 measures 52 characters");
        assertEquals(actual.toUpperCase(Locale.ROOT), actual,
                "app/cbl/COCRDUPC.cbl:L789 holds an upper case literal");
        assertFalse(actual.contains(", "),
                "app/cbl/COCRDUPC.cbl:L789 carries no space after the comma");
        assertTrue(actual.endsWith("MUST BE A 16 DIGIT NUMBER"),
                "app/cbl/COCRDUPC.cbl:L789 reads A 16 before DIGIT NUMBER");
    }

    // Inventory. The distinct-text set catches a missing text, an extra text, or a typo.

    /**
     * Asserts the whole inventory. The production class holds twenty distinct texts, and
     * the set below names every one. A missing text, an extra text, or a changed
     * character fails this test.
     */
    @Test
    void distinctTextsNumberExactlyTwentyAndMatchTheSourceInventory() {
        Set<String> expected = Set.of(
                "Account number not provided",
                "Card number not provided",
                "Card name not provided",
                "Card name can only contain alphabets and spaces",
                "No input received",
                "No change detected with respect to values fetched.",
                "Account number must be a non zero 11 digit number",
                "Card number if supplied must be a 16 digit number",
                "Card Active Status must be Y or N",
                "Card expiry month must be between 1 and 12",
                "Invalid card expiry year",
                "Did not find this account in cards database",
                "Did not find cards for this search condition",
                "Could not lock record for update",
                "Record changed by some one else. Please review",
                "Update of record failed",
                "Error reading Card Data File",
                "Looks Good.... so far",
                "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER",
                "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        Set<String> actual = messageTexts();
        assertEquals(DISTINCT_TEXT_COUNT, actual.size(),
                "app/cbl/COCRDUPC.cbl supplies 20 distinct texts, 18 from the condition "
                        + "names at lines 178 to 214 and 2 from the moves at lines 745 and 789");
        assertEquals(expected, actual,
                "the distinct texts match the source inventory character for character");
    }

    /**
     * Asserts the declared field count. The production class holds twenty-one constants
     * for twenty texts. Lines 190 and 192 each keep their own constant.
     */
    @Test
    void classDeclaresTwentyOneTextFields() {
        assertEquals(DECLARED_FIELD_COUNT, declaredFields().size(),
                "CardValidationMessages declares 21 fields");
        assertEquals(DECLARED_FIELD_COUNT, textFields().size(),
                "every declared field of CardValidationMessages holds a String");
    }

    // Absences.

    /**
     * Asserts that no text of {@code WS-INFO-MSG} reaches the production class. That
     * field sits at {@code app/cbl/COCRDUPC.cbl:L157} and carries six texts at lines 161,
     * 163, 165, 167, 169 and 171.
     */
    @Test
    void noTextOfTheInfoMessageFieldAppears() {
        List<String> infoTexts = List.of(
                "Details of selected card shown above",
                "Please enter Account and Card Number",
                "Update card details presented above.",
                "Changes validated.Press F5 to save",
                "Changes committed to database",
                "Changes unsuccessful. Please try again");
        Set<String> texts = messageTexts();
        for (String infoText : infoTexts) {
            assertFalse(texts.contains(infoText),
                    "WS-INFO-MSG at app/cbl/COCRDUPC.cbl:L157 holds this text and "
                            + "CardValidationMessages does not: " + infoText);
        }
    }

    /**
     * Asserts that the exit text stays out of the production class.
     * {@code WS-EXIT-MESSAGE} at {@code app/cbl/COCRDUPC.cbl:L175} holds thirty-four
     * characters, twenty of text and fourteen trailing spaces. A second assertion covers
     * the trimmed form.
     */
    @Test
    void exitTextFromLine175IsAbsent() {
        String exitText = "PF03 pressed.Exiting              ";
        Set<String> texts = messageTexts();
        assertFalse(texts.contains(exitText),
                "WS-EXIT-MESSAGE at app/cbl/COCRDUPC.cbl:L175 is screen navigation and "
                        + "CardValidationMessages does not carry it");
        assertFalse(texts.contains(exitText.strip()),
                "the trimmed form of WS-EXIT-MESSAGE at app/cbl/COCRDUPC.cbl:L175 is "
                        + "absent too");
    }

    /**
     * Asserts that every text fits the field it comes from. {@code WS-RETURN-MSG} is
     * seventy-five characters wide at {@code app/cbl/COCRDUPC.cbl:L173}, and
     * {@code CCARD-RETURN-MSG} repeats that width at {@code app/cpy/CVCRD01Y.cpy:L29}.
     */
    @Test
    void everyTextFitsTheSeventyFiveCharacterField() {
        for (Field field : textFields()) {
            String text = readText(field);
            assertTrue(text.length() <= MESSAGE_FIELD_WIDTH,
                    "WS-RETURN-MSG at app/cbl/COCRDUPC.cbl:L173 holds 75 characters and "
                            + field.getName() + " holds " + text.length());
        }
    }

    /**
     * Asserts that no text names the card verification value. The card record declares
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}, and no message of
     * the card update program mentions that field.
     */
    @Test
    void noTextNamesTheCardVerificationValue() {
        for (String text : messageTexts()) {
            String folded = text.toLowerCase(Locale.ROOT);
            assertFalse(folded.contains("cvv"),
                    "no text of app/cbl/COCRDUPC.cbl names the card verification value: "
                            + text);
            assertFalse(folded.contains("verification"),
                    "no text of app/cbl/COCRDUPC.cbl names the card verification value: "
                            + text);
        }
    }

    // Shape of the class.

    /**
     * Asserts that {@code CardValidationMessages} and its fields carry no annotation. The
     * production class holds text and reaches no framework.
     */
    @Test
    void classAndFieldsCarryNoAnnotation() {
        assertEquals(0, CardValidationMessages.class.getAnnotations().length,
                "CardValidationMessages carries no annotation");
        assertEquals(0, CardValidationMessages.class.getDeclaredAnnotations().length,
                "CardValidationMessages declares no annotation");
        for (Field field : declaredFields()) {
            assertEquals(0, field.getAnnotations().length,
                    "field " + field.getName() + " carries no annotation");
        }
    }

    /**
     * Asserts that every field {@code CardValidationMessages} declares is a
     * {@code public static final String}.
     */
    @Test
    void everyDeclaredFieldIsPublicStaticFinalString() {
        for (Field field : declaredFields()) {
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isPublic(modifiers),
                    "field " + field.getName() + " is public");
            assertTrue(Modifier.isStatic(modifiers),
                    "field " + field.getName() + " is static");
            assertTrue(Modifier.isFinal(modifiers),
                    "field " + field.getName() + " is final");
            assertEquals(String.class, field.getType(),
                    "field " + field.getName() + " holds a String");
        }
    }

    /**
     * Asserts that {@code CardValidationMessages} is final and declares one private
     * constructor with no parameters. No caller creates an instance.
     */
    @Test
    void classIsFinalAndDeclaresOnePrivateConstructor() {
        assertTrue(Modifier.isFinal(CardValidationMessages.class.getModifiers()),
                "CardValidationMessages is final");
        Constructor<?>[] constructors = CardValidationMessages.class.getDeclaredConstructors();
        assertEquals(1, constructors.length,
                "CardValidationMessages declares one constructor");
        assertEquals(0, constructors[0].getParameterCount(),
                "the constructor of CardValidationMessages takes no parameter");
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()),
                "the constructor of CardValidationMessages is private");
    }

    // Reflection helpers.

    /**
     * Returns every field {@code CardValidationMessages} declares, minus the synthetic
     * fields some coverage tools add.
     */
    private static List<Field> declaredFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : CardValidationMessages.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Returns the declared fields that hold a {@code String}. */
    private static List<Field> textFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : declaredFields()) {
            if (field.getType() == String.class) {
                fields.add(field);
            }
        }
        return fields;
    }

    /** Returns the distinct texts the declared {@code String} fields hold. */
    private static Set<String> messageTexts() {
        Set<String> texts = new LinkedHashSet<>();
        for (Field field : textFields()) {
            texts.add(readText(field));
        }
        return texts;
    }

    /**
     * Reads one field. Every field is public and static on a public class in this
     * package, and the read never fails.
     */
    private static String readText(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException exception) {
            throw new AssertionError("field " + field.getName() + " is not readable",
                    exception);
        }
    }

}
