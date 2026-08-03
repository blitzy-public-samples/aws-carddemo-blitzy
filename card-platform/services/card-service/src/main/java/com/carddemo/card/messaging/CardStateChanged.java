package com.carddemo.card.messaging;

import com.carddemo.cobol.PanMasker;
import com.carddemo.events.EventEnvelope;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The event the card service publishes when a card update commits.
 *
 * <p>Service-local. This record belongs to the card service and not to the shared
 * {@code com.carddemo.events} library, which holds the transaction and fraud contracts. It carries
 * an {@link EventEnvelope} from that library, so a card event and a transaction event label
 * themselves with the same five fields.
 *
 * <p>The card service publishes on a card update and publishes nothing on a card list or a card
 * read. Six payload components carry the card as it stands after the update, and every width comes
 * from the 150-byte card record at {@code app/cpy/CVACT02Y.cpy}.
 *
 * <p>The wire form is flat. {@link JsonUnwrapped} lifts the five envelope fields beside the six
 * payload fields. One event therefore travels as eleven properties of a single JavaScript Object
 * Notation (JSON) object, and no nesting key reaches a topic.
 *
 * <p>Those eleven properties are the eleven {@code required} properties of
 * {@code schemas/card-state-changed-v1.json}, the document
 * {@link KafkaEventPublisher} loads from {@link #EVENT_TYPE} and {@link #SCHEMA_VERSION} and
 * validates every payload against before it reaches a topic.
 *
 * <p>Two fields of the card record have no component here. The card verification value at
 * {@code app/cpy/CVACT02Y.cpy:L7} reaches no event, no log line and no response body. The trailing
 * filler at {@code app/cpy/CVACT02Y.cpy:L11} holds no data.
 *
 * <p>{@link #ofUnmaskedCardNumber} masks the card number, and the canonical constructor rejects an
 * unmasked value. A card lookup keys on all sixteen characters, so a caller resolves the card
 * first and builds the event second.
 *
 * <p>The card service outbox writer turns this record into text with Jackson and stores that text
 * in an {@code outbox_event} row, inside the one transaction that writes the card row. For the
 * choices this record carries, read {@code card-platform/docs/decision-log.md}.
 *
 * @param envelope         the five fields every platform event carries, written flat beside the
 *                         six components below
 * @param maskedCardNumber twelve mask characters then the last four digits of the card number,
 *                         sixteen characters in all. ADDITIVE: no CardDemo program masks a Primary
 *                         Account Number, and {@code app/bms/COCRDSL.bms:L96-L100} defines the card
 *                         detail field unprotected at the full sixteen characters. Width from
 *                         {@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5}
 * @param accountId        the eleven-digit account identifier, equal to the envelope
 *                         {@code aggregateId} and to the Kafka message key. From
 *                         {@code CARD-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT02Y.cpy:L6}, the
 *                         width {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 *                         also carries. A leading zero belongs to the value
 * @param changeType       which mutation produced the event, and the one constant the
 *                         {@code changeType} enumeration of
 *                         {@code schemas/card-state-changed-v1.json} allows. ADDITIVE: no copybook
 *                         and no program under {@code app/cbl/} records a change type
 * @param embossedName     the name embossed on the card, at most fifty characters. From
 *                         {@code CARD-EMBOSSED-NAME PIC X(50)} at {@code app/cpy/CVACT02Y.cpy:L8}
 * @param expirationDate       the card expiry date, ten characters shaped {@code YYYY-MM-DD}. From
 *                         {@code CARD-EXPIRAION-DATE PIC X(10)} at
 *                         {@code app/cpy/CVACT02Y.cpy:L9}, which the source spells with the
 *                         transposed word; the target spells it {@code expirationDate}.
 *                         {@code app/cbl/COCRDUPC.cbl:L1505-L1507} slices the field into a
 *                         four-character year, a two-character month and a two-character day
 * @param activeStatus     the one-character active status after the update,
 *                         {@link #ACTIVE_STATUS_ACTIVE} or {@link #ACTIVE_STATUS_INACTIVE}. From
 *                         {@code CARD-ACTIVE-STATUS PIC X(01)} at {@code app/cpy/CVACT02Y.cpy:L10},
 *                         whose domain {@code app/cbl/COCRDUPC.cbl:L91} fixes to those two literals
 */
public record CardStateChanged(@JsonUnwrapped EventEnvelope envelope, String maskedCardNumber,
        String accountId, ChangeKind changeType, String embossedName, String expirationDate,
        String activeStatus) {

    /**
     * The routing discriminator every instance carries, and the simple name of this record.
     *
     * <p>{@link #ofUnmaskedCardNumber} stamps this value, and the canonical constructor accepts no
     * envelope carrying another one.
     */
    public static final String EVENT_TYPE = CardStateChanged.class.getSimpleName();

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
     * The form {@link #accountId()} takes, from {@link EventEnvelope#AGGREGATE_ID_PATTERN}: exactly
     * eleven decimal digits, matching {@code CARD-ACCT-ID PIC 9(11)} at
     * {@code app/cpy/CVACT02Y.cpy:L6}.
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

    /**
     * Which mutation produced one event, and the type {@code changeType} carries.
     *
     * <p>ADDITIVE. The source publishes nothing and records no change type. The single constant
     * names the mutation the source performs and is the only value the {@code changeType}
     * enumeration of {@code schemas/card-state-changed-v1.json} allows.
     */
    public enum ChangeKind {

        /**
         * The card field update the online path performs, validated at
         * {@code app/cbl/COCRDUPC.cbl:L190-L202} and rewritten at {@code app/cbl/COCRDUPC.cbl}.
         */
        CARD_UPDATED
    }

    /** {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check every instance passes. */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /** {@link #ACCOUNT_ID_PATTERN} compiled, and the check every instance passes. */
    private static final Pattern ACCOUNT_ID_MATCHER = Pattern.compile(ACCOUNT_ID_PATTERN);

    /**
     * Checks the envelope and all six payload components, and changes no value it accepts.
     *
     * <p>{@link EventEnvelope} checks its own five fields, {@code eventType} must equal
     * {@link #EVENT_TYPE}, and {@code accountId} must equal the envelope {@code aggregateId}. One
     * account identifier therefore reaches the Kafka message key, the envelope and the payload.
     *
     * <p>An unmasked card number fails {@link #MASKED_CARD_NUMBER_PATTERN}, so a caller that
     * bypasses {@link #ofUnmaskedCardNumber} still cannot build an event carrying a full card
     * number. That failure reports the length of the rejected value and never the value, and no
     * message here carries a card number or an account identifier.
     *
     * <p>Every message names the component that failed and states what the component accepts. A
     * component this constructor accepts survives a write and a read unchanged.
     *
     * @throws NullPointerException     when the envelope or any payload component is {@code null}
     * @throws IllegalArgumentException when the envelope does not carry {@link #EVENT_TYPE}, when a
     *                                  payload component misses its pattern or its width, or when
     *                                  {@code accountId} differs from the envelope
     *                                  {@code aggregateId}
     */
    public CardStateChanged {
        Objects.requireNonNull(envelope, "envelope must be present");
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new IllegalArgumentException("envelope.eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + envelope.eventType() + "\"");
        }

        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber must be present");
        if (!MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must hold "
                    + MASKED_CARD_NUMBER_LENGTH + " characters matching "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value holds "
                    + maskedCardNumber.length() + " characters");
        }

        Objects.requireNonNull(accountId, "accountId must be present");
        if (!ACCOUNT_ID_MATCHER.matcher(accountId).matches()) {
            throw new IllegalArgumentException("accountId must match " + ACCOUNT_ID_PATTERN
                    + " and the supplied value holds " + accountId.length() + " characters");
        }
        if (!accountId.equals(envelope.aggregateId())) {
            throw new IllegalArgumentException(
                    "accountId must equal the envelope aggregateId, and the two supplied values "
                            + "differ");
        }

        Objects.requireNonNull(changeType, "changeType must be present");

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
     * {@link #SCHEMA_VERSION} and the current moment, and takes {@code accountId} as the envelope
     * {@code aggregateId}.
     *
     * <p>A {@code null}, blank or short argument masks to sixteen mask characters, which the
     * canonical constructor then rejects. No argument value passes through unmasked.
     *
     * <p>{@link ChangeKind#CARD_UPDATED} is stamped, which is the one constant the
     * {@code changeType} enumeration of {@code schemas/card-state-changed-v1.json} allows.
     *
     * @param unmaskedCardNumber the card number as stored in {@code CARD-NUM}, at its full width
     * @param accountId          the eleven-digit account identifier, and the Kafka message key
     * @param embossedName       the name embossed on the card after the update
     * @param expirationDate         the card expiry date after the update, ten characters
     * @param activeStatus       the one-character active status after the update
     * @return an event carrying the masked card number, the four supplied values and a fresh
     *         envelope
     * @throws NullPointerException     when a payload argument other than
     *                                  {@code unmaskedCardNumber} is {@code null}
     * @throws IllegalArgumentException when an argument misses the form this record states
     */
    public static CardStateChanged ofUnmaskedCardNumber(String unmaskedCardNumber, String accountId,
            String embossedName, String expirationDate, String activeStatus) {
        return new CardStateChanged(EventEnvelope.of(EVENT_TYPE, accountId),
                PanMasker.maskCardNumber(unmaskedCardNumber), accountId, ChangeKind.CARD_UPDATED,
                embossedName, expirationDate, activeStatus);
    }
}
