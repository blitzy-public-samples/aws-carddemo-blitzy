/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.account.repository;

import java.math.BigDecimal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.carddemo.common.domain.Account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :purpose: Testcontainers-backed persistence integration test for
 *     {@link AccountRepository}, covering the VSAM ``ACCTFILE`` ->
 *     PostgreSQL ``accounts`` migration behind ``COACTVWC``/``COACTUPC``.
 *     Verifies that the five ``NUMERIC(12,2)`` money columns round-trip at
 *     scale 2 and that the JPA ``@Version`` optimistic-lock column is live
 *     (increments on update; a stale write is rejected), reproducing the
 *     COACTUPC read-snapshot-compare-rewrite concurrency semantics.
 * :output: JUnit 5 assertions; no console output.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountRepositoryIT {

    private static final Long ACCT_ID = 1L;

    @Autowired
    private AccountRepository accountRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static Account newAccount(Long acctId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(new BigDecimal("1234.56"));
        account.setAcctCreditLimit(new BigDecimal("5000.00"));
        account.setAcctCashCreditLimit(new BigDecimal("2500.99"));
        account.setAcctCurrCycCredit(new BigDecimal("0.00"));
        account.setAcctCurrCycDebit(new BigDecimal("789.01"));
        account.setAcctOpenDate("2014-11-20");
        account.setAcctExpiraionDate("2025-05-20");
        account.setAcctReissueDate("2025-05-20");
        account.setAcctAddrZip("A000000000");
        return account;
    }

    @Test
    @DisplayName("save then findById round-trips all five NUMERIC(12,2) money fields at scale 2")
    void saveAndFindByIdRoundTripsMoneyFields() {
        accountRepository.saveAndFlush(newAccount(ACCT_ID));
        entityManager.clear();

        Account loaded = accountRepository.findById(ACCT_ID).orElseThrow();

        assertThat(loaded.getAcctId()).isEqualTo(ACCT_ID);
        assertThat(loaded.getAcctActiveStatus()).isEqualTo("Y");

        assertThat(loaded.getAcctCurrBal()).isEqualByComparingTo("1234.56");
        assertThat(loaded.getAcctCurrBal().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(loaded.getAcctCreditLimit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCashCreditLimit()).isEqualByComparingTo("2500.99");
        assertThat(loaded.getAcctCashCreditLimit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCurrCycCredit()).isEqualByComparingTo("0.00");
        assertThat(loaded.getAcctCurrCycCredit().scale()).isEqualTo(2);
        assertThat(loaded.getAcctCurrCycDebit()).isEqualByComparingTo("789.01");
        assertThat(loaded.getAcctCurrCycDebit().scale()).isEqualTo(2);

        assertThat(loaded.getAcctOpenDate()).isEqualTo("2014-11-20");
        assertThat(loaded.getAcctExpiraionDate()).isEqualTo("2025-05-20");
    }

    @Test
    @DisplayName("@Version increments on update (optimistic-lock column is live)")
    void versionIncrementsOnUpdate() {
        accountRepository.saveAndFlush(newAccount(ACCT_ID));
        entityManager.clear();

        Account loaded = accountRepository.findById(ACCT_ID).orElseThrow();
        Long baseVersion = loaded.getVersion();
        assertThat(baseVersion).isNotNull();

        loaded.setAcctCurrBal(new BigDecimal("250.00"));
        accountRepository.saveAndFlush(loaded);
        entityManager.clear();

        Account reloaded = accountRepository.findById(ACCT_ID).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(baseVersion + 1);
        assertThat(reloaded.getAcctCurrBal()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("stale @Version write is rejected with OptimisticLockingFailureException")
    void staleVersionWriteRaisesOptimisticLockException() {
        accountRepository.saveAndFlush(newAccount(ACCT_ID));
        entityManager.clear();

        Account first = accountRepository.findById(ACCT_ID).orElseThrow();
        entityManager.detach(first);
        Account second = accountRepository.findById(ACCT_ID).orElseThrow();

        second.setAcctCurrBal(new BigDecimal("300.00"));
        accountRepository.saveAndFlush(second);
        entityManager.clear();

        first.setAcctCurrBal(new BigDecimal("400.00"));
        assertThatThrownBy(() -> accountRepository.saveAndFlush(first))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}
