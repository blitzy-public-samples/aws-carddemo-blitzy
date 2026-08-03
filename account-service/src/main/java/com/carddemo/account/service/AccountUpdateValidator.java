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
package com.carddemo.account.service;

import com.carddemo.common.constant.LookupCodes;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.util.DateUtil;
import com.carddemo.common.crypto.PiiMasker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * :purpose: Reproduce the field-level input edits of the legacy account-update program
 *     ``COACTUPC`` paragraph ``1200-EDIT-MAP-INPUTS`` (``app/cbl/COACTUPC.cbl``
 *     L1429-1678), fail-fast and in the exact COBOL ``PERFORM`` order: account status,
 *     open date, credit limit, expiry date, cash credit limit, reissue date, current
 *     balance, current cycle credit, current cycle debit, SSN, date of birth, FICO
 *     score, first/middle/last name, address line 1, state, zip, address line 2, city,
 *     country, phone 1, phone 2, EFT account id and primary card holder.
 * :output: Returns normally when every edit passes; otherwise raises
 *     {@link CardDemoException} (HTTP 400) carrying the first failing message exactly
 *     as the legacy program would have moved it to ``WS-RETURN-MSG``. Only ONE message
 *     is ever produced, preserving the single-message screen contract.
 * :note: Every message literal is reproduced character-for-character from the source:
 *     the static forms come from the ``WS-RETURN-MSG`` 88-levels (COACTUPC L480-530)
 *     and the composed forms from the generic edit paragraphs
 *     (``1215``/``1220``/``1225``/``1235``/``1245``/``1250``/``1260``/``1265``/``1270``
 *     and ``app/cpy/CSUTLDPY.cpy``), which build
 *     ``FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)`` followed by the fixed suffix.
 */
@Component
public class AccountUpdateValidator {

    // -- Static WS-RETURN-MSG 88-level literals (COACTUPC L480-530). ----------------

    /** ``NO-SEARCH-CRITERIA-RECEIVED`` (L490) — nothing at all was submitted. */
    public static final String MSG_NO_INPUT = "No input received";

    /** ``NO-CHANGES-DETECTED`` (L492) — the submitted values match what was fetched. */
    public static final String MSG_NO_CHANGES = "No change detected with respect to values fetched.";

    /** ``WS-PROMPT-FOR-ACCT`` (L484) — the account search key was not supplied. */
    public static final String MSG_ACCT_NOT_PROVIDED = "Account number not provided";

    /** ``WS-PROMPT-FOR-LASTNAME`` (L486) — the last name was not supplied. */
    public static final String MSG_LASTNAME_NOT_PROVIDED = "Last name not provided";

    /** ``WS-NAME-MUST-BE-ALPHA`` (L488) — a name carried a non-alphabetic character. */
    public static final String MSG_NAME_ALPHA = "Name can only contain alphabets and spaces";

    /** ``ACCT-STATUS-MUST-BE-YES-NO`` (L504). */
    public static final String MSG_STATUS_YN = "Account Active Status must be Y or N";

    /** ``CRED-LIMIT-IS-BLANK`` (L506). */
    public static final String MSG_CREDIT_LIMIT_BLANK = "Credit Limit must be supplied";

    /** ``CRED-LIMIT-IS-NOT-VALID`` (L508). */
    public static final String MSG_CREDIT_LIMIT_INVALID = "Credit Limit is not valid";

    /** ``THIS-MONTH-NOT-VALID`` (L510). */
    public static final String MSG_EXPIRY_MONTH = "Card expiry month must be between 1 and 12";

    /** ``THIS-YEAR-NOT-VALID`` (L512). */
    public static final String MSG_EXPIRY_YEAR = "Invalid card expiry year";

    // -- Composed suffixes from the generic edit paragraphs. -----------------------

    /** ``1215-EDIT-MANDATORY`` / ``1220`` / ``1225`` / ``1245`` / ``1250`` suffix. */
    private static final String SUFFIX_MUST_BE_SUPPLIED = " must be supplied.";

    /** ``1220-EDIT-YESNO`` suffix. */
    private static final String SUFFIX_MUST_BE_YES_NO = " must be Y or N.";

    /** ``1225-EDIT-ALPHA-REQD`` / ``1235-EDIT-ALPHA-OPT`` suffix. */
    private static final String SUFFIX_ALPHABETS_ONLY = " can have alphabets only.";

    /** ``1245-EDIT-NUM-REQD`` suffix. */
    private static final String SUFFIX_ALL_NUMERIC = " must be all numeric.";

    /** ``1245-EDIT-NUM-REQD`` suffix. */
    private static final String SUFFIX_NOT_ZERO = " must not be zero.";

    /** ``1250-EDIT-SIGNED-9V2`` suffix. */
    private static final String SUFFIX_NOT_VALID = " is not valid";

    /** ``1270-EDIT-US-STATE-CD`` suffix. */
    private static final String SUFFIX_INVALID_STATE = ": is not a valid state code";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_AREA_CODE_SUPPLIED = ": Area code must be supplied.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_AREA_CODE_3_DIGIT = ": Area code must be A 3 digit number.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_AREA_CODE_INVALID =
            ": Not valid North America general purpose area code";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_PREFIX_SUPPLIED = ": Prefix code must be supplied.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_PREFIX_3_DIGIT = ": Prefix code must be A 3 digit number.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_PREFIX_ZERO = ": Prefix code cannot be zero";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_LINE_SUPPLIED = ": Line number code must be supplied.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_LINE_4_DIGIT = ": Line number code must be A 4 digit number.";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_LINE_ZERO = ": Line number code cannot be zero";

    /** ``1260-EDIT-US-PHONE-NUM`` suffix. */
    private static final String SUFFIX_AREA_CODE_ZERO = ": Area code cannot be zero";

    /** ``1280-EDIT-US-STATE-ZIP-CD`` message; carries no variable-name prefix. */
    public static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    /** ``1265-EDIT-US-SSN`` variable name for the first three characters. */
    private static final String SSN_PART1_LABEL = "SSN: First 3 chars";

    /** ``1265-EDIT-US-SSN`` variable name for the fourth and fifth characters. */
    private static final String SSN_PART2_LABEL = "SSN 4th & 5th chars";

    /** ``1265-EDIT-US-SSN`` variable name for the last four characters. */
    private static final String SSN_PART3_LABEL = "SSN Last 4 chars";

    /** ``1265-EDIT-US-SSN`` suffix for the first three characters. */
    private static final String SUFFIX_SSN_RANGE = ": should not be 000, 666, or between 900 and 999";

    /** ``1275-EDIT-FICO-SCORE`` suffix (COACTUPC L2514-2531). */
    private static final String SUFFIX_FICO_RANGE = ": should be between 300 and 850";

    /** ``app/cpy/CSUTLDPY.cpy`` year edit suffix. */
    private static final String SUFFIX_YEAR_SUPPLIED = " : Year must be supplied.";

    /** ``app/cpy/CSUTLDPY.cpy`` month edit suffix. */
    private static final String SUFFIX_MONTH_RANGE = ": Month must be a number between 1 and 12.";

    /** ``app/cpy/CSUTLDPY.cpy`` day edit suffix. */
    private static final String SUFFIX_DAY_RANGE = ":day must be a number between 1 and 31.";

    // -- WS-EDIT-VARIABLE-NAME values, in COACTUPC 1200 order. ---------------------

    private static final String VAR_OPEN_DATE = "Open Date";
    private static final String VAR_EXPIRY_DATE = "Expiry Date";
    private static final String VAR_CASH_CREDIT_LIMIT = "Cash Credit Limit";
    private static final String VAR_REISSUE_DATE = "Reissue Date";
    private static final String VAR_CURRENT_BALANCE = "Current Balance";
    private static final String VAR_CURR_CYC_CREDIT = "Current Cycle Credit Limit";
    private static final String VAR_CURR_CYC_DEBIT = "Current Cycle Debit Limit";
    private static final String VAR_DATE_OF_BIRTH = "Date of Birth";
    private static final String VAR_FICO_SCORE = "FICO Score";
    private static final String VAR_FIRST_NAME = "First Name";
    private static final String VAR_MIDDLE_NAME = "Middle Name";
    private static final String VAR_ADDRESS_LINE_1 = "Address Line 1";
    private static final String VAR_STATE = "State";
    private static final String VAR_ZIP = "Zip";
    private static final String VAR_CITY = "City";
    private static final String VAR_COUNTRY = "Country";
    private static final String VAR_PHONE_NUMBER_1 = "Phone Number 1";
    private static final String VAR_PHONE_NUMBER_2 = "Phone Number 2";
    private static final String VAR_EFT_ACCOUNT_ID = "EFT Account Id";
    private static final String VAR_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /** :purpose: Lowest FICO score the legacy range check accepts. */
    private static final int FICO_MIN = 300;

    /** :purpose: Highest FICO score the legacy range check accepts. */
    private static final int FICO_MAX = 850;

    /** :purpose: Monetary scale of every ``S9(09)V99`` COMP-3 amount. */
    private static final int MONEY_SCALE = 2;

    /** :purpose: Earliest card expiry year the legacy year edit accepts. */
    private static final int EXPIRY_YEAR_MIN = 1950;

    /** :purpose: Latest card expiry year the legacy year edit accepts. */
    private static final int EXPIRY_YEAR_MAX = 2099;

    /** :purpose: ``WS-EDIT-ALPHANUM-LENGTH`` used for the zip edit (COACTUPC L1607). */
    private static final int ZIP_EDIT_LENGTH = 5;

    /** :purpose: ``WS-EDIT-ALPHANUM-LENGTH`` used for the EFT id edit (COACTUPC L1651). */
    private static final int EFT_EDIT_LENGTH = 10;

    /** :purpose: Declared width of ``CUST-SSN PIC 9(09)``. */
    private static final int SSN_WIDTH = 9;

    /** :purpose: Declared width of ``CUST-ADDR-ZIP PIC X(10)``. */
    private static final int ZIP_WIDTH = 10;

    /** :purpose: Declared width of ``CUST-EFT-ACCOUNT-ID PIC X(10)``. */
    private static final int EFT_WIDTH = 10;

    /** :purpose: Declared width of ``CUST-PHONE-NUM-1``/``-2`` ``PIC X(15)``. */
    private static final int PHONE_WIDTH = 15;

    /**
     * :purpose: Run the whole ``1200-EDIT-MAP-INPUTS`` sequence over a submitted
     *     account-update request, in COBOL ``PERFORM`` order, stopping at the first
     *     failure exactly as the legacy program's ``GO TO`` exits do.
     * :param request: the submitted account and customer field values.
     * :raises CardDemoException: (HTTP 400) carrying the first failing message.
     */
    public void validate(AccountUpdateRequestDto request) {
        if (request == null || isEmptySubmission(request)) {
            throw new CardDemoException(MSG_NO_INPUT);
        }

        // 1220-EDIT-YESNO on 'Account Status'.
        requireYesNo(request.getAcctActiveStatus(), MSG_STATUS_YN);

        // EDIT-DATE-CCYYMMDD on 'Open Date'.
        requireValidDate(request.getAcctOpenDate(), VAR_OPEN_DATE);

        // 1250-EDIT-SIGNED-9V2 on 'Credit Limit' — the only amount with its own
        // dedicated 88-level messages.
        requireAmount(request.getAcctCreditLimit(), MSG_CREDIT_LIMIT_BLANK, MSG_CREDIT_LIMIT_INVALID);

        // EDIT-DATE-CCYYMMDD on 'Expiry Date'; the month and year failures carry the
        // account program's own dedicated literals.
        requireValidExpiryDate(request.getAcctExpiraionDate());

        // 1250-EDIT-SIGNED-9V2 on the remaining amounts, in COBOL order.
        requireAmount(request.getAcctCashCreditLimit(),
                VAR_CASH_CREDIT_LIMIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CASH_CREDIT_LIMIT + SUFFIX_NOT_VALID);

        // EDIT-DATE-CCYYMMDD on 'Reissue Date'.
        requireValidDate(request.getAcctReissueDate(), VAR_REISSUE_DATE);

        requireAmount(request.getAcctCurrBal(),
                VAR_CURRENT_BALANCE + SUFFIX_MUST_BE_SUPPLIED, VAR_CURRENT_BALANCE + SUFFIX_NOT_VALID);
        requireAmount(request.getAcctCurrCycCredit(),
                VAR_CURR_CYC_CREDIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CURR_CYC_CREDIT + SUFFIX_NOT_VALID);
        requireAmount(request.getAcctCurrCycDebit(),
                VAR_CURR_CYC_DEBIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CURR_CYC_DEBIT + SUFFIX_NOT_VALID);

        // 1265-EDIT-US-SSN: the screen carries three separate SSN fields, each edited by
        // 1245-EDIT-NUM-REQD over its own fixed width, with the range check applied only to
        // part 1 and only when part 1 already passed.
        requireSsn(request.getCustSsn());

        // EDIT-DATE-CCYYMMDD plus EDIT-DATE-OF-BIRTH on 'Date of Birth'.
        requireValidDate(request.getCustDobYyyyMmDd(), VAR_DATE_OF_BIRTH);

        // 1245-EDIT-NUM-REQD plus 1275-EDIT-FICO-SCORE on 'FICO Score'.
        requireFicoScore(request.getCustFicoCreditScore());

        // 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT on the names. The dedicated
        // 88-level literals win over the generic composed forms here.
        requireAlpha(request.getCustFirstName(), VAR_FIRST_NAME + SUFFIX_MUST_BE_SUPPLIED, MSG_NAME_ALPHA);
        requireOptionalAlpha(request.getCustMiddleName(), VAR_MIDDLE_NAME + SUFFIX_ALPHABETS_ONLY);
        requireAlpha(request.getCustLastName(), MSG_LASTNAME_NOT_PROVIDED, MSG_NAME_ALPHA);

        // 1215-EDIT-MANDATORY on 'Address Line 1'.
        requireSupplied(request.getCustAddrLine1(), VAR_ADDRESS_LINE_1 + SUFFIX_MUST_BE_SUPPLIED);

        // 1225-EDIT-ALPHA-REQD plus 1270-EDIT-US-STATE-CD on 'State'.
        requireStateCode(request.getCustAddrStateCd());

        // 1245-EDIT-NUM-REQD on 'Zip'. Only the FIRST FIVE characters are edited
        // (COACTUPC L1607 moves 5 into WS-EDIT-ALPHANUM-LENGTH), so a ZIP+4 value passes.
        requireNumeric(request.getCustAddrZip(), VAR_ZIP, ZIP_EDIT_LENGTH, ZIP_WIDTH);

        // 1225-EDIT-ALPHA-REQD on 'City' and 'Country'. CVCUS01Y carries no separate
        // city column: COACTUPC edits 'City' against CUST-ADDR-LINE-3.
        requireAlpha(request.getCustAddrLine3(), VAR_CITY + SUFFIX_MUST_BE_SUPPLIED,
                VAR_CITY + SUFFIX_ALPHABETS_ONLY);
        requireAlpha(request.getCustAddrCountryCd(), VAR_COUNTRY + SUFFIX_MUST_BE_SUPPLIED,
                VAR_COUNTRY + SUFFIX_ALPHABETS_ONLY);

        // 1260-EDIT-US-PHONE-NUM on both phone numbers.
        requirePhone(request.getCustPhoneNum1(), VAR_PHONE_NUMBER_1);
        requirePhone(request.getCustPhoneNum2(), VAR_PHONE_NUMBER_2);

        // 1245-EDIT-NUM-REQD on 'EFT Account Id' over its declared 10-character width. The
        // view masks this identifier too (AAP 0.6.7), so an echoed mask carries no new value
        // and there is nothing to edit, exactly as for the SSN above.
        if (!PiiMasker.isMaskShaped(request.getCustEftAccountId())) {
            requireNumeric(request.getCustEftAccountId(), VAR_EFT_ACCOUNT_ID,
                    EFT_EDIT_LENGTH, EFT_WIDTH);
        }

        // 1220-EDIT-YESNO on 'Primary Card Holder'.
        requireYesNo(request.getCustPriCardHolderInd(),
                VAR_PRIMARY_CARD_HOLDER + SUFFIX_MUST_BE_YES_NO);

        // Cross-field edit (COACTUPC L1664-1669): performed only once the state code and the
        // zip have each passed their own edit.
        requireZipMatchesState(request.getCustAddrStateCd(), request.getCustAddrZip());
    }

    /**
     * :purpose: Detect a submission that carries no field at all, which the legacy
     *     program reports as ``NO-SEARCH-CRITERIA-RECEIVED``.
     * :param request: the submitted request.
     * :returns: ``true`` when every editable field is absent.
     */
    private boolean isEmptySubmission(AccountUpdateRequestDto request) {
        return isBlank(request.getAcctActiveStatus())
                && request.getAcctCurrBal() == null
                && request.getAcctCreditLimit() == null
                && request.getAcctCashCreditLimit() == null
                && request.getAcctCurrCycCredit() == null
                && request.getAcctCurrCycDebit() == null
                && isBlank(request.getAcctOpenDate())
                && isBlank(request.getAcctExpiraionDate())
                && isBlank(request.getAcctReissueDate())
                && isBlank(request.getAcctGroupId())
                && isBlank(request.getCustFirstName())
                && isBlank(request.getCustMiddleName())
                && isBlank(request.getCustLastName())
                && isBlank(request.getCustAddrLine1())
                && isBlank(request.getCustAddrLine2())
                && isBlank(request.getCustAddrLine3())
                && isBlank(request.getCustAddrStateCd())
                && isBlank(request.getCustAddrCountryCd())
                && isBlank(request.getCustAddrZip())
                && isBlank(request.getCustPhoneNum1())
                && isBlank(request.getCustPhoneNum2())
                && isBlank(request.getCustSsn())
                && isBlank(request.getCustGovtIssuedId())
                && isBlank(request.getCustDobYyyyMmDd())
                && isBlank(request.getCustEftAccountId())
                && isBlank(request.getCustPriCardHolderInd())
                && request.getCustFicoCreditScore() == null;
    }

    /**
     * :purpose: ``1220-EDIT-YESNO`` — the value must be supplied and must be ``Y`` or ``N``.
     * :param value: the submitted flag.
     * :param message: the message reported when the value is absent or not Y/N.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireYesNo(String value, String message) {
        if (!"Y".equals(value) && !"N".equals(value)) {
            throw new CardDemoException(message);
        }
    }

    /**
     * :purpose: ``1250-EDIT-SIGNED-9V2`` — the amount must be supplied and must fit the
     *     ``S9(09)V99`` picture, so no more than two decimal places and no more than
     *     nine integer digits. A value with extra precision is rejected rather than
     *     silently rounded, preserving COBOL's fixed-scale semantics (AAP 0.6.1).
     * :param value: the submitted amount.
     * :param blankMessage: the message reported when the amount is absent.
     * :param invalidMessage: the message reported when the amount does not fit the picture.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireAmount(BigDecimal value, String blankMessage, String invalidMessage) {
        if (value == null) {
            throw new CardDemoException(blankMessage);
        }
        if (value.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new CardDemoException(invalidMessage);
        }
        if (value.precision() - value.scale() > 9) {
            throw new CardDemoException(invalidMessage);
        }
    }

    /**
     * :purpose: ``EDIT-DATE-CCYYMMDD`` (``app/cpy/CSUTLDPY.cpy``) — the date must be
     *     supplied in ``YYYY-MM-DD`` form with a valid year, month and day, validated
     *     with the strict ``java.time`` parsing that replaces ``CSUTLDTC``/``CEEDAYS``.
     * :param value: the submitted date.
     * :param variableName: the ``WS-EDIT-VARIABLE-NAME`` used to compose the message.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireValidDate(String value, String variableName) {
        if (isBlank(value)) {
            throw new CardDemoException(variableName + SUFFIX_YEAR_SUPPLIED);
        }
        Integer month = datePart(value, 5, 7);
        if (month == null || month < 1 || month > 12) {
            throw new CardDemoException(variableName + SUFFIX_MONTH_RANGE);
        }
        Integer day = datePart(value, 8, 10);
        if (day == null || day < 1 || day > 31) {
            throw new CardDemoException(variableName + SUFFIX_DAY_RANGE);
        }
        if (!DateUtil.isValid(value, DateUtil.MASK_ISO)) {
            throw new CardDemoException(variableName + SUFFIX_DAY_RANGE);
        }
    }

    /**
     * :purpose: ``EDIT-DATE-CCYYMMDD`` on 'Expiry Date', whose month and year failures
     *     carry the account program's own dedicated 88-level literals
     *     (COACTUPC L510/L512) rather than the generic composed forms.
     * :param value: the submitted expiry date, legacy-spelled ``ACCT-EXPIRAION-DATE``.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireValidExpiryDate(String value) {
        Integer year = datePart(value, 0, 4);
        if (year == null || year < EXPIRY_YEAR_MIN || year > EXPIRY_YEAR_MAX) {
            throw new CardDemoException(MSG_EXPIRY_YEAR);
        }
        Integer month = datePart(value, 5, 7);
        if (month == null || month < 1 || month > 12) {
            throw new CardDemoException(MSG_EXPIRY_MONTH);
        }
        Integer day = datePart(value, 8, 10);
        if (day == null || day < 1 || day > 31 || !DateUtil.isValid(value, DateUtil.MASK_ISO)) {
            throw new CardDemoException(VAR_EXPIRY_DATE + SUFFIX_DAY_RANGE);
        }
    }

    /**
     * :purpose: ``1265-EDIT-US-SSN`` — the first three characters must be numeric and
     *     must not be ``000``, ``666`` or fall in the ``900``-``999`` range.
     * :param value: the submitted social security number.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireSsn(String value) {
        // The view masks this identifier (AAP 0.6.7), a target-only control COACTUPC has no
        // analogue for: a client that echoes the screen back submits the MASK, which carries
        // no new value, so there is nothing for 1265-EDIT-US-SSN to edit and the stored value
        // stands. A genuinely typed value contains no mask character and is edited in full.
        if (PiiMasker.isMaskShaped(value)) {
            return;
        }
        String padded = padToWidth(value, SSN_WIDTH);

        // Part 1 -- 1245-EDIT-NUM-REQD over 3 characters, then the range check, which the
        // legacy program performs only when part 1 already passed (FLG-...-PART1-ISVALID).
        requireNumericSlice(padded, 0, 3, SSN_PART1_LABEL);
        int part1 = Integer.parseInt(padded.substring(0, 3));
        if (part1 == 666 || part1 >= 900) {
            throw new CardDemoException(SSN_PART1_LABEL + SUFFIX_SSN_RANGE);
        }

        // Part 2 -- 1245-EDIT-NUM-REQD over 2 characters.
        requireNumericSlice(padded, 3, 5, SSN_PART2_LABEL);

        // Part 3 -- 1245-EDIT-NUM-REQD over 4 characters.
        requireNumericSlice(padded, 5, 9, SSN_PART3_LABEL);
    }

    /**
     * :purpose: ``1245-EDIT-NUM-REQD`` applied to a fixed-width slice of a value, exactly as
     *     the legacy program applies it after ``MOVE <n> TO WS-EDIT-ALPHANUM-LENGTH``.
     * :param padded: the value already padded to its declared PIC width.
     * :param begin: inclusive start index of the slice.
     * :param end: exclusive end index of the slice.
     * :param variableName: the ``WS-EDIT-VARIABLE-NAME`` used to compose the message.
     * :raises CardDemoException: when the slice is blank, non-numeric or zero.
     */
    private void requireNumericSlice(String padded, int begin, int end, String variableName) {
        String slice = padded.substring(begin, end);
        if (slice.trim().isEmpty()) {
            throw new CardDemoException(variableName + SUFFIX_MUST_BE_SUPPLIED);
        }
        if (!slice.chars().allMatch(Character::isDigit)) {
            throw new CardDemoException(variableName + SUFFIX_ALL_NUMERIC);
        }
        if (Integer.parseInt(slice) == 0) {
            throw new CardDemoException(variableName + SUFFIX_NOT_ZERO);
        }
    }

    /**
     * :purpose: Reproduce a COBOL ``MOVE`` into a fixed-width alphanumeric field: the value is
     *     right-padded with spaces to the declared PIC width and truncated when longer, so the
     *     fixed-width slices the edits operate on always exist.
     * :param value: the submitted value, possibly ``null`` or shorter than the field.
     * :param width: the declared PIC width.
     * :returns: the value padded or truncated to exactly ``width`` characters.
     */
    private static String padToWidth(String value, int width) {
        String source = value == null ? "" : value;
        if (source.length() >= width) {
            return source.substring(0, width);
        }
        StringBuilder padded = new StringBuilder(source);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    /**
     * :purpose: ``1245-EDIT-NUM-REQD`` plus ``1275-EDIT-FICO-SCORE`` — the score must be
     *     supplied and must fall between 300 and 850 inclusive.
     * :param value: the submitted FICO score.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireFicoScore(Integer value) {
        if (value == null) {
            throw new CardDemoException(VAR_FICO_SCORE + SUFFIX_MUST_BE_SUPPLIED);
        }
        if (value < FICO_MIN || value > FICO_MAX) {
            throw new CardDemoException(VAR_FICO_SCORE + SUFFIX_FICO_RANGE);
        }
    }

    /**
     * :purpose: ``1225-EDIT-ALPHA-REQD`` — the value must be supplied and may contain
     *     only alphabetic characters and spaces.
     * :param value: the submitted value.
     * :param blankMessage: the message reported when the value is absent.
     * :param alphaMessage: the message reported when the value carries a non-alphabetic character.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireAlpha(String value, String blankMessage, String alphaMessage) {
        if (isBlank(value)) {
            throw new CardDemoException(blankMessage);
        }
        if (!isAlphaOrSpace(value)) {
            throw new CardDemoException(alphaMessage);
        }
    }

    /**
     * :purpose: ``1235-EDIT-ALPHA-OPT`` — an absent value passes, a supplied value may
     *     contain only alphabetic characters and spaces.
     * :param value: the submitted value.
     * :param alphaMessage: the message reported when the value carries a non-alphabetic character.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireOptionalAlpha(String value, String alphaMessage) {
        if (!isBlank(value) && !isAlphaOrSpace(value)) {
            throw new CardDemoException(alphaMessage);
        }
    }

    /**
     * :purpose: ``1215-EDIT-MANDATORY`` — the value must be supplied.
     * :param value: the submitted value.
     * :param message: the message reported when the value is absent.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireSupplied(String value, String message) {
        if (isBlank(value)) {
            throw new CardDemoException(message);
        }
    }

    /**
     * :purpose: ``1245-EDIT-NUM-REQD`` — the leading ``editLength`` characters of the value,
     *     taken from the field padded to its declared PIC width, must be supplied, all numeric
     *     and not zero. Only that leading slice is edited, exactly as the legacy program does
     *     after ``MOVE <editLength> TO WS-EDIT-ALPHANUM-LENGTH``.
     * :param value: the submitted value.
     * :param variableName: the ``WS-EDIT-VARIABLE-NAME`` used to compose the message.
     * :param editLength: number of leading characters the legacy edit inspects.
     * :param width: declared PIC width of the field.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireNumeric(String value, String variableName, int editLength, int width) {
        requireNumericSlice(padToWidth(value, width), 0, editLength, variableName);
    }

    /**
     * :purpose: ``1225-EDIT-ALPHA-REQD`` plus ``1270-EDIT-US-STATE-CD`` — the state code
     *     must be supplied, alphabetic and a two-character US state code.
     * :param value: the submitted state code.
     * :raises CardDemoException: when the edit fails.
     */
    private void requireStateCode(String value) {
        requireAlpha(value, VAR_STATE + SUFFIX_MUST_BE_SUPPLIED, VAR_STATE + SUFFIX_ALPHABETS_ONLY);
        if (!LookupCodes.VALID_US_STATE_CODE.contains(value.trim().toUpperCase(Locale.ROOT))) {
            throw new CardDemoException(VAR_STATE + SUFFIX_INVALID_STATE);
        }
    }

    /**
     * :purpose: ``1280-EDIT-US-STATE-ZIP-CD`` (COACTUPC L1664-1669): the state code
     *     concatenated with the first two zip digits must be a known combination. The legacy
     *     program performs this cross-field edit only once the state code and the zip have
     *     each passed their own edit, which is why it runs last here.
     * :param stateCode: the submitted state code.
     * :param zip: the submitted zip code.
     * :raises CardDemoException: when the combination is not a known one.
     */
    private void requireZipMatchesState(String stateCode, String zip) {
        String combination = padToWidth(stateCode, 2) + padToWidth(zip, ZIP_WIDTH).substring(0, 2);
        if (!LookupCodes.VALID_US_STATE_ZIP_CD2_COMBO.contains(combination.toUpperCase(Locale.ROOT))) {
            throw new CardDemoException(MSG_INVALID_ZIP_FOR_STATE);
        }
    }

    /**
     * :purpose: ``1260-EDIT-US-PHONE-NUM`` — the phone number must be supplied and carry
     *     a three-digit, non-zero area code.
     * :param value: the submitted phone number.
     * :param variableName: the ``WS-EDIT-VARIABLE-NAME`` used to compose the message.
     * :raises CardDemoException: when the edit fails.
     */
    private void requirePhone(String value, String variableName) {
        String[] parts = splitPhone(value);
        String areaCode = parts[0];
        String prefix = parts[1];
        String lineNumber = parts[2];

        // 1260-EDIT-US-PHONE-NUM: a phone number is NOT mandatory. When all three screen
        // fields are blank the edit reports valid and exits (COACTUPC L2232-2244).
        if (areaCode.isEmpty() && prefix.isEmpty() && lineNumber.isEmpty()) {
            return;
        }

        // EDIT-AREA-CODE.
        if (areaCode.isEmpty()) {
            throw new CardDemoException(variableName + SUFFIX_AREA_CODE_SUPPLIED);
        }
        if (areaCode.length() != 3 || !areaCode.chars().allMatch(Character::isDigit)) {
            throw new CardDemoException(variableName + SUFFIX_AREA_CODE_3_DIGIT);
        }
        if (Integer.parseInt(areaCode) == 0) {
            throw new CardDemoException(variableName + SUFFIX_AREA_CODE_ZERO);
        }
        if (!LookupCodes.VALID_GENERAL_PURP_CODE.contains(areaCode)) {
            throw new CardDemoException(variableName + SUFFIX_AREA_CODE_INVALID);
        }

        // EDIT-US-PHONE-PREFIX.
        if (prefix.isEmpty()) {
            throw new CardDemoException(variableName + SUFFIX_PREFIX_SUPPLIED);
        }
        if (prefix.length() != 3 || !prefix.chars().allMatch(Character::isDigit)) {
            throw new CardDemoException(variableName + SUFFIX_PREFIX_3_DIGIT);
        }
        if (Integer.parseInt(prefix) == 0) {
            throw new CardDemoException(variableName + SUFFIX_PREFIX_ZERO);
        }

        // EDIT-US-PHONE-LINENUM.
        if (lineNumber.isEmpty()) {
            throw new CardDemoException(variableName + SUFFIX_LINE_SUPPLIED);
        }
        if (lineNumber.length() != 4 || !lineNumber.chars().allMatch(Character::isDigit)) {
            throw new CardDemoException(variableName + SUFFIX_LINE_4_DIGIT);
        }
        if (Integer.parseInt(lineNumber) == 0) {
            throw new CardDemoException(variableName + SUFFIX_LINE_ZERO);
        }
    }

    /**
     * :purpose: Recover the three screen fields ``WS-EDIT-US-PHONE-NUMA`` / ``-NUMB`` / ``-NUMC``
     *     from the single ``PIC X(15)`` value the record and the REST contract carry. COACTUPC
     *     redefines that field as ``FILLER X(1)``, ``X(3)``, ``FILLER X(1)``, ``X(3)``,
     *     ``FILLER X(1)``, ``X(4)``, ``FILLER X(2)`` (L810-819), i.e. the fixed-width
     *     ``(aaa)ppp-llll`` presentation, so the parts are taken positionally.
     * :param value: the submitted phone number.
     * :returns: a three-element array of area code, prefix and line number, each trimmed.
     */
    private static String[] splitPhone(String value) {
        String padded = padToWidth(value, PHONE_WIDTH);
        return new String[]{
                padded.substring(1, 4).trim(),
                padded.substring(5, 8).trim(),
                padded.substring(9, 13).trim()};
    }

    /**
     * :purpose: Extract a fixed-position numeric slice of a ``YYYY-MM-DD`` value.
     * :param value: the submitted date.
     * :param begin: inclusive start index.
     * :param end: exclusive end index.
     * :returns: the parsed integer, or ``null`` when the slice is absent or not numeric.
     */
    private static Integer datePart(String value, int begin, int end) {
        if (value == null || value.length() < end) {
            return null;
        }
        String slice = value.substring(begin, end);
        if (!slice.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Integer.valueOf(slice);
    }

    /**
     * :purpose: Report whether every character is an ASCII letter or a space, matching
     *     the COBOL class test the alphabetic edits apply.
     * :param value: the value to test.
     * :returns: ``true`` when the value contains only letters and spaces.
     */
    private static boolean isAlphaOrSpace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean alpha = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
            if (!alpha && ch != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * :purpose: Report whether a value is absent or contains only whitespace.
     * :param value: the value to test.
     * :returns: ``true`` when the value is ``null``, empty or whitespace.
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
