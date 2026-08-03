package com.carddemo.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CopybookRecordParser}, built from records this class assembles field by field.
 *
 * <p>No test here reads a fixture. Each raw record is built from independently chosen field values
 * at the widths the copybooks declare, so a wrong offset inside the parser moves a known value into
 * the wrong component and fails. Reading a fixture instead would compare the parser against itself
 * whenever both agreed on a wrong offset.</p>
 *
 * <p>The layouts covered are {@code CARD-RECORD} at {@code app/cpy/CVACT02Y.cpy:L4-L11},
 * {@code CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:L4-L8} and {@code CUSTOMER-RECORD} at
 * {@code app/cpy/CVCUS01Y.cpy:L4-L23}, together with the four field readers and the overpunch
 * decode every layout depends on.</p>
 *
 * <p>Every field value below is chosen so that no two fields of one record share a value. A parser
 * that read the right width at the wrong offset would return a value belonging to a neighbour, and
 * an assertion naming the field it belongs to would fail.</p>
 *
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

    /**
     * Asserts the seven field widths of {@code CARD-RECORD} sum to its declared length, and
     * asserts each offset is the running sum of the widths above it.
     */
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

    /**
     * Asserts the four field widths of {@code CARD-XREF-RECORD} sum to its declared length, and
     * asserts the text fixture stops on the last byte of {@code XREF-ACCT-ID}.
     */
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

    /**
     * Asserts that every component of {@code CARD-RECORD} comes from its own field. Each field
     * carries a distinct value, so a read at the wrong offset returns a neighbour's value and
     * fails.
     */
    @Test
    void everyCardComponentComesFromItsOwnField() {
        String record = assembledCardRecord();
        assertEquals(CARD_RECORD_LENGTH, record.length(),
                "the assembled record holds the " + CARD_RECORD_LENGTH
                        + " bytes app/cpy/CVACT02Y.cpy:L4-L11 declares");

        CopybookRecordParser.CardRecord parsed = CopybookRecordParser.parseCard(record);

        assertEquals(CARD_NUMBER, parsed.cardNumber(),
                "cardNumber comes from CARD-NUM at offset 0 of width " + CARD_NUM_WIDTH);
        assertEquals(CARD_ACCOUNT_ID, parsed.accountId(),
                "accountId comes from CARD-ACCT-ID at offset 16 of width " + CARD_ACCT_ID_WIDTH);
        assertEquals(CARD_VERIFICATION_VALUE, parsed.cardVerificationValue(),
                "cardVerificationValue comes from CARD-CVV-CD at offset 27 of width "
                        + CARD_CVV_WIDTH);
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

    /**
     * Asserts that every component of {@code CARD-XREF-RECORD} comes from its own field, at the
     * width the text fixture delivers and again at the declared width.
     */
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

            assertEquals(XREF_CARD_NUMBER, parsed.cardNumber(),
                    "cardNumber comes from XREF-CARD-NUM at offset 0 in the " + form + " form");
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

    /**
     * Asserts that every component of {@code CUSTOMER-RECORD} comes from its own field. Eighteen
     * distinct values are assembled, so a read at a wrong offset returns a neighbour's value.
     */
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

    // Field readers.

    /**
     * Asserts that {@code fixedText} keeps every byte of its field, including trailing spaces, and
     * that {@code text} removes trailing spaces and keeps leading ones.
     */
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

    /**
     * Asserts that a field running past the end of the record is refused, and that the failure
     * names the field, the width, the offset and the record length, and no field text.
     */
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

    /** Asserts that a negative offset and a negative width are both refused by name. */
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
     * zeros, and that a character other than a digit is refused with the position and the width and
     * no field text.
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

    // Overpunch decode.

    /**
     * Asserts the twenty sign overpunch characters decode to the ten digits with the two signs. The
     * expected pairs are typed here from the position of each character in its published list.
     */
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

    /**
     * Asserts that a plain trailing digit reads as a positive value, and that the result carries
     * exactly the scale requested and drops no digit.
     */
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

    /**
     * Asserts that a trailing character that is neither a digit nor an overpunch is refused, and
     * that a leading character that is not a digit is refused. Neither failure carries the text of
     * the field or the offending character.
     */
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
     * Asserts that the rendered {@code CardRecord} carries no full card number and no verification
     * value, and that it publishes the masked card number instead.
     */
    @Test
    void theRenderedCardRecordCarriesNoFullCardNumberAndNoVerificationValue() {
        String rendered = CopybookRecordParser.parseCard(assembledCardRecord()).toString();

        assertFalse(rendered.contains(CARD_NUMBER),
                "the rendered card record holds the full card number of the assembled record");
        assertFalse(rendered.contains(CARD_VERIFICATION_VALUE),
                "the rendered card record holds the verification value of the assembled record");
        assertTrue(rendered.contains(maskOf(CARD_NUMBER)),
                "the rendered card record publishes the masked card number instead");
        assertTrue(rendered.contains(String.valueOf(MASK_CHARACTER).repeat(CARD_CVV_WIDTH)),
                "the verification value renders as " + CARD_CVV_WIDTH + " mask characters");
        assertTrue(rendered.contains(CARD_EMBOSSED_NAME),
                "the rendered card record keeps the fields that are not redacted");
    }

    /** Asserts that the rendered {@code CardCrossReferenceRecord} carries no full card number. */
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
     * Asserts that the rendered {@code CustomerRecord} carries none of its three identifiers, and
     * that each renders as mask characters at the width of the value it replaced.
     */
    @Test
    void theRenderedCustomerRecordCarriesNoneOfItsThreeIdentifiers() {
        Map<String, String> values = customerValues();
        String rendered = CopybookRecordParser
                .parseCustomer(assembledFixedWidthRecord(customerLayout(), values)).toString();

        Map<String, String> redacted = new LinkedHashMap<>();
        redacted.put("CUST-SSN", values.get("CUST-SSN"));
        redacted.put("CUST-GOVT-ISSUED-ID", values.get("CUST-GOVT-ISSUED-ID").strip());
        redacted.put("CUST-EFT-ACCOUNT-ID", values.get("CUST-EFT-ACCOUNT-ID").strip());

        for (Map.Entry<String, String> field : redacted.entrySet()) {
            assertFalse(rendered.contains(field.getValue()),
                    "the rendered customer record holds the value of " + field.getKey());
            assertTrue(
                    rendered.contains(
                            String.valueOf(MASK_CHARACTER).repeat(field.getValue().length())),
                    field.getKey() + " renders as " + field.getValue().length()
                            + " mask characters");
        }

        assertTrue(rendered.contains(values.get("CUST-DOB-YYYY-MM-DD")),
                "the rendered customer record keeps the fields that are not redacted");
        assertTrue(rendered.contains(values.get("CUST-ID")),
                "the rendered customer record keeps the customer identifier");
    }

    /**
     * Asserts that the rendered {@code RejectedTransactionRecord} carries none of its transaction
     * blob. The blob holds a whole daily transaction record, whose card number sits at offset 15.
     */
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

    /**
     * Asserts that a redacted component holding null renders without a failure. A rendering that
     * threw would turn a diagnostic into a second failure.
     */
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
