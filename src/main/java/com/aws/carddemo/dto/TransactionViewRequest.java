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

import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo Transaction View / Detail screen.
 *
 * <p>This type is the Java re-expression of the single unprotected input on the
 * 3270 BMS input map {@code COTRN1AI} defined in {@code COTRN01.CPY} and
 * rendered by BMS map {@code COTRN01} / map {@code COTRN1A} (see
 * {@code legacy/bms/COTRN01.bms}). It carries the lookup key a terminal operator
 * keyed on the legacy online program {@code COTRN01C} (CICS transaction
 * {@code CT01}), and is accepted by the modernized {@code TransactionViewController}
 * before {@code TransactionService.view(...)} fetches the single transaction by
 * id.
 *
 * <p><strong>Field-level contract.</strong> Of all the fields on the
 * {@code COTRN01} map, only {@code TRNIDIN} is defined {@code UNPROT} (operator
 * input); every other field is {@code ASKIP} (protected, display-only) and is
 * therefore modeled on the paired {@link TransactionViewResponse} rather than
 * here. Consequently this request preserves exactly one screen field &mdash; the
 * transaction-id key &mdash; keeping the name, maximum length and type of its
 * legacy counterpart to maintain the field-level contract required for
 * behavioral parity. The character field ({@code PIC X(16)}) maps to
 * {@link String} constrained to a maximum length of 16.
 *
 * <p><strong>Action / PF-key contract.</strong> The {@code action} component is
 * the transport-neutral translation of the 3270 Attention Identifier
 * ({@code EIBAID}) whose canonical Java form is {@link PfKeyAction} (the shared
 * translation of {@code CSSTRPFY.cpy}). Per the on-screen footer
 * &mdash; {@code "ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran."}
 * (see {@code legacy/bms/COTRN01.bms}) &mdash; the keys this screen recognizes
 * are {@link PfKeyAction#ENTER} (fetch the transaction),
 * {@link PfKeyAction#PF3} (back to the caller), {@link PfKeyAction#PF4} (clear
 * the form) and {@link PfKeyAction#PF5} (browse the transaction list). The
 * component is optional: when absent the owning controller applies its
 * screen-entry default exactly as the legacy program did on first entry. The
 * concrete routing for each key is decided by the controller and service, not
 * by this DTO; this type holds no business logic.
 *
 * <p>Implemented as an immutable {@link Record}; the compiler-generated
 * {@code toString()} is safe to use because this request contains no sensitive
 * data (no card verification value and no password).
 *
 * @param transactionId the 16-character transaction identifier used as the
 *                       lookup key; corresponds to the sole unprotected map
 *                       field {@code TRNIDIN} / symbolic input {@code TRNIDINI}
 *                       ({@code PIC X(16)}). Optional at binding time; validated
 *                       to a maximum length of 16.
 * @param action        the operator's attention key for this submission,
 *                       translated from {@code EIBAID} via {@code CSSTRPFY.cpy};
 *                       recognized values are {@link PfKeyAction#ENTER},
 *                       {@link PfKeyAction#PF3}, {@link PfKeyAction#PF4} and
 *                       {@link PfKeyAction#PF5}. Optional; {@code null} denotes
 *                       screen entry.
 */
public record TransactionViewRequest(
        @Size(max = 16) String transactionId,
        PfKeyAction action) {
}
