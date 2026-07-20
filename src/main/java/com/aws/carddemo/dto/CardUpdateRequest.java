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
package com.aws.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <em>Card Update</em> screen.
 *
 * <p>This immutable record is the Java re-expression of the operator-supplied
 * input carried by the BMS symbolic input map {@code CCRDUPAI} (BMS map
 * {@code COCRDUP}, mapset {@code CCRDUPA}), which drives the online CICS program
 * {@code COCRDUPC} (transaction {@code CCUP}) and is surfaced through
 * {@code CardUpdateController}. It bundles the record-key identifiers together
 * with the editable card attributes that the 3270 screen accepted from the
 * terminal operator.
 *
 * <p>Per the migration contract (AAP section 0.5.3 / section 0.7.1 hotspot H2)
 * every field preserves the <strong>name, maximum length, and type</strong> of
 * its originating COBOL field so that the field-level 3270 screen contract is
 * retained exactly. Each component maps one-to-one to an {@code -I} (input)
 * field of the {@code CCRDUPAI} group and its {@code PIC X(n)} clause defines the
 * documented maximum character length enforced here by {@link Size}. Values are
 * modeled as {@link String} because the 3270 map transmitted every field as
 * display characters; the format constraints expressed by {@link Pattern}
 * reproduce the legacy numeric/flag edits declaratively.
 *
 * <p>On the underlying map the account number ({@code ACCTSID}) is a
 * <em>protected</em> field that provides navigation context, while the card
 * number and the four card attributes ({@code CARDSID}, {@code CRDNAME},
 * {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR}) are <em>unprotected</em>
 * entry fields. The account number is nonetheless part of the update key
 * contract, so it is carried here as a display/context identifier.
 *
 * <p>Scope notes, in line with the migration boundaries:
 * <ul>
 *   <li>The BMS attribute-byte and length sub-fields (the {@code -L},
 *       {@code -F}/{@code -A} siblings of each field) are intentionally omitted:
 *       there is no terminal rendering in the Java target (AAP section 0.3.3),
 *       and the legacy RED-highlight-on-error visual defined by the edit-attribute
 *       template {@code CSSETATY.cpy} is therefore not reproduced.</li>
 *   <li>The display-only expiry-day field ({@code EXPDAY}, dark/protected on the
 *       map) is not an operator input and is excluded from this request.</li>
 *   <li>No card verification value (CVV) is present: it is neither part of this
 *       screen's input map nor accepted from the client.</li>
 * </ul>
 *
 * <p>This record carries no business logic and performs no cross-field
 * validation. The constraints declared on its components express only
 * presence/format edits (Bean Validation); the substantive edit paragraphs of
 * {@code COCRDUPC} and the READ-UPDATE-REWRITE cycle are reproduced by the
 * service and rule layers ({@code CardService.update(...)} and
 * {@code service/rule/}), not here.
 *
 * @param accountId   11-digit account number providing the update-key context;
 *                    protected on the screen. COBOL {@code ACCTSIDI},
 *                    {@code PIC X(11)}. Constrained to at most 11 characters and,
 *                    when present, to digits only ({@code ^\d{0,11}$}).
 * @param cardId      16-digit card number key (the unprotected entry field that
 *                    selects the card to update). COBOL {@code CARDSIDI},
 *                    {@code PIC X(16)}. Constrained to at most 16 characters.
 * @param cardName    embossed name on the card. COBOL {@code CRDNAMEI},
 *                    {@code PIC X(50)}. Constrained to at most 50 characters.
 * @param cardStatus  card active flag as shown by the "Card Active Y/N" prompt.
 *                    COBOL {@code CRDSTCDI}, {@code PIC X(1)}. Constrained
 *                    declaratively only to at most 1 character (the {@code PIC X(1)}
 *                    length). The {@code Y}/{@code N} value check is a service-level
 *                    same-screen edit (COBOL {@code 1240-EDIT-CARDSTATUS} &rarr;
 *                    "Card Active Status must be Y or N"), so an invalid one-character
 *                    value is echoed back on-screen (HTTP 200) rather than rejected at
 *                    the transport layer.
 * @param expiryMonth card expiry month. COBOL {@code EXPMONI}, {@code PIC X(2)}.
 *                    Constrained to at most 2 characters and, when present, to
 *                    digits only ({@code ^\d{0,2}$}).
 * @param expiryYear  card expiry year. COBOL {@code EXPYEARI}, {@code PIC X(4)}.
 *                    Constrained to at most 4 characters and, when present, to
 *                    digits only ({@code ^\d{0,4}$}).
 * @param action      the operator's attention key for this submission, the
 *                    transport-neutral equivalent of the legacy {@code EIBAID}
 *                    resolved by the shared copybook {@code CSSTRPFY.cpy}.
 *                    Optional; per the screen's function-key legend the
 *                    meaningful values are {@link PfKeyAction#ENTER} (Process),
 *                    {@link PfKeyAction#PF3} (Exit), {@link PfKeyAction#PF5}
 *                    (Save) and {@link PfKeyAction#PF12} (Cancel).
 */
public record CardUpdateRequest(

        @Size(max = 11)
        @Pattern(regexp = "^\\d{0,11}$")
        String accountId,

        @Size(max = 16)
        String cardId,

        @Size(max = 50)
        String cardName,

        // CRDSTCDI PIC X(1): only the single-character length is enforced here; the
        // Y/N value edit is a service-level same-screen edit (CardService,
        // 1240-EDIT-CARDSTATUS), so an invalid one-character status returns HTTP 200.
        @Size(max = 1)
        String cardStatus,

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String expiryMonth,

        @Size(max = 4)
        @Pattern(regexp = "^\\d{0,4}$")
        String expiryYear,

        PfKeyAction action) {
}
