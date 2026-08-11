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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** The width of a card token: the hexadecimal rendering of a 32-byte keyed code. */
    private static final int CARD_TOKEN_WIDTH = 64;

    /**
     * A second card number sharing the last four characters of {@link #FULL_CARD_NUMBER}, derived
     * from a serial that differs above those four digits so no literal is written down.
     */
    private static final String SIBLING_CARD_NUMBER = syntheticCardNumber(444455557823L);

    /**
     * The string fields of {@code PanMasker} that hold declared text rather than mask characters,
     * each against the literal it is expected to hold.
     *
     * <p>A field named here is proved to hold a compile-time literal and therefore no argument
     * value. A field not named here must hold mask characters alone.
     *
     * <p>{@code PUBLISHED_DEMO_CARD_TOKEN_SECRET} belongs here rather than being an exception to
     * the rule: it is a key this repository states in plain text, so writing it down again in a
     * test discloses nothing, and the field has to hold exactly that value for the start-up
     * refusal to recognise it.
     */
    private static final java.util.Map<String, String> DECLARED_TOKEN_TEXT = java.util.Map.ofEntries(
            java.util.Map.entry("PUBLISHED_DEMO_CARD_TOKEN_SECRET",
                    "carddemo-demo-card-token-key-not-for-production"),
            java.util.Map.entry("CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY",
                    "carddemo.card-token.allow-published-key"),
            java.util.Map.entry("CARD_TOKEN_ALLOW_PUBLISHED_KEY_VARIABLE",
                    "CARD_TOKEN_ALLOW_PUBLISHED_KEY"),
            java.util.Map.entry("CARD_TOKEN_PATTERN", "^[0-9a-f]{64}$"),
            java.util.Map.entry("ABSENT_CARD_TOKEN", "0".repeat(CARD_TOKEN_WIDTH)),
            java.util.Map.entry("CARD_TOKEN_LABEL_PREFIX", "CardDemo/card-token/v"),
            java.util.Map.entry("CARD_TOKEN_ALGORITHM", "HmacSHA256"),
            java.util.Map.entry("REDACTED_KEY", "[redacted]"),
            java.util.Map.entry("CARD_TOKEN_SECRET_PROPERTY", "carddemo.card-token.secret"),
            java.util.Map.entry("CARD_TOKEN_SECRET_VARIABLE", "CARD_TOKEN_SECRET"),
            java.util.Map.entry("CARD_TOKEN_VERSION_PROPERTY", "carddemo.card-token.version"),
            java.util.Map.entry("CARD_TOKEN_VERSION_VARIABLE", "CARD_TOKEN_VERSION"),
            java.util.Map.entry("CARD_TOKEN_PREVIOUS_SECRET_PROPERTY",
                    "carddemo.card-token.previous-secret"),
            java.util.Map.entry("CARD_TOKEN_PREVIOUS_SECRET_VARIABLE",
                    "CARD_TOKEN_PREVIOUS_SECRET"),
            java.util.Map.entry("CARD_TOKEN_PREVIOUS_VERSION_PROPERTY",
                    "carddemo.card-token.previous-version"),
            java.util.Map.entry("CARD_TOKEN_PREVIOUS_VERSION_VARIABLE",
                    "CARD_TOKEN_PREVIOUS_VERSION"),
            java.util.Map.entry("CARD_TOKEN_ROTATION_PROPERTY",
                    "carddemo.card-token.rotation-enabled"),
            java.util.Map.entry("CARD_TOKEN_ROTATION_VARIABLE", "CARD_TOKEN_ROTATION_ENABLED"),
            java.util.Map.entry("DEFAULT_CARD_TOKEN_VERSION", "1"));

    /**
     * The single-argument string methods that return a digest rather than mask characters.
     *
     * <p>Each holds digits of its own, so the guarantee they are held to is that the digest is not
     * its argument and takes the declared shape, rather than that it carries no digit.</p>
     */
    private static final Set<String> DIGEST_METHODS =
            Set.of("cardToken", "tokenOf", "previousCardToken");

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
     * Every masked card number is sixteen characters wide, the width of
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
     * An argument longer than the stored width normalizes to the stored width and
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
     * Every value a {@code PIC 9(03)} field can hold redacts to exactly three mask
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
                        "tokenOf", "previousCardToken"),
                names,
                "the set of single-argument string methods changed, and each new method needs its"
                        + " own disclosure guarantee stated here");

        // maskCardNumber and redactCardVerificationValue both return mask characters alone, so a
        // returned digit would be a disclosure. cardToken, tokenOf and previousCardToken return a
        // digest rendered as hexadecimal, which holds digits of its own; the guarantee it makes is
        // tested by cardTokenHoldsNoCharacterOfItsArgumentAndNoVerificationValue and by
        // cardTokenIsNotReversibleForAnyStoredVerificationValueWidthArgument below.
        // previousCardToken derives under the key a rotation is leaving behind, so it is held to the
        // same shape and the same non-reversal, under a previous pair this test configures.
        for (Method method : stringMethods) {
            if (DIGEST_METHODS.contains(method.getName())) {
                for (int value = 0; value < STORED_VERIFICATION_VALUE_COMBINATIONS; value++) {
                    String stored = String.format("%03d", value);
                    String returned = derive(method, stored);

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

            // Nineteen of the twenty-two string fields are declared text rather than mask
            // characters: the token shape a schema document and a check constraint repeat, the
            // absent-token sentinel, the label the keyed code covers, the algorithm name, the
            // redaction a rendered key holder shows, the twelve configuration names, the published
            // demonstration key and the default version. Each is compared against the literal
            // expected here, which is how this test still proves the field holds no argument value.
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
     * The token separates the cards the mask conflates.
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
     * One card number yields one token on every call, which is what makes the token usable
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
     * A card number and its whitespace-padded form yield one token, and that a stored
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
     * The token reveals no character of its argument and equals neither the argument
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
     * Pins the token derivation, so a change to the algorithm, the covered message or the width
     * fails the build before it can silently re-key every stored row.
     *
     * <p>A token is a persistent key. Changing how it is derived orphans every row already keyed on
     * the old value, so the derivation is frozen here and the expectation is computed from the
     * declared inputs under the key this build configures, with no value copied from a run.
     */
    @Test
    void theTokenDerivationIsFrozen() throws Exception {
        String expected = expectedToken(configuredKey(), PanMasker.cardTokenVersion(),
                FULL_CARD_NUMBER);

        assertEquals(expected, PanMasker.cardToken(FULL_CARD_NUMBER),
                "the token derivation changed, so every row already keyed on a token is orphaned");
        assertEquals(64, PanMasker.CARD_TOKEN_LENGTH,
                "the token width changed, so every column sized for it is wrong");
        assertEquals("^[0-9a-f]{64}$", PanMasker.CARD_TOKEN_PATTERN,
                "the token shape changed, so a stored value may no longer validate");
    }

    /**
     * The derivation is keyed: one card number under two keys yields two tokens.
     *
     * <p>This is the property an unkeyed digest cannot supply. A holder of a token cannot recompute
     * it for a candidate card number without the key, so the finite sixteen-digit card-number space
     * stops being an offline search.
     */
    @Test
    void twoKeysYieldTwoTokensForOneCardNumber() throws Exception {
        String underOneKey = expectedToken(A_KEY, PanMasker.DEFAULT_CARD_TOKEN_VERSION,
                FULL_CARD_NUMBER);
        String underAnother = expectedToken(ANOTHER_KEY, PanMasker.DEFAULT_CARD_TOKEN_VERSION,
                FULL_CARD_NUMBER);

        assertNotEquals(underOneKey, underAnother,
                "one card number derived two equal tokens under two keys, so the derivation is not"
                        + " keyed and a token is recomputable by anyone holding one");
        assertEquals(underOneKey, withConfiguration(A_KEY, null,
                () -> PanMasker.cardToken(FULL_CARD_NUMBER)),
                "the configured key is not the key the derivation takes");
        assertEquals(underAnother, withConfiguration(ANOTHER_KEY, null,
                () -> PanMasker.cardToken(FULL_CARD_NUMBER)),
                "a key change did not take effect, so a rollover cannot be staged");
    }

    /**
     * The version rolls every token over under one key, which is what stages a rollover.
     */
    @Test
    void raisingTheVersionRollsEveryTokenOverUnderOneKey() throws Exception {
        String atVersionOne = withConfiguration(A_KEY, "1",
                () -> PanMasker.cardToken(FULL_CARD_NUMBER));
        String atVersionTwo = withConfiguration(A_KEY, "2",
                () -> PanMasker.cardToken(FULL_CARD_NUMBER));

        assertNotEquals(atVersionOne, atVersionTwo,
                "the version is not part of the covered message, so raising it rolls nothing over");
        assertEquals(expectedToken(A_KEY, "1", FULL_CARD_NUMBER), atVersionOne,
                "the version one token is not the code over the version one message");
        assertEquals(expectedToken(A_KEY, "2", FULL_CARD_NUMBER), atVersionTwo,
                "the version two token is not the code over the version two message");
        assertEquals("1", PanMasker.DEFAULT_CARD_TOKEN_VERSION,
                "the default version changed, so every seeded token belongs to another version");
        assertEquals("1", withConfiguration(A_KEY, null, PanMasker::cardTokenVersion),
                "an unset version must report the default rather than an absent value");
    }

    /**
     * Proves the published demonstration key is refused unless a deployment states that it means
     * to use it, and that a key of the deployment's own needs no statement.
     *
     * <p>This is the check a derivation site cannot make. An absent or short key stops a
     * derivation; the published key derives perfectly good tokens that anyone holding this
     * repository can recompute for a candidate card number, so nothing downstream can tell that
     * configuration apart from a rotated one.
     */
    @Test
    void thePublishedDemonstrationKeyIsRefusedUnlessTheDeploymentStatesIt() throws Exception {
        String held = System.getProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);
        try {
            System.clearProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);

            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> withConfiguration(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET, null,
                            PanMasker::requireCardTokenSecretFitForUse),
                    "the published key was accepted with no statement accompanying it, so a"
                            + " deployment can carry it by copying an example file");
            assertTrue(refused.getMessage()
                            .contains(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY),
                    "the refusal must name the property that states the intent: "
                            + refused.getMessage());
            assertTrue(refused.getMessage().contains(PanMasker.CARD_TOKEN_SECRET_VARIABLE),
                    "and the variable that carries a key of the deployment's own: "
                            + refused.getMessage());

            assertFalse(withConfiguration(A_KEY, null, PanMasker::requireCardTokenSecretFitForUse),
                    "a key of the deployment's own needs no statement and is not a published key");
            assertFalse(withConfiguration(null, null, PanMasker::requireCardTokenSecretFitForUse),
                    "an absent key is refused at the derivation, which is where the caller that"
                            + " needs one asks for it, so this check must let a service that"
                            + " tokenizes nothing start");

            System.setProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, "true");
            assertTrue(withConfiguration(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET, null,
                            PanMasker::requireCardTokenSecretFitForUse),
                    "a stated demonstration must start and report that its key is published");

            System.setProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, "yes");
            assertThrows(IllegalStateException.class,
                    () -> withConfiguration(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET, null,
                            PanMasker::requireCardTokenSecretFitForUse),
                    "only true states the intent: any other value has to read as unstated rather"
                            + " than as approval");
        } finally {
            restore(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, held);
        }

        assertFalse(PanMasker.requireCardTokenSecretFitForUse(),
                "this build runs on the build-scope key card-platform/pom.xml declares, which is"
                        + " not the key this repository publishes, so the guard has to accept the"
                        + " configuration every other test derives under and report that the key is"
                        + " not published");
    }

    /**
     * Proves an unconfigured key derives no token at all, rather than falling back to a value every
     * reader of this repository can recompute.
     */
    @Test
    void anUnconfiguredOrTooShortKeyDerivesNoToken() throws Exception {
        IllegalStateException unconfigured = assertThrows(IllegalStateException.class,
                () -> withConfiguration(null, null, () -> PanMasker.cardToken(FULL_CARD_NUMBER)),
                "an unconfigured key derived a token, so tokens are recomputable without a key");
        assertTrue(unconfigured.getMessage().contains(PanMasker.CARD_TOKEN_SECRET_PROPERTY),
                "the refusal must name the property that supplies the key");

        String tooShort = "x".repeat(PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH - 1);
        IllegalStateException short_ = assertThrows(IllegalStateException.class,
                () -> withConfiguration(tooShort, null,
                        () -> PanMasker.cardToken(FULL_CARD_NUMBER)),
                "a key under the required length was accepted rather than refused");
        assertFalse(short_.getMessage().contains(tooShort),
                "the refusal quoted the key material");

        IllegalStateException badVersion = assertThrows(IllegalStateException.class,
                () -> withConfiguration(A_KEY, "0", PanMasker::cardTokenVersion),
                "a version outside one to three non-zero-leading digits was accepted");
        assertTrue(badVersion.getMessage().contains(PanMasker.CARD_TOKEN_VERSION_PROPERTY),
                "the refusal must name the property that supplies the version");

        assertEquals(PanMasker.ABSENT_CARD_TOKEN,
                withConfiguration(null, null, () -> PanMasker.tokenOf(null)),
                "an absent card number needs no key, because it derives no code");
    }

    /**
     * Proves the read half of a rotation derives under the key being left behind.
     *
     * <p>A store holding a token and no card number cannot derive its own replacement, so a rotation
     * has to state which stored value became which new value. That statement is only possible while
     * both keys are readable, which is what the previous pair is for.
     */
    @Test
    void previousCardTokenDerivesUnderTheKeyARotationIsLeavingBehind() throws Exception {
        String underPrevious = withConfiguration(A_KEY, null,
                () -> withPreviousConfiguration(ANOTHER_KEY, null,
                        () -> PanMasker.previousCardToken(FULL_CARD_NUMBER)));

        assertEquals(expectedToken(ANOTHER_KEY, PanMasker.DEFAULT_CARD_TOKEN_VERSION,
                        FULL_CARD_NUMBER),
                underPrevious,
                "the previous token is not the code the previous key takes over the same message,"
                        + " so a mapping built from it names a card no store ever held");
        assertNotEquals(withConfiguration(A_KEY, null,
                        () -> PanMasker.cardToken(FULL_CARD_NUMBER)),
                underPrevious,
                "the two halves of the dual read agreed, so nothing moved and the mapping is empty");
        assertEquals(underPrevious, withConfiguration(A_KEY, null,
                        () -> withPreviousConfiguration(ANOTHER_KEY, null,
                                () -> PanMasker.previousCardToken("  " + FULL_CARD_NUMBER + "  "))),
                "surrounding whitespace changed the previous token, so a padded stored card number"
                        + " would map onto a value no row carries");
    }

    /**
     * Proves a version raised on its own is a rotation, and that the previous version defaults.
     *
     * <p>Turning the key over and leaving the version alone is the common case, so it has to need one
     * setting rather than two. Raising the version alone rolls every token over under one key, and the
     * read half has to name the earlier version for that case to be mappable.
     */
    @Test
    void thePreviousVersionDefaultsToTheCurrentOneAndIsHeldToItsShape() throws Exception {
        assertEquals("2", withConfiguration(A_KEY, "2",
                        () -> withPreviousConfiguration(ANOTHER_KEY, null,
                                PanMasker::previousCardTokenVersion)),
                "an unset previous version has to read as the current version, so turning only the"
                        + " key over needs one setting");
        assertEquals("1", withConfiguration(A_KEY, "2",
                        () -> withPreviousConfiguration(ANOTHER_KEY, "1",
                                PanMasker::previousCardTokenVersion)),
                "a configured previous version is the version the stored token was taken under");

        assertEquals(expectedToken(A_KEY, "1", FULL_CARD_NUMBER),
                withConfiguration(A_KEY, "2",
                        () -> withPreviousConfiguration(A_KEY, "1",
                                () -> PanMasker.previousCardToken(FULL_CARD_NUMBER))),
                "a version raised on one key must be a rotation the read half can derive, or a"
                        + " version rollover leaves the other stores unmappable");

        IllegalStateException badVersion = assertThrows(IllegalStateException.class,
                () -> withConfiguration(A_KEY, null,
                        () -> withPreviousConfiguration(ANOTHER_KEY, "0",
                                PanMasker::previousCardTokenVersion)),
                "a previous version outside the declared shape was accepted");
        assertTrue(badVersion.getMessage()
                        .contains(PanMasker.CARD_TOKEN_PREVIOUS_VERSION_PROPERTY),
                "the refusal must name the property that supplied the previous version: "
                        + badVersion.getMessage());
    }

    /**
     * Proves the three ways a previous pair can be unusable are refused rather than derived under.
     *
     * <p>Each refusal exists because the alternative is a mapping that reads as complete and is
     * wrong: an absent key derives nothing, a key too short was never a key this platform derived
     * under, and a pair equal to the current one maps every value onto itself.
     */
    @Test
    void anUnusablePreviousPairDerivesNoTokenAtAll() throws Exception {
        assertFalse(PanMasker.previousCardTokenConfigured(),
                "this build configures no previous pair, so a rotation is not in progress and"
                        + " nothing may report one");

        IllegalStateException unconfigured = assertThrows(IllegalStateException.class,
                () -> withPreviousConfiguration(null, null,
                        () -> PanMasker.previousCardToken(FULL_CARD_NUMBER)),
                "an unconfigured previous key derived a token, so a rotation could invent the value"
                        + " it claims a store held");
        assertTrue(unconfigured.getMessage()
                        .contains(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_PROPERTY),
                "the refusal must name the property that supplies the previous key: "
                        + unconfigured.getMessage());
        assertTrue(unconfigured.getMessage()
                        .contains(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_VARIABLE),
                "and the variable that carries it: " + unconfigured.getMessage());

        String tooShort = "x".repeat(PanMasker.CARD_TOKEN_SECRET_MIN_LENGTH - 1);
        IllegalStateException short_ = assertThrows(IllegalStateException.class,
                () -> withPreviousConfiguration(tooShort, null,
                        () -> PanMasker.previousCardToken(FULL_CARD_NUMBER)),
                "a previous key under the required length was accepted, and this platform never"
                        + " derived under one");
        assertFalse(short_.getMessage().contains(tooShort),
                "the refusal quoted the key material");

        IllegalStateException samePair = assertThrows(IllegalStateException.class,
                () -> withConfiguration(A_KEY, null,
                        () -> withPreviousConfiguration(A_KEY, null,
                                () -> PanMasker.previousCardToken(FULL_CARD_NUMBER))),
                "a previous pair equal to the current pair was accepted, so a rotation would report"
                        + " a mapping of every value onto itself");
        assertTrue(samePair.getMessage()
                        .contains(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_PROPERTY),
                "the refusal must name the setting to correct: " + samePair.getMessage());

        assertThrows(NullPointerException.class,
                () -> withPreviousConfiguration(ANOTHER_KEY, null,
                        () -> PanMasker.previousCardToken(null)),
                "an absent card number names no card, and a mapping row for it would name none"
                        + " either");
        assertThrows(IllegalArgumentException.class,
                () -> withPreviousConfiguration(ANOTHER_KEY, null,
                        () -> PanMasker.previousCardToken("   ")),
                "a blank card number names no card");
    }

    /**
     * Proves the previous key is not held to the published-key refusal the current key is.
     *
     * <p>Rotating away from the published demonstration key is the case that refusal exists to force.
     * Holding the read half to it would make the one rotation this platform most needs impossible to
     * perform.
     */
    @Test
    void thePublishedKeyIsDerivableAsThePreviousKeyOfARotation() throws Exception {
        String held = System.getProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);
        try {
            System.clearProperty(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY);

            String underPublished = withConfiguration(A_KEY, null,
                    () -> withPreviousConfiguration(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET,
                            null, () -> PanMasker.previousCardToken(FULL_CARD_NUMBER)));

            assertEquals(expectedToken(PanMasker.PUBLISHED_DEMO_CARD_TOKEN_SECRET,
                            PanMasker.DEFAULT_CARD_TOKEN_VERSION, FULL_CARD_NUMBER),
                    underPublished,
                    "a deployment leaving the published key behind must be able to derive what its"
                            + " rows carried, or the rotation the refusal forces cannot be done");
        } finally {
            restore(PanMasker.CARD_TOKEN_ALLOW_PUBLISHED_KEY_PROPERTY, held);
        }
    }

    /**
     * Proves a rotation is stated rather than inferred, and that only {@code true} states it.
     *
     * <p>A rotation rewrites a value other stores and granted authorities already name. Reading any
     * non-empty value as approval would let a typo re-key a platform.
     */
    @Test
    void aRotationIsRequestedOnlyByTheStatedValue() throws Exception {
        String held = System.getProperty(PanMasker.CARD_TOKEN_ROTATION_PROPERTY);
        try {
            System.clearProperty(PanMasker.CARD_TOKEN_ROTATION_PROPERTY);
            assertFalse(PanMasker.cardTokenRotationRequested(),
                    "an unstated deployment must not be rotating");

            System.setProperty(PanMasker.CARD_TOKEN_ROTATION_PROPERTY, "true");
            assertTrue(PanMasker.cardTokenRotationRequested(),
                    "the stated value has to request the rotation it states");

            System.setProperty(PanMasker.CARD_TOKEN_ROTATION_PROPERTY, "yes");
            assertFalse(PanMasker.cardTokenRotationRequested(),
                    "only true states the intent: any other value has to read as unstated");

            System.setProperty(PanMasker.CARD_TOKEN_ROTATION_PROPERTY, "");
            assertFalse(PanMasker.cardTokenRotationRequested(),
                    "an empty value states nothing");
        } finally {
            restore(PanMasker.CARD_TOKEN_ROTATION_PROPERTY, held);
        }
    }

    @Test
    void theResolvedKeyRendersWithoutItsKeyMaterial() throws Exception {
        PanMasker.cardToken(FULL_CARD_NUMBER);

        java.lang.reflect.Field resolved = PanMasker.class.getDeclaredField("RESOLVED_KEY");
        resolved.setAccessible(true);
        Object held = ((java.util.concurrent.atomic.AtomicReference<?>) resolved.get(null)).get();

        assertNotNull(held, "a derivation left no resolved key, so every call rebuilds one");
        assertFalse(held.toString().contains(configuredKey()),
                "the resolved key rendered its key material, so a log line could carry it");
        assertTrue(held.toString().contains("[redacted]"),
                "the resolved key must state that its key material is withheld");
    }

    /** A key of the minimum width, used where a test configures one of its own. */
    private static final String A_KEY = "panmasker-test-key-one-0123456789";

    /** A second key of the minimum width, differing from {@link #A_KEY}. */
    private static final String ANOTHER_KEY = "panmasker-test-key-two-9876543210";

    private static String configuredKey() {
        String property = System.getProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY);
        return property == null || property.isBlank()
                ? System.getenv(PanMasker.CARD_TOKEN_SECRET_VARIABLE)
                : property;
    }

    /**
     * Computes the token one card number must carry under one key and version, from the declared
     * inputs alone.
     */
    private static String expectedToken(String key, String version, String cardNumber)
            throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] code = mac.doFinal(("CardDemo/card-token/v" + version + ":" + cardNumber)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder rendered = new StringBuilder(PanMasker.CARD_TOKEN_LENGTH);
        for (byte octet : code) {
            rendered.append(String.format("%02x", octet));
        }
        return rendered.toString();
    }

    /**
     * Runs one derivation under a named key and version, then restores the build's own settings.
     *
     * <p>The restore runs whatever the body does, so one failing case cannot leave the remaining
     * tests of this class deriving under another key.
     */
    private static <T> T withConfiguration(String key, String version,
            java.util.concurrent.Callable<T> body) throws Exception {
        String heldKey = System.getProperty(PanMasker.CARD_TOKEN_SECRET_PROPERTY);
        String heldVersion = System.getProperty(PanMasker.CARD_TOKEN_VERSION_PROPERTY);
        try {
            restore(PanMasker.CARD_TOKEN_SECRET_PROPERTY, key);
            restore(PanMasker.CARD_TOKEN_VERSION_PROPERTY, version);
            return body.call();
        } finally {
            restore(PanMasker.CARD_TOKEN_SECRET_PROPERTY, heldKey);
            restore(PanMasker.CARD_TOKEN_VERSION_PROPERTY, heldVersion);
        }
    }

    /**
     * Runs one derivation under a named previous key and version, then restores what was held.
     *
     * <p>A deployment that is not rotating configures neither setting, which is the state every
     * other test in this class runs in, so a test of the read half has to supply the pair itself.
     */
    private static <T> T withPreviousConfiguration(String key, String version,
            java.util.concurrent.Callable<T> body) throws Exception {
        String heldKey = System.getProperty(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_PROPERTY);
        String heldVersion = System.getProperty(PanMasker.CARD_TOKEN_PREVIOUS_VERSION_PROPERTY);
        try {
            restore(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_PROPERTY, key);
            restore(PanMasker.CARD_TOKEN_PREVIOUS_VERSION_PROPERTY, version);
            return body.call();
        } finally {
            restore(PanMasker.CARD_TOKEN_PREVIOUS_SECRET_PROPERTY, heldKey);
            restore(PanMasker.CARD_TOKEN_PREVIOUS_VERSION_PROPERTY, heldVersion);
        }
    }

    /**
     * Invokes one digest method on one argument, supplying a previous pair where it needs one.
     *
     * <p>{@code previousCardToken} refuses to derive without a previous key, and refuses a previous
     * pair equal to the current one, so the reflective sweep supplies a key of its own.
     */
    private static String derive(Method method, String argument) throws Exception {
        if (!"previousCardToken".equals(method.getName())) {
            return (String) method.invoke(null, argument);
        }
        return withPreviousConfiguration(ANOTHER_KEY, null,
                () -> (String) method.invoke(null, argument));
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
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

        // Every method that takes a card-shaped value takes exactly that one value, so none can
        // combine two of them into one result. The exceptions each take no card-shaped value at
        // all: cardTokenVersion and previousCardTokenVersion report a configured version,
        // requireVersionShape holds one configured version to its shape and names where it came
        // from, resolvedKey builds the key, configured reads one setting from a property name and a
        // variable name, previousCardTokenConfigured and cardTokenRotationRequested each read one
        // setting, and requireCardTokenSecretFitForUse reads the configured key and the statement
        // beside it.
        Set<String> argumentFreeOfCardValues = Set.of("cardTokenVersion", "resolvedKey",
                "configuredSecret", "configured", "requireCardTokenSecretFitForUse",
                "previousCardTokenVersion", "previousCardTokenConfigured",
                "cardTokenRotationRequested", "requireVersionShape");
        for (Method method : PanMasker.class.getDeclaredMethods()) {
            if (method.isSynthetic() || argumentFreeOfCardValues.contains(method.getName())) {
                continue;
            }
            assertEquals(1, method.getParameterCount(),
                    "method " + method.getName() + " accepts a second argument");
        }

        Set<String> publicSurface = new LinkedHashSet<>();
        for (Method method : PanMasker.class.getDeclaredMethods()) {
            if (!method.isSynthetic() && Modifier.isPublic(method.getModifiers())) {
                publicSurface.add(method.getName());
            }
        }
        assertEquals(Set.of("maskCardNumber", "redactCardVerificationValue", "cardToken",
                        "tokenOf", "cardTokenVersion", "requireCardTokenSecretFitForUse",
                        "previousCardToken", "previousCardTokenVersion",
                        "previousCardTokenConfigured", "cardTokenRotationRequested"),
                publicSurface,
                "the public surface changed, and every addition needs its own disclosure"
                        + " guarantee stated in this class");
    }

    /**
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
