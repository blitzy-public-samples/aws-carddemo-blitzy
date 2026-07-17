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

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <em>Main Menu</em> screen.
 *
 * <p>Java re-expression of the {@code COMEN1AI} input group of BMS symbolic copybook
 * {@code COMEN01} (BMS map {@code COMEN01} / mapset {@code COMEN1A}, online program
 * {@code COMEN01C}, CICS transaction {@code CM00}). It carries the single operator-editable
 * field the legacy 3270 screen accepted &mdash; the menu-option selector &mdash; together with
 * the attention key that was pressed.</p>
 *
 * <p>The pseudo-conversational CICS screen is preserved as a REST request contract rather than a
 * rendered terminal (AAP 0.3.3). Of all the fields on the map, {@code OPTION} is the only
 * {@code UNPROT} (unprotected / editable) field; every other field on {@code COMEN01.bms} is an
 * {@code ASKIP} display field and is therefore represented only on the outbound
 * {@link MainMenuResponse}. Field name, maximum length, and edit rules are preserved from the
 * copybook and the map for the field-level traceability required by AAP 0.9.2.</p>
 *
 * <p>This transport type performs no business logic: routing the chosen option to the target
 * sub-function (account, card, transaction, report, or bill-payment screens) is the
 * responsibility of {@code MenuService}, which parses and range-checks the option against the
 * menu items available to the signed-on user.</p>
 *
 * @param option the menu-option selector typed by the operator; origin {@code OPTIONI},
 *               {@code PIC X(2)}. On the 3270 the underlying {@code OPTION} field is {@code NUM}
 *               with {@code JUSTIFY=(RIGHT,ZERO)} and {@code LENGTH=2}, so it holds at most two
 *               numeric digits. Modeled as {@link String} (never a numeric type) to preserve the
 *               exact two-character width contract; an empty value is permitted because the field
 *               may be left blank (for example when exiting with {@link PfKeyAction#PF3}).
 *               Constrained to at most two characters ({@link Size}) that must all be digits
 *               ({@link Pattern}).
 * @param action the 3270 attention identifier (AID) the operator transmitted; origin
 *               {@code EIBAID} via shared copybook {@code CSSTRPFY.cpy}. Optional and may be
 *               {@code null}. For this screen the footer advertises {@code ENTER=Continue  F3=Exit},
 *               so the meaningful values are {@link PfKeyAction#ENTER} (process the selected
 *               option) and {@link PfKeyAction#PF3} (exit to the prior screen); any other key is
 *               ignored by the owning controller.
 */
public record MainMenuRequest(

        @Size(max = 2)
        @Pattern(regexp = "^\\d{0,2}$")
        String option,

        PfKeyAction action) {
}
