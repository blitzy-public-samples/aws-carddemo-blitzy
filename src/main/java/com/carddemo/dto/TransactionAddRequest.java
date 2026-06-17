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
package com.carddemo.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable JSON request body for adding a financial transaction through
 * {@code POST /transactions} (handled by {@code TransactionController}).
 *
 * <h2>Lineage</h2>
 * <p>This contract is the Java/REST re-expression of the legacy CICS online
 * transaction-add program {@code COTRN02C} and its BMS map {@code COTRN02}
 * (field-layout copybook {@code COTRN02.CPY}). The persisted transaction record
 * is {@code CVTRA05Y} ({@code TRAN-RECORD}, RECLN&nbsp;350).</p>
 *
 * <p><strong>Request lengths follow the BMS <em>screen</em> field widths, not the
 * persisted record.</strong> Where the stored record is wider than the 3270 input
 * field — for example {@code TRAN-MERCHANT-NAME PIC X(50)} versus the screen field
 * {@code MNAME PIC X(30)}, or {@code TRAN-DESC PIC X(100)} versus {@code TDESC PIC
 * X(60)} — this request intentionally enforces the narrower BMS input length,
 * exactly as the original terminal screen did.</p>
 *
 * <h2>Account-or-card key &mdash; service-enforced XOR</h2>
 * <p>{@code COTRN02C} keys each transaction on <em>either</em> an Account ID
 * <em>or</em> a Card Number and rejects the request when neither is supplied
 * (legacy message {@code 'Account or Card Number must be entered...'}). Both
 * {@link #accountId()} and {@link #cardNum()} are therefore <strong>optional at the
 * field level</strong>; the rule that <strong>exactly one</strong> of them must be
 * present cannot be expressed with stock bean-validation constraints and is
 * enforced in {@code TransactionService} (mirroring the COBOL
 * {@code VALIDATE-INPUT-KEY-FIELDS} paragraph). When a card number is supplied
 * without an account id, the service resolves the owning account through the card
 * cross-reference (and vice-versa).</p>
 *
 * <h2>Responsibilities deliberately left to the service layer</h2>
 * <ul>
 *   <li>Generation of the 16-character {@code tranId} (PostgreSQL sequence value
 *       left-padded to 16 characters) is performed by {@code TranIdGenerator}
 *       within {@code TransactionService}; it is <strong>not</strong> part of this
 *       request and is never supplied by the client.</li>
 *   <li>The origination and processing timestamps ({@code origTs}/{@code procTs})
 *       are stamped by the service.</li>
 *   <li>Calendar validity of {@link #origDate()} and {@link #procDate()} (leap year,
 *       month range, real day-of-month, etc.) is delegated to
 *       {@code DateValidationService}, the Java parity of the {@code CSUTLDTC} date
 *       utility. These fields are therefore carried as the raw {@code YYYY-MM-DD}
 *       <em>text</em> ({@code String}) rather than a pre-parsed {@code java.time.LocalDate}:
 *       a strict Jackson date deserializer would reject an invalid date as a generic
 *       "Malformed request body." <em>before</em> the service ran, whereas binding to a
 *       {@code String} lets {@code DateValidationService} perform the validation and emit a
 *       <em>field-specific</em> message (e.g. "Orig Date is not a valid date; expected format
 *       YYYY-MM-DD"). The {@code @NotBlank} constraints here only guarantee that the values
 *       were supplied; calendar validity is enforced by the service.</li>
 * </ul>
 *
 * <p>The BMS {@code CONFIRM} (Y/N) field is intentionally omitted: it is a 3270
 * screen-only confirmation guard with no REST equivalent.</p>
 *
 * @param accountId    optional 11-digit account identifier (BMS {@code ACTIDIN});
 *                     supply exactly one of {@code accountId} / {@code cardNum}
 * @param cardNum      optional 16-digit card number (BMS {@code CARDNIN}); supply
 *                     exactly one of {@code accountId} / {@code cardNum}
 * @param typeCd       2-digit transaction type code (BMS {@code TTYPCD})
 * @param categoryCd   4-digit transaction category code (BMS {@code TCATCD})
 * @param source       transaction source, max 10 chars (BMS {@code TRNSRC})
 * @param description  transaction description, max 60 chars (BMS {@code TDESC})
 * @param amount       transaction amount (record {@code TRAN-AMT PIC S9(09)V99})
 * @param origDate     origination date {@code YYYY-MM-DD} (BMS {@code TORIGDT})
 * @param procDate     processing date {@code YYYY-MM-DD} (BMS {@code TPROCDT})
 * @param merchantId   9-digit merchant identifier (BMS {@code MID})
 * @param merchantName merchant name, max 30 chars (BMS {@code MNAME})
 * @param merchantCity merchant city, max 25 chars (BMS {@code MCITY})
 * @param merchantZip  merchant ZIP, max 10 chars (BMS {@code MZIP})
 */
public record TransactionAddRequest(

        // ACTIDIN — BMS PIC X(11) numeric account id. Optional at the field level
        // (TransactionService enforces "exactly one of accountId / cardNum", the COTRN02C XOR),
        // but when supplied it must fit the 11-digit BMS width. @Digits is null-tolerant, so a
        // null value still passes here and the account-or-card XOR is preserved.
        @Digits(integer = 11, fraction = 0, message = "Account ID must be at most 11 digits")
        Long accountId,

        // CARDNIN — BMS PIC X(16) card number. Optional at the field level; when present
        // it must be exactly 16 digits (COTRN02C: "Card Number must be Numeric...").
        @Pattern(regexp = "\\d{16}", message = "Card Number must be 16 digits")
        String cardNum,

        // TTYPCD — BMS PIC X(2) transaction type code. Required and numeric
        // (COTRN02C: "Type CD can NOT be empty..." / "Type CD must be Numeric...").
        @NotBlank(message = "Type CD can NOT be empty")
        @Pattern(regexp = "\\d{2}", message = "Type CD must be Numeric")
        String typeCd,

        // TCATCD — BMS PIC X(4) transaction category code. Required; numeric-ness is
        // enforced by the Integer binding and the 4-digit BMS width by @Digits
        // (COTRN02C: "Category CD can NOT be empty...").
        @NotNull(message = "Category CD can NOT be empty")
        @Digits(integer = 4, fraction = 0, message = "Category CD must be at most 4 digits")
        Integer categoryCd,

        // TRNSRC — BMS PIC X(10) transaction source. Required
        // (COTRN02C: "Source can NOT be empty...").
        @NotBlank(message = "Source can NOT be empty")
        @Size(max = 10, message = "Source must be at most 10 characters")
        String source,

        // TDESC — BMS PIC X(60) (persisted record CVTRA05Y is X(100); request uses the
        // BMS screen length). Required (COTRN02C: "Description can NOT be empty...").
        @NotBlank(message = "Description can NOT be empty")
        @Size(max = 60, message = "Description must be at most 60 characters")
        String description,

        // TRNAMT — persisted as TRAN-AMT PIC S9(09)V99 in CVTRA05Y. Required; at most
        // 9 integer and 2 fraction digits (COTRN02C: "Amount can NOT be empty..." plus
        // the sign + 8-digit + '.' + 2-digit format check).
        @NotNull(message = "Amount can NOT be empty")
        @Digits(integer = 9, fraction = 2,
                message = "Amount must have at most 9 integer digits and 2 fraction digits")
        BigDecimal amount,

        // TORIGDT — BMS PIC X(10) origination date in YYYY-MM-DD. Required and non-blank. Carried
        // as the raw text (String, not LocalDate) so DateValidationService (CSUTLDTC parity) runs
        // the calendar validation and emits a field-specific message; a strict Jackson LocalDate
        // deserializer would otherwise reject an invalid date as a generic "Malformed request body."
        // before the service ran. @NotBlank guarantees the value was supplied; calendar validity
        // (month 01-12, real day-of-month, leap year) is enforced by the service.
        @NotBlank(message = "Orig Date can NOT be empty")
        String origDate,

        // TPROCDT — BMS PIC X(10) processing date in YYYY-MM-DD. Required and non-blank; calendar
        // validity is delegated to DateValidationService exactly as for origDate above
        // (COTRN02C explicitly validates PROCDATE is not empty).
        @NotBlank(message = "Proc Date can NOT be empty")
        String procDate,

        // MID — BMS PIC X(9) merchant id. Required; numeric-ness is enforced by the Long
        // binding and the 9-digit BMS width by @Digits
        // (COTRN02C: "Merchant ID can NOT be empty..." / "Merchant ID must be Numeric...").
        @NotNull(message = "Merchant ID can NOT be empty")
        @Digits(integer = 9, fraction = 0, message = "Merchant ID must be at most 9 digits")
        Long merchantId,

        // MNAME — BMS PIC X(30) (persisted record CVTRA05Y is X(50); request uses the BMS
        // screen length). Required (COTRN02C: "Merchant Name can NOT be empty...").
        @NotBlank(message = "Merchant Name can NOT be empty")
        @Size(max = 30, message = "Merchant Name must be at most 30 characters")
        String merchantName,

        // MCITY — BMS PIC X(25) (persisted record CVTRA05Y is X(50); request uses the BMS
        // screen length). Required (COTRN02C: "Merchant City can NOT be empty...").
        @NotBlank(message = "Merchant City can NOT be empty")
        @Size(max = 25, message = "Merchant City must be at most 25 characters")
        String merchantCity,

        // MZIP — BMS PIC X(10) merchant ZIP. Required
        // (COTRN02C: "Merchant Zip can NOT be empty...").
        @NotBlank(message = "Merchant Zip can NOT be empty")
        @Size(max = 10, message = "Merchant Zip must be at most 10 characters")
        String merchantZip
) {
}
