package com.carddemo.card.api.dto;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Pattern;

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
 * {@code app/cbl/COCRDUPC.cbl:L307}. It holds the embossed name at L308 and the active status at
 * L313. Between them the {@code CCUP-NEW-EXPIRAION-DATE} subgroup at L309 holds its year, month
 * and day parts at L310 through L312. Five components follow from those five fields.
 *
 * <p>Three fields of the enclosing group stay out of the payload.
 * {@code CCUP-NEW-ACCTID PIC X(11)} sits at L304, {@code CCUP-NEW-CARDID PIC X(16)} at L305 and
 * {@code CCUP-NEW-CVV-CD PIC X(3)} at L306, all three outside the group opened at L307. The card
 * number arrives as a component of the body rather than as a path variable. A path reaches an
 * access log, and a card number does not belong in one. The card detail screen renders all
 * sixteen of its characters: {@code CARDSID DFHMDF ATTRB=(FSET,NORM,UNPROT)} carries
 * {@code LENGTH=16} at {@code app/bms/COCRDSL.bms:L96-L100}.
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
 * <p>Every message test compares for exact equality against a literal typed in this file.
 *
 * <p>The expiry day carries no range. The edit chain runs from {@code 1230-EDIT-NAME} at L806 to
 * {@code 1260-EDIT-EXPIRY-YEAR-EXIT} at L945, and {@code 2000-DECIDE-ACTION} opens at L948, so no
 * paragraph between them reaches the day. No edit tests a card number for a checksum: L784 reads
 * {@code IF CC-CARD-NUM IS NOT NUMERIC} alone.
 *
 * <p>These tests build one {@link Validator} and reflect over the record. The constraint
 * annotations of {@code jakarta.validation.constraints} name no record component in their target
 * list. The compiler therefore propagates each one to the field and to the accessor, and the
 * helpers below read the field. The tests read no file, start no application context and issue no
 * Representational State Transfer request.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
final class CardUpdateRequestTest {

    /**
     * Count of components the payload holds. The source declares the card number at
     * {@code app/cbl/COCRDUPC.cbl:L305}. The {@code CCUP-NEW-CARDDATA} group holds five more at
     * L307 through L313, once the expiry subgroup at L309 is flattened into its year, month and
     * day slices.
     */
    private static final int EXPECTED_COMPONENT_COUNT = 6;

    /** Generated sixteen-digit card number every ordinary construction below supplies. */
    private static final String SUPPLIED_CARD_NUMBER = syntheticCardNumber(1L);

    /** Name of the component that carries the card number. */
    private static final String COMPONENT_CARD_NUMBER = "cardNumber";

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

    /**
     * The four components in the order {@code app/cbl/COCRDUPC.cbl:L698-L708} edits them:
     * {@code 1230-EDIT-NAME}, {@code 1240-EDIT-CARDSTATUS}, {@code 1250-EDIT-EXPIRY-MON}, then
     * {@code 1260-EDIT-EXPIRY-YEAR}. Bean Validation reports an unordered set, so a multi-invalid
     * payload needs this order to name its earliest failing edit.
     */
    private static final List<String> SOURCE_EDIT_ORDER = List.of(COMPONENT_EMBOSSED_NAME,
            COMPONENT_ACTIVE_STATUS, COMPONENT_EXPIRY_MONTH, COMPONENT_EXPIRY_YEAR);

    /** The five component names, in the order the source group declares its fields. */
    private static final List<String> EXPECTED_COMPONENT_NAMES = List.of(
            COMPONENT_CARD_NUMBER,
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

    /** Text of {@code WS-PROMPT-FOR-CARD} at {@code app/cbl/COCRDUPC.cbl:L180}, set at L774. */
    private static final String EXPECTED_CARD_NUMBER_NOT_PROVIDED = "Card number not provided";

    /**
     * Text of the card filter edit at {@code app/cbl/COCRDUPC.cbl:L789}, moved into the message
     * field at L790 under the test at L784. The source writes it with no condition name.
     */
    private static final String EXPECTED_CARD_NUMBER_NOT_NUMERIC =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /**
     * The one additive text. It carries no source literal and reports the transport width of the
     * expiry day, which no source paragraph edits.
     */
    private static final String EXPECTED_EXPIRY_DAY_WIDTH = "Card expiry day must be two digits";

    /**
     * The transport-width pattern of the expiry day: two digits and nothing else, from
     * {@code CCUP-NEW-EXPDAY PIC X(2)} at {@code app/cbl/COCRDUPC.cbl:L312}.
     */
    private static final String EXPECTED_EXPIRY_DAY_PATTERN = "[0-9]{2}";

    /** Length of an expiry day far past the two characters the source field holds. */
    private static final int OVERLONG_EXPIRY_DAY_LENGTH = 4096;

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

    /** Folded names of a single combined expiry date component, none of which may appear. */
    private static final Set<String> COMBINED_EXPIRY_NAMES = Set.of(
            "expiry", "expirydate", "expiration", "expirationdate", "expiraiondate",
            "expiraiondatex", "cardexpirydate", "cardexpirationdate");

    /** A generated sixteen-digit card number that passes a Luhn check. */
    private static final String LUHN_PASSING_CARD_NUMBER = luhnPassingCardNumber(2L);

    /** A generated sixteen-digit card number that fails a Luhn check. */
    private static final String LUHN_FAILING_CARD_NUMBER =
            luhnFailingCardNumber(LUHN_PASSING_CARD_NUMBER);

    /** Built once for the whole class and closed after the last test. */
    private static ValidatorFactory validatorFactory;

    /** The validator every test uses. */
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
     * Asserts the payload carries exactly six components: the card number, one name field, three
     * expiry parts and one status field. The group at {@code app/cbl/COCRDUPC.cbl:L307} holds the
     * last five, and {@code CCUP-NEW-CARDID PIC X(16)} at L305 supplies the first.
     *
     * <p>The card number is a component of the body and not a path variable. A path reaches an
     * access log, a proxy log, a trace and a browser history, and a full Primary Account Number
     * belongs in none of them.
     */
    @Test
    void updateRequestCarriesExactlySixComponents() {
        RecordComponent[] components = CardUpdateRequest.class.getRecordComponents();

        assertEquals(EXPECTED_COMPONENT_COUNT, components.length,
                () -> "CardUpdateRequest declares " + components.length + " components: "
                        + componentNames() + ". The payload holds " + EXPECTED_COMPONENT_COUNT
                        + ": " + EXPECTED_COMPONENT_NAMES + ". The card number comes from "
                        + "CCUP-NEW-CARDID PIC X(16) at app/cbl/COCRDUPC.cbl:L305 and the other "
                        + "five from the group that follows it. " + GROUP_LOCATOR);
    }

    /**
     * Asserts the six component names, in declaration order. The source declares the card number
     * at L305. The group then declares the name at L308, the year at L310, the month at L311, the
     * day at L312 and the status at L313.
     */
    @Test
    void componentNamesFollowThePayloadGroupInDeclarationOrder() {
        assertEquals(EXPECTED_COMPONENT_NAMES, componentNames(),
                () -> "CardUpdateRequest declares " + componentNames() + ". The payload group "
                        + "declares " + EXPECTED_COMPONENT_NAMES + ", in that order. "
                        + GROUP_LOCATOR);
    }

    /**
     * Asserts every component holds a {@code String}. All six source fields carry a character
     * picture: {@code PIC X(16)} at L305, {@code PIC X(50)} at L308, {@code PIC X(4)} at L310,
     * {@code PIC X(2)} at L311 and L312, and {@code PIC X(1)} at L313.
     */
    @Test
    void everyComponentHoldsAString() {
        for (String componentName : EXPECTED_COMPONENT_NAMES) {
            assertEquals(String.class, typeOf(componentName),
                    "Component " + componentName + " holds characters. " + GROUP_LOCATOR);
        }
    }

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
     * {@code app/cbl/COCRDUPC.cbl:L305}, and the card number arrives in the request body rather
     * than in a path. The record therefore declares a component for it, constrained to the sixteen
     * digits the source tests at L784.
     */
    @Test
    void updateRequestCarriesTheCardNumberInTheBody() {
        assertEquals(String.class, typeOf(COMPONENT_CARD_NUMBER),
                "CCUP-NEW-CARDID PIC X(16) at app/cbl/COCRDUPC.cbl:L305 is a character field");
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "Y"),
                "app/cbl/COCRDUPC.cbl:L784 tests a card number for sixteen digits");
        assertSingleViolation(new CardUpdateRequest("050002445376574", "JOHN Q PUBLIC", "2027",
                        "03", "01", "Y"),
                COMPONENT_CARD_NUMBER, CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                "app/cbl/COCRDUPC.cbl:L789, moved at L790 under the test at L784");
        assertSingleViolation(new CardUpdateRequest("05000244537657x0", "JOHN Q PUBLIC", "2027",
                        "03", "01", "Y"),
                COMPONENT_CARD_NUMBER, CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                "a card number holds digits only");
        assertSingleViolation(new CardUpdateRequest(null, "JOHN Q PUBLIC", "2027", "03", "01", "Y"),
                COMPONENT_CARD_NUMBER, CardValidationMessages.PROMPT_FOR_CARD,
                "app/cbl/COCRDUPC.cbl:L180, set at L774 under the guard on L773");
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
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "01", "Y"),
                STATUS_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "01", "N"),
                STATUS_LOCATOR);
    }

    /**
     * Asserts lower-case input fails. Paragraph {@code 1240-EDIT-CARDSTATUS} moves the value to
     * {@code FLG-YES-NO-CHECK} at {@code app/cbl/COCRDUPC.cbl:L861} and tests the condition name
     * at L863, with no upper-case conversion between the two lines.
     */
    @Test
    void lowerCaseActiveStatusIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "y"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "n"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    /**
     * Asserts any other character fails with the same text. The source writes it at
     * {@code app/cbl/COCRDUPC.cbl:L869}, under the guard on L868.
     */
    @Test
    void anyOtherActiveStatusCharacterIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "X"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "1"),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    /**
     * Asserts a missing status takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L850-L852} and writes the text at L856.
     */
    @Test
    void missingActiveStatusTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", null),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC",
                        "2027", "03", "01", " "),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC",
                        "2027", "03", "01", ""),
                COMPONENT_ACTIVE_STATUS, EXPECTED_STATUS_MUST_BE_YES_NO, STATUS_LOCATOR);
    }

    // Expiry month. The condition name at L95 spans 1 through 12.

    /** Asserts both ends of the range at {@code app/cbl/COCRDUPC.cbl:L95} pass. */
    @Test
    void expiryMonthAcceptsBothEndsOfTheSourceRange() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "01",
                        "01", "Y"),
                MONTH_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "12",
                        "01", "Y"),
                MONTH_LOCATOR);
    }

    /** Asserts the value below the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L198}. */
    @Test
    void expiryMonthZeroIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "00", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /** Asserts the value above the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L198}. */
    @Test
    void expiryMonthThirteenIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "13", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /**
     * Asserts a one-character month fails. The source field is {@code CCUP-NEW-EXPMON PIC X(2)}
     * at {@code app/cbl/COCRDUPC.cbl:L311} and the write path joins two characters at L1469.
     */
    @Test
    void singleDigitExpiryMonthIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "1", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    /**
     * Asserts a missing month takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L883-L885} and writes the text at L889.
     */
    @Test
    void missingExpiryMonthTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        null, "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC",
                        "2027", "  ", "01", "Y"),
                COMPONENT_EXPIRY_MONTH, EXPECTED_MONTH_NOT_VALID, MONTH_LOCATOR);
    }

    // Expiry year. The condition name at L99 spans 1950 through 2099.

    /** Asserts both ends of the range at {@code app/cbl/COCRDUPC.cbl:L99} pass. */
    @Test
    void expiryYearAcceptsBothEndsOfTheSourceRange() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "1950", "03",
                        "01", "Y"),
                YEAR_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2099", "03",
                        "01", "Y"),
                YEAR_LOCATOR);
    }

    /** Asserts the year below the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L200}. */
    @Test
    void expiryYearNineteenFortyNineIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "1949",
                        "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    /** Asserts the year above the range fails with the text at {@code app/cbl/COCRDUPC.cbl:L200}. */
    @Test
    void expiryYearTwentyOneHundredIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2100",
                        "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    /**
     * Asserts a missing year takes the same text. The source tests for low values, spaces and
     * zeroes at {@code app/cbl/COCRDUPC.cbl:L916-L918} and writes the text at L922.
     */
    @Test
    void missingExpiryYearTakesTheSameSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", null,
                        "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC",
                        "    ", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
        assertEveryViolationReports(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC",
                        "ABCD", "03", "01", "Y"),
                COMPONENT_EXPIRY_YEAR, EXPECTED_YEAR_NOT_VALID, YEAR_LOCATOR);
    }

    // Embossed name. The edit at L806 admits letters and spaces to fifty characters.

    /**
     * Asserts letters and spaces pass, up to the fifty characters of
     * {@code CCUP-NEW-CRDNAME PIC X(50)} at {@code app/cbl/COCRDUPC.cbl:L308}.
     */
    @Test
    void embossedNameAcceptsLettersAndSpacesToFiftyCharacters() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "01", "Y"),
                NAME_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "Mary Jane Smith", "2027",
                        "03", "01", "Y"),
                NAME_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "A".repeat(50), "2027", "03",
                        "01", "Y"),
                NAME_LOCATOR);
    }

    /**
     * Asserts a digit fails with the text at {@code app/cbl/COCRDUPC.cbl:L184}. The alphabet
     * literal at L255 lists the fifty-two letters and no digit.
     */
    @Test
    void embossedNameWithADigitIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC1", "2027",
                        "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts punctuation fails with the same text. The strip at
     * {@code app/cbl/COCRDUPC.cbl:L823-L826} leaves a hyphen in place, and the trim test at L828
     * then finds a character.
     */
    @Test
    void embossedNameWithAHyphenIsRejectedWithTheSourceText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "MARY-JANE", "2027", "03",
                        "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "O'BRIEN", "2027", "03",
                        "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts a fifty-first character fails. {@code CCUP-NEW-CRDNAME} holds fifty characters at
     * {@code app/cbl/COCRDUPC.cbl:L308}, and {@code CARD-EMBOSSED-NAME PIC X(50)} at
     * {@code app/cpy/CVACT02Y.cpy:L8} holds the stored form.
     */
    @Test
    void embossedNameLongerThanFiftyCharactersIsRejected() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "A".repeat(51), "2027",
                        "03", "01", "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_MUST_BE_ALPHA, NAME_LOCATOR);
    }

    /**
     * Asserts a missing name takes the text at {@code app/cbl/COCRDUPC.cbl:L182}. The source
     * tests for low values, spaces and zeroes at L811 through L813 and writes the text at L817.
     */
    @Test
    void missingEmbossedNameTakesTheNotProvidedText() {
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, null, "2027", "03", "01",
                        "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "", "2027", "03", "01",
                        "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
        assertSingleViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "   ", "2027", "03", "01",
                        "Y"),
                COMPONENT_EMBOSSED_NAME, EXPECTED_NAME_NOT_PROVIDED, NAME_LOCATOR);
    }

    @Test
    void aFullyValidPayloadProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "01", "Y"),
                GROUP_LOCATOR);
    }

    // Message binding. The texts the constraints carry come from the production constant class.

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
     * Asserts every constraint on the record binds one of the seven source texts or the one
     * declared additive text. The set is read from the {@code message} member of each annotation,
     * so a constraint bound to a ninth text fails here.
     *
     * <p>Two of the seven belong to the card number, which arrives in the request body rather than
     * in a path. The source writes them at {@code app/cbl/COCRDUPC.cbl:L180} and L789, and the
     * remaining five come from the four edits at L806 through L945.
     *
     * <p>{@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_DAY_WIDTH} carries no source literal.
     * It reports the transport width of the expiry day, which the source does not edit and a
     * Representational State Transfer (REST) payload must still bound.
     */
    @Test
    void everyConstraintBindsOneOfTheSevenSourceTextsOrTheDeclaredWidthText() {
        Set<String> expected = new TreeSet<>(List.of(
                EXPECTED_CARD_NUMBER_NOT_PROVIDED,
                EXPECTED_CARD_NUMBER_NOT_NUMERIC,
                EXPECTED_NAME_NOT_PROVIDED,
                EXPECTED_NAME_MUST_BE_ALPHA,
                EXPECTED_STATUS_MUST_BE_YES_NO,
                EXPECTED_MONTH_NOT_VALID,
                EXPECTED_YEAR_NOT_VALID,
                EXPECTED_EXPIRY_DAY_WIDTH));

        assertEquals(expected, boundMessages(),
                () -> "CardUpdateRequest binds " + boundMessages() + ". The card-number edit of "
                        + "app/cbl/COCRDUPC.cbl writes the text at L180, set at L774, and the "
                        + "text at L789, moved at L790 under the test at L784. The four edits at "
                        + "L806-L945 write the five texts at L182, L184, L196, L198 and L200. The "
                        + "eighth text is additive and reports the transport width of the expiry "
                        + "day alone.");
    }

    /**
     * Asserts each expected text above equals the production constant it stands for. The set
     * comparison in the test above proves the record binds these eight texts. This test proves the
     * eight literals typed into this class are the eight the production class declares. A silent
     * edit to either side therefore fails here.
     */
    @Test
    void everyExpectedTextEqualsTheProductionConstant() {
        assertEquals(EXPECTED_CARD_NUMBER_NOT_PROVIDED, CardValidationMessages.PROMPT_FOR_CARD,
                "app/cbl/COCRDUPC.cbl:L180, set at L774 under the guard on L773");
        assertEquals(EXPECTED_CARD_NUMBER_NOT_NUMERIC,
                CardValidationMessages.CARD_FILTER_NOT_NUMERIC,
                "app/cbl/COCRDUPC.cbl:L789, moved at L790 under the test at L784");
        assertEquals(EXPECTED_NAME_NOT_PROVIDED, CardValidationMessages.PROMPT_FOR_NAME,
                "app/cbl/COCRDUPC.cbl:L182");
        assertEquals(EXPECTED_NAME_MUST_BE_ALPHA, CardValidationMessages.NAME_MUST_BE_ALPHA,
                "app/cbl/COCRDUPC.cbl:L184");
        assertEquals(EXPECTED_STATUS_MUST_BE_YES_NO,
                CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                "app/cbl/COCRDUPC.cbl:L196");
        assertEquals(EXPECTED_MONTH_NOT_VALID,
                CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L198");
        assertEquals(EXPECTED_YEAR_NOT_VALID, CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID,
                "app/cbl/COCRDUPC.cbl:L200");
        assertEquals(EXPECTED_EXPIRY_DAY_WIDTH,
                CardValidationMessages.ADDITIVE_CARD_EXPIRY_DAY_WIDTH,
                "additive: no source paragraph edits the expiry day");
    }

    // First absence: the expiry day carries no calendar edit. It carries a transport width, and
    // nothing else.

    /**
     * Asserts the expiry day component carries the transport-width bound and no calendar rule.
     *
     * <p>The source declares a condition name for the month at {@code app/cbl/COCRDUPC.cbl:L95} and
     * for the year at L99, and it declares none for the day. The two annotations here reproduce that
     * absence of a calendar rule while holding the component to the two characters of
     * {@code CCUP-NEW-EXPDAY PIC X(2)} at {@code app/cbl/COCRDUPC.cbl:L312}. The source reads that
     * field from a fixed-width map field. A REST payload has no such width, so the bound is
     * additive. {@code Min} and {@code Max} stay off the component, because the source declares no
     * calendar rule for the day.
     */
    @Test
    void expiryDayCarriesTheTransportWidthBoundAndNoCalendarRule() {
        List<Annotation> annotations = annotationsOf(COMPONENT_EXPIRY_DAY);
        Set<String> declared = new TreeSet<>(simpleNames(annotations));

        assertEquals(new TreeSet<>(List.of("NotBlank", "Pattern")), declared,
                () -> "Component " + COMPONENT_EXPIRY_DAY + " carries " + declared
                        + ". The component bounds the transport width and adds no calendar rule. "
                        + EXPIRY_DAY_LOCATOR);
        assertEquals(EXPECTED_EXPIRY_DAY_PATTERN, patternOf(COMPONENT_EXPIRY_DAY),
                "the bound is the two digits the source field holds and nothing else");
    }

    /**
     * Asserts an out-of-range expiry day passes. Day 99 falls outside any calendar month, and the
     * source performs no day edit. The payload therefore reports nothing on the day component. The
     * bound is the transport width alone, and it admits any two digits.
     */
    @Test
    void outOfRangeExpiryDayProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "99", "Y"),
                EXPIRY_DAY_LOCATOR);
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03",
                        "00", "Y"),
                EXPIRY_DAY_LOCATOR);
    }

    @Test
    void impossibleCalendarDayProducesNoViolation() {
        assertNoViolation(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "02",
                        "31", "Y"),
                EXPIRY_DAY_LOCATOR);
    }

    /**
     * Asserts a non-numeric, absent, empty, overlong or control-bearing expiry day fails the
     * transport-width bound, and that every such failure names the expiry day alone.
     *
     * <p>The bound is additive. The source edits the name, the status, the month and the year at
     * {@code app/cbl/COCRDUPC.cbl:L806-L945} and edits no day. It reads the day from a
     * two-character map field, so no value of another width or another character class could reach
     * it. A Representational State Transfer payload carries no width, and an unbounded component
     * accepts control characters, markup and arbitrarily long text. Each case below reports
     * {@link CardValidationMessages#ADDITIVE_CARD_EXPIRY_DAY_WIDTH} and nothing else, so no other
     * component of the payload is affected.
     */
    @Test
    void nonNumericAbsentAndOverlongExpiryDayFailTheTransportWidthBound() {
        List<String> rejected = new ArrayList<>();
        rejected.add("ZZ");
        rejected.add(null);
        rejected.add("");
        rejected.add("0");
        rejected.add("012");
        rejected.add("0\n");
        rejected.add("<script>alert(1)</script>");
        rejected.add("0".repeat(OVERLONG_EXPIRY_DAY_LENGTH));

        for (String day : rejected) {
            assertEveryViolationReports(
                    new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "03", day,
                                    "Y"),
                    COMPONENT_EXPIRY_DAY, EXPECTED_EXPIRY_DAY_WIDTH, EXPIRY_DAY_LOCATOR);
        }
    }

    // Second absence: no annotation and no message applies a checksum to the card number. The
    // record does carry the card number, in the body, so the absence asserted below is the
    // checksum and not the field.

    @Test
    void theRecordUsesFourConstraintAnnotationsAndNoMore() {
        Set<String> expected = new TreeSet<>(List.of("Max", "Min", "NotBlank", "Pattern"));
        Set<String> actual = new TreeSet<>(simpleNames(allAnnotations()));

        assertEquals(expected, actual,
                () -> "CardUpdateRequest carries " + actual + ". The four edits of "
                        + "app/cbl/COCRDUPC.cbl at L806-L945 need a presence test, a character "
                        + "test and two range ends. " + CARD_NUMBER_TEST_LOCATOR);
    }

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
     * Asserts no text the constraints carry names a checksum, and that the two card-number texts
     * the record does bind are exactly the two the source's own card-number edit writes.
     *
     * <p>The source writes three card-number texts. Two belong to the card-number edit this record
     * reproduces: {@code app/cbl/COCRDUPC.cbl:L180}, set at L774, and L789, moved at L790 under the
     * test at L784. The third, at L194, belongs to the card search screen and is not bound here.
     * None of the three computes a checksum, so binding two of them adds no rule the source lacks.
     */
    @Test
    void noBoundTextNamesAChecksum() {
        for (String message : boundMessages()) {
            String folded = normalize(message);
            for (String token : CHECKSUM_TOKENS) {
                assertFalse(folded.contains(token),
                        "Bound text names a checksum token " + token + ": " + message + ". "
                                + CARD_NUMBER_TEST_LOCATOR);
            }
        }
        Set<String> bound = boundMessages();
        assertTrue(bound.contains(CardValidationMessages.PROMPT_FOR_CARD),
                "the card number arrives in the request body, so the presence test binds the text "
                        + "at app/cbl/COCRDUPC.cbl:L180, set at L774 under the guard on L773");
        assertTrue(bound.contains(CardValidationMessages.CARD_FILTER_NOT_NUMERIC),
                "the sixteen-digit test binds the text at app/cbl/COCRDUPC.cbl:L789, moved at L790 "
                        + "under the test at L784. " + CARD_NUMBER_TEST_LOCATOR);
        assertFalse(bound.contains(CARD_NUMBER_DIGIT_COUNT_TEXT),
                "app/cbl/COCRDUPC.cbl:L194 belongs to the card search screen, not to the update "
                        + "edit, and it counts digits rather than computing a checksum. "
                        + CARD_NUMBER_TEST_LOCATOR);
    }

    /**
     * Asserts a sixteen-digit card number that fails a Luhn check passes every constraint on this
     * payload.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L784} reads {@code IF CC-CARD-NUM IS NOT NUMERIC} and nothing
     * more. The source therefore posts a Luhn-failing card, and this record must too. A constraint
     * that refused it would refuse a card the source accepts, so the absence of a checksum rule is
     * a deliberate non-addition rather than an oversight.
     */
    @Test
    void luhnFailingSixteenDigitCardNumberPassesEveryConstraintOnThisPayload() {
        assertEquals(16, LUHN_FAILING_CARD_NUMBER.length(),
                "the sample card number holds sixteen digits, the width of "
                        + "CCUP-NEW-CARDID PIC X(16) at app/cbl/COCRDUPC.cbl:L305");
        assertTrue(passesLuhnCheck(LUHN_PASSING_CARD_NUMBER),
                "the Luhn-passing sample of width " + LUHN_PASSING_CARD_NUMBER.length()
                        + " passes a Luhn check");
        assertFalse(passesLuhnCheck(LUHN_FAILING_CARD_NUMBER),
                "the Luhn-failing sample of width " + LUHN_FAILING_CARD_NUMBER.length()
                        + " fails a Luhn check");

        assertNoViolation(new CardUpdateRequest(LUHN_FAILING_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "Y"),
                "the Luhn-failing card number passes every edit this record applies. "
                        + CARD_NUMBER_TEST_LOCATOR);
        assertNoViolation(new CardUpdateRequest(LUHN_PASSING_CARD_NUMBER, "JOHN Q PUBLIC", "2027",
                        "03", "01", "Y"),
                "the Luhn-passing card number passes the same edits, so the two outcomes are "
                        + "indistinguishable to this record. " + CARD_NUMBER_TEST_LOCATOR);
        assertEquals(1, componentsNaming(CARD_NUMBER_TOKENS).size(),
                () -> "exactly one component names a card number, and it is the body component "
                        + "the sixteen-digit test at app/cbl/COCRDUPC.cbl:L784 reads. Components "
                        + "naming a card number: " + componentsNaming(CARD_NUMBER_TOKENS));
        assertEquals(List.of(COMPONENT_CARD_NUMBER), componentsNaming(CARD_NUMBER_TOKENS),
                "the card number travels in the body under the name cardNumber, never in a path");
    }

    // Multi-invalid payloads. The source edits four components in one fixed order, and the first
    // failing edit writes the message the caller keeps.

    /**
     * Asserts that each adjacent pair of edits, both failing at once, reports the earlier edit's
     * message first.
     *
     * <p>{@code app/cbl/COCRDUPC.cbl:L698-L708} performs the four edits in one order:
     * {@code 1230-EDIT-NAME}, then {@code 1240-EDIT-CARDSTATUS}, then
     * {@code 1250-EDIT-EXPIRY-MON}, then {@code 1260-EDIT-EXPIRY-YEAR}. Bean Validation reports an
     * unordered set, so {@link #firstMessageInSourceOrder} sorts the violations by that order
     * before reading the first.</p>
     */
    @Test
    void eachAdjacentPairOfFailingEditsReportsTheEarlierMessageFirst() {
        assertEquals(EXPECTED_NAME_MUST_BE_ALPHA,
                firstMessageInSourceOrder(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN1",
                                "2027", "03", "01", "X")),
                "the name edit at app/cbl/COCRDUPC.cbl:L698 runs ahead of the status edit at "
                        + "app/cbl/COCRDUPC.cbl:L701");

        assertEquals(EXPECTED_STATUS_MUST_BE_YES_NO,
                firstMessageInSourceOrder(
                        new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "2027", "13",
                                        "01", "X")),
                "the status edit at app/cbl/COCRDUPC.cbl:L701 runs ahead of the month edit at "
                        + "app/cbl/COCRDUPC.cbl:L704");

        assertEquals(EXPECTED_MONTH_NOT_VALID,
                firstMessageInSourceOrder(
                        new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN Q PUBLIC", "1949", "13",
                                        "01", "Y")),
                "the month edit at app/cbl/COCRDUPC.cbl:L704 runs ahead of the year edit at "
                        + "app/cbl/COCRDUPC.cbl:L707");
    }

    @Test
    void aPayloadFailingEveryEditReportsTheNameMessageFirst() {
        CardUpdateRequest allInvalid = new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN1", "1949",
                        "13", "01", "X");

        assertEquals(EXPECTED_NAME_MUST_BE_ALPHA, firstMessageInSourceOrder(allInvalid),
                "the earliest failing edit is the name edit at app/cbl/COCRDUPC.cbl:L698");

        Set<String> components = new LinkedHashSet<>();
        for (ConstraintViolation<CardUpdateRequest> violation : validator.validate(allInvalid)) {
            components.add(violation.getPropertyPath().toString());
        }

        assertEquals(new LinkedHashSet<>(SOURCE_EDIT_ORDER), components,
                "every one of the four edits fails on this payload");
    }

    @Test
    void anAbsentNameReportsThePresenceMessageAheadOfEveryLaterEdit() {
        for (CardUpdateRequest payload : List.of(
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "", "2027", "03", "01", "X"),
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "", "2027", "13", "01", "Y"),
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "", "1949", "03", "01", "Y"),
                new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "", "1949", "13", "01", "X"))) {

            assertEquals(EXPECTED_NAME_NOT_PROVIDED, firstMessageInSourceOrder(payload),
                    "an absent name reports the presence text at app/cbl/COCRDUPC.cbl:L182 first");
        }
    }

    @Test
    void aPayloadFailingTwoEditsReportsTwoComponents() {
        Set<String> components = new LinkedHashSet<>();
        for (ConstraintViolation<CardUpdateRequest> violation
                : validator.validate(new CardUpdateRequest(SUPPLIED_CARD_NUMBER, "JOHN1", "2027",
                                "03", "01", "X"))) {
            components.add(violation.getPropertyPath().toString());
        }

        assertEquals(Set.of(COMPONENT_EMBOSSED_NAME, COMPONENT_ACTIVE_STATUS), components,
                "the name edit and the status edit both report");
    }

    // Assertion helpers.

    /**
     * Reports the message of the earliest failing edit, ordering the violations the way
     * {@code app/cbl/COCRDUPC.cbl:L698-L708} performs the edits.
     *
     * @param payload the payload to validate
     * @return the message the earliest failing edit carries
     */
    private static String firstMessageInSourceOrder(CardUpdateRequest payload) {
        Set<ConstraintViolation<CardUpdateRequest>> found = validator.validate(payload);

        assertFalse(found.isEmpty(), () -> "at least one edit fails on this payload");

        ConstraintViolation<CardUpdateRequest> earliest = null;
        int earliestPosition = Integer.MAX_VALUE;
        for (ConstraintViolation<CardUpdateRequest> violation : found) {
            int position = SOURCE_EDIT_ORDER.indexOf(violation.getPropertyPath().toString());

            assertTrue(position >= 0,
                    () -> "component " + violation.getPropertyPath()
                            + " sits in the source edit order " + SOURCE_EDIT_ORDER);
            if (position < earliestPosition) {
                earliestPosition = position;
                earliest = violation;
            }
        }
        return earliest.getMessage();
    }

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
     * Returns the component names whose folded form contains any of the tokens supplied, in
     * declaration order. The inverse of {@link #assertNoComponentNameMentions}: a test that expects
     * exactly one match names it here rather than asserting an absence.
     *
     * @param tokens folded tokens to look for
     * @return matching component names in declaration order
     */
    private static List<String> componentsNaming(String... tokens) {
        return componentNames().stream()
                .filter(componentName -> {
                    String folded = normalize(componentName);
                    return Arrays.stream(tokens).anyMatch(folded::contains);
                })
                .toList();
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
     * Returns the regular expression one component's {@code Pattern} constraint declares.
     *
     * @param componentName the record component to read
     * @return the {@code regexp} member of its one {@code Pattern} constraint
     */
    private static String patternOf(String componentName) {
        for (Annotation annotation : annotationsOf(componentName)) {
            if (annotation instanceof Pattern pattern) {
                return pattern.regexp();
            }
        }
        throw new AssertionError("component " + componentName + " declares no Pattern constraint");
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

    /** Builds a clearly synthetic sixteen-digit card value. */
    private static String syntheticCardNumber(long serial) {
        return "9999" + String.format(Locale.ROOT, "%012d", serial);
    }

    /** Builds a synthetic sixteen-digit value that passes a Luhn check. */
    private static String luhnPassingCardNumber(long serial) {
        String prefix = "9999" + String.format(Locale.ROOT, "%011d", serial);
        for (int digit = 0; digit <= 9; digit++) {
            String candidate = prefix + digit;
            if (passesLuhnCheck(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no Luhn check digit was found");
    }

    /** Changes the last digit to produce a synthetic value that fails a Luhn check. */
    private static String luhnFailingCardNumber(String passing) {
        String prefix = passing.substring(0, passing.length() - 1);
        int lastDigit = Character.digit(passing.charAt(passing.length() - 1), 10);
        for (int offset = 1; offset <= 9; offset++) {
            String candidate = prefix + ((lastDigit + offset) % 10);
            if (!passesLuhnCheck(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no failing Luhn digit was found");
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
