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
package com.carddemo.card.repository;

import java.math.BigDecimal;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.testsupport.MigratedSchemaContainer;
import com.carddemo.common.domain.Customer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * :purpose: Persistence-slice tests for {@link CardXrefRepository}, exercising the
 *  card-to-customer-to-account cross-reference (legacy ``CVACT03Y`` / ``CXACAIX``)
 *  lookups that enforce referential integrity during transaction validation.
 * :output: Confirms account-scoped ``findByXrefAcctId`` (present / empty) and
 *  primary-key ``findById`` round-trip against a real ``postgres:18`` Testcontainer.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardXrefRepositoryTest {

    /**
     * :purpose: Bind the datasource to the shared, already-migrated ``postgres:18`` container
     *  from :java:class:`com.carddemo.common.testsupport.MigratedSchemaContainer`, whose schema,
     *  indexes, foreign keys and seed data come exclusively from the committed Flyway
     *  migrations. With ``ddl-auto: validate`` the entity-to-migration mapping becomes an
     *  assertion of this test instead of a fixture it manufactures.
     * :param registry: the dynamic property registry supplied by the Spring Test context.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        MigratedSchemaContainer.registerDataSource(registry);
    }

    private static final String CARD_1 = "4111111111111111";
    private static final String CARD_2 = "4222222222222222";

    /** :purpose: Ids outside the 1..50 range the migrations seed, so this test owns its rows. */
    private static final Long CUST_1 = 900_000_011L;
    private static final Long CUST_2 = 900_000_012L;
    private static final Long ACCT_1 = 90_000_021L;
    private static final Long ACCT_2 = 90_000_022L;
    private static final Long UNKNOWN_ACCT_ID = 9_999_999_999L;

    @Autowired
    private CardXrefRepository cardXrefRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * :purpose: Build a detached cross-reference from the three persisted columns.
     * :param cardNum: 16-character card number (primary key).
     * :param custId: owning customer identifier.
     * :param acctId: owning account identifier.
     * :output: a transient ``CardXref`` ready to persist.
     */
    private static CardXref newCardXref(String cardNum, Long custId, Long acctId) {
        CardXref cardXref = new CardXref();
        cardXref.setXrefCardNum(cardNum);
        cardXref.setXrefCustId(custId);
        cardXref.setXrefAcctId(acctId);
        return cardXref;
    }

    /**
     * :purpose: Build a minimal valid owning customer carrying only the not-null columns.
     * :param custId: customer identifier (primary key).
     * :output: a transient ``Customer`` ready to persist; PII columns are left unset.
     */
    private static Customer newCustomer(Long custId) {
        Customer customer = new Customer();
        customer.setCustId(custId);
        customer.setCustFirstName("Test");
        customer.setCustMiddleName("T");
        customer.setCustLastName("Customer");
        customer.setCustAddrLine1("Addr line 1");
        customer.setCustAddrLine2("Addr line 2");
        customer.setCustAddrLine3("Addr line 3");
        customer.setCustAddrStateCd("NC");
        customer.setCustAddrCountryCd("USA");
        customer.setCustAddrZip("00000");
        customer.setCustPhoneNum1("(000)000-0000");
        customer.setCustPhoneNum2("(000)000-0000");
        customer.setCustDobYyyyMmDd("1970-01-01");
        customer.setCustPriCardHolderInd("Y");
        customer.setCustFicoCreditScore(700);
        // The migrated NOT NULL PII columns carry blank values: no real PII is ever seeded
        // (AAP 0.6.7) and the CryptoConverter passes empty strings through unchanged.
        customer.setCustSsn("");
        customer.setCustGovtIssuedId("");
        customer.setCustEftAccountId("");
        return customer;
    }

    /**
     * :purpose: Build a minimal valid owning account carrying only the not-null columns.
     * :param acctId: account identifier (primary key).
     * :output: a transient ``Account`` ready to persist; monetary columns default to zero.
     */
    private static Account newAccount(Long acctId) {
        Account account = new Account();
        account.setAcctId(acctId);
        account.setAcctActiveStatus("Y");
        account.setAcctCurrBal(BigDecimal.ZERO);
        account.setAcctCreditLimit(BigDecimal.ZERO);
        account.setAcctCashCreditLimit(BigDecimal.ZERO);
        account.setAcctOpenDate("2020-01-01");
        account.setAcctExpiraionDate("2099-12-31");
        account.setAcctReissueDate("2020-01-01");
        account.setAcctCurrCycCredit(BigDecimal.ZERO);
        account.setAcctCurrCycDebit(BigDecimal.ZERO);
        account.setAcctAddrZip("00000");
        return account;
    }

    /**
     * :purpose: Persist the owning customer and account so a cross-reference row satisfies the
     *  ``fk_card_xref_customer`` and ``fk_card_xref_account`` referential-integrity constraints.
     * :param custId: owning customer identifier.
     * :param acctId: owning account identifier.
     */
    private void seedParents(Long custId, Long acctId) {
        entityManager.persist(newCustomer(custId));
        entityManager.persist(newAccount(acctId));
        entityManager.flush();
    }

    /**
     * :purpose: The ``CXACAIX`` account-scoped lookup resolves the one cross-reference for a
     *  known account and does not leak another account's row.
     */
    @Test
    @DisplayName("findByXrefAcctId returns the CXACAIX cross-reference for a known account id")
    void findByXrefAcctIdReturnsMatchForKnownAccount() {
        seedParents(CUST_1, ACCT_1);
        seedParents(CUST_2, ACCT_2);
        cardXrefRepository.saveAndFlush(newCardXref(CARD_1, CUST_1, ACCT_1));
        cardXrefRepository.saveAndFlush(newCardXref(CARD_2, CUST_2, ACCT_2));
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findByXrefAcctId(ACCT_1);

        assertThat(found).isPresent();
        CardXref match = found.orElseThrow();
        assertThat(match.getXrefCardNum()).isEqualTo(CARD_1);
        assertThat(match.getXrefCustId()).isEqualTo(CUST_1);
        assertThat(match.getXrefAcctId()).isEqualTo(ACCT_1);
    }

    /**
     * :purpose: An account with no cross-reference yields an empty result, mirroring the legacy
     *  ``NOTFND`` referential-integrity failure (reject code 100 context).
     */
    @Test
    @DisplayName("findByXrefAcctId returns Optional.empty() for an unknown account id")
    void findByXrefAcctIdReturnsEmptyForUnknownAccount() {
        seedParents(CUST_1, ACCT_1);
        cardXrefRepository.saveAndFlush(newCardXref(CARD_1, CUST_1, ACCT_1));
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findByXrefAcctId(UNKNOWN_ACCT_ID);

        assertThat(found).isEmpty();
    }

    /**
     * :purpose: The 16-digit card-number primary key (legacy ``CARDXREF`` KSDS key) round-trips
     *  through a persist-then-read-back cycle with its customer and account identifiers intact.
     */
    @Test
    @DisplayName("findById round-trips a CardXref by its 16-digit card-number key")
    void findByIdRoundTripsByCardNumber() {
        seedParents(CUST_1, ACCT_1);
        cardXrefRepository.saveAndFlush(newCardXref(CARD_1, CUST_1, ACCT_1));
        entityManager.clear();

        Optional<CardXref> found = cardXrefRepository.findById(CARD_1);

        assertThat(found).isPresent();
        CardXref match = found.orElseThrow();
        assertThat(match.getXrefCustId()).isEqualTo(CUST_1);
        assertThat(match.getXrefAcctId()).isEqualTo(ACCT_1);
    }
}
