package com.carddemo.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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
 * <p>The wire form is flat. The five envelope fields are declared first below, beside the five
 * payload fields that follow them, so one serialized event is a single JavaScript Object Notation
 * (JSON) object. Ten fields reach the topic and no {@code envelope} key does.
 * {@code schemas/fraud-flagged-v1.json} names all ten in its required array and sets
 * {@code additionalProperties} to {@code false}, so a field a later version adds joins the
 * document as a new version rather than arriving unannounced.
 *
 * <p>{@code aggregateId} and {@code accountId} both hold the account identifier, and
 * {@code aggregateId} is the Kafka message key. The canonical constructor rejects two differing
 * values, so the key and the payload cannot disagree.
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
 * <p>The fraud detection service reads {@code transaction.authorized} and publishes this event
 * afterwards, so it never sits in the authorization response path. The notification service reads
 * the event under the {@code notification-fraud} consumer group.
 *
 * @param eventId        the idempotency key each consumer records before it applies side effects,
 *                       a Universally Unique Identifier (UUID)
 * @param eventType      the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion  the contract version, always {@link EventEnvelope#SCHEMA_VERSION}
 * @param occurredAt     the moment the producer wrote the event, in Coordinated Universal Time
 * @param aggregateId    the eleven-digit account identifier, and the Kafka message key. From
 *                       {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * @param transactionId  the identifier of the transaction the rules assessed, exactly
 *                       {@link #TRANSACTION_ID_LENGTH} characters, matching the
 *                       {@code transactionId} on {@code TransactionAuthorized}
 * @param accountId      the eleven-digit account identifier the assessed transaction belongs to,
 *                       always equal to the {@code aggregateId} of {@code envelope}. Width
 *                       borrowed from {@code XREF-ACCT-ID PIC 9(11)} at
 *                       {@code app/cpy/CVACT03Y.cpy:L7}. Leading zeros belong to the value
 * @param riskScore      the score the rules produced, from {@link #MINIMUM_RISK_SCORE} through
 *                       {@link #MAXIMUM_RISK_SCORE}, where a higher number means more risk
 * @param triggeredRules the rules that flagged the transaction, at least one, without duplicates,
 *                       each one a member of {@link #RULE_IDENTIFIERS}. The list is immutable and
 *                       its order carries no meaning
 * @param assessedAt     the moment the rules finished, in Coordinated Universal Time
 * @param accountId      the same eleven-digit account identifier {@code aggregateId} carries. From
 *                       {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}. Leading
 *                       zeros belong to the value
 */
public record FraudFlagged(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String transactionId,
        int riskScore,
        List<String> triggeredRules,
        Instant assessedAt,
        String accountId) {

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

    /**
     * The form {@code accountId} takes, from {@link EventEnvelope#AGGREGATE_ID_PATTERN}: exactly
     * eleven decimal digits.
     *
     * <p>Width borrowed from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
     * Reusing the envelope constant keeps one pattern behind both account identifiers.
     */
    public static final String ACCOUNT_ID_PATTERN = EventEnvelope.AGGREGATE_ID_PATTERN;

    /** The lowest {@code riskScore} an instance may carry, matching the schema {@code minimum}. */
    public static final int MINIMUM_RISK_SCORE = 0;

    /** The highest {@code riskScore} an instance may carry, matching the schema {@code maximum}. */
    public static final int MAXIMUM_RISK_SCORE = 100;

    /** The rule identifier the planned velocity rule reports. */
    public static final String VELOCITY_RULE = "VELOCITY";

    /** The rule identifier the planned amount-anomaly rule reports. */
    public static final String AMOUNT_ANOMALY_RULE = "AMOUNT_ANOMALY";

    /** The rule identifier the planned merchant-category rule reports. */
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

    /** {@link #ACCOUNT_ID_PATTERN} compiled, and the check {@code accountId} runs. */
    private static final Pattern ACCOUNT_ID_MATCHER = Pattern.compile(ACCOUNT_ID_PATTERN);

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check both account identifiers
     * run.
     */
    private static final Pattern ACCOUNT_IDENTIFIER_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

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
     * @throws NullPointerException     when {@code eventId}, {@code eventType},
     *                                  {@code occurredAt}, {@code transactionId},
     *                                  {@code triggeredRules} or {@code assessedAt} is
     *                                  {@code null}
     * @throws IllegalArgumentException when a component fails its check. The envelope checks are
     *                                  {@code eventType} against {@link #EVENT_TYPE} and
     *                                  {@code schemaVersion} against
     *                                  {@link EventEnvelope#SCHEMA_VERSION}. The identifier checks
     *                                  are eleven decimal digits for {@code aggregateId} and for
     *                                  {@code accountId}, equality between the two, and
     *                                  {@link #TRANSACTION_ID_LENGTH} characters for
     *                                  {@code transactionId}. The assessment checks are
     *                                  {@code riskScore} inside {@link #MINIMUM_RISK_SCORE} through
     *                                  {@link #MAXIMUM_RISK_SCORE}, and a {@code triggeredRules}
     *                                  list that is non-empty, free of repeats and of {@code null},
     *                                  and drawn from {@link #RULE_IDENTIFIERS}
     */
    public FraudFlagged {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");
        Objects.requireNonNull(transactionId, "transactionId must be present");
        Objects.requireNonNull(triggeredRules, "triggeredRules must be present");
        Objects.requireNonNull(assessedAt, "assessedAt must be present");

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + EVENT_TYPE
                    + " and the supplied value is " + eventType);
        }
        if (schemaVersion != EventEnvelope.SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be "
                    + EventEnvelope.SCHEMA_VERSION + " and the supplied value is " + schemaVersion);
        }

        requireAccountIdentifier(aggregateId, "aggregateId");
        if (accountId == null) {
            accountId = aggregateId;
        }
        requireAccountIdentifier(accountId, "accountId");
        if (!aggregateId.equals(accountId)) {
            throw new IllegalArgumentException("aggregateId and accountId must hold one account "
                    + "identifier and the two supplied values differ");
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
     * The five envelope components, as the carrier a producer builds and a consumer routes on.
     *
     * <p>The returned envelope holds the values this record already carries, so the two cannot
     * disagree. Serialization ignores the method, and a serialized event carries no
     * {@code envelope} key.
     *
     * @return an envelope holding {@code eventId}, {@code eventType}, {@code schemaVersion},
     *         {@code occurredAt} and {@code aggregateId}
     */
    public EventEnvelope envelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Checks one account identifier against {@link EventEnvelope#AGGREGATE_ID_PATTERN}.
     *
     * @param value     the identifier to check
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null} or is not eleven decimal
     *                                  digits. The message reports the length and never the value
     */
    private static void requireAccountIdentifier(String value, String component) {
        if (value == null || !ACCOUNT_IDENTIFIER_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match "
                    + EventEnvelope.AGGREGATE_ID_PATTERN + " and the supplied value "
                    + (value == null ? "is null" : "holds " + value.length() + " characters"));
        }
    }

    /**
     * Builds a flagged event, stamping the envelope the fraud detection service would otherwise
     * assemble by hand.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a fresh event identifier, the schema
     * version and the publish timestamp. {@link #EVENT_TYPE} is stamped here, so a producer cannot
     * misroute a message on the {@code fraud.assessed} topic that FraudCleared shares.
     *
     * @param aggregateId    the eleven-digit account identifier the transaction belongs to. It
     *                       becomes both the Kafka message key and {@link #accountId()}. Leading
     *                       zeros belong to the value
     * @param transactionId  the identifier of the transaction the rules assessed, exactly
     *                       {@link #TRANSACTION_ID_LENGTH} characters
     * @param riskScore      the score the rules produced, from {@link #MINIMUM_RISK_SCORE} through
     *                       {@link #MAXIMUM_RISK_SCORE}
     * @param triggeredRules the rules that flagged the transaction, at least one, without
     *                       duplicates, each one a member of {@link #RULE_IDENTIFIERS}
     * @param assessedAt     the moment the rules finished, in Coordinated Universal Time
     * @return a flagged event carrying a stamped envelope, the four supplied payload values and
     *         the account identifier under both names
     * @throws NullPointerException     when {@code transactionId}, {@code triggeredRules} or
     *                                  {@code assessedAt} is {@code null}
     * @throws IllegalArgumentException when {@code aggregateId} is {@code null} or is not eleven
     *                                  decimal digits, or when any payload value fails the check
     *                                  the canonical constructor runs
     */
    public static FraudFlagged of(String aggregateId, String transactionId, int riskScore,
            List<String> triggeredRules, Instant assessedAt) {
        EventEnvelope envelope = EventEnvelope.of(EVENT_TYPE, aggregateId);

        return new FraudFlagged(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateId(), transactionId, riskScore,
                triggeredRules, assessedAt, envelope.aggregateId());
    }
    /**
     * Renders the technical identifiers and withholds the score and the rule list.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the risk score and the identifiers of the rules that triggered, which
     * together describe how this platform scores risk.
     *
     * <p>The count of triggered rules appears in place of the list. A reader can tell that rules
     * fired without learning which ones.
     *
     * @return the identifiers of this event with the score and the rule list withheld, never
     *         {@code null}
     */
    @Override
    public String toString() {
        return "FraudFlagged[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", transactionId=" + transactionId
                + ", accountId=" + EventEnvelope.WITHHELD + ", riskScore=" + EventEnvelope.WITHHELD + ", triggeredRules="
                + EventEnvelope.WITHHELD + " (" + triggeredRules.size() + " entries), assessedAt="
                + assessedAt + "]";
    }

}
