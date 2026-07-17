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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <strong>Account View</strong> screen.
 *
 * <p>This type is the Java re-platform of the BMS input map {@code COACTVW}
 * ({@code CACTVWAI} symbolic input group in {@code app/cpy-bms/COACTVW.CPY};
 * screen defined in {@code app/bms/COACTVW.bms}; mapset {@code CACTVWA}) driven
 * by the online program {@code COACTVWC} (CICS transaction {@code CAVW}). It is
 * consumed by {@code AccountViewController} / {@code AccountService} and carries
 * the single value the terminal operator enters to inquire on an account. The
 * Account View screen is a read-only inquiry, so this request has no update
 * fields and adds no capability beyond the original COBOL scope.</p>
 *
 * <p><b>Field contract.</b> The only unprotected ({@code UNPROT}) input field on
 * the 3270 map is {@code ACCTSID}, which is declared numeric via
 * {@code PICIN='99999999999'} with {@code LENGTH=11} and {@code VALIDN=(MUSTFILL)}
 * (its symbolic input field {@code ACCTSIDI} is {@code PIC 9(11)}). It is
 * preserved here as {@link #accountId()} so that the field-level 3270 contract
 * &mdash; name, maximum length, and PIC-derived type &mdash; remains intact. The
 * value is modeled as a {@link String} rather than a numeric type so that the
 * fixed eleven-character width and any leading zeros are retained exactly as
 * keyed; the service layer parses it to the numeric account key. Floating-point
 * types ({@code double}/{@code float}) are never used for this identifier.</p>
 *
 * <p><b>Attention identifier.</b> The {@link #action()} component captures which
 * 3270 Attention Identifier (AID) the operator transmitted, modeled by the
 * shared {@link PfKeyAction} enum (the Java translation of {@code CSSTRPFY.cpy}).
 * On this screen {@link PfKeyAction#ENTER} requests that the entered account be
 * fetched and displayed, while {@link PfKeyAction#PF3} exits back to the prior
 * menu, per the screen footer {@code '  F3=Exit '}. The component is optional
 * (nullable): a null value represents the initial screen entry with no key yet
 * pressed. The screen-specific meaning of each key is decided by the owning
 * controller/service, mirroring the legacy paragraph logic; this DTO encodes no
 * routing behavior.</p>
 *
 * <p>This is an immutable value carrier with no business logic. It contains no
 * sensitive fields, so the record's default {@code toString()} is retained.
 * Validation is expressed with Jakarta Bean Validation constraints
 * ({@link NotBlank}, {@link Size}, {@link Pattern}) and enforced by the web
 * layer when the request is bound.</p>
 *
 * @param accountId the eleven-digit numeric account identifier the operator
 *                  entered ({@code ACCTSID} / {@code ACCTSIDI}, {@code PIC 9(11)},
 *                  {@code MUSTFILL}). Must be present and consist of one to
 *                  eleven decimal digits; retained as a {@link String} to
 *                  preserve fixed width and leading zeros.
 * @param action    the Attention Identifier (PF key / Enter) transmitted with
 *                  the request ({@code EIBAID} via {@code CSSTRPFY.cpy}); may be
 *                  {@code null} on initial entry. {@link PfKeyAction#ENTER}
 *                  fetches the account; {@link PfKeyAction#PF3} exits.
 */
public record AccountViewRequest(

        @NotBlank
        @Size(max = 11)
        @Pattern(regexp = "^\\d{1,11}$")
        String accountId,

        PfKeyAction action) {
}
