package com.carddemo.account.repository;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 * <p>The methods here assert fixture bytes and nothing about a database row.</p>
 *
 * <p>Fifty account records hold five money fields each, and all two hundred and fifty trailing
 * bytes are the positive brace.
 * {@link #negativeOverpunchBytesDecodeToTheirNegatedDigit(char, int)} covers the negative half of
 * the table from field text built in memory.</p>
 *
 * <p>Every expected value below names the copybook line and the fixture positions it comes
 * from.</p>
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
    //
    // The customer record carries personal data: three name fields at CVCUS01Y lines 6 to 8, three
    // address lines at lines 9 to 11, a zip at line 14, two telephone numbers at lines 15 and 16, a
    // Social Security Number at line 17, a government-issued identifier at line 18, a date of birth
    // at line 19, an electronic funds transfer account at line 20 and a credit score at line 22.
    //
    // Each of those fields is pinned by the SHA-256 digest of its exact field text, padding
    // included, rather than by the text itself. The assertion stays exact: one changed character
    // changes the digest. Neither the value nor a failure message carries the personal data, so a
    // build log holds none of it.
    //
    // The record key, the two-character state code, the three-character country code and the
    // one-character primary-holder indicator are pinned as text. None identifies a person, and the
    // key names the record under test.

    /** CUST-ID at CVCUS01Y line 5, positions 1 through 9 of record 1. */
    private static final String RECORD_1_CUSTOMER_ID = "000000001";

    /**
     * SHA-256 digest of the field text of CUST-FIRST-NAME at CVCUS01Y line 6, positions 10 through 34 of record 1.
     */
    private static final String RECORD_1_FIRST_NAME_DIGEST =
            "b46039dfd63d952d8880070172213eece8046e8826072b82926e19a8ecee8ebd";

    /**
     * SHA-256 digest of the field text of CUST-MIDDLE-NAME at CVCUS01Y line 7, positions 35 through 59 of record 1.
     */
    private static final String RECORD_1_MIDDLE_NAME_DIGEST =
            "e239448b9da06a05628e9f6407cd6d6e34f00b887a5a83834b60561bfdbf8746";

    /**
     * SHA-256 digest of the field text of CUST-LAST-NAME at CVCUS01Y line 8, positions 60 through 84 of record 1.
     */
    private static final String RECORD_1_LAST_NAME_DIGEST =
            "dfcd5018ccaffd0a2a06c5684aeb3716140db00ef8ceff970996274d5370371d";

    /**
     * SHA-256 digest of the field text of CUST-ADDR-LINE-1 at CVCUS01Y line 9, positions 85 through 134 of record 1.
     */
    private static final String RECORD_1_ADDRESS_LINE_1_DIGEST =
            "fc1bb391d3fda38ba50b411548f650a4874963be59007dac05e28f0c83cd8da5";

    /**
     * SHA-256 digest of the field text of CUST-ADDR-LINE-2 at CVCUS01Y line 10, positions 135 through 184 of record 1.
     */
    private static final String RECORD_1_ADDRESS_LINE_2_DIGEST =
            "a593d398503df49f5390c0d962604f8317bb6da1d95d5d2b281550adde516317";

    /**
     * SHA-256 digest of the field text of CUST-ADDR-LINE-3 at CVCUS01Y line 11, positions 185 through 234 of record 1.
     */
    private static final String RECORD_1_ADDRESS_LINE_3_DIGEST =
            "37e3f121bf54abe3317ff465488c7ac1fdaed6ff0b6fee2626dee6817e75542f";

    /** CUST-ADDR-STATE-CD at CVCUS01Y line 12, positions 235 and 236 of record 1. */
    private static final String RECORD_1_STATE_CODE = "NC";

    /** CUST-ADDR-COUNTRY-CD at CVCUS01Y line 13, positions 237 through 239 of record 1. */
    private static final String RECORD_1_COUNTRY_CODE = "USA";

    /**
     * SHA-256 digest of the field text of CUST-ADDR-ZIP at CVCUS01Y line 14, positions 240 through 249 of record 1.
     */
    private static final String RECORD_1_CUSTOMER_ZIP_DIGEST =
            "513dee4c300abd4a370eaab837316922575c83e3a057d1c49ddd7646066cd810";

    /**
     * Digest pinning CUST-SSN, the Social Security Number, at
     * {@code app/cpy/CVCUS01Y.cpy:L17 PIC 9(09)}, positions 280 through 288 of record 1.
     *
     * <p>No test in this class holds a complete identifier. The digest covers the field name, a
     * colon and the decoded value, so it pins the exact value the decode must produce without
     * storing that value and without printing it when an assertion fails.</p>
     */
    private static final String RECORD_1_SOCIAL_SECURITY_NUMBER_DIGEST =
            "65265e07a465ed10de7ae0351e72ca1504772100476c1d4a8e07f176f600438c";

    /** Declared width of {@code CUST-SSN PIC 9(09)} at app/cpy/CVCUS01Y.cpy:L17. */
    private static final int SOCIAL_SECURITY_NUMBER_WIDTH = 9;

    /** The count of trailing characters a masked identifier leaves visible. */
    private static final int VISIBLE_SUFFIX_LENGTH = 4;

    /** The character that replaces a hidden identifier character. */
    private static final char MASK_CHARACTER = '*';

    /** The digest algorithm that pins each stored identifier without storing it. */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The masked form of record 1's Social Security Number: five masks then the last four. */
    private static final String RECORD_1_SOCIAL_SECURITY_NUMBER_MASKED = "*****3888";

    /**
     * Digest pinning CUST-GOVT-ISSUED-ID at {@code app/cpy/CVCUS01Y.cpy:L18 PIC X(20)}, positions
     * 289 through 308 of record 1. Built the same way as
     * {@link #RECORD_1_SOCIAL_SECURITY_NUMBER_DIGEST}.
     */
    private static final String RECORD_1_GOVERNMENT_ISSUED_ID_DIGEST =
            "8f003654c94e6bfce1accc6e0742bf3d08055fae58b82fb254197706073b28e8";

    /** Declared width of {@code CUST-GOVT-ISSUED-ID PIC X(20)} at app/cpy/CVCUS01Y.cpy:L18. */
    private static final int GOVERNMENT_ISSUED_ID_WIDTH = 20;

    /** The masked form of record 1's government-issued identifier. */
    private static final String RECORD_1_GOVERNMENT_ISSUED_ID_MASKED = "****************8437";

    /**
     * SHA-256 digest of the field text of CUST-PHONE-NUM-1 at CVCUS01Y line 15, positions 250 through 264 of record 1.
     */
    private static final String RECORD_1_PHONE_NUMBER_1_DIGEST =
            "3380fbe69f1b2afc238b06221985762c121693fac7e956e4170e8b4d2a636825";

    /**
     * SHA-256 digest of the field text of CUST-PHONE-NUM-2 at CVCUS01Y line 16, positions 265 through 279 of record 1.
     */
    private static final String RECORD_1_PHONE_NUMBER_2_DIGEST =
            "c096efeb4597bf00b1b841acfbc087e200c691442cb4c382b9af053fe50dc42c";

    /**
     * Digest pinning CUST-EFT-ACCOUNT-ID, the electronic funds transfer account identifier, at
     * {@code app/cpy/CVCUS01Y.cpy:L20 PIC X(10)}, positions 319 through 328 of record 1. Built the
     * same way as {@link #RECORD_1_SOCIAL_SECURITY_NUMBER_DIGEST}.
     */
    private static final String RECORD_1_EFT_ACCOUNT_ID_DIGEST =
            "039c417ab60cc0836c6df4196b27f7ec19e1f1b53a4175f3b90b0b347d054ea1";

    /** Declared width of {@code CUST-EFT-ACCOUNT-ID PIC X(10)} at app/cpy/CVCUS01Y.cpy:L20. */
    private static final int EFT_ACCOUNT_ID_WIDTH = 10;

    /** The masked form of record 1's electronic funds transfer account identifier. */
    private static final String RECORD_1_EFT_ACCOUNT_ID_MASKED = "******1756";

    /**
     * SHA-256 digest of the field text of CUST-DOB-YYYY-MM-DD at CVCUS01Y line 19, positions 309 through 318 of record 1.
     */
    private static final String RECORD_1_DATE_OF_BIRTH_DIGEST =
            "e50be91f748c886d810e2ba759b6b415b540588e9305e3003a2bfa5e955f32d8";

    /** CUST-PRI-CARD-HOLDER-IND at CVCUS01Y line 21, position 329 of record 1. */
    private static final String RECORD_1_PRIMARY_HOLDER_INDICATOR = "Y";

    /**
     * SHA-256 digest of the field text of CUST-FICO-CREDIT-SCORE at CVCUS01Y line 22, positions 330 through 332 of record 1.
     */
    private static final String RECORD_1_CREDIT_SCORE_DIGEST =
            "718127812c05853f0bec61582a4a3840b1c844fe11fe1a004b5b7eb8b8b59846";

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
    /**
     * Asserts one field of a record against the SHA-256 digest of its field text, and asserts the
     * field width at the same time.
     *
     * <p>The digest keeps the assertion exact while keeping personal data out of this file and out
     * of the failure message. A changed character changes the digest, and the message names the
     * field and its width alone.</p>
     *
     * @param record      the fixture record
     * @param fixtureField the field to read
     * @param expectedDigest the lower-case hexadecimal SHA-256 digest of the field text
     * @param description a locator for the failure message, carrying no field value
     */
    private static void assertFieldDigest(String record, Field fixtureField, String expectedDigest,
            String description) {
        String fieldText = field(record, fixtureField);

        assertThat(fieldText.length())
                .as("%s spans %d characters", description, fixtureField.length())
                .isEqualTo(fixtureField.length());
        assertThat(sha256Hex(fieldText))
                .as("%s carries the expected field text, compared by digest", description)
                .isEqualTo(expectedDigest);
    }

    /**
     * Returns the lower-case hexadecimal SHA-256 digest of a string, read as UTF-8 bytes.
     *
     * <p>Every character of the fixture is ASCII, so the UTF-8 bytes are the fixture bytes.</p>
     *
     * @param text the text to digest
     * @return the 64-character hexadecimal digest
     */
    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", unavailable);
        }
    }

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

        // The failure text names the field width and the accepted byte sets. It carries neither the
        // field text nor the byte it refused, so a fixture value cannot reach a build log.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> decodeZonedDecimal(unrecognisedText, MONEY_SCALE))
                .withMessageContaining(String.valueOf(MONEY_FIELD_LENGTH))
                .withMessageNotContaining(unrecognisedText);
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
        assertFieldDigest(record, CUSTOMER_FIRST_NAME, RECORD_1_FIRST_NAME_DIGEST,
                "CUST-FIRST-NAME at CVCUS01Y line 6");
        assertFieldDigest(record, CUSTOMER_MIDDLE_NAME, RECORD_1_MIDDLE_NAME_DIGEST,
                "CUST-MIDDLE-NAME at CVCUS01Y line 7");
        assertFieldDigest(record, CUSTOMER_LAST_NAME, RECORD_1_LAST_NAME_DIGEST,
                "CUST-LAST-NAME at CVCUS01Y line 8");
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries three address lines, state NC, country USA and a five-digit zip")
    void customerRecordOneAddressFieldsMatchTheFixture() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertFieldDigest(record, CUSTOMER_ADDRESS_LINE_1, RECORD_1_ADDRESS_LINE_1_DIGEST,
                "CUST-ADDR-LINE-1 at CVCUS01Y line 9");
        assertFieldDigest(record, CUSTOMER_ADDRESS_LINE_2, RECORD_1_ADDRESS_LINE_2_DIGEST,
                "CUST-ADDR-LINE-2 at CVCUS01Y line 10");
        assertFieldDigest(record, CUSTOMER_ADDRESS_LINE_3, RECORD_1_ADDRESS_LINE_3_DIGEST,
                "CUST-ADDR-LINE-3 at CVCUS01Y line 11");
        assertThat(field(record, CUSTOMER_STATE_CODE)).isEqualTo(RECORD_1_STATE_CODE);
        assertThat(field(record, CUSTOMER_COUNTRY_CODE)).isEqualTo(RECORD_1_COUNTRY_CODE);
        assertFieldDigest(record, CUSTOMER_ADDRESS_ZIP, RECORD_1_CUSTOMER_ZIP_DIGEST,
                "CUST-ADDR-ZIP at CVCUS01Y line 14");
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries two phone numbers padded to fifteen characters")
    void customerRecordOnePhoneNumbersCarryTheirTrailingPadding() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertFieldDigest(record, CUSTOMER_PHONE_NUMBER_1, RECORD_1_PHONE_NUMBER_1_DIGEST,
                "CUST-PHONE-NUM-1 at CVCUS01Y line 15");
        assertFieldDigest(record, CUSTOMER_PHONE_NUMBER_2, RECORD_1_PHONE_NUMBER_2_DIGEST,
                "CUST-PHONE-NUM-2 at CVCUS01Y line 16");
    }

    @Test
    @DisplayName("record 1 of custdata.txt carries a date of birth, a primary-holder indicator and "
            + "a three-digit score at their declared positions")
    void customerRecordOneDateOfBirthAndScoreMatchTheFixture() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        assertFieldDigest(record, CUSTOMER_DATE_OF_BIRTH, RECORD_1_DATE_OF_BIRTH_DIGEST,
                "CUST-DOB-YYYY-MM-DD at CVCUS01Y line 19");
        assertThat(field(record, CUSTOMER_PRIMARY_HOLDER_INDICATOR))
                .isEqualTo(RECORD_1_PRIMARY_HOLDER_INDICATOR);
        assertFieldDigest(record, CUSTOMER_CREDIT_SCORE, RECORD_1_CREDIT_SCORE_DIGEST,
                "CUST-FICO-CREDIT-SCORE at CVCUS01Y line 22");
        assertThat(field(record, CUSTOMER_FILLER)).isEqualTo(padded("", CUSTOMER_FILLER.length()));
        assertThat(CUSTOMER_FILLER.endInclusive()).isEqualTo(MEASURED_CUSTOMER_WIDTH);
    }

    /**
     * Asserts the three stored identifiers of record 1 decode at their declared widths, hold digits
     * throughout, mask to their published suffixes, and match the digest that pins each value.
     *
     * <p>Nothing in this test holds or prints a complete identifier. Each assertion reads a derived
     * value: a length, a boolean, a masked form, or a digest. A wrong decode fails on the digest,
     * and the failure report carries the digest rather than the identifier.</p>
     */
    @Test
    @DisplayName("record 1 of custdata.txt carries the three stored identifiers at their declared "
            + "widths, and each matches the digest that pins it")
    void customerRecordOneStoredIdentifiersMatchTheirDigests() {
        String record = readCustomerRecords().get(FIRST_RECORD);

        String socialSecurityNumber = field(record, CUSTOMER_SOCIAL_SECURITY_NUMBER);
        assertThat(socialSecurityNumber.length()).isEqualTo(SOCIAL_SECURITY_NUMBER_WIDTH);
        assertThat(holdsOnlyDigits(socialSecurityNumber))
                .as("CUST-SSN holds only digits")
                .isTrue();
        assertThat(maskedSuffix(socialSecurityNumber))
                .isEqualTo(RECORD_1_SOCIAL_SECURITY_NUMBER_MASKED);
        assertThat(saltedDigest("CUST-SSN", socialSecurityNumber))
                .isEqualTo(RECORD_1_SOCIAL_SECURITY_NUMBER_DIGEST);

        String governmentIssuedId = field(record, CUSTOMER_GOVERNMENT_ISSUED_ID);
        assertThat(governmentIssuedId.length()).isEqualTo(GOVERNMENT_ISSUED_ID_WIDTH);
        assertThat(holdsOnlyDigits(governmentIssuedId))
                .as("CUST-GOVT-ISSUED-ID holds only digits")
                .isTrue();
        assertThat(maskedSuffix(governmentIssuedId))
                .isEqualTo(RECORD_1_GOVERNMENT_ISSUED_ID_MASKED);
        assertThat(saltedDigest("CUST-GOVT-ISSUED-ID", governmentIssuedId))
                .isEqualTo(RECORD_1_GOVERNMENT_ISSUED_ID_DIGEST);

        String eftAccountId = field(record, CUSTOMER_EFT_ACCOUNT_ID);
        assertThat(eftAccountId.length()).isEqualTo(EFT_ACCOUNT_ID_WIDTH);
        assertThat(holdsOnlyDigits(eftAccountId))
                .as("CUST-EFT-ACCOUNT-ID holds only digits")
                .isTrue();
        assertThat(maskedSuffix(eftAccountId)).isEqualTo(RECORD_1_EFT_ACCOUNT_ID_MASKED);
        assertThat(saltedDigest("CUST-EFT-ACCOUNT-ID", eftAccountId))
                .isEqualTo(RECORD_1_EFT_ACCOUNT_ID_DIGEST);
    }

    /**
     * Asserts that no expected value in this class carries a complete stored identifier.
     *
     * <p>The three published masked forms keep four characters each, and the three digests carry no
     * character of the value they pin. This test walks the declared fields by reflection, so a
     * plaintext identifier added later fails here.</p>
     */
    @Test
    @DisplayName("No expected value in this class carries a complete stored identifier")
    void noExpectedValueCarriesACompleteStoredIdentifier() {
        String record = readCustomerRecords().get(FIRST_RECORD);
        List<String> identifiers = List.of(
                field(record, CUSTOMER_SOCIAL_SECURITY_NUMBER),
                field(record, CUSTOMER_GOVERNMENT_ISSUED_ID),
                field(record, CUSTOMER_EFT_ACCOUNT_ID));

        for (java.lang.reflect.Field declared
                : ZonedDecimalFixtureDecodingTest.class.getDeclaredFields()) {
            if (declared.isSynthetic() || declared.getType() != String.class) {
                continue;
            }
            declared.setAccessible(true);
            String expected;
            try {
                expected = (String) declared.get(null);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(
                        "Reading field %s failed.".formatted(declared.getName()), failure);
            }
            if (expected == null) {
                continue;
            }
            for (String identifier : identifiers) {
                assertThat(expected.contains(identifier))
                        .as("field %s carries a complete stored identifier", declared.getName())
                        .isFalse();
            }
        }

        for (String masked : List.of(RECORD_1_SOCIAL_SECURITY_NUMBER_MASKED,
                RECORD_1_GOVERNMENT_ISSUED_ID_MASKED, RECORD_1_EFT_ACCOUNT_ID_MASKED)) {
            assertThat(masked.chars().filter(Character::isDigit).count())
                    .as("visible characters in a masked identifier")
                    .isEqualTo(VISIBLE_SUFFIX_LENGTH);
        }
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

    /**
     * Masks every character of an identifier except the last {@value #VISIBLE_SUFFIX_LENGTH}.
     *
     * @param identifier the decoded field content
     * @return the masked form, at the width of the argument
     */
    private static String maskedSuffix(String identifier) {
        int hidden = identifier.length() - VISIBLE_SUFFIX_LENGTH;
        return String.valueOf(MASK_CHARACTER).repeat(hidden)
                + identifier.substring(hidden);
    }

    /**
     * Reports whether every character of a field is a decimal digit.
     *
     * @param field the decoded field content
     * @return true when the field holds at least one character and every one is a digit
     */
    private static boolean holdsOnlyDigits(String field) {
        return !field.isEmpty() && field.chars().allMatch(Character::isDigit);
    }

    /**
     * Digests a field name, a colon and a field value, and returns the digest as lower-case
     * hexadecimal.
     *
     * <p>The field name salts the digest, so the result is not the digest of a bare digit string.
     * The digest pins the exact value the decode must produce and carries no character of it.</p>
     *
     * @param fieldName the copybook field name
     * @param value     the decoded field content
     * @return the digest, sixty-four hexadecimal characters
     */
    private static String saltedDigest(String fieldName, String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
                    .digest((fieldName + ":" + value).getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "The runtime supplies no %s digest.".formatted(DIGEST_ALGORITHM), failure);
        }

        StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
        for (byte octet : digest) {
            hexadecimal.append("%02x".formatted(octet));
        }
        return hexadecimal.toString();
    }
}
