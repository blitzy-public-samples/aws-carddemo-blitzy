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

import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.carddemo.common.domain.CardXref;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Testcontainers-backed persistence integration test for
 *     {@link CardXrefRepository}, covering the VSAM ``CXACAIX`` alternate
 *     index -> PostgreSQL ``card_xref`` migration. Verifies the custom
 *     ``findByXrefAcctId`` derived query for known and unknown account ids
 *     and a round-trip by the card-number primary key.
 * :output: JUnit 5 assertions; no console output. The card number is a
 *     synthetic test PAN and is never logged.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardXrefRepositoryIT {

    private static final String CARD_NUM = "4111111111111111";
    private static final Long CUST_ID = 1L;
    private static final Long ACCT_ID = 1L;
    private static final Long UNKNOWN_ACCT_ID = 9_999_999_999L;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static CardXref newCardXref() {
        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(CARD_NUM);
        cardXref.setXrefCustId(CUST_ID);
        cardXref.setXrefAcctId(ACCT_ID);
        return cardXref;
    }

    /**
     * :purpose: Insert the owning ``customers`` and ``accounts`` parent rows that the
     *     ``card_xref`` foreign keys (``fk_card_xref_customer``, ``fk_card_xref_account``)
     *     require under the create-drop schema, so a cross-reference can be persisted. Native
     *     inserts limited to the NOT NULL columns keep the fixture independent of the Customer
     *     and Account object models. Rationale -> docs/decision-log.md.
     */
    private void seedReferentialParents() {
        entityManager.createNativeQuery(
                "INSERT INTO customers (cust_id, cust_first_name, cust_last_name, "
                        + "cust_fico_credit_score, version) VALUES ("
                        + CUST_ID + ", 'TEST', 'CUSTOMER', 700, 0)")
                .executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO accounts (acct_id, acct_active_status, acct_curr_bal, "
                        + "acct_credit_limit, acct_cash_credit_limit, acct_open_date, "
                        + "acct_curr_cyc_credit, acct_curr_cyc_debit, version) VALUES ("
                        + ACCT_ID + ", 'Y', 0, 0, 0, '2020-01-01', 0, 0, 0)")
                .executeUpdate();
        entityManager.flush();
    }

    @Test
    @DisplayName("findByXrefAcctId returns the CXACAIX cross-reference for a known account id")
    void findByXrefAcctIdReturnsMatchForKnownAccount() {
        seedReferentialParents();
        cardXrefRepository.saveAndFlush(newCardXref());
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findByXrefAcctId(ACCT_ID);

        assertThat(found).isPresent();
        assertThat(found.get().getXrefCardNum()).isEqualTo(CARD_NUM);
        assertThat(found.get().getXrefCustId()).isEqualTo(CUST_ID);
        assertThat(found.get().getXrefAcctId()).isEqualTo(ACCT_ID);
    }

    @Test
    @DisplayName("findByXrefAcctId returns Optional.empty() for an unknown account id")
    void findByXrefAcctIdReturnsEmptyForUnknownAccount() {
        seedReferentialParents();
        cardXrefRepository.saveAndFlush(newCardXref());
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findByXrefAcctId(UNKNOWN_ACCT_ID);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findById round-trips a CardXref by its 16-digit card-number key")
    void findByIdRoundTripsByCardNumber() {
        seedReferentialParents();
        cardXrefRepository.saveAndFlush(newCardXref());
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findById(CARD_NUM);

        assertThat(found).isPresent();
        assertThat(found.get().getXrefCustId()).isEqualTo(CUST_ID);
        assertThat(found.get().getXrefAcctId()).isEqualTo(ACCT_ID);
    }
}
