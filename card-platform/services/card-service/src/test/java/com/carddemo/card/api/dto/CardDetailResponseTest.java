package com.carddemo.card.api.dto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * function-key line at L163.
 *
 * <p>{@link #aResponseHidesTheCardVerificationValueAndTheLeadingCardNumberCharacters()} holds the
 * masking claim this class rests on, over the card-number field alone.
 *
 * <p>The card update program validates a card number with a numeric class test alone at
 * {@code app/cbl/COCRDUPC.cbl:L784}. The two comments above it at
 * {@code app/cbl/COCRDUPC.cbl:L782-L783} name a numeric test and a sixteen-character test, and no
 * length test follows. {@code card-platform/docs/business-rule-flags.md} carries that finding as
 * item 24, and the card status the source never reads before posting as item 3. Neither check is
 * added here.
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

    /** Generated card number at the width of {@code CARD-NUM PIC X(16)}. */
    private static final String SYNTHETIC_CARD_NUMBER = syntheticCardNumber(5740L);

    /**
     * The masked form of {@link #SYNTHETIC_CARD_NUMBER}: twelve mask characters and four digits.
     */
    private static final String SYNTHETIC_MASKED_CARD_NUMBER =
            PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);

    /** A synthetic account identifier at the width of {@code CARD-ACCT-ID PIC 9(11)} at L6. */
    private static final String SYNTHETIC_ACCOUNT_ID = syntheticDigits(11, 500_001L);

    /** A synthetic value at the width of {@code CARD-CVV-CD PIC 9(03)} at L7. */
    private static final String SYNTHETIC_VERIFICATION_VALUE = syntheticDigits(3, 451L);

    /** A synthetic name at the width of {@code CARD-EMBOSSED-NAME PIC X(50)} at L8. */
    private static final String SYNTHETIC_EMBOSSED_NAME = "JOHN Q PUBLIC";

    /**
     * A synthetic date at the width of {@code CARD-EXPIRAION-DATE PIC X(10)} at L9, which the
     * target spells {@code expirationDate}.
     */
    private static final LocalDate SYNTHETIC_EXPIRATION_DATE = LocalDate.of(2027, 3, 9);

    /** Synthetic active status at the width of {@code CARD-ACTIVE-STATUS PIC X(01)}. */
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
     * <p>The test calls {@link PanMasker#maskCardNumber(String)} on a generated synthetic value.
     * The result keeps the sixteen-character width of
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. A card read keys on the full
     * sixteen-character Primary Account Number, and masking runs at the serialization boundary.
     */
    @Test
    void maskedCardNumberCarriesTwelveMaskCharactersAndFourDigits() {
        int hiddenWidth = CARD_NUMBER_WIDTH - VISIBLE_CARD_NUMBER_WIDTH;
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);
        String visible = SYNTHETIC_CARD_NUMBER.substring(hiddenWidth);
        String hidden = SYNTHETIC_CARD_NUMBER.substring(0, hiddenWidth);

        assertAll("masked form of a generated synthetic card number",
                () -> assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                        "The masked card number must match "
                                + MASKED_CARD_NUMBER_PATTERN.pattern()
                                + ": twelve mask characters and four digits. The value received"
                                + " holds " + masked.length() + " characters."),
                () -> assertEquals(CARD_NUMBER_WIDTH, masked.length(),
                        "The masked card number must keep the width of"
                                + " CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5."),
                () -> assertEquals(SYNTHETIC_MASKED_CARD_NUMBER, masked,
                        "The generated card number must mask to the published form."),
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

        assertAll("generated synthetic card data",
                () -> assertEquals(SYNTHETIC_MASKED_CARD_NUMBER,
                        response.maskedCardNumber(),
                        "The response must carry the published masked card number."),
                () -> assertFalse(rendered.contains(SYNTHETIC_VERIFICATION_VALUE),
                        "The rendered response holds the generated card verification value,"
                                + " whose width comes from CARD-CVV-CD PIC 9(03) at"
                                + " app/cpy/CVACT02Y.cpy:L7. The rendered text is withheld from"
                                + " this message so a failure cannot copy the value into a log."),
                () -> assertFalse(rendered.contains(SYNTHETIC_CARD_NUMBER),
                        "The rendered response holds the full generated card number."),
                () -> assertEquals(SYNTHETIC_ACCOUNT_ID, response.accountId(),
                        "The response must carry the account identifier whose width is"
                                + " CARD-ACCT-ID PIC 9(11) at"
                                + " app/cpy/CVACT02Y.cpy:L6."),
                () -> assertEquals(SYNTHETIC_EMBOSSED_NAME, response.embossedName(),
                        "The response must carry the embossed name declared as"
                                + " CARD-EMBOSSED-NAME PIC X(50) at"
                                + " app/cpy/CVACT02Y.cpy:L8."),
                () -> assertEquals(SYNTHETIC_EXPIRATION_DATE, response.expirationDate(),
                        "The response must carry the expiration date declared as"
                                + " CARD-EXPIRAION-DATE PIC X(10) at"
                                + " app/cpy/CVACT02Y.cpy:L9."),
                () -> assertEquals(SYNTHETIC_ACTIVE_STATUS, response.activeStatus(),
                        "The response must carry the active status declared as"
                                + " CARD-ACTIVE-STATUS PIC X(01) at"
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

    /** Builds a clearly synthetic sixteen-digit card value. */
    private static String syntheticCardNumber(long serial) {
        return "9999" + syntheticDigits(12, serial);
    }

    /**
     * Asserts this read contract holds the active status to the domain the API publishes.
     *
     * <p>The domain is {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
     * {@code app/cbl/COCRDUPC.cbl:L91}. One helper carries it,
     * {@link CardSummary#requireActiveStatusFlag(String)}, so the list row and this detail row
     * cannot come apart, and {@code ck_card_active_status} in
     * {@code V5__xref_reconciliation_and_status_domain.sql} holds the column to the same pair for
     * every writer. Before all three, a row loaded outside the update path could be answered with a
     * third value inside a schema that enumerates two.
     */
    @Test
    void theActiveStatusIsHeldToTheSourceDomain() {
        assertEquals(CardSummary.ACTIVE_STATUS_NO,
                new CardDetailResponse(SYNTHETIC_MASKED_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID,
                        SYNTHETIC_EMBOSSED_NAME, SYNTHETIC_EXPIRATION_DATE,
                        CardSummary.ACTIVE_STATUS_NO).activeStatus(),
                "N is one of the two values the column may hold");
        assertThrows(NullPointerException.class,
                () -> new CardDetailResponse(SYNTHETIC_MASKED_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID,
                        SYNTHETIC_EMBOSSED_NAME, SYNTHETIC_EXPIRATION_DATE, null),
                "a detail row with no status is not a row this contract can answer");
        for (String outside : List.of("X", "y", "n", " ", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CardDetailResponse(SYNTHETIC_MASKED_CARD_NUMBER, SYNTHETIC_ACCOUNT_ID,
                            SYNTHETIC_EMBOSSED_NAME, SYNTHETIC_EXPIRATION_DATE, outside),
                    "'" + outside + "' is outside 88 FLG-YES-NO-VALID and must be refused");
        }
    }

    /** Builds a zero-padded synthetic digit string. */
    private static String syntheticDigits(int width, long serial) {
        return String.format(Locale.ROOT, "%0" + width + "d", serial);
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
