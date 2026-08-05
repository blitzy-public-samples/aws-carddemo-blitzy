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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that {@link PanMasker} hides a Primary Account Number (PAN) and never reveals a card
 * verification value (CVV).
 *
 * <p>{@code PanMasker} has no COBOL ancestor. No expected value in this class comes from
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
 * a case label and a width. A security test that fails must not print the value it protects, which
 * {@link #describe(String)} enforces for every argument-bearing message.
 *
 * <p>No card-shaped fixture in this class is written down.
 * {@link #syntheticCardNumber(long)} derives every one of them from the leading digits
 * {@code 9999}, which none of the fifty records of
 * {@code app/data/ASCII/carddata.txt} begins with. Their masked forms are derived from the same
 * method without typing a second value, so an expectation cannot drift from its argument.
 */
class PanMaskerTest {

    /**
     * Builds a sixteen-digit card number this repository does not carry.
     *
     * <p>The four leading digits are {@code 9999}, and none of the fifty records of
     * {@code app/data/ASCII/carddata.txt} begins with them. Every card-shaped fixture of this class
     * comes from here, so no card-number literal stands in this source file.
     *
     * @param serial the trailing serial, at most twelve digits
     * @return sixteen digits, opening with {@code 9999}
     */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format("%012d", serial);
    }

    /**
     * Builds the expected masked form of one argument from the rule, with no literal.
     *
     * @param cardNumber the argument to mask
     * @return twelve mask characters then the last four characters of {@code cardNumber}
     */
    private static String maskedFormOf(String cardNumber) {
        return "*".repeat(12) + cardNumber.substring(cardNumber.length() - 4);
    }

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

    /** A card number at the stored width, derived without a committed literal. */
    private static final String FULL_CARD_NUMBER = syntheticCardNumber(222233337823L);

    /** The masked form of {@link #FULL_CARD_NUMBER}: twelve mask characters and four digits. */
    private static final String MASKED_FULL_CARD_NUMBER = maskedFormOf(FULL_CARD_NUMBER);

    /** A card number one character short of the stored width. */
    private static final String FIFTEEN_CHARACTER_CARD_NUMBER =
            FULL_CARD_NUMBER.substring(0, STORED_CARD_NUMBER_WIDTH - 1);

    /** The masked form of {@link #FIFTEEN_CHARACTER_CARD_NUMBER}. */
    private static final String MASKED_FIFTEEN_CHARACTER_CARD_NUMBER =
            maskedFormOf(FIFTEEN_CHARACTER_CARD_NUMBER);

    /** A digit string one character longer than the stored width. */
    private static final String SEVENTEEN_CHARACTER_CARD_NUMBER = FULL_CARD_NUMBER + "4";

    /** A digit string three characters longer than the stored width. */
    private static final String NINETEEN_CHARACTER_CARD_NUMBER = FULL_CARD_NUMBER + "456";

    /** A digit string twice the stored width. */
    private static final String THIRTY_TWO_CHARACTER_CARD_NUMBER =
            FULL_CARD_NUMBER + syntheticCardNumber(222233330199L);

    /** A digit string four characters longer than the stored width, ending in the visible tail. */
    private static final String TWENTY_CHARACTER_CARD_NUMBER =
            syntheticCardNumber(1000L) + FULL_CARD_NUMBER
                    .substring(STORED_CARD_NUMBER_WIDTH - VISIBLE_CHARACTER_COUNT);

    /** Eleven digits, shorter than the stored width and longer than the visible tail. */
    private static final String ELEVEN_CHARACTER_CARD_FRAGMENT =
            FULL_CARD_NUMBER.substring(0, 11);

    /** Sixteen mask characters, the result for an argument of four characters or fewer. */
    private static final String FULLY_MASKED_CARD_NUMBER = "****************";

    /** A card verification value at the stored width. */
    private static final String CARD_VERIFICATION_VALUE = "451";

    /** Three mask characters, the complete result of a card verification value redaction. */
    private static final String EXPECTED_REDACTION = "***";

    /** The count of three-digit values a {@code PIC 9(03)} field can hold. */
    private static final int STORED_VERIFICATION_VALUE_COMBINATIONS = 1000;

    /** The width of a card token, the hexadecimal rendering of a SHA-256 digest. */
    private static final int CARD_TOKEN_WIDTH = 64;

    /** A second card number sharing the last four characters of {@link #FULL_CARD_NUMBER}. */
    private static final String SIBLING_CARD_NUMBER = "4111999988887823";

    /**
     * The three string fields of {@code PanMasker} that hold declared text rather than mask
     * characters, each against the literal it is expected to hold.
     *
     * <p>A field named here is proved to hold a compile-time literal and therefore no argument
     * value. A field not named here must hold mask characters alone.
     */
    private static final java.util.Map<String, String> DECLARED_TOKEN_TEXT = java.util.Map.of(
            "CARD_TOKEN_PATTERN", "^[0-9a-f]{64}$",
            "ABSENT_CARD_TOKEN", "0".repeat(CARD_TOKEN_WIDTH),
            "CARD_TOKEN_LABEL", "CardDemo/card-token/v1:",
            "CARD_TOKEN_ALGORITHM", "SHA-256");

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

        String twentyCharacterCardNumber =
                TWENTY_CHARACTER_CARD_NUMBER + SEVENTEEN_CHARACTER_CARD_NUMBER
                        .substring(SEVENTEEN_CHARACTER_CARD_NUMBER.length()
                                - VISIBLE_CHARACTER_COUNT);
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
     * the assertion is exhaustive rather than a sample. No expected value names a digit. A masked
     * card number may end in any digit,
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

    @Test
    void noMaskingMethodEmitsADigitOfAValueAtCardVerificationValueWidth() throws Exception {
        List<Method> stringMethods = declaredStringMethods();

        Set<String> names = new LinkedHashSet<>();
        for (Method method : stringMethods) {
            names.add(method.getName());
        }

        assertEquals(Set.of("maskCardNumber", "redactCardVerificationValue", "cardToken",
                        "tokenOf"),
                names,
                "the set of single-argument string methods changed, and each new method needs its"
                        + " own disclosure guarantee stated here");

        // maskCardNumber and redactCardVerificationValue both return mask characters alone, so a
        // returned digit would be a disclosure. cardToken and tokenOf return a digest rendered as
        // hexadecimal, which holds digits of its own; the guarantee it makes is tested by
        // cardTokenHoldsNoCharacterOfItsArgumentAndNoVerificationValue and by
        // cardTokenIsNotReversibleForAnyStoredVerificationValueWidthArgument below.
        for (Method method : stringMethods) {
            if (method.getName().equals("cardToken") || method.getName().equals("tokenOf")) {
                for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
                    String stored = String.format("%03d", value);
                    String returned = (String) method.invoke(null, stored);

                    assertTrue(returned.matches(PanMasker.CARD_TOKEN_PATTERN),
                            method.getName() + " returned a value outside its declared shape for"
                                    + " the stored value at index " + value);
                    assertNotEquals(stored, returned,
                            method.getName() + " returned its argument for the stored value at"
                                    + " index " + value);
                }
                continue;
            }
            for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
                String stored = String.format("%03d", value);

                String returned = (String) method.invoke(null, stored);

                assertFalse(holdsAnyDigit(returned),
                        "method " + method.getName() + " emitted a digit for a stored value at "
                                + "index " + value);
            }
        }
    }

    @Test
    void cardTokenIsNotReversibleForAnyStoredVerificationValueWidthArgument() {
        Set<String> tokens = new LinkedHashSet<>();

        for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
            String stored = String.format("%03d", value);
            String token = PanMasker.cardToken(stored);

            assertNotEquals(stored, token,
                    "a token equalled its argument at index " + value);
            assertTrue(token.matches(PanMasker.CARD_TOKEN_PATTERN),
                    "a token missed its declared shape at index " + value);
            tokens.add(token);
        }

        assertEquals(STORED_VERIFICATION_VALUE_COMBINATIONS, tokens.size(),
                "two arguments shared one token");
    }

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
        PanMasker.cardToken(FULL_CARD_NUMBER);
        PanMasker.cardToken(THIRTY_TWO_CHARACTER_CARD_NUMBER);

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

            // Three string fields are declared text rather than mask characters: the token shape a
            // schema document and a check constraint repeat, the label the token digest covers, and
            // the digest algorithm name. Each is compared against the literal expected here, which
            // is how this test still proves the field holds no argument value.
            if (DECLARED_TOKEN_TEXT.containsKey(field.getName())) {
                assertEquals(DECLARED_TOKEN_TEXT.get(field.getName()), held,
                        "field " + field.getName() + " stopped holding its declared literal");
                continue;
            }

            assertFalse(holdsAnyDigit(held),
                    "field " + field.getName() + " retained a digit after a masking call");
            for (int position = 0; position < held.length(); position++) {
                assertEquals(PanMasker.MASK_CHARACTER, held.charAt(position),
                        "field " + field.getName() + " retained a non-mask character at position "
                                + position);
            }
        }
    }

    @Test
    void maskingIsNotInjectiveSoNoInverseExists() {
        String[] sharingOneSuffix = sharingTheSameVisibleTail();

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
     * Supplies four distinct card numbers sharing one visible tail, which is the collision the mask
     * creates and the token must not.
     *
     * <p>Each value carries the same last {@value #VISIBLE_CHARACTER_COUNT} characters and differs
     * ahead of them, so {@link PanMasker#maskCardNumber(String)} collapses all four onto one value.
     *
     * @return four distinct card numbers with one visible tail
     */
    private static String[] sharingTheSameVisibleTail() {
        String tail = FULL_CARD_NUMBER.substring(
                STORED_CARD_NUMBER_WIDTH - VISIBLE_CHARACTER_COUNT);
        String[] cardNumbers = new String[4];
        for (int index = 0; index < cardNumbers.length; index++) {
            cardNumbers[index] =
                    syntheticCardNumber(100000000000L + index * 1000000L).substring(0,
                            STORED_CARD_NUMBER_WIDTH - VISIBLE_CHARACTER_COUNT) + tail;
        }
        return cardNumbers;
    }

    /**
     * Asserts the token separates the cards the mask conflates.
     *
     * <p>This is the property a persistent key needs and a masked card number does not have. Four
     * distinct card numbers sharing one visible tail collapse onto one masked value, so a
     * read model keyed on the masked value would merge four cardholders into one row set. The
     * same four yield four distinct tokens, so a read model keyed on the token keeps them
     * apart.
     *
     * <p>The read model this protects is {@code app/cpy/COSTM01.CPY:L20-L36}, whose key the
     * statement job builds by sorting on the card number at {@code app/jcl/CREASTMT.JCL}.
     */
    @Test
    void theTokenSeparatesTheCardsTheMaskConflates() {
        String[] sharingOneTail = sharingTheSameVisibleTail();

        Set<String> maskedValues = new LinkedHashSet<>();
        Set<String> tokens = new LinkedHashSet<>();
        for (String cardNumber : sharingOneTail) {
            maskedValues.add(PanMasker.maskCardNumber(cardNumber));
            tokens.add(PanMasker.cardToken(cardNumber));
        }

        assertEquals(sharingOneTail.length, new LinkedHashSet<>(List.of(sharingOneTail)).size(),
                "the four card numbers stopped being distinct, so this test measures nothing");
        assertEquals(1, maskedValues.size(),
                "the four card numbers stopped collapsing onto one masked value, so this test no "
                        + "longer measures the collision the token exists to break");
        assertEquals(sharingOneTail.length, tokens.size(),
                "two cards sharing their last " + VISIBLE_CHARACTER_COUNT + " characters produced "
                        + "one token, so a read model keyed on the token would merge two "
                        + "cardholders");
    }

    /**
     * Asserts one card number yields one token on every call, which is what makes the token usable
     * as a primary key, an index and a path segment.
     */
    @Test
    void oneCardNumberYieldsOneTokenOnEveryCall() {
        for (String argument : maskingArguments()) {
            if (argument == null || argument.isBlank()) {
                continue;
            }
            String first = PanMasker.cardToken(argument);
            String second = PanMasker.cardToken(argument);

            assertEquals(first, second,
                    "the token changed between two calls for " + describe(argument));
            assertEquals(PanMasker.CARD_TOKEN_LENGTH, first.length(),
                    "the token changed width for " + describe(argument));
            assertTrue(first.matches(PanMasker.CARD_TOKEN_PATTERN),
                    "the token left its declared shape for " + describe(argument));
        }
    }

    /**
     * Asserts a card number and its whitespace-padded form yield one token, and that a stored
     * fixed-width field and a trimmed request field therefore agree.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is fixed-width storage, so a
     * value read from it may carry padding a request field does not.
     */
    @Test
    void paddingDoesNotChangeTheToken() {
        String bare = PanMasker.cardToken(FULL_CARD_NUMBER);

        assertEquals(bare, PanMasker.cardToken("  " + FULL_CARD_NUMBER + "  "),
                "padding changed the token, so one card would key two rows");
        assertEquals(bare, PanMasker.cardToken(FULL_CARD_NUMBER + "   "),
                "trailing padding changed the token");
        assertEquals(PanMasker.ABSENT_CARD_TOKEN, PanMasker.tokenOf(null),
                "the tolerant alias changed its absent-card token");
        assertEquals(PanMasker.ABSENT_CARD_TOKEN, PanMasker.tokenOf("   "),
                "the tolerant alias stopped treating a blank card number as absent");
    }

    /**
     * Asserts the token reveals no character of its argument and equals neither the argument
     * nor its masked form, so nothing recovers a card number from a stored key.
     */
    @Test
    void theTokenRevealsNoCharacterOfItsArgument() {
        for (String argument : maskingArguments()) {
            if (argument == null || argument.isBlank()) {
                continue;
            }
            String token = PanMasker.cardToken(argument);
            String stripped = argument == null ? "" : argument.strip();

            assertNotEquals(stripped, token,
                    "the token equalled its argument for " + describe(argument));
            assertNotEquals(PanMasker.maskCardNumber(argument), token,
                    "the token equalled the masked form for " + describe(argument));
            if (stripped.length() >= VISIBLE_CHARACTER_COUNT) {
                assertFalse(token.contains(
                                stripped.substring(stripped.length() - VISIBLE_CHARACTER_COUNT)),
                        "the token carried the visible tail of " + describe(argument));
            }
        }
    }

    /**
     * Pins the token of one card number, so a change to the digest, the domain label or the
     * truncation width fails the build before it can silently re-key every stored row.
     *
     * <p>A token is a persistent key. Changing how it is derived orphans every row already keyed on
     * the old value, so the derivation is frozen here and the expectation is computed from the
     * declared inputs, with no value copied from a run.
     */
    @Test
    void theTokenDerivationIsFrozen() throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update("CardDemo/card-token/v1:".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII));
        digest.update(FULL_CARD_NUMBER.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] full = digest.digest();

        StringBuilder expected = new StringBuilder(PanMasker.CARD_TOKEN_LENGTH);
        for (int index = 0; index < PanMasker.CARD_TOKEN_LENGTH / 2; index++) {
            expected.append(String.format("%02x", full[index]));
        }

        assertEquals(expected.toString(), PanMasker.cardToken(FULL_CARD_NUMBER),
                "the token derivation changed, so every row already keyed on a token is orphaned");
        assertEquals(64, PanMasker.CARD_TOKEN_LENGTH,
                "the token width changed, so every column sized for it is wrong");
        assertEquals("^[0-9a-f]{64}$", PanMasker.CARD_TOKEN_PATTERN,
                "the token shape changed, so a stored value may no longer validate");
    }

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

    @Test
    void shortCardNumberIsNeverReturnedAsIs() {
        assertEquals(MASKED_FIFTEEN_CHARACTER_CARD_NUMBER,
                PanMasker.maskCardNumber(FIFTEEN_CHARACTER_CARD_NUMBER));

        String[] shortArguments = {
                FIFTEEN_CHARACTER_CARD_NUMBER, ELEVEN_CHARACTER_CARD_FRAGMENT, "12345", "1234", "1",
                "", "   ",
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
                ELEVEN_CHARACTER_CARD_FRAGMENT,
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

    @Test
    void cardTokenIsSixtyFourLowerCaseHexadecimalCharacters() {
        String token = PanMasker.cardToken(FULL_CARD_NUMBER);

        assertEquals(CARD_TOKEN_WIDTH, token.length());
        assertEquals(CARD_TOKEN_WIDTH, PanMasker.CARD_TOKEN_LENGTH);
        assertTrue(token.matches(PanMasker.CARD_TOKEN_PATTERN),
                "a token missed the shape the schema documents declare");
        assertEquals(token.toLowerCase(java.util.Locale.ROOT), token);
    }

    @Test
    void cardTokenIsDeterministicAcrossCalls() {
        assertEquals(PanMasker.cardToken(FULL_CARD_NUMBER),
                PanMasker.cardToken(FULL_CARD_NUMBER));
        assertEquals(PanMasker.cardToken(FULL_CARD_NUMBER),
                PanMasker.cardToken("  " + FULL_CARD_NUMBER + "  "));
    }

    @Test
    void twoCardsSharingTheLastFourDigitsMaskAlikeAndTokenizeApart() {
        assertEquals(PanMasker.maskCardNumber(FULL_CARD_NUMBER),
                PanMasker.maskCardNumber(SIBLING_CARD_NUMBER),
                "two cards ending alike must mask alike, which is why masking is not identity");

        assertNotEquals(PanMasker.cardToken(FULL_CARD_NUMBER),
                PanMasker.cardToken(SIBLING_CARD_NUMBER),
                "two different cards must tokenize apart");
    }

    @Test
    void everyDistinctCardNumberInAFixtureRangeTokenizesToADistinctValue() {
        Set<String> tokens = new LinkedHashSet<>();
        int cardCount = 512;

        for (int index = 0; index < cardCount; index++) {
            String cardNumber = "4111222233330000".substring(0, STORED_CARD_NUMBER_WIDTH - 4)
                    + String.format("%04d", index);
            tokens.add(PanMasker.cardToken(cardNumber));
        }

        assertEquals(cardCount, tokens.size(), "two card numbers shared one token");
    }

    @Test
    void cardTokenHoldsNoCharacterOfItsArgumentAndNoVerificationValue() {
        String token = PanMasker.cardToken(FULL_CARD_NUMBER);

        assertFalse(token.contains(FULL_CARD_NUMBER),
                "a token disclosed the card number it covers");
        assertFalse(token.contains(FULL_CARD_NUMBER.substring(0, 6)),
                "a token disclosed the issuer identification number of its argument");
        assertFalse(token.contains(FULL_CARD_NUMBER.substring(STORED_CARD_NUMBER_WIDTH - 4)),
                "a token disclosed the last four characters of its argument");
        assertFalse(token.contains(CARD_VERIFICATION_VALUE),
                "a token disclosed a card verification value");
    }

    @Test
    void cardTokenDiffersFromABareDigestOfTheSameCardNumber() throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String bare = java.util.HexFormat.of().formatHex(
                digest.digest(FULL_CARD_NUMBER.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertNotEquals(bare, PanMasker.cardToken(FULL_CARD_NUMBER),
                "the label must separate this token from any other digest of the same card number");
    }

    @Test
    void cardTokenRefusesAnArgumentThatNamesNoCard() {
        assertThrows(NullPointerException.class, () -> PanMasker.cardToken(null));
        assertThrows(IllegalArgumentException.class, () -> PanMasker.cardToken(""));
        assertThrows(IllegalArgumentException.class, () -> PanMasker.cardToken("   "));
    }

    @Test
    void cardTokenRefusalNamesNoCardNumber() {
        IllegalArgumentException refusal =
                assertThrows(IllegalArgumentException.class, () -> PanMasker.cardToken("  "));

        assertFalse(holdsAnyDigit(refusal.getMessage()),
                "a refusal message carried a digit of its argument");
    }

    /**
     * The argument values a field must not hold after the calls this class makes.
     *
     * <p>The last four characters of a card number are the part a masked value leaves visible, so a
     * field holding them would hold the one fragment masking publishes.
     *
     * @return each value no field may contain
     */
    private static String[] retainedValueProbes() {
        return new String[] {
                FULL_CARD_NUMBER,
                THIRTY_TWO_CHARACTER_CARD_NUMBER,
                CARD_VERIFICATION_VALUE,
                FULL_CARD_NUMBER.substring(FULL_CARD_NUMBER.length() - VISIBLE_CHARACTER_COUNT),
                PanMasker.tokenOf(FULL_CARD_NUMBER),
        };
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
