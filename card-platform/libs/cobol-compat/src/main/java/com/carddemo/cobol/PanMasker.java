package com.carddemo.cobol;

/**
 * Masks a Primary Account Number (PAN) for a published payload or a log line.
 *
 * <p>ADDITIVE. This class has no source ancestor. No masking exists in the CardDemo COBOL
 * source. The locators below are references, not ancestors.
 *
 * <p>The card record stores the card number as {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5}. The same record stores the card verification value in the
 * clear as {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. The card detail
 * screen shows all sixteen characters unprotected: {@code CARDSID DFHMDF} carries
 * {@code ATTRB=(FSET,NORM,UNPROT)} and {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L96-100}.
 *
 * <p>Two obligations fall on the caller, because a helper class cannot enforce either one.
 * Use {@link #maskCardNumber(String)} before emitting a card number to an event, a log or an
 * application programming interface response, and never emit a card verification value.
 * Resolve the authorization decision on the full card number first: the card cross-reference
 * lookup keys on all sixteen characters.
 *
 * <p>Card number validation in the source is a numeric class test only, at
 * {@code app/cbl/COCRDUPC.cbl:L782-784}. This class adds no further card-number validation.
 */
public final class PanMasker {

    /** The character that replaces a hidden card-number digit. */
    public static final char MASK_CHARACTER = '*';

    /** The count of trailing card-number characters left visible. */
    public static final int VISIBLE_DIGIT_COUNT = 4;

    /** The width of {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    public static final int CARD_NUMBER_LENGTH = 16;

    /** The width of {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. */
    public static final int CARD_VERIFICATION_VALUE_LENGTH = 3;

    /**
     * The fixed result of every card verification value redaction. Three mask characters,
     * matching the width of {@code CARD-CVV-CD PIC 9(03)}.
     */
    public static final String REDACTED_CARD_VERIFICATION_VALUE =
            String.valueOf(MASK_CHARACTER).repeat(CARD_VERIFICATION_VALUE_LENGTH);

    /** The twelve mask characters that precede the four visible characters. */
    private static final String MASK_PREFIX =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH - VISIBLE_DIGIT_COUNT);

    /** A card number with all sixteen characters hidden. */
    private static final String FULLY_MASKED_CARD_NUMBER =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH);

    private PanMasker() {
    }

    /**
     * Replaces every character of a card number except the last four with the mask character.
     *
     * <p>The result is always exactly {@value #CARD_NUMBER_LENGTH} characters wide, matching
     * {@code CARD-NUM PIC X(16)}. Leading and trailing whitespace is discarded before the last
     * four characters are read.
     *
     * <p>An argument wider than {@value #CARD_NUMBER_LENGTH} characters normalizes to the stored
     * width. The result keeps the last {@value #VISIBLE_DIGIT_COUNT} characters of the stripped
     * argument and no other character of it. The mask-character count therefore stays at
     * {@value #CARD_NUMBER_LENGTH} minus {@value #VISIBLE_DIGIT_COUNT}, whatever the argument
     * width.
     *
     * <p>A {@code null} argument, a blank argument, and an argument of
     * {@value #VISIBLE_DIGIT_COUNT} characters or fewer each yield a fully masked result. No
     * argument value passes through unmasked. No method on this class reverses the result.
     *
     * @param cardNumber the full card number, as stored in {@code CARD-NUM}; may be
     *                   {@code null}
     * @return sixteen characters, ending in at most the last four characters of the argument
     */
    public static String maskCardNumber(String cardNumber) {
        if (cardNumber == null) {
            return FULLY_MASKED_CARD_NUMBER;
        }

        String value = cardNumber.strip();
        if (value.length() <= VISIBLE_DIGIT_COUNT) {
            return FULLY_MASKED_CARD_NUMBER;
        }

        String visible = value.substring(value.length() - VISIBLE_DIGIT_COUNT);
        return MASK_PREFIX + visible;
    }

    /**
     * Replaces a card verification value with a fixed redaction of
     * {@value #CARD_VERIFICATION_VALUE_LENGTH} mask characters.
     *
     * <p>Every argument yields the same result, including {@code null}. No digit of the
     * argument reaches the result. The card record stores this field as
     * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}; a caller must not
     * emit it in any form other than this redaction.
     *
     * @param cardVerificationValue the stored card verification value; may be {@code null}
     * @return three mask characters
     */
    public static String redactCardVerificationValue(String cardVerificationValue) {
        return REDACTED_CARD_VERIFICATION_VALUE;
    }
}
