package com.carddemo.ledger.entity;

import static com.carddemo.cobol.PicClause.DALYTRAN_AMT_PRECISION;
import static com.carddemo.cobol.PicClause.DALYTRAN_AMT_SCALE;
import static com.carddemo.cobol.PicClause.DALYTRAN_CARD_NUM_WIDTH;
import static com.carddemo.cobol.PicClause.DALYTRAN_CAT_CD_WIDTH;
import static com.carddemo.cobol.PicClause.DALYTRAN_ID_WIDTH;
import static com.carddemo.cobol.PicClause.DALYTRAN_MERCHANT_ID_WIDTH;
import static com.carddemo.cobol.PicClause.DALYTRAN_ORIG_TS_WIDTH;
import static com.carddemo.cobol.PicClause.DALYTRAN_TYPE_CD_WIDTH;
import static com.carddemo.cobol.PicClause.VALIDATION_FAIL_REASON_DESC_WIDTH;
import static com.carddemo.cobol.PicClause.VALIDATION_FAIL_REASON_WIDTH;

import com.carddemo.cobol.PanMasker;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * <p>The 350 characters do NOT stay whole. {@code REJECT-TRAN-DATA} is a copy of the daily
 * transaction record laid out by {@code 01 DALYTRAN-RECORD} at {@code app/cpy/CVTRA06Y.cpy:L4}, and
 * {@code DALYTRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L15} sits inside it. This
 * class holds the nine source-derived fields a reader needs to diagnose or replay a refusal, beside
 * an assigned identifier and the instant of the refusal. The card number is masked on the way in by
 * {@link PanMasker#maskCardNumber(String)}, and the column check constraint {@code
 * ck_rejected_transaction_masked_card_number} refuses any value whose first twelve positions hold a
 * digit.</p>
 *
 * <p>The four free-text fields of the source record, the merchant name, the merchant city, the
 * merchant postal code and the transaction description, are dropped.
 * {@code reject_reason_description} names the failure.</p>
 *
 * <pre>
 * COBOL field                     Column
 * (additive)                      id
 * DALYTRAN-ID                     transaction_id
 * WS-VALIDATION-FAIL-REASON       reject_reason_code
 * WS-VALIDATION-FAIL-REASON-DESC  reject_reason_description
 * DALYTRAN-CARD-NUM (masked)      masked_card_number
 * DALYTRAN-AMT                    transaction_amount
 * DALYTRAN-TYPE-CD                transaction_type_code
 * DALYTRAN-CAT-CD                 transaction_category_code
 * DALYTRAN-MERCHANT-ID            merchant_id
 * DALYTRAN-ORIG-TS                origin_timestamp
 * (dropped)                       DALYTRAN-DESC, DALYTRAN-MERCHANT-NAME,
 *                                 DALYTRAN-MERCHANT-CITY, DALYTRAN-MERCHANT-ZIP,
 *                                 DALYTRAN-SOURCE
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

    /**
     * Shape every {@code masked_card_number} matches: twelve asterisks, then either the last four
     * digits of the card number or four more asterisks.
     *
     * <p>{@link PanMasker#maskCardNumber(String)} returns the first form for a readable card number
     * and the second for one it cannot read, and a refused transaction is exactly where an
     * unreadable one turns up. The pattern is the same test the column check constraint
     * {@code ck_rejected_transaction_masked_card_number} applies, so a value this class accepts is a
     * value the database accepts.</p>
     */
    private static final Pattern MASKED_CARD_NUMBER_PATTERN =
            Pattern.compile("^\\*{12}(?:[0-9]{4}|\\*{4})$");

    // Each fixed-width column below states its type inline as bpchar(n), which is how PostgreSQL
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
     * Masked form of {@code DALYTRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA06Y.cpy:L15}:
     * twelve asterisks then the last four digits. The unmasked value never reaches this column, and
     * the constructor applies the mask rather than trusting its caller.
     */
    @Column(name = "masked_card_number", nullable = false, length = DALYTRAN_CARD_NUM_WIDTH,
            columnDefinition = "bpchar(" + DALYTRAN_CARD_NUM_WIDTH + ")")
    private String maskedCardNumber;

    /**
     * Amount of the refused transaction, {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}. Reason code 0102 at {@code app/cbl/CBTRN02C.cbl:L403-L413}
     * turns on this value, so a reader diagnosing an overlimit refusal needs it.
     */
    @Column(name = "transaction_amount", nullable = false,
            precision = DALYTRAN_AMT_PRECISION, scale = DALYTRAN_AMT_SCALE)
    private BigDecimal transactionAmount;

    /**
     * Type code of the refused transaction, {@code DALYTRAN-TYPE-CD PIC X(02)} at
     * {@code app/cpy/CVTRA06Y.cpy:L6}. The first half of the categorisation key.
     */
    @Column(name = "transaction_type_code", nullable = false, length = DALYTRAN_TYPE_CD_WIDTH,
            columnDefinition = "bpchar(" + DALYTRAN_TYPE_CD_WIDTH + ")")
    private String transactionTypeCode;

    /**
     * Category code of the refused transaction, {@code DALYTRAN-CAT-CD PIC 9(04)} at
     * {@code app/cpy/CVTRA06Y.cpy:L7}, held as text so {@code 0001} stays four characters and
     * compares equal to {@code transaction.category_code}.
     */
    @Column(name = "transaction_category_code", nullable = false, length = DALYTRAN_CAT_CD_WIDTH,
            columnDefinition = "bpchar(" + DALYTRAN_CAT_CD_WIDTH + ")")
    private String transactionCategoryCode;

    /**
     * Merchant of the refused transaction, {@code DALYTRAN-MERCHANT-ID PIC 9(09)} at
     * {@code app/cpy/CVTRA06Y.cpy:L11}, held as text for the same reason. Enough to notice that
     * one merchant accounts for a run of refusals, and it names no cardholder.
     */
    @Column(name = "merchant_id", nullable = false, length = DALYTRAN_MERCHANT_ID_WIDTH,
            columnDefinition = "bpchar(" + DALYTRAN_MERCHANT_ID_WIDTH + ")")
    private String merchantId;

    /**
     * Origin timestamp of the refused transaction, {@code DALYTRAN-ORIG-TS PIC X(26)} at
     * {@code app/cpy/CVTRA06Y.cpy:L16}. Reason code 0103 at
     * {@code app/cbl/CBTRN02C.cbl:L414-L420} compares its first ten characters against the account
     * expiry. A reader diagnosing that refusal needs it, and it stays text for the same reason the
     * comparison does.
     */
    @Column(name = "origin_timestamp", nullable = false, length = DALYTRAN_ORIG_TS_WIDTH,
            columnDefinition = "bpchar(" + DALYTRAN_ORIG_TS_WIDTH + ")")
    private String originTimestamp;

    /**
     * Point in time the ledger recorded the refusal. The caller supplies the value, and no field
     * of the 430-byte source record carries it.
     */
    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    protected RejectedTransactionEntity() {
    }

    /**
     * Holds the values of one reject row, masking the card number on the way in.
     *
     * <p>{@code cardNumber} is the only argument this constructor changes.
     * {@code domain.RejectRecorder}, the one caller, passes the {@code maskedCardNumber} the
     * consumed event already carries, and {@link PanMasker#maskCardNumber(String)} is applied again
     * here. That call keeps the last four characters and replaces the first twelve, so masking an
     * already-masked value yields the same value: the mask is enforced whichever form arrives, and
     * the assertion after the call proves the result matches what the column accepts.</p>
     *
     * @param id                        identifier of the row
     * @param transactionId             identifier of the refused transaction, at most 16 characters
     * @param rejectReasonCode          four decimal digits identifying the validation failure
     * @param rejectReasonDescription   text of the validation failure, at most 76 characters
     * @param cardNumber                the card number of the refused transaction, masked here
     *                                  before it is stored
     * @param transactionAmount         amount of the refused transaction, scale
     *                                  {@value com.carddemo.cobol.PicClause#DALYTRAN_AMT_SCALE}
     * @param transactionTypeCode       type code of the refused transaction, two characters
     * @param transactionCategoryCode   category code of the refused transaction, four digits
     * @param merchantId                merchant identifier, nine digits
     * @param originTimestamp           origin timestamp, twenty-six characters
     * @param rejectedAt                point in time the ledger recorded the refusal
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException on any of four conditions.
     *                                  {@code rejectReasonCode} is not four decimal digits.
     *                                  {@code transactionId} or
     *                                  {@code rejectReasonDescription} exceeds its width.
     *                                  A fixed-width field is the wrong width.
     *                                  The amount carries the wrong scale
     */
    public RejectedTransactionEntity(UUID id, String transactionId, String rejectReasonCode,
            String rejectReasonDescription, String cardNumber, BigDecimal transactionAmount,
            String transactionTypeCode, String transactionCategoryCode, String merchantId,
            String originTimestamp, Instant rejectedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.transactionId = requireAtMost("transactionId", transactionId, DALYTRAN_ID_WIDTH);
        this.rejectReasonCode = requireFourDigits(rejectReasonCode);
        this.rejectReasonDescription = requireAtMost("rejectReasonDescription",
                rejectReasonDescription, VALIDATION_FAIL_REASON_DESC_WIDTH);
        this.maskedCardNumber = requireMasked(
                PanMasker.maskCardNumber(Objects.requireNonNull(cardNumber,
                        "cardNumber is required")));
        this.transactionAmount = requireScale(transactionAmount);
        this.transactionTypeCode = requireExactly("transactionTypeCode", transactionTypeCode,
                DALYTRAN_TYPE_CD_WIDTH);
        this.transactionCategoryCode = requireExactly("transactionCategoryCode",
                transactionCategoryCode, DALYTRAN_CAT_CD_WIDTH);
        this.merchantId = requireExactly("merchantId", merchantId, DALYTRAN_MERCHANT_ID_WIDTH);
        this.originTimestamp = requireExactly("originTimestamp", originTimestamp,
                DALYTRAN_ORIG_TS_WIDTH);
        this.rejectedAt = Objects.requireNonNull(rejectedAt, "rejectedAt is required");
    }

    /**
     * Checks that a masked card number matches {@link #MASKED_CARD_NUMBER_PATTERN}.
     *
     * <p>The constructor applies the mask itself, so a failure here reports that the masking
     * helper changed shape. The failure names the shape and never the value.</p>
     *
     * @param value the masked card number
     * @return {@code value} unchanged
     */
    private static String requireMasked(String value) {
        if (!MASKED_CARD_NUMBER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "maskedCardNumber matches " + MASKED_CARD_NUMBER_PATTERN.pattern());
        }
        return value;
    }

    /**
     * Checks the amount against the scale {@code DALYTRAN-AMT PIC S9(09)V99} declares.
     *
     * <p>The check reads {@link BigDecimal#scale()} and adjusts nothing:
     * {@code com.carddemo.cobol.CobolDecimal} owns every scale change on this platform. The
     * failure reports the two scales and never the amount.</p>
     *
     * @param value the amount the caller supplied
     * @return {@code value} unchanged
     */
    private static BigDecimal requireScale(BigDecimal value) {
        Objects.requireNonNull(value, "transactionAmount is required");
        if (value.scale() != DALYTRAN_AMT_SCALE) {
            throw new IllegalArgumentException("transactionAmount carries scale "
                    + DALYTRAN_AMT_SCALE + ", not " + value.scale());
        }
        return value;
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
     * Reads the masked card number of the refused transaction.
     *
     * @return twelve asterisks then either the last four digits or four more asterisks
     */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /**
     * Reads the amount of the refused transaction.
     *
     * @return the amount at scale {@value com.carddemo.cobol.PicClause#DALYTRAN_AMT_SCALE}
     */
    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    /**
     * Reads the type code of the refused transaction.
     *
     * @return two characters
     */
    public String getTransactionTypeCode() {
        return transactionTypeCode;
    }

    /**
     * Reads the category code of the refused transaction.
     *
     * @return four digit characters
     */
    public String getTransactionCategoryCode() {
        return transactionCategoryCode;
    }

    /**
     * Reads the merchant identifier of the refused transaction.
     *
     * @return nine digit characters
     */
    public String getMerchantId() {
        return merchantId;
    }

    /**
     * Reads the origin timestamp of the refused transaction.
     *
     * @return twenty-six characters
     */
    public String getOriginTimestamp() {
        return originTimestamp;
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
     * Renders the routing and reason columns of this row.
     *
     * <p>The card number appears in its masked form, which is the only form this row holds. The
     * amount is left out: a refusal reaches a log line, and the amount of one cardholder's refused
     * transaction is that cardholder's business. A reader who needs it reads the column.</p>
     *
     * @return the rendered values
     */
    @Override
    public String toString() {
        return "RejectedTransactionEntity{id=" + id
                + ", rejectReasonCode=" + rejectReasonCode
                + ", rejectReasonDescription=" + rejectReasonDescription
                + ", maskedCardNumber=" + maskedCardNumber
                + ", transactionTypeCode=" + transactionTypeCode
                + ", transactionCategoryCode=" + transactionCategoryCode
                + ", merchantId=" + merchantId
                + ", rejectedAt=" + rejectedAt
                + ", transactionId redacted}";
    }
}
