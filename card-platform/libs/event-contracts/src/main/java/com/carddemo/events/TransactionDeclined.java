package com.carddemo.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The event the authorization service publishes when it rejects a transaction.
 *
 * <p>Provenance is one program. {@code app/cbl/CBTRN02C.cbl} holds the only
 * validate-and-authorize logic in the CardDemo source, and its eighty-byte validation trailer at
 * {@code app/cbl/CBTRN02C.cbl:L176-L182} gives this record its shape. Two source fields fix two
 * widths. {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code app/cbl/CBTRN02C.cbl:L181} gives
 * the code four digits, and {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
 * {@code app/cbl/CBTRN02C.cbl:L182} caps the text at seventy-six characters. The batch job writes
 * both fields into the trailer of a four-hundred-and-thirty-byte reject record, allocated at
 * {@code app/jcl/POSTTRAN.jcl:L36}.
 *
 * <p>A decline is expected traffic, not a failure. {@code app/cbl/CBTRN02C.cbl:L229-L230} ends the
 * batch job with return code 4 when any record was rejected. The authorization service answers the
 * caller with the reason and publishes one event.
 *
 * <p>Four reason locators. {@link DeclineReason#INVALID_CARD_NUMBER} comes from
 * {@code app/cbl/CBTRN02C.cbl:L385-L387}, {@link DeclineReason#ACCOUNT_NOT_FOUND} from
 * {@code app/cbl/CBTRN02C.cbl:L397-L399}, {@link DeclineReason#OVER_CREDIT_LIMIT} from
 * {@code app/cbl/CBTRN02C.cbl:L410-L412}, and {@link DeclineReason#ACCOUNT_EXPIRED} from
 * {@code app/cbl/CBTRN02C.cbl:L417-L419}. One declined transaction carries one reason, so
 * {@code declineReasonCode} holds a single value and never a list. The source reads the account
 * only while the code is still zero, gated at {@code app/cbl/CBTRN02C.cbl:L372}, and no gate
 * separates the credit-limit test from the expiration test.
 *
 * <p>The wire form is flat. {@code envelope} serializes unwrapped, so one JavaScript Object
 * Notation (JSON) object holds the five envelope fields beside the six payload fields. No
 * {@code envelope} key reaches a topic. The document at
 * {@code schemas/transaction-declined-v1.json} validates the result on serialize and on
 * deserialize.
 *
 * <p>{@code maskedCardNumber} is ADDITIVE. No CardDemo program masks a Primary Account Number
 * (PAN), and {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at the full sixteen
 * characters. The authorization decision runs on the full PAN, because the cross-reference read at
 * {@code app/cbl/CBTRN02C.cbl:L382-L383} keys on all sixteen characters. Masking happens where the
 * event is serialized, so a caller supplies a value that is already masked.
 *
 * <p>Add a fifth reason as a new {@link DeclineReason} constant, not by changing this record. The
 * source marks the same extension point with the comment {@code * ADD MORE VALIDATIONS HERE} at
 * {@code app/cbl/CBTRN02C.cbl:L377}.
 *
 * <p>The authorization service publishes this event to the {@code transaction.declined} topic, and
 * no service consumes it in the demo topology. For the path each event travels from publish to
 * consume, read {@code card-platform/docs/event-flow.md}; for the reasoning behind the choices
 * above, read {@code card-platform/docs/decision-log.md}.
 *
 * @param envelope                 the five fields every event carries, serialized unwrapped beside
 *                                 the six payload fields below. Its {@code eventType} must equal
 *                                 {@link #EVENT_TYPE} and its {@code aggregateId} carries the
 *                                 account identifier that is also the Kafka message key
 * @param transactionId            the transaction identifier, exactly
 *                                 {@link #TRANSACTION_ID_LENGTH} characters. From
 *                                 {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} and
 *                                 {@code DALYTRAN-ID} at {@code app/cpy/CVTRA06Y.cpy:L5}
 * @param accountId                the eleven-digit account identifier, from
 *                                 {@code XREF-ACCT-ID PIC 9(11)} at
 *                                 {@code app/cpy/CVACT03Y.cpy:L7}. Always equal to the
 *                                 {@code aggregateId} of {@code envelope}, so the message key and
 *                                 the payload cannot disagree. Leading zeros belong to the value
 * @param declineReasonCode        the reason the authorization service rejected the transaction,
 *                                 serialized as four zero-padded digits
 * @param declineReasonDescription the reason text, character for character from the source, and
 *                                 always the {@link DeclineReason#description()} of
 *                                 {@code declineReasonCode}
 * @param amount                   the attempted amount, from
 *                                 {@code DALYTRAN-AMT PIC S9(09)V99} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L10}. Held at
 *                                 {@link #AMOUNT_SCALE} fractional digits and serialized as a
 *                                 decimal string. A negative amount is ordinary traffic: 50 of the
 *                                 300 records in {@code app/data/ASCII/dailytran.txt} carry one
 * @param maskedCardNumber         twelve mask characters then the last four digits of the card
 *                                 number, sixteen characters in all. ADDITIVE. Width from
 *                                 {@code DALYTRAN-CARD-NUM PIC X(16)} at
 *                                 {@code app/cpy/CVTRA06Y.cpy:L15}
 */
public record TransactionDeclined(
        @JsonUnwrapped EventEnvelope envelope,
        String transactionId,
        String accountId,
        DeclineReason declineReasonCode,
        String declineReasonDescription,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String maskedCardNumber) {

    /**
     * The routing discriminator this record carries, and the {@code eventType} the schema document
     * pins with {@code "const": "TransactionDeclined"}.
     *
     * <p>The canonical constructor accepts no envelope naming another type. {@link #of(String,
     * String, DeclineReason, BigDecimal, String)} stamps this value.
     */
    public static final String EVENT_TYPE = "TransactionDeclined";

    /**
     * The width of {@code transactionId}: sixteen characters.
     *
     * <p>From {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The identifier is text,
     * not a number, so leading zeros survive a serialize and deserialize round trip.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * The number of fractional digits {@code amount} carries on the wire: two.
     *
     * <p>From the two decimal places of {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}.
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * The form {@code amount} takes on the wire: a decimal string with an optional leading minus,
     * at most nine digits before the point, and exactly {@link #AMOUNT_SCALE} digits after it.
     *
     * <p>The nine integer digits come from {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy:L10}. The same pattern constrains {@code amount} in
     * {@code schemas/transaction-declined-v1.json}. A balance carries ten integer digits and a
     * different pattern, so the two are not interchangeable.
     */
    public static final String AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /**
     * The form {@code maskedCardNumber} takes: twelve mask characters then four digits.
     *
     * <p>The total width of sixteen matches {@code DALYTRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA06Y.cpy:L15}. The same pattern constrains {@code maskedCardNumber} in
     * {@code schemas/transaction-declined-v1.json}.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * The cap on {@code declineReasonDescription}: seventy-six characters.
     *
     * <p>From {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code app/cbl/CBTRN02C.cbl:L182}. The longest of the four texts holds forty-two characters.
     */
    public static final int DESCRIPTION_MAX_LENGTH = 76;

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check {@code accountId} runs.
     *
     * <p>Reusing the envelope constant keeps one pattern behind both account identifiers.
     */
    private static final Pattern ACCOUNT_ID_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /** {@link #AMOUNT_PATTERN} compiled, and the check {@code amount} runs once normalized. */
    private static final Pattern AMOUNT_MATCHER = Pattern.compile(AMOUNT_PATTERN);

    /** {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check {@code maskedCardNumber} runs. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * Checks every component and rejects a value the schema document would reject.
     *
     * <p>Each exception message names the component that failed. Three messages report a length
     * instead of a value, so no account identifier, no transaction identifier and no card number
     * reaches a log through a failure. A caller that mistakes a full Primary Account Number for a
     * masked one therefore leaks nothing.
     *
     * <p>{@code amount} arrives held at {@link #AMOUNT_SCALE} fractional digits by
     * {@link RoundingMode#DOWN}, which truncates toward zero. The pattern check then runs on the
     * held value, so the stored amount and the serialized string agree. Every other component is
     * stored as supplied.
     *
     * @throws NullPointerException     when {@code envelope}, {@code declineReasonCode} or
     *                                  {@code amount} is {@code null}
     * @throws IllegalArgumentException when a component fails its check. The {@code eventType} of
     *                                  {@code envelope} must be {@link #EVENT_TYPE}, and
     *                                  {@code transactionId} must hold
     *                                  {@link #TRANSACTION_ID_LENGTH} characters.
     *                                  {@code accountId} must be eleven digits and must equal the
     *                                  {@code aggregateId} of {@code envelope}.
     *                                  {@code declineReasonDescription} must be the
     *                                  {@link DeclineReason#description()} of
     *                                  {@code declineReasonCode}, within
     *                                  {@link #DESCRIPTION_MAX_LENGTH} characters.
     *                                  {@code amount} must fit nine digits before the point, and
     *                                  {@code maskedCardNumber} must match
     *                                  {@link #MASKED_CARD_NUMBER_PATTERN}.
     */
    public TransactionDeclined {
        Objects.requireNonNull(envelope, "envelope must be present");
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");
        Objects.requireNonNull(amount, "amount must be present");

        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("envelope must carry the eventType " + EVENT_TYPE
                    + " and the supplied envelope carries " + envelope.eventType());
        }
        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value " + (transactionId == null ? "is null"
                            : "holds " + transactionId.length() + " characters"));
        }
        if (accountId == null || !ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
            throw new IllegalArgumentException("accountId must match "
                    + EventEnvelope.AGGREGATE_ID_PATTERN + " and the supplied value "
                    + (accountId == null ? "is null"
                            : "holds " + accountId.length() + " characters"));
        }
        if (!accountId.equals(envelope.aggregateId())) {
            throw new IllegalArgumentException("accountId must equal the aggregateId of envelope, "
                    + "which is the Kafka message key, and the two supplied values differ");
        }
        if (!declineReasonCode.description().equals(declineReasonDescription)) {
            throw new IllegalArgumentException("declineReasonDescription must be \""
                    + declineReasonCode.description() + "\" beside reason code "
                    + declineReasonCode.code() + " and the supplied value is "
                    + (declineReasonDescription == null ? "null"
                            : "\"" + declineReasonDescription + "\""));
        }
        if (declineReasonDescription.length() > DESCRIPTION_MAX_LENGTH) {
            throw new IllegalArgumentException("declineReasonDescription must hold at most "
                    + DESCRIPTION_MAX_LENGTH + " characters and the text of reason code "
                    + declineReasonCode.code() + " holds " + declineReasonDescription.length());
        }

        amount = amount.setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (!AMOUNT_MATCHER.matcher(amount.toPlainString()).matches()) {
            throw new IllegalArgumentException("amount must match " + AMOUNT_PATTERN
                    + " once held at " + AMOUNT_SCALE
                    + " fractional digits, and the supplied value holds "
                    + amount.toPlainString());
        }
        if (maskedCardNumber == null
                || !MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must match "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value "
                    + (maskedCardNumber == null ? "is null"
                            : "holds " + maskedCardNumber.length() + " characters"));
        }
    }

    /**
     * Builds a declined event on an envelope the producer already holds.
     *
     * <p>{@code accountId} takes the {@code aggregateId} of {@code envelope}, and
     * {@code declineReasonDescription} takes the {@link DeclineReason#description()} of
     * {@code declineReasonCode}. A caller cannot pair one reason code with another reason's text,
     * and cannot make the payload disagree with the Kafka message key.
     *
     * @param envelope          the envelope to carry, naming {@link #EVENT_TYPE}
     * @param transactionId     the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                          characters
     * @param declineReasonCode the reason the authorization service rejected the transaction
     * @param amount            the attempted amount, held at {@link #AMOUNT_SCALE} fractional
     *                          digits
     * @param maskedCardNumber  the card number already masked to
     *                          {@link #MASKED_CARD_NUMBER_PATTERN}
     * @return a declined event carrying the four supplied values and the two derived ones
     * @throws NullPointerException     when {@code envelope}, {@code declineReasonCode} or
     *                                  {@code amount} is {@code null}
     * @throws IllegalArgumentException when a component fails a check of the canonical constructor
     */
    public static TransactionDeclined of(EventEnvelope envelope, String transactionId,
            DeclineReason declineReasonCode, BigDecimal amount, String maskedCardNumber) {
        Objects.requireNonNull(envelope, "envelope must be present");
        Objects.requireNonNull(declineReasonCode, "declineReasonCode must be present");

        return new TransactionDeclined(envelope, transactionId, envelope.aggregateId(),
                declineReasonCode, declineReasonCode.description(), amount, maskedCardNumber);
    }

    /**
     * Builds a declined event and stamps a fresh envelope, which is the path the authorization
     * service takes.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a new event identifier, the schema
     * version, and the current moment truncated to milliseconds. The two derived components behave
     * as they do in {@link #of(EventEnvelope, String, DeclineReason, BigDecimal, String)}.
     *
     * @param accountId         the eleven-digit account identifier, which becomes both the
     *                          {@code aggregateId} of the new envelope and the Kafka message key
     * @param transactionId     the transaction identifier, {@link #TRANSACTION_ID_LENGTH}
     *                          characters
     * @param declineReasonCode the reason the authorization service rejected the transaction
     * @param amount            the attempted amount, held at {@link #AMOUNT_SCALE} fractional
     *                          digits
     * @param maskedCardNumber  the card number already masked to
     *                          {@link #MASKED_CARD_NUMBER_PATTERN}
     * @return a declined event carrying a freshly stamped envelope
     * @throws NullPointerException     when {@code declineReasonCode} or {@code amount} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code accountId} is not eleven digits, or when a
     *                                  component fails a check of the canonical constructor
     */
    public static TransactionDeclined of(String accountId, String transactionId,
            DeclineReason declineReasonCode, BigDecimal amount, String maskedCardNumber) {
        return of(EventEnvelope.of(EVENT_TYPE, accountId), transactionId, declineReasonCode, amount,
                maskedCardNumber);
    }
}
