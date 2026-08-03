package com.carddemo.card.api.dto;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape and constraint tests for {@link CardUpdateRequest}, the card update payload.
 *
 * <p>The payload mirrors one source group. {@code CCUP-NEW-CARDDATA} opens at
 * {@code app/cbl/COCRDUPC.cbl:L307} and holds {@code CCUP-NEW-CRDNAME PIC X(50)} at L308, the
 * {@code CCUP-NEW-EXPIRAION-DATE} subgroup at L309 with {@code CCUP-NEW-EXPYEAR PIC X(4)} at
 * L310, {@code CCUP-NEW-EXPMON PIC X(2)} at L311 and {@code CCUP-NEW-EXPDAY PIC X(2)} at L312,
 * and {@code CCUP-NEW-CRDSTCD PIC X(1)} at L313. Five components follow from those five fields.
 * {@code CARD-UPDATE-RECORD} at L314 is the write record and supplies no component.
 *
 * <p>Three fields of the enclosing group stay out of the payload.
 * {@code CCUP-NEW-ACCTID PIC X(11)} sits at L304, {@code CCUP-NEW-CARDID PIC X(16)} at L305 and
 * {@code CCUP-NEW-CVV-CD PIC X(3)} at L306, all three outside the group opened at L307. The card
 * number arrives as the path variable of the update endpoint, and the card detail screen renders
 * all sixteen of its characters: {@code CARDSID DFHMDF ATTRB=(FSET,NORM,UNPROT)} carries
 * {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L96-L100}. Three tests below hold the three
 * absences, and {@code card-platform/docs/traceability-matrix.md} carries the mapping.
 *
 * <p>The three expiry parts stay separate, and working storage slices the ten-character date at
 * {@code app/cbl/COCRDUPC.cbl:L117} through L121. The parts run four characters of year, one
 * separator, two of month, one separator and two of day. The write path joins the three parts
 * with hyphens at L1467 through L1474. The conflict check reads positions 1 to 4, 6 to 7 and 9 to
 * 10 at L1505 through L1507. The card record holds the joined form as
 * {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}.
 *
 * <p>Three edits carry a range. {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'.} sits at
 * {@code app/cbl/COCRDUPC.cbl:L91}, {@code 88 VALID-MONTH VALUES 1 THRU 12.} at L95 and
 * {@code 88 VALID-YEAR VALUES 1950 THRU 2099.} at L99. The name edit admits letters and spaces.
 * Paragraph {@code 1230-EDIT-NAME} converts every letter of the fifty-two character
 * {@code LIT-ALL-ALPHA-FROM} literal at L255 to a space at L823 through L826, and the trim test
 * at L828 then accepts only spaces. Its width comes from
 * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}.
 *
 * <p>Every message test below compares for exact equality against a literal typed in this file.
 *
 * <p>The expiry day carries no range. The edit chain runs from {@code 1230-EDIT-NAME} at L806 to
 * {@code 1260-EDIT-EXPIRY-YEAR-EXIT} at L945, and {@code 2000-DECIDE-ACTION} opens at L948. The
 * four edits in between read the name, the active status, the expiry month and the expiry year.
 * L621 moves the day in and L1471 joins it into the reassembled date. The card update screen
 * darkens and protects the field: {@code EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT)} at
 * {@code app/bms/COCRDUP.bms:L142}, one of the two darkened fields of that screen.
 *
 * <p>No edit tests a card number for a checksum. L782 and L783 comment on a numeric test and a
 * sixteen-character test, and L784 reads {@code IF CC-CARD-NUM IS NOT NUMERIC} alone, over
 * {@code CC-CARD-NUM PIC X(16)} at {@code app/cpy/CVCRD01Y.cpy:L37}. A search for checksum,
 * Luhn, modulo and check-digit wording across the twenty-eight programs under {@code app/cbl}
 * and the twenty-eight copybooks under {@code app/cpy} returns no hit.
 * {@code card-platform/docs/business-rule-flags.md} carries that finding as register item 24.
 *
 * <p>These tests build one {@link Validator} and reflect over the record. The constraint
 * annotations of {@code jakarta.validation.constraints} name no record component in their target
 * list. The compiler propagates each one to the field and to the accessor, and the helpers below
 * read the field. The tests read no file, start no application context and issue no
 * Representational State Transfer request. Rationale for each deviation above:
 * {@code card-platform/docs/decision-log.md}.
 */
final class CardUpdateRequestTest {

    /** Count of fields the {@code CCUP-NEW-CARDDATA} group holds at L307 through L313. */
    private static final int EXPECTED_COMPONENT_COUNT = 5;

    /** Component carrying {@code CCUP-NEW-CRDNAME PIC X(50)} at L308. */
    private static final String COMPONENT_EMBOSSED_NAME = "embossedName";

    /** Component carrying {@code CCUP-NEW-EXPYEAR PIC X(4)} at L310. */
    private static final String COMPONENT_EXPIRY_YEAR = "expiryYear";

    /** Component carrying {@code CCUP-NEW-EXPMON PIC X(2)} at L311. */
    private static final String COMPONENT_EXPIRY_MONTH = "expiryMonth";

    /** Component carrying {@code CCUP-NEW-EXPDAY PIC X(2)} at L312. */
    private static final String COMPONENT_EXPIRY_DAY = "expiryDay";

    /** Component carrying {@code CCUP-NEW-CRDSTCD PIC X(1)} at L313. */
    private static final String COMPONENT_ACTIVE_STATUS = "activeStatus";

    /** The five component names, in the order the source group declares its fields. */
    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            COMPONENT_EMBOSSED_NAME,
            COMPONENT_EXPIRY_YEAR,
            COMPONENT_EXPIRY_MONTH,
            COMPONENT_EXPIRY_DAY,
            COMPONENT_ACTIVE_STATUS);

    /** The three components the {@code CCUP-NEW-EXPIRAION-DATE} subgroup at L309 supplies. */
    private static final List<String> EXPECTED_EXPIRY_COMPONENT_NAMES = List.of(
            COMPONENT_EXPIRY_YEAR,
            COMPONENT_EXPIRY_MONTH,
            COMPONENT_EXPIRY_DAY);

    // Expected texts. Each one is typed here and compared for exact equality, both against
    // the violation the validator reports and against the production constant.

    /** Text of {@code WS-PROMPT-FOR-NAME} at {@code app/cbl/COCRDUPC.cbl:L182}. */
    private static final String EXPECTED_NAME_NOT_PROVIDED = "Card name not provided";

    /** Text of {@code WS-NAME-MUST-BE-ALPHA} at {@code app/cbl/COCRDUPC.cbl:L184}. */
    private static final String EXPECTED_NAME_MUST_BE_ALPHA =
            "Card name can only contain alphabets and spaces";

    /** Text of {@code CARD-STATUS-MUST-BE-YES-NO} at {@code app/cbl/COCRDUPC.cbl:L196}. */
    private static final String EXPECTED_STATUS_MUST_BE_YES_NO =
            "Card Active Status must be Y or N";

    /** Text of {@code CARD-EXPIRY-MONTH-NOT-VALID} at {@code app/cbl/COCRDUPC.cbl:L198}. */
    private static final String EXPECTED_MONTH_NOT_VALID =
            "Card expiry month must be between 1 and 12";

    /** Text of {@code CARD-EXPIRY-YEAR-NOT-VALID} at {@code app/cbl/COCRDUPC.cbl:L200}. */
    private static final String EXPECTED_YEAR_NOT_VALID = "Invalid card expiry year";

    /** Text of {@code SEARCHED-CARD-NOT-NUMERIC} at {@code app/cbl/COCRDUPC.cbl:L194}. */
    private static final String CARD_NUMBER_DIGIT_COUNT_TEXT =
            "Card number if supplied must be a 16 digit number";

    /** Locator of the group the five components come from. */
    private static final String GROUP_LOCATOR =
            "Source CCUP-NEW-CARDDATA at app/cbl/COCRDUPC.cbl:L307-L313.";

    /** Locator of the three fields that sit outside that group. */
    private static final String OUTSIDE_GROUP_LOCATOR =
            "Source app/cbl/COCRDUPC.cbl:L304-L306 sits outside CCUP-NEW-CARDDATA at L307.";

    /** Locator of the expiry slice and the reassembly that reads it. */
    private static final String EXPIRY_LOCATOR =
            "Source app/cbl/COCRDUPC.cbl:L117-L121, joined at L1467-L1474, sliced at L1505-L1507.";

    /** Locator of the darkened and protected expiry day field. */
    private static final String EXPIRY_DAY_LOCATOR =
            "Source app/cbl/COCRDUPC.cbl edits the name, the status, the month and the year at "
                    + "L806-L945, and no paragraph edits the day. "
                    + "EXPDAY DFHMDF ATTRB=(DRK,FSET,PROT) at app/bms/COCRDUP.bms:L142.";

    /** Locator of the active status edit and its condition name. */
    private static final String STATUS_LOCATOR =
            "Source 88 FLG-YES-NO-VALID VALUES 'Y', 'N'. at app/cbl/COCRDUPC.cbl:L91, read by "
                    + "1240-EDIT-CARDSTATUS at L861-L863, which folds no case.";

    /** Locator of the expiry month edit and its condition name. */
    private static final String MONTH_LOCATOR =
            "Source 88 VALID-MONTH VALUES 1 THRU 12. at app/cbl/COCRDUPC.cbl:L95, read by "
                    + "1250-EDIT-EXPIRY-MON at L896-L898.";

    /** Locator of the expiry year edit and its condition name. */
    private static final String YEAR_LOCATOR =
            "Source 88 VALID-YEAR VALUES 1950 THRU 2099. at app/cbl/COCRDUPC.cbl:L99, read by "
                    + "1260-EDIT-EXPIRY-YEAR at L932-L934.";

    /** Locator of the embossed name edit and the alphabet literal it reads. */
    private static final String NAME_LOCATOR =
            "Source 1230-EDIT-NAME at app/cbl/COCRDUPC.cbl:L806-L840 converts every letter of "
                    + "LIT-ALL-ALPHA-FROM PIC X(52) at L255 to a space at L823-L826 and accepts "
                    + "only spaces at L828. Width from CCUP-NEW-CRDNAME PIC X(50) at L308.";

    /** Locator of the one card-number test the source performs. */
    private static final String CARD_NUMBER_TEST_LOCATOR =
            "Source app/cbl/COCRDUPC.cbl:L784 reads IF CC-CARD-NUM IS NOT NUMERIC and nothing "
                    + "more, over CC-CARD-NUM PIC X(16) at app/cpy/CVCRD01Y.cpy:L37.";

    /** Tokens that name a card number under any spelling the source or the target uses. */
    private static final String[] CARD_NUMBER_TOKENS = {
        "cardnum", "cardnumber", "cardid", "pan", "primaryaccountnumber"
    };

    /** Tokens that name an account identifier. */
    private static final String[] ACCOUNT_IDENTIFIER_TOKENS = {"acct", "account"};

    /** Tokens that name a card verification value, a security code or a three-digit code. */
    private static final String[] CARD_VERIFICATION_VALUE_TOKENS = {
        "cvv", "cvc", "csc", "cvn", "verification", "security", "threedigit"
    };

    /** Tokens that name a checksum over a card number. */
    private static final String[] CHECKSUM_TOKENS = {
        "luhn", "checksum", "checkdigit", "mod10", "mod11", "modulo", "creditcard", "verhoeff"
    };

    /** Folded names a single combined expiry date component would carry. */
    private static final Set<String> COMBINED_EXPIRY_NAMES = Set.of(
            "expiry", "expirydate", "expiration", "expirationdate", "expiraiondate",
            "expiraiondatex", "cardexpirydate", "cardexpirationdate");

    /** A sixteen-digit card number that fails a Luhn check. */
    private static final String LUHN_FAILING_CARD_NUMBER = "4111111111111112";

    /** A sixteen-digit card number that passes a Luhn check. */
    private static final String LUHN_PASSING_CARD_NUMBER = "4111111111111111";

    /** Built once for the whole class and closed after the last test. */
    private static ValidatorFactory validatorFactory;

    /** The validator every test below uses. */
    private static Validator validator;

    /**
     * Builds the validator the tests share. The default provider arrives with
     * {@code spring-boot-starter-validation}, which this module already declares, so no
     * application context and no test resource file take part.
     */
    @BeforeAll
    static void buildValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** Releases the factory built in {@link #buildValidator()}. */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    // Shape. The payload carries the five fields of one source group and nothing else.

    /**
     * Asserts the payload carries exactly five components. The group at
     * {@code app/cbl/COCRDUPC.cbl:L307} holds one name field, three expiry parts and one status
     * field.
     */
    @Test
    void updateRequestCarriesExactlyFiveComponents() {
        RecordComponent[] components = CardUpdateRequest.class.getRecordComponents();

        assertEquals(EXPECTED_COMPONENT_COUNT, components.length,
                () -> "CardUpdateRequest declares " + components.length + " components: "
                        + componentNames() + ". The payload group holds "
                        + EXPECTED_COMPONENT_COUNT + ": " + EXPECTED_COMPONENT_NAMES + ". "
                        + GROUP_LOCATOR);
    }

    /**
     * Asserts the five component names, in declaration order. The source group declares the name
     * at L308, the year at L310, the month at L311, the day at L312 and the status at L313.
     */
    @Test
    void componentNamesFollowThePayloadGroupInDeclarationOrder() {
        assertEquals(EXPECTED_COMPONENT_NAMES, componentNames(),
                () -> "CardUpdateRequest declares " + componentNames() + ". The payload group "
                        + "declares " + EXPECTED_COMPONENT_NAMES + ", in that order. "
                        + GROUP_LOCATOR);
    }

    /**
     * Asserts every component holds a {@code String}. All five source fields carry a character
     * picture: {@code PIC X(50)} at L308, {@code PIC X(4)} at L310, {@code PIC X(2)} at L311 and
     * L312, and {@code PIC X(1)} at L313.
     */
    @Test
    void everyComponentHoldsAString() {
        for (String componentName : EXPECTED_COMPONENT_NAMES) {
            assertEquals(String.class, typeOf(componentName),
                    "Component " + componentName + " holds characters. " + GROUP_LOCATOR);
        }
    }

    /**
     * Asserts the expiry arrives as three separate components, in year, month and day order. The
     * ten-character date splits at L117 through L121 and the write path rejoins the parts.
     */
    @Test
    void expiryArrivesAsThreeSeparateComponents() {
        List<String> expiryComponents = componentNames().stream()
                .filter(name -> normalize(name).contains("expir"))
                .toList();

        assertEquals(EXPECTED_EXPIRY_COMPONENT_NAMES, expiryComponents,
                () -> "CardUpdateRequest declares " + expiryComponents + " for the expiry date. "
                        + "The subgroup at app/cbl/COCRDUPC.cbl:L309 declares three parts: "
                        + EXPECTED_EXPIRY_COMPONENT_NAMES + ", in that order. " + EXPIRY_LOCATOR);
    }

    /**
     * Asserts no component carries the joined ten-character expiry date. The card update service
     * joins the three parts, and the payload carries the parts.
     */
    @Test
    void noComponentCarriesACombinedExpiryDate() {
        for (String componentName : componentNames()) {
            String folded = normalize(componentName);
            assertFalse(COMBINED_EXPIRY_NAMES.contains(folded),
                    "Component " + componentName + " carries one part of the expiry date, not "
                            + "the joined form. " + EXPIRY_LOCATOR);
        }
    }

    /**
     * Asserts no component name mentions a card number. {@code CCUP-NEW-CARDID PIC X(16)} sits at
     * {@code app/cbl/COCRDUPC.cbl:L305}, outside the payload group, and the card number arrives
     * as the path variable of the update endpoint.
     */
    @Test
    void updateRequestCarriesNoCardNumberComponent() {
        assertNoComponentNameMentions("a card number",
                OUTSIDE_GROUP_LOCATOR + " Field CCUP-NEW-CARDID PIC X(16) at L305.",
                CARD_NUMBER_TOKENS);
    }

    /**
     * Asserts no component name mentions an account identifier.
     * {@code CCUP-NEW-ACCTID PIC X(11)} sits at {@code app/cbl/COCRDUPC.cbl:L304}, outside the
     * payload group.
     */
    @Test
    void updateRequestCarriesNoAccountIdentifierComponent() {
        assertNoComponentNameMentions("an account identifier",
                OUTSIDE_GROUP_LOCATOR + " Field CCUP-NEW-ACCTID PIC X(11) at L304.",
                ACCOUNT_IDENTIFIER_TOKENS);
    }

    /**
     * Asserts no component name mentions a card verification value.
     * {@code CCUP-NEW-CVV-CD PIC X(3)} sits at {@code app/cbl/COCRDUPC.cbl:L306}, outside the
     * payload group, and the card record stores the value as {@code CARD-CVV-CD PIC 9(03)} at
     * {@code app/cpy/CVACT02Y.cpy:L7}.
     */
    @Test
    void updateRequestCarriesNoCardVerificationValueComponent() {
        assertNoComponentNameMentions("a card verification value",
                OUTSIDE_GROUP_LOCATOR + " Field CCUP-NEW-CVV-CD PIC X(3) at L306.",
                CARD_VERIFICATION_VALUE_TOKENS);
    }

    // Active status. The condition name at L91 lists two values and the edit folds no case.

    /** Asserts the two values the condition name at {@code app/cbl/COCRDUPC.cbl:L91} lists. */
    @Test
    void activeStatusAcceptsUpperCaseYAndUpperCaseN() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "Y"),
                STATUS_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "N"),
                STATUS_LOCATOR);
    }

    /**
     * Asserts lower-case input fails. Paragraph {@code 1240-EDIT-CARDSTATUS} moves the value to
     * {@code FLG-YES-NO-CHECK} at {@code app/cbl/COCRDUPC.cbl:L861} and tests the condition name
     * at L863, with no upper-case conversion between the two lines.
     */
    @Test
    void lowerCaseActiveStatusIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "y"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "n"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    /**
     * Asserts any other character fails with the same text. The source writes it at
     * {@code app/cbl/COCRDUPC.cbl:L869}, under the guard on L868.
     */
    @Test
    void anyOtherActiveStatusCharacterIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "X"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "1"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    /**
     * Asserts a missing status takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L850-L852} and writes the text at L856.
     */
    @Test
    void missingActiveStatusTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", null),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", " "),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", ""),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    // Expiry month. The condition name at L95 spans 1 through 12.

    /** Asserts both ends of the range at {@code app/cbl/COCRDUPC.cbl:L95} pass. */
    @Test
    void expiryMonthAcceptsBothEndsOfTheSourceRange() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "01", "01", "Y"),
                MONTH_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "12", "01", "Y"),
                MONTH_LOCATOR);
    }

    /** Asserts the value below the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L198}. */
    @Test
    void expiryMonthZeroIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "00", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /** Asserts the value above the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L198}. */
    @Test
    void expiryMonthThirteenIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "13", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /**
     * Asserts a one-character month fails. The source field is {@code CCUP-NEW-EXPMON PIC X(2)}
     * at {@code app/cbl/COCRDUPC.cbl:L311} and the write path joins two characters at L1469.
     */
    @Test
    void singleDigitExpiryMonthIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "1", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /**
     * Asserts a missing month takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L883-L885} and writes the text at L889.
     */
    @Test
    void missingExpiryMonthTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", null, "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest("JOHN Q PUBLIC", "2027", "  ", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    // Expiry year. The condition name at L99 spans 1950 through 2099.

    /** Asserts both ends of the range at {@code app/cbl/COCRDUPC.cbl:L99} pass. */
    @Test
    void expiryYearAcceptsBothEndsOfTheSourceRange() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "1950", "03", "01", "Y"),
                YEAR_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2099", "03", "01", "Y"),
                YEAR_LOCATOR);
    }

    /** Asserts the year below the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L200}. */
    @Test
    void expiryYearNineteenFortyNineIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "1949", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    /** Asserts the year above the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L200}. */
    @Test
    void expiryYearTwentyOneHundredIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2100", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    /**
     * Asserts a missing year takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L916-L918} and writes the text at L922.
     */
    @Test
    void missingExpiryYearTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC", null, "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest("JOHN Q PUBLIC", "    ", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest("JOHN Q PUBLIC", "ABCD", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    // Embossed name. The edit at L806 admits letters and spaces to fifty characters.

    /**
     * Asserts letters and spaces pass, up to the fifty characters of
     * {@code CCUP-NEW-CRDNAME PIC X(50)} at {@code app/cbl/COCRDUPC.cbl:L308}.
     */
    @Test
    void embossedNameAcceptsLettersAndSpacesToFiftyCharacters() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "Y"),
                NAME_LOCATOR);
        assertNoViolation(new CardUpdateRequest("Mary Jane Smith", "2027", "03", "01", "Y"),
                NAME_LOCATOR);
        assertNoViolation(new CardUpdateRequest("A".repeat(50), "2027", "03", "01", "Y"),
                NAME_LOCATOR);
    }

    /**
     * Asserts a digit fails with the text at {@code app/cbl/COCRDUPC.cbl:L184}. The alphabet
     * literal at L255 lists the fifty-two letters and no digit.
     */
    @Test
    void embossedNameWithADigitIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("JOHN Q PUBLIC1", "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts punctuation fails with the same text. The strip at
     * {@code app/cbl/COCRDUPC.cbl:L823-L826} leaves a hyphen in place, and the trim test at L828
     * then finds a character.
     */
    @Test
    void embossedNameWithAHyphenIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest("MARY-JANE", "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest("O'BRIEN", "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts a fifty-first character fails. {@code CCUP-NEW-CRDNAME} holds fifty characters at
     * {@code app/cbl/COCRDUPC.cbl:L308}, and {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8} holds the stored form.
     */
    @Test
    void embossedNameLongerThanFiftyCharactersIsRejected() {
        assertSingleViolation(new CardUpdateRequest("A".repeat(51), "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts a missing name takes the text at {@code app/cbl/COCRDUPC.cbl:L182}. The source
     * tests for low values, spaces and zeroes at L811 through L813 and writes the text at L817.
     */
    @Test
    void missingEmbossedNameTakesTheNotProvidedText() {
        assertSingleViolation(new CardUpdateRequest(null, "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest("", "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest("   ", "2027", "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
    }

    /**
     * Asserts a payload that passes all four edits reports nothing. A constraint bound to the
     * wrong component fires here.
     */
    @Test
    void aFullyValidPayloadProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "Y"),
                GROUP_LOCATOR);
    }

    // Message binding. The texts the constraints carry come from the production constant class.

    /**
     * Asserts each expected text typed in this file equals its constant in
     * {@link CardValidationMessages}. A change on either side fails here.
     */
    @Test
    void eachExpectedTextEqualsItsProductionConstant() {
        assertEquals(EXPECTED_NAME_NOT_PROVIDED, CardValidationMessages.PROMPT_FOR_NAME,
                "app/cbl/COCRDUPC.cbl:L182 reads: " + EXPECTED_NAME_NOT_PROVIDED);
        assertEquals(EXPECTED_NAME_MUST_BE_ALPHA, CardValidationMessages.NAME_MUST_BE_ALPHA,
                "app/cbl/COCRDUPC.cbl:L184 reads: " + EXPECTED_NAME_MUST_BE_ALPHA);
        assertEquals(EXPECTED_STATUS_MUST_BE_YES_NO,
                CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                "app/cbl/COCRDUPC.cbl:L196 reads: " + EXPECTED_STATUS_MUST_BE_YES_NO);
        assertEquals(EXPECTED_MONTH_NOT_VALID,
                CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L198 reads: " + EXPECTED_MONTH_NOT_VALID);
        assertEquals(EXPECTED_YEAR_NOT_VALID, CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L200 reads: " + EXPECTED_YEAR_NOT_VALID);
    }

    /**
     * Asserts every constraint on the record binds one of the five texts. The set is read from
     * the {@code message} member of each annotation, so a constraint bound to a sixth text fails
     * here.
     */
    @Test
    void everyConstraintBindsOneOfTheFiveSourceTexts() {
        Set<String> expected = new TreeSet<>(List.of(
                EXPECTED_NAME_NOT_PROVIDED,
                EXPECTED_NAME_MUST_BE_ALPHA,
                EXPECTED_STATUS_MUST_BE_YES_NO,
                EXPECTED_MONTH_NOT_VALID,
                EXPECTED_YEAR_NOT_VALID));

        assertEquals(expected, boundMessages(),
                () -> "CardUpdateRequest binds " + boundMessages() + ". The four edits of "
                        + "app/cbl/COCRDUPC.cbl at L806-L945 write the five texts at L182, L184, "
                        + "L196, L198 and L200.");
    }

    // First absence: the expiry day carries no edit.

    /**
     * Asserts the expiry day component carries no constraint annotation. The source declares a
     * condition name for the month at {@code app/cbl/COCRDUPC.cbl:L95} and for the year at L99,
     * and it declares none for the day.
     */
    @Test
    void expiryDayCarriesNoConstraintAnnotation() {
        List<Annotation> annotations = annotationsOf(COMPONENT_EXPIRY_DAY);

        assertTrue(annotations.isEmpty(),
                () -> "Component " + COMPONENT_EXPIRY_DAY + " carries " + simpleNames(annotations)
                        + ". " + EXPIRY_DAY_LOCATOR);
    }

    /**
     * Asserts an out-of-range expiry day passes. Day 99 falls outside any calendar month, and the
     * source performs no day edit, so the payload reports nothing on the day component.
     */
    @Test
    void outOfRangeExpiryDayProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "99", "Y"),
                EXPIRY_DAY_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "00", "Y"),
                EXPIRY_DAY_LOCATOR);
    }

    /**
     * Asserts a day of 31 February passes. The source validates no calendar combination, so the
     * month and the day travel with no cross-field test.
     */
    @Test
    void impossibleCalendarDayProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "02", "31", "Y"),
                EXPIRY_DAY_LOCATOR);
    }

    /**
     * Asserts a non-numeric expiry day and a missing expiry day both pass. The component carries
     * no annotation, so neither value meets a test.
     */
    @Test
    void nonNumericAndMissingExpiryDayProduceNoViolation() {
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "ZZ", "Y"),
                EXPIRY_DAY_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", null, "Y"),
                EXPIRY_DAY_LOCATOR);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "", "Y"),
                EXPIRY_DAY_LOCATOR);
    }

    // Second absence: no component and no message names a checksum over a card number.

    /**
     * Asserts the record uses four constraint annotations and no more. The closed set holds
     * {@code Max}, {@code Min}, {@code NotBlank} and {@code Pattern}, none of which computes a
     * checksum.
     */
    @Test
    void theRecordUsesFourConstraintAnnotationsAndNoMore() {
        Set<String> expected = new TreeSet<>(List.of("Max", "Min", "NotBlank", "Pattern"));
        Set<String> actual = new TreeSet<>(simpleNames(allAnnotations()));

        assertEquals(expected, actual,
                () -> "CardUpdateRequest carries " + actual + ". The four edits of "
                        + "app/cbl/COCRDUPC.cbl at L806-L945 need a presence test, a character "
                        + "test and two range ends. " + CARD_NUMBER_TEST_LOCATOR);
    }

    /**
     * Asserts no constraint annotation on the record names a checksum. The token list covers
     * Luhn, modulo, check-digit and credit-card wording.
     */
    @Test
    void noConstraintAnnotationNamesAChecksum() {
        for (Annotation annotation : allAnnotations()) {
            String folded = normalize(annotation.annotationType().getSimpleName());
            for (String token : CHECKSUM_TOKENS) {
                assertFalse(folded.contains(token),
                        "Annotation " + annotation.annotationType().getSimpleName()
                                + " names a checksum token: " + token + ". "
                                + CARD_NUMBER_TEST_LOCATOR);
            }
        }
    }

    /**
     * Asserts no text the constraints carry names a checksum, and none of the three card-number
     * texts is bound. Those three sit at {@code app/cbl/COCRDUPC.cbl:L180}, L194 and L789.
     */
    @Test
    void noBoundTextNamesAChecksumOrACardNumber() {
        for (String message : boundMessages()) {
            String folded = normalize(message);
            for (String token : CHECKSUM_TOKENS) {
                assertFalse(folded.contains(token),
                        "Bound text names a checksum token " + token + ": " + message + ". "
                                + CARD_NUMBER_TEST_LOCATOR);
            }
        }
        Set<String> bound = boundMessages();
        assertFalse(bound.contains(CardValidationMessages.PROMPT_FOR_CARD),
                "app/cbl/COCRDUPC.cbl:L180 belongs to the card filter edit at L773. "
                        + OUTSIDE_GROUP_LOCATOR);
        assertFalse(bound.contains(CARD_NUMBER_DIGIT_COUNT_TEXT),
                "app/cbl/COCRDUPC.cbl:L194 counts digits and computes no checksum. "
                        + CARD_NUMBER_TEST_LOCATOR);
        assertFalse(bound.contains(CardValidationMessages.CARD_FILTER_NOT_NUMERIC),
                "app/cbl/COCRDUPC.cbl:L789 belongs to the card filter edit at L784. "
                        + CARD_NUMBER_TEST_LOCATOR);
    }

    /**
     * Asserts a sixteen-digit card number that fails a Luhn check meets no constraint here. The
     * first two assertions establish the Luhn outcome of both literals, and the payload then
     * validates clean while carrying no card-number component.
     */
    @Test
    void luhnFailingSixteenDigitCardNumberMeetsNoConstraintOnThisPayload() {
        assertEquals(16, LUHN_FAILING_CARD_NUMBER.length(),
                "the sample card number holds sixteen digits, the width of "
                        + "CCUP-NEW-CARDID PIC X(16) at app/cbl/COCRDUPC.cbl:L305");
        assertTrue(passesLuhnCheck(LUHN_PASSING_CARD_NUMBER),
                LUHN_PASSING_CARD_NUMBER + " passes a Luhn check");
        assertFalse(passesLuhnCheck(LUHN_FAILING_CARD_NUMBER),
                LUHN_FAILING_CARD_NUMBER + " fails a Luhn check");

        assertNoComponentNameMentions("a card number",
                "the payload takes no card number, so " + LUHN_FAILING_CARD_NUMBER
                        + " reaches no constraint on it. " + CARD_NUMBER_TEST_LOCATOR,
                CARD_NUMBER_TOKENS);
        assertNoViolation(new CardUpdateRequest("JOHN Q PUBLIC", "2027", "03", "01", "Y"),
                "the payload that accompanies card number " + LUHN_FAILING_CARD_NUMBER
                        + " passes every edit. " + CARD_NUMBER_TEST_LOCATOR);
    }

    // Assertion helpers.

    /**
     * Asserts one payload reports one violation, on one component, with one text.
     *
     * @param payload the payload to validate
     * @param expectedComponent the component name the violation names
     * @param expectedText the text the violation carries, compared for exact equality
     * @param locator the source locator to print on failure
     */
    private static void assertSingleViolation(CardUpdateRequest payload, String expectedComponent,
            String expectedText, String locator) {
        Set<ConstraintViolation<CardUpdateRequest>> found = validator.validate(payload);

        assertEquals(1, found.size(),
                () -> "one edit fails and reports: " + expectedText + ". Found " + describe(found)
                        + ". " + locator);
        ConstraintViolation<CardUpdateRequest> violation = found.iterator().next();
        assertEquals(expectedComponent, violation.getPropertyPath().toString(),
                () -> "the violation names component " + expectedComponent + ". " + locator);
        assertEquals(expectedText, violation.getMessage(),
                () -> "the violation reads: " + expectedText + ". " + locator);
    }

    /**
     * Asserts one payload reports at least one violation and that every violation names the same
     * component and carries the same text. A missing value trips a presence test and a character
     * test at once, and both report the one text the source writes.
     *
     * @param payload the payload to validate
     * @param expectedComponent the component name every violation names
     * @param expectedText the text every violation carries, compared for exact equality
     * @param locator the source locator to print on failure
     */
    private static void assertEveryViolationReports(CardUpdateRequest payload,
            String expectedComponent, String expectedText, String locator) {
        Set<ConstraintViolation<CardUpdateRequest>> found = validator.validate(payload);

        assertFalse(found.isEmpty(),
                () -> "at least one edit fails and reports: " + expectedText + ". " + locator);
        for (ConstraintViolation<CardUpdateRequest> violation : found) {
            assertEquals(expectedComponent, violation.getPropertyPath().toString(),
                    () -> "every violation names component " + expectedComponent + ". Found "
                            + describe(found) + ". " + locator);
            assertEquals(expectedText, violation.getMessage(),
                    () -> "every violation reads: " + expectedText + ". Found " + describe(found)
                            + ". " + locator);
        }
    }

    /**
     * Asserts one payload reports no violation at all.
     *
     * @param payload the payload to validate
     * @param locator the source locator to print on failure
     */
    private static void assertNoViolation(CardUpdateRequest payload, String locator) {
        Set<ConstraintViolation<CardUpdateRequest>> found = validator.validate(payload);

        assertTrue(found.isEmpty(),
                () -> "every edit passes. Found " + describe(found) + ". " + locator);
    }

    /**
     * Asserts no component name of the record mentions a subject, under any of its tokens.
     *
     * @param subject plain-language name of the subject, printed on failure
     * @param locator the source locator to print on failure
     * @param tokens folded tokens that would appear in a component name for that subject
     */
    private static void assertNoComponentNameMentions(String subject, String locator,
            String... tokens) {
        for (String componentName : componentNames()) {
            String folded = normalize(componentName);
            for (String token : tokens) {
                assertFalse(folded.contains(token),
                        "Component " + componentName + " names " + subject + " through token "
                                + token + ". The payload carries no such component. " + locator);
            }
        }
    }

    /**
     * Renders a violation set as sorted component-and-text pairs.
     *
     * @param found the violations to render
     * @return one sorted list of {@code component -> text} entries
     */
    private static String describe(Set<ConstraintViolation<CardUpdateRequest>> found) {
        return found.stream()
                .map(violation -> violation.getPropertyPath() + " -> " + violation.getMessage())
                .sorted()
                .toList()
                .toString();
    }

    // Reflection helpers.

    /**
     * Returns the component names of {@link CardUpdateRequest}, in declaration order.
     *
     * @return the declared component names
     */
    private static List<String> componentNames() {
        return Arrays.stream(CardUpdateRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the declared type of one component of {@link CardUpdateRequest}.
     *
     * @param componentName the component name to look up
     * @return the declared type, or {@code null} when no component carries that name
     */
    private static Class<?> typeOf(String componentName) {
        for (RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getType();
            }
        }
        return null;
    }

    /**
     * Returns the annotations one component of {@link CardUpdateRequest} carries. The constraint
     * annotations of {@code jakarta.validation.constraints} name no record component in their
     * target list, so the compiler propagates each one to the field of the same name.
     *
     * @param componentName the component name to look up
     * @return the annotations on the field that backs the component
     */
    private static List<Annotation> annotationsOf(String componentName) {
        try {
            Field field = CardUpdateRequest.class.getDeclaredField(componentName);
            return List.of(field.getAnnotations());
        } catch (NoSuchFieldException cause) {
            throw new AssertionError(
                    "CardUpdateRequest declares a field for component " + componentName + ". "
                            + GROUP_LOCATOR, cause);
        }
    }

    /**
     * Returns every annotation the five components carry, in component order.
     *
     * @return the annotations across the whole record
     */
    private static List<Annotation> allAnnotations() {
        List<Annotation> annotations = new ArrayList<>();
        for (String componentName : componentNames()) {
            annotations.addAll(annotationsOf(componentName));
        }
        return annotations;
    }

    /**
     * Returns the distinct texts the constraints of {@link CardUpdateRequest} carry, read from the
     * {@code message} member every constraint annotation declares.
     *
     * @return the bound texts, sorted
     */
    private static Set<String> boundMessages() {
        Set<String> messages = new TreeSet<>();
        for (Annotation annotation : allAnnotations()) {
            messages.add(messageOf(annotation));
        }
        return messages;
    }

    /**
     * Reads the {@code message} member of one constraint annotation.
     *
     * @param annotation the constraint annotation to read
     * @return the bound text
     */
    private static String messageOf(Annotation annotation) {
        try {
            Method member = annotation.annotationType().getMethod("message");
            return (String) member.invoke(annotation);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError(
                    "every constraint annotation declares a message member, and "
                            + annotation.annotationType().getName() + " declares none", cause);
        }
    }

    /**
     * Renders the simple names of a list of annotations.
     *
     * @param annotations the annotations to render
     * @return the simple names, in list order
     */
    private static List<String> simpleNames(List<Annotation> annotations) {
        return annotations.stream()
                .map(annotation -> annotation.annotationType().getSimpleName())
                .toList();
    }

    /**
     * Folds text to lower case and drops every character outside {@code a-z0-9}.
     *
     * @param text the text to fold
     * @return the folded text
     */
    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Computes the Luhn check over a digit string. The source performs no such computation, and
     * this helper establishes the Luhn outcome of the two sample card numbers.
     *
     * @param digits the digit string to check
     * @return {@code true} when the digits sum to a multiple of ten under the Luhn doubling
     */
    private static boolean passesLuhnCheck(String digits) {
        int sum = 0;
        boolean doubling = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = Character.digit(digits.charAt(index), 10);
            if (digit < 0) {
                return false;
            }
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }
}
