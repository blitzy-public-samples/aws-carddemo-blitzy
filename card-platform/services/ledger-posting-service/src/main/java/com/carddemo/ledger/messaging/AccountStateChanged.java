package com.carddemo.ledger.messaging;

import com.carddemo.cobol.PicClause;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * The account state this service replicates into {@code account_balance_projection}, read from one
 * schema-checked {@code AccountStateChanged} message.
 *
 * <p>ADDITIVE IN FULL. No Common Business Oriented Language (COBOL) program publishes a state change.
 * {@code app/cbl/CBTRN02C.cbl:L545-L560} reads and rewrites the account record itself, so the posting
 * program and the account it posts to were never two copies of anything. This record exists because
 * the target posts against a copy, and a copy has to be told when the original moved.
 *
 * <p>Three components matter here, and they are the three columns the projection holds:
 * {@code currentBalance}, {@code currentCycleCredit} and {@code currentCycleDebit}. The message also
 * carries a credit limit and an expiry date, and this record deliberately drops both. Those two are
 * read by the decline rules, the decline rules belong to the authorization service, and a component
 * this service holds but never reads is a component that can go stale without anything noticing.
 *
 * <p>{@code changeKind} is dropped for a different reason. The two values the schema admits are
 * {@code ACCOUNT_UPDATED} and {@code BILLING_CYCLE_CLOSED}, and both mean the same thing to a replica:
 * these are the current numbers. The cycle close at {@code app/cbl/CBACT04C.cbl:L353-L354} zeroes both
 * accumulators, so it arrives as an ordinary change carrying zeroes rather than as a separate command.
 *
 * <p>This is the consuming side of a contract the account service owns. The shared contract is the
 * document at {@code libs/event-contracts/src/main/resources/schemas/account-state-changed-v1.json},
 * not a Java class: that library validates the message against the document and names no class to
 * build, because the record belongs to the service that owns the aggregate. Each consumer reads the
 * checked tree into a shape of its own, which is why the authorization service's record of the same
 * message holds a different set of components from this one.
 *
 * <p>Binding here rather than importing the producer's class is a structural requirement and not a
 * preference. No service module may depend on another, so an import of the account service's record
 * would not compile. The schema is what keeps the two in step.
 *
 * <p>Decisions: {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId            the identifier the processed-event marker records
 * @param occurredAt         the producer's clock, which orders two changes to one account
 * @param accountId          the account this change describes, eleven digits
 * @param currentBalance     {@code ACCT-CURR-BAL} at {@code app/cpy/CVACT01Y.cpy:L7}, scale 2
 * @param currentCycleCredit {@code ACCT-CURR-CYC-CREDIT} at {@code app/cpy/CVACT01Y.cpy:L12},
 *                           scale 2
 * @param currentCycleDebit  {@code ACCT-CURR-CYC-DEBIT} at {@code app/cpy/CVACT01Y.cpy:L13},
 *                           scale 2
 */
public record AccountStateChanged(
        UUID eventId,
        Instant occurredAt,
        String accountId,
        BigDecimal currentBalance,
        BigDecimal currentCycleCredit,
        BigDecimal currentCycleDebit) {

    /** The one value the message's {@code eventType} holds. */
    public static final String EVENT_TYPE = "AccountStateChanged";

    /** Property names this record reads, each one required by the schema document. */
    private static final String EVENT_ID = "eventId";
    private static final String OCCURRED_AT = "occurredAt";
    private static final String ACCOUNT_ID = "accountId";
    private static final String CURRENT_BALANCE = "currentBalance";
    private static final String CURRENT_CYCLE_CREDIT = "currentCycleCredit";
    private static final String CURRENT_CYCLE_DEBIT = "currentCycleDebit";

    /**
     * Holds every component present and every scale at what the replica column declares.
     *
     * <p>The schema has already refused a malformed message, so these checks are the second line and
     * not the first. They are here because the replica is written by a native upsert, which does not
     * pass through the entity constructor that would otherwise enforce the same scales: a value that
     * reached the column at the wrong scale would be silently rounded or padded by the database, and
     * a rounded cycle balance is exactly the kind of divergence this platform exists to prevent.
     *
     * @throws NullPointerException     when a component is absent
     * @throws IllegalArgumentException when the account identifier is not
     *                                  {@value PicClause#ACCT_ID_WIDTH} digits, or when a monetary
     *                                  component is not at scale
     *                                  {@value PicClause#ACCT_CURR_BAL_SCALE}
     */
    public AccountStateChanged {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(currentBalance, "currentBalance is required");
        Objects.requireNonNull(currentCycleCredit, "currentCycleCredit is required");
        Objects.requireNonNull(currentCycleDebit, "currentCycleDebit is required");

        requireDigits(ACCOUNT_ID, accountId, PicClause.ACCT_ID_WIDTH);
        requireScale(CURRENT_BALANCE, currentBalance, PicClause.ACCT_CURR_BAL_SCALE);
        requireScale(CURRENT_CYCLE_CREDIT, currentCycleCredit,
                PicClause.ACCT_CURR_CYC_CREDIT_SCALE);
        requireScale(CURRENT_CYCLE_DEBIT, currentCycleDebit, PicClause.ACCT_CURR_CYC_DEBIT_SCALE);
    }

    /**
     * Reads one schema-checked message into this record.
     *
     * <p>Money arrives as a decimal string and never as a JSON number, which the schema enforces with
     * a string pattern. Reading it as text and constructing a {@link BigDecimal} from that text keeps
     * the scale the producer sent. A JSON number would have been parsed into a binary floating-point
     * double by most readers, and the whole correctness argument of this platform rests on that never
     * happening to an amount.
     *
     * @param message the validated message tree
     * @return the components this service replicates
     * @throws NullPointerException     when {@code message} is {@code null} or a required property is
     *                                  absent
     * @throws IllegalArgumentException when a component breaks a width or a scale
     */
    public static AccountStateChanged from(JsonNode message) {
        Objects.requireNonNull(message, "message is required");

        return new AccountStateChanged(
                UUID.fromString(requiredText(message, EVENT_ID)),
                Instant.parse(requiredText(message, OCCURRED_AT)),
                requiredText(message, ACCOUNT_ID),
                new BigDecimal(requiredText(message, CURRENT_BALANCE)),
                new BigDecimal(requiredText(message, CURRENT_CYCLE_CREDIT)),
                new BigDecimal(requiredText(message, CURRENT_CYCLE_DEBIT)));
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

    /**
     * Checks one component against the digit class and width its source Picture clause declares.
     *
     * @param property the property name the message reports
     * @param value    the value under test
     * @param width    the width the Picture clause declares
     * @throws IllegalArgumentException when the width or the digit class does not match
     */
    private static void requireDigits(String property, String value, int width) {
        if (value.length() != width) {
            throw new IllegalArgumentException(property + " must hold exactly " + width
                    + " digits, found " + value.length());
        }
        for (int at = 0; at < width; at++) {
            char digit = value.charAt(at);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(
                        property + " must hold digits only, and one character is not a digit");
            }
        }
    }

    /**
     * Checks one monetary component against the scale its source Picture clause declares.
     *
     * <p>The scale is read and never adjusted. A value at another scale means the producer and this
     * consumer disagree about the contract, and that stops here rather than reaching a column.
     *
     * @param property the property name the message reports
     * @param value    the value under test
     * @param scale    the scale the Picture clause declares
     * @throws IllegalArgumentException when the scales differ
     */
    private static void requireScale(String property, BigDecimal value, int scale) {
        if (value.scale() != scale) {
            throw new IllegalArgumentException(property + " must carry scale " + scale + ", found "
                    + value.scale());
        }
    }
}
