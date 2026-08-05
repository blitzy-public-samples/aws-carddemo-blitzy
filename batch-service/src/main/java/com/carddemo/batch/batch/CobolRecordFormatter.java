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

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.domain.Transaction;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * :purpose: Stateless formatter that renders CardDemo domain records back into
 *  their legacy COBOL print/record representations for the data-management read
 *  and combine jobs. It reproduces the ``DISPLAY`` output of ``CBACT01C`` (the
 *  labelled account dump), the whole-record ``DISPLAY`` of ``CBACT02C``,
 *  ``CBACT03C``, and ``CBCUS01C`` (fixed-width card, card cross-reference, and
 *  customer records per copybooks ``CVACT02Y``/``CVACT03Y``/``CVCUS01Y``), and
 *  the fixed-width transaction record of ``CVTRA05Y`` written to the combined
 *  ``COMBTRAN`` output file. ``PIC 9(n)`` fields are zero-padded, ``PIC X(n)``
 *  fields are left-justified and space-padded, and ``PIC S9(p)V99`` zoned
 *  amounts are encoded with a trailing sign-overpunch digit.
 * :output: Per-record strings; the methods never mutate their argument and never
 *  log record content.
 */
public final class CobolRecordFormatter {

    /** Field-name column width preceding the ``:`` in each ``CBACT01C`` label. */
    private static final int LABEL_NAME_WIDTH = 24;

    /**
     * Ordered ``CBACT01C`` account field labels, preserved verbatim including the
     * legacy misspelling ``ACCT-EXPIRAION-DATE``. Each rendered label is the name
     * left-justified in {@link #LABEL_NAME_WIDTH} characters followed by ``:``,
     * matching the 25-character label literals of the source ``DISPLAY``
     * statements.
     */
    private static final String[] ACCOUNT_LABEL_NAMES = {
            "ACCT-ID",
            "ACCT-ACTIVE-STATUS",
            "ACCT-CURR-BAL",
            "ACCT-CREDIT-LIMIT",
            "ACCT-CASH-CREDIT-LIMIT",
            "ACCT-OPEN-DATE",
            "ACCT-EXPIRAION-DATE",
            "ACCT-REISSUE-DATE",
            "ACCT-CURR-CYC-CREDIT",
            "ACCT-CURR-CYC-DEBIT",
            "ACCT-GROUP-ID"
    };

    /** The 49-hyphen record separator emitted after each ``CBACT01C`` record. */
    private static final String ACCOUNT_SEPARATOR = "-".repeat(49);

    /** Positive sign-overpunch digits for a zoned-decimal units position (0..9). */
    private static final char[] POSITIVE_OVERPUNCH =
            {'{', 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I'};

    /** Negative sign-overpunch digits for a zoned-decimal units position (0..9). */
    private static final char[] NEGATIVE_OVERPUNCH =
            {'}', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'};

    private CobolRecordFormatter() {
    }

    /**
     * :purpose: Render one account as the multi-line ``CBACT01C`` dump: eleven
     *  labelled field lines followed by the 49-hyphen separator, reproducing the
     *  ``1100-DISPLAY-ACCT-RECORD`` paragraph.
     * :param account: the account to render; read but never modified.
     * :returns: the eleven labelled lines and the separator joined by ``\n``
     *  (no trailing newline; the writer appends the record separator).
     */
    public static String accountDump(Account account) {
        String[] values = {
                padNumericZeros(account.getAcctId(), 11),
                padText(account.getAcctActiveStatus(), 1),
                encodeSignedZonedDecimal(account.getAcctCurrBal(), 10, 2),
                encodeSignedZonedDecimal(account.getAcctCreditLimit(), 10, 2),
                encodeSignedZonedDecimal(account.getAcctCashCreditLimit(), 10, 2),
                padText(account.getAcctOpenDate(), 10),
                padText(account.getAcctExpiraionDate(), 10),
                padText(account.getAcctReissueDate(), 10),
                encodeSignedZonedDecimal(account.getAcctCurrCycCredit(), 10, 2),
                encodeSignedZonedDecimal(account.getAcctCurrCycDebit(), 10, 2),
                padText(account.getAcctGroupId(), 10)
        };
        StringBuilder builder = new StringBuilder(11 * 40 + ACCOUNT_SEPARATOR.length());
        for (int i = 0; i < ACCOUNT_LABEL_NAMES.length; i++) {
            builder.append(padText(ACCOUNT_LABEL_NAMES[i], LABEL_NAME_WIDTH))
                    .append(':')
                    .append(values[i])
                    .append('\n');
        }
        builder.append(ACCOUNT_SEPARATOR);
        return builder.toString();
    }

    /**
     * :purpose: Render one card as the 150-character fixed-width ``CVACT02Y``
     *  record dumped by ``CBACT02C`` (``DISPLAY CARD-RECORD``).
     * :param card: the card to render; read but never modified.
     * :returns: a 150-character fixed-width record string.
     */
    public static String cardRecord(Card card) {
        return padText(card.getCardNum(), 16)
                + padNumericZeros(card.getCardAcctId(), 11)
                + padNumericText(card.getCardCvvCd(), 3)
                + padText(card.getCardEmbossedName(), 50)
                + padText(card.getCardExpiraionDate(), 10)
                + padText(card.getCardActiveStatus(), 1)
                + padText("", 59);
    }

    /**
     * :purpose: Render one card cross-reference as the 50-character fixed-width
     *  ``CVACT03Y`` record dumped by ``CBACT03C`` (``DISPLAY CARD-XREF-RECORD``).
     * :param xref: the cross-reference to render; read but never modified.
     * :returns: a 50-character fixed-width record string.
     */
    public static String cardXrefRecord(CardXref xref) {
        return padText(xref.getXrefCardNum(), 16)
                + padNumericZeros(xref.getXrefCustId(), 9)
                + padNumericZeros(xref.getXrefAcctId(), 11)
                + padText("", 14);
    }

    /**
     * :purpose: Render one customer as the 500-character fixed-width ``CVCUS01Y``
     *  record dumped by ``CBCUS01C`` (``DISPLAY CUSTOMER-RECORD``).
     * :param customer: the customer to render; read but never modified.
     * :returns: a 500-character fixed-width record string.
     */
    public static String customerRecord(Customer customer) {
        return padNumericZeros(customer.getCustId(), 9)
                + padText(customer.getCustFirstName(), 25)
                + padText(customer.getCustMiddleName(), 25)
                + padText(customer.getCustLastName(), 25)
                + padText(customer.getCustAddrLine1(), 50)
                + padText(customer.getCustAddrLine2(), 50)
                + padText(customer.getCustAddrLine3(), 50)
                + padText(customer.getCustAddrStateCd(), 2)
                + padText(customer.getCustAddrCountryCd(), 3)
                + padText(customer.getCustAddrZip(), 10)
                + padText(customer.getCustPhoneNum1(), 15)
                + padText(customer.getCustPhoneNum2(), 15)
                + padNumericText(customer.getCustSsn(), 9)
                + padText(customer.getCustGovtIssuedId(), 20)
                + padText(customer.getCustDobYyyyMmDd(), 10)
                + padText(customer.getCustEftAccountId(), 10)
                + padText(customer.getCustPriCardHolderInd(), 1)
                + padNumericZeros(customer.getCustFicoCreditScore(), 3)
                + padText("", 168);
    }

    /**
     * :purpose: Render one transaction as the 350-character fixed-width
     *  ``CVTRA05Y`` record for the combined ``COMBTRAN`` output file, with the
     *  ``TRAN-AMT`` (``PIC S9(09)V99``) encoded as an 11-character zoned decimal
     *  carrying a trailing sign-overpunch digit.
     * :param transaction: the transaction to render; read but never modified.
     * :returns: a 350-character fixed-width record string.
     */
    public static String transactionRecord(Transaction transaction) {
        return padText(transaction.getTranId(), 16)
                + padText(transaction.getTranTypeCd(), 2)
                + padNumericZeros(transaction.getTranCatCd(), 4)
                + padText(transaction.getTranSource(), 10)
                + padText(transaction.getTranDesc(), 100)
                + encodeSignedZonedDecimal(transaction.getTranAmt(), 9, 2)
                + padNumericZeros(transaction.getTranMerchantId(), 9)
                + padText(transaction.getTranMerchantName(), 50)
                + padText(transaction.getTranMerchantCity(), 50)
                + padText(transaction.getTranMerchantZip(), 10)
                + padText(transaction.getTranCardNum(), 16)
                + padText(transaction.getTranOrigTs(), 26)
                + padText(transaction.getTranProcTs(), 26)
                + padText("", 20);
    }

    /**
     * :purpose: Render a text value into a fixed-width field, left-justified and
     *  space-padded on the right (or truncated), reproducing a COBOL ``PIC X(n)``
     *  field.
     * :param value: the source value; a ``null`` value is treated as empty.
     * :param width: the exact output width in characters.
     * :returns: a string of exactly ``width`` characters.
     */
    static String padText(String value, int width) {
        String safe = (value == null) ? "" : value;
        if (safe.length() > width) {
            return safe.substring(0, width);
        }
        StringBuilder builder = new StringBuilder(width);
        builder.append(safe);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    /**
     * :purpose: Render a numeric value into a right-justified, zero-padded
     *  fixed-width field, reproducing a COBOL ``PIC 9(n)`` field; the magnitude
     *  is used so the field holds only digits.
     * :param value: the source value; a ``null`` value is treated as zero.
     * :param width: the exact output width in digits.
     * :returns: a string of exactly ``width`` digit characters.
     */
    static String padNumericZeros(Long value, int width) {
        long magnitude = Math.abs(value == null ? 0L : value);
        return zeroPad(Long.toString(magnitude), width);
    }

    /**
     * :purpose: Render a numeric value into a right-justified, zero-padded
     *  fixed-width field, reproducing a COBOL ``PIC 9(n)`` field.
     * :param value: the source value; a ``null`` value is treated as zero.
     * :param width: the exact output width in digits.
     * :returns: a string of exactly ``width`` digit characters.
     */
    static String padNumericZeros(Integer value, int width) {
        int magnitude = Math.abs(value == null ? 0 : value);
        return zeroPad(Integer.toString(magnitude), width);
    }

    /**
     * :purpose: Render a numeric value already held as text (for example a card
     *  CVV or a social-security number) into a right-justified, zero-padded
     *  fixed-width field, reproducing a COBOL ``PIC 9(n)`` field; non-digit
     *  characters are removed before padding.
     * :param value: the source value; a ``null`` or blank value is treated as
     *  zero.
     * :param width: the exact output width in digits.
     * :returns: a string of exactly ``width`` digit characters.
     */
    static String padNumericText(String value, int width) {
        String digits = (value == null) ? "" : value.replaceAll("\\D", "");
        return zeroPad(digits, width);
    }

    /**
     * :purpose: Right-justify a digit string in a zero-padded field of the given
     *  width, truncating the high-order digits when the value is too long (the
     *  COBOL ``MOVE`` truncation rule).
     * :param digits: the digit characters to place; an empty string yields all
     *  zeros.
     * :param width: the exact output width in digits.
     * :returns: a string of exactly ``width`` digit characters.
     */
    private static String zeroPad(String digits, int width) {
        if (digits.length() > width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * :purpose: Encode a decimal amount as a COBOL zoned-decimal ``PIC S9(p)V99``
     *  field: ``intDigits + scale`` digit positions with an implied decimal
     *  point, the sign carried as an overpunch on the trailing (units) digit.
     * :param value: the amount to encode; a ``null`` value is treated as zero.
     * :param intDigits: the number of integer digit positions (the ``9(p)`` part).
     * :param scale: the number of fractional digit positions (the ``V99`` part).
     * :returns: a string of exactly ``intDigits + scale`` characters, the last of
     *  which is the sign-overpunch representation of the units digit.
     * :note: Excess fraction digits are truncated toward zero, not rounded: a COBOL
     *  ``MOVE`` into a ``V99`` receiver carries no ``ROUNDED`` phrase, so ``1.005``
     *  encodes as ``1.00`` and ``-1.005`` as ``-1.00``.
     */
    static String encodeSignedZonedDecimal(BigDecimal value, int intDigits, int scale) {
        int total = intDigits + scale;
        BigDecimal scaled = (value == null ? BigDecimal.ZERO : value)
                .setScale(scale, RoundingMode.DOWN);
        boolean negative = scaled.signum() < 0;
        BigInteger unscaled = scaled.abs().movePointRight(scale).toBigInteger();
        String digits = zeroPad(unscaled.toString(), total);
        int unitsDigit = digits.charAt(total - 1) - '0';
        char overpunch = negative ? NEGATIVE_OVERPUNCH[unitsDigit] : POSITIVE_OVERPUNCH[unitsDigit];
        return digits.substring(0, total - 1) + overpunch;
    }
}
