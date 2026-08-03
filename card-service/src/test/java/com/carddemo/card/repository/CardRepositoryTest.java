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
import java.util.List;
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

import com.carddemo.common.domain.Account;
import com.carddemo.common.domain.Card;
import com.carddemo.common.testsupport.MigratedSchemaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * :purpose: Persistence-slice tests for {@link CardRepository}, exercising the derived
 *  query that replaces the legacy VSAM ``CARDAIX`` alternate-index browse and the
 *  primary-key read that replaces the ``CARDDATA`` KSDS key access.
 * :output: Confirms account-scoped ``findByCardAcctId`` (zero-to-many rows),
 *  primary-key ``findById`` round-trip, and empty-result handling against a real
 *  ``postgres:18`` Testcontainer.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardRepositoryTest {

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

    private static final String CARD_A1 = "4111111111111111";
    private static final String CARD_A2 = "4111111111111112";
    private static final String CARD_B1 = "4222222222222222";

    /** :purpose: Account ids outside the 1..50 range the migrations seed, so this test owns its rows. */
    private static final Long ACCT_A = 90_000_011L;
    private static final Long ACCT_B = 90_000_012L;
    private static final Long UNKNOWN_ACCT_ID = 9_999_999_999L;

    @Autowired
    private CardRepository cardRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * :purpose: Builds a transient {@link Card} fixture using only non-sensitive fields.
     * :param cardNum: the 16-character card-number primary key.
     * :param cardAcctId: the owning account identifier (``card_acct_id``).
     * :output: an unsaved {@link Card} populated with non-sensitive fields only.
     */
    private static Card newCard(String cardNum, Long cardAcctId) {
        Card card = new Card();
        card.setCardNum(cardNum);
        card.setCardAcctId(cardAcctId);
        card.setCardEmbossedName("TEST CARDHOLDER");
        card.setCardExpiraionDate("2025-12-31");
        card.setCardActiveStatus("Y");
        // card_cvv_cd intentionally left unset (PII: never seeded, asserted, or logged)
        return card;
    }

    /**
     * :purpose: Persists and flushes a minimal owning account row for ``acctId`` so that
     *  cards referencing it satisfy the ``fk_cards_account`` referential constraint that
     *  the {@link Card} entity and the ``cards`` schema declare against ``accounts``.
     * :param acctId: the owning account identifier (``acct_id``) to create.
     */
    private void persistOwningAccount(Long acctId) {
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
        entityManager.persist(account);
        entityManager.flush();
    }

    @Test
    @DisplayName("findByCardAcctId returns all cards for a known account and excludes other accounts")
    void findByCardAcctIdReturnsAccountScopedCards() {
        persistOwningAccount(ACCT_A);
        persistOwningAccount(ACCT_B);
        cardRepository.saveAndFlush(newCard(CARD_A1, ACCT_A));
        cardRepository.saveAndFlush(newCard(CARD_A2, ACCT_A));
        cardRepository.saveAndFlush(newCard(CARD_B1, ACCT_B));
        entityManager.clear();

        List<Card> cards = cardRepository.findByCardAcctId(ACCT_A);

        assertThat(cards).hasSize(2);
        assertThat(cards).extracting(Card::getCardNum)
                .containsExactlyInAnyOrder(CARD_A1, CARD_A2);
        assertThat(cards).extracting(Card::getCardAcctId).containsOnly(ACCT_A);
    }

    /**
     * :purpose: The migrated ``cards`` table enforces card ownership in the database
     *  (AAP 0.1.1): a card naming an account absent from the accounts master is rejected by
     *  ``fk_cards_account`` instead of being silently written the way the application-enforced
     *  VSAM read order allowed.
     * :output: ``DataIntegrityViolationException`` on flush.
     */
    @Test
    @DisplayName("a card referencing an unknown account is rejected by the account foreign key")
    void unknownAccountIsRejectedByForeignKey() {
        Card orphan = newCard(CARD_B1, UNKNOWN_ACCT_ID);

        assertThatThrownBy(() -> cardRepository.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByCardAcctId returns an empty list for an unknown account")
    void findByCardAcctIdReturnsEmptyForUnknownAccount() {
        persistOwningAccount(ACCT_A);
        cardRepository.saveAndFlush(newCard(CARD_A1, ACCT_A));
        entityManager.clear();

        List<Card> cards = cardRepository.findByCardAcctId(UNKNOWN_ACCT_ID);

        assertThat(cards).isEmpty();
    }

    @Test
    @DisplayName("findById round-trips a card by its 16-digit card-number key")
    void findByIdRoundTripsByCardNumber() {
        persistOwningAccount(ACCT_A);
        cardRepository.saveAndFlush(newCard(CARD_A1, ACCT_A));
        entityManager.clear();

        Optional<Card> found = cardRepository.findById(CARD_A1);

        assertThat(found).isPresent();
        Card card = found.orElseThrow();
        assertThat(card.getCardAcctId()).isEqualTo(ACCT_A);
        assertThat(card.getCardEmbossedName()).isEqualTo("TEST CARDHOLDER");
        assertThat(card.getCardExpiraionDate()).isEqualTo("2025-12-31");
        assertThat(card.getCardActiveStatus()).isEqualTo("Y");
    }
}
