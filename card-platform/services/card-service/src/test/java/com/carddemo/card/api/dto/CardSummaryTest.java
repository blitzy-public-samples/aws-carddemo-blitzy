package com.carddemo.card.api.dto;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.carddemo.cobol.PanMasker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * alone, and {@link CardDetailResponse} carries both.
 *
 * <p>Masking is an addition. The card detail screen renders all sixteen characters unprotected:
 * {@code CARDSID DFHMDF} carries {@code ATTRB=(FSET,NORM,UNPROT)} and {@code LENGTH=16} at
 * {@code app/bms/COCRDSL.bms:L96-L100}. A card lookup keys on the full sixteen-character Primary
 * Account Number, and masking applies at the serialization boundary.
 *
 * <p>The card-verification-value scan is scoped to record one of
 * {@code app/data/ASCII/carddata.txt} because the same three-digit sequences occur inside unrelated
 * fixture card numbers.
 *
 * <p>The darkened-attribute claim is scoped to the card-number field. The card detail screen file
 * {@code app/bms/COCRDSL.bms} holds no {@code DRK} attribute, and six other Basic Mapping Support
 * files do hold one. In {@code app/bms/COCRDUP.bms} the card-number field at L96-L100 is
 * byte-identical to the card-number field of the card detail screen. That file applies {@code DRK}
 * at L142 to {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} and at L163 to
 * {@code FKEYSC DFHMDF ATTRB=(ASKIP,DRK)}.
 *
 * <p>These tests reflect over the record components. They read no file, start no application
 * context and issue no Representational State Transfer request.
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

    /** Generated card number at the width of {@code CARD-NUM PIC X(16)}. */
    private static final String SYNTHETIC_CARD_NUMBER = syntheticCardNumber(5740L);

    /** A synthetic account identifier, eleven digits padded on the left with zeros. */
    private static final String SYNTHETIC_ACCOUNT_ID = syntheticDigits(11, 500_001L);

    /** A synthetic active status. */
    private static final String SYNTHETIC_ACTIVE_STATUS = "Y";

    /** Width of {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. */
    private static final int ACCOUNT_ID_LENGTH = 11;

    /** A synthetic value at the width of {@code CARD-CVV-CD PIC 9(03)}. */
    private static final String SYNTHETIC_VERIFICATION_VALUE = syntheticDigits(3, 451L);

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
                "Component " + COMPONENT_CARD_NUMBER + " holds the masked card number as text, "
                        + "sixteen characters wide. " + CARD_NUMBER_LOCATOR);

        assertEquals(String.class, typeOf(COMPONENT_ACCOUNT_ID),
                "Component " + COMPONENT_ACCOUNT_ID + " holds eleven digits as text, which keeps "
                        + "the leading zeros of a numeric-display field through serialization. "
                        + "Source CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6.");

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
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);

        assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                () -> "PanMasker.maskCardNumber returned a value of width "
                        + masked.length() + " that does not match "
                        + MASKED_CARD_NUMBER_PATTERN.pattern() + ". " + CARD_NUMBER_LOCATOR);

        int hiddenLength = PanMasker.CARD_NUMBER_LENGTH - PanMasker.VISIBLE_DIGIT_COUNT;

        assertEquals(SYNTHETIC_CARD_NUMBER.substring(hiddenLength),
                masked.substring(hiddenLength),
                "The last " + PanMasker.VISIBLE_DIGIT_COUNT + " characters of the masked form come "
                        + "from the card number. " + CARD_NUMBER_LOCATOR);
    }

    @Test
    void oneRenderedRowCarriesNoCardVerificationValueDigits() {
        CardSummary row = new CardSummary(
                PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                SYNTHETIC_ACCOUNT_ID,
                SYNTHETIC_ACTIVE_STATUS);

        assertEquals(PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                row.cardNumber(),
                "Accessor " + COMPONENT_CARD_NUMBER + " returns the masked card number. "
                        + CARD_NUMBER_LOCATOR);
        assertEquals(SYNTHETIC_ACCOUNT_ID, row.accountId(),
                "Accessor " + COMPONENT_ACCOUNT_ID + " returns the account identifier it was "
                        + "handed, leading zeros included.");
        assertEquals(SYNTHETIC_ACTIVE_STATUS, row.activeStatus(),
                "Accessor " + COMPONENT_ACTIVE_STATUS + " returns the active status it was "
                        + "handed.");

        String rendered = row.toString();

        assertFalse(rendered.contains(SYNTHETIC_VERIFICATION_VALUE),
                () -> "The rendered row shows a card verification value. "
                        + COPYBOOK_LOCATOR + " Field CARD-CVV-CD PIC 9(03) at L7.");

        assertFalse(rendered.contains(SYNTHETIC_CARD_NUMBER),
                () -> "The rendered row shows all sixteen characters of the card number. "
                        + "The row holds the masked form. " + CARD_NUMBER_LOCATOR);
    }

    /**
     * Asserts the account identifier holds exactly eleven digits. The card record declares
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}, and the screen row table
     * at {@code app/cbl/COCRDLIC.cbl:L250-L260} holds eleven characters for it.
     */
    @Test
    void listRowAccountIdentifierHoldsElevenDigits() {
        assertEquals(SYNTHETIC_ACCOUNT_ID,
                new CardSummary(PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                        SYNTHETIC_ACCOUNT_ID,
                        SYNTHETIC_ACTIVE_STATUS).accountId(),
                "Eleven digits with leading zeros are valid. Source CARD-ACCT-ID PIC 9(11) at "
                        + "app/cpy/CVACT02Y.cpy:L6.");

        for (String rejected : List.of("50", "0000000005", "000000000500", " 0000000050",
                "0000000005X")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CardSummary(PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                            rejected,
                            SYNTHETIC_ACTIVE_STATUS),
                    "Value '" + rejected + "' is not eleven digits. Source CARD-ACCT-ID PIC 9(11) "
                            + "at app/cpy/CVACT02Y.cpy:L6.");
        }

    }

    /**
     * Asserts that the masker hides every character of a card number except the last four, so the
     * value the controller places in this row carries no full Primary Account Number.
     */
    @Test
    void theMaskerHidesEveryCardNumberCharacterExceptTheLastFour() {
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);

        assertTrue(MASKED_CARD_NUMBER_PATTERN.matcher(masked).matches(),
                () -> "The masked form must match " + MASKED_CARD_NUMBER_PATTERN.pattern() + ". "
                        + CARD_NUMBER_LOCATOR);
        assertFalse(masked.contains(SYNTHETIC_CARD_NUMBER),
                "The masked form holds the full card number it was built from.");
        assertEquals(PanMasker.CARD_NUMBER_LENGTH, masked.length(),
                "The masked form keeps the width of CARD-NUM PIC X(16).");
    }

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

    private static List<String> componentNames() {
        return Arrays.stream(CardSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    @Test
    void accountIdentifierKeepsItsLeadingZeros() {
        CardSummary row = new CardSummary(
                PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER),
                SYNTHETIC_ACCOUNT_ID,
                SYNTHETIC_ACTIVE_STATUS);

        assertEquals(SYNTHETIC_ACCOUNT_ID, row.accountId(),
                "Accessor " + COMPONENT_ACCOUNT_ID + " returns all eleven characters, leading "
                        + "zeros included. Source CARD-ACCT-ID PIC 9(11) at "
                        + "app/cpy/CVACT02Y.cpy:L6.");
        assertEquals(ACCOUNT_ID_LENGTH, row.accountId().length(),
                "The account identifier occupies " + ACCOUNT_ID_LENGTH + " characters.");
        assertFalse(row.toString().contains(SYNTHETIC_ACCOUNT_ID),
                () -> "Row '" + row + "' renders the account identifier. The accessor returns it "
                        + "and the rendering withholds it.");
    }

    @Test
    void accountIdentifierOfTheWrongWidthIsRejected() {
        String masked = PanMasker.maskCardNumber(SYNTHETIC_CARD_NUMBER);

        for (String rejected : new String[] {"", "50", "00000000050 ", "0000000005X",
                "000000000500"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CardSummary(masked, rejected, SYNTHETIC_ACTIVE_STATUS),
                    () -> "CardSummary accepted accountId '" + rejected + "'. The component holds "
                            + ACCOUNT_ID_LENGTH + " digits. Source CARD-ACCT-ID PIC 9(11) at "
                            + "app/cpy/CVACT02Y.cpy:L6.");
        }

        assertThrows(NullPointerException.class,
                () -> new CardSummary(masked, null, SYNTHETIC_ACTIVE_STATUS),
                "CardSummary accepted a null accountId. An absent identifier is a missing "
                        + "argument, not a value of the wrong width.");
    }

    /**
     * Asserts the active-status domain is a rule of this record and not only of the update path.
     *
     * <p>The domain is {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} at
     * {@code app/cbl/COCRDUPC.cbl:L91}, tested at {@code app/cbl/COCRDUPC.cbl:L1861-L1863}. Before
     * this check, {@code api/dto/CardUpdateRequest} held an inbound status to the pair and nothing
     * held an outbound one, so a row loaded by any other writer was answered as it stood inside a
     * contract that enumerates two values. {@code ck_card_active_status} in
     * {@code V5__xref_reconciliation_and_status_domain.sql} closes the same gap at the column.
     */
    @Test
    void bothValuesOfTheSourceDomainBuildARow() {
        String masked = PanMasker.maskCardNumber(syntheticCardNumber(1L));

        assertEquals(CardSummary.ACTIVE_STATUS_YES,
                new CardSummary(masked, SYNTHETIC_ACCOUNT_ID, CardSummary.ACTIVE_STATUS_YES)
                        .activeStatus(),
                "Y is one of the two values 88 FLG-YES-NO-VALID declares");
        assertEquals(CardSummary.ACTIVE_STATUS_NO,
                new CardSummary(masked, SYNTHETIC_ACCOUNT_ID, CardSummary.ACTIVE_STATUS_NO)
                        .activeStatus(),
                "N is the other, and the fixture carries both");
    }

    /**
     * Asserts a status outside the domain is refused, in every form a stored row could carry it.
     *
     * <p>Lower case is refused rather than folded: {@code 1240-EDIT-CARDSTATUS} at
     * {@code app/cbl/COCRDUPC.cbl:L1855-L1866} folds no case, so accepting {@code y} would answer a
     * value the source never stores.
     */
    @Test
    void aStatusOutsideTheSourceDomainIsRefused() {
        String masked = PanMasker.maskCardNumber(syntheticCardNumber(2L));

        assertThrows(NullPointerException.class,
                () -> new CardSummary(masked, SYNTHETIC_ACCOUNT_ID, null),
                "a row with no status is not a row this contract can answer");
        for (String outside : List.of("X", "y", "n", " ", "YN", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CardSummary(masked, SYNTHETIC_ACCOUNT_ID, outside),
                    "'" + outside + "' is outside 88 FLG-YES-NO-VALID and must be refused");
        }
    }

    /**
     * Asserts the shared helper is the one place the domain is written down.
     *
     * <p>{@code api/dto/CardDetailResponse} calls the same method, so one domain governs both read
     * contracts. A second copy of the pair would be a second thing to change.
     */
    @Test
    void theSharedHelperCarriesTheDomainForBothReadContracts() {
        assertEquals("Y", CardSummary.ACTIVE_STATUS_YES, "the affirmative value of L91");
        assertEquals("N", CardSummary.ACTIVE_STATUS_NO, "the negative value of L91");
        CardSummary.requireActiveStatusFlag(CardSummary.ACTIVE_STATUS_YES);
        CardSummary.requireActiveStatusFlag(CardSummary.ACTIVE_STATUS_NO);
        assertThrows(IllegalArgumentException.class,
                () -> CardSummary.requireActiveStatusFlag("X"),
                "the helper refuses what the two records refuse");
    }

    /**
     * Returns the declared type of one component of {@link CardSummary}.
     *
     * @param componentName the name of the component to look up
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

    private static String normalize(String componentName) {
        return componentName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Builds a clearly synthetic sixteen-digit card value. */
    private static String syntheticCardNumber(long serial) {
        return "9999" + syntheticDigits(12, serial);
    }

    /** Builds a zero-padded synthetic digit string. */
    private static String syntheticDigits(int width, long serial) {
        return String.format(Locale.ROOT, "%0" + width + "d", serial);
    }
}
