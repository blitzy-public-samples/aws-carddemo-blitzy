package com.carddemo.card.api.dto;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A card number in its masked form: twelve mask characters followed by the last four digits.
 *
 * <p>ADDITIVE. No CardDemo program masks a Primary Account Number (PAN). The card record stores it
 * as {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}, and the card detail map defines
 * the field at the full sixteen characters, {@code ATTRB=(FSET,NORM,UNPROT)} with
 * {@code LENGTH=16}, at {@code app/bms/COCRDSL.bms:L96-L100}.
 *
 * <p>This type exists so a response cannot carry a full PAN by accident. {@link #of(String)} is the
 * only way to build one, and it accepts nothing but the masked form. A raw sixteen-digit card
 * number fails that check, so no mapping mistake, refactor, or new response type can serialize a
 * PAN through a component of this type. Producing the masked form is a separate, explicit step:
 * {@code com.carddemo.cobol.PanMasker.maskCardNumber} takes the full PAN and returns the value this
 * type accepts.
 *
 * <p>Reads and lookups still run on the full sixteen characters, exactly as the source does. The
 * card cross-reference read at {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on the whole field, so
 * masking belongs at the serialization boundary and nowhere earlier.
 *
 * <p>{@link #value()} carries the annotation that makes this type serialize as a plain JavaScript
 * Object Notation (JSON) string, so a response body is unchanged by the introduction of the type.
 *
 *
 * @param value the masked card number, exactly {@value #MASKED_LENGTH} characters and matching
 *              {@link #MASKED_PATTERN}
 */
public record MaskedCardNumber(@JsonValue String value) {

    /** Width of the masked form, from {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    public static final int MASKED_LENGTH = 16;

    /**
     * The only shape this type accepts: twelve asterisks then four digits. The same pattern
     * constrains {@code maskedCardNumber} in the event schemas under
     * {@code card-platform/libs/event-contracts/src/main/resources/schemas}.
     */
    public static final String MASKED_PATTERN = "^\\*{12}[0-9]{4}$";

    /** Compiled once from {@link #MASKED_PATTERN}. */
    private static final Pattern MASKED = Pattern.compile(MASKED_PATTERN);

    /**
     * Checks the masked form.
     *
     * @throws IllegalArgumentException when {@code value} is null or does not match
     *         {@link #MASKED_PATTERN}. The failure text names the pattern and the length received,
     *         never the value, so a rejected card number cannot reach a log.
     */
    public MaskedCardNumber {
        if (value == null || !MASKED.matcher(value).matches()) {
            throw new IllegalArgumentException("a masked card number matches " + MASKED_PATTERN
                    + " and the supplied value "
                    + (value == null ? "is null" : "holds " + value.length() + " characters"));
        }
    }

    /**
     * Returns the masked card number for an already-masked value.
     *
     * <p>A full card number does not match {@link #MASKED_PATTERN} and is refused. Mask it first
     * with {@code com.carddemo.cobol.PanMasker.maskCardNumber}, then pass the result here.
     *
     * @param maskedValue twelve mask characters followed by four digits
     * @return the value type holding that masked card number
     * @throws IllegalArgumentException when {@code maskedValue} is not in the masked form
     */
    public static MaskedCardNumber of(String maskedValue) {
        return new MaskedCardNumber(maskedValue);
    }

    /**
     * Returns the masked card number itself, so a log line or a message carries the masked form and
     * never a full card number.
     *
     * @return the masked card number
     */
    @Override
    public String toString() {
        return value;
    }
}
