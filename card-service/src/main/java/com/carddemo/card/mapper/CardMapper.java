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
package com.carddemo.card.mapper;

import com.carddemo.common.domain.Card;
import com.carddemo.common.domain.CardXref;
import com.carddemo.common.dto.CardDetailResponseDto;
import com.carddemo.common.dto.CardListItemDto;
import com.carddemo.common.dto.CardListResponseDto;
import com.carddemo.common.dto.CardUpdateRequestDto;
import com.carddemo.common.dto.CardUpdateResponseDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * :purpose: Hand-written, stateless mapper that translates between the shared
 *   ``carddemo-common`` card entities ({@link Card}, {@link CardXref}) and the
 *   card list/detail/update DTOs (``com.carddemo.common.dto``). Re-expresses the
 *   screen field-assembly of the legacy CICS programs ``COCRDLIC`` (``CCLI``,
 *   card list, seven rows per page), ``COCRDSLC`` (``CCDL``, card detail) and
 *   ``COCRDUPC`` (``CCUP``, card update). Performs pure field copies only: no
 *   persistence, cross-reference lookup, validation, reformatting or logging;
 *   those responsibilities belong to ``CardService``.
 */
@Component
public class CardMapper {

    /**
     * :purpose: Assemble the read-only card detail response from a resolved card
     *   and its (optional) cross-reference, re-expressing the ``COCRDSLC`` detail
     *   SEND-MAP assembly. Copies the card number, owning account id, embossed
     *   name, expiry date and active status; when a cross-reference is
     *   supplied it also copies the owning customer id. The sensitive CVV is
     *   never placed in the response.
     * :param card: the resolved card entity; when ``null`` the method returns
     *   ``null``.
     * :param cardXref: the resolved card cross-reference supplying the owning
     *   customer id, or ``null`` when it has not been joined.
     * :returns: a populated {@link CardDetailResponseDto}, or ``null`` when
     *   ``card`` is ``null``.
     */
    public CardDetailResponseDto toDetailResponse(Card card, CardXref cardXref) {
        if (card == null) {
            return null;
        }
        CardDetailResponseDto response = new CardDetailResponseDto();
        response.setCardNum(card.getCardNum());
        response.setCardAcctId(card.getCardAcctId());
        response.setCardEmbossedName(card.getCardEmbossedName());
        response.setCardExpiraionDate(card.getCardExpiraionDate());
        response.setCardActiveStatus(card.getCardActiveStatus());
        if (cardXref != null) {
            response.setCustId(cardXref.getXrefCustId());
        }
        return response;
    }

    /**
     * :purpose: Assemble the card list response from a page slice of card
     *   entities, re-expressing the ``COCRDLIC`` list-row population where each
     *   row carries the owning account id, card number and active status (seven
     *   rows per page on the legacy screen). Pagination is performed by
     *   ``CardService``; this mapper maps exactly the slice it is given.
     * :param cards: the page of card entities to map; a ``null`` or empty list
     *   yields a response with an empty row collection.
     * :returns: a populated {@link CardListResponseDto} whose row collection
     *   mirrors the supplied cards in order.
     */
    public CardListResponseDto toListResponse(List<Card> cards) {
        CardListResponseDto response = new CardListResponseDto();
        List<CardListItemDto> rows = new ArrayList<>();
        if (cards != null) {
            for (Card card : cards) {
                if (card != null) {
                    rows.add(toListRow(card));
                }
            }
        }
        response.setCards(rows);
        return response;
    }

    /**
     * :purpose: Map a single card entity to one card-list row, copying only the
     *   three fields shown on the legacy ``COCRDLIC`` list screen: owning account
     *   id, card number and active status. The sensitive CVV is never read here.
     * :param card: the card entity to map; must be non-``null``.
     * :returns: a populated {@link CardListItemDto} row.
     */
    private CardListItemDto toListRow(Card card) {
        CardListItemDto row = new CardListItemDto();
        row.setCardAcctId(card.getCardAcctId());
        row.setCardNum(card.getCardNum());
        row.setCardActiveStatus(card.getCardActiveStatus());
        return row;
    }

    /**
     * :purpose: Apply the editable card fields from an update request onto the
     *   caller-supplied managed card, re-expressing the ``COCRDUPC``
     *   update-record preparation. Mutates the passed-in entity in place so JPA
     *   optimistic-locking and identity are preserved; sets only the embossed
     *   name, active status, expiry date and CVV. The card number (primary
     *   key) and the owning account id (linkage) are identifiers and are never
     *   overwritten from the request.
     * :param request: the card update request DTO; when ``null`` the method is a
     *   no-op.
     * :param card: the managed card entity to mutate; when ``null`` the method is
     *   a no-op.
     */
    public void applyUpdate(CardUpdateRequestDto request, Card card) {
        if (request == null || card == null) {
            return;
        }
        card.setCardEmbossedName(request.getCardEmbossedName());
        card.setCardActiveStatus(request.getCardActiveStatus());
        card.setCardExpiraionDate(request.getCardExpiraionDate());
        card.setCardCvvCd(request.getCardCvvCd());
    }

    /**
     * :purpose: Assemble the post-update echo response reflecting the freshly
     *   persisted card state, using the same field set as
     *   {@link #toDetailResponse(Card, CardXref)}. The sensitive CVV is never
     *   placed in the response.
     * :param card: the persisted card entity; when ``null`` the method returns
     *   ``null``.
     * :param cardXref: the resolved card cross-reference supplying the owning
     *   customer id, or ``null`` when it has not been joined.
     * :returns: a populated {@link CardUpdateResponseDto}, or ``null`` when
     *   ``card`` is ``null``.
     */
    public CardUpdateResponseDto toUpdateResponse(Card card, CardXref cardXref) {
        if (card == null) {
            return null;
        }
        CardUpdateResponseDto response = new CardUpdateResponseDto();
        response.setCardNum(card.getCardNum());
        response.setCardAcctId(card.getCardAcctId());
        response.setCardEmbossedName(card.getCardEmbossedName());
        response.setCardExpiraionDate(card.getCardExpiraionDate());
        response.setCardActiveStatus(card.getCardActiveStatus());
        if (cardXref != null) {
            response.setCustId(cardXref.getXrefCustId());
        }
        return response;
    }
}
