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

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * :purpose: Inbound DTO for the card update screen (COCRDUPC, CICS CCUP). Carries the editable card fields (the new values), plus the optional display-time snapshot of those fields (the legacy ``CCUP-OLD-*`` values) used to detect a concurrent modification. The card number and owning account id are carried as identifiers only -- in the body rather than the URL, so a Primary Account Number is not written verbatim into access, proxy and trace logs -- and are never rewritten.
 * :output: A mutable carrier of the addressed card number and optional account id, the editable embossed name, active status, expiry date, and CVV, together with the optional display-time snapshot (old embossed name, active status, expiry date, and CVV) of the same fields.
 */
public class CardUpdateRequestDto {

    /** :purpose: ``CARD-CVV-CD PIC 9(03)`` -- exactly three decimal digits. */
    private static final String CVV_PATTERN = "\\d{3}";

    /** :purpose: Message reported when a supplied CVV does not fit ``PIC 9(03)``. */
    private static final String MSG_CVV_THREE_DIGITS = "Card CVV must be exactly 3 digits";

    /** :purpose: Declared width of ``CARD-EMBOSSED-NAME PIC X(50)`` / ``VARCHAR(50)``. */
    private static final int EMBOSSED_NAME_WIDTH = 50;

    /** :purpose: Declared width of ``CARD-EXPIRAION-DATE PIC X(10)`` / ``VARCHAR(10)``. */
    private static final int EXPIRY_DATE_WIDTH = 10;

    /** :purpose: Declared width of ``CARD-ACTIVE-STATUS PIC X(01)`` / ``VARCHAR(1)``. */
    private static final int ACTIVE_STATUS_WIDTH = 1;

    /** :purpose: Message reported when the embossed name exceeds its declared width. */
    private static final String MSG_NAME_WIDTH =
            "Card Embossed Name must be at most 50 characters";

    /** :purpose: Message reported when the expiration date exceeds its declared width. */
    private static final String MSG_EXPIRY_WIDTH =
            "Card Expiry Date must be at most 10 characters";

    /** :purpose: Message reported when the active status exceeds its declared width. */
    private static final String MSG_STATUS_WIDTH =
            "Card Active Status must be at most 1 character";

    /**
     * :purpose: the sixteen-digit card number addressed by this update (``CARD-NUM``
     *  ``PIC X(16)``). It is carried in the body rather than in the URL because a card
     *  number is a Primary Account Number and every access log, proxy log and
     *  distributed trace records a URL verbatim, which would persist the PAN in
     *  plaintext right across the infrastructure. It is an identifier, not an editable
     *  field: the service uses it to read the record and never writes it.
     */
    private String cardNumber;

    /**
     * :purpose: the optional ``ACCTSID`` completing the composite selection the legacy
     *  screen collected; when supplied it must be a non-zero eleven-digit value.
     */
    private String accountId;

    /**
     * :purpose: the embossed name (``CARD-EMBOSSED-NAME`` ``PIC X(50)``).
     * :note: Bounded at its declared width so an over-length value is reported as a field error.
     *  Unbounded, the value reached the ``VARCHAR(50)`` column, and the constraint violation the
     *  database raised surfaced to the caller as a ``500`` data-access error -- a client mistake
     *  reported as a server fault, on an unguarded write path.
     */
    @Size(max = EMBOSSED_NAME_WIDTH, message = MSG_NAME_WIDTH)
    private String cardEmbossedName;

    /** :purpose: the card active status (``CARD-ACTIVE-STATUS`` ``PIC X(01)``). */
    @Size(max = ACTIVE_STATUS_WIDTH, message = MSG_STATUS_WIDTH)
    private String cardActiveStatus;

    /** :purpose: the card expiration date (legacy-spelled ``CARD-EXPIRAION-DATE``, YYYY-MM-DD). */
    @Size(max = EXPIRY_DATE_WIDTH, message = MSG_EXPIRY_WIDTH)
    private String cardExpiraionDate;

    /**
     * :purpose: the card CVV code (``CARD-CVV-CD`` ``PIC 9(03)``); write-only, never returned.
     *  An ABSENT value leaves the stored verification value untouched -- no read path returns
     *  it, so an ordinary name/status/expiry change legitimately omits it. A SUPPLIED value
     *  must be exactly three digits, exactly as the legacy field could hold nothing else.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Pattern(regexp = CVV_PATTERN, message = MSG_CVV_THREE_DIGITS)
    private String cardCvvCd;

    /**
     * :purpose: the optimistic-lock version the client read for this card
     *  (``@Version``; no legacy field -- the 3270 flow held a VSAM update lock instead).
     *  Optional: when supplied it is compared against the stored version before anything is
     *  rewritten, which is what lets a stateless caller detect the concurrent modification
     *  ``COCRDUPC 9300-CHECK-CHANGE-IN-REC`` detected under its lock.
     */
    private Long version;

    /**
     * :purpose: the display-time snapshot of the embossed name (``CCUP-OLD-CRDNAME``); the
     *  value the client last read for this field, used by the read-snapshot-compare-rewrite
     *  concurrency check (``COCRDUPC 9300-CHECK-CHANGE-IN-REC``). Optional; ``null`` when the
     *  caller supplies no snapshot.
     */
    @Size(max = EMBOSSED_NAME_WIDTH, message = MSG_NAME_WIDTH)
    private String oldCardEmbossedName;

    /**
     * :purpose: the display-time snapshot of the active status (``CCUP-OLD-CRDSTCD``). Optional;
     *  ``null`` when the caller supplies no snapshot.
     */
    @Size(max = ACTIVE_STATUS_WIDTH, message = MSG_STATUS_WIDTH)
    private String oldCardActiveStatus;

    /**
     * :purpose: the display-time snapshot of the expiration date (legacy-spelled
     *  ``CCUP-OLD-EXPIRAION-DATE``, YYYY-MM-DD; year/month/day compared per ``9300``). Optional;
     *  ``null`` when the caller supplies no snapshot.
     */
    @Size(max = EXPIRY_DATE_WIDTH, message = MSG_EXPIRY_WIDTH)
    private String oldCardExpiraionDate;

    /**
     * :purpose: the display-time snapshot of the CVV code (``CCUP-OLD-CVV-CD``); write-only,
     *  never returned. Optional; ``null`` when the caller supplies no snapshot -- which is the
     *  normal case, because no read path returns the CVV.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Pattern(regexp = CVV_PATTERN, message = MSG_CVV_THREE_DIGITS)
    private String oldCardCvvCd;

    /**
     * :purpose: Create an empty CardUpdateRequestDto. Required for JSON (Jackson) serialization.
     */
    public CardUpdateRequestDto() {
    }

    /**
     * :purpose: Return the sixteen-digit card number addressed by this update.
     * :output: the ``cardNumber`` value.
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * :purpose: Set the sixteen-digit card number addressed by this update.
     * :param cardNumber: the card number as submitted.
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * :purpose: Return the optional account filter completing the composite selection.
     * :output: the ``accountId`` value.
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * :purpose: Set the optional account filter completing the composite selection.
     * :param accountId: the account id as submitted.
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
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
     * :purpose: Return the optimistic-lock version the client read for this card.
     * :output: the ``version`` value, or ``null`` when the caller supplied none.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * :purpose: Set the optimistic-lock version the client read for this card.
     * :param version: the ``version`` value.
     */
    public void setVersion(Long version) {
        this.version = version;
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
