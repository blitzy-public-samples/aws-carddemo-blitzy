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

import java.util.List;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <strong>Card List</strong> screen.
 *
 * <p>This immutable record is the Java re-expression of the <em>input</em>
 * symbolic group {@code CCRDLIAI} of BMS map {@code COCRDLI} (mapset
 * {@code CCRDLIA}), as driven by transaction {@code CCLI} / online program
 * {@code COCRDLIC} and surfaced by {@code CardListController}. The legacy
 * sources are {@code app/cpy-bms/COCRDLI.CPY} and {@code app/bms/COCRDLI.bms}
 * (relocated under {@code legacy/**}). It transports everything the operator can
 * supply on the card-browse screen: the two search filters, the per-row
 * selection flags, and the attention key (Enter / PF key) that was pressed.</p>
 *
 * <p><strong>Field-contract parity.</strong> Field names, lengths, and types are
 * preserved from the originating 3270 screen contract (behavioural-parity
 * requirement, AAP &sect;0.9.2). Only the three unprotected / operator-editable
 * inputs of {@code COCRDLI.bms} are modelled here &mdash; the account-number
 * filter ({@code ACCTSID}), the card-number filter ({@code CARDSID}), and the
 * seven per-row selection fields ({@code CRDSEL1}&hellip;{@code CRDSEL7}). The
 * protected display columns of the browse rows ({@code ACCTNOn}, {@code CRDNUMn},
 * {@code CRDSTSn}), the per-row card-stop attribute flags ({@code CRDSTPn}), the
 * header fields, the page indicator, and the information / error message lines
 * are output data and therefore belong to {@code CardListResponse}, not to this
 * request.</p>
 *
 * <p><strong>Browse semantics (AAP &sect;0.5.3, &sect;0.7.1 H5).</strong>
 * {@code COCRDLIC} lists the cards for an account via the
 * {@code CARDDATA.VSAM.AIX} alternate-index browse, paging with PF7 / PF8. In the
 * migrated application that VSAM browse becomes a sorted, paged Spring Data query
 * executed by the service layer ({@code CardService}). This DTO carries only the
 * requested {@link #action() action}, the {@link #accountId() filters}, and the
 * per-row {@link #rowSelections() selections}; it performs no paging arithmetic
 * and holds no business logic &mdash; the service translates the action into the
 * appropriate {@code org.springframework.data.domain.Pageable}.</p>
 *
 * <p><strong>Attention-key semantics.</strong> The screen footer of
 * {@code COCRDLI.bms} advertises {@code F3=Exit F7=Backward F8=Forward}. The
 * {@link #action() action} therefore carries one of the shared
 * {@link PfKeyAction} values, with the conventional Card-List meanings:</p>
 * <ul>
 *   <li>{@link PfKeyAction#ENTER} &mdash; apply the filters and, if a row
 *       selection flag is set, drill into the chosen card (view / update);</li>
 *   <li>{@link PfKeyAction#PF3} &mdash; exit back to the calling menu;</li>
 *   <li>{@link PfKeyAction#PF7} &mdash; page backward through the card list;</li>
 *   <li>{@link PfKeyAction#PF8} &mdash; page forward through the card list.</li>
 * </ul>
 * The concrete routing for a given key is decided by the owning controller and
 * service (the Java equivalents of the {@code COCRDLIC} paragraph logic); the
 * meanings above are documentation only.
 *
 * <p>No field on this screen is sensitive (there is no CVV, password, or other
 * secret in the card-list input contract), so the record's default
 * {@link Record#toString() toString()} is retained.</p>
 *
 * @param accountId     the account-number search filter; maps to
 *                      {@code ACCTSIDI} (COBOL {@code PIC X(11)}). Used to list
 *                      the cards belonging to a single account via the
 *                      {@code CARDDATA.VSAM.AIX} browse. When blank the browse is
 *                      not constrained by account. Constrained to at most eleven
 *                      numeric characters; may be {@code null} or empty when the
 *                      operator supplies no account filter.
 * @param cardId        the card-number search filter; maps to {@code CARDSIDI}
 *                      (COBOL {@code PIC X(16)}). When blank the browse is not
 *                      constrained by card number. Constrained to at most sixteen
 *                      characters; may be {@code null} or empty.
 * @param rowSelections the per-row selection flags; maps to
 *                      {@code CRDSEL1I}&hellip;{@code CRDSEL7I} (COBOL
 *                      {@code PIC X(1)} each), preserving screen order so that
 *                      index {@code 0} is row&nbsp;1 &hellip; index {@code 6} is
 *                      row&nbsp;7. A non-blank flag marks the row the operator
 *                      chose to drill into (view / update). Each element is at
 *                      most one character; the list itself may be {@code null} or
 *                      empty when no row is selected.
 * @param action        the attention identifier (Enter / PF key) the operator
 *                      transmitted, as the shared {@link PfKeyAction} translation
 *                      of {@code EIBAID} (COBOL copybook {@code CSSTRPFY.cpy}).
 *                      Optional; may be {@code null} when the action is implied by
 *                      the transport.
 */
public record CardListRequest(

        @Size(max = 11)
        @Pattern(regexp = "^\\d{0,11}$")
        String accountId,                            // ACCTSIDI  PIC X(11)

        @Size(max = 16)
        String cardId,                               // CARDSIDI  PIC X(16)

        List<@Size(max = 1) String> rowSelections,   // CRDSEL1I..CRDSEL7I  PIC X(1) each

        PfKeyAction action                           // EIBAID via CSSTRPFY.cpy

) {
}
