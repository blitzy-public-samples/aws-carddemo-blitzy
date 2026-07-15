/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.account.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aws.carddemo.account.domain.Account;
import com.aws.carddemo.account.dto.AccountResponse;
import com.aws.carddemo.account.dto.AccountUpdateRequest;

/**
 * Unit tests for {@link AccountMapper} verifying currency scale-2 and ISO date
 * ({@code yyyy-MM-dd}) formatting parity with the retired legacy 3270 Account View.
 *
 * <p>The legacy presentation contract these tests protect comes from two places:
 * the {@code COACTVW.bms} map, whose five monetary output fields carry the edit
 * picture {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} (two fraction digits), and the
 * {@code COACTVWC.cbl} format paragraph {@code 1200-SETUP-SCREEN-VARS}, which
 * performs straight {@code MOVE}s of the {@code ACCOUNT-RECORD} fields
 * ({@code CVACT01Y.cpy}) onto those output fields. The comma grouping and the
 * always-leading sign of the 3270 edit are terminal display artifacts with no
 * place in a JSON numeric contract; what carries forward is the two-fraction-digit
 * fidelity and non-scientific rendering. These tests therefore assert the modern
 * carry-forward: every monetary value is a {@link BigDecimal} at {@code scale() == 2}
 * whose {@code toPlainString()} contains no scientific-notation exponent, and every
 * date is a {@link LocalDate} that renders ISO {@code yyyy-MM-dd}.
 *
 * <p>This is a pure unit test: {@link AccountMapper} is a stateless {@link org.springframework.stereotype.Component}
 * with no injected collaborators, so it is instantiated directly with {@code new}
 * &mdash; no Spring context, no database, no mocking framework is involved. All
 * fixture values are drawn from record 1 of the legacy seed file
 * {@code app/data/ASCII/acctdata.txt} (the seed-row-1 oracle).
 */
class AccountMapperTest {

    private AccountMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AccountMapper();
    }

    // ---------- toResponse: currency scale-2 parity (all five money fields) ----------

    @Test
    @DisplayName("toResponse normalizes all five monetary fields to scale 2")
    void toResponseScalesAllMoneyFieldsToTwoDecimals() {
        Account account = seedAccount();
        account.setCurrentBalance(new BigDecimal("194"));        // scale 0
        account.setCreditLimit(new BigDecimal("2020.0"));        // scale 1
        account.setCashCreditLimit(new BigDecimal("1020.000"));  // scale 3
        account.setCurrentCycleCredit(new BigDecimal("0"));      // scale 0
        account.setCurrentCycleDebit(new BigDecimal("-50.5"));   // scale 1, negative

        AccountResponse response = mapper.toResponse(account);

        assertThat(response.getCurrentBalance().scale()).isEqualTo(2);
        assertThat(response.getCurrentBalance().toPlainString()).isEqualTo("194.00");
        assertThat(response.getCreditLimit().scale()).isEqualTo(2);
        assertThat(response.getCreditLimit().toPlainString()).isEqualTo("2020.00");
        assertThat(response.getCashCreditLimit().scale()).isEqualTo(2);
        assertThat(response.getCashCreditLimit().toPlainString()).isEqualTo("1020.00");
        assertThat(response.getCurrentCycleCredit().scale()).isEqualTo(2);
        assertThat(response.getCurrentCycleCredit().toPlainString()).isEqualTo("0.00");
        assertThat(response.getCurrentCycleDebit().scale()).isEqualTo(2);
        assertThat(response.getCurrentCycleDebit().toPlainString()).isEqualTo("-50.50");
    }

    @Test
    @DisplayName("toResponse renders money without scientific notation")
    void toResponseMoneyNeverUsesScientificNotation() {
        Account account = seedAccount();
        account.setCurrentBalance(new BigDecimal("1E+7")); // raw toString() would be "1E+7"

        AccountResponse response = mapper.toResponse(account);
        BigDecimal balance = response.getCurrentBalance();

        assertThat(balance.scale()).isEqualTo(2);
        assertThat(balance.toPlainString()).isEqualTo("10000000.00");
        assertThat(balance.toPlainString()).doesNotContain("E").doesNotContain("e");
        assertThat(balance.toString()).doesNotContain("E").doesNotContain("e");
    }

    // ---------- toResponse: date + identity parity ----------

    @Test
    @DisplayName("toResponse copies dates unchanged, rendering ISO yyyy-MM-dd")
    void toResponseCopiesDatesAsIso() {
        AccountResponse response = mapper.toResponse(seedAccount());

        assertThat(response.getOpenDate()).isEqualTo(LocalDate.of(2014, 11, 20));
        assertThat(response.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(response.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(response.getOpenDate().toString()).isEqualTo("2014-11-20");
        assertThat(response.getExpirationDate().toString()).isEqualTo("2025-05-20");
        assertThat(response.getReissueDate().toString()).isEqualTo("2025-05-20");
    }

    @Test
    @DisplayName("toResponse echoes id, status, zip, group and version")
    void toResponseEchoesIdentityAndVersion() {
        AccountResponse response = mapper.toResponse(seedAccount());

        assertThat(response.getAccountId()).isEqualTo("00000000001");
        assertThat(response.getActiveStatus()).isEqualTo("Y");
        assertThat(response.getAddressZip()).isEqualTo("A000000000");
        assertThat(response.getGroupId()).isEqualTo("TESTGRP01");
        assertThat(response.getVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("toResponse returns null for a null entity")
    void toResponseNullEntityReturnsNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }

    // ---------- applyUpdate: date parsing + money scaling ----------

    @Test
    @DisplayName("applyUpdate parses string dates to LocalDate and scales money to 2")
    void applyUpdateParsesDatesAndScalesMoney() {
        Account account = seedAccount();
        AccountUpdateRequest request = seedUpdateRequest();

        mapper.applyUpdate(request, account);

        assertThat(account.getActiveStatus()).isEqualTo("N");
        assertThat(account.getOpenDate()).isEqualTo(LocalDate.of(2020, 1, 15));
        assertThat(account.getExpirationDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getReissueDate()).isEqualTo(LocalDate.of(2025, 5, 20));
        assertThat(account.getCurrentBalance().scale()).isEqualTo(2);
        assertThat(account.getCurrentBalance().toPlainString()).isEqualTo("500.00");
        assertThat(account.getCreditLimit().toPlainString()).isEqualTo("9999999999.99");
        assertThat(account.getAddressZip()).isEqualTo("B111111111");
    }

    // ---------- applyUpdate: immutability ----------

    @Test
    @DisplayName("applyUpdate never modifies accountId, groupId or version")
    void applyUpdateLeavesImmutableFieldsUnchanged() {
        Account account = seedAccount();
        account.setVersion(7L);

        mapper.applyUpdate(seedUpdateRequest(), account);

        assertThat(account.getAccountId()).isEqualTo("00000000001");
        assertThat(account.getGroupId()).isEqualTo("TESTGRP01");
        assertThat(account.getVersion()).isEqualTo(7L);
    }

    // ---------- applyUpdate: null-safety (both argument positions) ----------

    @Test
    @DisplayName("applyUpdate is a no-op for a null request")
    void applyUpdateNullRequestIsNoOp() {
        Account account = seedAccount();

        assertThatCode(() -> mapper.applyUpdate(null, account)).doesNotThrowAnyException();

        assertThat(account.getActiveStatus()).isEqualTo("Y");
        assertThat(account.getCurrentBalance().toPlainString()).isEqualTo("194.00");
    }

    @Test
    @DisplayName("applyUpdate is a no-op for a null account")
    void applyUpdateNullAccountIsNoOp() {
        assertThatCode(() -> mapper.applyUpdate(seedUpdateRequest(), null))
                .doesNotThrowAnyException();
    }

    // ---------- fixtures (seed row 1 oracle: app/data/ASCII/acctdata.txt) ----------

    /**
     * Builds an {@link Account} entity populated from record 1 of the legacy seed
     * file (the seed-row-1 oracle). {@code groupId} is set to a non-blank fixture
     * value ({@code "TESTGRP01"}) so the immutability assertions can prove the mapper
     * leaves it untouched (the seed row's own group id is blank).
     */
    private Account seedAccount() {
        Account account = new Account();
        account.setAccountId("00000000001");
        account.setActiveStatus("Y");
        account.setCurrentBalance(new BigDecimal("194.00"));
        account.setCreditLimit(new BigDecimal("2020.00"));
        account.setCashCreditLimit(new BigDecimal("1020.00"));
        account.setOpenDate(LocalDate.of(2014, 11, 20));
        account.setExpirationDate(LocalDate.of(2025, 5, 20));
        account.setReissueDate(LocalDate.of(2025, 5, 20));
        account.setCurrentCycleCredit(new BigDecimal("0.00"));
        account.setCurrentCycleDebit(new BigDecimal("0.00"));
        account.setAddressZip("A000000000");
        account.setGroupId("TESTGRP01");
        account.setVersion(3L);
        return account;
    }

    /**
     * Builds a fully populated {@link AccountUpdateRequest}. The three dates are set
     * as ISO {@code yyyy-MM-dd} {@link String}s (the request DTO intentionally types
     * its dates as {@code String} so the service-layer validator can strict-parse
     * them); the mapper parses them into {@link LocalDate}. The request carries no
     * {@code accountId} or {@code groupId} field, which is what structurally enforces
     * their read-only status.
     */
    private AccountUpdateRequest seedUpdateRequest() {
        AccountUpdateRequest request = new AccountUpdateRequest();
        request.setActiveStatus("N");
        request.setCurrentBalance(new BigDecimal("500"));
        request.setCreditLimit(new BigDecimal("9999999999.99"));
        request.setCashCreditLimit(new BigDecimal("1500.5"));
        request.setOpenDate("2020-01-15");
        request.setExpirationDate("2025-05-20");
        request.setReissueDate("2025-05-20");
        request.setCurrentCycleCredit(new BigDecimal("0"));
        request.setCurrentCycleDebit(new BigDecimal("0"));
        request.setAddressZip("B111111111");
        request.setVersion(3L);
        return request;
    }
}
