package com.carddemo.cobol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link PanMasker} hides a Primary Account Number (PAN) and never reveals a card
 * verification value (CVV).
 *
 * <p>ADDITIVE. {@code PanMasker} has no COBOL ancestor. No expected value in this class comes from
 * a source paragraph. The locators below are negative evidence: they record what the CardDemo
 * source does with a card number and a card verification value today.
 *
 * <p>The card record holds the card number as {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5} and the card verification value in the clear as
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. The card detail screen shows
 * all sixteen characters in an unprotected field: {@code ATTRB=(FSET,NORM,UNPROT)} at
 * {@code app/bms/COCRDSL.bms:L96} and {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L99}.
 * Card-number validation in the source is the numeric class test at
 * {@code app/cbl/COCRDUPC.cbl:L784} and nothing more. No test here checks a card number beyond
 * sixteen numeric digits.
 *
 * <p>Every test below asserts one of five properties. A masked value equals twelve mask characters
 * followed by the last four characters of its own argument. Every masked value is sixteen
 * characters wide, whatever the argument width. Every card verification value redacts to three
 * mask characters, and any method fed a value of card-verification-value width returns a value
 * holding no digit. No field of the class retains an argument. Masking is not injective, so no
 * inverse of it can exist.
 *
 * <p>No assertion description in this class concatenates an argument or a result. A failure reports
 * a case label and a width, because a security test that fails must not print the value it
 * protects.
 */
class PanMaskerTest {

    /** The stored width of a card number, from {@code CARD-NUM PIC X(16)}. */
    private static final int STORED_CARD_NUMBER_WIDTH = 16;

    /** The count of trailing characters a masked card number keeps. */
    private static final int VISIBLE_CHARACTER_COUNT = 4;

    /** The count of leading characters a masked card number hides. */
    private static final int HIDDEN_CHARACTER_COUNT = 12;

    /** The stored width of a card verification value, from {@code CARD-CVV-CD PIC 9(03)}. */
    private static final int STORED_VERIFICATION_VALUE_WIDTH = 3;

    /** Twelve mask characters, the fixed leading part of every masked card number. */
    private static final String MASK_PREFIX = "************";

    /** A card number at the stored width. */
    private static final String FULL_CARD_NUMBER = "4111222233337823";

    /** The masked form of {@link #FULL_CARD_NUMBER}: twelve mask characters and four digits. */
    private static final String MASKED_FULL_CARD_NUMBER = "************7823";

    /** A card number one character short of the stored width. */
    private static final String FIFTEEN_CHARACTER_CARD_NUMBER = "411122223333782";

    /** The masked form of {@link #FIFTEEN_CHARACTER_CARD_NUMBER}. */
    private static final String MASKED_FIFTEEN_CHARACTER_CARD_NUMBER = "************3782";

    /** A digit string one character longer than the stored width. */
    private static final String SEVENTEEN_CHARACTER_CARD_NUMBER = "41112222333378234";

    /** A digit string three characters longer than the stored width. */
    private static final String NINETEEN_CHARACTER_CARD_NUMBER = "4111222233337823456";

    /** A digit string twice the stored width. */
    private static final String THIRTY_TWO_CHARACTER_CARD_NUMBER =
            "41112222333378234111222233330199";

    /** Sixteen mask characters, the result for an argument of four characters or fewer. */
    private static final String FULLY_MASKED_CARD_NUMBER = "****************";

    /** A card verification value at the stored width. */
    private static final String CARD_VERIFICATION_VALUE = "451";

    /** Three mask characters, the complete result of a card verification value redaction. */
    private static final String EXPECTED_REDACTION = "***";

    /** The count of three-digit values a {@code PIC 9(03)} field can hold. */
    private static final int STORED_VERIFICATION_VALUE_COMBINATIONS = 1000;

    /**
     * Asserts that a masked card number carries twelve mask characters and the last four
     * characters of its argument. A card number padded to a fixed-width field yields the same
     * result.
     */
    @Test
    void maskCardNumberHidesAllButTheLastFourCharacters() {
        String masked = PanMasker.maskCardNumber(FULL_CARD_NUMBER);

        assertEquals(MASKED_FULL_CARD_NUMBER, masked);

        for (int position = 0; position < HIDDEN_CHARACTER_COUNT; position++) {
            assertEquals(PanMasker.MASK_CHARACTER, masked.charAt(position),
                    "a masked card number kept a hidden character at position " + position);
        }

        String lastFourOfArgument =
                FULL_CARD_NUMBER.substring(STORED_CARD_NUMBER_WIDTH - VISIBLE_CHARACTER_COUNT);
        assertEquals(lastFourOfArgument, masked.substring(HIDDEN_CHARACTER_COUNT));

        assertEquals(MASKED_FULL_CARD_NUMBER,
                PanMasker.maskCardNumber("  " + FULL_CARD_NUMBER + "  "));
    }

    /**
     * Asserts that every masked card number is sixteen characters wide, the width of
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. The arguments run from
     * {@code null} through twice the stored width.
     */
    @Test
    void maskCardNumberAlwaysReturnsSixteenCharacters() {
        for (String argument : maskingArguments()) {
            assertEquals(STORED_CARD_NUMBER_WIDTH, PanMasker.maskCardNumber(argument).length(),
                    "a masked card number changed width for " + describe(argument));
        }
    }

    /**
     * Asserts that an argument longer than the stored width normalizes to the stored width and
     * still reveals only its own last four characters.
     *
     * <p>The cases run one, three and sixteen characters past {@code CARD-NUM PIC X(16)}, and a
     * padded over-length case proves the strip happens before the last four are read.</p>
     */
    @Test
    void maskCardNumberNormalizesAnArgumentLongerThanTheStoredWidth() {
        String[] overLengthArguments = {
                SEVENTEEN_CHARACTER_CARD_NUMBER,
                NINETEEN_CHARACTER_CARD_NUMBER,
                THIRTY_TWO_CHARACTER_CARD_NUMBER,
                "  " + SEVENTEEN_CHARACTER_CARD_NUMBER + "  ",
        };

        for (String argument : overLengthArguments) {
            String stripped = argument.strip();
            assertTrue(stripped.length() > STORED_CARD_NUMBER_WIDTH,
                    "the case stopped exceeding the stored width: " + describe(argument));

            String masked = PanMasker.maskCardNumber(argument);
            String lastFour =
                    stripped.substring(stripped.length() - VISIBLE_CHARACTER_COUNT);

            assertEquals(MASK_PREFIX + lastFour, masked,
                    "an over-length card number stopped normalizing for " + describe(argument));
            assertEquals(STORED_CARD_NUMBER_WIDTH, masked.length(),
                    "an over-length masked card number changed width for " + describe(argument));
            assertEquals(HIDDEN_CHARACTER_COUNT,
                    masked.chars().filter(character -> character == PanMasker.MASK_CHARACTER)
                            .count(),
                    "an over-length masked card number changed its mask count for "
                            + describe(argument));
        }

        String twentyCharacterCardNumber = "55000000000000008234";
        assertNotEquals(SEVENTEEN_CHARACTER_CARD_NUMBER, twentyCharacterCardNumber,
                "the two over-length arguments stopped differing");
        assertEquals(PanMasker.maskCardNumber(SEVENTEEN_CHARACTER_CARD_NUMBER),
                PanMasker.maskCardNumber(twentyCharacterCardNumber),
                "two over-length arguments sharing a suffix stopped masking alike");
    }

    /**
     * Asserts that every value a {@code PIC 9(03)} field can hold redacts to exactly three mask
     * characters.
     *
     * <p>The loop covers all {@value #STORED_VERIFICATION_VALUE_COMBINATIONS} stored values, so
     * the assertion is exhaustive rather than a sample. No expected value names a digit, which is
     * what makes the oracle valid: a masked card number may legitimately end in any digit,
     * including a digit a card verification value also holds.</p>
     */
    @Test
    void everyStoredCardVerificationValueRedactsToThreeMaskCharacters() {
        for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
            String stored = String.format("%03d", value);

            String redacted = PanMasker.redactCardVerificationValue(stored);

            assertEquals(EXPECTED_REDACTION, redacted,
                    "a redaction changed for a stored value at index " + value);
            assertFalse(holdsAnyDigit(redacted),
                    "a redaction carried a digit for a stored value at index " + value);
        }

        assertEquals(EXPECTED_REDACTION, PanMasker.redactCardVerificationValue(null),
                "a null card verification value stopped redacting");
        assertEquals(EXPECTED_REDACTION, PanMasker.REDACTED_CARD_VERIFICATION_VALUE,
                "the published redaction constant changed");
    }

    /**
     * Asserts that a masked card number equals twelve mask characters followed by the last four
     * characters of its own argument, and holds no other digit.
     *
     * <p>The expected value comes from the argument at run time, so the assertion checks the
     * masking rule rather than a stored string. An argument of four characters or fewer expects
     * sixteen mask characters and no digit at all.</p>
     */
    @Test
    void everyMaskedCardNumberIsTwelveMasksAndTheLastFourOfItsArgument() {
        for (String argument : maskingArguments()) {
            String masked = PanMasker.maskCardNumber(argument);
            String stripped = argument == null ? "" : argument.strip();

            String expected = stripped.length() <= VISIBLE_CHARACTER_COUNT
                    ? FULLY_MASKED_CARD_NUMBER
                    : MASK_PREFIX
                            + stripped.substring(stripped.length() - VISIBLE_CHARACTER_COUNT);

            assertEquals(expected, masked,
                    "a masked card number stopped following the masking rule for "
                            + describe(argument));

            assertEquals(MASK_PREFIX, masked.substring(0, HIDDEN_CHARACTER_COUNT),
                    "a masked card number stopped hiding its leading twelve characters for "
                            + describe(argument));

            long digitsInMasked = masked.chars().filter(Character::isDigit).count();
            long digitsInVisibleTail = expected.substring(HIDDEN_CHARACTER_COUNT).chars()
                    .filter(Character::isDigit).count();
            assertEquals(digitsInVisibleTail, digitsInMasked,
                    "a masked card number carried a digit outside its visible tail for "
                            + describe(argument));
        }
    }

    /**
     * Asserts that every single-argument method on the class returns a value holding no digit when
     * fed a value of card-verification-value width.
     *
     * <p>The loop walks the declared methods by reflection rather than naming two of them, so a
     * method added later that echoes an argument of that width fails here. A card verification
     * value is {@value #STORED_VERIFICATION_VALUE_WIDTH} characters wide, which is inside the
     * {@value #VISIBLE_CHARACTER_COUNT}-character floor of the masking rule, so no digit of it may
     * ever surface.</p>
     */
    @Test
    void noMethodEmitsADigitOfAValueAtCardVerificationValueWidth() throws Exception {
        List<Method> stringMethods = declaredStringMethods();

        assertEquals(2, stringMethods.size(),
                "the count of single-argument string methods changed");

        for (Method method : stringMethods) {
            for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
                String stored = String.format("%03d", value);

                String returned = (String) method.invoke(null, stored);

                assertFalse(holdsAnyDigit(returned),
                        "method " + method.getName() + " emitted a digit for a stored value at "
                                + "index " + value);
            }
        }
    }

    /**
     * Asserts that the class holds no mutable state and retains no argument.
     *
     * <p>Every declared field must be static and final, so no instance can carry a card number.
     * Every declared field of type {@link String} must hold mask characters only, both before and
     * after a masking call, so no field can accumulate an argument.</p>
     */
    @Test
    void panMaskerRetainsNoArgumentValueInAnyField() throws Exception {
        List<Field> fields = new ArrayList<>();
        for (Field field : PanMasker.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                fields.add(field);
            }
        }

        assertFalse(fields.isEmpty(), "PanMasker stopped declaring any field");

        PanMasker.maskCardNumber(FULL_CARD_NUMBER);
        PanMasker.maskCardNumber(THIRTY_TWO_CHARACTER_CARD_NUMBER);
        PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE);

        for (Field field : fields) {
            assertTrue(Modifier.isStatic(field.getModifiers()),
                    "field " + field.getName() + " stopped being static");
            assertTrue(Modifier.isFinal(field.getModifiers()),
                    "field " + field.getName() + " stopped being final");

            if (field.getType() != String.class) {
                continue;
            }

            field.setAccessible(true);
            String held = (String) field.get(null);

            assertFalse(holdsAnyDigit(held),
                    "field " + field.getName() + " retained a digit after a masking call");
            for (int position = 0; position < held.length(); position++) {
                assertEquals(PanMasker.MASK_CHARACTER, held.charAt(position),
                        "field " + field.getName() + " retained a non-mask character at position "
                                + position);
            }
        }
    }

    /**
     * Asserts that masking is not injective, so no inverse of it exists.
     *
     * <p>Four card numbers that differ across their leading twelve characters and share a suffix
     * collapse onto one masked value. A function that maps many arguments onto one result cannot
     * be reversed, which is a property of the behaviour rather than of a method name.</p>
     */
    @Test
    void maskingIsNotInjectiveSoNoInverseExists() {
        String[] sharingOneSuffix = {
                "4111222233337823",
                "5500000000007823",
                "3400000000007823",
                "6011000000007823",
        };

        Set<String> maskedValues = new LinkedHashSet<>();
        for (String argument : sharingOneSuffix) {
            maskedValues.add(PanMasker.maskCardNumber(argument));
        }

        assertEquals(1, maskedValues.size(),
                "four arguments sharing a suffix stopped collapsing onto one masked value");
        assertEquals(sharingOneSuffix.length,
                new LinkedHashSet<>(List.of(sharingOneSuffix)).size(),
                "the four arguments stopped being distinct");

        Set<String> maskedAcrossTwoSuffixes = new LinkedHashSet<>();
        for (String argument : sharingOneSuffix) {
            maskedAcrossTwoSuffixes.add(PanMasker.maskCardNumber(argument));
        }
        maskedAcrossTwoSuffixes.add(PanMasker.maskCardNumber(FIFTEEN_CHARACTER_CARD_NUMBER));

        assertEquals(2, maskedAcrossTwoSuffixes.size(),
                "the count of masked values stopped tracking the count of distinct suffixes");
    }

    /**
     * Asserts that the redaction returns three mask characters for every argument, including
     * {@code null}, and keeps no character of that argument.
     */
    @Test
    void redactCardVerificationValueRevealsNoDigitOfItsArgument() {
        String[] arguments = {CARD_VERIFICATION_VALUE, "000", "999", "7", "", null};

        for (int index = 0; index < arguments.length; index++) {
            String redacted = PanMasker.redactCardVerificationValue(arguments[index]);

            assertEquals(EXPECTED_REDACTION, redacted,
                    "a redaction changed for the argument at index " + index);
            assertEquals(STORED_VERIFICATION_VALUE_WIDTH, redacted.length(),
                    "a redaction changed width for the argument at index " + index);

            for (int position = 0; position < redacted.length(); position++) {
                assertEquals(PanMasker.MASK_CHARACTER, redacted.charAt(position),
                        "a redaction kept a character of the argument at index " + index);
            }
        }

        assertEquals(EXPECTED_REDACTION, PanMasker.REDACTED_CARD_VERIFICATION_VALUE);
        assertSame(PanMasker.REDACTED_CARD_VERIFICATION_VALUE,
                PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE),
                "the redaction stopped returning the published constant");
    }

    /**
     * Asserts that the argument keeps its digits and the masked value is a separate string. A
     * caller holds the full PAN for the authorization cross-reference lookup.
     */
    @Test
    void maskCardNumberLeavesItsArgumentUnmasked() {
        String cardNumber = FULL_CARD_NUMBER;

        String masked = PanMasker.maskCardNumber(cardNumber);

        assertEquals(STORED_CARD_NUMBER_WIDTH, cardNumber.length(),
                "the argument changed width across the call");
        assertEquals(STORED_CARD_NUMBER_WIDTH,
                cardNumber.chars().filter(Character::isDigit).count(),
                "the argument lost a digit across the call");
        assertNotSame(cardNumber, masked);
        assertNotEquals(cardNumber, masked);
    }

    /**
     * Asserts that a {@code null} card number yields sixteen mask characters, throws nothing, and
     * returns the same value on a repeat call.
     */
    @Test
    void nullCardNumberYieldsAFullyMaskedValue() {
        String masked = PanMasker.maskCardNumber(null);

        assertEquals(FULLY_MASKED_CARD_NUMBER, masked);
        assertEquals(STORED_CARD_NUMBER_WIDTH, masked.length());

        for (int position = 0; position < masked.length(); position++) {
            assertEquals(PanMasker.MASK_CHARACTER, masked.charAt(position));
        }

        assertEquals(masked, PanMasker.maskCardNumber(null));
    }

    /**
     * Asserts that a card number shorter than the stored width comes back masked, never as the
     * argument.
     */
    @Test
    void shortCardNumberIsNeverReturnedAsIs() {
        assertEquals(MASKED_FIFTEEN_CHARACTER_CARD_NUMBER,
                PanMasker.maskCardNumber(FIFTEEN_CHARACTER_CARD_NUMBER));

        String[] shortArguments = {
                FIFTEEN_CHARACTER_CARD_NUMBER, "41112222333", "12345", "1234", "1", "", "   ",
        };

        for (int index = 0; index < shortArguments.length; index++) {
            String argument = shortArguments[index];
            String masked = PanMasker.maskCardNumber(argument);

            assertNotEquals(argument, masked,
                    "an argument passed through unmasked: " + describe(argument));
            assertEquals(STORED_CARD_NUMBER_WIDTH, masked.length(),
                    "a masked card number changed width for " + describe(argument));
            assertEquals(PanMasker.MASK_CHARACTER, masked.charAt(0),
                    "a masked card number opened with a visible character for "
                            + describe(argument));
        }
    }

    /**
     * Asserts that four characters stay visible, that one character does the masking, and that no
     * method parameter varies either count.
     */
    @Test
    void visibleDigitCountIsFixedAtFour() {
        assertEquals(VISIBLE_CHARACTER_COUNT, PanMasker.VISIBLE_DIGIT_COUNT);
        assertEquals(STORED_CARD_NUMBER_WIDTH, PanMasker.CARD_NUMBER_LENGTH);
        assertEquals(STORED_VERIFICATION_VALUE_WIDTH, PanMasker.CARD_VERIFICATION_VALUE_LENGTH);
        assertEquals(HIDDEN_CHARACTER_COUNT,
                PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT);
        assertEquals('*', PanMasker.MASK_CHARACTER);
        assertEquals(MASK_PREFIX.length(), HIDDEN_CHARACTER_COUNT);

        for (Method method : PanMasker.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertEquals(1, method.getParameterCount(),
                    "method " + method.getName() + " accepts a second argument");
        }
    }

    // Private helpers.

    /**
     * Supplies the argument set the width and rule assertions share, from {@code null} through
     * twice the stored card-number width.
     *
     * @return the arguments, holding one {@code null} entry
     */
    private static String[] maskingArguments() {
        return new String[] {
                FULL_CARD_NUMBER,
                "  " + FULL_CARD_NUMBER + "  ",
                FIFTEEN_CHARACTER_CARD_NUMBER,
                SEVENTEEN_CHARACTER_CARD_NUMBER,
                NINETEEN_CHARACTER_CARD_NUMBER,
                THIRTY_TWO_CHARACTER_CARD_NUMBER,
                "  " + THIRTY_TWO_CHARACTER_CARD_NUMBER + " ",
                "41112222333",
                "12345",
                "1234",
                "1",
                "",
                "   ",
                null,
        };
    }

    /**
     * Collects the declared static methods that take one {@link String} and return one.
     *
     * @return the methods, in declaration order
     */
    private static List<Method> declaredStringMethods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : PanMasker.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != String.class
                    || method.getReturnType() != String.class) {
                continue;
            }
            method.setAccessible(true);
            methods.add(method);
        }
        return methods;
    }

    /**
     * Reports whether a value holds a decimal digit.
     *
     * @param value the value to inspect; must not be {@code null}
     * @return {@code true} when at least one character is a digit
     */
    private static boolean holdsAnyDigit(String value) {
        return value.chars().anyMatch(Character::isDigit);
    }

    /**
     * Labels an argument by case and width, holding no character of it.
     *
     * <p>A failing security assertion prints this label instead of the value it protects.</p>
     *
     * @param argument the argument to label; may be {@code null}
     * @return the label
     */
    private static String describe(String argument) {
        if (argument == null) {
            return "a null argument";
        }
        return "an argument of width " + argument.length() + " and stripped width "
                + argument.strip().length();
    }
}
