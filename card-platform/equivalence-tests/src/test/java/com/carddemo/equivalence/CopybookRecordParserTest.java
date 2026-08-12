package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CopybookRecordParser}, built from records this class assembles field by field.
 *
 * <p>No test here reads a fixture. Each raw record is built from independently chosen field values
 * at the widths the copybooks declare. A wrong offset inside the parser therefore moves a known
 * value into the wrong component and fails.</p>
 *
 * <p>The layouts covered are {@code CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:L4-L11},
 * {@code CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4-L8} and {@code CUSTOMER-RECORD} at
 * {@code app/cpy/CVCUS01Y.cpy:L4-L23}, together with the four field readers and the overpunch
 * decode every layout depends on.</p>
 *
 * <p>Every field value below is chosen so that no two fields of one record share a value. A read
 * at the right width and the wrong offset therefore returns a neighbour's value, and the assertion
 * naming the field it belongs to fails.</p>
 */
class CopybookRecordParserTest {

    // Widths, typed here from the copybooks rather than read from PicClause, so a drift in either
    // one fails.

    /** {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}. */
    private static final int CARD_NUM_WIDTH = 16;

    /** {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}. */
    private static final int CARD_ACCT_ID_WIDTH = 11;

    /** {@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7}. */
    private static final int CARD_CVV_WIDTH = 3;

    /** {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}. */
    private static final int CARD_EMBOSSED_NAME_WIDTH = 50;

    /** {@code CARD-EXPIRAION-DATE PIC X(10)} at {@code app/cpy/CVACT02Y.cpy:L9}. */
    private static final int CARD_EXPIRATION_DATE_WIDTH = 10;

    /** {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10}. */
    private static final int CARD_ACTIVE_STATUS_WIDTH = 1;

    /** {@code FILLER PIC X(59)} at {@code app/cpy/CVACT02Y.cpy:L11}, which no component carries. */
    private static final int CARD_FILLER_WIDTH = 59;

    /** Declared length of {@code CARD-RECORD}, the sum of its seven field widths. */
    private static final int CARD_RECORD_LENGTH = 150;

    /** {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:L5}. */
    private static final int XREF_CARD_NUM_WIDTH = 16;

    /** {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:L6}. */
    private static final int XREF_CUST_ID_WIDTH = 9;

    /** {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. */
    private static final int XREF_ACCT_ID_WIDTH = 11;

    /** {@code FILLER PIC X(14)} at {@code app/cpy/CVACT03Y.cpy:L8}, absent from the text fixture. */
    private static final int XREF_FILLER_WIDTH = 14;

    /** Width the text fixture delivers, which stops on the last byte of {@code XREF-ACCT-ID}. */
    private static final int XREF_DELIVERED_WIDTH = 36;

    /** Declared length of {@code CARD-XREF-RECORD} per {@code app/jcl/XREFFILE.jcl:L44}. */
    private static final int XREF_RECORD_LENGTH = 50;

    // Field values for one assembled CARD-RECORD. No two share a value.

    /** {@code CARD-NUM} of the assembled card record, sixteen digits. */
    private static final String CARD_NUMBER = "4111222233334444";

    /** {@code CARD-ACCT-ID} of the assembled card record, eleven digits. */
    private static final String CARD_ACCOUNT_ID = "00000000077";

    /** {@code CARD-CVV-CD} of the assembled card record, three digits. */
    private static final String CARD_VERIFICATION_VALUE = "915";

    /** {@code CARD-EMBOSSED-NAME} of the assembled card record, before its space padding. */
    private static final String CARD_EMBOSSED_NAME = "PARSER FIXTURE NAME";

    /** {@code CARD-EXPIRAION-DATE} of the assembled card record, ten characters. */
    private static final String CARD_EXPIRATION_DATE = "2031-08-31";

    /** {@code CARD-ACTIVE-STATUS} of the assembled card record, one character. */
    private static final String CARD_ACTIVE_STATUS = "N";

    // Field values for one assembled CARD-XREF-RECORD. No two share a value, and none matches a
    // value of the assembled card record.

    /** {@code XREF-CARD-NUM} of the assembled cross-reference record, sixteen digits. */
    private static final String XREF_CARD_NUMBER = "5555666677778888";

    /** {@code XREF-CUST-ID} of the assembled cross-reference record, nine digits. */
    private static final String XREF_CUSTOMER_ID = "000000123";

    /** {@code XREF-ACCT-ID} of the assembled cross-reference record, eleven digits. */
    private static final String XREF_ACCOUNT_ID = "00000000456";

    /** Number of characters a masked card number keeps in the clear. */
    private static final int VISIBLE_CARD_NUMBER_DIGITS = 4;

    /** Character every redaction uses. */
    private static final char MASK_CHARACTER = '*';

    // Offsets. Every offset a parse method reaches is the running sum of the widths above it.

    @Test
    void theCardRecordOffsetsAreTheRunningSumsOfItsWidths() {
        List<Integer> widths = List.of(CARD_NUM_WIDTH, CARD_ACCT_ID_WIDTH, CARD_CVV_WIDTH,
                CARD_EMBOSSED_NAME_WIDTH, CARD_EXPIRATION_DATE_WIDTH, CARD_ACTIVE_STATUS_WIDTH,
                CARD_FILLER_WIDTH);
        List<Integer> expectedOffsets = List.of(0, 16, 27, 30, 80, 90, 91);

        assertEquals(CARD_RECORD_LENGTH, widths.stream().mapToInt(Integer::intValue).sum(),
                "app/cpy/CVACT02Y.cpy:L4-L11 declares seven fields summing to "
                        + CARD_RECORD_LENGTH + " bytes");
        assertEquals(expectedOffsets, runningSums(widths),
                "each field of app/cpy/CVACT02Y.cpy:L4-L11 starts where the fields above it end");
    }

    @Test
    void theCrossReferenceOffsetsAreTheRunningSumsOfItsWidths() {
        List<Integer> widths = List.of(XREF_CARD_NUM_WIDTH, XREF_CUST_ID_WIDTH,
                XREF_ACCT_ID_WIDTH, XREF_FILLER_WIDTH);
        List<Integer> expectedOffsets = List.of(0, 16, 25, 36);

        assertEquals(XREF_RECORD_LENGTH, widths.stream().mapToInt(Integer::intValue).sum(),
                "app/cpy/CVACT03Y.cpy:L4-L8 declares four fields summing to " + XREF_RECORD_LENGTH
                        + " bytes, the width app/jcl/XREFFILE.jcl:L44 declares");
        assertEquals(expectedOffsets, runningSums(widths),
                "each field of app/cpy/CVACT03Y.cpy:L4-L8 starts where the fields above it end");
        assertEquals(XREF_DELIVERED_WIDTH,
                XREF_CARD_NUM_WIDTH + XREF_CUST_ID_WIDTH + XREF_ACCT_ID_WIDTH,
                "the three modelled fields close on byte " + XREF_DELIVERED_WIDTH
                        + ", the width app/data/ASCII/cardxref.txt delivers");
        assertEquals(XREF_RECORD_LENGTH - XREF_DELIVERED_WIDTH, XREF_FILLER_WIDTH,
                "the app/cpy/CVACT03Y.cpy:L8 filler accounts for every byte the fixture omits");
    }

    // Whole-record parses against records this class assembles.

    @Test
    void everyCardComponentComesFromItsOwnField() {
        String record = assembledCardRecord();
        assertEquals(CARD_RECORD_LENGTH, record.length(),
                "the assembled record holds the " + CARD_RECORD_LENGTH
                        + " bytes app/cpy/CVACT02Y.cpy:L4-L11 declares");

        CopybookRecordParser.CardRecord parsed = CopybookRecordParser.parseCard(record);

        assertRedactedEquals(CARD_NUMBER, parsed.cardNumber(),
                "cardNumber, from CARD-NUM at offset 0 of width " + CARD_NUM_WIDTH,
                CopybookRecordParserTest::redactedCardNumber);
        assertEquals(CARD_ACCOUNT_ID, parsed.accountId(),
                "accountId comes from CARD-ACCT-ID at offset 16 of width " + CARD_ACCT_ID_WIDTH);
        assertRedactedEquals(CARD_VERIFICATION_VALUE, parsed.cardVerificationValue(),
                "cardVerificationValue, from CARD-CVV-CD at offset 27 of width " + CARD_CVV_WIDTH,
                CopybookRecordParserTest::redactedVerificationValue);
        assertEquals(CARD_EMBOSSED_NAME, parsed.embossedName(),
                "embossedName comes from CARD-EMBOSSED-NAME at offset 30, with its trailing "
                        + "spaces removed");
        assertEquals(CARD_EXPIRATION_DATE, parsed.expirationDate(),
                "expirationDate comes from CARD-EXPIRAION-DATE at offset 80 of width "
                        + CARD_EXPIRATION_DATE_WIDTH);
        assertEquals(CARD_ACTIVE_STATUS, parsed.activeStatus(),
                "activeStatus comes from CARD-ACTIVE-STATUS at offset 90 of width "
                        + CARD_ACTIVE_STATUS_WIDTH);
    }

    /**
     * Asserts that a failing card-number or verification-value comparison publishes neither value.
     *
     * <p>This is the assertion the redaction exists for, so it is tested rather than assumed. The
     * comparison is driven to fail on purpose, its message is captured, and the message is required
     * to carry no whole value, no sixteen-digit run and no three-digit verification value — while
     * still carrying the field name and the widths a reader needs to place the fault.</p>
     */
    @Test
    void aFailedCardComparisonPublishesNeitherValue() {
        AssertionError cardNumberFailure = assertThrows(AssertionError.class,
                () -> assertRedactedEquals(CARD_NUMBER, XREF_CARD_NUMBER, "CARD-NUM",
                        CopybookRecordParserTest::redactedCardNumber));
        AssertionError verificationFailure = assertThrows(AssertionError.class,
                () -> assertRedactedEquals(CARD_VERIFICATION_VALUE, "246", "CARD-CVV-CD",
                        CopybookRecordParserTest::redactedVerificationValue));

        for (AssertionError failure : List.of(cardNumberFailure, verificationFailure)) {
            String message = failure.getMessage();
            assertFalse(message.contains(CARD_NUMBER),
                    "the expected card number reached the message, which is published");
            assertFalse(message.contains(XREF_CARD_NUMBER),
                    "the read card number reached the message");
            assertFalse(message.contains(CARD_VERIFICATION_VALUE),
                    "the expected verification value reached the message");
            assertFalse(message.contains("246"), "the read verification value reached the message");
            assertFalse(message.matches("(?s).*[0-9]{5,}.*"),
                    "a run of five or more digits reached the message, and the masked form keeps "
                            + "four");
        }
        assertTrue(cardNumberFailure.getMessage().contains("CARD-NUM")
                        && cardNumberFailure.getMessage().contains(maskOf(XREF_CARD_NUMBER)),
                "a redacted failure still has to name the field and the last four characters read, "
                        + "or it diagnoses nothing");
        assertTrue(verificationFailure.getMessage()
                        .contains(String.valueOf(MASK_CHARACTER).repeat(CARD_CVV_WIDTH)),
                "and a verification value is reported fully masked");
    }

    /**
     * Asserts that the account identifier of {@code CARD-RECORD} keeps its leading zeros. The field
     * is {@code PIC 9(11)} and the parser reads it as fixed text, so the zeros survive.
     */
    @Test
    void theCardAccountIdentifierKeepsItsLeadingZeros() {
        CopybookRecordParser.CardRecord parsed =
                CopybookRecordParser.parseCard(assembledCardRecord());

        assertEquals(CARD_ACCT_ID_WIDTH, parsed.accountId().length(),
                "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6 reads as " + CARD_ACCT_ID_WIDTH
                        + " characters, so its leading zeros survive");
        assertTrue(parsed.accountId().startsWith("0"),
                "the assembled account identifier starts with a zero, and the parser keeps it");
        assertEquals(CARD_CVV_WIDTH, parsed.cardVerificationValue().length(),
                "CARD-CVV-CD PIC 9(03) at app/cpy/CVACT02Y.cpy:L7 reads as " + CARD_CVV_WIDTH
                        + " characters");
    }

    @Test
    void everyCrossReferenceComponentComesFromItsOwnField() {
        String delivered = assembledCrossReferenceRecord(XREF_DELIVERED_WIDTH);
        String declared = assembledCrossReferenceRecord(XREF_RECORD_LENGTH);

        assertEquals(XREF_DELIVERED_WIDTH, delivered.length(),
                "the delivered form holds the " + XREF_DELIVERED_WIDTH
                        + " bytes app/data/ASCII/cardxref.txt carries");
        assertEquals(XREF_RECORD_LENGTH, declared.length(),
                "the declared form holds the " + XREF_RECORD_LENGTH
                        + " bytes app/jcl/XREFFILE.jcl:L44 declares");

        for (String record : List.of(delivered, declared)) {
            CopybookRecordParser.CardCrossReferenceRecord parsed =
                    CopybookRecordParser.parseCardCrossReference(record);
            String form = record.length() == XREF_DELIVERED_WIDTH ? "delivered" : "declared";

            assertRedactedEquals(XREF_CARD_NUMBER, parsed.cardNumber(),
                    "cardNumber, from XREF-CARD-NUM at offset 0 in the " + form + " form",
                    CopybookRecordParserTest::redactedCardNumber);
            assertEquals(XREF_CUSTOMER_ID, parsed.customerId(),
                    "customerId comes from XREF-CUST-ID at offset 16 in the " + form + " form");
            assertEquals(XREF_ACCOUNT_ID, parsed.accountId(),
                    "accountId comes from XREF-ACCT-ID at offset 25 in the " + form + " form");
        }
    }

    /**
     * Asserts that a record one byte short of the last modelled field is refused, and that the
     * failure names the layout, the length supplied and both widths the layout accepts. This is the
     * boundary of the width tolerance the loader relies on.
     *
     * <p>The record width is checked before the first field is read, and only the two measured
     * widths pass: the 36 bytes {@code app/data/ASCII/cardxref.txt} delivers and the 50 bytes
     * {@code app/jcl/XREFFILE.jcl:L44} declares. No field read can therefore run past the end of a
     * record this method accepts, which is what
     * {@link #aFieldPastTheEndOfTheRecordIsRefused} covers for the cursor itself.</p>
     */
    @Test
    void aRecordEndingInsideTheLastModelledFieldIsRefused() {
        String tooShort = assembledCrossReferenceRecord(XREF_DELIVERED_WIDTH)
                .substring(0, XREF_DELIVERED_WIDTH - 1);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.parseCardCrossReference(tooShort),
                "a record ending inside XREF-ACCT-ID cannot be read");
        assertTrue(refusal.getMessage().contains("CVACT03Y CARD-XREF-RECORD"),
                "the refusal names the layout whose width the record misses");
        assertTrue(refusal.getMessage().contains(String.valueOf(XREF_DELIVERED_WIDTH - 1)),
                "the refusal names the length of the record supplied");
        assertTrue(refusal.getMessage().contains(String.valueOf(XREF_DELIVERED_WIDTH))
                        && refusal.getMessage().contains(String.valueOf(XREF_RECORD_LENGTH)),
                "the refusal names both widths the layout accepts");
    }

    @Test
    void everyCustomerComponentComesFromItsOwnField() {
        Map<String, Integer> layout = customerLayout();
        Map<String, String> values = customerValues();
        String record = assembledFixedWidthRecord(layout, values);

        assertEquals(500, record.length(),
                "app/cpy/CVCUS01Y.cpy:L4-L23 declares a 500-byte record");

        CopybookRecordParser.CustomerRecord parsed = CopybookRecordParser.parseCustomer(record);

        assertEquals(values.get("CUST-ID"), parsed.customerId(), "customerId comes from CUST-ID");
        assertEquals(values.get("CUST-FIRST-NAME").strip(), parsed.firstName(),
                "firstName comes from CUST-FIRST-NAME");
        assertEquals(values.get("CUST-MIDDLE-NAME").strip(), parsed.middleName(),
                "middleName comes from CUST-MIDDLE-NAME");
        assertEquals(values.get("CUST-LAST-NAME").strip(), parsed.lastName(),
                "lastName comes from CUST-LAST-NAME");
        assertEquals(values.get("CUST-ADDR-LINE-1").strip(), parsed.addressLine1(),
                "addressLine1 comes from CUST-ADDR-LINE-1");
        assertEquals(values.get("CUST-ADDR-LINE-2").strip(), parsed.addressLine2(),
                "addressLine2 comes from CUST-ADDR-LINE-2");
        assertEquals(values.get("CUST-ADDR-LINE-3").strip(), parsed.addressLine3(),
                "addressLine3 comes from CUST-ADDR-LINE-3");
        assertEquals(values.get("CUST-ADDR-STATE-CD"), parsed.stateCode(),
                "stateCode comes from CUST-ADDR-STATE-CD");
        assertEquals(values.get("CUST-ADDR-COUNTRY-CD"), parsed.countryCode(),
                "countryCode comes from CUST-ADDR-COUNTRY-CD");
        assertEquals(values.get("CUST-ADDR-ZIP").strip(), parsed.addressZip(),
                "addressZip comes from CUST-ADDR-ZIP");
        assertEquals(values.get("CUST-PHONE-NUM-1").strip(), parsed.phoneNumber1(),
                "phoneNumber1 comes from CUST-PHONE-NUM-1");
        assertEquals(values.get("CUST-PHONE-NUM-2").strip(), parsed.phoneNumber2(),
                "phoneNumber2 comes from CUST-PHONE-NUM-2");
        assertEquals(values.get("CUST-SSN"), parsed.socialSecurityNumber(),
                "socialSecurityNumber comes from CUST-SSN");
        assertEquals(values.get("CUST-GOVT-ISSUED-ID").strip(), parsed.governmentIssuedId(),
                "governmentIssuedId comes from CUST-GOVT-ISSUED-ID");
        assertEquals(values.get("CUST-DOB-YYYY-MM-DD"), parsed.dateOfBirth(),
                "dateOfBirth comes from CUST-DOB-YYYY-MM-DD");
        assertEquals(values.get("CUST-EFT-ACCOUNT-ID").strip(), parsed.eftAccountId(),
                "eftAccountId comes from CUST-EFT-ACCOUNT-ID");
        assertEquals(values.get("CUST-PRI-CARD-HOLDER-IND"), parsed.primaryCardHolderIndicator(),
                "primaryCardHolderIndicator comes from CUST-PRI-CARD-HOLDER-IND");
        assertEquals(Integer.parseInt(values.get("CUST-FICO-CREDIT-SCORE")),
                parsed.ficoCreditScore(),
                "ficoCreditScore comes from CUST-FICO-CREDIT-SCORE, read as a number");
    }

    @Test
    void fixedTextKeepsPaddingAndTextRemovesTrailingPadding() {
        String record = "AB   CD   ";

        assertEquals("AB   ", CopybookRecordParser.fixedText(record, 0, 5, "FIRST"),
                "fixedText keeps the trailing spaces of its field");
        assertEquals("AB", CopybookRecordParser.text(record, 0, 5, "FIRST"),
                "text removes the trailing spaces of its field");
        assertEquals("CD   ", CopybookRecordParser.fixedText(record, 5, 5, "SECOND"),
                "fixedText reads the second field at offset 5");
        assertEquals("CD", CopybookRecordParser.text(record, 5, 5, "SECOND"),
                "text reads the second field at offset 5");
        assertEquals("  A", CopybookRecordParser.text("  A  ", 0, 5, "LEADING"),
                "text keeps the leading spaces of its field");
        assertEquals("", CopybookRecordParser.text("     ", 0, 5, "ALL-SPACES"),
                "a field of only spaces reads as the empty string");
    }

    @Test
    void aFieldPastTheEndOfTheRecordIsRefused() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.fixedText("ABCDE", 3, 5, "OVERRUN"),
                "a field needing 5 bytes at offset 3 of a 5-byte record cannot be read");
        String message = refusal.getMessage();

        assertTrue(message.contains("OVERRUN"), "the refusal names the field");
        assertTrue(message.contains("5"), "the refusal names the width and the record length");
        assertTrue(message.contains("3"), "the refusal names the offset");
        assertFalse(message.contains("ABCDE"), "the refusal carries no text of the record");
    }

    @Test
    void aNegativeOffsetAndANegativeWidthAreRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.fixedText("ABCDE", -1, 2, "NEGATIVE-OFFSET"),
                "a negative offset cannot be read").getMessage().contains("NEGATIVE-OFFSET"),
                "the refusal names the field whose offset is negative");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.fixedText("ABCDE", 0, -2, "NEGATIVE-WIDTH"),
                "a negative width cannot be read").getMessage().contains("NEGATIVE-WIDTH"),
                "the refusal names the field whose width is negative");
    }

    /**
     * Asserts that {@code unsignedInteger} reads a {@code PIC 9(n)} field and drops its leading
     * zeros. A character other than a digit is refused, and the refusal names the position and the
     * width and no field text.
     */
    @Test
    void unsignedIntegerDropsLeadingZerosAndRefusesANonDigit() {
        assertEquals(7, CopybookRecordParser.unsignedInteger("007", 0, 3, "SCORE"),
                "a PIC 9(03) field of 007 reads as 7");
        assertEquals(0, CopybookRecordParser.unsignedInteger("000", 0, 3, "SCORE"),
                "a PIC 9(03) field of zeros reads as 0");
        assertEquals(850, CopybookRecordParser.unsignedInteger("850", 0, 3, "SCORE"),
                "a PIC 9(03) field of 850 reads as 850");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.unsignedInteger("8X0", 0, 3, "SCORE"),
                "a PIC 9(03) field holding a letter cannot be read as a number");
        assertTrue(refusal.getMessage().contains("SCORE"), "the refusal names the field");
        assertTrue(refusal.getMessage().contains("1"),
                "the refusal names the field position of the character");
        assertFalse(refusal.getMessage().contains("8X0"),
                "the refusal carries no text of the field");
        assertFalse(refusal.getMessage().contains("'X'"),
                "the refusal carries no character of the field");
    }

    @Test
    void allTwentyOverpunchCharactersDecodeToTheirDigitAndSign() {
        String positive = "{ABCDEFGHI";
        String negative = "}JKLMNOPQR";

        assertEquals(positive, CopybookRecordParser.POSITIVE_SIGN_OVERPUNCH_DIGITS,
                "the positive overpunch list holds the ten characters for digits zero to nine");
        assertEquals(negative, CopybookRecordParser.NEGATIVE_SIGN_OVERPUNCH_DIGITS,
                "the negative overpunch list holds the ten characters for digits zero to nine");
        assertEquals(positive.length(), negative.length(),
                "the two overpunch lists hold the same number of characters");

        for (int digit = 0; digit < positive.length(); digit++) {
            BigDecimal value = CopybookRecordParser.signedDecimal(
                    "12" + positive.charAt(digit), 0, 3, 1, "AMOUNT");
            assertEquals(new BigDecimal("12." + (char) ('0' + digit)), value,
                    "the positive overpunch for digit " + digit
                            + " reads as that digit with a positive sign");
        }
        for (int digit = 0; digit < negative.length(); digit++) {
            BigDecimal value = CopybookRecordParser.signedDecimal(
                    "12" + negative.charAt(digit), 0, 3, 1, "AMOUNT");
            assertEquals(new BigDecimal("-12." + (char) ('0' + digit)), value,
                    "the negative overpunch for digit " + digit
                            + " reads as that digit with a negative sign");
        }
    }

    @Test
    void aPlainTrailingDigitReadsAsPositiveAtTheScaleRequested() {
        assertEquals(new BigDecimal("123.45"),
                CopybookRecordParser.signedDecimal("12345", 0, 5, 2, "AMOUNT"),
                "a plain trailing digit reads as a positive value at scale 2");
        assertEquals(2, CopybookRecordParser.signedDecimal("12345", 0, 5, 2, "AMOUNT").scale(),
                "the result carries exactly the scale requested");
        assertEquals(new BigDecimal("-123.45"),
                CopybookRecordParser.signedDecimal("1234N", 0, 5, 2, "AMOUNT"),
                "the negative overpunch for digit five reads as a negative value");
        assertEquals(new BigDecimal("0.00"),
                CopybookRecordParser.signedDecimal("00000", 0, 5, 2, "AMOUNT"),
                "a field of zeros reads as zero at the scale requested");
        assertEquals(new BigDecimal("12345"),
                CopybookRecordParser.signedDecimal("12345", 0, 5, 0, "COUNT"),
                "a scale of zero leaves the value whole");
    }

    @Test
    void anUnreadableSignAndANonDigitAreRefusedWithoutEchoingTheField() {
        IllegalArgumentException sign = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.signedDecimal("1234Z", 0, 5, 2, "AMOUNT"),
                "a trailing character outside both overpunch lists cannot be read");
        assertTrue(sign.getMessage().contains("AMOUNT"), "the refusal names the field");
        assertTrue(sign.getMessage().contains("4"),
                "the refusal names the field position of the trailing character");
        assertFalse(sign.getMessage().contains("1234Z"),
                "the refusal carries no text of the field");
        assertFalse(sign.getMessage().contains("'Z'"),
                "the refusal carries no character of the field");

        IllegalArgumentException leading = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.signedDecimal("12X45", 0, 5, 2, "AMOUNT"),
                "a leading character that is not a digit cannot be read");
        assertTrue(leading.getMessage().contains("AMOUNT"), "the refusal names the field");
        assertTrue(leading.getMessage().contains("2"),
                "the refusal names the field position of the character");
        assertFalse(leading.getMessage().contains("12X45"),
                "the refusal carries no text of the field");
    }

    // Redaction of rendered records.

    /**
     * Asserts that the rendered {@code CardRecord} carries no full card number, no verification
     * value, no embossed name and no expiry date, and that it publishes the masked card number and
     * the two operational fields instead.
     *
     * <p>The embossed name and the expiry date joined the redacted set because the two of them
     * beside four real digits of the number are what a card-not-present authorization asks for.
     * Before that, this test asserted the embossed name was still rendered, which is the contract
     * the finding names rather than the one the code should hold.
     */
    @Test
    void theRenderedCardRecordCarriesNoFullCardNumberAndNoCardholderValue() {
        String rendered = CopybookRecordParser.parseCard(assembledCardRecord()).toString();

        assertFalse(rendered.contains(CARD_NUMBER),
                "the rendered card record holds the full card number of the assembled record");
        assertFalse(rendered.contains(CARD_VERIFICATION_VALUE),
                "the rendered card record holds the verification value of the assembled record");
        assertFalse(rendered.contains(CARD_EMBOSSED_NAME),
                "the rendered card record holds the embossed name of the assembled record");
        assertFalse(rendered.contains(CARD_EXPIRATION_DATE),
                "the rendered card record holds the expiry date of the assembled record");

        assertTrue(rendered.contains(maskOf(CARD_NUMBER)),
                "the rendered card record publishes the masked card number instead");
        assertTrue(rendered.contains(String.valueOf(MASK_CHARACTER).repeat(CARD_CVV_WIDTH)),
                "the verification value renders as " + CARD_CVV_WIDTH + " mask characters");
        assertTrue(
                rendered.contains(
                        String.valueOf(MASK_CHARACTER).repeat(CARD_EMBOSSED_NAME.length())),
                "the embossed name renders as " + CARD_EMBOSSED_NAME.length()
                        + " mask characters, the width the parser delivered after stripping the"
                        + " padding of the " + CARD_EMBOSSED_NAME_WIDTH + " character field");
        assertTrue(
                rendered.contains(
                        String.valueOf(MASK_CHARACTER).repeat(CARD_EXPIRATION_DATE_WIDTH)),
                "the expiry date renders as " + CARD_EXPIRATION_DATE_WIDTH + " mask characters");

        assertTrue(rendered.contains("accountId=" + CARD_ACCOUNT_ID),
                "the rendered card record keeps the account identifier, so a diverging row is"
                        + " nameable");
        assertTrue(rendered.contains("activeStatus=" + CARD_ACTIVE_STATUS),
                "the rendered card record keeps the status flag, which identifies nobody");
    }

    @Test
    void theRenderedCrossReferenceCarriesNoFullCardNumber() {
        String rendered = CopybookRecordParser
                .parseCardCrossReference(assembledCrossReferenceRecord(XREF_DELIVERED_WIDTH))
                .toString();

        assertFalse(rendered.contains(XREF_CARD_NUMBER),
                "the rendered cross-reference holds the full card number of the assembled record");
        assertTrue(rendered.contains(maskOf(XREF_CARD_NUMBER)),
                "the rendered cross-reference publishes the masked card number instead");
        assertTrue(rendered.contains(XREF_CUSTOMER_ID),
                "the rendered cross-reference keeps the customer identifier");
        assertTrue(rendered.contains(XREF_ACCOUNT_ID),
                "the rendered cross-reference keeps the account identifier");
    }

    /**
     * Asserts that the rendered {@code CustomerRecord} carries none of its fourteen identifying
     * values, and that each renders as mask characters at the width of the value it replaced.
     *
     * <p>Three of the fourteen were redacted before: the Social Security Number, the government
     * identifier and the bank account identifier. The other eleven are the ones the finding names.
     * A name, a home address, a postal code, two telephone numbers, a date of birth and a credit
     * score identify a person as surely as the first three do, and this test asserted the date of
     * birth was still rendered.
     */
    @Test
    void theRenderedCustomerRecordCarriesNoneOfItsFourteenIdentifyingValues() {
        Map<String, String> values = customerValues();
        String rendered = CopybookRecordParser
                .parseCustomer(assembledFixedWidthRecord(customerLayout(), values)).toString();

        Map<String, String> redacted = new LinkedHashMap<>();
        for (String field : new String[] {"CUST-FIRST-NAME", "CUST-MIDDLE-NAME", "CUST-LAST-NAME",
                "CUST-ADDR-LINE-1", "CUST-ADDR-LINE-2", "CUST-ADDR-LINE-3", "CUST-ADDR-ZIP",
                "CUST-PHONE-NUM-1", "CUST-PHONE-NUM-2", "CUST-SSN", "CUST-GOVT-ISSUED-ID",
                "CUST-DOB-YYYY-MM-DD", "CUST-EFT-ACCOUNT-ID", "CUST-FICO-CREDIT-SCORE"}) {
            redacted.put(field, values.get(field).strip());
        }
        assertEquals(14, redacted.size(), "every identifying component of the record is listed");

        for (Map.Entry<String, String> field : redacted.entrySet()) {
            assertFalse(rendered.contains(field.getValue()),
                    "the rendered customer record holds the value of " + field.getKey());
            assertTrue(
                    rendered.contains(
                            String.valueOf(MASK_CHARACTER).repeat(field.getValue().length())),
                    field.getKey() + " renders as " + field.getValue().length()
                            + " mask characters");
        }

        assertTrue(rendered.contains("customerId=" + values.get("CUST-ID")),
                "the rendered customer record keeps the customer identifier, so a diverging row is"
                        + " nameable");
        assertTrue(rendered.contains("stateCode=" + values.get("CUST-ADDR-STATE-CD")),
                "the rendered customer record keeps the state code, which narrows a record to a"
                        + " state and no further");
        assertTrue(rendered.contains("countryCode=" + values.get("CUST-ADDR-COUNTRY-CD")),
                "the rendered customer record keeps the country code");
        assertTrue(
                rendered.contains(
                        "primaryCardHolderIndicator=" + values.get("CUST-PRI-CARD-HOLDER-IND")),
                "the rendered customer record keeps the cardholder indicator, a single flag");
    }

    /**
     * Asserts that the rendered posted and daily transaction records carry no spending detail, and
     * that the two renderings agree component for component.
     *
     * <p>The two layouts are the same layout at the same copybook lines, so a redaction applied to
     * one and missed on the other would be a hole with no visible cause. The assertion that the
     * two renderings differ only in the class name is what closes it.
     */
    @Test
    void theRenderedTransactionRecordsCarryNoSpendingDetail() {
        String description = "PARSER FIXTURE DESCRIPTION";
        BigDecimal amount = new BigDecimal("-1234.56");
        String merchantName = "PARSER FIXTURE MERCHANT";
        String merchantCity = "PARSER FIXTURE CITY";
        String merchantZip = "820019999";
        String merchantId = "000000000123456";

        CopybookRecordParser.PostedTransactionRecord posted =
                new CopybookRecordParser.PostedTransactionRecord("TRAN000000000001", "01", "0001",
                        "POS", description, amount, merchantId, merchantName, merchantCity,
                        merchantZip, CARD_NUMBER, "2031-08-31 10:11:12.13", "2031-09-01 00:00:00");
        CopybookRecordParser.DailyTransactionRecord daily =
                new CopybookRecordParser.DailyTransactionRecord("TRAN000000000001", "01", "0001",
                        "POS", description, amount, merchantId, merchantName, merchantCity,
                        merchantZip, CARD_NUMBER, "2031-08-31 10:11:12.13", "2031-09-01 00:00:00");

        for (String rendered : List.of(posted.toString(), daily.toString())) {
            assertFalse(rendered.contains(CARD_NUMBER),
                    "the rendering holds the full card number: " + rendered);
            assertTrue(rendered.contains(maskOf(CARD_NUMBER)),
                    "the rendering publishes the masked card number instead");
            for (String withheld : List.of(description, amount.toPlainString(), merchantName,
                    merchantCity, merchantZip)) {
                assertFalse(rendered.contains(withheld),
                        "the rendering holds a withheld value: " + withheld);
                assertTrue(
                        rendered.contains(
                                String.valueOf(MASK_CHARACTER).repeat(withheld.length())),
                        withheld.length() + " mask characters stand in for the withheld value");
            }
            assertTrue(rendered.contains("transactionId=TRAN000000000001"),
                    "the rendering keeps the transaction identifier, so a diverging record is"
                            + " nameable");
            assertTrue(rendered.contains("merchantId=" + merchantId),
                    "the rendering keeps the merchant identifier, a key the merchant table"
                            + " resolves");
        }

        assertEquals(posted.toString().replace("PostedTransactionRecord", ""),
                daily.toString().replace("DailyTransactionRecord", ""),
                "the two layouts are byte for byte the same, so the two renderings redact the same"
                        + " components");
    }

    @Test
    void theRenderedRejectedRecordCarriesNoneOfItsTransactionBlob() {
        int blobWidth = 350;
        String blob = "X".repeat(15) + CARD_NUMBER + "Y".repeat(blobWidth - 15 - CARD_NUM_WIDTH);
        assertEquals(blobWidth, blob.length(),
                "the assembled blob holds the " + blobWidth
                        + " bytes app/cbl/CBTRN02C.cbl:L177 declares");

        CopybookRecordParser.RejectedTransactionRecord record =
                new CopybookRecordParser.RejectedTransactionRecord(blob, 102,
                        "OVERLIMIT TRANSACTION");
        String rendered = record.toString();

        assertFalse(rendered.contains(CARD_NUMBER),
                "the rendered rejected record holds the card number inside its blob");
        assertFalse(rendered.contains(blob), "the rendered rejected record holds its whole blob");
        assertTrue(rendered.contains(String.valueOf(MASK_CHARACTER).repeat(blobWidth)),
                "the blob renders as " + blobWidth + " mask characters");
        assertTrue(rendered.contains("102"),
                "the rendered rejected record keeps its reject reason");
        assertTrue(rendered.contains("OVERLIMIT TRANSACTION"),
                "the rendered rejected record keeps its reject description");
    }

    @Test
    void aRedactedComponentHoldingNullRendersWithoutFailing() {
        String rendered = new CopybookRecordParser.CardRecord(null, null, null, null, null, null)
                .toString();
        assertTrue(rendered.contains("cardNumber="),
                "a record of null components still renders its component names");
        assertTrue(rendered.contains("null"),
                "a null component that is not masked renders as the four characters null");

        String blobless = new CopybookRecordParser.RejectedTransactionRecord(null, 0, null)
                .toString();
        assertTrue(blobless.contains("transactionData=null"),
                "a null blob renders as the four characters null and not as a failure");
    }

    // Widths and values of CVTRA05Y TRAN-RECORD, which CVTRA06Y DALYTRAN-RECORD repeats field for
    // field under a different prefix. Typed from the copybooks, as the card widths above are.

    /** Declared length of {@code TRAN-RECORD} at {@code app/cpy/CVTRA05Y.cpy:L4-L18}. */
    private static final int TRANSACTION_RECORD_LENGTH = 350;

    /** Offset of {@code TRAN-AMT}, the sum of the five widths above it. */
    private static final int TRANSACTION_AMOUNT_OFFSET = 132;

    /** Offset of {@code TRAN-CARD-NUM}, the sum of the ten widths above it. */
    private static final int TRANSACTION_CARD_NUMBER_OFFSET = 262;

    /** {@code TRAN-ID} of the assembled transaction record, sixteen characters. */
    private static final String TRANSACTION_ID = "9000000000000001";

    /** {@code TRAN-TYPE-CD} of the assembled transaction record. */
    private static final String TRANSACTION_TYPE_CODE = "07";

    /** {@code TRAN-CAT-CD} of the assembled transaction record. */
    private static final String TRANSACTION_CATEGORY_CODE = "0042";

    /** {@code TRAN-SOURCE} of the assembled transaction record, before its padding. */
    private static final String TRANSACTION_SOURCE = "PARSERSRC";

    /** {@code TRAN-DESC} of the assembled transaction record, before its padding. */
    private static final String TRANSACTION_DESCRIPTION = "PARSER FIXTURE DESCRIPTION";

    /**
     * {@code TRAN-AMT} as the fixture encodes it: ten digits and a trailing negative overpunch, the
     * character {@code N} standing for the digit five with a negative sign.
     */
    private static final String TRANSACTION_AMOUNT_ENCODED = "0000001234N";

    /** {@code TRAN-AMT} as {@code PIC S9(09)V99} decodes, at the scale its picture declares. */
    private static final BigDecimal TRANSACTION_AMOUNT = new BigDecimal("-123.45");

    /** {@code TRAN-MERCHANT-ID} of the assembled transaction record, nine digits. */
    private static final String TRANSACTION_MERCHANT_ID = "700000009";

    /** {@code TRAN-MERCHANT-NAME} of the assembled transaction record, before its padding. */
    private static final String TRANSACTION_MERCHANT_NAME = "PARSER MERCHANT NAME";

    /** {@code TRAN-MERCHANT-CITY} of the assembled transaction record, before its padding. */
    private static final String TRANSACTION_MERCHANT_CITY = "PARSER MERCHANT CITY";

    /** {@code TRAN-MERCHANT-ZIP} of the assembled transaction record, ten characters. */
    private static final String TRANSACTION_MERCHANT_ZIP = "ZIP0000001";

    /** {@code TRAN-CARD-NUM} of the assembled transaction record, sixteen digits. */
    private static final String TRANSACTION_CARD_NUMBER = "6011333344445555";

    /** {@code TRAN-ORIG-TS} of the assembled transaction record, twenty-six characters. */
    private static final String TRANSACTION_ORIGIN_TIMESTAMP = "2024-03-04 05:06:07.891011";

    /** {@code TRAN-PROC-TS} of the assembled transaction record, twenty-six characters. */
    private static final String TRANSACTION_PROCESSING_TIMESTAMP = "2024-09-10-11.12.13.140000";

    /** Declared length of {@code REJECT-RECORD} at {@code app/cbl/CBTRN02C.cbl:L176-L178}. */
    private static final int REJECT_RECORD_LENGTH = 430;

    /** Width of {@code VALIDATION-TRAILER} at {@code app/cbl/CBTRN02C.cbl:L178}. */
    private static final int REJECT_TRAILER_WIDTH = 80;

    /** Width of {@code WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L181}. */
    private static final int REJECT_REASON_WIDTH = 4;

    /** Width of {@code WS-VALIDATION-FAIL-REASON-DESC} at {@code app/cbl/CBTRN02C.cbl:L182}. */
    private static final int REJECT_REASON_DESCRIPTION_WIDTH = 76;

    /** Reason {@code app/cbl/CBTRN02C.cbl:L403-L413} assigns, used where one reason is enough. */
    private static final int REJECT_REASON_OVERLIMIT = 102;

    /**
     * The four reject reasons and their descriptions, quoted from
     * {@code app/cbl/CBTRN02C.cbl:L385-L420} character for character.
     */
    private static final Map<Integer, String> REJECT_REASONS = rejectReasons();

    /** Builds {@link #REJECT_REASONS} in the order the validation chain assigns them. */
    private static Map<Integer, String> rejectReasons() {
        Map<Integer, String> reasons = new LinkedHashMap<>();
        reasons.put(100, "INVALID CARD NUMBER FOUND");
        reasons.put(101, "ACCOUNT RECORD NOT FOUND");
        reasons.put(REJECT_REASON_OVERLIMIT, "OVERLIMIT TRANSACTION");
        reasons.put(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        return reasons;
    }

    /** Characters {@code app/cbl/CBTRN02C.cbl:L414} compares against an account expiry field. */
    private static final int ACCOUNT_EXPIRATION_COMPARISON_WIDTH = 10;

    /** Width of {@code TRAN-PROC-TS} at {@code app/cpy/CVTRA05Y.cpy:L17}. */
    private static final int PROCESSING_TIMESTAMP_WIDTH = 26;

    /** Offset of the four digits {@code app/cbl/CBTRN02C.cbl:L701} writes as zeros. */
    private static final int PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET = 22;

    /** Count of those four digits. */
    private static final int PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS = 4;

    /** Fractional digits {@code app/cbl/CBTRN02C.cbl:L700} carries into the field. */
    private static final int PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS = 2;

    /** Record layouts the parser publishes a parse operation for. */
    private static final int PARSE_OPERATION_COUNT = 11;

    /**
     * One parse operation and the width its layout accepts.
     *
     * @param layoutLabel   copybook and record name the operation reports on a failure
     * @param acceptedWidth a width the operation accepts
     * @param parse         the operation
     */
    private record ParseOperation(String layoutLabel, int acceptedWidth,
            Function<String, Object> parse) {
    }

    /** Every parse operation the parser publishes, with a width its layout accepts. */
    private static final List<ParseOperation> PARSE_OPERATIONS = List.of(
            new ParseOperation("CVACT01Y ACCOUNT-RECORD", 300,
                    CopybookRecordParser::parseAccount),
            new ParseOperation("CVACT02Y CARD-RECORD", CARD_RECORD_LENGTH,
                    CopybookRecordParser::parseCard),
            new ParseOperation("CVACT03Y CARD-XREF-RECORD", XREF_DELIVERED_WIDTH,
                    CopybookRecordParser::parseCardCrossReference),
            new ParseOperation("CVCUS01Y CUSTOMER-RECORD", 500,
                    CopybookRecordParser::parseCustomer),
            new ParseOperation("CVTRA01Y TRAN-CAT-BAL-RECORD", 50,
                    CopybookRecordParser::parseTransactionCategoryBalance),
            new ParseOperation("CVTRA02Y DIS-GROUP-RECORD", 50,
                    CopybookRecordParser::parseDisclosureGroup),
            new ParseOperation("CVTRA03Y TRAN-TYPE-RECORD", 60,
                    CopybookRecordParser::parseTransactionType),
            new ParseOperation("CVTRA04Y TRAN-CAT-RECORD", 60,
                    CopybookRecordParser::parseTransactionCategory),
            new ParseOperation("CVTRA05Y TRAN-RECORD", TRANSACTION_RECORD_LENGTH,
                    CopybookRecordParser::parsePostedTransaction),
            new ParseOperation("CVTRA06Y DALYTRAN-RECORD", TRANSACTION_RECORD_LENGTH,
                    CopybookRecordParser::parseDailyTransaction),
            new ParseOperation("CBTRN02C REJECT-RECORD", REJECT_RECORD_LENGTH,
                    CopybookRecordParser::parseRejectedTransaction));

    // CVTRA05Y TRAN-RECORD and CVTRA06Y DALYTRAN-RECORD, the two layouts that share every offset.

    /**
     * Asserts the fourteen field widths of the transaction layout sum to its declared length, and
     * asserts each offset is the running sum of the widths above it.
     */
    @Test
    void theTransactionRecordOffsetsAreTheRunningSumsOfItsWidths() {
        List<Integer> widths = List.copyOf(transactionLayout().values());
        List<String> fields = List.copyOf(transactionLayout().keySet());

        int total = 0;
        for (int width : widths) {
            total += width;
        }
        assertEquals(TRANSACTION_RECORD_LENGTH, total,
                "the fourteen widths of app/cpy/CVTRA05Y.cpy:L4-L18 sum to its declared length");

        List<Integer> offsets = runningSums(widths);
        assertEquals(0, offsets.get(fields.indexOf("TRAN-ID")), "TRAN-ID opens the record");
        assertEquals(TRANSACTION_AMOUNT_OFFSET, offsets.get(fields.indexOf("TRAN-AMT")),
                "TRAN-AMT sits at the sum of the five widths above it");
        assertEquals(TRANSACTION_CARD_NUMBER_OFFSET, offsets.get(fields.indexOf("TRAN-CARD-NUM")),
                "TRAN-CARD-NUM sits at the sum of the ten widths above it");
    }

    /**
     * Asserts that {@link CopybookRecordParser#parsePostedTransaction} reads each of its thirteen
     * components from its own field. Every value differs from every other, so a read at the wrong
     * offset returns a neighbour's value.
     */
    @Test
    void everyPostedTransactionComponentComesFromItsOwnField() {
        CopybookRecordParser.PostedTransactionRecord record =
                CopybookRecordParser.parsePostedTransaction(assembledTransactionRecord());

        assertEquals(TRANSACTION_ID, record.transactionId(), "TRAN-ID");
        assertEquals(TRANSACTION_TYPE_CODE, record.typeCode(), "TRAN-TYPE-CD");
        assertEquals(TRANSACTION_CATEGORY_CODE, record.categoryCode(), "TRAN-CAT-CD");
        assertEquals(TRANSACTION_SOURCE, record.source(), "TRAN-SOURCE, its padding removed");
        assertEquals(TRANSACTION_DESCRIPTION, record.description(),
                "TRAN-DESC, its padding removed");
        assertEquals(TRANSACTION_AMOUNT, record.amount(),
                "TRAN-AMT, decoded from its trailing sign overpunch");
        assertEquals(TRANSACTION_MERCHANT_ID, record.merchantId(), "TRAN-MERCHANT-ID");
        assertEquals(TRANSACTION_MERCHANT_NAME, record.merchantName(), "TRAN-MERCHANT-NAME");
        assertEquals(TRANSACTION_MERCHANT_CITY, record.merchantCity(), "TRAN-MERCHANT-CITY");
        assertEquals(TRANSACTION_MERCHANT_ZIP, record.merchantZip(), "TRAN-MERCHANT-ZIP");
        assertRedactedEquals(TRANSACTION_CARD_NUMBER, record.cardNumber(), "TRAN-CARD-NUM",
                CopybookRecordParserTest::redactedCardNumber);
        assertEquals(TRANSACTION_ORIGIN_TIMESTAMP, record.originTimestamp(), "TRAN-ORIG-TS");
        assertEquals(TRANSACTION_PROCESSING_TIMESTAMP, record.processingTimestamp(),
                "TRAN-PROC-TS");
    }

    /**
     * Asserts that the posted layout of {@code app/cpy/CVTRA05Y.cpy} and the daily layout of
     * {@code app/cpy/CVTRA06Y.cpy} read the same thirteen offsets from the same record.
     *
     * <p>The two copybooks declare identical widths in identical order and differ only in their
     * field name prefix. Nothing enforces that, so it is asserted here: the posting service copies
     * a daily record onto a posted record field by field at
     * {@code app/cbl/CBTRN02C.cbl:L424-L437}, and a width that drifted in one copybook would move
     * every field below it in one layout and not the other.</p>
     */
    @Test
    void theDailyAndPostedLayoutsReadTheSameOffsets() {
        String record = assembledTransactionRecord();

        CopybookRecordParser.PostedTransactionRecord posted =
                CopybookRecordParser.parsePostedTransaction(record);
        CopybookRecordParser.DailyTransactionRecord daily =
                CopybookRecordParser.parseDailyTransaction(record);

        assertEquals(posted.transactionId(), daily.transactionId(), "TRAN-ID against DALYTRAN-ID");
        assertEquals(posted.typeCode(), daily.typeCode(), "the type code of both layouts");
        assertEquals(posted.categoryCode(), daily.categoryCode(), "the category code of both");
        assertEquals(posted.source(), daily.source(), "the source of both");
        assertEquals(posted.description(), daily.description(), "the description of both");
        assertEquals(posted.amount(), daily.amount(), "the amount of both, at the same scale");
        assertEquals(posted.merchantId(), daily.merchantId(), "the merchant identifier of both");
        assertEquals(posted.merchantName(), daily.merchantName(), "the merchant name of both");
        assertEquals(posted.merchantCity(), daily.merchantCity(), "the merchant city of both");
        assertEquals(posted.merchantZip(), daily.merchantZip(), "the merchant zip of both");
        assertRedactedEquals(posted.cardNumber(), daily.cardNumber(),
                "the card number both layouts read from offset " + TRANSACTION_CARD_NUMBER_OFFSET,
                CopybookRecordParserTest::redactedCardNumber);
        assertEquals(posted.originTimestamp(), daily.originTimestamp(),
                "the origin timestamp of both");
        assertEquals(posted.processingTimestamp(), daily.processingTimestamp(),
                "the processing timestamp of both");
    }

    /**
     * Asserts that the rendered {@code PostedTransactionRecord} publishes a masked card number and
     * no full Primary Account Number.
     */
    @Test
    void theRenderedPostedTransactionCarriesNoFullCardNumber() {
        String rendered = CopybookRecordParser
                .parsePostedTransaction(assembledTransactionRecord()).toString();

        assertFalse(rendered.contains(TRANSACTION_CARD_NUMBER),
                "the rendered posted transaction holds its full card number");
        assertTrue(rendered.contains(maskOf(TRANSACTION_CARD_NUMBER)),
                "the rendered posted transaction publishes the masked card number");
        assertTrue(rendered.contains(TRANSACTION_ID),
                "the rendered posted transaction keeps its transaction identifier");
    }

    // CBTRN02C REJECT-RECORD, the 430-byte record the reject path writes.

    /**
     * Asserts that {@link CopybookRecordParser#parseRejectedTransaction} reads the transaction blob,
     * the reason code and the description from their own fields, for each of the four reasons
     * {@code app/cbl/CBTRN02C.cbl:L385-L420} assigns.
     */
    @Test
    void everyRejectedTransactionComponentComesFromItsOwnField() {
        String blob = assembledTransactionRecord();

        for (Map.Entry<Integer, String> reason : REJECT_REASONS.entrySet()) {
            String record = assembledRejectRecord(blob, reason.getKey(), reason.getValue());

            CopybookRecordParser.RejectedTransactionRecord rejected =
                    CopybookRecordParser.parseRejectedTransaction(record);

            assertEquals(blob, rejected.transactionData(),
                    "REJECT-TRAN-DATA of reason " + reason.getKey()
                            + " holds the whole transaction record");
            assertEquals(reason.getKey().intValue(), rejected.failReason(),
                    "WS-VALIDATION-FAIL-REASON of reason " + reason.getKey());
            assertEquals(reason.getValue(), rejected.failReasonDescription(),
                    "WS-VALIDATION-FAIL-REASON-DESC of reason " + reason.getKey()
                            + ", its padding removed");
        }
    }

    /**
     * Asserts that the eighty-byte validation trailer of {@code app/cbl/CBTRN02C.cbl:L178} follows
     * the transaction data and splits into the four-digit reason and its seventy-six-character
     * description.
     */
    @Test
    void theRejectTrailerFollowsTheTransactionData() {
        assertEquals(REJECT_RECORD_LENGTH, TRANSACTION_RECORD_LENGTH + REJECT_TRAILER_WIDTH,
                "the reject record is the transaction record plus the validation trailer");
        assertEquals(REJECT_TRAILER_WIDTH,
                REJECT_REASON_WIDTH + REJECT_REASON_DESCRIPTION_WIDTH,
                "the trailer splits into the reason of app/cbl/CBTRN02C.cbl:L181 and the "
                        + "description of app/cbl/CBTRN02C.cbl:L182");

        String record = assembledRejectRecord(assembledTransactionRecord(),
                REJECT_REASON_OVERLIMIT, REJECT_REASONS.get(REJECT_REASON_OVERLIMIT));
        assertEquals(REJECT_RECORD_LENGTH, record.length(),
                "the assembled reject record holds the length its layout declares");
        assertEquals(REJECT_REASONS.get(REJECT_REASON_OVERLIMIT).length(),
                CopybookRecordParser
                        .text(record, TRANSACTION_RECORD_LENGTH + REJECT_REASON_WIDTH,
                                REJECT_REASON_DESCRIPTION_WIDTH, "WS-VALIDATION-FAIL-REASON-DESC")
                        .length(),
                "the description read from its own offset holds the text and none of its padding");
    }

    /**
     * Asserts that a reason field holding a character outside the digit class is refused, and that
     * the failure quotes neither the field nor the record.
     */
    @Test
    void aRejectReasonOutsideTheDigitClassIsRefused() {
        String blob = assembledTransactionRecord();
        String record = blob + "10x2"
                + padded(REJECT_REASONS.get(REJECT_REASON_OVERLIMIT),
                        REJECT_REASON_DESCRIPTION_WIDTH);
        assertEquals(REJECT_RECORD_LENGTH, record.length(),
                "the assembled record holds the length its layout declares");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.parseRejectedTransaction(record),
                "a reason field holding a character other than a digit is refused");

        assertTrue(refused.getMessage().contains("WS-VALIDATION-FAIL-REASON"),
                "the failure names the field it could not read");
        assertFalse(refused.getMessage().contains(record),
                "the failure quotes none of the record");
        assertFalse(refused.getMessage().contains(TRANSACTION_CARD_NUMBER),
                "the failure quotes no card number from inside the blob");
    }

    // Width refusal, for every one of the eleven parse operations.

    /**
     * Asserts that each parse operation refuses a record of any width its layout does not declare,
     * that the failure names the layout, and that it quotes none of the record.
     *
     * <p>The cross-reference layout accepts two widths, the one the text fixture delivers and the
     * one the dataset definition declares. The two widths tested for it are one either side of the
     * delivered width, so neither is the declared width.</p>
     */
    @Test
    void everyLayoutRefusesARecordOfAnotherWidthWithoutEchoingIt() {
        for (ParseOperation operation : PARSE_OPERATIONS) {
            for (int width : List.of(operation.acceptedWidth() - 1,
                    operation.acceptedWidth() + 1)) {
                String record = "Z".repeat(width);

                IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        () -> operation.parse().apply(record),
                        operation.layoutLabel() + " accepted a record of " + width + " characters");

                assertTrue(refused.getMessage().contains(operation.layoutLabel()),
                        "the failure of " + operation.layoutLabel() + " names its layout");
                assertTrue(refused.getMessage().contains(String.valueOf(width)),
                        "the failure of " + operation.layoutLabel() + " names the width supplied");
                assertFalse(refused.getMessage().contains(record),
                        "the failure of " + operation.layoutLabel() + " quotes the record");
            }
        }
    }

    /**
     * Asserts the operation table names one operation per record layout the parser publishes, and
     * names each layout once. A parse operation added later without a table entry fails here.
     */
    @Test
    void theWidthRefusalTableCoversEveryParseOperation() {
        Set<String> labels = new LinkedHashSet<>();
        for (ParseOperation operation : PARSE_OPERATIONS) {
            assertTrue(labels.add(operation.layoutLabel()),
                    operation.layoutLabel() + " appears in the table more than once");
        }

        assertEquals(PARSE_OPERATION_COUNT, PARSE_OPERATIONS.size(),
                "the table names one operation per record layout the parser publishes");
        assertEquals(PARSE_OPERATION_COUNT, labels.size(), "the table names each layout once");
    }

    // Timestamp operations.

    /**
     * Asserts that the expiration comparison of {@code app/cbl/CBTRN02C.cbl:L414} reads exactly the
     * leading ten characters of a timestamp and compares them as text.
     */
    @Test
    void theExpirationComparisonReadsTenCharactersOfATimestamp() {
        String datePart =
                CopybookRecordParser.timestampDatePart(TRANSACTION_ORIGIN_TIMESTAMP);

        assertEquals(ACCOUNT_EXPIRATION_COMPARISON_WIDTH, datePart.length(),
                "the comparison reads the width app/cbl/CBTRN02C.cbl:L414 reads");
        assertEquals(TRANSACTION_ORIGIN_TIMESTAMP.substring(0,
                        ACCOUNT_EXPIRATION_COMPARISON_WIDTH), datePart,
                "the comparison reads the leading characters and no others");
        assertEquals(datePart, CopybookRecordParser.timestampDatePart(datePart),
                "a field already at the comparison width reads unchanged");
    }

    /** Asserts that a timestamp shorter than the comparison reads is refused, value withheld. */
    @Test
    void aTimestampShorterThanTheComparisonIsRefused() {
        String tooShort = TRANSACTION_ORIGIN_TIMESTAMP.substring(0,
                ACCOUNT_EXPIRATION_COMPARISON_WIDTH - 1);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.timestampDatePart(tooShort),
                "a timestamp shorter than the comparison reads is refused");

        assertTrue(refused.getMessage()
                        .contains(String.valueOf(ACCOUNT_EXPIRATION_COMPARISON_WIDTH)),
                "the failure names the width the comparison reads");
        assertFalse(refused.getMessage().contains(tooShort),
                "the failure quotes none of the timestamp");
    }

    /**
     * Asserts that the truncation of {@code app/cbl/CBTRN02C.cbl:L700-L701} keeps two fractional
     * digits and sets the trailing four to zero, leaving every character above them alone.
     */
    @Test
    void theProcessingTimestampTruncationZeroesTheTrailingFourDigits() {
        String rendered = "2024-09-10-11.12.13.987654";
        assertEquals(PROCESSING_TIMESTAMP_WIDTH, rendered.length(),
                "the timestamp under test holds the width its field declares");

        String truncated = CopybookRecordParser.truncateProcessingTimestampToHundredths(rendered);

        assertEquals(PROCESSING_TIMESTAMP_WIDTH, truncated.length(),
                "the truncation keeps the width of the field");
        assertEquals(rendered.substring(0, PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                truncated.substring(0, PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                "every character above the trailing digits is left alone");
        assertEquals("0".repeat(PROCESSING_TIMESTAMP_TRAILING_ZERO_DIGITS),
                truncated.substring(PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                "the trailing digits are the zeros app/cbl/CBTRN02C.cbl:L701 writes");
        assertEquals("98", truncated.substring(PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET
                        - PROCESSING_TIMESTAMP_SIGNIFICANT_FRACTION_DIGITS,
                PROCESSING_TIMESTAMP_TRAILING_ZEROS_OFFSET),
                "the two significant fractional digits of app/cbl/CBTRN02C.cbl:L700 survive");
    }

    /**
     * Asserts that the truncation is idempotent and that a field holding only padding reads
     * unchanged, which is how an unposted daily record carries an empty processing timestamp.
     */
    @Test
    void theProcessingTimestampTruncationIsIdempotentAndLeavesPaddingAlone() {
        String truncated = CopybookRecordParser
                .truncateProcessingTimestampToHundredths("2024-09-10-11.12.13.987654");

        assertEquals(truncated,
                CopybookRecordParser.truncateProcessingTimestampToHundredths(truncated),
                "truncating an already truncated timestamp changes nothing");

        String padding = " ".repeat(PROCESSING_TIMESTAMP_WIDTH);
        assertEquals(padding,
                CopybookRecordParser.truncateProcessingTimestampToHundredths(padding),
                "a field holding only padding reads unchanged rather than gaining four zeros");
    }

    /** Asserts that a populated timestamp of another width is refused, value withheld. */
    @Test
    void aProcessingTimestampOfAnotherWidthIsRefused() {
        String tooLong = "2024-09-10-11.12.13.9876543";
        assertEquals(PROCESSING_TIMESTAMP_WIDTH + 1, tooLong.length(),
                "the timestamp under test is one character wider than its field");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> CopybookRecordParser.truncateProcessingTimestampToHundredths(tooLong),
                "a timestamp of another width is refused");

        assertTrue(refused.getMessage().contains("TRAN-PROC-TS"),
                "the failure names the field it could not read");
        assertTrue(refused.getMessage().contains(String.valueOf(PROCESSING_TIMESTAMP_WIDTH)),
                "the failure names the width the field holds");
        assertFalse(refused.getMessage().contains(tooLong),
                "the failure quotes none of the timestamp");
    }

    // Record assembly. Each helper builds a raw record from field values this class chose.

    /** Returns the offset of each field, as the running sum of the widths above it. */
    private static List<Integer> runningSums(List<Integer> widths) {
        List<Integer> offsets = new ArrayList<>(widths.size());
        int running = 0;
        for (int width : widths) {
            offsets.add(running);
            running += width;
        }
        return offsets;
    }

    /** Returns a value padded on the right with spaces to {@code width} characters. */
    private static String padded(String value, int width) {
        if (value.length() > width) {
            throw new AssertionError("the test value holds " + value.length()
                    + " characters and the field holds " + width);
        }
        return value + " ".repeat(width - value.length());
    }

    /** Returns the masked form of a card number: twelve mask characters and its last four. */
    private static String maskOf(String cardNumber) {
        return String.valueOf(MASK_CHARACTER).repeat(CARD_NUM_WIDTH - VISIBLE_CARD_NUMBER_DIGITS)
                + cardNumber.substring(cardNumber.length() - VISIBLE_CARD_NUMBER_DIGITS);
    }

    // Redacted comparison. Every assertion below runs against a card-number-shaped or a
    // verification-value-shaped value, and a failing assertion writes both sides into the Surefire
    // report, which .github/workflows/ci.yml uploads and keeps. A seeded card number in a published
    // report is the same exposure whether a log line or an assertion message put it there, so these
    // values are compared for equality and reported in a masked form. The redaction runs only on the
    // failure
    // path, so a passing run pays nothing for it.

    /**
     * Returns a card-number-shaped value in the form this platform publishes it: the last four
     * characters in the clear and everything before them masked.
     *
     * <p>The length is named as well, because a component read at the wrong offset is usually the
     * wrong length, and that is the diagnostic the masked form loses. A value of four characters or
     * fewer is masked entirely, since keeping its last four would keep all of it.</p>
     *
     * @param value the value to describe, which may be any width or {@code null}
     * @return a description carrying no more of the value than the last four characters
     */
    private static String redactedCardNumber(String value) {
        if (value == null) {
            return "no value";
        }
        if (value.length() <= VISIBLE_CARD_NUMBER_DIGITS) {
            return String.valueOf(MASK_CHARACTER).repeat(value.length())
                    + " (" + value.length() + " characters)";
        }
        return String.valueOf(MASK_CHARACTER).repeat(value.length() - VISIBLE_CARD_NUMBER_DIGITS)
                + value.substring(value.length() - VISIBLE_CARD_NUMBER_DIGITS)
                + " (" + value.length() + " characters)";
    }

    /**
     * Returns a verification value in the form this platform publishes it, which is no part of it.
     *
     * <p>{@code com.carddemo.cobol.PanMasker#redactCardVerificationValue} keeps none of this field,
     * because three digits with no part withheld is the whole secret. The length is named instead.</p>
     *
     * @param value the value to describe, which may be any width or {@code null}
     * @return a description carrying none of the value
     */
    private static String redactedVerificationValue(String value) {
        if (value == null) {
            return "no value";
        }
        return String.valueOf(MASK_CHARACTER).repeat(value.length())
                + " (" + value.length() + " characters)";
    }

    /**
     * Asserts that one parsed component equals the value this class put in its field, and reports a
     * mismatch without writing either side into the message.
     *
     * <p>This is the equality {@code assertEquals} performs. What differs is the failure: JUnit
     * renders the expected and the actual value, and both are cardholder data here.</p>
     *
     * @param expected  the value this class assembled into the field
     * @param actual    the value the parser read
     * @param field     the copybook field the value belongs to, named in a failure
     * @param redaction how to describe a value of this field's kind
     */
    private static void assertRedactedEquals(String expected, String actual, String field,
            Function<String, String> redaction) {
        if (expected.equals(actual)) {
            return;
        }
        throw new AssertionError(field + " did not read the value this test assembled into it."
                + " Expected " + redaction.apply(expected)
                + " and read " + redaction.apply(actual)
                + ". Both sides are redacted because this report is published: compare the widths"
                + " and the visible characters against the offsets asserted above to find which"
                + " neighbouring field was read instead.");
    }

    /** Returns one raw {@code CARD-RECORD} built from the six field values this class chose. */
    private static String assembledCardRecord() {
        return CARD_NUMBER
                + CARD_ACCOUNT_ID
                + CARD_VERIFICATION_VALUE
                + padded(CARD_EMBOSSED_NAME, CARD_EMBOSSED_NAME_WIDTH)
                + CARD_EXPIRATION_DATE
                + CARD_ACTIVE_STATUS
                + " ".repeat(CARD_FILLER_WIDTH);
    }

    /**
     * Returns one raw {@code CARD-XREF-RECORD} at {@code width} characters.
     *
     * @param width either the width the text fixture delivers or the declared record length
     */
    private static String assembledCrossReferenceRecord(int width) {
        String modelled = XREF_CARD_NUMBER + XREF_CUSTOMER_ID + XREF_ACCOUNT_ID;
        return modelled + " ".repeat(width - modelled.length());
    }

    /** Returns the eighteen field widths of {@code CUSTOMER-RECORD} plus its trailing filler. */
    private static Map<String, Integer> customerLayout() {
        Map<String, Integer> layout = new LinkedHashMap<>();
        layout.put("CUST-ID", 9);
        layout.put("CUST-FIRST-NAME", 25);
        layout.put("CUST-MIDDLE-NAME", 25);
        layout.put("CUST-LAST-NAME", 25);
        layout.put("CUST-ADDR-LINE-1", 50);
        layout.put("CUST-ADDR-LINE-2", 50);
        layout.put("CUST-ADDR-LINE-3", 50);
        layout.put("CUST-ADDR-STATE-CD", 2);
        layout.put("CUST-ADDR-COUNTRY-CD", 3);
        layout.put("CUST-ADDR-ZIP", 10);
        layout.put("CUST-PHONE-NUM-1", 15);
        layout.put("CUST-PHONE-NUM-2", 15);
        layout.put("CUST-SSN", 9);
        layout.put("CUST-GOVT-ISSUED-ID", 20);
        layout.put("CUST-DOB-YYYY-MM-DD", 10);
        layout.put("CUST-EFT-ACCOUNT-ID", 10);
        layout.put("CUST-PRI-CARD-HOLDER-IND", 1);
        layout.put("CUST-FICO-CREDIT-SCORE", 3);
        layout.put("FILLER", 168);
        return layout;
    }

    /**
     * Returns the fourteen field widths of {@code TRAN-RECORD}, which {@code DALYTRAN-RECORD}
     * repeats. Field names are the posted spelling; the daily copybook prefixes them
     * {@code DALYTRAN} and declares the same widths in the same order.
     */
    private static Map<String, Integer> transactionLayout() {
        Map<String, Integer> layout = new LinkedHashMap<>();
        layout.put("TRAN-ID", 16);
        layout.put("TRAN-TYPE-CD", 2);
        layout.put("TRAN-CAT-CD", 4);
        layout.put("TRAN-SOURCE", 10);
        layout.put("TRAN-DESC", 100);
        layout.put("TRAN-AMT", 11);
        layout.put("TRAN-MERCHANT-ID", 9);
        layout.put("TRAN-MERCHANT-NAME", 50);
        layout.put("TRAN-MERCHANT-CITY", 50);
        layout.put("TRAN-MERCHANT-ZIP", 10);
        layout.put("TRAN-CARD-NUM", 16);
        layout.put("TRAN-ORIG-TS", 26);
        layout.put("TRAN-PROC-TS", 26);
        layout.put("FILLER", 20);
        return layout;
    }

    /** Returns one distinct value per field of {@code TRAN-RECORD}, each at its field width. */
    private static Map<String, String> transactionValues() {
        Map<String, Integer> layout = transactionLayout();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("TRAN-ID", TRANSACTION_ID);
        values.put("TRAN-TYPE-CD", TRANSACTION_TYPE_CODE);
        values.put("TRAN-CAT-CD", TRANSACTION_CATEGORY_CODE);
        values.put("TRAN-SOURCE", padded(TRANSACTION_SOURCE, layout.get("TRAN-SOURCE")));
        values.put("TRAN-DESC", padded(TRANSACTION_DESCRIPTION, layout.get("TRAN-DESC")));
        values.put("TRAN-AMT", TRANSACTION_AMOUNT_ENCODED);
        values.put("TRAN-MERCHANT-ID", TRANSACTION_MERCHANT_ID);
        values.put("TRAN-MERCHANT-NAME",
                padded(TRANSACTION_MERCHANT_NAME, layout.get("TRAN-MERCHANT-NAME")));
        values.put("TRAN-MERCHANT-CITY",
                padded(TRANSACTION_MERCHANT_CITY, layout.get("TRAN-MERCHANT-CITY")));
        values.put("TRAN-MERCHANT-ZIP", TRANSACTION_MERCHANT_ZIP);
        values.put("TRAN-CARD-NUM", TRANSACTION_CARD_NUMBER);
        values.put("TRAN-ORIG-TS", TRANSACTION_ORIGIN_TIMESTAMP);
        values.put("TRAN-PROC-TS", TRANSACTION_PROCESSING_TIMESTAMP);
        values.put("FILLER", " ".repeat(layout.get("FILLER")));
        return values;
    }

    /** Returns one raw {@code TRAN-RECORD}, which is also one raw {@code DALYTRAN-RECORD}. */
    private static String assembledTransactionRecord() {
        return assembledFixedWidthRecord(transactionLayout(), transactionValues());
    }

    /**
     * Returns one raw {@code REJECT-RECORD}: the transaction data followed by the eighty-byte
     * validation trailer.
     *
     * @param transactionData the 350 bytes of {@code REJECT-TRAN-DATA}
     * @param reason          the reject reason, rendered at its four-digit width
     * @param description     the reject description, padded to its declared width
     * @return the assembled raw record
     */
    private static String assembledRejectRecord(String transactionData, int reason,
            String description) {
        Map<String, Integer> layout = new LinkedHashMap<>();
        layout.put("REJECT-TRAN-DATA", TRANSACTION_RECORD_LENGTH);
        layout.put("WS-VALIDATION-FAIL-REASON", REJECT_REASON_WIDTH);
        layout.put("WS-VALIDATION-FAIL-REASON-DESC", REJECT_REASON_DESCRIPTION_WIDTH);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("REJECT-TRAN-DATA", transactionData);
        values.put("WS-VALIDATION-FAIL-REASON",
                String.format("%0" + REJECT_REASON_WIDTH + "d", reason));
        values.put("WS-VALIDATION-FAIL-REASON-DESC",
                padded(description, REJECT_REASON_DESCRIPTION_WIDTH));
        return assembledFixedWidthRecord(layout, values);
    }

    /** Returns one distinct value per field of {@code CUSTOMER-RECORD}, each at its field width. */
    private static Map<String, String> customerValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("CUST-ID", "000000321");
        values.put("CUST-FIRST-NAME", padded("PARSERFIRST", 25));
        values.put("CUST-MIDDLE-NAME", padded("PARSERMIDDLE", 25));
        values.put("CUST-LAST-NAME", padded("PARSERLAST", 25));
        values.put("CUST-ADDR-LINE-1", padded("ADDRESS LINE ONE", 50));
        values.put("CUST-ADDR-LINE-2", padded("ADDRESS LINE TWO", 50));
        values.put("CUST-ADDR-LINE-3", padded("ADDRESS LINE THREE", 50));
        values.put("CUST-ADDR-STATE-CD", "WY");
        values.put("CUST-ADDR-COUNTRY-CD", "USA");
        values.put("CUST-ADDR-ZIP", padded("82001", 10));
        values.put("CUST-PHONE-NUM-1", padded("(307)5550101", 15));
        values.put("CUST-PHONE-NUM-2", padded("(307)5550202", 15));
        values.put("CUST-SSN", "987654321");
        values.put("CUST-GOVT-ISSUED-ID", padded("GOVTID0000000009", 20));
        values.put("CUST-DOB-YYYY-MM-DD", "1971-02-03");
        values.put("CUST-EFT-ACCOUNT-ID", padded("EFT0000077", 10));
        values.put("CUST-PRI-CARD-HOLDER-IND", "Y");
        values.put("CUST-FICO-CREDIT-SCORE", "742");
        values.put("FILLER", " ".repeat(168));
        return values;
    }

    /**
     * Joins field values in layout order, checking each against its declared width.
     *
     * @param layout field name to declared width, in copybook declaration order
     * @param values field name to the value that field carries, at its declared width
     * @return the assembled raw record
     */
    private static String assembledFixedWidthRecord(Map<String, Integer> layout,
            Map<String, String> values) {
        StringBuilder record = new StringBuilder();
        for (Map.Entry<String, Integer> field : layout.entrySet()) {
            String value = values.get(field.getKey());
            if (value == null) {
                throw new AssertionError("the test supplies no value for " + field.getKey());
            }
            if (value.length() != field.getValue()) {
                throw new AssertionError("the test value for " + field.getKey() + " holds "
                        + value.length() + " characters and the field holds " + field.getValue());
            }
            record.append(value);
        }
        return record.toString();
    }
}
