package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for account master data.
 *
 * <p>Maps the 300-byte {@code ACCOUNT-RECORD} from {@code app/cpy/CVACT01Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68). The copybook declares 12 user fields plus a
 * trailing {@code FILLER PIC X(178)}; the filler carries no business meaning and is
 * intentionally not represented in Java (PR-13 maps only the named fields).</p>
 *
 * <p>This is a CORE master-data table. Rows are seeded by the data-initialization
 * load ({@code DataInitializationJobConfig}) from {@code app/data/ASCII/acctdata.txt};
 * they are read and maintained by the account view/update flows
 * ({@code COACTVWC}/{@code COACTUPC} &rarr; {@code AccountController}/{@code AccountService}),
 * referenced through the cross-reference junction ({@link CardXref}), and heavily exercised
 * by the critical batch programs described below.</p>
 *
 * <p><strong>Critical batch interactions (business-logic parity rules):</strong></p>
 * <ul>
 *   <li><strong>CBTRN02C 2800-UPDATE-ACCOUNT-REC</strong> [app/cbl/CBTRN02C.cbl:L545-L560] &mdash;
 *       PR-07 sign-based bucket: {@code amount >= 0} &rarr; {@code currCycCredit += amount};
 *       otherwise {@code currCycDebit += amount} (the debit is accumulated as a positive value,
 *       per the COBOL); in all cases {@code currBal += amount}.</li>
 *   <li><strong>CBACT04C 1050-UPDATE-ACCOUNT</strong> [app/cbl/CBACT04C.cbl:L350-L370] &mdash;
 *       PR-08 interest application: {@code currBal += WS-TOTAL-INT}; then reset both
 *       {@code currCycCredit = 0} and {@code currCycDebit = 0}; then REWRITE the record.</li>
 *   <li><strong>CBTRN02C 1500-VALIDATE-TRAN</strong> [app/cbl/CBTRN02C.cbl:L393-L422] &mdash;
 *       PR-04 credit-limit check: {@code creditLimit < (currCycCredit - currCycDebit + amount)}
 *       yields validation code 102 ("OVERLIMIT TRANSACTION"); PR-05 expiration check:
 *       {@code expirationDate < tranOrigTs[0..10]} yields validation code 103
 *       ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION"). All monetary comparisons use
 *       {@link BigDecimal#compareTo(BigDecimal)} (never {@code equals}) per PR-16.</li>
 *   <li><strong>CBACT04C 1200-GET-INTEREST-RATE</strong> [app/cbl/CBACT04C.cbl:L415-L440] &mdash;
 *       {@link #groupId} feeds the DISCGRP interest-rate lookup, which falls back to the
 *       {@code 'DEFAULT'} group when an exact match is missing (PR-02). The column is therefore
 *       an intentionally UNenforced reference to {@code disclosure_groups.group_id}.</li>
 * </ul>
 *
 * <p><strong>Note (PR-14).</strong> The COBOL field {@code ACCT-EXPIRAION-DATE}
 * [sic &mdash; misspelled in the copybook] is normalized to the correctly spelled Java field
 * {@code expirationDate} and the database column {@code expiration_date}, consistent with the
 * same normalization applied to {@code cards.expiration_date} (see {@link Card}).</p>
 *
 * <p><strong>Money (PR-16).</strong> The five monetary fields
 * ({@link #currBal}, {@link #creditLimit}, {@link #cashCreditLimit}, {@link #currCycCredit},
 * {@link #currCycDebit}) map the COBOL {@code PIC S9(10)V99} packed-decimal money fields to
 * {@link BigDecimal} with scale 2 ({@code NUMERIC(15,2)}). {@code float}/{@code double} are
 * forbidden for any monetary value; arithmetic uses {@code RoundingMode.HALF_UP} via
 * {@code BigDecimalUtil}.</p>
 *
 * <p><strong>Schema conformance (mandatory).</strong> Every {@code @Column} mapping below
 * mirrors the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code accounts}). The {@code dev}, {@code prod}, and {@code test} profiles all run
 * Hibernate with {@code ddl-auto: validate}, so any divergence in column name, SQL type, or
 * length would cause the Spring context to fail to start. In particular, the committed DDL
 * dictates the following Java types (which differ from a naive 1:1 string mapping of the COBOL
 * {@code PIC} clauses):</p>
 * <ul>
 *   <li>{@code active_status CHAR(1) NOT NULL} &rarr; {@link String} annotated with
 *       {@link JdbcTypeCode}{@code (Types.CHAR)} so Hibernate binds it as a fixed-width
 *       {@code CHAR} (a plain {@code String} resolves to {@code VARCHAR} and would fail
 *       validation against the committed schema), consistent with the {@code CHAR} mappings in
 *       {@link Card} and {@link Transaction}.</li>
 *   <li>{@code open_date}/{@code expiration_date}/{@code reissue_date DATE} &rarr;
 *       {@link LocalDate} (not {@code String}); the {@code PIC X(10)} text dates are normalized
 *       to SQL {@code DATE} columns, consistent with {@code cards.expiration_date}.</li>
 *   <li>{@code version INTEGER NOT NULL DEFAULT 0} &rarr; {@link Integer}.</li>
 *   <li>The audit fields keep the canonical Java names
 *       {@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy} but map to the
 *       DDL column names
 *       {@code created_date}/{@code last_modified_date}/{@code created_by}/{@code last_modified_by}.</li>
 * </ul>
 *
 * <p><strong>Optimistic locking (PR-22).</strong> {@code @Version version} replaces the VSAM
 * {@code READ UPDATE} exclusive-lock semantics with non-blocking optimistic concurrency; a
 * concurrent modification raises {@code OptimisticLockException} (mapped to HTTP 409). The
 * column is {@code INTEGER NOT NULL DEFAULT 0}, so the version is modeled as an
 * {@link Integer}.</p>
 *
 * <p><strong>Auditing (AAP &sect;0.6.12).</strong> {@code @CreatedDate}/{@code @LastModifiedDate}/
 * {@code @CreatedBy}/{@code @LastModifiedBy} are populated by Spring Data's
 * {@code AuditingEntityListener} (wired via {@link EntityListeners}) when
 * {@code @EnableJpaAuditing} is active &mdash; addressing the audit-log gap noted in the
 * original system.</p>
 *
 * <p>Persistence and auditing annotations use the {@code jakarta.*} namespace (PR-28).
 * Identity-based {@link #equals(Object)}/{@link #hashCode()} derive from the natural primary
 * key {@code acctId} alone ({@code acct_id BIGINT NOT NULL PRIMARY KEY}).</p>
 *
 * @see Card
 * @see CardXref
 * @see Transaction
 */
@Entity
@Table(name = "accounts")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account {

    /**
     * Maps COBOL {@code ACCT-ID PIC 9(11)} &rarr; {@code acct_id BIGINT NOT NULL PRIMARY KEY}.
     * The natural 11-digit account identifier; also the target of the foreign keys
     * {@code cards.account_id} and {@code card_xref.xref_acct_id}. Held as a {@link Long}
     * (an {@code 11}-digit value exceeds the range of {@code int}).
     */
    @Id
    @Column(name = "acct_id", nullable = false)
    private Long acctId;

    /**
     * Maps COBOL {@code ACCT-ACTIVE-STATUS PIC X(01)} &rarr; {@code active_status CHAR(1) NOT
     * NULL} (e.g. {@code "Y"}/{@code "N"}). {@code @JdbcTypeCode(Types.CHAR)} forces the
     * fixed-width {@code CHAR} binding required by {@code ddl-auto: validate} (a plain
     * {@code String} resolves to {@code VARCHAR} and would fail validation against the committed
     * schema), consistent with the {@code CHAR} mappings in {@link Card} and {@link Transaction}.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * Maps COBOL {@code ACCT-CURR-BAL PIC S9(10)V99} &rarr; {@code curr_bal NUMERIC(15,2) NOT
     * NULL}. The current account balance. Updated by CBTRN02C
     * ({@code currBal += DALYTRAN-AMT}, PR-07) and CBACT04C
     * ({@code currBal += WS-TOTAL-INT}, PR-08). {@link BigDecimal} scale 2 per PR-16.
     */
    @Column(name = "curr_bal", precision = 15, scale = 2, nullable = false)
    private BigDecimal currBal;

    /**
     * Maps COBOL {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} &rarr; {@code credit_limit
     * NUMERIC(15,2) NOT NULL}. The total credit line; used in the PR-04 over-limit check
     * {@code creditLimit < (currCycCredit - currCycDebit + amount)} (code 102).
     * {@link BigDecimal} scale 2 per PR-16.
     */
    @Column(name = "credit_limit", precision = 15, scale = 2, nullable = false)
    private BigDecimal creditLimit;

    /**
     * Maps COBOL {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} &rarr; {@code cash_credit_limit
     * NUMERIC(15,2) NOT NULL}. The cash-advance sub-limit. {@link BigDecimal} scale 2 per PR-16.
     */
    @Column(name = "cash_credit_limit", precision = 15, scale = 2, nullable = false)
    private BigDecimal cashCreditLimit;

    /**
     * Maps COBOL {@code ACCT-OPEN-DATE PIC X(10)} &rarr; {@code open_date DATE}. The account
     * open date; the 10-character text date is normalized to a SQL {@code DATE} / {@link LocalDate}.
     */
    @Column(name = "open_date")
    private LocalDate openDate;

    /**
     * Maps COBOL {@code ACCT-EXPIRAION-DATE} [sic] {@code PIC X(10)} &rarr;
     * {@code expiration_date DATE}. The COBOL field name is misspelled; the Java field corrects
     * it to {@code expirationDate} and the column is {@code expiration_date} (PR-14). Used by the
     * PR-05 expiration check ({@code expirationDate < tranOrigTs[0..10]}, code 103); the
     * downstream validator formats this {@link LocalDate} to its {@code yyyy-MM-dd} text form for
     * the COBOL substring comparison.
     */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /**
     * Maps COBOL {@code ACCT-REISSUE-DATE PIC X(10)} &rarr; {@code reissue_date DATE}. The most
     * recent card-reissue date; the 10-character text date is normalized to a SQL {@code DATE} /
     * {@link LocalDate}.
     */
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    /**
     * Maps COBOL {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} &rarr; {@code curr_cyc_credit
     * NUMERIC(15,2) NOT NULL}. Current-cycle credit total. Accumulated by the PR-07 sign-based
     * bucket (when {@code amount >= 0}) and reset to {@code 0} by CBACT04C interest application
     * (PR-08). {@link BigDecimal} scale 2 per PR-16.
     */
    @Column(name = "curr_cyc_credit", precision = 15, scale = 2, nullable = false)
    private BigDecimal currCycCredit;

    /**
     * Maps COBOL {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} &rarr; {@code curr_cyc_debit
     * NUMERIC(15,2) NOT NULL}. Current-cycle debit total. Accumulated by the PR-07 sign-based
     * bucket (when {@code amount < 0}, added as a positive value per the COBOL) and reset to
     * {@code 0} by CBACT04C interest application (PR-08). {@link BigDecimal} scale 2 per PR-16.
     */
    @Column(name = "curr_cyc_debit", precision = 15, scale = 2, nullable = false)
    private BigDecimal currCycDebit;

    /** Maps COBOL {@code ACCT-ADDR-ZIP PIC X(10)} &rarr; {@code addr_zip VARCHAR(10)}. */
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    /**
     * Maps COBOL {@code ACCT-GROUP-ID PIC X(10)} &rarr; {@code group_id VARCHAR(10)}. Drives the
     * CBACT04C DISCGRP interest-rate lookup with a {@code 'DEFAULT'} fallback (PR-02); an
     * intentionally UNenforced reference to {@code disclosure_groups.group_id}.
     */
    @Column(name = "group_id", length = 10)
    private String groupId;

    /**
     * Optimistic-locking version (PR-22). Maps {@code version INTEGER NOT NULL DEFAULT 0};
     * managed by Hibernate. Modeled as {@link Integer} to match the {@code INTEGER} column.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    /**
     * Audit timestamp set once on initial persist by Spring Data's
     * {@code AuditingEntityListener} via {@code @CreatedDate} (active when
     * {@code @EnableJpaAuditing} is present); immutable thereafter ({@code updatable = false}).
     * Maps to {@code created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp refreshed on every update by Spring Data's
     * {@code AuditingEntityListener} via {@code @LastModifiedDate}. Maps to
     * {@code last_modified_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} (AAP &sect;0.6.12).
     */
    @LastModifiedDate
    @Column(name = "last_modified_date", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Identity of the principal that created the record, populated once on persist via
     * {@code @CreatedBy} ({@code updatable = false}). Maps to {@code created_by VARCHAR(50)}.
     */
    @CreatedBy
    @Column(name = "created_by", length = 50, updatable = false)
    private String createdBy;

    /**
     * Identity of the principal that last modified the record, refreshed on update via
     * {@code @LastModifiedBy}. Maps to {@code last_modified_by VARCHAR(50)}.
     */
    @LastModifiedBy
    @Column(name = "last_modified_by", length = 50)
    private String updatedBy;

    // -------------------- equals / hashCode (natural-key identity) --------------------

    /**
     * Identity equality based solely on the natural primary key {@link #acctId}. Two
     * {@code Account} instances are equal only when both have a non-null, equal {@code acctId}.
     * Transient (unsaved) instances with a {@code null} account id are therefore never equal to
     * one another.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account)) {
            return false;
        }
        Account that = (Account) o;
        return acctId != null && acctId.equals(that.acctId);
    }

    /**
     * Hash code derived from the natural primary key {@link #acctId} (0 when {@code acctId} is
     * {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return acctId != null ? acctId.hashCode() : 0;
    }
}
