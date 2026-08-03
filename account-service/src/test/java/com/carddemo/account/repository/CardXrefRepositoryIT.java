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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.common.domain.CardXref;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardXrefRepositoryIT {

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *     from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`. The
     *     ``card_xref`` table, its two foreign keys and its alternate-index secondary index all
     *     come from the committed card-service migrations, so ``ddl-auto: validate`` asserts the
     *     entity-to-migration contract instead of validating test-authored DDL.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    private static final String CARD_NUM = "4111111111111111";

    /** :purpose: Ids outside the 1..50 range the migrations seed, so this test owns its rows. */
    private static final Long CUST_ID = 900_000_002L;
    private static final Long ACCT_ID = 90_000_002L;
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
     *     require, using ids outside the migration seed range so the seeded rows are never
     *     touched. Native inserts limited to the NOT NULL columns keep the fixture independent
     *     of the Customer and Account object models (and of the PII converter).
     */
    private void seedReferentialParents() {
        entityManager.createNativeQuery(
                "INSERT INTO customers (cust_id, cust_first_name, cust_middle_name, "
                        + "cust_last_name, cust_addr_line_1, cust_addr_line_2, cust_addr_line_3, "
                        + "cust_addr_state_cd, cust_addr_country_cd, cust_addr_zip, "
                        + "cust_phone_num_1, cust_phone_num_2, cust_ssn, cust_govt_issued_id, "
                        + "cust_dob_yyyy_mm_dd, cust_eft_account_id, cust_pri_card_holder_ind, "
                        + "cust_fico_credit_score, version) VALUES ("
                        + CUST_ID + ", 'TEST', 'T', 'CUSTOMER', 'ADDR1', 'ADDR2', 'ADDR3', "
                        + "'NC', 'USA', '00000', '0000000000', '0000000000', '', '', "
                        + "'1970-01-01', '', 'Y', 700, 0)")
                .executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO accounts (acct_id, acct_active_status, acct_curr_bal, "
                        + "acct_credit_limit, acct_cash_credit_limit, acct_open_date, "
                        + "acct_expiraion_date, acct_reissue_date, acct_curr_cyc_credit, "
                        + "acct_curr_cyc_debit, acct_addr_zip, version) VALUES ("
                        + ACCT_ID + ", 'Y', 0, 0, 0, '2020-01-01', '2099-12-31', '2020-01-01', "
                        + "0, 0, '00000', 0)")
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

    /**
     * :purpose: The migrated ``card_xref`` enforces the CXACAIX linkage in the database
     *     (AAP 0.1.1): a cross-reference naming an account that is absent from the accounts
     *     master is rejected by ``fk_card_xref_account`` rather than silently persisted the way
     *     the application-enforced VSAM order allowed.
     * :output: ``DataIntegrityViolationException`` on flush.
     */
    @Test
    @DisplayName("a cross-reference to an unknown account is rejected by the account foreign key")
    void unknownAccountIsRejectedByForeignKey() {
        seedReferentialParents();

        CardXref orphan = new CardXref("4111111111111112", CUST_ID, UNKNOWN_ACCT_ID);

        assertThatThrownBy(() -> cardXrefRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * :purpose: The migrated ``card_xref`` also enforces the customer side of the linkage
     *     (AAP 0.1.1): a cross-reference naming a customer that is absent from the customers
     *     master is rejected by ``fk_card_xref_customer``.
     * :output: ``DataIntegrityViolationException`` on flush.
     */
    @Test
    @DisplayName("a cross-reference to an unknown customer is rejected by the customer foreign key")
    void unknownCustomerIsRejectedByForeignKey() {
        seedReferentialParents();

        CardXref orphan = new CardXref("4111111111111113", 999_999_999L, ACCT_ID);

        assertThatThrownBy(() -> cardXrefRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
