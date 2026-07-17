/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for the CardDemo <strong>Transaction List</strong> screen.
 *
 * <p>This record is the Java re-expression of the input side of the 3270 screen
 * driven by CICS transaction {@code CT00}, BMS map {@code COTRN00} (mapset
 * {@code COTRN0A}), and COBOL program {@code COTRN00C} (migrated to
 * {@code TransactionListController} /
 * {@link com.aws.carddemo.service}). It corresponds to the operator-modifiable
 * ({@code UNPROT}) fields of the symbolic input group {@code COTRN0AI} defined in
 * copybook {@code app/cpy-bms/COTRN00.CPY} and map {@code app/bms/COTRN00.bms}
 * (both relocated under {@code legacy/}).</p>
 *
 * <p>The screen lists a single page of up to ten transaction summary rows. The
 * operator may type a transaction id into the search field to reposition the
 * browse, and/or type a one-character marker (conventionally {@code 'S'}) next to
 * a row to select it for viewing. Only the input (request-side) fields are
 * carried here; the protected header, per-row detail, page indicator, and
 * error-message fields belong to {@link TransactionListResponse}.</p>
 *
 * <p>Field-to-COBOL mapping (symbolic map {@code COTRN0AI} / BMS
 * {@code UNPROT} fields):</p>
 * <table border="1">
 *   <caption>COTRN0AI input field mapping</caption>
 *   <tr><th>DTO component</th><th>COBOL field</th><th>PIC</th><th>BMS field</th></tr>
 *   <tr><td>{@code transactionId}</td><td>{@code TRNIDINI}</td><td>X(16)</td>
 *       <td>{@code TRNIDIN}</td></tr>
 *   <tr><td>{@code rowSelections}</td>
 *       <td>{@code SEL0001I}..{@code SEL0010I}</td><td>X(1) &times;10</td>
 *       <td>{@code SEL0001}..{@code SEL0010}</td></tr>
 *   <tr><td>{@code action}</td><td>{@code EIBAID} (via {@code CSSTRPFY.cpy})</td>
 *       <td>&mdash;</td><td>AID / PF key</td></tr>
 * </table>
 *
 * <p><strong>Search key.</strong> {@code transactionId} preserves the 16-character
 * {@code TRNIDINI} search/start key. In the legacy program it seeds the VSAM
 * {@code STARTBR}; in the migrated design it drives the sorted, paged repository
 * query executed by the transaction service (the Java equivalent of the
 * {@code STARTBR}/{@code READNEXT} browse). It is optional: a blank value starts
 * the browse from the first transaction.</p>
 *
 * <p><strong>Row selections.</strong> {@code rowSelections} preserves the ten
 * ordered, one-character selection markers {@code SEL0001I}..{@code SEL0010I},
 * one per displayed row. Each element is a single-character marker (a non-blank
 * marker &mdash; conventionally {@code 'S'} per the map footer
 * <em>Type 'S' to View Transaction details from the list</em> &mdash; routes the
 * corresponding row to the transaction-view screen). The list is capped at the
 * ten fixed row slots of the {@code COTRN00} map; slot order is significant and
 * is preserved as list order.</p>
 *
 * <p><strong>Action.</strong> {@code action} carries the Attention Identifier the
 * operator transmitted, translated from the raw {@code EIBAID} by the shared
 * {@code CSSTRPFY.cpy} contract into a {@link PfKeyAction}. The
 * {@code COTRN00} map footer defines the valid keys for this screen:
 * {@link PfKeyAction#ENTER ENTER} = Continue (submit the search / apply a row
 * selection), {@link PfKeyAction#PF3 PF3} = Back (return to the prior menu),
 * {@link PfKeyAction#PF7 PF7} = Backward (previous page), and
 * {@link PfKeyAction#PF8 PF8} = Forward (next page). It is optional; the owning
 * controller decides the concrete routing for the transmitted key.</p>
 *
 * <p>This record carries no business logic. It is a transport-neutral input
 * carrier: the field names, lengths, and types mirror the BMS contract so the
 * REST request stays faithful to the original terminal screen. Bean Validation
 * ({@code jakarta.validation}) constraints bound the field sizes to the legacy
 * {@code PIC} lengths; the actual paging and selection behavior is reproduced by
 * the service/controller layer, not here. No field is sensitive, so the default
 * record {@code toString()} is safe.</p>
 *
 * @param transactionId the 16-character transaction-id search/start key
 *                      ({@code TRNIDINI}, X(16)); may be {@code null} or blank to
 *                      start the browse from the beginning
 * @param rowSelections the ten ordered per-row selection markers
 *                      ({@code SEL0001I}..{@code SEL0010I}, X(1) each); each
 *                      element is a single-character marker and slot order is
 *                      preserved; may be {@code null} when no row is selected
 * @param action        the transmitted Attention Identifier / PF key for this
 *                      screen ({@link PfKeyAction#ENTER}, {@link PfKeyAction#PF3},
 *                      {@link PfKeyAction#PF7}, {@link PfKeyAction#PF8}); optional
 */
public record TransactionListRequest(
        @Size(max = 16) String transactionId,
        @Size(max = 10) List<@Size(max = 1) String> rowSelections,
        PfKeyAction action) {
}
