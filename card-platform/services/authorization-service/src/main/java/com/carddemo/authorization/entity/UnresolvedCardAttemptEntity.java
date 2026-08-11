package com.carddemo.authorization.entity;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One authorization attempt whose card resolved to no account.
 *
 * <p>Maps {@code unresolved_card_attempt}, created by
 * {@code src/main/resources/db/migration/V3__unresolved_card_attempt.sql}.
 *
 * <p>Reject code {@code 0100} is assigned at {@code app/cbl/CBTRN02C.cbl:L385} when the keyed read
 * of the cross-reference dataset takes its {@code INVALID KEY} branch. The read resolved no account
 * at that point, and that is the fact this row records: the card number reached this service and no
 * cross-reference row held it. The source records the same condition in its own terms:
 * {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a reject record whose eighty-byte trailer at
 * {@code app/cbl/CBTRN02C.cbl:L180-L182} carries the reject code and its text.
 *
 * <p>{@code account_id} carries the subject the decision applied to, which is the account the caller
 * declared because the cross-reference read resolved none. The decline is published and keyed on that
 * account like every other, so this row and the event agree on the subject. The column is nullable
 * because a row written before this service resolved a subject for reject code {@code 0100} honestly
 * has none, and migration {@code V19} adds the column without inventing a value for those rows.
 *
 * <p>The card number is stored masked. No COBOL ancestor: no source program masks a Primary Account
 * Number (PAN), and {@code app/bms/COCRDSL.bms:L96-L99} renders the card field at its full sixteen
 * characters. The lookup that failed ran on the full number, and only this record carries the
 * masked form. {@link PanMasker#maskCardNumber(String)} produces it, and the constructor rejects an
 * unmasked value.
 *
 * <p>No column carries a card verification value. This service never reads the card record, so it
 * holds none.
 */
@Entity
@Table(name = "unresolved_card_attempt",
        indexes = {
                @Index(name = "idx_unresolved_card_attempt_attempted_at",
                        columnList = "attempted_at"),
                @Index(name = "idx_unresolved_card_attempt_account_id",
                        columnList = "account_id")})
public class UnresolvedCardAttemptEntity {

    /** Widest {@code transactionId} this row holds, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_MAX_LENGTH = PicClause.DALYTRAN_ID_WIDTH;

    /** Characters {@code maskedCardNumber} holds, from {@code CARD-NUM PIC X(16)}. */
    public static final int MASKED_CARD_NUMBER_LENGTH = PanMasker.CARD_NUMBER_LENGTH;

    /** Characters a reject code holds, from {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}. */
    public static final int DECLINE_REASON_CODE_LENGTH = 4;

    /**
     * Widest reject text this row holds, from
     * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    public static final int DECLINE_REASON_DESCRIPTION_MAX_LENGTH = 76;

    /** Characters {@code accountId} holds, from {@code XREF-ACCT-ID PIC 9(11)}. */
    public static final int ACCOUNT_ID_LENGTH = PicClause.ACCT_ID_WIDTH;

    /** The form {@code maskedCardNumber} takes: twelve mask characters then four digits. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile("^\\*{12}[0-9]{4}$");

    /** The form {@code accountId} takes, from {@code XREF-ACCT-ID PIC 9(11)}. */
    private static final Pattern ACCOUNT_ID_MATCHER = Pattern.compile("^[0-9]{11}$");

    @Id
    @Column(name = "transaction_id", nullable = false, length = TRANSACTION_ID_MAX_LENGTH)
    private String transactionId;

    @Column(name = "masked_card_number", nullable = false, length = MASKED_CARD_NUMBER_LENGTH)
    private String maskedCardNumber;

    @Column(name = "amount", nullable = false, precision = PicClause.DALYTRAN_AMT_PRECISION,
            scale = PicClause.DALYTRAN_AMT_SCALE)
    private BigDecimal amount;

    @Column(name = "decline_reason_code", nullable = false, length = DECLINE_REASON_CODE_LENGTH)
    private String declineReasonCode;

    @Column(name = "decline_reason_description", nullable = false,
            length = DECLINE_REASON_DESCRIPTION_MAX_LENGTH)
    private String declineReasonDescription;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "account_id", length = ACCOUNT_ID_LENGTH)
    private String accountId;

    /** Jakarta Persistence requires a no-argument constructor, and no caller uses this one. */
    protected UnresolvedCardAttemptEntity() {
    }

    /**
     * Builds one attempt record, and changes no value it accepts.
     *
     * @param transactionId            identifier of the attempted transaction
     * @param maskedCardNumber         the card number, already masked
     * @param amount                   the attempted amount, at two digits after the decimal point
     * @param declineReasonCode        four characters, the reject code the source assigns
     * @param declineReasonDescription the text that reject code carries
     * @param attemptedAt              the moment the attempt was decided
     * @param accountId                the eleven-digit account the decision applied to
     * @throws NullPointerException     when any argument is {@code null}
     * @throws IllegalArgumentException when a value misses its width, when
     *                                  {@code maskedCardNumber} is not masked, or when
     *                                  {@code accountId} is not eleven digits
     */
    public UnresolvedCardAttemptEntity(String transactionId, String maskedCardNumber,
            BigDecimal amount, String declineReasonCode, String declineReasonDescription,
            Instant attemptedAt, String accountId) {
        Objects.requireNonNull(transactionId, "transactionId must be present");
        if (transactionId.isBlank() || transactionId.length() > TRANSACTION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("transactionId holds one to "
                    + TRANSACTION_ID_MAX_LENGTH + " characters and the supplied value holds "
                    + transactionId.length());
        }
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber must be present");
        if (!MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber holds "
                    + MASKED_CARD_NUMBER_LENGTH
                    + " characters, twelve of them mask characters, and the supplied value holds "
                    + maskedCardNumber.length() + " characters");
        }
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");
        if (declineReasonCode.length() != DECLINE_REASON_CODE_LENGTH) {
            throw new IllegalArgumentException("declineReasonCode holds "
                    + DECLINE_REASON_CODE_LENGTH + " characters and the supplied value holds "
                    + declineReasonCode.length());
        }
        Objects.requireNonNull(declineReasonDescription,
                "declineReasonDescription must be present");
        if (declineReasonDescription.length() > DECLINE_REASON_DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("declineReasonDescription holds at most "
                    + DECLINE_REASON_DESCRIPTION_MAX_LENGTH
                    + " characters and the supplied value holds "
                    + declineReasonDescription.length());
        }

        this.transactionId = transactionId;
        this.maskedCardNumber = maskedCardNumber;
        this.amount = Objects.requireNonNull(amount, "amount must be present");
        this.declineReasonCode = declineReasonCode;
        this.declineReasonDescription = declineReasonDescription;
        Objects.requireNonNull(accountId, "accountId must be present");
        if (!ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
            throw new IllegalArgumentException("accountId holds " + ACCOUNT_ID_LENGTH
                    + " decimal digits and the supplied value holds " + accountId.length()
                    + " characters");
        }

        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt must be present");
        this.accountId = accountId;
    }

    /**
     * Returns the identifier of the attempted transaction.
     *
     * @return the identifier, never {@code null}
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the masked card number.
     *
     * @return sixteen characters, twelve of them mask characters, never {@code null}
     */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /**
     * Returns the attempted amount.
     *
     * @return the amount at two digits after the decimal point, never {@code null}
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the reject code the source assigns for an unresolved card.
     *
     * @return four characters, never {@code null}
     */
    public String getDeclineReasonCode() {
        return declineReasonCode;
    }

    /**
     * Returns the text that reject code carries.
     *
     * @return the text, never {@code null}
     */
    public String getDeclineReasonDescription() {
        return declineReasonDescription;
    }

    /**
     * Returns the moment the attempt was decided.
     *
     * @return the moment, never {@code null}
     */
    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    /**
     * Returns the account the decision applied to.
     *
     * @return the eleven-digit account identifier, or {@code null} on a row written before this
     *         service resolved a subject for reject code {@code 0100}
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Compares two rows on the identifier that is their primary key.
     *
     * @param other the object to compare against
     * @return {@code true} when {@code other} is a row carrying the same transaction identifier
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UnresolvedCardAttemptEntity attempt)) {
            return false;
        }
        return Objects.equals(transactionId, attempt.transactionId);
    }

    /**
     * Hashes the primary key.
     *
     * @return the hash of the transaction identifier
     */
    @Override
    public int hashCode() {
        return Objects.hash(transactionId);
    }

    /**
     * Renders the row without its card number and without its amount.
     *
     * @return the transaction identifier, the reject code and the moment
     */
    @Override
    public String toString() {
        return "UnresolvedCardAttemptEntity[transactionId=" + transactionId + ", declineReasonCode="
                + declineReasonCode + ", attemptedAt=" + attemptedAt + "]";
    }
}
