package com.carddemo.authorization.entity;

import com.carddemo.cobol.PanMasker;
import com.carddemo.cobol.PicClause;
import com.carddemo.events.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Persistable;

/**
 * One authorization decision this service took, approved or declined.
 *
 * <p>Maps {@code authorization_decision}, created by
 * {@code src/main/resources/db/migration/V5__authorization_decision.sql}.
 *
 * <p>ADDITIVE. The source stores no decision of its own.
 * {@code app/cbl/CBTRN02C.cbl:L424-L444} posts an approved transaction into three files and
 * {@code app/cbl/CBTRN02C.cbl:L446-L465} writes a 430-byte reject record for a declined one, so the
 * two outcomes land in different places and neither carries the decision as such. This row is the
 * one place that answers what this service decided for one transaction.
 *
 * <p>The row and the {@code outbox_event} row commit in one local transaction, so a published event
 * always has a decision behind it and a stored decision always names the event it published through.
 * All eight file definitions of {@code app/csd/CARDDEMO.CSD} carry {@code JOURNAL(NO)} at
 * {@code :L7} and {@code RECOVERY(NONE)} at {@code :L9}, so that atomicity is ADDITIVE too.
 *
 * <p><b>Insert only.</b> {@link #isNew()} answers {@code true} for every instance, so a store inserts
 * and never updates. The primary key reproduces the key of the transaction file, where
 * {@code app/jcl/TRANFILE.jcl:L53} declares {@code KEYS(16 0)} and
 * {@code app/cbl/CBTRN02C.cbl:L562-L579} writes one record under it and fails the status test at
 * {@code :L566} on a duplicate. A second call carrying one caller-supplied identifier therefore
 * raises a primary-key violation and rolls the whole decision back, rather than replacing the stored
 * decision with the arriving one.
 *
 * <p>The card number is stored masked and tokenized. The full Primary Account Number (PAN) reaches no
 * column of this table, and the card verification value reaches nothing at all: this service never
 * reads the card record.
 */
@Entity
// One index, and one read path. ix_authorization_decision_decided_at serves the bounded retention
// delete of AuthorizationDecisionRepository. The account and actor composites were withdrawn by
// V18__authorization_decision_index_pruning.sql: no read reached either, so every authorization
// maintained two index entries nothing read.
@Table(name = "authorization_decision",
        indexes = {
            @Index(name = "ix_authorization_decision_decided_at", columnList = "decided_at")
        })
public class AuthorizationDecisionEntity implements Persistable<String> {

    /** The value {@link #outcome()} answers for a call every rule accepted. */
    public static final String APPROVED_OUTCOME = "APPROVED";

    /** The value {@link #outcome()} answers for a call one rule declined. */
    public static final String DECLINED_OUTCOME = "DECLINED";

    /**
     * Characters {@code actor} holds at most.
     *
     * <p>Not a source width. {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy:L18} is
     * eight characters wide and this column does not hold that field: it holds the authenticated
     * principal of an HTTP request, which {@code carddemo.security.users} configures and which no
     * source field bounds. The column was eight characters wide until
     * {@code src/main/resources/db/migration/V7__cycle_exposure_reservation.sql} widened it, and at
     * that width the shipped nine-character monitoring identity was recorded as {@code monitor0}.
     * Two identities that agreed in their first eight characters then shared one recorded actor,
     * which makes the column unusable for the one purpose it has.
     *
     * <p>{@code config/SecurityConfig} refuses a configured identity wider than this at start-up, so
     * no deployment reaches a decision with a principal this column cannot hold whole.
     */
    public static final int ACTOR_MAX_LENGTH = 64;

    /** Widest {@code transactionId} this row holds, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_MAX_LENGTH = PicClause.DALYTRAN_ID_WIDTH;

    /** Characters {@code accountId} holds, from {@code XREF-ACCT-ID PIC 9(11)}. */
    public static final int ACCOUNT_ID_LENGTH = PicClause.XREF_ACCT_ID_WIDTH;

    /** Characters {@code maskedCardNumber} holds, from {@code CARD-NUM PIC X(16)}. */
    public static final int MASKED_CARD_NUMBER_LENGTH = PanMasker.CARD_NUMBER_LENGTH;

    /** Characters {@code cardToken} holds, from {@link PanMasker#CARD_TOKEN_LENGTH}. */
    public static final int CARD_TOKEN_LENGTH = PanMasker.CARD_TOKEN_LENGTH;

    /** Characters a reject code holds, from {@code WS-VALIDATION-FAIL-REASON PIC 9(04)}. */
    public static final int DECLINE_REASON_CODE_LENGTH = 4;

    /**
     * Widest reject text this row holds, from {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}.
     */
    public static final int DECLINE_REASON_DESCRIPTION_MAX_LENGTH = 76;

    /** Eleven decimal digits, the form {@code accountId} takes when it is present. */
    private static final Pattern ACCOUNT_ID_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /**
     * The two forms {@code maskedCardNumber} takes: twelve mask characters then four digits, or
     * sixteen mask characters when the request named no card number.
     * {@link PanMasker#maskCardNumber(String)} produces both.
     */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile("^(?:\\*{12}[0-9]{4}|\\*{16})$");

    /**
     * The form {@code cardToken} takes: {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal
     * characters, read from {@link PanMasker#CARD_TOKEN_PATTERN} so one derivation fixes the shape
     * everywhere.
     */
    private static final Pattern CARD_TOKEN_MATCHER =
            Pattern.compile(PanMasker.CARD_TOKEN_PATTERN);

    /** Four decimal digits, the form a reject code takes. */
    private static final Pattern DECLINE_REASON_CODE_MATCHER = Pattern.compile("^[0-9]{4}$");

    @Id
    @Column(name = "transaction_id", nullable = false, length = TRANSACTION_ID_MAX_LENGTH)
    private String transactionId;

    @Column(name = "actor", nullable = false, length = ACTOR_MAX_LENGTH)
    private String actor;

    @Column(name = "account_id", length = ACCOUNT_ID_LENGTH, columnDefinition = "bpchar(11)")
    private String accountId;

    @Column(name = "masked_card_number", nullable = false, length = MASKED_CARD_NUMBER_LENGTH)
    private String maskedCardNumber;

    @Column(name = "card_token", nullable = false, length = CARD_TOKEN_LENGTH,
            columnDefinition = "bpchar(" + PanMasker.CARD_TOKEN_LENGTH + ")")
    private String cardToken;

    @Column(name = "amount", nullable = false, precision = PicClause.DALYTRAN_AMT_PRECISION,
            scale = PicClause.DALYTRAN_AMT_SCALE)
    private BigDecimal amount;

    @Column(name = "approved", nullable = false)
    private boolean approved;

    @Column(name = "decline_reason_code", length = DECLINE_REASON_CODE_LENGTH)
    private String declineReasonCode;

    @Column(name = "decline_reason_description",
            length = DECLINE_REASON_DESCRIPTION_MAX_LENGTH)
    private String declineReasonDescription;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    // Nullable at the column since V13__decision_without_event.sql, and required of every new row by
    // ck_authorization_decision_event since V15__unresolved_decline_is_published.sql. The nullability
    // stays so that rows written while one outcome published nothing remain the record they are; the
    // constraint is NOT VALID for the same reason.
    @Column(name = "event_id")
    private UUID eventId;

    /**
     * The processing moment the caller declared, at the width the transaction record holds.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L470} moves {@code TPROCDTI OF COTRN2AI} into
     * {@code TRAN-PROC-TS PIC X(26)}, so the capture path stores what the caller declared. The
     * ledger stamps its own value when it posts, at {@code app/cbl/CBTRN02C.cbl:L438}, so this column
     * is the only record of the declared moment.
     */
    @Column(name = "declared_processing_timestamp",
            length = PicClause.TRAN_PROC_TS_WIDTH)
    private String declaredProcessingTimestamp;

    /** Jakarta Persistence requires a no-argument constructor, and no caller uses this one. */
    protected AuthorizationDecisionEntity() {
    }

    /**
     * Builds one decision row, and changes no value it accepts.
     *
     * @param transactionId            identifier of the decided transaction
     * @param actor                    the authenticated request identity behind the decision
     * @param accountId                the account the cross-reference resolved, or {@code null}
     * @param maskedCardNumber         the card number, already masked
     * @param cardToken                the card token, or {@link PanMasker#ABSENT_CARD_TOKEN}
     * @param amount                   the decided amount, at two digits after the decimal point
     * @param approved                 whether every rule accepted
     * @param declineReasonCode        four characters on a decline, {@code null} on an approval
     * @param declineReasonDescription the text that reject code carries, {@code null} on an approval
     * @param decidedAt                the moment the decision was taken
     * @param eventId                  the outbox row this decision published through, required of
     *                                 every decision because one authorization call publishes one
     *                                 event
     * @param declaredProcessingTimestamp the processing moment the caller declared, or {@code null}
     */
    private AuthorizationDecisionEntity(String transactionId, String actor, String accountId,
            String maskedCardNumber, String cardToken, BigDecimal amount, boolean approved,
            String declineReasonCode, String declineReasonDescription, Instant decidedAt,
            UUID eventId, String declaredProcessingTimestamp) {
        Objects.requireNonNull(transactionId, "transactionId must be present");
        Objects.requireNonNull(actor, "actor must be present");
        if (actor.isBlank() || actor.length() > ACTOR_MAX_LENGTH) {
            throw new IllegalArgumentException("actor holds one to " + ACTOR_MAX_LENGTH
                    + " characters and the supplied value holds " + actor.length());
        }
        if (transactionId.isBlank() || transactionId.length() > TRANSACTION_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("transactionId holds one to "
                    + TRANSACTION_ID_MAX_LENGTH + " characters and the supplied value holds "
                    + transactionId.length());
        }
        if (accountId != null && !ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
            throw new IllegalArgumentException("accountId holds " + ACCOUNT_ID_LENGTH
                    + " decimal digits and the supplied value holds " + accountId.length()
                    + " characters");
        }
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber must be present");
        if (!MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber holds "
                    + MASKED_CARD_NUMBER_LENGTH
                    + " characters, twelve or sixteen of them mask characters, and the supplied"
                    + " value holds " + maskedCardNumber.length() + " characters");
        }
        Objects.requireNonNull(cardToken, "cardToken must be present");
        if (!CARD_TOKEN_MATCHER.matcher(cardToken).matches()) {
            throw new IllegalArgumentException("cardToken holds " + CARD_TOKEN_LENGTH
                    + " lower-case hexadecimal characters and the supplied value holds "
                    + cardToken.length() + " characters");
        }
        if (approved && accountId == null) {
            throw new IllegalArgumentException(
                    "an approval names the account the cross-reference resolved and the supplied"
                            + " value is absent");
        }
        // ck_authorization_decision_event in V15__unresolved_decline_is_published.sql, held here as well
        // so a caller learns of the mismatch at the call rather than at the flush. AAP
        // transformation rule T4 gives one authorization call one event, so every decision names the
        // outbox row it published through. A decision that resolved an account names an
        // account-keyed event; a decision that resolved none names a transaction-keyed
        // schemas/transaction-declined-v2.json event.
        if (eventId == null) {
            throw new IllegalArgumentException(
                    "every decision publishes one event and names its identifier");
        }

        this.transactionId = transactionId;
        this.actor = actor;
        this.accountId = accountId;
        this.maskedCardNumber = maskedCardNumber;
        this.cardToken = cardToken;
        this.amount = Objects.requireNonNull(amount, "amount must be present");
        this.approved = approved;
        this.declineReasonCode = declineReasonCode;
        this.declineReasonDescription = declineReasonDescription;
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must be present");
        this.eventId = eventId;
        if (declaredProcessingTimestamp != null
                && declaredProcessingTimestamp.length() != PicClause.TRAN_PROC_TS_WIDTH) {
            throw new IllegalArgumentException("declaredProcessingTimestamp holds "
                    + PicClause.TRAN_PROC_TS_WIDTH + " characters and the supplied value holds "
                    + declaredProcessingTimestamp.length());
        }
        this.declaredProcessingTimestamp = declaredProcessingTimestamp;
    }

    /**
     * Builds the row of a call every rule accepted.
     *
     * <p>Approval is reject reason zero. {@code app/cbl/CBTRN02C.cbl:L208} clears that field before
     * validation and {@code :L211} posts a record whose field still holds zero, so an approval names
     * no reject reason and this row carries neither reject column.
     *
     * @param transactionId    identifier of the decided transaction
     * @param actor            the authenticated request identity behind the decision
     * @param accountId        the account the cross-reference resolved, eleven decimal digits
     * @param maskedCardNumber the card number, already masked
     * @param cardToken        the card token
     * @param amount           the decided amount, at two digits after the decimal point
     * @param decidedAt        the moment the decision was taken
     * @param eventId          the outbox row this decision published through
     * @param declaredProcessingTimestamp the processing moment the caller declared, at the record
     *                                    width, or {@code null}
     * @return the approved row
     * @throws NullPointerException     when a required argument is {@code null}
     * @throws IllegalArgumentException when a value misses its width or its digit class
     */
    public static AuthorizationDecisionEntity approved(String transactionId, String actor,
            String accountId, String maskedCardNumber, String cardToken, BigDecimal amount,
            Instant decidedAt, UUID eventId, String declaredProcessingTimestamp) {
        Objects.requireNonNull(accountId, "accountId must be present on an approval");

        return new AuthorizationDecisionEntity(transactionId, actor, accountId, maskedCardNumber,
                cardToken, amount, true, null, null, decidedAt, eventId,
                declaredProcessingTimestamp);
    }

    /**
     * Builds the row of a call one rule declined.
     *
     * <p>{@code accountId} names the account the decision applied to on every outcome this service
     * records. Reject code {@code 0100} at {@code app/cbl/CBTRN02C.cbl:L385-L387} follows the
     * {@code INVALID KEY} branch of the cross-reference read at {@code :L383}, so its value is the
     * account the caller declared rather than the one that read resolved. The parameter stays nullable
     * because a row written before migration {@code V19} honestly holds none.
     *
     * @param transactionId            identifier of the decided transaction
     * @param actor                    the request identity this decision is recorded against
     * @param accountId                the account the cross-reference resolved, or {@code null}
     * @param maskedCardNumber         the card number, already masked
     * @param cardToken                the card token, or {@link PanMasker#ABSENT_CARD_TOKEN}
     * @param amount                   the decided amount, at two digits after the decimal point
     * @param declineReasonCode        the four-character reject code that stands
     * @param declineReasonDescription the text that reject code carries
     * @param decidedAt                the moment the decision was taken
     * @param eventId                  the outbox row this decision published through. Every decline
     *                                 names one account-keyed
     *                                 {@code schemas/transaction-declined-v3.json} event, whichever
     *                                 of the four reject codes stands
     * @param declaredProcessingTimestamp the processing moment the caller declared, at the record
     *                                    width, or {@code null}
     * @return the declined row
     * @throws NullPointerException     when a required argument is {@code null}
     * @throws IllegalArgumentException when a value misses its width or its digit class
     */
    public static AuthorizationDecisionEntity declined(String transactionId, String actor,
            String accountId, String maskedCardNumber, String cardToken, BigDecimal amount,
            String declineReasonCode, String declineReasonDescription, Instant decidedAt,
            UUID eventId, String declaredProcessingTimestamp) {
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present on a decline");
        if (!DECLINE_REASON_CODE_MATCHER.matcher(declineReasonCode).matches()) {
            throw new IllegalArgumentException("declineReasonCode holds "
                    + DECLINE_REASON_CODE_LENGTH + " decimal digits and the supplied value holds "
                    + declineReasonCode.length() + " characters");
        }
        Objects.requireNonNull(declineReasonDescription,
                "declineReasonDescription must be present on a decline");
        if (declineReasonDescription.length() > DECLINE_REASON_DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("declineReasonDescription holds at most "
                    + DECLINE_REASON_DESCRIPTION_MAX_LENGTH
                    + " characters and the supplied value holds "
                    + declineReasonDescription.length());
        }

        return new AuthorizationDecisionEntity(transactionId, actor, accountId, maskedCardNumber,
                cardToken, amount, false, declineReasonCode, declineReasonDescription, decidedAt,
                eventId, declaredProcessingTimestamp);
    }

    /**
     * Returns the primary key of this row.
     *
     * @return the sixteen-character transaction identifier, never {@code null} on a constructed
     *         instance
     */
    @Override
    public String getId() {
        return transactionId;
    }

    /**
     * Reports this row as new, always.
     *
     * <p>The identifier arrives from the caller rather than from the database, so a provider given a
     * populated key would otherwise read the table and choose an update. This answer removes that
     * choice: every store is an insert, and a repeat of one identifier raises a primary-key
     * violation.
     *
     * @return {@code true}
     */
    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    /**
     * Returns the identifier of the decided transaction.
     *
     * @return the identifier, never {@code null}
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the authenticated request identity behind this decision, recorded whole.
     *
     * @return the actor, at most {@value #ACTOR_MAX_LENGTH} characters, never {@code null} on a
     *         constructed instance
     */
    public String getActor() {
        return actor;
    }

    /**
     * Names this decision the way an operator reads it.
     *
     * @return {@value #APPROVED_OUTCOME} when every rule accepted, and {@value #DECLINED_OUTCOME}
     *         when one declined
     */
    public String outcome() {
        return approved ? APPROVED_OUTCOME : DECLINED_OUTCOME;
    }

    /**
     * Returns the account the cross-reference resolved.
     *
     * @return eleven decimal digits, or {@code null} when the cross-reference resolved none
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the masked card number.
     *
     * @return sixteen characters, twelve or sixteen of them mask characters, never {@code null}
     */
    public String getMaskedCardNumber() {
        return maskedCardNumber;
    }

    /**
     * Returns the card token.
     *
     * @return {@value PanMasker#CARD_TOKEN_LENGTH} lower-case hexadecimal characters, never {@code null}
     */
    public String getCardToken() {
        return cardToken;
    }

    /**
     * Returns the decided amount.
     *
     * @return the amount at two digits after the decimal point, never {@code null}
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Reports whether every rule accepted.
     *
     * @return {@code true} on an approval
     */
    public boolean isApproved() {
        return approved;
    }

    /**
     * Returns the reject code that stands.
     *
     * @return four characters, or {@code null} on an approval
     */
    public String getDeclineReasonCode() {
        return declineReasonCode;
    }

    /**
     * Returns the text that reject code carries.
     *
     * @return the text, or {@code null} on an approval
     */
    public String getDeclineReasonDescription() {
        return declineReasonDescription;
    }

    /**
     * Returns the moment the decision was taken.
     *
     * @return the moment, never {@code null}
     */
    public Instant getDecidedAt() {
        return decidedAt;
    }

    /**
     * Returns the outbox row this decision published through.
     *
     * @return the event identifier, never {@code null}
     */
    public UUID getEventId() {
        return eventId;
    }

    /**
     * Returns the processing moment the caller declared.
     *
     * @return the declared moment at {@value PicClause#TRAN_PROC_TS_WIDTH} characters, or
     *         {@code null} when the request carried none this row could store
     */
    public String getDeclaredProcessingTimestamp() {
        return declaredProcessingTimestamp;
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
        if (!(other instanceof AuthorizationDecisionEntity decision)) {
            return false;
        }
        return Objects.equals(transactionId, decision.transactionId);
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
     * Renders the row without its account identifier and without its amount.
     *
     * @return the transaction identifier, the outcome, the reject code and the moment
     */
    @Override
    public String toString() {
        return "AuthorizationDecisionEntity[transactionId=" + transactionId + ", accountId="
                + EventEnvelope.WITHHELD + ", approved=" + approved + ", declineReasonCode="
                + declineReasonCode + ", decidedAt=" + decidedAt + "]";
    }
}
