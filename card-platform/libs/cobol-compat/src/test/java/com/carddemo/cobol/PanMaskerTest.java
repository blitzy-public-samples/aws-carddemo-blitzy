package com.carddemo.cobol;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

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
 * <p>Every test below asserts one of three properties. A masked value hides all but the last four
 * characters and stays sixteen characters wide. The argument survives the call unmasked, and no
 * returned string carries a digit of a card verification value.
 *
 * <p>Rationale lives in {@code card-platform/docs/decision-log.md}. Flagged source rules live in
 * {@code card-platform/docs/business-rule-flags.md}.
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

    /** A card number at the stored width. */
    private static final String FULL_CARD_NUMBER = "4111222233337823";

    /** The masked form of {@link #FULL_CARD_NUMBER}: twelve mask characters and four digits. */
    private static final String MASKED_FULL_CARD_NUMBER = "************7823";

    /** A card number one character short of the stored width. */
    private static final String FIFTEEN_CHARACTER_CARD_NUMBER = "411122223333782";

    /** The masked form of {@link #FIFTEEN_CHARACTER_CARD_NUMBER}. */
    private static final String MASKED_FIFTEEN_CHARACTER_CARD_NUMBER = "************3782";

    /** Sixteen mask characters, the result for an argument of four characters or fewer. */
    private static final String FULLY_MASKED_CARD_NUMBER = "****************";

    /**
     * A card verification value at the stored width. Digits 4, 5 and 1 appear in no masked value
     * this class expects.
     */
    private static final String CARD_VERIFICATION_VALUE = "451";

    /** Three mask characters, the complete result of a card verification value redaction. */
    private static final String EXPECTED_REDACTION = "***";

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
                    "position " + position + " kept a card-number character: " + masked);
        }

        String lastFourOfArgument =
                FULL_CARD_NUMBER.substring(STORED_CARD_NUMBER_WIDTH - VISIBLE_CHARACTER_COUNT);
        assertEquals(lastFourOfArgument, masked.substring(HIDDEN_CHARACTER_COUNT));

        assertEquals(MASKED_FULL_CARD_NUMBER,
                PanMasker.maskCardNumber("  " + FULL_CARD_NUMBER + "  "));
    }

    /**
     * Asserts that every masked card number is sixteen characters wide, the width of
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    @Test
    void maskCardNumberAlwaysReturnsSixteenCharacters() {
        String[] arguments = {
                FULL_CARD_NUMBER,
                "  " + FULL_CARD_NUMBER + "  ",
                FIFTEEN_CHARACTER_CARD_NUMBER,
                "41112222333",
                "12345",
                "1234",
                "1",
                "",
                "   ",
                null,
        };

        for (String argument : arguments) {
            assertEquals(STORED_CARD_NUMBER_WIDTH, PanMasker.maskCardNumber(argument).length(),
                    "masked width changed for argument: " + argument);
        }
    }

    /**
     * Asserts that no CVV digit reaches a masked card number or a redaction. The card record holds
     * the field in the clear at {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    @Test
    void cardVerificationValueNeverAppearsInAnyMaskedOutput() {
        String[] outputs = {
                PanMasker.maskCardNumber(FULL_CARD_NUMBER),
                PanMasker.maskCardNumber(FIFTEEN_CHARACTER_CARD_NUMBER),
                PanMasker.maskCardNumber("  " + FULL_CARD_NUMBER + "  "),
                PanMasker.maskCardNumber(null),
                PanMasker.redactCardVerificationValue(CARD_VERIFICATION_VALUE),
        };

        for (String output : outputs) {
            assertFalse(output.contains(CARD_VERIFICATION_VALUE),
                    "output carried the card verification value: " + output);

            for (int index = 0; index < CARD_VERIFICATION_VALUE.length(); index++) {
                char digit = CARD_VERIFICATION_VALUE.charAt(index);
                assertEquals(-1, output.indexOf(digit),
                        "output carried card verification value digit " + digit + ": " + output);
            }
        }
    }

    /**
     * Asserts that the redaction returns three mask characters for every argument, including
     * {@code null}, and keeps no digit of that argument.
     */
    @Test
    void redactCardVerificationValueRevealsNoDigitOfItsArgument() {
        String[] arguments = {CARD_VERIFICATION_VALUE, "000", "999", "7", "", null};

        for (String argument : arguments) {
            String redacted = PanMasker.redactCardVerificationValue(argument);

            assertEquals(EXPECTED_REDACTION, redacted, "redaction changed for argument: " + argument);
            assertEquals(STORED_VERIFICATION_VALUE_WIDTH, redacted.length());

            for (int position = 0; position < redacted.length(); position++) {
                assertEquals(PanMasker.MASK_CHARACTER, redacted.charAt(position),
                        "redaction kept a character of argument: " + argument);
            }
        }

        assertEquals(EXPECTED_REDACTION, PanMasker.REDACTED_CARD_VERIFICATION_VALUE);
    }

    /**
     * Asserts that the argument keeps its digits and the masked value is a separate string. A
     * caller holds the full PAN for the authorization cross-reference lookup.
     */
    @Test
    void maskCardNumberLeavesItsArgumentUnmasked() {
        String cardNumber = FULL_CARD_NUMBER;

        String masked = PanMasker.maskCardNumber(cardNumber);

        assertEquals("4111222233337823", cardNumber);
        assertEquals(STORED_CARD_NUMBER_WIDTH, cardNumber.length());
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

        for (String argument : shortArguments) {
            String masked = PanMasker.maskCardNumber(argument);

            assertNotEquals(argument, masked, "argument passed through unmasked: " + argument);
            assertEquals(STORED_CARD_NUMBER_WIDTH, masked.length(),
                    "masked width changed for argument: " + argument);
            assertEquals(PanMasker.MASK_CHARACTER, masked.charAt(0),
                    "masked value opened with a card-number character: " + masked);
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

        for (Method method : PanMasker.class.getDeclaredMethods()) {
            assertEquals(1, method.getParameterCount(),
                    "method accepts a second argument: " + method.getName());
        }
    }

    /**
     * Asserts that {@code PanMasker} declares no method that turns a masked value back into a card
     * number.
     */
    @Test
    void noMethodReversesTheMaskedValue() {
        String[] forbiddenFragments = {"unmask", "reverse", "restore", "roundtrip", "unredact", "reveal"};

        for (Method method : PanMasker.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);

            for (String fragment : forbiddenFragments) {
                assertFalse(name.contains(fragment),
                        "PanMasker declared a method that reverses a masked value: "
                                + method.getName());
            }
        }
    }
}
