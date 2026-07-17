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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Immutable request payload for the CardDemo <em>Delete User</em> screen.
 *
 * <p>This record is the Java re-platform of the BMS symbolic <em>input</em> map
 * {@code COUSR3AI} (map {@code COUSR3A}, map set {@code COUSR03}, defined in
 * {@code legacy/cpy-bms/COUSR03.CPY} and {@code legacy/bms/COUSR03.bms}) that
 * was driven by the legacy CICS program {@code COUSR03C} &mdash; whose function
 * is documented as <q>Delete a user from USRSEC file</q>. In the migrated,
 * layered Spring Boot architecture it is bound by {@code UserDeleteController}
 * (CICS transaction {@code CU03}) from the inbound JSON request, replacing the
 * 3270 terminal keystrokes while preserving the field-level contract of the
 * original map.</p>
 *
 * <h2>Screen semantics preserved</h2>
 * <p>The Delete User transaction is a read-then-confirm flow: the operator keys
 * a user id and presses {@code ENTER} to <em>fetch</em> the matching
 * {@code USRSEC} record for visual confirmation, then presses {@code F5} to
 * <em>delete</em> it. This request therefore carries only the operator-supplied
 * inputs of that flow &mdash; the {@link #userId() user id} lookup/delete key
 * and the {@link #action() attention key} that was pressed. The confirmation
 * detail (first name, last name, user type) is not part of the request: it is
 * fetched server-side and echoed back on the corresponding {@code UserDeleteResponse}.
 * The available program function keys are {@code ENTER=Fetch}, {@code F3=Back},
 * {@code F4=Clear} and {@code F5=Delete}, exactly as shown on the legacy map
 * footer.</p>
 *
 * <h2>Security</h2>
 * <p>The Delete User map exposes <strong>no password field</strong> (the legacy
 * {@code COUSR3AI} map and {@code COUSR03C} program reference none), and this
 * DTO deliberately preserves that omission. No credential or other sensitive
 * value is present, so the record's compiler-generated {@link #toString()} is
 * safe to log.</p>
 *
 * <h2>Field-level traceability</h2>
 * <p>The single terminal-enterable field of {@code COUSR3AI} maps one-to-one to
 * {@link #userId()}, preserving the field name intent, {@code String} type and
 * maximum length ({@code PIC X(8)}) of the original 3270 contract. All other
 * map fields ({@code FNAME}, {@code LNAME}, {@code USRTYPE}) are autoskip
 * display-only output on this screen and are therefore not carried here. The
 * attention identifier ({@code EIBAID}) that the legacy shared copybook
 * {@code legacy/cpy/CSSTRPFY.cpy} translated into a {@code CCARD-AID-*} condition
 * name is represented by the transport-neutral {@link #action()} value. This
 * transport type performs no business logic; the fetch-versus-delete decision is
 * made by {@code UserService} / {@code UserDeleteController} in line with the
 * original program's paragraph logic.</p>
 *
 * @param userId the identifier of the user to look up and delete; the sole
 *               terminal-enterable field of the Delete User map. Maps to
 *               {@code COUSR3AI.USRIDINI}, {@code PIC X(8)}. Required
 *               ({@link NotBlank}) and constrained to at most eight characters
 *               ({@link Size}), mirroring the 3270 field length.
 * @param action the attention key the operator transmitted, translated from the
 *               CICS {@code EIBAID} via {@code legacy/cpy/CSSTRPFY.cpy}. Optional
 *               (may be {@code null} when unspecified); on this screen the
 *               meaningful values are {@link PfKeyAction#ENTER} (fetch for
 *               confirmation), {@link PfKeyAction#PF3} (back), {@link PfKeyAction#PF4}
 *               (clear) and {@link PfKeyAction#PF5} (delete).
 */
public record UserDeleteRequest(

        @NotBlank
        @Size(max = 8)
        String userId,

        PfKeyAction action) {
}
