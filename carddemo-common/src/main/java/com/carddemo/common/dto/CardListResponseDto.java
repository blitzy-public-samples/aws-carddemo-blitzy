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
package com.carddemo.common.dto;

import java.util.List;

/**
 * :purpose: Outbound DTO for the card list screen (``COCRDLIC``, CICS ``CCLI``). Wraps the
 *  ordered page slice of card rows together with the paging state the legacy screen keeps in
 *  the COMMAREA (``WS-CA-SCREEN-NUM``, ``CA-NEXT-PAGE-EXISTS``), the row selection
 *  (``CA-CARD-SELECT-FLAG`` / the selected card number) and the two message lines the screen
 *  displays: ``WS-ERROR-MSG`` and the informational ``WS-INFO-MSG``. Page size remains the
 *  card service's decision, not this carrier's.
 * :output: A mutable carrier wrapping the ordered list of card rows, the paging state, the
 *  resolved selection and the two message lines.
 * :note: Without the message lines the ``COCRDLIC`` banners
 *  ``'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'``, ``'NO MORE RECORDS TO SHOW'``,
 *  ``'NO MORE PAGES TO DISPLAY'``, ``'NO PREVIOUS PAGES TO DISPLAY'`` and
 *  ``'INVALID ACTION CODE'`` could not reach any client.
 */
public class CardListResponseDto {

    /** :purpose: the ordered page of card list rows. */
    private List<CardListItemDto> cards;

    /** :purpose: One-based number of the returned page (``WS-CA-SCREEN-NUM``). */
    private int pageNumber;

    /** :purpose: Whether a further forward page exists (``CA-NEXT-PAGE-EXISTS``). */
    private boolean nextPage;

    /** :purpose: The card number of the row the operator acted on. */
    private String selectedCardNumber;

    /**
     * :purpose: The canonical row action resolved from the selection flag: ``"S"`` to view
     *  the detail screen (``COCRDSLC``) or ``"U"`` to update (``COCRDUPC``).
     */
    private String selectedAction;

    /** :purpose: The ``WS-ERROR-MSG`` line the screen displays; ``null`` when none applies. */
    private String message;

    /**
     * :purpose: The ``WS-INFO-MSG`` line the screen displays, i.e.
     *  ``WS-INFORM-REC-ACTIONS`` = ``'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD'``.
     */
    private String infoMessage;

    /**
     * :purpose: Create an empty CardListResponseDto. Required for JSON (Jackson) serialization.
     */
    public CardListResponseDto() {
    }

    /**
     * :purpose: Return the ordered page of card list rows.
     * :output: the ``cards`` value.
     */
    public List<CardListItemDto> getCards() {
        return cards;
    }

    /**
     * :purpose: Set the ordered page of card list rows.
     * :param cards: the ``cards`` value.
     */
    public void setCards(List<CardListItemDto> cards) {
        this.cards = cards;
    }

    /**
     * :purpose: Return the one-based page number of this page.
     * :output: the ``pageNumber`` value.
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * :purpose: Set the one-based page number of this page.
     * :param pageNumber: the ``pageNumber`` value.
     */
    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * :purpose: Report whether a further forward page exists.
     * :output: the ``nextPage`` value.
     */
    public boolean isNextPage() {
        return nextPage;
    }

    /**
     * :purpose: Record whether a further forward page exists.
     * :param nextPage: the ``nextPage`` value.
     */
    public void setNextPage(boolean nextPage) {
        this.nextPage = nextPage;
    }

    /**
     * :purpose: Return the card number of the row the operator acted on.
     * :output: the ``selectedCardNumber`` value.
     */
    public String getSelectedCardNumber() {
        return selectedCardNumber;
    }

    /**
     * :purpose: Set the card number of the row the operator acted on.
     * :param selectedCardNumber: the ``selectedCardNumber`` value.
     */
    public void setSelectedCardNumber(String selectedCardNumber) {
        this.selectedCardNumber = selectedCardNumber;
    }

    /**
     * :purpose: Return the canonical row action, ``"S"`` or ``"U"``.
     * :output: the ``selectedAction`` value.
     */
    public String getSelectedAction() {
        return selectedAction;
    }

    /**
     * :purpose: Set the canonical row action, ``"S"`` or ``"U"``.
     * :param selectedAction: the ``selectedAction`` value.
     */
    public void setSelectedAction(String selectedAction) {
        this.selectedAction = selectedAction;
    }

    /**
     * :purpose: Return the ``WS-ERROR-MSG`` line the screen displays.
     * :output: the ``message`` value.
     */
    public String getMessage() {
        return message;
    }

    /**
     * :purpose: Set the ``WS-ERROR-MSG`` line the screen displays.
     * :param message: the ``message`` value.
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * :purpose: Return the ``WS-INFO-MSG`` line the screen displays.
     * :output: the ``infoMessage`` value.
     */
    public String getInfoMessage() {
        return infoMessage;
    }

    /**
     * :purpose: Set the ``WS-INFO-MSG`` line the screen displays.
     * :param infoMessage: the ``infoMessage`` value.
     */
    public void setInfoMessage(String infoMessage) {
        this.infoMessage = infoMessage;
    }

}
