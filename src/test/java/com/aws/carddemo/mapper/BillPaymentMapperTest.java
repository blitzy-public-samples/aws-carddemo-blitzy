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

import com.aws.carddemo.common.util.DateUtils;
import com.aws.carddemo.domain.Account;
import com.aws.carddemo.dto.BillPaymentResponse;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BillPaymentMapper}, the hand-written projection that assembles the
 * {@link BillPaymentResponse} output contract for the CardDemo <em>Bill Payment</em> screen
 * (CICS transaction {@code CB00}, online program {@code COBIL00C}; source branch
 * {@code app/cbl/COBIL00C.cbl}). The mapper bridges the {@link Account} entity (record layout
 * {@code app/cpy/CVACT01Y.cpy}) to the bill-payment DTO derived from the BMS symbolic map
 * {@code COBIL0AO} ({@code app/cpy-bms/COBIL00.CPY}).
 *
 * <p><strong>What these tests prove (AAP&nbsp;&sect;0.9.2 field-contract and monetary-fidelity
 * parity, &sect;0.7.1 hotspot&nbsp;H3):</strong>
 * <ul>
 *   <li><strong>Monetary fidelity (the most important assertion).</strong> The
 *       {@code ACCT-CURR-BAL PIC S9(10)V99} balance is carried as a {@link BigDecimal} at a fixed
 *       scale of {@code 2}. Every balance assertion checks the value with
 *       {@code isEqualByComparingTo} <em>and</em> the scale with {@code scale()}, because value
 *       equality alone would not catch a scale regression. A scale-0 input is proven to normalize
 *       to scale 2. Every monetary literal is built with the {@code BigDecimal(String)}
 *       constructor &mdash; never an IEEE-754 binary literal and never a lossy primitive-argument
 *       {@code BigDecimal} factory &mdash; preserving decimal exactness.</li>
 *   <li><strong>Identifier rendering.</strong> The {@code Long} {@code ACCT-ID PIC 9(11)} key
 *       renders through {@code Long.toString(long)} to a clean digit string with no grouping
 *       separators, decimal point, or {@code L} suffix ({@code ACTIDINO PIC X(11)}).</li>
 *   <li><strong>Header population.</strong> The fixed {@code CB00}/{@code COBIL00C} transaction
 *       and program identifiers, the default title lines, and the {@code MM/dd/uu} date and
 *       {@code HH:mm:ss} time (formatted through the shared {@link DateUtils} helpers, the
 *       re-platform of {@code CSDAT01Y}) are placed on the response.</li>
 *   <li><strong>Not-found / pre-lookup path.</strong> The {@code String}-keyed overload echoes
 *       the operator-entered account id and status message while leaving the balance
 *       {@code null}, reproducing the legacy behavior of echoing the key alongside a validation
 *       or not-found message before a balance is displayed.</li>
 *   <li><strong>Null-safety.</strong> A {@code null} account, account id, balance, or timestamp
 *       yields {@code null} for the corresponding response component rather than throwing.</li>
 * </ul>
 *
 * <p>This is a pure JUnit&nbsp;5 + AssertJ unit test: it instantiates the stateless mapper
 * directly ({@code new BillPaymentMapper()}) with no Spring context, no database, and no
 * Mockito, and it uses a fixed {@link LocalDateTime} so every run is deterministic. The chosen
 * timestamp mirrors the {@code CVACT01Y.cpy} version stamp
 * ({@code Date: 2022-07-19 23:15:59 CDT}).
 */
class BillPaymentMapperTest {

    /**
     * Deterministic timestamp for every header date/time assertion. Chosen to mirror the
     * {@code app/cpy/CVACT01Y.cpy} version stamp ({@code 2022-07-19 23:15:59 CDT}). Formatted by
     * {@link DateUtils} it yields {@link #EXPECTED_DATE} and {@link #EXPECTED_TIME}.
     */
    private static final LocalDateTime NOW = LocalDateTime.of(2022, 7, 19, 23, 15, 58);

    /** Expected {@code MM/dd/uu} rendering of {@link #NOW} ({@code CURDATEO PIC X(8)}). */
    private static final String EXPECTED_DATE = "07/19/22";

    /** Expected {@code HH:mm:ss} rendering of {@link #NOW} ({@code CURTIMEO PIC X(8)}). */
    private static final String EXPECTED_TIME = "23:15:58";

    /**
     * Eleven-digit account identifier fixture, matching {@code ACCT-ID PIC 9(11)}. Held as a
     * primitive {@code long} literal; the value {@code 12345678901} is exactly eleven digits.
     */
    private static final long ACCT_ID = 12_345_678_901L;

    /** Expected clean {@code Long.toString(long)} rendering of {@link #ACCT_ID}. */
    private static final String ACCT_ID_DIGITS = "12345678901";

    /** The single stateless collaborator under test. */
    private final BillPaymentMapper mapper = new BillPaymentMapper();

    /**
     * Builds a fresh {@link Account} fixture for each test using the <em>public</em>
     * all-arguments constructor. The no-argument constructor is {@code protected} and therefore
     * not accessible from this (mapper) package, so the all-args constructor is used; only the
     * two fields the mapper reads ({@code acctId}, {@code currBal}) carry meaningful values and
     * the remaining persistent fields are {@code null}. A new instance per call keeps the
     * mutation-based tests independent.
     *
     * @return a new account with {@code acctId = }{@value #ACCT_ID_DIGITS} and a
     *         {@code currBal} of {@code 1234.56}
     */
    private static Account newAccount() {
        return new Account(
                ACCT_ID,                    // acctId (ACCT-ID PIC 9(11))
                "Y",                        // acctActiveStatus (ACCT-ACTIVE-STATUS PIC X(01))
                new BigDecimal("1234.56"),  // currBal (ACCT-CURR-BAL PIC S9(10)V99)
                null,                       // creditLimit
                null,                       // cashCreditLimit
                null,                       // acctOpenDate
                null,                       // acctExpirationDate
                null,                       // acctReissueDate
                null,                       // currCycCredit
                null,                       // currCycDebit
                null,                       // acctAddrZip
                null);                      // groupId
    }

    @Test
    @DisplayName("toResponse renders the Long acctId to a clean digit string (no comma/decimal/suffix)")
    void happyPath_accountIdRendersAsCleanDigits() {
        Account account = newAccount();

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);

        assertThat(response.accountId())
                .isEqualTo(ACCT_ID_DIGITS)
                .doesNotContain(",", ".", "L", " ");
    }

    @Test
    @DisplayName("toResponse carries currBal as BigDecimal preserving value and scale 2")
    void happyPath_currentBalanceHasValueAndScaleTwo() {
        Account account = newAccount();

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);

        // Value equality (scale-insensitive) AND scale equality: both are required, because
        // isEqualByComparingTo alone would pass for 1234.560 or 1234.5600 and miss a regression.
        assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("toResponse normalizes a scale-0 balance to scale 2 (100 -> 100.00)")
    void scaleZeroBalanceIsNormalizedToScaleTwo() {
        Account account = newAccount();
        account.setCurrBal(new BigDecimal("100")); // scale 0

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);

        assertThat(response.currentBalance().scale()).isEqualTo(2);
        assertThat(response.currentBalance()).isEqualByComparingTo("100.00");
        // isEqualTo delegates to BigDecimal.equals, which is scale-sensitive: this proves the
        // value is represented at exactly scale 2 (i.e. "100.00", not "100").
        assertThat(response.currentBalance()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("BillPaymentResponse.currentBalance is declared BigDecimal (never an IEEE-754 binary type)")
    void currentBalanceComponentIsDeclaredBigDecimal() {
        RecordComponent currentBalance = null;
        for (RecordComponent component : BillPaymentResponse.class.getRecordComponents()) {
            if ("currentBalance".equals(component.getName())) {
                currentBalance = component;
                break;
            }
        }

        assertThat(currentBalance).as("currentBalance record component must exist").isNotNull();
        assertThat(currentBalance.getType()).isEqualTo(BigDecimal.class);
    }

    @Test
    @DisplayName("toResponse (default titles) populates the CB00/COBIL00C header and MM/dd/uu + HH:mm:ss")
    void populatesHeaderConstantsAndFormattedDateTime() {
        Account account = newAccount();

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);

        assertThat(response.transactionName())
                .isEqualTo("CB00")
                .isEqualTo(BillPaymentMapper.TRANSACTION_NAME);
        assertThat(response.programName())
                .isEqualTo("COBIL00C")
                .isEqualTo(BillPaymentMapper.PROGRAM_NAME);
        assertThat(response.title01()).isEqualTo(BillPaymentMapper.TITLE01);
        assertThat(response.title02()).isEqualTo(BillPaymentMapper.TITLE02);
        assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
        assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
        // Cross-check the header date/time are produced through the shared DateUtils helpers.
        assertThat(response.currentDate()).isEqualTo(DateUtils.formatDateMmDdYy(NOW.toLocalDate()));
        assertThat(response.currentTime()).isEqualTo(DateUtils.formatTimeHhMmSs(NOW.toLocalTime()));
        // A null status message is carried through as null.
        assertThat(response.errorMessage()).isNull();
    }

    @Test
    @DisplayName("toResponse (5-arg) echoes caller-supplied title lines and status message")
    void customTitlesOverloadEchoesTitlesAndMessage() {
        Account account = newAccount();
        String title01 = "CUSTOM HEADER LINE ONE";
        String title02 = "CUSTOM HEADER LINE TWO";
        String message = "Enter Y to confirm payment";

        BillPaymentResponse response = mapper.toResponse(account, message, NOW, title01, title02);

        assertThat(response.title01()).isEqualTo(title01);
        assertThat(response.title02()).isEqualTo(title02);
        assertThat(response.errorMessage()).isEqualTo(message);
        assertThat(response.accountId()).isEqualTo(ACCT_ID_DIGITS);
        assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("toResponse (String not-found) echoes the entered id, sets the message, leaves balance null")
    void notFoundOverloadEchoesEnteredIdWithNullBalance() {
        String enteredId = "00000000042";
        String message = "Account ID NOT found...";

        BillPaymentResponse response = mapper.toResponse(enteredId, message, NOW);

        assertThat(response.accountId()).isEqualTo(enteredId);
        assertThat(response.errorMessage()).isEqualTo(message);
        assertThat(response.currentBalance()).isNull();
        // The screen header is still populated on the not-found path.
        assertThat(response.transactionName()).isEqualTo("CB00");
        assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
        assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
    }

    @Test
    @DisplayName("toResponse (String not-found, 5-arg) echoes custom titles with a null balance")
    void notFoundCustomTitlesOverloadHasNullBalance() {
        String enteredId = "99999999999";
        String title01 = "T1";
        String title02 = "T2";

        BillPaymentResponse response = mapper.toResponse(enteredId, "msg", NOW, title01, title02);

        assertThat(response.accountId()).isEqualTo(enteredId);
        assertThat(response.title01()).isEqualTo(title01);
        assertThat(response.title02()).isEqualTo(title02);
        assertThat(response.currentBalance()).isNull();
    }

    @Test
    @DisplayName("toResponse with a null currBal yields a null balance without throwing")
    void nullBalanceYieldsNullBalanceWithoutThrowing() {
        Account account = newAccount();
        account.setCurrBal(null);

        assertThatCode(() -> mapper.toResponse(account, null, NOW)).doesNotThrowAnyException();

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);
        assertThat(response.currentBalance()).isNull();
        // The account id is still rendered even when the balance is absent.
        assertThat(response.accountId()).isEqualTo(ACCT_ID_DIGITS);
    }

    @Test
    @DisplayName("toResponse with a null Account yields null id and balance without throwing; header intact")
    void nullAccountYieldsNullIdAndBalanceWithoutThrowing() {
        // The (Account) cast is required to select the Account overload over the String overload.
        assertThatCode(() -> mapper.toResponse((Account) null, null, NOW)).doesNotThrowAnyException();

        BillPaymentResponse response = mapper.toResponse((Account) null, null, NOW);
        assertThat(response.accountId()).isNull();
        assertThat(response.currentBalance()).isNull();
        // Header fields independent of the account remain populated.
        assertThat(response.transactionName()).isEqualTo("CB00");
        assertThat(response.currentDate()).isEqualTo(EXPECTED_DATE);
        assertThat(response.currentTime()).isEqualTo(EXPECTED_TIME);
    }

    @Test
    @DisplayName("toResponse with a null timestamp yields null date/time without throwing; data fields intact")
    void nullNowYieldsNullDateTimeWithoutThrowing() {
        Account account = newAccount();

        assertThatCode(() -> mapper.toResponse(account, null, null)).doesNotThrowAnyException();

        BillPaymentResponse response = mapper.toResponse(account, null, null);
        assertThat(response.currentDate()).isNull();
        assertThat(response.currentTime()).isNull();
        // Everything not derived from the timestamp is still present.
        assertThat(response.transactionName()).isEqualTo("CB00");
        assertThat(response.accountId()).isEqualTo(ACCT_ID_DIGITS);
        assertThat(response.currentBalance()).isEqualByComparingTo("1234.56");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("toResponse with a null acctId yields a null accountId while still carrying the balance")
    void nullAcctIdYieldsNullAccountId() {
        Account account = new Account(
                null,                       // acctId is null
                "Y",
                new BigDecimal("5.00"),
                null, null, null, null, null, null, null, null, null);

        BillPaymentResponse response = mapper.toResponse(account, null, NOW);

        assertThat(response.accountId()).isNull();
        assertThat(response.currentBalance()).isEqualByComparingTo("5.00");
        assertThat(response.currentBalance().scale()).isEqualTo(2);
    }
}
