package com.carddemo.card.api.dto;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.carddemo.cobol.PanMasker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape tests for {@link CardSummary}, the card list row.
 *
 * <p>The row carries three components: the masked card number, the account identifier and the
 * one-character active status. The card browse declares its screen row table at
 * {@code app/cbl/COCRDLIC.cbl:L250-L260} as eleven characters of account number, sixteen of card
 * number and one of status. The forward browse moves exactly those three fields at
 * {@code app/cbl/COCRDLIC.cbl:L1165-L1171}. The backward browse repeats the same three moves at
 * {@code app/cbl/COCRDLIC.cbl:L1338-L1344}.
 *
 * <p>Four fields of the card record stay out of this row. The card record copybook declares
 * {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7},
 * {@code CARD-EMBOSSED-NAME PIC X(50)} at L8, {@code CARD-EXPIRAION-DATE PIC X(10)} at L9 and
 * {@code FILLER PIC X(59)} at L11. The card verification value and the trailing filler stay out of
 * every payload this service returns. The embossed name and the expiry date stay out of the row
 * alone, and {@link CardDetailResponse} carries both. All four omissions appear in
 * {@code card-platform/docs/traceability-matrix.md}.
 *
 * <p>Masking is an addition. The card detail screen renders all sixteen characters unprotected:
 * {@code CARDSID DFHMDF} carries {@code ATTRB=(FSET,NORM,UNPROT)} and {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L96-L100}. A card lookup keys on the full sixteen-character Primary
 * Account Number, and masking applies at the serialization boundary.
 *
 * <p>Two deviations in these tests carry an entry in {@code card-platform/docs/decision-log.md}.
 * The first scopes the card-verification-value scan to fixture record one. Record one of
 * {@code app/data/ASCII/carddata.txt} carries the value {@code 747}, which recurs on line 47 inside
 * card number {@code 9349107475869214}. Record two carries {@code 567}, which recurs on line 24
 * inside card number {@code 5671184478505844}.
 *
 * <p>The second deviation scopes the darkened-attribute claim to the card-number field. The card
 * detail screen file {@code app/bms/COCRDSL.bms} holds no {@code DRK} attribute, and six other
 * Basic Mapping Support files do hold one. In {@code app/bms/COCRDUP.bms} the card-number field at
 * L96-L100 is byte-identical to the card-number field of the card detail screen. That file applies
 * {@code DRK} at L142 to {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} and at L163 to
 * {@code FKEYSC DFHMDF ATTRB=(ASKIP,DRK)}.
 *
 * <p>These tests reflect over the record components. They read no file, start no application
 * context and issue no Representational State Transfer request. The absent card-number checksum
 * test and the unchecked card status appear in
 * {@code card-platform/docs/business-rule-flags.md}.
 */
final class CardSummaryTest {

    /** Component that holds the masked card number. Source {@code CARD-NUM PIC X(16)}. */
    private static final String COMPONENT_CARD_NUMBER = "cardNumber";

    /** Component that holds the account identifier. Source {@code CARD-ACCT-ID PIC 9(11)}. */
    private static final String COMPONENT_ACCOUNT_ID = "accountId";

    /** Component that holds the active status. Source {@code CARD-ACTIVE-STATUS PIC X(01)}. */
    private static final String COMPONENT_ACTIVE_STATUS = "activeStatus";

    /** The three component names, in the order {@link CardSummary} declares them. */
    private static final List<String> EXPECTED_COMPONENT_NAMES =
            List.of(COMPONENT_CARD_NUMBER, COMPONENT_ACCOUNT_ID, COMPONENT_ACTIVE_STATUS);

    /** One row of {@code WS-SCREEN-ROWS} holds three fields. */
    private static final int EXPECTED_COMPONENT_COUNT = 3;

    /** Twelve mask characters followed by four digits. */
    private static final Pattern MASKED_CARD_NUMBER_PATTERN = Pattern.compile("^\\*{12}[0-9]{4}$");

    /** Card number of record one of {@code app/data/ASCII/carddata.txt}, typed as a literal. */
    private static final String FIXTURE_CARD_NUMBER = "0500024453765740";

    /** Account identifier of the same record, eleven digits padded on the left with zeros. */
    private static final String FIXTURE_ACCOUNT_ID = "00000000050";

    /** Active status of the same record. */
    private static final String FIXTURE_ACTIVE_STATUS = "Y";

    /** Card verification value of the same record, held for the single-record scan. */
    private static final String FIXTURE_CARD_VERIFICATION_VALUE = "747";

    /** Locator of the row declaration. */
    private static final String DECLARATION_LOCATOR =
            "Declared at app/cbl/COCRDLIC.cbl:L250-L260.";

    /** Locator of the forward browse moves. */
    private static final String POPULATION_LOCATOR =
            "Populated at app/cbl/COCRDLIC.cbl:L1165-L1171, "
                    + "and at app/cbl/COCRDLIC.cbl:L1338-L1344 on the backward browse.";

    /** Locator of the card record copybook. */
    private static final String COPYBOOK_LOCATOR = "Card record copybook app/cpy/CVACT02Y.cpy.";

    /** Locator of the card-number field width. */
    private static final String CARD_NUMBER_LOCATOR =
            "Source CARD-NUM PIC X(16) at app/cpy/CVACT02Y.cpy:L5.";

    /** Tokens that name an embossed name. */
    private static final String[] EMBOSSED_NAME_TOKENS = {"embossed"};

    /** Token that covers the source spelling {@code EXPIRAION}, plus expiry and expiration. */
    private static final String[] EXPIRY_TOKENS = {"expir"};

    /** Tokens that name a card verification value, a security code or a three-digit code. */
    private static final String[] CARD_VERIFICATION_VALUE_TOKENS = {
        "cvv", "cvc", "csc", "cvn", "verification", "security", "threedigit"
    };

    /**
     * Asserts the row carries exactly three components. The three source widths sum to the
     * twenty-eight characters of one row, and seven rows fill the 196-character table at
     * {@code app/cbl/COCRDLIC.cbl:L253}.
     */
    @Test
    void listRowCarriesExactlyThreeComponents() {
        RecordComponent[] components = CardSummary.class.getRecordComponents();

        assertEquals(EXPECTED_COMPONENT_COUNT, components.length,
                () -> "CardSummary declares " + components.length + " components: " + componentNames()
                        + ". The card list row carries " + EXPECTED_COMPONENT_COUNT + ": "
                        + EXPECTED_COMPONENT_NAMES + ". " + DECLARATION_LOCATOR);
    }

    /**
     * Asserts the three component names, in declaration order. The forward browse moves the card
     * number, the account identifier and the active status, and nothing else.
     */
    @Test
    void listRowComponentNamesMatchTheProjectedFields() {
        assertEquals(EXPECTED_COMPONENT_NAMES, componentNames(),
                () -> "CardSummary declares " + componentNames() + ". The card list row projects "
                        + EXPECTED_COMPONENT_NAMES + ", in that order. " + POPULATION_LOCATOR);
    }

    /**
     * Asserts the declared type of each component. The card record declares
     * {@code CARD-NUM PIC X(16)} at L5, {@code CARD-ACCT-ID PIC 9(11)} at L6 and
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at L10.
     */
    @Test
    void listRowComponentTypesMatchTheCopybookFields() {
        assertEquals(String.class, typeOf(COMPONENT_CARD_NUMBER),
                "Component " + COMPONENT_CARD_NUMBER + " holds sixteen characters. "
                        + CARD_NUMBER_LOCATOR);

        assertEquals(BigDecimal.class, typeOf(COMPONENT_ACCOUNT_ID),
                "Component " + COMPONENT_ACCOUNT_ID + " holds eleven digits with no fractional "
                        + "part. Source CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6.");

        assertEquals(String.class, typeOf(COMPONENT_ACTIVE_STATUS),
                "Component " + COMPONENT_ACTIVE_STATUS + " holds one character. "
                        + "Source CARD-ACTIVE-STATUS PIC X(01) at app/cpy/CVACT02Y.cpy:L10.");
    }

    /**
     * Asserts that no component name mentions an embossed name. The card record declares
     * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}, and the three
     * browse moves skip it.
     */
    @Test
    void listRowCarriesNoEmbossedName() {
        assertNoComponentNameMentions("an embossed name",
                COPYBOOK_LOCATOR + " Field CARD-EMBOSSED-NAME PIC X(50) at L8. "
                        + POPULATION_LOCATOR,
                EMBOSSED_NAME_TOKENS);
    }

    /**
     * Asserts that no component name mentions an expiry date, under the source spelling or the
     * corrected one. The card record declares {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     */
    @Test
    void listRowCarriesNoExpiryDateUnderEitherSpelling() {
        assertNoComponentNameMentions("an expiry date, spelled EXPIRAION in the source",
                COPYBOOK_LOCATOR + " Field CARD-EXPIRAION-DATE PIC X(10) at L9. "
                        + POPULATION_LOCATOR,
                EXPIRY_TOKENS);
    }

    /**
     * Asserts no component holds the card verification value under any name. The card record
     * stores it in the clear as {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}, and the card update record carries it at
     * {@code app/cbl/COCRDUPC.cbl:L1464-L1465}.
     */
    @Test
    void listRowCarriesNoCardVerificationValueUnderAnyName() {
        assertNoComponentNameMentions("a card verification value",
                COPYBOOK_LOCATOR + " Field CARD-CVV-CD PIC 9(03) at L7, carried into the update "
                        + "record at app/cbl/COCRDUPC.cbl:L1464-L1465. The value stays inside this "
                        + "service.",
                CARD_VERIFICATION_VALUE_TOKENS);
    }

    /**
     * Asserts the masked card number carries twelve mask characters followed by four digits, and
     * that the four digits come from the card number. The width of sixteen comes from
     * {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}.
     */
    @Test
    void maskedCardNumberCarriesTwelveMaskCharactersAndFourDigits() {
        String masked = PanMasker.maskCardNumber(FIXTURE_CARD_NUMBER);

        assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                () -> "PanMasker.maskCardNumber returned '" + masked + "'. The masked form matches "
                        + MASKED_CARD_NUMBER_PATTERN.pattern() + ". " + CARD_NUMBER_LOCATOR);

        int hiddenLength = PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT;

        assertEquals(FIXTURE_CARD_NUMBER.substring(hiddenLength), masked.substring(hiddenLength),
                "The last " + PanMasker.VISIBLE_DIGIT_COUNT + " characters of the masked form come "
                        + "from the card number. " + CARD_NUMBER_LOCATOR);
    }

    /**
     * Asserts the accessors return the constructor arguments, and that one rendered row shows
     * neither the card verification value nor the full card number. The scan covers fixture
     * record one alone.
     */
    @Test
    void oneRenderedRowCarriesNoCardVerificationValueDigits() {
        CardSummary row = new CardSummary(
                PanMasker.maskCardNumber(FIXTURE_CARD_NUMBER),
                new BigDecimal(FIXTURE_ACCOUNT_ID),
                FIXTURE_ACTIVE_STATUS);

        assertEquals(PanMasker.maskCardNumber(FIXTURE_CARD_NUMBER), row.cardNumber(),
                "Accessor " + COMPONENT_CARD_NUMBER + " returns the masked card number. "
                        + CARD_NUMBER_LOCATOR);
        assertEquals(new BigDecimal(FIXTURE_ACCOUNT_ID), row.accountId(),
                "Accessor " + COMPONENT_ACCOUNT_ID + " returns the account identifier of fixture "
                        + "record one. Source app/data/ASCII/carddata.txt line 1.");
        assertEquals(FIXTURE_ACTIVE_STATUS, row.activeStatus(),
                "Accessor " + COMPONENT_ACTIVE_STATUS + " returns the active status of fixture "
                        + "record one. Source app/data/ASCII/carddata.txt line 1.");

        String rendered = row.toString();

        assertFalse(rendered.contains(FIXTURE_CARD_VERIFICATION_VALUE),
                () -> "Row '" + rendered + "' shows the card verification value "
                        + FIXTURE_CARD_VERIFICATION_VALUE + " of fixture record one. "
                        + COPYBOOK_LOCATOR + " Field CARD-CVV-CD PIC 9(03) at L7.");

        assertFalse(rendered.contains(FIXTURE_CARD_NUMBER),
                () -> "Row '" + rendered + "' shows all sixteen characters of the card number. "
                        + "The row holds the masked form. " + CARD_NUMBER_LOCATOR);
    }

    /**
     * Asserts that no component name holds any of the given tokens. Each name folds to lower case
     * and drops every character outside {@code a-z0-9} before the test.
     *
     * @param subject the field the tokens name, for the failure message
     * @param locator the source locator, for the failure message
     * @param tokens  the normalized tokens no component name may hold
     */
    private static void assertNoComponentNameMentions(String subject, String locator,
            String... tokens) {
        for (String name : componentNames()) {
            String normalized = normalize(name);
            for (String token : tokens) {
                assertFalse(normalized.contains(token),
                        () -> "CardSummary declares component '" + name + "', which mentions "
                                + subject + " through the token '" + token + "'. The card list row "
                                + "carries " + EXPECTED_COMPONENT_COUNT + " components: "
                                + EXPECTED_COMPONENT_NAMES + ". " + locator);
            }
        }
    }

    /**
     * Returns the component names of {@link CardSummary}, in declaration order.
     *
     * @return the three declared component names
     */
    private static List<String> componentNames() {
        return Arrays.stream(CardSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the declared type of one component of {@link CardSummary}.
     *
     * @param componentName the component name to look up
     * @return the declared type, or {@code null} when no component carries that name
     */
    private static Class<?> typeOf(String componentName) {
        for (RecordComponent component : CardSummary.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getType();
            }
        }
        return null;
    }

    /**
     * Folds a component name to lower case and drops every character outside {@code a-z0-9}.
     *
     * @param componentName the declared component name
     * @return the folded name
     */
    private static String normalize(String componentName) {
        return componentName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
