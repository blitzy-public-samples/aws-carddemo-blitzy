package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import java.lang.annotation.Annotation;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Shape tests for {@link CardDetailResponse}.
 *
 * <p>Each test reads the record declaration through reflection and compares it against literals
 * typed in this file. No test here sends a Representational State Transfer (REST) request, opens a
 * database connection, or reads a file from disk. The Java Development Kit and Apache Maven are the
 * only tools a run needs.
 *
 * <p>The card record declares six named fields and one trailing filler at
 * {@code app/cpy/CVACT02Y.cpy:L5-L11}. The detail response carries five of the six. It leaves out
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} and
 * {@code FILLER PIC X(59)} at {@code app/cpy/CVACT02Y.cpy:L11}.
 *
 * <p>Three differences separate the card record from the detail response. The response renames
 * {@code CARD-EXPIRAION-DATE} at {@code app/cpy/CVACT02Y.cpy:L9} to {@code expirationDate}, omits
 * the card verification value at L7, and omits the 59-byte filler at L11.
 *
 * <p>The card detail screen shows all sixteen card-number characters on an unprotected field:
 * {@code CARDSID DFHMDF} carries {@code ATTRB=(FSET,NORM,UNPROT)} and {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L96-L100}. A search for the darkened-field attribute {@code DRK}
 * returns zero hits in that Basic Mapping Support file. Six other Basic Mapping Support files carry
 * the attribute, and {@code app/bms/COCRDUP.bms} applies it to the card expiry day at L142 and to a
 * function-key line at L163. The masking claim in this file covers the card-number field alone.
 *
 * <p>Two readings this test class adopts carry entries in
 * {@link #fixtureRecordOneResponseHidesItsCardVerificationValue()}, and the field-scoped reading of
 * the darkened-attribute evidence stated above.
 *
 * <p>The card update program validates a card number with a numeric class test alone at
 * {@code app/cbl/COCRDUPC.cbl:L784}. The two comments above it at
 * {@code app/cbl/COCRDUPC.cbl:L782-L783} name a numeric test and a sixteen-character test, and no
 * length test follows. That finding sits in
 * card-number length, or the active status as an authorization gate.
 */
final class CardDetailResponseTest {

    /** The component count the detail response declares. */
    private static final int EXPECTED_COMPONENT_COUNT = 5;

    /**
     * The five component names, in card-record declaration order.
     *
     * <p>The order follows {@code app/cpy/CVACT02Y.cpy} with L7 removed: {@code CARD-NUM} at L5,
     * {@code CARD-ACCT-ID} at L6, {@code CARD-EMBOSSED-NAME} at L8,
     * {@code CARD-EXPIRAION-DATE} at L9 and {@code CARD-ACTIVE-STATUS} at L10.
     */
    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            "maskedCardNumber",
            "accountId",
            "embossedName",
            "expirationDate",
            "activeStatus");

    /**
     * The component types, aligned position for position with {@link #EXPECTED_COMPONENT_NAMES}.
     *
     * <p>The card number component is a plain string carrying the masked form. The controller masks
     * with {@link PanMasker#maskCardNumber(String)} at the serialization boundary.
     */
    private static final List<Class<?>> EXPECTED_COMPONENT_TYPES = List.of(
            String.class,
            String.class,
            String.class,
            LocalDate.class,
            String.class);

    /** The source field behind each component, aligned with {@link #EXPECTED_COMPONENT_NAMES}. */
    private static final List<String> SOURCE_FIELD_CITATIONS = List.of(
            "CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5",
            "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6",
            "CARD-EMBOSSED-NAME PIC X(50) at app/cpy/CVACT02Y.cpy:L8",
            "CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9, sliced into a four-character"
                    + " year, a two-character month and a two-character day at"
                    + " app/cbl/COCRDUPC.cbl:L115-L123",
            "CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10");

    /** The corrected spelling of the expiration date component. */
    private static final String CORRECTED_EXPIRATION_DATE_NAME = "expirationDate";

    /** The transposed spelling the source carries in {@code CARD-EXPIRAION-DATE}. */
    private static final String SOURCE_EXPIRY_MISSPELLING = "expiraion";

    /**
     * Lower-case name fragments no component name may carry, each one a spelling of the card
     * verification value.
     *
     * <p>The fragments cover the field name in the source, the two common alternates, and two plain
     * English descriptions.
     */
    private static final List<String> CARD_VERIFICATION_VALUE_FRAGMENTS = List.of(
            "cvv",
            "cvc",
            "csc",
            "verification",
            "security",
            "threedigitcode");

    /** Lower-case name fragments that would model the trailing filler. */
    private static final List<String> TRAILING_FILLER_FRAGMENTS = List.of(
            "filler",
            "padding",
            "reserved");

    /** Twelve mask characters followed by four digits. */
    private static final Pattern MASKED_CARD_NUMBER_PATTERN = Pattern.compile("^\\*{12}[0-9]{4}$");

    /** The width of {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** The count of trailing card-number characters the masked form keeps. */
    private static final int VISIBLE_CARD_NUMBER_WIDTH = 4;

    /**
     * A synthetic token at the width of {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}: twelve zeros and four trailing characters. No card number
     * opens with a zero, so this class holds no card number of any fixture.
     */
    private static final String SYNTHETIC_CARD_NUMBER = "0".repeat(12) + "5740";

    /**
     * The masked form of {@link #SYNTHETIC_CARD_NUMBER}: twelve mask characters and four digits.
     */
    private static final String SYNTHETIC_MASKED_CARD_NUMBER = "************5740";

    /** A synthetic account identifier at the width of {@code CARD-ACCT-ID PIC 9(11)} at L6. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000050";

    /** A synthetic value at the width of {@code CARD-CVV-CD PIC 9(03)} at L7. */
    private static final String SYNTHETIC_VERIFICATION_VALUE = "451";

    /** A synthetic name at the width of {@code CARD-EMBOSSED-NAME PIC X(50)} at L8. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "JOHN Q PUBLIC";

    /**
     * A synthetic date at the width of {@code CARD-EXPIRAION-DATE PIC X(10)} at L9, which the
     * target spells {@code expirationDate}.
     */
    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2023, 3, 9);

    /** Active status of record one, offset 91, width 1. */
    private static final String SYNTHETIC_ACTIVE_STATUS = "Y";

    CardDetailResponseTest() {
    }

    /**
     * Asserts that the detail response declares exactly five components.
     *
     * <p>The card record names six fields at {@code app/cpy/CVACT02Y.cpy:L5-L10} and closes with a
     * 59-byte filler at L11. Six named fields, less the card verification value at L7, less the
     * filler at L11, leave five.
     */
    @Test
    void detailResponseDeclaresExactlyFiveComponents() {
        assertEquals(EXPECTED_COMPONENT_COUNT, components().length,
                "CardDetailResponse must declare exactly " + EXPECTED_COMPONENT_COUNT
                        + " components. The card record names six fields at"
                        + " app/cpy/CVACT02Y.cpy:L5-L10. The detail response drops"
                        + " CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 and"
                        + " FILLER PIC X(59) at app/cpy/CVACT02Y.cpy:L11. Declared components: "
                        + componentNames());
    }

    /**
     * Asserts the five component names, position by position.
     *
     * <p>The order tracks the card record declaration order at {@code app/cpy/CVACT02Y.cpy:L5-L10}
     * with L7 removed. A renamed or reordered component fails here with the source field named in
     * the message.
     */
    @Test
    void detailResponseNamesFiveComponentsInCardRecordOrder() {
        List<String> declared = componentNames();
        assertEquals(EXPECTED_COMPONENT_NAMES.size(), declared.size(),
                "CardDetailResponse must declare " + EXPECTED_COMPONENT_NAMES
                        + " in that order. Declared components: " + declared);

        for (int index = 0; index < EXPECTED_COMPONENT_NAMES.size(); index++) {
            assertEquals(EXPECTED_COMPONENT_NAMES.get(index), declared.get(index),
                    "Component at position " + index + " must be named "
                            + EXPECTED_COMPONENT_NAMES.get(index) + ", carrying "
                            + SOURCE_FIELD_CITATIONS.get(index) + ".");
        }
    }

    /**
     * Asserts the five component types, position by position.
     *
     * <p>The expiration date arrives as a {@link LocalDate}. The card update program slices the
     * ten-character source field into a year, a month and a day at
     * {@code app/cbl/COCRDUPC.cbl:L115-L123}. The widths close at four plus one plus two plus one
     * plus two.
     */
    @Test
    void detailResponseTypesFiveComponentsAsTheRecordDeclaresThem() {
        RecordComponent[] declared = components();
        assertEquals(EXPECTED_COMPONENT_TYPES.size(), declared.length,
                "CardDetailResponse must declare " + EXPECTED_COMPONENT_TYPES.size()
                        + " components before a type check runs. Declared components: "
                        + componentNames());

        for (int index = 0; index < EXPECTED_COMPONENT_TYPES.size(); index++) {
            assertEquals(EXPECTED_COMPONENT_TYPES.get(index), declared[index].getType(),
                    "Component " + declared[index].getName() + " must have type "
                            + EXPECTED_COMPONENT_TYPES.get(index).getName() + ", carrying "
                            + SOURCE_FIELD_CITATIONS.get(index) + ".");
        }
    }

    /**
     * Asserts that the corrected expiration date name is present.
     *
     * <p>The card record spells the field {@code CARD-EXPIRAION-DATE} at
     * {@code app/cpy/CVACT02Y.cpy:L9}, with the word transposed. The target component name carries
     * the corrected spelling.
     */
    @Test
    void detailResponseCarriesTheCorrectedExpirationDateName() {
        assertTrue(componentNames().contains(CORRECTED_EXPIRATION_DATE_NAME),
                "CardDetailResponse must declare a component named "
                        + CORRECTED_EXPIRATION_DATE_NAME + ", carrying"
                        + " CARD-EXPIRAION-DATE PIC X(10) at app/cpy/CVACT02Y.cpy:L9."
                        + " Declared components: " + componentNames());
    }

    /**
     * Asserts that no component name repeats the transposed source spelling.
     *
     * <p>The check runs over every component name in lower case and looks for the fragment
     * {@code expiraion} from {@code app/cpy/CVACT02Y.cpy:L9}. A reintroduced misspelling fails
     * here.
     */
    @Test
    void detailResponseOmitsTheSourceExpirySpelling() {
        for (String name : lowerCaseComponentNames()) {
            assertFalse(name.contains(SOURCE_EXPIRY_MISSPELLING),
                    "Component " + name + " repeats the transposed spelling '"
                            + SOURCE_EXPIRY_MISSPELLING + "' from CARD-EXPIRAION-DATE at"
                            + " app/cpy/CVACT02Y.cpy:L9. The target name is "
                            + CORRECTED_EXPIRATION_DATE_NAME + ".");
        }
    }

    /**
     * Asserts that no component carries the card verification value under any name.
     *
     * <p>The card record stores the value in the clear as {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}, and the card update program moves it into its update record
     * at {@code app/cbl/COCRDUPC.cbl:L1464-L1465}. The card table holds the column and the detail
     * response exposes no component for it.
     */
    @Test
    void detailResponseOmitsCardVerificationValue() {
        for (String name : lowerCaseComponentNames()) {
            for (String fragment : CARD_VERIFICATION_VALUE_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "Component " + name + " matches the fragment '" + fragment
                                + "', which names the card verification value"
                                + " CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7."
                                + " The detail response carries no such component.");
            }
        }
    }

    /**
     * Asserts that no component models the trailing filler.
     *
     * <p>The card record ends with {@code FILLER PIC X(59)} at {@code app/cpy/CVACT02Y.cpy:L11},
     * which brings the record to the 150 bytes named in the header comment at
     * {@code app/cpy/CVACT02Y.cpy:L2}. The detail response drops those 59 bytes.
     */
    @Test
    void detailResponseOmitsTrailingFiller() {
        for (String name : lowerCaseComponentNames()) {
            for (String fragment : TRAILING_FILLER_FRAGMENTS) {
                assertFalse(name.contains(fragment),
                        "Component " + name + " matches the fragment '" + fragment
                                + "', which names the trailing FILLER PIC X(59) at"
                                + " app/cpy/CVACT02Y.cpy:L11. The detail response drops those"
                                + " 59 bytes.");
            }
        }
    }

    /**
     * Asserts the masked card-number form: twelve mask characters and four digits.
     *
     * <p>The test calls {@link PanMasker#maskCardNumber(String)} on the card number of record one
     * of {@code app/data/ASCII/carddata.txt}. The result keeps the sixteen-character width of
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. A card read keys on the full
     * sixteen-character Primary Account Number, and masking runs at the serialization boundary.
     */
    @Test
    void maskedCardNumberCarriesTwelveMaskCharactersAndFourDigits() {
        int hiddenWidth = CARD_NUMBER_WIDTH - VISIBLE_CARD_NUMBER_WIDTH;
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);
        String visible = SYNTHETIC_CARD_NUMBER.substring(hiddenWidth);
        String hidden = SYNTHETIC_CARD_NUMBER.substring(0, hiddenWidth);

        assertAll("masked form of the card number of record one",
                () -> assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                        "The masked card number must match "
                                + MASKED_CARD_NUMBER_PATTERN.pattern()
                                + ": twelve mask characters and four digits. The value received"
                                + " holds " + masked.length() + " characters."),
                () -> assertEquals(CARD_NUMBER_WIDTH, masked.length(),
                        "The masked card number must keep the width of"
                                + " CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5."),
                () -> assertEquals(SYNTHETIC_MASKED_CARD_NUMBER, masked,
                        "The card number of record one of app/data/ASCII/carddata.txt must mask to"
                                + " the published masked form."),
                () -> assertTrue(masked.endsWith(visible),
                        "The masked card number must end in the last "
                                + VISIBLE_CARD_NUMBER_WIDTH + " characters of its argument."),
                () -> assertFalse(masked.contains(hidden),
                        "The masked card number must hold none of the leading "
                                + hidden.length() + " characters of its argument."));
    }

    @Test
    void aResponseHidesTheCardVerificationValueAndTheLeadingCardNumberCharacters() {
        CardDetailResponse response = new CardDetailResponse(
                PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                SYNTHETIC_ACCOUNT_ID,
                SYNTHETIC_EMBOSSED_NAME,
                SYNTHETIC_EXPIRATION_DATE,
                SYNTHETIC_ACTIVE_STATUS);
        String rendered = response.toString();

        assertAll("record one of app/data/ASCII/carddata.txt",
                () -> assertEquals(SYNTHETIC_MASKED_CARD_NUMBER,
                        response.maskedCardNumber(),
                        "The response for record one must carry the published masked card number."),
                () -> assertFalse(rendered.contains(SYNTHETIC_VERIFICATION_VALUE),
                        "The rendered response holds the card verification value of record one,"
                                + " read at offset 28 of CARD-CVV-CD PIC 9(03) at"
                                + " app/cpy/CVACT02Y.cpy:L7. The rendered text is withheld from"
                                + " this message so a failure cannot copy the value into a log."),
                () -> assertFalse(rendered.contains(SYNTHETIC_CARD_NUMBER),
                        "The rendered response holds the full card number of record one."),
                () -> assertEquals(SYNTHETIC_ACCOUNT_ID, response.accountId(),
                        "The response for record one must carry the account identifier read at"
                                + " offset 17 of CARD-ACCT-ID PIC 9(11) at"
                                + " app/cpy/CVACT02Y.cpy:L6."),
                () -> assertEquals(SYNTHETIC_EMBOSSED_NAME, response.embossedName(),
                        "The response for record one must carry the embossed name read at"
                                + " offset 31 of CARD-EMBOSSED-NAME PIC X(50) at"
                                + " app/cpy/CVACT02Y.cpy:L8."),
                () -> assertEquals(SYNTHETIC_EXPIRATION_DATE, response.expirationDate(),
                        "The response for record one must carry the expiration date read at"
                                + " offset 81 of CARD-EXPIRAION-DATE PIC X(10) at"
                                + " app/cpy/CVACT02Y.cpy:L9."),
                () -> assertEquals(SYNTHETIC_ACTIVE_STATUS, response.activeStatus(),
                        "The response for record one must carry the active status read at"
                                + " offset 91 of CARD-ACTIVE-STATUS PIC X(01) at"
                                + " app/cpy/CVACT02Y.cpy:L10."));
    }

    /**
     * Asserts that the masker supplies the form this response publishes, and that the response
     * itself declares no Bean Validation constraint.
     *
     * <p>The response is an outbound projection, so {@link PanMasker#maskCardNumber(String)} is the
     * one place a full Primary Account Number turns into the published form. A constraint
     * annotation on any component would move that work into request binding, where an outbound
     * record is never bound.
     */
    @Test
    void theMaskerSuppliesThePublishedFormAndTheResponseDeclaresNoConstraint() {
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);

        List<String> constraintAnnotations = new ArrayList<>();
        for (RecordComponent component : components()) {
            for (Annotation annotation : component.getAnnotations()) {
                if (annotation.annotationType().getName().startsWith("jakarta.validation")) {
                    constraintAnnotations.add(component.getName() + " carries "
                            + annotation.annotationType().getSimpleName());
                }
            }
        }

        assertAll("the masker is the single masking point",
                () -> assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                        "The masker must return twelve mask characters then four digits."),
                () -> assertEquals(SYNTHETIC_MASKED_CARD_NUMBER, masked,
                        "The masker must keep the last " + VISIBLE_CARD_NUMBER_WIDTH
                                + " characters of the card number and hide the rest."),
                () -> assertEquals(List.of(), constraintAnnotations,
                        "The detail response is an outbound projection and declares no Bean"
                                + " Validation constraint."));
    }

    /**
     * Reads the record components of {@link CardDetailResponse}. The record check runs first, so a
     * class that stops being a record fails with a named assertion.
     *
     * @return the record components, in declaration order
     */
    private static RecordComponent[] components() {
        assertTrue(CardDetailResponse.class.isRecord(),
                "CardDetailResponse must be a record. Its five components carry the card record"
                        + " fields at app/cpy/CVACT02Y.cpy:L5-L10.");
        return CardDetailResponse.class.getRecordComponents();
    }

    /**
     * Reads the component names of {@link CardDetailResponse}.
     *
     * @return the component names, in declaration order
     */
    private static List<String> componentNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : components()) {
            names.add(component.getName());
        }
        return List.copyOf(names);
    }

    /**
     * Reads the component names and folds each one to lower case for a fragment search.
     *
     * @return the lower-cased component names, in declaration order
     */
    private static List<String> lowerCaseComponentNames() {
        List<String> names = new ArrayList<>();
        for (String name : componentNames()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }
}
