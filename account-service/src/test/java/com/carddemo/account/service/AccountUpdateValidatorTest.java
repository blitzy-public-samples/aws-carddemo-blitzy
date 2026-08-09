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

import com.carddemo.common.crypto.PiiMasker;
import com.carddemo.common.domain.Customer;
import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.exception.CardDemoException;
import com.carddemo.common.exception.FieldValidationException;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * :purpose: Verify {@link AccountUpdateValidator} reproduces the account-update program's
 *     field edits, ``COACTUPC 1200-EDIT-MAP-INPUTS`` (``app/cbl/COACTUPC.cbl`` L1472-1672),
 *     with the exact ``WS-RETURN-MSG`` literals and the exact fail-fast ``PERFORM`` order.
 * :output: One assertion per legacy edit branch, plus an ordering assertion proving the
 *     first-failing edit wins when several fields are invalid at once, plus the out-of-scale
 *     money rejection that replaces silent rounding.
 */
class AccountUpdateValidatorTest {

    /** :purpose: The validator under test; it holds no state. */
    private final AccountUpdateValidator validator = new AccountUpdateValidator();

    /**
     * :purpose: Build a submission that satisfies every edit, so a single mutation isolates
     *     exactly one legacy branch.
     * :returns: a fully valid ``AccountUpdateRequestDto``.
     */
    private static AccountUpdateRequestDto validRequest() {
        AccountUpdateRequestDto request = new AccountUpdateRequestDto();
        request.setAcctActiveStatus("Y");
        request.setAcctOpenDate("2014-11-20");
        request.setAcctCreditLimit(new BigDecimal("2020.00"));
        request.setAcctExpiraionDate("2025-05-20");
        request.setAcctCashCreditLimit(new BigDecimal("1020.00"));
        request.setAcctReissueDate("2025-05-20");
        request.setAcctCurrBal(new BigDecimal("194.00"));
        request.setAcctCurrCycCredit(new BigDecimal("0.00"));
        request.setAcctCurrCycDebit(new BigDecimal("0.00"));
        request.setCustSsn("020973888");
        request.setCustDobYyyyMmDd("1961-06-08");
        request.setCustFicoCreditScore(700);
        request.setCustFirstName("Immanuel");
        request.setCustMiddleName("Madeline");
        request.setCustLastName("Kessler");
        request.setCustAddrLine1("618 Deshaun Route");
        request.setCustAddrStateCd("NC");
        // 1280-EDIT-US-STATE-ZIP-CD: only NC27 and NC28 are known NC combinations.
        request.setCustAddrZip("27610");
        request.setCustAddrLine2("Apt 802");
        request.setCustAddrLine3("Altenwerthshire");
        request.setCustAddrCountryCd("USA");
        // 1260-EDIT-US-PHONE-NUM: both area codes must be North America general purpose codes.
        request.setCustPhoneNum1("(908)119-8310");
        request.setCustPhoneNum2("(801)693-8684");
        request.setCustEftAccountId("0053581756");
        request.setCustPriCardHolderInd("Y");
        request.setCustGovtIssuedId("00000000000049368437");
        return request;
    }

    /**
     * :purpose: A submission that satisfies every legacy edit passes without raising.
     */
    @Test
    @DisplayName("a fully valid submission passes every 1200-EDIT-MAP-INPUTS edit")
    void validSubmissionPasses() {
        assertThatNoException().isThrownBy(() -> validator.validate(validRequest()));
    }

    /**
     * :purpose: ``NO-SEARCH-CRITERIA-RECEIVED`` (COACTUPC L490): a request carrying no field at
     *  all is rejected before anything is read, and a ``null`` request is treated the same way.
     */
    @Test
    @DisplayName("an empty submission reports 'No input received'")
    void emptySubmissionReportsNoInputReceived() {
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(new AccountUpdateRequestDto()))
                .withMessage("No input received");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(null))
                .withMessage("No input received");
    }

    /**
     * :purpose: Verify each individual edit branch reports its verbatim legacy message.
     * :param mutation: the single field mutation applied to an otherwise valid submission.
     * :param expectedMessage: the verbatim ``WS-RETURN-MSG`` text the edit must produce.
     */
    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("editBranches")
    void eachEditBranchReportsItsVerbatimMessage(Consumer<AccountUpdateRequestDto> mutation,
                                                 String expectedMessage) {
        AccountUpdateRequestDto request = validRequest();
        mutation.accept(request);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage(expectedMessage);
    }

    /**
     * :purpose: Enumerate every edit branch with the verbatim message the legacy program moves
     *  into ``WS-RETURN-MSG``, in the order the paragraphs are performed.
     * :returns: a stream of (mutation, verbatim message) pairs.
     */
    private static Stream<Arguments> editBranches() {
        return Stream.of(
                // 1220-EDIT-YESNO on 'Account Status' (L1856-1893). Both messages are
                // composed from WS-EDIT-VARIABLE-NAME, which L1472-1475 sets to
                // 'Account Status' before performing the paragraph. The 88-level at
                // L503-504 reading 'Account Active Status must be Y or N' is never SET
                // anywhere in the program, so no terminal can display it. The paragraph
                // reports absence and a wrong value with its two distinct messages.
                Arguments.of(mutate(r -> r.setAcctActiveStatus("Z")),
                        "Account Status must be Y or N."),
                Arguments.of(mutate(r -> r.setAcctActiveStatus(null)),
                        "Account Status must be supplied."),
                // EDIT-DATE-CCYYMMDD on 'Open Date' (CSUTLDPY).
                Arguments.of(mutate(r -> r.setAcctOpenDate(null)),
                        "Open Date : Year must be supplied."),
                Arguments.of(mutate(r -> r.setAcctOpenDate("2014-13-20")),
                        "Open Date: Month must be a number between 1 and 12."),
                Arguments.of(mutate(r -> r.setAcctOpenDate("2014-11-32")),
                        "Open Date:day must be a number between 1 and 31."),
                Arguments.of(mutate(r -> r.setAcctOpenDate("2014-02-30")),
                        "Open Date:day must be a number between 1 and 31."),
                // 1250-EDIT-SIGNED-9V2 on 'Credit Limit' (L506, L508).
                Arguments.of(mutate(r -> r.setAcctCreditLimit(null)),
                        "Credit Limit must be supplied"),
                Arguments.of(mutate(r -> r.setAcctCreditLimit(new BigDecimal("2020.005"))),
                        "Credit Limit is not valid"),
                // ELEVEN integer digits overflows PIC S9(10)V99 / NUMERIC(12,2) and is rejected.
                // Ten is legal and is asserted as ACCEPTED by the boundary test below; capping at
                // nine made an account legitimately holding a ten-digit amount impossible to update.
                Arguments.of(mutate(r -> r.setAcctCreditLimit(new BigDecimal("12345678901.00"))),
                        "Credit Limit is not valid"),
                // EDIT-DATE-CCYYMMDD on 'Expiry Date' (L510, L512).
                Arguments.of(mutate(r -> r.setAcctExpiraionDate("2025-13-20")),
                        "Card expiry month must be between 1 and 12"),
                Arguments.of(mutate(r -> r.setAcctExpiraionDate("1849-05-20")),
                        "Invalid card expiry year"),
                Arguments.of(mutate(r -> r.setAcctExpiraionDate(null)),
                        "Invalid card expiry year"),
                // 1250-EDIT-SIGNED-9V2 on the remaining amounts.
                Arguments.of(mutate(r -> r.setAcctCashCreditLimit(null)),
                        "Cash Credit Limit must be supplied."),
                Arguments.of(mutate(r -> r.setAcctCashCreditLimit(new BigDecimal("1.001"))),
                        "Cash Credit Limit is not valid"),
                Arguments.of(mutate(r -> r.setAcctCurrBal(new BigDecimal("300.005"))),
                        "Current Balance is not valid"),
                Arguments.of(mutate(r -> r.setAcctCurrCycCredit(null)),
                        "Current Cycle Credit Limit must be supplied."),
                Arguments.of(mutate(r -> r.setAcctCurrCycDebit(new BigDecimal("0.001"))),
                        "Current Cycle Debit Limit is not valid"),
                // 1265-EDIT-US-SSN.
                Arguments.of(mutate(r -> r.setCustSsn(null)),
                        "SSN: First 3 chars must be supplied."),
                Arguments.of(mutate(r -> r.setCustSsn("AB1234567")),
                        "SSN: First 3 chars must be all numeric."),
                Arguments.of(mutate(r -> r.setCustSsn("000123456")),
                        "SSN: First 3 chars must not be zero."),
                Arguments.of(mutate(r -> r.setCustSsn("12345678")),
                        "SSN Last 4 chars must be all numeric."),
                Arguments.of(mutate(r -> r.setCustSsn("123005678")),
                        "SSN 4th & 5th chars must not be zero."),
                Arguments.of(mutate(r -> r.setCustSsn("666123456")),
                        "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"),
                Arguments.of(mutate(r -> r.setCustSsn("900010001")),
                        "SSN: First 3 chars: should not be 000, 666, or between 900 and 999"),
                // 1275-EDIT-FICO-SCORE.
                Arguments.of(mutate(r -> r.setCustFicoCreditScore(null)),
                        "FICO Score must be supplied."),
                Arguments.of(mutate(r -> r.setCustFicoCreditScore(299)),
                        "FICO Score: should be between 300 and 850"),
                Arguments.of(mutate(r -> r.setCustFicoCreditScore(9999)),
                        "FICO Score: should be between 300 and 850"),
                // 1225-EDIT-ALPHA-REQD / 1235-EDIT-ALPHA-OPT on the names (L486, L488).
                Arguments.of(mutate(r -> r.setCustFirstName(null)),
                        "First Name must be supplied."),
                Arguments.of(mutate(r -> r.setCustFirstName("<script>alert(1)</script>")),
                        "Name can only contain alphabets and spaces"),
                Arguments.of(mutate(r -> r.setCustMiddleName("M4deline")),
                        "Middle Name can have alphabets only."),
                Arguments.of(mutate(r -> r.setCustLastName(null)),
                        "Last name not provided"),
                Arguments.of(mutate(r -> r.setCustLastName("Kessler1")),
                        "Name can only contain alphabets and spaces"),
                // 1215-EDIT-MANDATORY on 'Address Line 1'.
                Arguments.of(mutate(r -> r.setCustAddrLine1("  ")),
                        "Address Line 1 must be supplied."),
                // 1225-EDIT-ALPHA-REQD plus 1270-EDIT-US-STATE-CD on 'State'.
                Arguments.of(mutate(r -> r.setCustAddrStateCd(null)),
                        "State must be supplied."),
                Arguments.of(mutate(r -> r.setCustAddrStateCd("N1")),
                        "State can have alphabets only."),
                Arguments.of(mutate(r -> r.setCustAddrStateCd("NCX")),
                        "State: is not a valid state code"),
                // 1245-EDIT-NUM-REQD on 'Zip'.
                Arguments.of(mutate(r -> r.setCustAddrZip(null)),
                        "Zip must be supplied."),
                Arguments.of(mutate(r -> r.setCustAddrZip("1985A-6716")),
                        "Zip must be all numeric."),
                Arguments.of(mutate(r -> r.setCustAddrZip("00000")),
                        "Zip must not be zero."),
                // 1280-EDIT-US-STATE-ZIP-CD cross-field edit.
                Arguments.of(mutate(r -> r.setCustAddrZip("12546")),
                        "Invalid zip code for state"),
                // 1225-EDIT-ALPHA-REQD on 'City' (CUST-ADDR-LINE-3) and 'Country'.
                Arguments.of(mutate(r -> r.setCustAddrLine3(null)),
                        "City must be supplied."),
                Arguments.of(mutate(r -> r.setCustAddrLine3("Altenwerthshire2")),
                        "City can have alphabets only."),
                Arguments.of(mutate(r -> r.setCustAddrCountryCd("US1")),
                        "Country can have alphabets only."),
                // 1260-EDIT-US-PHONE-NUM on both phone numbers.
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(9O8)119-8310")),
                        "Phone Number 1: Area code must be A 3 digit number."),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(   )119-8310")),
                        "Phone Number 1: Area code must be supplied."),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(000)119-8310")),
                        "Phone Number 1: Area code cannot be zero"),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(373)119-8310")),
                        "Phone Number 1: Not valid North America general purpose area code"),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(908)000-8310")),
                        "Phone Number 1: Prefix code cannot be zero"),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(908)119-83 1")),
                        "Phone Number 1: Line number code must be A 4 digit number."),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(908)119-")),
                        "Phone Number 1: Line number code must be supplied."),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(908)   -8310")),
                        "Phone Number 1: Prefix code must be supplied."),
                Arguments.of(mutate(r -> r.setCustPhoneNum1("(908)119-0000")),
                        "Phone Number 1: Line number code cannot be zero"),
                Arguments.of(mutate(r -> r.setCustPhoneNum2("(000)693-8684")),
                        "Phone Number 2: Area code cannot be zero"),
                // 1245-EDIT-NUM-REQD on 'EFT Account Id'.
                Arguments.of(mutate(r -> r.setCustEftAccountId("EFT900001ACC")),
                        "EFT Account Id must be all numeric."),
                Arguments.of(mutate(r -> r.setCustEftAccountId("0000000000")),
                        "EFT Account Id must not be zero."),
                Arguments.of(mutate(r -> r.setCustEftAccountId(null)),
                        "EFT Account Id must be supplied."),
                // 1220-EDIT-YESNO on 'Primary Card Holder'.
                Arguments.of(mutate(r -> r.setCustPriCardHolderInd("X")),
                        "Primary Card Holder must be Y or N."));
    }

    /**
     * :purpose: ``1260-EDIT-US-PHONE-NUM`` (COACTUPC L2232-2244): a phone number is NOT
     *  mandatory, so an absent value passes rather than reporting a missing area code.
     */
    @Test
    @DisplayName("an absent phone number passes: 1260 treats it as optional")
    void absentPhoneNumberIsOptional() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustPhoneNum1(null);
        request.setCustPhoneNum2("   ");

        assertThatNoException().isThrownBy(() -> validator.validate(request));
    }

    /**
     * :purpose: ``1245-EDIT-NUM-REQD`` on 'Zip' inspects only the FIRST FIVE characters
     *  (COACTUPC L1607), so a ZIP+4 value passes when its leading five digits are numeric and
     *  the state combination is known.
     */
    @Test
    @DisplayName("a ZIP+4 value passes: only the first five characters are edited")
    void zipPlusFourPasses() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustAddrZip("27610-6716");

        assertThatNoException().isThrownBy(() -> validator.validate(request));
    }

    /**
     * :purpose: Preserve the COBOL fail-fast ``PERFORM`` order: when every field is invalid at
     *  once, the message reported is the one from the FIRST performed edit, and exactly one
     *  message is produced.
     */
    @Test
    @DisplayName("the first failing edit wins and only one message is produced")
    void firstFailingEditWins() {
        AccountUpdateRequestDto request = validRequest();
        request.setAcctActiveStatus("Z");
        request.setAcctOpenDate("2014-13-45");
        request.setAcctCreditLimit(null);
        request.setCustFicoCreditScore(9999);
        request.setCustFirstName("N4me");

        CardDemoException thrown = org.assertj.core.api.Assertions
                .catchThrowableOfType(CardDemoException.class, () -> validator.validate(request));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).isEqualTo("Account Status must be Y or N.");
        assertThat(thrown.getMessage()).doesNotContain(",");
    }

    /**
     * :purpose: 'Open Date' is edited before 'Credit Limit', so a submission invalid in both
     *  reports the date message, proving the ordering rather than only the first entry.
     */
    @Test
    @DisplayName("Open Date is edited before Credit Limit")
    void openDateIsEditedBeforeCreditLimit() {
        AccountUpdateRequestDto request = validRequest();
        request.setAcctOpenDate("2014-13-20");
        request.setAcctCreditLimit(null);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage("Open Date: Month must be a number between 1 and 12.");
    }

    /**
     * :purpose: AAP 0.6.1 financial precision: a monetary value carrying more than two decimal
     *  places is REJECTED rather than silently rounded to the ``S9(09)V99`` scale.
     */
    @Test
    @DisplayName("out-of-scale money is rejected, never rounded")
    void outOfScaleMoneyIsRejected() {
        AccountUpdateRequestDto request = validRequest();
        request.setAcctCurrBal(new BigDecimal("300.005"));

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage("Current Balance is not valid");

        // A trailing zero beyond scale 2 is not extra precision and must still pass.
        AccountUpdateRequestDto trailingZero = validRequest();
        trailingZero.setAcctCurrBal(new BigDecimal("300.0000"));
        assertThatNoException().isThrownBy(() -> validator.validate(trailingZero));
    }

    /**
     * :purpose: Every editable text field is refused when it is longer than its record-layout
     *  width, so an oversized value can never reach the column and surface as a server error.
     *  Each case carries the field's own composed message.
     */
    @Test
    @DisplayName("a value wider than its record-layout field is rejected with the field's width message")
    void oversizedFieldsAreRejectedWithTheirWidthMessage() {
        assertWidthRejected(request -> request.setAcctGroupId("ELEVENCHARS"),
                "Account Group must be at most 10 characters.");
        assertWidthRejected(request -> request.setAcctActiveStatus("YY"),
                "Account Status must be at most 1 character.");
        assertWidthRejected(request -> request.setCustFirstName("A".repeat(26)),
                "First Name must be at most 25 characters.");
        assertWidthRejected(request -> request.setCustMiddleName("B".repeat(26)),
                "Middle Name must be at most 25 characters.");
        assertWidthRejected(request -> request.setCustLastName("C".repeat(26)),
                "Last Name must be at most 25 characters.");
        assertWidthRejected(request -> request.setCustAddrLine1("D".repeat(51)),
                "Address Line 1 must be at most 50 characters.");
        assertWidthRejected(request -> request.setCustAddrLine2("E".repeat(51)),
                "Address Line 2 must be at most 50 characters.");
        assertWidthRejected(request -> request.setCustAddrLine3("F".repeat(51)),
                "City must be at most 50 characters.");
        assertWidthRejected(request -> request.setCustAddrCountryCd("USAX"),
                "Country must be at most 3 characters.");
        assertWidthRejected(request -> request.setCustGovtIssuedId("G".repeat(21)),
                "Govt Issued Id must be at most 20 characters.");
        assertWidthRejected(request -> request.setCustEftAccountId("0053581756123"),
                "EFT Account Id must be at most 10 characters.");
    }

    /**
     * :purpose: A ten-digit ``CUST-EFT-ACCOUNT-ID`` is a legal value and must pass the numeric
     *  edit. The legacy zero test is a character comparison over the whole field, so it must
     *  not be reproduced by parsing the digits -- ten digits overflow a 32-bit integer and the
     *  submission previously failed as a server error.
     */
    @Test
    @DisplayName("a ten-digit EFT Account Id passes, and an all-zero one reports the zero edit")
    void tenDigitEftAccountIdIsAcceptedAndAllZeroIsRejected() {
        AccountUpdateRequestDto widest = validRequest();
        widest.setCustEftAccountId("9999999999");
        assertThatNoException().isThrownBy(() -> validator.validate(widest));

        AccountUpdateRequestDto zeroes = validRequest();
        zeroes.setCustEftAccountId("0000000000");
        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(zeroes))
                .withMessage("EFT Account Id must not be zero.");
    }

    /**
     * :purpose: A rejected edit names the request member it faulted, so the calling screen can
     *  paint that field red and park the cursor on it as ``3300-SETUP-SCREEN-ATTRS`` does.
     */
    @Test
    @DisplayName("a rejected edit reports the request member it faulted")
    void rejectedEditNamesItsField() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustFicoCreditScore(274);

        FieldValidationException failure = Assertions
                .catchThrowableOfType(FieldValidationException.class, () -> validator.validate(request));

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("FICO Score: should be between 300 and 850");
        assertThat(failure.getField()).isEqualTo("custFicoCreditScore");
        assertThat(failure.getFields()).containsExactly("custFicoCreditScore");
    }

    /**
     * :purpose: ``1280-EDIT-US-STATE-ZIP-CD`` sets FLG-STATE-NOT-OK *and* FLG-ZIPCODE-NOT-OK,
     *  so the cross-field edit faults both members with the state code first — the field the
     *  legacy cursor rule reaches first in screen order.
     */
    @Test
    @DisplayName("the state/zip cross-field edit faults both members, state code first")
    void zipForStateFaultsBothMembers() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustAddrZip("99501");

        FieldValidationException failure = Assertions
                .catchThrowableOfType(FieldValidationException.class, () -> validator.validate(request));

        assertThat(failure).isNotNull();
        assertThat(failure.getMessage()).isEqualTo("Invalid zip code for state");
        assertThat(failure.getFields()).containsExactly("custAddrStateCd", "custAddrZip");
    }

    /**
     * :purpose: An echoed mask carries nothing new and passes; a value the operator edited
     *  THROUGH the mask is not numeric and is refused, naming its own field.
     */
    @Test
    @DisplayName("only the stored value's own mask is accepted for a masked identifier")
    void maskedIdentifierEditIsRefused() {
        Customer stored = new Customer();
        stored.setCustSsn("020973888");
        stored.setCustEftAccountId("0053581756");

        AccountUpdateRequestDto echoed = validRequest();
        echoed.setCustSsn(PiiMasker.maskSsn(stored.getCustSsn()));
        echoed.setCustEftAccountId(PiiMasker.maskIdentifier(stored.getCustEftAccountId()));
        assertThatNoException()
                .isThrownBy(() -> validator.validateMaskedIdentifiers(echoed, stored));

        AccountUpdateRequestDto editedSsn = validRequest();
        editedSsn.setCustSsn("***-**-1234");
        FieldValidationException ssnFailure = Assertions.catchThrowableOfType(
                FieldValidationException.class,
                () -> validator.validateMaskedIdentifiers(editedSsn, stored));
        assertThat(ssnFailure).isNotNull();
        assertThat(ssnFailure.getMessage()).isEqualTo("SSN: First 3 chars must be all numeric.");
        assertThat(ssnFailure.getField()).isEqualTo("custSsn");

        AccountUpdateRequestDto editedEft = validRequest();
        editedEft.setCustEftAccountId("******9999");
        FieldValidationException eftFailure = Assertions.catchThrowableOfType(
                FieldValidationException.class,
                () -> validator.validateMaskedIdentifiers(editedEft, stored));
        assertThat(eftFailure).isNotNull();
        assertThat(eftFailure.getMessage()).isEqualTo("EFT Account Id must be all numeric.");
        assertThat(eftFailure.getField()).isEqualTo("custEftAccountId");
    }

    /**
     * :purpose: Apply one oversized-field mutation to an otherwise valid submission and assert
     *  the exact message the width edit reports.
     * :param mutation: the field mutation that exceeds a declared width.
     * :param expectedMessage: the composed message the edit must report.
     */
    private void assertWidthRejected(Consumer<AccountUpdateRequestDto> mutation,
                                     String expectedMessage) {
        AccountUpdateRequestDto request = validRequest();
        mutation.accept(request);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage(expectedMessage);
    }

    /**
     * :purpose: Adapt a lambda to the parameterized-test argument type without repeating the cast.
     * :param mutation: the field mutation to apply.
     * :returns: the mutation typed as a ``Consumer``.
     */
    private static Consumer<AccountUpdateRequestDto> mutate(Consumer<AccountUpdateRequestDto> mutation) {
        return mutation;
    }
    /**
     * :purpose: Every account amount is declared ``PIC S9(10)V99`` [app/cpy/CVACT01Y.cpy:L7-L14] and
     *   stored in a ``NUMERIC(12,2)`` column, so TEN integer digits are legal and ``9999999999.99``
     *   is the largest representable value. All five amount fields are asserted, because the guard
     *   is shared and a full-snapshot PUT is rejected on ANY of them -- which is why capping at nine
     *   made an account holding a legal large amount impossible to update at all, no matter which
     *   field the caller was actually editing.
     * :param mutation: the single amount mutation applied to an otherwise valid submission.
     */
    @ParameterizedTest(name = "[{index}] ten integer digits accepted")
    @MethodSource("tenDigitAmounts")
    void tenIntegerDigitAmountsAreAccepted(Consumer<AccountUpdateRequestDto> mutation) {
        AccountUpdateRequestDto request = validRequest();
        mutation.accept(request);

        assertThatNoException().isThrownBy(() -> validator.validate(request));
    }

    /**
     * :purpose: Enumerate the maximum legal value on each of the five account amount fields.
     * :returns: a stream of single-field mutations setting the PIC S9(10)V99 maximum.
     */
    private static Stream<Arguments> tenDigitAmounts() {
        BigDecimal max = new BigDecimal("9999999999.99");
        BigDecimal tenDigits = new BigDecimal("1234567890.00");
        return Stream.of(
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCreditLimit(max)),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCreditLimit(tenDigits)),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCurrBal(max)),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCashCreditLimit(max)),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCurrCycCredit(max)),
                Arguments.of((Consumer<AccountUpdateRequestDto>) r -> r.setAcctCurrCycDebit(max)));
    }

    /**
     * :purpose: ``EDIT-DATE-OF-BIRTH`` (``app/cpy/CSUTLDPY.cpy``) rejects a date of birth that is
     *   not strictly in the past, with the literal the legacy ``STRING`` composes. The paragraph
     *   passes only when ``WS-CURRENT-DATE-BINARY > WS-EDIT-DATE-BINARY``, so TODAY is rejected too
     *   and that boundary is asserted rather than assumed.
     * :param dob: the submitted date of birth.
     */
    @ParameterizedTest(name = "[{index}] dob {0} rejected as future")
    @MethodSource("nonPastDatesOfBirth")
    void dateOfBirthMustBeStrictlyInThePast(String dob) {
        AccountUpdateRequestDto request = validRequest();
        request.setCustDobYyyyMmDd(dob);

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage("Date of Birth:cannot be in the future ");
    }

    /**
     * :purpose: Enumerate dates of birth that are not strictly in the past.
     * :returns: today and two future dates, all real calendar dates so the calendar edit passes and
     *   the reasonableness edit is the one that rejects them.
     */
    private static Stream<Arguments> nonPastDatesOfBirth() {
        return Stream.of(
                Arguments.of(LocalDate.now().toString()),
                Arguments.of(LocalDate.now().plusDays(1).toString()),
                Arguments.of("2099-01-01"));
    }

    /**
     * :purpose: A date of birth in the past is still accepted, so the new reasonableness edit did
     *   not turn a legitimate submission into a failure.
     */
    @Test
    @DisplayName("a past date of birth is still accepted")
    void pastDateOfBirthIsAccepted() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustDobYyyyMmDd(LocalDate.now().minusDays(1).toString());

        assertThatNoException().isThrownBy(() -> validator.validate(request));
    }

    /**
     * :purpose: The calendar edit runs FIRST and the reasonableness edit only when the date already
     *   parsed [app/cbl/COACTUPC.cbl:L1533-L1541], so an impossible future date reports its own
     *   calendar message rather than the future-date one.
     */
    @Test
    @DisplayName("an impossible future date reports the calendar message, not the future message")
    void impossibleFutureDateReportsCalendarMessageFirst() {
        AccountUpdateRequestDto request = validRequest();
        request.setCustDobYyyyMmDd("2099-02-30");

        assertThatExceptionOfType(CardDemoException.class)
                .isThrownBy(() -> validator.validate(request))
                .withMessage("Date of Birth:day must be a number between 1 and 31.");
    }

    /**
     * :purpose: Every numeric property of the request must have a bind-time message, and it must be
     *   the SAME string the edit reports, so a wrong-typed value and an out-of-range value read
     *   identically to the caller.
     */
    @Test
    @DisplayName("the type-mismatch map covers every numeric property with the edit's own message")
    void typeMismatchMapMatchesTheEditMessages() {
        assertThat(AccountUpdateValidator.typeMismatchMessages())
                .containsEntry("acctCreditLimit", "Credit Limit is not valid")
                .containsEntry("acctCurrBal", "Current Balance is not valid")
                .containsEntry("acctCashCreditLimit", "Cash Credit Limit is not valid")
                .containsEntry("acctCurrCycCredit", "Current Cycle Credit Limit is not valid")
                .containsEntry("acctCurrCycDebit", "Current Cycle Debit Limit is not valid")
                .containsEntry("custFicoCreditScore", "FICO Score: should be between 300 and 850");
    }
}
