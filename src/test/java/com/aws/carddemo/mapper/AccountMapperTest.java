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
package com.aws.carddemo.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.aws.carddemo.domain.Account;
import com.aws.carddemo.domain.Customer;
import com.aws.carddemo.dto.AccountUpdateRequest;
import com.aws.carddemo.dto.AccountUpdateResponse;
import com.aws.carddemo.dto.AccountViewResponse;

/**
 * Pure, isolated unit tests for {@link AccountMapper}, the hand-written mapper that bridges the
 * {@link Account} and {@link Customer} JPA entities and the Account View ({@code COACTVWC} /
 * {@code CAVW}) and Account Update ({@code COACTUPC} / {@code CAUP}) data-transfer objects.
 *
 * <p>{@code AccountMapper} is the most complex mapper in the migration because a single
 * account-maintenance screen displays fields from <em>two</em> entities and because it performs
 * <em>split / compose</em> transformations: single stored strings (SSN, date of birth, the three
 * account dates, and the two phone numbers) are decomposed into their individual screen component
 * fields for the Update screen and recomposed back into the stored strings on save. The dominant
 * theme of this suite is therefore <strong>round-trip parity</strong>: decompose (entity &rarr;
 * response) followed by compose (request &rarr; entity) must return the original stored values with
 * no drift, truncation, or padding artifacts.</p>
 *
 * <p><b>Source lineage.</b> The field contract is verified against the BMS symbolic copybooks and
 * record layouts relocated under {@code legacy/**} during the migration:
 * {@code legacy/cpy-bms/COACTVW.CPY} (view screen), {@code legacy/cpy-bms/COACTUP.CPY} (update
 * screen &mdash; confirms {@code ACTSSN1/2/3} as {@code X(3)}/{@code X(2)}/{@code X(4)}, the date
 * year/month/day parts, {@code ACSPH1A/B/C} and {@code ACSPH2A/B/C} as {@code X(3)}/{@code X(3)}/
 * {@code X(4)}, {@code ACSCITYI} for the city field, and {@code ACSZIPCI} as the 5-character ZIP),
 * {@code legacy/cpy/CVACT01Y.cpy} (Account layout, five {@code PIC S9(10)V99} monetary fields), and
 * {@code legacy/cpy/CVCUS01Y.cpy} (Customer layout, {@code CUST-SSN PIC 9(09)}). The deterministic
 * fixed timestamp mirrors the legacy source version date recorded in {@code CVACT01Y.cpy}
 * ({@code 2022-07-19 23:15:59 CDT}).</p>
 *
 * <p><b>Test discipline (AAP &sect;0.6.4, &sect;0.9.1, &sect;0.9.3).</b> This is a pure unit test:
 * the mapper is exercised directly through {@code new AccountMapper()} with no Spring context, no
 * database, and no Mockito. Imports are explicit (no wildcards) and use only the Jakarta-era
 * production types; {@code javax.*} is never referenced. Every monetary value is created with
 * {@code new BigDecimal("...")} (never a {@code double}/{@code float} literal) and asserted with
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo} together with an
 * explicit {@code scale() == 2} check. All timestamps are the fixed {@link #NOW} constant, so the
 * suite is fully deterministic.</p>
 */
class AccountMapperTest {

    /**
     * Deterministic timestamp used for every mapping call. Chosen to mirror the legacy source
     * version date stamped in {@code legacy/cpy/CVACT01Y.cpy} ({@code 2022-07-19 23:15:59 CDT}).
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /** Expected header date rendering of {@link #NOW} under the mapper's {@code MM/dd/uu} mask. */
    private static final String EXPECTED_DATE = "07/19/22";

    /** Expected header time rendering of {@link #NOW} under the mapper's {@code HH:mm:ss} mask. */
    private static final String EXPECTED_TIME = "23:15:58";

    /** The unit under test. Stateless and thread-safe, constructed directly (no Spring). */
    private final AccountMapper mapper = new AccountMapper();

    // ------------------------------------------------------------------------
    // Fixtures. Values are chosen so that every split / compose transformation
    // is individually observable and so that the account ZIP differs from the
    // customer ZIP (proving the mapper sources the ZIP from the customer).
    // ------------------------------------------------------------------------

    /**
     * Builds the canonical sample account. The {@code acctAddrZip} is deliberately set to a value
     * that never appears in any expected output so that a test asserting {@code zipCode == "98101"}
     * proves the mapper reads the <em>customer</em> ZIP, not the account ZIP.
     *
     * @return a fully populated {@link Account} with {@code version} pre-set to {@code 0}
     */
    private static Account sampleAccount() {
        Account account = new Account(
                12345678901L,               // acctId
                "Y",                        // acctActiveStatus
                new BigDecimal("1234.56"),  // currBal
                new BigDecimal("5000.00"),  // creditLimit
                new BigDecimal("1000.00"),  // cashCreditLimit
                "2018-03-15",               // acctOpenDate
                "2025-12-31",               // acctExpirationDate
                "2022-01-10",               // acctReissueDate
                new BigDecimal("250.00"),   // currCycCredit
                new BigDecimal("175.25"),   // currCycDebit
                "99999-0000",               // acctAddrZip (distinct: never expected in output)
                "GRP0000001");              // groupId
        account.setVersion(0L);
        return account;
    }

    /**
     * Builds the canonical sample customer, including the three sensitive fields (SSN, government
     * id, date of birth) that legitimately appear on the account-maintenance screen.
     *
     * @return a fully populated {@link Customer}
     */
    private static Customer sampleCustomer() {
        return new Customer(
                987654321L,        // custId
                "ALICE",           // custFirstName
                "M",               // custMiddleName
                "CARDHOLDER",      // custLastName
                "100 MAIN ST",     // custAddrLine1
                "APT 5",           // custAddrLine2
                "SEATTLE",         // custAddrLine3  -> city
                "WA",              // custAddrStateCd
                "USA",             // custAddrCountryCd
                "98101-1234",      // custAddrZip    -> zipCode leading 5 = "98101"
                "(206)555-0100",   // custPhoneNum1  -> 206 / 555 / 0100
                "(425)555-0199",   // custPhoneNum2  -> 425 / 555 / 0199
                "123456789",       // custSsn (SENSITIVE) -> 123 / 45 / 6789
                "G-ABC-1234567",   // custGovtIssuedId (SENSITIVE)
                "1985-06-20",      // custDob (SENSITIVE) -> 1985 / 06 / 20
                "EFT0000001",      // custEftAccountId
                "Y",               // custPriCardHolderInd
                Integer.valueOf(750)); // custFicoCreditScore
    }

    /**
     * Builds a bare target account carrying only its primary key and optimistic-lock version, with
     * every business field left {@code null}. Used as a compose target to detect any illegal
     * overwrite of the key or version by the mapper.
     *
     * @param acctId  the pre-set primary key
     * @param version the pre-set optimistic-lock version
     * @return a fresh {@link Account}
     */
    private static Account freshAccount(Long acctId, Long version) {
        Account account = new Account(
                acctId, null, null, null, null, null, null, null, null, null, null, null);
        account.setVersion(version);
        return account;
    }

    /**
     * Builds a bare target customer carrying only its primary key, with every other field left
     * {@code null}. ({@code Customer} has no optimistic-lock version column.)
     *
     * @param custId the pre-set primary key
     * @return a fresh {@link Customer}
     */
    private static Customer freshCustomer(Long custId) {
        return new Customer(
                custId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Bridges decompose to compose: maps every data component of an {@link AccountUpdateResponse}
     * one-for-one into a matching {@link AccountUpdateRequest} component, preserving order and
     * value exactly. The {@code action} attention key is irrelevant to the mapper and is passed as
     * {@code null}. This helper is the heart of the round-trip parity assertions.
     *
     * @param response the decomposed response to feed back through compose
     * @return an equivalent request carrying the same screen component parts
     */
    private static AccountUpdateRequest requestFrom(AccountUpdateResponse response) {
        return new AccountUpdateRequest(
                response.accountId(),
                response.accountStatus(),
                response.openYear(),
                response.openMonth(),
                response.openDay(),
                response.creditLimit(),
                response.expiryYear(),
                response.expiryMonth(),
                response.expiryDay(),
                response.cashLimit(),
                response.reissueYear(),
                response.reissueMonth(),
                response.reissueDay(),
                response.currentBalance(),
                response.currentCycleCredit(),
                response.groupId(),
                response.currentCycleDebit(),
                response.customerId(),
                response.ssnPart1(),
                response.ssnPart2(),
                response.ssnPart3(),
                response.dobYear(),
                response.dobMonth(),
                response.dobDay(),
                response.ficoScore(),
                response.firstName(),
                response.middleName(),
                response.lastName(),
                response.addressLine1(),
                response.stateCode(),
                response.addressLine2(),
                response.zipCode(),
                response.city(),
                response.countryCode(),
                response.phone1Area(),
                response.phone1Prefix(),
                response.phone1Line(),
                response.governmentId(),
                response.phone2Area(),
                response.phone2Prefix(),
                response.phone2Line(),
                response.eftAccountId(),
                response.primaryHolderFlag(),
                null); // action: not consumed by updateEntities
    }

    /**
     * Builds a fully populated update request from explicit literal values equal to the decomposed
     * form of {@link #sampleAccount()} / {@link #sampleCustomer()}. Using explicit literals (rather
     * than deriving from the decompose step) gives the compose tests an independent oracle.
     *
     * @return an {@link AccountUpdateRequest} carrying the expected screen component parts
     */
    private static AccountUpdateRequest explicitSampleRequest() {
        return new AccountUpdateRequest(
                "12345678901",              // accountId
                "Y",                        // accountStatus
                "2018", "03", "15",         // open year / month / day
                new BigDecimal("5000.00"),  // creditLimit
                "2025", "12", "31",         // expiry year / month / day
                new BigDecimal("1000.00"),  // cashLimit
                "2022", "01", "10",         // reissue year / month / day
                new BigDecimal("1234.56"),  // currentBalance
                new BigDecimal("250.00"),   // currentCycleCredit
                "GRP0000001",               // groupId
                new BigDecimal("175.25"),   // currentCycleDebit
                "987654321",                // customerId
                "123", "45", "6789",        // ssn part1 / part2 / part3
                "1985", "06", "20",         // dob year / month / day
                "750",                      // ficoScore
                "ALICE", "M", "CARDHOLDER", // first / middle / last name
                "100 MAIN ST",              // addressLine1
                "WA",                       // stateCode
                "APT 5",                    // addressLine2
                "98101",                    // zipCode (5-character screen field)
                "SEATTLE",                  // city
                "USA",                      // countryCode
                "206", "555", "0100",       // phone1 area / prefix / line
                "G-ABC-1234567",            // governmentId
                "425", "555", "0199",       // phone2 area / prefix / line
                "EFT0000001",               // eftAccountId
                "Y",                        // primaryHolderFlag
                null);                      // action: not consumed by updateEntities
    }

    // ------------------------------------------------------------------------
    // Phase 2 - toViewResponse: single-field merge of both entities.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("toViewResponse - single-field merge of account and customer")
    class ViewResponse {

        @Test
        @DisplayName("merges account and customer fields onto one response with header, info and error")
        void mergesBothEntities() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), "info", null, NOW);

            // Screen header / system fields.
            assertThat(response.transactionName()).isEqualTo("CAVW");
            assertThat(response.programName()).isEqualTo("COACTVWC");
            assertThat(response.title01()).contains("AWS Mainframe Modernization");
            assertThat(response.title02()).contains("CardDemo");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);

            // Account detail fields (single formatted strings on the view screen).
            assertThat(response.accountId()).isEqualTo("12345678901");
            assertThat(response.accountStatus()).isEqualTo("Y");
            assertThat(response.dateOpened()).isEqualTo("2018-03-15");
            assertThat(response.expiryDate()).isEqualTo("2025-12-31");
            assertThat(response.reissueDate()).isEqualTo("2022-01-10");
            assertThat(response.groupId()).isEqualTo("GRP0000001");

            // Customer detail fields.
            assertThat(response.customerId()).isEqualTo("987654321");
            assertThat(response.firstName()).isEqualTo("ALICE");
            assertThat(response.middleName()).isEqualTo("M");
            assertThat(response.lastName()).isEqualTo("CARDHOLDER");
            assertThat(response.addressLine1()).isEqualTo("100 MAIN ST");
            assertThat(response.addressLine2()).isEqualTo("APT 5");
            assertThat(response.stateCode()).isEqualTo("WA");
            assertThat(response.countryCode()).isEqualTo("USA");
            assertThat(response.phone1()).isEqualTo("(206)555-0100");
            assertThat(response.phone2()).isEqualTo("(425)555-0199");
            assertThat(response.ficoScore()).isEqualTo("750");
            assertThat(response.eftAccountId()).isEqualTo("EFT0000001");
            assertThat(response.primaryHolderFlag()).isEqualTo("Y");

            // Screen message fields.
            assertThat(response.infoMessage()).isEqualTo("info");
            assertThat(response.errorMessage()).isNull();
        }

        @Test
        @DisplayName("city comes from CUST-ADDR-LINE-3 and zipCode is the leading 5 of the customer ZIP")
        void cityFromAddrLine3AndZipLeadingFive() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), null, null, NOW);

            assertThat(response.city()).isEqualTo("SEATTLE");
            // Leading 5 of the customer ZIP "98101-1234"; NOT the account ZIP "99999-0000".
            assertThat(response.zipCode()).isEqualTo("98101");
            assertThat(response.zipCode()).doesNotContain("99999");
        }

        @Test
        @DisplayName("all five monetary fields are carried at scale 2 with their exact values")
        void monetaryFieldsCarriedAtScaleTwo() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), null, null, NOW);

            assertMoney(response.currentBalance(), "1234.56");
            assertMoney(response.creditLimit(), "5000.00");
            assertMoney(response.cashLimit(), "1000.00");
            assertMoney(response.currentCycleCredit(), "250.00");
            assertMoney(response.currentCycleDebit(), "175.25");
        }

        @Test
        @DisplayName("a null timestamp renders empty date and time header fields")
        void nullNowYieldsEmptyHeader() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), null, "boom", null);

            assertThat(response.currentDate()).isEmpty();
            assertThat(response.currentTime()).isEmpty();
            assertThat(response.errorMessage()).isEqualTo("boom");
        }

        @Test
        @DisplayName("a null account or customer is rejected (COBOL required-record contract)")
        void nullEntitiesRejected() {
            assertThatThrownBy(() -> mapper.toViewResponse(null, sampleCustomer(), null, null, NOW))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> mapper.toViewResponse(sampleAccount(), null, null, null, NOW))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    /**
     * Asserts that a monetary value equals the supplied canonical string amount and carries the
     * fixed monetary scale of 2. Uses {@code isEqualByComparingTo} so that the comparison is on
     * numeric value, and a separate {@code scale()} assertion so that representation is also pinned.
     *
     * @param actual   the monetary value produced by the mapper
     * @param expected the expected amount as a canonical decimal string
     */
    private static void assertMoney(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
        assertThat(actual.scale()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------
    // Phase 3 - Decompose (toUpdateResponse): split stored composites into parts.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("toUpdateResponse - decompose stored composites into screen parts")
    class Decompose {

        private AccountUpdateResponse decompose() {
            return mapper.toUpdateResponse(sampleAccount(), sampleCustomer(), "info", null, NOW);
        }

        @Test
        @DisplayName("SSN 123456789 splits into 123 / 45 / 6789 (substrings 0-3 / 3-5 / 5-9)")
        void ssnSplit() {
            AccountUpdateResponse response = decompose();
            assertThat(response.ssnPart1()).isEqualTo("123");
            assertThat(response.ssnPart2()).isEqualTo("45");
            assertThat(response.ssnPart3()).isEqualTo("6789");
        }

        @Test
        @DisplayName("date of birth 1985-06-20 splits into 1985 / 06 / 20")
        void dobSplit() {
            AccountUpdateResponse response = decompose();
            assertThat(response.dobYear()).isEqualTo("1985");
            assertThat(response.dobMonth()).isEqualTo("06");
            assertThat(response.dobDay()).isEqualTo("20");
        }

        @Test
        @DisplayName("open, expiration and reissue dates split into year / month / day")
        void dateSplits() {
            AccountUpdateResponse response = decompose();
            assertThat(response.openYear()).isEqualTo("2018");
            assertThat(response.openMonth()).isEqualTo("03");
            assertThat(response.openDay()).isEqualTo("15");
            assertThat(response.expiryYear()).isEqualTo("2025");
            assertThat(response.expiryMonth()).isEqualTo("12");
            assertThat(response.expiryDay()).isEqualTo("31");
            assertThat(response.reissueYear()).isEqualTo("2022");
            assertThat(response.reissueMonth()).isEqualTo("01");
            assertThat(response.reissueDay()).isEqualTo("10");
        }

        @Test
        @DisplayName("phone numbers split into area / prefix / line (substrings 1-4 / 5-8 / 9-13)")
        void phoneSplits() {
            AccountUpdateResponse response = decompose();
            assertThat(response.phone1Area()).isEqualTo("206");
            assertThat(response.phone1Prefix()).isEqualTo("555");
            assertThat(response.phone1Line()).isEqualTo("0100");
            assertThat(response.phone2Area()).isEqualTo("425");
            assertThat(response.phone2Prefix()).isEqualTo("555");
            assertThat(response.phone2Line()).isEqualTo("0199");
        }

        @Test
        @DisplayName("city, ZIP, header, constants and function-key labels are set on the update response")
        void scalarAndConstantFields() {
            AccountUpdateResponse response = decompose();
            assertThat(response.transactionName()).isEqualTo("CAUP");
            assertThat(response.programName()).isEqualTo("COACTUPC");
            assertThat(response.title01()).contains("AWS Mainframe Modernization");
            assertThat(response.title02()).contains("CardDemo");
            assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
            assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
            assertThat(response.accountId()).isEqualTo("12345678901");
            assertThat(response.customerId()).isEqualTo("987654321");
            assertThat(response.accountStatus()).isEqualTo("Y");
            assertThat(response.groupId()).isEqualTo("GRP0000001");
            assertThat(response.city()).isEqualTo("SEATTLE");
            assertThat(response.zipCode()).isEqualTo("98101");
            assertThat(response.functionKeys()).isEqualTo("ENTER=Process F3=Exit");
            assertThat(response.functionKeySave()).isEqualTo("F5=Save");
            assertThat(response.functionKeyCancel()).isEqualTo("F12=Cancel");
        }

        @Test
        @DisplayName("all five monetary fields are carried at scale 2 with their exact values")
        void monetaryFieldsCarriedAtScaleTwo() {
            AccountUpdateResponse response = decompose();
            assertMoney(response.currentBalance(), "1234.56");
            assertMoney(response.creditLimit(), "5000.00");
            assertMoney(response.cashLimit(), "1000.00");
            assertMoney(response.currentCycleCredit(), "250.00");
            assertMoney(response.currentCycleDebit(), "175.25");
        }
    }

    // ------------------------------------------------------------------------
    // Phase 4 - Compose (updateEntities): reassemble parts into stored fields.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("updateEntities - compose screen parts back into stored entity fields")
    class Compose {

        @Test
        @DisplayName("composes SSN, dates and phones and maps every single-value field")
        void composesStoredFields() {
            Account account = freshAccount(12345678901L, 3L);
            Customer customer = freshCustomer(987654321L);

            mapper.updateEntities(explicitSampleRequest(), account, customer);

            // Composed composite strings (parts reassembled into stored values).
            assertThat(customer.getCustSsn()).isEqualTo("123456789");
            assertThat(customer.getCustDob()).isEqualTo("1985-06-20");
            assertThat(account.getAcctOpenDate()).isEqualTo("2018-03-15");
            assertThat(account.getAcctExpirationDate()).isEqualTo("2025-12-31");
            assertThat(account.getAcctReissueDate()).isEqualTo("2022-01-10");
            assertThat(customer.getCustPhoneNum1()).isEqualTo("(206)555-0100");
            assertThat(customer.getCustPhoneNum2()).isEqualTo("(425)555-0199");

            // city composes back to CUST-ADDR-LINE-3; the 5-character ZIP is stored verbatim.
            assertThat(customer.getCustAddrLine3()).isEqualTo("SEATTLE");
            assertThat(customer.getCustAddrZip()).isEqualTo("98101");

            // Single-value account fields.
            assertThat(account.getAcctActiveStatus()).isEqualTo("Y");
            assertThat(account.getGroupId()).isEqualTo("GRP0000001");

            // Single-value customer fields.
            assertThat(customer.getCustFirstName()).isEqualTo("ALICE");
            assertThat(customer.getCustMiddleName()).isEqualTo("M");
            assertThat(customer.getCustLastName()).isEqualTo("CARDHOLDER");
            assertThat(customer.getCustAddrLine1()).isEqualTo("100 MAIN ST");
            assertThat(customer.getCustAddrLine2()).isEqualTo("APT 5");
            assertThat(customer.getCustAddrStateCd()).isEqualTo("WA");
            assertThat(customer.getCustAddrCountryCd()).isEqualTo("USA");
            assertThat(customer.getCustGovtIssuedId()).isEqualTo("G-ABC-1234567");
            assertThat(customer.getCustEftAccountId()).isEqualTo("EFT0000001");
            assertThat(customer.getCustPriCardHolderInd()).isEqualTo("Y");
            assertThat(customer.getCustFicoCreditScore()).isEqualTo(750);
        }

        @Test
        @DisplayName("all five monetary fields are stored on the account at scale 2")
        void composesMonetaryFieldsAtScaleTwo() {
            Account account = freshAccount(12345678901L, 0L);
            Customer customer = freshCustomer(987654321L);

            mapper.updateEntities(explicitSampleRequest(), account, customer);

            assertMoney(account.getCurrBal(), "1234.56");
            assertMoney(account.getCreditLimit(), "5000.00");
            assertMoney(account.getCashCreditLimit(), "1000.00");
            assertMoney(account.getCurrCycCredit(), "250.00");
            assertMoney(account.getCurrCycDebit(), "175.25");
        }

        @Test
        @DisplayName("a null request, account or customer is rejected")
        void nullArgumentsRejected() {
            assertThatThrownBy(() ->
                    mapper.updateEntities(null, freshAccount(1L, 0L), freshCustomer(1L)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    mapper.updateEntities(explicitSampleRequest(), null, freshCustomer(1L)))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                    mapper.updateEntities(explicitSampleRequest(), freshAccount(1L, 0L), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ------------------------------------------------------------------------
    // Phase 5 - Round-trip parity: decompose then compose returns the original.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("round-trip parity - decompose then compose returns the original stored values")
    class RoundTrip {

        @Test
        @DisplayName("SSN, DOB, all three dates, both phones, city and money survive with no drift")
        void decomposeThenComposeIsLossless() {
            Account source = sampleAccount();
            Customer sourceCustomer = sampleCustomer();

            AccountUpdateResponse decomposed =
                    mapper.toUpdateResponse(source, sourceCustomer, null, null, NOW);
            AccountUpdateRequest recomposed = requestFrom(decomposed);

            Account account = freshAccount(source.getAcctId(), 7L);
            Customer customer = freshCustomer(sourceCustomer.getCustId());
            mapper.updateEntities(recomposed, account, customer);

            // Sensitive composites round-trip exactly.
            assertThat(customer.getCustSsn()).isEqualTo(sourceCustomer.getCustSsn());
            assertThat(customer.getCustDob()).isEqualTo(sourceCustomer.getCustDob());

            // Account dates round-trip exactly.
            assertThat(account.getAcctOpenDate()).isEqualTo(source.getAcctOpenDate());
            assertThat(account.getAcctExpirationDate()).isEqualTo(source.getAcctExpirationDate());
            assertThat(account.getAcctReissueDate()).isEqualTo(source.getAcctReissueDate());

            // Phones round-trip exactly.
            assertThat(customer.getCustPhoneNum1()).isEqualTo(sourceCustomer.getCustPhoneNum1());
            assertThat(customer.getCustPhoneNum2()).isEqualTo(sourceCustomer.getCustPhoneNum2());

            // City round-trips exactly (sourced from and written back to CUST-ADDR-LINE-3).
            assertThat(customer.getCustAddrLine3()).isEqualTo(sourceCustomer.getCustAddrLine3());

            // Monetary values round-trip in both value and scale.
            assertMoney(account.getCurrBal(), source.getCurrBal().toPlainString());
            assertMoney(account.getCreditLimit(), source.getCreditLimit().toPlainString());
            assertMoney(account.getCashCreditLimit(), source.getCashCreditLimit().toPlainString());
            assertMoney(account.getCurrCycCredit(), source.getCurrCycCredit().toPlainString());
            assertMoney(account.getCurrCycDebit(), source.getCurrCycDebit().toPlainString());
        }

        @Test
        @DisplayName("ZIP is intentionally reduced to its 5-character screen form (documented, not drift)")
        void zipIsReducedToFiveCharsByDesign() {
            Account source = sampleAccount();
            Customer sourceCustomer = sampleCustomer();

            AccountUpdateResponse decomposed =
                    mapper.toUpdateResponse(source, sourceCustomer, null, null, NOW);
            Account account = freshAccount(source.getAcctId(), 1L);
            Customer customer = freshCustomer(sourceCustomer.getCustId());
            mapper.updateEntities(requestFrom(decomposed), account, customer);

            // The Update screen carries only a 5-character ZIP (ACSZIPC X(5)); the stored 10-char
            // ZIP is therefore reduced to its leading five characters on save. This is the mapper's
            // documented behavior, not round-trip drift, so ZIP is excluded from the parity set.
            assertThat(sourceCustomer.getCustAddrZip()).isEqualTo("98101-1234");
            assertThat(customer.getCustAddrZip()).isEqualTo("98101");
        }
    }

    // ------------------------------------------------------------------------
    // Phase 6 - Monetary fidelity: five fields, scale 2, no floating point.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("monetary fidelity - BigDecimal scale 2 across decompose and compose")
    class Monetary {

        @Test
        @DisplayName("the view response carries every monetary field at scale 2")
        void viewResponseScaleTwo() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), null, null, NOW);
            assertThat(response.creditLimit().scale()).isEqualTo(2);
            assertThat(response.cashLimit().scale()).isEqualTo(2);
            assertThat(response.currentBalance().scale()).isEqualTo(2);
            assertThat(response.currentCycleCredit().scale()).isEqualTo(2);
            assertThat(response.currentCycleDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("the update response carries every monetary field at scale 2")
        void updateResponseScaleTwo() {
            AccountUpdateResponse response =
                    mapper.toUpdateResponse(sampleAccount(), sampleCustomer(), null, null, NOW);
            assertThat(response.creditLimit().scale()).isEqualTo(2);
            assertThat(response.cashLimit().scale()).isEqualTo(2);
            assertThat(response.currentBalance().scale()).isEqualTo(2);
            assertThat(response.currentCycleCredit().scale()).isEqualTo(2);
            assertThat(response.currentCycleDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("compose stores every monetary field on the account at scale 2")
        void composeScaleTwo() {
            Account account = freshAccount(12345678901L, 0L);
            mapper.updateEntities(explicitSampleRequest(), account, freshCustomer(987654321L));
            assertThat(account.getCreditLimit().scale()).isEqualTo(2);
            assertThat(account.getCashCreditLimit().scale()).isEqualTo(2);
            assertThat(account.getCurrBal().scale()).isEqualTo(2);
            assertThat(account.getCurrCycCredit().scale()).isEqualTo(2);
            assertThat(account.getCurrCycDebit().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("a whole-number amount is normalized to scale 2 (100 -> 100.00) by the mapper")
        void wholeNumberNormalizedToScaleTwo() {
            // AccountViewResponse performs no monetary normalization of its own, so scale 2 here
            // proves the mapper's scale2() contract rather than a DTO-constructor side effect.
            Account wholeNumberAccount = new Account(
                    1L, "Y",
                    new BigDecimal("100"),  // currBal (scale 0 on input)
                    new BigDecimal("100"),  // creditLimit
                    new BigDecimal("100"),  // cashCreditLimit
                    "2020-01-01", "2020-01-01", "2020-01-01",
                    new BigDecimal("100"),  // currCycCredit
                    new BigDecimal("100"),  // currCycDebit
                    "00000", "GRP0000000");

            AccountViewResponse response =
                    mapper.toViewResponse(wholeNumberAccount, sampleCustomer(), null, null, NOW);

            assertMoney(response.currentBalance(), "100.00");
            assertMoney(response.creditLimit(), "100.00");
            assertMoney(response.cashLimit(), "100.00");
            assertMoney(response.currentCycleCredit(), "100.00");
            assertMoney(response.currentCycleDebit(), "100.00");
        }
    }

    // ------------------------------------------------------------------------
    // Phase 7 - Sensitive fields present on the screen yet never logged.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("sensitive fields - displayed on the maintenance screen yet never logged")
    class SensitiveFields {

        @Test
        @DisplayName("SSN, DOB and government id appear in the view response (legacy display parity)")
        void sensitivePresentInViewResponse() {
            AccountViewResponse response =
                    mapper.toViewResponse(sampleAccount(), sampleCustomer(), null, null, NOW);
            assertThat(response.ssn()).isEqualTo("123456789");
            assertThat(response.dateOfBirth()).isEqualTo("1985-06-20");
            assertThat(response.governmentId()).isEqualTo("G-ABC-1234567");
        }

        @Test
        @DisplayName("SSN parts, DOB parts and government id appear in the update response")
        void sensitivePresentInUpdateResponse() {
            AccountUpdateResponse response =
                    mapper.toUpdateResponse(sampleAccount(), sampleCustomer(), null, null, NOW);
            assertThat(response.ssnPart1()).isEqualTo("123");
            assertThat(response.ssnPart2()).isEqualTo("45");
            assertThat(response.ssnPart3()).isEqualTo("6789");
            assertThat(response.dobYear()).isEqualTo("1985");
            assertThat(response.dobMonth()).isEqualTo("06");
            assertThat(response.dobDay()).isEqualTo("20");
            assertThat(response.governmentId()).isEqualTo("G-ABC-1234567");
        }

        @Test
        @DisplayName("Customer.toString() excludes SSN, DOB and government id (AAP 0.9.3 never logged)")
        void customerToStringExcludesSensitive() {
            String rendered = sampleCustomer().toString();
            // Non-sensitive content is present, proving toString is genuinely populated...
            assertThat(rendered).contains("CARDHOLDER");
            // ...but the three sensitive values must never appear (mirrors Customer.toString).
            assertThat(rendered).doesNotContain("123456789");    // custSsn
            assertThat(rendered).doesNotContain("1985-06-20");    // custDob
            assertThat(rendered).doesNotContain("G-ABC-1234567"); // custGovtIssuedId
        }

        @Test
        @DisplayName("the response toString() masks the sensitive values (never logged)")
        void responseToStringMasksSensitive() {
            String rendered = mapper
                    .toViewResponse(sampleAccount(), sampleCustomer(), null, null, NOW)
                    .toString();
            // The three sensitive fields are rendered as the masked placeholder.
            assertThat(rendered).contains("ssn=***");
            assertThat(rendered).contains("dateOfBirth=***");
            assertThat(rendered).contains("governmentId=***");
            // Their raw values never appear. The SSN digits "123456789" are intentionally NOT used
            // as a sentinel here because they collide with the accountId prefix "12345678901"; the
            // DOB and government-id values are unique and therefore collision-free sentinels.
            assertThat(rendered).doesNotContain("1985-06-20");    // date-of-birth value
            assertThat(rendered).doesNotContain("G-ABC-1234567"); // government-id value
        }
    }

    // ------------------------------------------------------------------------
    // Phase 8 - Null / length safety of the split and compose helpers.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("null and length safety - helpers yield empty strings, never null, never throw")
    class NullSafety {

        @Test
        @DisplayName("null stored composites decompose to empty-string parts (never null, no NPE)")
        void nullCompositesDecomposeToEmpty() {
            // freshAccount has null dates; freshCustomer has null SSN, DOB and phone numbers.
            AccountUpdateResponse response =
                    mapper.toUpdateResponse(freshAccount(1L, 0L), freshCustomer(1L), null, null, NOW);

            assertThat(response.ssnPart1()).isEmpty();
            assertThat(response.ssnPart2()).isEmpty();
            assertThat(response.ssnPart3()).isEmpty();
            assertThat(response.dobYear()).isEmpty();
            assertThat(response.dobMonth()).isEmpty();
            assertThat(response.dobDay()).isEmpty();
            assertThat(response.openYear()).isEmpty();
            assertThat(response.openMonth()).isEmpty();
            assertThat(response.openDay()).isEmpty();
            assertThat(response.expiryYear()).isEmpty();
            assertThat(response.reissueYear()).isEmpty();
            assertThat(response.phone1Area()).isEmpty();
            assertThat(response.phone1Prefix()).isEmpty();
            assertThat(response.phone1Line()).isEmpty();
            assertThat(response.phone2Area()).isEmpty();
            assertThat(response.phone2Prefix()).isEmpty();
            assertThat(response.phone2Line()).isEmpty();
        }

        @Test
        @DisplayName("malformed short inputs yield empty missing parts without throwing")
        void malformedShortInputs() {
            Customer customer = freshCustomer(1L);
            customer.setCustSsn("123");       // only the first SSN part is present
            customer.setCustPhoneNum1("206"); // too short to contain a full area / prefix / line
            customer.setCustDob("2018");      // only the year is present
            Account account = freshAccount(1L, 0L);
            account.setAcctOpenDate("2018");  // only the year is present

            AccountUpdateResponse response =
                    mapper.toUpdateResponse(account, customer, null, null, NOW);

            // Present leading parts are returned.
            assertThat(response.ssnPart1()).isEqualTo("123");
            assertThat(response.dobYear()).isEqualTo("2018");
            assertThat(response.openYear()).isEqualTo("2018");
            // substring(1,4) of "206" is the two remaining characters "06" (deterministic).
            assertThat(response.phone1Area()).isEqualTo("06");
            // Missing parts are empty strings, never null, and no exception is thrown.
            assertThat(response.ssnPart2()).isEmpty();
            assertThat(response.ssnPart3()).isEmpty();
            assertThat(response.dobMonth()).isEmpty();
            assertThat(response.dobDay()).isEmpty();
            assertThat(response.openMonth()).isEmpty();
            assertThat(response.openDay()).isEmpty();
            assertThat(response.phone1Prefix()).isEmpty();
            assertThat(response.phone1Line()).isEmpty();
        }

        @Test
        @DisplayName("composing blank parts yields empty stored composites without throwing")
        void composeBlankPartsYieldsEmpty() {
            // Decomposing all-null entities produces a response whose parts are all empty strings;
            // feeding that back through compose must yield empty composites and must not throw.
            AccountUpdateResponse blank =
                    mapper.toUpdateResponse(freshAccount(1L, 0L), freshCustomer(1L), null, null, NOW);
            AccountUpdateRequest blankRequest = requestFrom(blank);

            Account account = freshAccount(1L, 0L);
            Customer customer = freshCustomer(1L);

            assertThatCode(() -> mapper.updateEntities(blankRequest, account, customer))
                    .doesNotThrowAnyException();

            assertThat(customer.getCustSsn()).isEmpty();
            assertThat(customer.getCustDob()).isEmpty();
            assertThat(customer.getCustPhoneNum1()).isEmpty();
            assertThat(customer.getCustPhoneNum2()).isEmpty();
            assertThat(account.getAcctOpenDate()).isEmpty();
            assertThat(account.getAcctExpirationDate()).isEmpty();
            assertThat(account.getAcctReissueDate()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Phase 9 - Key and version preservation: the mapper never touches them.
    // ------------------------------------------------------------------------

    @Nested
    @DisplayName("key and version preservation - acctId, custId and version are never overwritten")
    class KeyPreservation {

        @Test
        @DisplayName("updateEntities preserves pre-set keys and version even when the request carries other ids")
        void keysAndVersionUntouched() {
            // Pre-set the entity keys to values DIFFERENT from the request's accountId / customerId
            // so that any accidental copy of a key from the request would be detected here.
            Account account = freshAccount(55555555555L, 3L);
            Customer customer = freshCustomer(111222333L);

            // The request carries accountId "12345678901" and customerId "987654321".
            mapper.updateEntities(explicitSampleRequest(), account, customer);

            assertThat(account.getAcctId()).isEqualTo(55555555555L);   // key unchanged
            assertThat(customer.getCustId()).isEqualTo(111222333L);    // key unchanged
            assertThat(account.getVersion()).isEqualTo(3L);            // @Version is JPA-managed only
        }
    }


}
