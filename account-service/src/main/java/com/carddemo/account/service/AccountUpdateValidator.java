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
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.FieldValidationException;
import com.carddemo.common.util.DateUtil;
import com.carddemo.common.crypto.PiiMasker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * :purpose: Reproduce the field-level input edits of the legacy account-update program
 *     ``COACTUPC`` paragraph ``1200-EDIT-MAP-INPUTS`` (``app/cbl/COACTUPC.cbl`` L1429-1678),
 *     fail-fast and in the exact COBOL ``PERFORM`` order: account status, open date, credit
 *     limit, expiry date, cash credit limit, reissue date, current balance, current cycle
 *     credit, current cycle debit, SSN, date of birth, FICO score, first/middle/last name,
 *     address line 1, state, zip, address line 2, city, country, phone 1, phone 2, EFT account
 *     id and primary card holder.
 * :output: Returns normally when every edit passes; otherwise raises {@link
 *     CardDemoException} (HTTP 400) carrying the first failing message exactly as the legacy
 *     program would have moved it to ``WS-RETURN-MSG``. Only ONE message is ever produced,
 *     preserving the single-message screen contract.
 * :note: Every message literal is reproduced character-for-character from the source: the
 *     static forms come from the ``WS-RETURN-MSG`` 88-levels (COACTUPC L480-530) and the
 *     composed forms from the generic edit paragraphs
 *     (``1215``/``1220``/``1225``/``1235``/``1245``/``1250``/``1260``/``1265``/``1270`` and
 *     ``app/cpy/CSUTLDPY.cpy``), which build ``FUNCTION TRIM(WS-EDIT-VARIABLE-NAME)`` followed
 *     by the fixed suffix.
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
    public static final String SUFFIX_MUST_BE_YES_NO = " must be Y or N.";

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


    /**
     * :purpose: Complete the ``1265-EDIT-US-SSN`` and ``1245-EDIT-NUM-REQD`` numeric edits for
     *     the two identifiers the view masks. {@link #validate} runs before the record is
     *     read and so cannot tell an echoed mask ("nothing was typed here") from a value the
     *     operator edited THROUGH the mask ("****-**-1234"), which is not numeric and which
     *     the legacy program — displaying the identifiers in full — would have refused. This
     *     edit closes that gap once the stored values are available: only the stored value's
     *     own mask is accepted as an echo.
     * :param request: the submitted request.
     * :param customer: the stored customer master record whose identifiers were masked.
     * :raises FieldValidationException: when a mask-bearing value is not the stored mask.
     */
    public void validateMaskedIdentifiers(AccountUpdateRequestDto request, Customer customer) {
        if (request == null || customer == null) {
            return;
        }
        String ssn = request.getCustSsn();
        if (PiiMasker.isMaskShaped(ssn) && !PiiMasker.isMaskOf(ssn, customer.getCustSsn())) {
            throw new FieldValidationException(FIELD_CUST_SSN, SSN_PART1_LABEL + SUFFIX_ALL_NUMERIC);
        }
        String eft = request.getCustEftAccountId();
        if (PiiMasker.isMaskShaped(eft)
                && !PiiMasker.isMaskOf(eft, customer.getCustEftAccountId())) {
            throw new FieldValidationException(FIELD_CUST_EFT_ACCOUNT_ID,
                    VAR_EFT_ACCOUNT_ID + SUFFIX_ALL_NUMERIC);
        }
    }

    /**
     * :purpose: Run one edit and, if it fails, name the request property it refused. The
     *  legacy paragraphs each end their failure branch with ``MOVE -1 TO <field>L``, which is
     *  how a 3270 both highlighted the offending field and put the cursor in it; naming the
     *  property carries that same information to a screen that has no map to move a cursor
     *  on. The message, the exception type and the status are untouched, so an edit reports
     *  exactly what it reported before.
     * :param property: the request-payload property the edit covers.
     * :param edit: the edit to run.
     * :raises CardDemoException: the failure the edit raised, named against ``property``.
     */
    private void edit(String property, Runnable edit) {
        editFields(List.of(property), edit);
    }

    /**
     * :purpose: Run a cross-field edit and, when it fails, re-report the failure against every
     *     member the legacy program flags for it, cursor field first.
     * :param fields: the request-body members the edit covers, cursor field first.
     * :param edits: the edits to run.
     * :raises FieldValidationException: carrying the failing message and ``fields``.
     */
    private void editFields(List<String> fields, Runnable edits) {
        try {
            edits.run();
        } catch (FieldValidationException alreadyIdentified) {
            throw alreadyIdentified;
        } catch (CardDemoException failure) {
            throw new FieldValidationException(failure.getErrorCode(), fields, failure.getMessage());
        }
    }

    /**
     * :purpose: ``EDIT-DATE-OF-BIRTH`` failure literal, composed by the legacy ``STRING`` statement
     *     from the trimmed variable name and ``':cannot be in the future '``
     *     (``app/cpy/CSUTLDPY.cpy``). The leading colon and the trailing space are part of the
     *     literal and are preserved verbatim.
     */
    private static final String SUFFIX_CANNOT_BE_FUTURE = ":cannot be in the future ";

    /**
     * :purpose: Composed suffix reporting that a value is longer than the fixed-width field it
     *     is written into. The 3270 map bounded every field physically, so the legacy program
     *     carries no literal for this outcome; the wording follows the composed
     *     ``WS-EDIT-VARIABLE-NAME`` + suffix convention the other edits use. Without the edit
     *     an oversized value reached the column and surfaced as a server error rather than as
     *     the client input error it is.
     */
    private static final String SUFFIX_MAX_LENGTH_PREFIX = " must be at most ";

    /** :purpose: Trailing part of the width message; see {@link #SUFFIX_MAX_LENGTH_PREFIX}. */
    private static final String SUFFIX_MAX_LENGTH_SUFFIX = " characters.";

    /** :purpose: Singular form of {@link #SUFFIX_MAX_LENGTH_SUFFIX} for a one-character field. */
    private static final String SUFFIX_MAX_LENGTH_SINGULAR = " character.";

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
    public static final String VAR_ACCOUNT_STATUS = "Account Status";
    private static final String VAR_ACCOUNT_GROUP = "Account Group";
    private static final String VAR_LAST_NAME = "Last Name";
    private static final String VAR_ADDRESS_LINE_2 = "Address Line 2";
    private static final String VAR_GOVT_ISSUED_ID = "Govt Issued Id";

    // -- AccountUpdateRequestDto member names reported as the faulted field. --------
    //
    // A rejected edit names the REQUEST member it covers, not a 3270 field: the screen
    // splits the dates, the SSN and the phone numbers across several entry fields and owns
    // that mapping. Every value below matches the corresponding
    // ``AccountUpdateRequestDto`` property exactly.

    private static final String FIELD_ACCT_ACTIVE_STATUS = "acctActiveStatus";
    private static final String FIELD_ACCT_OPEN_DATE = "acctOpenDate";
    private static final String FIELD_ACCT_CREDIT_LIMIT = "acctCreditLimit";
    private static final String FIELD_ACCT_EXPIRAION_DATE = "acctExpiraionDate";
    private static final String FIELD_ACCT_CASH_CREDIT_LIMIT = "acctCashCreditLimit";
    private static final String FIELD_ACCT_REISSUE_DATE = "acctReissueDate";
    private static final String FIELD_ACCT_CURR_BAL = "acctCurrBal";
    private static final String FIELD_ACCT_CURR_CYC_CREDIT = "acctCurrCycCredit";
    private static final String FIELD_ACCT_CURR_CYC_DEBIT = "acctCurrCycDebit";
    private static final String FIELD_ACCT_GROUP_ID = "acctGroupId";
    private static final String FIELD_CUST_SSN = "custSsn";
    private static final String FIELD_CUST_DOB = "custDobYyyyMmDd";
    private static final String FIELD_CUST_FICO_SCORE = "custFicoCreditScore";
    private static final String FIELD_CUST_FIRST_NAME = "custFirstName";
    private static final String FIELD_CUST_MIDDLE_NAME = "custMiddleName";
    private static final String FIELD_CUST_LAST_NAME = "custLastName";
    private static final String FIELD_CUST_ADDR_LINE_1 = "custAddrLine1";
    private static final String FIELD_CUST_ADDR_LINE_2 = "custAddrLine2";
    private static final String FIELD_CUST_ADDR_LINE_3 = "custAddrLine3";
    private static final String FIELD_CUST_ADDR_STATE_CD = "custAddrStateCd";
    private static final String FIELD_CUST_ADDR_ZIP = "custAddrZip";
    private static final String FIELD_CUST_ADDR_COUNTRY_CD = "custAddrCountryCd";
    private static final String FIELD_CUST_PHONE_NUM_1 = "custPhoneNum1";
    private static final String FIELD_CUST_PHONE_NUM_2 = "custPhoneNum2";
    private static final String FIELD_CUST_EFT_ACCOUNT_ID = "custEftAccountId";
    private static final String FIELD_CUST_GOVT_ISSUED_ID = "custGovtIssuedId";
    private static final String FIELD_CUST_PRI_CARD_HOLDER = "custPriCardHolderInd";

    /** :purpose: Lowest FICO score the legacy range check accepts. */
    private static final int FICO_MIN = 300;

    /** :purpose: Highest FICO score the legacy range check accepts. */
    private static final int FICO_MAX = 850;

    /** :purpose: Declared width of ``ACCT-ACTIVE-STATUS`` ``PIC X(01)``. */
    private static final int ACCT_STATUS_WIDTH = 1;

    /** :purpose: Declared width of ``ACCT-GROUP-ID`` ``PIC X(10)``. */
    private static final int ACCT_GROUP_ID_WIDTH = 10;

    /** :purpose: Declared width of every ``CUST-*-NAME`` field, ``PIC X(25)``. */
    private static final int NAME_WIDTH = 25;

    /** :purpose: Declared width of every ``CUST-ADDR-LINE-n`` field, ``PIC X(50)``. */
    private static final int ADDRESS_WIDTH = 50;

    /** :purpose: Declared width of ``CUST-ADDR-COUNTRY-CD`` ``PIC X(03)``. */
    private static final int COUNTRY_CODE_WIDTH = 3;

    /** :purpose: Declared width of ``CUST-GOVT-ISSUED-ID`` ``PIC X(20)``. */
    private static final int GOVT_ID_WIDTH = 20;

    /** :purpose: Monetary scale of every ``S9(09)V99`` COMP-3 amount. */
    private static final int MONEY_SCALE = 2;

    /**
     * :purpose: Integer-digit capacity of every account amount, from ``PIC S9(10)V99``
     *     [app/cpy/CVACT01Y.cpy:L7-L14] and its ``NUMERIC(12,2)`` column: 12 total digits less the
     *     2 fractional ones. The largest representable amount is ``9999999999.99``.
     */
    private static final int MONEY_INTEGER_DIGITS = 10;

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

        // Record-layout widths (app/cpy/CVACT01Y.cpy, app/cpy/CVCUS01Y.cpy). Each width edit
        // sits at its field's position in the PERFORM order, so the message a submission with
        // several faults reports is still the one the legacy screen would have shown.
        edit(FIELD_ACCT_ACTIVE_STATUS, () ->
                requireMaxLength(request.getAcctActiveStatus(), VAR_ACCOUNT_STATUS, ACCT_STATUS_WIDTH));

        // 1220-EDIT-YESNO on 'Account Status'. The paragraph composes both of its messages
        // from WS-EDIT-VARIABLE-NAME, which L1472-1475 sets to 'Account Status' before
        // performing it for this field. The 88-level ACCT-STATUS-MUST-BE-YES-NO at L503-504
        // carries the wording 'Account Active Status must be Y or N' but is never SET
        // anywhere in the program, so it is unreachable and the composed form is what a
        // terminal displays.
        edit(FIELD_ACCT_ACTIVE_STATUS, () -> requireYesNo(request.getAcctActiveStatus(), VAR_ACCOUNT_STATUS));

        // EDIT-DATE-CCYYMMDD on 'Open Date'.
        edit(FIELD_ACCT_OPEN_DATE, () -> requireValidDate(request.getAcctOpenDate(), VAR_OPEN_DATE));

        // 1250-EDIT-SIGNED-9V2 on 'Credit Limit' — the only amount with its own
        // dedicated 88-level messages.
        edit(FIELD_ACCT_CREDIT_LIMIT, () ->
                requireAmount(request.getAcctCreditLimit(),
                        MSG_CREDIT_LIMIT_BLANK, MSG_CREDIT_LIMIT_INVALID));

        // EDIT-DATE-CCYYMMDD on 'Expiry Date'; the month and year failures carry the
        // account program's own dedicated literals.
        edit(FIELD_ACCT_EXPIRAION_DATE, () -> requireValidExpiryDate(request.getAcctExpiraionDate()));

        // 1250-EDIT-SIGNED-9V2 on the remaining amounts, in COBOL order.
        edit(FIELD_ACCT_CASH_CREDIT_LIMIT, () -> requireAmount(request.getAcctCashCreditLimit(),
                VAR_CASH_CREDIT_LIMIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CASH_CREDIT_LIMIT + SUFFIX_NOT_VALID));

        // EDIT-DATE-CCYYMMDD on 'Reissue Date'.
        edit(FIELD_ACCT_REISSUE_DATE, () -> requireValidDate(request.getAcctReissueDate(), VAR_REISSUE_DATE));

        edit(FIELD_ACCT_CURR_BAL, () -> requireAmount(request.getAcctCurrBal(),
                VAR_CURRENT_BALANCE + SUFFIX_MUST_BE_SUPPLIED, VAR_CURRENT_BALANCE + SUFFIX_NOT_VALID));
        edit(FIELD_ACCT_CURR_CYC_CREDIT, () -> requireAmount(request.getAcctCurrCycCredit(),
                VAR_CURR_CYC_CREDIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CURR_CYC_CREDIT + SUFFIX_NOT_VALID));
        edit(FIELD_ACCT_CURR_CYC_DEBIT, () -> requireAmount(request.getAcctCurrCycDebit(),
                VAR_CURR_CYC_DEBIT + SUFFIX_MUST_BE_SUPPLIED, VAR_CURR_CYC_DEBIT + SUFFIX_NOT_VALID));

        // 'Account Group' has no legacy edit of its own -- COACTUPC displays it and rewrites it
        // unchecked -- so its declared X(10) width is the only constraint that applies.
        edit(FIELD_ACCT_GROUP_ID, () ->
                requireMaxLength(request.getAcctGroupId(), VAR_ACCOUNT_GROUP, ACCT_GROUP_ID_WIDTH));

        // 1265-EDIT-US-SSN: the screen carries three separate SSN fields, each edited by
        // 1245-EDIT-NUM-REQD over its own fixed width, with the range check applied only to
        // part 1 and only when part 1 already passed.
        edit(FIELD_CUST_SSN, () -> requireSsn(request.getCustSsn()));

        // EDIT-DATE-CCYYMMDD plus EDIT-DATE-OF-BIRTH on 'Date of Birth'. Both edits, in this
        // order: COACTUPC runs the calendar edit first and performs the reasonableness edit only
        // when the date already parsed [app/cbl/COACTUPC.cbl:L1533-L1541], so an unparseable value
        // reports its own calendar message rather than the future-date one. Both are reported
        // against the same screen field so the caller can position the cursor on it.
        edit(FIELD_CUST_DOB, () -> requireValidDate(request.getCustDobYyyyMmDd(), VAR_DATE_OF_BIRTH));
        edit(FIELD_CUST_DOB, () -> requireDateOfBirthNotInFuture(request.getCustDobYyyyMmDd()));

        // 1245-EDIT-NUM-REQD plus 1275-EDIT-FICO-SCORE on 'FICO Score'.
        edit(FIELD_CUST_FICO_SCORE, () -> requireFicoScore(request.getCustFicoCreditScore()));

        // 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT on the names. The dedicated
        // 88-level literals win over the generic composed forms here.
        edit(FIELD_CUST_FIRST_NAME, () ->
                requireAlpha(request.getCustFirstName(),
                        VAR_FIRST_NAME + SUFFIX_MUST_BE_SUPPLIED, MSG_NAME_ALPHA));
        edit(FIELD_CUST_FIRST_NAME, () -> requireMaxLength(request.getCustFirstName(), VAR_FIRST_NAME, NAME_WIDTH));
        edit(FIELD_CUST_MIDDLE_NAME, () ->
                requireOptionalAlpha(request.getCustMiddleName(), VAR_MIDDLE_NAME + SUFFIX_ALPHABETS_ONLY));
        edit(FIELD_CUST_MIDDLE_NAME, () ->
                requireMaxLength(request.getCustMiddleName(), VAR_MIDDLE_NAME, NAME_WIDTH));
        edit(FIELD_CUST_LAST_NAME, () ->
                requireAlpha(request.getCustLastName(), MSG_LASTNAME_NOT_PROVIDED, MSG_NAME_ALPHA));
        edit(FIELD_CUST_LAST_NAME, () -> requireMaxLength(request.getCustLastName(), VAR_LAST_NAME, NAME_WIDTH));

        // 1215-EDIT-MANDATORY on 'Address Line 1'.
        edit(FIELD_CUST_ADDR_LINE_1, () ->
                requireSupplied(request.getCustAddrLine1(), VAR_ADDRESS_LINE_1 + SUFFIX_MUST_BE_SUPPLIED));
        edit(FIELD_CUST_ADDR_LINE_1, () ->
                requireMaxLength(request.getCustAddrLine1(), VAR_ADDRESS_LINE_1, ADDRESS_WIDTH));
        edit(FIELD_CUST_ADDR_LINE_2, () ->
                requireMaxLength(request.getCustAddrLine2(), VAR_ADDRESS_LINE_2, ADDRESS_WIDTH));

        // 1225-EDIT-ALPHA-REQD plus 1270-EDIT-US-STATE-CD on 'State'.
        edit(FIELD_CUST_ADDR_STATE_CD, () -> requireStateCode(request.getCustAddrStateCd()));

        // 1245-EDIT-NUM-REQD on 'Zip'. Only the FIRST FIVE characters are edited
        // (COACTUPC L1607 moves 5 into WS-EDIT-ALPHANUM-LENGTH), so a ZIP+4 value passes.
        edit(FIELD_CUST_ADDR_ZIP, () ->
                requireNumeric(request.getCustAddrZip(), VAR_ZIP, ZIP_EDIT_LENGTH, ZIP_WIDTH));

        // 1225-EDIT-ALPHA-REQD on 'City' and 'Country'. CVCUS01Y carries no separate
        // city column: COACTUPC edits 'City' against CUST-ADDR-LINE-3.
        edit(FIELD_CUST_ADDR_LINE_3, () ->
                requireAlpha(request.getCustAddrLine3(), VAR_CITY + SUFFIX_MUST_BE_SUPPLIED,
                        VAR_CITY + SUFFIX_ALPHABETS_ONLY));
        edit(FIELD_CUST_ADDR_LINE_3, () -> requireMaxLength(request.getCustAddrLine3(), VAR_CITY, ADDRESS_WIDTH));
        edit(FIELD_CUST_ADDR_COUNTRY_CD, () ->
                requireAlpha(request.getCustAddrCountryCd(),
                        VAR_COUNTRY + SUFFIX_MUST_BE_SUPPLIED, VAR_COUNTRY + SUFFIX_ALPHABETS_ONLY));
        edit(FIELD_CUST_ADDR_COUNTRY_CD, () ->
                requireMaxLength(request.getCustAddrCountryCd(), VAR_COUNTRY, COUNTRY_CODE_WIDTH));

        // 1260-EDIT-US-PHONE-NUM on both phone numbers.
        edit(FIELD_CUST_PHONE_NUM_1, () -> requirePhone(request.getCustPhoneNum1(), VAR_PHONE_NUMBER_1));
        edit(FIELD_CUST_PHONE_NUM_2, () -> requirePhone(request.getCustPhoneNum2(), VAR_PHONE_NUMBER_2));

        // 1245-EDIT-NUM-REQD on 'EFT Account Id' over its declared 10-character width. The
        // view masks this identifier too (AAP 0.6.7), so an echoed mask carries no new value
        // and there is nothing to edit, exactly as for the SSN above.
        if (!PiiMasker.isMaskShaped(request.getCustEftAccountId())) {
            edit(FIELD_CUST_EFT_ACCOUNT_ID, () -> requireNumeric(request.getCustEftAccountId(), VAR_EFT_ACCOUNT_ID,
                    EFT_EDIT_LENGTH, EFT_WIDTH));
            edit(FIELD_CUST_EFT_ACCOUNT_ID, () ->
                    requireMaxLength(request.getCustEftAccountId(), VAR_EFT_ACCOUNT_ID, EFT_WIDTH));
        }

        // 'Govt Issued Id' carries no legacy edit either, so only its X(20) width applies. A
        // masked echo of the stored identifier is not a new value and is left alone.
        if (!PiiMasker.isMaskShaped(request.getCustGovtIssuedId())) {
            edit(FIELD_CUST_GOVT_ISSUED_ID, () ->
                    requireMaxLength(request.getCustGovtIssuedId(), VAR_GOVT_ISSUED_ID, GOVT_ID_WIDTH));
        }

        // 1220-EDIT-YESNO on 'Primary Card Holder'.
        edit(FIELD_CUST_PRI_CARD_HOLDER, () ->
                requireYesNo(request.getCustPriCardHolderInd(), VAR_PRIMARY_CARD_HOLDER));

        // Cross-field edit (COACTUPC L1664-1669): performed only once the state code and the
        // zip have each passed their own edit. It sets FLG-STATE-NOT-OK *and*
        // FLG-ZIPCODE-NOT-OK, so both members are reported as faulted.
        editFields(List.of(FIELD_CUST_ADDR_STATE_CD, FIELD_CUST_ADDR_ZIP),
                () -> requireZipMatchesState(request.getCustAddrStateCd(), request.getCustAddrZip()));
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
    private void requireYesNo(String value, String variableName) {
        // 1220-EDIT-YESNO tests 'Not supplied' first (LOW-VALUES, SPACES or ZEROS) and
        // reports it with its own composed message, then tests the value itself.
        if (value == null || value.isBlank() || value.chars().allMatch(c -> c == '0')) {
            throw new CardDemoException(variableName + SUFFIX_MUST_BE_SUPPLIED);
        }
        if (!"Y".equals(value) && !"N".equals(value)) {
            throw new CardDemoException(variableName + SUFFIX_MUST_BE_YES_NO);
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
        // Ten integer digits, not nine. Every account amount is declared PIC S9(10)V99
        // [app/cpy/CVACT01Y.cpy:L7-L14] and stored in a NUMERIC(12,2) column, so 9999999999.99 is
        // a legal value. Capping at nine made an account that legitimately holds a ten-digit
        // amount impossible to update AT ALL -- every full-snapshot PUT was rejected on the
        // unchanged field, whichever field the caller was actually editing.
        if (value.precision() - value.scale() > MONEY_INTEGER_DIGITS) {
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
     * :purpose: ``EDIT-DATE-OF-BIRTH`` (``app/cpy/CSUTLDPY.cpy``) — the reasonableness edit that
     *     follows the calendar edit on 'Date of Birth'. The legacy paragraph passes only when
     *     ``WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY``, so a date of birth must fall STRICTLY
     *     before today; today itself is rejected, and that boundary is reproduced exactly.
     * :param value: the submitted date of birth, already known to be a real calendar date because
     *     the calendar edit runs first and throws on failure.
     * :raises CardDemoException: with ``'Date of Birth:cannot be in the future '`` when the date is
     *     today or later. The literal, including its leading colon and trailing space, is the one
     *     the legacy ``STRING`` statement composes from the trimmed variable name.
     */
    private void requireDateOfBirthNotInFuture(String value) {
        if (!DateUtil.isValidDateOfBirth(value, DateUtil.MASK_ISO)) {
            throw new CardDemoException(VAR_DATE_OF_BIRTH + SUFFIX_CANNOT_BE_FUTURE);
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
     * :note: The zero test compares the digits themselves rather than parsing them. The legacy
     *     edit is ``IF WS-EDIT-ALPHANUM-ONLY(1:length) = ZEROS``, a character comparison over a
     *     ``PIC X`` field of any width, whereas parsing a ten-digit ``CUST-EFT-ACCOUNT-ID``
     *     overflows a 32-bit integer and turned a perfectly valid identifier into a server
     *     error.
     */
    private void requireNumericSlice(String padded, int begin, int end, String variableName) {
        String slice = padded.substring(begin, end);
        if (slice.trim().isEmpty()) {
            throw new CardDemoException(variableName + SUFFIX_MUST_BE_SUPPLIED);
        }
        if (!slice.chars().allMatch(Character::isDigit)) {
            throw new CardDemoException(variableName + SUFFIX_ALL_NUMERIC);
        }
        if (slice.chars().allMatch(digit -> digit == '0')) {
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
     * :purpose: Map each numerically-typed request property to the message its screen edit reports
     *     for an invalid value, so a value that fails to BIND reports the same thing as a value
     *     that binds and then fails the edit.
     * :returns: an immutable property-name-to-message map covering every numeric property of the
     *     account update request.
     * :note: Exposed from this class, and composed here from the same constants the edits use, so
     *     each frozen literal exists exactly once. A copy kept beside the JSON layer would be free
     *     to drift away from the edit that owns it.
     * :note: A numeric property is converted by the JSON layer BEFORE any edit runs, so
     *     ``"acctCreditLimit": "ABC"`` fails during deserialization and
     *     {@link #requireAmount} never sees it. Without this map such a request reported only
     *     ``'Malformed request body'``, naming no field and losing the frozen literal.
     */
    static Map<String, String> typeMismatchMessages() {
        return Map.of(
                "acctCurrBal", VAR_CURRENT_BALANCE + SUFFIX_NOT_VALID,
                "acctCreditLimit", MSG_CREDIT_LIMIT_INVALID,
                "acctCashCreditLimit", VAR_CASH_CREDIT_LIMIT + SUFFIX_NOT_VALID,
                "acctCurrCycCredit", VAR_CURR_CYC_CREDIT + SUFFIX_NOT_VALID,
                "acctCurrCycDebit", VAR_CURR_CYC_DEBIT + SUFFIX_NOT_VALID,
                "custFicoCreditScore", VAR_FICO_SCORE + SUFFIX_FICO_RANGE);
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
     * :purpose: Refuse a value longer than the fixed-width field it is written into. A COBOL
     *     ``MOVE`` into a shorter ``PIC X(n)`` field truncates silently and the 3270 map could
     *     not accept the extra characters at all, so neither storing a truncated value nor
     *     letting the column reject it downstream is acceptable: the submission is refused
     *     with the field's own composed message.
     * :param value: the submitted value; an absent value passes and is left to the presence edit.
     * :param variableName: the ``WS-EDIT-VARIABLE-NAME`` of the field.
     * :param maxLength: the declared record-layout width.
     * :raises CardDemoException: when the trimmed value exceeds ``maxLength``.
     */
    private void requireMaxLength(String value, String variableName, int maxLength) {
        if (value == null) {
            return;
        }
        if (value.trim().length() > maxLength) {
            throw new CardDemoException(variableName + SUFFIX_MAX_LENGTH_PREFIX + maxLength
                    + (maxLength == 1 ? SUFFIX_MAX_LENGTH_SINGULAR : SUFFIX_MAX_LENGTH_SUFFIX));
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
