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
package com.aws.carddemo.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * Read-projection Data Transfer Object for a CardDemo account.
 *
 * <p>{@code AccountResponse} is the JSON read model returned by <em>both</em>
 * {@code GET /api/v1/accounts/{accountId}} and {@code PUT /api/v1/accounts/{accountId}}
 * in the {@code account-service} module. It is the modern replacement for the retired
 * 3270 <strong>Account Viewer</strong> screen ({@code COACTVW.bms}) and mirrors, field
 * for field, the authoritative 300-byte legacy record layout
 * {@code ACCOUNT-RECORD} defined in {@code CVACT01Y.cpy} — dropping only the trailing
 * {@code FILLER PIC X(178)} padding, which carries no semantic value.</p>
 *
 * <h2>Design decisions (see Technical Specification §0.3, §0.6)</h2>
 * <ul>
 *   <li><strong>Output only — never validated.</strong> This class carries no
 *       Bean Validation constraint annotations; those belong exclusively to
 *       {@code AccountUpdateRequest}, the write model.</li>
 *   <li><strong>Exact monetary precision.</strong> Every money field is a
 *       {@link java.math.BigDecimal}; approximate binary numeric types are prohibited
 *       in the money path per §0.6.2 to guarantee exact scale-2 precision.
 *       Values are normalized to scale 2 by {@code AccountMapper} and serialized by
 *       Jackson in plain (non-scientific) notation via the module-wide
 *       {@code WRITE_BIGDECIMAL_AS_PLAIN} setting owned by {@code application.yml};
 *       this DTO deliberately does not pre-format money into strings.</li>
 *   <li><strong>ISO-8601 dates.</strong> Every date field is a
 *       {@link java.time.LocalDate} annotated with
 *       {@link com.fasterxml.jackson.annotation.JsonFormat @JsonFormat} so the JSON
 *       representation is guaranteed to be {@code yyyy-MM-dd} regardless of global
 *       configuration (e.g. {@code openDate=2014-11-20} serializes to
 *       {@code "openDate":"2014-11-20"}).</li>
 *   <li><strong>Full read model.</strong> {@code accountId}, {@code groupId},
 *       {@code addressZip}, and the optimistic-lock {@code version} are all included.
 *       {@code addressZip} is not shown on the legacy BMS screen but is present in the
 *       record layout ({@code CVACT01Y.cpy}) and carries real data, so it is preserved
 *       (§0.1.1 / §0.7.1). {@code version} is echoed so clients can round-trip it into
 *       the next {@code PUT} to enable HTTP 409 conflict detection (§0.6.4).</li>
 * </ul>
 *
 * <p>The legacy copybook misspelling {@code ACCT-EXPIRAION-DATE} is corrected to
 * {@code expirationDate} in the Java attribute name.</p>
 *
 * <p><strong>Security (§0.6.6):</strong> This class intentionally omits
 * {@code toString()} so that the full account number and monetary values are never
 * emitted to logs in plaintext.</p>
 *
 * <p>This DTO depends only on the JDK and Jackson annotations; it references no sibling
 * package types.</p>
 */
public class AccountResponse {

    /**
     * Account identifier — the primary key. Sourced from {@code ACCT-ID PIC 9(11)}.
     * Represented as an 11-character, zero-padded numeric string (e.g.
     * {@code "00000000001"}); leading zeros are significant and must never be stripped,
     * preserving join compatibility with the card and cross-reference records.
     */
    private String accountId;

    /**
     * Active status flag. Sourced from {@code ACCT-ACTIVE-STATUS PIC X(01)}.
     * A single character, canonically {@code "Y"} or {@code "N"}.
     */
    private String activeStatus;

    /**
     * Current account balance. Sourced from {@code ACCT-CURR-BAL PIC S9(10)V99}.
     * Exact decimal with scale 2; range +/-9,999,999,999.99.
     */
    private BigDecimal currentBalance;

    /**
     * Credit limit. Sourced from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}.
     * Exact decimal with scale 2; range +/-9,999,999,999.99.
     */
    private BigDecimal creditLimit;

    /**
     * Cash credit limit. Sourced from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}.
     * Exact decimal with scale 2; range +/-9,999,999,999.99.
     */
    private BigDecimal cashCreditLimit;

    /**
     * Account open date. Sourced from {@code ACCT-OPEN-DATE PIC X(10)}.
     * Serialized as ISO {@code yyyy-MM-dd}.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate openDate;

    /**
     * Account expiration date. Sourced from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * (legacy spelling corrected). Serialized as ISO {@code yyyy-MM-dd}.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate expirationDate;

    /**
     * Account reissue date. Sourced from {@code ACCT-REISSUE-DATE PIC X(10)}.
     * Serialized as ISO {@code yyyy-MM-dd}.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate reissueDate;

    /**
     * Current cycle credit total. Sourced from
     * {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}.
     * Exact decimal with scale 2; range +/-9,999,999,999.99.
     */
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit total. Sourced from
     * {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}.
     * Exact decimal with scale 2; range +/-9,999,999,999.99.
     */
    private BigDecimal currentCycleDebit;

    /**
     * Account address ZIP code. Sourced from {@code ACCT-ADDR-ZIP PIC X(10)}.
     * Not displayed on the legacy BMS screen but preserved in the read model because
     * it is present in the record layout and carries real data.
     */
    private String addressZip;

    /**
     * Account group identifier. Sourced from {@code ACCT-GROUP-ID PIC X(10)}.
     * Included in the read model; treated as read-only (never accepted on update).
     */
    private String groupId;

    /**
     * Optimistic-lock version counter. No legacy equivalent; introduced by the JPA
     * {@code @Version} column on the {@code Account} entity. Echoed to the client so it
     * can be round-tripped into the next {@code PUT} request, enabling HTTP 409 conflict
     * detection when another writer has advanced the version in the interim.
     */
    private Long version;

    /**
     * Creates an empty {@code AccountResponse}.
     *
     * <p>Required for flexible construction and for Jackson deserialization during
     * round-trip testing.</p>
     */
    public AccountResponse() {
        // No-argument constructor intentionally left empty.
    }

    /**
     * Creates a fully populated {@code AccountResponse}.
     *
     * <p>Provided as a convenience so {@code AccountMapper} can build the response in a
     * single call. Parameters are declared in the same order as the fields and the
     * legacy record layout.</p>
     *
     * @param accountId          the 11-digit, zero-padded account identifier
     * @param activeStatus       the active status flag ("Y"/"N")
     * @param currentBalance     the current balance (scale 2)
     * @param creditLimit        the credit limit (scale 2)
     * @param cashCreditLimit    the cash credit limit (scale 2)
     * @param openDate           the account open date
     * @param expirationDate     the account expiration date
     * @param reissueDate        the account reissue date
     * @param currentCycleCredit the current cycle credit total (scale 2)
     * @param currentCycleDebit  the current cycle debit total (scale 2)
     * @param addressZip         the address ZIP code
     * @param groupId            the account group identifier
     * @param version            the optimistic-lock version counter
     */
    public AccountResponse(String accountId,
                           String activeStatus,
                           BigDecimal currentBalance,
                           BigDecimal creditLimit,
                           BigDecimal cashCreditLimit,
                           LocalDate openDate,
                           LocalDate expirationDate,
                           LocalDate reissueDate,
                           BigDecimal currentCycleCredit,
                           BigDecimal currentCycleDebit,
                           String addressZip,
                           String groupId,
                           Long version) {
        this.accountId = accountId;
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
        this.groupId = groupId;
        this.version = version;
    }

    /**
     * @return the 11-digit, zero-padded account identifier
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * @param accountId the 11-digit, zero-padded account identifier
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * @return the active status flag ("Y"/"N")
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * @param activeStatus the active status flag ("Y"/"N")
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * @return the current balance (scale 2)
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * @param currentBalance the current balance (scale 2)
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * @return the credit limit (scale 2)
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * @param creditLimit the credit limit (scale 2)
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * @return the cash credit limit (scale 2)
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * @param cashCreditLimit the cash credit limit (scale 2)
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * @return the account open date
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * @param openDate the account open date
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    /**
     * @return the account expiration date
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * @param expirationDate the account expiration date
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * @return the account reissue date
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * @param reissueDate the account reissue date
     */
    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * @return the current cycle credit total (scale 2)
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * @param currentCycleCredit the current cycle credit total (scale 2)
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * @return the current cycle debit total (scale 2)
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * @param currentCycleDebit the current cycle debit total (scale 2)
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * @return the address ZIP code
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * @param addressZip the address ZIP code
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * @return the account group identifier
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * @param groupId the account group identifier
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * @return the optimistic-lock version counter
     */
    public Long getVersion() {
        return version;
    }

    /**
     * @param version the optimistic-lock version counter
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
