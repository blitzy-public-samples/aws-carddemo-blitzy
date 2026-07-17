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

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable request payload for the CardDemo <em>Add User</em> screen.
 *
 * <p>This record re-expresses the operator-entry (unprotected) fields of the
 * input symbolic map group {@code COUSR1AI} of BMS map {@code COUSR01} (mapset
 * {@code COUSR1A}) &mdash; driven by the legacy online program {@code COUSR01C}
 * (CICS transaction {@code CU01}) &mdash; as an idiomatic REST request DTO
 * consumed by {@code UserAddController} and processed by
 * {@code UserService.add(...)}. Only the five keyable fields of the 3270 screen
 * are carried here; the header/title/date/time/message fields of the map are
 * output-only and belong to {@link UserAddResponse}.</p>
 *
 * <p>Field-level traceability to the legacy symbolic map (see
 * {@code legacy/cpy-bms/COUSR01.CPY} and {@code legacy/bms/COUSR01.bms}). Every
 * component preserves the field name, maximum length and type of its COBOL
 * counterpart, honoring the 3270 field contract required for behavioral parity.
 * The {@code UNPROT} fields on the map are:</p>
 *
 * <ul>
 *   <li>{@code firstName} &rarr; {@code FNAMEI}, PIC {@code X(20)}</li>
 *   <li>{@code lastName} &rarr; {@code LNAMEI}, PIC {@code X(20)}</li>
 *   <li>{@code userId} &rarr; {@code USERIDI}, PIC {@code X(8)}</li>
 *   <li>{@code password} &rarr; {@code PASSWDI}, PIC {@code X(8)}</li>
 *   <li>{@code userType} &rarr; {@code USRTYPEI}, PIC {@code X(1)}</li>
 * </ul>
 *
 * <p>The edit rules are expressed here purely through Jakarta Bean Validation
 * constraints; the corresponding COBOL edit paragraphs (and the RED-highlight
 * attribute handling from {@code legacy/cpy/CSSETATY.cpy}) are reproduced in the
 * service/rule layer, not in this transport object.</p>
 *
 * <p><strong>Sensitive-field handling.</strong> The {@code password} component
 * corresponds to the map's {@code PASSWD} field, which is defined with the
 * {@code DRK} (non-display) attribute so the value never appears on the 3270
 * screen. To preserve that discretion end-to-end, {@code password} is:</p>
 * <ul>
 *   <li>annotated {@link JsonProperty.Access#WRITE_ONLY WRITE_ONLY}, so it is
 *       accepted when the request is deserialized but is never emitted when any
 *       DTO in this flow is serialized back to a client; and</li>
 *   <li>masked by the overridden {@link #toString()} (rendered as
 *       {@code password=***}) so the raw value can never leak into application
 *       logs.</li>
 * </ul>
 * <p>The legacy system stored passwords in plaintext; hashing before
 * persistence is a documented security improvement applied in the security/
 * service layer and is intentionally out of scope for this input contract.</p>
 *
 * <p>The optional {@link #action} carries the terminal attention key that
 * triggered the submit, translated from {@code EIBAID} via the shared
 * {@link PfKeyAction} type (legacy copybook {@code CSSTRPFY.cpy}). Per the
 * screen footer &mdash; {@code ENTER=Add User  F3=Back  F4=Clear  F12=Exit}
 * &mdash; the meaningful keys for this screen are {@link PfKeyAction#ENTER}
 * (add the user), {@link PfKeyAction#PF3} (back), {@link PfKeyAction#PF4}
 * (clear the form) and {@link PfKeyAction#PF12} (exit); the controller decides
 * the behavior for the received key.</p>
 *
 * @param firstName the new user's first name; maps to {@code FNAMEI}
 *                  (PIC {@code X(20)}). Required, at most 20 characters.
 * @param lastName  the new user's last name; maps to {@code LNAMEI}
 *                  (PIC {@code X(20)}). Required, at most 20 characters.
 * @param userId    the new user's identifier (the record key); maps to
 *                  {@code USERIDI} (PIC {@code X(8)}). Required, at most 8
 *                  characters.
 * @param password  the new user's password; maps to {@code PASSWDI}
 *                  (PIC {@code X(8)}). Required, at most 8 characters.
 *                  <strong>Write-only:</strong> never serialized back to a
 *                  client and never logged (masked in {@link #toString()}).
 * @param userType  the new user's role; maps to {@code USRTYPEI}
 *                  (PIC {@code X(1)}). Required, exactly one of
 *                  {@code A} (Admin) or {@code U} (User), case-insensitive,
 *                  matching the {@code COCOM01Y} 88-level role condition names.
 * @param action    the terminal attention key that submitted the screen,
 *                  translated from {@code EIBAID}; optional.
 */
public record UserAddRequest(

        @NotBlank
        @Size(max = 20)
        String firstName,

        @NotBlank
        @Size(max = 20)
        String lastName,

        @NotBlank
        @Size(max = 8)
        String userId,

        @NotBlank
        @Size(max = 8)
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,

        @NotBlank
        @Size(max = 1)
        @Pattern(regexp = "^[AUau]$")
        String userType,

        PfKeyAction action) {

    /**
     * The masking token rendered by {@link #toString()} in place of the raw
     * {@code password} value, ensuring the credential never reaches a log sink.
     */
    private static final String MASKED_PASSWORD = "***";

    /**
     * Returns a diagnostic string representation of this request in the standard
     * record layout, but with the {@code password} component replaced by
     * {@value #MASKED_PASSWORD} so the credential is never exposed in logs or
     * diagnostics.
     *
     * <p>All other components are rendered verbatim; only the sensitive password
     * is masked. This override is the logging counterpart to the
     * {@link JsonProperty.Access#WRITE_ONLY WRITE_ONLY} serialization guard on
     * the {@code password} field.</p>
     *
     * @return a masked, human-readable representation safe to write to logs
     */
    @Override
    public String toString() {
        return "UserAddRequest["
                + "firstName=" + firstName
                + ", lastName=" + lastName
                + ", userId=" + userId
                + ", password=" + MASKED_PASSWORD
                + ", userType=" + userType
                + ", action=" + action
                + ']';
    }
}
