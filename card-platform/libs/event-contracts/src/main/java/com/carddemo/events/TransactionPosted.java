package com.carddemo.events;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * The event the ledger-posting service publishes after it applies one posting to an account
 * balance.
 *
 * <p>The notification service consumes it from the {@code transaction.posted} topic and renders a
 * cardholder alert. {@code schemas/transaction-posted-v1.json} is the contract every instance
 * satisfies.
 *
 * <p>Provenance is one paragraph of one program. {@code 2800-UPDATE-ACCOUNT-REC} opens at
 * {@code app/cbl/CBTRN02C.cbl:L545} and adds the transaction amount to {@code ACCT-CURR-BAL} at
 * {@code app/cbl/CBTRN02C.cbl:L547}. {@link #newBalance()} carries that field after the add. Its
 * width comes from {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}, so ten
 * digits sit ahead of the decimal point.
 *
 * <p>The same paragraph routes the amount into one of two billing-cycle accumulators at
 * {@code app/cbl/CBTRN02C.cbl:L548-L551}. Those two fields sit at
 * {@code app/cpy/CVACT01Y.cpy:L13-L14} and no event of this module carries either one.
 *
 * <p>Four payload fields come from the posted transaction record. {@code TRAN-ID PIC X(16)} at
 * {@code app/cpy/CVTRA05Y.cpy:L5} gives {@link #transactionId()} and
 * {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10} gives {@link #amount()}.
 * {@code TRAN-CARD-NUM PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L15} gives
 * {@link #maskedCardNumber()} and {@code TRAN-PROC-TS PIC X(26)} at
 * {@code app/cpy/CVTRA05Y.cpy:L17} gives {@link #postedAt()}.
 * {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7} gives {@link #accountId()} and
 * {@link #aggregateId()}, which hold one value. The trailing filler of both records,
 * {@code FILLER PIC X(178)} at {@code app/cpy/CVACT01Y.cpy:L17} and {@code FILLER PIC X(20)} at
 * {@code app/cpy/CVTRA05Y.cpy:L18}, is a deliberate omission.
 *
 * <p>{@link #maskedCardNumber()} is ADDITIVE. No CardDemo program masks a Primary Account Number
 * (PAN): {@code app/bms/COCRDSL.bms:L96-L99} defines the card detail field at the full sixteen
 * characters. Masking happens where the event is serialized, so the field reaches this record
 * already masked. The five envelope fields are ADDITIVE in full and {@link EventEnvelope} defines
 * them.
 *
 * <p>{@link #postedAt()} is text, not a temporal type, and its layout is not ISO-8601. Twenty-six
 * characters run {@code YYYY-MM-DD-HH.MM.SS.NN0000}, with the three dashes moved at
 * {@code app/cbl/CBTRN02C.cbl:L702} and the three dots at {@code app/cbl/CBTRN02C.cbl:L703}.
 * Precision is hundredths of a second: {@code DB2-MIL PIC 9(002)} at
 * {@code app/cbl/CBTRN02C.cbl:L173} holds two digits and {@code app/cbl/CBTRN02C.cbl:L701} moves
 * four zeros behind them. A consumer truncates to hundredths before it compares two values byte
 * for byte.
 *
 * <p>Both money fields are {@link BigDecimal} and both travel as decimal strings with two
 * fractional digits, never as JSON numbers. Every scale change applies {@link #MONEY_ROUNDING}.
 * The two integer widths differ, and {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} and
 * {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} keep them apart. Negative values are ordinary traffic
 * on both fields.
 *
 * <p>The wire form is flat. A serialized event holds the five envelope fields and the six payload
 * fields in one JavaScript Object Notation (JSON) object, so no {@code envelope} key reaches a
 * topic. {@link #envelope()} returns the carrier a producer builds and passes, and
 * {@link #of(EventEnvelope, String, BigDecimal, String, BigDecimal, String)} takes it back.
 *
 * <p>For the path this event travels from publish to consume, read
 * {@code card-platform/docs/event-flow.md}; for the reasoning behind the choices above, read
 * {@code card-platform/docs/decision-log.md}.
 *
 * @param eventId          the idempotency key, a Universally Unique Identifier (UUID) that
 *                         serializes as thirty-six lower-case characters. ADDITIVE
 * @param eventType        the routing discriminator, always {@link #EVENT_TYPE}. ADDITIVE
 * @param schemaVersion    the contract version, always {@link EventEnvelope#SCHEMA_VERSION}.
 *                         ADDITIVE
 * @param occurredAt       the moment the ledger wrote the event, serialized in Coordinated
 *                         Universal Time. ADDITIVE
 * @param aggregateId      the eleven-digit account identifier and the Kafka message key, from
 *                         {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}
 * @param transactionId    the sixteen-character transaction identifier, from
 *                         {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}
 * @param accountId        the same eleven-digit account identifier {@code aggregateId} carries,
 *                         from {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:L7}.
 *                         An absent value takes {@code aggregateId}
 * @param newBalance       the account balance after the posting, at scale two and ten integer
 *                         digits, from {@code ACCT-CURR-BAL PIC S9(10)V99} at
 *                         {@code app/cpy/CVACT01Y.cpy:L7} after the add at
 *                         {@code app/cbl/CBTRN02C.cbl:L547}
 * @param postedAt         the twenty-six-character posting timestamp, from
 *                         {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:L17}
 * @param amount           the amount that moved the balance, at scale two and nine integer digits,
 *                         from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}
 * @param maskedCardNumber the card number with twelve mask characters ahead of its last four
 *                         digits, at the width of {@code TRAN-CARD-NUM PIC X(16)} at
 *                         {@code app/cpy/CVTRA05Y.cpy:L15}. ADDITIVE
 */
public record TransactionPosted(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String aggregateId,
        String transactionId,
        String accountId,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal newBalance,
        String postedAt,
        @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
        String maskedCardNumber) {

    /**
     * The one value {@link #eventType()} accepts.
     *
     * <p>{@code schemas/transaction-posted-v1.json} pins the same text as the {@code const} of its
     * {@code eventType} property and as its {@code title}.
     */
    public static final String EVENT_TYPE = "TransactionPosted";

    /**
     * The scale both money fields carry: two fractional digits.
     *
     * <p>From the {@code V99} of {@code ACCT-CURR-BAL PIC S9(10)V99} at
     * {@code app/cpy/CVACT01Y.cpy:L7} and of {@code TRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA05Y.cpy:L10}.
     */
    public static final int MONEY_SCALE = 2;

    /**
     * The rounding mode every scale change in this record applies.
     *
     * <p>The {@code ROUNDED} phrase appears in no CardDemo program, so every monetary store in the
     * source truncates toward zero. A scale change here drops a third fractional digit and never
     * carries it upward.
     */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.DOWN;

    /** The width of {@link #transactionId()}, from {@code TRAN-ID PIC X(16)}. */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** The width of {@link #postedAt()}, from {@code TRAN-PROC-TS PIC X(26)}. */
    public static final int POSTED_AT_LENGTH = 26;

    /** The width of {@link #maskedCardNumber()}, from {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final int MASKED_CARD_NUMBER_LENGTH = 16;

    /**
     * The form {@link #newBalance()} takes: up to <strong>ten</strong> integer digits, then two
     * fractional digits, with an optional leading minus.
     *
     * <p>Ten from {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}. The same
     * pattern constrains {@code newBalance} in {@code schemas/transaction-posted-v1.json}. It is
     * not {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} and the two are not interchangeable.
     */
    public static final String TEN_INTEGER_DIGIT_BALANCE_PATTERN = "^-?\\d{1,10}\\.\\d{2}$";

    /**
     * The form {@link #amount()} takes: up to <strong>nine</strong> integer digits, then two
     * fractional digits, with an optional leading minus.
     *
     * <p>Nine from {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:L10}. The same
     * pattern constrains {@code amount} in {@code schemas/transaction-posted-v1.json}. It is not
     * {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} and the two are not interchangeable.
     */
    public static final String NINE_INTEGER_DIGIT_AMOUNT_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /**
     * The form {@link #postedAt()} takes: {@code YYYY-MM-DD-HH.MM.SS.NN0000}.
     *
     * <p>Three dashes, then three dots, then two fractional digits, then four literal zeros. The
     * separators come from {@code app/cbl/CBTRN02C.cbl:L702} and
     * {@code app/cbl/CBTRN02C.cbl:L703}, and the four zeros from
     * {@code app/cbl/CBTRN02C.cbl:L701}. The same pattern constrains {@code postedAt} in
     * {@code schemas/transaction-posted-v1.json}, which declares no {@code date-time} format
     * beside it.
     */
    public static final String POSTED_AT_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0000$";

    /**
     * The form {@link #maskedCardNumber()} takes: twelve mask characters, then four digits.
     *
     * <p>Sixteen characters in total, at the width of {@code TRAN-CARD-NUM PIC X(16)} at
     * {@code app/cpy/CVTRA05Y.cpy:L15}. The same pattern constrains {@code maskedCardNumber} in
     * {@code schemas/transaction-posted-v1.json}, so a full Primary Account Number fails the
     * check.
     */
    public static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * {@link #TEN_INTEGER_DIGIT_BALANCE_PATTERN} compiled, and the check {@code newBalance} runs.
     */
    private static final Pattern BALANCE_MATCHER =
            Pattern.compile(TEN_INTEGER_DIGIT_BALANCE_PATTERN);

    /** {@link #NINE_INTEGER_DIGIT_AMOUNT_PATTERN} compiled, and the check {@code amount} runs. */
    private static final Pattern AMOUNT_MATCHER =
            Pattern.compile(NINE_INTEGER_DIGIT_AMOUNT_PATTERN);

    /** {@link #POSTED_AT_PATTERN} compiled, and the check {@code postedAt} runs. */
    private static final Pattern POSTED_AT_MATCHER = Pattern.compile(POSTED_AT_PATTERN);

    /**
     * {@link #MASKED_CARD_NUMBER_PATTERN} compiled, and the check {@code maskedCardNumber} runs.
     */
    private static final Pattern MASKED_CARD_NUMBER_MATCHER =
            Pattern.compile(MASKED_CARD_NUMBER_PATTERN);

    /**
     * {@link EventEnvelope#AGGREGATE_ID_PATTERN} compiled, and the check both account identifiers
     * run.
     */
    private static final Pattern ACCOUNT_IDENTIFIER_MATCHER =
            Pattern.compile(EventEnvelope.AGGREGATE_ID_PATTERN);

    /**
     * Checks all eleven components and normalises the two money components to scale
     * {@link #MONEY_SCALE}.
     *
     * <p>Every failure message names the component that failed. The check rejects any value
     * {@code schemas/transaction-posted-v1.json} rejects, so a record that exists validates against
     * the contract. The two account identifiers must hold one value, which is the rule the schema
     * dialect cannot state. An absent {@code accountId} takes the value of {@code aggregateId},
     * which the contract document names as the one account identifier it declares.
     *
     * <p>A message reports the length of a rejected identifier or masked card number, never the
     * value. A failure therefore leaks no account identifier and no Primary Account Number into a
     * log. A rejected timestamp or money value does appear in the message, so the reader sees the
     * offending text.
     *
     * <p>Normalisation is the one change the constructor makes: each money component takes
     * {@link BigDecimal#setScale(int, RoundingMode)} with {@link #MONEY_ROUNDING} before its
     * pattern check. Every other component survives a serialize and deserialize round trip
     * unchanged.
     *
     * @throws NullPointerException     when {@code eventId}, {@code eventType} or
     *                                  {@code occurredAt} is {@code null}
     * @throws IllegalArgumentException when {@code eventType} is not {@link #EVENT_TYPE}, when
     *                                  {@code schemaVersion} is not
     *                                  {@link EventEnvelope#SCHEMA_VERSION}, when
     *                                  {@code aggregateId} is absent, when either account
     *                                  identifier is not eleven decimal digits, when the two
     *                                  account identifiers differ, when {@code transactionId} is
     *                                  not {@link #TRANSACTION_ID_LENGTH} characters, when either
     *                                  money component is absent or exceeds its integer width, or
     *                                  when {@code postedAt} or {@code maskedCardNumber} fails its
     *                                  pattern
     */
    public TransactionPosted {
        Objects.requireNonNull(eventId, "eventId must be present");
        Objects.requireNonNull(eventType, "eventType must be present");
        Objects.requireNonNull(occurredAt, "occurredAt must be present");

        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException("eventType must be \"" + EVENT_TYPE
                    + "\" and the supplied value is \"" + eventType + "\"");
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

        if (transactionId == null || transactionId.length() != TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must hold " + TRANSACTION_ID_LENGTH
                    + " characters and the supplied value " + describeLength(transactionId));
        }

        newBalance = normalisedMoney(newBalance, "newBalance", BALANCE_MATCHER,
                TEN_INTEGER_DIGIT_BALANCE_PATTERN);
        amount = normalisedMoney(amount, "amount", AMOUNT_MATCHER,
                NINE_INTEGER_DIGIT_AMOUNT_PATTERN);

        if (postedAt == null || postedAt.length() != POSTED_AT_LENGTH
                || !POSTED_AT_MATCHER.matcher(postedAt).matches()) {
            throw new IllegalArgumentException("postedAt must hold " + POSTED_AT_LENGTH
                    + " characters matching " + POSTED_AT_PATTERN + " and the supplied value is "
                    + (postedAt == null ? "null" : "\"" + postedAt + "\""));
        }

        if (maskedCardNumber == null || maskedCardNumber.length() != MASKED_CARD_NUMBER_LENGTH
                || !MASKED_CARD_NUMBER_MATCHER.matcher(maskedCardNumber).matches()) {
            throw new IllegalArgumentException("maskedCardNumber must hold "
                    + MASKED_CARD_NUMBER_LENGTH + " characters matching "
                    + MASKED_CARD_NUMBER_PATTERN + " and the supplied value "
                    + describeLength(maskedCardNumber));
        }
    }

    /**
     * Builds an event from an envelope a producer already holds.
     *
     * <p>{@link #accountId()} takes {@link EventEnvelope#aggregateId()}, so the two account
     * identifiers hold one value by construction. The envelope must carry {@link #EVENT_TYPE};
     * {@link EventEnvelope#of(String, String)} stamps it when the caller passes that constant.
     *
     * @param envelope         the envelope carrying the event identifier, the event type, the
     *                         contract version, the publish moment and the account identifier
     * @param transactionId    the sixteen-character transaction identifier
     * @param newBalance       the account balance after the posting, at up to ten integer digits
     * @param postedAt         the twenty-six-character posting timestamp
     * @param amount           the amount that moved the balance, at up to nine integer digits
     * @param maskedCardNumber the card number with twelve mask characters ahead of its last four
     *                         digits
     * @return the event, with both money components at scale {@link #MONEY_SCALE}
     * @throws NullPointerException     when {@code envelope} is {@code null}
     * @throws IllegalArgumentException when any component fails the canonical constructor
     */
    public static TransactionPosted of(EventEnvelope envelope, String transactionId,
            BigDecimal newBalance, String postedAt, BigDecimal amount, String maskedCardNumber) {
        Objects.requireNonNull(envelope, "envelope must be present");

        return new TransactionPosted(envelope.eventId(), envelope.eventType(),
                envelope.schemaVersion(), envelope.occurredAt(), envelope.aggregateId(),
                transactionId, envelope.aggregateId(), newBalance, postedAt, amount,
                maskedCardNumber);
    }

    /**
     * Builds an event for one account, stamping the envelope the ledger would otherwise assemble.
     *
     * <p>{@link EventEnvelope#of(String, String)} supplies a fresh event identifier,
     * {@link #EVENT_TYPE}, {@link EventEnvelope#SCHEMA_VERSION} and the current moment truncated to
     * milliseconds.
     *
     * @param accountId        the eleven-digit account identifier, which becomes both
     *                         {@link #aggregateId()} and {@link #accountId()}
     * @param transactionId    the sixteen-character transaction identifier
     * @param newBalance       the account balance after the posting, at up to ten integer digits
     * @param postedAt         the twenty-six-character posting timestamp
     * @param amount           the amount that moved the balance, at up to nine integer digits
     * @param maskedCardNumber the card number with twelve mask characters ahead of its last four
     *                         digits
     * @return the event, with both money components at scale {@link #MONEY_SCALE}
     * @throws IllegalArgumentException when any component fails the canonical constructor
     */
    public static TransactionPosted forAccount(String accountId, String transactionId,
            BigDecimal newBalance, String postedAt, BigDecimal amount, String maskedCardNumber) {
        return of(EventEnvelope.of(EVENT_TYPE, accountId), transactionId, newBalance, postedAt,
                amount, maskedCardNumber);
    }

    /**
     * The five envelope components, as the carrier a producer builds and a consumer routes on.
     *
     * <p>The returned envelope holds the values this record already carries, so the two cannot
     * disagree. Serialization ignores the method, and a serialized event carries no
     * {@code envelope} key.
     *
     * @return an envelope equal to the one
     *         {@link #of(EventEnvelope, String, BigDecimal, String, BigDecimal, String)} accepted
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
                    + describeLength(value));
        }
    }

    /**
     * Sets one money component to scale {@link #MONEY_SCALE} and checks the result against its own
     * pattern.
     *
     * <p>The scale change applies {@link #MONEY_ROUNDING}. The check runs on
     * {@link BigDecimal#toPlainString()}, which is the text the event carries at scale
     * {@link #MONEY_SCALE}.
     *
     * @param value     the amount to normalise
     * @param component the component name the failure message reports
     * @param matcher   the compiled pattern for that component
     * @param pattern   the pattern text the failure message reports
     * @return {@code value} at scale {@link #MONEY_SCALE}
     * @throws IllegalArgumentException when {@code value} is {@code null} or carries more integer
     *                                  digits than its component allows
     */
    private static BigDecimal normalisedMoney(BigDecimal value, String component, Pattern matcher,
            String pattern) {
        if (value == null) {
            throw new IllegalArgumentException(component + " must be present");
        }
        BigDecimal scaled = value.setScale(MONEY_SCALE, MONEY_ROUNDING);

        if (!matcher.matcher(scaled.toPlainString()).matches()) {
            throw new IllegalArgumentException(component + " must match " + pattern
                    + " and the supplied value holds " + scaled.toPlainString());
        }
        return scaled;
    }

    /**
     * Describes a rejected value by its length, keeping the value out of the message.
     *
     * @param value the rejected value, possibly {@code null}
     * @return {@code "is null"}, or the character count
     */
    private static String describeLength(String value) {
        return value == null ? "is null" : "holds " + value.length() + " characters";
    }
}

