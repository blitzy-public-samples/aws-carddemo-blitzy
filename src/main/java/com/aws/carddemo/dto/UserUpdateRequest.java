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

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the CardDemo <em>Update User</em> screen.
 *
 * <p>This DTO is the Java re-platform of the {@code COUSR2AI} input symbolic map
 * consumed by the legacy CICS program {@code COUSR02C} (transaction {@code CU02})
 * through BMS mapset {@code COUSR02} / map {@code COUSR2A}. It is bound by
 * {@code UserUpdateController} and carries the user-id lookup key together with
 * the editable user fields the operator may change. It preserves the field-level
 * 3270 contract forward without rendering a terminal screen: the BMS attribute,
 * length, colour, highlight and modified-data-tag bytes are intentionally not
 * modelled because there is no terminal presentation layer in the migrated
 * application (see AAP &sect;0.3.3).</p>
 *
 * <h2>Fetch-then-update flow</h2>
 * <p>The legacy screen is used in two steps that map onto this single request
 * contract. First the operator keys the {@code userId} and presses ENTER to
 * <em>fetch</em> the existing record; the fetched first name, last name and role
 * are returned on {@link UserUpdateResponse} for editing. The operator then
 * amends the editable fields and presses a save key (F5, or F3 to save and exit)
 * to persist the change. The interpretation of each key is decided by the owning
 * controller/service (the Java equivalents of the {@code COUSR02C} paragraph
 * logic), not by this DTO. An empty {@code password} means &quot;leave the stored
 * password unchanged&quot; &mdash; that semantic is a rule-layer concern handled
 * in {@code service/rule/} and is deliberately not encoded here.</p>
 *
 * <h2>Sensitive-field handling</h2>
 * <p>The {@code password} component is a <strong>write-only</strong> credential
 * (AAP &sect;0.7.3, &sect;0.9.3). It is accepted on the inbound request but is
 * never serialized back to a caller: it is annotated
 * {@link JsonProperty @JsonProperty}{@code (access = }{@link
 * JsonProperty.Access#WRITE_ONLY WRITE_ONLY}{@code )} so Jackson excludes it from
 * every response body, and this record's {@link #toString()} is overridden to
 * mask it so the value can never leak into logs, traces or diagnostics. This
 * mirrors the legacy behaviour where the BMS {@code PASSWD} field carried the
 * {@code DRK} (non-display) attribute so the value was never shown on the 3270
 * screen. The companion {@link UserUpdateResponse} deliberately has no password
 * field at all.</p>
 *
 * <h2>Field contract and edit rules</h2>
 * <p>Each component preserves the field name, maximum input length and type of
 * its originating {@code COUSR2AI} field so the source-to-target mapping remains
 * fully traceable (see AAP &sect;0.9.2). Edit rules are expressed declaratively
 * with Jakarta Bean Validation constraints only; no business logic lives in this
 * type beyond the masking {@link #toString()}. All fields are modelled as
 * {@link String}; none is monetary, so no {@code BigDecimal} is involved and, per
 * project policy, {@code double}/{@code float} are never used.</p>
 *
 * <table class="striped">
 *   <caption>{@code COUSR2AI} input map field mapping</caption>
 *   <thead>
 *     <tr><th>Record component</th><th>COBOL field</th><th>PIC</th><th>Constraints</th><th>Meaning</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>{@code userId}</td><td>{@code USRIDINI}</td><td>X(8)</td><td>{@code @NotBlank}, {@code @Size(max = 8)}</td><td>User-id lookup key (fetch, then edit).</td></tr>
 *     <tr><td>{@code firstName}</td><td>{@code FNAMEI}</td><td>X(20)</td><td>{@code @Size(max = 20)}</td><td>Editable first name.</td></tr>
 *     <tr><td>{@code lastName}</td><td>{@code LNAMEI}</td><td>X(20)</td><td>{@code @Size(max = 20)}</td><td>Editable last name.</td></tr>
 *     <tr><td>{@code password}</td><td>{@code PASSWDI}</td><td>X(8)</td><td>{@code @Size(max = 8)}, write-only</td><td>New password; blank leaves it unchanged (rule layer).</td></tr>
 *     <tr><td>{@code userType}</td><td>{@code USRTYPEI}</td><td>X(1)</td><td>{@code @Size(max = 1)}, {@code @Pattern}</td><td>Role code: {@code A}=Admin, {@code U}=User.</td></tr>
 *     <tr><td>{@code action}</td><td>{@code EIBAID}</td><td>&mdash;</td><td>optional</td><td>Attention key pressed (ENTER=Fetch, F3=Save&amp;Exit, F4=Clear, F5=Save, F12=Cancel).</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>The {@code action} component reuses the shared {@link PfKeyAction} enum (the
 * single Java translation of {@code CSSTRPFY.cpy}) to carry the attention key the
 * operator pressed. It is optional; a {@code null} value is permitted and left to
 * the controller to interpret as the default (ENTER) action.</p>
 *
 * <p>Instances are immutable value objects; this type holds no business logic.</p>
 *
 * @param userId    user-id lookup key for the record to update
 *                  (from {@code USRIDINI}, {@code PIC X(8)}); must not be blank
 * @param firstName editable first name
 *                  (from {@code FNAMEI}, {@code PIC X(20)})
 * @param lastName  editable last name
 *                  (from {@code LNAMEI}, {@code PIC X(20)})
 * @param password  write-only new password; blank means &quot;unchanged&quot;
 *                  (from {@code PASSWDI}, {@code PIC X(8)}); never serialized back
 *                  and masked in {@link #toString()}
 * @param userType  role code, {@code A}=Admin or {@code U}=User
 *                  (from {@code USRTYPEI}, {@code PIC X(1)})
 * @param action    attention key the operator pressed, or {@code null} to default
 *                  to the ENTER action (from {@code EIBAID} via {@code CSSTRPFY.cpy})
 */
public record UserUpdateRequest(

        @NotBlank(message = "User ID is required")
        @Size(max = 8, message = "User ID must be at most 8 characters")
        String userId,

        @Size(max = 20, message = "First name must be at most 20 characters")
        String firstName,

        @Size(max = 20, message = "Last name must be at most 20 characters")
        String lastName,

        @Size(max = 8, message = "Password must be at most 8 characters")
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,

        @Size(max = 1, message = "User type must be a single character")
        @Pattern(regexp = "^[AUau ]?$", message = "User type must be 'A' (Admin) or 'U' (User)")
        String userType,

        PfKeyAction action) {

    /**
     * Placeholder rendered in {@link #toString()} in place of the write-only
     * {@code password} value so the credential can never leak into logs, traces
     * or diagnostics.
     */
    private static final String PASSWORD_MASK = "***";

    /**
     * Returns a diagnostic string representation of this request with the
     * write-only {@code password} masked.
     *
     * <p>This deliberately overrides the record's auto-generated
     * {@code toString()}, which would otherwise include the raw password value.
     * The password is rendered as {@value #PASSWORD_MASK} when present and as
     * {@code null} when absent, so the actual credential (and its length) is
     * never exposed while a caller can still see whether a password was supplied.
     * All other components are rendered verbatim; none of them is sensitive.</p>
     *
     * @return a string representation safe to log, with the password masked
     */
    @Override
    public String toString() {
        return "UserUpdateRequest["
                + "userId=" + userId
                + ", firstName=" + firstName
                + ", lastName=" + lastName
                + ", password=" + (password == null ? "null" : PASSWORD_MASK)
                + ", userType=" + userType
                + ", action=" + action
                + "]";
    }
}
