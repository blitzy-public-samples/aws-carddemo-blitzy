package com.carddemo.account.messaging;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.PublishGate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * One account state change. The account service publishes it on a mutation and never on a read.
 *
 * <p>Twelve components serialize at one level: the five {@link EventEnvelope} components first,
 * then the seven payload components. No {@code envelope} key appears in the JavaScript Object
 * Notation (JSON) text, and {@link #toEnvelope()} returns the five envelope components as one
 * {@link EventEnvelope}.
 *
 * <p>Widths come from the 300-byte account record at {@code app/cpy/CVACT01Y.cpy}. The 178-byte
 * trailing {@code FILLER} at {@code app/cpy/CVACT01Y.cpy:L17} holds no data and no component maps
 * to it. Each of the four monetary components carries a scale of exactly {@link #MONETARY_SCALE}
 * and travels as a quoted decimal string matching {@link #MONETARY_PATTERN}.
 *
 * <p>No COBOL ancestor: {@link #changeKind()} and the five envelope components. No copybook and no
 * program under {@code app/cbl/} records a change kind or an event envelope.
 *
 * @param eventId            the idempotency key. A consumer checks it, then writes its side effects
 *                           and the marker row in one local transaction, and acknowledges only
 *                           after that transaction commits
 * @param eventType          the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion      the contract version, always {@link #SCHEMA_VERSION}
 * @param occurredAt         the moment the producer wrote the event, in Coordinated Universal Time
 * @param aggregateId        the account identifier and the message key, equal to
 *                           {@code accountId}. A broker orders messages within one partition, and
 *                           the key selects the partition, so the events of one account stay in
 *                           order
 * @param accountId          the eleven-digit account identifier, from {@code ACCT-ID PIC 9(11)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L5}. A leading zero belongs to the value
 * @param changeKind         which mutation produced the event. No source field carries it
 * @param currentBalance     the account balance, from {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L7}
 * @param creditLimit        the credit limit, from {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L8}
 * @param currentCycleCredit the cycle credit accumulator, from
 *                           {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L13}
 * @param currentCycleDebit  the cycle debit accumulator, from
 *                           {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
 *                           {@code app/cpy/CVACT01Y.cpy:L14}
 * @param expirationDate     the account expiry text, from {@code ACCT-EXPIRAION-DATE PIC X(10)} at
 *                           {@code app/cpy/CVACT01Y.cpy:L11}, which the source spells with the
 *                           transposed word. {@code app/cbl/CBTRN02C.cbl:L414} compares the field
 *                           character by character against the first ten characters of a
 *                           timestamp. The value travels as text and carries no date format
 */
public record AccountStateChanged(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String accountId,
        ChangeKind changeKind,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentBalance,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal creditLimit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleCredit,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal currentCycleDebit,
        String expirationDate) {

    /** The contract version this record carries, from {@link EventEnvelope#SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = EventEnvelope.SCHEMA_VERSION;

    /** The value {@code eventType} always holds: the simple name of this record. */
    public static final String EVENT_TYPE = AccountStateChanged.class.getSimpleName();

    /**
     * The form {@code accountId} and {@code aggregateId} both take: exactly eleven decimal digits,
     * from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    public static final String ACCOUNT_ID_PATTERN = EventEnvelope.AGGREGATE_ID_PATTERN;

    /** Fractional digits every monetary component carries, from the {@code V99} of the picture. */
    public static final int MONETARY_SCALE = 2;

    /**
     * The form every monetary component takes on the wire: an optional minus, one to ten integer
     * digits, a point, then two fractional digits.
     *
     * <p>The ten integer digits come from {@code S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L8},
     * {@code app/cpy/CVACT01Y.cpy:L13} and {@code app/cpy/CVACT01Y.cpy:L14}.
     */
    public static final String MONETARY_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /** Widest {@code expirationDate} this record holds, from {@code PIC X(10)}. */
    public static final int EXPIRATION_DATE_MAX_LENGTH = 10;

    /** {@link #MONETARY_PATTERN} compiled, and the check every monetary component passes. */
    private static final Pattern MONETARY_MATCHER = Pattern.compile(MONETARY_PATTERN);

    /**
     * Which mutation produced one event, and the type {@code changeKind} carries.
     *
     * <p>No COBOL ancestor. The source publishes nothing and records no change kind. Both constants
     * name a mutation the source performs.
     */
    public enum ChangeKind {

        /**
         * An account field update, the rewrite at {@code app/cbl/COACTUPC.cbl:L4066}.
         */
        ACCOUNT_UPDATED,

        /**
         * A billing cycle close, which zeroes both accumulators at
         * {@code app/cbl/CBACT04C.cbl:L353-L354}. An event carrying this kind reports both
         * accumulators at zero. The interest addition at {@code app/cbl/CBACT04C.cbl:L352} stays in
         * the batch program and no component reports it.
         */
        BILLING_CYCLE_CLOSED
    }

    /**
     * Checks all twelve components and truncates each monetary component to
     * {@link #MONETARY_SCALE} fractional digits.
     *
     * <p>The five envelope components pass through {@link EventEnvelope}, which applies the
     * envelope rules. {@code eventType} must equal {@link #EVENT_TYPE}, and {@code accountId} must
     * equal {@code aggregateId}, which gives {@code accountId} the eleven-digit form
     * {@link #ACCOUNT_ID_PATTERN} states.
     *
     * <p>Truncation drops the digits past the second toward zero, matching every arithmetic store
     * in {@code app/cbl/}, where the {@code ROUNDED} phrase appears zero times. A truncated value
     * whose plain text misses {@link #MONETARY_PATTERN} carries more than ten integer digits and is
     * rejected.
     *
     * <p>Every message names the component that failed. No message carries an account identifier or
     * a monetary value, so a failure writes neither to a log.
     *
     * @throws NullPointerException     when a reference component is {@code null}, which covers
     *                                  {@code eventId}, {@code eventType}, {@code occurredAt},
     *                                  {@code accountId}, {@code changeKind}, a monetary component
     *                                  and {@code expirationDate}
     * @throws IllegalArgumentException on any of six conditions.
     *                                  {@code eventType} is not {@link #EVENT_TYPE}.
     *                                  {@code schemaVersion} is not {@link #SCHEMA_VERSION}.
     *                                  {@code aggregateId} is {@code null} or is not eleven
     *                                  decimal digits.
     *                                  {@code accountId} differs from {@code aggregateId}.
     *                                  A truncated monetary component carries more than ten
     *                                  integer digits.
     *                                  {@code expirationDate} is longer than
     *                                  {@link #EXPIRATION_DATE_MAX_LENGTH}
     */
    public AccountStateChanged {
        EventEnvelope envelope =
                new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + EVENT_TYPE
                    + " and the supplied value is " + eventType);
        }
        Objects.requireNonNull(accountId, "accountId must be present");
        if (!accountId.equals(envelope.aggregateId())) {
            throw new IllegalArgumentException(
                    "accountId must equal aggregateId and the two supplied values differ");
        }
        Objects.requireNonNull(changeKind, "changeKind must be present");

        currentBalance = truncate("currentBalance", currentBalance);
        creditLimit = truncate("creditLimit", creditLimit);
        currentCycleCredit = truncate("currentCycleCredit", currentCycleCredit);
        currentCycleDebit = truncate("currentCycleDebit", currentCycleDebit);

        Objects.requireNonNull(expirationDate, "expirationDate must be present");
        if (expirationDate.length() > EXPIRATION_DATE_MAX_LENGTH) {
            throw new IllegalArgumentException("expirationDate must hold at most "
                    + EXPIRATION_DATE_MAX_LENGTH + " characters and the supplied value holds "
                    + expirationDate.length());
        }

    }

    /**
     * @param accountId          the eleven-digit account identifier
     * @param changeKind         which mutation produced the event
     * @param currentBalance     the account balance after the change
     * @param creditLimit        the credit limit after the change
     * @param currentCycleCredit the cycle credit accumulator after the change
     * @param currentCycleDebit  the cycle debit accumulator after the change
     * @param expirationDate     the account expiry text, at most ten characters
     * @return an event whose envelope carries a fresh identifier and the current moment
     * @throws NullPointerException     when a reference component is {@code null}
     * @throws IllegalArgumentException when a component misses the form this record states
     */
    public static AccountStateChanged of(String accountId, ChangeKind changeKind,
            BigDecimal currentBalance, BigDecimal creditLimit, BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit, String expirationDate) {
        return from(EventEnvelope.of(EVENT_TYPE, accountId), accountId, changeKind, currentBalance,
                creditLimit, currentCycleCredit, currentCycleDebit, expirationDate);
    }

    /**
     * @param envelope           the five envelope components to carry
     * @param accountId          the eleven-digit account identifier, equal to the envelope
     *                           {@code aggregateId}
     * @param changeKind         which mutation produced the event
     * @param currentBalance     the account balance after the change
     * @param creditLimit        the credit limit after the change
     * @param currentCycleCredit the cycle credit accumulator after the change
     * @param currentCycleDebit  the cycle debit accumulator after the change
     * @param expirationDate     the account expiry text, at most ten characters
     * @return an event carrying the supplied envelope and payload
     * @throws NullPointerException     when {@code envelope} or another reference component is
     *                                  {@code null}
     * @throws IllegalArgumentException when a component misses the form this record states
     */
    public static AccountStateChanged from(EventEnvelope envelope, String accountId,
            ChangeKind changeKind, BigDecimal currentBalance, BigDecimal creditLimit,
            BigDecimal currentCycleCredit, BigDecimal currentCycleDebit, String expirationDate) {
        Objects.requireNonNull(envelope, "envelope must be present");
        return new AccountStateChanged(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(), accountId,
                changeKind, currentBalance, creditLimit, currentCycleCredit, currentCycleDebit,
                expirationDate);
    }

    /**
     * @return the envelope this event carries
     */
    public EventEnvelope toEnvelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Renders one monetary value in the wire form {@link #MONETARY_PATTERN} states.
     *
     * <p>{@link BigDecimal#toPlainString()} writes the digits with no exponent, so a value such as
     * {@code 1E+2} reads {@code 100.00}.
     *
     * @param amount the value to render
     * @return the value truncated to {@link #MONETARY_SCALE} fractional digits, as plain text
     * @throws NullPointerException when {@code amount} is {@code null}
     */
    public static String toWireAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount must be present");
        return amount.setScale(MONETARY_SCALE, RoundingMode.DOWN).toPlainString();
    }

    /**
     * Reads one monetary value from the wire form {@link #MONETARY_PATTERN} states.
     *
     * <p>The returned value carries a scale of {@link #MONETARY_SCALE}, so a round trip through
     * {@link #toWireAmount(BigDecimal)} preserves the scale. Text with an exponent, a missing
     * fractional digit or more than ten integer digits is rejected.
     *
     * @param text the wire text to read
     * @return the value the text names
     * @throws NullPointerException     when {@code text} is {@code null}
     * @throws IllegalArgumentException when {@code text} misses {@link #MONETARY_PATTERN}
     */
    public static BigDecimal parseWireAmount(String text) {
        Objects.requireNonNull(text, "text must be present");
        if (!MONETARY_MATCHER.matcher(text).matches()) {
            throw new IllegalArgumentException("a monetary value must match " + MONETARY_PATTERN
                    + " and the supplied text holds " + text.length() + " characters");
        }
        return new BigDecimal(text);
    }

    /**
     * Truncates one monetary component and checks the result against {@link #MONETARY_PATTERN}.
     *
     * @param component the component name a failure message carries
     * @param value     the value to truncate
     * @return the value truncated to {@link #MONETARY_SCALE} fractional digits
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when the truncated value carries more than ten integer
     *                                  digits
     */
    private static BigDecimal truncate(String component, BigDecimal value) {
        Objects.requireNonNull(value, component + " must be present");
        BigDecimal truncated = value.setScale(MONETARY_SCALE, RoundingMode.DOWN);
        if (!MONETARY_MATCHER.matcher(truncated.toPlainString()).matches()) {
            throw new IllegalArgumentException(component + " must match " + MONETARY_PATTERN
                    + " once truncated to " + MONETARY_SCALE
                    + " fractional digits, and the supplied value carries "
                    + truncated.precision() + " significant digits");
        }
        return truncated;
    }

    /**
     * Serializes this event through the one publish-side gate every event of this platform passes.
     *
     * <p>{@link PublishGate} does the work, and it is the only boundary a payload of this platform
     * crosses on its way to a row: it resolves the registered event type, writes the flat wire form
     * through {@code JsonSchemaValidatingSerializer}, screens every property and every value for
     * cardholder data, checks the result against {@code schemas/account-state-changed-v1.json} in
     * {@code com.carddemo:event-contracts}, refuses a version no released contract publishes, and
     * refuses a document wider than the platform ceiling. The returned text is what a caller stores
     * in the {@code payload} column of {@code outbox_event} and what the relay later hands to the
     * broker unchanged.
     *
     * <p>This method is the only path from this event to a topic, so no publisher can reach one
     * without passing that gate, and the closed property set keeps an undeclared field out.
     *
     * @return this event as validated JavaScript Object Notation text, in UTF-8
     * @throws IllegalArgumentException when this event breaks its schema, carries a value the
     *         cardholder-data screens refuse, declares a version no contract publishes, or exceeds
     *         {@code EventWireBounds.MAX_EVENT_BYTES}
     */
    public String toValidatedJson() {
        return PublishGate.checkedJsonOf(this);
    }

    /**
     * Renders the technical identifiers and withholds every value the payload carries.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier twice, the balance, the credit limit and both
     * cycle accumulators.
     *
     * <p>The event identifier, the event type, the schema version, the occurrence time and the
     * change kind stay. A reader tracing this event through the relay needs them, and none carries
     * cardholder data. Every other component appears as {@link EventEnvelope#WITHHELD}, the
     * platform-wide redaction marker.
     *
     * @return the identifiers of this event with every payload value withheld, never {@code null}
     */
    @Override
    public String toString() {
        return "AccountStateChanged[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD + ", accountId="
                + EventEnvelope.WITHHELD + ", currentBalance=" + EventEnvelope.WITHHELD
                + ", creditLimit=" + EventEnvelope.WITHHELD
                + ", currentCycleCredit=" + EventEnvelope.WITHHELD
                + ", currentCycleDebit=" + EventEnvelope.WITHHELD + ", expirationDate="
                + EventEnvelope.WITHHELD + ", changeKind=" + changeKind + "]";
    }
}
