package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Constraint tests for {@link AuthorizationRequest}, the synchronous authorization payload.
 *
 * <p>The online transaction-add program validates the same fields in three passes.
 * {@code app/cbl/COTRN02C.cbl:L195-L230} resolves the identifier pair,
 * {@code app/cbl/COTRN02C.cbl:L251-L320} rejects eleven empty fields, and
 * {@code app/cbl/COTRN02C.cbl:L322-L334} applies the numeric class test to two of them. Every
 * expected text below is read from the {@code MOVE} that writes it into {@code WS-MESSAGE}.
 *
 * <p>Two controls have no COBOL ancestor and are asserted here all the same. The request names its
 * subject once, because {@code app/cbl/COTRN02C.cbl:L195-L230} keeps the account branch and never
 * reads the card field afterwards, and the identity that decides an authorization is the one the
 * cross-reference row holds at {@code app/cbl/CBTRN02C.cbl:L382-L383}; believing a second identifier
 * a caller supplied would be account confusion, so the pair is refused rather than silently
 * preferred. Every text component admits printable characters only, because every character of all
 * three hundred records of {@code app/data/ASCII/dailytran.txt} falls between the space and the
 * tilde, and a carriage return in the description would reach the fixed-width alert record the
 * notification service renders and let a caller forge a line of it.
 *
 * <p>These tests build one {@link Validator}, read no file, start no application context and issue
 * no Representational State Transfer request.
 */
final class AuthorizationRequestTest {

    /** Card number of a fixture row, and the one identifier {@link #VALID} names. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** Account identifier of a fixture row, named in place of the card number where a test needs it. */
    private static final String ACCOUNT_ID = "00000000077";

    /**
     * A complete request every test below starts from. It carries a card number and no account
     * identifier, which is the shape all 300 records of {@code app/cpy/CVTRA06Y.cpy} take, and which
     * satisfies the rule that a request names its subject exactly once.
     */
    private static final AuthorizationRequest VALID = new AuthorizationRequest(
            null, "01", "0001", "POS TERM", "Purchase at Abshire-Lowe",
            "+00000504.77", "800000000", "Abshire-Lowe", "North Enoshaven", "72112",
            CARD_NUMBER, "2022-06-10 19:27:53.412000", "2022-06-10-19.27.53.410000",
            null);

    /** The factory the tests share. */
    private static ValidatorFactory validatorFactory;

    /** The validator every test below uses. */
    private static Validator validator;

    /** Builds the validator the tests share, from the provider this module already declares. */
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

    /** Asserts the complete request reports no violation, so every test below starts from zero. */
    @Test
    void aCompleteRequestReportsNoViolation() {
        assertEquals(Set.of(), messages(VALID),
                "the complete request satisfies every constraint");
    }

    /**
     * Asserts each of the eleven fields the source rejects when empty reports its own verbatim text.
     *
     * <p>A field is emptied to spaces, which is what the source tests for. The two identifier
     * components stay filled, so the identifier rule reports nothing and only the emptied field
     * does.
     */
    @Test
    void eachOfTheElevenRequiredFieldsReportsItsOwnEmptinessText() {
        assertEquals(Set.of(AuthorizationRequest.TYPE_CODE_EMPTY_MESSAGE),
                messages(withTypeCode("   ")), "an empty type code reports its own text");
        assertEquals(Set.of(AuthorizationRequest.CATEGORY_CODE_EMPTY_MESSAGE),
                messages(withCategoryCode("   ")), "an empty category code reports its own text");
        assertEquals(Set.of(AuthorizationRequest.SOURCE_EMPTY_MESSAGE),
                messages(withSource("   ")), "an empty source reports its own text");
        assertEquals(Set.of(AuthorizationRequest.DESCRIPTION_EMPTY_MESSAGE),
                messages(withDescription("   ")), "an empty description reports its own text");
        assertEquals(Set.of(AuthorizationRequest.AMOUNT_EMPTY_MESSAGE),
                messages(withAmount("   ")), "an empty amount reports its own text");
        assertEquals(Set.of(AuthorizationRequest.ORIGIN_DATE_EMPTY_MESSAGE),
                messages(withOriginTimestamp("   ")),
                "an empty origin timestamp reports its own text");
        assertEquals(Set.of(AuthorizationRequest.PROCESSING_DATE_EMPTY_MESSAGE),
                messages(withProcessingTimestamp("   ")),
                "an empty processing timestamp reports its own text");
        assertEquals(Set.of(AuthorizationRequest.MERCHANT_ID_EMPTY_MESSAGE),
                messages(withMerchantId("   ")),
                "an empty merchant identifier reports its own text");
        assertEquals(Set.of(AuthorizationRequest.MERCHANT_NAME_EMPTY_MESSAGE),
                messages(withMerchantName("   ")), "an empty merchant name reports its own text");
        assertEquals(Set.of(AuthorizationRequest.MERCHANT_CITY_EMPTY_MESSAGE),
                messages(withMerchantCity("   ")), "an empty merchant city reports its own text");
        assertEquals(Set.of(AuthorizationRequest.MERCHANT_ZIP_EMPTY_MESSAGE),
                messages(withMerchantZip("   ")),
                "an empty merchant postal code reports its own text");
    }

    /** Asserts an absent field reports the same text a blank one reports. */
    @Test
    void anAbsentFieldReportsTheSameTextAsABlankOne() {
        assertEquals(messages(withTypeCode("   ")), messages(withTypeCode(null)),
                "absent and blank are one condition for the type code");
        assertEquals(messages(withMerchantZip("   ")), messages(withMerchantZip(null)),
                "absent and blank are one condition for the merchant postal code");
    }

    /**
     * Asserts the two class-tested fields report the numeric text when they hold content that is not
     * all digits, and the emptiness text when they hold none.
     *
     * <p>The source runs the emptiness pass first and sends the screen on the first empty field, so
     * an empty field never reaches the class test. Normalising a blank component to absent
     * reproduces that ordering here.
     */
    @Test
    void theTwoClassTestedFieldsSeparateAnEmptyValueFromANonNumericOne() {
        assertEquals(Set.of(AuthorizationRequest.TYPE_CODE_NOT_NUMERIC_MESSAGE),
                messages(withTypeCode("AB")), "a filled non-numeric type code is not numeric");
        assertEquals(Set.of(AuthorizationRequest.CATEGORY_CODE_NOT_NUMERIC_MESSAGE),
                messages(withCategoryCode("00X1")),
                "a filled non-numeric category code is not numeric");
        assertTrue(
                messages(withTypeCode("  ")).contains(AuthorizationRequest.TYPE_CODE_EMPTY_MESSAGE),
                "an empty type code reports emptiness");
        assertFalse(
                messages(withTypeCode("  "))
                        .contains(AuthorizationRequest.TYPE_CODE_NOT_NUMERIC_MESSAGE),
                "an empty type code does not also report the class test, as the source does not");
    }

    /** Asserts a field holding an embedded space fails the class test, as the source field does. */
    @Test
    void aFieldHoldingAnEmbeddedSpaceFailsTheClassTest() {
        assertEquals(Set.of(AuthorizationRequest.TYPE_CODE_NOT_NUMERIC_MESSAGE),
                messages(withTypeCode("1 ")),
                "the COBOL class test reads a field position by position, so a trailing space is "
                        + "not a digit");
    }

    /** Asserts the canonical constructor turns every blank component into an absent one. */
    @Test
    void theConstructorTurnsEveryBlankComponentIntoAnAbsentOne() {
        AuthorizationRequest blanked = new AuthorizationRequest("  ", "  ", "  ", "  ", "  ", "  ",
                "  ", "  ", "  ", "  ", "  ", "  ", "  ", "  ");

        assertNull(blanked.transactionId(), "a blank transaction identifier normalises to absent");
        assertNull(blanked.transactionTypeCode(), "a blank type code normalises to absent");
        assertNull(blanked.transactionCategoryCode(), "a blank category code normalises to absent");
        assertNull(blanked.source(), "a blank source normalises to absent");
        assertNull(blanked.description(), "a blank description normalises to absent");
        assertNull(blanked.amount(), "a blank amount normalises to absent");
        assertNull(blanked.merchantId(), "a blank merchant identifier normalises to absent");
        assertNull(blanked.merchantName(), "a blank merchant name normalises to absent");
        assertNull(blanked.merchantCity(), "a blank merchant city normalises to absent");
        assertNull(blanked.merchantZip(), "a blank merchant postal code normalises to absent");
        assertNull(blanked.cardNumber(), "a blank card number normalises to absent");
        assertNull(blanked.originTimestamp(), "a blank origin timestamp normalises to absent");
        assertNull(blanked.processingTimestamp(),
                "a blank processing timestamp normalises to absent");
        assertNull(blanked.accountId(), "a blank account identifier normalises to absent");
    }

    /** Asserts a component holding content passes through the constructor unchanged. */
    @Test
    void aComponentHoldingContentPassesThroughUnchanged() {
        assertEquals(" 07 ", withTypeCode(" 07 ").transactionTypeCode(),
                "content survives normalisation, spaces included");
    }

    /**
     * Asserts the card number is the only identifier that names a subject.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L206-L209} also accepts an account identifier and resolves a
     * card from the alternate index, and that branch is not reproduced: the card it returns is
     * whichever one the index holds first rather than one the caller presented, so a caller naming
     * an account authorizes a card the caller never held. The record the decision reproduces carries
     * a card number and no account identifier at all ({@code app/cpy/CVTRA06Y.cpy:L4-L18}).
     */
    @Test
    void onlyACardNumberNamesTheSubject() {
        assertEquals(Set.of(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE),
                messages(withIdentifiers(null, null)),
                "a request naming no card is rejected");
        assertEquals(Set.of(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE),
                messages(withIdentifiers(ACCOUNT_ID, null)),
                "an account identifier does not substitute for a card number");
        assertEquals(Set.of(), messages(withIdentifiers(null, CARD_NUMBER)),
                "a card number alone names the subject");
    }

    /**
     * Asserts a caller-supplied transaction identifier is refused rather than ignored.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L444-L451} allocates the identifier by browsing the file
     * backwards from high values and adding one, so no screen field offers it. An identifier a
     * caller chooses is one a caller can repeat, and a repeated identifier merges one payment with
     * another in the ledger, the alert history and the fraud assessment while every consumer sees a
     * fresh event identifier and no duplicate to skip.
     */
    @Test
    void aCallerSuppliedTransactionIdentifierIsRefused() {
        assertEquals(Set.of(AuthorizationRequest.TRANSACTION_ID_NOT_ACCEPTED_MESSAGE),
                messages(withTransactionId("0000000000683580")),
                "a caller-supplied transaction identifier reached the decision");
        assertEquals(Set.of(), messages(withTransactionId(null)),
                "an absent transaction identifier is the only accepted form");
    }

    /**
     * Asserts a valid account identifier may accompany the required card as a domain cross-check.
     */
    @Test
    void bothIdentifiersTogetherReachTheDomainCrossCheck() {
        assertEquals(Set.of(), messages(withIdentifiers(ACCOUNT_ID, CARD_NUMBER)),
                "the card is the lookup key and the account is an optional cross-check");
    }

    /**
     * Asserts a space-filled account component is absent.
     *
     * <p>The source reads the field from a space-filled map area, so it tests {@code NOT = SPACES}
     * at {@code app/cbl/COTRN02C.cbl:L196} before reading it. This payload reaches the same outcome
     * one step earlier: the canonical constructor turns a blank component into an absent one, so a
     * space-filled account identifier is an absence and the card number alone decides the subject.
     */
    @Test
    void aSpaceFilledAccountIdentifierLeavesOnlyTheCard() {
        Set<String> reported = messages(withIdentifiers("   ", CARD_NUMBER));

        assertEquals(Set.of(), reported,
                () -> "a space-filled account component is absent: " + reported);
    }

    /**
     * Asserts a carriage return in the description is refused.
     *
     * <p>The alert record at {@code app/cpy/COSTM01.CPY} lays out its columns by position, so a
     * carriage return would let a caller add a line to what the notification service renders.
     */
    @Test
    void aCarriageReturnInTheDescriptionIsRefused() {
        assertSingleViolation(withDescription("Coffee shop\r\nCoffee shop            999999.99"),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE,
                "a carriage return would forge a line of the rendered alert record");
    }

    /** Asserts a bare line feed, a tab, a null character and an escape are each refused. */
    @Test
    void theOtherControlCharactersAreRefusedToo() {
        assertSingleViolation(withDescription("Coffee shop\nforged line"),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE,
                "a line feed alone ends a line in the rendered record");
        assertSingleViolation(withDescription("Coffee\tshop"),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE,
                "app/cpy/COSTM01.CPY lays out columns by position, and a tab moves them");
        assertSingleViolation(withDescription("Coffee\u0000shop"),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE,
                "a null character truncates a string in any downstream reader");
        assertSingleViolation(withDescription("Coffee\u001bshop"),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE,
                "an escape opens a terminal control sequence in a console log");
    }

    /**
     * Asserts every text component refuses a control character, not the description alone.
     *
     * <p>Five components carry the printable-text guard and report
     * {@value AuthorizationRequest#CONTROL_CHARACTER_MESSAGE}. The type code and the category code
     * carry a numeric class test instead, which admits digits only and so excludes a control
     * character by a narrower rule and its own verbatim text. The transaction identifier carries an
     * exact-width printable pattern, which excludes a control character and a wrong width together
     * and reports {@value AuthorizationRequest#TRANSACTION_ID_WIDTH_MESSAGE}. Each value below stays
     * inside its own component width, so the one violation reported is the character class and not
     * the size.
     */
    @Test
    void everyTextComponentRefusesAControlCharacter() {
        String withReturn = "A\rB";

        assertTrue(messages(withTransactionId(withReturn))
                        .contains(AuthorizationRequest.CONTROL_CHARACTER_MESSAGE),
                "transactionId no longer reports the character class; it reports that text and the "
                        + "refusal of any supplied identifier together");
        assertSingleViolation(withSource(withReturn),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE, "source");
        assertSingleViolation(withDescription(withReturn),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE, "description");
        assertSingleViolation(withMerchantName(withReturn),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE, "merchantName");
        assertSingleViolation(withMerchantCity(withReturn),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE, "merchantCity");
        assertSingleViolation(withMerchantZip(withReturn),
                AuthorizationRequest.CONTROL_CHARACTER_MESSAGE, "merchantZip");

        assertSingleViolation(withTypeCode("0\r"),
                AuthorizationRequest.TYPE_CODE_NOT_NUMERIC_MESSAGE,
                "the numeric class test at app/cbl/COTRN02C.cbl:L323 admits no control character");
        assertSingleViolation(withCategoryCode("00\r1"),
                AuthorizationRequest.CATEGORY_CODE_NOT_NUMERIC_MESSAGE,
                "the numeric class test at app/cbl/COTRN02C.cbl:L329 admits no control character");
    }

    /**
     * Asserts a control character past the component width reports both bounds.
     *
     * <p>{@code DALYTRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA06Y.cpy:L8} holds ten characters,
     * so an eleven-character value carrying a carriage return breaks the width and the character
     * class, and a caller learns both at once.
     */
    @Test
    void aControlCharacterPastTheWidthReportsBothBounds() {
        Set<ConstraintViolation<AuthorizationRequest>> reported =
                violations(withSource("ABCDEFGHI\rK"));

        assertEquals(2, reported.size(),
                () -> "the width and the character class are both reported: " + messagesOf(reported));
        assertTrue(reported.stream().anyMatch(violation ->
                        AuthorizationRequest.CONTROL_CHARACTER_MESSAGE.equals(violation.getMessage())),
                () -> "the character class must be among them: " + messagesOf(reported));
    }

    /** Asserts the printable punctuation the fixtures do hold is accepted. */
    @Test
    void theFixturePunctuationIsAccepted() {
        assertEquals(Set.of(), messages(withDescription("O'Connell's Bar & Grill - 50% off")),
                "app/data/ASCII/dailytran.txt holds the apostrophe, the comma and the hyphen, so "
                        + "the guard must admit printable punctuation");
    }

    /**
     * Asserts the amount accepts every form the currency-tolerant grammar reads.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L456-L457} converts the field with {@code FUNCTION NUMVAL-C},
     * which reads a currency symbol and a thousands separator, so a strict positional shape would
     * refuse values the source converts.
     */
    @Test
    void theAmountAcceptsEveryFormTheTolerantGrammarReads() {
        for (String form : List.of("+00000504.77", "504.77", "$504.77", "$1,234.56", "-00000504.77",
                "1234.56")) {
            assertEquals(Set.of(), messages(withAmount(form)),
                    "the tolerant grammar reads " + form);
        }
    }

    /** Asserts every accepted form of one value carries that one value at scale two. */
    @Test
    void everyAcceptedFormOfOneValueCarriesThatValueAtScaleTwo() {
        BigDecimal expected = new BigDecimal("504.77");

        for (String form : List.of("+00000504.77", "504.77", "$504.77", "$0,000,504.77")) {
            assertEquals(expected, withAmount(form).amountValue(),
                    form + " carries the value the record field stores");
            assertEquals(2, withAmount(form).amountValue().scale(),
                    form + " carries two digits after the decimal point");
        }
    }

    /** Asserts an amount the grammar cannot read reports the source format text. */
    @Test
    void anAmountTheGrammarCannotReadReportsTheFormatText() {
        assertEquals(Set.of(AuthorizationRequest.AMOUNT_FORMAT_MESSAGE),
                messages(withAmount("five hundred")),
                "text the grammar cannot read reports the format the source states");
    }

    /**
     * Asserts an amount wider than the record field reports the format text.
     *
     * <p>{@code DALYTRAN-AMT PIC S9(09)V99} holds nine digits before the decimal point. A wider
     * value would lose its high-order digit on the store, and that loss would be silent.
     */
    @Test
    void anAmountWiderThanTheRecordFieldReportsTheFormatText() {
        assertEquals(Set.of(AuthorizationRequest.AMOUNT_FORMAT_MESSAGE),
                messages(withAmount("1000000000.00")),
                "ten digits before the point do not fit the record field");
        assertEquals(Set.of(), messages(withAmount("999999999.99")),
                "nine digits before the point fit the record field");
    }

    /** Asserts an account identifier that is not all digits reports its own verbatim text. */
    @Test
    void anAccountIdentifierThatIsNotAllDigitsReportsItsOwnText() {
        assertTrue(messages(withIdentifiers("0000000007X", null))
                        .contains(AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE),
                "the account identifier carries digits only");
        assertEquals(Set.of(AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE),
                messages(withIdentifiers("0000000007X", CARD_NUMBER)),
                "the account shape is reported before the domain cross-check runs");
    }

    /**
     * Asserts a short identifier is refused rather than widened.
     *
     * <p>{@code app/bms/COTRN02.bms:L85-L90} declares the account field at {@code LENGTH=11} and
     * {@code :L104-L108} declares the card field at {@code LENGTH=16}, neither with {@code JUSTIFY}
     * nor {@code PICIN}. Only {@code app/bms/COMEN01.bms:L147} and {@code app/bms/COADM01.bms:L147}
     * carry {@code JUSTIFY=(RIGHT,ZERO)} anywhere in {@code app/bms/}, so a short entry arrives
     * left-aligned with trailing spaces and fails the numeric class test at
     * {@code app/cbl/COTRN02C.cbl:L197} and {@code :L211}.
     *
     * <p>Widening would authorize a different identifier. Zero-padding {@code 4111} produces
     * {@code 0000000000004111}, a valid sixteen-digit key belonging to whichever cardholder holds it.
     */
    @Test
    void aShortIdentifierIsRefusedRatherThanWidened() {
        assertNull(withIdentifiers("7", null).canonicalAccountId(),
                "one digit is not the eleven-digit identifier the record field holds");
        assertNull(withIdentifiers(null, "4111").canonicalCardNumber(),
                "four digits are not the sixteen-digit card number the key holds");
        assertEquals(Set.of(AuthorizationRequest.ACCOUNT_ID_NOT_NUMERIC_MESSAGE,
                        AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE),
                messages(withIdentifiers("7", null)),
                "the short account reports its shape and that no usable identifier remains");
        assertEquals(Set.of(AuthorizationRequest.CARD_NUMBER_NOT_NUMERIC_MESSAGE),
                messages(withIdentifiers(null, "4111")),
                "the present but short card reports its shape");
    }

    /**
     * Returns every violation message one request reports.
     *
     * @param request the payload to validate
     * @return the messages, deduplicated and ordered so a comparison reads the same twice
     */
    private static Set<String> messages(AuthorizationRequest request) {
        Set<String> reported = new TreeSet<>();
        for (ConstraintViolation<AuthorizationRequest> violation : validator.validate(request)) {
            reported.add(violation.getMessage());
        }
        return new LinkedHashSet<>(reported);
    }

    /**
     * Returns every violation one request reports, undeduplicated.
     *
     * @param request the payload to validate
     * @return the violations
     */
    private static Set<ConstraintViolation<AuthorizationRequest>> violations(
            AuthorizationRequest request) {
        return validator.validate(request);
    }

    /**
     * Joins the texts of a violation set, for a failure message.
     *
     * @param reported the violations reported
     * @return the texts, comma separated, or a note that the set is empty
     */
    private static String messagesOf(Set<ConstraintViolation<AuthorizationRequest>> reported) {
        if (reported.isEmpty()) {
            return "no violation";
        }
        Set<String> texts = new TreeSet<>();
        for (ConstraintViolation<AuthorizationRequest> violation : reported) {
            texts.add(violation.getPropertyPath() + " " + violation.getMessage());
        }
        return String.join(", ", texts);
    }

    /**
     * Asserts a request reports exactly one violation carrying one text.
     *
     * @param request the request to validate
     * @param message the text the one violation carries
     * @param reason  what the violation guards
     */
    private static void assertSingleViolation(AuthorizationRequest request, String message,
            String reason) {
        Set<ConstraintViolation<AuthorizationRequest>> reported = violations(request);

        assertEquals(1, reported.size(),
                () -> reason + ". Reported: " + messagesOf(reported));
        assertEquals(message, reported.iterator().next().getMessage(), reason);
    }

    private static AuthorizationRequest withTransactionId(String value) {
        return new AuthorizationRequest(value, VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(),
                VALID.processingTimestamp(), VALID.accountId());
    }

    private static AuthorizationRequest withTypeCode(String value) {
        return new AuthorizationRequest(VALID.transactionId(), value,
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(),
                VALID.processingTimestamp(), VALID.accountId());
    }

    private static AuthorizationRequest withCategoryCode(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(), value,
                VALID.source(), VALID.description(), VALID.amount(), VALID.merchantId(),
                VALID.merchantName(), VALID.merchantCity(), VALID.merchantZip(),
                VALID.cardNumber(), VALID.originTimestamp(), VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withSource(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), value, VALID.description(), VALID.amount(),
                VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(), VALID.merchantZip(),
                VALID.cardNumber(), VALID.originTimestamp(), VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withDescription(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), value, VALID.amount(),
                VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(), VALID.merchantZip(),
                VALID.cardNumber(), VALID.originTimestamp(), VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withAmount(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(), value,
                VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(), VALID.merchantZip(),
                VALID.cardNumber(), VALID.originTimestamp(), VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withMerchantId(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), value, VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(),
                VALID.processingTimestamp(), VALID.accountId());
    }

    private static AuthorizationRequest withMerchantName(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), value, VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(),
                VALID.processingTimestamp(), VALID.accountId());
    }

    private static AuthorizationRequest withMerchantCity(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), value,
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(),
                VALID.processingTimestamp(), VALID.accountId());
    }

    private static AuthorizationRequest withMerchantZip(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                value, VALID.cardNumber(), VALID.originTimestamp(), VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withOriginTimestamp(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), value, VALID.processingTimestamp(),
                VALID.accountId());
    }

    private static AuthorizationRequest withProcessingTimestamp(String value) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), VALID.cardNumber(), VALID.originTimestamp(), value,
                VALID.accountId());
    }

    private static AuthorizationRequest withIdentifiers(String accountId, String cardNumber) {
        return new AuthorizationRequest(VALID.transactionId(), VALID.transactionTypeCode(),
                VALID.transactionCategoryCode(), VALID.source(), VALID.description(),
                VALID.amount(), VALID.merchantId(), VALID.merchantName(), VALID.merchantCity(),
                VALID.merchantZip(), cardNumber, VALID.originTimestamp(),
                VALID.processingTimestamp(), accountId);
    }
}
