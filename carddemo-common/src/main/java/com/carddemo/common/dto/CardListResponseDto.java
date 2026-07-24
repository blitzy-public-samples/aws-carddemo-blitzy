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
 * :purpose: Outbound DTO for the card list screen (COCRDLIC, CICS CCLI). Wraps the ordered page slice of card rows; page size and paging flags are decided by the card service, not this carrier.
 * :output: A mutable carrier wrapping the ordered list of card rows.
 */
public class CardListResponseDto {

    /** :purpose: the ordered page of card list rows. */
    private List<CardListItemDto> cards;

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

}
