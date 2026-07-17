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
package com.aws.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aws.carddemo.account.dto.AccountUpdateRequest;
import com.aws.carddemo.account.exception.ValidationException;

/**
 * Exhaustive unit tests for {@link AccountValidator}, the service-layer component
 * that reproduces the legacy CardDemo COBOL field edits behaviorally.
 *
 * <p>These are <strong>plain</strong> JUnit&nbsp;5 + AssertJ tests with no
 * application context, no mocking library, and no database. The validator holds no
 * state and has no injected collaborators, so it is instantiated directly via
 * {@code new AccountValidator()} and exercised rule-by-rule.</p>
 *
 * <p>Each test group traces to a row of the AAP &sect;0.6.5 validation-rule
 * traceability matrix and to a legacy source paragraph:</p>
 * <ul>
 *   <li>{@link #accountIdTests Account id} &mdash; {@code 1210-EDIT-ACCOUNT}
 *       [app/cbl/COACTUPC.cbl:L1783-L1822].</li>
 *   <li>{@link #activeStatusTests Active status} &mdash; {@code 1220-EDIT-YESNO}
 *       [app/cbl/COACTUPC.cbl:L1856-L1896].</li>
 *   <li>{@link #amountTests Monetary range/scale} &mdash; {@code 1250-EDIT-SIGNED-9V2}
 *       [app/cbl/COACTUPC.cbl:L2180-L2223].</li>
 *   <li>{@link #dateTests Date validity + century window} &mdash; the
 *       {@code EDIT-DATE-CCYYMMDD} chain [app/cpy/CSUTLDPY.cpy:L25-L282].</li>
 *   <li>{@link #orchestrationTests Orchestration / fail-fast ordering} &mdash;
 *       {@code COACTUPC} edit sequence.</li>
 * </ul>
 *
 * <p>All monetary values are constructed exclusively from {@link String} literals
 * via the {@link BigDecimal} string constructor, which preserves exact decimal
 * precision as required by AAP &sect;0.6.2 (binary fractional primitive types are
 * deliberately never used). Exception messages are asserted verbatim against the
 * confirmed validator contract.</p>
 */
class AccountValidatorTest {

    /** Representative monetary field name reused across the amount assertions. */
    private static final String MONEY_FIELD = "currentBalance";

    /** Representative date field name reused across the date assertions. */
    private static final String DATE_FIELD = "openDate";

    /** Instance under test; recreated fresh before every test for isolation. */
    private AccountValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AccountValidator();
    }

    // ------------------------------------------------------------------
    // validateAccountId — legacy 1210-EDIT-ACCOUNT
    // AAP §0.6.5 "Account-id 11-digit numeric"
    // ------------------------------------------------------------------

    /**
     * A canonical 11-digit, zero-padded, non-zero identifier is accepted.
     * Mirrors seed record #1 ({@code 00000000001}) [app/data/ASCII/acctdata.txt].
     */
    @Test
    void accountIdTests() {
        assertThatCode(() -> validator.validateAccountId("00000000001"))
                .doesNotThrowAnyException();
    }

    /** Additional valid keys (max non-zero and a mid-range value) are accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"00000000001", "99999999999", "00000123456", "12345678901"})
    void validateAccountId_accepts11DigitNonZero(String accountId) {
        assertThatCode(() -> validator.validateAccountId(accountId))
                .doesNotThrowAnyException();
    }

    /** A {@code null} identifier maps to the legacy blank branch. */
    @Test
    void validateAccountId_rejectsNull() {
        assertThatThrownBy(() -> validator.validateAccountId(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("accountId must be supplied.");
    }

    /** Empty and whitespace-only identifiers map to the legacy blank branch. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void validateAccountId_rejectsBlank(String accountId) {
        assertThatThrownBy(() -> validator.validateAccountId(accountId))
                .isInstanceOf(ValidationException.class)
                .hasMessage("accountId must be supplied.");
    }

    /**
     * Wrong-length, non-numeric, mixed, and all-zeros identifiers are rejected with
     * the verbatim legacy literal (note: no trailing period) from
     * [app/cbl/COACTUPC.cbl:L1806-L1808]. The all-zeros case reproduces the legacy
     * {@code CC-ACCT-ID-N EQUAL ZEROS} non-zero rule [app/cbl/COACTUPC.cbl:L1803].
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "1",              // too short (1 digit)
        "1234567890",     // too short (10 digits)
        "123456789012",   // too long (12 digits)
        "abcdefghijk",    // non-numeric (11 letters)
        "0000000000a",    // mixed digits + letter
        "12345 67890",    // embedded space
        "00000000000"     // all zeros -> non-zero rule
    })
    void validateAccountId_rejectsInvalidShape(String accountId) {
        assertThatThrownBy(() -> validator.validateAccountId(accountId))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account Number if supplied must be a 11 digit Non-Zero Number");
    }

    // ------------------------------------------------------------------
    // validateActiveStatus — legacy 1220-EDIT-YESNO
    // AAP §0.6.5 "Active status ∈ {Y, N}"
    // ------------------------------------------------------------------

    /** Exactly {@code "Y"} and {@code "N"} (uppercase) are accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"Y", "N"})
    void activeStatusTests(String status) {
        assertThatCode(() -> validator.validateActiveStatus(status))
                .doesNotThrowAnyException();
    }

    /** A {@code null} status maps to the legacy blank branch. */
    @Test
    void validateActiveStatus_rejectsNull() {
        assertThatThrownBy(() -> validator.validateActiveStatus(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("activeStatus must be supplied.");
    }

    /** Empty and whitespace-only statuses map to the legacy blank branch. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void validateActiveStatus_rejectsBlank(String status) {
        assertThatThrownBy(() -> validator.validateActiveStatus(status))
                .isInstanceOf(ValidationException.class)
                .hasMessage("activeStatus must be supplied.");
    }

    /**
     * Any value other than the exact uppercase literals is rejected. The lowercase
     * {@code "y"}/{@code "n"} cases prove the comparison is case-SENSITIVE, a critical
     * parity detail matching the legacy {@code FLG-YES-NO-ISVALID} flag.
     */
    @ParameterizedTest
    @ValueSource(strings = {"X", "y", "n", "YN", "1", "yes", "No"})
    void validateActiveStatus_rejectsOutOfDomain(String status) {
        assertThatThrownBy(() -> validator.validateActiveStatus(status))
                .isInstanceOf(ValidationException.class)
                .hasMessage("activeStatus must be Y or N.");
    }

    // ------------------------------------------------------------------
    // validateAmount — legacy 1250-EDIT-SIGNED-9V2
    // AAP §0.6.5 "Monetary range ±9,999,999,999.99, scale 2"
    // Evaluation order: null -> scale -> range. Every BigDecimal is built
    // from a String literal to preserve exact decimal precision per AAP §0.6.2.
    // ------------------------------------------------------------------

    /**
     * Values within the inclusive &plusmn;9,999,999,999.99 bound and with scale
     * &le; 2 are accepted. Includes both signed boundary values (range is evaluated
     * on the magnitude via {@code abs()}), zero, a negative amount, and scale-0/1/2
     * forms.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "9999999999.99",    // inclusive positive boundary
        "-9999999999.99",   // inclusive negative boundary
        "0.00",
        "-500.00",
        "0",                // scale 0
        "1000",             // scale 0
        "100.5",            // scale 1
        "1234567890.12"     // large in-range, scale 2
    })
    void amountTests(String value) {
        assertThatCode(() -> validator.validateAmount(new BigDecimal(value), MONEY_FIELD))
                .doesNotThrowAnyException();
    }

    /** A {@code null} amount maps to the legacy blank branch. */
    @Test
    void validateAmount_rejectsNull() {
        assertThatThrownBy(() -> validator.validateAmount(null, MONEY_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentBalance must be supplied.");
    }

    /**
     * Values whose magnitude exceeds the &plusmn;9,999,999,999.99 bound are rejected,
     * for both signs. Each carries scale 2 so it clears the scale check and fails
     * only on range.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "10000000000.00",   // just over positive boundary
        "-10000000000.00",  // just over negative boundary
        "10000000000.99",
        "99999999999.99"
    })
    void validateAmount_rejectsOutOfRange(String value) {
        assertThatThrownBy(() -> validator.validateAmount(new BigDecimal(value), MONEY_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentBalance must be within +/-9,999,999,999.99.");
    }

    /**
     * Values with more than two fraction digits are rejected on scale. The small-
     * magnitude cases ({@code 1.000}, {@code 0.001}) prove scale is checked BEFORE
     * range (they are in range but carry the wrong scale).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "1.234",    // scale 3
        "1.000",    // scale 3, in range -> proves ordering (scale before range)
        "0.001",    // scale 3, tiny magnitude
        "-5.555"    // scale 3, negative
    })
    void validateAmount_rejectsWrongScale(String value) {
        assertThatThrownBy(() -> validator.validateAmount(new BigDecimal(value), MONEY_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentBalance must have at most 2 decimal places.");
    }

    /** The error message is built from the supplied field name (sanitized, name-only). */
    @Test
    void validateAmount_usesSuppliedFieldNameInMessage() {
        assertThatThrownBy(() -> validator.validateAmount(null, "creditLimit"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("creditLimit must be supplied.");
    }

    // ------------------------------------------------------------------
    // validateDate — legacy EDIT-DATE-CCYYMMDD chain [app/cpy/CSUTLDPY.cpy]
    // AAP §0.6.5 month/day/leap/year rows. Two DISTINCT failure messages:
    //   * strict-parse failure  -> "... must be a valid date in yyyy-MM-dd format."
    //   * parses but year guard -> "... year must be between 1900 and 2099."
    // ------------------------------------------------------------------

    /**
     * Strictly valid ISO dates within the 1900&ndash;2099 window are accepted,
     * including the leap cases that prove the exact Gregorian rule: {@code 2000-02-29}
     * (century &divide;400) and {@code 2024-02-29} (&divide;4), plus the inclusive
     * year boundaries {@code 1900-01-01} and {@code 2099-12-31}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2014-11-20",   // matches seed data
        "2000-02-29",   // leap: divisible by 400
        "2024-02-29",   // leap: divisible by 4
        "1900-01-01",   // inclusive lower year boundary
        "2099-12-31"    // inclusive upper year boundary
    })
    void dateTests(String value) {
        assertThatCode(() -> validator.validateDate(value, DATE_FIELD))
                .doesNotThrowAnyException();
    }

    /** A {@code null} date maps to the legacy blank branch. */
    @Test
    void validateDate_rejectsNull() {
        assertThatThrownBy(() -> validator.validateDate(null, DATE_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate must be supplied.");
    }

    /** Empty and whitespace-only dates map to the legacy blank branch. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    void validateDate_rejectsBlank(String value) {
        assertThatThrownBy(() -> validator.validateDate(value, DATE_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate must be supplied.");
    }

    /**
     * Calendar-invalid and malformed dates fail STRICT parsing and yield the
     * invalid-date-format message. Covers non-leap Feb 29 ({@code 2023-02-29}),
     * century non-leap ({@code 1900-02-29}), month 13/00, 31-in-a-30-day-month,
     * 30-February, non-zero-padded ({@code 2014-1-5}), and structurally wrong forms.
     * These parse-failures are DISTINCT from the year-guard failures below.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "2023-02-29",   // non-leap Feb 29
        "1900-02-29",   // century year not divisible by 400
        "2014-13-01",   // month 13
        "2014-00-01",   // month 00
        "2024-04-31",   // 31 in a 30-day month (April)
        "2024-02-30",   // 30 February
        "2014-1-5",     // not zero-padded (STRICT requires 2-digit month/day)
        "notadate",     // not a date at all
        "2014/11/20",   // wrong separators
        "20141120"      // no separators
    })
    void validateDate_rejectsInvalidCalendarDate(String value) {
        assertThatThrownBy(() -> validator.validateDate(value, DATE_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate must be a valid date in yyyy-MM-dd format.");
    }

    /**
     * Dates that ARE valid on the calendar but fall outside the 1900&ndash;2099
     * century window parse successfully and then fail the explicit year guard,
     * producing the distinct year-range message. This is the KEY distinction from
     * the parse failures above &mdash; the two messages must not be conflated.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "1899-05-01",   // just below lower bound
        "2100-05-01",   // just above upper bound
        "1800-01-01",   // well below
        "2500-12-31"    // well above
    })
    void validateDate_rejectsYearOutsideCenturyWindow(String value) {
        assertThatThrownBy(() -> validator.validateDate(value, DATE_FIELD))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate year must be between 1900 and 2099.");
    }

    /** The error message is built from the supplied field name. */
    @Test
    void validateDate_usesSuppliedFieldNameInMessage() {
        assertThatThrownBy(() -> validator.validateDate(null, "expirationDate"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("expirationDate must be supplied.");
    }

    // ------------------------------------------------------------------
    // validateAddressZip — presence check (contract parity with the
    // NOT NULL address_zip column). AAP §0.6.5 "Data-field parity incl.
    // addressZip always-present (§0.7.1)"; legacy ACCT-ADDR-ZIP PIC X(10).
    // ------------------------------------------------------------------

    /**
     * A {@code null} (omitted) address ZIP is rejected with the sanitized,
     * field-name-based message. This keeps the request contract consistent with the
     * {@code NOT NULL address_zip} column: a null can no longer pass validation, flow
     * through the mapper into the entity, and surface downstream as an ungraceful
     * HTTP 500; it is rejected cleanly as a 400 instead.
     */
    @Test
    void validateAddressZip_rejectsNull() {
        assertThatThrownBy(() -> validator.validateAddressZip(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("addressZip must be supplied.");
    }

    /**
     * Blank-fill tolerance is preserved. Consistent with the legacy fixed-width
     * {@code ACCT-ADDR-ZIP PIC X(10)} (an all-spaces value is legitimate) and the DTO's
     * {@code @NotNull} (which forbids only {@code null}), an empty or whitespace-only
     * value is accepted; representative present values, including a 10-character
     * seed-style value ({@code A000000000}), are likewise accepted. Only an absent
     * ({@code null}) value is rejected.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "          ", "12345", "A000000000"})
    void validateAddressZip_acceptsPresentValuesIncludingBlank(String addressZip) {
        assertThatCode(() -> validator.validateAddressZip(addressZip))
                .doesNotThrowAnyException();
    }

    /**
     * F-08: an address ZIP containing an ISO control character &mdash; most importantly the
     * {@code NUL} byte (U+0000), which PostgreSQL rejects for a {@code text}/{@code varchar} value
     * &mdash; is rejected cleanly at the validation boundary as a 400, rather than passing the
     * presence check, flowing through the mapper into the {@code NOT NULL} {@code address_zip}
     * column, and aborting the {@code INSERT}/{@code UPDATE} at the driver level as an ungraceful
     * HTTP 500. The message names the field only and never echoes the offending value (AAP
     * &sect;0.6.6). Representative control characters across the C0 ({@code \u0000}, {@code \t},
     * {@code \n}) and C1 ({@code \u007F}, {@code \u0085}) ranges are covered.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "1234\u00005678", "A000\t0000", "A0000000\n", "\u007F", "AB\u0085CD"})
    void validateAddressZip_rejectsControlCharacters(String addressZip) {
        assertThatThrownBy(() -> validator.validateAddressZip(addressZip))
                .isInstanceOf(ValidationException.class)
                .hasMessage("addressZip must not contain control characters.");
    }

    /**
     * F-03: an address ZIP containing an <em>unpaired</em> UTF-16 surrogate code unit &mdash; a high
     * surrogate not followed by a low surrogate, or a lone low surrogate &mdash; is not a valid
     * Unicode scalar value and cannot be stored losslessly by PostgreSQL. It is rejected at the
     * validation boundary as a 400 rather than being echoed back at 200 while the database silently
     * substitutes a replacement character, which would break read-back fidelity. The message names
     * the field only (AAP &sect;0.6.6).
     */
    @ParameterizedTest
    @ValueSource(strings = {"\uD83D", "AB\uD83DCD", "\uDE00", "AB\uDE00", "\uDE00\uD83D"})
    void validateAddressZip_rejectsUnpairedSurrogates(String addressZip) {
        assertThatThrownBy(() -> validator.validateAddressZip(addressZip))
                .isInstanceOf(ValidationException.class)
                .hasMessage("addressZip must be valid Unicode text.");
    }

    /**
     * F-03: a <em>well-formed</em> supplementary-plane character (a correctly paired high+low
     * surrogate, here U+1F600) is valid Unicode and is accepted, confirming the surrogate guard
     * rejects only malformed/unpaired sequences rather than all non-BMP text.
     */
    @Test
    void validateAddressZip_acceptsPairedSurrogate() {
        assertThatCode(() -> validator.validateAddressZip("\uD83D\uDE00"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // validate(AccountUpdateRequest) — orchestration & fail-fast ordering
    //
    // Authoritative interleaved order, reproducing COACTUPC 1200-EDIT-MAP-INPUTS
    // [app/cbl/COACTUPC.cbl:L1469-L1529] — dates are INTERLEAVED between the money
    // fields, NOT "all money then all dates":
    //   1 activeStatus  2 openDate      3 creditLimit   4 expirationDate
    //   5 cashCreditLimit  6 reissueDate  7 currentBalance
    //   8 currentCycleCredit  9 currentCycleDebit  -> addressZip (LAST)
    // The first field (in this order) that fails is the one whose message surfaces,
    // so the order is behaviorally observable. The adjacent-pair tests below prove
    // every link of the chain. accountId/groupId/version are NOT validated here.
    // ------------------------------------------------------------------

    /** A fully valid request passes every rule. */
    @Test
    void orchestrationTests() {
        assertThatCode(() -> validator.validate(validRequest()))
                .doesNotThrowAnyException();
    }

    /** An invalid active status is rejected by the orchestrator. */
    @Test
    void validate_rejectsBadActiveStatus() {
        AccountUpdateRequest request = validRequest();
        request.setActiveStatus("X");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("activeStatus must be Y or N.");
    }

    /** A null monetary field is rejected by the orchestrator. */
    @Test
    void validate_rejectsNullAmount() {
        AccountUpdateRequest request = validRequest();
        request.setCurrentBalance(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentBalance must be supplied.");
    }

    /** An invalid date is rejected by the orchestrator. */
    @Test
    void validate_rejectsBadDate() {
        AccountUpdateRequest request = validRequest();
        request.setOpenDate("2023-02-29");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate must be a valid date in yyyy-MM-dd format.");
    }

    /*
     * Fail-fast ordering — adjacent-pair chain proving the authoritative interleaved
     * edit order of COACTUPC 1200-EDIT-MAP-INPUTS [app/cbl/COACTUPC.cbl:L1469-L1529].
     * Each test invalidates two ADJACENT fields in the chain and asserts the EARLIER
     * field's message wins, thereby proving that specific link. Chaining all links
     * proves the full sequence:
     *   activeStatus < openDate < creditLimit < expirationDate < cashCreditLimit
     *     < reissueDate < currentBalance < currentCycleCredit < currentCycleDebit
     *     < addressZip
     * The critical links are the interleaved ones (e.g. openDate BEFORE creditLimit and
     * expirationDate BEFORE cashCreditLimit), which the previous "all money then all
     * dates" order got wrong.
     */

    /** Link 1: activeStatus is validated before openDate. */
    @Test
    void validate_failsFast_activeStatusBeforeOpenDate() {
        AccountUpdateRequest request = validRequest();
        request.setActiveStatus("X");
        request.setOpenDate("2023-02-29");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("activeStatus must be Y or N.");
    }

    /** Link 2 (interleaved): openDate is validated before creditLimit. */
    @Test
    void validate_failsFast_openDateBeforeCreditLimit() {
        AccountUpdateRequest request = validRequest();
        request.setOpenDate("2023-02-29");
        request.setCreditLimit(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("openDate must be a valid date in yyyy-MM-dd format.");
    }

    /** Link 3 (interleaved): creditLimit is validated before expirationDate. */
    @Test
    void validate_failsFast_creditLimitBeforeExpirationDate() {
        AccountUpdateRequest request = validRequest();
        request.setCreditLimit(null);
        request.setExpirationDate("2023-02-29");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("creditLimit must be supplied.");
    }

    /** Link 4 (interleaved): expirationDate is validated before cashCreditLimit. */
    @Test
    void validate_failsFast_expirationDateBeforeCashCreditLimit() {
        AccountUpdateRequest request = validRequest();
        request.setExpirationDate("2023-02-29");
        request.setCashCreditLimit(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("expirationDate must be a valid date in yyyy-MM-dd format.");
    }

    /** Link 5 (interleaved): cashCreditLimit is validated before reissueDate. */
    @Test
    void validate_failsFast_cashCreditLimitBeforeReissueDate() {
        AccountUpdateRequest request = validRequest();
        request.setCashCreditLimit(null);
        request.setReissueDate("2023-02-29");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("cashCreditLimit must be supplied.");
    }

    /** Link 6 (interleaved): reissueDate is validated before currentBalance. */
    @Test
    void validate_failsFast_reissueDateBeforeCurrentBalance() {
        AccountUpdateRequest request = validRequest();
        request.setReissueDate("2023-02-29");
        request.setCurrentBalance(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("reissueDate must be a valid date in yyyy-MM-dd format.");
    }

    /** Link 7: currentBalance is validated before currentCycleCredit. */
    @Test
    void validate_failsFast_currentBalanceBeforeCurrentCycleCredit() {
        AccountUpdateRequest request = validRequest();
        request.setCurrentBalance(null);
        request.setCurrentCycleCredit(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentBalance must be supplied.");
    }

    /** Link 8: currentCycleCredit is validated before currentCycleDebit. */
    @Test
    void validate_failsFast_currentCycleCreditBeforeCurrentCycleDebit() {
        AccountUpdateRequest request = validRequest();
        request.setCurrentCycleCredit(null);
        request.setCurrentCycleDebit(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentCycleCredit must be supplied.");
    }

    /**
     * Each of the five monetary fields is wired into the orchestrator with its own
     * field name. Nulling each one in isolation surfaces that field's message.
     */
    @Test
    void validate_wiresEachMonetaryFieldName() {
        AccountUpdateRequest creditLimit = validRequest();
        creditLimit.setCreditLimit(null);
        assertThatThrownBy(() -> validator.validate(creditLimit))
                .isInstanceOf(ValidationException.class)
                .hasMessage("creditLimit must be supplied.");

        AccountUpdateRequest cashCreditLimit = validRequest();
        cashCreditLimit.setCashCreditLimit(null);
        assertThatThrownBy(() -> validator.validate(cashCreditLimit))
                .isInstanceOf(ValidationException.class)
                .hasMessage("cashCreditLimit must be supplied.");

        AccountUpdateRequest currentCycleCredit = validRequest();
        currentCycleCredit.setCurrentCycleCredit(null);
        assertThatThrownBy(() -> validator.validate(currentCycleCredit))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentCycleCredit must be supplied.");

        AccountUpdateRequest currentCycleDebit = validRequest();
        currentCycleDebit.setCurrentCycleDebit(null);
        assertThatThrownBy(() -> validator.validate(currentCycleDebit))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentCycleDebit must be supplied.");
    }

    /**
     * Each of the three date fields is wired into the orchestrator with its own
     * field name, and both date failure modes (strict-parse vs year-guard) are
     * surfaced through the orchestrator.
     */
    @Test
    void validate_wiresEachDateFieldName() {
        AccountUpdateRequest expiration = validRequest();
        expiration.setExpirationDate("2023-02-29");
        assertThatThrownBy(() -> validator.validate(expiration))
                .isInstanceOf(ValidationException.class)
                .hasMessage("expirationDate must be a valid date in yyyy-MM-dd format.");

        AccountUpdateRequest reissue = validRequest();
        reissue.setReissueDate("2100-01-01");
        assertThatThrownBy(() -> validator.validate(reissue))
                .isInstanceOf(ValidationException.class)
                .hasMessage("reissueDate year must be between 1900 and 2099.");
    }

    /**
     * A null address ZIP is rejected by the orchestrator. This is the exact scenario
     * from the QA finding: a request whose every other field is valid but whose
     * {@code addressZip} is {@code null} must be rejected at the validation boundary
     * (HTTP 400), rather than silently passing and later violating the {@code NOT NULL}
     * column as an ungraceful HTTP 500.
     */
    @Test
    void validate_rejectsNullAddressZip() {
        AccountUpdateRequest request = validRequest();
        request.setAddressZip(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("addressZip must be supplied.");
    }

    /**
     * A blank (present-but-empty) address ZIP is tolerated by the orchestrator,
     * preserving the legacy fixed-width {@code X(10)} blank-fill semantics: only an
     * absent ({@code null}) value is rejected.
     */
    @Test
    void validate_acceptsBlankAddressZip() {
        AccountUpdateRequest request = validRequest();
        request.setAddressZip("");
        assertThatCode(() -> validator.validate(request))
                .doesNotThrowAnyException();
    }

    /**
     * Link 9 (final): the address ZIP is validated LAST — after the last legacy money
     * field (currentCycleDebit). When BOTH currentCycleDebit and addressZip are invalid,
     * the currentCycleDebit message wins, proving addressZip never preempts a legacy
     * field's message and is applied last (matching the mapper's field-application order).
     */
    @Test
    void validate_failsFast_currentCycleDebitBeforeAddressZip() {
        AccountUpdateRequest request = validRequest();
        request.setCurrentCycleDebit(null);
        request.setAddressZip(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("currentCycleDebit must be supplied.");
    }

    // ------------------------------------------------------------------
    // Test fixtures
    // ------------------------------------------------------------------

    /**
     * Builds a fully valid {@link AccountUpdateRequest} using the no-arg constructor
     * and setters. This is a private HELPER METHOD (not a separate helper class), so
     * each orchestration test can start from a known-good request and invalidate a
     * single field.
     *
     * @return a request that passes every rule in {@link AccountValidator#validate}
     */
    private AccountUpdateRequest validRequest() {
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setActiveStatus("Y");
        request.setCurrentBalance(new BigDecimal("1000.00"));
        request.setCreditLimit(new BigDecimal("5000.00"));
        request.setCashCreditLimit(new BigDecimal("2000.00"));
        request.setCurrentCycleCredit(new BigDecimal("0.00"));
        request.setCurrentCycleDebit(new BigDecimal("0.00"));
        request.setOpenDate("2014-11-20");
        request.setExpirationDate("2025-11-20");
        request.setReissueDate("2020-11-20");
        request.setAddressZip("12345");
        request.setVersion(0L);
        return request;
    }
}
