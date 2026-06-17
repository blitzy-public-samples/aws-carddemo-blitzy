package com.carddemo.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA entity mapping the legacy CardDemo <strong>account master</strong> to the relational
 * {@code accounts} table.
 *
 * <h2>Lineage</h2>
 * This entity is the Spring Boot / Hibernate re-expression of the mainframe VSAM KSDS dataset
 * {@code AWS.M2.CARDDEMO.ACCTDATA} (catalog key length&nbsp;11, see {@code app/catlg/LISTCAT.txt})
 * whose record layout is defined by copybook {@code app/cpy/CVACT01Y.cpy}
 * ({@code 01 ACCOUNT-RECORD}, RECLN&nbsp;300). Each persisted column below corresponds one-to-one
 * to an {@code ACCT-*} field of that copybook; the trailing {@code FILLER PIC X(178)} is reserved
 * mainframe padding and is deliberately <strong>not</strong> persisted.
 *
 * <h2>Type-mapping rules (COBOL &rarr; Java/SQL)</h2>
 * <ul>
 *   <li>{@code ACCT-ID PIC 9(11)} &rarr; {@link Long} / {@code BIGINT} (assigned natural key; the
 *       11-digit identifier fits comfortably in a 64-bit integer).</li>
 *   <li>Fixed-point money fields {@code PIC S9(10)V99} &rarr; {@link java.math.BigDecimal} persisted
 *       as {@code NUMERIC(12,2)}. {@code BigDecimal} is mandatory: {@code double}/{@code float} would
 *       lose the exact base-10 semantics of COBOL packed decimal. All arithmetic
 *       ({@link java.math.RoundingMode#HALF_UP}) is performed in the service layer, never here.</li>
 *   <li>Date fields {@code PIC X(10)} (ISO {@code yyyy-MM-dd} text on the mainframe) &rarr;
 *       {@link java.time.LocalDate} / {@code DATE}.</li>
 *   <li>Single-character flag {@code ACCT-ACTIVE-STATUS PIC X(01)} &rarr; {@link String} persisted as
 *       a fixed-width {@code CHAR(1)} via {@link JdbcTypeCode}{@code (}{@link SqlTypes#CHAR}{@code )}.</li>
 *   <li>Free-text fields {@code PIC X(10)} &rarr; {@link String} / {@code VARCHAR(10)}.</li>
 * </ul>
 *
 * <h2>Optimistic locking (AAP &sect;0.6.6)</h2>
 * The {@link #version} field is annotated {@link Version} and maps to the {@code version BIGINT NOT
 * NULL} column. It reproduces the legacy lost-update guard of the online account-update program
 * {@code COACTUPC}, which issued a CICS {@code READ ... UPDATE} followed by a {@code REWRITE} and
 * inspected the VSAM file-status / RESP code to detect a record that had changed underneath it
 * (see its {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} / {@code LOCKED-BUT-UPDATE-FAILED} conditions).
 * Hibernate increments {@code version} on every flush; a stale value raises
 * {@code jakarta.persistence.OptimisticLockException} /
 * {@code org.springframework.orm.ObjectOptimisticLockingFailureException}, which the service layer
 * and {@code GlobalExceptionHandler} translate to <strong>HTTP&nbsp;409 Conflict</strong>. The
 * {@code accounts} table is the <em>only</em> table carrying a {@code version} column, so
 * {@link Version} appears on this entity alone.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li><strong>Flat scalar mapping (no JPA associations).</strong> Related records
 *       ({@code Card}, {@code Transaction}, {@code CardXref}) reference this account by the scalar
 *       {@code acct_id} value plus database-level foreign keys; this entity intentionally declares
 *       <em>no</em> {@code @OneToMany}/{@code @ManyToOne} relationships, mirroring the independent
 *       VSAM datasets of the source system and keeping the aggregate boundary explicit.</li>
 *   <li><strong>Assigned identifier.</strong> {@code acctId} is a natural key supplied by the
 *       caller / seed data; there is no {@code @GeneratedValue}.</li>
 *   <li><strong>Schema authority.</strong> Hibernate runs with {@code ddl-auto=validate} against the
 *       Flyway-managed schema ({@code db/migration/V1__schema.sql}); the column names, lengths,
 *       precision and scale declared here must match that DDL exactly.</li>
 *   <li><strong>No PII on this entity.</strong> Sensitive data (card CVV, customer SSN) lives on the
 *       {@code Card} / {@code Customer} entities, not here, so {@link #toString()} may safely include
 *       every account field.</li>
 * </ul>
 *
 * <p>This is a {@code Tier-0} entity: it depends on no other {@code com.carddemo} type and imports
 * only JDK types plus the JPA / Hibernate mapping annotations.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * {@code CVACT01Y.ACCT-ID PIC 9(11)} &mdash; the unique account identifier and primary key.
     *
     * <p>Mapped to {@code acct_id BIGINT NOT NULL}. This is an <em>assigned</em> natural key: it is
     * provided by the caller or by seed data and is never database-generated, so no
     * {@code @GeneratedValue} is declared.</p>
     */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * {@code CVACT01Y.ACCT-ACTIVE-STATUS PIC X(01)} &mdash; single-character active flag
     * (typically {@code "Y"} / {@code "N"}).
     *
     * <p>Persisted as a fixed-width {@code CHAR(1)} column. {@link JdbcTypeCode}{@code (}
     * {@link SqlTypes#CHAR}{@code )} forces the {@code CHAR} JDBC type so the mapping matches the
     * {@code active_status CHAR(1)} DDL exactly under {@code ddl-auto=validate}.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    /**
     * {@code CVACT01Y.ACCT-CURR-BAL PIC S9(10)V99} &mdash; current outstanding balance.
     * Mapped to {@code curr_bal NUMERIC(12,2)}.
     */
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    /**
     * {@code CVACT01Y.ACCT-CREDIT-LIMIT PIC S9(10)V99} &mdash; total credit limit.
     * Mapped to {@code credit_limit NUMERIC(12,2)}.
     */
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * {@code CVACT01Y.ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} &mdash; cash-advance credit limit.
     * Mapped to {@code cash_credit_limit NUMERIC(12,2)}.
     */
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /**
     * {@code CVACT01Y.ACCT-OPEN-DATE PIC X(10)} &mdash; account open date.
     * Mapped to {@code open_date DATE}.
     */
    @Column(name = "open_date")
    private LocalDate openDate;

    /**
     * {@code CVACT01Y.ACCT-EXPIRAION-DATE PIC X(10)} &mdash; account expiration date.
     *
     * <p>The copybook field name preserves the original mainframe spelling typo
     * ("EXPIRAION"); the Java field and the {@code expiration_date} column use the corrected
     * spelling. Mapped to {@code expiration_date DATE}.</p>
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * {@code CVACT01Y.ACCT-REISSUE-DATE PIC X(10)} &mdash; account reissue date.
     * Mapped to {@code reissue_date DATE}.
     */
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    /**
     * {@code CVACT01Y.ACCT-CURR-CYC-CREDIT PIC S9(10)V99} &mdash; credits posted in the current
     * billing cycle. Mapped to {@code curr_cyc_credit NUMERIC(12,2)}.
     *
     * <p>Used by the daily transaction-posting overlimit check
     * ({@code WS-TEMP-BAL = cyc_credit - cyc_debit + amt}, AAP &sect;0.6.1).</p>
     */
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    /**
     * {@code CVACT01Y.ACCT-CURR-CYC-DEBIT PIC S9(10)V99} &mdash; debits posted in the current
     * billing cycle. Mapped to {@code curr_cyc_debit NUMERIC(12,2)}.
     *
     * <p>Used by the daily transaction-posting overlimit check
     * ({@code WS-TEMP-BAL = cyc_credit - cyc_debit + amt}, AAP &sect;0.6.1).</p>
     */
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    /**
     * {@code CVACT01Y.ACCT-ADDR-ZIP PIC X(10)} &mdash; account address ZIP code.
     * Mapped to {@code addr_zip VARCHAR(10)}.
     */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /**
     * {@code CVACT01Y.ACCT-GROUP-ID PIC X(10)} &mdash; disclosure / pricing group identifier used to
     * resolve interest rates against {@code disclosure_group}. May be blank on the mainframe, which
     * maps to {@code null} here. Mapped to {@code group_id VARCHAR(10)}.
     */
    @Column(name = "group_id", length = 10)
    private String groupId;

    /**
     * JPA optimistic-locking token (AAP &sect;0.6.6). Mapped to {@code version BIGINT NOT NULL}.
     *
     * <p>Managed by Hibernate: incremented on every update, checked on flush. A stale value triggers
     * an optimistic-locking failure that the application surfaces as HTTP&nbsp;409, reproducing the
     * {@code COACTUPC} {@code READ ... UPDATE} / {@code REWRITE} conflict guard. Initialized to
     * {@code 0L} to match the seed rows ({@code version = 0}) and to keep newly constructed,
     * not-yet-persisted instances in a well-defined state. This token is internal infrastructure and
     * must <strong>not</strong> be exposed as a client-writable API field.</p>
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    /**
     * Public no-argument constructor required by JPA / Hibernate for entity instantiation.
     */
    public Account() {
        // No-op: persistence provider populates fields via reflection; defaults applied at field level.
    }

    // ------------------------------------------------------------------------
    // Accessors / mutators
    // ------------------------------------------------------------------------

    /**
     * @return the account identifier ({@code ACCT-ID}); primary key.
     */
    public Long getAcctId() {
        return acctId;
    }

    /**
     * @param acctId the account identifier to assign ({@code ACCT-ID}); this is the natural
     *               primary key and is expected to be set before persistence.
     */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    /**
     * @return the single-character active status ({@code ACCT-ACTIVE-STATUS}).
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * @param activeStatus the single-character active status to set ({@code ACCT-ACTIVE-STATUS}).
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = activeStatus;
    }

    /**
     * @return the current balance ({@code ACCT-CURR-BAL}).
     */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /**
     * @param currBal the current balance to set ({@code ACCT-CURR-BAL}).
     */
    public void setCurrBal(BigDecimal currBal) {
        this.currBal = currBal;
    }

    /**
     * @return the total credit limit ({@code ACCT-CREDIT-LIMIT}).
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * @param creditLimit the total credit limit to set ({@code ACCT-CREDIT-LIMIT}).
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    /**
     * @return the cash-advance credit limit ({@code ACCT-CASH-CREDIT-LIMIT}).
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * @param cashCreditLimit the cash-advance credit limit to set ({@code ACCT-CASH-CREDIT-LIMIT}).
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit = cashCreditLimit;
    }

    /**
     * @return the account open date ({@code ACCT-OPEN-DATE}).
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * @param openDate the account open date to set ({@code ACCT-OPEN-DATE}).
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = openDate;
    }

    /**
     * @return the account expiration date ({@code ACCT-EXPIRAION-DATE}, corrected spelling).
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * @param expirationDate the account expiration date to set ({@code ACCT-EXPIRAION-DATE}).
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = expirationDate;
    }

    /**
     * @return the account reissue date ({@code ACCT-REISSUE-DATE}).
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * @param reissueDate the account reissue date to set ({@code ACCT-REISSUE-DATE}).
     */
    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = reissueDate;
    }

    /**
     * @return the current-cycle credit total ({@code ACCT-CURR-CYC-CREDIT}).
     */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /**
     * @param currCycCredit the current-cycle credit total to set ({@code ACCT-CURR-CYC-CREDIT}).
     */
    public void setCurrCycCredit(BigDecimal currCycCredit) {
        this.currCycCredit = currCycCredit;
    }

    /**
     * @return the current-cycle debit total ({@code ACCT-CURR-CYC-DEBIT}).
     */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /**
     * @param currCycDebit the current-cycle debit total to set ({@code ACCT-CURR-CYC-DEBIT}).
     */
    public void setCurrCycDebit(BigDecimal currCycDebit) {
        this.currCycDebit = currCycDebit;
    }

    /**
     * @return the account address ZIP code ({@code ACCT-ADDR-ZIP}).
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * @param addrZip the account address ZIP code to set ({@code ACCT-ADDR-ZIP}).
     */
    public void setAddrZip(String addrZip) {
        this.addrZip = addrZip;
    }

    /**
     * @return the disclosure / pricing group id ({@code ACCT-GROUP-ID}); may be {@code null}.
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * @param groupId the disclosure / pricing group id to set ({@code ACCT-GROUP-ID}).
     */
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * @return the optimistic-locking version token managed by Hibernate.
     */
    public Long getVersion() {
        return version;
    }

    /**
     * Sets the optimistic-locking version token.
     *
     * <p>Hibernate owns the lifecycle of this value (it assigns and increments it). The setter is
     * provided for the persistence provider and for tests that need to simulate a stale version to
     * exercise the HTTP&nbsp;409 conflict path. It is <strong>not</strong> a client-writable API
     * field and must not be populated from request payloads.</p>
     *
     * @param version the version token to set.
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    // ------------------------------------------------------------------------
    // Identity, equality, diagnostics
    // ------------------------------------------------------------------------

    /**
     * Equality is based solely on the natural primary key {@link #acctId}, consistent with the
     * single-key VSAM record identity of the source dataset.
     *
     * <p>Two {@code Account} instances are equal iff they are both {@code Account} objects with
     * equal, non-{@code null} {@code acctId} values. Instances with a {@code null} {@code acctId}
     * (e.g. transient, not-yet-assigned) are equal only by reference identity, avoiding the
     * "all transient entities collide" hazard.</p>
     *
     * @param o the object to compare with.
     * @return {@code true} if {@code o} is an {@code Account} with the same {@code acctId}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account)) {
            return false;
        }
        Account other = (Account) o;
        return acctId != null && acctId.equals(other.acctId);
    }

    /**
     * Hash code derived from the natural primary key {@link #acctId}.
     *
     * <p>Returns a constant for instances whose {@code acctId} is {@code null} so that an unsaved
     * entity retains a stable hash across the assignment of its identifier (important when the
     * entity is held in hash-based collections before persistence).</p>
     *
     * @return a hash code consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return acctId == null ? 0 : Objects.hashCode(acctId);
    }

    /**
     * Diagnostic string containing every account field. This entity carries no PII (card CVV and
     * customer SSN live on other entities), so all fields are safe to include in logs.
     *
     * @return a human-readable representation of this account.
     */
    @Override
    public String toString() {
        return "Account{"
                + "acctId=" + acctId
                + ", activeStatus='" + activeStatus + '\''
                + ", currBal=" + currBal
                + ", creditLimit=" + creditLimit
                + ", cashCreditLimit=" + cashCreditLimit
                + ", openDate=" + openDate
                + ", expirationDate=" + expirationDate
                + ", reissueDate=" + reissueDate
                + ", currCycCredit=" + currCycCredit
                + ", currCycDebit=" + currCycDebit
                + ", addrZip='" + addrZip + '\''
                + ", groupId='" + groupId + '\''
                + ", version=" + version
                + '}';
    }
}
