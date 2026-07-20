/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <em>View Credit Card Detail</em> screen.
 *
 * <p>This immutable carrier is the Java re-platform of the input half of the
 * 3270 screen driven by BMS map {@code COCRDSL} (mapset {@code CCRDSLA}) and
 * online program {@code COCRDSLC} (CICS transaction {@code CCDL}), whose Java
 * equivalent is {@code CardViewController}. Its two data components correspond
 * one-to-one with the unprotected ({@code UNPROT}) input fields of the BMS
 * symbolic map {@code CCRDSLAI} defined in {@code app/cpy-bms/COCRDSL.CPY} and
 * declared in {@code app/bms/COCRDSL.bms} (both relocated to {@code legacy/**}).
 * Field names, maximum lengths, and character types are preserved exactly to
 * maintain the field-level UI contract of the migrated screen (no terminal is
 * rendered; the screen is expressed as a REST request/response contract).</p>
 *
 * <p>The screen is a card-detail lookup: the operator keys an account number
 * and/or card number and transmits the search. {@code CardService} then resolves
 * the requested card by the account and card key. Only the two search-key fields
 * are unprotected on {@code COCRDSL.bms}; every other field on the map
 * ({@code CRDNAME}, {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR}, the header
 * and message lines, and the PF-key legend) is a display / output field and is
 * therefore modeled on {@code CardViewResponse}, not here. There is no card
 * verification value (CVV) on this screen.</p>
 *
 * <p>Both search keys are modeled as {@link String} to preserve the legacy
 * fixed-width character layout; there are no monetary or decimal fields on this
 * screen, so no {@link java.math.BigDecimal} (and never {@code double} /
 * {@code float}) is involved. The bean-validation constraints below reproduce
 * only the field-format edits implied by the legacy {@code PIC} clauses (maximum
 * length, and digits-only for the numeric account key); they add no new business
 * rule. Neither key is marked mandatory, mirroring the legacy screen where the
 * operator may key either search key.</p>
 *
 * <p>The {@code action} component carries the 3270 Attention Identifier (the key
 * the operator pressed) as the shared, transport-neutral {@link PfKeyAction}
 * enum &mdash; the Java translation of the {@code EIBAID} handling in the shared
 * copybook {@code app/cpy/CSSTRPFY.cpy}. For this screen the footer legend
 * {@code 'ENTER=Search Cards  F3=Exit'} defines the two meaningful keys:
 * {@link PfKeyAction#ENTER} requests the card search and {@link PfKeyAction#PF3}
 * exits back to the prior screen. It is optional (may be {@code null}); the
 * owning controller decides the behavior for a given key, so no routing logic
 * lives on this DTO.</p>
 *
 * @param accountId the 11-digit account-number search key; legacy input field
 *                  {@code ACCTSIDI}, {@code PIC X(11)}. Optional; when supplied it
 *                  must be at most 11 characters and contain digits only.
 * @param cardId    the 16-character card-number search key; legacy input field
 *                  {@code CARDSIDI}, {@code PIC X(16)}. Optional; when supplied it
 *                  must be at most 16 characters.
 * @param action    the attention key that submitted the screen ({@link PfKeyAction#ENTER}
 *                  to search cards, {@link PfKeyAction#PF3} to exit); optional and
 *                  may be {@code null}.
 */
public record CardViewRequest(

        @Size(max = 11)
        @Pattern(regexp = "^\\d{0,11}$")
        String accountId,

        @Size(max = 16)
        String cardId,

        PfKeyAction action) {
}
