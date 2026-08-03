package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardValidationMessages}.
 *
 * <p>Each text test compares one constant against a literal typed in this file. The expected texts
 * come from the working-storage message field {@code WS-RETURN-MSG PIC X(75)} at
 * {@code app/cbl/COCRDUPC.cbl:L173} and from the two inline moves at lines 745 and 789. A text
 * belongs in the production class when its condition name sits under {@code WS-RETURN-MSG}.</p>
 *
 * <p>The tests below assert the text of each constant a caller can receive, and assert
 * that the whole reachable inventory of fifteen texts is declared. No test asserts how
 * many fields the production class declares, and no test asserts the text of a constant
 * runtime cannot reach. Removing an unreachable declaration is a behaviour-preserving
 * change, so the tests here do not stand in its way.</p>
 *
 * <p>Six declarations carry the {@code NEVER_EMITTED} prefix and hold five distinct
 * texts. Three of their condition names are declared and never set:
 * {@code SEARCHED-ACCT-ZEROES} at line 189, {@code SEARCHED-ACCT-NOT-NUMERIC} at line 191
 * and {@code SEARCHED-CARD-NOT-NUMERIC} at line 193. No {@code SET} site for them exists
 * in the twenty-eight programs under {@code app/cbl}. Lines 190 and 192 carry the same
 * characters under two of those names, and each line keeps its own constant.</p>
 *
 * <p>A fourth unreachable name, {@code DID-NOT-FIND-ACCT-IN-CARDXREF}, has one set site at
 * {@code app/cbl/COCRDSLC.cbl:L799}, inside paragraph {@code 9150-GETCARD-BYACCT}. That
 * paragraph spans lines 779 to 810 and no {@code PERFORM} names it.
 * {@code XREF-READ-ERROR} at line 212 and {@code CODING-TO-BE-DONE} at line 214 complete
 * the six. The property tests below still cover all six, because each iterates the
 * declared fields: every text fits the field width, no text names the card verification
 * value, and every field is public, static and final.</p>
 *
 * <p>Runtime reaches fifteen texts. Thirteen come from condition names with a reachable
 * {@code SET} site, among them line 731 and line 774, and two come from the moves at
 * lines 745 and 789, which each write an upper case literal and use no condition
 * name.</p>
 *
 * <p>Two groups of texts stay out of the production class, and two tests below hold those absences.
 * The six texts under {@code WS-INFO-MSG PIC X(40)} at {@code app/cbl/COCRDUPC.cbl:L157} sit under
 * a different field name. The exit text at lines 175 and 176 is screen navigation, dropped under
 * transformation rule T6 with the navigation fields at {@code app/cpy/CVCRD01Y.cpy:L21}, L23 and
 * L24.</p>
 *
 * <p>No text names the card verification value {@code CARD-CVV-CD PIC 9(03)} at
 * {@code app/cpy/CVACT02Y.cpy:L7}, and one test holds that line.</p>
 */
class CardValidationMessagesTest {

    /** Width of {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}. */
    private static final int MESSAGE_FIELD_WIDTH = 75;

    /**
     * Count of distinct texts runtime can reach. Thirteen condition names under
     * {@code WS-RETURN-MSG} have a reachable {@code SET} site, and the moves at
     * {@code app/cbl/COCRDUPC.cbl:L745} and line 789 write two more.
     */
    private static final int REACHABLE_TEXT_COUNT = 15;

    /** Name prefix that marks a constant carrying no source literal. */
    private static final String ADDITIVE_FIELD_PREFIX = "ADDITIVE_";

    /** Count of constants that carry no source literal. */
    private static final int ADDITIVE_TEXT_COUNT = 1;

    /**
     * The one additive text. It reports the transport width of the expiry day, a field the source
     * reads from a two-character map field at {@code app/cbl/COCRDUPC.cbl:L312} and edits nowhere.
     */
    private static final String EXPECTED_ADDITIVE_EXPIRY_DAY_WIDTH =
            "Card expiry day must be two digits";

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

    // Inventory of the texts runtime reaches. The set below names every text a caller can
    // receive, so a missing text or a typo fails this test. The set stays open at the
    // upper end: a text the production class declares and runtime never reaches passes
    // here, and removing such a declaration also passes.

    /**
     * Asserts that every text runtime can reach is declared, character for character.
     * Thirteen of the fifteen come from condition names under
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, and two come
     * from the inline moves at lines 745 and 789.
     *
     * <p>Each text is typed here and mapped to the constant that must hold it, so this
     * test names the constant that drifted rather than reporting a set difference.</p>
     */
    @Test
    void everyTextRuntimeReachesIsDeclaredCharacterForCharacter() {
        Map<String, String> reachable = new LinkedHashMap<>();
        reachable.put("PROMPT_FOR_ACCT", "Account number not provided");
        reachable.put("PROMPT_FOR_CARD", "Card number not provided");
        reachable.put("PROMPT_FOR_NAME", "Card name not provided");
        reachable.put("NAME_MUST_BE_ALPHA",
                "Card name can only contain alphabets and spaces");
        reachable.put("NO_SEARCH_CRITERIA_RECEIVED", "No input received");
        reachable.put("NO_CHANGES_DETECTED",
                "No change detected with respect to values fetched.");
        reachable.put("CARD_STATUS_MUST_BE_YES_NO", "Card Active Status must be Y or N");
        reachable.put("CARD_EXPIRY_MONTH_NOT_VALID",
                "Card expiry month must be between 1 and 12");
        reachable.put("CARD_EXPIRY_YEAR_NOT_VALID", "Invalid card expiry year");
        reachable.put("DID_NOT_FIND_ACCTCARD_COMBO",
                "Did not find cards for this search condition");
        reachable.put("COULD_NOT_LOCK_FOR_UPDATE", "Could not lock record for update");
        reachable.put("DATA_WAS_CHANGED_BEFORE_UPDATE",
                "Record changed by some one else. Please review");
        reachable.put("LOCKED_BUT_UPDATE_FAILED", "Update of record failed");
        reachable.put("ACCOUNT_FILTER_NOT_NUMERIC",
                "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        reachable.put("CARD_FILTER_NOT_NUMERIC",
                "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");

        assertEquals(REACHABLE_TEXT_COUNT, reachable.size(),
                "the set above names every text a caller can receive: 13 from the "
                        + "condition names set in app/cbl/COCRDUPC.cbl and 2 from the "
                        + "moves at lines 745 and 789");
        assertEquals(REACHABLE_TEXT_COUNT, Set.copyOf(reachable.values()).size(),
                "no two texts a caller can receive hold the same characters");

        Set<String> declared = messageTexts();
        for (Map.Entry<String, String> text : reachable.entrySet()) {
            assertEquals(text.getValue(), constantValue(text.getKey()),
                    "CardValidationMessages." + text.getKey()
                            + " holds its source text character for character");
            assertTrue(declared.contains(text.getValue()),
                    "CardValidationMessages declares the text of " + text.getKey());
        }
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
     * Returns the text one named constant holds.
     *
     * @param name name of a {@code public static final String} field of
     *        {@link CardValidationMessages}
     * @return the text that field holds
     * @throws AssertionError when the class declares no field of that name, or when the
     *         field cannot be read
     */
    private static String constantValue(String name) {
        for (Field field : textFields()) {
            if (field.getName().equals(name)) {
                try {
                    return (String) field.get(null);
                } catch (IllegalAccessException unreadable) {
                    throw new AssertionError(
                            "CardValidationMessages." + name + " cannot be read",
                            unreadable);
                }
            }
        }
        throw new AssertionError("CardValidationMessages declares no field named " + name);
    }

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
     * Returns the distinct texts of the constants that carry a source literal, which is every
     * constant whose name does not open with {@value #ADDITIVE_FIELD_PREFIX}.
     */
    private static Set<String> sourceMessageTexts() {
        Set<String> texts = new LinkedHashSet<>();
        for (Field field : sourceTextFields()) {
            texts.add(readText(field));
        }
        return texts;
    }

    /**
     * Returns the distinct texts of the constants that carry no source literal, which is every
     * constant whose name opens with {@value #ADDITIVE_FIELD_PREFIX}.
     */
    private static Set<String> additiveMessageTexts() {
        Set<String> texts = new LinkedHashSet<>();
        for (Field field : textFields()) {
            if (field.getName().startsWith(ADDITIVE_FIELD_PREFIX)) {
                texts.add(readText(field));
            }
        }
        return texts;
    }

    /** Returns the text fields that carry a source literal. */
    private static List<Field> sourceTextFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : textFields()) {
            if (!field.getName().startsWith(ADDITIVE_FIELD_PREFIX)) {
                fields.add(field);
            }
        }
        return fields;
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
