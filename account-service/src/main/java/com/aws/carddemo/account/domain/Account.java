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
package com.aws.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * JPA entity mapping the CardDemo account record to the relational {@code accounts} table.
 *
 * <p>This class is the modern, relational re-expression of the legacy 300-byte VSAM
 * {@code ACCOUNT-RECORD} copybook {@code app/cpy/CVACT01Y.cpy} (RECLN 300). It is the
 * foundational domain type of the {@code account-service} Spring Boot module and sits at the
 * base of the layered dependency chain {@code controller -> service -> repository -> domain};
 * {@code AccountRepository}, {@code AccountMapper}, and {@code AccountService} all compile
 * against it. The migration goal is to <em>preserve behavior/structure and replace
 * mechanism</em>: every semantic field of the legacy record is carried forward as a typed
 * column, while the record-oriented VSAM access is replaced by JPA-managed persistence.</p>
 *
 * <h2>Field-to-column provenance (legacy source {@code CVACT01Y.cpy})</h2>
 * <ul>
 *   <li>{@code ACCT-ID PIC 9(11)} (L5) &rarr; {@link #accountId} / {@code account_id VARCHAR(11)} (primary key)</li>
 *   <li>{@code ACCT-ACTIVE-STATUS PIC X(01)} (L6) &rarr; {@link #activeStatus} / {@code active_status CHAR(1)}</li>
 *   <li>{@code ACCT-CURR-BAL PIC S9(10)V99} (L7) &rarr; {@link #currentBalance} / {@code current_balance NUMERIC(12,2)}</li>
 *   <li>{@code ACCT-CREDIT-LIMIT PIC S9(10)V99} (L8) &rarr; {@link #creditLimit} / {@code credit_limit NUMERIC(12,2)}</li>
 *   <li>{@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} (L9) &rarr; {@link #cashCreditLimit} / {@code cash_credit_limit NUMERIC(12,2)}</li>
 *   <li>{@code ACCT-OPEN-DATE PIC X(10)} (L10) &rarr; {@link #openDate} / {@code open_date DATE}</li>
 *   <li>{@code ACCT-EXPIRAION-DATE PIC X(10)} (L11) &rarr; {@link #expirationDate} / {@code expiration_date DATE}</li>
 *   <li>{@code ACCT-REISSUE-DATE PIC X(10)} (L12) &rarr; {@link #reissueDate} / {@code reissue_date DATE}</li>
 *   <li>{@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} (L13) &rarr; {@link #currentCycleCredit} / {@code current_cycle_credit NUMERIC(12,2)}</li>
 *   <li>{@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} (L14) &rarr; {@link #currentCycleDebit} / {@code current_cycle_debit NUMERIC(12,2)}</li>
 *   <li>{@code ACCT-ADDR-ZIP PIC X(10)} (L15) &rarr; {@link #addressZip} / {@code address_zip VARCHAR(10)}</li>
 *   <li>{@code ACCT-GROUP-ID PIC X(10)} (L16) &rarr; {@link #groupId} / {@code group_id VARCHAR(10)}</li>
 *   <li>{@code version BIGINT} &rarr; {@link #version} (new optimistic-lock control; no legacy equivalent)</li>
 * </ul>
 *
 * <h2>Deliberate migration decisions</h2>
 * <ul>
 *   <li><strong>Trailing {@code FILLER PIC X(178)} (L17) is intentionally dropped</strong> as pure
 *       padding with no semantic value.</li>
 *   <li><strong>The legacy copybook typo {@code ACCT-EXPIRAION-DATE} is corrected</strong> to the
 *       properly-spelled {@code expiration} for both the Java attribute and the column name.</li>
 *   <li><strong>The account identifier is a zero-padded {@code String} natural key</strong>, not a
 *       numeric type. The 11-digit key is zero-padded (e.g. {@code 00000000001}); a numeric type
 *       would silently drop leading zeros. A {@code String}/{@code VARCHAR(11)} preserves the key
 *       exactly and keeps join compatibility with the 11-digit {@code CARD-ACCT-ID}
 *       ({@code CVACT02Y.cpy:L6}) and {@code XREF-ACCT-ID} ({@code CVACT03Y.cpy:L7}) keys carried on
 *       the card and cross-reference records. It is a supplied natural key, so no
 *       {@code @GeneratedValue} is used.</li>
 *   <li><strong>Monetary fields use {@link java.math.BigDecimal} with a fixed scale of 2 only</strong>;
 *       {@code float}/{@code double} are strictly prohibited in the money path to guarantee exact
 *       precision for {@code PIC S9(10)V99} values (range &plusmn;9,999,999,999.99).</li>
 *   <li><strong>Optimistic concurrency</strong> is enforced through the {@link #version}
 *       {@code @Version} column. It replaces the legacy field-by-field before-image comparison in
 *       {@code COACTUPC.cbl} {@code 9700-CHECK-CHANGE-IN-REC}: Hibernate increments the version on
 *       every update and a stale version raises an optimistic-lock failure that the service layer
 *       surfaces as HTTP 409.</li>
 * </ul>
 *
 * <h2>Schema ownership and validation contract</h2>
 * <p>The sibling Flyway migration {@code db/migration/V1__create_accounts_table.sql} owns the
 * schema and Hibernate runs with {@code spring.jpa.hibernate.ddl-auto=validate}. Every
 * {@code @Column(name = ...)} and its resolved JDBC type must therefore match the DDL exactly;
 * any drift throws {@code SchemaManagementException} at startup. The one mapping requiring special
 * care is {@link #activeStatus}: a plain {@code String} resolves to JDBC {@code VARCHAR}, but the
 * column is {@code CHAR(1)} (PostgreSQL {@code bpchar}), so it is aligned with
 * {@link org.hibernate.annotations.JdbcTypeCode @JdbcTypeCode(SqlTypes.CHAR)}.</p>
 *
 * <p>This class is a <em>pure persistence mapping</em>: it carries no Bean Validation annotations.
 * All business validation (active-status domain, monetary range/scale, strict date rules,
 * identifier/group immutability) lives in the service and DTO layers. For the same reason, and to
 * avoid leaking the account number or monetary values into logs, no {@code toString()} is
 * generated.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * Account identifier and primary key. Migrated from {@code ACCT-ID PIC 9(11)}
     * ({@code CVACT01Y.cpy:L5}). Stored as a zero-padded 11-character {@code String}
     * ({@code VARCHAR(11)}) to preserve leading zeros and remain join-compatible with the
     * card/cross-reference account keys. Supplied by the caller &mdash; not database-generated.
     */
    @Id
    @Column(name = "account_id", length = 11, nullable = false)
    private String accountId;

    /**
     * Account active status flag, migrated from {@code ACCT-ACTIVE-STATUS PIC X(01)}
     * ({@code CVACT01Y.cpy:L6}). The legacy domain is {@code Y}/{@code N}, enforced in the
     * validation layer (not here). Aligned to the {@code CHAR(1)} column via
     * {@link org.hibernate.annotations.JdbcTypeCode @JdbcTypeCode(SqlTypes.CHAR)} so that
     * {@code ddl-auto=validate} sees a matching JDBC {@code CHAR} type rather than {@code VARCHAR}.
     */
    @Column(name = "active_status", length = 1, nullable = false)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeStatus;

    /**
     * Current account balance, migrated from {@code ACCT-CURR-BAL PIC S9(10)V99}
     * ({@code CVACT01Y.cpy:L7}). {@link java.math.BigDecimal} with scale 2 for exact monetary
     * precision; maps to {@code NUMERIC(12,2)} (10 integer + 2 fraction digits).
     */
    @Column(name = "current_balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentBalance;

    /**
     * Credit limit, migrated from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code CVACT01Y.cpy:L8}). {@link java.math.BigDecimal} scale 2; maps to {@code NUMERIC(12,2)}.
     */
    @Column(name = "credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal creditLimit;

    /**
     * Cash credit limit, migrated from {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99}
     * ({@code CVACT01Y.cpy:L9}). {@link java.math.BigDecimal} scale 2; maps to {@code NUMERIC(12,2)}.
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2, nullable = false)
    private BigDecimal cashCreditLimit;

    /**
     * Account open date, migrated from {@code ACCT-OPEN-DATE PIC X(10)}
     * ({@code CVACT01Y.cpy:L10}). Stored ISO {@code YYYY-MM-DD}; maps to SQL {@code DATE}.
     */
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /**
     * Account expiration date, migrated from {@code ACCT-EXPIRAION-DATE PIC X(10)}
     * ({@code CVACT01Y.cpy:L11}). The legacy copybook misspelling is corrected here to
     * {@code expiration}. Stored ISO {@code YYYY-MM-DD}; maps to SQL {@code DATE}.
     */
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * Card reissue date, migrated from {@code ACCT-REISSUE-DATE PIC X(10)}
     * ({@code CVACT01Y.cpy:L12}). Stored ISO {@code YYYY-MM-DD}; maps to SQL {@code DATE}.
     */
    @Column(name = "reissue_date", nullable = false)
    private LocalDate reissueDate;

    /**
     * Current cycle credit amount, migrated from {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99}
     * ({@code CVACT01Y.cpy:L13}). {@link java.math.BigDecimal} scale 2; maps to {@code NUMERIC(12,2)}.
     */
    @Column(name = "current_cycle_credit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleCredit;

    /**
     * Current cycle debit amount, migrated from {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99}
     * ({@code CVACT01Y.cpy:L14}). {@link java.math.BigDecimal} scale 2; maps to {@code NUMERIC(12,2)}.
     */
    @Column(name = "current_cycle_debit", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentCycleDebit;

    /**
     * Account address ZIP code, migrated from {@code ACCT-ADDR-ZIP PIC X(10)}
     * ({@code CVACT01Y.cpy:L15}). Preserved per the Minimal Change Clause even though it was
     * omitted from the prompt's field table; seed data shows alphanumeric values (e.g.
     * {@code A000000000}), hence {@code VARCHAR(10)}, not numeric.
     */
    @Column(name = "address_zip", length = 10, nullable = false)
    private String addressZip;

    /**
     * Account group identifier, migrated from {@code ACCT-GROUP-ID PIC X(10)}
     * ({@code CVACT01Y.cpy:L16}); maps to {@code VARCHAR(10)}. Treated as read-only at the API
     * boundary — the update DTO ({@code AccountUpdateRequest}) omits it, so no update can change it.
     * This is an <strong>intentional security hardening</strong> (AAP &sect;0.7.2), <em>not</em> a
     * reproduction of legacy behavior: the legacy {@code COACTUP} BMS map defined the group-id field
     * as {@code UNPROT} (editable) and {@code COACTUPC.cbl} persisted a changed group id on rewrite.
     * The migration deliberately tightens it to display-only to satisfy the documented
     * "Account ID and group ID immutable" requirement.
     */
    @Column(name = "group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * Optimistic-locking control column (no legacy equivalent). Managed by Hibernate: incremented
     * on every update, and a stale value raises {@code ObjectOptimisticLockingFailureException},
     * which the {@code GlobalExceptionHandler} maps to HTTP 409. New rows start at {@code 0}.
     * Maps to {@code BIGINT NOT NULL}.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * No-argument constructor required by JPA/Hibernate for entity instantiation via reflection.
     */
    public Account() {
    }

    /**
     * Returns the 11-character zero-padded account identifier (primary key).
     *
     * @return the account id
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Sets the account identifier (primary key).
     *
     * @param accountId the 11-character zero-padded account id
     */
    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    /**
     * Returns the active status flag ({@code Y}/{@code N}).
     *
     * @return the active status
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * Sets the active status flag.
     *
     * @param activeStatus the active status ({@code Y}/{@code N})
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * Returns the current account balance.
     *
     * @return the current balance
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Sets the current account balance.
     *
     * @param currentBalance the current balance (scale 2)
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    /**
     * Returns the credit limit.
     *
     * @return the credit limit
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Sets the credit limit.
     *
     * @param creditLimit the credit limit (scale 2)
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * Sets the cash credit limit.
     *
     * @param cashCreditLimit the cash credit limit (scale 2)
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * Returns the account open date.
     *
     * @return the open date
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * Sets the account open date.
     *
     * @param openDate the open date
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    /**
     * Returns the account expiration date.
     *
     * @return the expiration date
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Sets the account expiration date.
     *
     * @param expirationDate the expiration date
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * Returns the card reissue date.
     *
     * @return the reissue date
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * Sets the card reissue date.
     *
     * @param reissueDate the reissue date
     */
    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * Returns the current cycle credit amount.
     *
     * @return the current cycle credit
     */
    public BigDecimal getCurrentCycleCredit() {
        return currentCycleCredit;
    }

    /**
     * Sets the current cycle credit amount.
     *
     * @param currentCycleCredit the current cycle credit (scale 2)
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit = currentCycleCredit;
    }

    /**
     * Returns the current cycle debit amount.
     *
     * @return the current cycle debit
     */
    public BigDecimal getCurrentCycleDebit() {
        return currentCycleDebit;
    }

    /**
     * Sets the current cycle debit amount.
     *
     * @param currentCycleDebit the current cycle debit (scale 2)
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit = currentCycleDebit;
    }

    /**
     * Returns the address ZIP code.
     *
     * @return the address ZIP
     */
    public String getAddressZip() {
        return addressZip;
    }

    /**
     * Sets the address ZIP code.
     *
     * @param addressZip the address ZIP
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = addressZip;
    }

    /**
     * Returns the account group identifier.
     *
     * @return the group id
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Sets the account group identifier.
     *
     * @param groupId the group id
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * Returns the optimistic-locking version.
     *
     * @return the version
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version.
     *
     * <p><strong>Persistence/test plumbing only.</strong> This setter exists so JPA/Hibernate can
     * hydrate the field on load and so tests can construct entities in a known state; the
     * {@code version} value is otherwise owned and incremented by the persistence provider. The
     * service layer MUST NOT implement conflict detection by assigning a stale value to a managed
     * entity's version — mutating a managed instance's version is not a reliable optimistic lock.
     * Instead the service must explicitly compare the client-supplied request version against the
     * loaded entity's version (or use a proven detached/merge pattern) and surface a mismatch as
     * HTTP 409 (see {@link #version}).</p>
     *
     * @param version the version
     */
    public void setVersion(Long version) {
        this.version = version;
    }
}
