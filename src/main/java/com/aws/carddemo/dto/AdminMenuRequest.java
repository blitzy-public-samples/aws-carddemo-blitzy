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

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <strong>Admin Menu</strong> screen.
 *
 * <p>Java re-expression of the inbound (operator-entered) fields of the 3270 map
 * <b>COADM01</b> (mapset {@code COADM1A}, online program <b>COADM01C</b>, CICS
 * transaction {@code CA00}). It is the request half of this screen's request/response
 * DTO pair &mdash; the outbound half is {@code AdminMenuResponse} &mdash; and carries what
 * the administrator submits from the menu: the option they typed and the attention key
 * they pressed. It is the Java re-platforming of the inbound symbolic fields of the map
 * copybook {@code app/cpy-bms/COADM01.CPY} (group {@code COADM01I}), retained for reference
 * under {@code legacy/cpy-bms/COADM01.CPY}.</p>
 *
 * <p>Consistent with the migration design (AAP 0.3.3), the pseudo-conversational CICS screen
 * is preserved as a REST request contract rather than a rendered 3270 terminal. Only the
 * single unprotected input field of the map is modeled; the numerous protected/output fields
 * (titles, headers, the twelve option label lines, the error line) and the BMS attribute-byte
 * sub-fields are carried by {@code AdminMenuResponse}, not by this request.</p>
 *
 * <p><strong>Field contract (preserved from BMS map COADM01).</strong> The map defines exactly
 * one unprotected field, {@code OPTION}, declared
 * {@code ATTRB=(FSET,IC,NORM,NUM,UNPROT), JUSTIFY=(RIGHT,ZERO), LENGTH=2}, whose symbolic input
 * field is {@code OPTIONI} ({@code PIC X(2)}). It is modeled as a two-character
 * {@link String} to preserve the exact field width and the right-justified, zero-filled 3270
 * semantics; the bean-validation constraints guard only that width and the numeric shape. The
 * option's <em>range</em> (which admin function it selects) is intentionally not validated here:
 * that routing decision is reproduced by {@code MenuService} from the legacy {@code COADM01C}
 * {@code PROCESS-ENTER-KEY} logic, so this DTO carries no business logic.</p>
 *
 * <p>This type declares no sensitive fields, so the record's generated {@link #toString()} is
 * safe to log.</p>
 *
 * @param option the menu-option selector the operator typed; maps to {@code OPTIONI}
 *        ({@code PIC X(2)}). At most two characters and, when present, composed solely of
 *        ASCII digits (the empty value represents a blank field). The value is retained
 *        verbatim &mdash; not parsed or range-checked &mdash; so the owning service can
 *        reproduce the exact legacy option-routing behavior.
 * @param action the attention key the operator pressed, as the transport-neutral
 *        {@link PfKeyAction} translation of the CICS {@code EIBAID}. For this screen the
 *        footer advertises {@code ENTER=Continue} and {@code F3=Exit}, i.e.
 *        {@link PfKeyAction#ENTER} and {@link PfKeyAction#PF3}; may be {@code null} when the
 *        caller does not distinguish the key.
 */
public record AdminMenuRequest(

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String option,

        PfKeyAction action) {
}
