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
package com.aws.carddemo.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * JSON write model (request body) for {@code PUT /api/v1/accounts/{accountId}}.
 *
 * <p>This DTO is the modern replacement for the retired 3270 <em>Account Update</em>
 * screen ({@code COACTUP.bms}) and the request side of legacy program
 * {@code COACTUPC} (transaction {@code CAUP}). It carries only the editable
 * fields of the account record; the authoritative field layout is derived from
 * the 300-byte {@code ACCOUNT-RECORD} copybook ({@code CVACT01Y.cpy}).</p>
 *
 * <h2>Read-only tightening (structural immutability by omission)</h2>
 * <p>This class <strong>intentionally omits</strong> both {@code accountId} and
 * {@code groupId}. The account identifier is supplied exclusively through the URL
 * path variable ({@code PUT /api/v1/accounts/{accountId}}), never through the
 * request body, and the group id is treated as display-only. Because neither field
 * exists here (no field, getter, setter, or constructor parameter), the account
 * identifier and group id can never be mutated through this endpoint, which
 * structurally prevents over-posting. This is a deliberate hardening relative to
 * the legacy BMS map, where the group-id field is {@code UNPROT} and the COBOL
 * rewrite path persists a changed group id.</p>
 *
 * <h2>Optimistic concurrency ({@code version})</h2>
 * <p>The mandatory {@link #version} field mirrors the JPA {@code @Version} column of
 * the persisted entity. The client echoes back the {@code version} it last read
 * (returned in {@code AccountResponse}); the service compares it during the update,
 * and a stale value ultimately yields an HTTP 409 Conflict. This reproduces the
 * legacy before-image comparison ("record changed by someone else &rarr; reject")
 * using modern optimistic locking. Because conflict detection is impossible without
 * it, {@code version} is annotated {@link NotNull}.</p>
 *
 * <h2>Types and validation strategy</h2>
 * <ul>
 *   <li><strong>Monetary fields</strong> use {@link java.math.BigDecimal} and carry
 *       only a structural {@link NotNull} presence guard. The exact
 *       {@code PIC S9(10)V99} shape &mdash; the &plusmn;9,999,999,999.99 range and the
 *       scale-2 limit &mdash; is <strong>authoritatively enforced by</strong>
 *       {@code service/AccountValidator} (legacy paragraph {@code 1250-EDIT-SIGNED-9V2}),
 *       not by a DTO {@code @Digits} constraint. This is deliberate: a {@code @Digits}
 *       guard fires during the unordered controller-level {@code @Valid} pass and would
 *       preempt the validator, emitting a generic Bean Validation message and defeating
 *       the legacy fail-fast field ordering. Removing it lets every monetary range/scale
 *       violation surface through {@code AccountValidator} with the exact legacy-parity
 *       message, in the correct interleaved edit order. {@code float}/{@code double} are
 *       never used, preserving exact decimal precision.</li>
 *   <li><strong>Date fields</strong> are intentionally declared as {@link String}
 *       (not {@code java.time.LocalDate}). The authoritative strict date validation
 *       (calendar validity plus the year 1900&ndash;2099 range) is performed by
 *       {@code service/AccountValidator}. Keeping the raw string here allows the
 *       validator to reject malformed or invalid values (for example
 *       {@code "2023-02-29"}, {@code "2014-13-01"}, {@code "2014-11-31"},
 *       {@code "1899-01-01"}) with legacy-parity error messages, rather than having
 *       Jackson reject them during deserialization before the validator can run.</li>
 * </ul>
 *
 * <p>The Bean Validation constraints declared here are <strong>structural only</strong>
 * (presence and shape). The business-rule domains &mdash; the {@code Y}/{@code N}
 * active-status values, the exact monetary range, and full calendar/century date
 * validity &mdash; are authoritatively enforced by {@code service/AccountValidator}
 * so that error semantics match the legacy program. Both structural and business
 * validation failures resolve to HTTP 400, so there is no behavioral conflict.</p>
 *
 * <p><strong>Security:</strong> this class deliberately does not implement
 * {@code toString()}, so monetary values and other account data are never emitted
 * to logs in plaintext.</p>
 */
public class AccountUpdateRequest {

    /**
     * Account active status. Legacy {@code ACCT-ACTIVE-STATUS PIC X(01)}.
     * The {@code Y}/{@code N} value domain is enforced by {@code AccountValidator}
     * (legacy paragraph {@code 1220-EDIT-YESNO}); the structural {@link Size}
     * guard here only bounds the length to a single character.
     */
    @NotNull
    @Size(min = 1, max = 1)
    @Schema(description = "Account active status (legacy ACCT-ACTIVE-STATUS). Domain enforced by "
            + "AccountValidator (1220-EDIT-YESNO).",
            allowableValues = {"Y", "N"}, example = "Y", maxLength = 1, minLength = 1,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String activeStatus;

    /**
     * Current account balance. Legacy {@code ACCT-CURR-BAL PIC S9(10)V99}.
     * The exact signed range (&plusmn;9,999,999,999.99, scale 2) is enforced by
     * {@code AccountValidator} (legacy paragraph {@code 1250-EDIT-SIGNED-9V2}) &mdash;
     * intentionally not by a DTO {@code @Digits} guard, so the legacy-parity message
     * surfaces in the correct edit order rather than being preempted by Bean Validation.
     */
    @NotNull
    @Schema(description = "Current account balance (legacy ACCT-CURR-BAL PIC S9(10)V99). Signed, scale 2, "
            + "range +/-9,999,999,999.99; range/scale enforced by AccountValidator (1250-EDIT-SIGNED-9V2).",
            type = "number", minimum = "-9999999999.99", maximum = "9999999999.99", multipleOf = 0.01,
            example = "194.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal currentBalance;

    /**
     * Credit limit. Legacy {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}.
     * Range/scale enforced authoritatively by {@code AccountValidator}
     * ({@code 1250-EDIT-SIGNED-9V2}); no DTO {@code @Digits} guard (see class Javadoc).
     */
    @NotNull
    @Schema(description = "Credit limit (legacy ACCT-CREDIT-LIMIT PIC S9(10)V99). Signed, scale 2, "
            + "range +/-9,999,999,999.99; range/scale enforced by AccountValidator (1250-EDIT-SIGNED-9V2).",
            type = "number", minimum = "-9999999999.99", maximum = "9999999999.99", multipleOf = 0.01,
            example = "2020.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit. Legacy {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}.
     * Range/scale enforced authoritatively by {@code AccountValidator}
     * ({@code 1250-EDIT-SIGNED-9V2}); no DTO {@code @Digits} guard (see class Javadoc).
     */
    @NotNull
    @Schema(description = "Cash credit limit (legacy ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99). Signed, scale 2, "
            + "range +/-9,999,999,999.99; range/scale enforced by AccountValidator (1250-EDIT-SIGNED-9V2).",
            type = "number", minimum = "-9999999999.99", maximum = "9999999999.99", multipleOf = 0.01,
            example = "1020.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal cashCreditLimit;

    /**
     * Account open date (ISO {@code yyyy-MM-dd}). Legacy {@code ACCT-OPEN-DATE PIC X(10)}.
     * Strict calendar validity and the year 1900&ndash;2099 range are enforced by
     * {@code AccountValidator}; kept as {@link String} so invalid values reach the
     * validator with legacy-parity error messages.
     */
    @NotNull
    @Schema(description = "Account open date, ISO yyyy-MM-dd (legacy ACCT-OPEN-DATE). Pattern expresses the "
            + "documented domain: year 1900-2099 (EDIT-YEAR-CCYY), month 01-12 (EDIT-MONTH), day 01-31 "
            + "(EDIT-DAY). Kept as a String so full cross-field calendar validity (leap year, days-per-month) "
            + "is enforced by AccountValidator with legacy-parity messages.",
            type = "string", pattern = "^(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$",
            example = "2014-11-20", requiredMode = Schema.RequiredMode.REQUIRED)
    private String openDate;

    /**
     * Account expiration date (ISO {@code yyyy-MM-dd}).
     * Legacy {@code ACCT-EXPIRAION-DATE PIC X(10)} (the copybook misspelling
     * "EXPIRAION" is corrected to "expiration" here). Validated by
     * {@code AccountValidator}.
     */
    @NotNull
    @Schema(description = "Account expiration date, ISO yyyy-MM-dd (legacy ACCT-EXPIRAION-DATE, corrected "
            + "spelling). Pattern expresses the documented domain: year 1900-2099, month 01-12, day 01-31. "
            + "Full cross-field calendar validity is enforced by AccountValidator.",
            type = "string", pattern = "^(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$",
            example = "2025-05-20", requiredMode = Schema.RequiredMode.REQUIRED)
    private String expirationDate;

    /**
     * Card reissue date (ISO {@code yyyy-MM-dd}). Legacy {@code ACCT-REISSUE-DATE PIC X(10)}.
     * Validated by {@code AccountValidator}.
     */
    @NotNull
    @Schema(description = "Card reissue date, ISO yyyy-MM-dd (legacy ACCT-REISSUE-DATE). Pattern expresses "
            + "the documented domain: year 1900-2099, month 01-12, day 01-31. Full cross-field calendar "
            + "validity is enforced by AccountValidator.",
            type = "string", pattern = "^(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$",
            example = "2025-05-20", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reissueDate;

    /**
     * Current cycle credit total. Legacy {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}.
     * Range/scale enforced authoritatively by {@code AccountValidator}
     * ({@code 1250-EDIT-SIGNED-9V2}); no DTO {@code @Digits} guard (see class Javadoc).
     */
    @NotNull
    @Schema(description = "Current cycle credit total (legacy ACCT-CURR-CYC-CREDIT PIC S9(10)V99). Signed, "
            + "scale 2, range +/-9,999,999,999.99; range/scale enforced by AccountValidator "
            + "(1250-EDIT-SIGNED-9V2).",
            type = "number", minimum = "-9999999999.99", maximum = "9999999999.99", multipleOf = 0.01,
            example = "0.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit total. Legacy {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}.
     * Range/scale enforced authoritatively by {@code AccountValidator}
     * ({@code 1250-EDIT-SIGNED-9V2}); no DTO {@code @Digits} guard (see class Javadoc).
     */
    @NotNull
    @Schema(description = "Current cycle debit total (legacy ACCT-CURR-CYC-DEBIT PIC S9(10)V99). Signed, "
            + "scale 2, range +/-9,999,999,999.99; range/scale enforced by AccountValidator "
            + "(1250-EDIT-SIGNED-9V2).",
            type = "number", minimum = "-9999999999.99", maximum = "9999999999.99", multipleOf = 0.01,
            example = "0.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal currentCycleDebit;

    /**
     * Account address ZIP. Legacy {@code ACCT-ADDR-ZIP PIC X(10)}.
     *
     * <p>Marked {@link NotNull} to keep this write-model contract consistent with the
     * persisted entity and schema, where the column is declared {@code NOT NULL}
     * ({@code Account.addressZip} / {@code address_zip VARCHAR(10) NOT NULL}). The
     * legacy record is fixed-width {@code X(10)}: the field is <em>always present</em>
     * (blank-filled), never absent (AAP &sect;0.7.1 data-field parity). A {@code null}
     * (an omitted value) is therefore rejected as a 400 rather than being allowed to
     * propagate into a {@code NOT NULL} column and surface as an ungraceful 500.
     * The legacy blank-fill tolerance is preserved: {@code @NotNull} forbids only
     * {@code null}, so an empty or all-blank value is still accepted, and
     * {@link Size}{@code (max = 10)} bounds the maximum length. The authoritative
     * presence check is reproduced server-side by {@code AccountValidator}.</p>
     */
    @NotNull
    @Size(max = 10)
    @Schema(description = "Account address ZIP (legacy ACCT-ADDR-ZIP PIC X(10)). Fixed-width X(10): always "
            + "present (may be blank), never absent; null is rejected. Maximum length 10.",
            type = "string", maxLength = 10, example = "A000000000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String addressZip;

    /**
     * Optimistic-locking token echoed from the last read. Mapped to the JPA
     * {@code @Version} column of the persisted entity. Required so the service can
     * detect a concurrent modification and respond with HTTP 409 Conflict.
     * There is no legacy record equivalent; it replaces the legacy before-image check.
     */
    @NotNull
    @Schema(description = "Optimistic-locking token echoed from the last read (maps to the JPA @Version "
            + "column). A stale value yields HTTP 409 Conflict. No legacy record equivalent; it replaces "
            + "the legacy before-image check.",
            type = "integer", format = "int64", minimum = "0", example = "0",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long version;

    /**
     * Default no-argument constructor required by Jackson for request-body
     * deserialization.
     */
    public AccountUpdateRequest() {
        // Intentionally empty: fields are populated by Jackson via setters.
    }

    /**
     * All-arguments constructor over the eleven editable fields, provided for
     * convenience in tests and programmatic construction.
     *
     * <p>Field order matches the declaration order above. By design this constructor
     * accepts neither {@code accountId} nor {@code groupId}, preserving the read-only
     * tightening.</p>
     *
     * @param activeStatus       account active status ({@code Y}/{@code N})
     * @param currentBalance     current account balance
     * @param creditLimit        credit limit
     * @param cashCreditLimit    cash credit limit
     * @param openDate           account open date ({@code yyyy-MM-dd})
     * @param expirationDate     account expiration date ({@code yyyy-MM-dd})
     * @param reissueDate        card reissue date ({@code yyyy-MM-dd})
     * @param currentCycleCredit current cycle credit total
     * @param currentCycleDebit  current cycle debit total
     * @param addressZip         account address ZIP (must not be {@code null}; may be blank)
     * @param version            optimistic-locking token from the last read
     */
    public AccountUpdateRequest(String activeStatus,
                                BigDecimal currentBalance,
                                BigDecimal creditLimit,
                                BigDecimal cashCreditLimit,
                                String openDate,
                                String expirationDate,
                                String reissueDate,
                                BigDecimal currentCycleCredit,
                                BigDecimal currentCycleDebit,
                                String addressZip,
                                Long version) {
        this.activeStatus = activeStatus;
        this.currentBalance = currentBalance;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.currentCycleCredit = currentCycleCredit;
        this.currentCycleDebit = currentCycleDebit;
        this.addressZip = addressZip;
        this.version = version;
    }

    // ---------------------------------------------------------------------
    // Accessors (hand-written; no Lombok). Setters support Jackson
    // deserialization; getters are consumed by AccountValidator,
    // AccountService, and AccountMapper.
    // ---------------------------------------------------------------------

    /**
     * @return the account active status ({@code Y}/{@code N})
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * @param activeStatus the account active status to set
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * @return the current account balance
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * @param currentBalance the current account balance to set
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * @return the credit limit
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * @param creditLimit the credit limit to set
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * @return the cash credit limit
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * @param cashCreditLimit the cash credit limit to set
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * @return the account open date ({@code yyyy-MM-dd})
     */
    public String getOpenDate() {
        return openDate;
    }

    /**
     * @param openDate the account open date to set
     */
    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    /**
     * @return the account expiration date ({@code yyyy-MM-dd})
     */
    public String getExpirationDate() {
        return expirationDate;
    }

    /**
     * @param expirationDate the account expiration date to set
     */
    public void setExpirationDate(String expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * @return the card reissue date ({@code yyyy-MM-dd})
     */
    public String getReissueDate() {
        return reissueDate;
    }

    /**
     * @param reissueDate the card reissue date to set
     */
    public void setReissueDate(String reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * @return the current cycle credit total
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * @param currentCycleCredit the current cycle credit total to set
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * @return the current cycle debit total
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * @param currentCycleDebit the current cycle debit total to set
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * @return the account address ZIP (non-null per the request contract; may be blank)
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * @param addressZip the account address ZIP to set
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * @return the optimistic-locking version token
     */
    public Long getVersion() {
        return version;
    }

    /**
     * @param version the optimistic-locking version token to set
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
