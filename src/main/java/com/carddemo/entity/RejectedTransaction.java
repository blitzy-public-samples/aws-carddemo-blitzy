package com.carddemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entity for rejected transaction records (DALYREJS).
 *
 * <p>Composed of:</p>
 * <ul>
 *   <li>The 350-byte {@code DALYTRAN-RECORD} business fields from
 *       {@code app/cpy/CVTRA06Y.cpy} (12 user fields; the trailing
 *       {@code FILLER PIC X(20)} is intentionally not represented in Java), AND</li>
 *   <li>An 80-byte DALYREJS rejection metadata tail consisting of
 *       {@code validationCode} (codes 100/101/102/103) and {@code rejectionReason}
 *       (human-readable message). Total: 430 bytes equivalent.</li>
 * </ul>
 *
 * <p>Rows are written by the POSTTRAN reject sink (a {@code CompositeItemWriter},
 * AAP &sect;0.6.6) whenever the {@code CBTRN02C} validation chain fails for a daily
 * transaction. The table carries no foreign keys — it is deliberately denormalized
 * to mirror the original rejected PS file.</p>
 *
 * <p><strong>Validation codes preserved exactly per PR-03</strong>
 * [app/cbl/CBTRN02C.cbl:L370-L422]:</p>
 * <ul>
 *   <li>100 &mdash; "INVALID CARD NUMBER FOUND" (cross-reference miss)</li>
 *   <li>101 &mdash; Account not found (account record missing)</li>
 *   <li>102 &mdash; "OVERLIMIT TRANSACTION" (credit limit exceeded &mdash; PR-04)</li>
 *   <li>103 &mdash; "TRANSACTION RECEIVED AFTER ACCT EXPIRATION" (PR-05)</li>
 * </ul>
 *
 * <p>Uses a synthetic auto-generated primary key ({@code id}) rather than the feed
 * transaction id ({@code dalytranId}) because the same logical transaction could be
 * re-submitted and re-rejected on subsequent processing cycles; each rejection event
 * gets its own row.</p>
 *
 * <p>No {@code @Version} field is declared &mdash; rejected rows are immutable audit
 * records once logged (per AAP &sect;0.3.3 optimistic locking is mandated only for
 * {@code Account}, {@code Card}, {@code Customer}, and {@code Transaction}).
 * Identity-based {@link #equals(Object)}/{@link #hashCode()} derive from the synthetic
 * {@code id} alone.</p>
 *
 * <p><strong>Schema conformance.</strong> Every {@code @Column} mapping below mirrors
 * the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code rejected_transactions}). This is mandatory: the {@code dev}, {@code prod},
 * and {@code test} profiles all run Hibernate with {@code ddl-auto: validate}, so any
 * divergence between this entity's column names/types and the Flyway-managed schema
 * would cause the Spring context to fail to start. Column lengths/precision follow the
 * COBOL {@code PIC} clauses (PR-13); the monetary {@code amount} field is a
 * {@link BigDecimal} (PR-16) &mdash; never a float/double. The {@code orig}/{@code proc}
 * timestamps and the audit timestamp are mapped as {@link LocalDateTime} to match the
 * {@code TIMESTAMP} columns, consistent with the sibling {@link DailyTransaction}
 * staging entity so the reject sink can copy fields directly.</p>
 *
 * @see DailyTransaction
 */
@Entity
@Table(name = "rejected_transactions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectedTransaction {

    /**
     * Synthetic primary key (database-generated). Maps the {@code rejected_id BIGSERIAL}
     * column. {@code dalytranId} is preserved as an ordinary column rather than the PK
     * because the same logical rejection could re-occur on subsequent retry cycles, so
     * the feed transaction id is not unique across reject events.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rejected_id")
    private Long id;

    /**
     * Original {@code DALYTRAN-ID PIC X(16)} from the rejected feed row. Mapped to the
     * {@code tran_id VARCHAR(16)} column (not the primary key).
     */
    @Column(name = "tran_id", length = 16)
    private String dalytranId;

    /**
     * Maps COBOL {@code DALYTRAN-TYPE-CD PIC X(02)} &rarr; {@code type_cd CHAR(2)}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width CHAR binding so Hibernate
     * {@code ddl-auto: validate} matches the {@code CHAR(2)} column (a plain {@code String}
     * resolves to {@code VARCHAR} and would fail validation against the committed schema).
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    /**
     * Maps COBOL {@code DALYTRAN-CAT-CD PIC 9(04)} &rarr; {@code cat_cd CHAR(4)}. Stored
     * as a fixed-width character code (preserving any leading zeros) to match the
     * committed DDL, consistent with {@link DailyTransaction#getCategoryCd()}.
     * {@code @JdbcTypeCode(Types.CHAR)} forces the fixed-width CHAR binding so Hibernate
     * {@code ddl-auto: validate} matches the {@code CHAR(4)} column.
     */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "cat_cd", length = 4)
    private String categoryCd;

    /** Maps COBOL {@code DALYTRAN-SOURCE PIC X(10)} &rarr; {@code source VARCHAR(10)}. */
    @Column(name = "source", length = 10)
    private String source;

    /** Maps COBOL {@code DALYTRAN-DESC PIC X(100)} &rarr; {@code description VARCHAR(100)}. */
    @Column(name = "description", length = 100)
    private String description;

    /**
     * Maps COBOL {@code DALYTRAN-AMT PIC S9(09)V99} &rarr; {@code amount NUMERIC(15,2)}.
     * Exact fixed-point money value held as {@link BigDecimal} with scale 2 (PR-16);
     * float/double are forbidden for any monetary field.
     */
    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    /** Maps COBOL {@code DALYTRAN-MERCHANT-ID PIC 9(09)} &rarr; {@code merchant_id BIGINT}. */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** Maps COBOL {@code DALYTRAN-MERCHANT-NAME PIC X(50)} &rarr; {@code merchant_name VARCHAR(50)}. */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    /** Maps COBOL {@code DALYTRAN-MERCHANT-CITY PIC X(50)} &rarr; {@code merchant_city VARCHAR(50)}. */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    /** Maps COBOL {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} &rarr; {@code merchant_zip VARCHAR(10)}. */
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    /** Maps COBOL {@code DALYTRAN-CARD-NUM PIC X(16)} &rarr; {@code card_num VARCHAR(16)}. */
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * Maps COBOL {@code DALYTRAN-ORIG-TS PIC X(26)} &rarr; {@code orig_timestamp TIMESTAMP}.
     * The original 26-char DB2 external timestamp ({@code YYYY-MM-DD-HH.MM.SS.MIL0000})
     * is normalized to a {@link LocalDateTime}; the 26-char textual form is reproduced at
     * I/O boundaries via {@code DateConversionUtil} (PR-11).
     */
    @Column(name = "orig_timestamp")
    private LocalDateTime origTimestamp;

    /**
     * Maps COBOL {@code DALYTRAN-PROC-TS PIC X(26)} &rarr; {@code proc_timestamp TIMESTAMP}.
     * Held as {@link LocalDateTime}; see {@link #origTimestamp} for the format rationale.
     */
    @Column(name = "proc_timestamp")
    private LocalDateTime procTimestamp;

    /**
     * Validation code from {@code CBTRN02C 1500-VALIDATE-TRAN}: 100 (INVALID CARD),
     * 101 (account not found), 102 (OVERLIMIT), 103 (EXPIRED). Per PR-03. Mapped to the
     * {@code validation_code INTEGER NOT NULL} column.
     */
    @Column(name = "validation_code", nullable = false)
    private Integer validationCode;

    /**
     * Human-readable rejection message (e.g. "INVALID CARD NUMBER FOUND",
     * "OVERLIMIT TRANSACTION"). Part of the 80-byte DALYREJS metadata tail; mapped to the
     * {@code rejection_reason VARCHAR(80) NOT NULL} column.
     */
    @Column(name = "rejection_reason", length = 80, nullable = false)
    private String rejectionReason;

    /**
     * Audit timestamp recording when the rejection was logged (AAP &sect;0.6.12). Populated
     * once on initial persist by Spring Data's {@code AuditingEntityListener} via
     * {@code @CreatedDate}; immutable thereafter ({@code updatable = false}). Mapped to the
     * {@code rejected_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP} column, whose
     * database default also satisfies the NOT NULL constraint when auditing is inactive.
     */
    @CreatedDate
    @Column(name = "rejected_date", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // -------------------- equals / hashCode (synthetic-key identity) --------------------

    /**
     * Identity equality based solely on the synthetic {@link #id} primary key. Two
     * {@code RejectedTransaction} instances are equal only when both have a non-null,
     * equal {@code id}. Transient (unsaved) instances are therefore never equal to one
     * another, which is the correct behaviour for an append-only audit record.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RejectedTransaction)) {
            return false;
        }
        RejectedTransaction that = (RejectedTransaction) o;
        return id != null && id.equals(that.id);
    }

    /**
     * Hash code derived from the synthetic {@link #id} primary key (0 when {@code id} is
     * {@code null}), consistent with {@link #equals(Object)}.
     */
    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
