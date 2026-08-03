package com.carddemo.ledger.entity;

import static com.carddemo.cobol.PicClause.DALYTRAN_ID_WIDTH;
import static com.carddemo.cobol.PicClause.REJECT_TRAN_DATA_WIDTH;
import static com.carddemo.cobol.PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH;
import static com.carddemo.cobol.PicClause.VALIDATION_FAIL_REASON_WIDTH;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One row of the 430-byte reject record the batch posting program writes, mapped onto the
 * {@code rejected_transaction} table.
 *
 * <p>The source record is {@code 01 REJECT-RECORD}, declared in Common Business Oriented Language
 * (COBOL) working storage at {@code app/cbl/CBTRN02C.cbl:L176}. It carries 350 bytes of refused
 * transaction data in {@code REJECT-TRAN-DATA PIC X(350)} at L177 and an 80-byte
 * {@code VALIDATION-TRAILER PIC X(80)} at L178. The trailer holds
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at L181 and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at L182. Paragraph
 * {@code 2500-WRITE-REJECT-REC} at L446 fills both halves and writes them as one 430-byte record,
 * a total that appears again as {@code LRECL=430} at {@code app/jcl/POSTTRAN.jcl:L36}.</p>
 *
 * <p>The 350 characters stay whole and undecomposed. The thirteen fields inside them are laid out
 * by {@code 01 DALYTRAN-RECORD} at {@code app/cpy/CVTRA06Y.cpy:L4}, and a caller masks the card
 * number among them before it reaches this service.</p>
 *
 * <pre>
 * COBOL field                     Column
 * (additive)                      id
 * DALYTRAN-ID                     transaction_id
 * WS-VALIDATION-FAIL-REASON       reject_reason_code
 * WS-VALIDATION-FAIL-REASON-DESC  reject_reason_description
 * REJECT-TRAN-DATA                rejected_transaction_data
 * (additive)                      rejected_at
 * </pre>
 *
 * <p>Rationale for this mapping: {@code card-platform/docs/decision-log.md}.</p>
 */
@Entity
@Table(name = "rejected_transaction",
        indexes = @Index(name = "idx_rejected_transaction_transaction_id",
                columnList = "transaction_id"))
public class RejectedTransactionEntity {

    /**
     * Shape every {@code reject_reason_code} matches: four decimal digits.
     * {@code WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L181} is
     * {@code PIC 9(04)}, a fixed-width display numeric that keeps its leading zeros.
     */
    private static final Pattern REJECT_REASON_CODE_PATTERN = Pattern.compile("^[0-9]{4}$");

    /**
     * Declared type of {@code rejected_transaction_data}. PostgreSQL reports its fixed-width
     * character type as {@code bpchar}, and the {@code CHAR(350)} column in
     * {@code V1__schema.sql} carries that type. The width comes from
     * {@code REJECT-TRAN-DATA PIC X(350)} at {@code app/cbl/CBTRN02C.cbl:L177}.
     */
    private static final String REJECTED_TRANSACTION_DATA_COLUMN_TYPE =
            "bpchar(" + REJECT_TRAN_DATA_WIDTH + ")";

    /**
     * Identifier of this row, mapped onto the {@code id} column and the primary key constraint
     * {@code pk_rejected_transaction}. The caller supplies the value. The DALYREJS dataset at
     * {@code app/jcl/POSTTRAN.jcl:L34} is sequential and declares no {@code KEYS} parameter, so
     * no source field carries the value.
     */
    @Id
    private UUID id;

    /**
     * Identifier of the refused transaction, {@code DALYTRAN-ID PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L5}. One transaction identifier reaches this table more than
     * once across redeliveries, so no unique constraint covers the column.
     */
    @Column(name = "transaction_id", nullable = false, length = DALYTRAN_ID_WIDTH)
    private String transactionId;

    /**
     * Four zero-padded digits identifying the validation failure,
     * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181}. The
     * column holds whatever four digits the caller hands it.
     */
    @Column(name = "reject_reason_code", nullable = false, length = VALIDATION_FAIL_REASON_WIDTH)
    private String rejectReasonCode;

    /**
     * Text of the validation failure, {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}. The source moves literals such as
     * {@code OVERLIMIT TRANSACTION} into the field.
     */
    @Column(name = "reject_reason_description", nullable = false,
            length = VALIDATION_FAIL_REASON_DESC_WIDTH)
    private String rejectReasonDescription;

    /**
     * The refused transaction record held whole, {@code REJECT-TRAN-DATA PIC X(350)} at
     * {@code app/cbl/CBTRN02C.cbl:L177}. Trailing spaces belong to the value, and the column
     * preserves them.
     */
    @Column(name = "rejected_transaction_data", nullable = false, length = REJECT_TRAN_DATA_WIDTH,
            columnDefinition = REJECTED_TRANSACTION_DATA_COLUMN_TYPE)
    private String rejectedTransactionData;

    /**
     * Point in time the ledger recorded the refusal. The caller supplies the value, and no field
     * of the 430-byte source record carries it.
     */
    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    /** Constructor the Jakarta Persistence provider calls when it materialises a row. */
    protected RejectedTransactionEntity() {
    }

    /**
     * Holds the six values of one reject row.
     *
     * @param id                      identifier of the row
     * @param transactionId           identifier of the refused transaction, at most 16 characters
     * @param rejectReasonCode        four decimal digits identifying the validation failure
     * @param rejectReasonDescription text of the validation failure, at most 76 characters
     * @param rejectedTransactionData the refused transaction record, exactly 350 characters
     * @param rejectedAt              point in time the ledger recorded the refusal
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when {@code rejectReasonCode} is not four decimal digits,
     *                                  when {@code transactionId} or
     *                                  {@code rejectReasonDescription} exceeds its width, or when
     *                                  {@code rejectedTransactionData} is not exactly 350
     *                                  characters
     */
    public RejectedTransactionEntity(UUID id, String transactionId, String rejectReasonCode,
            String rejectReasonDescription, String rejectedTransactionData, Instant rejectedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.transactionId = requireAtMost("transactionId", transactionId, DALYTRAN_ID_WIDTH);
        this.rejectReasonCode = requireFourDigits(rejectReasonCode);
        this.rejectReasonDescription = requireAtMost("rejectReasonDescription",
                rejectReasonDescription, VALIDATION_FAIL_REASON_DESC_WIDTH);
        this.rejectedTransactionData = requireExactly("rejectedTransactionData",
                rejectedTransactionData, REJECT_TRAN_DATA_WIDTH);
        this.rejectedAt = Objects.requireNonNull(rejectedAt, "rejectedAt is required");
    }

    /**
     * Checks one argument against the width of its column.
     *
     * @param field    name of the field, reported in every failure
     * @param value    the text the caller supplied
     * @param maxWidth widest text the column holds
     * @return {@code value} unchanged
     */
    private static String requireAtMost(String field, String value, int maxWidth) {
        Objects.requireNonNull(value, field + " is required");
        if (value.length() > maxWidth) {
            throw new IllegalArgumentException(field + " holds at most " + maxWidth
                    + " characters, not " + value.length());
        }
        return value;
    }

    /**
     * Checks one argument against the fixed width of its column.
     *
     * @param field name of the field, reported in every failure
     * @param value the text the caller supplied
     * @param width the one width the column holds
     * @return {@code value} unchanged
     */
    private static String requireExactly(String field, String value, int width) {
        Objects.requireNonNull(value, field + " is required");
        if (value.length() != width) {
            throw new IllegalArgumentException(field + " holds exactly " + width
                    + " characters, not " + value.length());
        }
        return value;
    }

    /**
     * Checks the reject code against {@link #REJECT_REASON_CODE_PATTERN}.
     *
     * <p>The failure names the field and the shape it requires. No failure repeats the value the
     * caller supplied, so a value that arrived in the wrong argument cannot reach a log.</p>
     *
     * @param value the text the caller supplied
     * @return {@code value} unchanged
     */
    private static String requireFourDigits(String value) {
        Objects.requireNonNull(value, "rejectReasonCode is required");
        if (!REJECT_REASON_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "rejectReasonCode matches " + REJECT_REASON_CODE_PATTERN.pattern());
        }
        return value;
    }

    /**
     * Reads the row identifier.
     *
     * @return the identifier the caller supplied at construction
     */
    public UUID getId() {
        return id;
    }

    /**
     * Reads the identifier of the refused transaction.
     *
     * @return at most 16 characters, the width of {@code DALYTRAN-ID}
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Reads the code identifying the validation failure.
     *
     * @return four decimal digits, leading zeros kept
     */
    public String getRejectReasonCode() {
        return rejectReasonCode;
    }

    /**
     * Reads the text of the validation failure.
     *
     * @return at most 76 characters
     */
    public String getRejectReasonDescription() {
        return rejectReasonDescription;
    }

    /**
     * Reads the refused transaction record.
     *
     * @return exactly 350 characters, trailing spaces included
     */
    public String getRejectedTransactionData() {
        return rejectedTransactionData;
    }

    /**
     * Reads the point in time the ledger recorded the refusal.
     *
     * @return the value the caller supplied at construction
     */
    public Instant getRejectedAt() {
        return rejectedAt;
    }

    /**
     * Compares two rows on {@link #getId()} alone.
     *
     * @param other the object to compare against
     * @return {@code true} when both rows carry the same non-null identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RejectedTransactionEntity that)) {
            return false;
        }
        UUID identifier = getId();
        return identifier != null && identifier.equals(that.getId());
    }

    /**
     * Derives the hash from {@link #getId()} alone.
     *
     * @return the hash of the identifier
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    /**
     * Renders five of the six values.
     *
     * <p>The refused transaction record is left out, so no log line built from this method
     * repeats those 350 characters.</p>
     *
     * @return the five rendered values
     */
    @Override
    public String toString() {
        return "RejectedTransactionEntity{id=" + id
                + ", transactionId=" + transactionId
                + ", rejectReasonCode=" + rejectReasonCode
                + ", rejectReasonDescription=" + rejectReasonDescription
                + ", rejectedAt=" + rejectedAt
                + "}";
    }
}
