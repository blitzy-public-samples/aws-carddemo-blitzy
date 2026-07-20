package com.aws.carddemo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity mapping the legacy VSAM {@code TRANSACT} KSDS onto the relational
 * {@code transaction} table as part of the AWS CardDemo COBOL-to-Java migration.
 *
 * <p><strong>Origin and traceability (AAP 0.6.10):</strong> migrated one-for-one from the COBOL
 * copybook {@code TRAN-RECORD} (record length 350) defined in {@code legacy/cpy/CVTRA05Y.cpy}
 * (retained read-only for reference). Each {@code 05}-level field of that copybook maps to exactly
 * one persisted column below; the trailing {@code FILLER PIC X(20)} is byte padding only and is
 * intentionally not persisted, so the 350-byte fixed-width record maps to the 13 columns declared
 * here.</p>
 *
 * <p><strong>Key semantics (AAP 0.6.2; review finding F7):</strong> the primary key is the
 * 16-character transaction id ({@code TRAN-ID} &rarr; column {@code tran_id}). The single legacy
 * VSAM alternate index over {@code TRANSACT} is defined by {@code legacy/jcl/TRANIDX.jcl} as
 * {@code KEYS(26 304) NONUNIQUEKEY} — that is, the 26-byte processing timestamp {@code TRAN-PROC-TS}
 * at offset 304 ("CREATE ALTERNATE INDEX ON PROCESSED TIMESTAMP"), <em>not</em> the card number. It
 * is therefore reproduced as a nonunique secondary index on {@code transaction(proc_ts)} (created in
 * the forthcoming {@code V3__indexes.sql}). Access by card number is a <em>separate functional
 * report/sort query optimization</em> (used by the transaction-report and card-list paths), surfaced
 * through the Spring Data derived query {@code TransactionRepository.findByCardNum(String)}; it does
 * not correspond to any legacy alternate index. To keep that derived query resolvable,
 * {@code TRAN-CARD-NUM} is deliberately exposed as the property {@code cardNum} (column
 * {@code card_num}) with the {@code TRAN-} prefix dropped; every other column retains its
 * {@code tran_} prefix.</p>
 *
 * <p><strong>Decimal and timestamp fidelity (AAP 0.6.1):</strong> the monetary amount
 * {@code TRAN-AMT PIC S9(09)V99} maps to {@link java.math.BigDecimal} with precision 11 and scale 2
 * ({@code NUMERIC(11,2)}); monetary values are never represented with binary floating-point types.
 * The two 26-character timestamp fields ({@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS}, formatted
 * {@code yyyy-MM-dd HH:mm:ss.SSSSSS}) map to {@link java.time.LocalDateTime}.</p>
 *
 * <p><strong>Insert semantics (AAP 0.6.5; review finding #32):</strong> the transaction id is an
 * <em>assigned</em> primary key (no {@code @GeneratedValue}), and transactions are an append-only
 * ledger &mdash; every write in the migrated system is an insert (online add {@code COTRN02C},
 * bill-pay {@code COBIL00C}, interest accrual {@code CBACT04C}, daily posting {@code CBTRN02C}); a
 * transaction row is never loaded and rewritten. Spring Data's {@code save(...)} would, for an
 * entity with a non-null assigned id and no {@code @Version}, take the {@code EntityManager.merge}
 * path, which does a {@code SELECT}-then-{@code UPDATE} and would therefore <em>silently overwrite</em>
 * an existing row on a duplicate id or a job rerun &mdash; masking the legacy {@code WRITE}
 * {@code DUPREC} (VSAM {@code FILE STATUS "22"} / CICS {@code DFHRESP(DUPKEY)}) that the COBOL relied
 * on. To reproduce that behaviour this entity implements {@link Persistable}: {@link #isNew()}
 * reports {@code true} for a freshly constructed instance, so {@code save(...)} issues a true
 * {@code EntityManager.persist} (an {@code INSERT}); a duplicate key then raises a
 * {@code DataIntegrityViolationException} that the write paths translate to the typed
 * {@code DuplicateKeyException} / roll the chunk back (batch abend parity). The flag is cleared by
 * the {@link PostPersist}/{@link PostLoad} callback so a managed instance behaves normally
 * thereafter.</p>
 */
@Entity
@Table(name = "transaction")
public class Transaction implements Persistable<String> {

    /**
     * Transient {@link Persistable#isNew()} flag (review finding #32). Defaults to {@code true} so a
     * freshly constructed {@link Transaction} forces {@code EntityManager.persist} (a true
     * {@code INSERT}); it is cleared by {@link #markNotNew()} after the row is persisted or loaded.
     * Marked {@link Transient} so it is never mapped to a column.
     */
    @Transient
    private boolean isNew = true;

    /** Transaction id &mdash; {@code TRAN-ID PIC X(16)}; primary key of the {@code TRANSACT} KSDS. */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_id", length = 16)
    private String tranId;

    /** Transaction type code &mdash; {@code TRAN-TYPE-CD PIC X(02)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_type_cd", length = 2)
    private String tranTypeCd;

    /**
     * Transaction category code &mdash; {@code TRAN-CAT-CD PIC 9(04)}. Mapped to SQL
     * {@code NUMERIC(4)} via {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) so Hibernate expects
     * {@code NUMERIC} rather than the default {@code INTEGER} (review finding F1).
     */
    @Column(name = "tran_cat_cd", precision = 4)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Integer tranCatCd;

    /** Transaction source &mdash; {@code TRAN-SOURCE PIC X(10)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_source", length = 10)
    private String tranSource;

    /** Transaction description &mdash; {@code TRAN-DESC PIC X(100)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_desc", length = 100)
    private String tranDesc;

    /**
     * Transaction amount &mdash; {@code TRAN-AMT PIC S9(09)V99}; maps to {@code NUMERIC(11,2)}.
     * Represented as {@link java.math.BigDecimal} (precision 11, scale 2); never floating point,
     * to preserve COBOL fixed-scale decimal arithmetic semantics.
     */
    @Column(name = "tran_amt", precision = 11, scale = 2)
    private BigDecimal tranAmt;

    /**
     * Merchant id &mdash; {@code TRAN-MERCHANT-ID PIC 9(09)}. Mapped to SQL {@code NUMERIC(9)} via
     * {@link JdbcTypeCode}({@link SqlTypes#NUMERIC}) so Hibernate expects {@code NUMERIC} rather than
     * the default {@code BIGINT} (review finding F1).
     */
    @Column(name = "tran_merchant_id", precision = 9)
    @JdbcTypeCode(SqlTypes.NUMERIC)
    private Long merchantId;

    /** Merchant name &mdash; {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_name", length = 50)
    private String merchantName;

    /** Merchant city &mdash; {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_city", length = 50)
    private String merchantCity;

    /** Merchant ZIP &mdash; {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tran_merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number &mdash; {@code TRAN-CARD-NUM PIC X(16)}. Exposed as {@code cardNum} / column
     * {@code card_num} (the {@code TRAN-} prefix is dropped) so the repository derived query
     * {@code findByCardNum(String)} resolves. This card-number access path is a functional
     * report/sort query optimization and does <em>not</em> correspond to the legacy VSAM alternate
     * index, which is on {@code TRAN-PROC-TS} (see the class-level key-semantics note; review finding
     * F7).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /** Original timestamp &mdash; {@code TRAN-ORIG-TS PIC X(26)}; {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. */
    @Column(name = "tran_orig_ts")
    private LocalDateTime origTs;

    /** Processing timestamp &mdash; {@code TRAN-PROC-TS PIC X(26)}; {@code yyyy-MM-dd HH:mm:ss.SSSSSS}. */
    @Column(name = "tran_proc_ts")
    private LocalDateTime procTs;

    /**
     * Creates an empty transaction. Required by the JPA specification for entity instantiation and
     * used by application code and the fixed-width record mapper when populating fields individually.
     */
    public Transaction() {
        // No-args constructor required by the JPA specification; intentionally empty.
    }

    public String getTranId() {
        return tranId;
    }

    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    public String getTranTypeCd() {
        return tranTypeCd;
    }

    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    public Integer getTranCatCd() {
        return tranCatCd;
    }

    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    public String getTranSource() {
        return tranSource;
    }

    public void setTranSource(String tranSource) {
        this.tranSource = tranSource;
    }

    public String getTranDesc() {
        return tranDesc;
    }

    public void setTranDesc(String tranDesc) {
        this.tranDesc = tranDesc;
    }

    public BigDecimal getTranAmt() {
        return tranAmt;
    }

    public void setTranAmt(BigDecimal tranAmt) {
        this.tranAmt = tranAmt;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getMerchantCity() {
        return merchantCity;
    }

    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    public String getMerchantZip() {
        return merchantZip;
    }

    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    public String getCardNum() {
        return cardNum;
    }

    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    public LocalDateTime getOrigTs() {
        return origTs;
    }

    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    public LocalDateTime getProcTs() {
        return procTs;
    }

    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /**
     * Two transactions are equal when they share the same non-null primary key ({@code tranId}),
     * matching the identity semantics of the underlying {@code TRANSACT} KSDS record key. Uses an
     * {@code instanceof} check so a Hibernate proxy compares equal to its underlying entity, and
     * treats an instance with a {@code null} id as not equal to any other instance (including other
     * unsaved instances), so distinct transient rows are never collapsed (review finding F10).
     *
     * @param o the object to compare with
     * @return {@code true} if {@code o} is a {@code Transaction} with an equal non-null {@code tranId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction that)) {
            return false;
        }
        return tranId != null && tranId.equals(that.tranId);
    }

    /**
     * Returns a constant, identity-stable hash code. A constant (rather than one derived from
     * {@code tranId}) is used so the hash does not change when the mutable primary key is assigned,
     * keeping instances locatable in hash-based collections and consistent with
     * {@link #equals(Object)} (review finding F10).
     *
     * @return a stable, class-level hash code
     */
    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }

    /**
     * The {@link Persistable} identifier &mdash; the transaction id ({@code TRAN-ID}).
     *
     * @return the primary key, or {@code null} before one is assigned
     */
    @Override
    public String getId() {
        return tranId;
    }

    /**
     * Reports whether this instance must be treated as a new row for the purposes of
     * {@code Spring Data} {@code save(...)} (review finding #32). Returns {@code true} for a freshly
     * constructed transaction so that {@code save(...)} performs a true {@code INSERT}
     * ({@code EntityManager.persist}) rather than a {@code merge}; a duplicate key therefore fails
     * loudly ({@code DataIntegrityViolationException} &rarr; {@code DuplicateKeyException} / batch
     * abend), reproducing the legacy {@code WRITE} {@code DUPREC} ({@code FILE STATUS "22"}) instead
     * of silently overwriting the existing ledger row. Cleared by {@link #markNotNew()} once the row
     * is persisted or loaded.
     *
     * @return {@code true} if this instance has not yet been persisted or loaded, otherwise {@code false}
     */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Clears the {@link #isNew} flag after the row has been inserted ({@link PostPersist}) or read
     * back from the database ({@link PostLoad}), so a managed {@link Transaction} is thereafter
     * treated as existing.
     */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    /**
     * Returns a non-sensitive diagnostic representation containing only the class name and an opaque
     * per-instance identity token. The card number (PAN), amount, merchant details, and every other
     * business field are deliberately never emitted — not even partially masked — so that payment
     * and transaction data cannot leak into logs or error messages (CWE-532; review finding F9).
     *
     * @return a non-sensitive string representation
     */
    @Override
    public String toString() {
        return "Transaction@" + Integer.toHexString(System.identityHashCode(this));
    }
}
