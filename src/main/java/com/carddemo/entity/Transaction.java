package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA entity mapping the posted-transaction record of the legacy AWS CardDemo
 * application to the relational {@code transactions} table.
 *
 * <h2>Source of truth</h2>
 * <p>This entity is the Java re-expression of the COBOL copybook
 * {@code app/cpy/CVTRA05Y.cpy} ({@code TRAN-RECORD}, record length 350), which
 * defined the layout of the VSAM {@code TRANSACT} KSDS. A posted transaction is
 * produced by four legacy programs that this migration preserves with full
 * functional parity:</p>
 * <ul>
 *   <li>{@code COTRN02C} &mdash; online "add transaction" (transaction id generated
 *       and left-padded to 16 characters by {@code util/TranIdGenerator});</li>
 *   <li>{@code CBTRN02C} &mdash; daily transaction posting batch (carries the
 *       incoming {@code DALYTRAN-ID} through verbatim and stamps {@code proc_ts}
 *       at posting time);</li>
 *   <li>{@code CBACT04C} &mdash; interest accrual batch (writes one interest
 *       transaction per category balance);</li>
 *   <li>{@code COBIL00C} &mdash; bill payment.</li>
 * </ul>
 *
 * <h2>Binding schema contract</h2>
 * <p>Hibernate runs with {@code spring.jpa.hibernate.ddl-auto=validate}; Flyway
 * (migration {@code V1__schema.sql}) owns the schema. Every column below maps
 * <strong>exactly</strong> to the {@code transactions} table definition &mdash;
 * note the column names are {@code source}, {@code description} and {@code amt}
 * (not {@code tran_source}/{@code tran_desc}/{@code tran_amt}). The table is
 * created empty and is never seeded.</p>
 *
 * <h2>Parity-critical design decisions</h2>
 * <ul>
 *   <li><b>Dual timestamps.</b> {@code CVTRA05Y} carries BOTH
 *       {@code TRAN-ORIG-TS X(26)} and {@code TRAN-PROC-TS X(26)}; both are
 *       persisted as distinct {@link LocalDateTime} columns ({@code orig_ts},
 *       {@code proc_ts}) and must never be collapsed into one (AAP &sect;0.7.3 #10).</li>
 *   <li><b>Denormalized account id.</b> {@code acctId} is not present in the
 *       copybook; it is added to back {@code TransactionRepository
 *       .findByAcctIdOrderByOrigTs(Long, Pageable)} and the composite index
 *       {@code idx_transactions_acct_id_orig_ts (acct_id, orig_ts)} that replaces
 *       the legacy {@code TRANSACT.AIX} alternate index.</li>
 *   <li><b>Externally assigned id.</b> {@code tranId} is the primary key and is
 *       assigned by the application (online add) or carried from the daily feed
 *       (batch). There is therefore <em>no</em> {@code @GeneratedValue} /
 *       {@code @SequenceGenerator} on this entity; the {@code transaction_id_seq}
 *       sequence is consumed solely by {@code util/TranIdGenerator}.</li>
 *   <li><b>{@code CHAR(2)} transaction type.</b> {@code typeCd} is annotated with
 *       {@code @JdbcTypeCode(SqlTypes.CHAR)} so Hibernate binds it as a fixed-width
 *       {@code CHAR(2)} column rather than a {@code VARCHAR}, matching the DDL.</li>
 *   <li><b>Fixed-point money.</b> {@code amt} is a {@link BigDecimal} of
 *       {@code precision = 12, scale = 2} ({@code NUMERIC(12,2)}), a safe superset
 *       of the source {@code S9(09)V99}; arithmetic in the service layer uses
 *       {@code RoundingMode.HALF_UP} to mirror COBOL fixed-point semantics.</li>
 *   <li><b>Flat (scalar) foreign keys.</b> {@code cardNum}, {@code acctId} and the
 *       {@code typeCd}/{@code catCd} pair are plain scalar columns &mdash; not JPA
 *       associations. The three database foreign keys ({@code fk_tran_card},
 *       {@code fk_tran_acct}, {@code fk_tran_cat}) enforce referential integrity,
 *       and the flat shape matches the derived repository queries.</li>
 * </ul>
 *
 * <p>The trailing {@code FILLER X(20)} of the copybook is intentionally not
 * persisted. This entity carries no sensitive data (no CVV, SSN or password), so
 * {@link #toString()} may include every field.</p>
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    /**
     * Transaction identifier &mdash; primary key.
     * <p>COBOL {@code TRAN-ID PIC X(16)} &rarr; {@code tran_id VARCHAR(16) NOT NULL}.
     * Assigned externally (see class Javadoc); no {@code @GeneratedValue}.</p>
     */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    /**
     * Transaction type code, part of the composite foreign key into
     * {@code transaction_category (type_cd, cat_cd)}.
     * <p>COBOL {@code TRAN-TYPE-CD PIC X(02)} &rarr; {@code type_cd CHAR(2)}. The
     * {@code @JdbcTypeCode(SqlTypes.CHAR)} forces fixed-width {@code CHAR} binding.</p>
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    /**
     * Transaction category code, part of the composite foreign key into
     * {@code transaction_category (type_cd, cat_cd)}.
     * <p>COBOL {@code TRAN-CAT-CD PIC 9(04)} &rarr; {@code cat_cd INTEGER}.</p>
     */
    @Column(name = "cat_cd")
    private Integer catCd;

    /**
     * Origin/source of the transaction.
     * <p>COBOL {@code TRAN-SOURCE PIC X(10)} &rarr; {@code source VARCHAR(10)}.
     * The column is literally named {@code source} (non-reserved in H2 and
     * PostgreSQL 15).</p>
     */
    @Column(name = "source", length = 10)
    private String source;

    /**
     * Free-text transaction description.
     * <p>COBOL {@code TRAN-DESC PIC X(100)} &rarr; {@code description VARCHAR(100)}.</p>
     */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Transaction amount (monetary).
     * <p>COBOL {@code TRAN-AMT PIC S9(09)V99} &rarr; {@code amt NUMERIC(12,2)}.</p>
     */
    @Column(name = "amt", precision = 12, scale = 2)
    private BigDecimal amt;

    /**
     * Merchant identifier.
     * <p>COBOL {@code TRAN-MERCHANT-ID PIC 9(09)} &rarr; {@code merchant_id BIGINT}.</p>
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    /**
     * Merchant name.
     * <p>COBOL {@code TRAN-MERCHANT-NAME PIC X(50)} &rarr; {@code merchant_name VARCHAR(50)}.</p>
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /**
     * Merchant city.
     * <p>COBOL {@code TRAN-MERCHANT-CITY PIC X(50)} &rarr; {@code merchant_city VARCHAR(50)}.</p>
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /**
     * Merchant postal code.
     * <p>COBOL {@code TRAN-MERCHANT-ZIP PIC X(10)} &rarr; {@code merchant_zip VARCHAR(10)}.</p>
     */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Card number that originated the transaction &mdash; scalar foreign key into
     * {@code cards (card_num)} ({@code fk_tran_card}).
     * <p>COBOL {@code TRAN-CARD-NUM PIC X(16)} &rarr; {@code card_num VARCHAR(16)}.</p>
     */
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * Origination timestamp &mdash; when the transaction entered the system.
     * <p>COBOL {@code TRAN-ORIG-TS PIC X(26)} &rarr; {@code orig_ts TIMESTAMP}.
     * Persisted distinctly from {@link #procTs} (AAP &sect;0.7.3 #10) and backs the
     * composite index {@code (acct_id, orig_ts)}.</p>
     */
    @Column(name = "orig_ts")
    private LocalDateTime origTs;

    /**
     * Processing timestamp &mdash; set when the transaction is posted by the daily
     * posting batch ({@code CBTRN02C}).
     * <p>COBOL {@code TRAN-PROC-TS PIC X(26)} &rarr; {@code proc_ts TIMESTAMP}.
     * Persisted distinctly from {@link #origTs}; the two timestamps are never
     * collapsed into one.</p>
     */
    @Column(name = "proc_ts")
    private LocalDateTime procTs;

    /**
     * Denormalized owning account id &mdash; scalar foreign key into
     * {@code accounts (acct_id)} ({@code fk_tran_acct}).
     * <p>Not present in {@code CVTRA05Y}; added to support the paginated
     * transaction-by-account browse ({@code findByAcctIdOrderByOrigTs}) and the
     * composite index {@code idx_transactions_acct_id_orig_ts}.</p>
     */
    @Column(name = "acct_id")
    private Long acctId;

    /**
     * Protected no-argument constructor required by the JPA specification for
     * entity instantiation. Application code builds instances via the setters.
     */
    public Transaction() {
        // No-args constructor required by JPA / Hibernate.
    }

    // ---------------------------------------------------------------------
    // Accessors (JavaBean getters / setters for all 14 mapped fields).
    // ---------------------------------------------------------------------

    /** @return the 16-character transaction identifier (primary key). */
    public String getTranId() {
        return tranId;
    }

    /** @param tranId the externally assigned 16-character transaction identifier. */
    public void setTranId(String tranId) {
        this.tranId = tranId;
    }

    /** @return the 2-character transaction type code. */
    public String getTypeCd() {
        return typeCd;
    }

    /** @param typeCd the 2-character transaction type code. */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /** @return the transaction category code. */
    public Integer getCatCd() {
        return catCd;
    }

    /** @param catCd the transaction category code. */
    public void setCatCd(Integer catCd) {
        this.catCd = catCd;
    }

    /** @return the transaction source. */
    public String getSource() {
        return source;
    }

    /** @param source the transaction source. */
    public void setSource(String source) {
        this.source = source;
    }

    /** @return the free-text transaction description. */
    public String getDescription() {
        return description;
    }

    /** @param description the free-text transaction description. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** @return the monetary transaction amount ({@code NUMERIC(12,2)}). */
    public BigDecimal getAmt() {
        return amt;
    }

    /** @param amt the monetary transaction amount ({@code NUMERIC(12,2)}). */
    public void setAmt(BigDecimal amt) {
        this.amt = amt;
    }

    /** @return the merchant identifier. */
    public Long getMerchantId() {
        return merchantId;
    }

    /** @param merchantId the merchant identifier. */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /** @return the merchant name. */
    public String getMerchantName() {
        return merchantName;
    }

    /** @param merchantName the merchant name. */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /** @return the merchant city. */
    public String getMerchantCity() {
        return merchantCity;
    }

    /** @param merchantCity the merchant city. */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /** @return the merchant postal code. */
    public String getMerchantZip() {
        return merchantZip;
    }

    /** @param merchantZip the merchant postal code. */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /** @return the originating card number (scalar foreign key into {@code cards}). */
    public String getCardNum() {
        return cardNum;
    }

    /** @param cardNum the originating card number (scalar foreign key into {@code cards}). */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /** @return the origination timestamp. */
    public LocalDateTime getOrigTs() {
        return origTs;
    }

    /** @param origTs the origination timestamp. */
    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    /** @return the processing (posting) timestamp. */
    public LocalDateTime getProcTs() {
        return procTs;
    }

    /** @param procTs the processing (posting) timestamp. */
    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /** @return the denormalized owning account id (scalar foreign key into {@code accounts}). */
    public Long getAcctId() {
        return acctId;
    }

    /** @param acctId the denormalized owning account id (scalar foreign key into {@code accounts}). */
    public void setAcctId(Long acctId) {
        this.acctId = acctId;
    }

    // ---------------------------------------------------------------------
    // Identity & diagnostics.
    // ---------------------------------------------------------------------

    /**
     * Entity equality is based solely on the primary key {@link #tranId}, which is
     * assigned before persistence and is stable for the lifetime of the row.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Transaction)) {
            return false;
        }
        Transaction that = (Transaction) o;
        return Objects.equals(tranId, that.tranId);
    }

    /** Hash code derived from the primary key {@link #tranId}. */
    @Override
    public int hashCode() {
        return Objects.hash(tranId);
    }

    /**
     * Full diagnostic representation. This entity holds no sensitive data (no CVV,
     * SSN or password), so every field may safely be included.
     */
    @Override
    public String toString() {
        return "Transaction{"
                + "tranId='" + tranId + '\''
                + ", typeCd='" + typeCd + '\''
                + ", catCd=" + catCd
                + ", source='" + source + '\''
                + ", description='" + description + '\''
                + ", amt=" + amt
                + ", merchantId=" + merchantId
                + ", merchantName='" + merchantName + '\''
                + ", merchantCity='" + merchantCity + '\''
                + ", merchantZip='" + merchantZip + '\''
                + ", cardNum='" + cardNum + '\''
                + ", origTs=" + origTs
                + ", procTs=" + procTs
                + ", acctId=" + acctId
                + '}';
    }
}
