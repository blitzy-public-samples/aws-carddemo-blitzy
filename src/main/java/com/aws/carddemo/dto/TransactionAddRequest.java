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

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for the CardDemo <strong>Transaction Add</strong> screen.
 *
 * <p>This record is the Java re-platform of the BMS input symbolic map
 * {@code COTRN2AI} (BMS map {@code COTRN02}, mapset {@code COTRN2A}), which is
 * driven by the online program {@code COTRN02C} (CICS transaction {@code CT02})
 * and surfaced through {@code TransactionAddController}. It carries the values a
 * client submits to add a new transaction, preserving the 3270 field contract
 * (field names, lengths, and PIC-derived types) so there is no feature
 * expansion relative to the legacy screen.</p>
 *
 * <p>The Transaction Add screen follows a <em>confirm-then-commit</em> flow: the
 * operator keys the transaction detail and submits, the values are echoed back
 * with the {@code confirm} prompt ("Y"/"N"), and the record is only persisted
 * once the operator confirms. Because the entry fields may legitimately arrive
 * partially populated across the steps of that flow, the constraints below are
 * <em>format</em> and <em>maximum-length</em> checks only; every field is
 * optional at the DTO layer. The authoritative cross-field edit logic (presence
 * of an account <em>or</em> card key, numeric/range checks, date validity,
 * type/category existence, and the exact COBOL {@code COTRN02C} evaluation
 * order) is reproduced in the service layer under {@code service/rule/} together
 * with {@code TransactionService.add(...)}; this DTO holds no business logic.</p>
 *
 * <p><strong>Field contract.</strong> Field names, lengths, and types are
 * preserved one-for-one from the {@code COTRN2AI} unprotected (input) fields to
 * maintain the 3270 screen contract. The 3270 attribute/length control
 * sub-fields (the {@code *L}, {@code *F}, {@code *A} companions of each map
 * field, and the {@code CSSETATY} red-highlight-on-error attribute) are
 * intentionally omitted because there is no terminal rendering in the
 * re-platformed application; presence/format are expressed purely through
 * Jakarta Bean Validation. The monetary amount, held on the 3270 map as display
 * text {@code PIC X(12)} with the {@code (-99999999.99)} hint, is modeled here
 * as a {@link java.math.BigDecimal} (never {@code double}/{@code float}) so
 * decimal fidelity is exact; the {@link Digits} bound of eight integer and two
 * fraction digits mirrors the signed {@code S9(8)V99} value the screen accepts.
 * The origination and processing dates are carried as ISO-8601
 * ({@code YYYY-MM-DD}) strings matching the on-screen hint.</p>
 *
 * <p>The {@code action} field replaces the CICS {@code EIBAID} attention
 * identifier resolved by copybook {@code CSSTRPFY.cpy}; it is a transport-neutral
 * {@link PfKeyAction} rather than a terminal keystroke. Per the screen footer
 * ("ENTER=Continue&nbsp;&nbsp;F3=Back&nbsp;&nbsp;F4=Clear&nbsp;&nbsp;F5=Copy Last
 * Tran."), the meaningful values are {@link PfKeyAction#ENTER} (continue/confirm),
 * {@link PfKeyAction#PF3} (back), {@link PfKeyAction#PF4} (clear the form), and
 * {@link PfKeyAction#PF5} (copy the last transaction); the owning controller
 * decides the behavior for each key, so {@code action} carries no constraint and
 * may be {@code null}.</p>
 *
 * @param accountId    account identifier the transaction is being added for; maps to {@code ACTIDINI} {@code PIC X(11)}
 * @param cardNumber   card number the transaction is being added for; maps to {@code CARDNINI} {@code PIC X(16)}
 * @param typeCode     transaction type code; maps to {@code TTYPCDI} {@code PIC X(2)}
 * @param categoryCode transaction category code; maps to {@code TCATCDI} {@code PIC X(4)}
 * @param source       transaction source; maps to {@code TRNSRCI} {@code PIC X(10)}
 * @param description  transaction description; maps to {@code TDESCI} {@code PIC X(60)}
 * @param amount       transaction amount as an exact decimal (3270 display hint {@code -99999999.99}); maps to {@code TRNAMTI} {@code PIC X(12)}
 * @param originDate   transaction origination date (ISO-8601 {@code YYYY-MM-DD}); maps to {@code TORIGDTI} {@code PIC X(10)}
 * @param processDate  transaction processing date (ISO-8601 {@code YYYY-MM-DD}); maps to {@code TPROCDTI} {@code PIC X(10)}
 * @param merchantId   merchant identifier; maps to {@code MIDI} {@code PIC X(9)}
 * @param merchantName merchant name; maps to {@code MNAMEI} {@code PIC X(30)}
 * @param merchantCity merchant city; maps to {@code MCITYI} {@code PIC X(25)}
 * @param merchantZip  merchant ZIP/postal code; maps to {@code MZIPI} {@code PIC X(10)}
 * @param confirm      confirmation flag ("Y"/"N") for the confirm-then-commit prompt; maps to {@code CONFIRMI} {@code PIC X(1)}
 * @param action       the attention key the client transmitted (Enter/PF3/PF4/PF5); replaces {@code EIBAID} via {@code CSSTRPFY.cpy}
 */
public record TransactionAddRequest(

        // ACTIDINI PIC X(11) - account id key; numeric characters only, up to 11 digits.
        @Size(max = 11)
        @Pattern(regexp = "^\\d{0,11}$")
        String accountId,

        // CARDNINI PIC X(16) - card number.
        @Size(max = 16)
        String cardNumber,

        // TTYPCDI PIC X(2) - transaction type code.
        @Size(max = 2)
        String typeCode,

        // TCATCDI PIC X(4) - transaction category code.
        @Size(max = 4)
        String categoryCode,

        // TRNSRCI PIC X(10) - transaction source.
        @Size(max = 10)
        String source,

        // TDESCI PIC X(60) - transaction description.
        @Size(max = 60)
        String description,

        // TRNAMTI PIC X(12), hint (-99999999.99) - signed monetary amount -> BigDecimal scale 2 (never double/float).
        @Digits(integer = 8, fraction = 2)
        BigDecimal amount,

        // TORIGDTI PIC X(10), hint (YYYY-MM-DD) - ISO-8601 origination date.
        @Size(max = 10)
        @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$")
        String originDate,

        // TPROCDTI PIC X(10), hint (YYYY-MM-DD) - ISO-8601 processing date.
        @Size(max = 10)
        @Pattern(regexp = "^(\\d{4}-\\d{2}-\\d{2})?$")
        String processDate,

        // MIDI PIC X(9) - merchant id.
        @Size(max = 9)
        String merchantId,

        // MNAMEI PIC X(30) - merchant name.
        @Size(max = 30)
        String merchantName,

        // MCITYI PIC X(25) - merchant city.
        @Size(max = 25)
        String merchantCity,

        // MZIPI PIC X(10) - merchant zip.
        @Size(max = 10)
        String merchantZip,

        // CONFIRMI PIC X(1): only the single-character length is enforced here; the Y/N value
        // EVALUATE is reproduced in TransactionAddController.processEnter (WHEN OTHER), so an
        // invalid one-character confirm returns HTTP 200 with "Invalid value. Valid values are (Y/N)...".
        @Size(max = 1)
        String confirm,

        // EIBAID (CSSTRPFY.cpy) - transmitted attention key; Enter/PF3/PF4/PF5 per footer.
        PfKeyAction action) {
}
