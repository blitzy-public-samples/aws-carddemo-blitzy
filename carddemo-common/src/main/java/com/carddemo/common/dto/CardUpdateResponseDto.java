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

/**
 * :purpose: Outbound DTO echoing the persisted card state after an update (COCRDUPC, CICS CCUP). Uses the same field set as the card detail response; the sensitive CVV is never surfaced.
 * :output: A mutable carrier of the persisted card detail fields.
 */
public class CardUpdateResponseDto {

    /** :purpose: the card number primary key (``CARD-NUM`` PIC X(16)). */
    private String cardNum;

    /** :purpose: the owning account id (``CARD-ACCT-ID``). */
    private Long cardAcctId;

    /** :purpose: the embossed name (``CARD-EMBOSSED-NAME``). */
    private String cardEmbossedName;

    /** :purpose: the card expiration date (legacy-spelled ``CARD-EXPIRAION-DATE``, YYYY-MM-DD). */
    private String cardExpiraionDate;

    /** :purpose: the card active status (``CARD-ACTIVE-STATUS``). */
    private String cardActiveStatus;

    /** :purpose: the owning customer id resolved from the card cross-reference (``XREF-CUST-ID``). */
    private Long custId;

    /**
     * :purpose: Create an empty CardUpdateResponseDto. Required for JSON (Jackson) serialization.
     */
    public CardUpdateResponseDto() {
    }

    /**
     * :purpose: Return the card number primary key (``CARD-NUM`` PIC X(16)).
     * :output: the ``cardNum`` value.
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * :purpose: Set the card number primary key (``CARD-NUM`` PIC X(16)).
     * :param cardNum: the ``cardNum`` value.
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
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
     * :purpose: Return the embossed name (``CARD-EMBOSSED-NAME``).
     * :output: the ``cardEmbossedName`` value.
     */
    public String getCardEmbossedName() {
        return cardEmbossedName;
    }

    /**
     * :purpose: Set the embossed name (``CARD-EMBOSSED-NAME``).
     * :param cardEmbossedName: the ``cardEmbossedName`` value.
     */
    public void setCardEmbossedName(String cardEmbossedName) {
        this.cardEmbossedName = cardEmbossedName;
    }

    /**
     * :purpose: Return the card expiration date (legacy-spelled ``CARD-EXPIRAION-DATE``, YYYY-MM-DD).
     * :output: the ``cardExpiraionDate`` value.
     */
    public String getCardExpiraionDate() {
        return cardExpiraionDate;
    }

    /**
     * :purpose: Set the card expiration date (legacy-spelled ``CARD-EXPIRAION-DATE``, YYYY-MM-DD).
     * :param cardExpiraionDate: the ``cardExpiraionDate`` value.
     */
    public void setCardExpiraionDate(String cardExpiraionDate) {
        this.cardExpiraionDate = cardExpiraionDate;
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

    /**
     * :purpose: Return the owning customer id resolved from the card cross-reference (``XREF-CUST-ID``).
     * :output: the ``custId`` value.
     */
    public Long getCustId() {
        return custId;
    }

    /**
     * :purpose: Set the owning customer id resolved from the card cross-reference (``XREF-CUST-ID``).
     * :param custId: the ``custId`` value.
     */
    public void setCustId(Long custId) {
        this.custId = custId;
    }

}
