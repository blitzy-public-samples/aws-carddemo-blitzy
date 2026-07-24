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

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carddemo.common.domain.Card;

/**
 * Spring Data JPA repository for the ``cards`` store.
 *
 * :purpose: Replaces the legacy keyed and alternate-index VSAM access of the
 *     card master file ``CARDDAT`` and its account alternate index ``CARDAIX``.
 *     The inherited keyed read and rewrite reproduce the card-detail read
 *     (``COCRDSLC``) and the card-update rewrite (``COCRDUPC``), while
 *     {@link #findByCardAcctId(Long)} reproduces the account-filtered card-list
 *     browse (``COCRDLIC``).
 * :output: The inherited ``findById`` performs the keyed read by 16-character
 *     card number and returns an ``Optional<Card>``; ``save`` persists a card
 *     via insert or update.
 */
@Repository
public interface CardRepository extends JpaRepository<Card, String> {

    /**
     * Finds every card belonging to a single owning account.
     *
     * :purpose: Reproduces the ``CARDAIX`` account-index browse used by the
     *     legacy card-list flow (``COCRDLIC``), resolving all cards owned by one
     *     account.
     * :param cardAcctId: the owning account identifier used as the
     *     alternate-index key.
     * :output: a possibly-empty ``List<Card>`` of the cards belonging to the
     *     account; an empty list means no cards were found for the account id.
     */
    List<Card> findByCardAcctId(Long cardAcctId);
}
