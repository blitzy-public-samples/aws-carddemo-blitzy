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
 * JPA entity for the daily transaction staging table.
 *
 * <p>Maps the 350-byte {@code DALYTRAN-RECORD} from {@code app/cpy/CVTRA06Y.cpy}
 * (13 user fields; the trailing {@code FILLER PIC X(20)} is intentionally not
 * represented in Java). This entity has the same business field layout as
 * {@link Transaction} but a different prefix and represents the <em>unprocessed</em>
 * daily transaction feed (the sequential PS file equivalent).</p>
 *
 * <p><strong>Staging strategy (AAP &sect;0.6.6):</strong></p>
 * <ol>
 *   <li>{@code DailyTransactionReadJobConfig} (CBTRN01C equivalent) reads
 *       {@code app/data/ASCII/dailytran.txt} via {@code FlatFileItemReader} +
 *       {@code FixedWidthRecordParser} and persists each row to
 *       {@code daily_transactions} with {@code processed = false} using the Lombok
 *       {@link #builder()}.</li>
 *   <li>{@code TransactionPostingJobConfig} (CBTRN02C equivalent) reads from this
 *       table via {@code JdbcCursorItemReader}; processes each row through
 *       {@code TransactionPostingProcessor} (validation codes 100/101/102/103,
 *       PR-03); accepted rows are marked {@code processed = true} so the chunk
 *       can be re-run idempotently after a restart.</li>
 *   <li>Rejected rows are inserted into {@link RejectedTransaction} with a
 *       {@code validationCode} and {@code rejectionReason}.</li>
 * </ol>
 *
 * <p><strong>Schema conformance.</strong> Every {@code @Column} mapping below mirrors
 * the committed Flyway DDL {@code src/main/resources/db/migration/V1__schema.sql}
 * (table {@code daily_transactions}). This is mandatory: the {@code dev}, {@code prod},
 * and {@code test} profiles all run Hibernate with {@code ddl-auto: validate}, so any
 * divergence between this entity's column names/types and the Flyway-managed schema
 * would cause the Spring context to fail to start. Consequently:</p>
 * <ul>
 *   <li>The primary key is a <strong>synthetic</strong> {@code daily_tran_id BIGSERIAL}
 *       surrogate ({@link #id}) — chosen by the schema to enable Spring Batch
 *       chunk-restart — rather than the feed transaction id. The original
 *       {@code DALYTRAN-ID PIC X(16)} is preserved as the ordinary, non-key
 *       {@code tran_id VARCHAR(16) NOT NULL} column ({@link #dalytranId}), mirroring
 *       the sibling {@link RejectedTransaction} which shares the same record layout.</li>
 *   <li>{@code type_cd} and {@code cat_cd} are fixed-width {@code CHAR} columns; the
 *       {@code String} fields therefore carry {@code @JdbcTypeCode(Types.CHAR)} so
 *       Hibernate binds them as {@code CHAR} (a plain {@code String} resolves to
 *       {@code VARCHAR} and would fail {@code ddl-auto: validate}).</li>
 *   <li>{@code categoryCd} is held as a {@code String} (preserving any leading zeros of
 *       the COBOL {@code PIC 9(04)} category code), consistent with
 *       {@link RejectedTransaction#getCategoryCd()}.</li>
 *   <li>The 26-character DB2 external timestamps {@code DALYTRAN-ORIG-TS} /
 *       {@code DALYTRAN-PROC-TS} ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}) are normalized to
 *       {@link LocalDateTime} {@code TIMESTAMP} columns; the 26-char textual form is
 *       reproduced at I/O boundaries via {@code DateConversionUtil} (PR-11). This keeps
 *       the field types identical to {@link RejectedTransaction} so the POSTTRAN reject
 *       sink can copy fields directly.</li>
 *   <li>Column lengths follow the COBOL {@code PIC} clauses (PR-13); the monetary
 *       {@code amount} field is a {@link BigDecimal} with scale 2 (PR-16) &mdash; never a
 *       {@code float}/{@code double}.</li>
 * </ul>
 *
 * <p>This table has NO {@code @Version} optimistic locking &mdash; staging rows are not
 * concurrently modified after insert (per AAP &sect;0.3.3 optimistic locking is mandated
 * only for {@code Account}, {@code Card}, {@code Customer}, and {@code Transaction}).
 * It also carries no foreign keys: it is deliberately denormalized to mirror the raw
 * PS feed so orphaned / invalid rows can be staged and then rejected.</p>
 *
 * <p>The {@code processed} boolean flag is a Java-only addition (not present in the COBOL
 * {@code DALYTRAN-RECORD}) supporting checkpoint/restart semantics. The {@code createdAt}
 * audit timestamp (AAP &sect;0.6.12) records when the feed row was first staged; it is
 * populated by Spring Data's {@code AuditingEntityListener} via {@code @CreatedDate} when
 * {@code @EnableJpaAuditing} is active, and is otherwise left {@code null} (the column is
 * nullable).</p>
 *
 * <p>Identity-based {@link #equals(Object)} / {@link #hashCode()} derive from the synthetic
 * {@link #id} primary key alone: the business {@code dalytranId} ({@code tran_id}) is
 * {@code NOT NULL} but not declared unique, so the same feed id could be re-staged across
 * processing cycles and is therefore unsafe as an identity basis.</p>
 *
 * @see Transaction
 * @see RejectedTransaction
 */
@Entity
@Table(name = "daily_transactions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyTransaction {

    /**
     * Synthetic primary key (database-generated). Maps the {@code daily_tran_id BIGSERIAL}
     * column. A surrogate key is used (rather than the feed {@code DALYTRAN-ID}) because the
     * schema documents it as enabling Spring Batch chunk-restart, and because the same
     * logical feed transaction could be re-staged on subsequent processing cycles.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_tran_id")
    private Long id;

    /**
     * Maps COBOL {@code DALYTRAN-ID PIC X(16)} &rarr; {@code tran_id VARCHAR(16) NOT NULL}.
     * The original feed transaction identifier; preserved as an ordinary (non-key) column.
     */
    @Column(name = "tran_id", length = 16, nullable = false)
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
     * Maps COBOL {@code DALYTRAN-CAT-CD PIC 9(04)} &rarr; {@code cat_cd CHAR(4)}. Stored as a
     * fixed-width character code (preserving any leading zeros) to match the committed DDL,
     * consistent with {@link RejectedTransaction#getCategoryCd()}.
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
     * {@code float}/{@code double} are forbidden for any monetary field. During posting this
     * amount feeds the PR-04 credit-limit check, the PR-06 TCATBAL upsert, and the PR-07
     * sign-based balance bucket.
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

    /**
     * Maps COBOL {@code DALYTRAN-CARD-NUM PIC X(16)} &rarr; {@code card_num VARCHAR(16)}.
     * Used by the {@code CBTRN02C 1500-A-LOOKUP-XREF} cross-reference lookup during posting.
     */
    @Column(name = "card_num", length = 16)
    private String cardNum;

    /**
     * Maps COBOL {@code DALYTRAN-ORIG-TS PIC X(26)} &rarr; {@code orig_timestamp TIMESTAMP}.
     * The original 26-char DB2 external timestamp ({@code YYYY-MM-DD-HH.MM.SS.MIL0000}) is
     * normalized to a {@link LocalDateTime}; the textual form is reproduced at I/O boundaries
     * via {@code DateConversionUtil} (PR-11). The PR-05 expiration check compares the first 10
     * characters ({@code yyyy-MM-dd}) of this timestamp against the account expiration date.
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
     * Staging flag &mdash; {@code false} until the row is posted by
     * {@code TransactionPostingJobConfig}; {@code true} after successful processing
     * (AAP &sect;0.6.6). This is a Java-only addition (not present in the COBOL
     * {@code DALYTRAN-RECORD}) that supports idempotent Spring Batch checkpoint/restart of
     * the posting job. Mapped to the {@code processed BOOLEAN NOT NULL} column;
     * {@code @Builder.Default} initializes new staging rows to {@code false}.
     */
    @Builder.Default
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    /**
     * Audit timestamp recording when the feed row was first staged into
     * {@code daily_transactions} (AAP &sect;0.6.12 staging-time tracking). Populated once on
     * initial persist by Spring Data's {@code AuditingEntityListener} via {@code @CreatedDate}
     * (active when {@code @EnableJpaAuditing} is present) and immutable thereafter
     * ({@code updatable = false}). Mapped to the nullable {@code created_at TIMESTAMP} column,
     * so a row inserted while auditing is inactive is simply left {@code null} rather than
     * violating a constraint.
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // -------------------- equals / hashCode (synthetic-key identity) --------------------

    /**
     * Identity equality based solely on the synthetic {@link #id} primary key. Two
     * {@code DailyTransaction} instances are equal only when both have a non-null, equal
     * {@code id}. Transient (unsaved) instances are therefore never equal to one another,
     * which is the correct behaviour for a generated-key staging entity. The business
     * {@code dalytranId} is not used because it is not declared unique and may repeat across
     * processing cycles.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DailyTransaction)) {
            return false;
        }
        DailyTransaction that = (DailyTransaction) o;
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
