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
 * :purpose: Inbound DTO for the card update screen (COCRDUPC, CICS CCUP). Carries the editable card fields (the new values), plus the optional display-time snapshot of those fields (the legacy ``CCUP-OLD-*`` values) used to detect a concurrent modification. The card number primary key and owning account id are identifiers and are never carried here.
 * :output: A mutable carrier of the editable embossed name, active status, expiry date, and CVV, together with the optional display-time snapshot (old embossed name, active status, expiry date, and CVV) of the same fields.
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
     * :purpose: the display-time snapshot of the embossed name (``CCUP-OLD-CRDNAME``); the
     *  value the client last read for this field, used by the read-snapshot-compare-rewrite
     *  concurrency check (``COCRDUPC 9300-CHECK-CHANGE-IN-REC``). Optional; ``null`` when the
     *  caller supplies no snapshot.
     */
    private String oldCardEmbossedName;

    /**
     * :purpose: the display-time snapshot of the active status (``CCUP-OLD-CRDSTCD``). Optional;
     *  ``null`` when the caller supplies no snapshot.
     */
    private String oldCardActiveStatus;

    /**
     * :purpose: the display-time snapshot of the expiration date (legacy-spelled
     *  ``CCUP-OLD-EXPIRAION-DATE``, YYYY-MM-DD; year/month/day compared per ``9300``). Optional;
     *  ``null`` when the caller supplies no snapshot.
     */
    private String oldCardExpiraionDate;

    /**
     * :purpose: the display-time snapshot of the CVV code (``CCUP-OLD-CVV-CD``); write-only,
     *  never returned. Optional; ``null`` when the caller supplies no snapshot.
     */
    private String oldCardCvvCd;

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

    /**
     * :purpose: Return the display-time snapshot of the embossed name (``CCUP-OLD-CRDNAME``).
     * :output: the ``oldCardEmbossedName`` value, or ``null`` when no snapshot was supplied.
     */
    public String getOldCardEmbossedName() {
        return oldCardEmbossedName;
    }

    /**
     * :purpose: Set the display-time snapshot of the embossed name (``CCUP-OLD-CRDNAME``).
     * :param oldCardEmbossedName: the ``oldCardEmbossedName`` value.
     */
    public void setOldCardEmbossedName(String oldCardEmbossedName) {
        this.oldCardEmbossedName = oldCardEmbossedName;
    }

    /**
     * :purpose: Return the display-time snapshot of the active status (``CCUP-OLD-CRDSTCD``).
     * :output: the ``oldCardActiveStatus`` value, or ``null`` when no snapshot was supplied.
     */
    public String getOldCardActiveStatus() {
        return oldCardActiveStatus;
    }

    /**
     * :purpose: Set the display-time snapshot of the active status (``CCUP-OLD-CRDSTCD``).
     * :param oldCardActiveStatus: the ``oldCardActiveStatus`` value.
     */
    public void setOldCardActiveStatus(String oldCardActiveStatus) {
        this.oldCardActiveStatus = oldCardActiveStatus;
    }

    /**
     * :purpose: Return the display-time snapshot of the expiration date (legacy-spelled
     *  ``CCUP-OLD-EXPIRAION-DATE``, YYYY-MM-DD).
     * :output: the ``oldCardExpiraionDate`` value, or ``null`` when no snapshot was supplied.
     */
    public String getOldCardExpiraionDate() {
        return oldCardExpiraionDate;
    }

    /**
     * :purpose: Set the display-time snapshot of the expiration date (legacy-spelled
     *  ``CCUP-OLD-EXPIRAION-DATE``, YYYY-MM-DD).
     * :param oldCardExpiraionDate: the ``oldCardExpiraionDate`` value.
     */
    public void setOldCardExpiraionDate(String oldCardExpiraionDate) {
        this.oldCardExpiraionDate = oldCardExpiraionDate;
    }

    /**
     * :purpose: Return the display-time snapshot of the CVV code (``CCUP-OLD-CVV-CD``);
     *  write-only, never returned.
     * :output: the ``oldCardCvvCd`` value, or ``null`` when no snapshot was supplied.
     */
    public String getOldCardCvvCd() {
        return oldCardCvvCd;
    }

    /**
     * :purpose: Set the display-time snapshot of the CVV code (``CCUP-OLD-CVV-CD``);
     *  write-only, never returned.
     * :param oldCardCvvCd: the ``oldCardCvvCd`` value.
     */
    public void setOldCardCvvCd(String oldCardCvvCd) {
        this.oldCardCvvCd = oldCardCvvCd;
    }

}
