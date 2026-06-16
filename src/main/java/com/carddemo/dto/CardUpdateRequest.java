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
package com.carddemo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Immutable JSON request body for the card-update operation (HTTP {@code PUT} on
 * the card resource, keyed in the URL path by the card identifier), handled by
 * {@code CardController} and delegated to {@code CardService}.
 *
 * <p>This DTO is the Spring Boot re-expression of the editable portion of the
 * legacy CICS card-maintenance screen. It carries <strong>only</strong> the
 * three fields that the original online program {@code COCRDUPC} permits an
 * operator to change on the BMS map {@code COCRDUP}:</p>
 * <ul>
 *   <li>{@code embossedName}   &larr; {@code CRDNAME} / {@code CARD-EMBOSSED-NAME PIC X(50)}</li>
 *   <li>{@code activeStatus}   &larr; {@code CRDSTCD}  / {@code CARD-ACTIVE-STATUS PIC X(01)}</li>
 *   <li>{@code expirationDate} &larr; {@code EXPMON}/{@code EXPYEAR}/{@code EXPDAY}
 *       composed from {@code CARD-EXPIRAION-DATE PIC X(10)}</li>
 * </ul>
 *
 * <h2>Immutability &amp; sensitive-data suppression</h2>
 * <p>Three fields of the card record ({@code CVACT02Y}) are deliberately
 * <strong>absent</strong> from this body:</p>
 * <ul>
 *   <li>{@code CARD-NUM PIC X(16)} &mdash; the card identifier is the resource
 *       key and is supplied exclusively through the URL path; it is immutable
 *       after creation.</li>
 *   <li>{@code CARD-ACCT-ID PIC 9(11)} &mdash; the owning-account binding is
 *       immutable after creation.</li>
 *   <li>the three-digit card verification value &mdash; persisted on the card
 *       record but never serialized into, nor accepted from, any API
 *       contract.</li>
 * </ul>
 * <p>Card-identifier and account-binding immutability is therefore enforced
 * structurally &mdash; by omission &mdash; exactly mirroring {@code COCRDUPC},
 * which never permits those keys to change (see AAP &sect;0.6.8).</p>
 *
 * <h2>Validation semantics</h2>
 * <p>The Bean Validation ({@code jakarta.validation}) constraints below are
 * evaluated when the controller binds the body with
 * {@code @Valid @RequestBody}; a violation yields an HTTP 400 response. Both
 * constraints intentionally treat {@code null} as valid: a card update is a
 * partial mutation in which an omitted field signals "leave unchanged", and the
 * service applies only the fields that are present &mdash; consistent with the
 * field-by-field change detection performed by {@code COCRDUPC}.</p>
 *
 * <p>This is a Tier-0 data-transfer record: it has no dependency on any other
 * application type and contains no business logic. As a Java {@code record} it
 * is shallowly immutable and exposes canonical component accessors
 * ({@link #embossedName()}, {@link #activeStatus()}, {@link #expirationDate()})
 * consumed by the service layer.</p>
 *
 * @param embossedName   the name embossed on the card; maps to
 *                       {@code CARD-EMBOSSED-NAME PIC X(50)}. May be {@code null}
 *                       to leave the existing value unchanged; when present it
 *                       must not exceed 50 characters.
 * @param activeStatus   the card active-status flag; maps to
 *                       {@code CARD-ACTIVE-STATUS PIC X(01)}. May be {@code null}
 *                       to leave the existing value unchanged; when present it
 *                       must be exactly {@code "Y"} (active) or {@code "N"}
 *                       (inactive), mirroring the {@code FLG-YES-NO-VALID}
 *                       check in {@code COCRDUPC}.
 * @param expirationDate the card expiration date; maps to
 *                       {@code CARD-EXPIRAION-DATE PIC X(10)}. May be
 *                       {@code null} to leave the existing value unchanged.
 *
 * @see <a href="http://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a>
 */
public record CardUpdateRequest(

        @Size(max = 50, message = "Embossed name must be at most 50 characters")
        String embossedName,        // CARD-EMBOSSED-NAME X(50)

        @Pattern(regexp = "[YN]", message = "Active status must be 'Y' or 'N'")
        String activeStatus,        // CARD-ACTIVE-STATUS X(01)

        LocalDate expirationDate    // CARD-EXPIRAION-DATE X(10)
) {
}
