/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.carddemo.batch.batch;

import org.springframework.batch.infrastructure.item.file.LineMapper;

import com.carddemo.common.domain.DailyTransaction;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * :purpose: Map one fixed-length line of the daily-transaction feed
 *  (``DALYTRAN``, copybook ``CVTRA06Y``, RECLN 350) into a
 *  {@link DailyTransaction}. It is the record-parsing counterpart of the
 *  sequential ``READ DALYTRAN-FILE`` in the legacy ``CBTRN01C`` batch program:
 *  each 350-character record is split by the ``CVTRA06Y`` field positions, the
 *  text fields are right-trimmed to their logical value, the two integral
 *  identifier fields are parsed as whole numbers, and the ``DALYTRAN-AMT``
 *  ``PIC S9(09)V99`` field is decoded from its zoned-decimal trailing
 *  sign-overpunch representation into a scale-2 {@link BigDecimal}.
 * :output: A populated {@link DailyTransaction} with the thirteen logical feed
 *  fields set; the trailing 20-character ``FILLER`` is ignored. Monetary and
 *  identifier values retain exact fixed-point and integral precision (no binary
 *  floating point). A record shorter than the fixed width is right-padded with
 *  spaces before splitting, reproducing the fixed-length record semantics of the
 *  mainframe feed; a malformed numeric field raises
 *  {@link IllegalArgumentException}.
 * :note: Field positions follow ``CVTRA06Y``: id ``X(16)``, type ``X(02)``,
 *  category ``9(04)``, source ``X(10)``, description ``X(100)``, amount
 *  ``S9(09)V99``, merchant id ``9(09)``, merchant name ``X(50)``, merchant city
 *  ``X(50)``, merchant zip ``X(10)``, card number ``X(16)``, origination
 *  timestamp ``X(26)``, processing timestamp ``X(26)``, filler ``X(20)``.
 */
public class DailyTransactionRecordMapper implements LineMapper<DailyTransaction> {

    /** Total fixed record width in characters (``CVTRA06Y`` RECLN 350). */
    static final int RECORD_LENGTH = 350;

    /** Implied decimal scale of ``DALYTRAN-AMT`` (``PIC S9(09)V99`` — two fractional digits). */
    private static final int AMOUNT_SCALE = 2;

    // Field end offsets (exclusive, zero-based) per the CVTRA06Y layout.
    private static final int ID_END = 16;             // DALYTRAN-ID            X(16) [0,16)
    private static final int TYPE_CD_END = 18;        // DALYTRAN-TYPE-CD       X(02) [16,18)
    private static final int CAT_CD_END = 22;         // DALYTRAN-CAT-CD        9(04) [18,22)
    private static final int SOURCE_END = 32;         // DALYTRAN-SOURCE        X(10) [22,32)
    private static final int DESC_END = 132;          // DALYTRAN-DESC          X(100) [32,132)
    private static final int AMT_END = 143;           // DALYTRAN-AMT           S9(09)V99 [132,143)
    private static final int MERCHANT_ID_END = 152;   // DALYTRAN-MERCHANT-ID   9(09) [143,152)
    private static final int MERCHANT_NAME_END = 202; // DALYTRAN-MERCHANT-NAME X(50) [152,202)
    private static final int MERCHANT_CITY_END = 252; // DALYTRAN-MERCHANT-CITY X(50) [202,252)
    private static final int MERCHANT_ZIP_END = 262;  // DALYTRAN-MERCHANT-ZIP  X(10) [252,262)
    private static final int CARD_NUM_END = 278;      // DALYTRAN-CARD-NUM      X(16) [262,278)
    private static final int ORIG_TS_END = 304;       // DALYTRAN-ORIG-TS       X(26) [278,304)
    private static final int PROC_TS_END = 330;       // DALYTRAN-PROC-TS       X(26) [304,330)

    /**
     * :purpose: Parse one fixed-length feed line into a {@link DailyTransaction}.
     * :param line: the raw record line; normalized to the fixed 350-character
     *  width (right-padded with spaces when shorter) before splitting.
     * :param lineNumber: the one-based line number within the feed; included in
     *  the exception message when a numeric field is malformed.
     * :returns: the populated {@link DailyTransaction}.
     */
    @Override
    public DailyTransaction mapLine(String line, int lineNumber) {
        String record = normalizeWidth(line);

        DailyTransaction transaction = new DailyTransaction();
        transaction.setDalytranId(rtrim(record.substring(0, ID_END)));
        transaction.setDalytranTypeCd(rtrim(record.substring(ID_END, TYPE_CD_END)));
        transaction.setDalytranCatCd(parseInt(record.substring(TYPE_CD_END, CAT_CD_END), lineNumber));
        transaction.setDalytranSource(rtrim(record.substring(CAT_CD_END, SOURCE_END)));
        transaction.setDalytranDesc(rtrim(record.substring(SOURCE_END, DESC_END)));
        transaction.setDalytranAmt(decodeSignedZonedDecimal(
                record.substring(DESC_END, AMT_END), AMOUNT_SCALE, lineNumber));
        transaction.setDalytranMerchantId(parseLong(record.substring(AMT_END, MERCHANT_ID_END), lineNumber));
        transaction.setDalytranMerchantName(rtrim(record.substring(MERCHANT_ID_END, MERCHANT_NAME_END)));
        transaction.setDalytranMerchantCity(rtrim(record.substring(MERCHANT_NAME_END, MERCHANT_CITY_END)));
        transaction.setDalytranMerchantZip(rtrim(record.substring(MERCHANT_CITY_END, MERCHANT_ZIP_END)));
        transaction.setDalytranCardNum(rtrim(record.substring(MERCHANT_ZIP_END, CARD_NUM_END)));
        transaction.setDalytranOrigTs(rtrim(record.substring(CARD_NUM_END, ORIG_TS_END)));
        transaction.setDalytranProcTs(rtrim(record.substring(ORIG_TS_END, PROC_TS_END)));
        return transaction;
    }

    /**
     * :purpose: Right-pad a record with spaces to the fixed width, or take its
     *  first {@link #RECORD_LENGTH} characters when it is longer, so field
     *  splitting is always positionally valid.
     * :param line: the raw record line.
     * :returns: a string of exactly {@link #RECORD_LENGTH} characters.
     */
    private static String normalizeWidth(String line) {
        String safe = (line == null) ? "" : line;
        if (safe.length() == RECORD_LENGTH) {
            return safe;
        }
        if (safe.length() > RECORD_LENGTH) {
            return safe.substring(0, RECORD_LENGTH);
        }
        StringBuilder padded = new StringBuilder(RECORD_LENGTH);
        padded.append(safe);
        while (padded.length() < RECORD_LENGTH) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * :purpose: Remove trailing spaces from a fixed-width ``PIC X`` field to
     *  recover its left-justified logical value; leading characters are
     *  preserved.
     * :param value: the fixed-width field slice.
     * :returns: the value with trailing spaces removed (possibly empty).
     */
    private static String rtrim(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * :purpose: Parse an unsigned fixed-width numeric field into an
     *  {@link Integer} (``PIC 9(04)`` category code); a blank field maps to zero.
     * :param value: the fixed-width numeric slice.
     * :param lineNumber: one-based feed line number for diagnostics.
     * :returns: the parsed integer value.
     */
    private static Integer parseInt(String value, int lineNumber) {
        String digits = value.trim();
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numeric field '" + value + "' on daily-transaction line " + lineNumber, e);
        }
    }

    /**
     * :purpose: Parse an unsigned fixed-width numeric field into a {@link Long}
     *  (``PIC 9(09)`` merchant id); a blank field maps to zero.
     * :param value: the fixed-width numeric slice.
     * :param lineNumber: one-based feed line number for diagnostics.
     * :returns: the parsed long value.
     */
    private static Long parseLong(String value, int lineNumber) {
        String digits = value.trim();
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.valueOf(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numeric field '" + value + "' on daily-transaction line " + lineNumber, e);
        }
    }

    /**
     * :purpose: Decode a COBOL zoned-decimal ``DISPLAY`` numeric with a trailing
     *  sign overpunch (``PIC S9(n)V(scale)``) into a scale-preserving
     *  {@link BigDecimal}. Every character except the last is a plain digit; the
     *  last character encodes both the least-significant digit and the sign
     *  (``{ABCDEFGHI`` for positive digits 0-9, ``}JKLMNOPQR`` for negative
     *  digits 0-9); a plain trailing digit is treated as positive. A blank field
     *  decodes to zero.
     * :param field: the fixed-width numeric slice, including the trailing
     *  sign-overpunch character.
     * :param scale: the number of implied fractional digits (the ``V`` position).
     * :param lineNumber: one-based feed line number for diagnostics.
     * :returns: the decoded value with exactly ``scale`` fractional digits.
     */
    static BigDecimal decodeSignedZonedDecimal(String field, int scale, int lineNumber) {
        if (field.trim().isEmpty()) {
            return BigDecimal.ZERO.setScale(scale);
        }

        int length = field.length();
        StringBuilder digits = new StringBuilder(length);
        for (int i = 0; i < length - 1; i++) {
            char current = field.charAt(i);
            if (current == ' ') {
                current = '0';
            }
            if (current < '0' || current > '9') {
                throw new IllegalArgumentException(
                        "Invalid zoned-decimal field '" + field
                                + "' on daily-transaction line " + lineNumber);
            }
            digits.append(current);
        }

        char last = field.charAt(length - 1);
        boolean negative = false;
        char lastDigit;
        switch (last) {
            case '{' -> lastDigit = '0';
            case 'A' -> lastDigit = '1';
            case 'B' -> lastDigit = '2';
            case 'C' -> lastDigit = '3';
            case 'D' -> lastDigit = '4';
            case 'E' -> lastDigit = '5';
            case 'F' -> lastDigit = '6';
            case 'G' -> lastDigit = '7';
            case 'H' -> lastDigit = '8';
            case 'I' -> lastDigit = '9';
            case '}' -> { negative = true; lastDigit = '0'; }
            case 'J' -> { negative = true; lastDigit = '1'; }
            case 'K' -> { negative = true; lastDigit = '2'; }
            case 'L' -> { negative = true; lastDigit = '3'; }
            case 'M' -> { negative = true; lastDigit = '4'; }
            case 'N' -> { negative = true; lastDigit = '5'; }
            case 'O' -> { negative = true; lastDigit = '6'; }
            case 'P' -> { negative = true; lastDigit = '7'; }
            case 'Q' -> { negative = true; lastDigit = '8'; }
            case 'R' -> { negative = true; lastDigit = '9'; }
            default -> {
                if (last >= '0' && last <= '9') {
                    lastDigit = last;
                } else {
                    throw new IllegalArgumentException(
                            "Invalid zoned-decimal sign overpunch '" + last + "' in field '" + field
                                    + "' on daily-transaction line " + lineNumber);
                }
            }
        }
        digits.append(lastDigit);

        BigDecimal magnitude = new BigDecimal(new BigInteger(digits.toString())).movePointLeft(scale);
        BigDecimal value = negative ? magnitude.negate() : magnitude;
        return value.setScale(scale);
    }
}
