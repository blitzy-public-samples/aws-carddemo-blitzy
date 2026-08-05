package com.carddemo.cobol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Masks a Primary Account Number (PAN) for a published payload or a log line, and derives the
 * card token that identifies a card where a card number must not travel.
 *
 * <p>This class has no COBOL ancestor. No masking exists in the CardDemo source, so the locators
 * below are references rather than ancestors.
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
 * <p>A masked card number identifies nothing. Twelve of its sixteen characters are the mask, so
 * two cards ending in the same four digits produce one masked value. {@link #cardToken(String)}
 * supplies the identity instead: one card, one token, and no two cards share one. A route key, a
 * storage key and an ownership authority take the token, and the masked form stays display data.
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

    /**
     * The width of a card token: the sixty-four hexadecimal characters a SHA-256 digest renders
     * as.
     */
    public static final int CARD_TOKEN_LENGTH = 64;

    /**
     * The shape of a card token: exactly {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters.
     *
     * <p>A schema document, a check constraint and a route parameter each declare this shape, so
     * a value that is not a token is refused before it reaches a query.
     */
    public static final String CARD_TOKEN_PATTERN = "^[0-9a-f]{" + CARD_TOKEN_LENGTH + "}$";

    /** The token a caller receives for an absent card number: every character a zero. */
    public static final String ABSENT_CARD_TOKEN = "0".repeat(CARD_TOKEN_LENGTH);

    /**
     * The label the token digest covers ahead of the card number.
     *
     * <p>The label separates this digest from every other digest of the same card number. A
     * digest taken elsewhere over the bare card number does not equal a token, so one cannot be
     * mistaken for the other. The trailing version marker is what a later token scheme changes.
     */
    private static final String CARD_TOKEN_LABEL = "CardDemo/card-token/v1:";

    /** The digest algorithm {@link #cardToken(String)} applies. */
    private static final String CARD_TOKEN_ALGORITHM = "SHA-256";

    /** The twelve mask characters that precede the four visible characters. */
    private static final String MASK_PREFIX =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH - VISIBLE_DIGIT_COUNT);

    /**
     * A card number with all sixteen characters hidden, which
     * {@link #maskCardNumber(String)} returns for an argument that names no card number.
     */
    public static final String FULLY_MASKED_CARD_NUMBER =
            String.valueOf(MASK_CHARACTER).repeat(CARD_NUMBER_LENGTH);

    /** Renders a digest as lower-case hexadecimal characters. */
    private static final HexFormat HEX = HexFormat.of();

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

    /**
     * Derives the card token of one card number.
     *
     * <p>The token is the {@value #CARD_TOKEN_ALGORITHM} digest of a fixed label followed by the
     * stripped card number, rendered as {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters. It always matches {@value #CARD_TOKEN_PATTERN}.
     *
     * <p>Three properties are what callers rely on. The same card number always yields the same
     * token, so a service that never meets the card number twice still keys its rows on one
     * value. Two different card numbers yield different tokens, which is the identity a masked
     * card number cannot supply. No character of the card number survives into the token, so a
     * token is safe in a route, an access log, a trace and a stored key.
     *
     * <p>The derivation is a digest and not a keyed function, so the token needs no configuration
     * and no shared secret: any service holding a card number reaches the same token, and a
     * service holding only a token cannot read it back. That is the whole guarantee this method
     * makes. A deployment that must also withstand an offline search of the card-number space
     * replaces the digest with a keyed function or a vault-issued surrogate, and the label above
     * is the one value such a change turns over.
     *
     * <p>Leading and trailing whitespace is discarded, so a value read from a fixed-width
     * {@code CARD-NUM PIC X(16)} field and the same value trimmed produce one token. Nothing else
     * is normalized: a value of another width is a different card number and yields a different
     * token.
     *
     * @param cardNumber the full card number, as stored in {@code CARD-NUM}; must not be
     *                   {@code null} and must hold at least one non-whitespace character
     * @return {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     * @throws NullPointerException     if {@code cardNumber} is {@code null}
     * @throws IllegalArgumentException if {@code cardNumber} holds no non-whitespace character,
     *                                  because a blank value names no card and every blank value
     *                                  would collapse onto one token
     */
    /**
     * Returns the token of one card number, or {@link #ABSENT_CARD_TOKEN} when none is supplied.
     *
     * <p>The same value {@link #cardToken(String)} returns. Two names exist because callers on both
     * sides of the platform were written against different ones, and a token has to be one value
     * everywhere or a read model keyed on it splits.
     *
     * @param cardNumber the full card number, or {@code null} or blank when the caller holds none
     * @return {@value #CARD_TOKEN_LENGTH} lower-case hexadecimal characters
     */
    public static String tokenOf(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return ABSENT_CARD_TOKEN;
        }
        return cardToken(cardNumber);
    }

    public static String cardToken(String cardNumber) {
        if (cardNumber == null) {
            throw new NullPointerException("cardNumber is required to derive a card token");
        }

        String value = cardNumber.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "cardNumber holds no character and names no card to tokenize");
        }

        byte[] covered = (CARD_TOKEN_LABEL + value).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(digestOf(covered));
    }

    /**
     * Digests the bytes one token covers.
     *
     * <p>{@value #CARD_TOKEN_ALGORITHM} is required of every Java platform, so the checked
     * exception the lookup declares cannot arise here. It becomes an error rather than a
     * swallowed condition, because a platform without the algorithm cannot key a row at all.
     *
     * @param covered the label and the card number, as bytes
     * @return the digest
     * @throws IllegalStateException if the runtime does not supply
     *                               {@value #CARD_TOKEN_ALGORITHM}
     */
    private static byte[] digestOf(byte[] covered) {
        try {
            return MessageDigest.getInstance(CARD_TOKEN_ALGORITHM).digest(covered);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(
                    "this runtime supplies no " + CARD_TOKEN_ALGORITHM + " digest, so no card "
                            + "token can be derived", absent);
        }
    }
}
