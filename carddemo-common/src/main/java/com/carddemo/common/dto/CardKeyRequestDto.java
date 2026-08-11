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
 * :purpose: The composite card key the two card detail screens address a record with --
 *  ``CARDSID`` and the optional ``ACCTSID`` that completes it (``COCRDSLC``
 *  ``2200-EDIT-MAP-INPUTS``, ``COCRDUPC`` ``1200-EDIT-MAP-INPUTS``). It exists so the
 *  key travels in a request BODY rather than in a URL path segment or query string: the
 *  card number is a Primary Account Number, and a URL is recorded verbatim by every
 *  access log, proxy and trace along the request path, whereas a body is not. The
 *  service applies the same edits to it either way.
 * :output: A mutable carrier of the card number and the optional owning account id.
 */
public class CardKeyRequestDto {

    /** :purpose: the sixteen-digit card number (``CARD-NUM`` ``PIC X(16)``). */
    private String cardNumber;

    /**
     * :purpose: the account filter the screen collects alongside the card number
     *  (``ACCTSID``); optional, and when supplied it must be a non-zero eleven-digit
     *  value, exactly as the legacy edit required.
     */
    private String accountId;

    /**
     * :purpose: Read the card number this request addresses.
     * :returns: the card number as submitted, or ``null`` when absent.
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * :purpose: Set the card number this request addresses.
     * :param cardNumber: the card number as submitted.
     */
    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    /**
     * :purpose: Read the optional account filter completing the composite key.
     * :returns: the account id as submitted, or ``null`` when absent.
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * :purpose: Set the optional account filter completing the composite key.
     * :param accountId: the account id as submitted.
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }
}
