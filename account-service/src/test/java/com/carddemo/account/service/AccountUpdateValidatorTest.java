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

import com.carddemo.common.dto.AccountUpdateRequestDto;
import com.carddemo.common.exception.CardDemoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
                // 1220-EDIT-YESNO on 'Account Status' (L504).
                Arguments.of(mutate(r -> r.setAcctActiveStatus("Z")),
                        "Account Active Status must be Y or N"),
                Arguments.of(mutate(r -> r.setAcctActiveStatus(null)),
                        "Account Active Status must be Y or N"),
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
                Arguments.of(mutate(r -> r.setAcctCreditLimit(new BigDecimal("1234567890.00"))),
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
        assertThat(thrown.getMessage()).isEqualTo("Account Active Status must be Y or N");
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
     * :purpose: Adapt a lambda to the parameterized-test argument type without repeating the cast.
     * :param mutation: the field mutation to apply.
     * :returns: the mutation typed as a ``Consumer``.
     */
    private static Consumer<AccountUpdateRequestDto> mutate(Consumer<AccountUpdateRequestDto> mutation) {
        return mutation;
    }
}
