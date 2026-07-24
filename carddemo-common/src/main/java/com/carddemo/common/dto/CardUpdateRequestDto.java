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
 * :purpose: Inbound DTO for the card update screen (COCRDUPC, CICS CCUP). Carries the editable card fields; the card number primary key and owning account id are identifiers and are never carried here.
 * :output: A mutable carrier of the editable embossed name, active status, expiry date, and CVV.
 */
public class CardUpdateRequestDto {

    /** :purpose: the embossed name (``CARD-EMBOSSED-NAME``). */
    private String cardEmbossedName;

    /** :purpose: the card active status (``CARD-ACTIVE-STATUS``). */
    private String cardActiveStatus;

    /** :purpose: the card expiration date (legacy-spelled ``CARD-EXPIRAION-DATE``, YYYY-MM-DD). */
    private String cardExpiraionDate;

    /** :purpose: the card CVV code (``CARD-CVV-CD``); write-only, never returned. */
    private String cardCvvCd;

    /**
     * :purpose: Create an empty CardUpdateRequestDto. Required for JSON (Jackson) serialization.
     */
    public CardUpdateRequestDto() {
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
     * :purpose: Return the card CVV code (``CARD-CVV-CD``); write-only, never returned.
     * :output: the ``cardCvvCd`` value.
     */
    public String getCardCvvCd() {
        return cardCvvCd;
    }

    /**
     * :purpose: Set the card CVV code (``CARD-CVV-CD``); write-only, never returned.
     * :param cardCvvCd: the ``cardCvvCd`` value.
     */
    public void setCardCvvCd(String cardCvvCd) {
        this.cardCvvCd = cardCvvCd;
    }

}
