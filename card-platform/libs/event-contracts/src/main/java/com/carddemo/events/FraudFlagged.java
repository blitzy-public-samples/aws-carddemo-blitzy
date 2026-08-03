package com.carddemo.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * The event the fraud detection service publishes when its risk rules flag an authorized
 * transaction.
 *
 * <p>ADDITIVE IN FULL. No COBOL program and no copybook defines this event, and the CardDemo
 * source holds no risk scoring, no pattern analysis and no velocity checking. Two borrowed widths
 * are the only source citations this record carries. {@code TRAN-ID PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L5} gives {@code transactionId} sixteen characters.
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives the envelope's
 * {@code aggregateId} eleven digits.
 *
 * <p>The wire form is flat. {@link EventEnvelope} unwraps, so one serialized event holds the five
 * envelope fields beside the four payload fields in a single JavaScript Object Notation (JSON)
 * object. Nine fields reach the topic and no {@code envelope} key does.
 * {@code schemas/fraud-flagged-v1.json} names all nine in its required array and keeps its
 * property set open, so a field a later version adds reaches an existing consumer without
 * breaking it.
 *
 * <p>The envelope's {@code aggregateId} is the one account identifier an instance carries, and it
 * is also the Kafka message key. Read it through {@link EventEnvelope#aggregateId()}. The schema
 * document declares no second account field, so the key and the payload cannot disagree.
 *
 * <p>{@code eventType} is pinned to {@link #EVENT_TYPE} and the canonical constructor accepts no
 * other value. FraudFlagged and FraudCleared both travel the {@code fraud.assessed} topic, so a
 * consumer reads {@code eventType} to learn which payload arrived.
 *
 * <p>{@code riskScore} is a bounded integer from {@link #MINIMUM_RISK_SCORE} through
 * {@link #MAXIMUM_RISK_SCORE} and serializes as a JSON integer. The score is not money. This
 * record carries no monetary field, no card number, no card verification value and no card or
 * account status.
 *
 * <p>{@code assessedAt} is ADDITIVE and holds the moment the rules finished, in Coordinated
 * Universal Time. It serializes as an ISO-8601 timestamp, unlike the two fixed-width COBOL
 * timestamps that {@code TransactionAuthorized} and {@code TransactionPosted} carry.
 *
 * <p>The fraud detection service consumes {@code transaction.authorized} and publishes this event
 * afterwards, so it never sits in the authorization response path. The notification service reads
 * the event under the {@code notification-fraud} consumer group. For the path the event travels
 * from publish to consume, read {@code card-platform/docs/event-flow.md}; for the reasoning behind
 * the choices above, read {@code card-platform/docs/decision-log.md}.
 *
 * @param envelope       the five fields every event carries, unwrapped into the same JSON object
 *                       as the four payload fields below
 * @param transactionId  the identifier of the transaction the rules assessed, exactly
 *                       {@link #TRANSACTION_ID_LENGTH} characters, matching the
 *                       {@code transactionId} on {@code TransactionAuthorized}
 * @param riskScore      the score the rules produced, from {@link #MINIMUM_RISK_SCORE} through
 *                       {@link #MAXIMUM_RISK_SCORE}, where a higher number means more risk
 * @param triggeredRules the rules that flagged the transaction, at least one, without duplicates,
 *                       each one a member of {@link #RULE_IDENTIFIERS}. The list is immutable and
 *                       its order carries no meaning
 * @param assessedAt     the moment the rules finished, in Coordinated Universal Time
 */
public record FraudFlagged(@JsonUnwrapped EventEnvelope envelope, String transactionId,
        int riskScore, List<String> triggeredRules, Instant assessedAt) {

    /**
     * The value {@code eventType} carries on every instance.
     *
     * <p>The schema document pins the same value with {@code "const"}. {@link #of} stamps it and
     * the canonical constructor accepts no other. The {@code fraud.assessed} topic carries
     * FraudFlagged and FraudCleared together, so a consumer routes on this value.
     */
    public static final String EVENT_TYPE = "FraudFlagged";

    /**
     * The exact length of {@code transactionId}: sixteen characters.
     *
     * <p>Width borrowed from {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}. The
     * schema document pins the same length with {@code minLength} and {@code maxLength}.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** The lowest {@code riskScore} an instance may carry, matching the schema {@code minimum}. */
    public static final int MINIMUM_RISK_SCORE = 0;

    /** The highest {@code riskScore} an instance may carry, matching the schema {@code maximum}. */
    public static final int MAXIMUM_RISK_SCORE = 100;

    /** The rule identifier {@code VelocityRule} reports. */
    public static final String VELOCITY_RULE = "VELOCITY";

    /** The rule identifier {@code AmountAnomalyRule} reports. */
    public static final String AMOUNT_ANOMALY_RULE = "AMOUNT_ANOMALY";

    /** The rule identifier {@code MerchantCategoryRule} reports. */
    public static final String MERCHANT_CATEGORY_RULE = "MERCHANT_CATEGORY";

    /**
     * The rule identifiers a flagged event may name, in the order the schema document enumerates
     * them.
     *
     * <p>The set is immutable and the canonical constructor rejects an entry it does not hold. A
     * fourth rule joins by adding a constant beside the three above and a value to the
     * {@code enum} of the schema document. That document caps the rule list nowhere, so the
     * fourth value reaches an existing consumer without breaking it.
     */
    public static final Set<String> RULE_IDENTIFIERS = Collections.unmodifiableSet(
            new LinkedHashSet<>(
                    List.of(VELOCITY_RULE, AMOUNT_ANOMALY_RULE, MERCHANT_CATEGORY_RULE)));

    /**
     * Checks every component and rejects a value the schema document does not accept.
     *
     * <p>Each message names the component that failed. A rejected {@code transactionId} is
     * reported by its length and never by its value, so no identifier reaches a log through a
     * failure. {@code triggeredRules} is copied, so the list this record holds is immutable and a
     * later change to the caller's list changes nothing here.
     *
     * <p>This constructor narrows nothing the schema document leaves open. It changes no value it
     * accepts beyond copying the rule list, so every component survives a serialize and
     * deserialize round trip unchanged.
     *
     * @throws NullPointerException     when {@code envelope}, {@code transactionId},
     *                                  {@code triggeredRules} or {@code assessedAt} is
     *                                  {@code null}
     * @throws IllegalArgumentException when {@code envelope} carries an {@code eventType} other
     *                                  than {@link #EVENT_TYPE}, when {@code transactionId} is
     *                                  not {@link #TRANSACTION_ID_LENGTH} characters, when
     *                                  {@code riskScore} falls outside
     *                                  {@link #MINIMUM_RISK_SCORE} through
     *                                  {@link #MAXIMUM_RISK_SCORE}, or when
     *                                  {@code triggeredRules} is empty, repeats an entry, holds a
     *                                  {@code null} entry, or names a rule
     *                                  {@link #RULE_IDENTIFIERS} does not hold
     */
    public FraudFlagged {
        Objects.requireNonNull(envelope, "envelope must be present");
        Objects.requireNonNull(transactionId, "transactionId must be present");
        Objects.requireNonNull(triggeredRules, "triggeredRules must be present");
        Objects.requireNonNull(assessedAt, "assessedAt must be present");

        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("envelope eventType must be " + EVENT_TYPE
                    + " and the supplied value is " + envelope.eventType());
        }
        if (transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value holds " + transactionId.length());
        }
        if (riskScore < MINIMUM_RISK_SCORE || riskScore > MAXIMUM_RISK_SCORE) {
            throw new IllegalArgumentException("riskScore must fall from " + MINIMUM_RISK_SCORE
                    + " through " + MAXIMUM_RISK_SCORE + " and the supplied value is " + riskScore);
        }
        if (triggeredRules.isEmpty()) {
            throw new IllegalArgumentException(
                    "triggeredRules must name at least one rule and the supplied list is empty");
        }

        Set<String> named = new LinkedHashSet<>();
        for (String rule : triggeredRules) {
            if (rule == null) {
                throw new IllegalArgumentException(
                        "triggeredRules must name only known rules and the supplied list holds a "
                                + "null entry");
            }
            if (!RULE_IDENTIFIERS.contains(rule)) {
                throw new IllegalArgumentException("triggeredRules must name only "
                        + RULE_IDENTIFIERS + " and the supplied list holds " + rule);
            }
            if (!named.add(rule)) {
                throw new IllegalArgumentException(
                        "triggeredRules must hold no duplicate and the supplied list repeats "
                                + rule);
            }
        }

        triggeredRules = List.copyOf(triggeredRules);
    }

    /**
     * Builds a flagged event, stamping the envelope the fraud detection service would otherwise
     * assemble by hand.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a fresh event identifier, the schema
     * version and the publish timestamp. {@link #EVENT_TYPE} is stamped here, so a producer cannot
     * misroute a message on the {@code fraud.assessed} topic that FraudCleared shares.
     *
     * @param aggregateId    the eleven-digit account identifier the transaction belongs to, and
     *                       the Kafka message key. Leading zeros belong to the value
     * @param transactionId  the identifier of the transaction the rules assessed, exactly
     *                       {@link #TRANSACTION_ID_LENGTH} characters
     * @param riskScore      the score the rules produced, from {@link #MINIMUM_RISK_SCORE} through
     *                       {@link #MAXIMUM_RISK_SCORE}
     * @param triggeredRules the rules that flagged the transaction, at least one, without
     *                       duplicates, each one a member of {@link #RULE_IDENTIFIERS}
     * @param assessedAt     the moment the rules finished, in Coordinated Universal Time
     * @return a flagged event carrying a stamped envelope and the four supplied payload values
     * @throws NullPointerException     when {@code transactionId}, {@code triggeredRules} or
     *                                  {@code assessedAt} is {@code null}
     * @throws IllegalArgumentException when {@code aggregateId} is {@code null} or is not eleven
     *                                  decimal digits, or when any payload value fails the check
     *                                  the canonical constructor runs
     */
    public static FraudFlagged of(String aggregateId, String transactionId, int riskScore,
            List<String> triggeredRules, Instant assessedAt) {
        return new FraudFlagged(EventEnvelope.of(EVENT_TYPE, aggregateId), transactionId, riskScore,
                triggeredRules, assessedAt);
    }
}
