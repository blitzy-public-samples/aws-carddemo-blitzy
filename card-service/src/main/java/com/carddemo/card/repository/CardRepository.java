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
import java.util.Optional;

import jakarta.persistence.LockModeType;


import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Finds one ordered window of the cards belonging to a single owning account.
     *
     * :purpose: Reproduces the ``CARDAIX`` account-index browse of ``COCRDLIC``
     *     while reading only the rows one screen needs: the caller asks for
     *     ``WS-MAX-SCREEN-LINES`` rows plus the one lookahead record the COBOL
     *     browse reads to learn whether a further page exists.
     * :param cardAcctId: the owning account identifier used as the
     *     alternate-index key.
     * :param pageable: the offset/limit window, ordered by card number to match
     *     the VSAM primary-key browse order.
     * :output: a possibly-empty ``List<Card>`` holding at most
     *     ``pageable.getPageSize()`` cards of the account, ascending by card number.
     */
    List<Card> findByCardAcctIdOrderByCardNumAsc(Long cardAcctId, Pageable pageable);

    /**
     * Finds one ordered window of the unfiltered card master browse.
     *
     * :purpose: Reproduces the unfiltered ``CARDDAT`` browse of ``COCRDLIC`` while
     *     reading only the rows one screen needs, so the store never materialises
     *     the whole card base for a seven-row page.
     * :param pageable: the offset/limit window, ordered by card number to match
     *     the VSAM primary-key browse order.
     * :output: a possibly-empty ``List<Card>`` holding at most
     *     ``pageable.getPageSize()`` cards, ascending by card number.
     */
    List<Card> findAllByOrderByCardNumAsc(Pageable pageable);

    /**
     * :purpose: Read a card for the ``COCRDUPC`` rewrite while holding a database write lock on
     *     its row, reproducing the legacy ``READ ... UPDATE`` against ``CARDDAT``.
     * :param cardNum: the sixteen-character card number (primary key).
     * :output: the locked card, or an empty ``Optional`` when no such card exists.
     * :note: The lock works WITH the ``@Version`` column rather than instead of it: the
     *     version tells a caller whose data has moved, the lock serialises the re-read that
     *     makes the determination reliable under concurrency
     *     [app/cbl/COCRDUPC.cbl:L1440-1519]. Rationale: docs/decision-log.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Card c where c.cardNum = :cardNum")
    Optional<Card> findForUpdateByCardNum(@Param("cardNum") String cardNum);
}
