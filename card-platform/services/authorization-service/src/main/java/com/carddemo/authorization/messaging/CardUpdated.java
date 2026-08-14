package com.carddemo.authorization.messaging;

import com.carddemo.cobol.PicClause;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * The card state this service reads to keep {@code card_xref} observably current, from one
 * schema-checked {@code CardUpdated} message.
 *
 * <p>ADDITIVE IN FULL, for the reason {@link AccountStateChanged} gives: the source reads the
 * cross-reference dataset itself at {@code app/cbl/CBTRN02C.cbl:L382} and has no copy to refresh.
 *
 * <p>What this message can and cannot do to the replica is fixed by one property of the contract, and
 * it is worth stating plainly. The message carries a masked card number and never a Primary Account
 * Number, because no full card number travels on any topic in this platform. {@code card_xref} is
 * keyed by the full sixteen-character number, so a message carrying only the last four digits cannot
 * name one row with certainty and cannot create a row at all. It carries the account identifier,
 * though, so it does confirm that the owning service has just written the card data of that account.
 * The consumer therefore refreshes the observation on that account's rows and writes no mapping
 * field. See {@code CardUpdatedConsumer} for what that means when two cards of one account share
 * their last four digits.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId          the identifier the processed-event marker records
 * @param occurredAt       the producer's clock, which orders two changes to one card
 * @param accountId        the account the card belongs to, eleven digits
 * @param maskedCardNumber twelve mask characters and the last four digits, sixteen in all
 */
public record CardUpdated(
        UUID eventId,
        Instant occurredAt,
        String accountId,
        String maskedCardNumber) {

    /** The one value the message's {@code eventType} holds. */
    public static final String EVENT_TYPE = "CardUpdated";

    /** How many digits of the card number a masked value leaves visible. */
    public static final int VISIBLE_DIGITS = 4;

    /** The shape a masked card number holds, from the schema document. */
    private static final Pattern MASKED_CARD_NUMBER = Pattern.compile("^\\*{12}[0-9]{4}$");

    /** Property names this record reads, each one required by the schema document. */
    private static final String EVENT_ID = "eventId";
    private static final String OCCURRED_AT = "occurredAt";
    private static final String ACCOUNT_ID = "accountId";
    private static final String MASKED_CARD_NUMBER_PROPERTY = "maskedCardNumber";

    /**
     * Holds every component present, the account identifier at its declared width and the card number
     * masked.
     *
     * <p>The masked shape is checked rather than assumed. A component that reached this record
     * unmasked would be a full card number, and this service must not write one to a log line or hold
     * one anywhere but the cross-reference key it authorizes against.
     *
     * @throws NullPointerException     when a component is absent
     * @throws IllegalArgumentException when the account identifier is not
     *                                  {@value PicClause#ACCT_ID_WIDTH} digits, or when the card
     *                                  number is not masked
     */
    public CardUpdated {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(maskedCardNumber, "maskedCardNumber is required");

        if (accountId.length() != PicClause.ACCT_ID_WIDTH) {
            throw new IllegalArgumentException(ACCOUNT_ID + " must hold exactly "
                    + PicClause.ACCT_ID_WIDTH + " digits, found " + accountId.length());
        }
        if (!MASKED_CARD_NUMBER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException(MASKED_CARD_NUMBER_PROPERTY
                    + " must carry twelve mask characters and four digits. The value is not"
                    + " reported, because an unmasked one would be a card number");
        }
    }

    /**
     * Reads one schema-checked message into this record.
     *
     * @param message the validated message tree
     * @return the components this service reads
     * @throws NullPointerException     when {@code message} is {@code null} or a required property is
     *                                  absent
     * @throws IllegalArgumentException when a component breaks a width or the masked shape
     */
    public static CardUpdated from(JsonNode message) {
        Objects.requireNonNull(message, "message is required");

        return new CardUpdated(
                UUID.fromString(requiredText(message, EVENT_ID)),
                Instant.parse(requiredText(message, OCCURRED_AT)),
                requiredText(message, ACCOUNT_ID),
                requiredText(message, MASKED_CARD_NUMBER_PROPERTY));
    }

    /**
     * The four digits the mask leaves visible, as a SQL suffix pattern.
     *
     * <p>The result is what a {@code LIKE} comparison matches a stored card number's tail against. It
     * is derived here rather than at the call site so the mask width lives in one place.
     *
     * @return a percent sign followed by the four visible digits
     */
    public String visibleDigitsSuffix() {
        return "%" + maskedCardNumber.substring(maskedCardNumber.length() - VISIBLE_DIGITS);
    }

    /**
     * Reads one required text property, naming the property when it is absent.
     *
     * @param message  the validated message tree
     * @param property the property name
     * @return the property value
     * @throws NullPointerException when the property is absent or empty
     */
    private static String requiredText(JsonNode message, String property) {
        JsonNode value = message.path(property);
        if (value.isMissingNode() || value.isNull()) {
            throw new NullPointerException(EVENT_TYPE + " carries no " + property);
        }
        String text = value.asString("");
        if (text.isEmpty()) {
            throw new NullPointerException(EVENT_TYPE + " carries an empty " + property);
        }
        return text;
    }
}
