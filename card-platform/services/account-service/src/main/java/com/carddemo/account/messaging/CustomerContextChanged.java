package com.carddemo.account.messaging;

import com.carddemo.events.EventEnvelope;
import com.carddemo.events.serde.PublishGate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One customer record rewrite. The account service publishes it on a mutation and never on a read.
 *
 * <p>Sixteen components serialize at one level: the five {@link EventEnvelope} components first,
 * then the eleven payload components. No {@code envelope} key appears in the JavaScript Object
 * Notation (JSON) text, and {@link #toEnvelope()} returns the five envelope components as one
 * {@link EventEnvelope}.
 *
 * <p>{@code app/cbl/COACTUPC.cbl:L4066} rewrites the account record and
 * {@code app/cbl/COACTUPC.cbl:L4086} rewrites the customer record in one unit of work. The account
 * half travels on {@link AccountStateChanged} and this event carries the customer half, so a
 * consumer that reads credit values alone receives no cardholder name, address or credit score.
 *
 * <p>Widths come from the 500-byte customer record at {@code app/cpy/CVCUS01Y.cpy}.
 * {@code app/cbl/CBSTM03A.CBL:L461-L484} renders every one of the ten cardholder fields on a
 * statement, so a consumer that renders a cardholder alert keeps its own projection current from
 * this event.
 *
 * <p>Eight fields of the customer record reach no event. {@code CUST-ID PIC 9(09)} at
 * {@code app/cpy/CVCUS01Y.cpy:L5} identifies a person and every consumer reads the context by
 * account. {@code CUST-PHONE-NUM-1} and {@code CUST-PHONE-NUM-2} at
 * {@code app/cpy/CVCUS01Y.cpy:L15-L16}, {@code CUST-SSN} at {@code :L17},
 * {@code CUST-GOVT-ISSUED-ID} at {@code :L18}, {@code CUST-DOB-YYYY-MM-DD} at {@code :L19},
 * {@code CUST-EFT-ACCOUNT-ID} at {@code :L20} and {@code CUST-PRI-CARD-HOLDER-IND} at {@code :L21}
 * carry no component. The 168-byte trailing {@code FILLER} at {@code :L23} holds no data.
 *
 * <p>ADDITIVE: the five envelope components. No copybook and no program under {@code app/cbl/}
 * records an event envelope. The addition is recorded in
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId      the idempotency key. A consumer checks it, then writes its side effects and
 *                     the marker row in one local transaction, and acknowledges only after that
 *                     transaction commits
 * @param eventType    the routing discriminator, always {@link #EVENT_TYPE}
 * @param schemaVersion the contract version, always {@link #SCHEMA_VERSION}
 * @param occurredAt   the moment the producer wrote the event, in Coordinated Universal Time
 * @param aggregateId  the account identifier and the message key, equal to {@code accountId}, so the
 *                     events of one account stay in one partition and stay in order
 * @param accountId    the eleven-digit identifier of the account whose customer record was
 *                     rewritten, from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}
 * @param firstName    the cardholder first name, from {@code CUST-FIRST-NAME PIC X(25)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L6}
 * @param middleName   the cardholder middle name, from {@code CUST-MIDDLE-NAME PIC X(25)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L7}
 * @param lastName     the cardholder last name, from {@code CUST-LAST-NAME PIC X(25)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L8}
 * @param addressLine1 the first address line, from {@code CUST-ADDR-LINE-1 PIC X(50)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L9}
 * @param addressLine2 the second address line, from {@code CUST-ADDR-LINE-2 PIC X(50)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L10}
 * @param addressLine3 the third address line, from {@code CUST-ADDR-LINE-3 PIC X(50)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L11}
 * @param stateCode    the state code, from {@code CUST-ADDR-STATE-CD PIC X(02)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L12}
 * @param countryCode  the country code, from {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L13}
 * @param zipCode      the postal code, from {@code CUST-ADDR-ZIP PIC X(10)} at
 *                     {@code app/cpy/CVCUS01Y.cpy:L14}
 * @param ficoScore    the credit score as three digits, from
 *                     {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22}
 */
public record CustomerContextChanged(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String accountId,
        String firstName,
        String middleName,
        String lastName,
        String addressLine1,
        String addressLine2,
        String addressLine3,
        String stateCode,
        String countryCode,
        String zipCode,
        String ficoScore) {

    /** The contract version this record carries, from {@link EventEnvelope#SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = EventEnvelope.SCHEMA_VERSION;

    /** The value {@code eventType} always holds: the simple name of this record. */
    public static final String EVENT_TYPE = CustomerContextChanged.class.getSimpleName();

    /**
     * The form {@code accountId} and {@code aggregateId} both take: exactly eleven decimal digits,
     * from {@code ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT01Y.cpy:L5}.
     */
    public static final String ACCOUNT_ID_PATTERN = EventEnvelope.AGGREGATE_ID_PATTERN;

    /**
     * Widest value each of {@code firstName}, {@code middleName} and {@code lastName} holds, from
     * {@code PIC X(25)} at {@code app/cpy/CVCUS01Y.cpy:L6-L8}.
     */
    public static final int NAME_MAX_LENGTH = 25;

    /**
     * Widest value each address line holds, from {@code PIC X(50)} at
     * {@code app/cpy/CVCUS01Y.cpy:L9-L11}.
     */
    public static final int ADDRESS_LINE_MAX_LENGTH = 50;

    /**
     * Widest {@code stateCode} this record holds, from {@code CUST-ADDR-STATE-CD PIC X(02)} at
     * {@code app/cpy/CVCUS01Y.cpy:L12}.
     */
    public static final int STATE_CODE_MAX_LENGTH = 2;

    /**
     * Widest {@code countryCode} this record holds, from {@code CUST-ADDR-COUNTRY-CD PIC X(03)} at
     * {@code app/cpy/CVCUS01Y.cpy:L13}.
     */
    public static final int COUNTRY_CODE_MAX_LENGTH = 3;

    /**
     * Widest {@code zipCode} this record holds, from {@code CUST-ADDR-ZIP PIC X(10)} at
     * {@code app/cpy/CVCUS01Y.cpy:L14}.
     */
    public static final int ZIP_CODE_MAX_LENGTH = 10;

    /**
     * The form {@code ficoScore} takes: exactly three decimal digits.
     *
     * <p>Width from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22}. A
     * text field keeps a leading zero.
     */
    public static final String FICO_SCORE_PATTERN = "^[0-9]{3}$";

    /** Digits in {@code ficoScore}, from {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    public static final int FICO_SCORE_LENGTH = 3;

    /** {@link #FICO_SCORE_PATTERN} compiled, and the check {@code ficoScore} passes. */
    private static final Pattern FICO_SCORE_MATCHER = Pattern.compile(FICO_SCORE_PATTERN);

    /**
     * Checks all sixteen components against the widths their source fields declare.
     *
     * <p>The five envelope components pass through {@link EventEnvelope}, which applies the envelope
     * rules. {@code eventType} must equal {@link #EVENT_TYPE}, and {@code accountId} must equal
     * {@code aggregateId}, which gives {@code accountId} the eleven-digit form
     * {@link #ACCOUNT_ID_PATTERN} states.
     *
     * <p>Every message names the component that failed and carries its length, never its value, so a
     * failure writes no cardholder value to a log.
     *
     * @throws NullPointerException     when a reference component is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is not {@link #EVENT_TYPE}, when
     *                                  {@code schemaVersion} is not {@link #SCHEMA_VERSION}, when
     *                                  {@code aggregateId} is {@code null} or is not eleven decimal
     *                                  digits, when {@code accountId} differs from
     *                                  {@code aggregateId}, when a cardholder component is wider
     *                                  than its source field, or when {@code ficoScore} is not three
     *                                  decimal digits
     */
    public CustomerContextChanged {
        EventEnvelope envelope =
                new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be " + EVENT_TYPE
                    + " and the supplied value is " + eventType);
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion must be " + SCHEMA_VERSION
                    + " and the supplied value is " + schemaVersion);
        }
        Objects.requireNonNull(accountId, "accountId must be present");
        if (!accountId.equals(envelope.aggregateId())) {
            throw new IllegalArgumentException(
                    "accountId must equal aggregateId and the two supplied values differ");
        }

        requireWidth("firstName", firstName, NAME_MAX_LENGTH);
        requireWidth("middleName", middleName, NAME_MAX_LENGTH);
        requireWidth("lastName", lastName, NAME_MAX_LENGTH);
        requireWidth("addressLine1", addressLine1, ADDRESS_LINE_MAX_LENGTH);
        requireWidth("addressLine2", addressLine2, ADDRESS_LINE_MAX_LENGTH);
        requireWidth("addressLine3", addressLine3, ADDRESS_LINE_MAX_LENGTH);
        requireWidth("stateCode", stateCode, STATE_CODE_MAX_LENGTH);
        requireWidth("countryCode", countryCode, COUNTRY_CODE_MAX_LENGTH);
        requireWidth("zipCode", zipCode, ZIP_CODE_MAX_LENGTH);

        Objects.requireNonNull(ficoScore, "ficoScore must be present");
        if (!FICO_SCORE_MATCHER.matcher(ficoScore).matches()) {
            throw new IllegalArgumentException("ficoScore must match " + FICO_SCORE_PATTERN
                    + " and the supplied value holds " + ficoScore.length() + " characters");
        }
    }

    /**
     * Builds an event, stamping a fresh envelope for the account the rewrite belongs to.
     *
     * @param accountId    the eleven-digit account identifier
     * @param firstName    the cardholder first name
     * @param middleName   the cardholder middle name
     * @param lastName     the cardholder last name
     * @param addressLine1 the first address line
     * @param addressLine2 the second address line
     * @param addressLine3 the third address line
     * @param stateCode    the state code
     * @param countryCode  the country code
     * @param zipCode      the postal code
     * @param ficoScore    the credit score as a magnitude, rendered here at three digits
     * @return an event whose envelope carries a fresh identifier and the current moment
     * @throws NullPointerException     when a reference component is {@code null}
     * @throws IllegalArgumentException when a component misses the form this record states
     */
    public static CustomerContextChanged of(String accountId, String firstName, String middleName,
            String lastName, String addressLine1, String addressLine2, String addressLine3,
            String stateCode, String countryCode, String zipCode, BigDecimal ficoScore) {
        return from(EventEnvelope.of(EVENT_TYPE, accountId), accountId, firstName, middleName,
                lastName, addressLine1, addressLine2, addressLine3, stateCode, countryCode, zipCode,
                ficoScore);
    }

    /**
     * Builds an event on an envelope a caller already holds.
     *
     * <p>A replay of a stored event supplies the envelope it stored, so the replayed event keeps its
     * original identifier and timestamp.
     *
     * @param envelope     the five envelope components to carry
     * @param accountId    the eleven-digit account identifier, equal to the envelope
     *                     {@code aggregateId}
     * @param firstName    the cardholder first name
     * @param middleName   the cardholder middle name
     * @param lastName     the cardholder last name
     * @param addressLine1 the first address line
     * @param addressLine2 the second address line
     * @param addressLine3 the third address line
     * @param stateCode    the state code
     * @param countryCode  the country code
     * @param zipCode      the postal code
     * @param ficoScore    the credit score as a magnitude, rendered here at three digits
     * @return an event carrying the supplied envelope and payload
     * @throws NullPointerException     when {@code envelope} or another reference component is
     *                                  {@code null}
     * @throws IllegalArgumentException when a component misses the form this record states
     */
    public static CustomerContextChanged from(EventEnvelope envelope, String accountId,
            String firstName, String middleName, String lastName, String addressLine1,
            String addressLine2, String addressLine3, String stateCode, String countryCode,
            String zipCode, BigDecimal ficoScore) {
        Objects.requireNonNull(envelope, "envelope must be present");

        return new CustomerContextChanged(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(), accountId,
                firstName, middleName, lastName, addressLine1, addressLine2, addressLine3,
                stateCode, countryCode, zipCode, toWireScore(ficoScore));
    }

    /**
     * Returns the five envelope components as one {@link EventEnvelope}.
     *
     * <p>The returned envelope equals the one the canonical constructor checked. Serialization reads
     * the sixteen record components and never this method, so no {@code envelope} key reaches a
     * topic.
     *
     * @return the envelope this event carries
     */
    public EventEnvelope toEnvelope() {
        return new EventEnvelope(eventId, eventType, schemaVersion, occurredAt, aggregateId);
    }

    /**
     * Serializes this event through the one publish-side gate every event of this platform passes.
     *
     * <p>{@link PublishGate} writes the flat wire form, screens every property and every value for
     * cardholder data, checks the result against {@code schemas/customer-context-changed-v1.json} in
     * {@code com.carddemo:event-contracts}, refuses a version no released contract publishes, and
     * refuses a document wider than the platform ceiling. The returned text is what a caller stores
     * in the {@code payload} column of {@code outbox_event} and what the relay later hands to the
     * broker unchanged.
     *
     * <p>The screens matter most on this event, because it is the one payload of this platform whose
     * fields a person filled in: a name, three address lines and a postal code, from
     * {@code app/cpy/CVCUS01Y.cpy:L6-L16}. A cardholder number typed into an address line reaches
     * this method, and does not reach a row.
     *
     * @return this event as validated JavaScript Object Notation text, in UTF-8
     * @throws IllegalArgumentException when this event breaks its schema, carries a value the
     *         cardholder-data screens refuse, declares a version no contract publishes, or exceeds
     *         the platform event ceiling
     */
    public String toValidatedJson() {
        return PublishGate.checkedJsonOf(this);
    }

    /**
     * Renders the credit score at the width of its Picture clause.
     *
     * <p>{@code CUST-FICO-CREDIT-SCORE PIC 9(03)} at {@code app/cpy/CVCUS01Y.cpy:L22} is a
     * three-digit display field, so the rendered text keeps a leading zero.
     *
     * @param ficoScore the credit score as a magnitude
     * @return the score as exactly {@link #FICO_SCORE_LENGTH} digits when it fits that width
     * @throws NullPointerException when {@code ficoScore} is {@code null}
     */
    private static String toWireScore(BigDecimal ficoScore) {
        Objects.requireNonNull(ficoScore, "ficoScore must be present");

        String digits = ficoScore.setScale(0, RoundingMode.DOWN).toPlainString();
        return digits.length() >= FICO_SCORE_LENGTH
                ? digits
                : "0".repeat(FICO_SCORE_LENGTH - digits.length()) + digits;
    }

    /**
     * Checks one cardholder component against the width of its source field.
     *
     * @param component the component name a failure message carries
     * @param value     the value to check
     * @param maximum   the widest accepted value
     * @throws NullPointerException     when {@code value} is {@code null}
     * @throws IllegalArgumentException when {@code value} is wider than {@code maximum}
     */
    private static void requireWidth(String component, String value, int maximum) {
        Objects.requireNonNull(value, component + " must be present");
        if (value.length() > maximum) {
            throw new IllegalArgumentException(component + " must hold at most " + maximum
                    + " characters and the supplied value holds " + value.length());
        }
    }

    /**
     * Renders the technical identifiers and withholds every value the payload carries.
     *
     * <p>This override replaces the representation the compiler generates for a record. That
     * generated form prints the account identifier twice, the cardholder name, the address and the
     * credit score.
     *
     * @return the identifiers of this event with every payload value withheld, never {@code null}
     */
    @Override
    public String toString() {
        return "CustomerContextChanged[eventId=" + eventId + ", eventType=" + eventType
                + ", schemaVersion=" + schemaVersion + ", occurredAt=" + occurredAt
                + ", aggregateId=" + EventEnvelope.WITHHELD + ", accountId="
                + EventEnvelope.WITHHELD + ", 10 cardholder fields " + EventEnvelope.WITHHELD + "]";
    }
}
