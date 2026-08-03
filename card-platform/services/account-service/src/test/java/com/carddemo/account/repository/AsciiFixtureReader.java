package com.carddemo.account.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the three fixed-width fixtures the account service tests bind to, and decodes the
 * zoned-decimal money fields those fixtures carry.
 *
 * <p>Each fixture stores one record per line as ASCII (American Standard Code for Information
 * Interchange) text at a single declared width. {@link #readRecords(String, int)} returns the
 * records of one fixture and checks the width of every record it returns.
 * {@link #decodeZonedDecimal(String, int)} converts one signed COBOL display field into a
 * {@link BigDecimal} at an exact scale.</p>
 *
 * <p>The field positions published as {@link Field} constants below are one-based, so they read the
 * same way as the column positions in the copybooks. {@code ZonedDecimalFixtureDecodingTest} reads
 * those constants.</p>
 *
 * <p>Every operation opens its fixture for reading. No operation writes to, copies or moves any
 * file under {@code app/}.</p> <h2>Source artifacts</h2> <table> <caption>Fixture files and the
 * copybooks that declare their record layouts</caption> <tr><th>Fixture</th><th>Record
 * layout</th><th>Records</th><th>Width</th></tr> <tr> <td> {@code app/data/ASCII/acctdata.txt}
 * </td> <td> {@code app/cpy/CVACT01Y.cpy} </td> <td>50</td><td>300</td> </tr> <tr> <td>
 * {@code app/data/ASCII/custdata.txt} </td> <td> {@code app/cpy/CVCUS01Y.cpy} </td>
 * <td>50</td><td>500</td> </tr> <tr> <td> {@code app/data/ASCII/discgrp.txt} </td> <td>
 * {@code app/cpy/CVTRA02Y.cpy} </td> <td>51</td><td>50</td> </tr> </table>
 *
 * <p>The counts and widths in that table come from measuring the three files.</p>
 *
 * <p>Decision: module-local fixture reader.</p>
 */
final class AsciiFixtureReader {

    // Fixture identity: file names, measured record counts, and declared record widths.

    /** File name of the account fixture, laid out by {@code app/cpy/CVACT01Y.cpy}. */
    static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /** File name of the customer fixture, laid out by {@code app/cpy/CVCUS01Y.cpy}. */
    static final String CUSTOMER_FIXTURE = "custdata.txt";

    /** File name of the disclosure group fixture, laid out by {@code app/cpy/CVTRA02Y.cpy}. */
    static final String DISCLOSURE_GROUP_FIXTURE = "discgrp.txt";

    /** Record width of {@code app/data/ASCII/acctdata.txt}, declared RECLN 300 in CVACT01Y. */
    static final int ACCOUNT_RECORD_LENGTH = 300;

    /** Record width of {@code app/data/ASCII/custdata.txt}, declared RECLN 500 in CVCUS01Y. */
    static final int CUSTOMER_RECORD_LENGTH = 500;

    /** Record width of {@code app/data/ASCII/discgrp.txt}, declared RECLN 50 in CVTRA02Y. */
    static final int DISCLOSURE_GROUP_RECORD_LENGTH = 50;

    /** Records held by {@code app/data/ASCII/acctdata.txt}, measured at 50. */
    static final int ACCOUNT_RECORD_COUNT = 50;

    /** Records held by {@code app/data/ASCII/custdata.txt}, measured at 50. */
    static final int CUSTOMER_RECORD_COUNT = 50;

    /** Records held by {@code app/data/ASCII/discgrp.txt}, measured at 51. */
    static final int DISCLOSURE_GROUP_RECORD_COUNT = 51;

    // Scales and field widths taken from the copybook Picture clauses.

    /** Digits after the decimal point in every {@code PIC S9(10)V99} account money field. */
    static final int MONEY_SCALE = 2;

    /** Digits after the decimal point in the {@code PIC S9(04)V99} rate at CVTRA02Y line 9. */
    static final int INTEREST_RATE_SCALE = 2;

    /** Bytes occupied by a {@code PIC S9(10)V99} field: ten integer digits plus two decimals. */
    static final int MONEY_FIELD_LENGTH = 12;

    /** Bytes occupied by a {@code PIC S9(04)V99} field: four integer digits plus two decimals. */
    static final int INTEREST_RATE_FIELD_LENGTH = 6;

    // Account record fields, from app/cpy/CVACT01Y.cpy lines 5 through 17.
    // Mapped fields span positions 1 through 122. Filler spans 123 through 300.

    /** ACCT-ID, {@code PIC 9(11)} at CVACT01Y line 5. */
    static final Field ACCOUNT_ID = new Field(1, 11);

    /** ACCT-ACTIVE-STATUS, {@code PIC X(01)} at CVACT01Y line 6. */
    static final Field ACCOUNT_ACTIVE_STATUS = new Field(12, 1);

    /** ACCT-CURR-BAL, {@code PIC S9(10)V99} at CVACT01Y line 7. */
    static final Field ACCOUNT_CURRENT_BALANCE = new Field(13, MONEY_FIELD_LENGTH);

    /** ACCT-CREDIT-LIMIT, {@code PIC S9(10)V99} at CVACT01Y line 8. */
    static final Field ACCOUNT_CREDIT_LIMIT = new Field(25, MONEY_FIELD_LENGTH);

    /** ACCT-CASH-CREDIT-LIMIT, {@code PIC S9(10)V99} at CVACT01Y line 9. */
    static final Field ACCOUNT_CASH_CREDIT_LIMIT = new Field(37, MONEY_FIELD_LENGTH);

    /** ACCT-OPEN-DATE, {@code PIC X(10)} at CVACT01Y line 10. */
    static final Field ACCOUNT_OPEN_DATE = new Field(49, 10);

    /**
     * ACCT-EXPIRAION-DATE, {@code PIC X(10)} at CVACT01Y line 11. The copybook spells the field
     * name with a transposed word.
     */
    static final Field ACCOUNT_EXPIRATION_DATE = new Field(59, 10);

    /** ACCT-REISSUE-DATE, {@code PIC X(10)} at CVACT01Y line 12. */
    static final Field ACCOUNT_REISSUE_DATE = new Field(69, 10);

    /** ACCT-CURR-CYC-CREDIT, {@code PIC S9(10)V99} at CVACT01Y line 13. */
    static final Field ACCOUNT_CURRENT_CYCLE_CREDIT = new Field(79, MONEY_FIELD_LENGTH);

    /** ACCT-CURR-CYC-DEBIT, {@code PIC S9(10)V99} at CVACT01Y line 14. */
    static final Field ACCOUNT_CURRENT_CYCLE_DEBIT = new Field(91, MONEY_FIELD_LENGTH);

    /** ACCT-ADDR-ZIP, {@code PIC X(10)} at CVACT01Y line 15. */
    static final Field ACCOUNT_ADDRESS_ZIP = new Field(103, 10);

    /** ACCT-GROUP-ID, {@code PIC X(10)} at CVACT01Y line 16. */
    static final Field ACCOUNT_GROUP_ID = new Field(113, 10);

    /** FILLER, {@code PIC X(178)} at CVACT01Y line 17. */
    static final Field ACCOUNT_FILLER = new Field(123, 178);

    /**
     * The five account money fields, in copybook order: CVACT01Y lines 7, 8, 9, 13 and 14. Every
     * field in this list carries an overpunched sign in its trailing byte and decodes at
     * {@link #MONEY_SCALE}.
     */
    static final List<Field> ACCOUNT_MONEY_FIELDS = List.of(
            ACCOUNT_CURRENT_BALANCE,
            ACCOUNT_CREDIT_LIMIT,
            ACCOUNT_CASH_CREDIT_LIMIT,
            ACCOUNT_CURRENT_CYCLE_CREDIT,
            ACCOUNT_CURRENT_CYCLE_DEBIT);

    // Customer record fields, from app/cpy/CVCUS01Y.cpy lines 5 through 23.
    // Mapped fields span positions 1 through 332. Filler spans 333 through 500.

    /** CUST-ID, {@code PIC 9(09)} at CVCUS01Y line 5. */
    static final Field CUSTOMER_ID = new Field(1, 9);

    /** CUST-FIRST-NAME, {@code PIC X(25)} at CVCUS01Y line 6. */
    static final Field CUSTOMER_FIRST_NAME = new Field(10, 25);

    /** CUST-MIDDLE-NAME, {@code PIC X(25)} at CVCUS01Y line 7. */
    static final Field CUSTOMER_MIDDLE_NAME = new Field(35, 25);

    /** CUST-LAST-NAME, {@code PIC X(25)} at CVCUS01Y line 8. */
    static final Field CUSTOMER_LAST_NAME = new Field(60, 25);

    /** CUST-ADDR-LINE-1, {@code PIC X(50)} at CVCUS01Y line 9. */
    static final Field CUSTOMER_ADDRESS_LINE_1 = new Field(85, 50);

    /** CUST-ADDR-LINE-2, {@code PIC X(50)} at CVCUS01Y line 10. */
    static final Field CUSTOMER_ADDRESS_LINE_2 = new Field(135, 50);

    /** CUST-ADDR-LINE-3, {@code PIC X(50)} at CVCUS01Y line 11. */
    static final Field CUSTOMER_ADDRESS_LINE_3 = new Field(185, 50);

    /** CUST-ADDR-STATE-CD, {@code PIC X(02)} at CVCUS01Y line 12. */
    static final Field CUSTOMER_STATE_CODE = new Field(235, 2);

    /** CUST-ADDR-COUNTRY-CD, {@code PIC X(03)} at CVCUS01Y line 13. */
    static final Field CUSTOMER_COUNTRY_CODE = new Field(237, 3);

    /** CUST-ADDR-ZIP, {@code PIC X(10)} at CVCUS01Y line 14. */
    static final Field CUSTOMER_ADDRESS_ZIP = new Field(240, 10);

    /** CUST-PHONE-NUM-1, {@code PIC X(15)} at CVCUS01Y line 15. */
    static final Field CUSTOMER_PHONE_NUMBER_1 = new Field(250, 15);

    /** CUST-PHONE-NUM-2, {@code PIC X(15)} at CVCUS01Y line 16. */
    static final Field CUSTOMER_PHONE_NUMBER_2 = new Field(265, 15);

    /** CUST-SSN, the Social Security Number, {@code PIC 9(09)} at CVCUS01Y line 17. */
    static final Field CUSTOMER_SOCIAL_SECURITY_NUMBER = new Field(280, 9);

    /** CUST-GOVT-ISSUED-ID, {@code PIC X(20)} at CVCUS01Y line 18. */
    static final Field CUSTOMER_GOVERNMENT_ISSUED_ID = new Field(289, 20);

    /** CUST-DOB-YYYY-MM-DD, {@code PIC X(10)} at CVCUS01Y line 19. */
    static final Field CUSTOMER_DATE_OF_BIRTH = new Field(309, 10);

    /** CUST-EFT-ACCOUNT-ID, the electronic funds transfer account, {@code PIC X(10)} at line 20. */
    static final Field CUSTOMER_EFT_ACCOUNT_ID = new Field(319, 10);

    /** CUST-PRI-CARD-HOLDER-IND, {@code PIC X(01)} at CVCUS01Y line 21. */
    static final Field CUSTOMER_PRIMARY_HOLDER_INDICATOR = new Field(329, 1);

    /** CUST-FICO-CREDIT-SCORE, {@code PIC 9(03)} at CVCUS01Y line 22. */
    static final Field CUSTOMER_CREDIT_SCORE = new Field(330, 3);

    /** FILLER, {@code PIC X(168)} at CVCUS01Y line 23. */
    static final Field CUSTOMER_FILLER = new Field(333, 168);

    // Disclosure group record fields, from app/cpy/CVTRA02Y.cpy lines 6 through 10.
    // Mapped fields span positions 1 through 22. Filler spans 23 through 50.

    /** DIS-ACCT-GROUP-ID, {@code PIC X(10)} at CVTRA02Y line 6. */
    static final Field DISCLOSURE_GROUP_ID = new Field(1, 10);

    /** DIS-TRAN-TYPE-CD, {@code PIC X(02)} at CVTRA02Y line 7. */
    static final Field DISCLOSURE_TRANSACTION_TYPE_CODE = new Field(11, 2);

    /** DIS-TRAN-CAT-CD, {@code PIC 9(04)} at CVTRA02Y line 8. */
    static final Field DISCLOSURE_TRANSACTION_CATEGORY_CODE = new Field(13, 4);

    /** DIS-INT-RATE, {@code PIC S9(04)V99} at CVTRA02Y line 9. */
    static final Field DISCLOSURE_INTEREST_RATE = new Field(17, INTEREST_RATE_FIELD_LENGTH);

    /** FILLER, {@code PIC X(28)} at CVTRA02Y line 10. */
    static final Field DISCLOSURE_GROUP_FILLER = new Field(23, 28);

    // Internal constants: the repository marker, the fixture location, and the sign bytes.

    /** Name of the directory that marks the repository root. */
    private static final String MARKER_DIRECTORY = "app";

    /** First path element below the marker directory. */
    private static final String FIXTURE_PARENT_DIRECTORY = "data";

    /** Second path element below the marker directory, holding the text fixtures. */
    private static final String FIXTURE_LEAF_DIRECTORY = "ASCII";

    /** Trailing byte carrying digit zero and a positive sign. */
    private static final char POSITIVE_ZERO_SIGN_BYTE = '{';

    /** Trailing byte carrying digit zero and a negative sign. */
    private static final char NEGATIVE_ZERO_SIGN_BYTE = '}';

    /** First trailing byte of the positive range, carrying digit one. */
    private static final char POSITIVE_RANGE_FIRST = 'A';

    /** Last trailing byte of the positive range, carrying digit nine. */
    private static final char POSITIVE_RANGE_LAST = 'I';

    /** First trailing byte of the negative range, carrying digit one. */
    private static final char NEGATIVE_RANGE_FIRST = 'J';

    /** Last trailing byte of the negative range, carrying digit nine. */
    private static final char NEGATIVE_RANGE_LAST = 'R';

    /** Carriage return, stripped from the end of a record when one is present. */
    private static final char CARRIAGE_RETURN = '\r';

    /** Holds static operations only. */
    private AsciiFixtureReader() {
    }

    // Field addressing.

    /**
     * One field of a fixed-width record, addressed by a one-based start position and a length.
     *
     * <p>The one-based start matches the column position a copybook field occupies, so a reader
     * comparing a constant against a copybook compares two identical numbers.</p>
     *
     * @param start  one-based position of the first character the field occupies, 1 or greater
     * @param length count of characters the field occupies, 1 or greater
     */
    record Field(int start, int length) {

        /**
         * Rejects a start position or a length below 1.
         *
         * @throws IllegalArgumentException if either component falls below 1
         */
        Field {
            if (start < 1) {
                throw new IllegalArgumentException(
                        "Field start position must be 1 or greater, and %d was given"
                                .formatted(start));
            }
            if (length < 1) {
                throw new IllegalArgumentException(
                        "Field length must be 1 or greater, and %d was given".formatted(length));
            }
        }

        /**
         * Returns the one-based position of the last character the field occupies.
         *
         * @return the one-based end position, inclusive
         */
        int endInclusive() {
            return start + length - 1;
        }
    }

    // Fixture location.

    /**
     * Resolves the repository root by walking upward from the current working directory.
     *
     * <p>The walk tests every directory on the path for a child directory named {@code app} that
     * itself holds {@code data/ASCII}. Only the repository root passes that test. The walk starts
     * at the current working directory and stops at the filesystem root.</p>
     *
     * <p>Maven sets the working directory to the module directory for some invocations and to the
     * aggregator directory for others. The upward walk finds the same fixture directory from
     * either starting point.</p>
     *
     * @return absolute, normalised path of the repository root
     * @throws IllegalStateException if the walk reaches the filesystem root with no match
     */
    static Path repositoryRoot() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(fixtureDirectoryUnder(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                ("Walked upward from %s to the filesystem root without finding a directory named "
                        + "'%s' that holds '%s/%s'. Run the tests from inside the repository.")
                        .formatted(start, MARKER_DIRECTORY, FIXTURE_PARENT_DIRECTORY,
                                FIXTURE_LEAF_DIRECTORY));
    }

    /**
     * Resolves the directory holding the three text fixtures.
     *
     * @return absolute path of {@code app/data/ASCII} under the repository root
     * @throws IllegalStateException if the repository root does not resolve
     */
    static Path fixtureDirectory() {
        return fixtureDirectoryUnder(repositoryRoot());
    }

    /**
     * Builds the fixture path a candidate directory would hold if the candidate were the root.
     *
     * @param candidate directory to test
     * @return the path {@code candidate/app/data/ASCII}, which need not exist
     */
    private static Path fixtureDirectoryUnder(Path candidate) {
        return candidate.resolve(MARKER_DIRECTORY)
                .resolve(FIXTURE_PARENT_DIRECTORY)
                .resolve(FIXTURE_LEAF_DIRECTORY);
    }

    // Reading records.

    /**
     * Reads one fixture and returns its records, checking the width of every record.
     *
     * <p>The method opens the file for reading with the ASCII charset and never writes to it. It
     * strips a trailing carriage return from each record when one is present, then compares the
     * remaining length against {@code expectedRecordLength}. A mismatch throws, naming the record
     * number and both lengths.</p>
     *
     * @param fixtureFileName      file name inside {@code app/data/ASCII}, such as
     *                             {@link #ACCOUNT_FIXTURE}
     * @param expectedRecordLength width every record must span, 1 or greater
     * @return an unmodifiable list of records in file order, each of the expected width
     * @throws IllegalArgumentException if the name is null or blank, or the width is below 1
     * @throws IllegalStateException    if the file is absent, or a record misses the width
     * @throws UncheckedIOException     if reading the file fails
     */
    static List<String> readRecords(String fixtureFileName, int expectedRecordLength) {
        if (fixtureFileName == null || fixtureFileName.isBlank()) {
            throw new IllegalArgumentException("Fixture file name must be supplied");
        }
        if (expectedRecordLength < 1) {
            throw new IllegalArgumentException(
                    "Expected record length must be 1 or greater, and %d was given for %s"
                            .formatted(expectedRecordLength, fixtureFileName));
        }

        Path fixture = fixtureDirectory().resolve(fixtureFileName);
        if (!Files.isRegularFile(fixture)) {
            throw new IllegalStateException(
                    "Fixture %s is not a readable file".formatted(fixture));
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(fixture, StandardCharsets.US_ASCII);
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading fixture %s failed".formatted(fixture), failure);
        }

        List<String> records = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            String record = stripTrailingCarriageReturn(lines.get(index));
            if (record.length() != expectedRecordLength) {
                throw new IllegalStateException(
                        "Record %d of %s spans %d characters, and the declared width is %d"
                                .formatted(index + 1, fixtureFileName, record.length(),
                                        expectedRecordLength));
            }
            records.add(record);
        }
        return List.copyOf(records);
    }

    /**
     * Reads {@code app/data/ASCII/acctdata.txt} at the width CVACT01Y declares.
     *
     * @return the account records, each spanning {@link #ACCOUNT_RECORD_LENGTH} characters
     */
    static List<String> readAccountRecords() {
        return readRecords(ACCOUNT_FIXTURE, ACCOUNT_RECORD_LENGTH);
    }

    /**
     * Reads {@code app/data/ASCII/custdata.txt} at the width CVCUS01Y declares.
     *
     * @return the customer records, each spanning {@link #CUSTOMER_RECORD_LENGTH} characters
     */
    static List<String> readCustomerRecords() {
        return readRecords(CUSTOMER_FIXTURE, CUSTOMER_RECORD_LENGTH);
    }

    /**
     * Reads {@code app/data/ASCII/discgrp.txt} at the width CVTRA02Y declares.
     *
     * @return the disclosure group records, each spanning
     *         {@link #DISCLOSURE_GROUP_RECORD_LENGTH} characters
     */
    static List<String> readDisclosureGroupRecords() {
        return readRecords(DISCLOSURE_GROUP_FIXTURE, DISCLOSURE_GROUP_RECORD_LENGTH);
    }

    /**
     * Removes one trailing carriage return from a record when the record ends with one.
     *
     * @param record record text as read from the file
     * @return the record without a trailing carriage return
     */
    private static String stripTrailingCarriageReturn(String record) {
        int length = record.length();
        if (length > 0 && record.charAt(length - 1) == CARRIAGE_RETURN) {
            return record.substring(0, length - 1);
        }
        return record;
    }

    // Slicing fields out of a record.

    /**
     * Slices one field out of a record using the one-based positions the field declares.
     *
     * <p>The caller passes a {@link Field} constant, so no caller converts a one-based copybook
     * position into a zero-based string index.</p>
     *
     * @param record record text at its full declared width
     * @param field  field to slice
     * @return the field text, including any leading or trailing spaces the record holds
     * @throws IllegalArgumentException if either argument is null
     * @throws IllegalStateException    if the record is too short to hold the field
     */
    static String field(String record, Field field) {
        if (record == null) {
            throw new IllegalArgumentException("Record must be supplied");
        }
        if (field == null) {
            throw new IllegalArgumentException("Field must be supplied");
        }
        if (record.length() < field.endInclusive()) {
            throw new IllegalStateException(
                    ("Field spanning positions %d to %d does not fit a record of %d characters")
                            .formatted(field.start(), field.endInclusive(), record.length()));
        }
        return record.substring(field.start() - 1, field.endInclusive());
    }

    /**
     * Slices one field out of a record and removes its leading and trailing spaces.
     *
     * <p>A {@code PIC X(n)} field pads unused positions with spaces, so a text field read from a
     * fixture usually carries them.</p>
     *
     * @param record record text at its full declared width
     * @param field  field to slice
     * @return the field text with surrounding spaces removed
     * @throws IllegalArgumentException if either argument is null
     * @throws IllegalStateException    if the record is too short to hold the field
     */
    static String trimmedField(String record, Field field) {
        return field(record, field).strip();
    }

    /**
     * Slices one signed display field out of a record and decodes it at the given scale.
     *
     * @param record record text at its full declared width
     * @param field  field to slice, such as {@link #ACCOUNT_CURRENT_BALANCE}
     * @param scale  digits to place after the decimal point, zero or greater
     * @return the decoded value, carrying exactly {@code scale} digits after the decimal point
     * @throws IllegalArgumentException if an argument is null, the scale is negative, or the field
     *                                  text is not a signed display field
     * @throws IllegalStateException    if the record is too short to hold the field
     */
    static BigDecimal decimalField(String record, Field field, int scale) {
        return decodeZonedDecimal(field(record, field), scale);
    }

    // Decoding a signed display field.

    /**
     * Decodes one signed COBOL display field into a {@link BigDecimal} at the given scale.
     *
     * <p>A signed display field stores its sign in the trailing byte together with the last digit.
     * The table below lists every trailing byte the method accepts.</p>
     *
     * <table>
     *   <caption>Overpunched sign bytes, and the digit and sign each byte carries</caption>
     *   <tr><th>Trailing byte</th><th>Digit</th><th>Sign</th></tr>
     *   <tr><td><code>&#123;</code></td><td>0</td><td>positive</td></tr>
     *   <tr><td><code>A</code> through <code>I</code></td><td>1 through 9</td>
     *       <td>positive</td></tr>
     *   <tr><td><code>&#125;</code></td><td>0</td><td>negative</td></tr>
     *   <tr><td><code>J</code> through <code>R</code></td><td>1 through 9</td>
     *       <td>negative</td></tr>
     * </table>
     *
     * <p>The method substitutes the digit the trailing byte carries, prefixes a minus sign for the
     * negative bytes, then places the decimal point at the requested scale. It sets the scale
     * exactly, so it drops no digit and applies no rounding mode.</p>
     *
     * <p>Decoding the twelve-byte field <code>00000001940{</code> at scale 2 reads ten integer
     * digits and two decimals. The trailing byte contributes a zero, so the digit string reads
     * {@code 000000019400} and the value is {@code 194.00}.</p>
     *
     * <p>An unrecognised trailing byte throws. The method never falls back to a plain numeric
     * parse.</p>
     *
     * @param fieldText raw field text, which may carry surrounding spaces
     * @param scale     digits to place after the decimal point, zero or greater
     * @return the decoded value, carrying exactly {@code scale} digits after the decimal point
     * @throws IllegalArgumentException if {@code fieldText} is null or blank, if {@code scale} is
     *                                  negative, if a position ahead of the trailing byte holds a
     *                                  non-digit, or if the trailing byte carries no overpunch
     */
    static BigDecimal decodeZonedDecimal(String fieldText, int scale) {
        if (fieldText == null) {
            throw new IllegalArgumentException("Field text must be supplied");
        }
        if (scale < 0) {
            throw new IllegalArgumentException(
                    "Scale must be zero or greater, and %d was given".formatted(scale));
        }

        String digitsWithSignByte = fieldText.strip();
        if (digitsWithSignByte.isEmpty()) {
            throw new IllegalArgumentException(
                    "Field text holds no digits and no sign byte");
        }

        int signBytePosition = digitsWithSignByte.length() - 1;
        String leadingDigits = digitsWithSignByte.substring(0, signBytePosition);
        requireDigits(leadingDigits, fieldText.length());

        SignedDigit signedDigit =
                decodeSignByte(digitsWithSignByte.charAt(signBytePosition), fieldText.length());

        String unscaledText = (signedDigit.negative() ? "-" : "")
                + leadingDigits
                + signedDigit.digit();
        return new BigDecimal(new BigInteger(unscaledText), scale);
    }

    /**
     * Checks that every character of the leading run is an ASCII digit.
     *
     * <p>The failure text names the position and the field width. It carries neither the field
     * text nor the character it refused, so a fixture value cannot reach a build log through this
     * path.</p>
     *
     * @param leadingDigits characters ahead of the trailing sign byte
     * @param fieldWidth    width of the field, reported in the failure message
     * @throws IllegalArgumentException if any character is not a digit from 0 through 9
     */
    private static void requireDigits(String leadingDigits, int fieldWidth) {
        for (int index = 0; index < leadingDigits.length(); index++) {
            char character = leadingDigits.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(
                        ("Position %d of a signed display field of %d characters holds a character "
                                + "that is not a digit. Every position ahead of the trailing sign "
                                + "byte must hold a digit from 0 through 9. The field text is "
                                + "withheld from this message.")
                                .formatted(index + 1, fieldWidth));
            }
        }
    }

    /**
     * Decodes the trailing sign byte into the digit it carries and the sign it sets.
     *
     * <p>The failure text names the field width and the accepted byte sets. It carries neither the
     * field text nor the byte it refused, so a fixture value cannot reach a build log through this
     * path.</p>
     *
     * @param signByte   trailing byte of the field
     * @param fieldWidth width of the field, reported in the failure message
     * @return the digit and the sign the trailing byte carries
     * @throws IllegalArgumentException if the byte is outside the four accepted sets
     */
    private static SignedDigit decodeSignByte(char signByte, int fieldWidth) {
        if (signByte == POSITIVE_ZERO_SIGN_BYTE) {
            return new SignedDigit(0, false);
        }
        if (signByte == NEGATIVE_ZERO_SIGN_BYTE) {
            return new SignedDigit(0, true);
        }
        if (signByte >= POSITIVE_RANGE_FIRST && signByte <= POSITIVE_RANGE_LAST) {
            return new SignedDigit(signByte - POSITIVE_RANGE_FIRST + 1, false);
        }
        if (signByte >= NEGATIVE_RANGE_FIRST && signByte <= NEGATIVE_RANGE_LAST) {
            return new SignedDigit(signByte - NEGATIVE_RANGE_FIRST + 1, true);
        }
        throw new IllegalArgumentException(
                ("The trailing byte of a signed display field of %d characters carries no "
                        + "overpunched sign. The accepted bytes are '%s', '%s' through '%s', "
                        + "'%s', and '%s' through '%s'. The field text and the refused byte are "
                        + "withheld from this message.")
                        .formatted(fieldWidth, POSITIVE_ZERO_SIGN_BYTE,
                                POSITIVE_RANGE_FIRST, POSITIVE_RANGE_LAST, NEGATIVE_ZERO_SIGN_BYTE,
                                NEGATIVE_RANGE_FIRST, NEGATIVE_RANGE_LAST));
    }

    /**
     * The digit and the sign one overpunched trailing byte carries.
     *
     * @param digit    digit from 0 through 9 the trailing byte carries
     * @param negative true when the trailing byte sets a negative sign
     */
    private record SignedDigit(int digit, boolean negative) {
    }
}
