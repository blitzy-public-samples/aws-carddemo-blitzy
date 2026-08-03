package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tests for {@link MaskedCardNumber}, the masked-card-number value type.
 *
 * <p>Each test builds a value in memory or serializes one small record. No test here sends a
 * Representational State Transfer (REST) request, opens a database connection or reads a file from
 * disk. The Java Development Kit and Apache Maven are the only tools a run needs.
 *
 * <p>The type exists so a response cannot carry a full Primary Account Number (PAN) by accident.
 * The card record stores the number as {@code CARD-NUM PIC X(16)} at
 * {@code app/cpy/CVACT02Y.cpy:L5}, and no CardDemo program masks it: the card detail map defines the
 * field at the full sixteen characters, {@code ATTRB=(FSET,NORM,UNPROT)} with {@code LENGTH=16}, at
 * {@code app/bms/COCRDSL.bms:L96-L100}. Masking is therefore an addition.
 *
 * <p>Two properties matter and both are asserted below. Construction accepts the masked form alone,
 * so a mapping that forgot to mask fails at the boundary rather than in a client's log. Serialization
 * emits a plain JavaScript Object Notation (JSON) string, so introducing the type leaves every
 * response body unchanged.
 *
 * <p>Reads still run on the full sixteen characters. The card cross-reference read at
 * {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on the whole field, so masking belongs at the
 * serialization boundary and nowhere earlier. No test here masks a value before a lookup.
 */
final class MaskedCardNumberTest {

    /**
     * A synthetic token at the width of {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}: twelve zeros and four trailing characters. No card number
     * opens with a zero, so this class holds no card number of any fixture.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "0".repeat(12) + "5740";

    /** The masked form of {@link #SYNTHETIC_CARD_NUMBER}: twelve mask characters and four digits. */
    private static final String FIXTURE_MASKED_CARD_NUMBER = "************5740";

    /** Account identifier of record one, offset 17, width 11. */
    private static final String FIXTURE_ACCOUNT_ID = "00000000050";

    /** A synthetic value at the width of {@code CARD-CVV-CD PIC 9(03)} at L7. */
    private static final String SYNTHETIC_VERIFICATION_VALUE = "451";

    /** A synthetic name at the width of {@code CARD-EMBOSSED-NAME PIC X(50)} at L8. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "JOHN Q PUBLIC";

    /** Expiration date of record one, offset 81, width 10, held in the source as 2023-03-09. */
    private static final LocalDate FIXTURE_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** Active status of record one, offset 91, width 1. */
    private static final String FIXTURE_ACTIVE_STATUS = "Y";

    /** Values the type refuses, each one a shape that would put card data in a response. */
    private static final List<String> REJECTED_VALUES = List.of(
            SYNTHETIC_CARD_NUMBER,
            "**********5740",
            "**************40",
            "***********45740",
            "****5740********",
            "************574",
            "************57400",
            "************574a",
            "************ 740",
            "            5740",
            "",
            "   ");

    /** Serializes with the mapper the services use, so the assertion reflects a real response. */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** JUnit builds one instance of this class per test method through this constructor. */
    MaskedCardNumberTest() {
    }

    /**
     * Asserts the masked form is accepted and returned unchanged.
     *
     * <p>{@link PanMasker#maskCardNumber(String)} produces the value, and the type accepts exactly
     * that output. The two components of the pair are kept in step by the shared pattern.
     */
    @Test
    void theMaskedFormIsAcceptedAndReturnedUnchanged() {
        MaskedCardNumber masked = MaskedCardNumber.of(PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER));

        assertAll("the masked card number of record one of app/data/ASCII/carddata.txt",
                () -> assertEquals(FIXTURE_MASKED_CARD_NUMBER, masked.value(),
                        "The type must return the masked form it was given."),
                () -> assertEquals(FIXTURE_MASKED_CARD_NUMBER, masked.toString(),
                        "The rendered form must be the masked form, so a log line carries no full"
                                + " card number."),
                () -> assertEquals(MaskedCardNumber.MASKED_LENGTH, masked.value().length(),
                        "The masked form must keep the width of CARD-NUM PIC X(16) at"
                                + " app/cpy/CVACT02Y.cpy:L5."),
                () -> assertEquals(masked, MaskedCardNumber.of(FIXTURE_MASKED_CARD_NUMBER),
                        "Two values built from the same masked form must be equal."));
    }

    /**
     * Asserts a full card number cannot become a {@link MaskedCardNumber}.
     *
     * <p>This is the property the type exists for. A mapping that reads
     * {@code CARD-NUM PIC X(16)} and forgets to mask it fails here, at construction, rather than
     * serializing sixteen digits to a client.
     */
    @Test
    void aFullCardNumberIsRefused() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> MaskedCardNumber.of(SYNTHETIC_CARD_NUMBER),
                "the value type accepted a card number that had not been masked");

        assertAll("refusal of a full card number",
                () -> assertFalse(refusal.getMessage().contains(SYNTHETIC_CARD_NUMBER),
                        "The refusal text must not carry the card number it refused. It names the"
                                + " pattern and the length received."),
                () -> assertTrue(refusal.getMessage().contains(MaskedCardNumber.MASKED_PATTERN),
                        "The refusal text must name the pattern "
                                + MaskedCardNumber.MASKED_PATTERN + "."),
                () -> assertTrue(
                        refusal.getMessage().contains(
                                String.valueOf(SYNTHETIC_CARD_NUMBER.length())),
                        "The refusal text must report the length received, so a caller can see what"
                                + " arrived without the value being copied."));
    }

    /**
     * Asserts every other shape that would leak card data is refused, and that no refusal repeats
     * the value it refused.
     *
     * <p>The list covers a short mask, a long mask, a mask in the wrong position, a non-digit tail,
     * a space in the tail, an all-space value and an empty value. Each one either shows more of the
     * card number than four digits or fails to identify the card at all.
     */
    @Test
    void everyOtherShapeIsRefusedAndNoRefusalRepeatsItsValue() {
        for (String rejected : REJECTED_VALUES) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> MaskedCardNumber.of(rejected),
                    "the value type accepted a value of length " + rejected.length()
                            + " that is not the masked form");

            assertFalse(refusal.getMessage().contains(rejected) && !rejected.isEmpty(),
                    "A refusal must not repeat the value it refused. The refused value holds "
                            + rejected.length() + " characters.");
        }
    }

    /**
     * Asserts a null card number is refused and the refusal says so.
     *
     * <p>A null component would serialize as a JSON null, which reads as "no card" rather than as a
     * mapping fault. Refusing it keeps the fault at the boundary.
     */
    @Test
    void aNullCardNumberIsRefused() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> MaskedCardNumber.of(null),
                "the value type accepted a null card number");

        assertTrue(refusal.getMessage().contains("null"),
                "The refusal text for a null card number must say so.");
    }

    /**
     * Asserts the type serializes as a plain JSON string.
     *
     * <p>Without this property the value type would change every response body from a string to a
     * nested object. The annotation on the component is what keeps the body unchanged.
     */
    @Test
    void theTypeSerializesAsAPlainJsonString() {
        String json = MAPPER.writeValueAsString(
                MaskedCardNumber.of(FIXTURE_MASKED_CARD_NUMBER));

        assertEquals('"' + FIXTURE_MASKED_CARD_NUMBER + '"', json,
                "The masked card number must serialize as a plain JSON string, not as an object.");
    }

    /**
     * Asserts a card detail response serializes its masked card number as a plain string, carries no
     * card verification value, and carries no full card number.
     *
     * <p>The card record declares {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}. No response type in this package declares a component for it,
     * so no body can carry it. This test asserts the outcome on a rendered body rather than on the
     * declaration, so a future component would be caught here as well.
     */
    @Test
    void aDetailResponseSerializesTheMaskedFormAndNoCardData() {
        CardDetailResponse response = new CardDetailResponse(
                MaskedCardNumber.of(PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER)),
                FIXTURE_ACCOUNT_ID,
                SYNTHETIC_EMBOSSED_NAME,
                FIXTURE_EXPIRATION_DATE,
                FIXTURE_ACTIVE_STATUS);

        String json = MAPPER.writeValueAsString(response);

        assertAll("serialized card detail response for record one",
                () -> assertTrue(
                        json.contains("\"maskedCardNumber\":\"" + FIXTURE_MASKED_CARD_NUMBER + '"'),
                        "The body must carry the masked card number as a plain string."),
                () -> assertFalse(json.contains(SYNTHETIC_CARD_NUMBER),
                        "The body must carry no full card number. The body text is withheld from"
                                + " this message."),
                () -> assertFalse(json.contains(SYNTHETIC_VERIFICATION_VALUE),
                        "The body must carry no card verification value, read at offset 28 of"
                                + " CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7. The body text"
                                + " is withheld from this message."));
    }
}
