package com.carddemo.card.messaging;

import com.carddemo.events.serde.EventContracts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.JsonSchemaValidatingSerializer;

/**
 * The event the card service publishes when a card update commits.
 *
 * <p>Service-local. This record belongs to the card service and not to the shared
 * {@code com.carddemo.events} library, which closes at the transaction and fraud contracts.
 *
 * <p>Plan alignment. The plan names this contract {@code CardUpdated} and describes five payload
 * components written with plain Jackson against no schema. This record carries those five
 * components under that name, and it validates every payload against the shared versioned document
 * {@code schemas/card-updated-v1.json} before publishing rather than writing it unchecked. It
 * declares no mutation-kind component: the card service publishes on an update and on nothing
 * else, so a consumer needs no discriminator to tell one kind from another. The decision log this
 * platform still owes, {@code card-platform/docs/decision-log.md} (planned), is to record the move
 * to a shared schema.
 *
 * <p>The card service publishes on a card update and publishes nothing on a card list or a card
 * read. Five payload components carry the card as it stands after the update, and every width
 * comes from the 150-byte card record at {@code app/cpy/CVACT02Y.cpy}.
 *
 * <p>The wire form is flat. The five fields {@link EventEnvelope} defines are declared first
 * below, beside the five payload fields, so one event travels as ten properties of a single
 * JavaScript Object Notation (JSON) object and no nesting key reaches a topic.
 *
 * <p>Those ten properties are the ten {@code required} properties of
 * {@code schemas/card-updated-v1.json}, the document {@link #toValidatedJson()} checks every
 * payload against before it reaches a topic. That document closes its property set and declares
 * one bounded {@code extensions} object no record writes, so a field this record does not carry
 * cannot travel under this event type.
 *
 * <p>Two fields of the card record have no component here. The card verification value at
 * {@code app/cpy/CVACT02Y.cpy:L7} reaches no event, no log line and no response body. The trailing
 * filler at {@code app/cpy/CVACT02Y.cpy:L11} holds no data.
 *
 * <p>{@link #ofUnmaskedCardNumber} masks the card number, and the canonical constructor rejects an
 * unmasked value. A card lookup keys on all sixteen characters, so a caller resolves the card first
 * and builds the event second.
 *
 * <p>The card service outbox writer turns this record into text and stores that text in an
 * {@code outbox_event} row, inside the one transaction that writes the card row. For the choices
 * this record carries, read {@code card-platform/docs/decision-log.md} (planned).
 *
 * @param eventId          the identifier every consumer records to detect a duplicate delivery
 * @param eventType        the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion    the contract version, always {@link #SCHEMA_VERSION}
 * @param occurredAt       the moment the producer stamped the event
 * @param aggregateId      the eleven-digit account identifier, and the Kafka message key
 * @param maskedCardNumber twelve mask characters then the last four digits of the card number,
 *                         sixteen characters in all. ADDITIVE: no CardDemo program masks a Primary
 *                         Account Number (PAN), and {@code app/bms/COCRDSL.bms:L96-L100} defines
 *                         the card detail field unprotected at the full sixteen characters. Width
 *                         from {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}
 * @param accountId        the eleven-digit account identifier, equal to {@code aggregateId} and to
 *                         the Kafka message key. From {@code CARD-ACCT-ID PIC 9(11)} at
 *                         {@code app/cpy/CVACT02Y.cpy:L6}, the width
 *                         {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} also
 *                         carries. A leading zero belongs to the value
 * @param embossedName     the name embossed on the card, at most fifty characters. From
 *                         {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}
 * @param expirationDate       the card expiry date, ten characters shaped {@code YYYY-MM-DD}. The
 *                         source field is {@code CARD-EXPIRAION-DATE PIC X(10)} at
 *                         {@code app/cpy/CVACT02Y.cpy:L9}, spelled with the transposed word; the
 *                         target spells it {@code expirationDate}.
 *                         {@code app/cbl/COCRDUPC.cbl:L1505-L1507} slices the field into a
 *                         four-character year, a two-character month and a two-character day
 * @param activeStatus     the one-character active status after the update,
 *                         {@link #ACTIVE_STATUS_ACTIVE} or {@link #ACTIVE_STATUS_INACTIVE}. From
 *                         {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10},
 *                         whose domain {@code app/cbl/COCRDUPC.cbl:L91} fixes to those two literals
 */
public record CardUpdated(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String maskedCardNumber,
        String accountId,
        String embossedName,
        String expirationDate,
        String activeStatus) {

    /**
     * The routing discriminator every instance carries, and the simple name of this record.
     *
     * <p>{@link #ofUnmaskedCardNumber} stamps this value, and the canonical constructor accepts no
     * other one.
     */
    public static final String EVENT_TYPE = CardUpdated.class.getSimpleName();

    /** The contract version every instance carries, from {@link EventEnvelope#SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = EventEnvelope.SCHEMA_VERSION;

    /**
     * The form {@link #maskedCardNumber()} takes: twelve mask characters then four decimal digits.
     *
     * <p>ADDITIVE. A card number opens with a digit, so an unmasked value fails this pattern.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * Characters {@link #maskedCardNumber()} holds, from {@link PanMasker#CARD_NUMBER_LENGTH}.
     *
     * <p>The masker preserves the width of {@code CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVACT02Y.cpy:L5}, so a masked value is as wide as the stored one.
     */
    public static final int MASKED_CARD_NUMBER_LENGTH = PanMasker.CARD_NUMBER_LENGTH;

    /**
     * The form {@link #accountId()} and {@link #aggregateId()} take, from
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN}: exactly eleven decimal digits, matching
     * {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}.
     */
    public static final String ACCOUNT_ID_PATTERN = EventEnvelope.AGGREGATE_ID_PATTERN;

    /**
     * Widest {@link #embossedName()} this record holds, from
     * {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}.
     */
    public static final int EMBOSSED_NAME_MAX_LENGTH = 50;

    /**
     * Characters {@link #expirationDate()} holds, from {@code CARD-EXPIRAION-DATE PIC X(10)} at
     * {@code app/cpy/CVACT02Y.cpy:L9}.
     *
     * <p>The component travels as text and carries no date type, so a comparison against it reads
     * the characters the source compares.
     */
    public static final int EXPIRATION_DATE_LENGTH = 10;

    /**
     * The {@link #activeStatus()} of an active card, from the {@code 'Y'} of
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     *
     * <p>The component carries the status with no interpretation. No program under {@code app/cbl}
     * reads the status field before it posts a transaction, and the batch posting program never
     * opens the card dataset.
     */
    public static final String ACTIVE_STATUS_ACTIVE = "Y";

    /**
     * The {@link #activeStatus()} of a card that is not active, from the {@code 'N'} of
     * {@code app/cbl/COCRDUPC.cbl:L91}.
     */
    public static final String ACTIVE_STATUS_INACTIVE = "N";

    /** {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check every instance passes. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /** {@link #ACCOUNT_ID_PATTERN} compiled, and the check every instance passes. */
    private static final Pattern ACCOUNT_ID_MATCHER = Pattern.compile(ACCOUNT_ID_PATTERN);

    /**
     * Checks all ten components and changes no value it accepts.
     *
     * <p>{@code eventType} must equal {@link #EVENT_TYPE}, {@code schemaVersion} must equal
     * {@link #SCHEMA_VERSION}, and {@code accountId} must equal {@code aggregateId}. One account
     * identifier therefore reaches the Kafka message key, the envelope fields and the payload.
     *
     * <p>An unmasked card number fails {@link #MASKED_CARD_NUMBER_PATTERN}, so a caller that
     * bypasses {@link #ofUnmaskedCardNumber} still cannot build an event carrying a full card
     * number. That failure reports the length of the rejected value and never the value, and no
     * message here carries a card number or an account identifier.
     *
     * <p>Every message names the component that failed and states what the component accepts. A
     * component this constructor accepts survives a write and a read unchanged.
     *
     * @throws NullPointerException     when any reference component is {@code null}
     * @throws IllegalArgumentException when {@code eventType} or {@code schemaVersion} carries
     *                                  another value, when a payload component misses its pattern
     *                                  or its width, or when {@code accountId} differs from
     *                                  {@code aggregateId}
     */
    public CardUpdated {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + eventType + "\"");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION
                    + " and the supplied value is " + schemaVersion);
        }

        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber must be present");
        if (!MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must hold "
                    + MASKED_CARD_NUMBER_LENGTH + " characters matching "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value holds "
                    + maskedCardNumber.length() + " characters");
        }

        requireAccountIdentifier(aggregateId, "aggregateId");
        requireAccountIdentifier(accountId, "accountId");
        if (!accountId.equals(aggregateId)) {
            throw new IllegalArgumentException(
                    "accountId must equal aggregateId, and the two supplied values differ");
        }

        Objects.requireNonNull(embossedName, "embossedName must be present");
        if (embossedName.length() > EMBOSSED_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("embossedName must hold at most "
                    + EMBOSSED_NAME_MAX_LENGTH + " characters and the supplied value holds "
                    + embossedName.length());
        }

        Objects.requireNonNull(expirationDate, "expirationDate must be present");
        if (expirationDate.length() != EXPIRATION_DATE_LENGTH) {
            throw new IllegalArgumentException("expirationDate must hold " + EXPIRATION_DATE_LENGTH
                    + " characters and the supplied value holds " + expirationDate.length());
        }

        Objects.requireNonNull(activeStatus, "activeStatus must be present");
        if (!ACTIVE_STATUS_ACTIVE.equals(activeStatus)
                && !ACTIVE_STATUS_INACTIVE.equals(activeStatus)) {
            throw new IllegalArgumentException("activeStatus must be \"" + ACTIVE_STATUS_ACTIVE
                    + "\" or \"" + ACTIVE_STATUS_INACTIVE + "\" and the supplied value is \""
                    + activeStatus + "\"");
        }
    }

    /**
     * Masks the card number and builds an event on a fresh envelope.
     *
     * <p>{@link PanMasker#maskCardNumber(String)} hides every character except the last four, and
     * the result reaches {@link #maskedCardNumber()}. The argument itself reaches no component of
     * the event.
     *
     * <p>{@link EventEnvelope#of(String, String)} stamps {@code eventId},
     * {@link #SCHEMA_VERSION} and the current moment, and takes {@code accountId} as
     * {@code aggregateId}.
     *
     * <p>A {@code null}, blank or short argument masks to sixteen mask characters, which the
     * canonical constructor then rejects. No argument value passes through unmasked.
     *
     * @param unmaskedCardNumber the card number as stored in {@code CARD-NUM}, at its full width
     * @param accountId          the eleven-digit account identifier, and the Kafka message key
     * @param embossedName       the name embossed on the card after the update
     * @param expirationDate     the card expiry date after the update, ten characters
     * @param activeStatus       the one-character active status after the update
     * @return an event carrying the masked card number, the four supplied values and a fresh
     *         envelope
     * @throws NullPointerException     when a payload argument other than
     *                                  {@code unmaskedCardNumber} is {@code null}
     * @throws IllegalArgumentException when an argument misses the form this record states
     */
    public static CardUpdated ofUnmaskedCardNumber(String unmaskedCardNumber, String accountId,
            String embossedName, String expirationDate, String activeStatus) {
        EventEnvelope envelope = EventEnvelope.of(EVENT_TYPE, accountId);

        return new CardUpdated(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateId(),
                PanMasker.maskCardNumber(unmaskedCardNumber), accountId, embossedName, expirationDate,
                activeStatus);
    }

    /**
     * Returns the five envelope fields of this event as one carrier.
     *
     * <p>The five fields are declared flat on this record, so a serialized event carries no
     * {@code envelope} key. This accessor builds the carrier a caller routes or logs on, and the
     * five values it returns are the five this record holds.
     *
     * @return the envelope of this event, never {@code null}
     */
    public EventEnvelope envelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Builds one card event on an envelope the producer already holds.
     *
     * <p>The five payload components arrive as supplied and the envelope contributes the five
     * fields every platform event carries, so a caller that stamped an envelope once reuses it.
     * The canonical constructor still refuses an unmasked card number, a foreign event type and an
     * account identifier that differs from the envelope {@code aggregateId}.
     *
     * @param envelope         the envelope to carry, naming {@link #EVENT_TYPE}
     * @param maskedCardNumber the card number already masked to
     *                         {@link #MASKED_CARD_NUMBER_PATTERN}
     * @param accountId        the eleven-digit account identifier, equal to the envelope
     *                         {@code aggregateId}
     * @param embossedName     the name embossed on the card after the update
     * @param expirationDate   the card expiry date after the update, ten characters
     * @param activeStatus     the one-character active status after the update
     * @return an event carrying the supplied envelope and payload
     * @throws NullPointerException     when {@code envelope} or another reference component is
     *                                  {@code null}
     * @throws IllegalArgumentException when a component misses the form this record states
     */
    public static CardUpdated from(EventEnvelope envelope, String maskedCardNumber,
            String accountId, String embossedName, String expirationDate, String activeStatus) {
        Objects.requireNonNull(envelope, "envelope must be present");
        return new CardUpdated(envelope.eventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.occurredAt(), envelope.aggregateId(), maskedCardNumber, accountId,
                embossedName, expirationDate, activeStatus);
    }

    /**
     * The one publish-side gate, shared with every other event of this platform.
     *
     * <p>The instance holds no mutable state and compiles every governed schema once, so one
     * instance serves the whole service.
     */
    private static final JsonSchemaValidatingSerializer<Object> SHARED_SERIALIZER =
            new JsonSchemaValidatingSerializer<>();

    /**
     * Serializes this event through the one publish-side gate every event of this platform passes.
     *
     * <p>{@link JsonSchemaValidatingSerializer} does the work: it writes the flat wire form, checks
     * the result against {@code schemas/card-updated-v1.json} in
     * {@code com.carddemo:event-contracts}, and refuses a document wider than the platform ceiling.
     * The returned text is what a caller stores in the {@code payload} column of
     * {@code outbox_event} and what the relay later hands to the broker unchanged.
     *
     * <p>This method exists so that no publisher of this event can reach a topic without passing
     * that gate. Before it existed, this service serialized its own event with its own conventions
     * while the shared serializer governed only the five core events, so a mutation event's payload
     * was never checked against the contract that describes it and the closed property set that
     * keeps an undeclared field out of a known event type never applied to it.
     *
     * @return this event as validated JavaScript Object Notation text, in UTF-8
     * @throws org.apache.kafka.common.errors.SerializationException when this event breaks its
     *         schema or exceeds {@code EventWireBounds.MAX_EVENT_BYTES}
     */
    public String toValidatedJson() {
        // The topic is supplied because the serializer refuses to write a registered event type
        // onto a topic it does not belong on, and a null topic is one of those. The default topic
        // is the right one here: a deployment that renames a topic applies the rename through the
        // producer property the publisher reads, not through this in-process gate.
        return new String(
                SHARED_SERIALIZER.serialize(EventContracts.defaultTopicFor(EVENT_TYPE), this),
                StandardCharsets.UTF_8);
    }

    /**
     * Returns a rendering that names the event and withholds every card value it carries.
     *
     * <p>The compiler-generated rendering a record carries would print the account identifier, the
     * embossed cardholder name, the expiry date and the last four digits of the card number on one
     * line. That set names a cardholder, and one {@code log.info("published {}", event)} anywhere in
     * this service would persist it. This rendering names the event and its type, and names no
     * value belonging to the card or the person holding it.
     *
     * @return the event identifier and the event type, with every card component withheld
     */
    @Override
    public String toString() {
        return "CardUpdated[eventId=" + eventId
                + ", eventType=" + EVENT_TYPE
                + ", aggregateId=" + EventEnvelope.WITHHELD
                + ", accountId=" + EventEnvelope.WITHHELD
                + ", maskedCardNumber=" + EventEnvelope.WITHHELD
                + ", embossedName=" + EventEnvelope.WITHHELD
                + ", expirationDate=" + EventEnvelope.WITHHELD
                + ", activeStatus=" + EventEnvelope.WITHHELD + "]";
    }

    /**
     * Checks one account identifier against {@link #ACCOUNT_ID_PATTERN}.
     *
     * @param value     the identifier to check
     * @param component the component name the failure message reports
     * @throws IllegalArgumentException when {@code value} is {@code null} or is not eleven decimal
     *                                  digits. The message reports the length and never the value
     */
    private static void requireAccountIdentifier(String value, String component) {
        if (value == null || !ACCOUNT_ID_MATCHER.matcher(value).matches()) {
            throw new IllegalArgumentException(component + " must match " + ACCOUNT_ID_PATTERN
                    + " and the supplied value " + (value == null ? "is null"
                            : "holds " + value.length() + " characters"));
        }
    }
}
