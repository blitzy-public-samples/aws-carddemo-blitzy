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
 * <p>The 350 characters stay whole. {@code rejected_transaction_data} holds them verbatim,
 * trailing spaces included, in the {@code 01 DALYTRAN-RECORD} order of
 * {@code app/cpy/CVTRA06Y.cpy:L5-L18}. The card number at
 * {@code app/cpy/CVTRA06Y.cpy:L15} arrives masked, and {@code toString} leaves the block out.</p>
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
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@Entity
@Table(name = "rejected_transaction",
        indexes = {
                @Index(name = "idx_rejected_transaction_transaction_id",
                        columnList = "transaction_id"),
                @Index(name = "ix_rejected_transaction_rejected_at", columnList = "rejected_at")})
public class RejectedTransactionEntity {

    /**
     * Shape every {@code reject_reason_code} matches: four decimal digits.
     * {@code WS-VALIDATION-FAIL-REASON} at {@code app/cbl/CBTRN02C.cbl:L181} is
     * {@code PIC 9(04)}, a fixed-width display numeric that keeps its leading zeros.
     */
    private static final Pattern REJECT_REASON_CODE_PATTERN = Pattern.compile("^[0-9]{4}$");

    // The fixed-width column below states its type inline as bpchar(n), which is how PostgreSQL
    // reports CHAR(n) through Java Database Connectivity metadata and what the start-up check
    // compares against. An annotation value has to be a compile-time constant, so no helper
    // method can assemble it.

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
     * The refused transaction exactly as it arrived, {@code REJECT-TRAN-DATA PIC X(350)} at
     * {@code app/cbl/CBTRN02C.cbl:L177}. The 350 characters follow
     * {@code app/cpy/CVTRA06Y.cpy:L5-L18} field order and keep every trailing space, so a reader
     * slices a field out at its copybook offset. The card number at
     * {@code app/cpy/CVTRA06Y.cpy:L15} is masked before it reaches this service.
     */
    @Column(name = "rejected_transaction_data", nullable = false, length = REJECT_TRAN_DATA_WIDTH,
            columnDefinition = "bpchar(" + REJECT_TRAN_DATA_WIDTH + ")")
    private String rejectedTransactionData;

    /**
     * Point in time the ledger recorded the refusal. The caller supplies the value, and no field
     * of the 430-byte source record carries it.
     */
    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    protected RejectedTransactionEntity() {
    }

    /**
     * Holds the six values of one reject row.
     *
     * @param id                        identifier of the row
     * @param transactionId             identifier of the refused transaction, at most 16 characters
     * @param rejectReasonCode          four decimal digits identifying the validation failure
     * @param rejectReasonDescription   text of the validation failure, at most 76 characters
     * @param rejectedTransactionData   the refused transaction record, exactly
     *                                  {@value com.carddemo.cobol.PicClause#REJECT_TRAN_DATA_WIDTH}
     *                                  characters
     * @param rejectedAt                point in time the ledger recorded the refusal
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException on any of three conditions.
     *                                  {@code rejectReasonCode} is not four decimal digits.
     *                                  {@code transactionId} or
     *                                  {@code rejectReasonDescription} exceeds its width.
     *                                  {@code rejectedTransactionData} misses its fixed width
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
     * <p>The failure names the field and the two widths. No failure repeats the value the caller
     * supplied, so a card number that arrived unmasked cannot reach a log.</p>
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

    public UUID getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getRejectReasonCode() {
        return rejectReasonCode;
    }

    public String getRejectReasonDescription() {
        return rejectReasonDescription;
    }

    /**
     * Reads the refused transaction record.
     *
     * @return exactly {@value com.carddemo.cobol.PicClause#REJECT_TRAN_DATA_WIDTH} characters in
     *         {@code app/cpy/CVTRA06Y.cpy:L5-L18} field order
     */
    public String getRejectedTransactionData() {
        return rejectedTransactionData;
    }

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
     * Renders the identifier and reason columns of this row.
     *
     * <p>{@code rejected_transaction_data} and {@code transaction_id} are left out. A reader who
     * needs either one reads the column.</p>
     *
     * @return the rendered values
     */
    @Override
    public String toString() {
        return "RejectedTransactionEntity{id=" + id
                + ", rejectReasonCode=" + rejectReasonCode
                + ", rejectReasonDescription=" + rejectReasonDescription
                + ", rejectedAt=" + rejectedAt
                + ", transactionId redacted, rejectedTransactionData redacted}";
    }
}
