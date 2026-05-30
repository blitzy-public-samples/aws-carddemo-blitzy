package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.sql.Types;
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
 * JPA entity for posted transaction records.
 *
 * <p>Maps the 350-byte {@code TRAN-RECORD} from {@code app/cpy/CVTRA05Y.cpy}
 * (CardDemo_v1.0-15-g27d6c6f-68). The copybook declares 13 user fields plus a
 * trailing {@code FILLER PIC X(20)}; the filler carries no business meaning and is
 * intentionally not represented in Java.</p>
 *
 * <p>This is the system-of-record transaction table (as opposed to the
 * {@link DailyTransaction} staging mirror and the {@link RejectedTransaction}
 * reject sink, both derived from the sibling {@code DALYTRAN-RECORD} layout in
 * {@code CVTRA06Y.cpy}). Posted transactions are written by the POSTTRAN job
 * ({@code CBTRN02C}), the interest run ({@code CBACT04C}), online creation
 * ({@code POST /api/transactions}), and consolidation ({@code COMBTRAN}); they are
 * read by the transaction list/view flows ({@code COTRN00C}/{@code COTRN01C}) and
 * by statement generation ({@code CBSTM03A}).</p>
 *
 * <p><strong>Index (replaces VSAM AIX per AAP &sect;0.6.13):</strong></p>
 * <ul>
 *   <li>{@code idx_transaction_orig_ts} on {@code orig_timestamp} replaces VSAM
 *       {@code TRANSACT.AIX} (originally keyed on {@code TRAN-ORIG-TS}); it backs
 *       {@code TransactionRepository.findByOrigTimestampBetween(start, end)} used by
 *       transaction list views and by the consolidation/reporting batch jobs. The
 *       physical index is also created by Flyway {@code V2__indexes.sql}; declaring it
 *       here keeps the entity self-describing and is harmless under
 *       {@code ddl-auto: validate} (which validates tables and columns, not indexes).</li>
 * </ul>
 *
 * <p><strong>ID generation (PR-10):</strong> Transaction IDs are 16 characters formed
 * as {@code parmDate(10) + suffix(6)}, where {@code parmDate} comes from the batch
 * {@code JobParameter} and {@code suffix} is a sequential counter (a per-{@code JobExecution}
 * atomic counter in batch, the {@code transaction_id_seq} database sequence online). This
 * entity only enforces the 16-character length; generation lives in
 * {@code TransactionIdGenerator}.</p>
 *
 * <p><strong>Timestamps (PR-11).</strong> The COBOL {@code TRAN-ORIG-TS}/{@code TRAN-PROC-TS}
 * are 26-character DB2 external timestamps ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}). They are
 * stored here as native {@code TIMESTAMP} columns and modeled as {@link LocalDateTime}
 * (AAP &sect;0.6.5), consistent with the sibling {@link DailyTransaction}/{@link RejectedTransaction}
 * entities and required by {@code TransactionRepository.findByOrigTimestampBetween(LocalDateTime, LocalDateTime)}.
 * The 26-character textual DB2 form is reproduced only at I/O boundaries (file emission,
 * JSON serialization) via {@code DateConversionUtil} / {@code DateConversionService}, using
 * {@code DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSS'0000'")}.</p>
 *
 * <p><strong>Money (PR-16).</strong> {@code amount} maps {@code TRAN-AMT PIC S9(09)V99} to a
 * {@link BigDecimal} with scale 2 ({@code NUMERIC(15,2)}); {@code float}/{@code double} are
 * forbidden for any monetary value. Arithmetic on this field uses
 * {@code RoundingMode.HALF_UP} via {@code BigDecimalUtil}.</p>
 *
 * <p><strong>Optimistic locking (PR-22).</strong> {@code @Version version} replaces the VSAM
 * {@code READ UPDATE} exclusive-lock semantics with non-blocking optimistic concurrency; a
 * concurrent modification raises {@code OptimisticLockException} (mapped to HTTP 409). The
 * column is {@code INTEGER NOT NULL DEFAULT 0}, so the version is modeled as an
 * {@link Integer}.</p>
 *
 * <p><strong>Auditing (AAP &sect;0.6.12).</strong> {@code @CreatedDate}/{@code @LastModifiedDate}/
 * {@code @CreatedBy}/{@code @LastModifiedBy} are populated by Spring Data's
 * {@code AuditingEntityListener} when {@code @EnableJpaAuditing} is active.</p>
 *
 * <p><strong>Schema conformance.</strong> Every {@code @Column} mapping below mirrors the
 * committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql} (table
 * {@code transactions}). This is mandatory: the {@code dev}, {@code prod}, and {@code test}
 * profiles all run Hibernate with {@code ddl-auto: validate}, so any divergence in column
 * name, SQL type, or length would cause the Spring context to fail to start. In particular:</p>
 * <ul>
 *   <li>{@code type_cd CHAR(2)} and {@code cat_cd CHAR(4)} are fixed-width {@code CHAR}
 *       columns; their {@code String} fields therefore carry {@code @JdbcTypeCode(Types.CHAR)}
 *       so Hibernate binds them as {@code CHAR} (a plain {@code String} resolves to
 *       {@code VARCHAR} and would fail validation). {@code categoryCd} is held as a
 *       {@code String} to preserve any leading zeros of the COBOL {@code PIC 9(04)} category
 *       code, consistent with {@link DailyTransaction} and {@link RejectedTransaction}.</li>
 *   <li>The audit fields keep the canonical Java names {@code createdAt}/{@code updatedAt}/
 *       {@code createdBy}/{@code updatedBy} but map to the DDL column names
 *       {@code created_date}/{@code last_modified_date}/{@code created_by}/{@code last_modified_by}.</li>
 * </ul>
 *
 * <p>Identity-based {@link #equals(Object)}/{@link #hashCode()} derive from the natural
 * primary key {@code tranId} alone ({@code tran_id VARCHAR(16) NOT NULL PRIMARY KEY}).</p>
 *
 * @see DailyTransaction
 * @see RejectedTransaction
 */
@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transaction_orig_ts", columnList = "orig_timestamp")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    /**
     * Maps COBOL {@code TRAN-ID PIC X(16)} &rarr; {@code tran_id VARCHAR(16) NOT NULL PRIMARY KEY}.
     * The natural 16-character primary key, generated as {@code parmDate(10) + suffix(6)} per
     * PR-10 (by {@code TransactionIdGenerator}); this entity enforces only the length.
     */
    @Id
    @Column(name = "tran_id", length = 16, nullable = false)
    private String tranId;

    /**
     * Maps COBOL {@code TRAN-TYPE-CD PIC X(02)} &rarr; {@code type_cd CHAR(2) NOT NULL}.
     * Foreign-key-by-value to {@code transaction_types.tran_type}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width CHAR binding so Hibernate
     * {@code ddl-auto: validate} matches the {@code CHAR(2)} column (a plain {@code String}
     * resolves to {@code VARCHAR} and would fail validation against the committed schema).
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "type_cd", length = 2, nullable = false)
    private String typeCd;

    /**
     * Maps COBOL {@code TRAN-CAT-CD PIC 9(04)} &rarr; {@code cat_cd CHAR(4) NOT NULL}. Stored as
     * a fixed-width character code (preserving leading zeros, e.g. {@code "0001"}) to match the
     * committed DDL and the sibling {@link DailyTransaction}/{@link RejectedTransaction} entities;
     * together with {@link #typeCd} it forms the value reference to
     * {@code transaction_categories (type_cd, cat_cd)}. {@code @JdbcTypeCode(Types.CHAR)} forces
     * the fixed-width CHAR binding required by {@code ddl-auto: validate}.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "cat_cd", length = 4, nullable = false)
    private String categoryCd;

    /** Maps COBOL {@code TRAN-SOURCE PIC X(10)} &rarr; {@code source VARCHAR(10)}. */
    @Column(name = "source", length = 10)
    private String source;

    /**
     * Maps COBOL {@code TRAN-DESC PIC X(100)} &rarr; {@code description VARCHAR(100)}.
     * The longest VARCHAR field on this entity.
     */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Maps COBOL {@code TRAN-AMT PIC S9(09)V99} &rarr; {@code amount NUMERIC(15,2) NOT NULL}.
     * Exact fixed-point money value held as {@link BigDecimal} with scale 2 (PR-16);
     * {@code float}/{@code double} are forbidden for any monetary field. Arithmetic uses
     * {@code RoundingMode.HALF_UP} via {@code BigDecimalUtil}.
     */
    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /** Maps COBOL {@code TRAN-MERCHANT-ID PIC 9(09)} &rarr; {@code merchant_id BIGINT}. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Maps COBOL {@code TRAN-MERCHANT-NAME PIC X(50)} &rarr; {@code merchant_name VARCHAR(50)}. */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /** Maps COBOL {@code TRAN-MERCHANT-CITY PIC X(50)} &rarr; {@code merchant_city VARCHAR(50)}. */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /** Maps COBOL {@code TRAN-MERCHANT-ZIP PIC X(10)} &rarr; {@code merchant_zip VARCHAR(10)}. */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /**
     * Maps COBOL {@code TRAN-CARD-NUM PIC X(16)} &rarr; {@code card_num VARCHAR(16) NOT NULL}.
     * Foreign key to {@code cards.card_num}; the card whose activity produced this transaction.
     */
    @Column(name = "card_num", length = 16, nullable = false)
    private String cardNum;

    /**
     * Maps COBOL {@code TRAN-ORIG-TS PIC X(26)} &rarr; {@code orig_timestamp TIMESTAMP}.
     * The original 26-char DB2 external timestamp ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}) is
     * normalized to a {@link LocalDateTime} (AAP &sect;0.6.5); the 26-char textual form is
     * reproduced at I/O boundaries via {@code DateConversionUtil} (PR-11). Indexed by
     * {@code idx_transaction_orig_ts} (replaces VSAM {@code TRANSACT.AIX}).
     */
    @Column(name = "orig_timestamp")
    private LocalDateTime origTimestamp;

    /**
     * Maps COBOL {@code TRAN-PROC-TS PIC X(26)} &rarr; {@code proc_timestamp TIMESTAMP}.
     * Held as {@link LocalDateTime}; see {@link #origTimestamp} for the DB2-format rationale.
     */
    @Column(name = "proc_timestamp")
    private LocalDateTime procTimestamp;

    /**
     * Optimistic-locking version (PR-22). Maps {@code version INTEGER NOT NULL DEFAULT 0};
     * managed by Hibernate. Modeled as {@link Integer} to match the {@code INTEGER} column.
     */
    @Version
    @Column(name = "version")
    private Integer version;

    /**
     * Audit timestamp set once on initial persist by Spring Data's
     * {@code AuditingEntityListener} via {@code @CreatedDate} (active when
     * {@code @EnableJpaAuditing} is present); immutable thereafter ({@code updatable = false}).
     * Maps to the {@code created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} column
     * (AAP &sect;0.6.12).
     */
    @CreatedDate
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Audit timestamp refreshed on every update by Spring Data's
     * {@code AuditingEntityListener} via {@code @LastModifiedDate}. Maps to the
     * {@code last_modified_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} column
     * (AAP &sect;0.6.12).
     */
    @LastModifiedDate
    @Column(name = "last_modified_date")
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
     * Identity equality based solely on the natural primary key {@link #tranId}. Two
     * {@code Transaction} instances are equal only when both have a non-null, equal
     * {@code tranId}. Transient (unsaved) instances with a {@code null} id are therefore
     * never equal to one another.
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
        return tranId != null && tranId.equals(that.tranId);
    }

    /**
     * Hash code derived from the natural primary key {@link #tranId} (0 when {@code tranId}
     * is {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return tranId != null ? tranId.hashCode() : 0;
    }
}
