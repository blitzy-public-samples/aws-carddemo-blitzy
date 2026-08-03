package com.carddemo.account.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.carddemo.account.repository.AsciiFixtureReader.Field;

import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_ACTIVE_STATUS;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_ADDRESS_ZIP;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_CASH_CREDIT_LIMIT;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_CREDIT_LIMIT;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_CURRENT_BALANCE;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_CURRENT_CYCLE_CREDIT;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_CURRENT_CYCLE_DEBIT;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_EXPIRATION_DATE;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_FILLER;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_FIXTURE;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_GROUP_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_MONEY_FIELDS;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_OPEN_DATE;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_RECORD_COUNT;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_RECORD_LENGTH;
import static com.carddemo.account.repository.AsciiFixtureReader.ACCOUNT_REISSUE_DATE;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_ADDRESS_LINE_1;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_ADDRESS_LINE_2;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_ADDRESS_LINE_3;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_ADDRESS_ZIP;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_COUNTRY_CODE;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_CREDIT_SCORE;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_DATE_OF_BIRTH;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_EFT_ACCOUNT_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_FILLER;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_FIRST_NAME;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_GOVERNMENT_ISSUED_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_LAST_NAME;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_MIDDLE_NAME;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_PHONE_NUMBER_1;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_PHONE_NUMBER_2;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_PRIMARY_HOLDER_INDICATOR;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_RECORD_COUNT;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_RECORD_LENGTH;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_SOCIAL_SECURITY_NUMBER;
import static com.carddemo.account.repository.AsciiFixtureReader.CUSTOMER_STATE_CODE;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_GROUP_FILLER;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_GROUP_ID;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_GROUP_RECORD_COUNT;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_GROUP_RECORD_LENGTH;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_INTEREST_RATE;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_TRANSACTION_CATEGORY_CODE;
import static com.carddemo.account.repository.AsciiFixtureReader.DISCLOSURE_TRANSACTION_TYPE_CODE;
import static com.carddemo.account.repository.AsciiFixtureReader.INTEREST_RATE_FIELD_LENGTH;
import static com.carddemo.account.repository.AsciiFixtureReader.INTEREST_RATE_SCALE;
import static com.carddemo.account.repository.AsciiFixtureReader.MONEY_FIELD_LENGTH;
import static com.carddemo.account.repository.AsciiFixtureReader.MONEY_SCALE;
import static com.carddemo.account.repository.AsciiFixtureReader.decimalField;
import static com.carddemo.account.repository.AsciiFixtureReader.decodeZonedDecimal;
import static com.carddemo.account.repository.AsciiFixtureReader.field;
import static com.carddemo.account.repository.AsciiFixtureReader.readAccountRecords;
import static com.carddemo.account.repository.AsciiFixtureReader.readCustomerRecords;
import static com.carddemo.account.repository.AsciiFixtureReader.readDisclosureGroupRecords;
import static com.carddemo.account.repository.AsciiFixtureReader.readRecords;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link AsciiFixtureReader} over the three fixtures the account service binds to.
 *
 * <p>Every assertion below calls {@code AsciiFixtureReader}. The class opens no database
 * connection, starts no container and loads no Spring context. It runs from a fresh checkout with
 * nothing prepared.</p>
 *
 * <p>A money field in these fixtures is a zoned decimal: one byte per digit, with the sign folded
 * into the trailing byte. That fold is a sign overpunch, and it turns the trailing byte into a
 * brace or a letter. A {@code PIC S9(10)V99} field spans twelve bytes, and a {@code PIC S9(09)V99}
 * field spans eleven.</p>
 *
 * <p>The methods here assert fixture bytes. {@code AccountRepositoryTest} asserts the rows the
 * seed migration loads. The pair together show the seed reproduces the fixture, and neither
 * repeats the other.</p>
 *
 * <p>Fifty account records hold five money fields each, and all two hundred and fifty trailing
 * bytes are the positive brace.
 * {@link #negativeOverpunchBytesDecodeToTheirNegatedDigit(char, int)} covers the negative half of
 * the table from field text built in memory.</p>
 *
 * <p>Every expected value below names the copybook line and the fixture positions it comes from.
 * The record-layout mapping and the field renames sit in
 * card-platform/docs/traceability-matrix.md, and the entity shapes drawn from the same copybooks
 * sit in card-platform/docs/data-model.md.</p>
 *
 * <p>Decision record: card-platform/docs/decision-log.md.</p>
 */
@DisplayName("AsciiFixtureReader over the account, customer and disclosure group fixtures")
class ZonedDecimalFixtureDecodingTest {

    // Fixture inventory, counted and measured on app/data/ASCII.

    /** Records counted in app/data/ASCII/acctdata.txt. */
    private static final int MEASURED_ACCOUNT_RECORDS = 50;

    /** Characters every record of app/data/ASCII/acctdata.txt spans, RECLN 300 in CVACT01Y. */
    private static final int MEASURED_ACCOUNT_WIDTH = 300;

    /** Records counted in app/data/ASCII/custdata.txt. */
    private static final int MEASURED_CUSTOMER_RECORDS = 50;

    /** Characters every record of app/data/ASCII/custdata.txt spans, RECLN 500 in CVCUS01Y. */
    private static final int MEASURED_CUSTOMER_WIDTH = 500;

    /** Records counted in app/data/ASCII/discgrp.txt. */
    private static final int MEASURED_DISCLOSURE_GROUP_RECORDS = 51;

    /** Characters every record of app/data/ASCII/discgrp.txt spans, RECLN 50 in CVTRA02Y. */
    private static final int MEASURED_DISCLOSURE_GROUP_WIDTH = 50;

    /** Index of the first record a fixture returns. */
    private static final int FIRST_RECORD = 0;

    // Overpunched trailing bytes named individually for the worked cases.

    /** Trailing byte carrying digit zero and a positive sign. */
    private static final char POSITIVE_ZERO_BYTE = '{';

    /** Trailing byte carrying digit zero and a negative sign. */
    private static final char NEGATIVE_ZERO_BYTE = '}';

    /** Trailing byte carrying digit one and a positive sign. */
    private static final char POSITIVE_ONE_BYTE = 'A';

    /** Trailing byte carrying digit one and a negative sign. */
    private static final char NEGATIVE_ONE_BYTE = 'J';

    /** Trailing byte outside the four sets the decoder accepts. */
    private static final char UNRECOGNISED_SIGN_BYTE = 'S';

    // Signed display field text built in memory, so no fixture carries a malformed record.

    /**
     * Eleven zeros. The prefix plus one trailing byte spans
     * {@link AsciiFixtureReader#MONEY_FIELD_LENGTH} characters.
     */
    private static final String MONEY_ZERO_PREFIX = "00000000000";

    /** Ten zeros. The prefix plus one trailing byte spans {@link #NARROW_MONEY_FIELD_LENGTH}. */
    private static final String NARROW_MONEY_ZERO_PREFIX = "0000000000";

    /** The eleven digits ahead of the trailing byte of ACCT-CURR-BAL, positions 13 through 23. */
    private static final String WORKED_MONEY_DIGITS = "00000001940";

    /** The ten digits ahead of the trailing byte of an eleven-character signed display field. */
    private static final String NARROW_WORKED_MONEY_DIGITS = "0000001940";

    /**
     * Characters a {@code PIC S9(09)V99} field spans: nine integer digits plus two decimals.
     * WS-TEMP-BAL carries that Picture clause at app/cbl/CBTRN02C.cbl:L187.
     */
    private static final int NARROW_MONEY_FIELD_LENGTH = 11;

    /** Field text holding a non-digit ahead of the trailing sign byte. */
    private static final String NON_DIGIT_MONEY_FIELD = "0000000X940{";

    /** Scale below zero, which the decoder rejects. */
    private static final int NEGATIVE_SCALE = -1;

    /** Scales the no-rounding check applies to one digit string. */
    private static final List<Integer> SCALES_UNDER_TEST = List.of(0, 2, 4);

    /** Digits WORKED_MONEY_DIGITS carries once a trailing byte contributes zero. */
    private static final BigInteger WORKED_DIGITS_WITH_TRAILING_ZERO = new BigInteger("000000019400");

    /** Digits WORKED_MONEY_DIGITS carries once a trailing byte contributes one. */
    private static final BigInteger WORKED_DIGITS_WITH_TRAILING_ONE = new BigInteger("000000019401");

    // Account record 1, from app/data/ASCII/acctdata.txt and app/cpy/CVACT01Y.cpy.

    /** ACCT-ID at CVACT01Y line 5, positions 1 through 11 of record 1. */
    private static final String RECORD_1_ACCOUNT_ID = "00000000001";

    /** ACCT-ACTIVE-STATUS at CVACT01Y line 6, position 12 of record 1. */
    private static final String RECORD_1_ACTIVE_STATUS = "Y";

    /** ACCT-CURR-BAL at CVACT01Y line 7, positions 13 through 24 of record 1. */
    private static final BigDecimal RECORD_1_CURRENT_BALANCE = new BigDecimal("194.00");

    /**
     * ACCT-CURR-BAL of record 1, positions 13 through 24, with the trailing byte switched to a
     * negative sign.
     */
    private static final BigDecimal NEGATED_RECORD_1_BALANCE = new BigDecimal("-194.00");

    /**
     * ACCT-CURR-BAL of record 1, positions 13 through 24, with the trailing byte switched to a
     * positive one.
     */
    private static final BigDecimal RECORD_1_BALANCE_WITH_TRAILING_ONE = new BigDecimal("194.01");

    /**
     * ACCT-CURR-BAL of record 1, positions 13 through 24, with the trailing byte switched to a
     * negative one.
     */
    private static final BigDecimal NEGATED_BALANCE_WITH_TRAILING_ONE = new BigDecimal("-194.01");

    /**
     * ACCT-CURR-BAL of record 1, positions 13 through 24, written with no decimals.
     * BigDecimal.equals separates that value from the same value at scale 2.
     */
    private static final BigDecimal RECORD_1_BALANCE_AT_SCALE_ZERO = new BigDecimal("194");

    /** ACCT-CREDIT-LIMIT at CVACT01Y line 8, positions 25 through 36 of record 1. */
    private static final BigDecimal RECORD_1_CREDIT_LIMIT = new BigDecimal("2020.00");

    /** ACCT-CASH-CREDIT-LIMIT at CVACT01Y line 9, positions 37 through 48 of record 1. */
    private static final BigDecimal RECORD_1_CASH_CREDIT_LIMIT = new BigDecimal("1020.00");

    /** ACCT-OPEN-DATE at CVACT01Y line 10, positions 49 through 58 of record 1. */
    private static final String RECORD_1_OPEN_DATE = "2014-11-20";

    /**
     * ACCT-EXPIRAION-DATE at CVACT01Y line 11, positions 59 through 68 of record 1. The field
     * carries hyphens and spans ten characters. app/cbl/CBTRN02C.cbl:L414 compares it as text
     * against the first ten characters of DALYTRAN-ORIG-TS, and the migrated column holds ten
     * characters of text. The copybook spells the name with a transposed word, and the constant
     * name below corrects the spelling.
     */
    private static final String RECORD_1_EXPIRATION_DATE = "2025-05-20";

    /** ACCT-REISSUE-DATE at CVACT01Y line 12, positions 69 through 78 of record 1. */
    private static final String RECORD_1_REISSUE_DATE = "2025-05-20";

    /**
     * Value ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT both carry in record 1, at CVACT01Y
     * lines 13 and 14, positions 79 through 90 and 91 through 102.
     */
    private static final BigDecimal ZERO_AT_MONEY_SCALE = new BigDecimal("0.00");

    /** ACCT-ADDR-ZIP at CVACT01Y line 15, positions 103 through 112 of every account record. */
    private static final String ACCOUNT_ADDRESS_ZIP_VALUE = "A000000000";

    /**
     * One-based positions the trailing byte occupies in the five account money fields, at CVACT01Y
     * lines 7, 8, 9, 13 and 14.
     */
    private static final List<Integer> ACCOUNT_SIGN_BYTE_POSITIONS = List.of(24, 36, 48, 90, 102);

    /**
     * Trailing bytes the five money fields of fifty account records hold together: fifty records
     * at five fields each.
     */
    private static final int ACCOUNT_SIGN_BYTE_TOTAL = 250;

    // Customer record 1, from app/data/ASCII/custdata.txt and app/cpy/CVCUS01Y.cpy.

    /** CUST-ID at CVCUS01Y line 5, positions 1 through 9 of record 1. */
    private static final String RECORD_1_CUSTOMER_ID = "000000001";

    /** CUST-FIRST-NAME at CVCUS01Y line 6, positions 10 through 34 of record 1. */
    private static final String RECORD_1_FIRST_NAME = "Immanuel";

    /** CUST-MIDDLE-NAME at CVCUS01Y line 7, positions 35 through 59 of record 1. */
    private static final String RECORD_1_MIDDLE_NAME = "Madeline";

    /** CUST-LAST-NAME at CVCUS01Y line 8, positions 60 through 84 of record 1. */
    private static final String RECORD_1_LAST_NAME = "Kessler";

    /** CUST-ADDR-LINE-1 at CVCUS01Y line 9, positions 85 through 134 of record 1. */
    private static final String RECORD_1_ADDRESS_LINE_1 = "618 Deshaun Route";

    /** CUST-ADDR-LINE-2 at CVCUS01Y line 10, positions 135 through 184 of record 1. */
    private static final String RECORD_1_ADDRESS_LINE_2 = "Apt. 802";

    /** CUST-ADDR-LINE-3 at CVCUS01Y line 11, positions 185 through 234 of record 1. */
    private static final String RECORD_1_ADDRESS_LINE_3 = "Altenwerthshire";

    /** CUST-ADDR-STATE-CD at CVCUS01Y line 12, positions 235 and 236 of record 1. */
    private static final String RECORD_1_STATE_CODE = "NC";

    /** CUST-ADDR-COUNTRY-CD at CVCUS01Y line 13, positions 237 through 239 of record 1. */
    private static final String RECORD_1_COUNTRY_CODE = "USA";

    /** CUST-ADDR-ZIP at CVCUS01Y line 14, positions 240 through 249 of record 1. */
    private static final String RECORD_1_CUSTOMER_ZIP = "12546";

    /** CUST-PHONE-NUM-1 at CVCUS01Y line 15, positions 250 through 264 of record 1. */
    private static final String RECORD_1_PHONE_NUMBER_1 = "(908)119-8310";

    /** CUST-PHONE-NUM-2 at CVCUS01Y line 16, positions 265 through 279 of record 1. */
    private static final String RECORD_1_PHONE_NUMBER_2 = "(373)693-8684";

    /**
     * CUST-SSN, the Social Security Number, at CVCUS01Y line 17, positions 280 through 288 of
     * record 1. The fixture stores nine digits and no separator.
     */
    private static final String RECORD_1_SOCIAL_SECURITY_NUMBER = "020973888";

    /** CUST-GOVT-ISSUED-ID at CVCUS01Y line 18, positions 289 through 308 of record 1. */
    private static final String RECORD_1_GOVERNMENT_ISSUED_ID = "00000000000049368437";

    /**
     * CUST-DOB-YYYY-MM-DD at CVCUS01Y line 19, positions 309 through 318 of record 1. The field
     * carries hyphens, matching the three account date fields.
     */
    private static final String RECORD_1_DATE_OF_BIRTH = "1961-06-08";

    /**
     * CUST-EFT-ACCOUNT-ID, the electronic funds transfer account, at CVCUS01Y line 20, positions
     * 319 through 328 of record 1.
     */
    private static final String RECORD_1_EFT_ACCOUNT_ID = "0053581756";

    /** CUST-PRI-CARD-HOLDER-IND at CVCUS01Y line 21, position 329 of record 1. */
    private static final String RECORD_1_PRIMARY_HOLDER_INDICATOR = "Y";

    /** CUST-FICO-CREDIT-SCORE at CVCUS01Y line 22, positions 330 through 332 of record 1. */
    private static final String RECORD_1_CREDIT_SCORE = "274";

    // Disclosure group record 1, from app/data/ASCII/discgrp.txt and app/cpy/CVTRA02Y.cpy.

    /** DIS-ACCT-GROUP-ID at CVTRA02Y line 6, positions 1 through 10 of record 1. */
    private static final String RECORD_1_DISCLOSURE_GROUP_ID = "A000000000";

    /** DIS-TRAN-TYPE-CD at CVTRA02Y line 7, positions 11 and 12 of record 1. */
    private static final String RECORD_1_TRANSACTION_TYPE_CODE = "01";

    /** DIS-TRAN-CAT-CD at CVTRA02Y line 8, positions 13 through 16 of record 1. */
    private static final String RECORD_1_TRANSACTION_CATEGORY_CODE = "0001";

    /** DIS-INT-RATE at CVTRA02Y line 9, positions 17 through 22 of record 1. */
    private static final String RECORD_1_INTEREST_RATE_FIELD = "00150{";

    /**
     * Value DIS-INT-RATE carries in record 1 of app/data/ASCII/discgrp.txt, at CVTRA02Y line 9,
     * positions 17 through 22.
     */
    private static final BigDecimal RECORD_1_INTEREST_RATE = new BigDecimal("15.00");

    /** Characters DIS-GROUP-KEY spans at CVTRA02Y line 5, positions 1 through 16. */
    private static final int DISCLOSURE_GROUP_KEY_LENGTH = 16;

    /** Character the FILLER at CVTRA02Y line 10 repeats in all 51 records. */
    private static final String DISCLOSURE_FILLER_CHARACTER = "0";

    // Helpers shared by the methods below.

    /**
     * Returns {@code value} followed by spaces up to {@code width}.
     *
     * <p>A {@code PIC X(n)} field pads unused positions with spaces, so an expected value needs
     * the same padding to span the same width.</p>
     *
     * @param value text the field holds ahead of its padding
     * @param width characters the field spans
     * @return {@code value} padded to exactly {@code width} characters
     * @throws IllegalArgumentException if {@code value} already spans more than {@code width}
     */
    private static String padded(String value, int width) {
        if (value.length() > width) {
            throw new IllegalArgumentException(
                    "Value spans %d characters and the field width is %d"
                            .formatted(value.length(), width));
        }
        return value + " ".repeat(width - value.length());
    }

    /**
     * Compares one decoded value against an expected value and checks its scale.
     *
     * <p>{@code BigDecimal.equals} separates 194.00 from 194, and the scale carries its own
     * check.</p>
     *
     * @param decoded  value the decoder returned
     * @param expected value the fixture or the overpunch table declares
     * @param scale    digits the decoded value carries after the decimal point
     */
    private static void assertValueAndScale(BigDecimal decoded, BigDecimal expected, int scale) {
        assertThat(decoded).isEqualByComparingTo(expected);
        assertThat(decoded.scale()).isEqualTo(scale);
    }

    // Fixture inventory: record counts and the width of every record.

    @Test
    @DisplayName("acctdata.txt holds 50 records and every record spans 300 characters")
    void accountFixtureHoldsFiftyRecordsOfTheDeclaredWidth() {
        List<String> records = readAccountRecords();

        assertThat(records).hasSize(MEASURED_ACCOUNT_RECORDS);
        assertThat(records)
                .allSatisfy(record -> assertThat(record).hasSize(MEASURED_ACCOUNT_WIDTH));
        assertThat(ACCOUNT_RECORD_COUNT).isEqualTo(MEASURED_ACCOUNT_RECORDS);
        assertThat(ACCOUNT_RECORD_LENGTH).isEqualTo(MEASURED_ACCOUNT_WIDTH);
    }

    @Test
    @DisplayName("custdata.txt holds 50 records and every record spans 500 characters")
    void customerFixtureHoldsFiftyRecordsOfTheDeclaredWidth() {
        List<String> records = readCustomerRecords();

        assertThat(records).hasSize(MEASURED_CUSTOMER_RECORDS);
        assertThat(records)
                .allSatisfy(record -> assertThat(record).hasSize(MEASURED_CUSTOMER_WIDTH));
        assertThat(CUSTOMER_RECORD_COUNT).isEqualTo(MEASURED_CUSTOMER_RECORDS);
        assertThat(CUSTOMER_RECORD_LENGTH).isEqualTo(MEASURED_CUSTOMER_WIDTH);
    }

    @Test
    @DisplayName("discgrp.txt holds 51 records and every record spans 50 characters")
    void disclosureGroupFixtureHoldsFiftyOneRecordsOfTheDeclaredWidth() {
        List<String> records = readDisclosureGroupRecords();

        assertThat(records).hasSize(MEASURED_DISCLOSURE_GROUP_RECORDS);
        assertThat(records)
                .allSatisfy(record -> assertThat(record).hasSize(MEASURED_DISCLOSURE_GROUP_WIDTH));
        assertThat(DISCLOSURE_GROUP_RECORD_COUNT).isEqualTo(MEASURED_DISCLOSURE_GROUP_RECORDS);
        assertThat(DISCLOSURE_GROUP_RECORD_LENGTH).isEqualTo(MEASURED_DISCLOSURE_GROUP_WIDTH);
    }

    // Width checks: the reader names the record and the positions it could not satisfy.

    @Test
    @DisplayName("readRecords names the record and both widths when a record misses the declared width")
    void readRecordsRejectsARecordThatMissesTheDeclaredWidth() {
        int wrongWidth = MEASURED_ACCOUNT_WIDTH - 1;

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> readRecords(ACCOUNT_FIXTURE, wrongWidth))
                .withMessageContaining("Record 1")
                .withMessageContaining(ACCOUNT_FIXTURE)
                .withMessageContaining(String.valueOf(MEASURED_ACCOUNT_WIDTH))
                .withMessageContaining(String.valueOf(wrongWidth));
    }

    @Test
    @DisplayName("field names the positions and the width when a record is too short to hold the field")
    void fieldRejectsARecordTooShortToHoldTheField() {
        String shortRecord = padded("", ACCOUNT_GROUP_ID.start() - 1);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> field(shortRecord, ACCOUNT_GROUP_ID))
                .withMessageContaining(String.valueOf(ACCOUNT_GROUP_ID.start()))
                .withMessageContaining(String.valueOf(ACCOUNT_GROUP_ID.endInclusive()))
                .withMessageContaining(String.valueOf(shortRecord.length()));
    }

    // The overpunch table, both signs, over a twelve-character PIC S9(10)V99 field.

    @ParameterizedTest(name = "trailing byte {0} carries digit {1} and a positive sign")
    @CsvSource(delimiter = '|', value = {
            "{ | 0",
            "A | 1",
            "B | 2",
            "C | 3",
            "D | 4",
            "E | 5",
            "F | 6",
            "G | 7",
            "H | 8",
            "I | 9"
    })
    @DisplayName("every positive overpunch byte decodes to its digit at scale 2")
    void positiveOverpunchBytesDecodeToTheirDigit(char signByte, int digit) {
        String fieldText = MONEY_ZERO_PREFIX + signByte;

        BigDecimal decoded = decodeZonedDecimal(fieldText, MONEY_SCALE);

        assertThat(fieldText).hasSize(MONEY_FIELD_LENGTH);
        assertValueAndScale(decoded, BigDecimal.valueOf(digit, MONEY_SCALE), MONEY_SCALE);
        assertThat(decoded.signum()).isEqualTo(digit == 0 ? 0 : 1);
    }

    @ParameterizedTest(name = "trailing byte {0} carries digit {1} and a negative sign")
    @CsvSource(delimiter = '|', value = {
            "} | 0",
            "J | 1",
            "K | 2",
            "L | 3",
            "M | 4",
            "N | 5",
            "O | 6",
            "P | 7",
            "Q | 8",
            "R | 9"
    })
    @DisplayName("every negative overpunch byte decodes to its negated digit at scale 2")
    void negativeOverpunchBytesDecodeToTheirNegatedDigit(char signByte, int digit) {
        String fieldText = MONEY_ZERO_PREFIX + signByte;

        BigDecimal decoded = decodeZonedDecimal(fieldText, MONEY_SCALE);

        assertThat(fieldText).hasSize(MONEY_FIELD_LENGTH);
        assertValueAndScale(decoded, BigDecimal.valueOf(-digit, MONEY_SCALE), MONEY_SCALE);
        assertThat(decoded.signum()).isEqualTo(digit == 0 ? 0 : -1);
    }

    @Test
    @DisplayName("a field of zeros closed by the negative zero byte decodes to zero at scale 2")
    void negativeZeroByteDecodesToZeroWithoutThrowing() {
        String wideText = MONEY_ZERO_PREFIX + NEGATIVE_ZERO_BYTE;
        String narrowText = NARROW_MONEY_ZERO_PREFIX + NEGATIVE_ZERO_BYTE;

        assertThatCode(() -> decodeZonedDecimal(wideText, MONEY_SCALE)).doesNotThrowAnyException();
        assertThatCode(() -> decodeZonedDecimal(narrowText, MONEY_SCALE)).doesNotThrowAnyException();

        assertThat(wideText).hasSize(MONEY_FIELD_LENGTH);
        assertThat(narrowText).hasSize(NARROW_MONEY_FIELD_LENGTH);
        assertThat(decodeZonedDecimal(wideText, MONEY_SCALE)).isEqualTo(ZERO_AT_MONEY_SCALE);
        assertThat(decodeZonedDecimal(narrowText, MONEY_SCALE)).isEqualTo(ZERO_AT_MONEY_SCALE);
        assertThat(decodeZonedDecimal(wideText, MONEY_SCALE).signum()).isZero();
    }

    // The worked cases, all four sharing the digits of ACCT-CURR-BAL in record 1.

    @Test
    @DisplayName("00000001940 closed by brace or A decodes to 194.00 or 194.01 at scale 2")
    void workedPositiveCasesDecodeToTheRecordOneBalance() {
        String positiveZeroText = WORKED_MONEY_DIGITS + POSITIVE_ZERO_BYTE;

        assertThat(positiveZeroText).hasSize(MONEY_FIELD_LENGTH);
        assertValueAndScale(decodeZonedDecimal(positiveZeroText, MONEY_SCALE),
                RECORD_1_CURRENT_BALANCE, MONEY_SCALE);
        assertValueAndScale(decodeZonedDecimal(WORKED_MONEY_DIGITS + POSITIVE_ONE_BYTE, MONEY_SCALE),
                RECORD_1_BALANCE_WITH_TRAILING_ONE, MONEY_SCALE);

        assertThat(decodeZonedDecimal(positiveZeroText, MONEY_SCALE).unscaledValue())
                .isEqualTo(WORKED_DIGITS_WITH_TRAILING_ZERO);
        assertThat(decodeZonedDecimal(positiveZeroText, MONEY_SCALE))
                .isNotEqualTo(RECORD_1_BALANCE_AT_SCALE_ZERO);
    }

    @Test
    @DisplayName("00000001940 closed by right brace or J decodes to -194.00 or -194.01 at scale 2")
    void workedNegativeCasesDecodeToTheNegatedRecordOneBalance() {
        assertValueAndScale(decodeZonedDecimal(WORKED_MONEY_DIGITS + NEGATIVE_ZERO_BYTE, MONEY_SCALE),
                NEGATED_RECORD_1_BALANCE, MONEY_SCALE);
        assertValueAndScale(decodeZonedDecimal(WORKED_MONEY_DIGITS + NEGATIVE_ONE_BYTE, MONEY_SCALE),
                NEGATED_BALANCE_WITH_TRAILING_ONE, MONEY_SCALE);
    }

    @Test
    @DisplayName("an eleven-character PIC S9(09)V99 field decodes to 194.00 at scale 2")
    void narrowMoneyFieldWidthDecodesAtScaleTwo() {
        String fieldText = NARROW_WORKED_MONEY_DIGITS + POSITIVE_ZERO_BYTE;

        assertThat(fieldText).hasSize(NARROW_MONEY_FIELD_LENGTH);
        assertValueAndScale(decodeZonedDecimal(fieldText, MONEY_SCALE),
                RECORD_1_CURRENT_BALANCE, MONEY_SCALE);
        assertThat(decodeZonedDecimal(fieldText, MONEY_SCALE).unscaledValue())
                .isEqualTo(WORKED_DIGITS_WITH_TRAILING_ZERO);
    }

    @Test
    @DisplayName("the plain BigDecimal constructor rejects the signed display field the decoder accepts")
    void plainBigDecimalConstructorRejectsASignedDisplayField() {
        String fieldText = WORKED_MONEY_DIGITS + POSITIVE_ZERO_BYTE;

        assertThatExceptionOfType(NumberFormatException.class)
                .isThrownBy(() -> new BigDecimal(fieldText));
        assertThatCode(() -> decodeZonedDecimal(fieldText, MONEY_SCALE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("decoding keeps every digit at scale 0, 2 and 4 and drops none")
    void decodingKeepsEveryDigitAtEveryScale() {
        String fieldText = WORKED_MONEY_DIGITS + POSITIVE_ONE_BYTE;

        for (int scale : SCALES_UNDER_TEST) {
            BigDecimal decoded = decodeZonedDecimal(fieldText, scale);

            assertThat(decoded.unscaledValue()).isEqualTo(WORKED_DIGITS_WITH_TRAILING_ONE);
            assertThat(decoded.scale()).isEqualTo(scale);
        }
    }

    @Test
    @DisplayName("the decoder rejects an unrecognised byte, a non-digit, empty text and a negative scale")
    void decoderRejectsMalformedSignedDisplayFields() {
        String blankText = padded("", MONEY_FIELD_LENGTH);
        String unrecognisedText = MONEY_ZERO_PREFIX + UNRECOGNISED_SIGN_BYTE;

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(unrecognisedText, MONEY_SCALE))
                .withMessageContaining(String.valueOf(UNRECOGNISED_SIGN_BYTE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(NON_DIGIT_MONEY_FIELD, MONEY_SCALE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(null, MONEY_SCALE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(blankText, MONEY_SCALE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(WORKED_MONEY_DIGITS + POSITIVE_ZERO_BYTE,
                        NEGATIVE_SCALE));
    }

    // Account fixture, from app/data/ASCII/acctdata.txt laid out by app/cpy/CVACT01Y.cpy.

    @Test
    @DisplayName("record 1 of acctdata.txt carries the identifier, status, three dates and two codes")
    void accountRecordOneTextFieldsMatchTheFixture() {
        String record = readAccountRecords().get(FIRST_RECORD);

        assertThat(field(record, ACCOUNT_ID)).isEqualTo(RECORD_1_ACCOUNT_ID);
        assertThat(field(record, ACCOUNT_ACTIVE_STATUS)).isEqualTo(RECORD_1_ACTIVE_STATUS);
        assertThat(field(record, ACCOUNT_OPEN_DATE)).isEqualTo(RECORD_1_OPEN_DATE);
        assertThat(field(record, ACCOUNT_EXPIRATION_DATE)).isEqualTo(RECORD_1_EXPIRATION_DATE);
        assertThat(field(record, ACCOUNT_REISSUE_DATE)).isEqualTo(RECORD_1_REISSUE_DATE);
        assertThat(field(record, ACCOUNT_ADDRESS_ZIP)).isEqualTo(ACCOUNT_ADDRESS_ZIP_VALUE);
        assertThat(field(record, ACCOUNT_GROUP_ID))
                .isEqualTo(padded("", ACCOUNT_GROUP_ID.length()));
        assertThat(field(record, ACCOUNT_FILLER)).isEqualTo(padded("", ACCOUNT_FILLER.length()));
        assertThat(ACCOUNT_FILLER.endInclusive()).isEqualTo(MEASURED_ACCOUNT_WIDTH);
    }

    @Test
    @DisplayName("record 1 of acctdata.txt decodes to 194.00, 2020.00, 1020.00 and two zero accumulators")
    void accountRecordOneMoneyFieldsDecodeToTheirFixtureValues() {
        String record = readAccountRecords().get(FIRST_RECORD);

        assertValueAndScale(decimalField(record, ACCOUNT_CURRENT_BALANCE, MONEY_SCALE),
                RECORD_1_CURRENT_BALANCE, MONEY_SCALE);
        assertValueAndScale(decimalField(record, ACCOUNT_CREDIT_LIMIT, MONEY_SCALE),
                RECORD_1_CREDIT_LIMIT, MONEY_SCALE);
        assertValueAndScale(decimalField(record, ACCOUNT_CASH_CREDIT_LIMIT, MONEY_SCALE),
                RECORD_1_CASH_CREDIT_LIMIT, MONEY_SCALE);
        assertValueAndScale(decimalField(record, ACCOUNT_CURRENT_CYCLE_CREDIT, MONEY_SCALE),
                ZERO_AT_MONEY_SCALE, MONEY_SCALE);
        assertValueAndScale(decimalField(record, ACCOUNT_CURRENT_CYCLE_DEBIT, MONEY_SCALE),
                ZERO_AT_MONEY_SCALE, MONEY_SCALE);
    }

    @Test
    @DisplayName("ACCT-GROUP-ID is blank in all 50 account records")
    void groupIdentifierIsBlankInEveryAccountRecord() {
        String blankGroupId = padded("", ACCOUNT_GROUP_ID.length());

        assertThat(readAccountRecords()).allSatisfy(record ->
                assertThat(field(record, ACCOUNT_GROUP_ID)).isEqualTo(blankGroupId));
    }

    @Test
    @DisplayName("ACCT-ADDR-ZIP reads A000000000 in all 50 account records")
    void addressZipCarriesTheSameValueInEveryAccountRecord() {
        assertThat(readAccountRecords()).allSatisfy(record ->
                assertThat(field(record, ACCOUNT_ADDRESS_ZIP)).isEqualTo(ACCOUNT_ADDRESS_ZIP_VALUE));
    }

    @Test
    @DisplayName("all 250 account money trailing bytes are the positive zero overpunch")
    void everyAccountMoneyTrailingByteCarriesThePositiveZeroOverpunch() {
        assertThat(ACCOUNT_MONEY_FIELDS.stream().map(Field::endInclusive).toList())
                .isEqualTo(ACCOUNT_SIGN_BYTE_POSITIONS);

        int trailingBytes = 0;
        for (String record : readAccountRecords()) {
            for (Field money : ACCOUNT_MONEY_FIELDS) {
                String fieldText = field(record, money);

                assertThat(fieldText).hasSize(MONEY_FIELD_LENGTH);
                assertThat(fieldText.charAt(fieldText.length() - 1)).isEqualTo(POSITIVE_ZERO_BYTE);
                assertThat(decodeZonedDecimal(fieldText, MONEY_SCALE).signum()).isNotNegative();
                trailingBytes++;
            }
        }

        assertThat(trailingBytes).isEqualTo(ACCOUNT_SIGN_BYTE_TOTAL);
    }

    // Customer fixture, from app/data/ASCII/custdata.txt laid out by app/cpy/CVCUS01Y.cpy.

    @Test
    @DisplayName("record 1 of custdata.txt carries the identifier and the three name fields")
    void customerRecordOneIdentityFieldsMatchTheFixture() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertThat(field(record, CUSTOMER_ID)).isEqualTo(RECORD_1_CUSTOMER_ID);
        assertThat(field(record, CUSTOMER_FIRST_NAME))
                .isEqualTo(padded(RECORD_1_FIRST_NAME, CUSTOMER_FIRST_NAME.length()));
        assertThat(field(record, CUSTOMER_MIDDLE_NAME))
                .isEqualTo(padded(RECORD_1_MIDDLE_NAME, CUSTOMER_MIDDLE_NAME.length()));
        assertThat(field(record, CUSTOMER_LAST_NAME))
                .isEqualTo(padded(RECORD_1_LAST_NAME, CUSTOMER_LAST_NAME.length()));
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries three address lines, state NC, country USA and zip 12546")
    void customerRecordOneAddressFieldsMatchTheFixture() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertThat(field(record, CUSTOMER_ADDRESS_LINE_1))
                .isEqualTo(padded(RECORD_1_ADDRESS_LINE_1, CUSTOMER_ADDRESS_LINE_1.length()));
        assertThat(field(record, CUSTOMER_ADDRESS_LINE_2))
                .isEqualTo(padded(RECORD_1_ADDRESS_LINE_2, CUSTOMER_ADDRESS_LINE_2.length()));
        assertThat(field(record, CUSTOMER_ADDRESS_LINE_3))
                .isEqualTo(padded(RECORD_1_ADDRESS_LINE_3, CUSTOMER_ADDRESS_LINE_3.length()));
        assertThat(field(record, CUSTOMER_STATE_CODE)).isEqualTo(RECORD_1_STATE_CODE);
        assertThat(field(record, CUSTOMER_COUNTRY_CODE)).isEqualTo(RECORD_1_COUNTRY_CODE);
        assertThat(field(record, CUSTOMER_ADDRESS_ZIP))
                .isEqualTo(padded(RECORD_1_CUSTOMER_ZIP, CUSTOMER_ADDRESS_ZIP.length()));
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries two phone numbers padded to fifteen characters")
    void customerRecordOnePhoneNumbersCarryTheirTrailingPadding() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertThat(field(record, CUSTOMER_PHONE_NUMBER_1))
                .isEqualTo(padded(RECORD_1_PHONE_NUMBER_1, CUSTOMER_PHONE_NUMBER_1.length()));
        assertThat(field(record, CUSTOMER_PHONE_NUMBER_2))
                .isEqualTo(padded(RECORD_1_PHONE_NUMBER_2, CUSTOMER_PHONE_NUMBER_2.length()));
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries the stored identifiers, a date of birth and score 274")
    void customerRecordOneStoredIdentifiersAndScoreMatchTheFixture() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertThat(field(record, CUSTOMER_SOCIAL_SECURITY_NUMBER))
                .isEqualTo(RECORD_1_SOCIAL_SECURITY_NUMBER);
        assertThat(field(record, CUSTOMER_GOVERNMENT_ISSUED_ID))
                .isEqualTo(RECORD_1_GOVERNMENT_ISSUED_ID);
        assertThat(field(record, CUSTOMER_DATE_OF_BIRTH)).isEqualTo(RECORD_1_DATE_OF_BIRTH);
        assertThat(field(record, CUSTOMER_EFT_ACCOUNT_ID)).isEqualTo(RECORD_1_EFT_ACCOUNT_ID);
        assertThat(field(record, CUSTOMER_PRIMARY_HOLDER_INDICATOR))
                .isEqualTo(RECORD_1_PRIMARY_HOLDER_INDICATOR);
        assertThat(field(record, CUSTOMER_CREDIT_SCORE)).isEqualTo(RECORD_1_CREDIT_SCORE);
        assertThat(field(record, CUSTOMER_FILLER)).isEqualTo(padded("", CUSTOMER_FILLER.length()));
        assertThat(CUSTOMER_FILLER.endInclusive()).isEqualTo(MEASURED_CUSTOMER_WIDTH);
    }

    // Disclosure group fixture, from app/data/ASCII/discgrp.txt laid out by app/cpy/CVTRA02Y.cpy.

    @Test
    @DisplayName("record 1 of discgrp.txt carries the sixteen-character key and a filler of 28 zeros")
    void disclosureGroupRecordOneMatchesTheDisclosureLayout() {
        String record = readDisclosureGroupRecords().get(FIRST_RECORD);
        String filler = DISCLOSURE_FILLER_CHARACTER.repeat(DISCLOSURE_GROUP_FILLER.length());

        assertThat(field(record, DISCLOSURE_GROUP_ID)).isEqualTo(RECORD_1_DISCLOSURE_GROUP_ID);
        assertThat(field(record, DISCLOSURE_TRANSACTION_TYPE_CODE))
                .isEqualTo(RECORD_1_TRANSACTION_TYPE_CODE);
        assertThat(field(record, DISCLOSURE_TRANSACTION_CATEGORY_CODE))
                .isEqualTo(RECORD_1_TRANSACTION_CATEGORY_CODE);
        assertThat(field(record, DISCLOSURE_INTEREST_RATE)).isEqualTo(RECORD_1_INTEREST_RATE_FIELD);
        assertThat(field(record, DISCLOSURE_GROUP_FILLER)).isEqualTo(filler);
        assertThat(record).isEqualTo(RECORD_1_DISCLOSURE_GROUP_ID
                + RECORD_1_TRANSACTION_TYPE_CODE
                + RECORD_1_TRANSACTION_CATEGORY_CODE
                + RECORD_1_INTEREST_RATE_FIELD
                + filler);
        assertThat(DISCLOSURE_TRANSACTION_CATEGORY_CODE.endInclusive())
                .isEqualTo(DISCLOSURE_GROUP_KEY_LENGTH);
        assertThat(DISCLOSURE_GROUP_FILLER.endInclusive())
                .isEqualTo(MEASURED_DISCLOSURE_GROUP_WIDTH);
    }

    @Test
    @DisplayName("DIS-INT-RATE spans six characters in record 1 of discgrp.txt and decodes to 15.00")
    void disclosureGroupRateDecodesToFifteenAtScaleTwo() {
        List<String> records = readDisclosureGroupRecords();
        String firstRecord = records.get(FIRST_RECORD);

        assertThat(RECORD_1_INTEREST_RATE_FIELD).hasSize(INTEREST_RATE_FIELD_LENGTH);
        assertValueAndScale(decimalField(firstRecord, DISCLOSURE_INTEREST_RATE, INTEREST_RATE_SCALE),
                RECORD_1_INTEREST_RATE, INTEREST_RATE_SCALE);
        assertThat(records).allSatisfy(record -> {
            BigDecimal rate = decimalField(record, DISCLOSURE_INTEREST_RATE, INTEREST_RATE_SCALE);

            assertThat(rate.scale()).isEqualTo(INTEREST_RATE_SCALE);
            assertThat(rate.signum()).isNotNegative();
        });
    }
}
