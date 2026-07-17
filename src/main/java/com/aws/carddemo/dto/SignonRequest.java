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
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo Sign-on screen.
 *
 * <p>This immutable data holder is the REST-request counterpart of the 3270
 * sign-on map <strong>COSGN00</strong> (mapset {@code COSGN0A}, online program
 * {@code COSGN00C}, transaction {@code CC00}), accepted by
 * {@code SignonController}. It carries the user-editable input fields that the
 * terminal operator typed into the unprotected ({@code UNPROT}) fields of the
 * symbolic input group <strong>{@code COSGN0AI}</strong> before the legacy
 * program issued {@code EXEC CICS RECEIVE MAP}. Migrating the mainframe UI to
 * REST preserves the field-level 3270 contract (field names, lengths, and
 * PIC-derived types) rather than emulating a terminal, per the migration
 * plan.</p>
 *
 * <p>Only the two unprotected input fields of {@code COSGN00.bms} are modeled
 * here &mdash; {@code USERID} and {@code PASSWD} &mdash; plus an explicit
 * {@code action} field that captures the Attention Identifier (the key the
 * operator pressed) so the controller can reproduce the legacy PF-key routing.
 * The remaining {@code COSGN0AI} entries (transaction name, titles, date, time,
 * program name, APPLID, SYSID, and the error-message line) are display-only
 * output fields and are intentionally absent from this inbound contract. The
 * screen footer defines the valid keys for sign-on as
 * {@code 'ENTER=Sign-on  F3=Exit'}; the credential validation of {@code userId}
 * and {@code password} against the user-security store is performed downstream
 * by {@code SignonService}, not here.</p>
 *
 * <p><strong>Security:</strong> {@code password} is a sensitive, write-only
 * credential. It is annotated
 * {@link JsonProperty.Access#WRITE_ONLY WRITE_ONLY} so it is accepted when a
 * request is deserialized but is never serialized back to any client, and it is
 * deliberately excluded from {@link #toString()} (rendered as {@code ***}) so it
 * can never leak into logs or diagnostics. No logging is performed inside this
 * DTO.</p>
 *
 * <p>This type is a plain, immutable data carrier: it holds no business logic
 * and performs no validation beyond the Bean Validation constraints declared on
 * its components, which reproduce the legacy 3270 field lengths.</p>
 *
 * @param userId   the user identifier entered on the screen; maps to
 *                 {@code USERIDI} ({@code PIC X(8)}), the cursor-insert
 *                 ({@code IC}), unprotected field labeled {@code '(8 Char)'}.
 *                 Required and limited to a maximum of 8 characters.
 * @param password the password entered on the screen; maps to {@code PASSWDI}
 *                 ({@code PIC X(8)}), the masked ({@code DRK}), unprotected
 *                 field. Required and limited to a maximum of 8 characters.
 *                 <strong>Sensitive:</strong> write-only on the wire and masked
 *                 in {@link #toString()}.
 * @param action   the Attention Identifier the operator transmitted; maps to
 *                 {@code EIBAID} as translated by {@code CSSTRPFY.cpy}. Optional
 *                 (a {@code null} value is treated as the default submit path by
 *                 the controller). For this screen the meaningful values are
 *                 {@link PfKeyAction#ENTER} (Sign-on) and {@link PfKeyAction#PF3}
 *                 (Exit).
 */
public record SignonRequest(

        @NotBlank
        @Size(max = 8)
        String userId,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @NotBlank
        @Size(max = 8)
        String password,

        PfKeyAction action) {

    /**
     * Returns a diagnostic string representation that intentionally omits the
     * sensitive {@link #password()} value.
     *
     * <p>The compiler-generated {@code record} {@code toString} would include
     * every component, which would expose the password in logs and error
     * output. This override renders only {@link #userId()} and
     * {@link #action()} and masks the password as {@code ***}, guaranteeing the
     * credential never appears in any textual representation of this object.</p>
     *
     * @return a safe string representation with the password masked
     */
    @Override
    public String toString() {
        return "SignonRequest{userId=" + userId
                + ", action=" + action
                + ", password=***}";
    }
}
