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

import com.carddemo.common.json.CobolNumberSerializers;
import tools.jackson.databind.annotation.JsonSerialize;

/**
 * :purpose: Outbound single-row DTO for the card list screen (COCRDLIC, CICS CCLI, seven rows per page). Carries the three columns the legacy list map surfaces per row: owning account id, card number, and active status.
 * :output: A mutable row carrier of the owning account id, card number, and active status.
 */
public class CardListItemDto {

    /** :purpose: the owning account id (``CARD-ACCT-ID``). */
    @JsonSerialize(using = CobolNumberSerializers.AccountId.class)
    private Long cardAcctId;

    /** :purpose: the card number (``CARD-NUM`` PIC X(16)). */
    private String cardNum;

    /** :purpose: the card active status (``CARD-ACTIVE-STATUS``). */
    private String cardActiveStatus;

    /**
     * :purpose: Create an empty CardListItemDto. Required for JSON (Jackson) serialization.
     */
    public CardListItemDto() {
    }

    /**
     * :purpose: Return the owning account id (``CARD-ACCT-ID``).
     * :output: the ``cardAcctId`` value.
     */
    public Long getCardAcctId() {
        return cardAcctId;
    }

    /**
     * :purpose: Set the owning account id (``CARD-ACCT-ID``).
     * :param cardAcctId: the ``cardAcctId`` value.
     */
    public void setCardAcctId(Long cardAcctId) {
        this.cardAcctId = cardAcctId;
    }

    /**
     * :purpose: Return the card number (``CARD-NUM`` PIC X(16)).
     * :output: the ``cardNum`` value.
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * :purpose: Set the card number (``CARD-NUM`` PIC X(16)).
     * :param cardNum: the ``cardNum`` value.
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * :purpose: Return the card active status (``CARD-ACTIVE-STATUS``).
     * :output: the ``cardActiveStatus`` value.
     */
    public String getCardActiveStatus() {
        return cardActiveStatus;
    }

    /**
     * :purpose: Set the card active status (``CARD-ACTIVE-STATUS``).
     * :param cardActiveStatus: the ``cardActiveStatus`` value.
     */
    public void setCardActiveStatus(String cardActiveStatus) {
        this.cardActiveStatus = cardActiveStatus;
    }

}
